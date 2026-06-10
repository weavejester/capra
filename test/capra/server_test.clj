(ns capra.server-test
  (:require [clojure.test :refer [deftest is]]
            [capra.server :as capra]
            [clj-http.client :as http]))

(deftest request-response-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain"}
                    :body    "Hello World"})
                 {:port 4321})]
    (let [response (http/get "http://localhost:4321")]
      (is (= {:status  200
              :headers {"Content-Type"      "text/plain"
                        "Server"            "Capra"
                        "Transfer-Encoding" "chunked"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date"))))
      (is (re-matches #"\w{3}, \d+ \w{3} \d{4} \d\d:\d\d:\d\d GMT"
                      (get-in response [:headers "Date"]))))))

(deftest response-content-length-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain"
                              "Content-Length" "11"}
                    :body    "Hello World"})
                 {:port 4322})]
    (let [response (http/get "http://localhost:4322")]
      (is (= {:status  200
              :headers {"Content-Type"   "text/plain"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))
