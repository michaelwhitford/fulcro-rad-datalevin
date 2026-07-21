(ns hooks.test-utils
  (:require [clj-kondo.hooks-api :as api]))

(defn with-test-conn [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [conn-binding] (:children binding-vec)]
    {:node (api/list-node
            (list*
             (api/token-node 'let)
             (api/vector-node [conn-binding (api/token-node 'nil)])
             body))}))

(defn with-test-conn-attrs [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [conn-binding _attrs] (:children binding-vec)]
    {:node (api/list-node
            (list*
             (api/token-node 'let)
             (api/vector-node [conn-binding (api/token-node 'nil)])
             body))}))
