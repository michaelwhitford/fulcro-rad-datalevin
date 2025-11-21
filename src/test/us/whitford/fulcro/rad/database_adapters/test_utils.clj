(ns us.whitford.fulcro.rad.database-adapters.test-utils
  "Shared test utilities and test data for datalevin adapter tests."
  (:require
   [com.fulcrologic.rad.attributes :as attr]
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
   ::attr/schema        :test
   ::attr/identity?     true})

(def account-name
  {::attr/qualified-key :account/name
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def account-email
  {::attr/qualified-key :account/email
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:account/id}
   ::dlo/attribute-schema {:db/unique :db.unique/value}})

(def account-active
  {::attr/qualified-key :account/active?
   ::attr/type          :boolean
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def account-balance
  {::attr/qualified-key :account/balance
   ::attr/type          :double
   ::attr/schema        :test
   ::attr/identities    #{:account/id}})

(def item-id
  {::attr/qualified-key :item/id
   ::attr/type          :uuid
   ::attr/schema        :test
   ::attr/identity?     true})

(def item-name
  {::attr/qualified-key :item/name
   ::attr/type          :string
   ::attr/schema        :test
   ::attr/identities    #{:item/id}})

(def account-items
  {::attr/qualified-key :account/items
   ::attr/type          :ref
   ::attr/schema        :test
   ::attr/cardinality   :many
   ::attr/target        :item/id
   ::attr/identities    #{:account/id}})

(def all-test-attributes
  [account-id account-name account-email account-active account-balance
   item-id item-name account-items])

;; ================================================================================
;; Helper Functions
;; ================================================================================

(defn create-test-conn
  "Create a test connection with schema."
  ([]
   (create-test-conn all-test-attributes))
  ([attributes]
   ;; Infer schema name from the first attribute's schema, default to :test
   (let [schema-name (or (::attr/schema (first attributes)) :test)
         path (str "/tmp/datalevin-test-" (new-uuid))
         schema (dl/automatic-schema schema-name attributes)]
     {:conn (d/get-conn path schema)
      :path path})))

(defn cleanup-test-conn
  "Close and cleanup a test connection."
  [{:keys [conn path]}]
  (when conn
    (d/close conn))
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn with-test-conn*
  "Execute f with a test connection, cleaning up afterward."
  ([f]
   (with-test-conn* all-test-attributes f))
  ([attributes f]
   (let [test-db (create-test-conn attributes)]
     (try
       (f (:conn test-db))
       (finally
         (cleanup-test-conn test-db))))))

(defmacro with-test-conn
  "Execute body with a test connection binding."
  [[conn-binding] & body]
  `(with-test-conn* (fn [~conn-binding] ~@body)))

(defmacro with-test-conn-attrs
  "Execute body with a test connection binding using specific attributes."
  [[conn-binding attributes] & body]
  `(with-test-conn* ~attributes (fn [~conn-binding] ~@body)))

(defn key->attribute-map
  "Create a key->attribute map from a collection of attributes."
  [attributes]
  (into {} (map (juxt ::attr/qualified-key identity)) attributes))

(defn cleanup-path
  "Cleanup a directory path by recursively deleting all files."
  [path]
  (when path
    (let [dir (java.io.File. path)]
      (when (.exists dir)
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))
