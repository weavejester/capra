(ns capra.system
  (:import java.io.File))

(def *source*
  "http://localhost:8080")

(def *home*
  (or (System/getenv "HOME")
      (System/getenv "HOMEPATH")))

(def *capra-home* 
  (File. *home* ".capra"))
