---
okf_version: "0.1"
---

# fulcro-rad-datalevin Knowledge Bundle

Memory and knowledge for the Fulcro RAD **Datalevin** database adapter. This is
a bounded [Mementum](../MEMENTUM-LAMBDA.md) guest bundle — everything under
`mementum/` is memory infrastructure, not the host project itself.

## Working Memory

* [state.md](state.md) — session bootloader; **read first every session**

## Knowledge

* [Full-Text Search Design](knowledge/design/full-text-search.md) — PROPOSED design
  for RAD full-text search over Datalevin 1.0 (`::dlo/fulltext?` → schema +
  `:search-domains` + `:<entity>/search` resolver)

## Memories

One observation per file in [`memories/`](memories/). Filter by symbol with
`git grep "💡" mementum/memories/` etc. Symbols:
💡 insight · 🔄 shift · 🎯 decision · 🌀 meta · ❌ mistake · ✅ win · 🔁 pattern
