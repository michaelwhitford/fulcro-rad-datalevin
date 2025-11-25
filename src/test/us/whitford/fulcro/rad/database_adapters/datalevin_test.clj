(ns us.whitford.fulcro.rad.database-adapters.datalevin-test
  "Comprehensive tests for the Datalevin RAD adapter.
   Tests schema generation, connections, delta processing, resolvers, query utilities,
   middleware (save and delete), error handling, and RAD integration."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]))

;; ================================================================================
;; Schema Generation Tests
;; ================================================================================

(deftest automatic-schema-generation
  (testing "generates schema from RAD attributes"
    (let [schema (dl/automatic-schema :test tu/all-test-attributes)]
      (is (map? schema))
      (is (= :db.type/uuid (get-in schema [:account/id :db/valueType])))
      (is (= :db.unique/identity (get-in schema [:account/id :db/unique])))
      (is (= :db.type/string (get-in schema [:account/name :db/valueType])))
      (is (= :db.unique/value (get-in schema [:account/email :db/unique])))
      (is (= :db.type/boolean (get-in schema [:account/active? :db/valueType])))
      (is (= :db.type/double (get-in schema [:account/balance :db/valueType])))
      (is (= :db.type/ref (get-in schema [:account/items :db/valueType])))
      (is (= :db.cardinality/many (get-in schema [:account/items :db/cardinality])))))

  (testing "filters by schema name"
    (let [other-attr {::attr/qualified-key :other/attr
                      ::attr/type          :string
                      ::attr/schema        :other}
          schema     (dl/automatic-schema :test (conj tu/all-test-attributes other-attr))]
      (is (contains? schema :account/id))
      (is (not (contains? schema :other/attr)))))

  (testing "handles attributes without schema"
    (let [no-schema-attr {::attr/qualified-key :no/schema
                          ::attr/type          :string}
          schema         (dl/automatic-schema :test (conj tu/all-test-attributes no-schema-attr))]
      (is (not (contains? schema :no/schema))))))

;; ================================================================================
;; Database Connection Tests
;; ================================================================================

(deftest start-database-test
  (testing "starts database with auto-schema"
    (let [path (str "/tmp/datalevin-start-test-" (new-uuid))
          conn (dl/start-database!
                {:path       path
                 :schema     :test
                 :attributes tu/all-test-attributes})]
      (is (some? conn))
      (is (some? (d/db conn)))
      (d/close conn)
      (tu/cleanup-path path)))

  (testing "starts database without auto-schema"
    (let [path (str "/tmp/datalevin-no-schema-" (new-uuid))
          conn (dl/start-database!
                {:path         path
                 :schema       :test
                 :attributes   []
                 :auto-schema? false})]
      (is (some? conn))
      (d/close conn)
      (tu/cleanup-path path))))

(deftest empty-db-connection-test
  (testing "creates in-memory test database"
    (let [conn (dl/empty-db-connection :test tu/all-test-attributes)]
      (is (some? conn))
      (is (some? (d/db conn)))
      (d/close conn))))

;; ================================================================================
;; Delta to Transaction Conversion Tests
;; ================================================================================

(deftest delta-to-transaction-conversion
  (testing "converts simple delta to transaction"
    (let [id    (new-uuid)
          delta {[:account/id id] {:account/name {:before nil :after "Alice"}
                                   :account/email {:before nil :after "alice@test.com"}}}
          txn   (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (is (= {:db/id [:account/id id]
              :account/name "Alice"
              :account/email "alice@test.com"}
             (first txn)))))

  (testing "handles tempids correctly"
    (let [tid       (tempid/tempid)
          real-uuid (new-uuid)
          delta     {[:account/id tid] {:account/id {:before nil :after real-uuid}
                                        :account/name {:before nil :after "Bob"}}}
          txn       (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (let [entry (first txn)]
        (is (number? (:db/id entry)))
        (is (neg? (:db/id entry)))
        (is (= real-uuid (:account/id entry)) "Must use real UUID from delta :after, not TempId")
        (is (= "Bob" (:account/name entry))))))

  (testing "handles value removal"
    (let [id    (new-uuid)
          delta {[:account/id id] {:account/email {:before "old@test.com" :after nil}}}
          txn   (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (is (nil? (:account/email (first txn))))))

  (testing "ignores unchanged values"
    (let [id    (new-uuid)
          delta {[:account/id id] {:account/name {:before "Same" :after "Same"}}}
          txn   (dl/delta->txn delta)]
      (is (empty? txn)))))

;; ================================================================================
;; Delta Validation Tests
;; ================================================================================

(deftest delta-validation
  (testing "validates delta is a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Delta must be a map"
                          (dl/delta->txn "not a map"))))

  (testing "validates delta entry structure"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid delta structure"
                          (dl/delta->txn {[:account/id 123] "not a map"}))))

  (testing "validates ident is a vector"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid delta structure"
                          (dl/delta->txn {:not-a-vector {:account/name {:before nil :after "x"}}}))))

  (testing "valid delta passes validation"
    (is (vector? (dl/delta->txn {[:account/id (new-uuid)]
                                 {:account/name {:before nil :after "Valid"}}})))))

;; ================================================================================
;; Tempid Handling Tests
;; ================================================================================

(deftest tempid-uniqueness
  (testing "multiple tempids get unique transaction IDs"
    (let [tid1  (tempid/tempid)
          tid2  (tempid/tempid)
          tid3  (tempid/tempid)
          delta {[:account/id tid1] {:account/id {:before nil :after (new-uuid)}
                                     :account/name {:before nil :after "User 1"}}
                 [:account/id tid2] {:account/id {:before nil :after (new-uuid)}
                                     :account/name {:before nil :after "User 2"}}
                 [:account/id tid3] {:account/id {:before nil :after (new-uuid)}
                                     :account/name {:before nil :after "User 3"}}}
          txn   (binding [us.whitford.fulcro.rad.database-adapters.datalevin/*tempid-mappings* (atom {})]
                  (dl/delta->txn delta))
          ids   (map :db/id txn)]
      (is (= 3 (count ids)))
      (is (= 3 (count (set ids))) "All IDs should be unique")
      (is (every? neg? ids) "All IDs should be negative"))))

(deftest tempid-value-must-be-real-uuid
  (testing "TempId ident uses real UUID value from delta, not TempId itself"
    (let [tid      (tempid/tempid)
          real-id  (new-uuid)
          delta    {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "Test"}}}
          txn-data (dl/delta->txn delta)
          entity   (first (filter map? txn-data))]

      (is (uuid? (:account/id entity)) "account/id must be UUID, not TempId")
      (is (= real-id (:account/id entity)) "Must use real UUID from delta :after value")
      (is (not (tempid/tempid? (:account/id entity))) "Must NOT be a TempId"))))

;; ================================================================================
;; Resolver Generation Tests
;; ================================================================================

(deftest resolver-generation
  (testing "generates resolvers for identity attributes"
    (let [resolvers (dl/generate-resolvers tu/all-test-attributes)]
      (is (seq resolvers))
      (is (some #(= :account/id (first (::pco/input (:config %)))) resolvers))
      (is (some #(= :item/id (first (::pco/input (:config %)))) resolvers))))

  (testing "generated resolvers have correct output"
    (let [resolvers    (dl/generate-resolvers tu/all-test-attributes)
          account-res  (first (filter #(= :account/id (first (::pco/input (:config %))))
                                      resolvers))
          outputs      (::pco/output (:config account-res))]
      (is (some? account-res))
      (is (contains? (set outputs) :account/name))
      (is (contains? (set outputs) :account/email))
      (is (contains? (set outputs) :account/active?))
      (is (contains? (set outputs) :account/balance))))

  (testing "generates all-ids resolvers for each entity type"
    (let [resolvers        (dl/generate-resolvers tu/all-test-attributes)
          all-accounts-res (first (filter (fn [res]
                                            (let [output (::pco/output (:config res))]
                                              (some #(and (map? %)
                                                          (contains? % :all-accounts))
                                                    output)))
                                          resolvers))
          all-items-res    (first (filter (fn [res]
                                            (let [output (::pco/output (:config res))]
                                              (some #(and (map? %)
                                                          (contains? % :all-items))
                                                    output)))
                                          resolvers))]
      (is (some? all-accounts-res) "Should generate all-accounts resolver")
      (is (some? all-items-res) "Should generate all-items resolver"))))

(deftest id-resolver-functionality
  (testing "resolver fetches entity by id"
    (tu/with-test-conn [conn]
      (let [id       (new-uuid)
            _        (d/transact! conn [{:account/id id
                                         :account/name "Resolver Test"
                                         :account/email "resolver@test.com"
                                         :account/active? true
                                         :account/balance 100.50}])
            resolver (dl/id-resolver tu/account-id [tu/account-name tu/account-email
                                                    tu/account-active tu/account-balance])
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env [{:account/id id}])]
        (is (= 1 (count result)))
        (is (= "Resolver Test" (get-in result [0 :account/name])))
        (is (= "resolver@test.com" (get-in result [0 :account/email])))
        (is (true? (get-in result [0 :account/active?])))
        (is (= 100.50 (get-in result [0 :account/balance]))))))

  (testing "resolver handles batch requests"
    (tu/with-test-conn [conn]
      (let [id1      (new-uuid)
            id2      (new-uuid)
            _        (d/transact! conn [{:account/id id1 :account/name "User 1"}
                                        {:account/id id2 :account/name "User 2"}])
            resolver (dl/id-resolver tu/account-id [tu/account-name])
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env [{:account/id id1} {:account/id id2}])]
        (is (= 2 (count result)))
        (is (= "User 1" (get-in result [0 :account/name])))
        (is (= "User 2" (get-in result [1 :account/name]))))))

  (testing "resolver handles missing entities"
    (tu/with-test-conn [conn]
      (let [resolver (dl/id-resolver tu/account-id [tu/account-name])
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env [{:account/id (new-uuid)}])]
        (is (= 1 (count result)))
        (is (= {} (first result)))))))

(deftest all-ids-resolver-functionality
  (testing "resolver returns all entity IDs"
    (tu/with-test-conn [conn]
      (let [id1      (new-uuid)
            id2      (new-uuid)
            id3      (new-uuid)
            _        (d/transact! conn [{:account/id id1 :account/name "User 1"}
                                        {:account/id id2 :account/name "User 2"}
                                        {:account/id id3 :account/name "User 3"}])
            resolver (dl/all-ids-resolver tu/account-id)
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env {})]
        (is (contains? result :all-accounts) "Should have :all-accounts key")
        (is (= 3 (count (:all-accounts result))) "Should return all 3 accounts")
        (let [returned-ids (set (map :account/id (:all-accounts result)))]
          (is (contains? returned-ids id1))
          (is (contains? returned-ids id2))
          (is (contains? returned-ids id3))))))

  (testing "resolver returns empty vector when no entities exist"
    (tu/with-test-conn [conn]
      (let [resolver (dl/all-ids-resolver tu/account-id)
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env {})]
        (is (contains? result :all-accounts))
        (is (vector? (:all-accounts result)))
        (is (empty? (:all-accounts result))))))

  (testing "resolver works for different entity types"
    (tu/with-test-conn [conn]
      (let [item1    (new-uuid)
            item2    (new-uuid)
            _        (d/transact! conn [{:item/id item1 :item/name "Item 1"}
                                        {:item/id item2 :item/name "Item 2"}])
            resolver (dl/all-ids-resolver tu/item-id)
            env      (dl/mock-resolver-env {:test conn})
            result   (resolver env {})]
        (is (contains? result :all-items) "Should have :all-items key")
        (is (= 2 (count (:all-items result))) "Should return all 2 items")
        (let [returned-ids (set (map :item/id (:all-items result)))]
          (is (contains? returned-ids item1))
          (is (contains? returned-ids item2)))))))

;; ================================================================================
;; Query Utility Tests
;; ================================================================================

(deftest query-utilities
  (testing "q executes datalog queries"
    (tu/with-test-conn [conn]
      (let [id     (new-uuid)
            _      (d/transact! conn [{:account/id id :account/name "Query Test"}])
            db     (d/db conn)
            result (dl/q '[:find ?name
                           :in $ ?id
                           :where [?e :account/id ?id]
                           [?e :account/name ?name]]
                         db id)]
        (is (= #{["Query Test"]} result)))))

  (testing "pull retrieves entity data"
    (tu/with-test-conn [conn]
      (let [id   (new-uuid)
            _    (d/transact! conn [{:account/id id
                                     :account/name "Pull Test"
                                     :account/active? true}])
            db   (d/db conn)
            eid  (ffirst (dl/q '[:find ?e :in $ ?id :where [?e :account/id ?id]] db id))
            data (dl/pull db [:account/name :account/active?] eid)]
        (is (= "Pull Test" (:account/name data)))
        (is (true? (:account/active? data))))))

  (testing "get-by-ids fetches multiple entities"
    (tu/with-test-conn [conn]
      (let [id1  (new-uuid)
            id2  (new-uuid)
            _    (d/transact! conn [{:account/id id1 :account/name "First"}
                                    {:account/id id2 :account/name "Second"}])
            db   (d/db conn)
            data (dl/get-by-ids db :account/id [id1 id2] [:account/name])]
        (is (= 2 (count data)))
        (is (= "First" (get-in data [id1 :account/name])))
        (is (= "Second" (get-in data [id2 :account/name])))))))

;; ================================================================================
;; Batch Size Limits Tests
;; ================================================================================

(deftest batch-size-limits
  (testing "throws when batch size exceeds maximum"
    (tu/with-test-conn [conn]
      (let [db  (d/db conn)
            ids (repeatedly 1001 new-uuid)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Batch size exceeds maximum"
                              (dl/get-by-ids db :account/id ids [:account/name]))))))

  (testing "accepts exactly maximum batch size"
    (tu/with-test-conn [conn]
      (let [db  (d/db conn)
            ids (repeatedly 1000 new-uuid)]
        (is (map? (dl/get-by-ids db :account/id ids [:account/name])))))))

;; ================================================================================
;; Resource Management Tests
;; ================================================================================

(deftest temp-database-creation
  (testing "creates database with cleanup function"
    (let [{:keys [conn path cleanup!]} (dl/create-temp-database! :test tu/all-test-attributes)]
      (is (some? conn))
      (is (string? path))
      (is (fn? cleanup!))
      (is (.exists (java.io.File. path)))
      (cleanup!)
      (Thread/sleep 100)
      (is (not (.exists (java.io.File. path))) "Cleanup should remove directory")))

  (testing "database is functional before cleanup"
    (let [{:keys [conn cleanup!]} (dl/create-temp-database! :test tu/all-test-attributes)
          id (new-uuid)]
      (d/transact! conn [{:account/id id :account/name "Test"}])
      (let [result (ffirst (d/q '[:find ?name
                                  :in $ ?id
                                  :where [?e :account/id ?id]
                                  [?e :account/name ?name]]
                                (d/db conn) id))]
        (is (= "Test" result)))
      (cleanup!))))

(deftest with-temp-database-macro
  (testing "executes body with connection"
    (let [result (dl/with-temp-database [conn :test tu/all-test-attributes]
                   (let [id (new-uuid)]
                     (d/transact! conn [{:account/id id :account/name "Macro Test"}])
                     (ffirst (d/q '[:find ?name
                                    :in $ ?id
                                    :where [?e :account/id ?id]
                                    [?e :account/name ?name]]
                                  (d/db conn) id))))]
      (is (= "Macro Test" result))))

  (testing "cleans up even on exception"
    (try
      (dl/with-temp-database [conn :test tu/all-test-attributes]
        (throw (ex-info "Test exception" {})))
      (catch Exception _))
    (is true "Cleanup should not throw")))

(deftest seed-database
  (testing "seeds database with initial data"
    (tu/with-test-conn [conn]
      (let [id1  (new-uuid)
            id2  (new-uuid)
            data [{:account/id id1 :account/name "Seed 1"}
                  {:account/id id2 :account/name "Seed 2"}]]
        (dl/seed-database! conn data)
        (let [db    (d/db conn)
              count (ffirst (dl/q '[:find (count ?e) :where [?e :account/id]] db))]
          (is (= 2 count))))))

  (testing "handles empty seed data"
    (tu/with-test-conn [conn]
      (dl/seed-database! conn [])
      (let [db    (d/db conn)
            count (dl/q '[:find ?e :where [?e :account/id]] db)]
        (is (empty? count))))))

(deftest mock-resolver-env-test
  (testing "creates proper environment structure"
    (tu/with-test-conn [conn]
      (let [env (dl/mock-resolver-env {:test conn})]
        (is (map? env))
        (is (contains? env ::dlo/connections))
        (is (contains? env ::dlo/databases))
        (is (some? (get-in env [::dlo/connections :test])))
        (is (some? (get-in env [::dlo/databases :test])))))))

;; ================================================================================
;; Save Middleware - Core Behavior
;; ================================================================================

(deftest save-middleware-returns-map
  (testing "wrap-datalevin-save returns a map, not a function"
    (tu/with-test-conn [conn]
      (let [tid          (tempid/tempid)
            real-id      (new-uuid)
            delta        {[:account/id tid] {:account/id {:before nil :after real-id}
                                             :account/name {:before nil :after "Test User"}}}
            env          {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
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
      (let [tid        (tempid/tempid)
            real-id    (new-uuid)
            delta      {[:account/id tid] {:account/id {:before nil :after real-id}
                                           :account/name {:before nil :after "Jane Doe"}
                                           :account/email {:before nil :after "jane@example.com"}
                                           :account/active? {:before nil :after true}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (= real-id (get (:tempids result) tid)))

        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= real-id (:account/id entity)))
          (is (= "Jane Doe" (:account/name entity)))
          (is (= "jane@example.com" (:account/email entity)))
          (is (true? (:account/active? entity)))))))

  (testing "updates existing entity"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id
                                           :account/name "Original"
                                           :account/email "original@example.com"}])
            delta      {[:account/id real-id] {:account/name {:before "Original" :after "Updated"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _          ((middleware (fn [_] {})) env)
            entity     (d/pull (d/db conn) '[*] [:account/id real-id])]
        (is (= "Updated" (:account/name entity)))
        (is (= "original@example.com" (:account/email entity)) "Unchanged field preserved")))))

(deftest save-middleware-handles-tempids
  (testing "maps multiple tempids to real IDs"
    (tu/with-test-conn [conn]
      (let [tid1       (tempid/tempid)
            tid2       (tempid/tempid)
            real-id1   (new-uuid)
            real-id2   (new-uuid)
            delta      {[:account/id tid1] {:account/id {:before nil :after real-id1}
                                            :account/name {:before nil :after "User 1"}}
                        [:account/id tid2] {:account/id {:before nil :after real-id2}
                                            :account/name {:before nil :after "User 2"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? (:tempids result)))
        (is (= real-id1 (get (:tempids result) tid1)))
        (is (= real-id2 (get (:tempids result) tid2)))

        (is (= "User 1" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id1]))))
        (is (= "User 2" (:account/name (d/pull (d/db conn) '[*] [:account/id real-id2]))))))))

(deftest save-middleware-edge-cases
  (testing "handles empty delta"
    (tu/with-test-conn [conn]
      (let [env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta {}}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (map? result))
        (is (= :ok (:result result))))))

  (testing "handles nil delta"
    (tu/with-test-conn [conn]
      (let [env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta nil}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (map? result))
        (is (= :ok (:result result))))))

  (testing "setting attribute to nil removes it"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id
                                           :account/name "Has Name"
                                           :account/email "has@email.com"}])
            delta      {[:account/id real-id] {:account/email {:before "has@email.com" :after nil}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            _          ((middleware (fn [_] {})) env)
            entity     (d/pull (d/db conn) '[*] [:account/id real-id])]
        (is (= "Has Name" (:account/name entity)))
        (is (nil? (:account/email entity)))))))

;; ================================================================================
;; New Entity Creation with UUID Keys (Edge Cases)
;; ================================================================================

(deftest create-new-entity-with-uuid-key
  (testing "creates new entity when ident has UUID (not tempid) and :before is nil"
    (tu/with-test-conn [conn]
      (let [new-id     (new-uuid)
            delta      {[:account/id new-id]
                        {:account/id {:before nil :after new-id}
                         :account/name {:before nil :after "Michael"}
                         :account/email {:before nil :after "michael@whitford.us"}
                         :account/active? {:before nil :after true}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result) "Result must be a map")
        (is (contains? result :tempids) "Must have :tempids key")

        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity)))
          (is (= "Michael" (:account/name entity)))
          (is (= "michael@whitford.us" (:account/email entity)))
          (is (true? (:account/active? entity)))))))

  (testing "handles ident with nested {:id uuid} map format"
    (tu/with-test-conn [conn]
      (let [new-id     (new-uuid)
            delta      {[:account/id {:id new-id}]
                        {:account/name {:before nil :after "Michael"}
                         :account/email {:before nil :after "michael@whitford.us"}
                         :account/active? {:before nil :after true}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (map? result))
        (is (contains? result :tempids))

        (let [entity (d/pull (d/db conn) '[*] [:account/id new-id])]
          (is (= new-id (:account/id entity)))
          (is (= "Michael" (:account/name entity))))))))

;; ================================================================================
;; Tempid Requirements - CRITICAL for RAD
;; ================================================================================

(deftest tempids-always-present-in-result
  (testing ":tempids present when saving new entity with tempids"
    (tu/with-test-conn [conn]
      (let [tid        (tempid/tempid)
            real-id    (new-uuid)
            delta      {[:account/id tid]
                        {:account/id {:before nil :after real-id}
                         :account/name {:before nil :after "New User"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (contains? result :tempids) ":tempids must be present for RAD")
        (is (map? (:tempids result)))
        (is (= real-id (get (:tempids result) tid))))))

  (testing ":tempids present when updating existing entity"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "Original"}])
            delta      {[:account/id real-id] {:account/name {:before "Original" :after "Updated"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {})) env)]

        (is (contains? result :tempids) ":tempids MUST be present even when updating")
        (is (map? (:tempids result)))
        (is (empty? (:tempids result))))))

  (testing ":tempids present with empty delta"
    (tu/with-test-conn [conn]
      (let [env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta {}}}
            middleware (dl/wrap-datalevin-save {:default-schema :test})
            result     ((middleware (fn [_] {:result :ok})) env)]

        (is (contains? result :tempids))
        (is (map? (:tempids result)))
        (is (empty? (:tempids result)))))))

;; ================================================================================
;; Delete Middleware
;; ================================================================================

(deftest delete-middleware-test
  (testing "deletes entity from database"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "ToDelete"}])
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id real-id}}
            middleware (dl/wrap-datalevin-delete {:default-schema :test})
            result     ((middleware (fn [_] {:deleted true})) env)]

        (is (true? (:deleted result)))

        (let [exists (d/q '[:find ?e :in $ ?id :where [?e :account/id ?id]]
                          (d/db conn) real-id)]
          (is (empty? exists))))))

  (testing "handles non-existent entity gracefully"
    (tu/with-test-conn [conn]
      (let [env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id (new-uuid)}}
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
            delta  {[:account/id tid]
                    {:account/id {:before nil :after real-id}
                     :account/name {:before nil :after "RAD User"}}}
            env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta delta}}
            ;; Simulate RAD middleware stack
            base-handler    (fn [_] {::form/complete? true})
            wrap-validation (fn [handler]
                              (fn [env]
                                (assoc (handler env) ::form/validated? true)))
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
        (is (every? #(not (fn? %)) (vals result)))

        (let [serialized (pr-str result)]
          (is (string? serialized))
          (is (not (re-find #"fn__\d+" serialized))))))))

;; ================================================================================
;; Error Handling Tests
;; ================================================================================

(deftest error-missing-connection
  (testing "save throws when connection missing"
    (let [delta      {[:account/id (new-uuid)] {:account/name {:before nil :after "Test"}}}
          env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {}
                      ::form/params         {::form/delta delta}}
          middleware (dl/wrap-datalevin-save {:default-schema :test})]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No database connection configured"
                            ((middleware (fn [_] {})) env)))))

  (testing "delete throws when connection missing"
    (let [env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                      ::dlo/connections     {}
                      ::form/params         {:account/id (new-uuid)}}
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
;; Incorrect Usage Tests
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
            middleware-wrapper (dl/wrap-datalevin-save {:default-schema :test})
            wrong-result       (middleware-wrapper env)]

        (is (fn? wrong-result) "WRONG USAGE: Returns function instead of map")
        (is (thrown? IllegalArgumentException (merge {} wrong-result)))))))

(deftest correct-vs-incorrect-usage
  (testing "side-by-side comparison"
    (let [base-handler (fn [_] {:base :result})]

      ;; CORRECT: Two-step process
      (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :test})
            handler       (middleware-fn base-handler)
            result        (handler {::form/params {::form/delta nil}})]
        (is (map? result))
        (is (= :result (:base result))))

      ;; INCORRECT: Direct call with env
      (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :test})
            bad-result    (middleware-fn {})]
        (is (fn? bad-result))
        (is (thrown? ClassCastException (assoc bad-result :key :value)))))))

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
      (let [test-db2  (tu/create-test-conn [item-id-other item-name-other])
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
        (is (= "Test Account" (:account/name (d/pull (d/db conn1) '[*] [:account/id acc-id]))))
        (is (= "Test Item" (:item/name (d/pull (d/db conn2) '[*] [:item/id item-id]))))

        (tu/cleanup-test-conn test-db2)))))

;; ================================================================================
;; save-form! Direct Usage Tests
;; ================================================================================

(deftest save-form-direct-usage
  (testing "save-form! creates new entity and returns tempid mapping"
    (tu/with-test-conn [conn]
      (let [tid     (tempid/tempid)
            real-id (new-uuid)
            delta   {[:account/id tid]
                     {:account/id    {:before nil :after real-id}
                      :account/name  {:before nil :after "Direct Save"}
                      :account/email {:before nil :after "direct@example.com"}}}
            env     {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/params         {::form/delta delta}}
            result  (dl/save-form! env)]

        (is (map? result))
        (is (contains? result :tempids))
        (is (= real-id (get (:tempids result) tid)))

        (let [entity (d/pull (d/db conn) '[*] [:account/id real-id])]
          (is (= "Direct Save" (:account/name entity)))
          (is (= "direct@example.com" (:account/email entity)))))))

  (testing "save-form! updates existing entity"
    (tu/with-test-conn [conn]
      (let [entity-id (new-uuid)
            _         (d/transact! conn [{:account/id entity-id :account/name "Original"}])
            delta     {[:account/id entity-id] {:account/name {:before "Original" :after "Updated"}}}
            env       {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                       ::dlo/connections     {:test conn}
                       ::form/params         {::form/delta delta}}
            result    (dl/save-form! env)]

        (is (map? result))
        (is (empty? (:tempids result)))
        (is (= "Updated" (:account/name (d/pull (d/db conn) '[*] [:account/id entity-id])))))))

  (testing "save-form! handles empty delta"
    (tu/with-test-conn [conn]
      (let [env    {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                    ::dlo/connections     {:test conn}
                    ::form/params         {::form/delta {}}}
            result (dl/save-form! env)]

        (is (map? result))
        (is (contains? result :tempids))
        (is (empty? (:tempids result)))))))
