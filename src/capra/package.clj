(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [list get find])
  (:use capra.adapter)
  (:use capra.util)
  (:use clojure.contrib.def)
  (:use clojure.contrib.duck-streams)
  (:use clojure.contrib.java-utils))

;; Environment

(defvar *root-dir*
  (or (System/getenv "CAPRA_HOME")
      (file (System/getenv "HOME") ".capra")))

;; Sources

(defn- read-sources
  "Read the list of sources from a file on disk."
  [filename]
  (let [filepath (file *root-dir* filename)]
    (if (.exists filepath)
      (read-lines filepath))))

(defvar sources
  (atom (vec (read-sources "sources.list")))
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

(defvar loaded (atom {})
  "A list of packages currently loaded into the classpath. This is always
  a subset of the package cache.")

(defvar cache (atom {})
  "A map of all cached packages.")

(defn cached?
  "Is a package already loaded?"
  [name version]
  (contains? @cache [name version]))

(defn- add-to-cache!
  "Add a package to the package cache."
  [package]
  (let [key [(package :name) (package :version)]]
    (swap! cache assoc key package)))

(defn get
  "Get a specific package by name and version."
  [name version]
  (first-not-nil
    (map (src-> get-package name version) @sources)))

(defn- download-path
  "Return the download path for a file."
  [file-info]
  (file *root-dir*
        "cache"
        (str (file-info :sha1) ".jar")))

(defn download
  "Download the jar from a single package jar. Returns the new package
  filepath."
  [package]
  (doall
    (for [file-info (package :files)]
      (let [filepath (download-path file-info)]
        (when-not (.exists filepath)
          (copy-url (file-info :url) filepath))
        filepath))))

(defn install
  "Downloads the package and all dependencies, then adds them to the
  classpath."
  [name version]
  (when-not (cached? name version)
    (println "Installing" name version)
    (let [package (get name version)]
      (add-to-cache! package)
      (doseq [dependency (package :dependencies)]
        (apply install dependency))
      (doseq [filepath (download package)]
        (add-classpath (.toURL filepath))))))
