(ns us.whitford.fulcro.rad.database-adapters.datalevin
  "Datalevin database adapter for Fulcro RAD. Provides automatic schema generation,
   resolver generation, and save/delete middleware for RAD forms."
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.authorization :as auth]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.plugin :as p.plugin]
   [datalevin.core :as d]
   [edn-query-language.core :as eql]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log]))

;; ================================================================================
;; Configuration and Limits
;; ================================================================================

(def ^:dynamic *max-batch-size*
  "Maximum number of entities to fetch in a single batch query."
  1000)

(def ^:dynamic *transaction-timeout-ms*
  "Timeout in milliseconds for database transactions."
  30000)

(def ^:dynamic *max-retries*
  "Maximum number of retry attempts for transient failures."
  3)

;; ================================================================================
;; Error Handling
;; ================================================================================

(defn- transient-error?
  "Check if exception represents a transient/retriable error."
  [e]
  (let [msg (str e)]
    (or (re-find #"(?i)timeout" msg)
        (re-find #"(?i)connection" msg)
        (re-find #"(?i)temporary" msg))))

(defn- transact-with-error-handling!
  "Execute a transaction with proper error handling and context.

   Arguments:
   - conn: Datalevin connection
   - schema: schema identifier for error context
   - txn-data: transaction data to execute

   Returns the transaction result.
   Throws ex-info with context on failure."
  [conn schema txn-data]
  (try
    (d/transact! conn txn-data)
    (catch Exception e
      (throw (ex-info "Datalevin transaction failed"
                      {:schema schema
                       :txn-count (count txn-data)
                       :error-message (.getMessage e)}
                      e)))))

(defn- validate-connection!
  "Ensure connection exists for schema, throw if missing.

   Arguments:
   - connections: map of schema -> connection
   - schema: the schema key to validate

   Throws ex-info with available schemas if connection is missing."
  [connections schema]
  (when-not (get connections schema)
    (throw (ex-info "No database connection configured for schema"
                    {:schema schema
                     :available-schemas (vec (keys connections))}))))

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
   - schema: map of attribute schemas

   Throws on incompatible schema changes."
  [conn schema]
  (when (seq schema)
    (try
      (d/update-schema conn schema)
      (log/info "Schema updated successfully")
      (catch clojure.lang.ExceptionInfo e
        ;; Check if this is a known "already exists" type error
        (let [msg (.getMessage e)]
          (if (or (re-find #"(?i)already exists" msg)
                  (re-find #"(?i)identical" msg))
            (log/debug "Schema already up to date")
            (throw (ex-info "Incompatible schema change detected"
                            {:schema-keys (vec (keys schema))
                             :error-message msg}
                            e)))))
      (catch Exception e
        (throw (ex-info "Failed to update schema"
                        {:schema-keys (vec (keys schema))
                         :error-message (.getMessage e)}
                        e))))))

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
;; Delta Processing for Save Middleware
;; ================================================================================

(defn- validate-delta-entry
  "Validate a single delta entry structure."
  [[ident changes]]
  (and (vector? ident)
       (= 2 (count ident))
       (keyword? (first ident))
       (map? changes)
       (every? (fn [[k v]]
                 (and (keyword? k)
                      (map? v)
                      (contains? v :before)
                      (contains? v :after)))
               changes)))

(defn- validate-delta!
  "Validate delta structure, throw on invalid input.

   Delta format: {[id-attr id] {attr {:before v :after v'} ...} ...}"
  [delta]
  (when-not (map? delta)
    (throw (ex-info "Delta must be a map"
                    {:actual-type (type delta)})))
  (when-not (every? validate-delta-entry delta)
    (throw (ex-info "Invalid delta structure. Expected {[id-attr id] {attr {:before X :after Y}} ...}"
                    {:delta-keys (vec (keys delta))}))))

(def ^:private tempid-counter
  "Atomic counter for generating unique negative transaction IDs."
  (atom -1000000))

(def ^:private ^:dynamic *tempid-mappings*
  "Dynamic var to track tempid -> txid mappings within a transaction context."
  nil)

(defn- tempid->txid
  "Convert Fulcro tempid to unique negative integer for Datalevin transaction.
   Uses atomic counter to guarantee uniqueness. Caches mappings in *tempid-mappings*
   when bound to ensure consistency."
  [id]
  (if (tempid/tempid? id)
    (if *tempid-mappings*
      ;; Use cached mapping or create new one
      (if-let [existing (get @*tempid-mappings* id)]
        existing
        (let [new-id (swap! tempid-counter dec)]
          (swap! *tempid-mappings* assoc id new-id)
          new-id))
      ;; No cache, just generate (backwards compatibility)
      (swap! tempid-counter dec))
    id))

(defn- normalize-ident-id
  "Normalize an ident ID value, extracting the actual ID from various formats.
   Handles:
   - Tempids
   - Plain values (UUIDs, strings, etc.)
   - Map format {:id value} (from some RAD scenarios)"
  [id]
  (cond
    (tempid/tempid? id) id
    (and (map? id) (contains? id :id)) (:id id)
    :else id))

(defn- ident->lookup-ref
  "Convert Fulcro ident [attr id] to Datalevin lookup ref.
   Handles tempids and map-wrapped IDs."
  [[attr id]]
  (let [normalized-id (normalize-ident-id id)]
    (if (tempid/tempid? normalized-id)
      (tempid->txid normalized-id)
      [attr normalized-id])))

(defn- delta-entry->txn
  "Convert a single delta entry to Datalevin transaction data.

   Arguments:
   - key-attr: the identity attribute from the ident
   - id: the entity id (may be a tempid, plain value, or {:id value} map)
   - delta: map of attribute changes {:attr {:before X :after Y}}

   Returns a vector of transaction operations (maps and/or retraction vectors)."
  [key-attr id delta]
  (let [;; Normalize the ID to handle {:id value} map format
        normalized-id (normalize-ident-id id)
        ;; Check if this is a new entity creation:
        ;; 1. It has a tempid, OR
        ;; 2. The identity attribute itself is in the delta with :before nil
        ;;    (indicating the identity attribute is being set for the first time)
        ;; 3. If identity attr is NOT in delta, but all other attrs have :before nil,
        ;;    AND the ident ID was originally wrapped in {:id ...} format (common for new entities)
        identity-attr-is-new? (and (contains? delta key-attr)
                                   (nil? (get-in delta [key-attr :before])))
        all-changes-from-nil? (every? (fn [[_ {:keys [before]}]] (nil? before)) delta)
        id-was-wrapped? (and (map? id) (contains? id :id))
        is-new-entity? (or (tempid/tempid? normalized-id)
                           identity-attr-is-new?
                           (and all-changes-from-nil? id-was-wrapped?))
        entity-id (cond
                    (tempid/tempid? normalized-id) (tempid->txid normalized-id)
                    is-new-entity? (swap! tempid-counter dec) ;; Generate new temp ID
                    :else [key-attr normalized-id]) ;; Use lookup ref for existing entity
        ;; For new entities, extract the real ID value from the delta's :after
        ;; (not the TempId from the ident!)
        ;; If the key-attr is in the delta, use that; otherwise use normalized-id
        ;; IMPORTANT: If the value is itself a tempid, extract the UUID from it
        raw-id-value (if (contains? delta key-attr)
                       (get-in delta [key-attr :after])
                       normalized-id)
        real-id-value (if (tempid/tempid? raw-id-value)
                        (:id raw-id-value)
                        raw-id-value)
        base-entity (if is-new-entity?
                      {:db/id entity-id key-attr real-id-value}
                      {:db/id entity-id})
        {updates false retractions true}
        (group-by (fn [[attr {:keys [before after]}]]
                    (and (some? before) (nil? after)))
                  delta)

        ;; Build update map
        entity-map (reduce-kv
                    (fn [txn-data attr {:keys [before after]}]
                      (if (and (some? after) (not= before after))
                        (let [;; Extract UUID from tempids, handle idents, or use value as-is
                              value (cond
                                      (tempid/tempid? after) (:id after)
                                      (eql/ident? after) (ident->lookup-ref after)
                                      :else after)]
                          (assoc txn-data attr value))
                        txn-data))
                    base-entity
                    (into {} updates))

        ;; Build retraction operations
        retract-ops (mapv (fn [[attr {:keys [before]}]]
                            [:db/retract entity-id attr before])
                          retractions)

        ;; Combine operations
        result (cond-> []
                 (> (count entity-map) 1) ;; Has more than just :db/id
                 (conj entity-map)

                 (seq retract-ops)
                 (into retract-ops))]
    result))

(defn delta->txn
  "Convert RAD form delta to Datalevin transaction data.

   Delta format: {[id-attr id] {attr {:before v :after v'} ...} ...}

   Returns a vector of transaction operations (maps and retraction vectors).

   Throws ex-info if delta structure is invalid."
  [delta]
  (validate-delta! delta)
  (reduce-kv
   (fn [txns [key-attr id :as ident] entity-delta]
     (let [txn-ops (delta-entry->txn key-attr id entity-delta)]
       (into txns txn-ops)))
   []
   delta))

(comment
  (let [d {[:account/id #uuid "29dcd458-c47e-4079-86a1-36777fefaea9"] {:account/name {:before "Bob Smith" :after "Bob Smithson"}}}
        delta-txn (delta->txn d)]
    delta-txn))

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

(defn keys-in-delta
  "extract all keys from delta"
  [delta]
  (let [id-keys (into #{}
                      (map first)
                      (keys delta))
        all-keys (into id-keys
                       (mapcat keys)
                       (vals delta))]
    all-keys))

(defn schemas-for-delta
  "extract all schemas"
  [{::attr/keys [key->attribute]} delta]
  (let [all-keys (keys-in-delta delta)
        schemas (into #{}
                      (keep #(-> % key->attribute ::attr/schema))
                      all-keys)]
    schemas))

(defn save-form!
  "Do all the possible datalevin operations for the given form delta (save to all datalevin databases)"
  [{::form/keys [delta] :as env}]
  (let [schemas (schemas-for-delta env delta)
        result (atom {:tempids {}})]
    (doseq [schema schemas
            :let [connection (-> env dlo/connections (get schema))
                  {:keys [txn]} (delta->txn delta)]])))

;; ================================================================================
;; Save Middleware
;; ================================================================================

(defn wrap-datalevin-save
  "Middleware that processes RAD form save operations for Datalevin.

   Expects the pathom env to contain:
   - ::dlo/connections - map of schema name to Datalevin connection

   The middleware receives a delta (diff) and transacts it to the database.

   Throws ex-info if:
   - Connection is missing for a schema
   - Transaction fails"
  ([]
   (wrap-datalevin-save {}))
  ([{:keys [default-schema]
     :or   {default-schema :main} :as props}]
   (fn [save-middleware]
     (fn [{::attr/keys [key->attribute]
           ::form/keys [params]
           ::dlo/keys  [connections]
           :as         env}]
       (let [save-result (save-middleware env)
             delta       (::form/delta params)
             ;; Only proceed with save if the base handler didn't return an error
             should-save? (not (false? (::form/complete? save-result)))
             schemas     (schemas-for-delta env delta)]
         (tap> {:from ::wrap-datalevin-save :env env :save-result save-result :delta delta
                :should-save? should-save? :schemas schemas :props props :save-middleware (str save-middleware)})
         (if (and (seq delta) should-save?)
           ;; Bind tempid mappings for consistent id generation
           (binding [*tempid-mappings* (atom {})]
             (let [result (reduce
                           (fn [acc schema]
                             (validate-connection! connections schema)
                             (let [conn          (get connections schema)
                                   schema-delta  (into {}
                                                       (filter (fn [[[k _] _]]
                                                                 (let [attr (get key->attribute k)]
                                                                   (or (nil? attr)
                                                                       (= schema (::attr/schema attr))
                                                                       (nil? (::attr/schema attr))))))
                                                       delta)
                                   txn-data      (delta->txn schema-delta)
                                   _             (log/debug "Transacting to" schema ":" txn-data)
                                   tx-result     (when (seq txn-data)
                                                   (transact-with-error-handling! conn schema txn-data))
                                   tempid-map    (when tx-result
                                                   (tempid->result-id tx-result schema-delta))]
                               (cond-> acc
                                 (seq tempid-map)
                                 (update :tempids merge tempid-map))))
                           save-result
                           schemas)]
               ;; Ensure :tempids and ::form/errors are always present
               ;; This is required for RAD's EQL queries to work correctly
               (cond-> result
                 (not (contains? result :tempids))
                 (assoc :tempids {})
                 
                 (not (contains? result ::form/errors))
                 (assoc ::form/errors []))))
           ;; No delta or should not save, but still ensure required keys are present
           (cond-> save-result
             (not (contains? save-result :tempids))
             (assoc :tempids {})
             
             (not (contains? save-result ::form/errors))
             (assoc ::form/errors []))))))))

;; ================================================================================
;; Delete Middleware
;; ================================================================================

(defn wrap-datalevin-delete
  "Middleware that processes RAD form delete operations for Datalevin.

   Expects the pathom env to contain:
   - ::dlo/connections - map of schema name to Datalevin connection

   The middleware retracts entities by their identity attribute.

   Throws ex-info if:
   - Connection is missing for a schema
   - Transaction fails"
  ([]
   (wrap-datalevin-delete {}))
  ([{:keys [default-schema]
     :or   {default-schema :main}}]
   (fn [delete-middleware]
     (fn [{::attr/keys [key->attribute]
           ::dlo/keys  [connections]
           ::form/keys [params]
           :as         env}]
       (let [delete-result (delete-middleware env)]
         (tap> {:from ::wrap-datalevin-delete :default-schema default-schema :env env :delete-result delete-result})
         (if (seq params)
           (let [id-attr (first (keys params))
                 id      (params id-attr)
                 schema  (or (::attr/schema (get key->attribute id-attr))
                             default-schema)]
             (tap> {:from ::wrap-datalevin-delete :params params :schema schema})
             (validate-connection! connections schema)
             (let [conn   (get connections schema)
                   db     (d/db conn)
                   eid    (ffirst (d/q '[:find ?e
                                         :in $ ?attr ?id
                                         :where [?e ?attr ?id]]
                                       db id-attr id))
                   _      (log/debug "Deleting entity" eid "from schema" schema)]
               (tap> {:from ::wrap-datalevin-delete :eid eid})
               (if eid
                 (do
                   (transact-with-error-handling! conn schema [[:db/retractEntity eid]])
                   (cond-> delete-result
                     (not (contains? delete-result :tempids))
                     (assoc :tempids {})
                     
                     (not (contains? delete-result ::form/errors))
                     (assoc ::form/errors [])))
                 ;; Entity not found, still return required keys
                 (cond-> delete-result
                   (not (contains? delete-result :tempids))
                   (assoc :tempids {})
                   
                   (not (contains? delete-result ::form/errors))
                   (assoc ::form/errors [])))))
           ;; No params, still ensure required keys are present
           (cond-> delete-result
             (not (contains? delete-result :tempids))
             (assoc :tempids {})
             
             (not (contains? delete-result ::form/errors))
             (assoc ::form/errors []))))))))

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
    (let [target-id-attr (first (filter #(= target (::attr/qualified-key %))
                                        all-attributes))]
      (if-not target-id-attr
        (do
          (log/warn "Reference target not found for" qualified-key "target:" target)
          nil)
        (when (::attr/identity? target-id-attr)
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
                  {qualified-key {target ref-val}}))))])))))

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
   in the Pathom environment for each request. Database snapshots are
   taken once per request root and reused for consistency.

   Arguments:
   - connections: map of schema name to Datalevin connection

   Returns a Pathom3 plugin map."
  [connections]
  {::p.plugin/id `datalevin-plugin
   :com.wsscode.pathom3.connect.runner/wrap-root-run
   (fn [process]
     (fn [env ast-or-graph entity-tree*]
       (let [dbs (reduce-kv
                  (fn [m schema conn]
                    (assoc m schema (d/db conn)))
                  {}
                  connections)]
         (process (assoc env
                         ::dlo/connections connections
                         ::dlo/databases dbs)
                  ast-or-graph
                  entity-tree*))))})

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
  "Create a temporary Datalevin connection for testing.

   Arguments:
   - schema-name: keyword identifying the schema
   - attributes: collection of RAD attributes

   Returns a temporary Datalevin connection.

   WARNING: This creates a directory under /tmp that will not be automatically
   cleaned up. The caller is responsible for calling d/close on the connection.
   Consider using create-temp-database! for automatic cleanup support."
  [schema-name attributes]
  (let [temp-dir (str "/tmp/datalevin-test-" (new-uuid))
        schema   (automatic-schema schema-name attributes)]
    (d/get-conn temp-dir schema)))

(defn create-temp-database!
  "Create a temporary Datalevin connection for testing with cleanup support.

   Arguments:
   - schema-name: keyword identifying the schema
   - attributes: collection of RAD attributes

   Returns a map with:
   - :conn - the database connection
   - :path - the temporary directory path
   - :cleanup! - function to call to close and remove the database

   IMPORTANT: Caller must invoke :cleanup! when done to prevent resource leaks."
  [schema-name attributes]
  (let [temp-dir (str "/tmp/datalevin-test-" (new-uuid))
        schema   (automatic-schema schema-name attributes)
        conn     (d/get-conn temp-dir schema)
        cleanup! (fn []
                   (try
                     (d/close conn)
                     (let [dir (java.io.File. temp-dir)]
                       (when (.exists dir)
                         (doseq [file (reverse (file-seq dir))]
                           (.delete file))))
                     (catch Exception e
                       (log/warn "Error during database cleanup:" (.getMessage e)))))]
    {:conn     conn
     :path     temp-dir
     :cleanup! cleanup!}))

(defmacro with-temp-database
  "Execute body with a temporary database, ensuring cleanup.

   Arguments:
   - binding: vector of [conn-sym schema-name attributes]
   - body: forms to execute with the connection

   Example:
   (with-temp-database [conn :test test-attributes]
     (d/transact! conn [...])
     (d/q '[:find ...] (d/db conn)))"
  [[conn-sym schema-name attributes] & body]
  `(let [temp-info# (create-temp-database! ~schema-name ~attributes)
         ~conn-sym  (:conn temp-info#)]
     (try
       ~@body
       (finally
         ((:cleanup! temp-info#))))))

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


(comment
  (let [conn (d/get-conn "data/test")])
  (d/transact! conn data))
