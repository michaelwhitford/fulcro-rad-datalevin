# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
