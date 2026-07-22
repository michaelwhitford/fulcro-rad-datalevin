---
type: Reference
title: Fulcro RAD Resolvers and Pathom Integration
status: active
tags: [fulcro-rad, upstream, pathom, resolvers, reports, query-params]
related:
  - ./database-adapter-contract.md
  - ./attributes.md
  - ../../design/full-text-search.md
---

# Fulcro RAD Resolvers and Pathom Integration

Verified against the fulcro-rad source
([github.com/fulcrologic/fulcro-rad](https://github.com/fulcrologic/fulcro-rad));
`src:` citations are repo-relative paths. The headline fact — critical for the full-text search resolver — is how query
params travel from a RAD report to a resolver.

## Query Params Reach Resolvers via `:query-params` — VERIFIED

Both RAD parsers normalize EQL node params into the env under the **plain
(non-namespaced) key `:query-params`**:

- **Pathom 2** (`com.fulcrologic.rad.pathom`): the standard parser installs
  `query-params-to-env-plugin`, which walks the top-level AST children and
  merges each non-mutation node's `:params` into env as `:query-params`.
- **Pathom 3** (`com.fulcrologic.rad.pathom3/new-processor`): the processor
  computes `(rpc/combined-query-params ast)` — the *same* logic, shared in
  `pathom-common.clj` — and assoc's it into env as `:query-params` before
  calling `p.eql/process`.

So a resolver reads params as `(:query-params env)` in both Pathom versions.
RAD does **not** use Pathom 3's native params mechanism for this; it normalizes
both to one key. Example from RAD's own docstrings:

```clojure
ao/pc-resolve (fn [{:keys [query-params] :as env} _]
                {:account/all-accounts (queries/get-all-accounts env query-params)})
```

`src: src/main/com/fulcrologic/rad/pathom.clj (query-params-to-env-plugin), src/main/com/fulcrologic/rad/pathom3.clj (new-processor), src/main/com/fulcrologic/rad/pathom_common.clj (combined-query-params)`

## How a Report Sends Params

The chain, fully verified in source:

1. Report controls hold values; `current-control-parameters` collects all
   non-nil control values from state.
2. `load-report!` passes them to `uism/load` as `{:params current-params …}`
   against `ro/source-attribute`.
3. Fulcro's load puts `:params` on the EQL query node; they travel in the AST.
4. Server-side, `combined-query-params` / `query-params-to-env-plugin` merge
   them into env `:query-params`.
5. The resolver registered for `ro/source-attribute` reads
   `(:query-params env)`.

`ro/source-attribute` is "a qualified keyword used as the entry-point key for
the query" — the server must have a global resolver for it.
`ro/query-inclusions` adds extra EQL keys to the report's root query.

This chain is what the full-text `:<entity>/search` resolver plugs into: a
report whose source attribute is the search key, with the query string as a
control parameter.

`src: src/main/com/fulcrologic/rad/report.cljc (load-report!, current-control-parameters), src/main/com/fulcrologic/rad/report_options.cljc (source-attribute)`

## Pathom 2 Parser (`pathom/new-parser`)

`(new-parser config extra-plugins resolvers)` builds a parser with, in order:
`pc/connect-plugin` (resolver registry), caller plugins (attr plugin, form
plugin, adapter plugin), `p/env-plugin` (config), `query-params-to-env-plugin`,
`p/error-handler-plugin`, optional logging/trace. Notable parser args:

- `::pc/mutation-join-globals [:tempids]` — merges `:tempids` from mutation
  responses globally (tempid remapping on the client)
- readers include `pc/open-ident-reader` — ident queries (form loads) work
- placeholder prefix `#{">"}`

`src: src/main/com/fulcrologic/rad/pathom.clj (parser-args, new-parser)`

## Pathom 3 Processor (`pathom3/new-processor`)

Builds env via caller-supplied `env-middleware` (typically `attr/wrap-env` +
`form/wrap-env` + adapter `wrap-env` composed), assoc's `:query-params`, and
runs `p.eql/process` with `{:pathom/lenient-mode? true}`. It also assoc's
`:parser` for P2 compatibility.

`convert-resolvers` / `pathom2->pathom3` auto-convert Pathom 2 resolver maps
(`::pc/sym` → `::pco/op-name`, `::pc/input` → vector `::pco/input`,
`::pc/output` → `::pco/output`, `::pc/batch?` → `::pco/batch?`, transform
applied at conversion). Caveat from the docstring: the translation isn't
perfect because the env differs between versions.

`src: src/main/com/fulcrologic/rad/pathom3.clj (new-processor, pathom2->pathom3, convert-resolvers)`

## Form Loads (id-resolver dependency)

`start-form!` → edit path → `start-edit` issues `uism/load` of the form ident
`[id-key id]` with the form's query. On the wire that is an ident join
(`[{[:account/id uuid] […]}]`), answered by the adapter's id-resolver. Route
ids are coerced from strings by `ids/id-string->id` using the identity
attribute's `::attr/type`.

`src: src/main/com/fulcrologic/rad/form.cljc (start-form!, start-edit, form-will-enter)`

## Attribute-Level Resolver Generation

For attributes with hand-written resolvers (`ao/pc-resolve` /
`ao/pathom3-resolve`), RAD generates resolvers with output defaulting to
`[qualified-key]` and auth redaction via `secure-resolver`. See
[database-adapter-contract.md](./database-adapter-contract.md) for details.

`src: src/main/com/fulcrologic/rad/resolvers.cljc, src/main/com/fulcrologic/rad/resolvers_pathom3.cljc`

## Implication for the Phase 0 Spike (RISK #1)

The param round-trip is already verified **in source**: report control params
arrive at the resolver under `(:query-params env)` for both Pathom 2 and 3.
The spike in `datalevin-test-app` should confirm this at runtime (which pathom
version the app uses, and that `{:params {:query …}}` on a raw `df/load!` — not
just report controls — also lands in `:query-params`, since
`combined-query-params` merges params from *any* non-mutation top-level node).
