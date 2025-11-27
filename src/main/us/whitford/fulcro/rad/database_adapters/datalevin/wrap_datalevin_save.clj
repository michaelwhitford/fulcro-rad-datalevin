(ns us.whitford.fulcro.rad.database-adapters.datalevin.wrap-datalevin-save
  "Save form middleware for Datalevin database adapter."
  (:require
   [clojure.pprint :refer [pprint]]
   [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.guardrails.core :refer [>defn =>]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [datalevin.core :as d]
   [edn-query-language.core :as eql]
   [taoensso.timbre :as log]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.datalevin.utilities :as util]))

(def ^:private tempid-counter
  "Atomic counter for generating unique negative transaction IDs."
  (atom -1000000))

(def ^:private ^:dynamic *tempid-mappings*
  "Dynamic var to track tempid -> txid mappings within a transaction context."
  nil)

(defn- tempid->txid
  "Convert Fulcro tempid to unique negative integer for Datalevin transaction."
  [id]
  (if (tempid/tempid? id)
    (if *tempid-mappings*
      (if-let [existing (get @*tempid-mappings* id)]
        existing
        (let [new-id (swap! tempid-counter dec)]
          (swap! *tempid-mappings* assoc id new-id)
          new-id))
      (swap! tempid-counter dec))
    id))

(defn- normalize-ident-id
  "Normalize an ident ID value, extracting the actual ID from various formats."
  [id]
  (cond
    (tempid/tempid? id) id
    (and (map? id) (contains? id :id)) (:id id)
    :else id))

(defn- native-ident?
  "Returns true if the given ident is using a database native ID (:db/id)"
  [{::attr/keys [key->attribute]} ident]
  (boolean (some-> ident first key->attribute dlo/native-id?)))

(defn- ident->lookup-ref
  "Convert Fulcro ident [attr id] to Datalevin lookup ref.
   For native IDs, returns the raw ID directly."
  [env [attr id]]
  (let [normalized-id (normalize-ident-id id)]
    (cond
      (tempid/tempid? normalized-id) (tempid->txid normalized-id)
      (native-ident? env [attr normalized-id]) normalized-id
      :else [attr normalized-id])))

(defn- delta-entry->txn
  "Convert a single delta entry to Datalevin transaction data.
   
   For native-id attributes:
   - New entities get a tempid that Datalevin will replace with :db/id
   - Existing entities use the raw :db/id directly
   - The identity attribute is NOT added to the entity map (it's built-in)"
  [env key-attr id delta]
  (let [normalized-id (normalize-ident-id id)
        is-native-id? (native-ident? env [key-attr normalized-id])
        identity-attr-is-new? (and (contains? delta key-attr)
                                   (nil? (get-in delta [key-attr :before])))
        all-changes-from-nil? (every? (fn [[_ {:keys [before]}]] (nil? before)) delta)
        id-was-wrapped? (and (map? id) (contains? id :id))
        is-new-entity? (or (tempid/tempid? normalized-id)
                           identity-attr-is-new?
                           (and all-changes-from-nil? id-was-wrapped?))
        entity-id (cond
                    ;; Tempid: generate a negative txid
                    (tempid/tempid? normalized-id) (tempid->txid normalized-id)
                    ;; New entity: generate a negative txid
                    is-new-entity? (swap! tempid-counter dec)
                    ;; Native ID existing entity: use raw ID
                    is-native-id? normalized-id
                    ;; Non-native existing entity: use lookup ref
                    :else [key-attr normalized-id])
        raw-id-value (if (contains? delta key-attr)
                       (get-in delta [key-attr :after])
                       normalized-id)
        real-id-value (if (tempid/tempid? raw-id-value)
                        (:id raw-id-value)
                        raw-id-value)
        ;; For native IDs, don't include the identity attribute in the entity map
        ;; (it's the built-in :db/id)
        base-entity (cond
                      ;; Native ID - only include :db/id
                      is-native-id?
                      {:db/id entity-id}
                      ;; Non-native new entity - include identity attr
                      is-new-entity?
                      {:db/id entity-id key-attr real-id-value}
                      ;; Non-native existing entity
                      :else
                      {:db/id entity-id})
        {updates false retractions true}
        (group-by (fn [[attr {:keys [before after]}]]
                    (and (some? before) (nil? after)))
                  delta)
        entity-map (reduce-kv
                    (fn [txn-data attr {:keys [before after]}]
                      ;; Skip the identity attribute for native IDs (it's :db/id)
                      (if (and (= attr key-attr) is-native-id?)
                        txn-data
                        (if (and (some? after) (not= before after))
                          (let [value (cond
                                        (tempid/tempid? after) (:id after)
                                        (eql/ident? after) (ident->lookup-ref env after)
                                        :else after)]
                            (assoc txn-data attr value))
                          txn-data)))
                    base-entity
                    (into {} updates))
        retract-ops (mapv (fn [[attr {:keys [before]}]]
                            [:db/retract entity-id attr before])
                          retractions)
        result (cond-> []
                 (> (count entity-map) 1)
                 (conj entity-map)
                 (seq retract-ops)
                 (into retract-ops))]
    result))

(>defn delta->txn
  "Convert RAD form delta to Datalevin transaction data.
   
   Arguments:
   - env: environment map with ::attr/key->attribute
   - delta: RAD form delta map
   
   Returns vector of Datalevin transaction operations."
  [env delta]
  [map? map? => vector?]
  (reduce-kv
   (fn [txns [key-attr id :as ident] entity-delta]
     (let [txn-ops (delta-entry->txn env key-attr id entity-delta)]
       (into txns txn-ops)))
   []
   delta))

(defn tempid->result-id
  "Extract the mapping from tempids to real ids from transaction result."
  [tx-result delta]
  (let [tempid-entries (filter (fn [[k _]]
                                 (tempid/tempid? (second k)))
                               delta)]
    (into {}
          (map (fn [[[id-attr tempid] _]]
                 (let [tx-id (tempid->txid tempid)
                       eid   (get-in tx-result [:tempids tx-id])
                       db    (:db-after tx-result)]
                   (when (and eid db)
                     (let [real-id (id-attr (util/pull db [id-attr] eid))]
                       [tempid real-id])))))
          tempid-entries)))

(defn keys-in-delta
  "Extract all keys from delta"
  [delta]
  (let [id-keys (into #{}
                      (map first)
                      (keys delta))
        all-keys (into id-keys
                       (mapcat keys)
                       (vals delta))]
    all-keys))

(defn schemas-for-delta
  "Extract all schemas referenced in delta"
  [{::attr/keys [key->attribute]} delta]
  (let [all-keys (keys-in-delta delta)
        schemas (into #{}
                      (keep #(-> % key->attribute ::attr/schema))
                      all-keys)]
    schemas))

(defn save-form!
  "Do all of the possible datalevin operations for the given form delta (save to all datalevin databases involved)"
  [env {::form/keys [delta]}]
  (let [schemas (schemas-for-delta env delta)
        result  (atom {:tempids {}})]
    (log/debug "Saving form across " schemas)
    (doseq [schema schemas
            :let [conn (-> env dlo/connections (get schema))]]
      (log/debug "Saving form delta" (with-out-str (pprint delta)))
      (log/debug "on schema" schema)
      (if conn
        (binding [*tempid-mappings* (atom {})]
          (let [{::attr/keys [key->attribute]} env
                schema-delta  (into {}
                                    (filter (fn [[[k _] _]]
                                              (let [attr (get key->attribute k)]
                                                (or (nil? attr)
                                                    (= schema (::attr/schema attr))
                                                    (nil? (::attr/schema attr))))))
                                    delta)
                txn-data      (delta->txn env schema-delta)]
            (log/debug "Running txn\n" (with-out-str (pprint txn-data)))
            (when (seq txn-data)
              (try
                (let [tx-result (d/transact! conn txn-data)
                      tempid-map (tempid->result-id tx-result schema-delta)]
                  (swap! result update :tempids merge tempid-map))
                (catch Exception e
                  (log/error e "Transaction failed!")
                  {})))))
        (log/error "Unable to save form. Connection was missing in env.")))
    @result))

(defn wrap-datalevin-save
  "Form save middleware to accomplish datalevin saves."
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result (save-form! pathom-env params)]
       (tap> {:from ::wrap-datalevin-save :pathom-env pathom-env :save-results save-result})
       (merge {:tempids {}} save-result))))
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result    (save-form! pathom-env params)
           handler-result (handler pathom-env)]
       (tap> {:from ::wrap-datalevin-save :pathom-env pathom-env :save-results save-result :handler-result handler-result})
       (deep-merge {:tempids {}} save-result handler-result)))))
