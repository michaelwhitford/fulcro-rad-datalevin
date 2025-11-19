(ns us.whitford.fulcro.rad.database-adapters.datalevin-rad-integration-test
  "Tests that simulate the exact RAD form save integration to reproduce the ClassCastException."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

;; ================================================================================
;; Test Attributes - matching typical RAD setup
;; ================================================================================

(def account-id
  {::attr/qualified-key :account/id
   ::attr/type          :uuid
   ::attr/schema        :production
   ::attr/identity?     true})

(def account-name
  {::attr/qualified-key :account/name
   ::attr/type          :string
   ::attr/schema        :production
   ::attr/identities    #{:account/id}})

(def account-email
  {::attr/qualified-key :account/email
   ::attr/type          :string
   ::attr/schema        :production
   ::attr/identities    #{:account/id}})

(def account-active
  {::attr/qualified-key :account/active?
   ::attr/type          :boolean
   ::attr/schema        :production
   ::attr/identities    #{:account/id}})

(def test-attributes
  [account-id account-name account-email account-active])

;; ================================================================================
;; Simulating RAD's Middleware Composition
;; ================================================================================

(defn base-save-middleware
  "This simulates RAD's base save middleware that performs validations.
   It returns what RAD expects: a map with form data."
  [env]
  (let [params (::form/params env)
        id-key (::form/master-pk env)]
    {::form/id [id-key (get params id-key)]
     ::form/complete? true}))

(defn wrap-validation
  "Simulates another middleware layer that RAD might have."
  [handler]
  (fn [env]
    ;; Do some validation, then call the wrapped handler
    (let [result (handler env)]
      (assoc result ::form/validated? true))))

(defn apply-rad-middleware-stack
  "This function simulates how RAD composes middleware.
   The order matters: outer middleware wraps inner middleware."
  [connection]
  (-> base-save-middleware
      ((dl/wrap-datalevin-save {:default-schema :production}))
      (wrap-validation)))

;; ================================================================================
;; Tests
;; ================================================================================

(deftest test-rad-middleware-composition
  (testing "RAD middleware stack returns a map, not a function"
    (let [path   (str "/tmp/datalevin-rad-test-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          
          ;; This is what RAD form sends
          params {:account/id tid
                  :account/name "RAD User"
                  :account/email "rad@example.com"
                  :account/active? true}
          
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "RAD User"}
                   :account/email {:before nil :after "rad@example.com"}
                   :account/active? {:before nil :after true}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          env    {::attr/key->attribute key->attr
                  ::dlo/connections     {:production conn}
                  ::form/delta          delta
                  ::form/params         params
                  ::form/master-pk      :account/id}
          
          ;; Get the fully composed handler
          handler (apply-rad-middleware-stack conn)
          
          ;; Call it
          result  (handler env)]
      
      ;; Critical assertions
      (is (map? result)
          (str "Result MUST be a map but got: " (type result) " - " (pr-str result)))
      
      (is (not (fn? result))
          "Result must NOT be a function")
      
      ;; Check RAD-specific keys are preserved
      (is (::form/complete? result)
          "Should have form complete flag from base middleware")
      
      (is (::form/validated? result)
          "Should have validated flag from validation middleware")
      
      (is (contains? result :tempids)
          "Should have tempids from datalevin middleware")
      
      (is (= real-id (get (:tempids result) tid))
          "Should correctly map tempid to real ID")
      
      ;; Verify database state
      (let [db     (d/db conn)
            entity (d/pull db '[*] [:account/id real-id])]
        (is (= real-id (:account/id entity)))
        (is (= "RAD User" (:account/name entity)))
        (is (= "rad@example.com" (:account/email entity)))
        (is (true? (:account/active? entity))))
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest test-pathom3-integration
  (testing "Integration with Pathom3 mutation"
    (let [path   (str "/tmp/datalevin-pathom-test-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          
          ;; Simulate what a Pathom3 mutation receives
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "Pathom User"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          ;; This is what the pathom env looks like
          pathom-env {::attr/key->attribute key->attr
                      ::dlo/connections     {:production conn}
                      ::form/delta          delta}
          
          ;; Simulating the middleware call
          middleware (dl/wrap-datalevin-save {:default-schema :production})
          wrapped-handler (middleware (fn [env] {}))  ;; Minimal base handler
          
          result (wrapped-handler pathom-env)]
      
      ;; This is the critical check that was failing
      (is (map? result)
          "Pathom3 mutations expect a map result")
      
      (is (not (fn? result))
          "Result cannot be a function - causes ClassCastException")
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest test-without-connections-in-env
  (testing "Gracefully handles missing connections"
    (let [delta  {[:account/id (new-uuid)]
                  {:account/name {:before nil :after "Test"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          ;; Missing ::dlo/connections!
          pathom-env {::attr/key->attribute key->attr
                      ::form/delta          delta}
          
          middleware (dl/wrap-datalevin-save {:default-schema :production})
          wrapped-handler (middleware (fn [env] {}))]
      
      ;; Should throw but not return a function
      (is (thrown? Exception
                   (wrapped-handler pathom-env))))))

(deftest test-empty-env-returns-map
  (testing "Even with minimal env, returns a map"
    (let [middleware (dl/wrap-datalevin-save)
          wrapped-handler (middleware (fn [env] {:base :result}))
          ;; Minimal env with no delta
          result (wrapped-handler {})]
      
      (is (map? result))
      (is (not (fn? result)))
      (is (= :result (:base result))))))

(deftest test-result-is-serializable
  (testing "Result can be serialized (no closures/functions)"
    (let [path   (str "/tmp/datalevin-serial-test-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "Serial Test"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          env    {::attr/key->attribute key->attr
                  ::dlo/connections     {:production conn}
                  ::form/delta          delta}
          
          middleware (dl/wrap-datalevin-save {:default-schema :production})
          wrapped-handler (middleware (fn [e] {:original :data}))
          
          result (wrapped-handler env)]
      
      ;; Result should be serializable (printable/readable)
      (is (map? result))
      
      ;; Try to pr-str it (would fail if it contains functions)
      (let [serialized (pr-str result)
            _          (is (string? serialized))]
        ;; Should not contain function references
        (is (not (re-find #"fn__\d+" serialized))
            "Result should not contain function references"))
      
      ;; All values should be data, not functions
      (is (every? #(not (fn? %)) (vals result))
          "No value in result should be a function")
      
      ;; Nested values should also be data
      (when (:tempids result)
        (is (map? (:tempids result)))
        (is (every? #(not (fn? %)) (vals (:tempids result)))))
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))
