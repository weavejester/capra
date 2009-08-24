(ns capra.adapter
  "Multimethods used for reading a package repository. Can be overridden to
  support different repository types.")

(defmulti get-package
  "Given a name and version of a package in a repository, return a map of the
  package metadata."
  (fn [type source name version] type))
