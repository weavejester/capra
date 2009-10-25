(ns capra.account
  "Access and manage accounts on a Capra server."
  (:refer-clojure :exclude [get list])
  (:use capra.http-client)
  (:use capra.system)
  (:use capra.util)
  (:import java.io.File))

(def key-file
  (File. *capra-home* "account.keys"))

(def account-keys
  (atom (or (read-file key-file) {})))

(defn get-key
  "Get the key for an account or throw an exception if none exist."
  [account-name]
  (or (@account-keys account-name)
      (throwf "No registered passkey for account.")))

(defn save-key!
  "Add a new account name and key to account-keys."
  [account-name passkey]
  (swap! account-keys assoc account-name passkey)
  (write-file key-file @account-keys))

(defn- assoc-package
  "Assoc a package with a map, using the package name as the key."
  [package-map package]
  (let [package (dissoc package :account :href)]
    (assoc package-map (package :name) package)))

(defn- format-packages
  "Turn a list of packages into a map of packages."
  [packages]
  (reduce assoc-package {} packages))

(defn list
  "List all Capra accounts on server."
  []
  (set (map :name (http-get *source*))))

(defn get
  "Get a Capra account by name."
  [name]
  (if-let [account (http-get (str *source* "/" name))]
    (-> account
      (dissoc :type)
      (update-in [:packages] format-packages))))

(defn create
  "Create a new Capra account from a map of values."
  [account]
  (doto (http-connect "POST" *source*)
        (http-send account)))

(defn register
  "Register a new Capra account with a random passkey. The passkey is saved to
  account-keys, and automatically used when updating or creating private data."
  [name email]
  (let [passkey (base64-encode (random-bytes 24))]
    (create {:name name, :passkey passkey, :email email})
    (save-key! name passkey)))

(defn put
  "Update an existing Capra account."
  [account]
  (let [name (account :name)]
    (doto (http-connect "PUT" (str *source* "/" name))
          (basic-auth name (get-key name))
          (http-send account))))
