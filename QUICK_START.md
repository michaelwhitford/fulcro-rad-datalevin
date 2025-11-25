# Quick Start Guide

Get up and running with fulcro-rad-datalevin in 5 minutes.

## 1. Add Dependency

```clojure
;; deps.edn
{:deps {us.whitford/fulcro-rad-datalevin 
        {:git/url "https://github.com/michaelwhitford/fulcro-rad-datalevin"
         :git/sha "LATEST_SHA"}
        
        ;; Also need these if not already present
        com.fulcrologic/fulcro-rad {:mvn/version "1.6.6"}
        io.github.juji-io/datalevin {:mvn/version "0.9.11"}}}
```

## 2. Define Attributes

```clojure
(ns app.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id :account/id :uuid
  {::attr/schema    :production
   ::attr/identity? true})

(defattr email :account/email :string
  {::attr/schema     :production
   ::attr/identities #{:account/id}
   ::attr/required?  true})

(defattr name :account/name :string
  {::attr/schema     :production
   ::attr/identities #{:account/id}})

(def attributes [id email name])
```

## 3. Start Database

```clojure
(ns app.components.database
  (:require
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
    [app.model.account :as account]))

(defonce connections (atom {}))

(defn start! []
  (let [conn (dl/start-database!
               {:path       "data/production.db"
                :schema     :production
                :attributes account/attributes})]
    (swap! connections assoc :production conn)))

(defn stop! []
  (doseq [[_ conn] @connections]
    (dl/stop-database! conn))
  (reset! connections {}))
```

## 4. Configure Pathom

```clojure
(ns app.components.parser
  (:require
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
    [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [app.model.account :as account]
    [app.components.database :as db]))

;; Generate resolvers from attributes
(def resolvers (dl/generate-resolvers account/attributes))

;; Create Pathom environment
(defn make-env []
  (let [connections @db/connections]
    {::dlo/connections connections
     ::dlo/databases   (into {} (map (fn [[k v]] [k (dl/db v)])) connections)}))

;; Create parser
(def parser
  (p.eql/boundary-interface
    (pci/register resolvers)))

;; Query function
(defn query [eql]
  (parser (make-env) eql))
```

## 5. Add Save/Delete Middleware

```clojure
(ns app.server.middleware
  (:require
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
    [app.components.database :as db]))

;; Create middleware
(def save-middleware
  (dl/wrap-datalevin-save 
    {:default-schema :production}))

(def delete-middleware
  (dl/wrap-datalevin-delete))

;; Wrap your handler
(defn wrap-api [handler]
  (-> handler
      save-middleware
      delete-middleware))
```

## 6. Use in Forms

```clojure
(ns app.ui.account-form
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.form :as form]
    [app.model.account :as account]))

(defsc AccountForm [this props]
  {:ident         :account/id
   :query         [:account/id :account/email :account/name]
   ::form/id      account-id
   ::form/fields  #{:account/email :account/name}
   ::form/attributes account/attributes}
  
  (form/render-layout this))
```

## 7. Query Data

```clojure
;; Get all accounts
(query [{:all-accounts [:account/id :account/email :account/name]}])
;; => {:all-accounts [{:account/id #uuid "..." :account/email "..." :account/name "..."}
;;                    ...]}

;; Get specific account
(query [{[:account/id #uuid "..."] [:account/email :account/name]}])
;; => {[:account/id #uuid "..."] {:account/email "..." :account/name "..."}}
```

## Testing

```bash
# Run all tests
clojure -M:run-tests

# Run specific test namespace
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-core-test
```

## Common Patterns

### Multi-Schema Setup

```clojure
(defn start! []
  (let [prod-conn (dl/start-database!
                    {:path "data/production.db"
                     :schema :production
                     :attributes production-attrs})
        dev-conn  (dl/start-database!
                    {:path "data/development.db"
                     :schema :development
                     :attributes dev-attrs})]
    (swap! connections assoc
      :production prod-conn
      :development dev-conn)))
```

### Custom Schema Overrides

```clojure
(defattr email :account/email :string
  {::attr/schema     :production
   ::attr/identities #{:account/id}
   ;; Override with Datalevin-specific schema
   ::dlo/attribute-schema {:db/unique :db.unique/value}})
```

### Batch Queries

```clojure
;; Default limit: 1000
(dl/get-by-ids db :account/id account-ids [:account/email :account/name])

;; Increase limit for large queries
(binding [dl/*max-batch-size* 5000]
  (dl/get-by-ids db :account/id many-ids pattern))
```

### Temporary Test Databases

```clojure
(deftest my-test
  (dl/with-temp-database [conn :test test-attributes]
    ;; Use conn for testing
    (dl/transact! conn [{:account/id (random-uuid)
                         :account/email "test@example.com"}])
    ;; Automatically cleaned up after test
    ))
```

## Next Steps

- Read the full [README.adoc](README.adoc) for complete API documentation
- Check [CHANGELOG.md](CHANGELOG.md) for recent updates
- See [AGENTS.md](AGENTS.md) for development workflow
- Review [BETA_RELEASE_NOTES.md](BETA_RELEASE_NOTES.md) for known limitations

## Need Help?

Common issues and solutions are in the README's Troubleshooting section.

For bugs or questions, please open an issue on GitHub.
