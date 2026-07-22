---
type: Reference
title: Fulcro RAD Form Save Protocol
status: active
tags: [fulcro-rad, upstream, form, delta, save-middleware, tempids, delete]
related:
  - ./database-adapter-contract.md
  - ./attributes.md
  - ../../../memories/middleware-must-propagate-failures.md
---

# Fulcro RAD Form Save Protocol

Verified against the fulcro-rad source
([github.com/fulcrologic/fulcro-rad](https://github.com/fulcrologic/fulcro-rad));
`src:` citations are repo-relative paths. This is the contract behind `wrap-datalevin-save` / `wrap-datalevin-delete` and
`delta->txn`.

## The Form Delta

Spec: `(>def ::delta (s/map-of eql/ident? map?))`. Shape:

```clojure
{[:account/id 1] {:account/name    {:before "Joe" :after "Sally"}
                  :account/address {:after [:address/id 2]}}
 [:address/id 2] {:address/street  {:before "" :after "123 Main St"}}}
```

- Keys are Fulcro idents `[qualified-id-key id-value]`.
- Values map attribute key → `{:before old :after new}`; `:before` is omitted
  for brand-new values, `:after` is omitted for removals.
- To-one refs are idents; to-many refs are vectors of idents.
- New entities carry a Fulcro **tempid** as the ident's id value:
  `[:account/id #fulcro/tempid[…]]`.

The delta is produced client-side by `fs/dirty-fields props true` (Fulcro form
state) via the form machine's `calc-diff`, and passed to the server untouched.

`src: src/main/com/fulcrologic/rad/form.cljc (::delta spec, calc-diff), src/main/com/fulcrologic/rad/middleware/save_middleware.cljc (wrap-rewrite-delta docstring)`

## What the Save Middleware Receives

The save mutation (`save-form` / `save-as-form`, registered via
`form/resolvers`) calls `save-form*`, which invokes the installed save
middleware with the pathom env augmented as `(assoc env ::form/params params)`.

`::form/params` is spec'd as `(s/keys :req [::id ::master-pk ::delta])`:

- `::form/id` — the master entity's id value (possibly a tempid)
- `::form/master-pk` — the master entity's identity attribute keyword
- `::form/delta` — the delta described above

The env also contains `::attr/key->attribute` (from `attr/wrap-env`), the
middleware itself under `::form/save-middleware` / `::form/delete-middleware`
(from `form/wrap-env` / `form/pathom-plugin`), and whatever the adapter's
pathom plugin added (connections, databases).

`src: src/main/com/fulcrologic/rad/form.cljc (::params spec, save-form*, wrap-env, pathom-plugin)`

## Required Return Value — Tempids

The middleware must return a map containing `:tempids` — `{tempid → real-id}`.
`save-form*` uses it to remap the master id and merges `{master-pk real-id}`
into the result:

```clojure
(let [{:keys [tempids]} result
      id (get tempids id id)]
  (merge result {master-pk id}))
```

On the wire, the Pathom 2 parser declares
`::pc/mutation-join-globals [:tempids]` so `:tempids` from the mutation
response merges globally; the client's `save-as-form` ok-action then rewrites
idents in state via `tempid/resolve-tempids`. (For Pathom 3 no equivalent
`mutation-join-globals` was found in `pathom3.clj` — tempid delivery appears to
rely on Fulcro's standard mutation-response merging; verify at runtime if it
matters.)

`src: src/main/com/fulcrologic/rad/form.cljc (save-form*, save-as-form ok-action), src/main/com/fulcrologic/rad/pathom.clj (parser-args)`

## Middleware Composition Pattern

Adapter save middleware follows a two-arity Ring-like convention:

```clojure
(defn wrap-my-adapter-save
  ([]        (fn [pathom-env] (save-to-db! pathom-env)))          ; terminal
  ([handler] (fn [pathom-env]
               (deep-merge (save-to-db! pathom-env)
                           (handler pathom-env)))))               ; composing
```

RAD also ships generic delta-rewriting middleware in
`com.fulcrologic.rad.middleware.save-middleware`:

- `wrap-rewrite-values` — per-ident rewrite via the `rewrite-value`
  multimethod, dispatched on `(first ident)` (the id keyword)
- `wrap-rewrite-delta` — wholesale delta rewrite:
  `(rewrite-fn env old-delta)`; a nil return keeps the old delta

`src: docs/DevelopersGuide.adoc (wrap-datomic-save example), src/main/com/fulcrologic/rad/middleware/save_middleware.cljc`

## Delete Protocol

Client: `(form/delete! this id-key id)` transacts
`(delete-entity {id-key id-value})` — params are a **map** (the ident as a map
entry, not a vector). Server: the delete mutation looks up
`::form/delete-middleware` in env and calls it with
`(assoc env ::form/params params)`; it throws
"form/pathom-plugin not installed" if the middleware is missing.

So delete middleware reads `::form/params` as `{id-key id-value}` and must
perform the retraction; failures should propagate (see the memory
[middleware-must-propagate-failures](../../../memories/middleware-must-propagate-failures.md)).

`src: src/main/com/fulcrologic/rad/form.cljc (delete!, pathom2-server-delete-entity-mutation)`

## Client-Side Flow (context)

- `start-form!` decides create vs edit by `tempid/tempid?` on the id; edit
  issues a load of the form ident (`[id-key id]`) resolved by the adapter's
  id-resolver.
- The save UISM triggers the remote `save-form` mutation with
  `{::form/master-pk … ::form/id … ::form/delta …}` plus any additional save
  params.

`src: src/main/com/fulcrologic/rad/form.cljc (start-form!, start-edit, :event/save handler)`
