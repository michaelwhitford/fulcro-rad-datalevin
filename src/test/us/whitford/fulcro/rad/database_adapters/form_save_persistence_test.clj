(ns us.whitford.fulcro.rad.database-adapters.form-save-persistence-test
  "Tests specifically targeting form save persistence issues.
   These tests simulate the complete form lifecycle: load → edit → save → verify persistence."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; Complete Form Lifecycle Tests
;; ================================================================================

(deftest form-edit-and-save-persists
  (testing "editing an existing form and saving persists changes to database"
    (tu/with-test-conn [conn]
      ;; Step 1: Create initial entity (simulating form load)
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Original Name"
                                         :account/email "original@example.com"
                                         :account/active? true}])

            ;; Step 2: Verify initial data
            initial   (d/pull (d/db conn) '[*] [:account/id entity-id])]
        (is (= "Original Name" (:account/name initial)))
        (is (= "original@example.com" (:account/email initial)))
        (is (true? (:account/active? initial)))

        ;; Step 3: Simulate form edit (user changes name and email)
        (let [delta     {[:account/id entity-id]
                        {:account/name {:before "Original Name" :after "Updated Name"}
                         :account/email {:before "original@example.com" :after "updated@example.com"}}}
              env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/delta          delta}
              middleware (dl/wrap-datalevin-save {:default-schema :test})

              ;; Step 4: Execute save
              result    ((middleware (fn [_] {:status :success})) env)]

          (is (map? result) "Result should be a map")
          (is (= :success (:status result)) "Base handler result preserved")

          ;; Step 5: CRITICAL - Verify changes actually persisted
          (let [updated (d/pull (d/db conn) '[*] [:account/id entity-id])]
            (is (= "Updated Name" (:account/name updated))
                "Name change should persist to database")
            (is (= "updated@example.com" (:account/email updated))
                "Email change should persist to database")
            (is (true? (:account/active? updated))
                "Unchanged fields should remain intact")))))))

(deftest form-save-then-reload-shows-changes
  (testing "saving form changes and reloading shows the new values"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            ;; Create initial entity
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "First Version"
                                         :account/balance 100.0}])

            ;; User edits the form
            delta     {[:account/id entity-id]
                      {:account/name {:before "First Version" :after "Second Version"}
                       :account/balance {:before 100.0 :after 250.50}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})

            ;; Save
            _         ((middleware (fn [_] {})) env)

            ;; Simulate form reload by querying database
            reloaded  (d/pull (d/db conn) '[*] [:account/id entity-id])]

        (is (= "Second Version" (:account/name reloaded))
            "Reloaded form should show updated name")
        (is (= 250.50 (:account/balance reloaded))
            "Reloaded form should show updated balance")))))

(deftest multiple-edits-in-sequence-all-persist
  (testing "making multiple sequential edits, each one persists"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            ;; Create initial entity
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Version 1"}])
            middleware (dl/wrap-datalevin-save {:default-schema :test})]

        ;; Edit 1: Change name
        (let [delta1 {[:account/id entity-id]
                     {:account/name {:before "Version 1" :after "Version 2"}}}
              env1   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta1}]
          ((middleware (fn [_] {})) env1))

        (is (= "Version 2" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "First edit should persist")

        ;; Edit 2: Change name again
        (let [delta2 {[:account/id entity-id]
                     {:account/name {:before "Version 2" :after "Version 3"}}}
              env2   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta2}]
          ((middleware (fn [_] {})) env2))

        (is (= "Version 3" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "Second edit should persist")

        ;; Edit 3: Add email field
        (let [delta3 {[:account/id entity-id]
                     {:account/email {:before nil :after "new@example.com"}}}
              env3   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta3}]
          ((middleware (fn [_] {})) env3))

        (let [final (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= "Version 3" (:account/name final))
              "Previous edits should remain")
          (is (= "new@example.com" (:account/email final))
              "Third edit should persist"))))))

;; ================================================================================
;; New Entity Creation Tests
;; ================================================================================

(deftest new-form-creation-and-save-persists
  (testing "creating a new entity via form and saving persists to database"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            ;; User fills out new form
            delta     {[:account/id tid]
                      {:account/id {:before nil :after real-id}
                       :account/name {:before nil :after "New Account"}
                       :account/email {:before nil :after "new@example.com"}
                       :account/active? {:before nil :after true}
                       :account/balance {:before nil :after 500.0}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result    ((middleware (fn [_] {})) env)]

        ;; Verify tempid mapping returned
        (is (contains? result :tempids) "Should return tempid mapping")
        (is (= real-id (get (:tempids result) tid))
            "Tempid should map to real ID")

        ;; CRITICAL: Verify entity actually exists in database
        (let [saved (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (some? saved) "Entity should exist in database")
          (is (= real-id (:account/id saved)))
          (is (= "New Account" (:account/name saved))
              "New entity should have all fields persisted")
          (is (= "new@example.com" (:account/email saved)))
          (is (true? (:account/active? saved)))
          (is (= 500.0 (:account/balance saved))))))))

(deftest new-form-save-then-edit-both-persist
  (testing "creating new entity, then editing it - both operations persist"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            middleware (dl/wrap-datalevin-save {:default-schema :test})]

        ;; Step 1: Create new entity
        (let [create-delta {[:account/id tid]
                           {:account/id {:before nil :after real-id}
                            :account/name {:before nil :after "Initial Name"}}}
              create-env   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                           ::dlo/connections     {:test conn}
                           ::form/delta          create-delta}
              create-result ((middleware (fn [_] {})) create-env)]

          (is (= real-id (get (:tempids create-result) tid))
              "Creation should return tempid mapping"))

        ;; Verify creation persisted
        (is (= "Initial Name"
               (:account/name (d/pull (d/db conn) '[*] [:account/id real-id])))
            "Creation should persist")

        ;; Step 2: Edit the newly created entity
        (let [edit-delta {[:account/id real-id]
                         {:account/name {:before "Initial Name" :after "Edited Name"}}}
              edit-env   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                         ::dlo/connections     {:test conn}
                         ::form/delta          edit-delta}]
          ((middleware (fn [_] {})) edit-env))

        ;; Verify edit persisted
        (is (= "Edited Name"
               (:account/name (d/pull (d/db conn) '[*] [:account/id real-id])))
            "Edit after creation should persist")))))

;; ================================================================================
;; Result Format Tests
;; ================================================================================

(deftest save-result-includes-all-necessary-keys
  (testing "save result includes all keys needed for Fulcro form update"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            delta     {[:account/id tid]
                      {:account/id {:before nil :after real-id}
                       :account/name {:before nil :after "Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            ;; Simulate base RAD handler that returns standard keys
            base-handler (fn [_env]
                          {::form/id [:account/id tid]
                           ::form/complete? true})
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result    ((middleware base-handler) env)]

        ;; Verify result structure
        (is (map? result) "Result must be a map")
        (is (not (fn? result)) "Result must not be a function")

        ;; Verify base handler keys preserved
        (is (= [:account/id tid] (::form/id result))
            "Base handler ::form/id should be preserved")
        (is (true? (::form/complete? result))
            "Base handler ::form/complete? should be preserved")

        ;; Verify datalevin keys added
        (is (contains? result :tempids)
            "Result should include :tempids")
        (is (= real-id (get (:tempids result) tid))
            "Tempid should map to real ID")

        ;; Verify result is serializable (can be sent to client)
        (is (every? #(not (fn? %)) (vals result))
            "No value in result should be a function")))))

(deftest save-result-format-with-existing-entity
  (testing "save result format is correct for existing entity updates"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Original"}])
            delta     {[:account/id entity-id]
                      {:account/name {:before "Original" :after "Updated"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            base-handler (fn [_env]
                          {::form/id [:account/id entity-id]
                           ::form/complete? true})
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result    ((middleware base-handler) env)]

        ;; For existing entities, tempids might be empty or not present
        (is (map? result))
        (is (true? (::form/complete? result)))

        ;; Most importantly, verify the save actually happened
        (is (= "Updated"
               (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "Update must persist even if no tempids in result")))))

;; ================================================================================
;; Edge Cases and Error Scenarios
;; ================================================================================

(deftest partial-delta-updates-only-changed-fields
  (testing "delta with only some fields updates only those fields"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Name"
                                         :account/email "email@test.com"
                                         :account/balance 100.0}])
            ;; Delta only changes balance
            delta     {[:account/id entity-id]
                      {:account/balance {:before 100.0 :after 200.0}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _         ((middleware (fn [_] {})) env)]

        (let [updated (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= 200.0 (:account/balance updated))
              "Changed field should be updated")
          (is (= "Name" (:account/name updated))
              "Unchanged field should remain the same")
          (is (= "email@test.com" (:account/email updated))
              "Unchanged field should remain the same"))))))

(deftest boolean-toggle-persists
  (testing "toggling boolean field persists correctly"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Test"
                                         :account/active? true}])
            middleware (dl/wrap-datalevin-save {:default-schema :test})]

        ;; Toggle false
        (let [delta1 {[:account/id entity-id]
                     {:account/active? {:before true :after false}}}
              env1   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta1}]
          ((middleware (fn [_] {})) env1))

        (is (false? (:account/active? (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "Toggle to false should persist")

        ;; Toggle back to true
        (let [delta2 {[:account/id entity-id]
                     {:account/active? {:before false :after true}}}
              env2   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta2}]
          ((middleware (fn [_] {})) env2))

        (is (true? (:account/active? (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "Toggle back to true should persist")))))

(deftest numeric-field-changes-persist
  (testing "changing numeric fields persists exact values"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/balance 0.0}])
            middleware (dl/wrap-datalevin-save {:default-schema :test})

            test-values [100.0 0.01 999.99 0.0 1000000.50]]

        ;; Test multiple numeric values
        (doseq [value test-values]
          (let [prev-value (or (:account/balance (d/pull (d/db conn) '[*] [:account/id entity-id]))
                              0.0)
                delta {[:account/id entity-id]
                      {:account/balance {:before prev-value :after value}}}
                env   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}]
            ((middleware (fn [_] {})) env)

            (is (= value (:account/balance (d/pull (d/db conn) '[*] [:account/id entity-id])))
                (str "Numeric value " value " should persist exactly"))))))))

;; ================================================================================
;; Middleware Integration Tests
;; ================================================================================

(deftest middleware-works-in-rad-stack
  (testing "datalevin middleware works correctly when composed in RAD stack"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Original"}])
            delta     {[:account/id entity-id]
                      {:account/name {:before "Original" :after "Stack Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta
                      ::form/params         {:account/id entity-id
                                            :account/name "Stack Test"}
                      ::form/master-pk      :account/id}

            ;; Simulate typical RAD middleware stack
            base-handler (fn [env]
                          {::form/id [:account/id (get-in env [::form/params :account/id])]
                           ::form/complete? true
                           ::form/params (::form/params env)})

            ;; Compose middleware (order matters!)
            handler (-> base-handler
                        ((dl/wrap-datalevin-save {:default-schema :test})))

            result (handler env)]

        ;; Verify middleware composition
        (is (map? result) "Result should be a map")
        (is (::form/complete? result) "Base handler executed")

        ;; CRITICAL: Verify save actually happened
        (is (= "Stack Test"
               (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
            "Save should persist in middleware stack")))))

(deftest middleware-preserves-error-results
  (testing "middleware preserves error results from base handler"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            delta     {[:account/id entity-id]
                      {:account/name {:before nil :after "Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}

            ;; Base handler returns error
            base-handler (fn [_env]
                          {::form/complete? false
                           ::form/errors {:account/name "Invalid name"}})

            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result    ((middleware base-handler) env)]

        (is (false? (::form/complete? result))
            "Error result should be preserved")
        (is (contains? (::form/errors result) :account/name)
            "Error details should be preserved")))))

;; ================================================================================
;; Reference Field Tests
;; ================================================================================

(deftest reference-field-updates-persist
  (testing "updating reference fields (to-many relationships) persists"
    (tu/with-test-conn [conn]
      (let [account-id (new-uuid)
            item1-id   (new-uuid)
            item2-id   (new-uuid)

            ;; Create account and items
            _          (d/transact! conn [{:account/id account-id
                                          :account/name "Test Account"}
                                         {:item/id item1-id
                                          :item/name "Item 1"}
                                         {:item/id item2-id
                                          :item/name "Item 2"}])

            ;; Add reference from account to items
            delta      {[:account/id account-id]
                       {:account/items {:before []
                                       :after [[:item/id item1-id]
                                              [:item/id item2-id]]}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _          ((middleware (fn [_] {})) env)]

        ;; Verify references persisted
        (let [account (d/pull (d/db conn) '[:account/id :account/name {:account/items [:item/id :item/name]}]
                             [:account/id account-id])]
          (is (= 2 (count (:account/items account)))
              "Should have 2 items")
          (is (some #(= item1-id (:item/id %)) (:account/items account))
              "Should contain item 1")
          (is (some #(= item2-id (:item/id %)) (:account/items account))
              "Should contain item 2"))))))

;; ================================================================================
;; Performance and Stress Tests
;; ================================================================================

(deftest multiple-fields-update-in-single-save
  (testing "updating many fields at once all persist correctly"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Old"
                                         :account/email "old@test.com"
                                         :account/active? false
                                         :account/balance 0.0}])

            ;; Update all fields at once
            delta     {[:account/id entity-id]
                      {:account/name {:before "Old" :after "New Name"}
                       :account/email {:before "old@test.com" :after "new@test.com"}
                       :account/active? {:before false :after true}
                       :account/balance {:before 0.0 :after 1000.0}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _         ((middleware (fn [_] {})) env)]

        (let [updated (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= "New Name" (:account/name updated))
              "Name should update")
          (is (= "new@test.com" (:account/email updated))
              "Email should update")
          (is (true? (:account/active? updated))
              "Active flag should update")
          (is (= 1000.0 (:account/balance updated))
              "Balance should update"))))))

;; ================================================================================
;; Diagnostic Tests - These help identify WHERE the issue is
;; ================================================================================

(deftest diagnostic-delta-is-processed
  (testing "DIAGNOSTIC: verify delta is actually processed by middleware"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            delta     {[:account/id entity-id]
                      {:account/id {:before nil :after entity-id}
                       :account/name {:before nil :after "Diagnostic Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}

            ;; Track what happens
            processed? (atom false)
            base-handler (fn [_env]
                          (reset! processed? true)
                          {:status :base-called})

            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result    ((middleware base-handler) env)]

        (is @processed? "Base handler should be called")
        (is (map? result) "Result should be map")

        ;; Check if data was saved
        (let [saved (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (some? saved) "Entity should exist")
          (is (= "Diagnostic Test" (:account/name saved))
              "Data should be saved to database"))))))

(deftest diagnostic-middleware-execution-order
  (testing "DIAGNOSTIC: verify middleware executes in correct order"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            delta     {[:account/id entity-id]
                      {:account/id {:before nil :after entity-id}
                       :account/name {:before nil :after "Order Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}

            execution-log (atom [])

            base-handler (fn [_env]
                          (swap! execution-log conj :base-handler)
                          {:status :ok})

            ;; Wrap with datalevin save
            middleware-fn (dl/wrap-datalevin-save {:default-schema :test})
            handler (middleware-fn base-handler)

            _      (swap! execution-log conj :before-handler-call)
            result (handler env)
            _      (swap! execution-log conj :after-handler-call)]

        (is (= [:before-handler-call :base-handler :after-handler-call]
               @execution-log)
            "Execution order should be correct")

        (is (map? result))

        ;; Verify save happened
        (let [saved (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= "Order Test" (:account/name saved))
              "Save should complete despite execution order"))))))

(deftest diagnostic-connection-is-available
  (testing "DIAGNOSTIC: verify database connection is available and working"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)

            ;; First, directly test the connection works
            _         (d/transact! conn [{:account/id entity-id
                                         :account/name "Direct Write"}])
            direct    (d/pull (d/db conn) '[*] [:account/id entity-id])]

        (is (= "Direct Write" (:account/name direct))
            "Direct database write should work")

        ;; Now test through middleware
        (let [delta     {[:account/id entity-id]
                        {:account/name {:before "Direct Write" :after "Via Middleware"}}}
              env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/delta          delta}
              middleware (dl/wrap-datalevin-save {:default-schema :test})
              _         ((middleware (fn [_] {})) env)]

          ;; Check if middleware write worked
          (let [via-middleware (d/pull (d/db conn) '[*] [:account/id entity-id])]
            (is (= "Via Middleware" (:account/name via-middleware))
                "Middleware write should work if direct write works")))))))
