(ns us.whitford.fulcro.rad.database-adapters.datalevin-save-test
  "Comprehensive tests for datalevin save and delete middleware.
   Tests save/delete operations, tempid mapping, error handling, and RAD integration."
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
;; Save Middleware - Basic Operations
;; ================================================================================

(deftest save-middleware-returns-map
  (testing "wrap-datalevin-save returns a map, not a function"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            delta     {[:account/id tid] {:account/id {:before nil :after real-id}
                                          :account/name {:before nil :after "Test User"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            base-handler (fn [_env] {:result :ok})
            middleware   (dl/wrap-datalevin-save {:default-schema :test})
            result       ((middleware base-handler) env)]

        (is (map? result) "Result must be a map")
        (is (not (fn? result)) "Result must NOT be a function")
        (is (= :ok (:result result)) "Base handler result preserved")
        (is (contains? result :tempids) "Should contain tempids mapping")))))

(deftest save-new-entity
  (testing "saves new entity to database"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            delta     {[:account/id tid] {:account/id {:before nil :after real-id}
                                          :account/name {:before nil :after "Jane Doe"}
                                          :account/email {:before nil :after "jane@example.com"}
                                          :account/active? {:before nil :after true}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (= real-id (get (:tempids result) tid)))

        ;; Verify data persistence
        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= real-id (:account/id entity)))
          (is (= "Jane Doe" (:account/name entity)))
          (is (= "jane@example.com" (:account/email entity)))
          (is (true? (:account/active? entity))))))))

(deftest update-existing-entity
  (testing "updates existing entity"
    (tu/with-test-conn [conn]
      (let [real-id   (new-uuid)
            _         (d/transact! conn [{:account/id real-id
                                          :account/name "Original"
                                          :account/email "original@example.com"}])
            delta     {[:account/id real-id] {:account/name {:before "Original" :after "Updated"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (contains? result :tempids))
        (is (empty? (:tempids result)) "No tempids when updating existing entity")

        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= "Updated" (:account/name entity)))
          (is (= "original@example.com" (:account/email entity)) "Unchanged field preserved"))))))

;; ================================================================================
;; Tempid Handling - CRITICAL Tests
;; ================================================================================

(deftest tempids-always-present-in-result
  (testing ":tempids present when saving new entity with tempids"
    (tu/with-test-conn [conn]
      (let [tid       (tempid/tempid)
            real-id   (new-uuid)
            delta     {[:account/id tid] 
                       {:account/id {:before nil :after real-id}
                        :account/name {:before nil :after "New User"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]
        
        (is (contains? result :tempids) ":tempids must be present for RAD")
        (is (map? (:tempids result)))
        (is (= real-id (get (:tempids result) tid))))))

  (testing ":tempids present when updating existing entity (no tempids)"
    (tu/with-test-conn [conn]
      (let [real-id   (new-uuid)
            _         (d/transact! conn [{:account/id real-id
                                          :account/name "Original"}])
            delta     {[:account/id real-id]
                       {:account/name {:before "Original" :after "Updated"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]
        
        (is (contains? result :tempids) ":tempids MUST be present even when updating")
        (is (map? (:tempids result)))
        (is (empty? (:tempids result))))))

  (testing ":tempids present with empty delta"
    (tu/with-test-conn [conn]
      (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta {}}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]
        
        (is (contains? result :tempids))
        (is (map? (:tempids result)))
        (is (empty? (:tempids result)))
        (is (= :ok (:result result))))))

  (testing ":tempids present with nil delta"
    (tu/with-test-conn [conn]
      (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta nil}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]
        
        (is (contains? result :tempids))
        (is (map? (:tempids result)))
        (is (empty? (:tempids result)))))))

(deftest multiple-tempids-mapped-correctly
  (testing "maps multiple tempids to real IDs"
    (tu/with-test-conn [conn]
      (let [tid1      (tempid/tempid)
            tid2      (tempid/tempid)
            real-id1  (new-uuid)
            real-id2  (new-uuid)
            delta     {[:account/id tid1] {:account/id {:before nil :after real-id1}
                                           :account/name {:before nil :after "User 1"}}
                       [:account/id tid2] {:account/id {:before nil :after real-id2}
                                           :account/name {:before nil :after "User 2"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? (:tempids result)))
        (is (= 2 (count (:tempids result))))
        (is (= real-id1 (get (:tempids result) tid1)))
        (is (= real-id2 (get (:tempids result) tid2)))

        ;; Verify both entities exist
        (is (= "User 1" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id1]))))
        (is (= "User 2" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id2]))))))))

(deftest mixed-new-and-existing-entities
  (testing "handles mixed new and existing entities in same save"
    (tu/with-test-conn [conn]
      (let [existing-id (new-uuid)
            _           (d/transact! conn [{:account/id existing-id
                                            :account/name "Existing"}])
            tid         (tempid/tempid)
            new-id      (new-uuid)
            delta       {[:account/id tid]
                         {:account/id {:before nil :after new-id}
                          :account/name {:before nil :after "New"}}
                         [:account/id existing-id]
                         {:account/name {:before "Existing" :after "Updated"}}}
            env         {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                         ::dlo/connections     {:test conn}
                         ::form/params         {::form/delta delta}}
            middleware  (dl/wrap-datalevin-save {:default-schema :test})
            result      ((middleware (fn [_] {})) env)]

        (is (= 1 (count (:tempids result))) "Only new entity has tempid mapping")
        (is (= new-id (get (:tempids result) tid)))
        
        ;; Verify both operations succeeded
        (is (= "New" (:account/name (d/pull (d/db conn) '[*] [:account/id new-id]))))
        (is (= "Updated" (:account/name (d/pull (d/db conn) '[*] [:account/id existing-id]))))))))

;; ================================================================================
;; Data Persistence Tests
;; ================================================================================

(deftest attribute-value-removal
  (testing "setting attribute to nil removes it"
    (tu/with-test-conn [conn]
      (let [real-id   (new-uuid)
            _         (d/transact! conn [{:account/id real-id
                                          :account/name "Has Name"
                                          :account/email "has@email.com"}])
            delta     {[:account/id real-id] {:account/email {:before "has@email.com" :after nil}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _          ((middleware (fn [_] {})) env)]

        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= "Has Name" (:account/name entity)))
          (is (nil? (:account/email entity))))))))

(deftest sequential-updates-persist
  (testing "making multiple sequential edits, each one persists"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id
                                          :account/name "Version 1"}])
            middleware (dl/wrap-datalevin-save {:default-schema :test})]

        ;; Edit 1
        (let [delta1 {[:account/id entity-id]
                      {:account/name {:before "Version 1" :after "Version 2"}}}
              env1   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/params         {::form/delta delta1}}]
          ((middleware (fn [_] {})) env1))
        (is (= "Version 2" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id]))))

        ;; Edit 2
        (let [delta2 {[:account/id entity-id]
                      {:account/name {:before "Version 2" :after "Version 3"}}}
              env2   {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {:test conn}
                      ::form/params         {::form/delta delta2}}]
          ((middleware (fn [_] {})) env2))
        (is (= "Version 3" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id]))))))))

;; ================================================================================
;; Delete Middleware Tests
;; ================================================================================

(deftest delete-middleware-test
  (testing "deletes entity from database"
    (tu/with-test-conn [conn]
      (let [real-id   (new-uuid)
            _         (d/transact! conn [{:account/id real-id
                                          :account/name "ToDelete"}])
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/delete-params  [[:account/id real-id]]}
            middleware (dl/wrap-datalevin-delete {:default-schema :test})
            result     ((middleware (fn [_] {:deleted true})) env)]

        (is (true? (:deleted result)))

        ;; Verify deletion
        (let [exists (d/q '[:find ?e :in $ ?id :where [?e :account/id ?id]]
                          (d/db conn) real-id)]
          (is (empty? exists))))))

  (testing "handles non-existent entity gracefully"
    (tu/with-test-conn [conn]
      (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/delete-params  [[:account/id (new-uuid)]]}
            middleware (dl/wrap-datalevin-delete {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (= :ok (:result result)))))))

;; ================================================================================
;; Error Handling Tests
;; ================================================================================

(deftest error-missing-connection
  (testing "save throws when connection missing"
    (let [delta     {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
          env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {}
                     ::form/params         {::form/delta delta}}
          middleware (dl/wrap-datalevin-save {:default-schema :test})]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No database connection configured"
                            ((middleware (fn [_] {})) env)))))

  (testing "delete throws when connection missing"
    (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {}
                     ::form/delete-params  [[:account/id (new-uuid)]]}
          middleware (dl/wrap-datalevin-delete {:default-schema :test})]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No database connection configured"
                            ((middleware (fn [_] {})) env))))))

(deftest error-includes-context
  (testing "error includes schema and available schemas"
    (tu/with-test-conn-attrs [conn [tu/account-id tu/account-name]]
      (let [wrong-id   (assoc tu/account-id ::attr/schema :other)
            wrong-name (assoc tu/account-name ::attr/schema :other)
            delta      {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
            env        {::attr/key->attribute {:account/id wrong-id
                                               :account/name wrong-name}
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})]

        (try
          ((middleware (fn [_] {})) env)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :other (:schema data)))
              (is (= [:test] (:available-schemas data))))))))))

;; ================================================================================
;; RAD Integration Tests
;; ================================================================================

(deftest rad-middleware-composition
  (testing "middleware composes properly with RAD save stack"
    (tu/with-test-conn [conn]
      (let [tid    (tempid/tempid)
            real-id (new-uuid)
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "RAD User"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            ;; Simulate RAD middleware stack
            base-handler (fn [_] {::form/complete? true})
            wrap-validation (fn [handler]
                              (fn [env]
                                (assoc (handler env) ::form/validated? true)))
            handler (-> base-handler
                        ((dl/wrap-datalevin-save {:default-schema :test}))
                        (wrap-validation))
            result  (handler env)]

        (is (map? result))
        (is (not (fn? result)))
        (is (::form/complete? result))
        (is (::form/validated? result))
        (is (contains? result :tempids))
        (is (= real-id (get (:tempids result) tid)))
        (is (= "RAD User" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id]))))))))

(deftest result-is-serializable
  (testing "result is serializable (no functions)"
    (tu/with-test-conn [conn]
      (let [tid    (tempid/tempid)
            real-id (new-uuid)
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "Serial Test"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/delta          delta}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:original :data})) env)]

        (is (map? result))
        (is (every? #(not (fn? %)) (vals result)) "No value should be a function")
        
        ;; Should be pr-str-able
        (let [serialized (pr-str result)]
          (is (string? serialized))
          (is (not (re-find #"fn__\d+" serialized))))))))

;; ================================================================================
;; Schema Configuration Tests
;; ================================================================================

(deftest default-schema-configuration
  (testing "uses default-schema when attribute doesn't specify one"
    (tu/with-test-conn [conn]
      (let [;; Attribute without schema
            no-schema-attr {::attr/qualified-key :other/field
                            ::attr/type          :string}
            id      (new-uuid)
            delta   {[:account/id id] {:account/id {:before nil :after id}
                                       :account/name {:before nil :after "Default Schema"}}}
            env     {::attr/key->attribute (assoc (tu/key->attribute-map tu/all-test-attributes)
                                                   :other/field no-schema-attr)
                     ::dlo/connections     {:test conn}
                     ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (= "Default Schema" (:account/name (d/pull (d/db conn) '[*] [:account/id id]))))))))

;; ================================================================================
;; Incorrect Usage Tests
;; ================================================================================

(deftest incorrect-middleware-usage
  (testing "calling middleware with env instead of handler"
    (tu/with-test-conn [conn]
      (let [delta  {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware-wrapper (dl/wrap-datalevin-save {:default-schema :test})
            wrong-result       (middleware-wrapper env)]

        ;; This is the error - result is a function, not a map
        (is (fn? wrong-result) "WRONG USAGE: Returns function instead of map")
        (is (thrown? IllegalArgumentException (merge {} wrong-result)))))))
