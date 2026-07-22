(ns us.whitford.fulcro.rad.database-adapters.pathom-integration-test
  "End-to-end integration tests that drive the adapter through REAL Pathom
   parsers — both Pathom 2 (com.fulcrologic.rad.pathom/new-parser) and Pathom 3
   (com.fulcrologic.rad.pathom3/new-processor). Unlike the unit tests (which call
   resolve fns directly and hand-build deltas), these exercise the full seam a
   Clojars consumer uses: plugin/env wiring, generated resolvers registered in a
   real index, and the actual RAD form/save-form and form/delete-entity mutation
   protocol.

   The save scenarios assert the RETURNED entity (not just :tempids), which
   proves read-your-writes: the save middleware publishes :db-after into the
   atom-backed ::dlo/databases snapshot so the mutation's output query resolves
   the just-saved data within the same request."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.fulcrologic.rad.pathom :as p2]
   [com.fulcrologic.rad.pathom3 :as p3]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; Scenarios — each takes `run` (a (fn [eql] response)) and the raw `conn`.
;; The same scenarios run against both the Pathom 2 parser and Pathom 3 processor.
;; ================================================================================

(defn- scenario-read
  "An id-resolver read resolves entity fields through the real parser."
  [run conn]
  (let [id (new-uuid)]
    (d/transact! conn [{:account/id id :account/name "Ada" :account/email "ada@x.com"}])
    (let [result (run [{[:account/id id] [:account/name :account/email]}])]
      (is (= "Ada" (get-in result [[:account/id id] :account/name])))
      (is (= "ada@x.com" (get-in result [[:account/id id] :account/email]))))))

(defn- scenario-all-ids
  "The generated all-ids resolver plus id-resolver join returns every entity."
  [run conn]
  (let [id1 (new-uuid) id2 (new-uuid)]
    (d/transact! conn [{:account/id id1 :account/name "A1"}
                       {:account/id id2 :account/name "A2"}])
    (let [result (run [{:account/all [:account/id :account/name]}])
          all    (:account/all result)]
      (is (= 2 (count all)))
      (is (= #{id1 id2} (set (map :account/id all))))
      (is (= #{"A1" "A2"} (set (map :account/name all)))))))

(defn- scenario-save-new
  "A form/save-form mutation for a NEW entity remaps the tempid and — via
   read-your-writes — returns the saved attributes in the same request."
  [run conn]
  (let [tid   (tempid/tempid)
        delta {[:account/id tid] {:account/id    {:after tid}
                                  :account/name  {:after "Bob"}
                                  :account/email {:after "bob@x.com"}}}
        resp  (run `[{(form/save-form ~{::form/id        tid
                                        ::form/master-pk :account/id
                                        ::form/delta     delta})
                      [:tempids :account/id :account/name :account/email]}])
        result  (get resp `form/save-form)
        real-id (get-in result [:tempids tid])]
    (is (uuid? real-id) "tempid remapped to a real uuid")
    (is (= "Bob" (:account/name result)) "save mutation returns saved name (read-your-writes)")
    (is (= "bob@x.com" (:account/email result)) "save mutation returns saved email")
    (is (= "Bob" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id])))
        "entity persisted to the database")))

(defn- scenario-save-update
  "A form/save-form mutation updating an existing entity persists and returns the
   new value."
  [run conn]
  (let [id (new-uuid)]
    (d/transact! conn [{:account/id id :account/name "Old" :account/email "old@x.com"}])
    (let [delta {[:account/id id] {:account/name {:before "Old" :after "New"}}}
          resp  (run `[{(form/save-form ~{::form/id        id
                                          ::form/master-pk :account/id
                                          ::form/delta     delta})
                        [:tempids :account/id :account/name :account/email]}])
          result (get resp `form/save-form)]
      (is (= "New" (:account/name result)) "returns updated name (read-your-writes)")
      (is (= "old@x.com" (:account/email result)) "unchanged field preserved")
      (is (= "New" (:account/name (d/pull (d/db conn) '[*] [:account/id id])))
          "update persisted"))))

(defn- scenario-delete
  "A form/delete-entity mutation removes the entity."
  [run conn]
  (let [id (new-uuid)]
    (d/transact! conn [{:account/id id :account/name "Gone"}])
    (run `[(form/delete-entity ~{:account/id id})])
    (is (nil? (:account/name (d/pull (d/db conn) '[*] [:account/id id])))
        "entity deleted from the database")))

;; Each scenario gets its OWN connection so state does not bleed across them
;; (e.g. so the all-ids scenario sees exactly its own entities). `make-run` builds
;; a real parser bound to that connection for the Pathom version under test.
(defn- run-all-scenarios [make-run]
  (doseq [scenario [scenario-read scenario-all-ids scenario-save-new
                    scenario-save-update scenario-delete]]
    (tu/with-test-conn [conn]
      (scenario (make-run conn) conn))))

;; ================================================================================
;; Pathom 2 — com.fulcrologic.rad.pathom/new-parser
;; ================================================================================

(defn- pathom2-run
  "Build a real Pathom 2 parser wired with the adapter, bound to `conn`."
  [conn]
  (let [save-mw   (dl/wrap-datalevin-save)
        delete-mw (dl/wrap-datalevin-delete)
        resolvers (dl/generate-resolvers tu/all-test-attributes :test)
        parser    (p2/new-parser {}
                    [(attr/pathom-plugin tu/all-test-attributes)
                     (form/pathom-plugin save-mw delete-mw)
                     (dl/pathom-plugin (fn [_env] {:test conn}))]
                    [resolvers form/resolvers])]
    (partial parser {})))

(deftest pathom2-parser-integration
  (testing "adapter works end-to-end through a real Pathom 2 parser"
    (run-all-scenarios pathom2-run)))

;; ================================================================================
;; Pathom 3 — com.fulcrologic.rad.pathom3/new-processor
;; (generate-resolvers returns Pathom-2-shape maps; new-processor auto-converts.)
;; ================================================================================

(defn- pathom3-run
  "Build a real Pathom 3 processor wired with the adapter, bound to `conn`.
   Passes our Pathom-2-shape resolvers straight in — new-processor converts them."
  [conn]
  (let [save-mw   (dl/wrap-datalevin-save)
        delete-mw (dl/wrap-datalevin-delete)
        env-mw    (-> (attr/wrap-env tu/all-test-attributes)
                      (form/wrap-env save-mw delete-mw)
                      (dl/wrap-env (fn [_env] {:test conn})))
        processor (p3/new-processor {}
                    env-mw
                    []
                    [(dl/generate-resolvers tu/all-test-attributes :test)
                     form/resolvers])]
    (partial processor {})))

(deftest pathom3-processor-integration
  (testing "adapter works end-to-end through a real Pathom 3 processor"
    (run-all-scenarios pathom3-run)))
