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
  "Boolean. If true, the attribute uses Datalevin's internal entity ID rather than a domain-specific ID."
  ::native-id?)

(def generate-resolvers?
  "Boolean. If false, automatic resolvers will not be generated for this attribute.
   Defaults to true."
  ::generate-resolvers?)

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
