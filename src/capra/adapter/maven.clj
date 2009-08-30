(ns capra.adapter.maven
  "Adapter for accessing Maven repositories."
  (:use capra.adapter)
  (:use capra.util)
  (:require [clojure.xml :as xml])
  (:use [clojure.zip :only (xml-zip)])
  (:use clojure.contrib.zip-filter.xml)
  (:use clojure.contrib.duck-streams))

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

(defn- get-file-sha1
  "Retrieve the SHA1 of a file referenced by a URL."
  [url]
  (let [content (slurp* (str url ".sha1"))]
    (re-find #"^[0-9a-fA-F]+" content)))

(defn- fetch-pom
  "Get the Maven POM file of the package."
  [maven-id]
  (let [url (str (url-prefix maven-id) ".pom")]
    (xml/parse url)))

(defn- ignore-dep?
  "True if dependency can be ignored."
  [dep]
  (or (= (dep :optional) "true")
      (not= (dep :type "jar") "jar")
      (contains? #{"test" "system"}
                 (dep :scope "compile"))))

(defn- parse-dep
  "Turn Maven dependency XML into a [package version] vector."
  [dep]
  (let [dep (tag-map dep)]
    (if-not (ignore-dep? dep)
      [[(str (dep :groupId) "/" (dep :artifactId))
        (dep :version)]])))

(defn- read-deps
  "Read the dependencies from the Maven POM XML."
  [pom-xml]
  (xml-> pom-xml
    :dependencies
    :dependency
    parse-dep))

(defmethod get-package :mvn
  [_ source name version]
  (let [maven-id (get-maven-id source name version)
        pom-xml  (xml-zip (fetch-pom maven-id))
        file-url (str (url-prefix maven-id) ".jar")]
    {:name    name
     :version version
     :description (xml1-> pom-xml :description text)
     :files [{:url  file-url
              :sha1 (get-file-sha1 file-url)}]
     :dependencies (read-deps pom-xml)}))
