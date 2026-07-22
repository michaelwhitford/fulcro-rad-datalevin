---
type: Reference
title: Fulcro RAD Database Adapter Contract
status: active
tags: [fulcro-rad, upstream, adapter, contract, resolvers, middleware, plugin]
related:
  - ./attributes.md
  - ./form-save-protocol.md
  - ./resolvers-and-pathom.md
---

# Fulcro RAD Database Adapter Contract

Verified against the fulcro-rad source
([github.com/fulcrologic/fulcro-rad](https://github.com/fulcrologic/fulcro-rad));
`src:` citations are repo-relative paths. Key finding: **there is no formal protocol** — no `defprotocol`, multimethod, or
spec defines "database adapter" in RAD core. The contract is de-facto,
documented in `docs/DevelopersGuide.adoc` and embodied by the Datomic adapter.
This page states that contract as this adapter implements it.

## Adapter Responsibilities

From the Developers Guide (none is enforced by RAD core; all are expected in
practice):

1. **Schema generation** — derive database schema from RAD attributes
   (`automatic-schema` here).
2. **Resolver generation** — Pathom resolvers that resolve entities by
   identity attribute, plus collection resolvers (`generate-resolvers`).
3. **Save middleware** — consume `::form/params` (delta) and persist
   (`wrap-datalevin-save`).
4. **Delete middleware** — entity deletion (`wrap-datalevin-delete`).
5. **Pathom plugin** — put connections/databases into env under
   adapter-namespaced keys (`pathom-plugin` → `::dlo/connections` /
   `::dlo/databases`).

Optional extras mentioned: schema validation, connection pooling, sharding, dev
mocking.

`src: docs/DevelopersGuide.adoc (lines ~873–917)`

## Attribute Options the Adapter Consumes

| Option | Adapter use |
|---|---|
| `ao/schema` | routes attribute to the right connection/database |
| `ao/identity?` | which attributes are PKs → id-resolvers + all-ids resolvers |
| `ao/identities` | entity membership → schema gen + resolver outputs |
| `ao/cardinality` | `:one`/`:many` → schema + save logic |
| `ao/target` / `ao/targets` | ref targets → schema refs + resolver joins |
| `ao/enumerated-values` | `:enum` → seeded enum idents |
| `ao/required?` | optional schema-level enforcement |

`src: src/main/com/fulcrologic/rad/attributes_options.cljc`

## Pathom Plugin Pattern

The adapter plugin receives a function from env to connections and invokes it
per request, so connection selection can depend on session/config:

```clojure
(adapter/pathom-plugin (fn [env] {:production the-conn}))
```

The returned map is keyed by **schema name** (matching `ao/schema` values).
The plugin assoc's the result into env under adapter-namespaced keys — in this
adapter, `::dlo/connections` and `::dlo/databases`.

`src: docs/DevelopersGuide.adoc (pathom-plugin usage, line ~742)`

## Resolver Expectations

Two resolver kinds are the minimum viable surface:

1. **Id-resolver** — given an ident like `[:account/id uuid]`, resolve the
   entity's attributes. RAD forms load via ident queries
   (`[{[:account/id id] form-query}]`), so this is mandatory for forms.
2. **Collection resolver** — a global (no-input) resolver returning all
   entities as a vector of idents, used by reports via `ro/source-attribute`.

Collection resolvers should return idents only and let the batched id-resolver
fill fields (this adapter's `all-ids-resolver` pattern; see the memory
`list-ident-resolver-pattern`).

`src: docs/DevelopersGuide.adoc (lines ~907–917)`

## Middleware Contract

Save and delete middleware are functions of the pathom env (with
`::form/params` assoc'd), installed via `form/wrap-env` (Pathom 3) or
`form/pathom-plugin` (Pathom 2). Adapters expose a two-arity wrapper
(terminal / composing) so applications can chain their own middleware around
the database write. Full detail in
[form-save-protocol.md](./form-save-protocol.md).

`src: src/main/com/fulcrologic/rad/form.cljc (wrap-env, pathom-plugin), docs/DevelopersGuide.adoc`

## Attribute-Level Resolvers (RAD-side generation)

Separate from adapter-generated resolvers, RAD generates resolvers for any
attribute that carries a resolve fn:

- Pathom 2: `com.fulcrologic.rad.resolvers/generate-resolvers` — picks up
  `::pc/resolve`, defaults output to `[qualified-key]`, merges all `::pc/*`
  keys, wraps the fn in `secure-resolver` (auth redaction). Resolver sym is
  `(symbol (str k "-resolver"))`.
- Pathom 3: `com.fulcrologic.rad.resolvers-pathom3/generate-resolvers` — same
  idea for `::pco/resolve`; op-name preserves the keyword namespace.

Applications typically concatenate: RAD attribute resolvers + adapter-generated
resolvers + `form/resolvers` (save/delete mutations).

`src: src/main/com/fulcrologic/rad/resolvers.cljc, src/main/com/fulcrologic/rad/resolvers_pathom3.cljc, src/main/com/fulcrologic/rad/form.cljc (def resolvers)`

## Divergences to Remember (this adapter vs Datomic adapter)

- Datalevin has no db-as-value; resolvers read through live connections.
- Native `:db/id` support is opt-in per attribute (`::dlo/native-id?`).
- Value coercion (`fix-numerics`) happens in save middleware because Datalevin
  validates types only when `:validate-data?` is on.
