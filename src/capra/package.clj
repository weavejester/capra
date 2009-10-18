(ns capra.package
  "Retrieve and manage packages on a Capra server."
  (:refer-clojure :exclude [get load])
  (:use [capra.account :only (account-keys)])
  (:use capra.http-client)
  (:use capra.system)
  (:use capra.util)
  (:import java.io.File)
  (:import java.io.FileInputStream))

(defn get
  "Retrieve package metadata by account, name and version."
  [account name version]
  (let [package (http-get (str *source* "/" account "/" name "/" version))]
    (dissoc package :type)))

(defn upload-file
  "Upload a file to an existing package."
  [account package version filepath]
  (let [url (str *source* "/" account "/" package "/" version)]
    (if-let [passkey (@account-keys account)]
      (doto (http-connect "POST" url)
            (basic-auth account passkey)
            (content-type "application/java-archive")
            (http-stream (FileInputStream. filepath)))
      (throwf "No registered passkey for account."))))

(defn create
  "Upload a new package with files."
  [package]
  (let [account (package :account)]
    (if-let [passkey (@account-keys account)]
      (do (doto (http-connect "POST" (str *source* "/" account))
                (basic-auth account passkey)
                (http-send (dissoc package :files)))
          (doseq [file (package :files)]
            (upload-file account
                         (package :name)
                         (package :version)
                         file)))
      (throwf "No registered passkey for account."))))

(def index-file
  (File. *capra-home* "cache.index"))

(def cache
  (atom (or (read-file index-file) {})))

(defn cached?
  "Is a package cached?"
  [account name version]
  (contains? @cache [account name version]))

(defn- cache-path
  "Return the download path for a file."
  [file-info]
  (File. (File. *capra-home* "cache")
         (str (file-info :sha1) ".jar")))

(defn cache!
  "Downloads and caches the content of a package."
  [package]
  (doseq [file-info (package :files)]
    (let [filepath (cache-path file-info)]
      (when-not (.exists filepath)
        (http-copy (file-info :href) filepath))))
  (let [key [(package :account)
             (package :name)
             (package :version)]]
    (swap! cache assoc key package)
    (write-file index-file @cache)))

(def loaded (atom #{}))

(defn load
  "Load a cached package into the classpath."
  [package]
  (when-not (@loaded package)
    (swap! loaded conj package)
    (doseq [file-info (package :files)]
      (add-classpath (.toURL (cache-path file-info))))))

(defn install
  "Downloads the package and all dependencies, then adds them to the
  classpath."
  [account name version]
  (let [package (or (@cache [account name version])
                    (get account name version))]
    (doseq [dependency (package :depends)]
      (apply install dependency))
    (cache! package)
    (load package)))
