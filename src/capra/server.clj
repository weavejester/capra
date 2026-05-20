(ns capra.server
  (:require [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp])
  (:import [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- parse-start-line [request buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)] 
    (let [[method uri protocol] (str/split line #" ")]
      (assoc! request
              ::state  :headers
              :request-method method
              :uri      uri
              :protocol protocol
              :headers  (transient {})))))

(defn- parse-header [{:keys [headers] :as request} buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (str/blank? line)
      (dissoc! request ::state)
      (let [[name value] (str/split line #":")]
        (->> (assoc! headers name (str/trim value))
             (assoc! request :headers))))))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    {::state      :start-line
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
               (tcp/close socket))]
     (recur request socket buffer)
     request))
  ([state _exception]
   (prn (update (persistent! state) :headers persistent!))))

(defn start-server [options]
  (tcp/start-server (assoc options :handler #'http-handler)))
