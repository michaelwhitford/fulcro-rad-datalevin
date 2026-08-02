(ns us.whitford.fulcro.rad.database-adapters.search-test
  "Full-text search Phase 1: ::dlo/fulltext? schema generation, search-conn-opts
   derivation, and end-to-end fulltext queries through start-database!.

   Grounded by the Phase 0 spike (datalevin-test-app fulltext_spike_test.clj):
   relevance order requires {:display :refs+scores} + explicit sort — Datalog's
   set semantics do not preserve the engine's rank order."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.rad.attributes :as attr]
   [com.fulcrologic.rad.ids :refer [new-uuid]]
   [datalevin.core :as d]
   [us.whitford.fulcro.rad.database-adapters.datalevin :as dl]
   [us.whitford.fulcro.rad.database-adapters.datalevin-options :as dlo]
   [us.whitford.fulcro.rad.database-adapters.datalevin.start-databases :as sd]
   [us.whitford.fulcro.rad.database-adapters.test-utils :as tu]))

;; ================================================================================
;; Test attributes (schema :search-test)
;; ================================================================================

(def doc-id
  {::attr/qualified-key :doc/id
   ::attr/type          :uuid
   ::attr/schema        :search-test
   ::attr/identity?     true})

(def doc-title
  {::attr/qualified-key :doc/title
   ::attr/type          :string
   ::attr/schema        :search-test
   ::attr/identities    #{:doc/id}
   ::dlo/fulltext?      true})

(def doc-body
  {::attr/qualified-key :doc/body
   ::attr/type          :string
   ::attr/schema        :search-test
   ::attr/identities    #{:doc/id}
   ::dlo/fulltext?      {:index-position? true}})

(def doc-attributes [doc-id doc-title doc-body])

;; ================================================================================
;; Schema generation
;; ================================================================================

(deftest fulltext-schema-generation
  (testing "::dlo/fulltext? emits :db/fulltext plus a derived per-entity domain"
    (let [schema (dl/automatic-schema :search-test doc-attributes)]
      (is (true? (get-in schema [:doc/title :db/fulltext]))
          "boolean fulltext? marks the attribute :db/fulltext")
      (is (= ["doc"] (get-in schema [:doc/title :db.fulltext/domains]))
          "domain derives from the attribute namespace (entity domain)")
      (is (true? (get-in schema [:doc/body :db/fulltext]))
          "map-valued fulltext? also marks the attribute :db/fulltext")
      (is (= ["doc"] (get-in schema [:doc/body :db.fulltext/domains]))
          "all searchable attributes of an entity share the same domain")
      (is (nil? (get-in schema [:doc/id :db/fulltext]))
          "non-fulltext attributes are untouched"))))

(deftest fulltext-schema-respects-user-domains
  (testing "user-specified native :db.fulltext/domains wins over derivation"
    (let [custom (assoc doc-title ::dlo/attribute-schema
                        {:db.fulltext/domains ["custom"]})
          schema (dl/automatic-schema :search-test [doc-id custom])]
      (is (= ["custom"] (get-in schema [:doc/title :db.fulltext/domains]))
          "explicit domains pass through unchanged")))
  (testing "user-specified :db.fulltext/autoDomain suppresses domain derivation"
    (let [auto   (assoc doc-title ::dlo/attribute-schema
                        {:db.fulltext/autoDomain true})
          schema (dl/automatic-schema :search-test [doc-id auto])]
      (is (true? (get-in schema [:doc/title :db.fulltext/autoDomain]))
          "autoDomain passes through")
      (is (nil? (get-in schema [:doc/title :db.fulltext/domains]))
          "no derived :db.fulltext/domains alongside autoDomain"))))

;; ================================================================================
;; Connection option derivation
;; ================================================================================

(deftest search-conn-opts-derivation
  (testing "map-valued fulltext? contributes per-domain search options"
    (is (= {:search-domains {"doc" {:index-position? true}}}
           (sd/search-conn-opts :search-test doc-attributes))
        "options collect under the shared entity domain"))
  (testing "boolean-only fulltext? attributes yield no conn opts"
    (is (nil? (sd/search-conn-opts :search-test [doc-id doc-title]))
        "default-configured domains are left to Datalevin"))
  (testing "attributes from other schemas are ignored"
    (is (nil? (sd/search-conn-opts :other-schema doc-attributes)))))

;; ================================================================================
;; End-to-end through start-database!
;; ================================================================================

(defn- with-search-db*
  "Start a :search-test database via the public start-database! entry point,
   run (f conn), always clean up."
  [f]
  (let [path (str "/tmp/datalevin-search-" (new-uuid))
        conn (dl/start-database! {:path       path
                                  :schema     :search-test
                                  :attributes doc-attributes})]
    (try
      (f conn)
      (finally
        (dl/stop-database! conn)
        (tu/cleanup-path path)))))

(defn- seed-docs! [conn]
  (d/transact! conn
               [{:doc/id (new-uuid) :doc/title "one"
                 :doc/body "the quick red fox jumps"}
                {:doc/id (new-uuid) :doc/title "two"
                 :doc/body "fox fox fox everywhere a fox"}
                {:doc/id (new-uuid) :doc/title "three"
                 :doc/body "lazy brown dog sleeps"}]))

(deftest fulltext-end-to-end-ranked
  (testing "a start-database! conn indexes fulltext attrs; refs+scores ranks results"
    (with-search-db*
      (fn [conn]
        (seed-docs! conn)
        (let [db     (d/db conn)
              scored (d/q '[:find ?e ?s
                            :in $ ?q ?opts
                            :where [(fulltext $ ?q ?opts) [[?e _ _ ?s]]]]
                          db "fox" {:display :refs+scores :domains ["doc"]})
              titles (mapv #(:doc/title (d/pull db '[:doc/title] %))
                           (mapv first (sort-by second > scored)))]
          (is (= ["two" "one"] titles)
              "score-sorted results put the fox-heavy doc first"))))))

(deftest fulltext-end-to-end-phrase
  (testing "phrase search works because ::dlo/fulltext? {:index-position? true}
            flowed into the :search-domains conn opts"
    (with-search-db*
      (fn [conn]
        (seed-docs! conn)
        (let [db   (d/db conn)
              hits (d/q '[:find [?e ...]
                          :in $ ?q ?opts
                          :where [(fulltext $ ?q ?opts) [[?e _ _]]]]
                        db {:phrase "quick red fox"} {:domains ["doc"]})
              titles (mapv #(:doc/title (d/pull db '[:doc/title] %)) hits)]
          (is (= ["one"] titles)
              "only the doc containing the exact phrase matches"))))))

(deftest fulltext-save-then-search
  (testing "an entity written through the adapter's save path is searchable"
    (with-search-db*
      (fn [conn]
        (let [id (new-uuid)]
          (d/transact! conn [{:doc/id id :doc/title "saved"
                              :doc/body "unmistakable zanzibar keyword"}])
          (let [db   (d/db conn)
                hits (d/q '[:find [?e ...]
                            :in $ ?q ?opts
                            :where [(fulltext $ ?q ?opts) [[?e _ _]]]]
                          db "zanzibar" {:domains ["doc"]})]
            (is (= [id] (mapv #(:doc/id (d/pull db '[:doc/id] %)) hits))
                "sync indexing: the new doc is immediately searchable")))))))
