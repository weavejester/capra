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
  [source group artifact version]
  (str source "/" group "/" artifact "/" version "/"
       artifact "-" version))

(defn- fetch-sha1
  "Retrieve the SHA1 of a file referenced by a URL."
  [url]
  (let [content (slurp* (str url ".sha1"))]
    (re-find #"^[0-9a-fA-F]+" content)))

(defn- fetch-pom
  "Get the Maven POM file of the package."
  [url]
  (xml-zip (xml/parse (str url ".pom"))))

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
      [[(dep :groupId)
        (dep :artifactId)
        (dep :version)]])))

(defn- read-deps
  "Read the dependencies from the Maven POM XML."
  [pom-xml]
  (xml-> pom-xml
    :dependencies
    :dependency
    parse-dep))

(defmethod query-package :mvn
  [_ source group name version]
  (let [url      (url-prefix source group name version)
        pom-xml  (fetch-pom url)
        file-url (str url ".jar")]
    {:group   group
     :name    name
     :version version
     :description (xml1-> pom-xml :description text)
     :files [{:url  file-url
              :sha1 (fetch-sha1 file-url)}]
     :dependencies (read-deps pom-xml)}))
