(ns capra.interface
  "Multimethods used for reading a package repository. Can be overridden to
  support different repository types.")

(defmulti list-packages
  "Return a list of all packages in the repository. Each package should be
  expressed as a [name version] vector."
  (fn [source-type source] source-type))

(defmulti find-package
  "Find a package from a repository and return the package metadata in the
  form of a map."
  (fn [source-type source name version] source-type))
