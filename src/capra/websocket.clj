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

(defn- read-fin+opcode [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [b (.get buffer)]
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

(defn- payload-length [^long b ^ByteBuffer buffer]
  (let [len (bit-and b 0x7F)]
    (case len
      126 (get-unsigned-short buffer)
      127 (get-long buffer)
      len)))

(defn- read-masked+length [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [b       (.get buffer)
          masked? (high-bit? b)]
      (when-some [len (payload-length b buffer)]
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
  (let [p (.position dest)]
    (dotimes [i len]
      (let [mask-shift (* 8 (- 3 (bit-and 3 (+ p i))))
            mask-byte  (unchecked-byte (bit-shift-right mask mask-shift))]
        (.put dest (unchecked-byte (bit-xor (.get src) mask-byte)))))))

(defn- put-masked [^ByteBuffer dest ^ByteBuffer src ^long mask]
  (let [len         (min (.remaining dest) (.remaining src))
        start-bytes (bit-and (- (.position dest)) 7)
        num-words   (bit-shift-right (- len start-bytes) 3)]
    (put-masked-bytes dest src mask start-bytes)
    (dotimes [_ num-words]
      (.putLong dest (bit-xor (.getLong src) mask)))
    (put-masked-bytes dest src mask (bit-and (- len start-bytes) 7))))

(defn- read-payload [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [^ByteBuffer payload (::payload state)]
      (if-some [mask (::mask state)]
        (put-masked payload buffer mask)
        (.put payload buffer)))
    (when-not (.hasRemaining buffer)
      (assoc! state ::step (if (::finished? state) :complete :fin+opcode)))))

(defn- buffer->utf-8 [^ByteBuffer buf]
  (String. (.array buf) (.arrayOffset buf) (.limit buf) StandardCharsets/UTF_8))

(defn- on-open [{::keys [listener] :as state} socket]
  (ws/on-open listener socket)
  (assoc! state ::step :fin+opcode))

(defn- on-text [{::keys [listener payload]} socket]
  (ws/on-message listener socket (buffer->utf-8 payload)))

(defn- on-binary [{::keys [listener ^ByteBuffer payload]} socket]
  (ws/on-message listener socket (.flip payload)))

(defn- on-close [{::keys [listener ^ByteBuffer payload]} socket]
  (let [exit-code (-> payload .flip .getShort)
        reason    (-> payload .slice buffer->utf-8)]
    (ws/on-close listener socket exit-code reason)))

(defn- on-pong [{::keys [listener ^ByteBuffer payload]} socket]
  (ws/on-pong listener socket (.flip payload)))

(defn- on-ping [{::keys [listener ^ByteBuffer payload]} socket]
  (when (satisfies? ws/PingListener listener)
    (ws/on-ping listener socket (.flip payload))))

(defn- deliver-message [{::keys [opcode listener] :as state} socket]
  (case (byte opcode)
    0x1 (on-text   state socket)
    0x2 (on-binary state socket)
    0x8 (on-close  state socket)
    0x9 (on-ping   state socket)
    0xA (on-pong   state socket))
  (transient {::step :fin+opcode, ::listener listener}))

(defn read-websocket-frame [state socket buffer]
  (loop [state state, changes 0]
    (if-some [new-state
              (try
                (case (::step state)
                  :open          (on-open state socket)
                  :fin+opcode    (read-fin+opcode state buffer)
                  :masked+length (read-masked+length state buffer)
                  :mask          (read-mask state buffer)
                  :payload       (read-payload state buffer)
                  :complete      (deliver-message state socket)
                  nil)
                (catch Exception ex
                  (ws/on-error (::listener state) socket ex)
                  nil))]
      (recur new-state (inc changes))
      (when (pos? changes) state))))
