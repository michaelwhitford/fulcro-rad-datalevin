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

**FULL-TEXT SEARCH SHIPPED (all phases 0–3 complete).**
- Phase 3 (final): test-app model declares `::dlo/fulltext?` on
  `:account/name` + `:person/bio` (native-id); `AccountSearchList` report
  (cljc — JVM-verified) with `:query` control; new
  `fulltext_search_integration_test.clj` proves report-shaped EQL through the
  app's REAL P3 parser (relevance order, auto-filled columns, `:top`
  pagination, empty query, native-id eids). App suite **91/356/0**
  (test-app commit `d5b5677`). Adapter README gains a "Full-Text Search"
  section; design doc status → all phases complete.
- Remaining future enhancements (design §5): surface relevance scores,
  cross-entity/global search, RAD report paging-control mapping.
- Test-app commits remain **local-only** (not pushed): deps bump, contract
  port, spike, Phase 3.

Before that:

**Full-text Phase 2 COMPLETE (generated search resolvers).**
- `search-resolver` in `generate_resolvers.clj` (mirrors `all-ids-resolver`):
  emitted per entity type with ≥1 `::dlo/fulltext?` attr; output
  `{:<ns>/search [idents…]}` in relevance order (`:refs+scores` + desc sort);
  params from `(:query-params env)` (`:query :top :limit :offset`);
  blank/missing query → `[]`; native-id aware (eid ≡ id); domains via
  `search-domains-for` (mirrors schema derivation incl. user domains).
- Wired as third resolver type in `generate-resolvers` (flows to P2 and, via
  RAD auto-convert, P3; `generate-resolvers-pathom3` inherits it).
- 7 new tests incl. **real P3 processor round-trip** (params + field auto-fill
  by id-resolver) and native-id path. Suite: **70 tests / 309 assertions /
  0 failures**, lint 0.
- Next: **Phase 3** — RAD report integration + pagination + README docs
  (client-side `ro/source-attribute :<ns>/search` + search control → params;
  spike already proved the server seam). Also consider surfacing scores later
  (design §5 open decision).

Before that:

**Full-text Phase 1 COMPLETE (schema + connection).**
- `::dlo/fulltext?` option (bool ∨ opts-map) in `datalevin_options.cljc`.
- `attr->schema`: emits `:db/fulltext true` + derived `:db.fulltext/domains
  [<attr-namespace>]` (one domain per entity type); user-supplied native
  domains/autoDomain suppress derivation.
- `search-conn-opts` (public, `start_databases.clj`, mirrors `vec-conn-opts`):
  only emits domains that have actual opts (e.g. `:index-position?`);
  `merge-conn-opts` generalized to deep-merge both `:vector-domains` and
  `:search-domains`; `start-database!` merges both derivers.
- `search_test.clj`: 6 tests — schema gen, user-domain respect, conn-opts
  derivation, e2e ranked search (refs+scores + sort, per spike finding),
  e2e **phrase search** (proves `:index-position?` flows attr → conn), sync
  index save→search. Suite: **63 tests / 298 assertions / 0 failures**, lint 0.
- Next: **Phase 2** — `search-resolver` in `generate_resolvers.clj` (mirror
  `all-ids-resolver`; params via `(:query-params env)`; `:refs+scores` +
  explicit sort; native-id aware). Then Phase 3 (RAD report integration).

Before that:

**Full-text Phase 0 spike COMPLETE** (test-app commit `9e4adb0`,
`src/test/app/fulltext_spike_test.clj`; suite 86/344/0):
- RISK #1 resolved: `{:params {:query …}}` → `(:query-params env)` through
  RAD's real P3 processor, proven with a P2-shape echo resolver (the exact
  generator shape). `d/fulltext` round-trip + `:top` also proven.
- **Design correction found**: `:find [?e ...]` does NOT preserve relevance
  order (Datalog set semantics) — resolver must use `{:display :refs+scores}`
  + explicit score sort. Design doc §4/§6 updated.
- Next: **Phase 1** (schema + connection: `::dlo/fulltext?`, `attr->schema`
  fulltext branch, `search-conn-opts` deriver) then **Phase 2** (resolver
  generator). See [knowledge/design/full-text-search.md](knowledge/design/full-text-search.md).

Before that:

**Test app modernized (proving ground ready for the full-text spike).**
- `~/src/datalevin-test-app` deps bumped to the adapter-tested stack (fulcro
  3.9.5, rad 1.6.24, guardrails 1.3.3, timbre 6.8.0, eql 2025.09.27, clojure
  1.12.5 + safe minors). Held back: hiccup 2.0 (breaking) and the coupled
  shadow-cljs 3.x / closure-compiler / cljs trio.
- Ported the app to the current adapter contract: deleted its local
  `datalevin-common` wrap-env shim (superseded by `dl/wrap-env`, atom-backed
  RYW snapshots); tests that hand-register raw P3 indexes or poke resolver
  records now use `dl/generate-resolvers-pathom3`; processor-based paths keep
  `dl/generate-resolvers` (RAD `new-processor` auto-converts P2-shape maps).
- App suite: was 82 tests / 19 errors / 9 failures (stale contract, NOT the
  deps bump — verified by baseline run) → **82 tests / 337 assertions / 0
  failures**. `app.server.parser` loads clean. Commits: test-app `230d047`
  (deps) + `6a71711` (port).

Before that:

**CI + release automation (mirrors fulcro-rad-git).**
- `.github/workflows/ci.yml` — push/PR to main: test job (temurin 21, official
  Clojure install script, deps cache, `clojure -M:run-tests`) + lint job
  (regenerates kondo dep configs from `clojure -Spath -A:test` with
  `--dependencies --copy-configs`, then lints `src/main src/test`). No git
  identity or `--skip-meta` needed here (unlike fulcro-rad-git).
- `.github/workflows/release.yml` — fires ONLY on `v1.2.3` and `v1.2.3-RC1`
  tags (`-alpha`/`-beta` are local-only, by policy): tests → `clojure -T:build
  jar` (VERSION from tag) → `clojure -X:deploy` to Clojars (needs
  `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` repo **secrets — must be set on GitHub
  before first release**) → `gh release create` with jar (`--prerelease` for
  hyphenated tags).
- **Build migrated depstar → tools.build** (`build.clj`, ported from
  fulcro-rad-git): `clojure -T:build clean|jar|install`; lib
  `us.whitford/fulcro-rad-datalevin`, default version **1.0.0-RC1**, thin jar
  `target/fulcro-rad-datalevin.jar`, pom from root `:deps` only (no test
  deps). `:deploy` alias now reads the pom from inside `target/classes/`.
  Old depstar `:jar`/`:install` aliases removed; `resources/.gitkeep` added
  (dir was in `:paths` but absent). README build section updated.
- Verified locally: jar pom coordinates correct; suite 57/284/0; lint 0
  warnings via the exact CI lint flow.
- **First CI runs failed; two layered causes, both fixed:**
  1. Cold `~/.m2` → `$(clojure -Spath -A:test)` interleaved `Downloading:`
     output into the classpath → clj-kondo copied no configs. Fix: `clojure
     -P -A:test` prime step before the substitution.
  2. **`~/.config/clj-kondo/config.edn` on the dev machine globally disables
     `:unused-referred-var`/`:unused-binding`/`:unused-namespace`/`:unused-private-var`**
     — local "0 warnings" was masked; CI surfaced 14 real findings. Fixed the
     code (removed dead requires/bindings) + one narrow tracked-config exclude
     for a true FP (guardrails gspec `=>`/`?` not registered as usage by its
     hook). To reproduce CI lint locally: `XDG_CONFIG_HOME=/tmp/empty-xdg
     clj-kondo --lint src/main src/test`.
- NOTE: AGENTS.md `λ deps` still says "depstar + deps-deploy" — stale, needs
  human-approved update to "tools.build (:build) + deps-deploy (:deploy)".

Before that:

**Real dual-Pathom integration tests + read-your-writes (v1.0-alpha prep).**
- New `pathom_integration_test.clj` drives the adapter through **real** RAD
  parsers — `pathom/new-parser` (P2) and `pathom3/new-processor` (P3) — full CRUD
  parity: id read, all-ids read, `form/save-form` new+update (asserting returned
  entity), `form/delete-entity`. `com.wsscode/pathom` (2) added as test dep.
- **Read-your-writes**: `::dlo/databases` is now `{schema -> atom<db>}`.
  `wrap-env`/`pathom-plugin` seed atoms per request; `save-form!`/`delete-entity!`
  `reset!` to the report's `:db-after` (idiomatic Datalevin — db is a live
  `@conn` ref; report carries `:db-after`). Resolvers deref leniently (bare `db`
  still accepted). New helper `pp/refresh-db-snapshot!`.
- **Bug fixed** (caught by the integration tests): generated batch id-resolvers
  assumed a vector input; Pathom 2 passes a single input map for a lone entity
  and expects a single map back. Now detect single-vs-batch and respond in kind.
- Pruned 2 superseded happy-path unit tests (`save-middleware-basic`,
  `save-then-resolve-round-trip`); kept edge cases (empty delta, nil removal,
  multi-tempid, handler composition, to-many refs, tempids contract, delete no-op).
- Reference wiring: `~/src/fulcro-rad-datomic` datomic_spec.clj is the P2+P3
  parser-test template; datalevin's `:db-after` mechanism verified against
  `~/src/datalevin` (conn.clj TxReport, `d/db` = `@conn`).
- Suite: **57 tests, 284 assertions, 0 failures**; lint 0 warnings.

Before that:

**Dual Pathom 2/3 support (mirror datomic/xtdb)** — the adapter is now
Pathom-version-agnostic with **zero hard pathom dependency**. Verified: main
namespaces load with pathom3 fully absent from the classpath.
- `generate-resolvers` emits **Pathom-2-shape maps** (`:com.wsscode.pathom.connect/{sym,input,output,batch?,resolve}`,
  plain data). Works in a P2 parser directly; RAD's P3 `new-processor` runs
  `convert-resolvers` so P3 apps need no changes (batching survives — verified in
  `resolvers-convert-to-pathom3`).
- `generate-resolvers-pathom3` — native P3 records via lazy
  `(requiring-resolve 'com.fulcrologic.rad.pathom3/convert-resolvers)`.
- `pathom-plugin` repurposed to **Pathom 2 `::p/wrap-parser`**; `wrap-env` is the
  P3 env-middleware path (unchanged). BREAKING for old P3 `pathom-plugin` users.
- `pathom3` moved from `:deps` to `:test`/`:run-tests` aliases only.
- Test-shape knowledge centralized in `test_utils` helpers (`resolver-input`,
  `resolver-output`, `resolver-fn`, `resolver-for-input`).
- Reference patterns live at `~/src/fulcro-rad-datomic` (P2-default + separate
  `generate-resolvers-pathom3` via `lazy-invoke`) and `~/src/fulcro-rad-xtdb`
  (P2-only). Key enabler: RAD `pathom2->pathom3` maps `::pc/batch? → ::pco/batch?`.
- Suite: **57 tests, 268 assertions, 0 failures**; lint 0 warnings.

Before that:

**Salvaged early-gen PR test** — merged PR #5 added `save_form_integration_test.clj`
(407 lines) written against a defunct adapter contract (`attr/attributes-by-name`
compile error; `wrap-datalevin-save`/`-delete` called with an options map — no such
arity; delta at top-level `::form/delta` instead of `::form/params → ::form/delta`).
It was ~90% redundant with `datalevin_test.clj`'s save coverage. Ported the two
genuinely-unique cases into `datalevin_test.clj` (to-many `:account/items` ref save;
save→resolve round-trip) using the current contract, then deleted the old file.
Confirmed empirically: the adapter **does** persist to-many `:ref` delta saves via
lookup-ref idents. Suite then 54 tests, 256 assertions (now 57/268 after dual-Pathom work).



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

Suite: **54 tests, 256 assertions, 0 failures**. Lint: **0 warnings**.

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

### Backlog (migrated from the retired PLAN.md)

Remaining adapter-parity / enhancement opportunities, roughly by value:

- **Bulk load** via `transact-async` / `init-db` / `fill-db`; **tuple round-trip**
  test under v1.0 storage.
- **Query result pruning (minimal pull)** — prune the pull pattern to just the
  client-requested keys (datomic adapter does this via `env->client-query-ast`).
- **Resolver transform support** — honor a `::pc/transform` / `::pco/transform`
  on generated resolvers.
- **Guardrails/spec coverage** — broaden `>defn` specs across the public API.
- **Indexed access / tuple scanning** — range/tuple resolvers (datomic
  `indexed_access`).
- **Transaction functions** — support `:db.fn/call`-style tx fns if a use case
  appears.

(PLAN.md was deleted — it had gone stale, e.g. still claiming Pathom 2 and
read-your-writes were unimplemented after both shipped. Working memory lives
here in `state.md`; `CHANGELOG.md` records shipped changes.)

## Blocking / open

- **Release deferred by human decision** — do NOT tag or deploy. CI is green,
  Clojars secrets are set, the pipeline is armed; the human will create and
  push the `v1.0.0-RC1` tag when ready for jars to go live. Before tagging:
  cut a `## [1.0.0-RC1]` section from `[Unreleased]` in CHANGELOG.md.

## Key files

- `src/main/.../datalevin/start_databases.clj` — schema gen, conn opts,
  `vec-conn-opts`, `merge-conn-opts`, `schema-problems`/`verify-schema!`.
- `src/main/.../datalevin/generate_resolvers.clj` — `id-resolver`,
  `all-ids-resolver` (mirror for search).
- `src/main/.../datalevin/wrap_datalevin_save.clj` — `save-form!`,
  `run-save-transact!`, `append-to-raw-txn`, `fix-numerics`.
- `src/main/.../datalevin/wrap_datalevin_delete.clj` — `delete-entity!`.
- `src/main/.../datalevin_options.cljc` — all `::dlo/*` option keys.
- `CHANGELOG.md` — host-facing changelog (shipped changes). Planning/working
  memory lives in this `state.md` (PLAN.md retired).
