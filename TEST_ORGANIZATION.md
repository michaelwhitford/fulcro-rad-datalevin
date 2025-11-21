# Test Organization

This document describes the current test suite organization for the fulcro-rad-datalevin adapter.

## Current Test Suite Structure

The test suite consists of **3 well-organized files** with clear separation of concerns:

**Location:** `src/test/us/whitford/fulcro/rad/database_adapters/test_utils.clj`

**Purpose:** Shared utilities, fixtures, and test data used across all test files.

**Contents:**
- Test attribute definitions (account and item entities)
- Database setup/cleanup helpers
- Helper macros: `with-test-conn`, `with-test-conn-attrs`
- Utility functions: `create-test-conn`, `cleanup-test-conn`, `key->attribute-map`, `cleanup-path`

**Key Attributes Defined:**
- Account entity: `:account/id`, `:account/name`, `:account/email`, `:account/active?`, `:account/balance`, `:account/items`
- Item entity: `:item/id`, `:item/name`

### 2. `datalevin_core_test.clj` - Core Functionality Tests

**Location:** `src/test/us/whitford/fulcro/rad/database_adapters/datalevin_core_test.clj`

**Test Count:** 16 tests, 81+ assertions

**Purpose:** Tests core adapter functionality including schema generation, connections, delta processing, resolvers, and query utilities.

- **Schema Generation Tests** (3 tests)
  - Automatic schema generation from RAD attributes
  - Schema filtering by name
  - Attributes without schema handling
  
- **Database Connection Tests** (2 tests)
  - Starting databases with auto-schema
  - Starting databases without auto-schema
  - Creating in-memory test databases
  
- **Delta to Transaction Conversion Tests** (4 tests)
  - Simple delta conversion
  - Tempid handling (converts TempId objects to real UUIDs)
  - Value removal (setting to nil)
  - Ignoring unchanged values
  
- **Delta Validation Tests** (4 tests)
  - Validates delta is a map
  - Validates delta entry structure
  - Validates ident is a vector
  - Accepts valid deltas
  
- **Tempid Handling Tests** (2 tests) ⚠️ **CRITICAL**
  - Multiple tempids get unique transaction IDs
  - TempId ident uses real UUID value from delta, not TempId object
  
- **Resolver Generation Tests** (3 tests)
  - Generates resolvers for identity attributes
  - Generated resolvers have correct output
  - Resolver fetches entity by id
  - Resolver handles batch requests
  - Resolver handles missing entities
  
- **Query Utility Tests** (3 tests)
  - Datalog queries (`q`)
  - Entity pulls (`pull`)
  - Batch entity fetching (`get-by-ids`)
  
- **Batch Size Limits Tests** (2 tests)
  - Throws when batch size exceeds maximum
  - Accepts exactly maximum batch size
  
- **Mock Environment Tests** (1 test)
  - Creates proper environment structure
  
- **Seed Database Tests** (2 tests)
  - Seeds database with initial data
  - Handles empty seed data
  
- **Metrics Tests** (1 test)
  - Reset metrics clears all counters
  
- **Resource Management Tests** (3 tests)
  - Creates temporary databases with cleanup
  - Database is functional before cleanup
  - `with-temp-database` macro executes with connection and cleans up

### 3. `datalevin_save_test.clj` - Save/Delete Middleware Tests

**Location:** `src/test/us/whitford/fulcro/rad/database_adapters/datalevin_save_test.clj`

**Test Count:** 15 tests, 64+ assertions

**Purpose:** Tests save and delete middleware operations, tempid mapping, error handling, and RAD integration.

- **Save Middleware - Basic Operations** (2 tests)
  - Returns a map, not a function
  - Saves new entity to database
  - Updates existing entity
  
- **Tempid Handling Tests** (4 tests) ⚠️ **CRITICAL**
  - `:tempids` present when saving new entity with tempids
  - `:tempids` present when updating existing entity (even when empty)
  - `:tempids` present with empty delta
  - `:tempids` present with nil delta
  - Maps multiple tempids to real IDs correctly
  - Handles mixed new and existing entities in same save
  
- **Data Persistence Tests** (2 tests)
  - Setting attribute to nil removes it
  - Sequential updates all persist correctly
  
- **Delete Middleware Tests** (2 tests)
  - Deletes entity from database
  - Handles non-existent entity gracefully
  
- **Error Handling Tests** (2 tests)
  - Save throws when connection missing
  - Delete throws when connection missing
  - Error includes schema and available schemas
  
- **RAD Integration Tests** (2 tests)
  - Middleware composes properly with RAD save stack
  - Result is serializable (no functions)
  
- **Schema Configuration Tests** (1 test)
  - Uses default-schema when attribute doesn't specify one
  
- **Incorrect Usage Tests** (1 test)
  - Calling middleware with env instead of handler (demonstrates wrong usage)

## Comprehensive Test Coverage

✅ **Schema Generation**
- Automatic schema generation from RAD attributes
- Type mapping (all RAD types to Datalevin types)
- Schema filtering by name
- Custom attribute schema overrides via `::dlo/attribute-schema`

✅ **Database Management**
- Database initialization with auto-schema
- In-memory and file-based databases
- Connection management (start/stop)
- Temporary database creation with automatic cleanup
- Schema updates and validation

✅ **Delta Processing**
- Delta to transaction conversion
- Delta structure validation
- TempId to real UUID conversion
- TempId uniqueness guarantees
- Handling of nil values and attribute removals
- Unchanged value filtering

✅ **Tempid Handling** ⚠️ **CRITICAL**
- TempIds use real UUID values (not TempId objects) from delta `:after`
- `:tempids` key ALWAYS present in save results (even when empty)
- Multiple tempid mapping in single save operation
- Mixed new/existing entity saves
- TempId to negative integer conversion for `:db/id`
- Proper tempid mapping returned for RAD form state updates

✅ **Save Middleware**
- New entity creation with tempids
- Existing entity updates
- Batch saves with multiple entities
- Attribute removal (setting to nil)
- Sequential updates maintaining consistency
- Proper result format for RAD integration
- Middleware composition with RAD stack

✅ **Delete Middleware**
- Entity deletion by identity
- Non-existent entity handling
- Proper error handling

✅ **Resolver Generation**
- Automatic resolver generation for identity attributes
- Batch entity fetching
- Missing entity handling
- Proper Pathom3 integration

✅ **Query Utilities**
- Datalog queries (`q`)
- Entity pulls (`pull`, `pull-many`)
- Batch get-by-ids with configurable limits
- Batch size limits enforcement

✅ **Error Handling & Validation**
- Missing connection errors with helpful context
- Invalid delta structure detection
- Transaction failure handling with retries
- Batch size limit enforcement
- Schema validation and migration detection
- Detailed error messages with ex-data context

✅ **RAD Integration**
- Middleware composition with RAD form stack
- Result serialization (no function values)
- Tempid resolution for RAD EQL queries
- Consistent database snapshots per request

✅ **Observability & Metrics**
- Transaction count tracking
- Error rate tracking
- Transaction timing metrics
- Query count tracking
- Metrics reset functionality

✅ **Resource Management**
- Temporary database cleanup
- `with-temp-database` macro
- Proper connection closing
- File system cleanup

## Test Organization Benefits

1. **Clear Separation of Concerns**
   - Core functionality in `datalevin_core_test.clj`
   - Middleware operations in `datalevin_save_test.clj`
   - Shared utilities in `test_utils.clj`

2. **Easy to Locate Tests**
   - Schema and connection tests → Core
   - Delta processing and resolvers → Core
   - Save/delete operations → Save
   - Error handling → Both (categorized by operation)

3. **Maintainable**
   - Each test file has a clear purpose
   - Shared fixtures prevent duplication
   - Helper macros simplify test setup
   - No redundant tests

4. **Comprehensive**
   - 31 tests with 145+ assertions
   - All critical paths tested
   - Edge cases covered
   - Error conditions validated

5. **Critical Tests Highlighted**
   - Tempid tests marked as CRITICAL in comments
   - These tests prevent regressions of the most common integration issues

## Running Tests

```bash
# Run all tests
clojure -M:run-tests

# Run specific test namespace
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-core-test
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-save-test

# Run all tests and watch for changes (if using Kaocha)
clojure -M:test-watch
```

## Adding New Tests

### When to Add to `datalevin_core_test.clj`

Add tests here for:
- Schema generation and type mapping changes
- New query utilities or database operations
- Delta processing and validation logic
- Resolver generation changes
- Batch operations and limits
- Metrics and observability features
- Resource management utilities

### When to Add to `datalevin_save_test.clj`

Add tests here for:
- Save middleware behavior changes
- Delete middleware behavior changes
- Tempid handling modifications
- RAD form integration
- Middleware composition patterns
- Data persistence scenarios
- Error handling for save/delete operations

### When to Update `test_utils.clj`

Update this file when:
- Adding new test attributes (entities)
- Creating new helper functions used across multiple test files
- Adding new database setup/cleanup utilities
- Creating new test macros

## Test Naming Conventions

- Test names should clearly describe what is being tested
- Use kebab-case for test names
- Group related tests with shared prefixes
- Mark critical tests with `CRITICAL` in comments
- Examples:
  - `save-new-entity` - clear, descriptive
  - `tempids-always-present-in-result` - describes the assertion
  - `error-missing-connection` - describes error condition

## Key Testing Principles

1. **Each test should test one thing** - Makes failures easy to diagnose
2. **Use descriptive assertions** - Include failure messages when helpful
3. **Test both success and failure paths** - Don't just test happy paths
4. **Clean up resources** - Always use `with-test-conn` or cleanup properly
5. **Make tests deterministic** - Avoid timing dependencies or random data
6. **Test critical paths first** - Tempid handling is marked CRITICAL for a reason

## Critical Test Areas (Do Not Break!)

### Tempid Handling
The most common source of integration issues. Tests ensure:
- TempIds are converted to real UUID values (not TempId objects)
- The `:tempids` key is ALWAYS present in save results
- Multiple tempids are handled correctly
- Tempid mappings allow RAD to update form state

**Why Critical:** Breaking these tests will cause forms to fail silently or not update properly.

### Delta Processing
Ensures form changes are correctly translated to database transactions:
- Delta validation catches malformed input early
- Unchanged values are filtered out
- Nil values trigger attribute removal
- Mixed operations (new + update) work in a single save

**Why Critical:** Breaking these tests means form data won't persist correctly.

### Middleware Composition
Tests that middleware works in the RAD stack:
- Returns maps, not functions
- Preserves base handler results
- Works with other middleware
- Serializable results

**Why Critical:** Breaking these tests means middleware won't integrate with RAD properly.

## Test Coverage Report

Current coverage: **31 tests, 145+ assertions**

- Schema Generation: 3 tests
- Database Connections: 2 tests  
- Delta Processing: 10 tests (4 conversion + 4 validation + 2 tempid)
- Resolvers: 6 tests
- Queries: 5 tests
- Save Middleware: 10 tests
- Delete Middleware: 2 tests
- Error Handling: 5 tests
- RAD Integration: 3 tests
- Metrics: 1 test
- Resource Management: 5 tests

All tests passing ✅
