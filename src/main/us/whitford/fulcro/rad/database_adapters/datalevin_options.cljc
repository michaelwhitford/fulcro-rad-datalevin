(ns us.whitford.fulcro.rad.database-adapters.datalevin-options
  "Options and configuration keys for Datalevin database adapter.")

;; Database environment keys
(def connections
  "Key in Pathom env for map of schema name -> Datalevin connection"
  ::connections)

(def databases
  "Key in Pathom env for map of schema name -> current database value"
  ::databases)

;; Attribute options
(def attribute-schema
  "Key to override/extend the generated Datalevin schema for an attribute.
   Value should be a map of Datalevin schema properties."
  ::attribute-schema)

(def native-id?
  "Boolean. If true, the attribute uses Datalevin's internal entity ID (:db/id) rather than 
   a domain-specific ID. The attribute must be of type :long.
   
   When true:
   - Schema generation skips this attribute (uses built-in :db/id)
   - Queries use raw entity IDs instead of lookup refs
   - Results map :db/id back to this attribute's qualified key
   
   Example:
   ```clojure
   (defattr id :person/id :long
     {::attr/identity? true
      ::dlo/native-id? true
      ::attr/schema :production})
   ```"
  ::native-id?)

(def generate-resolvers?
  "Boolean. If false, automatic resolvers will not be generated for this attribute.
   Defaults to true."
  ::generate-resolvers?)

(def wrap-resolve
  "Identity Attribute option. A `(fn [resolve])` that must return a `(fn [env input])`. 
   The `resolve` is the core resolving logic (a function of env/input), so the returned 
   function can manipulate the resolver inputs and outputs.

   This only affects auto-generated resolvers for this identity attribute.
   
   Example:
   ```clojure
   (defattr id :account/id :uuid
     {::attr/identity? true
      ::dlo/wrap-resolve (fn [resolve]
                           (fn [env input]
                             ;; Pre-processing
                             (let [result (resolve env input)]
                               ;; Post-processing  
                               result)))})
   ```"
  ::wrap-resolve)

;; Schema generation
(def schema
  "The schema key used for this set of attributes. Corresponds to a key in the connections map."
  ::schema)

;; Transaction options
(def transact-options
  "Map of options to pass to Datalevin transact! function"
  ::transact-options)

;; Performance and safety limits
(def transaction-timeout-ms
  "Timeout in milliseconds for database transactions. Default: 30000"
  ::transaction-timeout-ms)

(def max-retries
  "Maximum number of retry attempts for transient failures. Default: 3"
  ::max-retries)

(def max-batch-size
  "Maximum number of entities to fetch in a single batch query. Default: 1000"
  ::max-batch-size)
