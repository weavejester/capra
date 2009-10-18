(ns capra
  "Functions for automatically loading dependencies."
  (:require capra.package)
  (:use capra.util))

(with-ns 'clojure.core
  (def in-ns* in-ns)
  (defn in-ns [name]
    (doseq [dep (-> name meta :deps)]
      (apply capra.package/install (.split dep "/")))
    (in-ns* name)))
