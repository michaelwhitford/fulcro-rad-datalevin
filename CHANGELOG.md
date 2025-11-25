# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

#### Code Organization
- Simplified PLAN.md to minimal structure - removed completed 794-line production stability plan
- Consolidated test suite from 4 separate test files into single comprehensive `datalevin_test.clj`
- Removed obsolete test files: `datalevin_core_test.clj`, `datalevin_middleware_test.clj`, `datalevin_new_entity_test.clj`, `datalevin_save_test.clj`
- Maintained full test coverage with improved organization and reduced duplication

#### Documentation
- Enhanced AGENTS.md with lint command documentation
- Clarified file creation guidelines to emphasize using single files

#### Core Functionality
- Refactored `save-form!` function to be testable independently of middleware context
- Improved function docstrings with clear parameter and return value documentation
- Simplified `wrap-datalevin-save` middleware to delegate to `save-form!`
- Enhanced error handling with validation and helpful error messages

#### Test Utilities
- Added clj-kondo configuration to `test_utils.clj` for proper macro linting

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
- Metrics are recorded for all transactions (success and failure)

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

#### Metrics and Observability (TASK-011)
- New `metrics` atom tracks database operations
- `get-metrics` function returns current metrics
- `reset-metrics!` function for testing
- Metrics include:
  - Transaction count
  - Transaction errors
  - Total transaction time (milliseconds)
  - Query count

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
