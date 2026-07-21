(ns hooks.datalevin
  (:require [clj-kondo.hooks-api :as api]))

(defn with-transaction
  "Model datalevin.core/with-transaction, whose binding vector is
   `[tx-conn conn opts?]`, as a `let` binding of `tx-conn` to `conn` (with any
   `opts` expression kept in the body so it is still analyzed)."
  [{:keys [node]}]
  (let [[binding-vec & body]             (rest (:children node))
        [tx-binding conn-expr opts-expr] (:children binding-vec)]
    {:node (api/list-node
            (list*
             (api/token-node 'let)
             (api/vector-node [tx-binding conn-expr])
             (if opts-expr (cons opts-expr body) body)))}))
