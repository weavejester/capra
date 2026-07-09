(ns capra.server
  "The namespace for running the Capra server."
  (:require [capra.http.error :as err]
            [capra.http.reason :as reason]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.concurrent :refer [with-lock]]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.io Closeable File FileInputStream InputStream OutputStream]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.time ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent Executors]
           [java.util.concurrent.atomic AtomicInteger]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private ^:const SPACE 0x20)
(def ^:private ^:const COLON 0x3A)
(def ^:private ^:const CR 0x0D)
(def ^:private ^:const LF 0x0A)

(defn- ascii-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/US_ASCII))

(def ^:private http-1-1       (ascii-bytes "HTTP/1.1 "))
(def ^:private empty-chunk    (ascii-bytes "0\r\n\r\n"))
(def ^:private server-header  (ascii-bytes "Server: Capra\r\n"))
(def ^:private length-header  (ascii-bytes "Content-Length: "))
(def ^:private date-header    (ascii-bytes "Date: "))
(def ^:private close-header   (ascii-bytes "Connection: close\r\n"))
(def ^:private crlf           (ascii-bytes "\r\n"))
(def ^:private chunked-header
  (ascii-bytes (str "Transfer-Encoding: chunked\r\n"
                    "Connection: Transfer-Encoding\r\n")))

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    (transient {::step       :start-line
                :scheme      :http
                :server-port (.getPort local)
                :server-name (.getHostString local)
                :remote-addr (.getHostString remote)})))

(defmacro ^:private when-pos [[sym expr & clauses] & body]
  `(let [~sym ~expr]
     (if (pos? ~sym)
       ~(if (seq clauses)
          `(when-pos ~(vec clauses) ~@body)
          `(do ~@body)))))

(defmacro ^:private if-pos {:clj-kondo/lint-as 'clojure.core/let}
  [clauses then else]
  `(or (when-pos ~clauses ~then) ~else))

(defn- parse-start-line [state ^String line]
  (if-pos [space1 (.indexOf line SPACE)
           space2 (.indexOf line SPACE (inc space1))]
    (assoc! state
            ::step          :headers
            :request-method (keyword (str/lower-case (subs line 0 space1)))
            :uri            (subs line (inc space1) space2)
            :protocol       (subs line (inc space2))
            :headers        (transient {}))
    {::step  :error
     ::error :invalid-request-start-line}))

(defn- read-start-line [state ^ByteBuffer buffer ^long max-buffer-size]
  (if-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (let [{:keys [protocol] :as state} (parse-start-line state line)]
      (if (or (nil? protocol) (= protocol "HTTP/1.1"))
        state
        {::step    :error
         ::error   :http-version-not-supported
         ::request {:bad-protocol protocol}}))
    (when-not (< (.limit buffer) max-buffer-size)
      {::step :error, ::error :uri-too-long})))

(defn- assoc-request-header! [headers name value]
  (if-some [existing-val (headers name)]
    (assoc! headers name (str existing-val "," value))
    (assoc! headers name value)))

(defn- parse-header [{:keys [headers] :as state} ^String line]
  (if-pos [colon-index (.indexOf line COLON)]
    (let [name  (str/lower-case (subs line 0 colon-index))
          value (str/trim (subs line (inc colon-index)))]
      (assoc! state :headers (assoc-request-header! headers name value)))
    {::step    :error
     ::error   :invalid-request-header
     ::request {:bad-header line}}))

(defn- read-header [{:keys [headers] :as state} buffer ^long max-buffer-size]
  (if-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (= line "")
      (assoc! state ::step :handler, :headers (persistent! headers))
      (parse-header state line))
    (when-not (< (.limit ^ByteBuffer buffer) max-buffer-size)
      {::step :error, ::error :request-header-field-too-large})))

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(defn- write-crlf [^ByteBuffer buffer]
  (.put buffer (byte CR))
  (.put buffer (byte LF)))

(defn- chunked-output-stream ^OutputStream [^OutputStream out]
  (let [lock   (ReentrantLock.)
        closed (volatile! false)]
    (stream/output-stream
     (fn write [^bytes b off len]
       (let [header (ascii-bytes (format "%X\r\n" len))]
         (with-lock lock
           (.write out header)
           (.write out b off len)
           (.write out ^bytes crlf))))
     (fn close []
       (with-lock lock
         (when-not @closed
           (vreset! closed true)
           (.write out ^bytes empty-chunk)
           (.close out)))))))

(defn- limited-output-stream ^OutputStream [^OutputStream out limit socket]
  (let [limit (AtomicInteger. limit)]
    (stream/output-stream
     (fn write [^bytes b off ^long len]
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

(defn- run-writer [writerf socket ^ByteBuffer buffer callback]
  (if (writerf buffer)
    (do (tcp/write socket (.flip buffer))
        (callback))
    (tcp/write socket (.flip buffer)
               #(run-writer writerf socket (.clear buffer) callback))))

(defn- copy-buffer [^ByteBuffer src dest]
  (buf/copy src dest)
  (not (.hasRemaining src)))

(defn- bytes-writer [^bytes bs off len]
  (let [read-buf (ByteBuffer/wrap bs off len)]
    (fn [write-buf] (copy-buffer read-buf write-buf))))

(defn- file-writer [^FileChannel ch]
  (fn [^ByteBuffer write-buf] (neg? (.read ch write-buf))))

(defn- limit-buffer [f ^ByteBuffer buffer ^long new-limit]
  (let [limit (.limit buffer)]
    (.limit buffer new-limit)
    (try (f buffer) (finally (.limit buffer limit)))))

(defn- limit-writer [writerf len]
  (let [bytes-left (volatile! len)]
    (fn [^ByteBuffer write-buf]
      (let [^long len @bytes-left]
        (or (not (pos? len))
            (let [pos     (.position write-buf)
                  done?   (if (< len (.remaining write-buf))
                            (limit-buffer writerf write-buf (+ pos len))
                            (writerf write-buf))]
              (or done? (let [len (- len (- (.position write-buf) pos))]
                          (vreset! bytes-left len)
                          (not (pos? len))))))))))

(def ^:private end-chunk (ascii-bytes "\r\n0\r\n\r\n"))

(defn- chunk-writer [writerf len]
  (let [header (ByteBuffer/wrap (ascii-bytes (format "%X\r\n" len)))
        end    (ByteBuffer/wrap end-chunk)
        index  (volatile! 0)]
    (fn [write-buf]
      (let [idx (long @index)]
        (when (case idx
                0 (copy-buffer header write-buf)
                1 (writerf write-buf)
                2 (copy-buffer end write-buf))
          (vreset! index (inc idx))
          (>= idx 2))))))

(defn- write-known-length-to-socket [socket headers buffer writerf len callback]
  (cond
    (headers "content-length")
    (let [^long content-len (content-length headers)]
      (write-crlf buffer)
      (cond
        (= content-len ^long len)
        (run-writer writerf socket buffer callback)
        (< content-len ^long len)
        (run-writer (limit-writer writerf content-len) socket buffer callback)
        :else
        (do (run-writer writerf socket buffer callback)
            (tcp/close socket))))
    (chunked-transfer? headers)
    (do (write-crlf buffer)
        (run-writer (chunk-writer writerf len) socket buffer callback))
    :else
    (do (.put ^ByteBuffer buffer ^bytes length-header)
        (write-ascii buffer (str len))
        (write-crlf buffer)
        (write-crlf buffer)
        (run-writer writerf socket buffer callback))))

(defprotocol ResponseBody
  (write-body-to-socket [body response headers buffer socket async? callback]))

(extend (Class/forName "[B")
  ResponseBody
  {:write-body-to-socket
   (fn [^bytes body _response headers buffer socket _async? callback]
     (let [len     (alength body)
           writerf (bytes-writer body 0 len)]
       (write-known-length-to-socket socket headers buffer
                                     writerf len callback)))})

(extend-protocol ResponseBody
  String
  (write-body-to-socket [body response headers buffer socket async? callback]
    (let [^String charset (content-charset headers)
          body-bytes      (.getBytes body (or charset "UTF-8"))]
      (write-body-to-socket body-bytes response headers
                            buffer socket async? callback)))
  File
  (write-body-to-socket [body _response headers buffer socket _async? callback]
    (let [file-ch (.getChannel (FileInputStream. body))]
      (write-known-length-to-socket socket headers buffer (file-writer file-ch)
                                    (.size file-ch) callback)))
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
  (write-body-to-socket [_body _response headers buffer socket _async? callback]
    (let [writerf (constantly true)]
      (write-known-length-to-socket socket headers buffer writerf 0 callback))))

(defn- rfc-1123-date-time []
  (.format (ZonedDateTime/now ZoneOffset/UTC)
           DateTimeFormatter/RFC_1123_DATE_TIME))

(defn- write-status-line
  [^ByteBuffer buffer {:keys [status]}]
  (doto buffer
    (.put ^bytes http-1-1)
    (write-ascii (str status))
    (.put (byte SPACE))
    (write-ascii (reason/status->reason status))
    (.put (byte CR))
    (.put (byte LF))))

(defn- write-header [^ByteBuffer buffer k v]
  (doto buffer
    (.put (ascii-bytes k))
    (.put (byte COLON))
    (.put (byte SPACE))
    (.put (ascii-bytes v))
    (.put (byte CR))
    (.put (byte LF))))

(defn- write-date-header [^ByteBuffer buffer]
  (.put buffer ^bytes date-header)
  (write-ascii buffer (rfc-1123-date-time))
  (write-crlf buffer))

(defn- write-conn-header [^ByteBuffer buffer close?]
  (when close? (.put buffer ^bytes close-header)))

(defn- write-response-head
  [^ByteBuffer buffer {:keys [headers] :as response} lc-headers close?]
  (write-status-line buffer response)
  (when-not (lc-headers "connection") (write-conn-header buffer close?))
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

(defn- assoc-response-header! [headers name value]
  (assoc! headers
          (str/lower-case name)
          (if (string? value) value (str/join "," value))))

(defn- normalize-headers [headers]
  (persistent! (reduce-kv assoc-response-header! (transient {}) headers)))

(def ^:private re-close-connection #"(?i)(^| *,)close( *,|$)")

(defn- connection-close? [{:strs [connection]}]
  (when connection (.find (re-matcher re-close-connection connection))))

(defn- ring-responder [request socket handled {buf-size :response-buffer-size}]
  (fn respond [{:keys [headers body] :as response} async?]
    (when (compare-and-set! handled false true)
      (let [buffer  (get-cached response-buffer #(ByteBuffer/allocate buf-size))
            headers (normalize-headers headers)
            close?  (connection-close? (:headers request))]
        (.clear ^ByteBuffer buffer)
        (write-response-head buffer response headers close?)
        (write-body-to-socket body response headers buffer socket async?
                              #(when (or close? (connection-close? headers))
                                 (tcp/close socket)))))))

(defn- ring-raiser [request respond {:keys [error-handler error-logger]}]
  (fn [exception]
    (error-logger exception)
    (error-handler request #(respond % true) exception)))

(defn- valid-transfer-encoding? [{{encoding "transfer-encoding"} :headers}]
  (or (nil? encoding) (.equalsIgnoreCase "chunked" encoding)))

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
    (not (contains? (:headers request) "host"))
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

(defn- limit-buffer-to-length [^ByteBuffer buffer ^long length]
  (if (< length (.remaining buffer))
    (doto (.duplicate buffer)
      (.limit (+ (.position buffer) ^long length)))
    buffer))

(defn- read-known-length-body-stream
  [{::keys [handler ^long length state] :as st} socket ^ByteBuffer buffer]
  (if (pos? length)
    (when (.hasRemaining buffer)
      (let [capped-buffer ^ByteBuffer (limit-buffer-to-length buffer length)
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
  [handler {:keys [error-logger stream-buffer-size]
            max-buf-size :read-buffer-size
            :as   options}]
  (let [opts (assoc options :read-buffer-size stream-buffer-size)]
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

(defn- maybe-virtual-thread-executor  []
  (try (eval '(Executors/newVirtualThreadPerTaskExecutor))
       (catch Throwable _ nil)))

(defn- new-default-executor []
  (or (maybe-virtual-thread-executor)
      (Executors/newFixedThreadPool 256)))

(defn- new-default-options []
  {:direct-read-buffer?  true
   :error-handler        default-error-handler
   :error-logger         default-error-logger
   :executor             (new-default-executor)
   :port                 80
   :read-buffer-size     8192
   :response-buffer-size 32768
   :stream-buffer-size   8192})

(defn run-server
  "Start a web server in a new thread with the supplied Ring handler and
  options. Returns a `java.io.Closeable` instance that can be used to stop the
  adapter via the `close` method.
  
  Accepts the following options:

  - `:async?` - if true, expect 3-arity async Ring handlers (defaults to false)
  - `:control-queue-size` - the max number of queued control events (default 32)
  - `:direct-read-buffer?` - allocate a direct buffer for reads (default true)
  - `:error-handler` - an asynchronous Ring handler function used to handle
    uncaught exceptions (defaults to sending a 500 Internal Server Error).
  - `:error-logger` - a function that takes a single exception argument and
    logs it somehow (defaults to printing to *err*)
  - `:executor` - the ExecutorService to use for running handlers
  - `:port` - the port number to listen on (defaults to 80)
  - `:read-buffer-size` - the size of the buffer to use when reading from the
    socket (defaults to 8K)
  - `:recv-buffer-size` - the receive buffer size (i.e. the SO_RCVBUF option)
  - `:response-buffer-size` - the size of the buffer used when constructing
    the response, which must be at least large enough to contain the response
    status line and headers (defaults to 32K)
  - `:reuse-address?` - sets the SO_REUSEADDR socket option (default false)
  - `:stream-buffer-size` - the size of the buffer used to read in the body of
    the request when streaming (defaults to 8K)
  - `:write-buffer-size` - the write buffer size in bytes (default 32K)
  - `:write-queue-size` - the max number of writes in the queue (default 64)"
  ^Closeable [handler & {:as options}]
  (let [options (merge (new-default-options) options)
        handler (if (:async? options)
                  (async-handler handler)
                  (sync-handler handler))]
    (tcp/run-server
     (assoc options :handler (http-handler handler options)))))

(comment
  (require '[criterium.core :as c])

  (c/quick-bench (+ 1 1))

  (defn simple-handler [_request]
    {:status  200
     :headers {"Content-Type" "text/plain; charset=UTF-8"}
     :body    "Hello World"})

  (def capra-server (run-server simple-handler {:port 6201}))

  (.close capra-server))
