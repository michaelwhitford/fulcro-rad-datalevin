# Form Save Debugging Guide

## Problem Description

Forms populate correctly but when saving changes, they either:
1. Revert immediately without persisting to the database
2. Save to database but form state doesn't update
3. Save succeeds but subsequent loads show old data

This guide helps you diagnose and fix these issues.

## Understanding the Save Flow

When a Fulcro RAD form is saved:

1. **Client Side:** Form creates a delta of changes (`::form/delta`)
2. **Mutation:** Save mutation sends delta to server
3. **Server Middleware:** `wrap-datalevin-save` processes the delta
4. **Database:** Changes are transacted to Datalevin
5. **Result:** Middleware returns `{:tempids {tid real-id} ...}`
6. **Client Merge:** RAD merges tempids into app state
7. **Form Update:** Form re-renders with new data

**If any step fails, forms will appear to revert.**

## Quick Diagnosis

Run the test suite to verify the adapter is working:

```bash
# Run all save-related tests
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-save-test

# Run all tests
clojure -M:run-tests
```

**If tests pass but your app fails, the issue is in your app configuration, not the adapter.**

## Test Suite Overview

### 1. `datalevin_core_test.clj` - Core Functionality
Tests delta processing, tempid conversion, and basic operations.

**Key Tests:**
- `delta-to-transaction-conversion` - Verifies deltas convert correctly
- `tempid-value-must-be-real-uuid` - **CRITICAL** - Ensures TempIds use real UUID values
- `tempid-uniqueness` - Ensures multiple tempids get unique IDs

### 2. `datalevin_save_test.clj` - Save Middleware
Tests the actual save middleware that your forms use.

**Key Tests:**
- `save-middleware-returns-map` - Verifies middleware returns correct type
- `save-new-entity` - Tests creating new entities with tempids
- `update-existing-entity` - Tests updating existing entities
- `tempids-always-present-in-result` - **CRITICAL** - `:tempids` must always be in result
- `multiple-tempids-mapped-correctly` - Tests multiple new entities
- `mixed-new-and-existing-entities` - Tests batch operations
- `sequential-updates-persist` - Tests repeated saves
- `rad-middleware-composition` - Tests middleware in RAD stack

## Step-by-Step Debugging Process

### Step 1: Verify Adapter Works

Run the save tests:

```bash
clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-save-test
```

**If all tests pass:** The adapter is working correctly. Your issue is in app configuration (go to Step 2).

**If tests fail:** There's a bug in the adapter. Check the CHANGELOG and update to latest version.

### Step 2: Check Your Middleware Configuration

The most common issue is incorrect middleware setup.

#### Correct Middleware Usage

```clojure
(ns com.example.components.middleware
  (:require
    [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]))

;; CORRECT - Returns middleware function
(def save-middleware
  (dl/wrap-datalevin-save {:default-schema :production}))

;; Then apply to your Pathom parser/handler
(def parser
  (p/parser
    {::p/plugins [(pc/connect-plugin {::pc/register resolvers})
                  ;; other plugins
                  ]}))
```

#### Incorrect Usage (Common Mistake)

```clojure
;; WRONG - Calling middleware with env instead of handler
(let [middleware (dl/wrap-datalevin-save {:default-schema :production})]
  (middleware env))  ; Returns function, not result!

;; CORRECT - Pass handler, then call with env
(let [middleware (dl/wrap-datalevin-save {:default-schema :production})
      handler    (middleware base-handler)]
  (handler env))  ; Returns map
```

### Step 3: Verify Database Connection in Environment

Check that your Pathom environment includes database connections:

```clojure
(defn make-pathom-env [connections]
  {::dlo/connections connections
   ::dlo/databases   (into {} (map (fn [[k v]] [k (d/db v)])) connections)
   ;; other env setup
   })
```

### Step 4: Check Form Delta Creation

Add logging to see if the form is creating deltas:

```clojure
;; In your save mutation
(defmutation save-form [params]
  (action [{:keys [state] :as env}]
    (log/info "Delta:" (::form/delta env)))
  (remote [env]
    (log/info "Remote env keys:" (keys env))
    true))
```

### Step 5: Verify Tempid Merging

Check that tempids are being merged back into app state:

```clojure
;; Should see in browser console or logs
;; After save: {:tempids {#fulcro/tempid [...] #uuid "..."}}
```

## Common Issues and Solutions

### Issue 1: Forms Revert Immediately After Save

**Symptom:** Form appears to save but immediately reverts to old values.

**Cause:** Middleware not properly wired up OR `:tempids` not being returned.

**Tests that detect this:**
- `tempids-always-present-in-result` (will fail if adapter broken)
- `save-middleware-returns-map` (will fail if middleware returns function)

**Solution - Check your middleware setup:**

```clojure
;; In your parser/handler configuration
(require '[us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
         '[us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo])

;; Make sure middleware is applied correctly
(def save-middleware (dl/wrap-datalevin-save {:default-schema :production}))

;; Middleware should wrap your handler
(def wrapped-handler
  (-> base-handler
      save-middleware
      ;; other middleware
      ))

;; Then call with environment
(wrapped-handler env)
```

**Also verify `:tempids` is in the result:**

```clojure
;; Add temporary logging in your server
(def save-middleware-debug
  (fn [handler]
    (fn [env]
      (let [result (handler env)]
        (log/info "Save result keys:" (keys result))
        (log/info "Tempids:" (:tempids result))
        result))))

(def wrapped-handler
  (-> base-handler
      (dl/wrap-datalevin-save {:default-schema :production})
      save-middleware-debug))
```

### Issue 2: Missing Database Connection

**Symptom:** Error about missing connection for schema.

**Cause:** `::dlo/connections` not configured in Pathom environment.

**Tests that detect this:**
- `error-missing-connection` (should throw with helpful message)

**Solution - Configure connections in environment:**

```clojure
(ns com.example.components.parser
  (:require
    [datalevin.core :as d]
    [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]))

(defonce connections (atom {}))

(defn start-connections! []
  (let [conn (d/get-conn "/var/data/mydb" schema)]
    (reset! connections {:production conn})))

(defn make-pathom-env []
  {::dlo/connections @connections
   ::dlo/databases   (into {} (map (fn [[k conn]] [k (d/db conn)])) @connections)
   ;; other environment keys
   })
```

### Issue 3: Delta Not Being Created

**Symptom:** Save appears to work but nothing changes in database.

**Cause:** Form isn't creating deltas, or deltas are empty.

**Tests that detect this:**
- `update-existing-entity` (verifies deltas with changes work)

**Solution - Verify delta creation:**

```clojure
;; Add logging in your save mutation
(defmutation save-form [params]
  (action [{:keys [state] :as env}]
    (log/info "DELTA:" (::form/delta env))
    ;; Delta should look like:
    ;; {[:entity/id entity-id]
    ;;  {:entity/field {:before "old" :after "new"}}}
    )
  (remote [env]
    (eql/query->ast [:save-form params])))
```

**Check form configuration:**

```clojure
(defsc AccountForm [this props]
  {:ident         :account/id
   ::form/id      account-id
   ::form/fields  #{:account/name :account/email}
   ::form/validator account-validator
   ;; Make sure form attributes are properly configured
   }
  ...)
```

### Issue 4: Schema Mismatch

**Symptom:** Error about missing connection for schema.

**Cause:** Attribute specifies one schema, but connection uses different key.

**Tests that detect this:**
- `error-includes-context` (provides helpful error message)

**Solution - Ensure schema names match:**

```clojure
;; In your attribute definitions
(defattr account-id :account/id :uuid
  {::attr/schema :production    ; Schema name
   ::attr/identity? true})

(defattr account-name :account/name :string
  {::attr/schema :production    ; Must match!
   ::attr/identities #{:account/id}})

;; In your connection configuration
{::dlo/connections {:production conn}}  ; Same schema name

;; OR specify default-schema in middleware
(dl/wrap-datalevin-save {:default-schema :production})
```

### Issue 5: Data Persists But Form Doesn't Update

**Symptom:** Save works in database, but form still shows old values.

**Cause:** Tempids not being merged into client app state.

**Tests that detect this:**
- `tempids-always-present-in-result` (ensures `:tempids` always returned)
- `rad-middleware-composition` (ensures result format is correct)

**Solution - Verify tempid merge on client:**

```clojure
;; Check your save mutation on client
(defmutation save-account [params]
  (action [{:keys [state] :as env}]
    (log/info "Saving account..."))
  (remote [env]
    true)  ; Make sure remote returns true
  (ok-action [{:keys [state result] :as env}]
    (log/info "Save succeeded, tempids:" (:tempids result))
    ;; Fulcro should automatically merge tempids
    ;; If you're doing custom merging, ensure tempids are processed
    ))

;; Make sure your form uses standard RAD save
(use-sub-form! this AccountForm
  {:destructive? false})
```

**Verify server response format:**

The middleware should return:
```clojure
{:tempids {#fulcro/tempid [...] #uuid "..."}
 ;; other result keys
 }
```

Not:
```clojure
;; WRONG - function instead of map
#function[...]
```

## Debugging Checklist

Use this checklist to systematically diagnose form save issues:

### 1. Verify Adapter Works
- [ ] Run test suite: `clojure -M:run-tests`
- [ ] All tests pass (especially save tests)
- [ ] If tests fail, update to latest version

### 2. Check Middleware Configuration
- [ ] Middleware created with `(dl/wrap-datalevin-save)`
- [ ] Middleware wraps handler, not called with env directly
- [ ] Middleware returns map, not function
- [ ] Add debug logging to verify result format

### 3. Verify Database Connections
- [ ] `::dlo/connections` in Pathom environment
- [ ] Connection keys match attribute schemas
- [ ] Connections are active (not closed)
- [ ] Database snapshots in `::dlo/databases`

### 4. Check Form Configuration
- [ ] Form has proper ident
- [ ] Form fields match attributes
- [ ] Attributes have correct schema
- [ ] Form creates deltas (check with logging)

### 5. Verify Tempid Flow
- [ ] Server returns `:tempids` in result
- [ ] Client mutation has `ok-action` or uses RAD defaults
- [ ] Tempids are merged into app state
- [ ] Form re-renders after merge

### 6. Test Database Directly
- [ ] Can write to database with `d/transact!`
- [ ] Can read back data with `d/pull`
- [ ] Connection is to correct path/database

## Logging Strategy

Add strategic logging to trace the save flow:

### Server-Side Logging

```clojure
;; Wrap middleware with debug logging
(defn debug-save-middleware [handler]
  (fn [env]
    (log/info "=== SAVE START ===")
    (log/info "Delta present:" (boolean (::form/delta env)))
    (log/info "Delta keys:" (keys (::form/delta env)))
    (log/info "Connection present:" (boolean (get-in env [::dlo/connections :production])))
    
    (let [result (handler env)]
      (log/info "=== SAVE RESULT ===")
      (log/info "Result type:" (type result))
      (log/info "Result keys:" (keys result))
      (log/info "Tempids:" (:tempids result))
      (log/info "=== SAVE END ===")
      result)))

;; Apply to your handler
(def wrapped-handler
  (-> base-handler
      (dl/wrap-datalevin-save {:default-schema :production})
      debug-save-middleware))
```

### Client-Side Logging

```clojure
(defmutation save-account [params]
  (action [{:keys [state] :as env}]
    (log/info "=== CLIENT SAVE START ===")
    (log/info "Params:" params))
  (remote [env] true)
  (ok-action [{:keys [result] :as env}]
    (log/info "=== CLIENT SAVE SUCCESS ===")
    (log/info "Result:" result)
    (log/info "Tempids:" (:tempids result)))
  (error-action [{:keys [result] :as env}]
    (log/error "=== CLIENT SAVE ERROR ===")
    (log/error "Error:" result)))
```

### Database Verification

```clojure
;; After save, verify in database
(defn verify-save [conn entity-id]
  (let [db (d/db conn)
        entity (d/pull db '[*] [:account/id entity-id])]
    (log/info "DB Entity:" entity)
    entity))
```

## Common Error Messages

### "No database connection configured for schema :X"
**Cause:** Missing connection in `::dlo/connections`  
**Fix:** Add connection to Pathom environment

### "Delta must be a map"
**Cause:** Invalid delta structure passed to middleware  
**Fix:** Check form delta creation

### "Batch size exceeds maximum"
**Cause:** Trying to save too many entities at once  
**Fix:** Increase `*max-batch-size*` or batch your saves

### "attribute-unreachable" for `:tempids`
**Cause:** Middleware not returning `:tempids` key  
**Fix:** Update to latest adapter version (fixed in recent releases)

## Getting Help

If you've followed this guide and still have issues:

1. **Run the tests** and note which pass/fail
2. **Collect logs** from both client and server
3. **Check database** to see if data persists
4. **Verify versions** of Fulcro RAD and adapter
5. **Create minimal reproduction** if possible

Include this information when asking for help:
- Test results (pass/fail)
- Server logs showing save flow
- Client logs showing mutation
- Database state verification
- Middleware configuration code
- Form configuration code

## Summary

The test suite comprehensively covers:
- ✅ Schema generation and connections
- ✅ Delta processing and validation
- ✅ Tempid handling (CRITICAL for form updates)
- ✅ Save middleware with all edge cases
- ✅ Delete middleware
- ✅ Error handling and validation
- ✅ RAD middleware composition
- ✅ Data persistence verification
- ✅ Batch operations
- ✅ Resource management

**If tests pass, your issue is in application configuration.**  
**If tests fail, there's a bug in the adapter (report it!).**

Most form save issues are caused by:
1. Missing `:tempids` in result (fixed in recent versions)
2. Incorrect middleware wiring (handler vs env)
3. Missing database connections in environment
4. Schema name mismatches
5. Client not merging tempids

Follow the checklist and logging strategy above to diagnose your specific issue.
