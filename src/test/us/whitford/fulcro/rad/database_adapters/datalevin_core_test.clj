(ns us.whitford.fulcro.rad.database-adapters.datalevin-core-test
  "Core functionality tests for the Datalevin RAD adapter.
   Tests schema generation, connections, delta processing, resolvers, and query utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
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

  (comment
    (dl/automatic-schema :test tu/all-test-attributes))

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
    (let [tid   (tempid/tempid)
          real-uuid (new-uuid)
          delta {[:account/id tid] {:account/id {:before nil :after real-uuid}
                                    :account/name {:before nil :after "Bob"}}}
          txn   (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (let [entry (first txn)]
        (is (number? (:db/id entry)))
        (is (neg? (:db/id entry)))
        ;; CRITICAL: Must use the real UUID, not the TempId
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
    (let [tid1 (tempid/tempid)
          tid2 (tempid/tempid)
          tid3 (tempid/tempid)
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

      (is (uuid? (:account/id entity))
          "account/id must be UUID, not TempId")
      (is (= real-id (:account/id entity))
          "Must use real UUID from delta :after value")
      (is (not (tempid/tempid? (:account/id entity)))
          "Must NOT be a TempId"))))

(comment
  (dl/delta->txn {[:account/id 1] {:account/name {:before "Bob Smith" :after "Bob Smithson"}}})
  (dl/generate-resolvers tu/all-test-attributes))

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
      (is (contains? (set outputs) :account/balance)))))

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
      (let [id1    (new-uuid)
            id2    (new-uuid)
            _      (d/transact! conn [{:account/id id1 :account/name "User 1"}
                                      {:account/id id2 :account/name "User 2"}])
            resolver (dl/id-resolver tu/account-id [tu/account-name])
            env    (dl/mock-resolver-env {:test conn})
            result (resolver env [{:account/id id1} {:account/id id2}])]
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

;; ================================================================================
;; Query Utility Tests
;; ================================================================================

(deftest query-utilities
  (testing "q executes datalog queries"
    (tu/with-test-conn [conn]
      (let [id   (new-uuid)
            _    (d/transact! conn [{:account/id id :account/name "Query Test"}])
            db   (d/db conn)
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
      (let [id1   (new-uuid)
            id2   (new-uuid)
            _     (d/transact! conn [{:account/id id1 :account/name "First"}
                                     {:account/id id2 :account/name "Second"}])
            db    (d/db conn)
            data  (dl/get-by-ids db :account/id [id1 id2] [:account/name])]
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
;; Mock Environment Tests
;; ================================================================================

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
;; Seed Database Tests
;; ================================================================================

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

;; ================================================================================
;; Metrics Tests
;; ================================================================================

(deftest metrics-tracking
  (testing "reset-metrics! clears all counters"
    (dl/reset-metrics!)
    (let [metrics (dl/get-metrics)]
      (is (= 0 (:transaction-count metrics)))
      (is (= 0 (:transaction-errors metrics)))
      (is (= 0 (:total-transaction-time-ms metrics))))))

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
