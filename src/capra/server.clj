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
           [java.util.concurrent Executors]
           [java.util.concurrent.atomic AtomicInteger]))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    (transient {::step       :start-line
                :scheme      :http
                :server-port (.getPort local)
                :server-name (.getHostString local)
                :remote-addr (.getHostString remote)})))

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
  (defn- chunked-output-stream ^OutputStream [^OutputStream out complete]
    (stream/output-stream
     (fn write-chunk [^bytes b off len]
       (let [header (format "%X\r\n" len)]
         (.write out (.getBytes header StandardCharsets/US_ASCII))
         (.write out b off len)
         (.write out crlf)))
     (fn close-stream []
       (.write out eof)
       (.close out)
       (complete)))))

(defn- limited-output-stream ^OutputStream [^OutputStream out limit complete]
  (let [limit (AtomicInteger. limit)]
    (stream/output-stream
     (fn write [^bytes b off len]
       (let [len (min len (+ len (.addAndGet limit (- len))))]
         (when (pos? len)
           (.write out b off len))))
     (fn close []
       (.close out)
       (complete)))))

(defn- chunked-transfer? [{:strs [transfer-encoding]}]
  (boolean (some->> transfer-encoding (re-find #"(^|, *)chunked($|,)"))))

(defn- content-length [{:strs [content-length]}]
  (some-> content-length Long/parseLong))

(defn- write-response-head
  [socket {:keys [protocol]} {:keys [status headers]}
   {:keys [response-buffer-size]}]
  (let [buffer (ByteBuffer/allocate response-buffer-size)
        reason (reason/status->reason status)]
    (write-ascii buffer (str protocol " " status " " reason "\r\n"))
    (doseq [kv headers]
       (write-ascii buffer (str (key kv) ": " (val kv) "\r\n")))
    (write-ascii buffer "\r\n")
    (.flip buffer)
    (tcp/write socket buffer)))

(defn- lowercase-headers [headers]
  (persistent! (reduce-kv #(assoc! %1 (str/lower-case %2) %3)
                          (transient {}) headers)))

(defn- write-response-body [out {:keys [headers body] :as response} callback]
 (let [headers (lowercase-headers headers)
       out     (if (chunked-transfer? headers)
                 (chunked-output-stream out callback)
                 (limited-output-stream out (content-length headers) callback))]
   (ring/write-body-to-stream body response out)))

(defn- ring-responder [request socket out callback options]
  (fn respond
    ([response]
     (write-response-head socket request response options)
     (write-response-body out response callback))
    ([response ensure-body-closed?]
     (if ensure-body-closed?
       (try (respond response) (finally (.close ^OutputStream out)))
       (respond response)))))

(defn- valid-transfer-encoding? [{{encoding "transfer-encoding"} :headers}]
  (or (nil? encoding) (.equalsIgnoreCase "chunked" encoding)))

(defn- transfer-encoding-error
  [{:keys [protocol] {:strs [transfer-encoding]} :headers}] 
  (let [body (str "Unsupported request transfer encoding: \""
                    transfer-encoding "\".\n"
                    "Only \"chunked\" transfer encoding supported.")]
      (str protocol " 501 Not Implemented\r\n"
           "Connection: close\r\n"
           "Content-Type: text/plain; charset=UTF-8\r\n"
           "Content-Length: "
           (count (.getBytes body StandardCharsets/US_ASCII))
           "\r\n\r\n" body)))

(defn- ring->stream-handler [ring-handler request socket callback opts]
  (stream/stream-handler
   (fn [in out]
     (let [request (persistent! (assoc! request :body in))
           respond (ring-responder request socket out callback opts)
           raise   (fn [_ex])]
       (ring-handler request respond raise)))
   opts))

(defn- keepalive-socket [socket]
  (reify tcp/Socket
    (queue-control [_ c f] (tcp/queue-control socket c f))
    (socket-info   [_]     (tcp/socket-info socket))
    (queue-write [_ buffer callback]
      (if (= buffer ::tcp/close)
        (callback)
        (tcp/queue-write socket buffer callback))))) 

(defn- close-connection? [{:keys [protocol] {:strs [connection]} :headers}]
  (or (and (nil? connection) (= protocol "HTTP/1.0"))
      (.equalsIgnoreCase "close" connection)))

(defn- run-ring-handler [ring-handler req socket opts]
  (if (not (valid-transfer-encoding? req))
    {::step     :error
     ::response (transfer-encoding-error req)}
    (let [next?    (volatile! false)
          callback #(do (vreset! next? true)
                        (tcp/resume-reads socket)
                        (tcp/force-read socket)) 
          handler  (ring->stream-handler ring-handler req socket callback opts)
          socket   (if (close-connection? req) socket (keepalive-socket socket))]
      (transient
       {::step     :body
        ::handler  handler
        ::state    (handler socket)
        ::next?    next?
        ::chunked? (-> req :headers chunked-transfer?)
        ::length   (-> req :headers content-length)}))))

(defn- read-chunk! ^ByteBuffer [^ByteBuffer buffer]
  (let [chunked-buffer (.duplicate buffer)]
    (when-some [head (buf/read-line chunked-buffer StandardCharsets/US_ASCII)]
      (let [start  (.position chunked-buffer)
            length (Long/parseLong head 16)]
        (when (<= (+ length 2) (.remaining buffer))
          (.position buffer (+ start length 2))
          (doto chunked-buffer (.limit (+ start length))))))))

(defn- next-request [{::keys [handler state next?]} socket]
  (handler state socket nil)
  {::step :buffer ::next? next?})

(defn- read-chunked-body-stream
  [{::keys [handler state] :as st} socket buffer]
  (when-some [chunk-buf (read-chunk! buffer)]
    (if (.hasRemaining chunk-buf)
      (do (handler state socket chunk-buf) st)
      (next-request st socket)))) 

(defn- limit-buffer-to-length ^ByteBuffer [^ByteBuffer buffer length]
  (if (< length (.remaining buffer))
    (doto (.duplicate buffer) (.limit (+ (.position buffer) length)))
    buffer))

(defn- read-known-length-body-stream
  [{::keys [handler length state] :as st} socket ^ByteBuffer buffer]
  (if (pos? length)
    (when (.hasRemaining buffer)
      (let [capped-buffer (limit-buffer-to-length buffer length)
            buffer-size   (.remaining capped-buffer)
            _state        (handler state socket capped-buffer)
            bytes-read    (- buffer-size (.remaining capped-buffer))
            length        (- length bytes-read)]
        (.position buffer (.position capped-buffer))
        (assoc! st ::length length)))
    (next-request st socket)))

(defn- read-body-stream [state socket buffer]
  (cond
    (::length state)   (read-known-length-body-stream state socket buffer)
    (::chunked? state) (read-chunked-body-stream state socket buffer)
    :else              (next-request state socket)))

(defn- close-response [{::keys [handler state]} exception]
  (handler state exception))

(defn- buffer-next-request [{::keys [next?]} socket ^ByteBuffer buffer]
  (if @next?
    (init-request socket)
    (when (zero? (.capacity buffer))
      (tcp/pause-reads socket))))

(defn- write-error-response [{::keys [response]} socket]
  (let [response-bytes (.getBytes ^String response StandardCharsets/US_ASCII)]
    (tcp/write socket (ByteBuffer/wrap response-bytes))
    (tcp/close socket)
    nil))

(defn- http-handler
  [handler {:keys [handler-executor body-buffer-size response-buffer-size]}]
  (let [opts {:executor             handler-executor 
              :read-buffer-size     body-buffer-size
              :response-buffer-size response-buffer-size}]
    (fn
      ([socket]
       (init-request socket))
      ([{::keys [step] :as state} socket buffer]
       (if-some [state
                 (case step
                   :start-line (parse-start-line state buffer)
                   :headers    (parse-header state buffer)
                   :handler    (run-ring-handler handler state socket opts)
                   :body       (read-body-stream state socket buffer)
                   :buffer     (buffer-next-request state socket buffer)
                   :error      (write-error-response state socket)
                   nil)] 
          (recur state socket buffer)
          state))
      ([{::keys [step] :as state} exception]
       (when exception (prn :exception exception))
       (case step
         :body (close-response state exception) 
         nil)))))

(defn- sync->async-handler [handler]
  (fn [request respond raise]
    (let [response (try (handler request)
                        (catch Exception ex (raise ex) ::error))]
      (when (not= response ::error)
        (respond response true)))))

(defn- new-default-options []
  {:body-buffer-size     8192
   :response-buffer-size 32768
   :handler-executor     (Executors/newFixedThreadPool 16)})

(defn start-server [handler options]
  (let [handler-opts (merge (new-default-options) options)
        handler      (if (:async? handler-opts)
                       handler
                       (sync->async-handler handler))]
    (tcp/start-server
     (-> options
         (assoc :executor (:socket-executor options))
         (assoc :handler (http-handler handler handler-opts))))))

(comment
  server

  (def server
    (start-server
     (fn [request respond _raise]
       (respond {:status  200
                 :headers {"Content-Type"      "text/html; charset=UTF-8"
                           ;"Transfer-Encoding" "chunked"
                           "Content-Length"    "8"}
                 :body    (str "body=" (slurp (:body request)))}))
     {:port 4000, :async? true, :reuse-address? true}))
  
  (.close server))
