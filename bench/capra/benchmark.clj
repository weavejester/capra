(ns capra.benchmark
  (:require [capra.server :as capra]
            [clojure.java.shell :as shell]
            [org.httpkit.server :as httpkit]
            [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server Server]
           [org.eclipse.jetty.util.thread VirtualThreadPool]))

(def ^:const hot-work-timeout 40)

(defn simple-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Hello World"})

(defn hot-handler [_request]
  (let [t0 (System/currentTimeMillis)]
    (loop [n 0.0]
      (if (< (- (System/currentTimeMillis) t0) hot-work-timeout)
        (recur (+ n (Math/random)))
        {:status  200
         :headers {"Content-Type" "text/plain; charset=UTF-8"}
         :body    (str "Result: " n)}))))

(defn wrk [{:keys [port duration connections threads]
            :or   {connections 128, threads 2}}]
  (shell/sh "wrk" "-d" duration "-c" (str connections) "-t" (str threads)
            (str "http://localhost:" port)))

(defn -main []
  (println "Process ID: " (.pid (java.lang.ProcessHandle/current)))
  (Thread/sleep 2000)
  (with-open [_ (capra/run-server hot-handler
                                  :port 5800
                                  :error-logger (fn [_]))]
    (println "Warming up Capra...")
    (wrk {:port 5800 :duration "5s"})
    (println "Benchmarking Capra...")
    (println (:out (wrk {:port 5800 :duration "1m"}))))
  (let [close (httpkit/run-server hot-handler {:port 5801})]
    (try (println "Warming up http-kit...")
         (wrk {:port 5801 :duration "5s"})
         (println "Benchmarking HTTP-Kit...")
         (println (:out (wrk {:port 5801 :duration "1m"})))
         (finally (close))))
  (let [server (jetty/run-jetty hot-handler
                                {:port 5802
                                 :join? false
                                 :thread-pool (VirtualThreadPool.)})]
    (try (println "Warming up Ring Jetty...")
         (wrk {:port 5802 :duration "5s"})
         (println "Benchmarking Ring Jetty...")
         (println (:out (wrk {:port 5802 :duration "1m"})))
         (finally (.stop ^Server server))))
  (shutdown-agents))
