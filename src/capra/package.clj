(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [list get find])
  (:use capra.interface)
  (:use clojure.contrib.def))

;; Environment

(def *home-dir*
  (or (System/getenv "CAPRA_HOME")
      (System/getenv "HOME")))

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

(defn list
  "Return a list of all package names and versions."
  []
  (set (mapcat (src-> list-packages) @sources)))

(defn get
  "Get a specific package by name and version."
  [name version]
  (first-not-nil
    (map (src-> get-package name version) @sources)))
