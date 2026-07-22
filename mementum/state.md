# State — fulcro-rad-datalevin

> Working memory / session bootloader. Read this first. Update it after every
> significant change. Written for a brilliant stranger — that's you, next session.

## Project

Fulcro RAD database adapter for **Datalevin**. Repo:
`/Users/mwhitford/src/fulcro-rad-datalevin`. Working test app:
`/Users/mwhitford/src/datalevin-test-app`. Datalevin 1.0.0 source cloned at
`/Users/mwhitford/datalevin` (reference).

Build/test: `clojure -M:run-tests` (kaocha). Focus one ns:
`clojure -M:run-tests --focus <ns>`. Lint: `clj-kondo --lint src/main src/test`.

## Now

**Upstream knowledge pages seeded** — 9 source-verified prose reference pages
under `mementum/knowledge/upstream/{datalevin,fulcro-rad}/`, extracted from the
clones at `~/src/datalevin` (1.0.0) and `~/src/fulcro-rad`. Indexed in
`mementum/index.md`. Convention: **knowledge pages are prose, not lambda
notation** (lambdas are for system prompts/policy only — human decision).

Key finding encoded there (de-risks the full-text spike): both RAD parsers
(Pathom 2 & 3) normalize EQL params into env under the plain `:query-params`
key via `combined-query-params` — see
[knowledge/upstream/fulcro-rad/resolvers-and-pathom.md](knowledge/upstream/fulcro-rad/resolvers-and-pathom.md).

Before that: AGENTS.md converted to VSM lambda style with the Mementum
protocol inlined; first five memories committed; **Datalevin 0.10.5 → 1.0.0
upgrade** with capability surfacing complete.

## Recently done (Datalevin 1.0.0 wiring)

- `:vec` vector attributes → HNSW index (`:vector-domains` conn opt; dims live in
  conn-opts, NOT schema).
- Attribute predicates via native `:db.attr/preds` (through `::dlo/attribute-schema`).
- Transaction post-conditions via `:db/ensure` + `::dlo/raw-txn` /
  `append-to-raw-txn`.
- `schema-problems` / `verify-schema!`.
- `fix-numerics` value coercion on save.
- `::dlo/wrap-resolve` covered by tests (was already wired).
- **Tier 1** `:conn-opts` pass-through in `start-database!` (enables
  `:auto-entity-time?`, `:validate-data?`, `:closed-schema?`, `:wal?`,
  `:search-domains`, …).
- **Tier 2** wired dead options: `::dlo/transact-options` (tx-meta),
  `::dlo/transaction-timeout-ms` (per-txn `with-transaction` timeout),
  `::dlo/max-batch-size`; removed `::dlo/max-retries`.
- **Tier 3** delete middleware: `d/entid` lookup, native-id deletes, failure
  propagation.
- Test hygiene: test through public API (no private-var reaches); mixed-schema
  test conns build full schema across all schemas; `.clj-kondo/` config+hooks now
  tracked (with-transaction hook).

Suite: **52 tests, 251 assertions, 0 failures**. Lint: **0 warnings**.

Deps bumped (post-knowledge-seeding): clojure 1.12.5, fulcro 3.9.5,
fulcro-rad 1.6.24, guardrails 1.3.3, fulcro-spec 3.2.10, deps-deploy 0.2.5.
Kondo note: `datalevin.core/with-transaction` is a runtime re-export
(`import-macro`) → narrow `:unresolved-var` exclude in tracked
`.clj-kondo/config.edn`; the warning only appears after regenerating the
derived lint cache (CI does this). Also `:output {:exclude-files}` for
`.clj-kondo/imports/` — derived third-party configs (e.g. guardrails 1.3.3
hooks trip `redundant-str-call` in editors).

## Next

**Full-text search** — see [knowledge/design/full-text-search.md](knowledge/design/full-text-search.md).
Start with **Phase 0 spike (RISK #1)**: prove the Pathom param round-trip
(`{:params {:query …}}` → resolver) in `datalevin-test-app`, plus a low-level
`d/fulltext` round-trip. Then Phase 1 (schema + connection), Phase 2 (resolver).

Other remaining v1.0 opportunities (PLAN.md): bulk load via `transact-async` /
`init-db` / `fill-db`; tuple round-trip test under v1.0 storage.

## Blocking / open

- Branch is ~10+ commits **ahead of origin, unpushed**. Push when ready.
- CI note: `.clj-kondo/imports/` is gitignored (derived); a CI lint job must
  regenerate dep configs (`clj-kondo --lint "$(clojure -Spath)" --dependencies
  --copy-configs`) before linting source.

## Key files

- `src/main/.../datalevin/start_databases.clj` — schema gen, conn opts,
  `vec-conn-opts`, `merge-conn-opts`, `schema-problems`/`verify-schema!`.
- `src/main/.../datalevin/generate_resolvers.clj` — `id-resolver`,
  `all-ids-resolver` (mirror for search).
- `src/main/.../datalevin/wrap_datalevin_save.clj` — `save-form!`,
  `run-save-transact!`, `append-to-raw-txn`, `fix-numerics`.
- `src/main/.../datalevin/wrap_datalevin_delete.clj` — `delete-entity!`.
- `src/main/.../datalevin_options.cljc` — all `::dlo/*` option keys.
- `PLAN.md`, `CHANGELOG.md` — code planning + changelog (separate from mementum).
