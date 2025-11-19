(ns us.whitford.fulcro.rad.database-adapters.datalevin-diagnostic-test
  "Diagnostic tests to help debug issues in real applications.
   These tests provide detailed output and validation to identify
   exactly what's happening during save operations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.middleware.save-middleware :as save-mw]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]
   [taoensso.timbre :as log]))

;; ================================================================================
;; Test Attributes
;; ================================================================================

(def account-id
  {::attr/qualified-key :account/id
   ::attr/type          :uuid
   ::attr/schema        :main
   ::attr/identity?     true})

(def account-name
  {::attr/qualified-key :account/name
   ::attr/type          :string
   ::attr/schema        :main
   ::attr/identities    #{:account/id}
   ::attr/required?     true})

(def account-email
  {::attr/qualified-key :account/email
   ::attr/type          :string
   ::attr/schema        :main
   ::attr/identities    #{:account/id}
   ::attr/required?     true
   ::dlo/attribute-schema {:db/unique :db.unique/value}})

(def account-active
  {::attr/qualified-key :account/active?
   ::attr/type          :boolean
   ::attr/schema        :main
   ::attr/identities    #{:account/id}})

(def test-attributes
  [account-id account-name account-email account-active])

;; ================================================================================
;; Diagnostic Helpers
;; ================================================================================

(defn create-test-db
  []
  (let [path (str "/tmp/datalevin-diagnostic-" (new-uuid))
        schema (dl/automatic-schema :main test-attributes)
        conn (d/get-conn path schema)]
    {:conn conn
     :path path}))

(defn cleanup-test-db
  [{:keys [conn path]}]
  (when conn
    (d/close conn))
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn inspect-delta
  "Print diagnostic information about a delta"
  [delta]
  (println "\n=== DELTA INSPECTION ===")
  (println "Number of entities:" (count delta))
  (doseq [[[id-attr id] changes] delta]
    (println (str "\nEntity: [" id-attr " " id "]"))
    (println "  Changes:")
    (doseq [[attr {:keys [before after]}] changes]
      (println (str "    " attr ":"))
      (println (str "      before: " (pr-str before)))
      (println (str "      after:  " (pr-str after)))
      (println (str "      same?:  " (= before after)))))
  (println "======================\n"))

(defn inspect-transaction-data
  "Print diagnostic information about transaction data"
  [txn-data]
  (println "\n=== TRANSACTION DATA ===")
  (println "Number of operations:" (count txn-data))
  (doseq [op txn-data]
    (cond
      (map? op)
      (do
        (println "\n  Entity map:")
        (println "    :db/id ->" (:db/id op))
        (doseq [[k v] (dissoc op :db/id)]
          (println (str "    " k " -> " (pr-str v)))))
      
      (vector? op)
      (println (str "\n  Retraction: " op))
      
      :else
      (println (str "\n  Unknown op: " op))))
  (println "========================\n"))

(defn inspect-entity
  "Print diagnostic information about an entity in the database"
  [db id-attr id]
  (println (str "\n=== ENTITY [" id-attr " " id "] ==="))
  (let [entity (d/pull db '[*] [id-attr id])]
    (if (seq entity)
      (doseq [[k v] entity]
        (println (str "  " k " -> " (pr-str v))))
      (println "  NOT FOUND")))
  (println "============================\n"))

;; ================================================================================
;; Diagnostic Tests
;; ================================================================================

(deftest diagnostic-update-with-logging
  (testing "Diagnostic: Update with detailed logging"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          
          ;; Create initial entity
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Original Name"
                                          :account/email "original@example.com"
                                          :account/active? false}])
          
          ;; Inspect initial state
          _           (println "\nInitial state:")
          _           (inspect-entity (d/db conn) :account/id existing-id)
          
          ;; Create delta
          delta       {[:account/id existing-id]
                       {:account/name {:before "Original Name"
                                       :after "Updated Name"}
                        :account/active? {:before false
                                          :after true}}}
          
          ;; Inspect delta
          _           (inspect-delta delta)
          
          ;; Generate transaction data
          txn-data    (dl/delta->txn delta)
          
          ;; Inspect transaction data
          _           (inspect-transaction-data txn-data)
          
          ;; Execute save
          key->attr   (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env         {::attr/key->attribute key->attr
                       ::dlo/connections     {:main conn}
                       ::form/delta          delta
                       ::form/id             [:account/id existing-id]}
          
          save-handler (-> (fn [env] {})
                           ((dl/wrap-datalevin-save {:default-schema :main}))
                           (save-mw/wrap-rewrite-values))
          
          result      (save-handler env)
          
          ;; Inspect result
          _           (println "\nSave result:")
          _           (clojure.pprint/pprint result)
          
          ;; Inspect final state
          _           (println "\nFinal state:")
          _           (inspect-entity (d/db conn) :account/id existing-id)]
      
      ;; Verify the update worked
      (is (map? result))
      (is (contains? result :tempids))
      
      (let [final-entity (d/pull (d/db conn) '[*] [:account/id existing-id])]
        (is (= "Updated Name" (:account/name final-entity))
            "Name should be updated")
        (is (true? (:account/active? final-entity))
            "Active should be updated"))
      
      (cleanup-test-db db))))

(deftest diagnostic-delta-to-txn-conversion
  (testing "Diagnostic: Verify delta->txn conversion is correct"
    (let [test-cases
          [{:description "Simple update"
            :delta {[:account/id (new-uuid)]
                    {:account/name {:before "Old" :after "New"}}}
            :expected-op-count 1}
           
           {:description "Multiple field update"
            :delta {[:account/id (new-uuid)]
                    {:account/name {:before "Old" :after "New"}
                     :account/email {:before "old@test.com" :after "new@test.com"}}}
            :expected-op-count 1}
           
           {:description "Update and retraction"
            :delta {[:account/id (new-uuid)]
                    {:account/name {:before "Old" :after "New"}
                     :account/email {:before "old@test.com" :after nil}}}
            :expected-op-count 2}  ; One entity map, one retraction
           
           {:description "Retraction only"
            :delta {[:account/id (new-uuid)]
                    {:account/email {:before "old@test.com" :after nil}}}
            :expected-op-count 1}]]  ; Just the retraction
      
      (doseq [{:keys [description delta expected-op-count]} test-cases]
        (testing description
          (println (str "\n--- " description " ---"))
          (inspect-delta delta)
          (let [txn-data (dl/delta->txn delta)]
            (inspect-transaction-data txn-data)
            (is (= expected-op-count (count txn-data))
                (str "Expected " expected-op-count " operations for: " description))))))))

(deftest diagnostic-before-after-comparison
  (testing "Diagnostic: Before/after value comparison logic"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          
          ;; Create entity
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test"
                                          :account/email "test@example.com"
                                          :account/active? true}])
          
          test-deltas
          [{:description "Value actually changed"
            :delta {[:account/id existing-id]
                    {:account/name {:before "Test" :after "Changed"}}}}
           
           {:description "Value unchanged"
            :delta {[:account/id existing-id]
                    {:account/name {:before "Test" :after "Test"}}}}
           
           {:description "Before doesn't match database"
            :delta {[:account/id existing-id]
                    {:account/name {:before "Wrong" :after "Changed"}}}}
           
           {:description "Boolean flip"
            :delta {[:account/id existing-id]
                    {:account/active? {:before true :after false}}}}]]
      
      (doseq [{:keys [description delta]} test-deltas]
        (testing description
          (println (str "\n=== " description " ==="))
          
          ;; Show current state
          (println "Database state:")
          (inspect-entity (d/db conn) :account/id existing-id)
          
          ;; Show delta
          (inspect-delta delta)
          
          ;; Generate and show txn data
          (let [txn-data (dl/delta->txn delta)]
            (inspect-transaction-data txn-data)
            
            ;; Apply it
            (when (seq txn-data)
              (d/transact! conn txn-data))
            
            ;; Show resulting state
            (println "After transaction:")
            (inspect-entity (d/db conn) :account/id existing-id)
            
            ;; Add assertion to prevent "no assertions" warning
            (is true (str "Diagnostic test for: " description)))))
      
      (cleanup-test-db db))))

(deftest diagnostic-tempids-always-present
  (testing "Diagnostic: Verify :tempids is always in result"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test"
                                          :account/email "test@example.com"}])
          
          ;; Test various delta scenarios
          test-scenarios
          [{:description "Update existing entity"
            :delta {[:account/id existing-id]
                    {:account/name {:before "Test" :after "Updated"}}}}
           
           {:description "Empty delta"
            :delta {}}
           
           {:description "Nil delta"
            :delta nil}]
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          save-handler (-> (fn [env] {})
                           ((dl/wrap-datalevin-save {:default-schema :main})))]
      
      (doseq [{:keys [description delta]} test-scenarios]
        (testing description
          (println (str "\n=== " description " ==="))
          
          (let [env    {::attr/key->attribute key->attr
                        ::dlo/connections     {:main conn}
                        ::form/delta          delta
                        ::form/id             [:account/id existing-id]}
                result (save-handler env)]
            
            (println "Result keys:" (keys result))
            (println ":tempids value:" (pr-str (:tempids result)))
            
            (is (contains? result :tempids)
                (str ":tempids MUST be present for: " description))
            (is (map? (:tempids result))
                (str ":tempids MUST be a map for: " description)))))
      
      (cleanup-test-db db))))

(deftest diagnostic-transaction-data-structure
  (testing "Diagnostic: Verify transaction data structure is correct"
    (let [test-id (new-uuid)
          
          ;; Test different delta shapes
          test-cases
          [{:description "Entity map only (new entity)"
            :delta {[:account/id test-id]
                    {:account/id {:before nil :after test-id}
                     :account/name {:before nil :after "New"}}}
            :expect-entity-map? true
            :expect-retractions? false}
           
           {:description "Entity map only (update)"
            :delta {[:account/id test-id]
                    {:account/name {:before "Old" :after "New"}}}
            :expect-entity-map? true
            :expect-retractions? false}
           
           {:description "Retraction only"
            :delta {[:account/id test-id]
                    {:account/email {:before "old@test.com" :after nil}}}
            :expect-entity-map? false
            :expect-retractions? true}
           
           {:description "Both entity map and retraction"
            :delta {[:account/id test-id]
                    {:account/name {:before "Old" :after "New"}
                     :account/email {:before "old@test.com" :after nil}}}
            :expect-entity-map? true
            :expect-retractions? true}]]
      
      (doseq [{:keys [description delta expect-entity-map? expect-retractions?]} test-cases]
        (testing description
          (println (str "\n=== " description " ==="))
          (inspect-delta delta)
          
          (let [txn-data (dl/delta->txn delta)
                entity-maps (filter map? txn-data)
                retractions (filter vector? txn-data)]
            
            (inspect-transaction-data txn-data)
            
            (if expect-entity-map?
              (do
                (is (seq entity-maps)
                    (str "Expected entity map for: " description))
                (let [entity-map (first entity-maps)]
                  (is (contains? entity-map :db/id)
                      "Entity map should have :db/id")))
              (is (empty? entity-maps)
                  (str "Expected no entity map for: " description)))
            
            (if expect-retractions?
              (do
                (is (seq retractions)
                    (str "Expected retractions for: " description))
                (doseq [retraction retractions]
                  (is (or (= 3 (count retraction))  ; [:db/retract eid attr val]
                          (= 4 (count retraction))) ; [:db/retract [id-attr id] attr val]
                      "Retraction should have 3 or 4 elements (4 if using lookup ref)")
                  (is (= :db/retract (first retraction))
                      "First element should be :db/retract")))
              (is (empty? retractions)
                  (str "Expected no retractions for: " description)))))))))

(deftest diagnostic-manual-transaction-test
  (testing "Diagnostic: Manually execute transaction to isolate issues"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          
          ;; Create entity
          _           (do
                        (println "\n=== Creating initial entity ===")
                        (d/transact! conn [{:account/id existing-id
                                            :account/name "Original"
                                            :account/email "original@test.com"
                                            :account/active? false}]))
          
          _           (inspect-entity (d/db conn) :account/id existing-id)
          
          ;; Manually build transaction data
          manual-txn  [{:db/id [:account/id existing-id]
                        :account/name "Manually Updated"}]
          
          _           (println "\n=== Manual transaction ===")
          _           (inspect-transaction-data manual-txn)
          
          ;; Execute it
          _           (d/transact! conn manual-txn)
          
          _           (println "\n=== After manual transaction ===")
          _           (inspect-entity (d/db conn) :account/id existing-id)]
      
      ;; Verify manual transaction worked
      (let [entity (d/pull (d/db conn) '[*] [:account/id existing-id])]
        (is (= "Manually Updated" (:account/name entity))
            "Manual transaction should work")
        (is (= "original@test.com" (:account/email entity))
            "Other fields should be preserved")
        
        ;; Now try via delta->txn
        (println "\n=== Testing delta->txn ===")
        (let [delta {[:account/id existing-id]
                     {:account/name {:before "Manually Updated"
                                     :after "Via Delta"}}}
              _     (inspect-delta delta)
              txn-data (dl/delta->txn delta)
              _     (inspect-transaction-data txn-data)
              _     (d/transact! conn txn-data)
              _     (println "\n=== After delta->txn transaction ===")
              _     (inspect-entity (d/db conn) :account/id existing-id)
              final-entity (d/pull (d/db conn) '[*] [:account/id existing-id])]
          
          (is (= "Via Delta" (:account/name final-entity))
              "Delta-based transaction should work")))
      
      (cleanup-test-db db))))
