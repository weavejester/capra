(ns capra.util
  "Utility functions for Capra."
  (:use clojure.contrib.duck-streams)
  (:use clojure.contrib.java-utils)
  (:use clojure.contrib.zip-filter.xml)
  (:require [clojure.xml :as xml])
  (:require [clojure.zip :as zip])
  (:require [clojure.contrib.zip-filter :as zf]))

(defn read-file
  "Read the contents of a Clojure data structure from a file. Returns nil if
  the file does not exist."
  [filepath]
  (if (.exists (file filepath))
    (read-string (slurp* filepath))))

(defn copy-url
  "Copy the contents of a URL to a filepath."
  [url filepath]
  (copy (.openStream (as-url url))
        filepath))

(defn- tag-and-text
  "Return a vector containing the tag name and text of a node."
  [loc]
  [(-> loc zip/node :tag)
   (text loc)])

(defn tag-map
  "Return a map made from the tag names and text content contained under an
  XML node."
  [loc]
  (apply hash-map
    (xml-> loc
      zf/children
      tag-and-text)))
