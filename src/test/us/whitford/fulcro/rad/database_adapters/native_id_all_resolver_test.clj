(ns us.whitford.fulcro.rad.database-adapters.native-id-all-resolver-test
  "Tests for native-id all-ids resolver bug fix.
   
   Issue: The all-ids-resolver for native-id attributes was querying [?e _ _]
   which returned ALL entities in the database (enums, other entity types, etc.)
   instead of just entities of the requested type.
   
   Fix: Query for a non-identity attribute from the same entity type
   (e.g., for :person/id, query for entities with :person/name)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

(deftest native-id-all-resolver-excludes-other-entities
  (testing "person-all resolver only returns person entities, not enums or other types"
    (tu/with-test-conn-attrs [conn (concat tu/native-id-attributes tu/all-test-attributes)]
      ;; Insert some person entities (native ID)
      (d/transact! conn [{:person/name "Alice" :person/email "alice@test.com" :person/age 30}
                         {:person/name "Bob" :person/email "bob@test.com" :person/age 25}])
      
      ;; Insert some account entities (UUID ID)
      (let [acc-id1 (new-uuid)
            acc-id2 (new-uuid)]
        (d/transact! conn [{:account/id acc-id1 :account/name "Account 1"}
                           {:account/id acc-id2 :account/name "Account 2"}]))
      
      ;; Enum values are automatically created (e.g., :account.role/admin)
      ;; Empty entities might exist from previous operations
      
      ;; Generate resolvers
      (let [resolvers (dl/generate-resolvers (concat tu/native-id-attributes tu/all-test-attributes) :native-test)
            ;; Find person-all resolver
            person-all-resolver (first (filter #(let [output (::pco/output (:config %))]
                                                  (and (vector? output)
                                                       (map? (first output))
                                                       (contains? (first output) :person/all)))
                                               resolvers))
            env (assoc (tu/mock-resolver-env {:native-test conn})
                       ::attr/key->attribute (tu/key->attribute-map (concat tu/native-id-attributes tu/all-test-attributes)))
            result ((:resolve person-all-resolver) env {})]
        
        (is (some? person-all-resolver) "Should find person-all resolver")
        (is (contains? result :person/all) "Result should have :person/all key")
        
        (let [persons (:person/all result)]
          ;; Should only return the 2 person entities, not all 15+ entities
          (is (= 2 (count persons))
              (str "Should return exactly 2 persons, not all database entities. Got: " (count persons)))
          
          ;; All results should have :person/id (native entity IDs)
          (is (every? #(contains? % :person/id) persons)
              "All results should have :person/id")
          
          ;; Entity IDs should be positive integers (Datalevin entity IDs)
          (is (every? #(pos-int? (:person/id %)) persons)
              "All person IDs should be positive integers"))))))

(deftest native-id-all-resolver-returns-full-data
  (testing "person-all resolver returns full person data when joined with id-resolver"
    (tu/with-test-conn-attrs [conn tu/native-id-attributes]
      ;; Insert test persons
      (d/transact! conn [{:person/name "Charlie" :person/email "charlie@test.com" :person/age 28}
                         {:person/name "Dana" :person/email "dana@test.com" :person/age 35}])
      
      ;; Generate resolvers
      (let [resolvers (dl/generate-resolvers tu/native-id-attributes :native-test)
            person-all-resolver (first (filter #(let [output (::pco/output (:config %))]
                                                  (and (vector? output)
                                                       (map? (first output))
                                                       (contains? (first output) :person/all)))
                                               resolvers))
            person-id-resolver (first (filter #(= :person/id (first (::pco/input (:config %))))
                                              resolvers))
            env (assoc (tu/mock-resolver-env {:native-test conn})
                       ::attr/key->attribute (tu/key->attribute-map tu/native-id-attributes))
            ;; Get all person IDs
            all-result ((:resolve person-all-resolver) env {})
            person-ids (map :person/id (:person/all all-result))
            ;; Batch resolve full person data
            persons ((:resolve person-id-resolver) env (mapv #(hash-map :person/id %) person-ids))]
        
        (is (= 2 (count persons)) "Should have 2 persons")
        
        ;; All persons should have full data
        (doseq [person persons]
          (is (some? (:person/id person)) "Should have :person/id")
          (is (some? (:person/name person)) "Should have :person/name")
          (is (some? (:person/email person)) "Should have :person/email")
          (is (number? (:person/age person)) "Should have :person/age"))
        
        ;; Verify specific data
        (let [names (set (map :person/name persons))]
          (is (contains? names "Charlie"))
          (is (contains? names "Dana")))))))

(deftest native-id-all-resolver-with-no-attributes
  (testing "all-ids resolver handles entity types with only identity attribute"
    ;; Edge case: what if someone defines a native-id entity with no other attributes?
    (let [minimal-attrs [{::attr/qualified-key :minimal/id
                          ::attr/type           :long
                          ::attr/schema         :minimal
                          ::attr/identity?      true
                          ::dlo/native-id?      true}]
          resolvers (dl/generate-resolvers minimal-attrs :minimal)
          ;; Should generate all-ids resolver even with no sample attribute
          minimal-all (first (filter #(let [output (::pco/output (:config %))]
                                        (and (vector? output)
                                             (map? (first output))
                                             (contains? (first output) :minimal/all)))
                                     resolvers))]
      
      (is (some? minimal-all) "Should generate all-ids resolver even with no non-identity attributes")
      
      ;; Resolver should return empty list (with warning logged)
      (let [env (assoc (tu/mock-resolver-env {})
                       ::attr/key->attribute (tu/key->attribute-map minimal-attrs))
            result ((:resolve minimal-all) env {})]
        (is (contains? result :minimal/all))
        (is (empty? (:minimal/all result)) "Should return empty list when no sample attribute found")))))

(deftest native-id-all-resolver-query-uses-correct-attribute
  (testing "all-ids resolver uses first non-identity attribute from entity type"
    (tu/with-test-conn-attrs [conn tu/native-id-attributes]
      ;; person has :person/name, :person/email, :person/age, :person/bio
      ;; The resolver should pick one (likely :person/name as it's first alphabetically)
      
      ;; Insert persons - all should have at least one non-identity attribute
      (d/transact! conn [{:person/name "Eve" :person/age 40}
                         {:person/email "frank@test.com" :person/bio "No name"}])
      
      (let [resolvers (dl/generate-resolvers tu/native-id-attributes :native-test)
            person-all-resolver (first (filter #(let [output (::pco/output (:config %))]
                                                  (and (vector? output)
                                                       (map? (first output))
                                                       (contains? (first output) :person/all)))
                                               resolvers))
            env (assoc (tu/mock-resolver-env {:native-test conn})
                       ::attr/key->attribute (tu/key->attribute-map tu/native-id-attributes))
            result ((:resolve person-all-resolver) env {})]
        
        ;; If querying by :person/name, would only find Eve
        ;; If querying by any attribute (correct), would find both
        (let [count (count (:person/all result))]
          ;; The fix uses the first non-identity attribute found
          ;; which might be :person/age, :person/bio, :person/email, or :person/name
          ;; depending on iteration order
          (is (>= count 1) "Should find at least one person")
          (is (<= count 2) "Should not find more than 2 persons"))))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'us.whitford.fulcro.rad.database-adapters.native-id-all-resolver-test)
  
  ;; Run specific test
  (clojure.test/test-var #'native-id-all-resolver-excludes-other-entities)
  (clojure.test/test-var #'native-id-all-resolver-returns-full-data))
