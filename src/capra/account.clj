(ns capra.account
  "Access and manage accounts on a Capra server."
  (:refer-clojure :exclude [get list])
  (:use capra.http)
  (:use capra.system))

(defn- assoc-package
  "Assoc a package with an index map."
  [index package]
  (let [package (dissoc package :account :href)]
    (assoc index (package :name) package)))

(defn- format-packages
  [packages]
  (reduce assoc-package {} packages))

(defn list
  "List all Capra accounts on server."
  []
  (set (map :name (http-get (str *source* "/")))))

(defn get
  "Get a Capra account by name."
  [name]
  (let [account (http-get (str *source* "/" name))]
    (-> account
      (dissoc :type)
      (update-in [:packages] format-packages))))
