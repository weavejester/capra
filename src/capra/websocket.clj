(ns capra.websocket
  (:require [ring.websocket.protocols :as ws]
            [teensyp.server :as tcp])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.channels SelectionKey]))

(defn- put-payload-length [^ByteBuffer buf len]
  (cond
    (< len 126)   (.put buf (unchecked-byte len))
    (< len 65536) (do (.put buf (unchecked-byte 126))
                      (.putShort buf (unchecked-short len)))
    :else         (do (.put buf (unchecked-byte 127))
                      (.putLong buf len))))

(defn- frame-header [^long opcode ^ByteBuffer payload]
  (doto (ByteBuffer/allocate 10)
    (.put (unchecked-byte (bit-or 0x80 opcode)))
    (put-payload-length (.remaining payload))
    (.flip)))

(defn- send-message [socket ^long opcode ^ByteBuffer payload]
  (tcp/write socket (frame-header opcode payload))
  (tcp/write socket payload))

(defn- utf-8->buffer [^String s]
  (ByteBuffer/wrap (.getBytes s StandardCharsets/UTF_8)))

(defn- close-message [^long code ^String reason]
  (let [bs  (.getBytes reason StandardCharsets/UTF_8)
        buf (ByteBuffer/allocate (+ 2 (alength bs)))]
    (doto buf
      (.putShort (unchecked-short code))
      (.put bs)
      (.flip))))

(extend-protocol ws/Socket
  SelectionKey
  (-open? [socket] (.isValid socket))
  (-send [socket message]
    (if (string? message)
      (send-message socket 0x1 (utf-8->buffer message))
      (send-message socket 0x2 message)))
  (-ping [socket message]
    (send-message socket 0x9 message))
  (-pong [socket message]
    (send-message socket 0xA message))
  (-close [socket code reason]
    (send-message socket 0x8 (close-message code reason))
    (tcp/close socket)))

(defn init-websocket [listener]
  (transient {::step :open, ::listener listener}))

(defn- high-bit? [^long b]
  (not (zero? (bit-and b 0x80))))

(defn- valid-first-byte? [^long b]
  (case b (0 1 2 -128 -127 -126 -120 -119 -118) true false))

(defn- invalid-first-byte [b]
  (ex-info (format "Invalid first frame byte: 0x%02X" b)
           {:error :invalid-first-byte, :byte b, :close-code 1002}))

(defn- read-fin+opcode [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [b (.get buffer)]
      (when-not (valid-first-byte? b) (throw (invalid-first-byte b)))
      (-> state
          (assoc! ::finished? (high-bit? b))
          (assoc! ::opcode    (bit-and b 0x0F))
          (assoc! ::step      :masked+length)))))

(defn- get-unsigned-short [^ByteBuffer buffer]
  (when (>= (.remaining buffer) 2)
    (-> buffer .getShort (bit-and 0xFFFF))))

(defn- get-long [^ByteBuffer buffer]
  (when (>= (.remaining buffer) 8)
    (.getLong buffer)))

(defn- limited-length-opcode? [^long opcode]
  (case opcode (0x8 0x9 0xA) true false))

(defn- invalid-length [opcode len]
  (ex-info (format "Frame length %d too long for opcode 0x%01X" len opcode)
           {:error :invalid-length, :len len, :opcode opcode
            :close-code 1002}))

(defn- payload-length [{::keys [opcode]} ^long b ^ByteBuffer buffer]
  (let [len (bit-and b 0x7F)]
    (when (and (> len 125) (limited-length-opcode? opcode))
      (throw (invalid-length opcode len)))
    (case len
      126 (get-unsigned-short buffer)
      127 (get-long buffer)
      len)))

(defn- message-too-big [len max-len]
  (ex-info (format "Message length %s exceeds max length %d" len max-len)
           {:error :message-too-big, :len len, :max-len max-len
            :close-code 1009}))

(defn- read-masked+length [state ^ByteBuffer buffer max-len]
  (when (.hasRemaining buffer)
    (let [b       (.get buffer)
          masked? (high-bit? b)]
      (when-some [len (payload-length state b buffer)]
        (when (and max-len (> len max-len))
          (throw (message-too-big len max-len)))
        (-> state
            (assoc! ::masked? masked?)
            (assoc! ::payload (ByteBuffer/allocate len))
            (assoc! ::step    (if masked? :mask :payload)))))))

(defn- int->long-mask [^long mask]
  (let [mask (bit-and mask 0xFFFFFFFF)]
    (bit-or (bit-shift-left mask 32) mask)))

(defn- read-mask [state ^ByteBuffer buffer]
  (when (>= (.remaining buffer) 4)
    (-> state
        (assoc! ::mask (int->long-mask (.getInt buffer)))
        (assoc! ::step :payload))))

(defn- put-masked-bytes [^ByteBuffer dest ^ByteBuffer src ^long mask ^long len]
  (when (pos? len)
    (let [p (.position dest)]
      (dotimes [i len]
        (let [mask-shift (* 8 (- 3 (bit-and 3 (+ p i))))
              mask-byte  (unchecked-byte (bit-shift-right mask mask-shift))]
          (.put dest (unchecked-byte (bit-xor (.get src) mask-byte))))))))

(defn- put-masked [^ByteBuffer dest ^ByteBuffer src ^long mask]
  (let [len (min (.remaining dest) (.remaining src))]
    (when (pos? len)
      (let [start-bytes (min len (bit-and (- (.position dest)) 7))
            num-words   (bit-shift-right (- len start-bytes) 3)]
        (put-masked-bytes dest src mask start-bytes)
        (dotimes [_ num-words]
          (.putLong dest (bit-xor (.getLong src) mask)))
        (put-masked-bytes dest src mask (bit-and (- len start-bytes) 7))))))

(defn- read-payload [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [^ByteBuffer payload (::payload state)]
      (if-some [mask (::mask state)]
        (put-masked payload buffer mask)
        (.put payload buffer))
      (when-not (.hasRemaining payload)
        (assoc! state ::step (if (::finished? state) :complete :fin+opcode))))))

(defn- buffer->utf-8 [^ByteBuffer buf]
  (String. (.array buf) (.arrayOffset buf) (.limit buf) StandardCharsets/UTF_8))

(defn- on-open [{::keys [listener] :as state} socket]
  (ws/on-open listener socket)
  (assoc! state ::step :fin+opcode))

(defn- on-text [{::keys [listener payload]} socket]
  (ws/on-message listener socket (buffer->utf-8 payload)))

(defn- on-binary [{::keys [listener ^ByteBuffer payload]} socket]
  (ws/on-message listener socket (.flip payload)))

(defn- invalid-close-code [code]
  (ex-info
   (format "Close code %d is invalid (must be between 1000 and 4999)" code)
   {:error :invalid-close-code, :code code, :close-code 1002}))

(defn- on-close [{::keys [listener ^ByteBuffer payload]} socket]
  (let [code (-> payload .flip .getShort)]
    (when (or (< code 1000) (> code 4999))
      (throw (invalid-close-code code)))
    (let [reason (-> payload .slice buffer->utf-8)]
      (ws/on-close listener socket code reason))))

(defn- on-pong [{::keys [listener ^ByteBuffer payload]} socket]
  (ws/on-pong listener socket (.flip payload)))

(defn- on-ping [{::keys [listener ^ByteBuffer payload]} socket]
  (ws/-pong socket (-> payload .flip .duplicate))
  (when (satisfies? ws/PingListener listener)
    (ws/on-ping listener socket payload)))

(defn- deliver-message [{::keys [opcode listener] :as state} socket]
  (case (byte opcode)
    0x1 (on-text   state socket)
    0x2 (on-binary state socket)
    0x8 (on-close  state socket)
    0x9 (on-ping   state socket)
    0xA (on-pong   state socket))
  (transient {::step :fin+opcode, ::listener listener}))

(defn read-websocket-frame
  [state socket buffer {max-len :ws-message-max-size}]
  (try (loop [state state, changes 0]
         (if-some [new-state
                   (case (::step state)
                     :open          (on-open state socket)
                     :fin+opcode    (read-fin+opcode state buffer)
                     :masked+length (read-masked+length state buffer max-len)
                     :mask          (read-mask state buffer)
                     :payload       (read-payload state buffer)
                     :complete      (deliver-message state socket)
                     nil)]
           (recur new-state (inc changes))
           (when (pos? changes) state)))
       (catch clojure.lang.ExceptionInfo ex
         (ws/on-error (::listener state) socket ex)
         (ws/-close socket (:close-code (ex-data ex) 1011)
                    (ex-message ex))
         nil)
       (catch Exception ex
         (ws/on-error (::listener state) socket ex)
         (ws/-close socket 1011 "Internal error")
         nil)))
