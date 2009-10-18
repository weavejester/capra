(ns capra.http-client
  "Library for talking to Clojure web services, like capra-server."
  (:use capra.system)
  (:use capra.util)
  (:import java.io.IOException)
  (:import java.io.FileOutputStream)
  (:import java.io.OutputStream)
  (:import java.io.OutputStreamWriter)
  (:import java.net.URL)
  (:import java.net.HttpURLConnection))

(defn http-connect
  "Create a new HTTP connection"
  [method url]
  (let [conn (.openConnection (URL. url))]
    (doto conn
      (.setRequestMethod method)
      (.setDoOutput true)
      (.setRequestProperty "Content-Type" "application/clojure"))))

(defn basic-auth
  "Setup basic auth on a HTTP connection."
  [conn username password]
  (let [id   (str username ":" password)
        auth (str "Basic " (base64-encode (.getBytes id)))]
    (.setRequestProperty conn "Authorization" auth)))

(defn- with-connection
  "Interact with a HTTP server."
  [conn callback]
  (try
    (.connect conn)
    (callback)
    (.close (.getInputStream conn))
    (catch IOException e
      (throwf (read-stream (.getErrorStream conn))))
    (finally
      (.disconnect conn))))

(defn http-stream
  "Send a stream of data via a HTTP request to a server."
  [conn stream]
  (with-connection conn
    #(copy-stream stream (.getOutputStream conn))))

(defn http-send
  "Send data via a HTTP request to a Clojure web service"
  [conn data]
  (with-connection conn
    #(write-stream (.getOutputStream conn) data)))

(defn http-get
  "Send a HTTP GET request to a URL."
  [url]
  (read-stream (.openStream (URL. url))))

(defn http-copy
  "Download a URL to a location on disk."
  [src-url dest-path]
  (copy-stream
    (.openStream (URL. src-url))
    (FileOutputStream. dest-path)))
