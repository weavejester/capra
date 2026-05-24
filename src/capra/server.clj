(ns capra.server
  (:require [capra.http.reason :as reason]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    {::state      :start-line
     :scheme      :http
     :server-port (.getPort local)
     :server-name (.getHostString local)
     :remote-addr (.getHostString remote)}))

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

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(defn- ring-responder [{:keys [protocol]} socket out]
  (fn [{:keys [status headers body] :as response}]
    (let [buffer (ByteBuffer/allocate 32768)
          reason (reason/status->reason status)]
      (write-ascii buffer (str protocol " " status " " reason "\r\n"))
      (doseq [kv headers]
        (write-ascii buffer (str (key kv) ": " (val kv) "\r\n")))
      (write-ascii buffer "\r\n")
      (.flip buffer)
      (tcp/write socket buffer)
      (ring/write-body-to-stream body response out))))

(defn- run-ring-handler [ring-handler request socket stream-opts]
  (let [handler (stream/stream-handler
                  (fn [in out]
                    (let [respond (ring-responder request socket out)
                          raise   (fn [_ex])]
                      (-> (assoc! request :body in)
                          (persistent!)
                          (ring-handler respond raise))))
                  stream-opts)]
    {::state   :body
     ::handler handler
     ::context (handler socket)}))

(defn- write-body-stream [{::keys [handler] :as context} socket buffer]
  (update context ::context handler socket buffer))

(defn- close-body-stream [{::keys [handler context]} exception]
  (handler context exception))

(defn- new-default-executor []
  (Executors/newFixedThreadPool 16))

(defn- http-handler [handler {:keys [handler-executor body-buffer-size]}]
  (let [opts {:executor (or handler-executor (new-default-executor))
              :read-buffer-size (or body-buffer-size 8192)}]
    (fn
      ([socket]
       (transient (init-request socket)))
      ([{::keys [state] :as context} socket buffer]
       (if-some [request
                 (case state
                   :start-line (parse-start-line context buffer)
                   :headers    (parse-header context buffer)
                   :handler    (run-ring-handler handler context socket opts)
                   nil)] 
          (recur request socket buffer)
          (case state
            :body (write-body-stream context socket buffer)
            context)))
      ([{::keys [state] :as context} exception]
       (when exception (prn :exception exception))
       (case state
         :body (close-body-stream context exception) 
         nil)))))

(defn start-server [handler options]
  (tcp/start-server (-> options
                        (assoc :executor (:socket-executor options))
                        (assoc :handler (http-handler handler options)))))

(comment
  server

  (def server
    (start-server
     (fn [request respond _raise]
       (prn :request request)
       (respond {:status  200
                 :headers {"Content-Type" "text/html; charset=UTF-8"}
                 :body    "Hello World"}))
     {:port 4000, :reuse-address? true}))
  
  (.close server))
