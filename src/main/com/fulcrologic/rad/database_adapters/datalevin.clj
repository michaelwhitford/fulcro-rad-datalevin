(ns com.fulcrologic.rad.database-adapters.datalevin
  "Datalevin database adapter for Fulcro RAD. Provides automatic schema generation,
   resolver generation, and save/delete middleware for RAD forms."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database-adapters.datalevin-options :as dlo]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [datalevin.core :as d]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

;; ================================================================================
;; Type Mapping
;; ================================================================================

(def type-map
  "Map from RAD attribute types to Datalevin value types"
  {:string   :db.type/string
   :password :db.type/string
   :boolean  :db.type/boolean
   :int      :db.type/long
   :long     :db.type/long
   :double   :db.type/double
   :float    :db.type/float
   :bigdec   :db.type/bigdec
   :instant  :db.type/instant
   :keyword  :db.type/keyword
   :symbol   :db.type/symbol
   :uuid     :db.type/uuid
   :ref      :db.type/ref
   :tuple    :db.type/tuple})

;; ================================================================================
;; Schema Generation
;; ================================================================================

(defn- attr->schema
  "Convert a single RAD attribute to Datalevin schema entry.
   Returns a map entry [attr-key schema-map]."
  [{::attr/keys [type qualified-key cardinality identity?]
    ::dlo/keys  [attribute-schema]
    :as         attribute}]
  (when type
    (let [datalevin-type (get type-map type)
          base-schema    (cond-> {}
                           datalevin-type
                           (assoc :db/valueType datalevin-type)

                           (= :many cardinality)
                           (assoc :db/cardinality :db.cardinality/many)

                           identity?
                           (assoc :db/unique :db.unique/identity)

                           (= :ref type)
                           (assoc :db/valueType :db.type/ref))]
      (when (seq base-schema)
        [qualified-key (merge base-schema attribute-schema)]))))

(defn automatic-schema
  "Generate a Datalevin schema map from RAD attributes.

   Arguments:
   - schema-name: keyword identifying the schema (e.g., :production, :main)
   - attributes: collection of RAD attribute maps

   Returns a map suitable for passing to datalevin's get-conn."
  [schema-name attributes]
  (let [relevant-attrs (filter #(= schema-name (::attr/schema %)) attributes)]
    (into {}
          (comp
            (map attr->schema)
            (filter some?))
          relevant-attrs)))

(defn ensure-schema!
  "Ensure the database has the correct schema.
   If the connection already exists, attempts to update the schema.

   Arguments:
   - conn: existing Datalevin connection
   - schema: map of attribute schemas"
  [conn schema]
  (when (seq schema)
    (try
      (d/update-schema conn schema)
      (catch Exception e
        (log/error e "Failed to update schema. This may be expected if schema already exists.")))))

;; ================================================================================
;; Connection Management
;; ================================================================================

(defn start-database!
  "Start a Datalevin database connection.

   Arguments:
   - config: map containing:
     - :path - directory path for database storage
     - :schema - RAD schema name (keyword)
     - :attributes - collection of RAD attributes
     - :auto-schema? - if true, automatically create schema from attributes (default true)

   Returns a Datalevin connection."
  [{:keys [path schema attributes auto-schema?]
    :or   {auto-schema? true}}]
  (let [datalevin-schema (when auto-schema?
                           (automatic-schema schema attributes))
        conn             (if (seq datalevin-schema)
                           (d/get-conn path datalevin-schema)
                           (d/get-conn path))]
    (log/info "Started Datalevin database at" path "for schema" schema)
    conn))

(defn stop-database!
  "Close a Datalevin database connection."
  [conn]
  (when conn
    (d/close conn)
    (log/info "Closed Datalevin database connection")))

;; ================================================================================
;; Entity Operations
;; ================================================================================

(defn entity-query
  "Build a Datalog query to find entities by a single attribute value.
   Returns the query and inputs."
  [id-attr]
  {:query '[:find ?e
            :in $ ?v
            :where [?e id-attr ?v]]
   :args  '[id-attr]})

(defn get-by-ids
  "Fetch multiple entities by their identity attribute values.

   Arguments:
   - db: database value
   - id-attr: the identity attribute keyword
   - ids: collection of id values
   - pull-pattern: EQL pull pattern

   Returns a map of id -> entity data."
  [db id-attr ids pull-pattern]
  (let [result (d/q '[:find ?e ?id
                      :in $ ?id-attr [?id ...]
                      :where [?e ?id-attr ?id]]
                    db id-attr ids)]
    (into {}
          (map (fn [[eid id]]
                 [id (d/pull db pull-pattern eid)]))
          result)))

;; ================================================================================
;; Delta Processing for Save Middleware
;; ================================================================================

(defn- tempid->txid
  "Convert Fulcro tempid to negative integer for Datalevin transaction."
  [id]
  (if (tempid/tempid? id)
    (- (hash id))
    id))

(defn- ident->lookup-ref
  "Convert Fulcro ident [attr id] to Datalevin lookup ref."
  [[attr id]]
  (if (tempid/tempid? id)
    (tempid->txid id)
    [attr id]))

(defn- delta-entry->txn
  "Convert a single delta entry to Datalevin transaction data.

   Arguments:
   - key-attr: the identity attribute from the ident
   - id: the entity id
   - delta: map of attribute changes {:attr {:before X :after Y}}

   Returns transaction data for this entity."
  [key-attr id delta]
  (let [entity-id (if (tempid/tempid? id)
                    (tempid->txid id)
                    {key-attr id})]
    (reduce-kv
      (fn [txn-data attr {:keys [before after]}]
        (cond
          ;; Setting a new value or updating
          (and (some? after) (not= before after))
          (let [value (if (eql/ident? after)
                        (ident->lookup-ref after)
                        after)]
            (assoc txn-data attr value))

          ;; Removing a value
          (and (some? before) (nil? after))
          (assoc txn-data attr nil)

          :else
          txn-data))
      (if (tempid/tempid? id)
        {:db/id entity-id key-attr id}
        {:db/id entity-id})
      delta)))

(defn delta->txn
  "Convert RAD form delta to Datalevin transaction data.

   Delta format: {[id-attr id] {attr {:before v :after v'} ...} ...}

   Returns a vector of transaction maps."
  [delta]
  (reduce-kv
    (fn [txns [key-attr id :as ident] entity-delta]
      (let [txn-entry (delta-entry->txn key-attr id entity-delta)]
        (if (> (count txn-entry) 1) ;; Has more than just :db/id
          (conj txns txn-entry)
          txns)))
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
                     (let [real-id (id-attr (d/pull db [id-attr] eid))]
                       [tempid real-id])))))
          tempid-entries)))

;; ================================================================================
;; Save Middleware
;; ================================================================================

(defn wrap-datalevin-save
  "Middleware that processes RAD form save operations for Datalevin.

   Expects the pathom env to contain:
   - ::dlo/connections - map of schema name to Datalevin connection

   The middleware receives a delta (diff) and transacts it to the database."
  ([]
   (wrap-datalevin-save {}))
  ([{:keys [default-schema]
     :or   {default-schema :main}}]
   (fn [handler]
     (fn [{::attr/keys [key->attribute]
           ::dlo/keys  [connections]
           :as         env}]
       (let [result  (handler env)
             delta   (::form/delta env)
             schemas (into #{}
                           (map (fn [[[k _] _]]
                                  (or (::attr/schema (get key->attribute k))
                                      default-schema)))
                           delta)]
         (if (seq delta)
           (reduce
             (fn [acc schema]
               (if-let [conn (get connections schema)]
                 (let [schema-delta  (into {}
                                           (filter (fn [[[k _] _]]
                                                     (let [attr (get key->attribute k)]
                                                       (or (nil? attr)
                                                           (= schema (::attr/schema attr))
                                                           (nil? (::attr/schema attr))))))
                                           delta)
                       txn-data      (delta->txn schema-delta)
                       _             (log/debug "Transacting to" schema ":" txn-data)
                       tx-result     (when (seq txn-data)
                                       (d/transact! conn txn-data))
                       tempid-map    (when tx-result
                                       (tempid->result-id tx-result schema-delta))]
                   (cond-> acc
                     (seq tempid-map)
                     (update :tempids merge tempid-map)))
                 (do
                   (log/error "No connection for schema" schema)
                   acc)))
             result
             schemas)
           result))))))

;; ================================================================================
;; Delete Middleware
;; ================================================================================

(defn wrap-datalevin-delete
  "Middleware that processes RAD form delete operations for Datalevin.

   Expects the pathom env to contain:
   - ::dlo/connections - map of schema name to Datalevin connection

   The middleware retracts entities by their identity attribute."
  ([]
   (wrap-datalevin-delete {}))
  ([{:keys [default-schema]
     :or   {default-schema :main}}]
   (fn [handler]
     (fn [{::attr/keys [key->attribute]
           ::dlo/keys  [connections]
           ::form/keys [delete-params]
           :as         env}]
       (let [result (handler env)]
         (if (seq delete-params)
           (let [ident   (first delete-params)
                 id-attr (first ident)
                 id      (second ident)
                 schema  (or (::attr/schema (get key->attribute id-attr))
                             default-schema)]
             (if-let [conn (get connections schema)]
               (let [db     (d/db conn)
                     eid    (ffirst (d/q '[:find ?e
                                           :in $ ?attr ?id
                                           :where [?e ?attr ?id]]
                                         db id-attr id))
                     _      (log/debug "Deleting entity" eid "from schema" schema)
                     _      (when eid
                              (d/transact! conn [[:db/retractEntity eid]]))]
                 result)
               (do
                 (log/error "No connection for schema" schema)
                 result)))
           result))))))

;; ================================================================================
;; Resolver Generation
;; ================================================================================

(defn id-resolver
  "Generate a resolver that can look up an entity by its identity attribute.

   Arguments:
   - id-attr: the identity attribute (RAD attribute map)
   - output-attrs: collection of attributes that this entity has"
  [{::attr/keys [qualified-key schema]
    ::dlo/keys  [generate-resolvers?]
    :or         {generate-resolvers? true}
    :as         id-attr}
   output-attrs]
  (when generate-resolvers?
    (let [outputs (mapv ::attr/qualified-key output-attrs)]
      (pco/resolver
        (symbol (str (namespace qualified-key) "." (name qualified-key) "-resolver"))
        {::pco/input   [qualified-key]
         ::pco/output  outputs
         ::pco/batch?  true}
        (fn [{::dlo/keys [databases]} inputs]
          (let [db    (get databases schema)
                ids   (mapv #(get % qualified-key) inputs)
                data  (get-by-ids db qualified-key ids outputs)]
            (mapv #(get data (get % qualified-key) {}) inputs)))))))

(defn- ref-resolvers
  "Generate resolvers for reference attributes (to-one and to-many refs).

   These resolvers handle navigation from one entity to related entities."
  [{::attr/keys [qualified-key cardinality target schema]
    ::dlo/keys  [generate-resolvers?]
    :or         {generate-resolvers? true}
    :as         ref-attr}
   all-attributes]
  (when (and generate-resolvers? target)
    (let [target-id-attr (first (filter #(and (= target (::attr/qualified-key %))
                                               (::attr/identity? %))
                                         all-attributes))]
      (when target-id-attr
        [(pco/resolver
           (symbol (str (namespace qualified-key) "." (name qualified-key) "-ref-resolver"))
           {::pco/input  [qualified-key]
            ::pco/output [{qualified-key [target]}]}
           (fn [{::dlo/keys [databases]} input]
             (let [db       (get databases schema)
                   ref-val  (get input qualified-key)]
               (cond
                 (nil? ref-val)
                 {qualified-key nil}

                 (map? ref-val)
                 {qualified-key ref-val}

                 (and (= :many cardinality) (sequential? ref-val))
                 {qualified-key (vec ref-val)}

                 :else
                 {qualified-key {target ref-val}}))))]))))

(defn generate-resolvers
  "Generate all automatic resolvers for the given RAD attributes.

   This includes:
   - ID resolvers for each identity attribute
   - Reference resolvers for ref attributes

   Arguments:
   - attributes: collection of all RAD attribute maps

   Returns a collection of Pathom3 resolvers."
  [attributes]
  (let [id-attrs    (filter ::attr/identity? attributes)
        ref-attrs   (filter #(= :ref (::attr/type %)) attributes)
        key->attrs  (group-by ::attr/qualified-key attributes)
        id->outputs (reduce
                      (fn [acc attr]
                        (let [identities (::attr/identities attr)]
                          (reduce
                            (fn [a id]
                              (update a id (fnil conj []) attr))
                            acc
                            identities)))
                      {}
                      attributes)]
    (concat
      ;; ID resolvers
      (keep (fn [id-attr]
              (let [outputs (get id->outputs (::attr/qualified-key id-attr) [])]
                (id-resolver id-attr outputs)))
            id-attrs)
      ;; Reference resolvers
      (mapcat #(ref-resolvers % attributes) ref-attrs))))

;; ================================================================================
;; Pathom3 Plugin
;; ================================================================================

(defn pathom-plugin
  "Create a Pathom3 plugin that adds Datalevin database support.

   This plugin ensures that the current database value is available
   in the Pathom environment for each request.

   Arguments:
   - connections: map of schema name to Datalevin connection

   Returns a Pathom3 plugin map."
  [connections]
  {:com.wsscode.pathom3.connect.runner/wrap-resolve
   (fn [resolve]
     (fn [env node]
       (let [dbs (reduce-kv
                   (fn [m schema conn]
                     (assoc m schema (d/db conn)))
                   {}
                   connections)]
         (resolve (assoc env
                    ::dlo/connections connections
                    ::dlo/databases dbs)
           node))))})

;; ================================================================================
;; Utility Functions
;; ================================================================================

(defn mock-resolver-env
  "Create a mock Pathom environment for testing resolvers.

   Arguments:
   - connections: map of schema name to Datalevin connection

   Returns a map suitable for passing to resolvers."
  [connections]
  (let [dbs (reduce-kv
              (fn [m schema conn]
                (assoc m schema (d/db conn)))
              {}
              connections)]
    {::dlo/connections connections
     ::dlo/databases   dbs}))

(defn empty-db-connection
  "Create an in-memory Datalevin connection for testing.

   Arguments:
   - schema-name: keyword identifying the schema
   - attributes: collection of RAD attributes

   Returns a temporary Datalevin connection."
  [schema-name attributes]
  (let [temp-dir (str "/tmp/datalevin-test-" (new-uuid))
        schema   (automatic-schema schema-name attributes)]
    (d/get-conn temp-dir schema)))

(defn seed-database!
  "Seed a database with initial data.

   Arguments:
   - conn: Datalevin connection
   - data: vector of entity maps to transact"
  [conn data]
  (when (seq data)
    (d/transact! conn data)))

(defn q
  "Execute a Datalog query against the database.
   Convenience wrapper around datalevin.core/q."
  [query & args]
  (apply d/q query args))

(defn pull
  "Pull entity data from the database.
   Convenience wrapper around datalevin.core/pull."
  [db pattern eid]
  (d/pull db pattern eid))

(defn pull-many
  "Pull multiple entities from the database.
   Convenience wrapper around datalevin.core/pull-many."
  [db pattern eids]
  (d/pull-many db pattern eids))
