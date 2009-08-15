(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [list get find])
  (:use capra.interface)
  (:use clojure.contrib.def)
  (:use clojure.contrib.duck-streams)
  (:use clojure.contrib.java-utils))

;; Environment

(defvar *home-dir*
  (or (System/getenv "CAPRA_HOME")
      (System/getenv "HOME")))

(defvar *package-dir*
  (file *home-dir* ".capra" "packages"))

;; Sources

(defvar sources (atom [])
  "Atom containing a vector of repository URLs.")

(defn add-source
  "Add a source URL to the end of capra.package/sources."
  [source]
  (swap! sources conj source))

(defvar- source-regex #"^([a-z0-9.-]+)\+(.*)$"
  "Regular expression to split source type from source URL.")

(defn- split-source
  "Split the source type from a source URL."
  [source]
  (let [[_ type url] (re-matches source-regex source)]
    [(keyword type) url]))

(defn- src->
  "Turn a capra.interface function into one that takes a single source URL."
  [func & args]
  (fn [source]
    (let [[type url] (split-source source)]
      (apply func type url args))))

(defn- first-not-nil
  "Return the first value that is not nil."
  [coll]
  (first (remove nil? coll)))

;; Packages

(defn get
  "Get a specific package by name and version."
  [name version]
  (first-not-nil
    (map (src-> get-package name version) @sources)))

(defn download
  "Download the jar from a single package jar. Returns the new package
  filepath."
  [package]
  (let [filename (str (package :name) "-" (package :version) ".jar")
        filepath (file *package-dir* filename)
        stream   (.openStream (as-url (package :url)))]
    (copy stream filepath)
    filepath))

(defn install
  "Downloads the package and all dependencies, then adds them to the
  classpath."
  [name version]
  (let [package (get name version)]
    (doseq [dependency (package :dependencies)]
      (apply install dependency))
    (add-classpath (.toURL (download package)))))
