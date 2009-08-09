(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [find])
  (:import java.net.URL)
  (:import java.io.InputStreamReader)
  (:import java.io.PushbackReader))

;; Environment

(def *home-dir*
  (or (System/getenv "CAPRA_HOME")
      (System/getenv "HOME")))

;; Sources

(def sources (atom []))

(def #^{:private true}
  source-type-regex #"(^[a-z0-9.-]+)\+")

(defn- get-source-type
  "Gets the type of the source. The type is encoded in the scheme of the URL.
  For example, the type of 'capra+http://example.com' is 'capra'."
  [source]
  (second (re-find source-type-regex source)))

(defn- strip-source-type
  "Strips the source type from the URL. For example, 'capra+http://example.com'
  becomes 'http://example.com'."
  [url]
  (-> (re-matcher source-type-regex url)
    (.replaceAll "")))

(defmulti read-source
  "Reads package metadata from a source."
  get-source-type)

(defmethod read-source "capra"
  [source]
  (let [url    (strip-source-type source)
        stream (.openStream (URL. url))
        reader (PushbackReader. (InputStreamReader. stream))]
    (read reader)))

(defn read-all-sources
  "Reads all sources in capra.package/sources"
  []
  (apply merge (map read-source @sources)))

;; Packages

(defn- matches-version?
  "Returns true if the version matches the package."
  [version package]
  (= (package :version) version))

(defn find
  "Finds a package in the repository"
  [name version]
  (let [repository (read-all-sources)
        packages   (repository name)]
    (first (filter (partial matches-version? version)
                   packages))))
