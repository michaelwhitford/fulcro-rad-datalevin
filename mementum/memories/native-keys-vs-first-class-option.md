---
type: Decision
symbol: 🎯
title: Native schema keys via attribute-schema vs a first-class ::dlo option
related:
  - ../knowledge/design/full-text-search.md
---

🎯 Convention for surfacing a Datalevin feature through the adapter:

- **Pure-schema feature** (e.g. `:db.attr/preds`) → use the NATIVE key inside
  `::dlo/attribute-schema`. It already merges through `attr->schema`. Matches
  Datalevin-native usage and the Datomic adapter's pass-through philosophy. No
  new option key.
- **Cross-cutting feature** (schema + conn-opts + resolver/save) → introduce a
  first-class `::dlo/*` option. Precedent: `::dlo/native-id?`. Applies to the
  proposed `::dlo/fulltext?`.

Decide by blast radius: if it only touches the generated schema, pass it
through; if it also drives connection options or resolver generation, make it an
option.
