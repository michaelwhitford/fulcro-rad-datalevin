---
type: Insight
symbol: 💡
title: Datalevin :vec dimensions live in connection opts, not schema
related:
  - ../knowledge/design/full-text-search.md
---

💡 For Datalevin `:db.type/vec` attributes, the vector index config (notably
`:dimensions`) is NOT a valid schema key. It must be passed to `d/get-conn` as
`{:vector-domains {"domain" {:dimensions N}}}`. The schema attribute only names
which domain(s) it belongs to. Domain name = `attr-domain` (qualified key with
`/` → `_`). Verified against Datalevin 1.0.0 `storage.clj/init-vector-domains`,
which walks the schema for `:db.type/vec` attrs but reads dimensions from the
connection option. The adapter strips `:db.vec/dimensions` from the schema map
in `attr->schema` and derives `vec-conn-opts`. Full-text search follows the same
split (`:search-domains` in conn opts).
