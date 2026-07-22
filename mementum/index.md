---
okf_version: "0.1"
---

# fulcro-rad-datalevin Knowledge Bundle

Memory and knowledge for the Fulcro RAD **Datalevin** database adapter. This is
a bounded Mementum guest bundle — the protocol lambdas are inlined in the host's
[`AGENTS.md`](../AGENTS.md) (S5 `λ mementum`, S4 metabolism, S1 recipes).
Everything under `mementum/` is memory infrastructure, not the host project itself.

## Working Memory

* [state.md](state.md) — session bootloader; **read first every session**

## Knowledge

* [Full-Text Search Design](knowledge/design/full-text-search.md) — PROPOSED design
  for RAD full-text search over Datalevin 1.0 (`::dlo/fulltext?` → schema +
  `:search-domains` + `:<entity>/search` resolver)

### Upstream Reference — Datalevin ([datalevin/datalevin](https://github.com/datalevin/datalevin) @ 1.0.0)

Source-verified reference pages for the upstream Datalevin API:

* [Core Datalog API](knowledge/upstream/datalevin/core-api.md) — conn lifecycle,
  q/pull/entity/entid, db-is-not-a-value, Datomic divergences
* [Schema and Value Types](knowledge/upstream/datalevin/schema-and-types.md) —
  16 value types, schema keys, `:db.attr/preds`, fulltext/vec schema keys
* [Connection Options](knowledge/upstream/datalevin/connection-options.md) —
  conn-opts contract: `:search-domains`, `:vector-domains`, WAL, `:kv-opts`
* [Full-Text Search](knowledge/upstream/datalevin/search.md) — `fulltext` in
  Datalog, search opts/display modes, analyzers, domains, gotchas
* [Transactions](knowledge/upstream/datalevin/transactions.md) — TxReport,
  tempids, `with-transaction`, `:db/ensure`, error contract, bulk load

### Upstream Reference — Fulcro RAD ([fulcrologic/fulcro-rad](https://github.com/fulcrologic/fulcro-rad))

Source-verified reference pages for the RAD side of the adapter contract:

* [Attributes](knowledge/upstream/fulcro-rad/attributes.md) — `defattr`, `ao/*`
  options, open type system, registry/env wiring
* [Database Adapter Contract](knowledge/upstream/fulcro-rad/database-adapter-contract.md) —
  the de-facto contract: schema gen, resolvers, middleware, pathom plugin
* [Form Save Protocol](knowledge/upstream/fulcro-rad/form-save-protocol.md) —
  delta shape, `::form/params`, tempid return contract, delete protocol
* [Resolvers and Pathom](knowledge/upstream/fulcro-rad/resolvers-and-pathom.md) —
  **`:query-params` env key** (report param chain, verified for Pathom 2 & 3)

## Memories

One observation per file in [`memories/`](memories/). Filter by symbol with
`git grep "💡" mementum/memories/` etc. Symbols:
💡 insight · 🔄 shift · 🎯 decision · 🌀 meta · ❌ mistake · ✅ win · 🔁 pattern
