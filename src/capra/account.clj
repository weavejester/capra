(ns capra.account
  "Access and manage accounts on a Capra server."
  (:refer-clojure :exclude [get list])
  (:use capra.http))

(def url "http://localhost:8080")

(defn list
  "List all Capra accounts on server."
  []
  (let [accounts (http-request {:method "GET", :url url})]
    (set (map :name accounts))))

(defn get
  "Get a Capra account by name."
  [name]
  (let )
  (http-request {:method "GET", :url (str url "/" name)}))
