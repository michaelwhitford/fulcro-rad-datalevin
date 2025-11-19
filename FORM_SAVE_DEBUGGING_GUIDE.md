# Form Save Persistence Issue - Debugging Guide

## Problem Description
Forms populate fine, but when saving changes, they revert without persisting to the database.

## Tests Created
A comprehensive test suite has been created in:
```
src/test/us/whitford/fulcro/rad/database_adapters/form_save_persistence_test.clj
```

## Running the Tests

### Run all new persistence tests:
```bash
clojure -M:run-tests --focus form-save-persistence
```

### Run a specific test:
```bash
clojure -M:run-tests --focus form-edit-and-save-persists
```

### Run all tests:
```bash
clojure -M:run-tests
```

## Test Categories and What They Verify

### 1. Complete Form Lifecycle Tests
These tests simulate the exact workflow you're experiencing:

#### `form-edit-and-save-persists`
- Creates an entity with initial values
- Simulates user editing the form (changing fields)
- Saves via middleware
- **CRITICAL CHECK:** Verifies data actually persisted to database
- **This test directly addresses your issue**

#### `form-save-then-reload-shows-changes`
- Saves changes
- Reloads from database
- Verifies the reloaded data shows the changes
- **Tests if changes survive a reload cycle**

#### `multiple-edits-in-sequence-all-persist`
- Makes several sequential edits
- Verifies each edit persists before moving to the next
- **Tests if repeated saves work**

### 2. New Entity Creation Tests

#### `new-form-creation-and-save-persists`
- Creates new entity with tempid
- Saves to database
- Verifies entity exists and has all fields
- Checks tempid mapping is returned correctly

#### `new-form-save-then-edit-both-persist`
- Creates new entity
- Edits it immediately after
- Verifies both operations persist

### 3. Result Format Tests

#### `save-result-includes-all-necessary-keys`
- Verifies the middleware returns correct result format
- Checks for `::form/id`, `::form/complete?`, `:tempids`
- **Critical for Fulcro form state updates**

#### `save-result-format-with-existing-entity`
- Tests result format for updates to existing entities
- Verifies save happens even without tempids

### 4. Edge Cases

#### `partial-delta-updates-only-changed-fields`
- Updates only some fields
- Verifies unchanged fields remain intact

#### `boolean-toggle-persists`
- Tests toggling boolean fields (true ↔ false)
- Common form control issue

#### `numeric-field-changes-persist`
- Tests various numeric values (including 0.0, decimals)
- Ensures precision is maintained

### 5. Middleware Integration Tests

#### `middleware-works-in-rad-stack`
- Simulates realistic RAD middleware composition
- **Tests if middleware works when stacked with others**

#### `middleware-preserves-error-results`
- Verifies error handling doesn't break saves

### 6. Reference Field Tests

#### `reference-field-updates-persist`
- Tests to-many relationships
- Verifies reference updates persist

### 7. Diagnostic Tests

#### `diagnostic-delta-is-processed`
- Tracks whether middleware actually processes the delta
- **Helps identify if middleware is even being called**

#### `diagnostic-middleware-execution-order`
- Logs execution order
- **Helps identify middleware composition issues**

#### `diagnostic-connection-is-available`
- Tests direct database write vs middleware write
- **Helps isolate if issue is in middleware or database layer**

## How to Use These Tests to Debug Your Issue

### Step 1: Run the Diagnostic Tests First
```bash
clojure -M:run-tests --focus diagnostic
```

These tests will tell you:
- Is the middleware being called?
- Is the execution order correct?
- Is the database connection working?

### Step 2: Run the Core Lifecycle Tests
```bash
clojure -M:run-tests --focus form-edit-and-save-persists
clojure -M:run-tests --focus form-save-then-reload-shows-changes
```

If these PASS in the test suite but FAIL in your app, it means:
- The adapter code is correct
- **The issue is in how your app wires up the middleware**

If these FAIL in the test suite, it means:
- The adapter has a bug
- The tests have identified the exact issue

### Step 3: Check Middleware Composition
```bash
clojure -M:run-tests --focus middleware-works-in-rad-stack
```

This test simulates realistic RAD middleware stacking. If it fails, the issue is related to middleware composition.

## Common Issues and How Tests Will Surface Them

### Issue 1: Middleware Not Wired Up Correctly
**Symptom:** Forms revert immediately after save
**Tests that will fail:**
- `form-edit-and-save-persists`
- `diagnostic-middleware-execution-order`

**What to check in your app:**
```clojure
;; CORRECT
(let [save-middleware (dl/wrap-datalevin-save {:default-schema :production})]
  (let [handler (save-middleware base-handler)]  ; Step 1: wrap handler
    (handler env)))                              ; Step 2: call with env

;; WRONG - Will cause immediate revert
(let [save-middleware (dl/wrap-datalevin-save {:default-schema :production})]
  (save-middleware env))  ; Passed env instead of handler!
```

### Issue 2: Missing Database Connection in Pathom Env
**Symptom:** Silent failure, no error
**Tests that will fail:**
- `error-missing-connection`
- `diagnostic-connection-is-available`

**What to check in your app:**
```clojure
;; Your pathom environment must include:
{::dlo/connections {:production your-datalevin-conn}}
```

### Issue 3: Delta Not Being Created by Form
**Symptom:** Save appears to work but nothing changes
**Tests that will fail:**
- `form-edit-and-save-persists`
- `diagnostic-delta-is-processed`

**What to check in your app:**
```clojure
;; Check if form is creating delta correctly
;; Delta should look like:
{[:entity/id entity-id]
 {:entity/field {:before "old" :after "new"}}}
```

### Issue 4: Incorrect Schema Configuration
**Symptom:** Error about missing connection for schema
**Tests that will fail:**
- `error-includes-context`

**What to check in your app:**
```clojure
;; Ensure attribute schema matches connection schema
(defattr my-attribute :entity/field :string
  {::attr/schema :production})  ; Must match connection key

;; And connection exists
{::dlo/connections {:production conn}}
```

### Issue 5: Result Not Being Merged Back to Form
**Symptom:** Save works in DB, but form doesn't update
**Tests that will fail:**
- `save-result-includes-all-necessary-keys`
- `save-result-format-with-existing-entity`

**What to check in your app:**
- Ensure save mutation is properly wired to Fulcro
- Check that tempids are being merged into app state
- Verify form is using standard RAD save mechanism

## Manual Testing in Your App

### Add Logging to Your Save Handler
```clojure
(defmutation save-form [params]
  (action [{:keys [state] :as env}]
    (log/info "SAVE: Starting save with params:" params)
    ;; Your save logic here
    )
  (remote [env]
    (log/info "SAVE: Remote called with env keys:" (keys env))
    ;; Return save AST
    ))
```

### Add Logging to Middleware Wrapper
In your server setup:
```clojure
(let [save-middleware (dl/wrap-datalevin-save {:default-schema :production})]
  (fn [handler]
    (fn [env]
      (log/info "MIDDLEWARE: Before save, delta:" (::form/delta env))
      (let [result (handler env)]
        (log/info "MIDDLEWARE: After save, result keys:" (keys result))
        (log/info "MIDDLEWARE: Tempids:" (:tempids result))
        result))))
```

### Verify Database After Save
```clojure
;; After save mutation completes, manually check database
(let [db (d/db your-conn)
      entity (d/pull db '[*] [:entity/id entity-id])]
  (log/info "DB STATE:" entity))
```

## Expected Test Results

### If ALL tests pass:
- The adapter code is working correctly
- The issue is in your application's middleware setup or form configuration

### If diagnostic tests fail:
- Middleware is not being called or composed incorrectly
- Check your pathom plugin configuration
- Check your form configuration

### If lifecycle tests fail:
- There's a bug in the adapter's save mechanism
- This would be unexpected given existing test coverage

### If result format tests fail:
- The middleware is not returning the correct result structure
- Fulcro cannot update form state from the result

## Next Steps Based on Test Results

1. **Run the full test suite** and note which tests pass/fail
2. **Check the failure messages** - they're designed to be descriptive
3. **Compare passing tests** with your app configuration
4. **Add logging** to your app's save flow to trace execution
5. **If tests pass but app fails**, the issue is in app setup, not adapter

## Additional Debugging Tools

### Check Middleware Composition
Add this to your server setup:
```clojure
(defn debug-middleware [handler]
  (fn [env]
    (log/info "ENV KEYS:" (keys env))
    (log/info "HAS DELTA:" (boolean (::form/delta env)))
    (log/info "HAS CONNECTIONS:" (boolean (::dlo/connections env)))
    (handler env)))

;; Compose like:
(-> base-handler
    (dl/wrap-datalevin-save {:default-schema :production})
    (debug-middleware))
```

### Verify Pathom Plugin
```clojure
(pco/register
  [(dl/pathom-plugin
     (fn [env] {::dlo/connections {:production your-conn}
                ::dlo/databases   {:production (d/db your-conn)}}))])
```

## Questions to Answer

After running tests, answer these:

1. ✓ Do the tests pass? (Yes/No)
2. ✓ Which specific tests fail? (List them)
3. ✓ Does your app use the same middleware composition pattern as the tests?
4. ✓ Is `::dlo/connections` present in your Pathom environment?
5. ✓ Are you using custom save middleware that might interfere?
6. ✓ Do you see the delta in server logs when saving?
7. ✓ Does direct database write work in your app?

## Summary

These tests comprehensively cover:
- ✓ Complete form lifecycle (load → edit → save → verify)
- ✓ New entity creation with tempids
- ✓ Updates to existing entities
- ✓ Result format correctness
- ✓ Edge cases (booleans, numbers, nil values)
- ✓ Middleware composition
- ✓ Reference fields
- ✓ Diagnostic checks for common issues

The tests are designed to either:
1. **Pass** - confirming the adapter works, issue is in app setup
2. **Fail with descriptive messages** - pinpointing the exact bug

Run them and let me know the results!
