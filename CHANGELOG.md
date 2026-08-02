# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

#### Vector similarity search (`:<entity>/similar` resolvers)
- `generate-resolvers` emits a fourth resolver type: for every entity type
  with at least one `:vec` attribute, a parameterized nearest-neighbor
  resolver outputting `{:<ns>/similar [{id-attr ...} ...]}` — idents in
  **similarity order** (ascending vector distance), with fields auto-filled by
  the batched id-resolver:
  `[{(:account/similar {:vector embedding :top 10}) [:account/id :account/name]}]`.
- Params from `(:query-params env)`: `:vector` (required query embedding),
  `:attribute` (optional narrowing to one `:vec` attribute), `:top`. Missing
  `:vector` resolves to an empty list. Uses Datalevin's `vec-neighbors` with
  `{:display :refs+dists}` + explicit ascending sort (Datalog set semantics
  do not preserve engine rank order). Native-id entities supported.
- `vec-conn-opts` now also derives `:metric-type` from
  `:db.vec/metric-type` in `::dlo/attribute-schema` (stripped from the
  schema alongside `:db.vec/dimensions`), so cosine/euclidean/etc. can be
  declared per attribute.
- Pairs with the full-text `:<entity>/search` resolver for hybrid
  keyword + semantic search.

#### Full-text search documentation and report integration — Phase 3
- README gains a "Full-Text Search" section: `::dlo/fulltext?` declaration,
  generated-resolver EQL, parameter reference (`:query`/`:top`/`:limit`/`:offset`),
  phrase search + `:index-position?`, and a RAD report wiring example
  (`ro/source-attribute :account/search` + a `:query` control).
- Verified end-to-end in the proving-ground app (datalevin-test-app):
  report-shaped EQL through a real RAD Pathom 3 parser returns
  relevance-ordered rows with auto-filled columns; pagination params;
  native-id entity search; a working `AccountSearchList` report component.

#### Generated `:<entity>/search` resolvers — Phase 2
- `generate-resolvers` now emits a third resolver type: for every entity type
  with at least one `::dlo/fulltext?` attribute, a parameterized full-text
  search resolver outputting `{:<ns>/search [{id-attr ...} ...]}` — idents in
  **relevance order** (descending score), with fields auto-filled by the
  existing batched id-resolver:
  `[{(:account/search {:query "fox"}) [:account/id :account/name]}]`.
- Params are read from `(:query-params env)` (populated by both RAD parsers
  from the EQL join params / a load's `{:params ...}}`): `:query` (string,
  boolean expression vector, or `{:phrase "..."}`), `:top`, `:limit`,
  `:offset`. Missing/blank `:query` resolves to an empty list.
- Relevance ordering uses `{:display :refs+scores}` + explicit descending
  sort (Datalog set semantics do not preserve engine rank order). Native-id
  entities return the matched eid as the id; searches the entity's derived
  domain(s), honoring user-specified `:db.fulltext/domains`/`autoDomain`.

#### Full-text search foundation (`::dlo/fulltext?`) — Phase 1
- New attribute option `::dlo/fulltext?` (value `true` or a search-domain
  options map such as `{:index-position? true}` for phrase/proximity search).
- Schema generation emits `:db/fulltext true` plus a derived
  `:db.fulltext/domains [<entity-domain>]` — one shared domain per entity
  type, named after the attribute namespace. User-supplied native
  `:db.fulltext/domains` / `:db.fulltext/autoDomain` (via
  `::dlo/attribute-schema`) are respected and suppress derivation.
- New `search-conn-opts` derives `{:search-domains {...}}` from map-valued
  `::dlo/fulltext?` declarations; `start-database!` merges it (alongside
  `vec-conn-opts`) into the `d/get-conn` options. `merge-conn-opts` now
  deep-merges `:search-domains` like `:vector-domains`.
- Indexing is synchronous (read-your-writes). The generated
  `:<entity>/search` resolver is Phase 2 (see
  `mementum/knowledge/design/full-text-search.md`).

#### CI and release automation (GitHub Actions)
- `.github/workflows/ci.yml` — on push/PR to `main`: runs the full kaocha
  suite and a clj-kondo lint job (which regenerates third-party lint configs
  from the classpath before linting, since `.clj-kondo/imports/` is derived
  and gitignored).
- `.github/workflows/release.yml` — pushing a `vX.Y.Z` (full release) or
  `vX.Y.Z-RCn` (release candidate) tag runs the tests, builds the jar with
  the version derived from the tag, deploys to Clojars
  (`us.whitford/fulcro-rad-datalevin`), and creates a GitHub Release.
  `-alpha`/`-beta` version suffixes are local-only and never deploy.

### Changed

#### Build migrated from depstar to tools.build
- New `build.clj` (`clojure -T:build clean|jar|install`); version defaults to
  `1.0.0-RC1` and can be overridden with the `VERSION` env var. The published
  pom is built from the root `:deps` only (no test-only deps such as pathom).
- `:jar`/`:install` depstar aliases removed; `:deploy` (deps-deploy) now reads
  the pom from inside `target/classes/` and still runs in an isolated
  classpath.

#### Read-your-writes in the save/delete mutation (atom-backed db snapshot)
- `::dlo/databases` is now `{schema -> atom<db>}` (was `{schema -> db}`).
  `wrap-env` / `pathom-plugin` seed the atoms per request; `save-form!` and
  `delete-entity!` `reset!` the relevant atom to the transaction report's
  `:db-after`. As a result, a `form/save-form` mutation's output query resolves
  the just-saved (or updated) entity **within the same request** — the mutation
  returns the saved attributes, not just `:tempids` — matching the datomic/xtdb
  adapters. Uses Datalevin's native `:db-after` (idiomatic; the report is already
  in hand) for read-your-writes plus request-scoped snapshot consistency.
- Resolvers deref the snapshot leniently (a bare `db` is still accepted), so
  callers passing a plain db in a hand-built env continue to work.

#### Real Pathom 2 + Pathom 3 integration tests
- New `pathom_integration_test.clj` drives the adapter through **real** RAD
  parsers — `pathom/new-parser` (Pathom 2) and `pathom3/new-processor`
  (Pathom 3) — with full CRUD parity: id-resolver read, all-ids read,
  `form/save-form` new + update (asserting the returned entity → proves
  read-your-writes), and `form/delete-entity`. `com.wsscode/pathom` (2) added as
  a test-only dependency alongside pathom3.

#### Dual Pathom 2 / Pathom 3 Support (library is now Pathom-version-agnostic)
- **The adapter no longer hard-depends on any Pathom version.** `pathom3` moved
  out of `:deps` into the `:test`/`:run-tests` aliases only. The main namespaces
  load with no Pathom library on the classpath.
- **`generate-resolvers` now emits Pathom-2-shape resolver maps** (plain data
  keyed by `:com.wsscode.pathom.connect/{sym,input,output,batch?,resolve}`). These
  work directly in a Pathom 2 parser and are auto-converted by RAD's Pathom 3
  processor (`new-processor` runs `convert-resolvers`), so **Pathom 3 apps need
  no changes**. Batching survives the conversion.
- **`generate-resolvers-pathom3`** — new convenience returning native Pathom 3
  resolver records for callers that build a Pathom 3 index directly. Pathom 3 is
  resolved lazily via `requiring-resolve` at call time, keeping it optional.
- Mirrors the fulcro-rad-datomic / fulcro-rad-xtdb reference adapters.

#### Connection Options & Write-Path Wiring (Datalevin 1.0.0)
- **`:conn-opts` pass-through in `start-database!` / `start-databases`** — a
  database config may now supply a `:conn-opts` map of native Datalevin
  `get-conn` options, merged with the adapter-derived `:vector-domains`. This
  unlocks v1.0 features without adapter changes, notably:
  - `:auto-entity-time?` — Datalevin maintains `:db/created-at` / `:db/updated-at`
    (epoch-millis) per entity automatically. Free audit timestamps for RAD
    entities.
  - `:validate-data?` — runtime value-type validation on transact.
  - `:closed-schema?` — reject attributes not defined in the schema.
  - `:wal?`, `:search-domains`, `:kv-opts`, `:idoc-domains`, etc.
- **`::dlo/transact-options` is now honored** — passed as Datalevin `tx-meta`
  (the third arg to `transact!`) for every save transaction, so it is visible in
  tx reports and `listen!` callbacks (e.g. audit/user context).
- **`::dlo/transaction-timeout-ms` is now honored** — when present, each save
  transaction runs inside a `with-transaction` with that per-transaction
  `:timeout-ms` and is aborted if it exceeds it (v1.0). Preferred over the global
  `set-explicit-transaction-timeout!` for per-request control.
- **`::dlo/max-batch-size` is now honored** — auto-generated id-resolvers read it
  from the env to override the default (1000) batch limit (previously the option
  was declared but ignored).

### Changed

#### Pathom coupling (BREAKING for direct Pathom 3 users of the old plugin)
- **`pathom-plugin` is now a Pathom 2 `::p/wrap-parser` plugin** (was a Pathom 3
  `wrap-root-run` runner plugin). Pathom 3 apps should compose **`wrap-env`**
  (unchanged `(fn [env] env')`) into their processor's `env-middleware` instead —
  the idiomatic RAD Pathom 3 integration point.
- **`generate-resolvers` return shape changed** from native Pathom 3 records to
  Pathom-2-shape maps (see Added). Code that introspected `::pco/*` keys off the
  returned resolvers must read `:com.wsscode.pathom.connect/*` instead (or call
  `generate-resolvers-pathom3`).
- `pathom3` is no longer a transitive dependency; consumers bring their own
  Pathom (2 or 3).

#### Dependencies
- Clojure 1.12.4 → 1.12.5, Fulcro 3.9.3 → 3.9.5, Fulcro RAD 1.6.23 → 1.6.24,
  Guardrails 1.2.16 → 1.3.3, fulcro-spec 3.2.6 → 3.2.10 (test),
  deps-deploy 0.2.2 → 0.2.5 (release aliases). Full suite and lint green.
- clj-kondo: narrow `:unresolved-var` exclude for
  `datalevin.core/with-transaction` — Datalevin 1.0.0 re-exports it at runtime
  via `import-macro`, which clj-kondo cannot statically resolve. Surfaced by
  regenerating the derived lint cache; CI regeneration would hit it too.
- clj-kondo: `:output {:exclude-files [".clj-kondo/imports/"]}` — imports/ is
  derived third-party config regenerated from jars; guardrails 1.3.3 ships a
  hook file that trips `redundant-str-call` in editors otherwise.

#### Delete Middleware Parity
- `delete-entity!` now resolves the entity via `d/entid` (raw id for native-id
  attributes, `[pk id]` lookup ref otherwise) instead of a manual `d/q`, and
  handles **native-id deletes** correctly. Deleting a non-existent entity remains
  an idempotent no-op.

#### Removed dead option
- Removed `::dlo/max-retries` — it was declared but never implemented, and blind
  retries are inappropriate for embedded Datalevin (no transient network layer,
  and permanent failures like `:transact/attr-pred` must not be retried).

### Fixed

#### Batch id-resolvers now handle Pathom 2 single-input calls
- Generated id-resolvers assumed their input was always a vector of inputs (true
  for Pathom 3 and batched Pathom 2). Pathom 2 invokes a batch resolver with a
  **single input map** for a lone entity and expects a single map back; the old
  code iterated the map's entries and returned `[{}]`, which Pathom 2 rejected as
  an "Invalid resolve response". Resolvers now detect single-vs-batch input and
  respond in kind. Surfaced by the new real-parser integration tests.

#### Delete Transactions No Longer Swallow Failures
- `delete-entity!` now propagates transaction failures via `ex-info`
  (`{:ident :schema}`) instead of returning `{}` silently — mirroring the earlier
  save-path fix.

#### Adapter Parity Enhancements
- **Numeric coercion on save (`fix-numerics`)** — incoming values are now coerced
  to match the RAD attribute's declared numeric type before transacting
  (`:int`/`:long` → long, `:double`/`:float` → double, `:bigdec` → bigdec). This
  handles JavaScript clients that transmit integers where doubles are expected
  (and vice versa), avoiding `:db.type` mismatch errors. Non-numeric values and
  types pass through unchanged. (PLAN #9)
- **`::dlo/wrap-resolve` on identity resolvers** — the previously-declared
  `::dlo/wrap-resolve` attribute option is now covered by tests confirming it
  wraps the generated id-resolver for custom pre/post processing
  (`(fn [resolve]) => (fn [env input])`). (PLAN #5)
- **Schema verification (`schema-problems` / `verify-schema!`)** — new functions
  (re-exported from the `datalevin` facade) compare the RAD-derived expected
  schema against the live Datalevin schema of a connection. `schema-problems`
  returns `:missing` and `:mismatch` problem maps for the adapter-managed keys
  (`:db/valueType`, `:db/cardinality`, `:db/unique`); `verify-schema!` throws
  `ex-info` when problems exist and returns `true` otherwise. (PLAN #8)

#### Database-Side Validation & Post-Conditions (Datalevin 1.0.0)
- **NEW: Attribute predicates via native `:db.attr/preds`** — declare
  database-enforced validation on an attribute by adding `:db.attr/preds` to its
  `::dlo/attribute-schema`. The predicate(s) must be *qualified symbols* (the
  schema is persisted and resolved via `requiring-resolve`), invoked as
  `(pred value)` and must return strictly `true`; any other result aborts the
  write with a `:transact/attr-pred` error. This follows the Datalevin-native
  convention (and the Datomic adapter's "put native schema keys in
  `attribute-schema`" philosophy) rather than introducing a new option key.
  ```clojure
  (defattr email :account/email :string
    {::attr/identity?       true
     ::dlo/attribute-schema {:db.attr/preds 'my.app/valid-email?}})
  ```
- **NEW: Transaction post-conditions via `:db/ensure` + `::dlo/raw-txn`** — new
  env key `::dlo/raw-txn` and helper `append-to-raw-txn` (re-exported from the
  `datalevin` facade) let save middleware append native Datalevin transaction
  forms to a save. The primary use is `[:db/ensure pred & args]` post-conditions,
  which run against `db-after` and abort the transaction on any falsey result.
  ```clojure
  (dlo/append-to-raw-txn env [[:db/ensure `my.app/balance-non-negative? [:account/id id]]])
  ```
  Note: in a multi-schema save the forms are appended to each affected schema's
  transaction.
- Tests: full suite 35 tests, 217 assertions, 0 failures ✅ (Datalevin 1.0.0)

#### Vector Attribute Support (`:vec`)
- **NEW**: RAD attributes of type `:vec` now map to Datalevin's `:db.type/vec`
  and initialize a vector (HNSW) index at connection time.
- The vector index configuration (notably `:dimensions`) lives on the connection,
  not the schema. `start-database` now:
  - Strips `:db.vec/dimensions` from the generated schema map (it is not a valid
    Datalevin schema key).
  - Derives the vector domain name from the attribute's qualified key via
    `vec-attr-domain` (mirrors `datalevin.vector/attr-domain`: `/` → `_`).
  - Passes `:vector-domains {"domain" {:dimensions N}}` to `d/get-conn` so
    Datalevin can build the HNSW index.
- New public helper `vec-conn-opts` extracts these connection options from the
  `:vec` attributes for a schema.
- Verified against Datalevin 1.0.0's `init-vector-domains` (storage layer), which
  walks the schema for `:db.type/vec` attributes and merges per-domain config
  from the `:vector-domains` connection option.

### Changed

#### Dependency Upgrades
- **Datalevin `0.10.5` → `1.0.0`** — first stable Datalevin release. Notable
  upstream additions now available to build on: `:db/ensure` transaction special
  form (post-condition checks), `:db.attr/preds` attribute predicates,
  `datalog-kv`, and search `:limit`/`:offset`.
  - Note: composite tuple storage changed in 1.0.0 (existing tuple storage is
    rewritten by version migration; `:bytes` is no longer a valid tuple
    component). No impact on this adapter's current usage.
- **Fulcro `3.9.2` → `3.9.3`**
- **Fulcro RAD `1.6.20` → `1.6.23`**
- **encore `3.159.0` → `3.169.1`** — required by Datalevin 1.0.0's
  `nippy 3.7.0-beta1` (needs `encore >= 3.160.1`); resolves a version-conflict
  load error.
- Tests: 29 tests, 203 assertions, 0 failures ✅ (against Datalevin 1.0.0)

### Fixed

#### Save Transactions No Longer Swallow Failures
- **FIX**: `save-form!` previously caught transaction exceptions, logged, and
  returned `{}` — silently reporting success on a failed save. It now rethrows
  via `ex-info` with `{:schema :txn-data}` context so attribute-predicate
  (`:transact/attr-pred`), `:db/ensure` post-condition, and any other transaction
  failures propagate to the caller/client.

#### Enum Value Conversion in Save Operations (2024-11-28)
- **FIX**: Unqualified enum values are now correctly converted to their `:db/ident` format when saving
- Previously, saving an entity with raw enum values like `:admin` or `#{:read :write}` failed with:
  ```
  clojure.lang.ExceptionInfo: Nothing found for entity id :admin
  ```
- Root cause: Datalevin schemas define enums as `:db.type/ref`, which expects entity references, not raw keywords
- The `start-databases` code correctly creates `:db/ident` entities (e.g., `:account.role/admin`), but the save code wasn't converting raw values to match
- **Fix**: Added `convert-enum-value` function in `wrap-datalevin-save` that:
  - Checks if an attribute is an enum type
  - Converts unqualified keywords to their `:db/ident` format (e.g., `:admin` → `:account.role/admin`)
  - Preserves already-qualified keywords (e.g., `:status/active` stays as-is)
  - Handles sets for cardinality-many enums (e.g., `#{:read :write}` → `#{:account.permissions/read :account.permissions/write}`)
- Also handles retraction operations that need the same conversion
- Example transformation:
  ```clojure
  ;; Before (failed):
  ;; Delta: {:account/role {:before nil :after :admin}}
  ;; Transaction attempted: {:db/id [...] :account/role :admin}
  ;; Error: Nothing found for entity id :admin
  
  ;; After (works):
  ;; Delta: {:account/role {:before nil :after :admin}}
  ;; Transaction: {:db/id [...] :account/role :account.role/admin}
  ;; Success: Datalevin resolves :account.role/admin to the correct entity ref
  ```
- Tests: 29 tests, 203 assertions, 0 failures ✅

#### Native-ID All-IDs Resolver (2024-11-27)
- **FIX**: Native-ID all-ids resolver now correctly filters entities by type
- Previously, the all-ids resolver for native-id attributes (e.g., `:person/id`) used the query `[:find ?e :where [?e _ _]]` which returned ALL entities in the database
- This caused queries like `[:person/all]` to incorrectly return enums, other entity types, and all database entities instead of just person entities
- Root cause: The generic `[?e _ _]` pattern matches any entity with any attribute
- **Fix**: The resolver now finds a non-identity attribute from the same entity type (e.g., `:person/name` for `:person/id`) and queries for entities that have that specific attribute
- This ensures only entities of the correct type are returned
- If no non-identity attribute is found for an entity type, the resolver returns an empty list with a warning
- Updated `all-ids-resolver` function signature to accept `all-attributes` parameter for finding sample attributes
- Example transformation:
  ```clojure
  ;; Before (broken):
  ;; Query: [:person/all]
  ;; Returns: [{:person/id 1} {:person/id 2} ... {:person/id 47}]  ; All 47 entities including enums!
  
  ;; After (fixed):
  ;; Query: [:person/all]
  ;; Uses: [:find ?e :in $ ?attr :where [?e ?attr _]] with ?attr = :person/name
  ;; Returns: [{:person/id 1} {:person/id 2}]  ; Only 2 actual person entities
  ```
- Added comprehensive test suite in `native_id_all_resolver_test.clj`:
  - `native-id-all-resolver-excludes-other-entities` - verifies only correct entity type is returned
  - `native-id-all-resolver-returns-full-data` - tests integration with id-resolver
  - `native-id-all-resolver-with-no-attributes` - handles edge case of entity with only identity attribute
  - `native-id-all-resolver-query-uses-correct-attribute` - validates sample attribute selection
- Tests: 28 tests, 179 assertions, 0 failures ✅

### Added

#### Guardrails Integration (2024-11-27)
- **FEATURE**: Added `com.fulcrologic/guardrails` dependency for runtime validation
- All public functions now use `>defn` with specs for better error messages
- Function specs include:
  - `automatic-schema`: validates schema-name (keyword) and attributes (::attr/attributes)
  - `delta->txn`: validates env (map) and delta (map) inputs
  - `generate-resolvers`: validates attributes and schema inputs
  - `datalevin-result->pathom-result`: validates key->attribute map and EQL query
  - `id-resolver`: validates all-attributes, id-attribute, and output-attributes
- Specs provide runtime validation during development
- Improves debugging with clear error messages on invalid inputs
- Tests: 24 tests, 167 assertions, 0 failures ✅

#### Native ID Support (2024-11-27)
- **FEATURE**: Full support for native `:db/id` identity attributes (following Datomic pattern)
- New `::dlo/native-id?` option for identity attributes
- Native ID attributes use Datalevin's built-in `:db/id` instead of a domain-specific attribute
- Schema generation automatically skips native-id attributes (they use the built-in :db/id)
- Resolver generation correctly maps `:db/id` back to the identity attribute key in results
- Save/delete middleware handles native IDs using raw entity IDs instead of lookup refs
- Example usage:
  ```clojure
  (defattr id :person/id :long
    {::attr/identity? true
     ::dlo/native-id? true    ; ← Uses :db/id directly
     ::attr/schema :production})
  ```
- Benefits:
  - Better performance (no extra attribute lookup)
  - Compatibility with existing Datalevin databases using :db/id
  - Simpler migrations from Datomic applications
- Helper functions:
  - `native-id?`: checks if an attribute uses native ID
  - `pathom-query->datalevin-query`: converts Pathom EQL to Datalevin pull pattern
  - `datalevin-result->pathom-result`: maps :db/id back to identity key
- Tests: 24 tests, 167 assertions, 0 failures ✅

#### Wrap-Resolve Support (2024-11-27)
- **FEATURE**: Added `::dlo/wrap-resolve` option for identity attributes
- Allows wrapping resolver logic for custom input/output manipulation
- The wrap function receives the core resolver and must return a new resolver function
- Only affects auto-generated resolvers for the specific identity attribute
- Example usage:
  ```clojure
  (defattr id :account/id :uuid
    {::attr/identity? true
     ::dlo/wrap-resolve (fn [resolve]
                          (fn [env input]
                            ;; Pre-processing
                            (let [result (resolve env input)]
                              ;; Post-processing
                              result)))})
  ```
- Use cases:
  - Adding logging/metrics to specific resolvers
  - Implementing caching strategies
  - Adding custom authorization checks
  - Transforming inputs or outputs
- Tests: 24 tests, 167 assertions, 0 failures ✅

### Fixed

#### Enum Values in Resolvers (2024-11-27)
- **FIX**: Enum values are now correctly returned as keywords from generated resolvers
- Previously, enum values were returned as entity reference maps (e.g., `{:db/id 18}`) instead of their `:db/ident` keyword values
- This caused runtime errors in fulcro-rad when trying to call `name` on a map
- Implemented `replace-ref-types` function (following fulcro-rad-datomic pattern) that walks pull results and replaces enum entity references with their `:db/ident` values
- Works for both single-valued (`::attr/cardinality :one`) and multi-valued (`::attr/cardinality :many`) enum attributes
- ID resolver now includes the identity attribute in pull pattern to ensure it's present in results
- Tests: 19 tests, 139 assertions, 0 failures ✅
- Example transformation:
  ```clojure
  ;; Before (broken):
  {:account/id uuid
   :account/name "Alice"
   :account/role {:db/id 18}}  ; ← Error: can't call (name) on a map
  
  ;; After (fixed):
  {:account/id uuid
   :account/name "Alice" 
   :account/role :account.role/admin}  ; ← Correct: keyword value
  ```

### Added

#### Enum Support (2024-11-27)
- **FEATURE**: Full support for fulcro-rad's `:enum` attribute type
- Enum attributes are stored as `:db.type/ref` in Datalevin (following Datomic pattern)
- Enumerated values are stored as entities with `:db/ident`
- Supports both qualified and unqualified keywords for enum values:
  - Unqualified: `#{:admin :user}` → auto-generates `:account.role/admin`, `:account.role/user`
  - Qualified: `#{:status/active :status/inactive}` → uses as-is
- Supports both `:one` and `:many` cardinality for enum attributes
- Enum values are automatically transacted when starting a database
- Example usage:
  ```clojure
  (def account-role
    {::attr/qualified-key      :account/role
     ::attr/type               :enum
     ::attr/schema             :production
     ::attr/identities         #{:account/id}
     ::attr/enumerated-values  #{:admin :user :guest}
     ::attr/enumerated-labels  {:admin "Administrator"
                                :user  "Regular User"
                                :guest "Guest User"}})
  ```
- **Important**: When querying enum attributes with `d/pull`, use a pull pattern with `:db/ident`:
  ```clojure
  ;; For single-valued enum
  (d/pull db [:account/id {:account/role [:db/ident]}] [:account/id id])
  ;; Returns: {:account/id uuid :account/role {:db/ident :account.role/admin}}
  
  ;; For many-valued enum
  (d/pull db [:account/id {:account/permissions [:db/ident]}] [:account/id id])
  ;; Returns: {:account/id uuid :account/permissions [{:db/ident :read} {:db/ident :write}]}
  ```
- Tests: 18 tests, 129 assertions, 0 failures ✅

### Changed

#### Documentation Cleanup (2024-11-26)
- **REMOVED**: All references to metrics functionality from documentation
- The project never implemented metrics code, but documentation incorrectly claimed it did
- Removed from README.adoc:
  - "Built-in metrics and observability" feature bullet point
  - Entire "Metrics and Observability" API section (`get-metrics`, `reset-metrics!`)
- Removed from CHANGELOG.md:
  - "Metrics and Observability (TASK-011)" section
  - Mention of metrics recording in error handling section
- This aligns documentation with actual implementation (no metrics code exists)

### Fixed

#### Removed Eclipse Collections Conversion (2024-11-26)
- **FIX**: Removed unnecessary Eclipse Collections conversion that was causing data corruption
- **FIX**: Delete middleware now returns `{:tempids {}}` to match RAD expectations
- **BREAKING**: Removed `eclipse-collection->clojure` from public API
- Root cause analysis showed that Eclipse Collections serialization was not actually an issue:
  - Fulcro RAD form operations should return only `{:tempids {...}}`, not full transaction results
  - Query results don't need conversion - Datalevin's Eclipse Collections are Transit-compatible in practice
  - Previous conversion was corrupting data structures and database values
- Changes aligned with Datomic adapter pattern:
  - `save-form!` now returns only `{:tempids {...}}` map, not full transaction result
  - `delete-entity!` now returns `{}`, not transaction result
  - Both `wrap-datalevin-save` and `wrap-datalevin-delete` ensure `:tempids` key is present in result
  - Query helpers (`q`, `pull`, `pull-many`) return raw Datalevin results
  - `seed-database!` returns raw transaction result (not used in RAD operations)
- Removed all Eclipse Collection conversion logic:
  - Deleted `eclipse-collection?`, `convert-eclipse-collection`, and `eclipse-collection->clojure` functions
  - Removed conversion calls from all query and transaction operations
  - Simplified code and removed unnecessary `clojure.walk` dependency
- Tests: 15 tests, 95 assertions, 0 failures ✅
- Added comprehensive tempids tests to verify form operation contract:
  - `save-middleware-returns-tempids` - Tests save operations always include `:tempids`
  - `delete-middleware-returns-tempids` - Tests delete operations always include `:tempids`
  - Tests cover both standalone middleware and middleware with handlers
  - Tests verify new entities return tempid mappings
  - Tests verify existing entity updates return empty tempids
  - Tests verify deletes return empty tempids (even for non-existent entities)
- **Migration**: If you were using `eclipse-collection->clojure` directly, remove those calls. Query and form operations now work without conversion.

### Added

#### All-IDs Resolvers (2024-11-26)
- **FEATURE**: Re-added `all-ids-resolver` functionality that was removed during XTDB-style refactor
- `generate-resolvers` now creates two types of resolvers for each entity:
  - ID resolver: resolves entity data by ID (e.g., `:account/id` -> account data)
  - All-IDs resolver: resolves all entity IDs (e.g., `:all-accounts` -> `[{:account/id ...} ...]`)
- Example usage:
  ```clojure
  ;; Query for a specific account by ID
  [{:account/id some-uuid} [:account/name :account/email]]
  
  ;; Query for all account IDs
  [:account/all-accounts]  ;; Returns [{:account/id uuid-1} {:account/id uuid-2} ...]
  ```
- Naming convention: `:entity/all-entitys` (e.g., `:account/all-accounts`, `:item/all-items`)
- Tests: 13 tests, 76 assertions, 0 failures ✅

### Changed

#### Code Deduplication (2024-11-25)
- **Removed duplicate test utilities**: Consolidated test database helpers into `test_utils.clj`
  - Removed from `utilities.clj`: `empty-db-connection`, `create-temp-database!`, `with-temp-database`, `seed-database!`, `mock-resolver-env`
  - Moved to `test_utils.clj`: `seed-database!`, `mock-resolver-env`
  - Test utilities are now exclusively in the test namespace where they belong
- **Simplified `utilities.clj`**: Now only contains production query helpers (`q`, `pull`, `pull-many`)
  - Removed redundant re-exports of functions from other namespaces
  - Users should import from main `datalevin.clj` namespace for all public API functions
- **Fixed `datalevin.clj` re-exports**: Now directly re-exports from source namespaces
  - `get-by-ids` from `generate-resolvers`
  - `delta->txn`, `keys-in-delta`, `schemas-for-delta`, `save-form!` from `wrap-datalevin-save`
  - Removed incorrect indirection through `utilities.clj`
- **Fixed bug in `start-database!`**: Now correctly uses the `:schema` parameter instead of ignoring it
  - Previously always used `:default` when calling `automatic-schema`
  - Now properly passes through the schema name from config
- **Added clj-kondo configuration**: Created hooks for proper linting of test macros
  - `with-test-conn` and `with-test-conn-attrs` now lint correctly
  - Zero linting errors in codebase ✅
- **Removed unnecessary files**: Cleaned up temporary documentation
  - Removed `PLAN.md` (was empty, not being used)
  - Removed `DEDUPLICATION_SUMMARY.md` (changes now documented in CHANGELOG)
- **Tests**: All 13 tests still passing with 69 assertions ✅

#### Major Refactoring - XTDB-Style API (BREAKING CHANGES)
- **BREAKING**: Complete API alignment with fulcro-rad-xtdb adapter
- **BREAKING**: Restructured codebase to follow modular pattern from fulcro-rad-xtdb example
- Split monolithic `datalevin.clj` (~900 lines) into focused modules:
  - `datalevin/start_databases.clj` - Database lifecycle and schema generation
  - `datalevin/pathom_plugin.clj` - Pathom3 plugin for database access
  - `datalevin/generate_resolvers.clj` - Automatic resolver generation
  - `datalevin/wrap_datalevin_save.clj` - Save form middleware
  - `datalevin/wrap_datalevin_delete.clj` - Delete form middleware
  - `datalevin/utilities.clj` - Query helpers, delta processing, and test utilities
- Main `datalevin.clj` now serves as a clean API facade, re-exporting all public functions
- All tests continue to pass (31 tests, 172 assertions, 0 failures) ✅
- Improved code organization and maintainability following established patterns

#### API Changes (Breaking)

**generate-resolvers:**
- **BREAKING**: Schema parameter now required (was optional)
- Old: `(generate-resolvers attributes)` or `(generate-resolvers attributes schema)`
- New: `(generate-resolvers attributes schema)` - schema required
- Matches XTDB adapter exactly

**Middleware:**
- **BREAKING**: Simplified from 3-level to 2-level pattern (matches XTDB)
- Old: `((wrap-datalevin-save {:default-schema :main}) handler)`
- New: `(wrap-datalevin-save handler)` or `(wrap-datalevin-save)` for terminal
- Schema is now determined from `::attr/schema` in attributes
- No longer accepts options map

**Removed Features:**
- **BREAKING**: Removed `all-ids-resolver` (not in XTDB)
- **BREAKING**: Removed `ref-resolvers` (not in XTDB)
- **BREAKING**: Removed `id-resolver` from public API (internal only)
- Removed explicit validation functions (internal implementation details)

#### Code Organization
- Simplified PLAN.md to minimal structure - removed completed 794-line production stability plan
- Consolidated test suite from 4 separate test files into single comprehensive `datalevin_test.clj`
- Removed obsolete test files: `datalevin_core_test.clj`, `datalevin_middleware_test.clj`, `datalevin_new_entity_test.clj`, `datalevin_save_test.clj`
- Maintained full test coverage with improved organization and reduced duplication

#### Documentation
- Enhanced AGENTS.md with lint command documentation
- Clarified file creation guidelines to emphasize using single files
- Added inline documentation for all public functions
- Added note about `with-temp-database` macro location for proper imports

#### Core Functionality
- Refactored `save-form!` function to be testable independently of middleware context
- Improved function docstrings with clear parameter and return value documentation
- Simplified `wrap-datalevin-save` middleware to delegate to `save-form!`
- Enhanced error handling with validation and helpful error messages

#### Test Utilities
- Added clj-kondo configuration to `test_utils.clj` for proper macro linting
- `with-temp-database` macro now available via: 
  `(require '[us.whitford.fulcro.rad.database-adapters.datalevin.utilities :refer [with-temp-database]])`

## [0.1.0-beta1] - 2024-11-25

### Overview

First beta release of fulcro-rad-datalevin, a Datalevin database adapter for Fulcro RAD. This release includes full CRUD functionality, comprehensive error handling, and production-ready features.

**Test Coverage**: 56 tests, 269 assertions, 0 failures ✅

### Changed

#### Test Suite Organization
- Consolidated test suite into 4 well-organized files:
  - `test_utils.clj` - Shared utilities, fixtures, and test data
  - `datalevin_core_test.clj` - Core functionality tests (23 tests)
  - `datalevin_save_test.clj` - Save/delete middleware tests (15 tests)
  - `datalevin_new_entity_test.clj` - New entity creation tests (9 tests)
  - `datalevin_middleware_test.clj` - Middleware composition tests (9 tests)
- Removed redundant test files and duplicate test coverage
- All tempid handling tests clearly marked as CRITICAL
- Total: 56 tests with 269 assertions, all passing ✅

### Fixed

#### Tempids Resolution (TASK-014)
- **CRITICAL FIX**: Save middleware now always includes `:tempids` in the result map, even when saving existing entities
- Previously, `:tempids` was only added when there were actual tempid mappings, causing Pathom3 "attribute-unreachable" errors when RAD tried to query for `:tempids` after updating existing entities
- This resolves the error: "EQL query for :tempids cannot be resolved"
- All save operations now return `{:tempids {}}` at minimum, allowing RAD's EQL queries to work correctly
- Added comprehensive test suite in `datalevin_tempids_test.clj` to prevent regression

### Added

#### Error Handling (TASK-001)
- Transaction operations now include proper error handling with context
- Errors include schema name and transaction count for debugging

#### Connection Validation (TASK-002)
- Missing database connections now throw informative exceptions instead of silently failing
- Error messages include list of available schemas to help diagnose configuration issues
- Both save and delete middleware validate connections before proceeding

#### Tempid Collision Prevention (TASK-003)
- Replaced hash-based tempid generation with atomic counter to prevent collisions
- Guarantees unique transaction IDs even under high concurrency
- Tempid mappings are now tracked consistently within transaction context

#### Resource Management (TASK-004)
- New `create-temp-database!` function returns cleanup function to prevent resource leaks
- New `with-temp-database` macro automatically cleans up temporary databases
- `empty-db-connection` now includes warning about cleanup responsibility
- Cleanup functions properly close connections and remove temporary directories

#### Query Safety Limits (TASK-005)
- Batch queries now enforce configurable maximum size (default: 1000)
- `*max-batch-size*` dynamic var allows customization per-context
- Warning logged for large batches (>100 entities)
- Prevents potential OOM errors from unbounded queries

#### Schema Validation (TASK-006)
- Improved schema error handling distinguishes between:
  - Schema already exists (debug level log)
  - Incompatible schema changes (throws exception with context)
  - Other schema errors (throws exception with details)
- Better error messages for schema migration issues

#### Input Validation (TASK-009)
- Delta structure is validated before processing
- Clear error messages for malformed input
- Validates ident structure, attribute keys, and before/after presence

#### Consistent Database Snapshots (TASK-010)
- Pathom plugin now takes snapshots at request root level
- All resolvers in a single request see consistent database state
- Prevents inconsistent reads during concurrent writes

#### Configuration Options
- New `::dlo/transaction-timeout-ms` option key
- New `::dlo/max-retries` option key
- New `::dlo/max-batch-size` option key
- Dynamic vars for runtime configuration:
  - `*max-batch-size*`
  - `*transaction-timeout-ms*`
  - `*max-retries*`

### Changed

#### Reference Resolvers (TASK-008)
- Fixed logic to correctly find target identity attributes
- Warns when reference target is not found instead of silently failing
- Only generates resolvers when target is an identity attribute

#### Code Cleanup (TASK-012)
- Removed unused `clojure.set` import
- Improved docstrings throughout

### Breaking Changes

#### `empty-db-connection`
- Still returns just the connection for backwards compatibility
- Now logs warning about cleanup responsibility
- Users should migrate to `create-temp-database!` or `with-temp-database`

#### Missing Connection Handling
- Previously: silent failure with error log
- Now: throws `ex-info` with `:schema` and `:available-schemas` in ex-data
- **Migration**: Ensure all schemas used have corresponding connections configured

#### Batch Size Limits
- Queries with >1000 IDs will now throw an exception
- **Migration**: Use `binding` to increase `*max-batch-size*` if needed, or paginate large queries

### Migration Guide

#### From Previous Version

1. **Connection Configuration**: Ensure all schemas referenced in your attributes have corresponding connections in your configuration. Missing connections now throw exceptions instead of silently failing.

2. **Large Batch Queries**: If you query more than 1000 entities at once, either:
   - Paginate your queries
   - Increase the limit: `(binding [dl/*max-batch-size* 5000] ...)`

3. **Temporary Databases**: Update test code to properly clean up:
   ```clojure
   ;; Old (leaks resources)
   (let [conn (dl/empty-db-connection :test attrs)]
     ...)
   
   ;; New (automatic cleanup)
   (dl/with-temp-database [conn :test attrs]
     ...)
   
   ;; Or manual cleanup
   (let [{:keys [conn cleanup!]} (dl/create-temp-database! :test attrs)]
     (try
       ...
       (finally
         (cleanup!))))
   ```

4. **Error Handling**: Transaction and connection errors are now thrown as `ex-info` exceptions. Wrap operations in try-catch if you need custom error handling:
   ```clojure
   (try
     ((middleware handler) env)
     (catch clojure.lang.ExceptionInfo e
       (log/error "Database operation failed:" (ex-data e))))
   ```
