---
type: Reference
title: Datalevin Core Datalog API
status: active
tags: [datalevin, upstream, api, datalog, connection]
related:
  - ./transactions.md
  - ./schema-and-types.md
  - ./connection-options.md
---

# Datalevin Core Datalog API

Verified against the Datalevin 1.0.0 source
([github.com/datalevin/datalevin](https://github.com/datalevin/datalevin); version
declared in `src/datalevin/constants.clj`). All `src:` citations are paths
relative to that repo root. This page covers the
`datalevin.core` functions the adapter uses (aliased `d`). Datomic divergences
are flagged explicitly — do not assume Datomic parity.

## Connections

### `create-conn` and `get-conn`

Both take `[dir]`, `[dir schema]`, or `[dir schema opts]`. `dir` may be a local
path or a `dtlv://` URI string. `create-conn` opens or creates the database at
`dir`, merging the given schema into the persisted schema. `get-conn` behaves
the same but **reuses an existing open connection** to the same `dir` if one
exists; it only creates a fresh connection when none is open (or when the opts
contain `:runtime-opts`, which forces a new connection).

The returned connection is a mutable wrapper around the database (an
`IDeref`/atom-like object). Connection options are documented in
[connection-options.md](./connection-options.md).

`src: src/datalevin/core.clj (create-conn), src/datalevin/conn.clj (get-conn)`

### `close`

`(close conn)` closes the connection and returns `nil`. Connections to the same
`dir` are reference-counted: the underlying LMDB store only closes when the
last reference is released. This matters for tests that open several
connections to one database.

`src: src/datalevin/conn.clj (close)`

### `db` — not a value

`(db conn)` returns the underlying DB object. **Datomic divergence:** the
docstring is explicit that Datalevin has no "db as a value" feature — the
returned object is a *reference* to the live database, not an immutable
snapshot. Any code that assumes Datomic-style immutable db values is wrong
here.

`src: src/datalevin/conn.clj (db)`

## Query and Pull

### `q`

`(q query & inputs)` executes a Datalog query in Datomic query format.
Datalevin extends the query map with three extra clauses:

- `:order-by` — with optional `:asc`/`:desc` per variable
- `:limit` — result count cap
- `:timeout` — per-query timeout in milliseconds

These are useful for building paginated or bounded collection resolvers
without post-processing in Clojure.

`src: src/datalevin/core.clj (q docstring)`

### `pull` and `pull-many`

`(pull db pattern id)` returns a plain map following Datomic pull pattern
syntax; an optional 4th `opts` argument supports a `:visitor` function of four
args `[pattern e a v]` for side effects during the pull. `pull-many` is the
same but takes a sequence of ids and returns a sequence of maps. The adapter's
batched id-resolver is built on `pull-many`.

`src: src/datalevin/core.clj (pull, pull-many)`

### `entity`

`(entity db eid)` returns a lazy, map-like `Entity`. `eid` may be a numeric
entity id or a lookup ref `[unique-attr value]`. Cardinality-many attributes
return sequences; ref attributes return nested entities; reverse refs use the
`_` prefix (e.g. `:ns/_ref`).

`src: src/datalevin/core.clj (entity)`

### `entid`

`(entid db eid)` resolves an eid-like value to a numeric entity id:

- Lookup ref `[unique-attr value]` → numeric eid, or `nil` if absent
- Keyword → looked up by `:db/ident`
- **Non-negative integer → returned as-is, without an existence check**
  (Datomic divergence; do not use `entid` to test existence of numeric ids)

The delete middleware uses `entid` to resolve RAD idents to entity ids before
retraction.

`src: src/datalevin/db/tx/common.clj (entid)`

## Schema Introspection

- `(schema conn)` returns the current schema map. Because the db is a live
  reference, this always reflects current state.
- `(update-schema conn schema-update)` — also `[conn schema-update del-attrs]`
  and `[conn schema-update del-attrs rename-map]` — adds/changes attributes,
  deletes attributes (only if no datoms reference them; throws otherwise), and
  renames attributes. Returns the updated schema map.

Schema change restrictions (rejected when data exist) are covered in
[schema-and-types.md](./schema-and-types.md).

`src: src/datalevin/conn.clj (schema, update-schema)`

## Write API (summary)

`transact!`, `transact-async`, `transact`, `with-transaction`, `init-db`, and
`fill-db` are covered in depth in [transactions.md](./transactions.md). The
short version:

- `(transact! conn tx-data)` / `(transact! conn tx-data tx-meta)` — synchronous;
  returns a `TxReport` record with `:db-before :db-after :tx-data :tempids
  :tx-meta`. The connection is updated in place (Datomic divergence: `transact!`
  does not return the conn).
- Tempids are negative integers **or strings** in tx-data; `:tempids` in the
  report maps each to its assigned entity id.

`src: src/datalevin/conn.clj (transact!), src/datalevin/db.clj (TxReport)`
