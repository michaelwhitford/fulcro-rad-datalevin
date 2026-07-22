---
type: Reference
title: Datalevin Full-Text Search
status: active
tags: [datalevin, upstream, full-text-search, fulltext, analyzers]
related:
  - ./schema-and-types.md
  - ./connection-options.md
  - ../../design/full-text-search.md
---

# Datalevin Full-Text Search

Verified against the Datalevin 1.0.0 source
([github.com/datalevin/datalevin](https://github.com/datalevin/datalevin), source
plus `doc/search.md`); `src:` citations are repo-relative paths. This page
feeds the adapter's full-text feature — see the
[full-text-search design](../../design/full-text-search.md).

## Two Entry Points

1. **Standalone search engine** over a KV store: `new-search-engine`,
   `add-doc`, `remove-doc`, `search`. Useful for spikes and non-Datalog text.
2. **Datalog integration**: mark attributes `:db/fulltext true` in schema,
   configure domains in conn-opts, query with the `fulltext` built-in inside
   `q`, or call `fulltext-datoms` directly. This is the path the adapter uses.

## Standalone API

```clojure
(d/new-search-engine lmdb opts)     ; lmdb = open-kv handle
(d/add-doc engine doc-ref doc-text) ; 4th arg check-exist? defaults true
(d/remove-doc engine doc-ref)
(d/search engine query opts)        ; results in relevance order
```

Engine opts: `:index-position?` (default `false`; required for phrase search
and proximity scoring), `:include-text?` (default `false`; required for text
display modes), `:domain` (default `"datalevin"`), `:analyzer`,
`:query-analyzer`, `:search-opts`.

`src: src/datalevin/core.clj (new-search-engine, search docstrings), src/datalevin/search.clj (new-search-engine*)`

## Search Results and Options

Results are always ordered by relevance, highest first. The `:display` option
controls the shape:

| `:display` | Returns (lazy seq of) | Requires |
|---|---|---|
| `:refs` (default) | `doc-ref` | — |
| `:refs+scores` | `[doc-ref score]` | — |
| `:offsets` | `[doc-ref [[term [offset…]]…]]` | `:index-position? true` |
| `:texts` | `[doc-ref doc-text]` | `:include-text? true` |
| `:texts+offsets` | `[doc-ref doc-text [[term [offset…]]…]]` | both |

Query-time options: `:top` (default 10), `:limit`/`:offset` (paging;
`:limit` overrides `:top`), `:paging-cache-pages` (default 10), `:doc-filter`
(fn doc-ref → boolean, default `(constantly true)`), `:domains` (restrict to
listed domains), `:proximity-expansion` (default 2), `:proximity-max-dist`
(default 45).

Scoring is fixed: TF-IDF (`lnu.ltn` weighting) with a custom tiered-WAND
("T-Wand") algorithm; proximity scoring is a second pass when
`:index-position?` is on. **There is no `:algo` option** — do not look for one.

`src: src/datalevin/core.clj (search docstring), src/datalevin/constants.clj (defaults), doc/search.md`

## `fulltext` in Datalog Queries

```clojure
;; whole-DB search — binds [e a v] per match
[(fulltext $ "red") [[?e ?a ?v]]]

;; attribute-specific — REQUIRES :db.fulltext/autoDomain true on the attr,
;; otherwise an exception is raised
[(fulltext $ :product/description "red" {:top 10}) [[?e ?a ?v]]]

;; domain-restricted
[(fulltext $ "red" {:domains ["product_description"]}) [[?e ?a ?v]]]

;; with scores appended to the tuple
[(fulltext $ "red" {:display :refs+scores}) [[?e ?a ?v ?score]]]
```

`fulltext-datoms` is the function form: `(fulltext-datoms db query opts)`
returns a vector of `[e a v]` triples (not Datom objects), delegating to the
remote implementation for `dtlv://` stores.

`src: src/datalevin/built_ins.clj (fulltext, fulltext-datoms), src/datalevin/core.clj (fulltext-datoms)`

## Query Language

Boolean expressions nest arbitrarily with `:or`, `:and`, `:not`; phrases are
maps with a `:phrase` key and require `:index-position? true` on the domain:

```clojure
[:or "fox" "red" [:and "black" "sheep" [:not "yellow"]]]
[:and {:phrase "little lamb"} "fleece"]
```

`src: doc/search.md (boolean search expressions)`

## Analyzers

A custom analyzer is a function from a document string to a sequence of
`[term position offset]` triples (term string, non-negative int position and
offset). `:query-analyzer` overrides it at query time only. For Datalog search
domains, analyzers may also be UDF descriptor maps or registered UDF keyword
ids. Built-in helpers (stemming, stop words, regex, ngrams, prefix) live in
`datalevin.search-utils`; English stop words/punctuation sets are dynamic vars
in `datalevin.constants`.

Terms longer than **128 characters** are silently ignored
(`+max-term-length+`).

`src: src/datalevin/core.clj (new-search-engine docstring), doc/search.md, src/datalevin/constants.clj (+max-term-length+)`

## Indexing Modes

`:indexing-mode :async` (per domain, in `:search-domains`) commits a durable
secondary-index job atomically with the source datoms; an in-process worker
applies the index update after commit. Async fulltext indexes are **eventually
consistent** — a search immediately after a save may miss the new document.
Default is `:sync`.

`src: doc/search.md (indexing mode)`

## Adapter-Relevant Gotchas

- Attribute-specific `fulltext` query syntax throws without
  `:db.fulltext/autoDomain true` — the adapter's schema generation must set it
  when generating per-entity search.
- Domain names are strings derived from attribute keywords
  (`:doc/text` → `"doc_text"`); the same rule as vector domains.
- Phrase search silently depends on `:index-position? true` being set on the
  domain **at indexing time**; enabling it later requires reindexing.
