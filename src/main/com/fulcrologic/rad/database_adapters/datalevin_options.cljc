(ns com.fulcrologic.rad.database-adapters.datalevin-options
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
