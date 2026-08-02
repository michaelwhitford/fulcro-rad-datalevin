---
type: Pattern
symbol: 🔁
title: Datalog set semantics drop engine rank order — always sort by score/dist
related:
  - ../knowledge/design/full-text-search.md
---

🔁 Datalevin's ranked-search Datalog predicates return results in engine order
(relevance for `fulltext`, similarity for `vec-neighbors`), but Datalog's set
semantics destroy that order the moment results pass through `:find` — a plain
`:find [?e ...]` returns arbitrary order. Occurred twice, once per search
feature:

1. **Full-text** (Phase 0 spike): find order `["one" "two"]` vs score order
   `["two" "one"]`. Fix: `{:display :refs+scores}` → `[e a v score]`, sort by
   score **descending**.
2. **Vector** (`similar-resolver`): same trap, caught pre-code by source
   reading (datalevin's own doc example wraps results in `set` — a tell).
   Fix: `{:display :refs+dists}` → `[e a v dist]`, sort by distance
   **ascending**.

Rule: any Datalevin search predicate feeding an ordered result MUST use the
scored display variant + explicit sort + `distinct` (keep first ≡ best).
Never trust `:find` tuple order. Proof anchors: `search_test.clj`
`fulltext-end-to-end-ranked`, `vector_search_test.clj`
`similar-resolver-nearest-first`.
