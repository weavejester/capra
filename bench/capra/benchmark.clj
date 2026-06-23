(ns capra.benchmark
  (:require [capra.server :as capra]
            [clojure.java.shell :as shell]))

(defn simple-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Hello World"})

(defn wrk [{:keys [port duration connections threads]
            :or   {connections 1, threads 1}}]
  (shell/sh "wrk" "-d" duration "-c" (str connections) "-t" (str threads)
            (str "http://localhost:" port)))

(defn -main []
  (with-open [_ (capra/start-server simple-handler {:port 5800})]
    (println "Benchmarking Capra...")
    (println (:out (wrk {:port 5800 :duration "5s"})))
    (shutdown-agents)))
