(ns capra.package
  "An extensible package manager for Clojure."
  (:import java.net.URL)
  (:import java.io.InputStreamReader)
  (:import java.io.PushbackReader))

(def sources (atom []))

(def source-type-regex #"(^[a-z0-9.-]+)\+")

(defn- get-source-type
  "Gets the type of the source. The type is encoded in the scheme of the URL.
  For example, the type of 'capra+http://example.com' is 'capra'."
  [source]
  (second (re-find source-type-regex source)))

(defn- strip-source-type
  "Strip the source type from the URL. For example, 'capra+http://example.com'
  becomes 'http://example.com'."
  [url]
  (-> (re-matcher source-type-regex url)
    (.replaceAll "")))

(defmulti read-source
  "Read package metadata from a source."
  get-source-type)

(defmethod read-source "capra"
  [source]
  (let [url    (strip-source-type source)
        stream (.openStream (URL. url))
        reader (PushbackReader. (InputStreamReader. stream))]
    (read reader)))
