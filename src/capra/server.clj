(ns capra.server
  (:require [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(defn- test-ring-handler [request]
  (prn :request request))

(def ^:private test-executor
  (Executors/newFixedThreadPool 4))

(defn- run-ring-handler [request socket]
  (let [handler (stream/stream-handler
                 (fn [in _out]
                   (-> (assoc! request :body in)
                       (persistent!)
                       (test-ring-handler)))
                 {:executor test-executor})]
    {::state   :body
     ::handler handler
     ::context (handler socket)}))

(defn- parse-start-line [request buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)] 
    (let [[method uri protocol] (str/split line #" ")]
      (assoc! request
              ::state         :headers
              :request-method (keyword (str/lower-case method))
              :uri            uri
              :protocol       protocol
              :headers        (transient {})))))

(defn- parse-header [{:keys [headers] :as request} buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (str/blank? line)
      (assoc! request
              ::state  :handler
              :headers (persistent! headers))
      (let [[name value] (str/split line #":")]
        (->> (assoc! headers (str/lower-case name) (str/trim value))
             (assoc! request :headers))))))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    {::state      :start-line
     :scheme      :http
     :server-port (.getPort local)
     :server-name (.getHostString local)
     :remote-addr (.getHostString remote)}))

(defn- http-handler
  ([socket]
   (transient (init-request socket)))
  ([{::keys [state] :as request} socket buffer]
   (if-some [request
             (case state
               :start-line (parse-start-line request buffer)
               :headers    (parse-header request buffer)
               :handler    (run-ring-handler request socket)
               :body       (do (tcp/close socket) nil))]
     (recur request socket buffer)
     request))
  ([_state exception]
   (when exception (prn :exception exception))))

(defn start-server [options]
  (tcp/start-server (assoc options :handler #'http-handler)))
