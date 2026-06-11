(defproject dev.weavejester/capra "0.1.0-SNAPSHOT"
  :description "A fast Ring adapter"
  :url "https://github.com/weavejester/capra"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [dev.weavejester/teensyp "0.6.3"]
                 [org.ring-clojure/ring-core-protocols "1.15.4"]]
  :plugins [[cider/cider-nrepl "0.59.0"]]
  :global-vars {*warn-on-reflection* true}
  :profiles {:dev {:dependencies [[clj-http "3.13.1"]
                                  [http-kit "2.8.1"]]}})
