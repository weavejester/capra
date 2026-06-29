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
    (let [response (http/post "http://localhost:4323"
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

(deftest large-byte-array-response-body-test
  (let [large-body (apply str (repeat 100 "Hello World\n"))]
    (with-open [_ (capra/start-server
                   (fn handler [_request]
                     {:status  200
                      :headers {"Content-Type" "text/plain; charset=UTF-8"}
                      :body    (.getBytes ^String large-body "UTF-8")})
                   {:port 4326
                    :response-buffer-size 200})]
      (let [response (http/get "http://localhost:4326")]
        (is (= {:status  200
                :headers {"Content-Type"   "text/plain; charset=UTF-8"
                          "Content-Length" "1200"
                          "Server"         "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))))))

(deftest large-chunked-array-response-body-test
  (let [large-body (apply str (repeat 100 "Hello World\n"))]
    (with-open [_ (capra/start-server
                   (fn handler [_request]
                     {:status  200
                      :headers {"Content-Type" "text/plain; charset=UTF-8"
                                "Transfer-Encoding" "chunked"}
                      :body    (.getBytes ^String large-body "UTF-8")})
                   {:port 4327
                    :response-buffer-size 200})]
      (let [response (http/get "http://localhost:4327")]
        (is (= {:status  200
                :headers {"Content-Type"      "text/plain; charset=UTF-8"
                          "Transfer-Encoding" "chunked"
                          "Server"            "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))))))

(deftest persistent-connection-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4328})]
    (http/with-connection-pool {:max-total 1 :default-per-route 1}
      (let [responses (->> (repeatedly 10 #(http/get "http://localhost:4328"))
                           (doall)
                           (map #(-> %
                                     (select-keys [:status :headers :body])
                                     (update :headers dissoc "Date"))))]
        (is (= 10 (count responses)))
        (is (= 1 (count (distinct responses))))
        (is (= {:status  200
                :headers {"Content-Type"      "text/plain; charset=UTF-8"
                          "Content-Length"    "11"
                          "Server"            "Capra"}
                :body    "Hello World"}
               (first responses)))))))

(deftest respond-multiple-calls-test
  (with-open [_ (capra/start-server
                 (fn handler [_request respond _raise]
                   (respond
                    {:status  200
                     :headers {"Content-Type" "text/plain; charset=UTF-8"}
                     :body    "Hello"})
                   (respond
                    {:status  200
                     :headers {"Content-Type" "text/plain; charset=UTF-8"}
                     :body    "World"}))
                 {:port 4329
                  :async? true})]
    (let [response (http/get "http://localhost:4329")]
      (is (= {:status  200
              :headers {"Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "5"
                        "Server"         "Capra"}
              :body    "Hello"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest exception-in-handler-test
  (let [logs (atom [])]
    (with-open [_ (capra/start-server
                   (fn handler [_request]
                     (throw (ex-info "Error" {})))
                   {:port 4330
                    :error-logger #(swap! logs conj (ex-message %))})]
      (let [response (http/get "http://localhost:4330"
                               {:throw-exceptions false})]
        (is (= {:status  500
                :headers {"Content-Type"   "text/plain; charset=UTF-8"
                          "Content-Length" "21"
                          "Server"         "Capra"}
                :body    "Internal Server Error"}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))
        (is (= ["Error"] @logs))))))

(deftest nil-response-body-test
  (with-open [_ (capra/start-server
                 (fn handler [_request]
                   {:status  301
                    :headers {"Location" "http://example.com"}})
                 {:port 4331})]
    (let [response (http/get "http://localhost:4331"
                             {:redirect-strategy :none})]
      (is (= {:status  301
              :headers {"Content-Length" "0"
                        "Location"       "http://example.com"
                        "Server"         "Capra"}
              :body    ""}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))
