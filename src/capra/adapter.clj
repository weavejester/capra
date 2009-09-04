(ns capra.adapter
  "Multimethods used for reading a package repository. Can be overridden to
  support different repository types.")

(defmulti query-package
  "Given a group, name and version of a package in a repository, return a map
  of the package metadata."
  (fn [type source group name version] type))
