(ns us.whitford.fulcro.rad.database-adapters.datalevin.start-databases
  "Database lifecycle management for Datalevin adapter."
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.rad.attributes :as attr]
   [datalevin.core :as d]
   [taoensso.timbre :as log]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

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
    (log/info "Started Datalevin database at" path)
    conn))

(defn stop-database!
  "Close a Datalevin database connection."
  [conn]
  (when conn
    (d/close conn)
    (log/info "Closed Datalevin database connection")))

(defn start-databases
  "Start all of the databases described in config, using the schemas defined in attributes.

   Arguments:
   - config: a map that contains the key `dlo/databases`.

   The `dlo/databases` entry in the config is a map with the following form:

   ```
   {::dlo/databases
    {:production {:path \"data/production\"
                  :auto-schema? true}
     :test       {:path \"data/test\"
                  :auto-schema? true}}}
   ```

   where the key (i.e. `:production`) is a schema name and the value is a config map
   containing:
   - :path - directory path for database storage
   - :auto-schema? - if true, automatically generate schema from attributes (default true)

   - options: a map that contains:
     - :attributes - collection of all RAD attributes
     
   Returns a map whose keys are the schema names (i.e. `:production`) and whose
   values are the live database connections."
  ([config]
   (start-databases config {}))
  ([config {:keys [attributes] :as options}]
   (let [db-configs (or (dlo/databases config)
                        (::dlo/databases config)
                        {})]
     (reduce-kv
      (fn [m schema-name db-config]
        (log/info "Starting database" schema-name)
        (let [config-with-attrs (assoc db-config
                                       :attributes attributes
                                       :schema schema-name)]
          (assoc m schema-name (start-database! config-with-attrs))))
      {}
      db-configs))))

(defn stop-databases
  "Stop all database connections.
   
   Arguments:
   - connections: map of schema name -> connection (as returned by start-databases)"
  [connections]
  (doseq [[schema-name conn] connections]
    (log/info "Stopping database" schema-name)
    (stop-database! conn)))
