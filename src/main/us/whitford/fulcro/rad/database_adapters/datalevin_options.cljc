(ns us.whitford.fulcro.rad.database-adapters.datalevin-options
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
   Value should be a map of Datalevin schema properties.

   Any native Datalevin schema key may be supplied here; it is merged into (and
   takes precedence over) the auto-generated schema for the attribute. This is
   the datalevin-native way to reach features the adapter does not wrap
   explicitly.

   Attribute predicates (`:db.attr/preds`) — database-side validation:
   Datalevin enforces `:db.attr/preds` on every write of the attribute. Each
   predicate must be a *qualified symbol* (or a sequential collection of them),
   because the schema is persisted and must be resolvable via `requiring-resolve`
   at write time — anonymous functions cannot be used. The predicate is invoked
   as `(pred value)` and must return strictly `true`; any other result aborts the
   transaction with a `:transact/attr-pred` error.

   ```clojure
   ;; my.app.validation
   (defn valid-email? [v] (boolean (re-matches #\".+@.+\\..+\" v)))

   (defattr email :account/email :string
     {::attr/identity?       true
      ::dlo/attribute-schema {:db.attr/preds 'my.app.validation/valid-email?}})
   ```

   For entity-/transaction-level assertions (spanning multiple attributes), use a
   `[:db/ensure pred & args]` transaction form appended via `::raw-txn`
   (see `raw-txn`)."
  ::attribute-schema)

(def native-id?
  "Boolean. If true, the attribute uses Datalevin's internal entity ID (:db/id) rather than 
   a domain-specific ID. The attribute must be of type :long.
   
   When true:
   - Schema generation skips this attribute (uses built-in :db/id)
   - Queries use raw entity IDs instead of lookup refs
   - Results map :db/id back to this attribute's qualified key
   
   Example:
   ```clojure
   (defattr id :person/id :long
     {::attr/identity? true
      ::dlo/native-id? true
      ::attr/schema :production})
   ```"
  ::native-id?)

(def generate-resolvers?
  "Boolean. If false, automatic resolvers will not be generated for this attribute.
   Defaults to true."
  ::generate-resolvers?)

(def wrap-resolve
  "Identity Attribute option. A `(fn [resolve])` that must return a `(fn [env input])`. 
   The `resolve` is the core resolving logic (a function of env/input), so the returned 
   function can manipulate the resolver inputs and outputs.

   This only affects auto-generated resolvers for this identity attribute.
   
   Example:
   ```clojure
   (defattr id :account/id :uuid
     {::attr/identity? true
      ::dlo/wrap-resolve (fn [resolve]
                           (fn [env input]
                             ;; Pre-processing
                             (let [result (resolve env input)]
                               ;; Post-processing  
                               result)))})
   ```"
  ::wrap-resolve)

;; Schema generation
(def schema
  "The schema key used for this set of attributes. Corresponds to a key in the connections map."
  ::schema)

;; Transaction options
(def transact-options
  "Save-env key. Value is passed as the `tx-meta` argument to Datalevin's
   `transact!` for every save transaction (Datalevin's `transact!` takes
   `[conn tx-data tx-meta]`). Use it to attach transaction metadata that shows
   up in tx reports and `listen!` callbacks (e.g. audit/user context)."
  ::transact-options)

(def raw-txn
  "Save-env key holding a vector of additional *native* Datalevin transaction
   forms to append to the generated transaction during a save. Populate it via
   `wrap-datalevin-save`/`append-to-raw-txn` from your own save middleware.

   The primary use is entity-/transaction-level post-conditions with Datalevin's
   `:db/ensure` special form:

   ```clojure
   ;; my.app.rules
   (defn balance-non-negative? [db eid]
     (<= 0 (:account/balance (d/pull db [:account/balance] eid))))

   ;; in save middleware, before delegating to the datalevin save handler:
   (dlo/append-to-raw-txn env [[:db/ensure `my.app.rules/balance-non-negative? \"acct\"]])
   ```

   The `:db/ensure` predicate is invoked as `(pred db-after & resolved-args)`;
   any falsey result aborts the transaction. Args may be tempids (resolved to the
   new entity ids), `:db/current-tx`, or literals.

   NOTE: In a multi-schema save these forms are appended to each affected
   schema's transaction; reference entities that live within a single schema."
  ::raw-txn)

;; Performance and safety limits
(def transaction-timeout-ms
  "Save-env key. When present, each save transaction runs inside a Datalevin
   `with-transaction` with this per-transaction `:timeout-ms`; if it does not
   complete in time the transaction is aborted (Datalevin 1.0.0). When absent,
   no timeout is applied. Prefer this over the global
   `set-explicit-transaction-timeout!` for per-request control."
  ::transaction-timeout-ms)

(def max-batch-size
  "Resolver-env key. Maximum number of entities fetched in a single batch query.
   When present it overrides the default (1000) for auto-generated id-resolvers."
  ::max-batch-size)
