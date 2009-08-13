(ns capra.package
  "An extensible package manager for Clojure."
  (:refer-clojure :exclude [list find])
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

;; Packages

(defn list
  "Return a list of all package names and versions."
  []
  (set (mapcat
         #(apply list-packages (split-source %))
         @sources)))
