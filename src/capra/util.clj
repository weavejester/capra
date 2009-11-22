(ns capra.util
  "Utility functions for Capra."
  (:refer-clojure :exclude [->>])
  (:import clojure.lang.RT)
  (:import java.io.File)
  (:import java.io.InputStream)
  (:import java.io.OutputStream)
  (:import java.io.FileInputStream)
  (:import java.io.FileOutputStream)
  (:import java.io.InputStreamReader)
  (:import java.io.OutputStreamWriter)
  (:import java.io.PushbackReader)
  (:import java.security.SecureRandom))

(defn throwf
  "Throw an Exception object with a message."
  [message]
  (throw (Exception. message)))

(defn load-resource
  "Return an InputStream to a resource."
  [path]
  (.getResourceAsStream (RT/baseLoader) path))

(defmacro with-ns
  "Evaluates body in another namespace. Taken from clojure.contrib/with-ns."
  [ns & body]
  `(binding [*ns* (the-ns ~ns)]
     ~@(map (fn [form] `(eval '~form)) body)))

(defn byte-array
  "Create an array of bytes"
  [size]
  (make-array Byte/TYPE size))

(defn read-stream
  "Read a Clojure data structure from an input stream."
  [#^InputStream stream]
  (with-open [reader (InputStreamReader. stream)]
    (read (PushbackReader. reader))))

(defn read-file
  "Read a Clojure data structure from a file."
  [#^File file]
  (if (.exists file)
    (read-stream (FileInputStream. file))))

(defn write-stream
  "Write a Clojure data structure to an output stream."
  [#^OutputStream stream, data]
  (with-open [writer (OutputStreamWriter. stream)]
    (binding [*out* writer]
      (pr data))))

(defn write-file
  "Write a Clojure data structure to a file."
  [file data]
  (write-stream (FileOutputStream. file) data))

(defn copy-stream
  "Copy the contents of an InputStream into an OutputStream."
  ([in out]
    (copy-stream in out 4096))
  ([#^InputStream in, #^OutputStream out, buffer-size]
    (let [buffer (byte-array buffer-size)]
      (loop [len (.read in buffer)]
        (when (pos? len)
          (.write out buffer 0 len)
          (recur (.read in buffer)))))))

(defn random-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

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
