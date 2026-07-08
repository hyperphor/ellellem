(defproject com.hyperphor/ellellem "0.1.2-SNAPSHOT"
  :description "Unified multi-provider LLM client library (Clojure port of ellmer)"
  :url "https://github.com/hyperphor/ellellem"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [hato "1.0.0"]
                 [org.clojure/data.json "2.5.0"]
                 [environ "1.2.0"]
                 [com.hyperphor/multitool "0.2.4"]
                 [org.clojure/core.async "1.6.681"]]
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.4"]]}})
