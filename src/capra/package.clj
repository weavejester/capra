(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [list load])
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

;; Packages

(defn query
  "Get a specific package by group, name and version."
  [group name version]
  (first
    (remove nil?
      (map (src-> query-package group name version)
           @sources))))

(defvar- cache-index
  (file *root-dir* "cache.index"))

(defvar cache
  (atom (or (read-file cache-index) {}))
  "A map of all cached packages.")

(defn cached?
  "Is a package cached?"
  [group name version]
  (contains? @cache [group name version]))

(defn- download-path
  "Return the download path for a file."
  [file-info]
  (file *root-dir*
        "cache"
        (str (file-info :sha1) ".jar")))

(defn fetch
  "Downloads and caches the content of a package."
  [package]
  (doseq [file-info (package :files)]
    (let [filepath (download-path file-info)]
      (when-not (.exists filepath)
        (copy-url (file-info :url) filepath))))
  (let [key [(package :group)
             (package :name)
             (package :version)]]
    (swap! cache assoc key package)))

(defvar loaded (atom #{})
  "A set of packages currently loaded into the classpath. This is always
  a subset of the package cache.")

(defn load
  "Load a cached package into the classpath."
  [package]
  (when-not (@loaded package)
    (swap! loaded conj package)
    (doseq [file-info (package :files)]
      (add-classpath (.toURL (download-path file-info))))))

(defn install
  "Downloads the package and all dependencies, then adds them to the
  classpath."
  [group name version]
  (println "Installing" name version)
  (let [package (or (@cache [group name version])
                    (query group name version))]
    (doseq [dependency (package :dependencies)]
      (apply install dependency))
    (fetch package)
    (load package)))
