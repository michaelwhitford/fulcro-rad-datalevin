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
  (let [env {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)}]
    (testing "converts simple delta to transaction"
      (let [id    (new-uuid)
            delta {[:account/id id] {:account/name {:before nil :after "Alice"}
                                     :account/email {:before nil :after "alice@test.com"}}}
            txn   (dl/delta->txn env delta)]
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
            txn       (dl/delta->txn env delta)]
        (is (= 1 (count txn)))
        (let [entry (first txn)]
          (is (number? (:db/id entry)))
          (is (neg? (:db/id entry)))
          (is (= real-uuid (:account/id entry)) "Must use real UUID from delta :after, not TempId")
          (is (= "Bob" (:account/name entry))))))

    (testing "handles value removal"
      (let [id    (new-uuid)
            delta {[:account/id id] {:account/email {:before "old@test.com" :after nil}}}
            txn   (dl/delta->txn env delta)]
        (is (= 1 (count txn)))
        (is (vector? (first txn)))
        (is (= :db/retract (first (first txn))))))

    (testing "ignores unchanged values"
      (let [id    (new-uuid)
            delta {[:account/id id] {:account/name {:before "Same" :after "Same"}}}
            txn   (dl/delta->txn env delta)]
        (is (empty? txn))))))

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

;; ================================================================================
;; Enum Support Tests
;; ================================================================================

(deftest enum-schema-generation
  (testing "generates correct schema for enum attributes"
    (let [schema (dl/automatic-schema :test tu/all-test-attributes)]
      ;; Enum attributes should map to :db.type/ref
      (is (= :db.type/ref (get-in schema [:account/role :db/valueType])))
      (is (= :db.type/ref (get-in schema [:account/status :db/valueType])))
      (is (= :db.type/ref (get-in schema [:account/permissions :db/valueType])))
      
      ;; Many cardinality should be preserved
      (is (= :db.cardinality/many (get-in schema [:account/permissions :db/cardinality])))))

  (testing "generates enum ident entities with unqualified keywords"
    (let [enum-txn (#'us.whitford.fulcro.rad.database-adapters.datalevin.start-databases/enumerated-values
                    [tu/account-role])]
      (is (= 3 (count enum-txn)))
      (is (contains? (set (map :db/ident enum-txn)) :account.role/admin))
      (is (contains? (set (map :db/ident enum-txn)) :account.role/user))
      (is (contains? (set (map :db/ident enum-txn)) :account.role/guest))))

  (testing "generates enum ident entities with qualified keywords"
    (let [enum-txn (#'us.whitford.fulcro.rad.database-adapters.datalevin.start-databases/enumerated-values
                    [tu/account-status])]
      (is (= 3 (count enum-txn)))
      (is (contains? (set (map :db/ident enum-txn)) :status/active))
      (is (contains? (set (map :db/ident enum-txn)) :status/inactive))
      (is (contains? (set (map :db/ident enum-txn)) :status/pending)))))

(deftest enum-save-and-query
  (testing "saves and queries enum values with unqualified keywords"
    (tu/with-test-conn [conn]
      (let [id    (new-uuid)
            _     (d/transact! conn [{:account/id   id
                                      :account/name "Alice"
                                      :account/role :account.role/admin}])
            result (d/pull (d/db conn) [:account/id :account/name {:account/role [:db/ident]}] [:account/id id])
            role   (get-in result [:account/role :db/ident])]
        (is (= id (:account/id result)))
        (is (= "Alice" (:account/name result)))
        (is (= :account.role/admin role)))))

  (testing "saves and queries enum values with qualified keywords"
    (tu/with-test-conn [conn]
      (let [id    (new-uuid)
            _     (d/transact! conn [{:account/id     id
                                      :account/name   "Bob"
                                      :account/status :status/active}])
            result (d/pull (d/db conn) [:account/id :account/name {:account/status [:db/ident]}] [:account/id id])
            status (get-in result [:account/status :db/ident])]
        (is (= id (:account/id result)))
        (is (= "Bob" (:account/name result)))
        (is (= :status/active status)))))

  (testing "saves and queries enum values with many cardinality"
    (tu/with-test-conn [conn]
      (let [id    (new-uuid)
            _     (d/transact! conn [{:account/id          id
                                      :account/name        "Charlie"
                                      :account/permissions [:account.permissions/read
                                                            :account.permissions/write]}])
            result (d/pull (d/db conn) [:account/id :account/name {:account/permissions [:db/ident]}] [:account/id id])
            perms  (set (map :db/ident (:account/permissions result)))]
        (is (= id (:account/id result)))
        (is (= "Charlie" (:account/name result)))
        (is (= 2 (count perms)))
        (is (contains? perms :account.permissions/read))
        (is (contains? perms :account.permissions/write))))))

(deftest enum-delta-save
  (testing "saves enum via delta with unqualified keywords"
    (tu/with-test-conn [conn]
      (let [id      (new-uuid)
            delta   {[:account/id id] {:account/id {:before nil :after id}
                                       :account/name {:before nil :after "Dave"}
                                       :account/role {:before nil :after :account.role/user}}}
            env     {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save)
            _       (middleware env)
            result  (d/pull (d/db conn) [:account/id :account/name {:account/role [:db/ident]}] [:account/id id])
            role   (get-in result [:account/role :db/ident])]
        (is (= id (:account/id result)))
        (is (= "Dave" (:account/name result)))
        (is (= :account.role/user role)))))

  (testing "updates enum values via delta"
    (tu/with-test-conn [conn]
      (let [id      (new-uuid)
            ;; Create with admin role
            _       (d/transact! conn [{:account/id id
                                        :account/name "Eve"
                                        :account/role :account.role/admin}])
            ;; Update to user role via delta
            delta   {[:account/id id] {:account/role {:before :account.role/admin
                                                      :after :account.role/user}}}
            env     {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save)
            _       (middleware env)
            result  (d/pull (d/db conn) [:account/id :account/name {:account/role [:db/ident]}] [:account/id id])
            role    (get-in result [:account/role :db/ident])]
        (is (= id (:account/id result)))
        (is (= "Eve" (:account/name result)))
        (is (= :account.role/user role)))))

  (testing "saves many-cardinality enums via delta"
    (tu/with-test-conn [conn]
      (let [id      (new-uuid)
            delta   {[:account/id id] {:account/id {:before nil :after id}
                                       :account/name {:before nil :after "Frank"}
                                       :account/permissions {:before nil
                                                             :after [:account.permissions/read
                                                                     :account.permissions/execute]}}}
            env     {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
                     ::dlo/connections     {:test conn}
                     ::form/params         {::form/delta delta}}
            middleware (dl/wrap-datalevin-save)
            _       (middleware env)
            result  (d/pull (d/db conn) [:account/id :account/name {:account/permissions [:db/ident]}] [:account/id id])
            perms   (set (map :db/ident (:account/permissions result)))]
        (is (= id (:account/id result)))
        (is (= "Frank" (:account/name result)))
        (is (= 2 (count perms)))
        (is (contains? perms :account.permissions/read))
        (is (contains? perms :account.permissions/execute))))))

(deftest enum-resolver-returns-keywords
  (testing "resolver returns enum values as keywords, not entity maps"
    (tu/with-test-conn [conn]
      (let [id        (new-uuid)
            _         (d/transact! conn [{:account/id   id
                                          :account/name "Grace"
                                          :account/role :account.role/admin}])
            resolvers (dl/generate-resolvers tu/all-test-attributes :test)
            account-resolver (first (filter #(= :account/id (first (::pco/input (:config %))))
                                            resolvers))
            env       (assoc (tu/mock-resolver-env {:test conn})
                             ::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes))
            results   ((:resolve account-resolver) env [{:account/id id}])
            result    (first results)]
        (is (some? result) (str "Result should not be nil. Got results: " (pr-str results)))
        (is (= id (:account/id result)) (str "Expected account/id to be " id " but got: " (pr-str result)))
        (is (= "Grace" (:account/name result)))
        ;; This should be a keyword, not a map like {:db/id 18}
        (is (keyword? (:account/role result)) 
            (str "account/role should be a keyword, but got: " (pr-str (:account/role result))))
        (is (= :account.role/admin (:account/role result))))))

  (testing "resolver returns many-cardinality enums as keywords"
    (tu/with-test-conn [conn]
      (let [id        (new-uuid)
            _         (d/transact! conn [{:account/id          id
                                          :account/name        "Helen"
                                          :account/permissions [:account.permissions/read
                                                                :account.permissions/write]}])
            resolvers (dl/generate-resolvers tu/all-test-attributes :test)
            account-resolver (first (filter #(= :account/id (first (::pco/input (:config %))))
                                            resolvers))
            env       (assoc (tu/mock-resolver-env {:test conn})
                             ::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes))
            result    (first ((:resolve account-resolver) env [{:account/id id}]))
            perms     (:account/permissions result)]
        (is (some? result))
        (is (= id (:account/id result)))
        (is (= "Helen" (:account/name result)))
        ;; All permissions should be keywords, not maps
        (is (every? keyword? perms)
            (str "account/permissions should be keywords, but got: " (pr-str perms)))
        (is (= #{:account.permissions/read :account.permissions/write} (set perms)))))))

;; ================================================================================
;; Native ID Tests
;; ================================================================================

(deftest native-id-schema-generation
  (testing "native-id attributes are skipped in schema generation"
    (let [schema (dl/automatic-schema :native-test tu/native-id-attributes)]
      ;; Native ID attribute should NOT be in the schema (uses built-in :db/id)
      (is (not (contains? schema :person/id))
          "Native ID attribute should not be in schema")
      ;; Other attributes should be present
      (is (contains? schema :person/name))
      (is (contains? schema :person/email))
      (is (contains? schema :person/age))))

  (testing "native-id? helper identifies native-id attributes"
    (is (true? (dl/native-id? tu/person-id)))
    (is (false? (dl/native-id? tu/person-name)))
    (is (false? (dl/native-id? tu/account-id)))))

(deftest native-id-query-conversion
  (testing "pathom-query->datalevin-query replaces native-id keys with :db/id"
    (let [query [:person/id :person/name :person/email]
          converted (dl/pathom-query->datalevin-query tu/native-id-attributes query)]
      (is (= [:db/id :person/name :person/email] converted))))

  (testing "pathom-query->datalevin-query leaves non-native keys unchanged"
    (let [query [:account/id :account/name :account/email]
          converted (dl/pathom-query->datalevin-query tu/all-test-attributes query)]
      (is (= [:account/id :account/name :account/email] converted)))))

(deftest native-id-save-and-query
  (testing "saves and queries entities with native IDs"
    (tu/with-test-conn-attrs [conn tu/native-id-attributes]
      ;; Insert a person entity (Datalevin assigns :db/id)
      (let [tx-result (d/transact! conn [{:person/name "Alice"
                                          :person/email "alice@test.com"
                                          :person/age 30}])
            ;; Get the assigned entity ID from tx-result
            db (:db-after tx-result)
            ;; Query to find the entity
            result (first (d/q '[:find ?e ?name
                                 :where [?e :person/name ?name]]
                               db))
            eid (first result)]
        (is (some? eid) "Entity should have been created")
        (is (pos-int? eid) "Entity ID should be a positive integer")
        
        ;; Pull the entity
        (let [pulled (d/pull db [:db/id :person/name :person/email :person/age] eid)]
          (is (= "Alice" (:person/name pulled)))
          (is (= "alice@test.com" (:person/email pulled)))
          (is (= 30 (:person/age pulled)))
          (is (= eid (:db/id pulled))))))))

(deftest native-id-resolver-generation
  (testing "generates resolvers for native-id entities"
    (let [resolvers (dl/generate-resolvers tu/native-id-attributes :native-test)]
      ;; Should have an ID resolver and an all-IDs resolver
      (is (>= (count resolvers) 1) "Should generate at least one resolver")
      ;; Find the ID resolver
      (let [id-resolver (first (filter #(= :person/id (first (::pco/input (:config %))))
                                       resolvers))]
        (is (some? id-resolver) "Should have an ID resolver for :person/id"))))

  (testing "resolver correctly maps :db/id back to identity key"
    (tu/with-test-conn-attrs [conn tu/native-id-attributes]
      ;; Insert a person
      (let [tx-result (d/transact! conn [{:person/name "Bob"
                                          :person/email "bob@test.com"
                                          :person/age 25}])
            db (:db-after tx-result)
            ;; Find the entity ID
            eid (ffirst (d/q '[:find ?e :where [?e :person/name "Bob"]] db))
            ;; Generate resolvers and run query
            resolvers (dl/generate-resolvers tu/native-id-attributes :native-test)
            person-resolver (first (filter #(= :person/id (first (::pco/input (:config %))))
                                           resolvers))
            env (assoc (tu/mock-resolver-env {:native-test conn})
                       ::attr/key->attribute (tu/key->attribute-map tu/native-id-attributes))
            ;; Query using the native entity ID
            result (first ((:resolve person-resolver) env [{:person/id eid}]))]
        (is (some? result) "Resolver should return a result")
        ;; The result should have :person/id mapped from :db/id
        (is (= eid (:person/id result)) 
            (str "person/id should be " eid " but got: " (pr-str result)))
        (is (= "Bob" (:person/name result)))
        (is (= "bob@test.com" (:person/email result)))))))

(deftest native-id-delta-conversion
  (testing "delta->txn handles native-id attributes correctly"
    (let [env {::attr/key->attribute (tu/key->attribute-map tu/native-id-attributes)}
          ;; Simulating an update to an existing entity with native ID (eid 42)
          delta {[:person/id 42] {:person/name {:before "Old Name" :after "New Name"}
                                  :person/age {:before 25 :after 26}}}
          txn (dl/delta->txn env delta)]
      ;; Should use the raw entity ID, not a lookup ref
      (is (some? txn))
      (is (= 1 (count txn)) "Should have one transaction entry")
      (let [entry (first txn)]
        (is (map? entry))
        ;; The :db/id should be the raw entity ID (42), not [:person/id 42]
        (is (= 42 (:db/id entry)) 
            (str "Native ID should use raw entity ID, got: " (pr-str (:db/id entry))))
        (is (= "New Name" (:person/name entry)))
        (is (= 26 (:person/age entry)))
        ;; Should NOT have :person/id in the entity map (it's :db/id)
        (is (not (contains? entry :person/id))
            "Native ID entities should not have the identity attribute in the map")))))
