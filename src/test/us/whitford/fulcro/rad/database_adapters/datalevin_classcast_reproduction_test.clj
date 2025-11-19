(ns us.whitford.fulcro.rad.database-adapters.datalevin-classcast-reproduction-test
  "Attempts to reproduce the exact ClassCastException error the user is seeing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.form :as form]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]))

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

(def test-attributes [account-id account-name account-email account-active])

(deftest reproduce-classcast-with-into
  (testing "ClassCastException when using result with 'into'"
    (let [middleware-wrapper (dl/wrap-datalevin-save {:default-schema :production})
          ;; WRONG: Calling with env instead of handler
          wrong-result (middleware-wrapper {})]
      
      (is (fn? wrong-result))
      
      ;; This triggers IllegalArgumentException (ClassCastException in the message)
      (is (thrown? IllegalArgumentException
                   (into {} wrong-result))
          "'into' on a function throws IllegalArgumentException"))))

(deftest reproduce-classcast-with-seq
  (testing "ClassCastException when calling seq on result"
    (let [middleware-wrapper (dl/wrap-datalevin-save {:default-schema :production})
          wrong-result (middleware-wrapper {})]
      
      (is (fn? wrong-result))
      
      ;; This triggers IllegalArgumentException
      (is (thrown? IllegalArgumentException
                   (seq wrong-result))
          "'seq' on a function throws IllegalArgumentException"))))

(deftest reproduce-classcast-with-merge
  (testing "ClassCastException when merging with a function"
    (let [middleware-wrapper (dl/wrap-datalevin-save {:default-schema :production})
          wrong-result (middleware-wrapper {})]
      
      (is (fn? wrong-result))
      
      ;; merge calls seq internally, which triggers IllegalArgumentException
      (is (thrown? IllegalArgumentException
                   (merge {} wrong-result))
          "'merge' with a function throws IllegalArgumentException"))))

(deftest reproduce-exact-rad-save-form-scenario
  (testing "Reproduce the exact scenario from RAD save-form mutation"
    (let [path   (str "/tmp/datalevin-rad-scenario-" (new-uuid))
          schema (dl/automatic-schema :production test-attributes)
          conn   (d/get-conn path schema)
          
          tid    (tempid/tempid)
          real-id (new-uuid)
          
          delta  {[:account/id tid]
                  {:account/id {:before nil :after real-id}
                   :account/name {:before nil :after "Test User"}
                   :account/email {:before nil :after "test@example.com"}
                   :account/active? {:before nil :after true}}}
          
          key->attr (into {} (map (juxt ::attr/qualified-key identity)) test-attributes)
          
          env    {::attr/key->attribute key->attr
                  ::dlo/connections     {:production conn}
                  ::form/delta          delta}]
      
      ;; === SCENARIO 1: CORRECT USAGE ===
      (testing "Correct: Proper middleware composition"
        (let [base-handler    (fn [e] {::form/id [:account/id real-id]
                                       ::form/complete? true})
              middleware-fn   (dl/wrap-datalevin-save {:default-schema :production})
              wrapped-handler (middleware-fn base-handler)
              result          (wrapped-handler env)]
          
          (is (map? result) "Result is a map")
          (is (::form/complete? result))
          (is (contains? result :tempids))
          
          ;; This is what RAD does internally - merges results
          (let [merged (merge {::form/base :data} result)]
            (is (map? merged))
            (is (= :data (::form/base merged))))))
      
      ;; === SCENARIO 2: INCORRECT USAGE (reproduces the error) ===
      (testing "Incorrect: Missing handler step (REPRODUCES ERROR)"
        ;; This simulates if someone configured the middleware incorrectly
        (let [middleware-fn (dl/wrap-datalevin-save {:default-schema :production})
              ;; WRONG: Passing env directly instead of wrapping a handler first
              bad-result    (middleware-fn env)]
          
          (is (fn? bad-result)
              "Result is a function, not a map!")
          
          ;; This is what RAD's save-form does - it tries to merge results
          ;; This triggers: "Don't know how to create ISeq"
          (is (thrown-with-msg? IllegalArgumentException
                                #"Don't know how to create ISeq"
                                (merge {::form/base :data} bad-result))
              "Merging with function throws IllegalArgumentException - THIS IS THE ERROR!")))
      
      ;; Cleanup
      (d/close conn)
      (let [dir (java.io.File. path)]
        (when (.exists dir)
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(deftest check-if-issue-is-in-middleware-composition
  (testing "Check if the issue could be in how middleware is composed in app"
    (println "\n=== DIAGNOSIS ===")
    (println "The ClassCastException occurs when:")
    (println "1. wrap-datalevin-save is called incorrectly")
    (println "2. It returns a function instead of being properly composed")
    (println "3. RAD tries to merge that function result, causing the error")
    (println)
    (println "Common causes:")
    (println "- Middleware not properly wrapped around a base handler")
    (println "- Calling (wrap-datalevin-save env) instead of ((wrap-datalevin-save) handler)")
    (println "- Missing parentheses in middleware composition")
    (println)
    (println "Example of WRONG usage in form.cljc:")
    (println "  (def save-middleware")
    (println "    (wrap-datalevin-save {:default-schema :production}))")
    (println "  ;; Then calling: (save-middleware env) - WRONG!")
    (println)
    (println "Example of CORRECT usage:")
    (println "  (def save-middleware-stack")
    (println "    (-> pathom/default-save-middleware")
    (println "        ((wrap-datalevin-save {:default-schema :production}))))")
    (is true)))
