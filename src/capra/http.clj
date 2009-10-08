(ns capra.http
  "Library for talking to Clojure web services, like capra-server."
  (:import java.io.InputStreamReader)
  (:import java.io.PushbackReader)
  (:import java.net.URL)
  (:import java.net.HttpURLConnection))

(defn read-stream
  "Read a Clojure data structure from a stream"
  [stream]
  (with-open [reader (InputStreamReader. stream)]
    (read (PushbackReader. reader))))

(defn get-url
  "Retrieve data from a URL using a GET request."
  [url]
  (read-stream (.openStream (URL. url))))
