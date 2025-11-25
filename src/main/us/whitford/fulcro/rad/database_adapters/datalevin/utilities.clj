(ns us.whitford.fulcro.rad.database-adapters.datalevin.utilities
  "Utility functions for Datalevin adapter - query helpers."
  (:require
   [datalevin.core :as d]))

;; ================================================================================
;; Query Utilities (Convenience wrappers around Datalevin functions)
;; ================================================================================

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
