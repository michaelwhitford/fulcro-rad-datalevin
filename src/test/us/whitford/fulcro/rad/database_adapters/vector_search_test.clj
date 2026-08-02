(ns us.whitford.fulcro.rad.database-adapters.vector-search-test
  "Vector similarity search: :vec schema/conn-opts derivation (incl.
   :db.vec/metric-type) and the generated :<entity>/similar resolver.

   Ordering ground truth: vec-neighbors returns engine-ordered results but
   Datalog set semantics drop that order (same as fulltext), so the resolver
   queries {:display :refs+dists} and sorts by ascending distance."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [com.fulcrologic.rad.pathom3 :as p3]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.datalevin.start-databases :as sd]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; Test attributes (schema :vec-test) — 3-dimensional embeddings
;; ================================================================================

(def vdoc-id
  {::attr/qualified-key :vdoc/id
   ::attr/type          :uuid
   ::attr/schema        :vec-test
   ::attr/identity?     true})

(def vdoc-label
  {::attr/qualified-key :vdoc/label
   ::attr/type          :string
   ::attr/schema        :vec-test
   ::attr/identities    #{:vdoc/id}})

(def vdoc-embedding
  {::attr/qualified-key :vdoc/embedding
   ::attr/type          :vec
   ::attr/schema        :vec-test
   ::attr/identities    #{:vdoc/id}
   ::dlo/attribute-schema {:db.vec/dimensions  3
                           :db.vec/metric-type :euclidean}})

(def vdoc-attributes [vdoc-id vdoc-label vdoc-embedding])

;; ================================================================================
;; Schema + connection derivation
;; ================================================================================

(deftest vec-schema-strips-conn-opt-keys
  (testing ":db.vec/dimensions and :db.vec/metric-type are conn opts, not schema"
    (let [schema (dl/automatic-schema :vec-test vdoc-attributes)]
      (is (= :db.type/vec (get-in schema [:vdoc/embedding :db/valueType])))
      (is (nil? (get-in schema [:vdoc/embedding :db.vec/dimensions])))
      (is (nil? (get-in schema [:vdoc/embedding :db.vec/metric-type]))))))

(deftest vec-conn-opts-carries-metric-type
  (testing "vec-conn-opts derives dimensions AND metric-type per domain"
    (is (= {:vector-domains {"vdoc_embedding" {:dimensions  3
                                               :metric-type :euclidean}}}
           (sd/vec-conn-opts :vec-test vdoc-attributes)))))

;; ================================================================================
;; Generated :vdoc/similar resolver
;; ================================================================================

(defn- with-vec-db*
  [f]
  (let [path (str "/tmp/datalevin-vec-search-" (new-uuid))
        conn (dl/start-database! {:path       path
                                  :schema     :vec-test
                                  :attributes vdoc-attributes})]
    (try
      (f conn)
      (finally
        (dl/stop-database! conn)
        (tu/cleanup-path path)))))

(def id-exact (new-uuid))
(def id-near  (new-uuid))
(def id-far   (new-uuid))

(defn- seed-vdocs! [conn]
  (d/transact! conn
               [{:vdoc/id id-exact :vdoc/label "exact"
                 :vdoc/embedding [1.0 0.0 0.0]}
                {:vdoc/id id-near :vdoc/label "near"
                 :vdoc/embedding [0.9 0.1 0.0]}
                {:vdoc/id id-far :vdoc/label "far"
                 :vdoc/embedding [0.0 0.0 1.0]}]))

(defn- run-similar
  [conn query-params]
  (let [resolvers (dl/generate-resolvers vdoc-attributes :vec-test)
        resolver  (first (filter #(= 'vdoc-similar-resolver
                                     (:com.wsscode.pathom.connect/sym %))
                                 resolvers))]
    ((tu/resolver-fn resolver)
     {::dlo/databases {:vec-test (d/db conn)}
      :query-params   query-params}
     {})))

(deftest similar-resolver-generation-gating
  (testing "a :<ns>/similar resolver is generated only when :vec attrs exist"
    (is (some? (first (filter #(= 'vdoc-similar-resolver
                                  (:com.wsscode.pathom.connect/sym %))
                              (dl/generate-resolvers vdoc-attributes :vec-test)))))
    (is (not-any? #(= 'account-similar-resolver (:com.wsscode.pathom.connect/sym %))
                  (dl/generate-resolvers tu/all-test-attributes :test))
        "entity without :vec attrs gets none")))

(deftest similar-resolver-nearest-first
  (testing "idents come back in ascending-distance (nearest-first) order"
    (with-vec-db*
      (fn [conn]
        (seed-vdocs! conn)
        (is (= {:vdoc/similar [{:vdoc/id id-exact} {:vdoc/id id-near} {:vdoc/id id-far}]}
               (run-similar conn {:vector [1.0 0.0 0.0]}))
            "exact match first, near second, far last")))))

(deftest similar-resolver-top-param
  (testing ":top caps the neighbor count"
    (with-vec-db*
      (fn [conn]
        (seed-vdocs! conn)
        (is (= {:vdoc/similar [{:vdoc/id id-exact}]}
               (run-similar conn {:vector [1.0 0.0 0.0] :top 1})))))))

(deftest similar-resolver-attribute-param
  (testing "an explicit :attribute param narrows the search to that attribute"
    (with-vec-db*
      (fn [conn]
        (seed-vdocs! conn)
        (is (= {:vdoc/similar [{:vdoc/id id-far}]}
               (run-similar conn {:vector    [0.0 0.0 1.0]
                                  :attribute :vdoc/embedding
                                  :top       1})))))))

(deftest similar-resolver-missing-vector
  (testing "a missing :vector param resolves to an empty list without throwing"
    (with-vec-db*
      (fn [conn]
        (seed-vdocs! conn)
        (is (= {:vdoc/similar []} (run-similar conn {})))))))

(deftest similar-resolver-through-real-pathom3-processor
  (testing "params flow through a real RAD P3 processor and fields auto-fill"
    (with-vec-db*
      (fn [conn]
        (seed-vdocs! conn)
        (let [env-mw    (-> (attr/wrap-env vdoc-attributes)
                            (dl/wrap-env (fn [_env] {:vec-test conn})))
              processor (p3/new-processor {} env-mw []
                                          [(dl/generate-resolvers vdoc-attributes :vec-test)])
              result    (processor {}
                                   [(list {:vdoc/similar [:vdoc/id :vdoc/label]}
                                          {:vector [1.0 0.0 0.0] :top 2})])]
          (is (= [{:vdoc/id id-exact :vdoc/label "exact"}
                  {:vdoc/id id-near :vdoc/label "near"}]
                 (:vdoc/similar result))
              "nearest-first idents with fields filled by the id-resolver"))))))

;; ================================================================================
;; Native-id entity similarity
;; ================================================================================

(def vpage-id
  {::attr/qualified-key :vpage/id
   ::attr/type          :long
   ::attr/schema        :vec-native-test
   ::attr/identity?     true
   ::dlo/native-id?     true})

(def vpage-embedding
  {::attr/qualified-key :vpage/embedding
   ::attr/type          :vec
   ::attr/schema        :vec-native-test
   ::attr/identities    #{:vpage/id}
   ::dlo/attribute-schema {:db.vec/dimensions 3}})

(deftest similar-resolver-native-id
  (testing "for native-id entities the matched eid is returned as the id"
    (let [path (str "/tmp/datalevin-vec-native-" (new-uuid))
          conn (dl/start-database! {:path       path
                                    :schema     :vec-native-test
                                    :attributes [vpage-id vpage-embedding]})]
      (try
        (d/transact! conn [{:vpage/embedding [1.0 0.0 0.0]}
                           {:vpage/embedding [0.0 1.0 0.0]}])
        (let [resolvers (dl/generate-resolvers [vpage-id vpage-embedding] :vec-native-test)
              resolver  (first (filter #(= 'vpage-similar-resolver
                                           (:com.wsscode.pathom.connect/sym %))
                                       resolvers))
              result    ((tu/resolver-fn resolver)
                         {::dlo/databases {:vec-native-test (d/db conn)}
                          :query-params   {:vector [1.0 0.0 0.0] :top 1}}
                         {})
              idents    (:vpage/similar result)]
          (is (= 1 (count idents)))
          (is (pos-int? (:vpage/id (first idents)))
              "native-id ident carries the raw eid")
          (is (= [1.0 0.0 0.0]
                 (mapv double (:vpage/embedding
                               (d/pull (d/db conn) '[:vpage/embedding]
                                       (:vpage/id (first idents))))))
              "the nearest eid pulls back the matching embedding"))
        (finally
          (dl/stop-database! conn)
          (tu/cleanup-path path))))))
