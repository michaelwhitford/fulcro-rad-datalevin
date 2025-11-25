(ns us.whitford.fulcro.rad.database-adapters.datalevin.wrap-datalevin-delete
  "Delete form middleware for Datalevin database adapter."
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
   [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [datalevin.core :as d]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

(defn delete-entity!
  "Delete the given entity, if possible."
  [{::attr/keys [key->attribute] :as env} params]
  (enc/if-let [pk    (ffirst params)
               id    (get params pk)
               ident [pk id]
               {:keys [::attr/schema]} (key->attribute pk)
               conn  (-> env dlo/connections (get schema))]
    (do
      (log/info "Deleting" ident)
      (let [db  (d/db conn)
            eid (ffirst (d/q '[:find ?e
                               :in $ ?attr ?id
                               :where [?e ?attr ?id]]
                             db pk id))]
        (when eid
          (d/transact! conn [[:db/retractEntity eid]]))
        {}))
    (log/warn "Datalevin adapter failed to delete" params)))

(defn wrap-datalevin-delete
  "Form delete middleware to accomplish datalevin deletes."
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [local-result   (delete-entity! pathom-env params)
           handler-result (handler pathom-env)]
       (deep-merge handler-result local-result))))
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (delete-entity! pathom-env params))))
