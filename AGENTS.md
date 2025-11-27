# Agent Guidelines for fulcro-rad-datalevin

Use a single PLAN.md for planning. Use a single CHANGELOG.md for changes.
Ask the user clarifying questions where appropriate before making large changes.
This database adapter project is checked out at /Users/mwhitford/src/fulcro-rad-datalevin
A working test application is checked out at /Users/mwhitford/src/datalevin-test-app

## Build & Test Commands

- **Run all tests**: `clojure -M:run-tests`
- **Run single test**: `clojure -M:run-tests --focus us.whitford.fulcro.rad.database-adapters.datalevin-core-test`
- **Check outdated deps**: `clojure -M:outdated`
- **Lint command**: `clj-kondo --lint .`

## Code Style

**Imports:** Group requires in order: clojure._, com.fulcrologic._, us.whitford._, datalevin._, other libs. Alphabetize within groups.

**Formatting:** 2-space indent, kebab-case for names, use `defn-` for private functions, separate sections with `;;` headers (80 chars).

**Naming:** Descriptive predicates end with `?`, converters use `->`, mutating functions end with `!`, private helpers prefixed with `-`.

**Types:** No type hints unless performance-critical. Use Clojure spec for validation (see existing `validate-delta!`).

**Error Handling:** Use `ex-info` with context maps for all errors. Include schema, operation details, and helpful context for debugging.

**Documentation:** All public functions need docstrings. Document parameters, return values, and throw behavior. See `automatic-schema` for example.

**Testing:** Tests in `src/test`. Use `test_utils.clj` helpers. Always use `with-test-conn` macro for database cleanup. One assertion per test when possible.
