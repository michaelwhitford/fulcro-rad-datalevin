---
type: Reference
title: Datalevin Connection Options
status: active
tags: [datalevin, upstream, conn-opts, search-domains, vector-domains, wal, lmdb]
related:
  - ./core-api.md
  - ./schema-and-types.md
  - ./search.md
---

# Datalevin Connection Options

Verified against the Datalevin 1.0.0 source
([github.com/datalevin/datalevin](https://github.com/datalevin/datalevin)); `src:`
citations are repo-relative paths. This is the opts map accepted by `create-conn`, `get-conn`, `conn-from-datoms`, `empty-db`,
`init-db`, and `fill-db`. The adapter passes it through via the `:conn-opts`
config key in `start-database!` (Tier 1 pass-through).

## Datalog-Level Options

| Key | Type | Default | Meaning |
|---|---|---|---|
| `:validate-data?` | boolean | `false` | Validate value types during transact |
| `:closed-schema?` | boolean | `false` | Reject attributes not declared in schema |
| `:auto-entity-time?` | boolean | `false` | Maintain `:db/created-at` / `:db/updated-at` (long, epoch ms) |
| `:search-domains` | map | — | `{domain-string → search-engine-opts}` (below) |
| `:search-opts` | map | — | Defaults for `fulltext` when no domain override |
| `:vector-domains` | map | — | `{domain-string → vector-index-opts}` (below) |
| `:vector-opts` | map | — | Defaults for vector domains |
| `:kv-opts` | map | — | Passed to underlying LMDB `open-kv` (below) |
| `:wal?` | boolean | `false` | Enable write-ahead-log mode for new local stores |
| `:wal-durability-profile` | keyword | `:relaxed` | `:strict`, `:relaxed`, or `:extra` |
| `:wal-group-commit` | int | 128 | Max txns per durability batch (`:relaxed`) |
| `:wal-group-commit-ms` | int | 10 | Max ms per batch (`:relaxed`) |
| `:client-opts` | map | — | Client options when `dir` is a remote `dtlv://` URI |
| `:idoc-domains` / `:idoc-opts` | map | — | Indexed-document domains/defaults |

`src: src/datalevin/core.clj (create-conn docstring), src/datalevin/validate.clj (boolean-opts etc.), src/datalevin/constants.clj (*datalog-wal?*)`

## `:search-domains` Shape

A map from domain name string to search-engine options:

```clojure
{:search-domains
 {"product_description"
  {:index-position? true    ; term positions — required for phrase search
   :include-text?   false   ; store raw text in the index
   :analyzer        f       ; string → seq of [term position offset]
   :query-analyzer  f       ; query-time override of :analyzer
   :indexing-mode   :sync   ; :sync (default) or :async (eventually consistent)
   :search-opts     {}}}}   ; per-domain defaults for search calls
```

The default domain name is `"datalevin"`. Attribute-specific domains created by
`:db.fulltext/autoDomain` follow the naming rule: keyword without `:`, `/`
replaced by `_`.

`src: src/datalevin/search.clj (new-search-engine*), src/datalevin/constants.clj (default-domain)`

## `:vector-domains` Shape

A map from domain name string to vector-index options:

```clojure
{:vector-domains
 {"item_embedding"
  {:dimensions       300         ; REQUIRED — no default
   :metric-type      :euclidean  ; default
   :quantization     :float      ; default
   :connectivity     16          ; HNSW M
   :expansion-add    128         ; efConstruction
   :expansion-search 64          ; ef
   :indexing-mode    :sync}}}
```

**`:dimensions` is required and has no default.** This is why the adapter keeps
vector dimensions in conn-opts rather than schema. Domain names follow the same
`namespace_name` rule (e.g. `:item/embedding` → `"item_embedding"`).

`src: doc/vector.md, src/datalevin/constants.clj (default-* vector constants), src/datalevin/storage.clj (init-vector-domains)`

## `:kv-opts` — LMDB-Level Options

| Key | Default | Meaning |
|---|---|---|
| `:mapsize` | 100 MiB | Initial file size; auto-grows |
| `:max-readers` | 512 | Max concurrent readers |
| `:max-dbs` | 128 | Max sub-databases |
| `:flags` | `#{:nordahead :notls}` | LMDB environment flags |
| `:temp?` | `false` | Delete file on JVM exit (implies `:nosync`) |
| `:inmemory?` | `false` | No disk persistence |

Durability trade-off flags (via `:kv-opts {:flags …}`), from `doc/transact.md`:

- `:nometasync` — up to 5× faster; crash can lose the last txn, DB stays intact
- `:nosync` — up to 20× faster; crash may corrupt the DB
- `:writemap` + `:mapasync` — up to 25× faster; crash may corrupt; writable map

`src: src/datalevin/core.clj (open-kv docstring), src/datalevin/constants.clj (default-env-flags), doc/transact.md`

## Adapter Notes

- The adapter merges computed opts (`vec-conn-opts` for vector domains, enum
  seeding) with user-supplied `:conn-opts` in `start-database!`
  (`merge-conn-opts`). A future `search-conn-opts` for full-text should mirror
  `vec-conn-opts`.
- `get-conn` ignores schema/opts differences if a connection to the same dir is
  already open (it reuses); tests that need fresh opts must close first.
