(ns ^:no-doc capra.http
  "Internal functions for dealing with HTTP/1.1."
  (:require [capra.http.error :as err]
            [capra.http.reason :as reason]
            [capra.websocket :as ws]
            [clojure.string :as str]
            [ring.core.protocols :as ring]
            [teensyp.buffer :as buf]
            [teensyp.concurrent :refer [with-lock]]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.io File FileInputStream InputStream OutputStream]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels Channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Base64]
           [java.util.concurrent.atomic AtomicInteger]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private ^:const SPACE 0x20)
(def ^:private ^:const COLON 0x3A)
(def ^:private ^:const CR 0x0D)
(def ^:private ^:const LF 0x0A)
(def ^:private ^:const QUESTIONMARK 0x3F)

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

(defn- request-keyword [s]
  (keyword (str/lower-case s)))

(defn- parse-start-line [state ^String line]
  (if-pos [space1 (.indexOf line SPACE)
           space2 (.indexOf line SPACE (inc space1))]
    (let [query  (.indexOf line QUESTIONMARK (inc space1))
          query? (and (> query space1) (< query space2))]
      (-> state
          (assoc! ::step :headers)
          (assoc! :request-method (request-keyword (subs line 0 space1)))
          (assoc! :uri (subs line (inc space1) (if query? query space2)))
          (cond-> query?
            (assoc! :query-string (subs line (inc query) space2)))
          (assoc! :protocol (subs line (inc space2)))
          (assoc! :headers (transient {}))))
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

(defn- write-buffer-to-stream [^OutputStream out ^ByteBuffer buf ^long len]
  (if (.hasArray buf)
    (let [off (+ (.arrayOffset buf) (.position buf))]
      (.write out (.array buf) off len)
      (.position buf (.limit buf)))
    (let [ch (Channels/newChannel out)]
      (while (.hasRemaining buf)
        (.write ch buf)))))

(defn- chunked-output-stream ^OutputStream [^OutputStream out]
  (let [lock   (ReentrantLock.)
        closed (volatile! false)]
    (stream/output-stream
     (fn write [^ByteBuffer buf]
       (let [len    (.remaining buf)
             header (ascii-bytes (format "%X\r\n" len))]
         (with-lock lock
           (.write out header)
           (write-buffer-to-stream out buf len)
           (.write out ^bytes crlf)
           (+ (alength header) len 2))))
     (fn close []
       (with-lock lock
         (when-not @closed
           (vreset! closed true)
           (.write out ^bytes empty-chunk)
           (.close out)))))))

(defn- limited-output-stream ^OutputStream [^OutputStream out limit socket]
  (let [limit (AtomicInteger. limit)]
    (stream/output-stream
     (fn write [^ByteBuffer buf]
       (let [r   (.remaining buf)
             len (min r (+ r (.addAndGet limit (- r))))]
         (when (pos? len)
           (write-buffer-to-stream out buf len))
         len))
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
  (when content-type
    (when-some [m (re-find re-charset content-type)]
      (or (m 1) (m 2)))))

;; Writer functions add to a buffer and return true if there is nothing more
;; to write, and false otherwise. They are an internal abstraction that allows
;; different response body types to share common code.

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
    (.put ^bytes http-1-1) (write-ascii (str status))
    (.put (byte SPACE)) (write-ascii (reason/status->reason status "Unknown"))
    (write-crlf)))

(defn- write-header [^ByteBuffer buffer k v]
  (doto buffer
    (.put (ascii-bytes k)) (.put (byte COLON)) (.put (byte SPACE))
    (.put (ascii-bytes v)) (write-crlf)))

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

(defn- write-http-response
  [request {:keys [headers body] :as response} buffer socket async? next-state]
  (let [headers (normalize-headers headers)
        close?  (connection-close? (:headers request))]
    (write-response-head buffer response headers close?)
    (write-body-to-socket body response headers buffer socket async?
                          #(do (when (or close? (connection-close? headers))
                                 (tcp/close socket))
                               (vreset! next-state (init-request socket))
                               (tcp/resume-reads socket)))))

(def ^:private ^:const sec-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- sec-websocket-accept [key]
  (let [bytes (-> (str key sec-guid) (.getBytes StandardCharsets/UTF_8))
        hash  (-> (MessageDigest/getInstance "SHA-1") (.digest bytes))]
    (-> (Base64/getEncoder) (.encodeToString hash))))

(def ^:private switch-protocol-bytes
  (ascii-bytes (str "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: upgrade\r\n"
                    "Sec-Websocket-Accept: ")))

(defn- write-websocket-response
  [{{:strs [sec-websocket-key]} :headers}
   {:ring.websocket/keys [listener]}
   ^ByteBuffer buffer socket next-state]
  (.put buffer ^bytes switch-protocol-bytes)
  (write-ascii buffer (sec-websocket-accept sec-websocket-key))
  (write-crlf buffer)
  (write-crlf buffer)
  (tcp/write socket (.flip buffer)
             #(vreset! next-state (ws/init-websocket listener))))

(defn- ring-responder
  [req socket handled next-state {buf-size :response-buffer-size}]
  (fn respond [resp async?]
    (when (compare-and-set! handled false true)
      (let [buffer (get-cached response-buffer #(ByteBuffer/allocate buf-size))]
        (.clear ^ByteBuffer buffer)
        (if (:ring.websocket/listener resp)
          (write-websocket-response req resp buffer socket next-state)
          (write-http-response req resp buffer socket async? next-state))))))

(defn- ring-raiser [request respond {:keys [error-handler error-logger]}]
  (fn [exception]
    (error-logger exception)
    (error-handler request #(respond % true) exception)))

(defn- valid-transfer-encoding? [{{encoding "transfer-encoding"} :headers}]
  (or (nil? encoding) (.equalsIgnoreCase "chunked" encoding)))

(defn- ring->stream-handler [ring-handler request done opts]
  (stream/input-stream-handler
   (fn [in socket]
     (let [handled (atom false)
           request (assoc request :body in)
           respond (ring-responder request socket handled done opts)
           raise   (ring-raiser request respond opts)]
       (ring-handler request respond raise)))
   opts))

(defn- run-streaming-handler [ring-handler request socket opts]
  (let [next-state (volatile! nil)
        req        (persistent! request)
        handler    (ring->stream-handler ring-handler req next-state opts)]
    (transient
     {::step       :body
      ::handler    handler
      ::state      (handler socket)
      ::next-state next-state
      ::chunked?   (chunked-transfer? (:headers req))
      ::length     (content-length (:headers req))})))

(defn- run-simple-handler [ring-handler request socket opts]
  (let [next-state (volatile! nil)
        handled    (atom false)
        body       (InputStream/nullInputStream)
        request    (persistent! (assoc! request :body body))
        respond    (ring-responder request socket handled next-state opts)
        raise      (ring-raiser request respond opts)]
    (ring-handler request respond raise)
    {::step :buffer, ::next-state next-state}))

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

(defn- next-request [{::keys [next-state handler state]}]
  (handler state nil)
  {::step :buffer, ::next-state next-state})

(defn- read-chunked-body-stream
  [{::keys [handler state] :as st} socket buffer]
  (when-some [chunk-buf (read-chunk! buffer)]
    (if (.hasRemaining chunk-buf)
      (do (handler state socket chunk-buf) st)
      (next-request st))))

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
    (next-request st)))

(defn- read-body-stream [{:keys [next-state] :as state} socket buffer]
  (cond
    (::length state)   (read-known-length-body-stream state socket buffer)
    (::chunked? state) (read-chunked-body-stream state socket buffer)
    :else              {::step :buffer, ::next-state next-state}))

(defn- close-response [{::keys [handler state]} exception]
  (handler state exception))

(defn- write-error-response [{::keys [error request]} socket opts]
  (let [handled (atom false)
        respond (ring-responder request socket handled (volatile! nil) opts)
        handler (err/error-handlers error)]
    (respond (handler request) false)
    (tcp/close socket)
    nil))

(defn tcp-handler
  "Create a TeensyP handler from a Ring handler."
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
                     :buffer     (deref (::next-state state))
                     :websocket  (ws/read-websocket-frame state socket buffer)
                     :error      (write-error-response state socket opts)
                     nil)]
           (recur new-state)
           state)))
      ([{::keys [step] :as state} exception]
       (when exception (error-logger exception))
       (case step
         :body (close-response state exception)
         nil)))))
