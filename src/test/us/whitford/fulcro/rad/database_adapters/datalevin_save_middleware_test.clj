(ns us.whitford.fulcro.rad.database-adapters.datalevin-save-middleware-test
  "Comprehensive tests for wrap-datalevin-save middleware to diagnose and verify the fix
   for the ClassCastException error where a function was being returned instead of a map."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; Test Attributes
;; ================================================================================

(def account-id
  {::attr/qualified-key :account/id
   ::attr/type          :uuid
   ::attr/schema        :test
   ::attr/identity?     true})

(def account-name
  {::attr/qualified-key :account/name
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def account-email
  {::attr/qualified-key :account/email
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def account-active
  {::attr/qualified-key :account/active?
   ::attr/type          :boolean
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def test-attributes
  [account-id account-name account-email account-active])

;; ================================================================================
;; Helper Functions
;; ================================================================================

(defn create-test-conn
  "Create a test connection with schema."
  []
  (let [path (str "/tmp/datalevin-test-save-" (new-uuid))
        schema (dl/automatic-schema :test test-attributes)]
    {:conn (d/get-conn path schema)
     :path path}))

(defn cleanup-test-conn
  "Close and cleanup a test connection."
  [{:keys [conn path]}]
  (when conn
    (d/close conn))
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

;; ================================================================================
;; Critical Tests: Middleware Return Value Type
;; ================================================================================

(deftest middleware-returns-map-not-function
  (testing "CRITICAL: wrap-datalevin-save must return a map, not a function"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid       (tempid/tempid)
          real-id   (new-uuid)
          delta     {[:account/id tid] {:account/id {:before nil :after real-id}
                                        :account/name {:before nil :after "Test User"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          ;; This is what RAD does - it expects a map back
          base-handler (fn [_env] {:com.fulcrologic.rad.form/id [:account/id real-id]})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      ;; The critical assertion - result must be a map, not a function
      (is (map? result) 
          (str "Result must be a map but got: " (type result)))
      (is (not (fn? result))
          "Result must NOT be a function")
      
      ;; It should contain the base handler's result
      (is (contains? result :com.fulcrologic.rad.form/id)
          "Should contain base handler result")
      
      ;; It should contain tempids if there were any
      (is (or (contains? result :tempids)
              (not (seq delta)))
          "Should contain tempids mapping when tempids were used")
      
      (cleanup-test-conn test-db))))

(deftest middleware-preserves-base-handler-result
  (testing "wrap-datalevin-save must merge with base handler's result"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid       (tempid/tempid)
          id        (new-uuid)
          delta     {[:account/id tid] {:account/id {:before nil :after id}
                                        :account/name {:before nil :after "Test"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] 
                         {:some-key "some-value"
                          :another-key 42})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (= "some-value" (:some-key result))
          "Should preserve base handler's keys")
      (is (= 42 (:another-key result))
          "Should preserve all base handler's result")
      
      (cleanup-test-conn test-db))))

(deftest middleware-returns-map-with-empty-delta
  (testing "wrap-datalevin-save returns map even with empty delta"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          {}}  ;; Empty delta
          base-handler (fn [_env] {:result :ok})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result) "Result must be a map even with empty delta")
      (is (not (fn? result)))
      (is (= :ok (:result result)))
      
      (cleanup-test-conn test-db))))

(deftest middleware-returns-map-with-nil-delta
  (testing "wrap-datalevin-save returns map even with nil delta"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          nil}  ;; Nil delta
          base-handler (fn [_env] {:result :ok})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result) "Result must be a map even with nil delta")
      (is (not (fn? result)))
      (is (= :ok (:result result)))
      
      (cleanup-test-conn test-db))))

;; ================================================================================
;; Tempid Mapping Tests
;; ================================================================================

(deftest tempid-mapping-returned-correctly
  (testing "tempids are correctly mapped and returned"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid1      (tempid/tempid)
          tid2      (tempid/tempid)
          real-id1  (new-uuid)
          real-id2  (new-uuid)
          delta     {[:account/id tid1] {:account/id {:before nil :after real-id1}
                                         :account/name {:before nil :after "User 1"}}
                     [:account/id tid2] {:account/id {:before nil :after real-id2}
                                         :account/name {:before nil :after "User 2"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (map? (:tempids result)) "Should contain tempids map")
      (is (= real-id1 (get (:tempids result) tid1))
          "Should map first tempid to real id")
      (is (= real-id2 (get (:tempids result) tid2))
          "Should map second tempid to real id")
      
      ;; Verify data was actually saved
      (let [db (d/db conn)
            user1 (d/pull db [:account/name] [:account/id real-id1])
            user2 (d/pull db [:account/name] [:account/id real-id2])]
        (is (= "User 1" (:account/name user1)))
        (is (= "User 2" (:account/name user2))))
      
      (cleanup-test-conn test-db))))

(deftest multiple-tempids-get-unique-ids
  (testing "multiple tempids in same transaction get unique IDs"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid1      (tempid/tempid)
          tid2      (tempid/tempid)
          tid3      (tempid/tempid)
          real-id1  (new-uuid)
          real-id2  (new-uuid)
          real-id3  (new-uuid)
          delta     {[:account/id tid1] {:account/id {:before nil :after real-id1}
                                         :account/name {:before nil :after "A"}}
                     [:account/id tid2] {:account/id {:before nil :after real-id2}
                                         :account/name {:before nil :after "B"}}
                     [:account/id tid3] {:account/id {:before nil :after real-id3}
                                         :account/name {:before nil :after "C"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (= 3 (count (:tempids result))) "Should have 3 tempid mappings")
      (is (= real-id1 (get (:tempids result) tid1)))
      (is (= real-id2 (get (:tempids result) tid2)))
      (is (= real-id3 (get (:tempids result) tid3)))
      
      (cleanup-test-conn test-db))))

;; ================================================================================
;; Data Persistence Tests
;; ================================================================================

(deftest new-entity-is-saved
  (testing "new entity is persisted to database"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid       (tempid/tempid)
          real-id   (new-uuid)
          delta     {[:account/id tid] {:account/id {:before nil :after real-id}
                                        :account/name {:before nil :after "Jane Doe"}
                                        :account/email {:before nil :after "jane@example.com"}
                                        :account/active? {:before nil :after true}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      
      ;; Verify the data is in the database
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= real-id (:account/id entity)))
        (is (= "Jane Doe" (:account/name entity)))
        (is (= "jane@example.com" (:account/email entity)))
        (is (true? (:account/active? entity))))
      
      (cleanup-test-conn test-db))))

(deftest existing-entity-is-updated
  (testing "existing entity is updated correctly"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          real-id   (new-uuid)
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Original Name"
                                        :account/email "original@example.com"
                                        :account/active? false}])
          delta     {[:account/id real-id] {:account/name {:before "Original Name" :after "Updated Name"}
                                            :account/active? {:before false :after true}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      
      ;; Verify the updates
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= "Updated Name" (:account/name entity)))
        (is (= "original@example.com" (:account/email entity)) "Unchanged field should remain")
        (is (true? (:account/active? entity))))
      
      (cleanup-test-conn test-db))))

(deftest nil-value-removes-attribute
  (testing "setting attribute to nil removes it"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          real-id   (new-uuid)
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Has Name"
                                        :account/email "has@email.com"}])
          delta     {[:account/id real-id] {:account/email {:before "has@email.com" :after nil}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      
      ;; Verify the email was removed
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= "Has Name" (:account/name entity)) "Name should still exist")
        (is (nil? (:account/email entity)) "Email should be removed"))
      
      (cleanup-test-conn test-db))))

;; ================================================================================
;; Multiple Schema Tests
;; ================================================================================

(def item-id
  {::attr/qualified-key :item/id
   ::attr/type          :uuid
   ::attr/schema        :other  ;; Different schema!
   ::attr/identity?     true})

(def item-name
  {::attr/qualified-key :item/name
   ::attr/type          :string
   ::attr/schema        :other
   ::attr/identities    #{:item/id}})

(deftest multiple-schemas-handled-correctly
  (testing "saves to multiple schemas in one transaction"
    (let [test-db1 (create-test-conn)
          conn1    (:conn test-db1)
          ;; Create second schema
          path2    (str "/tmp/datalevin-test-save-other-" (new-uuid))
          schema2  (dl/automatic-schema :other [item-id item-name])
          conn2    (d/get-conn path2 schema2)
          test-db2 {:conn conn2 :path path2}
          
          acc-tid  (tempid/tempid)
          acc-id   (new-uuid)
          item-tid (tempid/tempid)
          item-id-val (new-uuid)
          delta    {[:account/id acc-tid] {:account/id {:before nil :after acc-id}
                                           :account/name {:before nil :after "Test Account"}}
                    [:item/id item-tid] {:item/id {:before nil :after item-id-val}
                                         :item/name {:before nil :after "Test Item"}}}
          all-attrs (concat test-attributes [item-id item-name])
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) all-attrs)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn1
                                            :other conn2}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      
      ;; Verify data in first schema
      (let [db1    (d/db conn1)
            acc    (d/pull db1 '[*] [:account/id acc-id])]
        (is (= "Test Account" (:account/name acc))))
      
      ;; Verify data in second schema
      (let [db2    (d/db conn2)
            item   (d/pull db2 '[*] [:item/id item-id-val])]
        (is (= "Test Item" (:item/name item))))
      
      (cleanup-test-conn test-db1)
      (cleanup-test-conn test-db2))))

;; ================================================================================
;; Error Cases
;; ================================================================================

(deftest error-when-connection-missing
  (testing "throws meaningful error when connection is missing"
    (let [delta     {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {}  ;; Empty!
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)]
      
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No database connection configured"
                            (wrapped-handler env))))))

(deftest error-contains-useful-context
  (testing "error includes schema and available schemas"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          id        (new-uuid)
          delta     {[:account/id id] {:account/name {:before nil :after "Test"}}}
          ;; Both attributes say :other schema, but we only have :test connection
          wrong-id-attr (assoc account-id ::attr/schema :other)
          wrong-name-attr (assoc account-name ::attr/schema :other)
          key->attr  {:account/name wrong-name-attr
                      :account/id wrong-id-attr}
          env        {::attr/key->attribute key->attr
                      ::dlo/connections     {:test conn}
                      ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)]
      
      (try
        (wrapped-handler env)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :other (:schema data)))
            (is (= [:test] (:available-schemas data))))))
      
      (cleanup-test-conn test-db))))

;; ================================================================================
;; Integration Test: Simulating RAD Form Save Flow
;; ================================================================================

(deftest simulates-full-rad-form-save-flow
  (testing "simulates complete RAD form save including pathom context"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid       (tempid/tempid)
          real-id   (new-uuid)
          ;; This mimics what RAD form does
          form-params {:account/id tid
                       :account/name "Form User"
                       :account/email "form@user.com"
                       :account/active? true}
          ;; Delta that RAD generates
          delta     {[:account/id tid] 
                     {:account/id {:before nil :after real-id}
                      :account/name {:before nil :after "Form User"}
                      :account/email {:before nil :after "form@user.com"}
                      :account/active? {:before nil :after true}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          ;; Full env as RAD would pass it
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta
                     ::form/params         form-params
                     ::form/master-pk      :account/id}
          ;; Base handler that returns what RAD expects
          base-handler (fn [env]
                         {::form/id [:account/id (get-in env [::form/params :account/id])]
                          ::form/complete? true})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      ;; Verify result structure
      (is (map? result) "Result must be a map")
      (is (not (fn? result)) "Result must not be a function")
      (is (::form/complete? result) "Should preserve base handler's result")
      (is (map? (:tempids result)) "Should have tempids")
      (is (= real-id (get (:tempids result) tid)) "Should map tempid correctly")
      
      ;; Verify database state
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= real-id (:account/id entity)))
        (is (= "Form User" (:account/name entity)))
        (is (= "form@user.com" (:account/email entity)))
        (is (true? (:account/active? entity))))
      
      (cleanup-test-conn test-db))))

;; ================================================================================
;; Edge Cases
;; ================================================================================

(deftest handles-unchanged-values
  (testing "unchanged values don't create unnecessary transaction data"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          real-id   (new-uuid)
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Same Name"}])
          ;; Delta with unchanged value
          delta     {[:account/id real-id] 
                     {:account/name {:before "Same Name" :after "Same Name"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      ;; The entity should still be there but unchanged
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= "Same Name" (:account/name entity))))
      
      (cleanup-test-conn test-db))))

(deftest handles-only-id-in-delta
  (testing "handles delta with only identity attribute"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid       (tempid/tempid)
          real-id   (new-uuid)
          ;; Only ID, no other attributes
          delta     {[:account/id tid] 
                     {:account/id {:before nil :after real-id}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (map? (:tempids result)))
      
      ;; Should still create the entity
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= real-id (:account/id entity))))
      
      (cleanup-test-conn test-db))))

(deftest concurrent-saves-with-tempids
  (testing "concurrent saves with different tempids don't conflict"
    (let [{:keys [conn] :as test-db} (create-test-conn)
          tid1      (tempid/tempid)
          tid2      (tempid/tempid)
          real-id1  (new-uuid)
          real-id2  (new-uuid)
          
          delta1    {[:account/id tid1] {:account/id {:before nil :after real-id1}
                                         :account/name {:before nil :after "User 1"}}}
          delta2    {[:account/id tid2] {:account/id {:before nil :after real-id2}
                                         :account/name {:before nil :after "User 2"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env1      {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta1}
          env2      {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta2}
          
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          
          ;; Simulate concurrent calls
          result1   (wrapped-handler env1)
          result2   (wrapped-handler env2)]
      
      (is (map? result1))
      (is (map? result2))
      
      ;; Both should have their tempids mapped
      (is (= real-id1 (get (:tempids result1) tid1)))
      (is (= real-id2 (get (:tempids result2) tid2)))
      
      ;; Both entities should exist in database
      (let [db     (d/db conn)
            user1  (d/pull db '[*] [:account/id real-id1])
            user2  (d/pull db '[*] [:account/id real-id2])]
        (is (= "User 1" (:account/name user1)))
        (is (= "User 2" (:account/name user2))))
      
      (cleanup-test-conn test-db))))
