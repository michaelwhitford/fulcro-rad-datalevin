(ns com.fulcrologic.rad.database-adapters.datalevin-test
  (:require
    [clojure.test :refer [deftest testing is use-fixtures]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.database-adapters.datalevin :as dl]
    [com.fulcrologic.rad.database-adapters.datalevin-options :as dlo]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [datalevin.core :as d]))

;; ================================================================================
;; Test Fixtures and Helpers
;; ================================================================================

(def test-db-path (atom nil))
(def test-conn (atom nil))

(defn setup-test-db [f]
  (let [path (str "/tmp/datalevin-test-" (new-uuid))]
    (reset! test-db-path path)
    (try
      (f)
      (finally
        (when @test-conn
          (d/close @test-conn)
          (reset! test-conn nil))
        ;; Clean up test directory
        (let [dir (java.io.File. path)]
          (when (.exists dir)
            (doseq [file (reverse (file-seq dir))]
              (.delete file))))))))

(use-fixtures :each setup-test-db)

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
   ::attr/identities    #{:account/id}
   ::dlo/attribute-schema {:db/unique :db.unique/value}})

(def account-active
  {::attr/qualified-key :account/active?
   ::attr/type          :boolean
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def account-balance
  {::attr/qualified-key :account/balance
   ::attr/type          :double
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def item-id
  {::attr/qualified-key :item/id
   ::attr/type          :uuid
   ::attr/schema        :test
   ::attr/identity?     true})

(def item-name
  {::attr/qualified-key :item/name
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:item/id}})

(def account-items
  {::attr/qualified-key :account/items
   ::attr/type          :ref
   ::attr/schema        :test
   ::attr/cardinality   :many
   ::attr/target        :item/id
   ::attr/identities    #{:account/id}})

(def test-attributes
  [account-id account-name account-email account-active account-balance
   item-id item-name account-items])

;; ================================================================================
;; Schema Generation Tests
;; ================================================================================

(deftest automatic-schema-test
  (testing "generates schema from RAD attributes"
    (let [schema (dl/automatic-schema :test test-attributes)]
      (is (map? schema))
      (is (= :db.type/uuid (get-in schema [:account/id :db/valueType])))
      (is (= :db.unique/identity (get-in schema [:account/id :db/unique])))
      (is (= :db.type/string (get-in schema [:account/name :db/valueType])))
      (is (= :db.type/string (get-in schema [:account/email :db/valueType])))
      (is (= :db.unique/value (get-in schema [:account/email :db/unique])))
      (is (= :db.type/boolean (get-in schema [:account/active? :db/valueType])))
      (is (= :db.type/double (get-in schema [:account/balance :db/valueType])))
      (is (= :db.type/ref (get-in schema [:account/items :db/valueType])))
      (is (= :db.cardinality/many (get-in schema [:account/items :db/cardinality])))))

  (testing "filters by schema name"
    (let [other-attr {::attr/qualified-key :other/attr
                      ::attr/type          :string
                      ::attr/schema        :other}
          schema     (dl/automatic-schema :test (conj test-attributes other-attr))]
      (is (contains? schema :account/id))
      (is (not (contains? schema :other/attr)))))

  (testing "handles attributes without schema"
    (let [no-schema-attr {::attr/qualified-key :no/schema
                          ::attr/type          :string}
          schema         (dl/automatic-schema :test (conj test-attributes no-schema-attr))]
      (is (not (contains? schema :no/schema))))))

;; ================================================================================
;; Database Connection Tests
;; ================================================================================

(deftest start-database-test
  (testing "starts database with auto-schema"
    (let [conn (dl/start-database!
                 {:path       @test-db-path
                  :schema     :test
                  :attributes test-attributes})]
      (reset! test-conn conn)
      (is (some? conn))
      (is (some? (d/db conn)))))

  (testing "starts database without auto-schema"
    (let [path (str @test-db-path "-no-schema")
          conn (dl/start-database!
                 {:path         path
                  :schema       :test
                  :attributes   []
                  :auto-schema? false})]
      (is (some? conn))
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest empty-db-connection-test
  (testing "creates in-memory test database"
    (let [conn (dl/empty-db-connection :test test-attributes)]
      (is (some? conn))
      (is (some? (d/db conn)))
      (d/close conn))))

;; ================================================================================
;; Delta Processing Tests
;; ================================================================================

(deftest delta->txn-test
  (testing "converts simple delta to transaction"
    (let [id    (new-uuid)
          delta {[:account/id id] {:account/name {:before nil :after "Alice"}
                                   :account/email {:before nil :after "alice@test.com"}}}
          txn   (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (is (= {:db/id {:account/id id}
              :account/name "Alice"
              :account/email "alice@test.com"}
             (first txn)))))

  (testing "handles tempids"
    (let [tid   (tempid/tempid)
          delta {[:account/id tid] {:account/id {:before nil :after tid}
                                    :account/name {:before nil :after "Bob"}}}
          txn   (dl/delta->txn delta)]
      (is (= 1 (count txn)))
      (let [entry (first txn)]
        (is (number? (:db/id entry)))
        (is (neg? (:db/id entry)))
        (is (= tid (:account/id entry)))
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
;; Save Middleware Tests
;; ================================================================================

(deftest wrap-datalevin-save-test
  (testing "saves new entity"
    (let [conn      (dl/empty-db-connection :test test-attributes)
          tid       (tempid/tempid)
          id        (new-uuid)
          delta     {[:account/id tid] {:account/id {:before nil :after id}
                                         :account/name {:before nil :after "Test User"}
                                         :account/email {:before nil :after "test@example.com"}
                                         :account/active? {:before nil :after true}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          handler   (fn [_] {:result :ok})
          middleware (dl/wrap-datalevin-save {:default-schema :test})
          result    ((middleware handler) env)]
      (is (= :ok (:result result)))
      (is (map? (:tempids result)))
      ;; Verify data was saved
      (let [db   (d/db conn)
            data (ffirst (d/q '[:find (pull ?e [*])
                                :in $ ?id
                                :where [?e :account/id ?id]]
                              db id))]
        (is (= "Test User" (:account/name data)))
        (is (= "test@example.com" (:account/email data)))
        (is (true? (:account/active? data))))
      (d/close conn)))

  (testing "updates existing entity"
    (let [conn      (dl/empty-db-connection :test test-attributes)
          id        (new-uuid)
          _         (d/transact! conn [{:account/id id
                                         :account/name "Original"
                                         :account/email "original@test.com"}])
          delta     {[:account/id id] {:account/name {:before "Original" :after "Updated"}}}
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delta          delta}
          handler   (fn [_] {})
          middleware (dl/wrap-datalevin-save {:default-schema :test})
          _         ((middleware handler) env)
          db        (d/db conn)
          data      (ffirst (d/q '[:find (pull ?e [*])
                                   :in $ ?id
                                   :where [?e :account/id ?id]]
                                 db id))]
      (is (= "Updated" (:account/name data)))
      (is (= "original@test.com" (:account/email data)))
      (d/close conn))))

;; ================================================================================
;; Delete Middleware Tests
;; ================================================================================

(deftest wrap-datalevin-delete-test
  (testing "deletes entity"
    (let [conn      (dl/empty-db-connection :test test-attributes)
          id        (new-uuid)
          _         (d/transact! conn [{:account/id id
                                         :account/name "ToDelete"
                                         :account/email "delete@test.com"}])
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delete-params  [[:account/id id]]}
          handler   (fn [_] {:deleted true})
          middleware (dl/wrap-datalevin-delete {:default-schema :test})
          result    ((middleware handler) env)]
      (is (true? (:deleted result)))
      ;; Verify entity was deleted
      (let [db     (d/db conn)
            exists (d/q '[:find ?e
                          :in $ ?id
                          :where [?e :account/id ?id]]
                        db id)]
        (is (empty? exists)))
      (d/close conn)))

  (testing "handles non-existent entity gracefully"
    (let [conn      (dl/empty-db-connection :test test-attributes)
          id        (new-uuid)
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env       {::attr/key->attribute key->attr
                     ::dlo/connections     {:test conn}
                     ::form/delete-params  [[:account/id id]]}
          handler   (fn [_] {:result :ok})
          middleware (dl/wrap-datalevin-delete {:default-schema :test})
          result    ((middleware handler) env)]
      (is (= :ok (:result result)))
      (d/close conn))))

;; ================================================================================
;; Resolver Generation Tests
;; ================================================================================

(deftest generate-resolvers-test
  (testing "generates resolvers for identity attributes"
    (let [resolvers (dl/generate-resolvers test-attributes)]
      (is (seq resolvers))
      (is (some #(= :account/id (first (::pco/input (meta %)))) resolvers))
      (is (some #(= :item/id (first (::pco/input (meta %)))) resolvers))))

  (testing "generated resolvers have correct output"
    (let [resolvers    (dl/generate-resolvers test-attributes)
          account-res  (first (filter #(= :account/id (first (::pco/input (meta %))))
                                       resolvers))
          outputs      (::pco/output (meta account-res))]
      (is (some? account-res))
      (is (contains? (set outputs) :account/name))
      (is (contains? (set outputs) :account/email))
      (is (contains? (set outputs) :account/active?))
      (is (contains? (set outputs) :account/balance)))))

(deftest id-resolver-test
  (testing "resolver fetches entity by id"
    (let [conn     (dl/empty-db-connection :test test-attributes)
          id       (new-uuid)
          _        (d/transact! conn [{:account/id id
                                        :account/name "Resolver Test"
                                        :account/email "resolver@test.com"
                                        :account/active? true
                                        :account/balance 100.50}])
          resolver (dl/id-resolver account-id [account-name account-email account-active account-balance])
          env      (dl/mock-resolver-env {:test conn})
          result   (resolver env [{:account/id id}])]
      (is (= 1 (count result)))
      (is (= "Resolver Test" (get-in result [0 :account/name])))
      (is (= "resolver@test.com" (get-in result [0 :account/email])))
      (is (true? (get-in result [0 :account/active?])))
      (is (= 100.50 (get-in result [0 :account/balance])))
      (d/close conn)))

  (testing "resolver handles batch requests"
    (let [conn   (dl/empty-db-connection :test test-attributes)
          id1    (new-uuid)
          id2    (new-uuid)
          _      (d/transact! conn [{:account/id id1 :account/name "User 1"}
                                     {:account/id id2 :account/name "User 2"}])
          resolver (dl/id-resolver account-id [account-name])
          env    (dl/mock-resolver-env {:test conn})
          result (resolver env [{:account/id id1} {:account/id id2}])]
      (is (= 2 (count result)))
      (is (= "User 1" (get-in result [0 :account/name])))
      (is (= "User 2" (get-in result [1 :account/name])))
      (d/close conn)))

  (testing "resolver handles missing entities"
    (let [conn     (dl/empty-db-connection :test test-attributes)
          id       (new-uuid)
          resolver (dl/id-resolver account-id [account-name])
          env      (dl/mock-resolver-env {:test conn})
          result   (resolver env [{:account/id id}])]
      (is (= 1 (count result)))
      (is (= {} (first result)))
      (d/close conn))))

;; ================================================================================
;; Query Utility Tests
;; ================================================================================

(deftest query-utilities-test
  (testing "q executes datalog queries"
    (let [conn (dl/empty-db-connection :test test-attributes)
          id   (new-uuid)
          _    (d/transact! conn [{:account/id id :account/name "Query Test"}])
          db   (d/db conn)
          result (dl/q '[:find ?name
                         :in $ ?id
                         :where [?e :account/id ?id]
                                [?e :account/name ?name]]
                       db id)]
      (is (= [["Query Test"]] result))
      (d/close conn)))

  (testing "pull retrieves entity data"
    (let [conn (dl/empty-db-connection :test test-attributes)
          id   (new-uuid)
          _    (d/transact! conn [{:account/id id
                                    :account/name "Pull Test"
                                    :account/active? true}])
          db   (d/db conn)
          eid  (ffirst (dl/q '[:find ?e :in $ ?id :where [?e :account/id ?id]] db id))
          data (dl/pull db [:account/name :account/active?] eid)]
      (is (= "Pull Test" (:account/name data)))
      (is (true? (:account/active? data)))
      (d/close conn)))

  (testing "get-by-ids fetches multiple entities"
    (let [conn  (dl/empty-db-connection :test test-attributes)
          id1   (new-uuid)
          id2   (new-uuid)
          _     (d/transact! conn [{:account/id id1 :account/name "First"}
                                    {:account/id id2 :account/name "Second"}])
          db    (d/db conn)
          data  (dl/get-by-ids db :account/id [id1 id2] [:account/name])]
      (is (= 2 (count data)))
      (is (= "First" (get-in data [id1 :account/name])))
      (is (= "Second" (get-in data [id2 :account/name])))
      (d/close conn))))

;; ================================================================================
;; Mock Environment Tests
;; ================================================================================

(deftest mock-resolver-env-test
  (testing "creates proper environment structure"
    (let [conn (dl/empty-db-connection :test test-attributes)
          env  (dl/mock-resolver-env {:test conn})]
      (is (map? env))
      (is (contains? env ::dlo/connections))
      (is (contains? env ::dlo/databases))
      (is (some? (get-in env [::dlo/connections :test])))
      (is (some? (get-in env [::dlo/databases :test])))
      (d/close conn))))

;; ================================================================================
;; Seed Database Tests
;; ================================================================================

(deftest seed-database-test
  (testing "seeds database with initial data"
    (let [conn (dl/empty-db-connection :test test-attributes)
          id1  (new-uuid)
          id2  (new-uuid)
          data [{:account/id id1 :account/name "Seed 1"}
                {:account/id id2 :account/name "Seed 2"}]]
      (dl/seed-database! conn data)
      (let [db    (d/db conn)
            count (ffirst (dl/q '[:find (count ?e) :where [?e :account/id]] db))]
        (is (= 2 count)))
      (d/close conn)))

  (testing "handles empty seed data"
    (let [conn (dl/empty-db-connection :test test-attributes)]
      (dl/seed-database! conn [])
      (let [db    (d/db conn)
            count (dl/q '[:find ?e :where [?e :account/id]] db)]
        (is (empty? count)))
      (d/close conn))))
