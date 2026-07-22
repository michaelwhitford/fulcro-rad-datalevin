(ns us.whitford.fulcro.rad.database-adapters.datalevin.pathom-plugin
  "Pathom environment wiring for the Datalevin database adapter.

   This namespace has NO hard dependency on any Pathom version:

   - `wrap-env` is a plain `(fn [env] env')` augmentor. It is the idiomatic
     **Pathom 3** integration point — compose it into the `env-middleware` you
     pass to RAD's `com.fulcrologic.rad.pathom3/new-processor` (alongside
     `attr/wrap-env` and `form/wrap-env`).
   - `pathom-plugin` wraps that same augmentation in a **Pathom 2**
     `::p/wrap-parser` plugin map (a plain namespaced keyword literal — no
     pathom require needed), for use with `com.fulcrologic.rad.pathom/new-parser`."
  (:require
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

(defn wrap-env
  "Wrap the pathom environment with database connections and snapshots.

   This is the **Pathom 3** integration point: the returned `(fn [env] env')`
   can be composed into the `env-middleware` given to RAD's Pathom 3 processor.

   Arguments:
   - base-wrapper: optional function to further wrap the env
   - database-mapper: function that takes env and returns map of schema -> connection

   Returns a function that wraps the environment with database values, adding:
   - `dlo/connections`: the result of database-mapper
   - `dlo/databases`:   a map from schema name to current database values"
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
  "Create a **Pathom 2** plugin that adds Datalevin database support.

   The plugin augments the parsing env for each request so that the current
   database value is available to all resolvers. Database snapshots are taken
   once per request (parser call) and reused for consistency.

   Arguments:
   - database-mapper: a function `(fn [pathom-env] {schema-name connection})`
     for a given request.

   The resulting pathom-env available to all resolvers will then have:
   - `dlo/connections`: the result of database-mapper
   - `dlo/databases`:   a map from schema name to current database values

   Returns a Pathom 2 plugin map keyed by
   `:com.wsscode.pathom.core/wrap-parser` (the keyword is written as a literal,
   so this namespace does not depend on pathom being present).

   For **Pathom 3**, do not use this plugin; compose `wrap-env` into your
   processor's `env-middleware` instead."
  [database-mapper]
  (let [augment (wrap-env database-mapper)]
    {:com.wsscode.pathom.core/wrap-parser
     (fn env-wrap-wrap-parser [parser]
       (fn env-wrap-wrap-internal [env tx]
         (parser (augment env) tx)))}))
