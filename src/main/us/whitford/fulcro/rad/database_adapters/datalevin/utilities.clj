(ns us.whitford.fulcro.rad.database-adapters.datalevin.utilities
  "Utility functions for Datalevin adapter - query helpers."
  (:require
   [com.fulcrologic.guardrails.core :refer [>defn =>]]
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

(defn seed-database!
  "Seed a database with initial data.

   Arguments:
   - conn: Datalevin connection
   - data: vector of entity maps to transact

   Returns the transaction result."
  [conn data]
  (when (seq data)
    (d/transact! conn data)))
