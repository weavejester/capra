(ns capra.util
  "Utility functions for Capra."
  (:import java.io.File)
  (:import java.io.InputStream)
  (:import java.io.OutputStream)
  (:import java.io.FileInputStream)
  (:import java.io.FileOutputStream)
  (:import java.io.InputStreamReader)
  (:import java.io.PushbackReader))

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
  (write-stream (FileOutputStream. file)))

(defn copy-stream
  "Copy the contents of an InputStream into an OutputStream."
  ([in out]
    (copy-stream in out 4096))
  ([#^InputStream in, #^OutputStream out, buffer-size]
    (let [buffer (make-array Byte/TYPE buffer-size)]
      (loop [len (.read in buffer)]
        (when (pos? len)
          (.write out buffer 0 len)
          (recur (.read in buffer)))))))
