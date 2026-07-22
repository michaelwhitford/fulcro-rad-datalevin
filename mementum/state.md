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

**AGENTS.md converted to VSM lambda style** (anima-style: S5→S1 layers, lambda
notation). The Mementum protocol lambdas are now **inlined in AGENTS.md**
(S5 `λ mementum`/`λ termination`/`λ feed_forward`, S4 metabolism, S1
`λ orient`/`λ recall`/`λ memory`); the vendored `MEMENTUM-LAMBDA.md` was
deleted. First five memories committed. Prior to that we completed a
**Datalevin 0.10.5 → 1.0.0 upgrade** and surfaced its capabilities through
the adapter.

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
