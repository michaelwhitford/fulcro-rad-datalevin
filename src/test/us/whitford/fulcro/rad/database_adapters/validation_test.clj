(ns us.whitford.fulcro.rad.database-adapters.validation-test
  "Tests for database-side validation and post-conditions:
   - Attribute predicates via native :db.attr/preds (Feature A)
   - Transaction post-conditions via :db/ensure + ::dlo/raw-txn (Feature B)
   - Save exception propagation (Fix)

   These exercise Datalevin 1.0.0 correctness features surfaced through the
   RAD adapter."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; Predicate vars (referenced by qualified symbol from :db.attr/preds)
;; ================================================================================

(defn valid-email?
  "Attribute predicate: value must look like an email. Must return strictly true."
  [v]
  (boolean (and (string? v) (re-matches #".+@.+\..+" v))))

(defn balance-non-negative?
  "`:db/ensure` predicate: the account's balance in db-after must be >= 0."
  [db eid]
  (let [bal (:account/balance (d/pull db [:account/balance] eid))]
    (or (nil? bal) (<= 0 bal))))

;; ================================================================================
;; Test attributes with :db.attr/preds
;; ================================================================================

(def account-email-checked
  "Email attribute with a database-side predicate via native :db.attr/preds."
  {::attr/qualified-key   :account/email
   ::attr/type            :string
   ::attr/schema          :test
   ::attr/identities      #{:account/id}
   ::dlo/attribute-schema {:db.attr/preds `valid-email?}})

(def preds-attributes
  [tu/account-id tu/account-name account-email-checked])

;; ================================================================================
;; Feature A: attribute predicates via native :db.attr/preds
;; ================================================================================

(deftest attr-preds-flow-into-schema
  (testing ":db.attr/preds supplied via ::dlo/attribute-schema is merged into the generated schema"
    (let [schema (dl/automatic-schema :test preds-attributes)]
      (is (= `valid-email?
             (get-in schema [:account/email :db.attr/preds]))
          "the qualified-symbol predicate survives schema generation")
      (is (= :db.type/string (get-in schema [:account/email :db/valueType]))
          "the base schema is still generated alongside the predicate"))))

(deftest attr-preds-enforced-on-write
  (testing "a value satisfying :db.attr/preds transacts successfully"
    (tu/with-test-conn-attrs [conn preds-attributes]
      (let [id (com.fulcrologic.rad.ids/new-uuid)]
        (is (some? (d/transact! conn [{:account/id id :account/email "alice@example.com"}]))
            "valid email is accepted")
        (is (= "alice@example.com"
               (:account/email (d/pull (d/db conn) [:account/email] [:account/id id])))))))

  (testing "a value violating :db.attr/preds aborts the transaction"
    (tu/with-test-conn-attrs [conn preds-attributes]
      (let [id (new-uuid)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (d/transact! conn [{:account/id id :account/email "not-an-email"}]))
            "invalid email is rejected by the database")))))

;; ================================================================================
;; Feature B: transaction post-conditions via :db/ensure + ::dlo/raw-txn
;; ================================================================================

(defn- save-env
  "Build a minimal save env for save-form! against the given connection."
  [conn]
  {::attr/key->attribute (tu/key->attribute-map tu/all-test-attributes)
   ::dlo/connections     {:test conn}})

(deftest append-to-raw-txn-accumulates-forms
  (testing "append-to-raw-txn adds native forms under ::dlo/raw-txn, additively"
    (let [env  (-> {}
                   (dl/append-to-raw-txn [[:db/ensure `balance-non-negative? "a"]])
                   (dl/append-to-raw-txn [[:db/ensure `balance-non-negative? "b"]]))
          txn  (get env dlo/raw-txn)]
      (is (= [[:db/ensure `balance-non-negative? "a"]
              [:db/ensure `balance-non-negative? "b"]]
             txn)
          "forms accumulate in order across multiple calls"))))

(deftest ensure-post-condition-passes-for-valid-save
  (testing ":db/ensure post-condition allows a save that satisfies it"
    (tu/with-test-conn [conn]
      (let [id (new-uuid)]
        (d/transact! conn [{:account/id id :account/balance 100.0}])
        (let [env   (dl/append-to-raw-txn
                     (save-env conn)
                     [[:db/ensure `balance-non-negative? [:account/id id]]])
              delta {[:account/id id] {:account/balance {:before 100.0 :after 50.0}}}]
          (is (map? (dl/save-form! env {::form/delta delta}))
              "valid save returns a result map")
          (is (= 50.0 (:account/balance
                       (d/pull (d/db conn) [:account/balance] [:account/id id])))
              "the new balance is persisted"))))))

(deftest ensure-post-condition-aborts-invalid-save
  (testing ":db/ensure post-condition aborts a save that violates it"
    (tu/with-test-conn [conn]
      (let [id (new-uuid)]
        (d/transact! conn [{:account/id id :account/balance 100.0}])
        (let [env   (dl/append-to-raw-txn
                     (save-env conn)
                     [[:db/ensure `balance-non-negative? [:account/id id]]])
              delta {[:account/id id] {:account/balance {:before 100.0 :after -50.0}}}]
          (is (thrown? clojure.lang.ExceptionInfo
                       (dl/save-form! env {::form/delta delta}))
              "save that would violate the post-condition throws")
          (is (= 100.0 (:account/balance
                        (d/pull (d/db conn) [:account/balance] [:account/id id])))
              "the balance is unchanged because the transaction aborted"))))))

;; ================================================================================
;; Fix: save-form! propagates transaction failures (no longer swallows them)
;; ================================================================================

(deftest save-form-propagates-transaction-failure
  (testing "a failing save throws ex-info with context instead of returning empty tempids"
    (tu/with-test-conn-attrs [conn preds-attributes]
      (let [id    (new-uuid)
            env   {::attr/key->attribute (tu/key->attribute-map preds-attributes)
                   ::dlo/connections     {:test conn}}
            delta {[:account/id id] {:account/id    {:before nil :after id}
                                     :account/email {:before nil :after "not-an-email"}}}
            ex    (try (dl/save-form! env {::form/delta delta}) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "save-form! throws on a failed transaction")
        (is (= :test (:schema (ex-data ex)))
            "the thrown ex-info carries the schema context")
        (is (contains? (ex-data ex) :txn-data)
            "the thrown ex-info carries the attempted txn-data")
        (is (empty? (d/q '[:find ?e :where [?e :account/email _]] (d/db conn)))
            "nothing was persisted because the transaction aborted")))))
