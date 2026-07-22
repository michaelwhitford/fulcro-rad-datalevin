---
type: Reference
title: Datalevin Transactions
status: active
tags: [datalevin, upstream, transactions, tx-meta, db-ensure, bulk-load, wal]
related:
  - ./core-api.md
  - ./connection-options.md
  - ../../../memories/middleware-must-propagate-failures.md
---

# Datalevin Transactions

Verified against the Datalevin 1.0.0 source
([github.com/datalevin/datalevin](https://github.com/datalevin/datalevin), source
plus `doc/transact.md`); `src:` citations are repo-relative paths. This is the
write path behind `save-form!` / `run-save-transact!` and the delete
middleware.

## `transact!`

`(transact! conn tx-data)` or `(transact! conn tx-data tx-meta)`. Synchronous;
returns a `TxReport` record:

```clojure
(defrecord TxReport [db-before db-after tx-data tempids tx-meta])
```

- `:tx-data` — vector of Datom objects added/retracted
- `:tempids` — map of tempid → assigned entity id
- `:tx-meta` — the tx-meta value passed in, returned verbatim (it is *not*
  stored as a datom; there is no auto `:db/txInstant`)

Tempids in tx-data are negative integers (`[:db/add -1 :name "Ivan"]`) **or
strings** (`[:db/add "ivan" :name "Ivan"]`); both appear in `:tempids`
(e.g. `{-1 296}`, `{"ivan" 297}`). The adapter passes tx-meta through
`::dlo/transact-options`.

`src: src/datalevin/conn.clj (transact!), src/datalevin/db.clj (TxReport)`

## `with-transaction`

```clojure
(d/with-transaction [tx-conn conn]                  body…)
(d/with-transaction [tx-conn conn {:timeout-ms 5000}] body…)
```

Runs the body in a single read/write transaction. Timeout semantics: the
transaction thread is interrupted and the txn aborted when control returns to
the macro — non-interruptible user code keeps running until it returns. A
global default timeout is managed with `set-explicit-transaction-timeout!` /
`explicit-transaction-timeout` (default nil = none). Explicit rollback from
inside the body: `abort-transact` (Datalog) / `abort-transact-kv` (KV).

The adapter's `::dlo/transaction-timeout-ms` maps to the `:timeout-ms` option.

`src: src/datalevin/conn.clj (with-transaction), src/datalevin/core.clj (explicit-transaction-timeout)`

## `:db/ensure` — Post-Conditions

```clojure
[:db/ensure pred arg1 …]   ; minimum 3 elements, else arity error
```

- `pred` may be a callable fn/var, a qualified symbol, a UDF descriptor map, or
  a registered UDF keyword id.
- Args are resolved before the call: tempids → assigned eids,
  `:db/current-tx` → txn entity id, everything else passes through.
- Evaluated **after** all tx-data has been applied, over `db-after`. The call
  is `(apply pred db resolved-args)` — the predicate receives the db first,
  then the resolved args.
- **Any falsey return aborts the transaction** with ex-data
  `{:error :transact/ensure :predicate pred :args args :result result}`.

**Datomic divergence:** Datomic's `:db/ensure` attaches entity specs; Datalevin
passes the db plus positional args, not an entity map. The adapter exposes this
via `::dlo/raw-txn` / `append-to-raw-txn`.

`src: src/datalevin/db/tx/execute.clj (handle-ensure), src/datalevin/db.clj (run-report-ensures!), src/datalevin/validate.clj (validate-ensure-*)`

## Error Behavior

Failures throw `ExceptionInfo` (via `u/raise`) whose ex-data always includes an
`:error` key:

- `:transact/attr-pred` — attribute predicate failed
- `:transact/ensure` — `:db/ensure` post-condition failed
- `:transact/cas` — `:db.fn/cas` mismatch
- `:schema/validation` — invalid schema definition

On failure the LMDB transaction rolls back; no partial write is visible to
other readers. The adapter must **propagate** these (see the memory
[middleware-must-propagate-failures](../../../memories/middleware-must-propagate-failures.md)) —
it wraps them in `ex-info` with `{:schema :txn-data}` context and rethrows.

`src: src/datalevin/validate.clj (u/raise call sites), doc/transact.md`

## Concurrency Model

LMDB MVCC: reads and writes never block each other, but **writes are
serialized** — one writer thread at a time. Each commit flushes to disk by
default (fully durable); WAL mode and env flags trade durability for
throughput (see [connection-options.md](./connection-options.md)).

`src: doc/transact.md`

## Async and Bulk Writes

### `transact-async` / `transact`

`(transact-async conn tx-data tx-meta callback)` returns a future immediately;
an adaptive batching algorithm grows batch size under load (order-of-magnitude
throughput gains under heavy writes). Transactions commit in submission order.
`(transact conn tx-data)` is the blocking variant — useful as a fence: when it
returns, all previously submitted async transactions are also committed.

`src: src/datalevin/conn.clj (transact-async, transact), doc/transact.md`

### `init-db` / `fill-db`

```clojure
(d/init-db datoms dir schema opts) ; EMPTY db only
(d/fill-db db datoms)              ; append to non-empty db
```

Low-level bulk load of already-correct datoms: **no validation, no tempid
resolution** — the caller supplies real entity ids. Batch size is controlled by
`datalevin.constants/*fill-db-batch-size*` (default 2,097,152 datoms). For bulk
load *with* validation, prefer `transact-async` in WAL mode.

These back the "bulk load" item on the adapter's PLAN.md backlog.

`src: src/datalevin/core.clj (init-db, fill-db), doc/transact.md (bulk load)`
