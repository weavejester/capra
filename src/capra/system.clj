(ns capra.system
  (:import java.io.File))

(def *source* "https://capra-packages.net")

(def *home*
  (or (System/getenv "HOME")
      (str (System/getenv "HOMEDRIVE")
           (System/getenv "HOMEPATH"))))

(def *capra-home*
  (if-let [dir (System/getenv "CAPRA_HOME")]
    (File. dir)
    (File. *home* ".capra")))
