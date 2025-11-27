(ns us.whitford.fulcro.rad.database-adapters.datalevin-test
  "Tests for the Datalevin RAD adapter matching XTDB adapter patterns."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

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
      (is (vector? (first txn)))
      (is (= :db/retract (first (first txn))))))

  (testing "ignores unchanged values"
    (let [id    (new-uuid)
          delta {[:account/id id] {:account/name {:before "Same" :after "Same"}}}
          txn   (dl/delta->txn delta)]
      (is (empty? txn)))))

;; ================================================================================
;; Resolver Generation Tests
;; ================================================================================

(deftest resolver-generation
  (testing "generates resolvers for schema"
    (let [resolvers (dl/generate-resolvers tu/all-test-attributes :test)]
      (is (seq resolvers))
      (is (some #(= :account/id (first (::pco/input (:config %)))) resolvers))
      (is (some #(= :item/id (first (::pco/input (:config %)))) resolvers))))

  (testing "generated resolvers have correct output"
    (let [resolvers    (dl/generate-resolvers tu/all-test-attributes :test)
          account-res  (first (filter #(= :account/id (first (::pco/input (:config %))))
                                      resolvers))
          outputs      (::pco/output (:config account-res))]
      (is (some? account-res))
      (is (contains? (set outputs) :account/name))
      (is (contains? (set outputs) :account/email))
      (is (contains? (set outputs) :account/active?))
      (is (contains? (set outputs) :account/balance))))

  (testing "generates all-ids resolvers"
    (let [resolvers     (dl/generate-resolvers tu/all-test-attributes :test)
          ;; Check if we have the right number of resolvers
          ;; Should have 2 id-resolvers + 2 all-ids-resolvers = 4 total
          ]
      (is (= 4 (count resolvers)) "Should have 2 id-resolvers and 2 all-ids-resolvers")))

  (testing "all-ids resolver returns entity IDs"
    (tu/with-test-conn [conn]
      (let [id1       (new-uuid)
            id2       (new-uuid)
            _         (d/transact! conn [{:account/id id1 :account/name "Account 1"}
                                         {:account/id id2 :account/name "Account 2"}])
            resolvers (dl/generate-resolvers tu/all-test-attributes :test)
            ;; Find resolver by checking if output is a vector (all-ids resolvers have vector output)
            ;; and contains the :account/all key
            all-accts (first (filter #(let [output (::pco/output (:config %))]
                                        (and (vector? output)
                                             (map? (first output))
                                             (contains? (first output) :account/all)))
                                     resolvers))]
        (is (some? all-accts) "Should find all-accounts resolver")
        (when all-accts
          (let [env    (assoc (tu/mock-resolver-env {:test conn})
                              ::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes))
                result ((:resolve all-accts) env {})]
            (is (some? result))
            (is (contains? result :account/all))
            (is (= 2 (count (:account/all result))))
            (is (every? #(contains? % :account/id) (:account/all result)))
            (is (= (set [id1 id2]) (set (map :account/id (:account/all result)))))))))))

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
;; Save Middleware Tests
;; ================================================================================

(deftest save-middleware-basic
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
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]

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
            middleware (dl/wrap-datalevin-save)
            _          (middleware env)
            entity     (d/pull (d/db conn) '[*] [:account/id real-id])]
        (is (= "Updated" (:account/name entity)))
        (is (= "original@example.com" (:account/email entity)) "Unchanged field preserved")))))

(deftest save-middleware-with-handler
  (testing "composes with other middleware"
    (tu/with-test-conn [conn]
      (let [tid        (tempid/tempid)
            real-id    (new-uuid)
            delta      {[:account/id tid] {:account/id {:before nil :after real-id}
                                           :account/name {:before nil :after "Test User"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            base-handler (fn [_] {:base-result true})
            middleware (dl/wrap-datalevin-save base-handler)
            result     (middleware env)]

        (is (true? (:base-result result)))
        (is (= real-id (get (:tempids result) tid)))))))

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
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]

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
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]
        (is (map? result))
        (is (contains? result :tempids)))))

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
            middleware (dl/wrap-datalevin-save)
            _          (middleware env)
            entity     (d/pull (d/db conn) '[*] [:account/id real-id])]
        (is (= "Has Name" (:account/name entity)))
        (is (nil? (:account/email entity)))))))

;; ================================================================================
;; Tempids Tests (CRITICAL - Fulcro RAD requires :tempids in all form operation results)
;; ================================================================================

(deftest save-middleware-returns-tempids
  (testing "save with new entity returns tempids mapping"
    (tu/with-test-conn [conn]
      (let [tid        (tempid/tempid)
            real-id    (new-uuid)
            delta      {[:account/id tid] {:account/id {:before nil :after real-id}
                                           :account/name {:before nil :after "Test"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key")
        (is (map? (:tempids result)) ":tempids must be a map")
        (is (= real-id (get (:tempids result) tid)) "Tempid should map to real ID"))))

  (testing "save with existing entity returns empty tempids map"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "Original"}])
            delta      {[:account/id real-id] {:account/name {:before "Original" :after "Updated"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key")
        (is (map? (:tempids result)) ":tempids must be a map")
        (is (empty? (:tempids result)) "No tempids for existing entity updates"))))

  (testing "save with handler preserves tempids"
    (tu/with-test-conn [conn]
      (let [tid        (tempid/tempid)
            real-id    (new-uuid)
            delta      {[:account/id tid] {:account/id {:before nil :after real-id}
                                           :account/name {:before nil :after "Test"}}}
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {::form/delta delta}}
            base-handler (fn [_] {:extra-data true})
            middleware (dl/wrap-datalevin-save base-handler)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key")
        (is (map? (:tempids result)) ":tempids must be a map")
        (is (= real-id (get (:tempids result) tid)) "Tempid should map to real ID")
        (is (true? (:extra-data result)) "Handler data should be merged")))))

(deftest delete-middleware-returns-tempids
  (testing "delete returns empty tempids map"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "ToDelete"}])
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id real-id}}
            middleware (dl/wrap-datalevin-delete)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key")
        (is (map? (:tempids result)) ":tempids must be a map")
        (is (empty? (:tempids result)) "Delete should return empty tempids map"))))

  (testing "delete with handler preserves tempids"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "ToDelete"}])
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id real-id}}
            base-handler (fn [_] {:extra-data true})
            middleware (dl/wrap-datalevin-delete base-handler)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key")
        (is (map? (:tempids result)) ":tempids must be a map")
        (is (empty? (:tempids result)) "Delete should return empty tempids map")
        (is (true? (:extra-data result)) "Handler data should be merged"))))

  (testing "delete of non-existent entity returns tempids"
    (tu/with-test-conn [conn]
      (let [fake-id    (new-uuid)
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id fake-id}}
            middleware (dl/wrap-datalevin-delete)
            result     (middleware env)]

        (is (contains? result :tempids) "Result must contain :tempids key even for failed deletes")
        (is (map? (:tempids result)) ":tempids must be a map")))))

;; ================================================================================
;; Delete Middleware Tests
;; ================================================================================

(deftest delete-middleware-test
  (testing "deletes entity from database"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "ToDelete"}])
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id real-id}}
            middleware (dl/wrap-datalevin-delete)
            result     (middleware env)]

        (is (map? result))

        (let [exists (d/q '[:find ?e :in $ ?id :where [?e :account/id ?id]]
                          (d/db conn) real-id)]
          (is (empty? exists))))))

  (testing "composes with other middleware"
    (tu/with-test-conn [conn]
      (let [real-id    (new-uuid)
            _          (d/transact! conn [{:account/id real-id :account/name "ToDelete"}])
            env        {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                        ::dlo/connections     {:test conn}
                        ::form/params         {:account/id real-id}}
            base-handler (fn [_] {:deleted true})
            middleware (dl/wrap-datalevin-delete base-handler)
            result     (middleware env)]

        (is (true? (:deleted result)))))))

;; ================================================================================
;; Resource Management Tests
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
;; Pathom Plugin Tests
;; ================================================================================

(deftest pathom-plugin-test
  (testing "creates plugin with database-mapper"
    (tu/with-test-conn [conn]
      (let [database-mapper (fn [_env] {:test conn})
            plugin          (dl/pathom-plugin database-mapper)]
        (is (map? plugin))
        (is (contains? plugin :com.wsscode.pathom3.connect.runner/wrap-root-run))))))

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
            middleware (dl/wrap-datalevin-save)
            result     (middleware env)]

        (is (map? result))
        (is (= "Test Account" (:account/name (d/pull (d/db conn1) '[*] [:account/id acc-id]))))
        (is (= "Test Item" (:item/name (d/pull (d/db conn2) '[*] [:item/id item-id]))))

        (tu/cleanup-test-conn test-db2)))))
