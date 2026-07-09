(defproject dev.weavejester/capra "0.1.0-SNAPSHOT"
  :description "A Ring Adapter written in Clojure"
  :url "https://github.com/weavejester/capra"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [dev.weavejester/teensyp "0.8.0"]
                 [org.ring-clojure/ring-core-protocols "1.15.5"]]
  :plugins [[cider/cider-nrepl "0.59.0"]]
  :global-vars {*warn-on-reflection* true}
  :aliases {"bench" ["with-profile" "+bench" "run"]}
  :profiles
  {:bench {:dependencies [[aleph "0.9.9"]
                          [com.s-exp/hirundo "1.0.0-alpha214"]
                          [info.sunng/ring-jetty9-adapter "0.40.1"]
                          [io.github.robaho/httpserver "1.0.29"]
                          [luminus/ring-undertow-adapter "1.5.2"]
                          [org.clojars.jj/ring-http-exchange "1.4.8"]
                          [ring/ring-jetty-adapter "1.15.5"]
                          [ring/ring-core "1.15.5"]
                          [commons-io "2.21.0"]]
           :source-paths ["bench" "src"]
           :main capra.benchmark
           :global-vars {*warn-on-reflection* false}}
   :dev   {:dependencies [[clj-http "3.13.1"]
                          [http-kit "2.8.1"]
                          [criterium "0.4.6"]]}})
