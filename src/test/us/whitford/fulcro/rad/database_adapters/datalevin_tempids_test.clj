(ns us.whitford.fulcro.rad.database-adapters.datalevin-tempids-test
  "Tests specifically for :tempids handling in save operations.
   This addresses the issue where RAD queries for :tempids but it's not present
   when saving existing entities (without tempids)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
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

(def test-attributes
  [account-id account-name account-email])

;; ================================================================================
;; Helper Functions
;; ================================================================================

(defn create-test-db
  "Create a test database connection."
  []
  (let [path (str "/tmp/datalevin-tempids-test-" (new-uuid))
        schema (dl/automatic-schema :test test-attributes)]
    {:conn (d/get-conn path schema)
     :path path}))

(defn cleanup-test-db
  "Close and cleanup a test database."
  [{:keys [conn path]}]
  (when conn
    (d/close conn))
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

;; ================================================================================
;; Critical Tests: :tempids must ALWAYS be present
;; ================================================================================

(deftest tempids-present-when-saving-new-entity-with-tempids
  (testing ":tempids is present when saving new entity with tempids"
    (let [{:keys [conn] :as test-db} (create-test-db)
          tid       (tempid/tempid)
          real-id   (new-uuid)
          delta     {[:account/id tid] 
                     {:account/id {:before nil :after real-id}
                      :account/name {:before nil :after "New User"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      ;; CRITICAL: :tempids must be present
      (is (map? result))
      (is (contains? result :tempids)
          ":tempids must be present in result for RAD EQL queries")
      (is (map? (:tempids result))
          ":tempids must be a map")
      (is (= real-id (get (:tempids result) tid))
          "Tempid should map to real ID")
      
      (cleanup-test-db test-db))))

(deftest tempids-present-when-updating-existing-entity
  (testing ":tempids is present even when updating existing entity (no tempids)"
    (let [{:keys [conn] :as test-db} (create-test-db)
          real-id   (new-uuid)
          ;; Pre-populate the database with an existing entity
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Original Name"
                                        :account/email "original@example.com"}])
          ;; Delta for updating the existing entity (no tempids!)
          delta     {[:account/id real-id]
                     {:account/name {:before "Original Name" :after "Updated Name"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      ;; CRITICAL: :tempids must be present even when there are no tempids!
      (is (map? result))
      (is (contains? result :tempids)
          ":tempids MUST be present even when updating existing entities")
      (is (map? (:tempids result))
          ":tempids must be a map")
      (is (empty? (:tempids result))
          ":tempids should be empty when no tempids were used")
      
      ;; Verify the update actually happened
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= "Updated Name" (:account/name entity))))
      
      (cleanup-test-db test-db))))

(deftest tempids-present-with-empty-delta
  (testing ":tempids is present even with empty delta"
    (let [{:keys [conn] :as test-db} (create-test-db)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          {}}  ;; Empty delta
          base-handler (fn [_env] {:some-key :some-value})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids)
          ":tempids must be present even with empty delta")
      (is (map? (:tempids result)))
      (is (empty? (:tempids result)))
      (is (= :some-value (:some-key result))
          "Should preserve base handler result")
      
      (cleanup-test-db test-db))))

(deftest tempids-present-with-nil-delta
  (testing ":tempids is present even with nil delta"
    (let [{:keys [conn] :as test-db} (create-test-db)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          nil}  ;; Nil delta
          base-handler (fn [_env] {:result :ok})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids)
          ":tempids must be present even with nil delta")
      (is (map? (:tempids result)))
      (is (empty? (:tempids result)))
      (is (= :ok (:result result)))
      
      (cleanup-test-db test-db))))

(deftest tempids-merged-correctly-with-multiple-saves
  (testing ":tempids correctly merges multiple tempid mappings"
    (let [{:keys [conn] :as test-db} (create-test-db)
          tid1      (tempid/tempid)
          tid2      (tempid/tempid)
          real-id1  (new-uuid)
          real-id2  (new-uuid)
          delta     {[:account/id tid1] 
                     {:account/id {:before nil :after real-id1}
                      :account/name {:before nil :after "User 1"}}
                     [:account/id tid2]
                     {:account/id {:before nil :after real-id2}
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
      (is (contains? result :tempids))
      (is (= 2 (count (:tempids result)))
          "Should have 2 tempid mappings")
      (is (= real-id1 (get (:tempids result) tid1)))
      (is (= real-id2 (get (:tempids result) tid2)))
      
      (cleanup-test-db test-db))))

(deftest tempids-with-mixed-new-and-existing-entities
  (testing ":tempids contains only new entity mappings when mixed with updates"
    (let [{:keys [conn] :as test-db} (create-test-db)
          existing-id (new-uuid)
          ;; Create existing entity
          _         (d/transact! conn [{:account/id existing-id
                                        :account/name "Existing"}])
          
          tid       (tempid/tempid)
          new-id    (new-uuid)
          ;; Delta with both new and existing entity
          delta     {[:account/id tid]
                     {:account/id {:before nil :after new-id}
                      :account/name {:before nil :after "New"}}
                     [:account/id existing-id]
                     {:account/name {:before "Existing" :after "Updated"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids))
      (is (= 1 (count (:tempids result)))
          "Should only have tempid mapping for new entity")
      (is (= new-id (get (:tempids result) tid))
          "Should map tempid to new entity ID")
      (is (nil? (get (:tempids result) existing-id))
          "Existing entity ID should not be in tempids")
      
      (cleanup-test-db test-db))))

(deftest tempids-is-always-a-map-not-nil
  (testing ":tempids is always a map, never nil"
    (let [{:keys [conn] :as test-db} (create-test-db)
          real-id   (new-uuid)
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Test"}])
          delta     {[:account/id real-id]
                     {:account/email {:before nil :after "test@example.com"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          result       (wrapped-handler env)]
      
      (is (map? result))
      (is (contains? result :tempids))
      (is (map? (:tempids result))
          ":tempids must be a map, not nil")
      (is (not (nil? (:tempids result)))
          ":tempids should never be nil")
      
      (cleanup-test-db test-db))))

;; ================================================================================
;; Integration Test: Simulating RAD EQL Query for :tempids
;; ================================================================================

(deftest simulates-rad-eql-query-for-tempids
  (testing "Simulates RAD querying for :tempids after a save mutation"
    (let [{:keys [conn] :as test-db} (create-test-db)
          real-id   (new-uuid)
          _         (d/transact! conn [{:account/id real-id
                                        :account/name "Existing User"}])
          
          ;; This is what happens when user saves an existing account
          delta     {[:account/id real-id]
                     {:account/name {:before "Existing User" :after "Updated User"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          base-handler (fn [_env] {::form/complete? true})
          middleware   (dl/wrap-datalevin-save {:default-schema :test})
          wrapped-handler (middleware base-handler)
          
          ;; Execute the save
          result       (wrapped-handler env)]
      
      ;; RAD will query the result for [:tempids] 
      ;; This must not fail with "attribute-unreachable"
      (is (map? result))
      (is (contains? result :tempids)
          "Result must contain :tempids key for RAD to query")
      
      ;; Simulate what RAD does: access :tempids from result
      (let [tempids (:tempids result)]
        (is (map? tempids)
            ":tempids must be resolvable as a map")
        (is (empty? tempids)
            "When updating existing entity, :tempids should be empty but present"))
      
      ;; Verify the save actually worked
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= "Updated User" (:account/name entity))))
      
      (cleanup-test-db test-db))))
