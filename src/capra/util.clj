(ns capra.util
  "Utility functions for Capra."
  (:import java.io.File)
  (:import java.io.InputStream)
  (:import java.io.FileInputStream)
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
