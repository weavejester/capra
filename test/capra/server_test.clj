(ns capra.server-test
  (:require [clojure.test :refer [deftest is]]
            [capra.server :as capra]
            [clj-http.client :as http]))

(defn- hello-world-handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(deftest request-response-test
  (with-open [_ (capra/start-server hello-world-handler {:port 4321})]
    (is (= {:status  200
            :headers {"Content-Type"      "text/plain"
                      "Server"            "Capra"
                      "Transfer-Encoding" "chunked"}
            :body    "Hello World"}
           (-> (http/get "http://localhost:4321")
               (select-keys [:status :headers :body]))))))
