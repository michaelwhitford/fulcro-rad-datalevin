(ns us.whitford.fulcro.rad.database-adapters.datalevin-new-entity-test
  "Tests specifically for new entity creation scenarios, including edge cases
   where the identity attribute value is not a tempid but the entity is new."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; New Entity Creation with UUID Keys (Not Tempids)
;; ================================================================================

(deftest create-new-entity-with-uuid-key
  (testing "creates new entity when ident has UUID (not tempid) and :before is nil"
    (tu/with-test-conn [conn]
      (let [new-id    (new-uuid)
            ;; This is the problematic case - the ident has a UUID, not a tempid
            ;; but :before nil indicates this is a NEW entity
            delta     {[:account/id new-id]
                       {:account/id {:before nil :after new-id}
                        :account/name {:before nil :after "Michael"}
                        :account/email {:before nil :after "michael@whitford.us"}
                        :account/active? {:before nil :after true}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result) "Result must be a map")
        (is (contains? result :tempids) "Must have :tempids key")

        ;; Verify entity was created
        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity)) "Entity ID should match")
          (is (= "Michael" (:account/name entity)) "Name should be saved")
          (is (= "michael@whitford.us" (:account/email entity)) "Email should be saved")
          (is (true? (:account/active? entity)) "Active flag should be saved"))))))

(deftest create-new-entity-detects-nil-before
  (testing "detects new entity by checking :before nil on identity attribute"
    (tu/with-test-conn [conn]
      (let [new-id (new-uuid)
            delta  {[:account/id new-id]
                    {:account/id {:before nil :after new-id}
                     :account/name {:before nil :after "New Entity"}}}]
        
        ;; Generate transaction data
        (let [txn-data (dl/delta->txn delta)]
          (is (vector? txn-data) "Should return transaction vector")
          (is (pos? (count txn-data)) "Should have transaction data")
          
          ;; Check that none of the transaction data contains nil values
          (doseq [txn txn-data]
            (when (map? txn)
              (is (not-any? nil? (vals txn))
                  (str "Transaction should not contain nil values: " txn))))
          
          ;; Execute the transaction
          (let [tx-result (d/transact! conn txn-data)]
            (is tx-result "Transaction should succeed")
            
            ;; Verify entity exists
            (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
              (is (= new-id (:account/id entity)))
              (is (= "New Entity" (:account/name entity))))))))))

(deftest delta-txn-no-nil-values
  (testing "delta->txn never generates transaction data with nil attribute values"
    (let [new-id (new-uuid)
          delta  {[:account/id new-id]
                  {:account/id {:before nil :after new-id}
                   :account/name {:before nil :after "Test"}
                   :account/email {:before nil :after "test@example.com"}}}
          txn-data (dl/delta->txn delta)]
      
      ;; Check every transaction map for nil values
      (doseq [txn txn-data]
        (when (map? txn)
          (is (not-any? (fn [[k v]] (and (not= k :db/id) (nil? v)))
                        txn)
              (str "Transaction map should not have nil values (except :db/id): " txn)))))))

;; ================================================================================
;; Ident Key Format Tests
;; ================================================================================

(deftest ident-with-nested-id-map
  (testing "handles ident with nested {:id uuid} map format - THIS IS THE BUG"
    (tu/with-test-conn [conn]
      (let [new-id (new-uuid)
            ;; This is the ACTUAL format from the error log
            ;; {[:account/id {:id #uuid "318c42b3-6823-4da1-8841-cc7a96de4814"}]
            ;;  {:account/name {:before nil, :after "michael"}, ...}}
            delta  {[:account/id {:id new-id}]
                    {:account/name {:before nil :after "Michael"}
                     :account/email {:before nil :after "michael@whitford.us"}
                     :account/active? {:before nil :after true}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result) "Result must be a map")
        (is (contains? result :tempids) "Must have :tempids key")

        ;; Verify entity was created
        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity)) "Entity ID should match")
          (is (= "Michael" (:account/name entity)) "Name should be saved")
          (is (= "michael@whitford.us" (:account/email entity)) "Email should be saved")
          (is (true? (:account/active? entity)) "Active flag should be saved"))))))

;; ================================================================================
;; Multiple New Entities
;; ================================================================================

(deftest create-multiple-new-entities-with-uuids
  (testing "creates multiple new entities with UUID keys in single transaction"
    (tu/with-test-conn [conn]
      (let [id1    (new-uuid)
            id2    (new-uuid)
            delta  {[:account/id id1]
                    {:account/id {:before nil :after id1}
                     :account/name {:before nil :after "User One"}}
                    [:account/id id2]
                    {:account/id {:before nil :after id2}
                     :account/name {:before nil :after "User Two"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (contains? result :tempids))

        ;; Verify both entities exist
        (let [entity1 (d/pull (d/db conn) '[*] [:account/id id1])
              entity2 (d/pull (d/db conn) '[*] [:account/id id2])]
          (is (= id1 (:account/id entity1)))
          (is (= "User One" (:account/name entity1)))
          (is (= id2 (:account/id entity2)))
          (is (= "User Two" (:account/name entity2))))))))

;; ================================================================================
;; Mixed Scenarios
;; ================================================================================

(deftest mixed-tempid-and-uuid-new-entities
  (testing "handles mix of tempid and UUID keys for new entities"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            tid-real  (new-uuid)
            uuid-id   (new-uuid)
            delta     {[:account/id tid]
                       {:account/id {:before nil :after tid-real}
                        :account/name {:before nil :after "Tempid User"}}
                       [:account/id uuid-id]
                       {:account/id {:before nil :after uuid-id}
                        :account/name {:before nil :after "UUID User"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (contains? result :tempids))
        (is (= tid-real (get (:tempids result) tid)) "Tempid should be mapped")

        ;; Verify both entities exist
        (let [entity1 (d/pull (d/db conn) '[*] [:account/id tid-real])
              entity2 (d/pull (d/db conn) '[*] [:account/id uuid-id])]
          (is (= tid-real (:account/id entity1)))
          (is (= "Tempid User" (:account/name entity1)))
          (is (= uuid-id (:account/id entity2)))
          (is (= "UUID User" (:account/name entity2))))))))

;; ================================================================================
;; Delta Format Edge Cases
;; ================================================================================

(deftest new-entity-minimal-attributes
  (testing "creates new entity with only identity attribute"
    (tu/with-test-conn [conn]
      (let [new-id (new-uuid)
            delta  {[:account/id new-id]
                    {:account/id {:before nil :after new-id}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (contains? result :tempids))

        ;; Verify entity exists with just the ID
        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity))))))))

(deftest new-entity-all-attributes
  (testing "creates new entity with all available attributes"
    (tu/with-test-conn [conn]
      (let [new-id (new-uuid)
            delta  {[:account/id new-id]
                    {:account/id {:before nil :after new-id}
                     :account/name {:before nil :after "Complete User"}
                     :account/email {:before nil :after "complete@example.com"}
                     :account/active? {:before nil :after true}
                     :account/balance {:before nil :after 100.50}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))

        ;; Verify all attributes saved
        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity)))
          (is (= "Complete User" (:account/name entity)))
          (is (= "complete@example.com" (:account/email entity)))
          (is (true? (:account/active? entity)))
          (is (= 100.50 (:account/balance entity))))))))

;; ================================================================================
;; Tempid Value Extraction
;; ================================================================================

(deftest tempid-value-extraction
  (testing "extracts UUID from tempid value in delta"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            ;; The delta contains the tempid object itself in the :after
            ;; This happens when RAD creates a new entity
            delta     {[:account/id tid]
                       {:account/id {:before nil :after tid}
                        :account/name {:before nil :after "Tempid Value Test"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result) "Result must be a map")
        (is (contains? result :tempids) "Must have :tempids key")
        
        ;; The tempid should be mapped to its UUID
        (let [real-uuid (get (:tempids result) tid)]
          (is (uuid? real-uuid) "Should be a UUID")
          (is (= (:id tid) real-uuid) "Should be the tempid's UUID")
          
          ;; Verify entity was created with the UUID, not the tempid object
          (let [entity (d/pull (d/db conn) '[*] [:account/id real-uuid])]
            (is (= real-uuid (:account/id entity)) "Entity should have UUID as ID")
            (is (= "Tempid Value Test" (:account/name entity)) "Name should be saved")))))))

;; ================================================================================
;; Error Cases
;; ================================================================================

(deftest reject-nil-identity-value
  (testing "properly handles/rejects nil identity value in transaction"
    (tu/with-test-conn [conn]
      (let [;; Malformed delta with nil ID value in :after
            delta  {[:account/id nil]
                    {:account/id {:before nil :after nil}
                     :account/name {:before nil :after "No ID User"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})]
        
        ;; This should either throw an error or handle gracefully
        ;; Currently it fails because datalevin doesn't accept nil values
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(Cannot store nil|transaction failed)"
                              ((middleware (fn [_] {})) env)))))))
