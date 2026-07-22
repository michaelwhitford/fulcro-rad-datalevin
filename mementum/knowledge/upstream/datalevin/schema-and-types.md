---
type: Reference
title: Datalevin Schema and Value Types
status: active
tags: [datalevin, upstream, schema, value-types, predicates, fulltext]
related:
  - ./core-api.md
  - ./connection-options.md
  - ./search.md
  - ../../../memories/datalevin-vec-dimensions-in-conn-opts.md
---

# Datalevin Schema and Value Types

Verified against the Datalevin 1.0.0 source
([github.com/datalevin/datalevin](https://github.com/datalevin/datalevin)); `src:`
citations are repo-relative paths. This is the schema surface
`automatic-schema` targets in `start_databases.clj`.

## Value Types

Datalevin 1.0.0 supports exactly 16 `:db/valueType` values
(`src: src/datalevin/constants.clj (datalog-value-types)`):

```clojure
:db.type/keyword :db.type/symbol :db.type/string :db.type/boolean
:db.type/long    :db.type/double :db.type/float  :db.type/ref
:db.type/bigint  :db.type/bigdec :db.type/instant :db.type/uuid
:db.type/bytes   :db.type/tuple  :db.type/vec    :db.type/idoc
```

Notes:

- `:db.type/vec` (dense vectors for HNSW similarity search) and
  `:db.type/idoc` (indexed document) are **Datalevin-specific** — no Datomic
  equivalent.
- Tuple component types exclude `:db.type/bytes`, `:db.type/tuple`,
  `:db.type/vec`, and `:db.type/idoc`
  (`src: src/datalevin/constants.clj (tuple-value-types)`).

## Schema Map Keys

| Key | Values / notes |
|---|---|
| `:db/valueType` | one of the 16 types above |
| `:db/cardinality` | `:db.cardinality/one` (default) or `:db.cardinality/many` |
| `:db/unique` | `:db.unique/identity` or `:db.unique/value` |
| `:db/isComponent` | `true` on ref attrs that own their target |
| `:db.attr/preds` | attribute-value predicates (see below) |
| `:db/fulltext` | `true` — full-text index this attribute |
| `:db.fulltext/domains` | seq of search-domain name strings |
| `:db.fulltext/autoDomain` | `true` — auto-create a per-attribute domain (note camelCase) |
| `:db/tupleAttrs` | composite tuple from other attributes |
| `:db/tupleType` / `:db/tupleTypes` | homogeneous / heterogeneous tuple types |
| `:db.vec/domains` | extra vector-domain names for `:db.type/vec` attrs |
| `:db/embedding`, `:db.embedding/domains`, `:db.embedding/autoDomain` | embedding indexing |

`src: src/datalevin/storage.clj (schema->rschema, attr->properties), src/datalevin/validate.clj`

Two absences worth knowing (both verified against `attr->properties`):

- **There is no `:db/index` key.** The AVE index is maintained implicitly for
  unique, ref, and fulltext attributes; there is no explicit opt-in.
- **`:db/noHistory` was not found** in the 1.0.0 schema code — do not assume it
  exists.

## Attribute Predicates — `:db.attr/preds`

Transaction-time value validation, used by the adapter via
`::dlo/attribute-schema`.

- The value may be a qualified symbol (resolved with `requiring-resolve`), a
  UDF descriptor map, a registered UDF keyword id, or a non-empty sequential
  collection of these.
- Each predicate is called with **one argument: the attribute value**.
  (Divergence from Datomic entity specs, which pass db and entity.)
- The predicate must return **strictly `true`**. Any other return — including
  other truthy values — fails the transaction with ex-data
  `{:error :transact/attr-pred :entity e :attribute a :value v :predicate pred
  :db.error/pred-return <return>}`.

`src: src/datalevin/validate.clj (validate-attr-preds), src/datalevin/db/tx/execute.clj`

## Full-Text Schema Keys

- `:db/fulltext true` marks an attribute for full-text indexing. The value need
  not be a string — `str` is applied at index time.
- `:db.fulltext/domains` lists the search domains the attribute indexes into;
  omitted means the default `"datalevin"` domain.
- `:db.fulltext/autoDomain true` creates a domain named after the attribute
  (keyword without `:`, `/` replaced by `_`; e.g. `:doc/text` → `"doc_text"`).
  This is **required** for attribute-specific `fulltext` query syntax.
- The key is exactly `:db.fulltext/autoDomain` — camelCase, not kebab-case.

See [search.md](./search.md) for the query side.

`src: src/datalevin/storage.clj (init-search-domains)`

## Vector Attributes

Schema only carries `:db/valueType :db.type/vec` and optionally
`:db.vec/domains`. **Dimensions and HNSW parameters live in the connection
options** (`:vector-domains`), not in schema — see
[connection-options.md](./connection-options.md) and the memory
[datalevin-vec-dimensions-in-conn-opts](../../../memories/datalevin-vec-dimensions-in-conn-opts.md).
A `:db.type/vec` attribute automatically creates its own domain using the same
naming rule as fulltext auto-domains.

`src: src/datalevin/storage.clj (init-vector-domains)`

## Schema Lifecycle and Change Restrictions

Schema passed at connection time is merged into the persisted schema; new
attributes get an internal `:db/aid`, existing aids are preserved.
`update-schema` mutates a live connection (add / delete / rename).

Rejected changes when data exist (`src: src/datalevin/validate.clj`):

- cardinality many → one
- `:db/valueType` change (unless previously untyped `:data`)
- `:db/unique` addition that existing data violates
- any embedding schema key change on a populated attribute (requires explicit
  rebuild)
