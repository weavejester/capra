(ns capra.server
  (:require [capra.http.reason :as reason]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.io OutputStream]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    {::step       :start-line
     :scheme      :http
     :server-port (.getPort local)
     :server-name (.getHostString local)
     :remote-addr (.getHostString remote)}))

(defn- parse-start-line [state buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)] 
    (let [[method uri protocol] (str/split line #" ")]
      (assoc! state
              ::step          :headers
              :request-method (keyword (str/lower-case method))
              :uri            uri
              :protocol       protocol
              :headers        (transient {})))))

(defn- parse-header [{:keys [headers] :as state} buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (str/blank? line)
      (assoc! state
              ::step   :handler
              :headers (persistent! headers))
      (let [[name value] (str/split line #":")]
        (->> (assoc! headers (str/lower-case name) (str/trim value))
             (assoc! state :headers))))))

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(let [crlf (.getBytes "\r\n" StandardCharsets/US_ASCII)
      eof  (.getBytes "0\r\n\r\n")]      
  (defn- chunked-output-stream ^OutputStream [^OutputStream out]
    (stream/output-stream
     (fn write-chunk [^bytes b off len]
       (let [header (format "%X\r\n" len)]
         (.write out (.getBytes header StandardCharsets/US_ASCII))
         (.write out b off len)
         (.write out crlf)))
     (fn close-stream []
       (.write out eof)
       (.close out)))))
  
(defn- chunked-transfer? [{:strs [transfer-encoding]}]
  (boolean (some->> transfer-encoding (re-find #"(^|, *)chunked($|,)"))))

(defn- lowercase-headers [headers]
  (persistent! (reduce-kv #(assoc! %1 (str/lower-case %2) %3)
                          (transient {}) headers)))

(defn- ring-responder
  [{:keys [protocol]} socket out {:keys [response-buffer-size]}]
  (fn [{:keys [status headers body] :as response}]
    (let [buffer  (ByteBuffer/allocate response-buffer-size)
          reason  (reason/status->reason status)]
      (write-ascii buffer (str protocol " " status " " reason "\r\n"))
      (doseq [kv headers]
        (write-ascii buffer (str (key kv) ": " (val kv) "\r\n")))
      (write-ascii buffer "\r\n")
      (.flip buffer)
      (tcp/write socket buffer)
      (if (chunked-transfer? (lowercase-headers headers))
        (ring/write-body-to-stream body response (chunked-output-stream out))
        (ring/write-body-to-stream body response out)))))

(defn- content-length [{{:strs [content-length]} :headers}]
  (some-> content-length Long/parseLong))

(defn- run-ring-handler [ring-handler request socket opts]
  (let [handler (stream/stream-handler
                 (fn [in out]
                   (let [respond (ring-responder request socket out opts)
                         raise   (fn [_ex])]
                     (-> (assoc! request :body in)
                         (persistent!)
                         (ring-handler respond raise))))
                 opts)]
    {::step    :body
     ::handler handler
     ::state   (handler socket)
     ::length  (or (content-length request) :chunked)}))

(defn- limit-buffer-to-length ^ByteBuffer [^ByteBuffer buffer length]
  (if (< length (.remaining buffer))
    (doto (.duplicate buffer) (.limit (+ (.position buffer) length)))
    buffer))

(defn- write-body-stream
  [{::keys [handler length] :as state} socket ^ByteBuffer buffer]
  (let [capped-buffer (limit-buffer-to-length buffer length)
        buffer-size   (.remaining capped-buffer)
        state         (update state ::state handler socket capped-buffer)
        bytes-read    (- buffer-size (.remaining capped-buffer))
        length        (- length bytes-read)]
    (.position buffer (.position capped-buffer))
    (-> (assoc state ::length length)
        (cond-> (<= length 0) (update ::state handler socket nil)))))

(defn- close-body-stream [{::keys [handler state]} exception]
  (handler state exception))

(defn- http-handler
  [handler {:keys [handler-executor body-buffer-size response-buffer-size]}]
  (let [opts {:executor             handler-executor 
              :read-buffer-size     body-buffer-size
              :response-buffer-size response-buffer-size}]
    (fn
      ([socket]
       (transient (init-request socket)))
      ([{::keys [step] :as state} socket buffer]
       (if-some [state
                 (case step
                   :start-line (parse-start-line state buffer)
                   :headers    (parse-header state buffer)
                   :handler    (run-ring-handler handler state socket opts)
                   nil)] 
          (recur state socket buffer)
          (case step
            :body (write-body-stream state socket buffer)
            state)))
      ([{::keys [step] :as state} exception]
       (when exception (prn :exception exception))
       (case step
         :body (close-body-stream state exception) 
         nil)))))

(defn- new-default-options []
  {:body-buffer-size     8192
   :response-buffer-size 32768
   :handler-executor     (Executors/newFixedThreadPool 16)})

(defn start-server [handler options]
  (let [handler-opts (merge (new-default-options) options)]
    (tcp/start-server
     (-> options
         (assoc :executor (:socket-executor options))
         (assoc :handler (http-handler handler handler-opts))))))

(comment
  server

  (def server
    (start-server
     (fn [request respond _raise]
       (prn :request request)
       (respond {:status  200
                 :headers {"Content-Type"      "text/html; charset=UTF-8"
                           "Transfer-Encoding" "chunked"}
                 :body    (str "body=" (slurp (:body request)))}))
     {:port 4000, :reuse-address? true}))
  
  (.close server))
