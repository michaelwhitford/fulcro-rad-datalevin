(ns us.whitford.fulcro.rad.database-adapters.datalevin-middleware-test
  "Comprehensive tests for datalevin middleware (save and delete).
   Tests cover correct usage, incorrect usage, error handling, and RAD integration."
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
;; Save Middleware - Core Behavior
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

(deftest save-middleware-persists-data
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

        ;; Verify data persistence
        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= real-id (:account/id entity)))
          (is (= "Jane Doe" (:account/name entity)))
          (is (= "jane@example.com" (:account/email entity)))
          (is (true? (:account/active? entity)))))))

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
            _          ((middleware (fn [_] {})) env)]

        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= "Updated" (:account/name entity)))
          (is (= "original@example.com" (:account/email entity)) "Unchanged field preserved"))))))

(deftest save-middleware-handles-tempids
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
        (is (= real-id1 (get (:tempids result) tid1)))
        (is (= real-id2 (get (:tempids result) tid2)))

        ;; Verify both entities exist
        (is (= "User 1" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id1]))))
        (is (= "User 2" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id2]))))))))

(deftest save-middleware-edge-cases
  (testing "handles empty delta"
    (tu/with-test-conn [conn]
      (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta {}}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (map? result))
        (is (= :ok (:result result))))))

  (testing "handles nil delta"
    (tu/with-test-conn [conn]
      (let [env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta nil}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (map? result))
        (is (= :ok (:result result))))))

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

;; ================================================================================
;; Delete Middleware
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
;; RAD Integration Tests
;; ================================================================================

(deftest rad-middleware-composition-test
  (testing "middleware composes properly with RAD save stack"
    (tu/with-test-conn [conn]
      (let [tid    (tempid/tempid)
            real-id (new-uuid)
            params {:account/id tid
                    :account/name "RAD User"}
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "RAD User"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         (assoc params ::form/delta delta)
                    ::form/master-pk      :account/id}
            ;; Simulate RAD middleware stack
            base-handler (fn [env] {::form/id [:account/id (get-in env [::form/params :account/id])]
                                    ::form/complete? true})
            wrap-validation (fn [handler]
                              (fn [env]
                                (assoc (handler env) ::form/validated? true)))
            ;; Compose middleware like RAD does
            handler (-> base-handler
                        ((dl/wrap-datalevin-save {:default-schema :test}))
                        (wrap-validation))
            result  (handler env)]

        (is (map? result))
        (is (not (fn? result)))
        (is (::form/complete? result) "Base handler result preserved")
        (is (::form/validated? result) "Validation middleware ran")
        (is (contains? result :tempids) "Datalevin middleware ran")
        (is (= real-id (get (:tempids result) tid)))

        ;; Verify data saved
        (is (= "RAD User" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id]))))))))

(deftest rad-result-serialization-test
  (testing "result is serializable (no functions)"
    (tu/with-test-conn [conn]
      (let [tid    (tempid/tempid)
            real-id (new-uuid)
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "Serial Test"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:original :data})) env)]

        (is (map? result))

        ;; Should be pr-str-able without function references
        (let [serialized (pr-str result)]
          (is (string? serialized))
          (is (not (re-find #"fn__\d+" serialized))))

        ;; No value should be a function
        (is (every? #(not (fn? %)) (vals result)))))))

;; ================================================================================
;; Incorrect Usage Tests (Error Reproduction)
;; ================================================================================

(deftest incorrect-usage-missing-handler-step
  (testing "INCORRECT: calling middleware with env instead of handler"
    (tu/with-test-conn [conn]
      (let [tid    (tempid/tempid)
            real-id (new-uuid)
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "Test"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/delta          delta}
            ;; WRONG: Pass env directly to middleware wrapper
            middleware-wrapper (dl/wrap-datalevin-save {:default-schema :test})
            wrong-result       (middleware-wrapper env)]

        ;; This is the error - result is a function, not a map
        (is (fn? wrong-result) "WRONG USAGE: Returns function instead of map")

        ;; This triggers the ClassCastException / IllegalArgumentException
        (is (thrown? IllegalArgumentException
                     (merge {} wrong-result))
            "Merging with function throws IllegalArgumentException")

        (is (thrown? IllegalArgumentException
                     (into {} wrong-result))
            "into with function throws IllegalArgumentException")))))

(deftest correct-vs-incorrect-usage
  (testing "side-by-side comparison"
    (let [base-handler (fn [_] {:base :result})]

      ;; CORRECT: Two-step process
      (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :test})
            handler       (middleware-fn base-handler)  ;; Step 1: wrap handler
            result        (handler {::form/params {::form/delta nil}})] ;; Step 2: call with env
        (is (map? result))
        (is (= :result (:base result))))

      ;; INCORRECT: Direct call with env
      (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :test})
            bad-result    (middleware-fn {})]  ;; WRONG: passed env instead of handler
        (is (fn? bad-result))
        (is (thrown? ClassCastException
                     (assoc bad-result :key :value)))))))

;; ================================================================================
;; Error Handling Tests
;; ================================================================================

(deftest error-missing-connection
  (testing "save throws when connection missing"
    (let [delta     {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
          env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {}  ;; Empty!
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
      (let [;; Attributes say :other schema, but only :test connection exists
            wrong-id   (assoc tu/account-id ::attr/schema :other)
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

(deftest error-handles-missing-env-gracefully
  (testing "handles missing connections key in env"
    (let [delta      {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
          env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ;; Missing ::dlo/connections entirely
                      ::form/params         {::form/delta delta}}
          middleware (dl/wrap-datalevin-save {:default-schema :test})]

      (is (thrown? Exception
                   ((middleware (fn [_] {})) env))))))

;; ================================================================================
;; Multiple Schema Tests
;; ================================================================================

(def item-id-other
  (assoc tu/item-id ::attr/schema :other))

(def item-name-other
  (assoc tu/item-name ::attr/schema :other))

(deftest multiple-schemas-test
  (testing "saves to multiple schemas in one transaction"
    (tu/with-test-conn-attrs [conn1 [tu/account-id tu/account-name]]
      (let [;; Create second schema
            test-db2  (tu/create-test-conn [item-id-other item-name-other])
            conn2     (:conn test-db2)

            acc-tid   (tempid/tempid)
            acc-id    (new-uuid)
            item-tid  (tempid/tempid)
            item-id   (new-uuid)
            delta     {[:account/id acc-tid] {:account/id {:before nil :after acc-id}
                                              :account/name {:before nil :after "Test Account"}}
                       [:item/id item-tid] {:item/id {:before nil :after item-id}
                                            :item/name {:before nil :after "Test Item"}}}
            all-attrs (concat [tu/account-id tu/account-name] [item-id-other item-name-other])
            env       {::attr/key->attribute (tu/key->attribute-map all-attrs)
                       ::dlo/connections     {:test conn1 :other conn2}
                       ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))

        ;; Verify data in both schemas
        (is (= "Test Account" (:account/name (d/pull (d/db conn1) '[*] [:account/id acc-id]))))
        (is (= "Test Item" (:item/name (d/pull (d/db conn2) '[*] [:item/id item-id]))))

        (tu/cleanup-test-conn test-db2)))))

(comment
  ; no test exists using save-form* function.
  (form/save-form*))
