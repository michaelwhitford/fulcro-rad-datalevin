# Improvement Plan: fulcro-rad-datalevin

Based on detailed comparison with `fulcro-rad-datomic` and `fulcro-rad-xtdb` adapters.

## Priority Legend
- 🔴 HIGH - Critical for production/parity
- 🟡 MEDIUM - Valuable enhancements  
- 🟢 LOW - Nice to have / future

---

## 🔴 HIGH PRIORITY

### 1. Native ID Support (`native-id?`)
**Source:** Datomic adapter  
**Status:** ✅ COMPLETED (2024-11-27)

The Datomic adapter supports `native-id?` option allowing attributes to map directly to `:db/id` instead of a domain-specific UUID. This is important for:
- Performance (no extra attribute lookup)
- Compatibility with existing Datalevin databases
- Simpler migrations

**Implementation:**
- [x] Implement `native-ident?` helper function
- [x] Update `failsafe-id` to handle native IDs
- [x] Update schema generation to skip native ID attributes
- [x] Update resolver generation to map `:db/id` back to identity key
- [x] Add tests for native ID entities
- [x] Fix all-ids-resolver to correctly filter by entity type (Bug fix 2024-11-27)

**Files modified:**
- `start_databases.clj` - schema generation
- `generate_resolvers.clj` - ID mapping in results, all-ids resolver fix
- `wrap_datalevin_save.clj` - transaction ID handling

---

### 2. Guardrails / Spec Support
**Source:** Both Datomic and XTDB adapters  
**Status:** Not implemented

Both reference adapters use `com.fulcrologic.guardrails.core` for function specs and validation. This provides:
- Runtime validation during development
- Better error messages
- Documentation via specs

**Implementation:**
- [ ] Add guardrails dependency
- [ ] Add `>defn` specs to public functions:
  - `automatic-schema`
  - `delta->txn`
  - `generate-resolvers`
  - `id-resolver`
- [ ] Add specs for key data structures

**Example from Datomic:**
```clojure
(>defn automatic-schema
  [attributes schema-name]
  [::attr/attributes keyword? => vector?]
  ...)
```

---

### 3. Query Result Pruning (Minimal Pull)
**Source:** Datomic adapter  
**Status:** Not implemented

Datomic adapter supports pruning pull queries to only fetch what the client requested, improving performance.

**Features:**
- `*minimal-pull?*` dynamic var
- `generate-minimal-pull?` attribute option
- `prune-query` / `prune-query-ast` functions
- Integration with Pathom's client query AST

**Implementation:**
- [ ] Add `prune-query-ast` function
- [ ] Add `env->client-query-ast` to extract Pathom query
- [ ] Add `generate-minimal-pull?` option
- [ ] Add `resolver-cache?` option (for controlling Pathom caching)
- [ ] Integrate into `id-resolver`

---

### 4. Raw Transaction Support
**Source:** Datomic adapter  
**Status:** ✅ COMPLETED (Datalevin 1.0.0)

The `raw-txn` feature allows middleware to append additional transaction data.
With Datalevin 1.0.0, the primary use is `:db/ensure` transaction post-conditions
(the predicate runs against `db-after` and aborts the txn on any falsey result).

**Use cases:**
- Transaction metadata (`:db/ensure`, audit info)
- Pre/post-condition checks via `:db/ensure`
- Custom enrichment

**Implementation:**
- [x] Add `::dlo/raw-txn` option key to `datalevin_options.cljc`
- [x] Add `append-to-raw-txn` helper function (re-exported from `datalevin` facade)
- [x] Update save middleware (`save-form!`) to append raw-txn per affected schema
- [x] Tests for `:db/ensure` pass/abort via `save-form!`

**Note:** rather than routing through `delta->txn`, raw-txn forms are appended in
`save-form!` (they are transaction-level, not delta-derived).

---

### 5. Wrap-Resolve Support
**Source:** Datomic adapter  
**Status:** ✅ COMPLETED

Allows wrapping resolver logic for custom input/output manipulation.

```clojure
(defattr id :account/id :uuid
  {::attr/identity? true
   ::do/wrap-resolve (fn [resolve]
                       (fn [env input]
                         ;; Pre-processing
                         (let [result (resolve env input)]
                           ;; Post-processing
                           result)))})
```

**Implementation:**
- [x] Add `::dlo/wrap-resolve` option to `datalevin_options.cljc`
- [x] `id-resolver` applies wrap-resolve to the generated resolver
- [x] Tests verifying pre/post-processing wrapping

---

## 🟡 MEDIUM PRIORITY

### 6. Pathom 2 Support (Dual Pathom)
**Source:** Datomic adapter  
**Status:** Only Pathom 3 supported

Datomic supports both Pathom 2 and Pathom 3 via separate resolver makers.

**Implementation:**
- [ ] Add `make-pathom2-resolver` function
- [ ] Add `generate-resolvers-pathom2` (or option flag)
- [ ] Update pathom-plugin for Pathom 2

---

### 7. Transform Support for Resolvers
**Source:** Datomic adapter  
**Status:** Not implemented

The `::pc/transform` attribute option allows transforming resolver config.

```clojure
(defattr id ::id :uuid
  {::pc/transform (fn [resolver]
                    (assoc resolver ::custom-flag true))})
```

**Implementation:**
- [ ] Pass transform through to `pco/resolver`
- [ ] Document transform option

---

### 8. Schema Validation / Verification
**Source:** Datomic adapter  
**Status:** Partial — attribute predicates done; schema-compatibility verify pending

Datomic has `verify-schema!` and `schema-problems` for validating schema compatibility.

Datalevin 1.0.0 adds **attribute predicates** (`:db.attr/preds`) for database-side
value validation, now supported via `::dlo/attribute-schema` pass-through (see
CHANGELOG "Database-Side Validation & Post-Conditions"). Save failures now
propagate instead of being swallowed.

**Implementation:**
- [x] Attribute predicates via native `:db.attr/preds` (`::dlo/attribute-schema`)
- [x] Propagate save transaction failures (no longer swallowed)
- [x] Add `schema-problems` function to check db vs attributes
- [x] Add `verify-schema!` that throws on problems (re-exported from facade)
- [ ] Optionally call verify after schema updates in `start-database!`

---

### 9. Fix Numerics Helper
**Source:** Datomic adapter  
**Status:** ✅ COMPLETED

JavaScript can send integers when floats are expected. The `fix-numerics` function handles type coercion.

```clojure
(defn fix-numerics [{::attr/keys [type]} v]
  (case type
    :double (double v)
    :float (double v)
    :int (long v)
    :long (long v)
    v))
```

**Implementation:**
- [x] Add `fix-numerics` to wrap_datalevin_save.clj (also handles `:bigdec`)
- [x] Apply in the value conversion path of `delta-entry->txn`
- [x] Tests for `:double`←int and `:long`←double coercion (delta + round-trip)

---

### 10. Improved Database Refresh
**Source:** Datomic adapter  
**Status:** Not implemented

`refresh-current-dbs!` updates database atoms after mutations so subsequent resolvers see fresh data.

**Implementation:**
- [ ] Add `refresh-current-dbs!` function
- [ ] Document usage in mutations

---

### 11. Empty/Mock Database Test Utilities
**Source:** Datomic adapter  
**Status:** Partial

Datomic has sophisticated test database management:
- `pristine-db-connection` - reusable base
- `empty-db-connection` - memoized schema'd connections
- `reset-migrated-dbs!` - cache invalidation
- `mock-resolver-env` - test env helper

**Current datalevin:** Has `with-temp-database` macro but lacks memoization.

**Implementation:**
- [ ] Add memoized schema database cache
- [ ] Add `reset-test-schema!` function
- [ ] Improve test performance via reuse

---

### 12. Indexed Access / Tuple Scanning
**Source:** Datomic adapter (indexed_access.clj)  
**Status:** Not implemented

Advanced feature for efficient range queries on tuples/indexes.

**Features:**
- `tuple-index-scan` for paginated queries
- `search-parameters->range+filters`
- `generate-tuple-resolver`

**Implementation:**
- [ ] Evaluate Datalevin's index access capabilities
- [ ] Implement if supported

---

## 🟢 LOW PRIORITY

### 13. Logging Blacklist
**Source:** Datomic adapter  
**Status:** Not applicable

Datomic has `suggested-logging-blacklist` for noisy namespaces. May not be needed for Datalevin.

---

### 14. Transaction Functions Support
**Source:** Both adapters  
**Status:** Not implemented

Both Datomic and XTDB support transaction functions for complex atomic operations.

**XTDB example:**
```clojure
(def transaction-functions
  {::delta-update '(fn [ctx [id {:keys [before after]}]]
                     ...)})
```

**Implementation:**
- [ ] Research Datalevin transaction function support
- [ ] Add `transaction-functions` option if supported

---

### 15. Multiple Driver Support
**Source:** Datomic adapter  
**Status:** N/A

Datomic supports multiple backends (PostgreSQL, MySQL, mem, etc.). Datalevin is file-based only, so this doesn't apply.

---

### 16. Datomock Integration
**Source:** Datomic adapter  
**Status:** Not applicable

Datomic uses Datomock for test forking. Datalevin doesn't have equivalent, but temp directories work.

---

## Implementation Order

### Phase 1: Core Parity (Week 1-2)
1. ✅ Native ID Support
2. ✅ Guardrails Integration
3. ✅ Raw Transaction Support
4. ✅ Wrap-Resolve Support

### Phase 2: Performance (Week 2-3)
5. Query Result Pruning
6. Fix Numerics
7. Database Refresh

### Phase 3: Developer Experience (Week 3-4)
8. Schema Validation
9. Improved Test Utilities
10. Pathom 2 Support (if needed)

### Phase 4: Advanced Features (Future)
11. Indexed Access (if supported)
12. Transaction Functions
13. Transform Support

---

## Files Reference

### Datomic Adapter Structure
```
datomic_common.clj      - Shared logic (most features here)
datomic.clj             - On-prem specific
datomic_cloud.clj       - Cloud specific
datomic_options.cljc    - Configuration keys
indexed_access.clj      - Tuple scanning
```

### XTDB Adapter Structure
```
xtdb.clj                - Main API facade
xtdb/generate_resolvers.clj
xtdb/pathom_plugin.clj
xtdb/start_databases.clj
xtdb/wrap_xtdb_save.clj
xtdb/wrap_xtdb_delete.clj
xtdb_options.cljc
```

### Current Datalevin Structure (Good!)
```
datalevin.clj           - Main API facade ✅
datalevin/generate_resolvers.clj ✅
datalevin/pathom_plugin.clj ✅
datalevin/start_databases.clj ✅
datalevin/wrap_datalevin_save.clj ✅
datalevin/wrap_datalevin_delete.clj ✅
datalevin/utilities.clj ✅
datalevin_options.cljc  ✅
```

---

## Notes

- The XTDB adapter is simpler because XTDB is document-oriented
- Datalevin is Datalog-based like Datomic, so most Datomic patterns apply
- Priority should be on features that affect correctness, then performance
- Enum support was recently added and is working well

## Datalevin 1.0.0 Wiring (completed)

Upgraded to Datalevin 1.0.0 and surfaced its capabilities through the adapter:
- ✅ `:vec` vector attributes → HNSW index (`:vector-domains`)
- ✅ Attribute predicates via native `:db.attr/preds`
- ✅ Transaction post-conditions via `:db/ensure` + `::dlo/raw-txn`
- ✅ `schema-problems` / `verify-schema!`
- ✅ `fix-numerics` value coercion (#9)
- ✅ `:conn-opts` pass-through in `start-database!` — enables `:auto-entity-time?`,
  `:validate-data?`, `:closed-schema?`, `:wal?`, `:search-domains`, etc.
- ✅ Wired previously-dead options: `::dlo/transact-options` (tx-meta),
  `::dlo/transaction-timeout-ms` (per-txn `with-transaction` timeout),
  `::dlo/max-batch-size`; removed unimplementable `::dlo/max-retries`
- ✅ Delete middleware parity: `d/entid` lookup, native-id deletes, failure
  propagation

Remaining v1.0 opportunities (future): full-text search (`:db/fulltext`,
`:search-domains`, `:limit`/`:offset`), bulk load via `transact-async` /
`init-db` / `fill-db`, and a tuple round-trip test under v1.0 storage.
