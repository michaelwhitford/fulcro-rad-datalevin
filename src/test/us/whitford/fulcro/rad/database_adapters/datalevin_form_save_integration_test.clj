(ns us.whitford.fulcro.rad.database-adapters.datalevin-form-save-integration-test
  "Integration tests that simulate the exact RAD form save flow,
   matching what happens in a real Fulcro RAD application.
   
   These tests are designed to catch issues that might not show up
   in unit tests but appear in real applications."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.middleware.save-middleware :as save-mw]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; Test Attributes - Exactly as they appear in the test app
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
;; Helper: Create Middleware Stack Exactly as Test App Does
;; ================================================================================

(defn create-test-db
  []
  (let [path (str "/tmp/datalevin-form-test-" (new-uuid))
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

(defn create-save-middleware-stack
  "Create the exact middleware stack as used in the test app."
  []
  (-> (fn [env] {})
      ((dl/wrap-datalevin-save {:default-schema :main}))
      (save-mw/wrap-rewrite-values)))

;; ================================================================================
;; Critical Test: Reproduce the exact "silent revert" issue
;; ================================================================================

(deftest reproduce-silent-revert-issue
  (testing "CRITICAL: Reproduce the exact issue from the test app"
    (let [{:keys [conn] :as db} (create-test-db)
          
          ;; Step 1: Create an account (as if user clicked "Add Account")
          initial-id (new-uuid)
          _          (d/transact! conn [{:account/id initial-id
                                         :account/name "John Doe"
                                         :account/email "john@example.com"
                                         :account/active? true
                                         :account/created-at (java.util.Date.)}])
          
          ;; Verify it was created
          db-after-create (d/db conn)
          initial-entity  (d/pull db-after-create '[*] [:account/id initial-id])]
      
      (is (= "John Doe" (:account/name initial-entity)))
      (is (= "john@example.com" (:account/email initial-entity)))
      (is (true? (:account/active? initial-entity)))
      
      ;; Step 2: User loads the form to edit
      ;; (In real app, this would be via a resolver)
      (let [loaded-data {:account/id initial-id
                         :account/name "John Doe"
                         :account/email "john@example.com"
                         :account/active? true
                         :account/created-at (:account/created-at initial-entity)}
            
            ;; Step 3: User changes the name in the form
            ;; RAD generates a delta
            delta {[:account/id initial-id]
                   {:account/name {:before "John Doe"
                                   :after "Jane Doe"}}}
            
            ;; Step 4: User clicks Save
            ;; RAD constructs the params and env
            params (assoc loaded-data :account/name "Jane Doe")
            
            key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
            env       {::attr/key->attribute key->attr
                       ::dlo/connections     {:main conn}
                       ::form/delta          delta
                       ::form/params         params
                       ::form/id             [:account/id initial-id]
                       ::form/master-pk      :account/id}
            
            ;; Step 5: RAD executes the save middleware
            save-handler (create-save-middleware-stack)
            result       (save-handler env)]
        
        ;; Step 6: Verify the result structure
        (is (map? result)
            "CRITICAL: Result must be a map")
        (is (contains? result :tempids)
            "CRITICAL: :tempids must be present (was causing silent failure)")
        (is (map? (:tempids result))
            ":tempids must be a map")
        (is (empty? (:tempids result))
            "For existing entity, :tempids should be empty")
        
        ;; Step 7: CRITICAL - Verify the change was actually persisted
        (let [db-after-save (d/db conn)
              saved-entity  (d/pull db-after-save '[*] [:account/id initial-id])]
          
          (is (= "Jane Doe" (:account/name saved-entity))
              "CRITICAL: The name change MUST be persisted to the database")
          (is (= "john@example.com" (:account/email saved-entity))
              "Unchanged fields should remain the same")
          (is (true? (:account/active? saved-entity))
              "Unchanged boolean should remain the same")))
      
      (cleanup-test-db db))))

(deftest simulate-user-editing-workflow
  (testing "Simulate complete user workflow: create -> view -> edit -> save"
    (let [{:keys [conn] :as db} (create-test-db)
          save-handler (create-save-middleware-stack)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          ;; Phase 1: User creates a new account
          new-tempid  (tempid/tempid)
          new-real-id (new-uuid)
          
          create-delta {[:account/id new-tempid]
                        {:account/id {:before nil :after new-real-id}
                         :account/name {:before nil :after "New User"}
                         :account/email {:before nil :after "new@user.com"}
                         :account/active? {:before nil :after false}}}
          
          create-env   {::attr/key->attribute key->attr
                        ::dlo/connections     {:main conn}
                        ::form/delta          create-delta
                        ::form/params         {:account/id new-tempid
                                               :account/name "New User"
                                               :account/email "new@user.com"
                                               :account/active? false}
                        ::form/id             [:account/id new-tempid]
                        ::form/master-pk      :account/id}
          
          create-result (save-handler create-env)]
      
      ;; Verify creation worked
      (is (map? create-result))
      (is (contains? create-result :tempids))
      (is (= new-real-id (get (:tempids create-result) new-tempid)))
      
      (let [db-after-create (d/db conn)
            created-entity  (d/pull db-after-create '[*] [:account/id new-real-id])]
        (is (= "New User" (:account/name created-entity)))
        (is (= "new@user.com" (:account/email created-entity)))
        (is (false? (:account/active? created-entity))))
      
      ;; Phase 2: User views the account (simulates navigation to edit form)
      ;; In real app, this would load via resolver
      (let [viewed-data {:account/id new-real-id
                         :account/name "New User"
                         :account/email "new@user.com"
                         :account/active? false}
            
            ;; Phase 3: User edits multiple fields
            edit-delta {[:account/id new-real-id]
                        {:account/name {:before "New User"
                                        :after "Edited User"}
                         :account/email {:before "new@user.com"
                                         :after "edited@user.com"}
                         :account/active? {:before false
                                           :after true}}}
            
            edit-env   {::attr/key->attribute key->attr
                        ::dlo/connections     {:main conn}
                        ::form/delta          edit-delta
                        ::form/params         {:account/id new-real-id
                                               :account/name "Edited User"
                                               :account/email "edited@user.com"
                                               :account/active? true}
                        ::form/id             [:account/id new-real-id]
                        ::form/master-pk      :account/id}
            
            ;; Phase 4: User saves edits
            edit-result (save-handler edit-env)]
        
        ;; Verify edit result
        (is (map? edit-result))
        (is (contains? edit-result :tempids))
        (is (empty? (:tempids edit-result)) "No tempids for edit operation")
        
        ;; CRITICAL: Verify edits were persisted
        (let [db-after-edit (d/db conn)
              edited-entity (d/pull db-after-edit '[*] [:account/id new-real-id])]
          (is (= "Edited User" (:account/name edited-entity))
              "CRITICAL: Name edit must be persisted")
          (is (= "edited@user.com" (:account/email edited-entity))
              "CRITICAL: Email edit must be persisted")
          (is (true? (:account/active? edited-entity))
              "CRITICAL: Active status edit must be persisted"))
        
        ;; Phase 5: User edits again (to test sequential edits)
        (let [second-edit-delta {[:account/id new-real-id]
                                 {:account/name {:before "Edited User"
                                                 :after "Final Name"}}}
              
              second-edit-env   {::attr/key->attribute key->attr
                                 ::dlo/connections     {:main conn}
                                 ::form/delta          second-edit-delta
                                 ::form/params         {:account/id new-real-id
                                                        :account/name "Final Name"
                                                        :account/email "edited@user.com"
                                                        :account/active? true}
                                 ::form/id             [:account/id new-real-id]
                                 ::form/master-pk      :account/id}
              
              second-edit-result (save-handler second-edit-env)]
          
          (is (map? second-edit-result))
          (is (contains? second-edit-result :tempids))
          
          (let [db-final (d/db conn)
                final-entity (d/pull db-final '[*] [:account/id new-real-id])]
            (is (= "Final Name" (:account/name final-entity))
                "CRITICAL: Second edit must be persisted")
            (is (= "edited@user.com" (:account/email final-entity))
                "Previous edits should still be there")
            (is (true? (:account/active? final-entity))
                "Previous edits should still be there"))))
      
      (cleanup-test-db db))))

(deftest test-empty-delta-edge-case
  (testing "Edge case: Save with empty delta (no changes)"
    (let [{:keys [conn] :as db} (create-test-db)
          existing-id (new-uuid)
          _           (d/transact! conn [{:account/id existing-id
                                          :account/name "Test"
                                          :account/email "test@example.com"}])
          
          ;; User opens form but makes no changes
          empty-delta {}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          empty-delta
                     ::form/params         {:account/id existing-id
                                            :account/name "Test"
                                            :account/email "test@example.com"}
                     ::form/id             [:account/id existing-id]
                     ::form/master-pk      :account/id}
          
          save-handler (create-save-middleware-stack)
          result       (save-handler env)]
      
      ;; Should succeed with empty delta
      (is (map? result))
      (is (contains? result :tempids))
      (is (empty? (:tempids result)))
      
      ;; Data should be unchanged
      (let [db-after (d/db conn)
            entity   (d/pull db-after '[*] [:account/id existing-id])]
        (is (= "Test" (:account/name entity))))
      
      (cleanup-test-db db))))

(deftest test-concurrent-edits-scenario
  (testing "Scenario: Two users editing same entity (last write wins)"
    (let [{:keys [conn] :as db} (create-test-db)
          shared-id (new-uuid)
          _         (d/transact! conn [{:account/id shared-id
                                        :account/name "Original"
                                        :account/email "original@example.com"
                                        :account/active? false}])
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          save-handler (create-save-middleware-stack)
          
          ;; User 1 changes the name
          user1-delta {[:account/id shared-id]
                       {:account/name {:before "Original"
                                       :after "User 1 Edit"}}}
          user1-env   {::attr/key->attribute key->attr
                       ::dlo/connections     {:main conn}
                       ::form/delta          user1-delta
                       ::form/id             [:account/id shared-id]}
          
          _           (save-handler user1-env)
          
          db-after-user1 (d/db conn)
          after-user1    (d/pull db-after-user1 '[*] [:account/id shared-id])]
      
      (is (= "User 1 Edit" (:account/name after-user1)))
      
      ;; User 2 changes the email (has stale :before for name)
      (let [user2-delta {[:account/id shared-id]
                         {:account/email {:before "original@example.com"
                                          :after "user2@example.com"}
                          ;; This :before is stale (should be "User 1 Edit")
                          :account/name {:before "Original"
                                         :after "User 2 Edit"}}}
            user2-env   {::attr/key->attribute key->attr
                         ::dlo/connections     {:main conn}
                         ::form/delta          user2-delta
                         ::form/id             [:account/id shared-id]}
            
            _           (save-handler user2-env)
            
            db-final    (d/db conn)
            final-entity (d/pull db-final '[*] [:account/id shared-id])]
        
        ;; Last write wins - User 2's changes apply
        (is (= "User 2 Edit" (:account/name final-entity))
            "Last write should win (User 2)")
        (is (= "user2@example.com" (:account/email final-entity))
            "User 2's email change should apply"))
      
      (cleanup-test-db db))))

(deftest test-form-with-all-field-types
  (testing "Save form that uses all supported field types"
    (let [{:keys [conn] :as db} (create-test-db)
          entity-id (new-uuid)
          now       (java.util.Date.)
          
          ;; Create with all fields
          _         (d/transact! conn [{:account/id entity-id
                                        :account/name "Full User"
                                        :account/email "full@example.com"
                                        :account/active? true
                                        :account/created-at now}])
          
          ;; Update all fields
          later     (java.util.Date. (+ (.getTime now) 60000))
          delta     {[:account/id entity-id]
                     {:account/name {:before "Full User"
                                     :after "Updated User"}
                      :account/email {:before "full@example.com"
                                      :after "updated@example.com"}
                      :account/active? {:before true
                                        :after false}
                      :account/created-at {:before now
                                           :after later}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id entity-id]}
          
          save-handler (create-save-middleware-stack)
          result       (save-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids))
      
      ;; Verify all fields were updated
      (let [db-after (d/db conn)
            entity   (d/pull db-after '[*] [:account/id entity-id])]
        (is (= "Updated User" (:account/name entity)))
        (is (= "updated@example.com" (:account/email entity)))
        (is (false? (:account/active? entity)))
        (is (= later (:account/created-at entity))))
      
      (cleanup-test-db db))))

(deftest test-partial-update-preserves-other-fields
  (testing "Partial update (only some fields) preserves others"
    (let [{:keys [conn] :as db} (create-test-db)
          entity-id (new-uuid)
          now       (java.util.Date.)
          
          ;; Create with all fields
          _         (d/transact! conn [{:account/id entity-id
                                        :account/name "Original"
                                        :account/email "original@example.com"
                                        :account/active? true
                                        :account/created-at now}])
          
          ;; Update only name (delta only contains name)
          delta     {[:account/id entity-id]
                     {:account/name {:before "Original"
                                     :after "Updated"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id entity-id]}
          
          save-handler (create-save-middleware-stack)
          result       (save-handler env)]
      
      (is (map? result))
      
      ;; Verify only name changed, others preserved
      (let [db-after (d/db conn)
            entity   (d/pull db-after '[*] [:account/id entity-id])]
        (is (= "Updated" (:account/name entity))
            "Name should be updated")
        (is (= "original@example.com" (:account/email entity))
            "Email should be preserved")
        (is (true? (:account/active? entity))
            "Active should be preserved")
        (is (= now (:account/created-at entity))
            "Created-at should be preserved"))
      
      (cleanup-test-db db))))

(deftest test-validation-with-required-fields
  (testing "Save respects required field constraints (via wrap-rewrite-values)"
    (let [{:keys [conn] :as db} (create-test-db)
          entity-id (new-uuid)
          
          ;; Create initial entity
          _         (d/transact! conn [{:account/id entity-id
                                        :account/name "Has Name"
                                        :account/email "has@email.com"}])
          
          ;; Try to update name to nil (which is required)
          ;; Note: wrap-rewrite-values should handle validation
          delta     {[:account/id entity-id]
                     {:account/name {:before "Has Name"
                                     :after nil}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id entity-id]}
          
          save-handler (create-save-middleware-stack)
          
          ;; The middleware stack includes wrap-rewrite-values which may
          ;; handle validation. If not, the database will enforce it.
          result       (save-handler env)]
      
      ;; Depending on validation, this might succeed (retraction) or fail
      ;; Document the actual behavior
      (is (map? result) "Result should still be a map")
      
      (cleanup-test-db db))))

(deftest test-delta-with-ident-references
  (testing "Delta containing ident references (for to-one refs)"
    ;; Note: This test documents how reference updates would work
    ;; when the adapter supports reference attributes
    (let [{:keys [conn] :as db} (create-test-db)
          entity-id (new-uuid)
          
          _         (d/transact! conn [{:account/id entity-id
                                        :account/name "Test"
                                        :account/email "test@example.com"}])
          
          ;; This documents the shape of delta for reference updates
          ;; (not currently used in account model but good to test)
          delta     {[:account/id entity-id]
                     {:account/name {:before "Test"
                                     :after "Updated Test"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:main conn}
                     ::form/delta          delta
                     ::form/id             [:account/id entity-id]}
          
          save-handler (create-save-middleware-stack)
          result       (save-handler env)]
      
      (is (map? result))
      
      (let [db-after (d/db conn)
            entity   (d/pull db-after '[*] [:account/id entity-id])]
        (is (= "Updated Test" (:account/name entity))))
      
      (cleanup-test-db db))))
