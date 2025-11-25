# All-IDs Resolver Implementation

## Summary

Added automatic generation of "all-ids" resolvers that return all entity IDs for a given entity type. This complements the existing ID resolver (which looks up a single entity by ID).

## What Was Added

### 1. `all-ids-resolver` Function

A new resolver generator that creates a resolver for fetching all IDs of an entity type.

**Location:** `src/main/us/whitford/fulcro/rad/database_adapters/datalevin.clj`

**Function signature:**
```clojure
(defn all-ids-resolver
  "Generate a resolver that returns all entity IDs for a given entity type."
  [{::attr/keys [qualified-key schema]
    ::dlo/keys  [generate-resolvers?]
    :or         {generate-resolvers? true}
    :as         id-attr}]
  ...)
```

**Key features:**
- Takes an identity attribute (e.g., `:account/id`)
- Generates a resolver with output key `:all-<namespace-plural>` (e.g., `:all-accounts`)
- Returns a vector of ID maps: `[{:account/id uuid1} {:account/id uuid2} ...]`
- Respects the `::dlo/generate-resolvers?` option

### 2. Updated `generate-resolvers` Function

Modified to include all-ids resolvers in the generated resolver collection.

**Before:** Generated only ID resolvers and reference resolvers
**After:** Generates ID resolvers, all-ids resolvers, and reference resolvers

## Usage Example

### Pathom Query

```clojure
;; Query for all account IDs
[:all-accounts]

;; Result:
{:all-accounts [{:account/id #uuid "..."}
                {:account/id #uuid "..."}
                {:account/id #uuid "..."}]}

;; You can also join on the IDs to fetch full entities:
[{:all-accounts [:account/id :account/name :account/email]}]

;; Result:
{:all-accounts [{:account/id #uuid "..." 
                 :account/name "Alice"
                 :account/email "alice@example.com"}
                {:account/id #uuid "..."
                 :account/name "Bob"
                 :account/email "bob@example.com"}]}
```

### Attribute Setup

```clojure
(def account-id
  {::attr/qualified-key :account/id
   ::attr/type          :uuid
   ::attr/schema        :production
   ::attr/identity?     true})

(def account-name
  {::attr/qualified-key :account/name
   ::attr/type          :string
   ::attr/schema        :production
   ::attr/identities    #{:account/id}})

;; Generate resolvers
(def resolvers (dl/generate-resolvers [account-id account-name]))

;; Resolvers now include:
;; 1. Form errors resolver
;; 2. ID resolver: :account/id -> entity data
;; 3. All-IDs resolver: :all-accounts -> [{:account/id ...} ...]
;; 4. Any reference resolvers (if applicable)
```

## Naming Convention

The all-ids resolver follows this pattern:

- **Input attribute:** `:account/id`
- **Output key:** `:all-accounts` (pluralized namespace + "all-" prefix)
- **Resolver name:** `account.all-accounts-resolver`

For entity types already plural:
- **Input attribute:** `:items/id`  
- **Output key:** `:all-items`
- **Resolver name:** `items.all-items-resolver`

## Implementation Details

### Query Used

The resolver uses a Datalog query to find all values for the identity attribute:

```clojure
(d/q '[:find ?v
       :in $ ?attr
       :where [?e ?attr ?v]]
     db qualified-key)
```

### Performance Considerations

- Returns all entity IDs in a single query
- For large datasets, consider pagination or filtering at the application level
- The resolver output is a vector suitable for Pathom's batch processing

## Tests Added

**Location:** `src/test/us/whitford/fulcro/rad/database_adapters/datalevin_core_test.clj`

New test suite: `all-ids-resolver-functionality`

Tests include:
1. ✅ Resolver returns all entity IDs
2. ✅ Resolver returns empty vector when no entities exist
3. ✅ Resolver works for different entity types (accounts, items, etc.)
4. ✅ Generated resolvers include all-ids resolvers in the collection

All 56 tests pass with 269 assertions.

## Benefits

1. **Convenience:** Easily fetch all IDs for listing views, dropdowns, etc.
2. **RAD Integration:** Follows Fulcro RAD patterns and conventions
3. **Automatic:** No manual resolver writing needed
4. **Consistent:** Same behavior across all entity types
5. **Composable:** Works with Pathom's join system for fetching full entities

## Migration Notes

This is a **backwards-compatible** addition:
- Existing resolvers continue to work as before
- No changes required to existing code
- New functionality is opt-in via Pathom queries
