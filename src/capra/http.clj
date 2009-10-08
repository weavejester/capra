(ns capra.http
  "Library for talking to Clojure web services, like capra-server."
  (:import java.io.IOException)
  (:import java.io.InputStream)
  (:import java.io.OutputStream)
  (:import java.io.InputStreamReader)
  (:import java.io.OutputStreamWriter)
  (:import java.io.PushbackReader)
  (:import java.net.URL)
  (:import java.net.HttpURLConnection))

(defn has-content?
  "True if the PushbackReader has content."
  [#^PushbackReader reader]
  (let [c (.read reader)]
    (when-not (= c -1)
      (.unread reader c)
      true)))

(defn read-stream
  "Read a Clojure data structure from an input stream."
  [#^InputStream stream]
  (with-open [reader (PushbackReader. (InputStreamReader. stream))]
    (if (has-content? reader)
      (read reader))))

(defn write-stream
  "Write a Clojure data structure to an output stream."
  [#^OutputStream stream, data]
  (with-open [writer (OutputStreamWriter. stream)]
    (binding [*out* writer]
      (pr data))))

(defn http-request
  "Send a HTTP request to a Clojure web-service."
  [request]
  (let [body (request :body)
        conn (.openConnection (URL. (request :url)))]
    (.setRequestMethod conn (request :method))
    (.setRequestProperty conn "Content-Type" "application/clojure")
    (when body (.setDoOutput conn true))
    (try
      (.connect conn)
      (when body (write-stream (.getOutputStream conn) body))
      (read-stream (.getInputStream conn))
      (catch IOException e
        (read-stream (.getErrorStream conn)))
      (finally
        (.disconnect conn)))))
