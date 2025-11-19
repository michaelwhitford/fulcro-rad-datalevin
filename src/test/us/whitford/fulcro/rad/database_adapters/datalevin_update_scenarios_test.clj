(ns us.whitford.fulcro.rad.database-adapters.datalevin-update-scenarios-test
  "Comprehensive tests for update scenarios to catch 'silent revert' bugs.
   These tests simulate real-world update operations that might fail silently."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.middleware.save-middleware :as save-mw]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; Test Attributes - Matching the test app model
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

(def account-created-at
  {::attr/qualified-key :account/created-at
   ::attr/type          :instant
   ::attr/schema        :main
   ::attr/identities    #{:account/id}})

(def test-attributes
  [account-id account-name account-email account-active account-created-at])

;; ================================================================================
;; Helper Functions
;; ================================================================================

(defn create-test-db
  "Create a test database with schema."
  []
  (let [path (str "/tmp/datalevin-update-test-" (new-uuid))
        schema (dl/automatic-schema :main test-attributes)
        conn (d/get-conn path schema)]
    {:conn conn
     :path path}))

(defn cleanup-test-db
  "Cleanup test database."
  [{:keys [conn path]}]
  (when conn
    (d/close conn))
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn create-save-middleware
  "Create the save middleware as the test app does."
  []
  (-> (fn [env] {})
      ((dl/wrap-datalevin-save {:default-schema :main}))
      (save-mw/wrap-rewrite-values)))

;; ================================================================================
;; Critical Test: Simple Update That Was Silently Reverting
;; ================================================================================

(deftest update-single-field-on-existing-account
  (testing "Update a single field on an existing account"
    (let [{:keys [conn] :as db} (create-test-db)
          ;; Create an existing account
          existing-id (new-uuid)
          created-at  (java.util.Date.)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Original Name"
                                          :account/email "original@example.com"
                                          :account/active? false
                                          :account/created-at created-at}])
          
          ;; User changes just the name in the UI
          delta {[:account/id existing-id]
                 {:account/name {:before "Original Name"
                                 :after "Updated Name"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]
                     ::form/params         {:account/id existing-id
                                            :account/name "Updated Name"
                                            :account/email "original@example.com"
                                            :account/active? false}}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      ;; Verify result structure
      (is (map? result) "Result must be a map")
      (is (contains? result :tempids) ":tempids must be present")
      (is (empty? (:tempids result)) "No tempids for existing entity update")
      
      ;; CRITICAL: Verify the database was actually updated
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "Updated Name" (:account/name entity))
            "CRITICAL: Name should be updated in database")
        (is (= "original@example.com" (:account/email entity))
            "Email should remain unchanged")
        (is (false? (:account/active? entity))
            "Active flag should remain unchanged")
        (is (= created-at (:account/created-at entity))
            "Created-at should remain unchanged"))
      
      (cleanup-test-db db))))

(deftest update-multiple-fields-on-existing-account
  (testing "Update multiple fields on an existing account"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "John Doe"
                                          :account/email "john@example.com"
                                          :account/active? false}])
          
          ;; User changes name, email, and active status
          delta {[:account/id existing-id]
                 {:account/name {:before "John Doe"
                                 :after "Jane Doe"}
                  :account/email {:before "john@example.com"
                                  :after "jane@example.com"}
                  :account/active? {:before false
                                    :after true}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids))
      
      ;; Verify ALL changes were applied
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "Jane Doe" (:account/name entity))
            "Name should be updated")
        (is (= "jane@example.com" (:account/email entity))
            "Email should be updated")
        (is (true? (:account/active? entity))
            "Active status should be updated"))
      
      (cleanup-test-db db))))

(deftest update-with-nil-before-value
  (testing "Update field that was previously nil (optional field)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          ;; Create account without created-at
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test User"
                                          :account/email "test@example.com"}])
          
          ;; Now add created-at
          now   (java.util.Date.)
          delta {[:account/id existing-id]
                 {:account/created-at {:before nil
                                       :after now}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      ;; Verify the field was added
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (some? (:account/created-at entity))
            "Created-at should now be set")
        (is (= now (:account/created-at entity))
            "Created-at should have the correct value"))
      
      (cleanup-test-db db))))

(deftest update-with-nil-after-value
  (testing "Update field to nil (remove optional field)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          now         (java.util.Date.)
          ;; Create account with created-at
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test User"
                                          :account/email "test@example.com"
                                          :account/created-at now}])
          
          ;; Remove created-at (set to nil)
          delta {[:account/id existing-id]
                 {:account/created-at {:before now
                                       :after nil}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      ;; Verify the field was removed
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (nil? (:account/created-at entity))
            "Created-at should be removed"))
      
      (cleanup-test-db db))))

(deftest update-with-mismatched-before-value
  (testing "Update where :before value doesn't match database (potential race condition)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          ;; Database has one value
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Database Value"
                                          :account/email "db@example.com"}])
          
          ;; But delta thinks it had a different :before value
          delta {[:account/id existing-id]
                 {:account/name {:before "Stale UI Value"  ; This doesn't match!
                                 :after "New Value"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      ;; The current implementation applies the update regardless of :before value
      ;; This test documents that behavior
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "New Value" (:account/name entity))
            "Update should be applied even if :before doesn't match (last-write-wins)"))
      
      (cleanup-test-db db))))

(deftest update-boolean-false-to-true
  (testing "Update boolean from false to true (edge case for falsy values)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test"
                                          :account/email "test@example.com"
                                          :account/active? false}])
          
          delta {[:account/id existing-id]
                 {:account/active? {:before false
                                    :after true}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (true? (:account/active? entity))
            "Boolean false should be updateable to true"))
      
      (cleanup-test-db db))))

(deftest update-boolean-true-to-false
  (testing "Update boolean from true to false"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test"
                                          :account/email "test@example.com"
                                          :account/active? true}])
          
          delta {[:account/id existing-id]
                 {:account/active? {:before true
                                    :after false}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (false? (:account/active? entity))
            "Boolean true should be updateable to false"))
      
      (cleanup-test-db db))))

(deftest update-no-actual-change
  (testing "Delta with :before equal to :after (no actual change)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Same Name"
                                          :account/email "same@example.com"}])
          
          ;; Delta shows change but before == after
          delta {[:account/id existing-id]
                 {:account/name {:before "Same Name"
                                 :after "Same Name"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      ;; Should be a no-op but not error
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "Same Name" (:account/name entity))))
      
      (cleanup-test-db db))))

;; ================================================================================
;; Test: Update with wrap-rewrite-values (as test app does)
;; ================================================================================

(deftest update-with-rewrite-values-middleware
  (testing "Update works correctly with wrap-rewrite-values middleware"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Original"
                                          :account/email "original@test.com"
                                          :account/active? false}])
          
          ;; This simulates what RAD sends
          delta {[:account/id existing-id]
                 {:account/name {:before "Original"
                                 :after "Modified"}
                  :account/active? {:before false
                                    :after true}}}
          
          params {:account/id existing-id
                  :account/name "Modified"
                  :account/email "original@test.com"
                  :account/active? true}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/params         params
                     ::form/id             [:account/id existing-id]
                     ::form/master-pk      :account/id}
          
          ;; Use the exact middleware stack from the test app
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result) "Result must be a map")
      (is (contains? result :tempids) ":tempids must be present")
      
      ;; CRITICAL: Verify changes were persisted
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "Modified" (:account/name entity))
            "CRITICAL: Name change must be persisted")
        (is (true? (:account/active? entity))
            "CRITICAL: Active change must be persisted")
        (is (= "original@test.com" (:account/email entity))
            "Unchanged field must remain"))
      
      (cleanup-test-db db))))

;; ================================================================================
;; Test: Sequence of Updates (state machine)
;; ================================================================================

(deftest sequence-of-updates-on-same-entity
  (testing "Multiple sequential updates to the same entity"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          
          ;; Initial state
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Initial"
                                          :account/email "initial@test.com"
                                          :account/active? false}])
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          save-handler (create-save-middleware)
          
          ;; First update: change name
          delta1 {[:account/id existing-id]
                  {:account/name {:before "Initial"
                                  :after "First Update"}}}
          env1   {::attr/key->attribute key->attr
                  ::dlo/connections     {:main conn}
                  ::form/delta          delta1
                  ::form/id             [:account/id existing-id]}
          _      (save-handler env1)
          
          ;; Verify first update
          db1    (d/db conn)
          entity1 (d/pull db1 '[*] [:account/id existing-id])]
      
      (is (= "First Update" (:account/name entity1))
          "First update should be applied")
      
      ;; Second update: change active status
      (let [delta2 {[:account/id existing-id]
                    {:account/active? {:before false
                                       :after true}}}
            env2   {::attr/key->attribute key->attr
                    ::dlo/connections     {:main conn}
                    ::form/delta          delta2
                    ::form/id             [:account/id existing-id]}
            _      (save-handler env2)
            
            db2    (d/db conn)
            entity2 (d/pull db2 '[*] [:account/id existing-id])]
        
        (is (= "First Update" (:account/name entity2))
            "Previous update should still be there")
        (is (true? (:account/active? entity2))
            "Second update should be applied")
        
        ;; Third update: change name again
        (let [delta3 {[:account/id existing-id]
                      {:account/name {:before "First Update"
                                      :after "Final Update"}}}
              env3   {::attr/key->attribute key->attr
                      ::dlo/connections     {:main conn}
                      ::form/delta          delta3
                      ::form/id             [:account/id existing-id]}
              _      (save-handler env3)
              
              db3    (d/db conn)
              entity3 (d/pull db3 '[*] [:account/id existing-id])]
          
          (is (= "Final Update" (:account/name entity3))
              "Third update should be applied")
          (is (true? (:account/active? entity3))
              "Previous updates should still be there")))
      
      (cleanup-test-db db))))

;; ================================================================================
;; Test: Edge Cases That Might Cause Silent Failures
;; ================================================================================

(deftest update-with-empty-string
  (testing "Update field to empty string (not nil)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Has Name"
                                          :account/email "has@email.com"}])
          
          delta {[:account/id existing-id]
                 {:account/name {:before "Has Name"
                                 :after ""}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "" (:account/name entity))
            "Empty string should be a valid value"))
      
      (cleanup-test-db db))))

(deftest update-with-whitespace-only
  (testing "Update field to whitespace-only string"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Valid Name"
                                          :account/email "valid@email.com"}])
          
          delta {[:account/id existing-id]
                 {:account/name {:before "Valid Name"
                                 :after "   "}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id existing-id]}
          
          save-handler (create-save-middleware)
          result       (save-handler env)]
      
      (is (map? result))
      
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id existing-id])]
        (is (= "   " (:account/name entity))
            "Whitespace string should be saved"))
      
      (cleanup-test-db db))))

(deftest update-unique-field-to-existing-value
  (testing "Update unique field (email) to value that already exists should fail"
    (let [{:keys [conn] :as db} (create-test-db)
          id1 (new-uuid)
          id2 (new-uuid)
          ;; Create two accounts with different emails
          _   (d/transact! conn [{:account/id id1
                                  :account/name "User 1"
                                  :account/email "user1@test.com"}
                                 {:account/id id2
                                  :account/name "User 2"
                                  :account/email "user2@test.com"}])
          
          ;; Try to change user2's email to user1's email (should fail due to unique constraint)
          delta {[:account/id id2]
                 {:account/email {:before "user2@test.com"
                                  :after "user1@test.com"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id id2]}
          
          save-handler (create-save-middleware)]
      
      ;; This should throw due to unique constraint violation
      (is (thrown? Exception
                   (save-handler env))
          "Should throw on unique constraint violation")
      
      ;; Verify original values are unchanged
      (let [db      (d/db conn)
            entity2 (d/pull db '[*] [:account/id id2])]
        (is (= "user2@test.com" (:account/email entity2))
            "Email should remain unchanged after failed update"))
      
      (cleanup-test-db db))))

;; ================================================================================
;; Test: Update Logging and Debugging
;; ================================================================================

(deftest update-produces-transaction-data
  (testing "Verify that updates produce correct transaction data"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Original"
                                          :account/email "original@test.com"}])
          
          delta {[:account/id existing-id]
                 {:account/name {:before "Original"
                                 :after "Updated"}}}
          
          ;; Build transaction data directly to inspect it
          txn-data (dl/delta->txn delta)]
      
      ;; Transaction data should contain the update
      (is (vector? txn-data) "Transaction data should be a vector")
      (is (seq txn-data) "Transaction data should not be empty")
      
      ;; Should have an entity map with the lookup ref
      (let [entity-maps (filter map? txn-data)]
        (is (seq entity-maps) "Should have at least one entity map")
        (let [entity-map (first entity-maps)]
          (is (= [:account/id existing-id] (:db/id entity-map))
              "Entity map should use lookup ref")
          (is (= "Updated" (:account/name entity-map))
              "Entity map should contain updated value")))
      
      (cleanup-test-db db))))
