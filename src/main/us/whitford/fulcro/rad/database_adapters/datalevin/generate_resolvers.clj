(ns us.whitford.fulcro.rad.database-adapters.datalevin.generate-resolvers
  "Resolver generation for Datalevin database adapter."
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [com.fulcrologic.guardrails.core :refer [>defn => ?]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.authorization :as auth]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]
   [edn-query-language.core :as eql]
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
;; Native ID Helpers
;; ================================================================================

(defn native-id?
  "Returns true if the given attribute uses native :db/id."
  [attribute]
  (boolean (dlo/native-id? attribute)))

(defn pathom-query->datalevin-query
  "Convert a Pathom EQL query to a Datalevin pull pattern.
   
   Replaces any native-id identity attribute keys with :db/id in the pull pattern,
   since native-id attributes use Datalevin's built-in entity ID."
  [all-attributes pathom-query]
  (let [native-id-attr? #(and (native-id? %) (::attr/identity? %))
        native-id-keys (into #{}
                             (comp
                              (filter native-id-attr?)
                              (map ::attr/qualified-key))
                             all-attributes)]
    (walk/prewalk
     (fn [e]
       (if (and (keyword? e) (contains? native-id-keys e))
         :db/id
         e))
     pathom-query)))

(defn- fix-id-keys
  "Fix the ID keys recursively on result.
   
   Replaces :db/id with the proper identity attribute key when using native IDs.
   The AST should be from the PATHOM query (with the original identity key like :person/id),
   not the Datalevin query (which has :db/id)."
  [key->attribute ast-nodes result]
  (let [;; Find the identity attribute from AST nodes
        id? (fn [{:keys [dispatch-key]}]
              (some-> dispatch-key key->attribute ::attr/identity?))
        id-node (first (filter id? ast-nodes))
        id-key (:key id-node)
        native-id? (some-> id-key key->attribute dlo/native-id?)
        join-key->children (into {}
                                 (comp
                                  (filter #(= :join (:type %)))
                                  (map (fn [{:keys [key children]}] [key children])))
                                 ast-nodes)
        join-keys (set (keys join-key->children))
        join-key? #(contains? join-keys %)]
    (reduce-kv
     (fn [m k v]
       (cond
         ;; Replace :db/id with the identity attribute key for native IDs
         (and native-id? (= :db/id k)) (assoc m id-key v)
         ;; Recursively fix nested vectors (to-many joins)
         (and (join-key? k) (vector? v)) (assoc m k (mapv #(fix-id-keys key->attribute (join-key->children k) %) v))
         ;; Recursively fix nested maps (to-one joins)
         (and (join-key? k) (map? v)) (assoc m k (fix-id-keys key->attribute (join-key->children k) v))
         ;; Pass through everything else
         :else (assoc m k v)))
     {}
     result)))

(>defn datalevin-result->pathom-result
  "Convert a Datalevin result containing :db/id into a Pathom result containing
   the proper id keyword that was used in the original query."
  [key->attribute pathom-query result]
  [(s/map-of keyword? ::attr/attribute) ::eql/query (? coll?) => (? coll?)]
  (when result
    (let [{:keys [children]} (eql/query->ast pathom-query)]
      (if (vector? result)
        (mapv #(fix-id-keys key->attribute children %) result)
        (fix-id-keys key->attribute children result)))))

;; ================================================================================
;; Entity Operations
;; ================================================================================

(defn ref-entity->ident
  "Convert an entity reference to its :db/ident keyword value.
   
   If the entity has a :db/ident, returns that keyword.
   Otherwise returns the entity unchanged (not an enum)."
  [db {:db/keys [ident id] :as ent}]
  (cond
    ;; Already has :db/ident in the map, return it
    ident ident
    ;; Has :db/id, pull its :db/ident
    id (if-let [pulled (util/pull db [:db/ident] id)]
         (:db/ident pulled ent)
         ent)
    ;; Neither :db/ident nor :db/id, return as-is
    :else ent))

(defn replace-ref-types
  "Walk through query results and replace enum entity references with their :db/ident keywords.
   
   Arguments:
   - db: database value  
   - enum-keys: set of attribute keywords that are enums
   - result: the pull result to transform
   
   Returns the result with enum entity maps replaced by their :db/ident keyword values."
  [db enum-keys result]
  (walk/postwalk
   (fn [arg]
     (cond
       ;; If it's a map with enum keys, replace ref entities with idents
       (and (map? arg) (some #(contains? enum-keys %) (keys arg)))
       (reduce
        (fn [acc enum-k]
          (cond
            ;; Single enum value (to-one cardinality)
            (and (get acc enum-k) (not (vector? (get acc enum-k))))
            (update acc enum-k (partial ref-entity->ident db))
            
            ;; Multiple enum values (to-many cardinality)
            (and (get acc enum-k) (vector? (get acc enum-k)))
            (update acc enum-k #(mapv (partial ref-entity->ident db) %))
            
            :else acc))
        arg
        enum-keys)
       :else arg))
   result))

(defn get-by-ids
  "Fetch multiple entities by their identity attribute values.

   Arguments:
   - db: database value
   - id-attr: the identity attribute keyword
   - ids: collection of id values (max *max-batch-size*). For native IDs, these are entity IDs directly.
   - pull-pattern: EQL pull pattern
   - enum-keys: (optional) set of attribute keywords that are enums, to be converted to :db/ident
   - native-id?: (optional) if true, ids are raw entity IDs (not lookup refs)

   Returns a map of id -> entity data.

   Throws if ids count exceeds *max-batch-size*.
   Logs warning for large batches (> 100 ids)."
  ([db id-attr ids pull-pattern]
   (get-by-ids db id-attr ids pull-pattern #{} false))
  ([db id-attr ids pull-pattern enum-keys]
   (get-by-ids db id-attr ids pull-pattern enum-keys false))
  ([db id-attr ids pull-pattern enum-keys native-id?]
   (let [id-count (count ids)]
     (when (> id-count *max-batch-size*)
       (throw (ex-info "Batch size exceeds maximum"
                       {:requested id-count
                        :maximum *max-batch-size*
                        :id-attr id-attr})))
     (when (> id-count 100)
       (log/warn "Large batch query:" id-count "entities for" id-attr))
     (if native-id?
       ;; Native ID: ids are raw entity IDs, pull directly
       (let [entities (into {}
                            (map (fn [eid]
                                   [eid (util/pull db pull-pattern eid)]))
                            ids)]
         (if (seq enum-keys)
           (into {} (map (fn [[id ent]] [id (replace-ref-types db enum-keys ent)])) entities)
           entities))
       ;; Non-native ID: query to find entity IDs from attribute values
       (let [result (util/q '[:find ?e ?id
                              :in $ ?id-attr [?id ...]
                              :where [?e ?id-attr ?id]]
                            db id-attr ids)
             entities (into {}
                            (map (fn [[eid id]]
                                   [id (util/pull db pull-pattern eid)]))
                            result)]
         (if (seq enum-keys)
           (into {} (map (fn [[id ent]] [id (replace-ref-types db enum-keys ent)])) entities)
           entities))))))

;; ================================================================================
;; Resolver Generation
;; ================================================================================

(>defn id-resolver
  "Generates a resolver from `id-attribute` to the `output-attributes`.
   
   Handles:
   - Native ID attributes (using :db/id directly)
   - Enum attribute conversion (db refs -> keywords)
   - Optional wrap-resolve for custom pre/post processing"
  [all-attributes
   {::attr/keys [qualified-key] :keys [::attr/schema] :as id-attribute}
   output-attributes]
  [::attr/attributes ::attr/attribute ::attr/attributes => some?]
  (log/info "Building ID resolver for" qualified-key)
  (enc/if-let [_       id-attribute
               outputs (attr/attributes->eql output-attributes)]
    (let [is-native-id?  (native-id? id-attribute)
          wrap-resolve   (dlo/wrap-resolve id-attribute)
          ;; The pathom pattern uses the original identity key (e.g., :person/id)
          ;; This is what we return to the client and use for result transformation
          pathom-pattern (vec (cons qualified-key outputs))
          ;; For native IDs, the datalevin pull uses :db/id; otherwise same as pathom pattern
          datalevin-pull (if is-native-id?
                           (vec (cons :db/id outputs))
                           pathom-pattern)
          ;; Convert any other native-id refs in the pull pattern to :db/id
          datalevin-pull (pathom-query->datalevin-query all-attributes datalevin-pull)
          ;; Collect enum attribute keys from output attributes
          enum-keys      (into #{}
                               (comp
                                (filter #(= :enum (::attr/type %)))
                                (map ::attr/qualified-key))
                               output-attributes)
          resolve-sym    (symbol (str (namespace qualified-key) "." (name qualified-key) "-resolver"))
          core-resolver  (fn [{::dlo/keys [databases] ::attr/keys [key->attribute] :as env} inputs]
                           (let [db     (get databases schema)
                                 ids    (mapv #(get % qualified-key) inputs)
                                 data   (get-by-ids db qualified-key ids datalevin-pull enum-keys is-native-id?)
                                 ;; Convert :db/id back to qualified key for native IDs
                                 result (mapv #(get data (get % qualified-key) {}) inputs)
                                 ;; Fix up ID keys in result for native IDs
                                 ;; Use pathom-pattern (with :person/id) for AST parsing, not datalevin-pull (with :db/id)
                                 fixed  (if is-native-id?
                                          (datalevin-result->pathom-result key->attribute pathom-pattern result)
                                          result)]
                             (auth/redact env fixed)))
          final-resolver (if wrap-resolve
                           (wrap-resolve core-resolver)
                           core-resolver)]
      (log/debug "Computed output is" outputs)
      (log/debug "Datalevin pull pattern is" datalevin-pull)
      (pco/resolver
       resolve-sym
       {::pco/input  [qualified-key]
        ::pco/output outputs
        ::pco/batch? true}
       final-resolver))
    (do
      (log/error "Unable to generate id-resolver. "
                 "Attribute was missing schema, or could not be found: " qualified-key)
      nil)))

(defn all-ids-resolver
  "Generates a resolver that returns all IDs for a given entity type.

   Arguments:
   - id-attribute: The identity attribute (e.g., :account/id)

   Returns a resolver that outputs all entity IDs for queries like :account/all.

   For native-id attributes, queries :db/id and maps back to the qualified key.

   Example:
   - For :account/id, creates a resolver for :account/all
   - Query [:account/all] returns [{:account/id uuid-1} {:account/id uuid-2} ...]"
  [{::attr/keys [qualified-key] :keys [::attr/schema] :as id-attribute}]
  (let [entity-ns     (namespace qualified-key)
        all-ids-key   (keyword entity-ns "all")
        is-native-id? (native-id? id-attribute)]
    (log/info "Building all-ids resolver for" qualified-key "->" all-ids-key
              (when is-native-id? "(native-id)"))
    (pco/resolver
     (symbol (str entity-ns "-all-resolver"))
     {::pco/output [{all-ids-key [qualified-key]}]}
     (fn [{::dlo/keys [databases] :as env} _input]
       (let [db (get databases schema)]
         (if is-native-id?
           ;; For native IDs, we need to find all entities that have any attribute
           ;; from this entity type. Use :db/id directly.
           ;; This is a limitation - we can't easily enumerate all entities without
           ;; knowing at least one attribute they have.
           (let [result (util/q '[:find ?e
                                  :where [?e _ _]]
                                db)
                 ;; Note: This gets ALL entities which may not be what we want
                 ;; In practice, native-id entities should have at least one identifying attribute
                 ids (mapv (fn [[eid]] {qualified-key eid}) result)]
             {all-ids-key ids})
           ;; For non-native IDs, query the ID attribute directly
           (let [result (util/q '[:find ?id
                                  :in $ ?id-attr
                                  :where [?e ?id-attr ?id]]
                                db qualified-key)
                 ids (mapv (fn [[id]] {qualified-key id}) result)]
             {all-ids-key ids})))))))

(>defn generate-resolvers
  "Generate all of the resolvers that make sense for the given database config. This should be passed
  to your Pathom parser to register resolvers for each of your schemas.

   Generates two types of resolvers:
   1. ID resolvers: resolve entity data by ID (e.g., :account/id -> account data)
   2. All-IDs resolvers: resolve all entity IDs (e.g., :all-accounts -> [{:account/id ...} ...])"
  [attributes schema]
  [::attr/attributes keyword? => sequential?]
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
