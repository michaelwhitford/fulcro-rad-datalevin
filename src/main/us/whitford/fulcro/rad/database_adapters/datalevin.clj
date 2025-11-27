(ns us.whitford.fulcro.rad.database-adapters.datalevin
  "Datalevin database adapter for Fulcro RAD.

   This namespace provides the main public API by re-exporting functions
   from the modular sub-namespaces. Import this namespace and use its
   functions to work with the Datalevin adapter.

   Main functions:
   - start-databases: Start database connections
   - stop-databases: Stop database connections
   - pathom-plugin: Create a Pathom3 plugin for database access
   - generate-resolvers: Generate automatic resolvers for RAD attributes
   - wrap-datalevin-save: Middleware for saving forms
   - wrap-datalevin-delete: Middleware for deleting entities
   - automatic-schema: Generate Datalevin schema from RAD attributes"
  {:clj-kondo/config '{:linters {:unresolved-var {:level :off}}}}
  (:require
   [us.whitford.fulcro.rad.database-adapters.datalevin.generate-resolvers :as gr]
   [us.whitford.fulcro.rad.database-adapters.datalevin.pathom-plugin :as pp]
   [us.whitford.fulcro.rad.database-adapters.datalevin.start-databases :as sd]
   [us.whitford.fulcro.rad.database-adapters.datalevin.utilities :as util]
   [us.whitford.fulcro.rad.database-adapters.datalevin.wrap-datalevin-delete :as wdd]
   [us.whitford.fulcro.rad.database-adapters.datalevin.wrap-datalevin-save :as wds]))

;; ================================================================================
;; Re-export main API functions
;; ================================================================================

;; Database lifecycle
(def start-databases sd/start-databases)
(def stop-databases sd/stop-databases)
(def start-database! sd/start-database!)
(def stop-database! sd/stop-database!)
(def seed-database! util/seed-database!)

;; Schema generation
(def automatic-schema sd/automatic-schema)
(def ensure-schema! sd/ensure-schema!)

;; Pathom integration
(def pathom-plugin pp/pathom-plugin)
(def wrap-env pp/wrap-env)

;; Resolver generation
(def generate-resolvers
  "Generate all of the resolvers that make sense for the given database config.
   
   Arguments:
   - attributes: collection of RAD attributes
   - schema: schema keyword (e.g., :main)
   
   Returns: sequence of Pathom3 resolvers"
  gr/generate-resolvers)
(def get-by-ids gr/get-by-ids)

;; Form middleware
(def wrap-datalevin-save wds/wrap-datalevin-save)
(def wrap-datalevin-delete wdd/wrap-datalevin-delete)

;; Form utilities - Delta processing
(def delta->txn wds/delta->txn)
(def keys-in-delta wds/keys-in-delta)
(def schemas-for-delta wds/schemas-for-delta)
(def save-form! wds/save-form!)

;; Query utilities
(def q util/q)
(def pull util/pull)
(def pull-many util/pull-many)
