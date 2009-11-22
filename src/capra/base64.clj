(ns capra.base64
  "Base64 encoding library."
  (:refer-clojure :exclude [->>]))

(defmacro #^{:private true} ->>
  "Backported from Clojure HEAD."
  ([x form] (if (seq? form)
              `(~(first form) ~@(next form)  ~x)
              (list form x)))
  ([x form & more] `(->> (->> ~x ~form) ~@more)))

(def base64-chars
  (vec "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"))

(defn- bytes->int
  "Bitshift three bytes into an integer."
  [[x y z]]
  (+ (bit-shift-left x 16)
     (bit-shift-left y 8)
     z))

(defn- encode-bits
  "Encode part of an integer into a Base64 character."
  [i n]
  (base64-chars (bit-and (bit-shift-right i n) 0x3f)))

(defn- encode-padded-bytes
  "Encode a collection of padded bytes into Base64."
  [bytes]
  (mapcat
    (fn [chunk]
      (let [i (bytes->int chunk)]
         (map #(encode-bits i %) [18 12 6 0])))
    (partition 3 bytes)))

(defn base64-encode
  "Encode a list of bytes into a Base64 string."
  [bytes]
  (let [pad-size (mod (- 3 (mod (count bytes) 3)) 3)
        pad-with (fn [x coll]
                   (concat coll (repeat pad-size x)))]
    (->> bytes
      (pad-with (byte 0))
      (encode-padded-bytes)
      (drop-last pad-size)
      (pad-with \=)
      (apply str))))
