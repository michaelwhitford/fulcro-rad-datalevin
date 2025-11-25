(ns us.whitford.fulcro.rad.database-adapters.datalevin.pathom-plugin
  "Pathom3 plugin for Datalevin database adapter."
  (:require
   [com.wsscode.pathom3.plugin :as p.plugin]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

(defn wrap-env
  "Wrap the pathom environment with database connections and snapshots.
   
   Arguments:
   - base-wrapper: optional function to further wrap the env
   - database-mapper: function that takes env and returns map of schema -> connection
   
   Returns a function that wraps the environment with database values."
  ([database-mapper]
   (wrap-env nil database-mapper))
  ([base-wrapper database-mapper]
   (fn [env]
     (let [connections (database-mapper env)
           databases   (reduce-kv
                        (fn [m schema conn]
                          (assoc m schema (d/db conn)))
                        {}
                        connections)]
       (cond-> (assoc env
                      dlo/connections connections
                      dlo/databases databases)
         base-wrapper (base-wrapper))))))

(defn pathom-plugin
  "Create a Pathom3 plugin that adds Datalevin database support.

   This plugin ensures that the current database value is available
   in the Pathom environment for each request. Database snapshots are
   taken once per request root and reused for consistency.

   Arguments:
   - database-mapper: a function `(fn [pathom-env] {schema-name connection})`
     for a given request.

   The resulting pathom-env available to all resolvers will then have:
   - `dlo/connections`: The result of database-mapper
   - `dlo/databases`: A map from schema name to current database values

   This plugin should run before (be listed after) most other plugins in the
   plugin chain since it adds connection details to the parsing env."
  [database-mapper]
  (let [augment (wrap-env database-mapper)]
    {::p.plugin/id `datalevin-plugin
     :com.wsscode.pathom3.connect.runner/wrap-root-run
     (fn [process]
       (fn [env ast-or-graph entity-tree*]
         (process (augment env) ast-or-graph entity-tree*)))}))
