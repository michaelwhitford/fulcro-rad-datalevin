(ns us.whitford.fulcro.rad.database-adapters.datalevin-incorrect-usage-test
  "Tests that demonstrate INCORRECT usage patterns that lead to ClassCastException.
   These tests are meant to FAIL to show common mistakes."
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
;; Test Attributes
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

(def test-attributes [account-id account-name])

;; ================================================================================
;; WRONG Usage Patterns
;; ================================================================================

(deftest incorrect-usage-missing-double-call
  (testing "COMMON MISTAKE: Calling wrap-datalevin-save with only ONE call instead of TWO"
    (let [path   (str "/tmp/datalevin-incorrect-test-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "Test"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env    {::attr/key->attribute key->attr
                  ::dlo/connections     {:production conn}
                  ::form/delta          delta}
          
          ;; WRONG: Only one call to wrap-datalevin-save
          ;; This returns a middleware wrapper function, not the final handler
          middleware-wrapper (dl/wrap-datalevin-save {:default-schema :production})
          
          ;; WRONG: Passing env directly to the wrapper
          ;; This will return a function, not a result map!
          result (try
                   (middleware-wrapper env)
                   (catch ClassCastException e
                     ::caught-error))]
      
      ;; This demonstrates the error: result is a FUNCTION, not a map
      (is (fn? result)
          "WRONG USAGE: Result is a function, which will cause ClassCastException later")
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest incorrect-usage-pathom-integration
  (testing "MISTAKE: Not properly integrating with form save middleware stack"
    (let [path   (str "/tmp/datalevin-pathom-incorrect-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "Test"}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          env    {::attr/key->attribute key->attr
                  ::dlo/connections     {:production conn}
                  ::form/delta          delta}]
      
      ;; CORRECT usage for comparison:
      (let [correct-middleware (dl/wrap-datalevin-save {:default-schema :production})
            correct-handler    (correct-middleware (fn [e] {}))
            correct-result     (correct-handler env)]
        
        (is (map? correct-result)
            "CORRECT: Calling middleware(base-handler)(env) returns a map"))
      
      ;; WRONG usage - what causes the error:
      (let [wrong-wrapper (dl/wrap-datalevin-save {:default-schema :production})
            wrong-result  (wrong-wrapper env)]  ;; Missing the base-handler step!
        
        (is (fn? wrong-result)
            "WRONG: Calling middleware(env) directly returns a function!")
        
        ;; If you try to merge this wrong_result, you get IllegalArgumentException:
        (is (thrown? IllegalArgumentException
                     (merge {} wrong-result))
            "Trying to merge with the function throws IllegalArgumentException"))
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest correct-vs-incorrect-usage-examples
  (testing "Side-by-side comparison of correct vs incorrect usage"
    (let [base-handler (fn [env] {:base :result})]
      
      ;; === CORRECT USAGE ===
      (testing "CORRECT: Two-step call"
        (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :production})
              handler       (middleware-fn base-handler)  ;; Step 1: wrap the base handler
              result        (handler {})]                  ;; Step 2: call with env
          
          (is (map? result) "Result is a map")
          (is (= :result (:base result)) "Contains base handler data")))
      
      ;; === INCORRECT USAGE ===
      (testing "INCORRECT: Direct call"
        (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :production})
              bad-result    (middleware-fn {})]  ;; WRONG: passing env instead of handler
          
          (is (fn? bad-result)
              "Result is a function (ERROR!)")
          
          (is (thrown? ClassCastException
                       (assoc bad-result :new-key :value))
              "Cannot use as a map - throws ClassCastException")))
      
      ;; === ANOTHER INCORRECT USAGE ===
      (testing "INCORRECT: Missing config call"
        (let [bad-result (dl/wrap-datalevin-save base-handler)]  ;; WRONG: passing handler directly
          
          (is (fn? bad-result)
              "Returns a function because we didn't call with config first"))))))

(deftest demonstrate-error-in-reducer
  (testing "Shows how the error manifests in reduce/merge operations"
    (let [base-handler (fn [env] {:original :data})]
      
      ;; CORRECT: Middleware properly composed
      (let [correct-wrapped (dl/wrap-datalevin-save {:default-schema :production})
            correct-handler (correct-wrapped base-handler)
            correct-result  (correct-handler {::form/delta nil})]
        
        (is (map? correct-result))
        ;; This works fine:
        (is (map? (merge correct-result {:new :key}))))
      
      ;; INCORRECT: Missing the handler wrapping step
      (let [incorrect-wrapped (dl/wrap-datalevin-save {:default-schema :production})
            incorrect-result  (incorrect-wrapped {})]  ;; Passed env instead of handler!
        
        (is (fn? incorrect-result))
        ;; This throws ClassCastException:
        (is (thrown? ClassCastException
                     (merge incorrect-result {:new :key}))
            "Cannot merge a function with a map")))))

;; ================================================================================
;; Diagnostic Test: Check Middleware Signature
;; ================================================================================

(deftest check-middleware-signature
  (testing "Verify wrap-datalevin-save has correct signature"
    ;; With config map
    (let [result (dl/wrap-datalevin-save {:default-schema :test})]
      (is (fn? result)
          "wrap-datalevin-save({config}) returns a function"))
    
    ;; Without config map (uses defaults)
    (let [result (dl/wrap-datalevin-save)]
      (is (fn? result)
          "wrap-datalevin-save() returns a function"))
    
    ;; Calling with a handler
    (let [middleware-fn (dl/wrap-datalevin-save)
          result        (middleware-fn (fn [env] {}))]
      (is (fn? result)
          "middleware-fn(handler) returns a function (the wrapped handler)"))
    
    ;; Finally calling the wrapped handler with env
    (let [middleware-fn    (dl/wrap-datalevin-save)
          wrapped-handler  (middleware-fn (fn [env] {}))
          result           (wrapped-handler {})]
      (is (map? result)
          "wrapped-handler(env) returns a map (the result)"))))

(deftest document-correct-usage-pattern
  (testing "Document the CORRECT way to use wrap-datalevin-save"
    (println "\n=== CORRECT USAGE PATTERN ===")
    (println "Step 1: Create middleware with config")
    (println "  (def middleware (dl/wrap-datalevin-save {:default-schema :production}))")
    (println)
    (println "Step 2: Wrap your base save handler")
    (println "  (def wrapped-handler (middleware base-save-fn))")
    (println)
    (println "Step 3: Call the wrapped handler with env")
    (println "  (def result (wrapped-handler env))")
    (println)
    (println "Or in one expression:")
    (println "  (def result")
    (println "    ((dl/wrap-datalevin-save {:default-schema :production})")
    (println "     base-save-fn)")
    (println "    env))")
    (println)
    (println "In a threading macro:")
    (println "  (-> base-save-fn")
    (println "      ((dl/wrap-datalevin-save {:default-schema :production}))")
    (println "      ((other-middleware)))")
    (println)
    (is true "Documentation printed")))
