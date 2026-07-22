# Quick Start Guide

Get up and running with fulcro-rad-datalevin in a few minutes. See
[README.adoc](README.adoc) for the complete API reference.

## 1. Add Dependencies

```clojure
;; deps.edn
{:deps {us.whitford/fulcro-rad-datalevin
        {:git/url "https://github.com/michaelwhitford/fulcro-rad-datalevin"
         :git/sha "LATEST_SHA"}

        ;; Peer dependencies (if not already present)
        com.fulcrologic/fulcro-rad {:mvn/version "1.6.24"}
        datalevin/datalevin        {:mvn/version "1.0.0"}

        ;; Bring your own Pathom — this library does NOT bundle it.
        ;; Pathom 3:
        com.wsscode/pathom3        {:mvn/version "2025.01.16-alpha"}
        ;; ...or Pathom 2:
        ;; com.wsscode/pathom      {:mvn/version "2.4.0"}
        }}
```

The adapter works with **both Pathom 2 and Pathom 3** and has no hard dependency
on either.

## 2. Define Attributes

```clojure
(ns app.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

(defattr id :account/id :uuid
  {::attr/schema    :main
   ::attr/identity? true})

(defattr email :account/email :string
  {::attr/schema     :main
   ::attr/identities #{:account/id}
   ::attr/required?  true
   ;; Merge native Datalevin schema keys via ::dlo/attribute-schema
   ::dlo/attribute-schema {:db/unique :db.unique/value}})

(defattr name :account/name :string
  {::attr/schema     :main
   ::attr/identities #{:account/id}})

(def attributes [id email name])
```

## 3. Start the Database

```clojure
(ns app.components.database
  (:require
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
    [app.model.account :as account]))

(defonce connections (atom {}))

(defn start! []
  (let [conn (dl/start-database!
               {:path       "data/main.db"
                :schema     :main
                :attributes account/attributes
                ;; optional native get-conn options (Datalevin 1.0.0)
                :conn-opts  {:auto-entity-time? true}})]
    (swap! connections assoc :main conn)))

(defn stop! []
  (doseq [[_ conn] @connections]
    (dl/stop-database! conn))
  (reset! connections {}))
```

## 4. Configure the Pathom Processor

The save/delete middleware and generated resolvers are wired into the parser env
via `form/*` and `dl/*` helpers. `generate-resolvers` returns Pathom-2-shape
resolver maps; RAD's Pathom 3 `new-processor` auto-converts them.

### Pathom 3

```clojure
(ns app.components.parser
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.pathom3 :as pathom3]
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
    [app.model.account :as account]
    [app.components.database :as db]))

(def all-attributes (vec account/attributes))

(def processor
  (pathom3/new-processor {}
    ;; env-middleware chain: attr, form (with our save/delete), then our wrap-env
    (-> (attr/wrap-env all-attributes)
        (form/wrap-env (dl/wrap-datalevin-save) (dl/wrap-datalevin-delete))
        (dl/wrap-env (fn [_env] @db/connections)))
    []                                              ;; extra Pathom 3 plugins
    [(dl/generate-resolvers all-attributes :main)   ;; schema arg is required
     form/resolvers]))                              ;; RAD's save/delete mutations

;; Run an EQL query:
(processor {} [{[:account/id some-uuid] [:account/name]}])
```

### Pathom 2

Use `pathom/new-parser` with the plugin variants — `dl/pathom-plugin` and
`form/pathom-plugin`. See [README.adoc](README.adoc) ("Configure Pathom Parser")
for the full Pathom 2 example.

## 5. Use in Forms

```clojure
(ns app.ui.account-form
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [app.model.account :as account]))

(defsc-form AccountForm [this props]
  {fo/id         account/id
   fo/attributes [account/name account/email]})
```

Saving a form runs the `form/save-form` mutation through the save middleware.
Because the adapter publishes the transaction's `:db-after` into the request
snapshot (read-your-writes), the mutation returns the saved entity — not just
`:tempids` — so the form is populated with server-confirmed values.

## 6. Query Data

```clojure
;; All accounts (the generated all-ids resolver key is :<entity-ns>/all)
(processor {} [{:account/all [:account/id :account/email :account/name]}])
;; => {:account/all [{:account/id #uuid "..." :account/email "..." :account/name "..."} ...]}

;; A specific account by ident
(processor {} [{[:account/id some-uuid] [:account/email :account/name]}])
;; => {[:account/id some-uuid] {:account/email "..." :account/name "..."}}
```

## Testing

```bash
# Run all tests
clojure -M:run-tests

# Focus a single namespace
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-test
```

Throwaway databases for your own tests are provided by the **test-only** helpers
`with-test-conn` / `with-test-conn-attrs` and `mock-resolver-env` in
`us.whitford.fulcro.rad.database-adapters.test-utils`.

## Common Patterns

### Multi-Schema Setup

```clojure
(defn start! []
  (swap! connections assoc
    :main    (dl/start-database! {:path "data/main.db"    :schema :main    :attributes main-attrs})
    :reports (dl/start-database! {:path "data/reports.db" :schema :reports :attributes report-attrs})))
```

Each attribute's `::attr/schema` selects its connection; the wiring function you
pass to `dl/wrap-env` / `dl/pathom-plugin` returns the whole schema → connection map.

### Composing Save Middleware

```clojure
;; Terminal (most common):
(dl/wrap-datalevin-save)

;; Composing over your own handler (runs after the save):
(dl/wrap-datalevin-save my-base-save-handler)
```

The schema is derived from each attribute's `::attr/schema`; the middleware takes
no options map.

### Batch Queries

```clojure
;; Default limit: 1000 (override via ::dlo/max-batch-size in the resolver env)
(dl/get-by-ids db :account/id account-ids [:account/email :account/name])
```

## Next Steps

- Read the full [README.adoc](README.adoc) for complete API documentation.
- Check [CHANGELOG.md](CHANGELOG.md) for recent changes and known breaking changes.
- See [AGENTS.md](AGENTS.md) for the development workflow.

For bugs or questions, please open an issue on GitHub.
