(ns build
  "Build tasks for fulcro-rad-datalevin (tools.build).

   Usage:
     clojure -T:build clean      ; remove target/
     clojure -T:build jar        ; write pom + build thin jar
     clojure -T:build install    ; install to local ~/.m2

   Deploy to Clojars is a SEPARATE step (deps-deploy in an isolated classpath —
   it cannot share tools.build's classpath due to conflicting maven libs):
     clojure -T:build jar
     clojure -X:deploy

   Deploying requires Clojars credentials in the environment:
     CLOJARS_USERNAME  — your Clojars username
     CLOJARS_PASSWORD  — a Clojars deploy token (not your account password)

   Versioning policy: -alpha / -beta suffixes are for LOCAL builds only; CI
   (release.yml) deploys only full releases (v1.2.3) and release candidates
   (v1.2.3-RC1), setting the VERSION env var from the pushed git tag."
  (:require
    [clojure.tools.build.api :as b]))

(def lib 'us.whitford/fulcro-rad-datalevin)
;; Version defaults to the value below for local builds; CI (release.yml) sets
;; the VERSION env var from the pushed git tag so the jar/pom match the release.
(def version (or (System/getenv "VERSION") "1.0.0-RC1"))
(def class-dir "target/classes")
;; Version-less jar name so the :deploy alias needn't track the version
;; (the pom inside the jar carries the coordinates).
(def jar-file (format "target/%s.jar" (name lib)))
(def scm-url "https://github.com/michaelwhitford/fulcro-rad-datalevin")

(defn- basis
  "Project basis from the root :deps only (no aliases) — keeps the published
   pom free of test-only deps such as pathom."
  []
  (b/create-basis {:project "deps.edn"}))

(defn clean
  "Remove the target/ build directory."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and build a thin jar into target/."
  [_]
  (clean nil)
  (b/write-pom
    {:class-dir class-dir
     :lib       lib
     :version   version
     :basis     (basis)
     :src-dirs  ["src/main"]
     :scm       {:url                 scm-url
                 :connection          "scm:git:git://github.com/michaelwhitford/fulcro-rad-datalevin.git"
                 :developerConnection "scm:git:ssh://git@github.com/michaelwhitford/fulcro-rad-datalevin.git"
                 :tag                 (str "v" version)}
     :pom-data  [[:description "A Fulcro RAD database adapter for Datalevin, an embedded Datalog database."]
                 [:url scm-url]
                 [:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs   ["src/main" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))

(defn install
  "Build the jar and install it into the local Maven repository (~/.m2)."
  [_]
  (jar nil)
  (b/install {:basis     (basis)
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed" (str lib) version "to local Maven repo"))
