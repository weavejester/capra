(ns capra.server
  (:require [capra.http.reason :as reason]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.io Closeable InputStream OutputStream]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.time ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent Executors]
           [java.util.concurrent.atomic AtomicInteger]))

(defn- ascii-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/US_ASCII))

(def ^:private empty-chunk   (ascii-bytes "0\r\n\r\n"))
(def ^:private server-header (ascii-bytes "Server: Capra\r\n"))
(def ^:private crlf          (ascii-bytes "\r\n"))

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
    (let [space1 (str/index-of line \space)
          space2 (str/index-of line \space (inc space1))]
      (assoc! state
              ::step          :headers
              :request-method (keyword (str/lower-case (subs line 0 space1)))
              :uri            (subs line (inc space1) space2)
              :protocol       (subs line (inc space2))
              :headers        (transient {})))))

(defn- parse-header [{:keys [headers] :as state} buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (= line "")
      (assoc! state
              ::step   :handler
              :headers (persistent! headers))
      (let [colon-index (str/index-of line \:)]
        (->> (assoc! headers
                     (str/lower-case (subs line 0 colon-index))
                     (str/trim       (subs line (inc colon-index))))
             (assoc! state :headers))))))

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(defn- write-crlf [^ByteBuffer buffer]
  (.put buffer (byte \return))
  (.put buffer (byte \newline)))

(defn- write-chunk [writef ^bytes b off len]
  (let [header (ascii-bytes (format "%X\r\n" len))]
    (writef header 0 (alength header))
    (writef b off len)
    (writef crlf 0 2)))

(defn- chunked-output-stream ^OutputStream [^OutputStream out]
  (stream/output-stream
   (fn write [b off len]
     (write-chunk #(.write out ^bytes %1 %2 %3) b off len))
   (fn close []
     (.write out ^bytes empty-chunk)
     (.close out))))

(defn- limited-output-stream ^OutputStream [^OutputStream out limit]
  (let [limit (AtomicInteger. limit)]
    (stream/output-stream
     (fn write [^bytes b off len]
       (let [len (min len (+ len (.addAndGet limit (- len))))]
         (when (pos? len)
           (.write out b off len))))
     (fn close []
       (.close out)))))

(defn- content-length [{:strs [content-length]}]
  (some-> content-length Long/parseLong))

(defn- chunked-transfer? [{:strs [transfer-encoding]}]
  (.equalsIgnoreCase "chunked" transfer-encoding))

(defn- chunked-response? [{:strs [transfer-encoding content-length]}]
  (or (.equalsIgnoreCase "chunked" transfer-encoding)
      (and (nil? transfer-encoding) (nil? content-length))))

(def ^:private re-charset
  #"(?x);(?:.*\s)?(?i:charset)=(?:
      ([!\#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)|  # token
      \"((?:\\\"|[^\"])*)\"               # quoted
    )\s*(?:;|$)")

(defn- content-charset [{:strs [content-type]}]
  (when-let [m (re-find re-charset content-type)]
    (or (m 1) (m 2))))

(defprotocol ResponseBody
  (write-body-to-buffer [body response headers buffer])
  (write-body-to-stream [body response headers out-stream]))

(extend-protocol ResponseBody
  String
  (write-body-to-buffer [body _response headers ^ByteBuffer buffer]
    (let [^String charset (content-charset headers)
          bs (.getBytes body (or charset "UTF-8"))]
      (cond
        (headers "content-length")
        (do (write-crlf buffer)
            (.put buffer bs 0 (content-length headers)))
        (chunked-transfer? headers)
        (do (write-crlf buffer)
            (write-chunk #(.put buffer ^bytes %1 %2 %3) bs 0 (alength bs))
            (.put buffer ^bytes empty-chunk))
        :else
        (do (write-ascii buffer (str "Content-Length: " (alength bs)))
            (write-crlf buffer)
            (write-crlf buffer)
            (.put buffer bs)))))
  (write-body-to-stream [_body _response _headers ^OutputStream out-stream]
    (.close out-stream))

  Object
  (write-body-to-buffer [_body _response headers buffer]
    (if (and (nil? (headers "transfer-encoding"))
             (nil? (headers "content-length")))
      (write-ascii buffer "Transfer-Encoding: chunked\r\n\r\n")
      (write-crlf buffer)))
  (write-body-to-stream [body response headers out-stream]
    (let [out (if (chunked-response? headers)
                (chunked-output-stream out-stream)
                (limited-output-stream out-stream (content-length headers)))]
      (ring/write-body-to-stream body response out))))

(defn- date-header []
  (str "Date: " (.format (ZonedDateTime/now ZoneOffset/UTC)
                         DateTimeFormatter/RFC_1123_DATE_TIME) "\r\n"))

(defn- write-response-head
  [^ByteBuffer buffer {:keys [protocol]} {:keys [status headers]}]
  (let [reason (reason/status->reason status)]
    (write-ascii buffer (str protocol " " status " " reason "\r\n"))
    (write-ascii buffer (date-header))
    (.put buffer ^bytes server-header)
    (doseq [kv headers]
      (let [value (val kv)]
        (if (vector? value)
          (doseq [v value]
            (write-ascii buffer (str (key kv) ": " v "\r\n")))
          (write-ascii buffer (str (key kv) ": " value "\r\n")))))))

(defn- get-cached [^ThreadLocal thread-local f]
  (or (.get thread-local)
      (let [v (f)] (.set thread-local v) v)))

(def ^:private response-buffer (ThreadLocal.))

(defn- lowercase-headers [headers]
  (persistent! (reduce-kv (fn [m k v] (assoc! m (str/lower-case k) v))
                          (transient {}) headers)))

(defn- close-connection? [{:keys [protocol] {:strs [connection]} :headers}]
  (or (and (nil? connection) (= protocol "HTTP/1.0"))
      (.equalsIgnoreCase "close" connection)))

(defn- ring-responder [request socket {buf-size :response-buffer-size}]
  (let [out (if (close-connection? request)
              (stream/socket->output-stream socket)
              (stream/socket->output-stream socket {:on-close (fn [_])}))]
    (fn respond
      ([{:keys [headers body] :as response}]
       (let [buffer  (get-cached response-buffer #(ByteBuffer/allocate buf-size))
             headers (lowercase-headers headers)]
         (.clear ^ByteBuffer buffer)
         (write-response-head buffer request response)
         (write-body-to-buffer body response headers buffer)
         (.flip ^ByteBuffer buffer)
         (tcp/write socket buffer)
         (write-body-to-stream body response headers out)))
      ([response ensure-body-closed?]
       (if ensure-body-closed?
         (try (respond response) (finally (.close ^OutputStream out)))
         (respond response))))))

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
         "Content-Length: " (count (ascii-bytes body))
         "Server: Capra"
         body)))

(defn- ring->stream-handler [ring-handler request opts]
  (stream/input-stream-handler
   (fn [in socket]
     (let [request (assoc request :body in)
           respond (ring-responder request socket opts)
           raise   (fn [_ex])]
       (ring-handler request respond raise)))
   opts))

(defn- run-streaming-handler [ring-handler request socket opts]
  (let [req     (persistent! request)
        handler (ring->stream-handler ring-handler req opts)]
    (transient
     {::step     :body
      ::handler  handler
      ::state    (handler socket)
      ::chunked? (chunked-transfer? (:headers req))
      ::length   (content-length (:headers req))})))

(defn- run-simple-handler [ring-handler request socket opts]
  (let [body    (InputStream/nullInputStream)
        request (persistent! (assoc! request :body body))
        respond (ring-responder request socket opts)
        raise   (fn [_ex])]
    (ring-handler request respond raise)
    (init-request socket)))

(defn- empty-request-body? [{:keys [headers]}]
  (and (not (contains? headers "content-length"))
       (not (contains? headers "transfer-encoding"))))

(defn- run-ring-handler [ring-handler request socket opts]
  (if (not (valid-transfer-encoding? request))
    {::step     :error
     ::response (transfer-encoding-error request)}
    (if (empty-request-body? request)
      (run-simple-handler ring-handler request socket opts)
      (run-streaming-handler ring-handler request socket opts))))

(defn- read-chunk! ^ByteBuffer [^ByteBuffer buffer]
  (let [chunked-buffer (.duplicate buffer)]
    (when-some [head (buf/read-line chunked-buffer StandardCharsets/US_ASCII)]
      (let [start  (.position chunked-buffer)
            length (Long/parseLong head 16)]
        (when (<= (+ length 2) (.remaining buffer))
          (.position buffer (+ start length 2))
          (doto chunked-buffer (.limit (+ start length))))))))

(defn- next-request [{::keys [handler state]} socket]
  (handler state nil)
  (init-request socket))

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

(defn- write-error-response [{::keys [response]} socket]
  (let [response-bytes (ascii-bytes response)]
    (tcp/write socket (ByteBuffer/wrap response-bytes))
    (tcp/close socket)
    nil))

(defn- print-ex [ex]
  (locking *err* (binding [*out* *err*]) (prn ex)))

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
  (.substring s 5)

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

  (def capra-server   (start-server #'simple-handler {:port 6201}))
  (def httpkit-server (hk/run-server simple-handler {:port 6202}))

  (.close capra-server))
