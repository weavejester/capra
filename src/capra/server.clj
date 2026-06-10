(ns capra.server
  (:require [capra.http.reason :as reason]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.io Closeable OutputStream]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.time ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent Executors]
           [java.util.concurrent.atomic AtomicInteger]))

(def ^:private ^:const server-header
  "Server: Capra\r\n\r\n")

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
      (let [[name value] (str/split line #":" 2)]
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

(defn- response-info [{:keys [headers]}]
  (reduce-kv (fn [m k v]
               (cond
                 (.equalsIgnoreCase "transfer-encoding" k)
                 (assoc m :transfer-encoding (str/lower-case v))
                 (.equalsIgnoreCase "content-length" k)
                 (assoc m :content-length (Long/parseLong v))
                 :else m))
             {} headers))

(defn- date-header []
  (str "Date: " (.format (ZonedDateTime/now ZoneOffset/UTC)
                         DateTimeFormatter/RFC_1123_DATE_TIME) "\r\n"))

(defn- write-response-head
  [socket
   {:keys [protocol]} {:keys [transfer-encoding content-length]}
   {:keys [status headers]}
   {:keys [response-buffer-size]}]
  (let [buffer (ByteBuffer/allocate response-buffer-size)
        reason (reason/status->reason status)]
    (write-ascii buffer (str protocol " " status " " reason "\r\n"))
    (write-ascii buffer (date-header))
    (doseq [kv headers]
      (write-ascii buffer (str (key kv) ": " (val kv) "\r\n")))
    (when (and (nil? transfer-encoding) (nil? content-length))
      (write-ascii buffer "Transfer-Encoding: chunked\r\n"))
    (write-ascii buffer server-header)
    (.flip buffer)
    (tcp/write socket buffer)))

(defn- write-response-body
  [out
   {:keys [transfer-encoding content-length]}
   {:keys [body] :as response}
   callback]
  (let [out (if (or (= transfer-encoding "chunked")
                    (and (nil? transfer-encoding) (nil? content-length)))
              (chunked-output-stream out callback)
              (limited-output-stream out content-length callback))]
    (ring/write-body-to-stream body response out)))

(defn- ring-responder [request socket out callback options]
  (fn respond
    ([response]
     (let [info (response-info response)]
       (write-response-head socket request info response options)
       (write-response-body out info response callback)))
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
         (date-header)
         "Connection: close\r\n"
         "Content-Type: text/plain; charset=UTF-8\r\n"
         "Content-Length: "
         (count (.getBytes body StandardCharsets/US_ASCII))
         server-header
         body)))

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

(defn- chunked-transfer? [{{:strs [transfer-encoding]} :headers}]
  (.equalsIgnoreCase "chunked" transfer-encoding))

(defn- content-length [{{:strs [content-length]} :headers}]
  (some-> content-length Long/parseLong))

(defn- run-ring-handler [ring-handler req socket opts]
  (if (not (valid-transfer-encoding? req))
    {::step     :error
     ::response (transfer-encoding-error req)}
    (let [next?    (volatile! false)
          callback #(do (vreset! next? true) (tcp/resume-reads socket))
          handler  (ring->stream-handler ring-handler req socket callback opts)
          socket   (if (close-connection? req) socket (keepalive-socket socket))]
      (transient
       {::step     :body
        ::handler  handler
        ::state    (handler socket)
        ::next?    next?
        ::chunked? (chunked-transfer? req)
        ::length   (content-length req)}))))

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
    (doto (.duplicate buffer)
      (.limit (+ (.position buffer) ^long length)))
    buffer))

(defn- read-known-length-body-stream
  [{::keys [handler ^long length state] :as st} socket ^ByteBuffer buffer]
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
    (when (= (.limit buffer) (.capacity buffer))
      (tcp/pause-reads socket))))

(defn- write-error-response [{::keys [response]} socket]
  (let [response-bytes (.getBytes ^String response StandardCharsets/US_ASCII)]
    (tcp/write socket (ByteBuffer/wrap response-bytes))
    (tcp/close socket)
    nil))

(let [lock (Object.)]
  (defn- print-ex [ex]
    (locking lock
      (binding [*out* *err*]
        (prn :exception ex)))))

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
       (when exception (print-ex exception))
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

(defn start-server ^Closeable [handler options]
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

  (def s "Host:")
  (int \:)
  (.indexOf s 58)

  (def server
    (start-server
     (fn [request respond _raise]
       (respond {:status  200
                 :headers {"Content-Type"      "text/html; charset=UTF-8"
                           ;"Transfer-Encoding" "chunked"
                           "Content-Length"    "8"}
                 :body    (str "body=" (slurp (:body request)))}))
     {:port 4000, :async? true, :reuse-address? true}))

  (defn simple-handler [_request]
    {:status  200
     :headers {"Content-Type" "text/plain; charset=UTF-8"}
     :body    "Hello World"})

  (require '[org.httpkit.server :as hk])

  (def capra-server   (start-server simple-handler {:port 6201}))
  (def httpkit-server (hk/run-server simple-handler {:port 6202}))

  (.close server))
