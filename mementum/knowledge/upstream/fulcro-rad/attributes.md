---
type: Reference
title: Fulcro RAD Attributes
status: active
tags: [fulcro-rad, upstream, attributes, defattr, attribute-options]
related:
  - ./database-adapter-contract.md
  - ./resolvers-and-pathom.md
---

# Fulcro RAD Attributes

Verified against the fulcro-rad source
([github.com/fulcrologic/fulcro-rad](https://github.com/fulcrologic/fulcro-rad));
`src:` citations are repo-relative paths. Attributes
are the input the adapter consumes for schema generation, resolver generation,
and save/delete processing.

## `defattr`

```clojure
(defattr sym qualified-keyword data-type options-map)
(defattr sym "docstring" qualified-keyword data-type options-map)
```

Expands to `(def sym (new-attribute qualified-keyword data-type options-map))`.
`new-attribute` produces the options map with `::attr/type` and
`::attr/qualified-key` assoc'd in. On the JVM the result is a "registered map"
keyed by the defining var; in CLJS it is a plain map. Spec-wise an attribute
only *requires* `::attr/type` and `::attr/qualified-key`:

```clojure
(>def ::attribute (s/keys :req [::type ::qualified-key] :opt [::target]))
```

`new-attribute` warns (logs) when a `:ref` attribute lacks `::target`/
`::targets`, and when a non-ref attribute has one.

`src: src/main/com/fulcrologic/rad/attributes.cljc (defattr, new-attribute, ::attribute spec)`

## Attribute Types

Commonly used types: `:string :uuid :int :long :decimal :instant :boolean
:keyword :symbol :enum :ref`. Important: **the type system is open** — there is
no closed enum in RAD core; only `:ref` is special-cased (targets, EQL joins,
validity checks). Adapters may add types — this adapter adds `:vec` mapping to
`:db.type/vec`.

`src: src/main/com/fulcrologic/rad/attributes.cljc (new-attribute, valid-value?, attributes->eql)`

## Key `ao/*` Options (attributes-options)

All are namespaced under `:com.fulcrologic.rad.attributes/*` unless noted.

| Option | Meaning |
|---|---|
| `ao/identity?` | boolean; this attribute is an entity primary key |
| `ao/identities` | set of identity-attr qualified keys that "own" this attribute (entity membership) |
| `ao/schema` | keyword naming the database/schema this attribute lives on |
| `ao/cardinality` | `:one` (default) or `:many` |
| `ao/target` / `ao/targets` | qualified key(s) of identity attrs a `:ref` points to |
| `ao/required?` | boolean; may be enforced by forms and/or schema |
| `ao/component?` | ref target is exclusively owned (component) |
| `ao/enumerated-values` | set of keywords; required for `:enum` type |
| `ao/enumerated-labels` | map or fn; UI labels for enum values |
| `ao/valid?` | `(fn [value props qualified-key] boolean?)` form validation |
| `ao/read-only?` | boolean or fn |
| `ao/label`, `ao/style`, `ao/field-style-config`, `ao/computed-options` | UI concerns |

Pathom resolver options use the pathom namespaces directly:

| Option | Actual keyword |
|---|---|
| `ao/pc-resolve` / `ao/pc-output` / `ao/pc-input` / `ao/pc-transform` | `:com.wsscode.pathom.connect/*` (Pathom 2) |
| `ao/pathom3-resolve` / `ao/pathom3-output` / `ao/pathom3-input` / `ao/pathom3-transform` / `ao/pathom3-batch?` | `:com.wsscode.pathom3.connect.operation/*` (Pathom 3) |

If `pathom3-output` is unspecified, resolver generation defaults it to
`[qualified-key]`.

`src: src/main/com/fulcrologic/rad/attributes_options.cljc (all def vars)`

## Database Membership Pattern

```clojure
(defattr name :account/name :string
  {ao/identities #{:account/id}   ; belongs to the :account/id entity
   ao/schema     :production})    ; lives on the :production database
```

`ao/schema` routes the attribute to a connection (the adapter looks up
`::dlo/connections` by this key); `ao/identities` groups attributes into
entities for schema generation and id-resolver output.

`src: src/main/com/fulcrologic/rad/attributes_options.cljc (schema, identities docstrings)`

## Registry Helpers and Env Wiring

- `(attr/attribute-map attributes)` → `{qualified-key → attribute}`
- `(attr/entity-map attributes)` → `{identity-key → [attributes]}` (grouped by
  each key in `ao/identities`)
- `(attr/wrap-env all-attributes)` → env middleware that assoc's
  `::attr/key->attribute` (the registry map) and `::attr/id-keys` (set of
  identity attribute keys) into the pathom env
- `(attr/pathom-plugin all-attributes)` → the Pathom 2 plugin wrapping the
  parser with `wrap-env`; for Pathom 3 `wrap-env` is applied to the base env
  directly

The adapter's resolvers and middleware rely on `::attr/key->attribute` being
present in env.

`src: src/main/com/fulcrologic/rad/attributes.cljc (attribute-map, entity-map, wrap-env, pathom-plugin)`
