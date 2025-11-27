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
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.datalevin.utilities :as util]))

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
    (let [result (util/q '[:find ?e ?id
                           :in $ ?id-attr [?id ...]
                           :where [?e ?id-attr ?id]]
                         db id-attr ids)]
      (into {}
            (map (fn [[eid id]]
                   [id (util/pull db pull-pattern eid)]))
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

(defn all-ids-resolver
  "Generates a resolver that returns all IDs for a given entity type.

   Arguments:
   - id-attribute: The identity attribute (e.g., :account/id)

   Returns a resolver that outputs all entity IDs for queries like :account/all.

   Example:
   - For :account/id, creates a resolver for :account/all
   - Query [:account/all] returns [{:account/id uuid-1} {:account/id uuid-2} ...]"
  [{::attr/keys [qualified-key] :keys [::attr/schema] :as id-attribute}]
  (let [entity-ns   (namespace qualified-key)
        all-ids-key (keyword entity-ns "all")]
    (log/info "Building all-ids resolver for" qualified-key "->" all-ids-key)
    (pco/resolver
     (symbol (str entity-ns "-all-resolver"))
     {::pco/output [{all-ids-key [qualified-key]}]}
     (fn [{::dlo/keys [databases] :as env} _input]
       (let [db     (get databases schema)
             result (util/q '[:find ?id
                              :in $ ?id-attr
                              :where [?e ?id-attr ?id]]
                            db qualified-key)
             ids    (mapv (fn [[id]] {qualified-key id}) result)]
         {all-ids-key ids})))))

(defn generate-resolvers
  "Generate all of the resolvers that make sense for the given database config. This should be passed
  to your Pathom parser to register resolvers for each of your schemas.

   Generates two types of resolvers:
   1. ID resolvers: resolve entity data by ID (e.g., :account/id -> account data)
   2. All-IDs resolvers: resolve all entity IDs (e.g., :all-accounts -> [{:account/id ...} ...])"
  [attributes schema]
  (let [attributes    (filter #(= schema (::attr/schema %)) attributes)
        key->attribute (attr/attribute-map attributes)
        ;; Find all identity attributes for this schema
        identity-attributes (filter ::attr/identity? attributes)
        ;; Group non-identity attributes by their identity keys
        entity-id->attributes (group-by ::k (mapcat (fn [attribute]
                                                      (map
                                                       (fn [id-key] (assoc attribute ::k id-key))
                                                       (get attribute ::attr/identities)))
                                                    attributes))
        ;; Generate ID resolvers (entity by ID)
        entity-resolvers (reduce-kv
                          (fn [result k v]
                            (enc/if-let [attr     (key->attribute k)
                                         resolver (id-resolver attributes attr v)]
                              (conj result resolver)
                              (do
                                (log/error "Internal error generating resolver for ID key" k)
                                result)))
                          []
                          entity-id->attributes)
        ;; Generate all-IDs resolvers (all entities of a type)
        all-ids-resolvers (mapv all-ids-resolver identity-attributes)]
    (concat entity-resolvers all-ids-resolvers)))
