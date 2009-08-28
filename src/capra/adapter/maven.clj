(ns capra.adapter.maven
  "Adapter for accessing Maven repositories."
  (:use capra.adapter)
  (:require [clojure.xml :as xml])
  (:use [clojure.zip :only (xml-zip)])
  (:use clojure.contrib.zip-filter.xml))

(defn- url-prefix
  "Given a four-part Maven ID, return the common URL prefix."
  [[source group artifact version]]
  (str source "/" group "/" artifact "/" version "/"
       artifact "-" version))

(defn- split-name
  "Split package name into a group and artifact ID."
  [name]
  (if-let [groups (re-matches #"(.+)/(.+)" name)]
    (rest groups)
    [name name]))

(defn- get-maven-id
  "Get the full Maven ID from the source, name and version of a package."
  [source name version]
  (let [[group artifact] (split-name name)]
    [source group artifact version]))

(defn- jar-url
  "Return the URL for the Maven jar file."
  [maven-id]
  (str (url-prefix maven-id) ".jar"))

(defn- fetch-pom
  "Get the Maven POM file of the package."
  [maven-id]
  (let [url (str (url-prefix maven-id) ".pom")]
    (xml/parse url)))

(defn- parse-dep
  "Turn Maven dependency XML into a [package version] vector."
  [dep]
  (let [tag #(first (xml-> dep % text))]
    (list
      [(str (tag :groupId) "/" (tag :artifactId))
       (tag :version)])))

(defn- read-deps
  "Read the dependencies from the Maven POM XML."
  [pom-xml]
  (xml-> (xml-zip pom-xml)
    :dependencies
    :dependency
    parse-dep))

(defmethod get-package :mvn
  [_ source name version]
  (let [maven-id (get-maven-id source name version)
        pom-xml  (fetch-pom maven-id)]
    {:name    name
     :version version
     :url     (jar-url maven-id)
     :dependencies (read-deps pom-xml)}))
