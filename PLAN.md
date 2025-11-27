# Implementation Plan: Enum Support

## Goal
Add support for fulcro-rad's `:enum` type with `::attr/enumerated-values` in the datalevin adapter.

## Analysis

### How Fulcro RAD Defines Enums
- Type: `:enum`
- Required: `::attr/enumerated-values` - set of keywords defining allowed values
- Optional: `::attr/enumerated-labels` - map from keyword to display string

### How Datomic Handles Enums
- Schema: Maps `:enum` type to `:db.type/ref`
- Storage: Creates entities with `:db/ident` for each enumerated value
- Values can be simple keywords or fully qualified keywords
- If values aren't qualified, auto-generates namespace from attribute (e.g., `:account/role.admin`)

### How XTDB Handles Enums
- No special handling - just stores keywords directly
- Simpler approach since XTDB is document-oriented

### Datalevin Approach
- Datalevin uses Datalog like Datomic
- Should follow Datomic pattern for compatibility
- Use `:db.type/ref` for enum attributes
- Create `:db/ident` entities for enumerated values

## Implementation Tasks

### 1. Update Type Map
- [x] Add `:enum` -> `:db.type/ref` to type-map in `start_databases.clj`

### 2. Generate Enumerated Value Entities
- [x] Create `enumerated-values` function similar to Datomic's
- [x] Handle both qualified and unqualified keywords
- [x] Auto-generate namespace from attribute if needed
- [x] Support map-style enum values (for advanced use cases)

### 3. Update `automatic-schema`
- [x] Include enumerated value entities in schema transaction
- [x] Ensure enum attributes and their values are created together
- [x] Update `start-database!` to transact enum idents
- [x] Update `create-test-conn` test utility to transact enum idents

### 4. Testing
- [x] Test enum attribute schema generation
- [x] Test save/load of enum values
- [x] Test both single and many cardinality
- [x] Test qualified and unqualified keywords
- [x] Test enum delta saves via middleware
- [x] Test enum updates via delta
- [x] All 18 tests pass with 129 assertions ✅
- [ ] Test in test-app to verify end-to-end

### 5. Documentation
- [ ] Update README with enum support
- [ ] Add enum examples to documentation
- [x] Update CHANGELOG

## Implementation Notes

- Follow the Datomic pattern closely since Datalevin is Datalog-based
- Ensure backward compatibility - don't break existing code
- Enums stored as refs mean queries return keyword idents, not entity maps
- This is the expected behavior in RAD
