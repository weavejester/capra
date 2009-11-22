(ns capra.util
  "Miscellaneous Utility functions for Capra."
  (:import java.security.SecureRandom))

(defn throwf
  "Throw an Exception object with a message."
  [message]
  (throw (Exception. message)))

(defmacro with-ns
  "Evaluates body in another namespace. Taken from clojure.contrib/with-ns."
  [ns & body]
  `(binding [*ns* (the-ns ~ns)]
     ~@(map (fn [form] `(eval '~form)) body)))

(defn byte-array
  "Create an array of bytes"
  [size]
  (make-array Byte/TYPE size))

(defn bytes->hex
  "Return a list of bytes as a string of hexidecimal characters."
  [bytes]
  (let [hex (vec "01234567890abcdef")]
    (apply str
      (mapcat
        (fn [b]
          [(hex (bit-shift-right b 4))
           (hex (bit-and b 0x0f))])
        bytes))))

(defn random-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))
