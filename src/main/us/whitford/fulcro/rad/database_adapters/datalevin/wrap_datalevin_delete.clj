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

(defn- native-ident?
  "Returns true if the delete's identity key uses a Datalevin native :db/id."
  [{::attr/keys [key->attribute]} pk]
  (boolean (some-> pk key->attribute dlo/native-id?)))

(defn delete-entity!
  "Delete the entity identified by the delete `params`.

   Resolves the entity id with `d/entid` from the identity key: the raw id for
   native-id attributes, otherwise the `[pk id]` lookup ref — so no extra query
   is needed. Deleting a non-existent entity is an idempotent no-op. Transaction
   failures are propagated via `ex-info` rather than swallowed."
  [{::attr/keys [key->attribute] :as env} params]
  (enc/if-let [pk    (ffirst params)
               id    (get params pk)
               ident [pk id]
               {:keys [::attr/schema]} (key->attribute pk)
               conn  (-> env dlo/connections (get schema))]
    (let [db     (d/db conn)
          lookup (if (native-ident? env pk) id [pk id])
          eid    (d/entid db lookup)]
      (if eid
        (do
          (log/info "Deleting" ident)
          (try
            (d/transact! conn [[:db/retractEntity eid]])
            {}
            (catch Exception e
              (log/error e "Datalevin delete transaction failed for" ident)
              (throw (ex-info "Datalevin delete transaction failed"
                              {:ident  ident
                               :schema schema}
                              e)))))
        (do
          (log/info "Nothing to delete for" ident)
          {})))
    (do
      (log/warn "Datalevin adapter failed to delete" params)
      {})))

(defn wrap-datalevin-delete
  "Form delete middleware to accomplish datalevin deletes."
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [delete-result (delete-entity! pathom-env params)]
       (tap> {:from ::wrap-datalevin-delete :pathom-env pathom-env :delete-result delete-result})
       (merge {:tempids {}} delete-result))))
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [delete-result   (delete-entity! pathom-env params)
           handler-result (handler pathom-env)]
       (tap> {:from ::wrap-datalevin-delete :pathom-env pathom-env :delete-result delete-result :handler-result handler-result})
       (deep-merge {:tempids {}} delete-result handler-result)))))
