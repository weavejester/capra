(ns capra.benchmark
  (:require [capra.server :as capra]
            [clojure.java.shell :as shell]
            [org.httpkit.server :as httpkit]
            [ring.adapter.jetty :as jetty]))

(defn simple-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Hello World"})

(defn wrk [{:keys [port duration connections threads]
            :or   {connections 10, threads 2}}]
  (shell/sh "wrk" "-d" duration "-c" (str connections) "-t" (str threads)
            (str "http://localhost:" port)))

(defn -main []
  (with-open [_ (capra/start-server simple-handler {:port 5800})]
    (println "Benchmarking Capra...")
    (println (:out (wrk {:port 5800 :duration "1m"}))))
  (let [close (httpkit/run-server simple-handler {:port 5801})]
    (try (println "Benchmarking HTTP-Kit...")
         (println (:out (wrk {:port 5801 :duration "1m"})))
         (finally (close))))
  (let [server (jetty/run-jetty simple-handler {:port 5802, :join? false})]
    (try (println "Benchmarking Ring Jetty...")
         (println (:out (wrk {:port 5802 :duration "1m"})))
         (finally (.stop ^org.eclipse.jetty.server.Server server))))
  (shutdown-agents))
