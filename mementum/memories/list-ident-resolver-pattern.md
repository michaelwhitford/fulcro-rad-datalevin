---
type: Pattern
symbol: 🔁
title: Collection resolvers return idents; fields fill via batched id-resolver
related:
  - ../knowledge/design/full-text-search.md
---

🔁 In this adapter, collection-style resolvers return a list of IDENTS
(`[{id-attr val} ...]`), never fields. `all-ids-resolver` produces
`{:entity/all [{:entity/id …}]}`; the proposed search resolver produces
`{:entity/search [{:entity/id …}]}`. Fields are filled downstream by the
existing **batched `id-resolver`**. Any new collection resolver (search, range,
filter) should mirror this shape and reuse the native-id id-mapping logic from
`id-resolver` / `all-ids-resolver`. Keeps resolvers composable and avoids
duplicating pull logic.
