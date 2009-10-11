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
  "Send a HTTP GET request to a Clojure web service."
  [url]
  (read-stream (.openStream (URL. url))))

(defn http-copy
  "Download a URL to a location on disk."
  [src-url dest-path]
  (copy-stream
    (.openStream (URL. src-url))
    (FileOutputStream. dest-path)))

(defn http-post
  "Send a HTTP POST request to a Clojure web service"
  [url data]
  (let [conn (.openConnection (URL. url))]
    (doto conn
      (.setRequestMethod "POST")
      (.setDoOutput true)
      (.setRequestProperty "Content-Type" "application/clojure"))
    (try
      (.connect conn)
      (write-stream (.getOutputStream conn) data)
      (.close (.getInputStream conn))
      (catch IOException e
        (read-stream (.getErrorStream conn)))
      (finally
        (.disconnect conn)))))
