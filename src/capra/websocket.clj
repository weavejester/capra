(ns capra.websocket
  (:require [ring.websocket.protocols :as ws])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))

;; Websocket opcodes
(def ^:private ^:const CONTINUE 0x0)
(def ^:private ^:const TEXT     0x1)
(def ^:private ^:const BINARY   0x2)
(def ^:private ^:const CLOSE    0x8)
(def ^:private ^:const PING     0x9)
(def ^:private ^:const PONG     0xA)

(def ^:private ^:const sec-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- sec-websocket-accept [key]
  (let [bytes (-> (str key sec-guid) (.getBytes StandardCharsets/UTF_8))
        hash  (-> (MessageDigest/getInstance "SHA-1") (.digest bytes))]
    (-> (Base64/getEncoder) (.encodeToString hash))))

(defn write-websocket-response [request response buffer])

(defn- high-bit? [^byte b]
  (not (zero? (bit-and b 0x80))))

(defn- opcode [^byte b]
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
  (when (>= (.remaining buffer) 4)
    (.getLong buffer)))

(defn- payload-length [^byte b ^ByteBuffer buffer]
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

(defn- read-mask [{::keys [masked?] :as state} ^ByteBuffer buffer]
  (when (and masked? (>= (.remaining buffer) 4))
    (let [mask (byte-array 4)]
      (.get buffer ^bytes mask)
      (assoc! state ::mask mask))))

(defn- read-payload [state ^ByteBuffer buffer]
  (when (.hasRemaining buffer)
    (.get buffer ^ByteBuffer (::payload state))
    (when-not (.hasRemaining buffer)
      (assoc! state ::step (if (::finished? state) :complete :fin+opcode)))))

(defn- buffer->utf-8 [^ByteBuffer buf]
  (String. (.array buf) StandardCharsets/UTF_8))

(defn- deliver-message [{::keys [listener opcode payload]} socket]
  (case opcode
    TEXT   (ws/on-message listener socket (buffer->utf-8 payload))
    BINARY (ws/on-message listener socket payload)
    PONG   (ws/on-pong    listener socket payload)
    CLOSE  (ws/on-close   listener socket 1000 "")))

(defn read-websocket-frame [state socket buffer]
  (loop [state state]
    (if-some [new-state
              (case (::step state)
                :fin+opcode    (read-fin+opcode state buffer)
                :masked+length (read-masked+length state buffer)
                :mask          (read-mask state buffer)
                :payload       (read-payload state buffer)
                :complete      (deliver-message state socket)
                (transient {::step :fin+opcode}))]
      (recur new-state)
      state)))
