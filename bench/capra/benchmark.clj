(ns capra.benchmark
  (:require [aleph.http :as aleph]
            [capra.server :as capra]
            [clojure.java.shell :as shell]
            [org.httpkit.server :as httpkit]
            [ring.adapter.jetty :as jetty]
            [ring.adapter.undertow :as undertow])
  (:import [org.eclipse.jetty.server Server]
           [ring.adapter.undertow UndertowWrapper]))

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

(defn bench-aleph [handler port]
  (with-open [_ (aleph/start-server handler {:port port})]
    (println "Warming up Aleph")
    (wrk {:port port :duration "5s"})
    (println "Benchmarking Aleph")
    (println (:out (wrk {:port port :duration "1m"})))))

(defn bench-capra [handler port]
  (with-open [_ (capra/run-server handler :port port :error-logger (fn [_]))]
    (println "Warming up Capra...")
    (wrk {:port port :duration "5s"})
    (println "Benchmarking Capra...")
    (println (:out (wrk {:port port :duration "1m"})))))

(defn bench-http-kit [handler port]
  (let [close (httpkit/run-server handler {:port port})]
    (try (println "Warming up http-kit...")
         (wrk {:port port :duration "5s"})
         (println "Benchmarking HTTP-Kit...")
         (println (:out (wrk {:port port :duration "1m"})))
         (finally (close)))))

(defn bench-jetty [handler port]
  (let [server (jetty/run-jetty handler {:port port :join? false})]
    (try (println "Warming up Ring Jetty...")
         (wrk {:port port :duration "5s"})
         (println "Benchmarking Ring Jetty...")
         (println (:out (wrk {:port port :duration "1m"})))
         (finally (.stop ^Server server)))))

(defn bench-undertow [handler port]
  (let [server (undertow/run-undertow handler {:port port})]
     (try (println "Warming up Ring Undertow...")
          (wrk {:port port :duration "5s"})
          (println "Benchmarking Ring Undertow...")
          (println (:out (wrk {:port port :duration "1m"})))
          (finally (.stop ^UndertowWrapper server)))))

(defn -main []
  (println "Process ID:" (.pid (java.lang.ProcessHandle/current)))
  (doto simple-handler
    (bench-aleph 5800)
    (bench-capra 5801)
    (bench-http-kit 5802)
    (bench-jetty 5802)
    (bench-undertow 5804))
  (shutdown-agents))
