(ns capra.package
  "Retrieve and manage packages on a Capra server."
  (:refer-clojure :exclude [get])
  (:use capra.http))

(defn get
  "Get a package by account, name and version."
  [account name version]
  (let [package (http-get "/" account "/" name "/" version)]
    (dissoc package :type)))


(comment
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
    (swap! cache assoc key package)
    (write-file cache-index @cache)))

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

  )
