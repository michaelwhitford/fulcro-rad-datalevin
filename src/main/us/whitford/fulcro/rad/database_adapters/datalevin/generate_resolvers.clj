(ns us.whitford.fulcro.rad.database-adapters.datalevin.generate-resolvers
  "Resolver generation for Datalevin database adapter."
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.authorization :as auth]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

;; ================================================================================
;; Configuration and Limits
;; ================================================================================

(def ^:dynamic *max-batch-size*
  "Maximum number of entities to fetch in a single batch query."
  1000)

;; ================================================================================
;; Entity Operations
;; ================================================================================

(defn get-by-ids
  "Fetch multiple entities by their identity attribute values.

   Arguments:
   - db: database value
   - id-attr: the identity attribute keyword
   - ids: collection of id values (max *max-batch-size*)
   - pull-pattern: EQL pull pattern

   Returns a map of id -> entity data.

   Throws if ids count exceeds *max-batch-size*.
   Logs warning for large batches (> 100 ids)."
  [db id-attr ids pull-pattern]
  (let [id-count (count ids)]
    (when (> id-count *max-batch-size*)
      (throw (ex-info "Batch size exceeds maximum"
                      {:requested id-count
                       :maximum *max-batch-size*
                       :id-attr id-attr})))
    (when (> id-count 100)
      (log/warn "Large batch query:" id-count "entities for" id-attr))
    (let [result (d/q '[:find ?e ?id
                        :in $ ?id-attr [?id ...]
                        :where [?e ?id-attr ?id]]
                      db id-attr ids)]
      (into {}
            (map (fn [[eid id]]
                   [id (d/pull db pull-pattern eid)]))
            result))))

;; ================================================================================
;; Resolver Generation
;; ================================================================================

(defn id-resolver
  "Generates a resolver from `id-attribute` to the `output-attributes`."
  [all-attributes
   {::attr/keys [qualified-key] :keys [::attr/schema] :as id-attribute}
   output-attributes]
  (log/info "Building ID resolver for" qualified-key)
  (let [outputs (attr/attributes->eql output-attributes)]
    (pco/resolver
     (symbol (str (namespace qualified-key) "." (name qualified-key) "-resolver"))
     {::pco/input   [qualified-key]
      ::pco/output  outputs
      ::pco/batch?  true}
     (fn [{::dlo/keys [databases] ::attr/keys [key->attribute] :as env} inputs]
       (let [db     (get databases schema)
             ids    (mapv #(get % qualified-key) inputs)
             data   (get-by-ids db qualified-key ids outputs)
             result (mapv #(get data (get % qualified-key) {}) inputs)]
         (auth/redact env result))))))

(defn generate-resolvers
  "Generate all of the resolvers that make sense for the given database config. This should be passed
  to your Pathom parser to register resolvers for each of your schemas."
  [attributes schema]
  (let [attributes    (filter #(= schema (::attr/schema %)) attributes)
        key->attribute (attr/attribute-map attributes)
        entity-id->attributes (group-by ::k (mapcat (fn [attribute]
                                                      (map
                                                       (fn [id-key] (assoc attribute ::k id-key))
                                                       (get attribute ::attr/identities)))
                                                    attributes))
        entity-resolvers (reduce-kv
                          (fn [result k v]
                            (enc/if-let [attr     (key->attribute k)
                                         resolver (id-resolver attributes attr v)]
                              (conj result resolver)
                              (do
                                (log/error "Internal error generating resolver for ID key" k)
                                result)))
                          []
                          entity-id->attributes)]
    entity-resolvers))
