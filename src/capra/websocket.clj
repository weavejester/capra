(ns capra.websocket
  (:require [ring.websocket.protocols :as ws])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(defn init-websocket [listener]
  (transient {:capra.http/step :websocket
              ::step           :fin+opcode
              ::listener       listener}))

(defn- high-bit? [^long b]
  (not (zero? (bit-and b 0x80))))

(defn- opcode [^long b]
  (bit-and b 0x0F))

(defn- read-fin+opcode [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (let [b (.get buffer)]
      (-> state
          (assoc! ::finished? (high-bit? b))
          (assoc! ::opcode (opcode b))
          (assoc! ::step :masked+length)))))

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
            (assoc! ::step (if masked? :mask :payload)))))))

(defn- read-mask [state ^ByteBuffer buffer]
  (when (>= (.remaining buffer) 4)
    (let [mask (byte-array 4)]
      (.get buffer ^bytes mask)
      (-> state
          (assoc! ::mask mask)
          (assoc! ::step :payload)))))

(defn- put-masked [^ByteBuffer payload ^ByteBuffer buffer ^bytes mask]
  (let [pos   (.position payload)
        limit (+ pos (.remaining buffer))]
    (loop [i pos]
      (when (< i limit)
        (.put payload (byte (bit-xor (.get buffer) (aget mask (mod i 4)))))
        (recur (inc i))))))

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

(defn- deliver-message [{::keys [opcode listener] :as state} socket]
  (case (byte opcode)
    0x1 (on-text   state socket)
    0x2 (on-binary state socket)
    0x8 (on-close  state socket) 
    0xA (on-pong   state socket))
  (init-websocket listener))

(defn read-websocket-frame [state socket buffer]
  (loop [state state, changes 0]
    (if-some [new-state
              (case (::step state)
                :fin+opcode    (read-fin+opcode state buffer)
                :masked+length (read-masked+length state buffer)
                :mask          (read-mask state buffer)
                :payload       (read-payload state buffer)
                :complete      (deliver-message state socket)
                nil)]
      (recur new-state (inc changes))
      (when (pos? changes) state))))
