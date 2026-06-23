(ns capra.server-test
  (:require [clojure.test :refer [deftest is]]
            [capra.server :as capra]
            [clj-http.client :as http]))

(deftest request-response-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4321})]
    (let [response (http/get "http://localhost:4321")]
      (is (= {:status  200
              :headers {"Content-Type"      "text/plain; charset=UTF-8"
                        "Content-Length"    "11"
                        "Server"            "Capra"}
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

(deftest request-with-content-length-test
  (with-open [_ (capra/start-server
                 (fn handler [{:keys [headers body]}]
                   {:status  200
                    :headers {"Content-Type"   (headers "content-type")
                              "Content-Length" (headers "content-length")}
                    :body    (slurp body)})
                 {:port 4323})]
    (let [response (http/get "http://localhost:4323"
                             {:headers {"Content-Type" "text/plain"}
                              :body "Hello World"})]
      (is (= {:status  200
              :headers {"Content-Type"   "text/plain"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest multiple-response-headers-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"
                              "X-Example" ["foo" "bar"]}
                    :body    "Hello World"})
                 {:port 4324})]
    (let [response (http/get "http://localhost:4324")]
      (is (= {"Content-Type"   "text/plain; charset=UTF-8"
              "Content-Length" "11"
              "Server"         "Capra"
              "X-Example"      ["foo" "bar"]}
             (-> response :headers (dissoc "Date")))))))

(deftest byte-array-response-body-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (.getBytes "Hello World" "UTF-8")})
                 {:port 4325})]
    (let [response (http/get "http://localhost:4325")]
      (is (= {:status  200
              :headers {"Content-Type"      "text/plain; charset=UTF-8"
                        "Content-Length"    "11"
                        "Server"            "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))
