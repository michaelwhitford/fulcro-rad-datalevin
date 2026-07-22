---
type: Design
title: Full-Text Search for fulcro-rad-datalevin
status: open
tags: [datalevin, full-text-search, rad, resolvers, pathom]
related:
  - ../../state.md
  - ../../memories/list-ident-resolver-pattern.md
  - ../../memories/native-keys-vs-first-class-option.md
---

# Design: Full-Text Search for fulcro-rad-datalevin

> Status: **PROPOSED — not yet implemented.** Design frozen for pickup in a new
> session. Nothing in this document has been coded yet.

## Memory anchors (6 interrogatives)

```clojure
{:memory/anchors
 {:who/needs-this        ["apps using this adapter that need keyword/full-text search"
                          "the 'next project' that already uses :vec vectors → hybrid search"]
  :what/core-identity    "Map Datalevin 1.0 full-text search onto RAD as a parameterized :<entity>/search resolver that returns relevance-ordered idents, mirroring the existing :<entity>/all all-ids-resolver."
  :what/invariants       ["search results are a list of idents in relevance order"
                          "fields are filled by the EXISTING batched id-resolver, not re-fetched"
                          ":db/fulltext lives in schema; :search-domains lives in conn-opts"
                          "one search domain per entity type (domain = attribute namespace)"]
  :when/signals          ["user asks for search / keyword / relevance / fulltext"
                          "::dlo/fulltext? option" ":db/fulltext" "d/fulltext" ":search-domains"]
  :why/prevents          ["reinventing a search resolver per app; storing text twice"]
  :why/enables           ["RAD reports/lists backed by full-text search"
                          "hybrid semantic (:vec) + keyword search for the next project"]
  :where/compounds-with  ["Tier-1 :conn-opts pass-through (already shipped) carries :search-domains"
                          "vec-conn-opts pattern (mirror it for search-conn-opts)"
                          "all-ids-resolver pattern (mirror it for the search resolver)"
                          "native-id? handling in resolvers (reuse for id mapping)"]
  :how/verify            ["round-trip: index docs via save, d/fulltext returns ranked eids"
                          "generated :<ns>/search resolver returns idents in rank order"
                          "RAD load with {:params {:query ...}} reaches the resolver"]
  :how/fail-modes        ["phrase search without :index-position? → error"
                          "params not forwarded by RAD report → resolver gets no query"
                          "silently defaulting domain to \"datalevin\" instead of per-entity"]}}
```

---

## 1. Context

We upgraded the adapter to **Datalevin 1.0.0** and wired most of its new
capabilities (see `CHANGELOG.md` "Unreleased" and `PLAN.md` "Datalevin 1.0.0
Wiring"). Full-text search is the remaining strategic feature, called out because
the next project pairs it with `:vec` vector attributes for **hybrid
semantic + keyword search**.

This document is the design to implement it. It was written after reading
`~/datalevin/doc/search.md` in full and the adapter source.

## 2. The core problem

RAD is **EQL-driven**: a client asks for keys, resolvers produce them. Datalevin
search is a **query predicate** `(fulltext $ ?q opts)` returning `[e a v]`
tuples. The integration problem is: **turn a parameterized search into a
resolvable EQL key.**

Key realization: the adapter already has the exact shape to mirror.
`all-ids-resolver` (in `datalevin/generate_resolvers.clj`) produces
`{:account/all [{:account/id …} …]}` — a list of idents consumed by RAD
reports/lists, with fields auto-filled by the batched id-resolver. **Full-text
search is the same shape, just parameterized by a query string.**

## 3. Datalevin 1.0 full-text API (grounded facts)

From `~/datalevin/doc/search.md`:

- **Schema:** an attribute is searchable with `:db/fulltext true`. Value need not
  be a string (indexer calls `str`). Optional:
  - `:db.fulltext/domains ["d1" "d2"]` — which domains the attribute joins.
  - `:db.fulltext/autoDomain true` — attribute becomes its own domain, named
    after the attribute (without the leading `:`).
  - Default (no domains) → attribute joins the default `"datalevin"` domain.
- **Connection config:** `:search-domains {"domain" {:index-position? true …}}`
  passed to `get-conn`. `:search-opts` gives default search options.
  `:index-position? true` is REQUIRED for phrase/proximity search (storage cost).
- **Query predicate:** `(fulltext $ ?q opts)` in Datalog, returns `[e a v]`
  tuples ordered by relevance. Also `(fulltext $ :attr ?q opts)` for
  attribute-specific. `opts` supports `:top` (default 10), `:limit` (page size),
  `:offset`, `:display` (`:refs`|`:refs+scores`|`:texts`|`:offsets`|
  `:texts+offsets`), `:doc-filter` (fn of doc-ref), `:domains` (list to search).
- **Result shapes by `:display`:** `:refs`→`[e a v]`, `:refs+scores`→`[e a v score]`,
  `:texts`→`[e a v text]`, etc.
- **Query syntax:** boolean `[:and [:or "x" "y"] [:not "z"]]` and phrases
  `{:phrase "little lamb"}` (phrase requires `:index-position? true`).
- **Indexing:** synchronous by default (read-your-writes). Opt-in async per
  domain via `:indexing-mode :async` (eventual consistency;
  `d/secondary-index-status`, `d/wait-for-secondary-index`).
- **Standalone API** (not needed for RAD path): `new-search-engine`, `add-doc`,
  `remove-doc`, `search`.

## 4. Design — three layers

### Layer 1 — Schema: mark an attribute searchable

**Decision: first-class `::dlo/fulltext?` option** (not just native pass-through).

Rationale: unlike `:db.attr/preds` (pure schema pass-through, so we used the
native key inside `::dlo/attribute-schema`), full-text is **cross-cutting** — it
must drive schema **and** connection opts **and** a generated resolver. That
justifies a first-class option, exactly like `::dlo/native-id?` which also spans
schema+resolver+save.

```clojure
(defattr name :account/name :string
  {::attr/identities #{:account/id}
   ::dlo/fulltext?   true})            ; or a map, e.g. {:index-position? true}
```

In `attr->schema` (`datalevin/start_databases.clj`), when `::dlo/fulltext?` is
truthy, emit into the datalevin schema:
- `:db/fulltext true`
- `:db.fulltext/domains [<entity-domain>]` where `<entity-domain>` = the
  attribute's namespace string (e.g. `"account"`) — UNLESS the user supplied
  explicit domains. This gives **one search domain per entity type**, so all of
  an entity's searchable attributes are searched together.

Mirror the existing `vec-attr-domain`/vec handling (which strips
`:db.vec/dimensions` and derives a domain). See `start_databases.clj`
`attr->schema` and `vec-conn-opts` for the exact pattern to copy.

Also keep native pass-through working: if a user hand-writes `:db/fulltext true`
in `::dlo/attribute-schema`, respect it (detect via
`(get-in attr [::dlo/attribute-schema :db/fulltext])`).

### Layer 2 — Connection: register the search domain

Add a `search-conn-opts` deriver **parallel to `vec-conn-opts`** in
`start_databases.clj`: for each schema, collect entities with searchable
attributes and produce
`{:search-domains {"account" {:index-position? <bool>}}}`.

This rides through the **Tier-1 `:conn-opts` merge machinery already shipped**
(`merge-conn-opts` in `start_databases.clj`, and `start-database!` already merges
derived opts with user `:conn-opts` and passes to `get-conn`). So extend
`start-database!` to also merge `search-conn-opts` (currently it only merges
`vec-conn-opts`).

`:index-position?` is opt-in per attribute (`::dlo/fulltext? {:index-position?
true}`) because it costs index storage and is only needed for phrase/proximity.

### Layer 3 — Resolver: a parameterized `:<entity>/search`

In `generate-resolvers` (`datalevin/generate_resolvers.clj`), emit — per entity
type that has searchable attributes — a resolver parallel to `all-ids-resolver`:

- **Output:** `{:<ns>/search [{<id-attr> …}]}` (relevance-ordered idents).
- **Params (from Pathom):** `:query` (required), `:limit`, `:offset`, `:top`.
- **Body sketch:**
  ```clojure
  (fn [{::dlo/keys [databases] :as env} _input]
    (let [{:keys [query limit offset top]} (pathom-params env)   ; SEE RISK #1
          db   (get databases schema)
          opts (cond-> {:domains [entity-domain]}
                 top    (assoc :top top)
                 limit  (assoc :limit limit)
                 offset (assoc :offset offset))
          eids (d/q '[:find [?e ...]
                      :in $ ?q ?opts
                      :where [(fulltext $ ?q ?opts) [[?e _ _]]]]
                    db query opts)]
      {search-key (mapv (fn [eid] {id-key (eid->id db eid)}) eids)}))
  ```
  Map `?e` → identity ident, **native-id aware** (reuse the native-id logic from
  `all-ids-resolver` / `id-resolver`: for native-id entities the id IS the eid;
  otherwise pull the id-attr value). Preserve relevance order (use `:find [?e
  ...]` ordered, or dedupe while preserving order).

**Client usage (pure RAD):**
```clojure
(df/load! app :account/search AccountSearchList
          {:params {:query "red fox" :limit 20}})
;; => relevance-ordered [{:account/id …} …]; columns filled by id-resolver
```

## 5. Decisions

Resolved:
- **First-class `::dlo/fulltext?` option** (cross-cutting → warrants an option).
- **One domain per entity type**, domain name = attribute namespace.
- **Resolver returns idents in relevance order**; fields via existing id-resolver.
- **`:index-position?` opt-in** per attribute.
- **Sync indexing** (read-your-writes) for now; async is a later opt-in.

Open (need a call):
1. **Scores.** Start WITHOUT surfacing relevance score (keep the ident model
   clean). `:display :refs+scores` could later feed a synthetic score key.
2. **Whole-DB / cross-entity search.** Start per-entity. A separate optional
   `:datalevin.search/results` resolver for global search can come later.
3. **Pagination → RAD.** Map RAD report paging controls to `:limit`/`:offset`
   (Datalevin caches a top-k window, so paging is efficient). Wire when doing the
   report integration.

## 6. Risks / unknowns (do these first)

**RISK #1 — Pathom param plumbing (the riskiest unknown).** Must verify exactly
how a RAD report/`df/load!` forwards `{:params {:query …}}` into a Pathom3
resolver, and how the resolver reads them (likely `com.wsscode.pathom3.connect.operation/params`
or the EQL AST params). **Prove this end-to-end BEFORE building the generator.**
Suggested spike: a hand-written resolver `:account/search` that echoes the params
back, loaded via a RAD report, asserting the query string arrives.

- Verify in the working test app: `~/src/datalevin-test-app`.
- Pathom3 access is roughly `(-> env :com.wsscode.pathom3.connect.planner/node …)`
  or a `pco/params`-style helper — CONFIRM the exact call.

**RISK #2 — phrase search without `:index-position?`** throws. Ensure the derived
`search-conn-opts` sets `:index-position?` whenever any searchable attr requests
phrase capability, and document the requirement.

**RISK #3 — domain naming collisions** between derived per-entity domains and any
user-specified `:search-domains`/`:vector-domains`. `merge-conn-opts` currently
special-cases `:vector-domains` merge; add the same care for `:search-domains`
(user opts should merge, not clobber).

## 7. Implementation plan (phased)

- **Phase 0 — Spike (do first):** prove RISK #1 param round-trip with a
  hand-written resolver in a test. Also a low-level `d/fulltext` round-trip test
  (index via `:db/fulltext` schema + save, query ranked eids).
- **Phase 1 — Schema + connection (easily testable, no Pathom):**
  - `::dlo/fulltext?` option in `datalevin_options.cljc`.
  - `attr->schema`: emit `:db/fulltext true` + derived `:db.fulltext/domains`.
  - `search-conn-opts` deriver + merge into `start-database!`.
  - Tests: schema has fulltext keys; conn has search domain; `d/fulltext` returns
    ranked results after a save.
- **Phase 2 — Resolver generation:**
  - `search-resolver` in `generate_resolvers.clj` (mirror `all-ids-resolver`),
    native-id aware, params-driven.
  - Register in `generate-resolvers`.
  - Re-export nothing new needed (resolvers flow through `generate-resolvers`).
  - Tests: generated `:<ns>/search` returns idents in rank order; native-id path.
- **Phase 3 — RAD report integration + pagination + docs.**

## 8. Files to touch (grounding)

- `src/main/.../datalevin_options.cljc` — add `::dlo/fulltext?` (+ docstring like
  `native-id?`/`attribute-schema`).
- `src/main/.../datalevin/start_databases.clj` — `attr->schema` (mirror the `:vec`
  branch), new `search-conn-opts` (mirror `vec-conn-opts`), extend `start-database!`
  merge (currently merges only `vec-conn-opts`).
- `src/main/.../datalevin/generate_resolvers.clj` — new `search-resolver` (mirror
  `all-ids-resolver`), wire into `generate-resolvers`.
- `src/test/.../` — new `search_test.clj`.
- `CHANGELOG.md` / `PLAN.md` (PLAN has a "Remaining v1.0 opportunities" note).

## 9. Patterns to mirror (concrete anchors in this codebase)

- **`vec-conn-opts` + `vec-attr-domain` + the `:vec` branch in `attr->schema`**
  (`start_databases.clj`) — the template for schema derivation + conn-opts.
- **`all-ids-resolver`** (`generate_resolvers.clj`) — the template for a
  list-of-idents resolver, including the native-id `sample-attr` logic and the
  `{all-ids-key [...]}` output shape.
- **`id-resolver`'s native-id handling** — for mapping `?e`/`:db/id` back to the
  identity key.
- **Tier-1 `merge-conn-opts` / `:conn-opts`** (`start_databases.clj`) — already
  carries arbitrary `get-conn` options; `search-conn-opts` merges here.

## 10. Notes

- Because Tier-1 `:conn-opts` pass-through already ships, a user can wire
  full-text search **manually today** (`:search-domains` via `:conn-opts` +
  `:db/fulltext true` via `::dlo/attribute-schema` + a hand-written resolver).
  This design is about making it **first-class and automatic**.
- Datalevin's search engine stores only a reference to source text (no double
  storage) and beats Lucene on search speed (per its docs/benchmark).
- Working test app for end-to-end verification: `~/src/datalevin-test-app`.
