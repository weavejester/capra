(ns capra.benchmark
  (:require [aleph.http :as aleph]
            [capra.server :as capra]
            [clojure.java.shell :as shell]
            [s-exp.hirundo :as hirundo]
            [org.httpkit.server :as httpkit]
            [ring-http-exchange.core :as http-exchange]
            [ring.adapter.jetty :as jetty]
            [ring.adapter.jetty9 :as rj9a]
            [ring.adapter.undertow :as undertow])
  (:import [org.eclipse.jetty.server Server]
           [ring.adapter.undertow UndertowWrapper]))

(defn simple-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Hello World"})

(defn hot-work [n]
  (loop [i 0, x 0.0]
    (when (< i n)
      (recur (inc i) (+ x (Math/random))))))

(defn hot-handler [_request]
  (try
    (hot-work (+ 500 (rand-int 1500)))
    (Thread/sleep (inc (rand-int 10)))
    (hot-work (+ 500 (rand-int 1500)))
    {:status  200
     :headers {"Content-Type" "text/plain; charset=UTF-8"}
     :body    "Simulated work and I/O response"}
    (catch InterruptedException _
     {:status  500
      :headers {"Content-Type" "text/plain; charset=UTF-8"}
      :body    "Interrupted."})))

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

(defn bench-hirundo [handler port]
  (let [server (hirundo/start! {:http-handler handler :port port})]
    (try (println "Warming up Hirundo...")
         (wrk {:port port :duration "5s"})
         (println "Benchmarking Hirundo...")
         (println (:out (wrk {:port port :duration "1m"})))
         (finally (hirundo/stop! server)))))

(defn bench-http-exchange [handler port]
  (let [server (http-exchange/run-http-server handler {:port port})]
    (try (println "Warming up http-exchange...")
         (wrk {:port port :duration "5s"})
         (println "Benchmarking http-exchange...")
         (println (:out (wrk {:port port :duration "1m"})))
         (finally (http-exchange/stop-http-server server)))))

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

(defn bench-rj9a [handler port]
  (let [server (rj9a/run-jetty handler {:port port :join? false})]
    (try (println "Warming up rj9a...")
         (wrk {:port port :duration "5s"})
         (println "Benchmarking rj9a...")
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
  (doto hot-handler
    (bench-aleph 5800)
    (bench-capra 5801)
    (bench-hirundo 5802)
    (bench-http-exchange 5803)
    (bench-http-kit 5804)
    (bench-jetty 5805)
    (bench-rj9a 5806)
    (bench-undertow 5807))
  (shutdown-agents))
