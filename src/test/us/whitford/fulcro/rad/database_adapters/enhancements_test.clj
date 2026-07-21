(ns us.whitford.fulcro.rad.database-adapters.enhancements-test
  "Tests for adapter parity enhancements:
   - fix-numerics coercion in save (PLAN #9)
   - wrap-resolve on identity resolvers (PLAN #5)
   - schema-problems / verify-schema! (PLAN #8)"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; PLAN #9: fix-numerics coercion in save
;; ================================================================================

(def account-score
  "A :long-typed non-identity attribute for numeric coercion tests."
  {::attr/qualified-key :account/score
   ::attr/type          :long
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def numeric-attributes
  (conj tu/all-test-attributes account-score))

(defn- entity-map
  "Return the single entity map produced by delta->txn (ignores retract ops)."
  [txn]
  (first (filter map? txn)))

(deftest fix-numerics-coerces-double-attr-from-integer
  (testing "an integer written to a :double attribute is stored as a double"
    (let [id    (new-uuid)
          env   {::attr/key->attribute (tu/key->attribute-map numeric-attributes)}
          delta {[:account/id id] {:account/id      {:before nil :after id}
                                   :account/balance {:before nil :after 100}}}
          ent   (entity-map (dl/delta->txn env delta))]
      (is (instance? Double (:account/balance ent))
          "integer 100 is coerced to a Double")
      (is (= 100.0 (:account/balance ent))))))

(deftest fix-numerics-coerces-long-attr-from-double
  (testing "a double written to a :long attribute is stored as a long"
    (let [id    (new-uuid)
          env   {::attr/key->attribute (tu/key->attribute-map numeric-attributes)}
          delta {[:account/id id] {:account/id    {:before nil :after id}
                                   :account/score {:before nil :after 42.0}}}
          ent   (entity-map (dl/delta->txn env delta))]
      (is (instance? Long (:account/score ent))
          "double 42.0 is coerced to a Long")
      (is (= 42 (:account/score ent))))))

(deftest fix-numerics-leaves-non-numeric-untouched
  (testing "non-numeric attribute values pass through unchanged"
    (let [id    (new-uuid)
          env   {::attr/key->attribute (tu/key->attribute-map numeric-attributes)}
          delta {[:account/id id] {:account/id   {:before nil :after id}
                                   :account/name {:before nil :after "Alice"}}}
          ent   (entity-map (dl/delta->txn env delta))]
      (is (= "Alice" (:account/name ent))))))

(deftest fix-numerics-round-trips-through-save
  (testing "an integer saved to a :double attribute persists as a double"
    (tu/with-test-conn-attrs [conn numeric-attributes]
      (let [id  (new-uuid)
            env {::attr/key->attribute (tu/key->attribute-map numeric-attributes)
                 ::dlo/connections     {:test conn}}]
        (dl/save-form! env {:com.fulcrologic.rad.form/delta
                            {[:account/id id] {:account/id      {:before nil :after id}
                                               :account/balance {:before nil :after 250}}}})
        (let [stored (:account/balance
                      (d/pull (d/db conn) [:account/balance] [:account/id id]))]
          (is (instance? Double stored))
          (is (= 250.0 stored)))))))

;; ================================================================================
;; PLAN #5: wrap-resolve on identity resolvers
;; ================================================================================

(def ^:private wrap-calls (atom 0))

(def wrapped-id
  "Identity attribute with a ::dlo/wrap-resolve that records invocation (pre)
   and upper-cases the resolved name (post)."
  {::attr/qualified-key :wrapped/id
   ::attr/type          :uuid
   ::attr/schema        :test
   ::attr/identity?     true
   ::dlo/wrap-resolve   (fn [resolve]
                          (fn [env input]
                            (swap! wrap-calls inc)
                            (mapv (fn [row]
                                    (cond-> row
                                      (:wrapped/name row)
                                      (update :wrapped/name str/upper-case)))
                                  (resolve env input))))})

(def wrapped-name
  {::attr/qualified-key :wrapped/name
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:wrapped/id}})

(def wrapped-attributes [wrapped-id wrapped-name])

(defn- id-resolver-for
  "Find the generated id-resolver whose input is [id-key]."
  [resolvers id-key]
  (first (filter #(= id-key (first (::pco/input (:config %)))) resolvers)))

(deftest wrap-resolve-applied-to-id-resolver
  (testing "::dlo/wrap-resolve wraps the identity resolver (pre + post processing)"
    (tu/with-test-conn-attrs [conn wrapped-attributes]
      (let [id (new-uuid)]
        (d/transact! conn [{:wrapped/id id :wrapped/name "alice"}])
        (reset! wrap-calls 0)
        (let [resolvers (dl/generate-resolvers wrapped-attributes :test)
              r         (id-resolver-for resolvers :wrapped/id)
              env       (assoc (tu/mock-resolver-env {:test conn})
                               ::attr/key->attribute (tu/key->attribute-map wrapped-attributes))
              result    ((:resolve r) env [{:wrapped/id id}])]
          (is (some? r) "id-resolver for :wrapped/id exists")
          (is (= 1 @wrap-calls) "the wrapper's pre-processing ran exactly once")
          (is (= "ALICE" (:wrapped/name (first result)))
              "the wrapper's post-processing upper-cased the resolved name"))))))

(deftest without-wrap-resolve-result-is-unmodified
  (testing "an identity attribute without wrap-resolve returns the raw resolved value"
    (tu/with-test-conn-attrs [conn wrapped-attributes]
      (let [id (new-uuid)]
        (d/transact! conn [{:wrapped/id id :wrapped/name "bob"}])
        ;; account/id has no wrap-resolve; use it as the control on the same conn
        (let [ctrl-attrs [(assoc wrapped-id ::dlo/wrap-resolve nil) wrapped-name]
              resolvers  (dl/generate-resolvers ctrl-attrs :test)
              r          (id-resolver-for resolvers :wrapped/id)
              env        (assoc (tu/mock-resolver-env {:test conn})
                                ::attr/key->attribute (tu/key->attribute-map ctrl-attrs))
              result     ((:resolve r) env [{:wrapped/id id}])]
          (is (= "bob" (:wrapped/name (first result)))
              "no wrapper means the name is returned unchanged"))))))

;; ================================================================================
;; PLAN #8: schema-problems / verify-schema!
;; ================================================================================

(def balance-as-long
  "A conflicting redeclaration of :account/balance as :long instead of :double."
  (assoc tu/account-balance ::attr/type :long))

(def nickname-attr
  "An attribute not present in the seeded database schema."
  {::attr/qualified-key :account/nickname
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(defn- without-balance []
  (remove #(= :account/balance (::attr/qualified-key %)) tu/all-test-attributes))

(deftest schema-verify-clean-when-matching
  (testing "schema-problems is empty and verify-schema! returns true for a matching schema"
    (tu/with-test-conn [conn]
      (is (empty? (dl/schema-problems conn :test tu/all-test-attributes)))
      (is (true? (dl/verify-schema! conn :test tu/all-test-attributes))))))

(deftest schema-verify-reports-value-type-mismatch
  (testing "a value-type mismatch is reported and verify-schema! throws"
    (tu/with-test-conn [conn]
      (let [attrs    (conj (vec (without-balance)) balance-as-long)
            problems (dl/schema-problems conn :test attrs)
            balance  (first (filter #(= :account/balance (:attribute %)) problems))]
        (is (= :mismatch (:problem balance)))
        (is (= :db/valueType (:key balance)))
        (is (= :db.type/long (:expected balance)))
        (is (= :db.type/double (:actual balance)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (dl/verify-schema! conn :test attrs)))))))

(deftest schema-verify-reports-missing-attribute
  (testing "an expected attribute absent from the db schema is reported as :missing"
    (tu/with-test-conn [conn]
      (let [attrs    (conj tu/all-test-attributes nickname-attr)
            problems (dl/schema-problems conn :test attrs)
            nick     (first (filter #(= :account/nickname (:attribute %)) problems))]
        (is (= :missing (:problem nick)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (dl/verify-schema! conn :test attrs)))))))
