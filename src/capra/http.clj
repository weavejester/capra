(ns capra.http
  "Library for talking to Clojure web services, like capra-server."
  (:use capra.system)
  (:use capra.util)
  (:import java.io.IOException)
  (:import java.io.FileOutputStream)
  (:import java.io.OutputStream)
  (:import java.io.OutputStreamWriter)
  (:import java.net.URL)
  (:import java.net.HttpURLConnection))

(defn http-get
  "Send a HTTP GET request to a Clojure web-service."
  [url]
  (read-stream (.openStream (URL. url))))

(defn http-copy
  "Download a URL to a location on disk."
  [src-url dest-path]
  (copy-stream
    (.openStream (URL. src-url))
    (FileOutputStream. dest-path)))

(defn http-request
  "Send a HTTP request to a Clojure web-service."
  [request]
  (let [body (request :body)
        conn (.openConnection (URL. (request :url)))]
    (.setRequestMethod conn (request :method))
    (when body
      (.setDoOutput conn true)
      (.setRequestProperty conn "Content-Type" "application/clojure"))
    (try
      (.connect conn)
      (when body
        (write-stream (.getOutputStream conn) body))
      (read-stream (.getInputStream conn))
      (catch IOException e
        (read-stream (.getErrorStream conn)))
      (finally
        (.disconnect conn)))))
