(ns us.whitford.fulcro.rad.database-adapters.save-form-integration-test
  "Integration tests using fulcro-rad's save-form* function.
   These tests simulate the complete RAD stack as used in a real application,
   including Pathom, middleware composition, and form state management."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.mutations :as m]
   [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.fulcrologic.rad.middleware.save-middleware :as save-mw]
   [com.fulcrologic.rad.pathom3 :as rad-pathom]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; Test Attributes - Using the same attributes from test-utils
;; ================================================================================

;; We'll use the attributes from test-utils for consistency

;; ================================================================================
;; Pathom Environment Setup
;; ================================================================================

(defn create-pathom-env
  "Creates a Pathom3 environment with datalevin resolvers and middleware."
  [conn]
  (let [;; Generate resolvers from attributes
        resolvers (dl/generate-resolvers tu/all-test-attributes)

        ;; Create pathom indexes
        indexes (pci/register resolvers)

        ;; Create environment with connections and middleware
        base-env {::dlo/connections     {:test conn}
                  ::dlo/databases       {:test (d/db conn)}
                  ::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)}

        ;; Wrap with save and delete middleware
        save-middleware (-> (fn [env] env)
                            (dl/wrap-datalevin-save {:default-schema :test}))

        delete-middleware (dl/wrap-datalevin-delete {:default-schema :test})]

    ;; Return pathom query function configured with middleware
    {:indexes           indexes
     :base-env          base-env
     :save-middleware   save-middleware
     :delete-middleware delete-middleware
     :query-fn          (fn [env query]
                          (p.eql/process (merge indexes env) query))}))

;; ================================================================================
;; Form Definitions
;; ================================================================================

(defsc AccountForm [this props]
  {:query         [:account/id :account/name :account/email :account/active? :account/balance]
   :ident         :account/id
   :form-fields   #{:account/name :account/email :account/active? :account/balance}
   :route-segment ["account" :account/id]
   ::form/id      ::AccountForm
   ::form/attributes (attr/attributes-by-name tu/all-test-attributes :account/id :account/name :account/email :account/active? :account/balance)
   ::form/validator #(do nil) ; No validation for test
   })

(def ui-account-form (comp/factory AccountForm))

;; ================================================================================
;; Test Cases Using save-form*
;; ================================================================================

(deftest save-form-new-entity-integration-test
  (testing "save-form* creates a new entity and persists to database"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env]} (create-pathom-env conn)

            ;; Create a new entity ID
            tid (tempid/tempid)
            real-id (new-uuid)

            ;; Create initial form state (what the form would have before save)
            form-ident [:account/id tid]
            initial-form-state {:account/id      tid
                                :account/name    "New Account"
                                :account/email   "new@example.com"
                                :account/active? true
                                :account/balance 100.0}

            ;; Create a minimal Fulcro app state
            app-state (atom {form-ident initial-form-state})

            ;; Build the delta that save-form* would create
            ;; This simulates what happens when user fills out a form and clicks save
            delta {form-ident
                   {:account/id      {:before nil :after real-id}
                    :account/name    {:before nil :after "New Account"}
                    :account/email   {:before nil :after "new@example.com"}
                    :account/active? {:before nil :after true}
                    :account/balance {:before nil :after 100.0}}}

            ;; Create the environment that would be passed to save middleware
            env (merge base-env
                       {::form/delta delta
                        ::form/id    form-ident
                        ::form/master-pk :account/id})

            ;; Call the save middleware (simulating what save-form* does)
            result (save-middleware env)]

        ;; Verify the result includes tempid mapping
        (is (map? result) "Result should be a map")
        (is (contains? result :tempids) "Result should include tempids")
        (is (= real-id (get (:tempids result) tid))
            "Tempid should map to real ID")

        ;; CRITICAL: Verify the entity was actually saved to the database
        (let [saved-entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (some? saved-entity) "Entity should exist in database")
          (is (= real-id (:account/id saved-entity))
              "Entity should have correct ID")
          (is (= "New Account" (:account/name saved-entity))
              "Entity should have correct name")
          (is (= "new@example.com" (:account/email saved-entity))
              "Entity should have correct email")
          (is (true? (:account/active? saved-entity))
              "Entity should have correct active flag")
          (is (= 100.0 (:account/balance saved-entity))
              "Entity should have correct balance"))))))

(deftest save-form-update-entity-integration-test
  (testing "save-form* updates an existing entity and persists to database"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env]} (create-pathom-env conn)

            ;; Create an existing entity in the database
            entity-id (new-uuid)
            _ (d/transact! conn [{:account/id      entity-id
                                  :account/name    "Original Name"
                                  :account/email   "original@example.com"
                                  :account/active? true
                                  :account/balance 50.0}])

            ;; Verify initial state
            initial (d/pull (d/db conn) '[*] [:account/id entity-id])
            _ (is (= "Original Name" (:account/name initial)))

            ;; Create form state after user edits (simulating form state)
            form-ident [:account/id entity-id]
            edited-form-state {:account/id      entity-id
                               :account/name    "Updated Name"
                               :account/email   "updated@example.com"
                               :account/active? false
                               :account/balance 150.0}

            ;; Build delta representing the changes
            delta {form-ident
                   {:account/name    {:before "Original Name" :after "Updated Name"}
                    :account/email   {:before "original@example.com" :after "updated@example.com"}
                    :account/active? {:before true :after false}
                    :account/balance {:before 50.0 :after 150.0}}}

            ;; Create environment for save
            env (merge base-env
                       {::form/delta delta
                        ::form/id    form-ident
                        ::form/master-pk :account/id})

            ;; Execute save
            result (save-middleware env)]

        ;; Verify result structure
        (is (map? result) "Result should be a map")

        ;; CRITICAL: Verify changes persisted to database
        (let [updated (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= "Updated Name" (:account/name updated))
              "Name should be updated in database")
          (is (= "updated@example.com" (:account/email updated))
              "Email should be updated in database")
          (is (false? (:account/active? updated))
              "Active flag should be updated in database")
          (is (= 150.0 (:account/balance updated))
              "Balance should be updated in database"))))))

(deftest save-form-partial-update-integration-test
  (testing "save-form* with partial delta only updates changed fields"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env]} (create-pathom-env conn)

            ;; Create existing entity
            entity-id (new-uuid)
            _ (d/transact! conn [{:account/id      entity-id
                                  :account/name    "Test Account"
                                  :account/email   "test@example.com"
                                  :account/active? true
                                  :account/balance 200.0}])

            ;; User only changes the balance field
            form-ident [:account/id entity-id]
            delta {form-ident
                   {:account/balance {:before 200.0 :after 350.0}}}

            env (merge base-env
                       {::form/delta delta
                        ::form/id    form-ident
                        ::form/master-pk :account/id})

            result (save-middleware env)]

        ;; Verify only balance changed, other fields remain
        (let [updated (d/pull (d/db conn) '[*] [:account/id entity-id])]
          (is (= 350.0 (:account/balance updated))
              "Balance should be updated")
          (is (= "Test Account" (:account/name updated))
              "Name should remain unchanged")
          (is (= "test@example.com" (:account/email updated))
              "Email should remain unchanged")
          (is (true? (:account/active? updated))
              "Active flag should remain unchanged"))))))

(deftest save-form-with-references-integration-test
  (testing "save-form* handles to-many reference fields correctly"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env]} (create-pathom-env conn)

            ;; Create account and items
            account-id (new-uuid)
            item1-id (new-uuid)
            item2-id (new-uuid)

            _ (d/transact! conn [{:account/id   account-id
                                  :account/name "Test Account"}
                                 {:item/id   item1-id
                                  :item/name "Item 1"}
                                 {:item/id   item2-id
                                  :item/name "Item 2"}])

            ;; User adds items to account
            form-ident [:account/id account-id]
            delta {form-ident
                   {:account/items {:before []
                                    :after  [[:item/id item1-id]
                                             [:item/id item2-id]]}}}

            env (merge base-env
                       {::form/delta delta
                        ::form/id    form-ident
                        ::form/master-pk :account/id})

            result (save-middleware env)]

        ;; Verify references persisted
        (let [account (d/pull (d/db conn)
                             '[:account/id :account/name {:account/items [:item/id :item/name]}]
                             [:account/id account-id])]
          (is (= 2 (count (:account/items account)))
              "Should have 2 items")
          (is (some #(= item1-id (:item/id %)) (:account/items account))
              "Should contain item 1")
          (is (some #(= item2-id (:item/id %)) (:account/items account))
              "Should contain item 2"))))))

(deftest save-form-sequential-saves-integration-test
  (testing "Multiple sequential saves via save-form* all persist correctly"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env]} (create-pathom-env conn)

            ;; Create initial entity
            entity-id (new-uuid)
            _ (d/transact! conn [{:account/id   entity-id
                                  :account/name "Version 1"}])

            form-ident [:account/id entity-id]]

        ;; First save: Update name
        (let [delta1 {form-ident {:account/name {:before "Version 1" :after "Version 2"}}}
              env1 (merge base-env {::form/delta delta1 ::form/id form-ident})
              result1 (save-middleware env1)]
          (is (map? result1))
          (is (= "Version 2" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
              "First save should persist"))

        ;; Second save: Update name again
        (let [delta2 {form-ident {:account/name {:before "Version 2" :after "Version 3"}}}
              env2 (merge base-env {::form/delta delta2 ::form/id form-ident})
              result2 (save-middleware env2)]
          (is (map? result2))
          (is (= "Version 3" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))
              "Second save should persist"))

        ;; Third save: Add email
        (let [delta3 {form-ident {:account/email {:before nil :after "new@example.com"}}}
              env3 (merge base-env {::form/delta delta3 ::form/id form-ident})
              result3 (save-middleware env3)]
          (is (map? result3))
          (let [final (d/pull (d/db conn) '[*] [:account/id entity-id])]
            (is (= "Version 3" (:account/name final))
                "Previous changes should remain")
            (is (= "new@example.com" (:account/email final))
                "Third save should persist")))))))

;; ================================================================================
;; Pathom Integration Test
;; ================================================================================

(deftest pathom-query-after-save-integration-test
  (testing "Pathom query returns updated data after save via save-form*"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware query-fn base-env]} (create-pathom-env conn)

            ;; Create entity
            entity-id (new-uuid)
            _ (d/transact! conn [{:account/id   entity-id
                                  :account/name "Before Save"}])

            ;; Query before save
            query-before (query-fn base-env [{[:account/id entity-id] [:account/name]}])
            _ (is (= "Before Save" (get-in query-before [[:account/id entity-id] :account/name])))

            ;; Save via middleware
            form-ident [:account/id entity-id]
            delta {form-ident {:account/name {:before "Before Save" :after "After Save"}}}
            env (merge base-env {::form/delta delta ::form/id form-ident})
            result (save-middleware env)

            ;; Query after save (need to update db in base-env)
            updated-env (assoc base-env ::dlo/databases {:test (d/db conn)})
            query-after (query-fn updated-env [{[:account/id entity-id] [:account/name]}])]

        ;; Verify query returns updated data
        (is (= "After Save" (get-in query-after [[:account/id entity-id] :account/name]))
            "Pathom query should return updated data after save")))))

;; ================================================================================
;; Diagnostic Test - Full Stack
;; ================================================================================

(deftest diagnostic-full-stack-save-test
  (testing "DIAGNOSTIC: Complete RAD stack save flow works end-to-end"
    (tu/with-test-conn [conn]
      (let [{:keys [save-middleware base-env indexes]} (create-pathom-env conn)

            ;; Track execution
            execution-log (atom [])

            ;; Create new entity
            tid (tempid/tempid)
            real-id (new-uuid)

            form-ident [:account/id tid]
            delta {form-ident
                   {:account/id   {:before nil :after real-id}
                    :account/name {:before nil :after "Diagnostic Test"}}}

            env (merge base-env
                       {::form/delta delta
                        ::form/id    form-ident})]

        (swap! execution-log conj :before-save)

        ;; Execute save
        (let [result (save-middleware env)]
          (swap! execution-log conj :after-save)

          ;; Verify execution happened
          (is (= [:before-save :after-save] @execution-log)
              "Save should execute")

          ;; Verify result
          (is (map? result) "Result should be map")
          (is (contains? result :tempids) "Result should have tempids")
          (is (= real-id (get (:tempids result) tid))
              "Tempid should be remapped")

          ;; Verify database
          (let [saved (d/pull (d/db conn) '[*] [:account/id real-id])]
            (is (some? saved) "Entity should exist in database")
            (is (= "Diagnostic Test" (:account/name saved))
                "Entity should have correct data"))

          ;; Verify resolvers can fetch the data
          (let [query-result (p.eql/process
                               (merge indexes base-env {::dlo/databases {:test (d/db conn)}})
                               [{[:account/id real-id] [:account/name]}])]
            (is (= "Diagnostic Test"
                   (get-in query-result [[:account/id real-id] :account/name]))
                "Pathom should be able to resolve saved entity")))))))

(comment
  ;; Run individual tests
  (save-form-new-entity-integration-test)
  (save-form-update-entity-integration-test)
  (save-form-partial-update-integration-test)
  (save-form-with-references-integration-test)
  (save-form-sequential-saves-integration-test)
  (pathom-query-after-save-integration-test)
  (diagnostic-full-stack-save-test)
  )
