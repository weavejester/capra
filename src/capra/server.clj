(ns capra.server
  (:require [capra.http.error :as err]
            [capra.http.reason :as reason]
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

(def ^:private empty-chunk      (ascii-bytes "0\r\n\r\n"))
(def ^:private server-header    (ascii-bytes "Server: Capra\r\n"))
(def ^:private chunked-header   (ascii-bytes "Transfer-Encoding: chunked\r\n"))
(def ^:private length-header      (ascii-bytes "Content-Length: "))
(def ^:private zero-length-header (ascii-bytes "Content-Length: 0\r\n\r\n"))
(def ^:private date-header      (ascii-bytes "Date: "))
(def ^:private close-header     (ascii-bytes "Connection: close\r\n"))
(def ^:private keepalive-header (ascii-bytes "Connection: keep-alive\r\n"))
(def ^:private crlf             (ascii-bytes "\r\n"))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    (transient {::step       :start-line
                :scheme      :http
                :server-port (.getPort local)
                :server-name (.getHostString local)
                :remote-addr (.getHostString remote)})))

(defn- parse-start-line [state line]
  (or (when-some [space1 (str/index-of line \space)]
        (when-some [space2 (str/index-of line \space (inc space1))]
          (assoc! state
                  ::step          :headers
                  :request-method (keyword (str/lower-case (subs line 0 space1)))
                  :uri            (subs line (inc space1) space2)
                  :protocol       (subs line (inc space2))
                  :headers        (transient {}))))
      {::step  :error
       ::error :invalid-request-start-line}))

(defn- read-start-line [state ^ByteBuffer buffer max-buffer-size]
  (if-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (let [{:keys [protocol] :as state} (parse-start-line state line)]
      (if (or (nil? protocol) (= protocol "HTTP/1.1") (= protocol "HTTP/1.0"))
        state
        {::step    :error
         ::error   :http-version-not-supported
         ::request {:bad-protocol protocol}}))
    (when-not (< (.limit buffer) max-buffer-size)
      {::step :error, ::error :uri-too-long})))

(defn- parse-header [{:keys [headers] :as state} line]
  (if-some [colon-index (str/index-of line \:)]
    (assoc! state :headers
            (assoc! headers
                    (str/lower-case (subs line 0 colon-index))
                    (str/trim       (subs line (inc colon-index)))))
    {::step    :error
     ::error   :invalid-request-header
     ::request {:bad-header line}}))

(defn- read-header [{:keys [headers] :as state} buffer max-buffer-size]
  (if-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (= line "")
      (assoc! state ::step :handler, :headers (persistent! headers))
      (parse-header state line))
    (when-not (< (.limit ^ByteBuffer buffer) max-buffer-size)
      {::step :error, ::error :request-header-field-too-large})))

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(defn- write-crlf [^ByteBuffer buffer]
  (.put buffer (byte \return))
  (.put buffer (byte \newline)))

(defn- chunked-output-stream ^OutputStream [^OutputStream out]
  (stream/output-stream
   (fn write [^bytes b off len]
     (let [header (ascii-bytes (format "%X\r\n" len))]
       (.write out header)
       (.write out b off len)
       (.write out ^bytes crlf)))
   (fn close []
     (.write out ^bytes empty-chunk)
     (.close out))))

(defn- limited-output-stream ^OutputStream [^OutputStream out limit socket]
  (let [limit (AtomicInteger. limit)]
    (stream/output-stream
     (fn write [^bytes b off len]
       (let [len (min len (+ len (.addAndGet limit (- len))))]
         (when (pos? len)
           (.write out b off len))))
     (fn close []
       (.close out)
       (when (pos? (.get limit))
         (tcp/close socket))))))

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

(defn- run-writer [writerf socket ^ByteBuffer buffer]
  (if (writerf buffer)
    (tcp/write socket (.flip buffer))
    (tcp/write socket (.flip buffer)
               #(run-writer writerf socket (.clear buffer)))))

(defn- bytes-writer [^bytes bs off len]
  (let [read-buf (ByteBuffer/wrap bs off len)]
    (fn [write-buf]
      (buf/copy read-buf write-buf)
      (not (.hasRemaining read-buf)))))

(def ^:private end-chunk (ascii-bytes "\r\n0\r\n\r\n"))

(defn- chunk-writer [^bytes bs off len]
  (let [buffers [(ByteBuffer/wrap (ascii-bytes (format "%X\r\n" len)))
                 (ByteBuffer/wrap bs off len)
                 (ByteBuffer/wrap end-chunk)]
        index   (volatile! 0)]
    (fn [write-buf]
      (let [read-buf ^ByteBuffer (buffers @index)]
        (buf/copy read-buf write-buf)
        (when-not (.hasRemaining read-buf)
          (> (vswap! index inc) 2))))))

(defprotocol ResponseBody
  (write-body-to-socket
    [body response headers buffer socket async? callback]))

(extend (Class/forName "[B")
  ResponseBody
  {:write-body-to-socket
   (fn [^bytes body _response headers buffer socket _async? callback]
     (cond
       (headers "content-length")
       (let [content-len (content-length headers)
             body-len    (alength body)]
         (write-crlf buffer)
         (if (<= content-len body-len)
           (run-writer (bytes-writer body 0 content-len) socket buffer)
           (do (run-writer (bytes-writer body 0 body-len) socket buffer)
               (tcp/close socket))))
       (chunked-transfer? headers)
       (do (write-crlf buffer)
           (run-writer (chunk-writer body 0 (alength body)) socket buffer))
       :else
       (let [len (alength body)]
         (.put ^ByteBuffer buffer ^bytes length-header)
         (write-ascii buffer (str len))
         (write-crlf buffer)
         (write-crlf buffer)
         (run-writer (bytes-writer body 0 len) socket buffer)))
     (callback))})

(extend-protocol ResponseBody
  String
  (write-body-to-socket [body response headers buffer socket async? callback]
    (let [^String charset (content-charset headers)
          body-bytes      (.getBytes body (or charset "UTF-8"))]
      (write-body-to-socket
       body-bytes response headers buffer socket async? callback)))
  Object
  (write-body-to-socket [body response headers buffer socket async? callback]
    (when (and (nil? (headers "transfer-encoding"))
               (nil? (headers "content-length")))
      (.put ^ByteBuffer buffer ^bytes chunked-header))
    (write-crlf buffer)
    (.flip ^ByteBuffer buffer)
    (tcp/write socket buffer)
    (let [out (stream/socket->output-stream socket
                                            {:on-close (fn [_] (callback))})
          out (if (chunked-response? headers)
                (chunked-output-stream out)
                (limited-output-stream out (content-length headers) socket))]
      (try (ring/write-body-to-stream body response out)
           (finally
             (when-not async? (.close out))))))
  nil
  (write-body-to-socket
    [_body _response headers ^ByteBuffer buffer socket _async? callback]
    (cond
      (headers "content-length")
      (do (write-crlf buffer)
          (tcp/write socket (.flip buffer))
          (when-not (zero? (content-length headers))
            (tcp/close socket)))
      (chunked-transfer? headers)
      (do (.put buffer ^bytes end-chunk)
          (tcp/write socket (.flip buffer)))
      :else
      (do (.put buffer ^bytes zero-length-header)
          (tcp/write socket (.flip buffer))))
    (callback)))

(defn- rfc-1123-date-time []
  (.format (ZonedDateTime/now ZoneOffset/UTC)
           DateTimeFormatter/RFC_1123_DATE_TIME))

(defn- write-status-line
  [^ByteBuffer buffer {:keys [protocol]} {:keys [status]}]
  (doto buffer
    (write-ascii (or protocol "HTTP/1.1"))
    (.put (byte \space))
    (write-ascii (str status))
    (.put (byte \space))
    (write-ascii (reason/status->reason status))
    (.put (byte \return))
    (.put (byte \newline))))

(defn- write-header [^ByteBuffer buffer k v]
  (doto buffer
    (.put (ascii-bytes k))
    (.put (byte \:))
    (.put (byte \space))
    (.put (ascii-bytes v))
    (.put (byte \return))
    (.put (byte \newline))))

(defn- write-date-header [^ByteBuffer buffer]
  (.put buffer ^bytes date-header)
  (write-ascii buffer (rfc-1123-date-time))
  (write-crlf buffer))

(defn- write-conn-header [^ByteBuffer buffer {:keys [protocol]} close?]
  (when (not= (boolean close?) (= protocol "HTTP/1.0"))
    (.put buffer (if close? ^bytes close-header ^bytes keepalive-header))))

(defn- write-response-head
  [^ByteBuffer buffer request {:keys [headers] :as response} lc-headers close?]
  (write-status-line buffer request response)
  (when-not (lc-headers "connection") (write-conn-header buffer request close?))
  (when-not (lc-headers "date")       (write-date-header buffer))
  (when-not (lc-headers "server")     (.put buffer ^bytes server-header))
  (doseq [kv headers]
    (let [value (val kv)]
      (if (vector? value)
        (doseq [v value] (write-header buffer (key kv) v))
        (write-header buffer (key kv) value)))))

(defn- get-cached [^ThreadLocal thread-local f]
  (or (.get thread-local)
      (let [v (f)] (.set thread-local v) v)))

(def ^:private response-buffer (ThreadLocal.))

(defn- lowercase-headers [headers]
  (persistent! (reduce-kv (fn [m k v] (assoc! m (str/lower-case k) v))
                          (transient {}) headers)))

(def ^:private re-close-connection #"(?i)(^| *,)close( *,|$)")

(defn- request-close? [{:keys [protocol] {:strs [connection]} :headers}]
  (if (nil? connection)
    (= protocol "HTTP/1.0")
    (.find (re-matcher re-close-connection connection))))

(defn- response-close? [{:strs [connection]}]
  (when connection (.find (re-matcher re-close-connection connection))))

(defn- ring-responder [request socket handled {buf-size :response-buffer-size}]
  (fn respond [{:keys [headers body] :as response} async?]
    (when (compare-and-set! handled false true)
      (let [buffer  (get-cached response-buffer #(ByteBuffer/allocate buf-size))
            headers (lowercase-headers headers)
            close?  (request-close? request)]
        (.clear ^ByteBuffer buffer)
        (write-response-head buffer request response headers close?)
        (write-body-to-socket body response headers buffer socket async?
                              #(when (or close? (response-close? headers))
                                 (tcp/close socket)))))))

(defn- ring-raiser [request respond {:keys [error-handler error-logger]}]
  (fn [exception]
    (error-logger exception)
    (error-handler request #(respond % true) exception)))

(defn- valid-transfer-encoding? [{{encoding "transfer-encoding"} :headers}]
  (or (nil? encoding) (.equalsIgnoreCase "chunked" encoding)))

(defn- missing-host-header? [{:keys [protocol headers]}]
  (and (= protocol "HTTP/1.1") (not (contains? headers "host"))))

(defn- ring->stream-handler [ring-handler request opts]
  (stream/input-stream-handler
   (fn [in socket]
     (let [handled (atom false)
           request (assoc request :body in)
           respond (ring-responder request socket handled opts)
           raise   (ring-raiser request respond opts)]
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
        handled (atom false)
        request (persistent! (assoc! request :body body))
        respond (ring-responder request socket handled opts)
        raise   (ring-raiser request respond opts)]
    (ring-handler request respond raise)
    (init-request socket)))

(defn- empty-request-body? [{:keys [headers]}]
  (and (not (contains? headers "content-length"))
       (not (contains? headers "transfer-encoding"))))

(defn- run-ring-handler [ring-handler request socket opts]
  (cond
    (not (valid-transfer-encoding? request))
    {::step :error, ::error :unsupported-transfer-encoding, ::request request}
    (missing-host-header? request)
    {::step :error, ::error :missing-host-header, ::request request}
    (empty-request-body? request)
    (run-simple-handler ring-handler request socket opts)
    :else
    (run-streaming-handler ring-handler request socket opts)))

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

(defn- write-error-response [{::keys [error request]} socket opts]
  (let [handled (atom false)
        respond (ring-responder request socket handled opts)
        handler (err/error-handlers error)]
    (respond (handler request) false)
    (tcp/close socket)
    nil))

(defn- http-handler
  [handler {:keys [handler-executor body-buffer-size error-logger]
            max-buf-size :read-buffer-size
            :as   options}]
  (let [opts (assoc options
                    :executor         handler-executor
                    :read-buffer-size body-buffer-size)]
    (fn
      ([socket]
       (init-request socket))
      ([state socket buffer]
       (loop [state state]
         (if-some [new-state
                   (case (::step state)
                     :start-line (read-start-line state buffer max-buf-size)
                     :headers    (read-header state buffer max-buf-size)
                     :handler    (run-ring-handler handler state socket opts)
                     :body       (read-body-stream state socket buffer)
                     :error      (write-error-response state socket opts)
                     nil)]
           (recur new-state)
           state)))
      ([{::keys [step] :as state} exception]
       (when exception (error-logger exception))
       (case step
         :body (close-response state exception)
         nil)))))

(defn- async-handler [handler]
  (fn [request respond raise]
    (handler request #(respond % true) raise)))

(defn- sync-handler [handler]
  (fn [request respond raise]
    (let [response (try (handler request)
                        (catch Exception ex (raise ex) ::error))]
      (when (not= response ::error)
        (respond response false)))))

(defn- default-error-handler [_request respond _exception]
  (respond {:status  500
            :headers {"Content-Type" "text/plain; charset=UTF-8"}
            :body    "Internal Server Error"}))

(defn- default-error-logger [exception]
  (locking *err* (binding [*out* *err*]) (prn exception)))

(defn- new-default-executor []
  (Executors/newVirtualThreadPerTaskExecutor))

(defn- new-default-options []
  (let [executor (new-default-executor)]
    {:body-buffer-size     8192
     :read-buffer-size     8192
     :response-buffer-size 32768
     :error-handler        default-error-handler
     :error-logger         default-error-logger
     :handler-executor     executor
     :socket-executor      executor}))

(defn start-server ^Closeable [handler options]
  (let [handler-opts (merge (new-default-options) options)
        handler      (if (:async? handler-opts)
                       (async-handler handler)
                       (sync-handler handler))]
    (tcp/start-server
     (-> options
         (assoc :executor (:socket-executor options))
         (assoc :handler (http-handler handler handler-opts))))))

(comment
  (require '[criterium.core :as c])

  (defn re-find-fast [^java.util.regex.Pattern re s]
    (.find (re-matcher re s)))

  (re-find-fast #"aa" "abb")

  (c/quick-bench (re-find-fast #"aa" "baab"))

  (defn- write-response-head-1
    [^ByteBuffer buffer request {:keys [headers] :as response} lc-headers]
    (write-status-line buffer request response)
    (when-not (lc-headers "date")
      (write-date-header buffer))
    (when-not (lc-headers "server")
      (.put buffer ^bytes server-header))
    (doseq [kv headers]
      (let [value (val kv)]
        (if (vector? value)
          (doseq [v value] (write-header buffer (key kv) v))
          (write-header buffer (key kv) value)))))

  (defn- write-response-head-2
    [^ByteBuffer buffer request {:keys [headers] :as response} lc-headers]
    (write-status-line buffer request response)
    (let [headers (cond-> headers
                    (not (lc-headers "date"))
                    (assoc "Date" (rfc-1123-date-time))
                    (not (lc-headers "server"))
                    (assoc "Server" "Capra"))]
      (doseq [kv headers]
        (let [value (val kv)]
          (if (vector? value)
            (doseq [v value] (write-header buffer (key kv) v))
            (write-header buffer (key kv) value))))))

  (def request  {:protocol "HTTP/1.1"})
  (def response {:status 200
                 :headers {"Content-Type" "text/plain; charset=UTF-8"}})
  (def buffer   (ByteBuffer/allocate 256))
  (def headers  (lowercase-headers (:headers response)))

  (c/quick-bench
   (do (.clear ^ByteBuffer buffer)
       (write-response-head-1 buffer request response headers)))

  (require '[org.httpkit.server :as hk])

  (defn simple-handler [_request]
    {:status  200
     :headers {"Content-Type" "text/plain; charset=UTF-8"}
     :body    "Hello World"})

  (def capra-server   (start-server simple-handler {:port 6201}))
  (def httpkit-server (hk/run-server simple-handler {:port 6202}))

  (.close capra-server))
