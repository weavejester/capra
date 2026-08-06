(ns capra.server-test
  (:require [capra.server :as capra]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.core.protocols]))

(defn- raw-http-request [^String host ^long port ^String raw-request]
  (with-open [socket (java.net.Socket. host port)
              writer (io/writer (.getOutputStream socket) :encoding "US-ASCII")]
    (.setSoTimeout socket 1000)
    (.write writer raw-request)
    (.flush writer)
    (slurp (.getInputStream socket) :encoding "US-ASCII")))

(defn- half-closed-http-request [^String host ^long port ^String raw-request]
  (with-open [socket (java.net.Socket. host port)]
    (.setSoTimeout socket 1000)
    (let [writer (io/writer (.getOutputStream socket) :encoding "US-ASCII")]
      (.write writer raw-request)
      (.flush writer)
      (.shutdownOutput socket)
      (slurp (.getInputStream socket) :encoding "US-ASCII"))))

(defn- sha256sum [^bytes bs]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.formatHex (java.util.HexFormat/of) (.digest digest bs))))

(deftest request-response-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4321})]
    (let [response (http/get "http://localhost:4321")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date"))))
      (is (re-matches #"\w{3}, \d+ \w{3} \d{4} \d\d:\d\d:\d\d GMT"
                      (get-in response [:headers "Date"]))))))

(deftest response-content-length-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain"
                              "Content-Length" "11"}
                    :body    "Hello World"})
                 {:port 4322})]
    (let [response (http/get "http://localhost:4322")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest request-with-content-length-test
  (with-open [_ (capra/run-server
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
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest multiple-response-headers-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"
                              "X-Example" ["foo" "bar"]}
                    :body    "Hello World"})
                 {:port 4324})]
    (let [response (http/get "http://localhost:4324")]
      (is (= {"Connection"     "close"
              "Content-Type"   "text/plain; charset=UTF-8"
              "Content-Length" "11"
              "Server"         "Capra"
              "X-Example"      ["foo" "bar"]}
             (-> response :headers (dissoc "Date")))))))

(deftest byte-array-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (.getBytes "Hello World" "UTF-8")})
                 {:port 4325})]
    (let [response (http/get "http://localhost:4325")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest large-byte-array-response-body-test
  (let [large-body (apply str (repeat 100 "Hello World\n"))]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:status  200
                      :headers {"Content-Type" "text/plain; charset=UTF-8"}
                      :body    (.getBytes ^String large-body "UTF-8")})
                   {:port 4326
                    :response-buffer-size 200})]
      (let [response (http/get "http://localhost:4326")]
        (is (= {:status  200
                :headers {"Connection"     "close"
                          "Content-Type"   "text/plain; charset=UTF-8"
                          "Content-Length" "1200"
                          "Server"         "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))))))

(deftest large-chunked-array-response-body-test
  (let [large-body (apply str (repeat 100 "Hello World\n"))]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:status  200
                      :headers {"Content-Type" "text/plain; charset=UTF-8"
                                "Transfer-Encoding" "chunked"}
                      :body    (.getBytes ^String large-body "UTF-8")})
                   {:port 4327
                    :response-buffer-size 200})]
      (let [response (http/get "http://localhost:4327")]
        (is (= {:status  200
                :headers {"Connection"        "close"
                          "Content-Type"      "text/plain; charset=UTF-8"
                          "Transfer-Encoding" "chunked"
                          "Server"            "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))))))

(deftest persistent-connection-test
  (with-open [_ (capra/run-server
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
  (with-open [_ (capra/run-server
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
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "5"
                        "Server"         "Capra"}
              :body    "Hello"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest exception-in-handler-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     (throw (ex-info "Error" {})))
                   {:port 4330
                    :error-logger #(swap! logs conj (ex-message %))})]
      (let [response (http/get "http://localhost:4330"
                               {:throw-exceptions false})]
        (is (= {:status  500
                :headers {"Connection"     "close"
                          "Content-Type"   "text/plain; charset=UTF-8"
                          "Content-Length" "21"
                          "Server"         "Capra"}
                :body    "Internal Server Error"}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))
        (is (= ["Error"] @logs))))))

(deftest nil-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  301
                    :headers {"Location" "http://example.com"}})
                 {:port 4331})]
    (let [response (http/get "http://localhost:4331"
                             {:redirect-strategy :none})]
      (is (= {:status  301
              :headers {"Connection"     "close"
                        "Content-Length" "0"
                        "Location"       "http://example.com"
                        "Server"         "Capra"}
              :body    ""}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest bad-user-content-length-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain; charset=UTF-8"
                              "Content-Length" "10"}
                    :body    "Hello World"})
                 {:port 4332})]
    (let [response (http/get "http://localhost:4332")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "10"
                        "Server"         "Capra"}
              :body    "Hello Worl"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))
          "Shorter Content-Length cuts off body")))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain; charset=UTF-8"
                              "Content-Length" "10"}
                    :body    (java.io.ByteArrayInputStream.
                              (.getBytes "Hello World" "UTF-8"))})
                 {:port 4333})]
    (let [response (http/get "http://localhost:4333")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "10"
                        "Server"         "Capra"}
              :body    "Hello Worl"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))
          "Shorter Content-Length cuts off body")))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain; charset=UTF-8"
                              "Content-Length" "12"}
                    :body    "Hello World"})
                 {:port 4334})]
    (is (thrown-with-msg? org.apache.http.ConnectionClosedException
                          #"Premature end of Content-Length"
                          (http/get "http://localhost:4334"))
        "Longer Content-Length immediately closes"))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain; charset=UTF-8"
                              "Content-Length" "12"}
                    :body    (java.io.ByteArrayInputStream.
                              (.getBytes "Hello World" "UTF-8"))})
                 {:port 4335})]
    (is (thrown-with-msg? org.apache.http.ConnectionClosedException
                          #"Premature end of Content-Length"
                          (http/get "http://localhost:4335"))
        "Longer Content-Length immediately closes"))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Length" "1"}})
                 {:port 4336})]
    (is (thrown-with-msg? org.apache.http.ConnectionClosedException
                          #"Premature end of Content-Length"
                          (http/get "http://localhost:4336"))
        "Longer Content-Length immediately closes")))

(deftest unsupported-transfer-encoding-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4337})]
    (let [response (raw-http-request
                    "localhost" 4337
                    (str "POST / HTTP/1.1\r\n"
                         "Transfer-Encoding: gzip\r\n"
                         "Content-Length: 3\r\n"
                         "\r\n"
                         "foo"))]
      (is (= (str "HTTP/1.1 501 Not Implemented\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 90\r\n\r\n"
                  "Unsupported request transfer encoding: \"gzip\".\n"
                  "Only \"chunked\" transfer encoding supported.")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest client-connection-close-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4338})]
    (let [response (raw-http-request
                    "localhost" 4338
                    (str "GET / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 11\r\n\r\n"
                  "Hello World")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest too-long-uri-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4339
                  :read-buffer-size 200})]
    (let [long-uri (apply str (repeat 100 "foobar"))
          response (http/get (str "http://localhost:4339/" long-uri)
                             {:throw-exceptions false})]
      (is (= {:status  414
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "13"
                        "Server"         "Capra"}
              :body    "URI too long."}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest too-large-header-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4340
                  :read-buffer-size 200})]
    (let [long-header (apply str (repeat 100 "foobar"))
          response    (http/get "http://localhost:4340/"
                                {:throw-exceptions false
                                 :headers {"Long-Header" long-header}})]
      (is (= {:status  431
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "31"
                        "Server"         "Capra"}
              :body    "Request header field too large."}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest conflicting-request-framing-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (str "Handled " uri)})
                 {:port 4351})]
    (let [response (raw-http-request
                    "localhost" 4351
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Content-Length: 6\r\n"
                         "Transfer-Encoding: chunked\r\n\r\n"
                         "0\r\n\r\n"
                         "GET /admin HTTP/1.1\r\n"
                         "Host: localhost\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 65\r\n\r\n"
                  "Both \"Content-Length\" and \"Transfer-Encoding\" headers"
                  " in request.")
             (str/replace response #"Date: (.*?)\r\n" "")))
      (is (not (str/includes? response "/admin"))
          "The smuggled request does not reach the handler"))))

(deftest invalid-content-length-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (str "Handled " uri)})
                 {:port 4352})]
    (let [response (raw-http-request
                    "localhost" 4352
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Content-Length: -5\r\n\r\n"
                         "GET /admin HTTP/1.1\r\n"
                         "Host: localhost\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 49\r\n\r\n"
                  "Invalid \"Content-Length\" header in request: \"-5\".")
             (str/replace response #"Date: (.*?)\r\n" "")))
      (is (not (str/includes? response "/admin"))
          "The smuggled request does not reach the handler"))
    (doseq [value ["+5" "0x5" "" "5 5" "abc" "99999999999999999999"]]
      (let [response (raw-http-request
                      "localhost" 4352
                      (str "POST / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Content-Length: " value "\r\n\r\n"
                           "GET /admin HTTP/1.1\r\n"
                           "Host: localhost\r\n\r\n"))]
        (is (str/starts-with? response "HTTP/1.1 400 Bad Request\r\n")
            (str "Content-Length: " (pr-str value) " is rejected"))
        (is (not (str/includes? response "/admin"))
            (str "Content-Length: " (pr-str value) " smuggles nothing"))))))

(deftest duplicate-content-length-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [body]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (slurp body)})
                 {:port 4353})]
    (let [response (raw-http-request
                    "localhost" 4353
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Content-Length: 5\r\n"
                         "Content-Length: 5\r\n"
                         "Connection: close\r\n\r\n"
                         "Hello"))]
      (is (str/ends-with? response "\r\n\r\nHello")
          "Identical repeated Content-Length fields collapse to one value"))
    (let [response (raw-http-request
                    "localhost" 4353
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Content-Length: 5\r\n"
                         "Content-Length: 6\r\n\r\n"
                         "Hello"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 50\r\n\r\n"
                  "Invalid \"Content-Length\" header in request: \"5,6\".")
             (str/replace response #"Date: (.*?)\r\n" "")))
      (is (not (str/includes? response "Hello"))
          "The request body is not treated as a request"))))

(deftest chunked-body-trailer-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri body]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (str uri ":" (slurp body))})
                 {:port 4354})]
    (let [response (raw-http-request
                    "localhost" 4354
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Transfer-Encoding: chunked\r\n"
                         "Connection: close\r\n\r\n"
                         "5\r\nHello\r\n"
                         "0\r\n"
                         "GET /admin HTTP/1.1\r\n"
                         "\r\n"))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 7\r\n\r\n"
                  "/:Hello")
             (str/replace response #"Date: (.*?)\r\n" ""))
          "The trailer section is consumed, not parsed as a request"))))

(deftest chunk-size-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [body]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (slurp body)})
                 {:port 4355
                  :error-logger (fn [_exception])})]
    (let [response (raw-http-request
                    "localhost" 4355
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Transfer-Encoding: chunked\r\n"
                         "Connection: close\r\n\r\n"
                         "5;name=value\r\nHello\r\n0\r\n\r\n"))]
      (is (str/ends-with? response "\r\n\r\nHello")
          "A chunk extension is ignored, not rejected"))
    (doseq [size ["-5" "+5" "0x5" " 5" ""]]
      (let [response (raw-http-request
                      "localhost" 4355
                      (str "POST / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Transfer-Encoding: chunked\r\n\r\n"
                           size "\r\nHello\r\n0\r\n\r\n"))]
        (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                    "Server: Capra\r\n"
                    "Connection: close\r\n"
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                    "Content-Length: 35\r\n\r\n"
                    "Invalid chunk size in request body.")
               (str/replace response #"Date: (.*?)\r\n" ""))
            (str "Chunk size " (pr-str size) " is rejected"))))))

(deftest framing-error-closes-request-body-with-exception-test
  (let [body-result (promise)]
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [body]} _respond _raise]
                     (try
                       (deliver body-result {:body (slurp body)})
                       (catch Exception exception
                         (deliver body-result {:exception exception}))))
                   {:port 4370, :async? true})]
      (let [response (raw-http-request
                      "localhost" 4370
                      (str "POST / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Transfer-Encoding: chunked\r\n\r\n"
                           "5\r\nHello\r\n"
                           "not-a-size\r\n"))
            result   (deref body-result 1000 ::timeout)]
        (is (str/starts-with? response "HTTP/1.1 400 Bad Request\r\n"))
        (is (instance? java.io.IOException (:exception result))
            "The Ring body stream throws instead of returning partial data")
        (is (= "Invalid HTTP request framing: invalid-chunk-size"
               (ex-message (:exception result))))))))

(deftest truncated-request-body-closes-with-exception-test
  (let [body-result (promise)]
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [body]} _respond _raise]
                     (try
                       (deliver body-result {:body (slurp body)})
                       (catch Exception exception
                         (deliver body-result {:exception exception}))))
                   {:port 4375, :async? true})]
      (half-closed-http-request
       "localhost" 4375
       (str "POST / HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "Content-Length: 10\r\n\r\n"
            "Hello"))
      (let [result (deref body-result 1000 ::timeout)]
        (is (instance? java.io.IOException (:exception result))
            "A truncated fixed-length body does not return partial success")
        (is (= "Unexpected EOF in HTTP request body"
               (ex-message (:exception result))))))))

(deftest invalid-chunk-data-terminator-test
  (let [body-result (promise)]
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [body]} _respond _raise]
                     (try
                       (deliver body-result {:body (slurp body)})
                       (catch Exception exception
                         (deliver body-result {:exception exception}))))
                   {:port 4371, :async? true})]
      (let [response (raw-http-request
                      "localhost" 4371
                      (str "POST / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Transfer-Encoding: chunked\r\n\r\n"
                           "5\r\nHelloX\n"))
            result   (deref body-result 1000 ::timeout)]
        (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                    "Server: Capra\r\n"
                    "Connection: close\r\n"
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                    "Content-Length: 41\r\n\r\n"
                    "Invalid chunk terminator in request body.")
               (str/replace response #"Date: (.*?)\r\n" "")))
        (is (instance? java.io.IOException (:exception result))
            "A malformed data terminator fails the Ring body stream")))))

(deftest missing-host-header-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4341})]
    (let [response (raw-http-request
                    "localhost" 4341
                    (str "GET / HTTP/1.1\r\n"
                         "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 33\r\n\r\n"
                  "Missing \"Host\" header in request.")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest duplicate-host-header-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (str "Handled " uri)})
                 {:port 4356})]
    (let [response (raw-http-request
                    "localhost" 4356
                    (str "GET / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Host: example.com\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 35\r\n\r\n"
                  "Multiple \"Host\" headers in request.")
             (str/replace response #"Date: (.*?)\r\n" "")))
      (is (not (str/includes? response "Handled"))
          "The request does not reach the handler"))))

(deftest unsupported-http-version-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4341})]
    (let [response (raw-http-request
                    "localhost" 4341
                    (str "GET / HTTP/1.0\r\n"
                         "Host: localhost\r\n"
                         "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 505 HTTP Version Not Supported\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 67\r\n\r\n"
                  "Unsupported HTTP version: \"HTTP/1.0\".\n"
                  "Only \"HTTP/1.1\" is supported.")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest invalid-request-start-line-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4341})]
    (let [response (raw-http-request
                    "localhost" 4341
                    "Hello World??\r\n")]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 32\r\n\r\n"
                  "Invalid HTTP request start line.")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest invalid-request-header-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4341})]
    (let [response (raw-http-request
                    "localhost" 4341
                    (str "GET / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Connection: close\r\n"
                         "InvalidHeader\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 50\r\n\r\n"
                  "Invalid HTTP request header line: \"InvalidHeader\".")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest non-token-request-header-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (str "Handled " uri)})
                 {:port 4357})]
    (let [response (raw-http-request
                    "localhost" 4357
                    (str "POST / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Content-Length : 5\r\n\r\n"
                         "GET /admin HTTP/1.1\r\n"
                         "Host: localhost\r\n\r\n"))]
      (is (= (str "HTTP/1.1 400 Bad Request\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 55\r\n\r\n"
                  "Invalid HTTP request header line: \"Content-Length : 5\".")
             (str/replace response #"Date: (.*?)\r\n" ""))
          "Whitespace before the colon is rejected")
      (is (not (str/includes? response "/admin"))
          "The smuggled request does not reach the handler"))
    (doseq [line [" folded: value" "\tfolded: value" "Bad(Name): value"]]
      (let [response (raw-http-request
                      "localhost" 4357
                      (str "GET / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           line "\r\n\r\n"))]
        (is (str/starts-with? response "HTTP/1.1 400 Bad Request\r\n")
            (str "The header line " (pr-str line) " is rejected"))))))

(deftest invalid-response-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [uri]}]
                     (case uri
                       "/split"   {:status  200
                                   :headers {"Location" "/x\r\nX-Injected: yes"}
                                   :body    "Hello World"}
                       "/name"    {:status  200
                                   :headers {"X Example" "foo"}
                                   :body    "Hello World"}
                       "/status"  {:status "200" :headers {} :body "Hello World"}
                       "/unknown" {:status 599 :headers {} :body "Hello World"}))
                   {:port 4358
                    :error-logger #(swap! logs conj (ex-message %))})]
      (doseq [uri ["/split" "/name" "/status"]]
        (let [response (raw-http-request
                        "localhost" 4358
                        (str "GET " uri " HTTP/1.1\r\n"
                             "Host: localhost\r\n"
                             "Connection: close\r\n\r\n"))]
          (is (= (str "HTTP/1.1 500 Internal Server Error\r\n"
                      "Connection: close\r\n"
                      "Server: Capra\r\n"
                      "Content-Type: text/plain; charset=UTF-8\r\n"
                      "Content-Length: 21\r\n\r\n"
                      "Internal Server Error")
                 (str/replace response #"Date: (.*?)\r\n" ""))
              (str "The response to " uri " is replaced by a 500"))))
      (is (= ["Invalid response" "Invalid response" "Invalid response"] @logs))
      (let [response (raw-http-request
                      "localhost" 4358
                      (str "GET /unknown HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Connection: close\r\n\r\n"))]
        (is (str/starts-with? response "HTTP/1.1 599 Unknown\r\n")
            "A status with no known reason phrase is still written")))))

(deftest unsupported-informational-response-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:status 103, :headers {}, :body "hints"})
                   {:port 4372
                    :error-logger #(swap! logs conj (ex-message %))})]
      (let [response (raw-http-request
                      "localhost" 4372
                      (str "GET / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Connection: close\r\n\r\n"))]
        (is (str/starts-with? response
                              "HTTP/1.1 500 Internal Server Error\r\n"))
        (is (= ["Invalid response"] @logs)
            "A lone 1xx response is rejected instead of ending the exchange")))))

(deftest successful-connect-response-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:status 200, :headers {}, :body "not a tunnel"})
                   {:port 4373
                    :error-logger #(swap! logs conj (ex-message %))})]
      (let [response (raw-http-request
                      "localhost" 4373
                      (str "CONNECT example.com:443 HTTP/1.1\r\n"
                           "Host: example.com:443\r\n"
                           "Connection: close\r\n\r\n"))]
        (is (str/starts-with? response
                              "HTTP/1.1 500 Internal Server Error\r\n"))
        (is (= ["Invalid response"] @logs)
            "A successful CONNECT is rejected without a tunnel handoff API")))))

(deftest non-map-response-headers-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [uri]}]
                     {:status 200
                      :headers (case uri
                                 "/vector" [["X-Test" "value"]]
                                 "/nil" nil)
                      :body "Hello World"})
                   {:port 4374
                    :error-logger #(swap! logs conj (ex-message %))})]
      (doseq [uri ["/vector" "/nil"]]
        (let [response (raw-http-request
                        "localhost" 4374
                        (str "GET " uri " HTTP/1.1\r\n"
                             "Host: localhost\r\n"
                             "Connection: close\r\n\r\n"))]
          (is (str/starts-with? response
                                "HTTP/1.1 500 Internal Server Error\r\n")
              (str "The response to " uri " uses the 500 fallback"))))
      (is (= ["Invalid response" "Invalid response"] @logs)))))

(deftest async-streaming-response-body-test
  (let [body (reify ring.core.protocols/StreamableResponseBody
               (write-body-to-stream [_ _ out]
                 (.write ^java.io.OutputStream out (.getBytes "Hello World"
                                                              "UTF-8"))
                 (.flush ^java.io.OutputStream out)))]
    (with-open [_ (capra/run-server
                   (fn handler [_request respond _raise]
                     (future (respond {:status  200
                                       :headers {"Content-Type" "text/plain"}
                                       :body    body})))
                   {:port 4359, :async? true})]
      (let [response (raw-http-request
                      "localhost" 4359
                      (str "GET / HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Connection: close\r\n\r\n"))]
        (is (= (str "HTTP/1.1 200 OK\r\n"
                    "Connection: close\r\n"
                    "Server: Capra\r\n"
                    "Content-Type: text/plain\r\n"
                    "Transfer-Encoding: chunked\r\n"
                    "Connection: Transfer-Encoding\r\n\r\n"
                    "B\r\nHello World\r\n"
                    "0\r\n\r\n")
               (str/replace response #"Date: (.*?)\r\n" ""))
            "A body that does not close the sink is still terminated")))))

(deftest head-request-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4360})]
    (let [strip #(str/replace % #"Date: (.*?)\r\n" "")
          get*  (raw-http-request "localhost" 4360
                                  (str "GET / HTTP/1.1\r\n"
                                       "Host: localhost\r\n"
                                       "Connection: close\r\n\r\n"))
          head  (raw-http-request "localhost" 4360
                                  (str "HEAD / HTTP/1.1\r\n"
                                       "Host: localhost\r\n"
                                       "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 11\r\n\r\n")
             (strip head))
          "A HEAD response has the headers of a GET response and no body")
      (is (= (strip get*) (str (strip head) "Hello World"))))))

(deftest bodyless-status-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri]}]
                   (case uri
                     "/204" {:status  204
                             :headers {"Content-Length" "11"}
                             :body    "Hello World"}
                     "/304" {:status  304
                             :headers {"ETag" "\"abc\""}
                             :body    "Hello World"}))
                 {:port 4361})]
    (is (= (str "HTTP/1.1 204 No Content\r\n"
                "Connection: close\r\n"
                "Server: Capra\r\n\r\n")
           (str/replace (raw-http-request "localhost" 4361
                                          (str "GET /204 HTTP/1.1\r\n"
                                               "Host: localhost\r\n"
                                               "Connection: close\r\n\r\n"))
                        #"Date: (.*?)\r\n" ""))
        "A 204 response carries no framing fields and no body")
    (is (= (str "HTTP/1.1 304 Not Modified\r\n"
                "Connection: close\r\n"
                "Server: Capra\r\n"
                "ETag: \"abc\"\r\n\r\n")
           (str/replace (raw-http-request "localhost" 4361
                                          (str "GET /304 HTTP/1.1\r\n"
                                               "Host: localhost\r\n"
                                               "Connection: close\r\n\r\n"))
                        #"Date: (.*?)\r\n" ""))
        "A 304 response keeps its fields but carries no body")))

(deftest file-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (io/file "test/capra/test_file.txt")})
                 {:port 4342})]
    (let [response (http/get "http://localhost:4342")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "12"
                        "Server"         "Capra"}
              :body    "Hello World\n"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest multiple-request-headers-test
  (with-open [_ (capra/run-server
                 (fn handler [{{:strs [test-header]} :headers}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (pr-str test-header)})
                 {:port 4343})]
    (let [response (http/get "http://localhost:4343/"
                             {:headers {"Test-Header" ["One" "Two" "Three"]}})]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "15"
                        "Server"         "Capra"}
              :body    "\"One,Two,Three\""}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest multiple-connection-headers-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Connection" ["close" "Transport-Encoding"]
                              "Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4344})]
    (let [response (raw-http-request
                    "localhost" 4344
                    (str "GET / HTTP/1.1\r\n"
                         "Host: localhost\r\n\r\n"))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Server: Capra\r\n"
                  "Connection: close\r\n"
                  "Connection: Transport-Encoding\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 11\r\n\r\n"
                  "Hello World")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest input-stream-response-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (java.io.ByteArrayInputStream.
                              (.getBytes "Hello World" "UTF-8"))})
                 {:port 4345})]
    (let [response (http/get "http://localhost:4345")]
      (is (= {:status  200
              :headers {"Connection"        ["close" "Transfer-Encoding"]
                        "Content-Type"      "text/plain; charset=UTF-8"
                        "Transfer-Encoding" "chunked"
                        "Server"            "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest large-file-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (io/file "test/capra/test_image.jpg")})
                 {:port 4346})]
    (let [response (http/get "http://localhost:4346"
                             {:as :byte-array})]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Type"   "text/plain; charset=UTF-8"
                        "Content-Length" "707328"
                        "Server"         "Capra"}}
             (-> response
                 (select-keys [:status :headers])
                 (update :headers dissoc "Date"))))
      (is (= "af779e68245bad2adc3e2537103b7a898e2a068d5ebbee5c30ef0b217a1c8199"
             (sha256sum (:body response)))))))

(deftest run-server-options-test
  (letfn [(handler [_request]
            {:status  200
             :headers {"Content-Type" "text/plain; charset=UTF-8"}
             :body    "Hello World"})]
    (with-open [_ (capra/run-server handler :port 4347)]
      (let [response (http/get "http://localhost:4347")]
        (is (= {:status  200
                :headers {"Connection"     "close"
                          "Content-Type"   "text/plain; charset=UTF-8"
                          "Content-Length" "11"
                          "Server"         "Capra"}
                :body    "Hello World"}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "Date"))))))))

(deftest request-with-chunked-body-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [body]}]
                   {:status  200, :headers {}, :body body})
                 {:port 4348})]
    (let [response (http/post "http://localhost:4348"
                              {:body (java.io.ByteArrayInputStream.
                                      (.getBytes "Hello World" "UTF-8"))})]
      (is (= {:status  200
              :headers {"Connection"        ["close" "Transfer-Encoding"]
                        "Transfer-Encoding" "chunked"
                        "Server"            "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest response-without-content-type-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {}
                    :body    "Hello World"})
                 {:port 4349})]
    (let [response (http/get "http://localhost:4349")]
      (is (= {:status  200
              :headers {"Connection"     "close"
                        "Content-Length" "11"
                        "Server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "Date")))))))

(deftest pipelined-requests-test
  (with-open [_ (capra/run-server
                 (fn [{:keys [uri]} respond _raise]
                   (case uri
                     "/foo"
                     (future
                       (Thread/sleep 30)
                       (respond
                        {:status  200
                         :headers {"Content-Type" "text/plain; charset=UTF-8"}
                         :body    "Foo"}))
                     "/bar"
                     (respond
                      {:status  200
                       :headers {"Content-Type" "text/plain; charset=UTF-8"}
                       :body    "Bar"})))
                 {:port   4350
                  :async? true})]
    (let [response (raw-http-request
                    "localhost" 4350
                    (str "GET /foo HTTP/1.1\r\n"
                         "Host: localhost\r\n\r\n"
                         "GET /bar HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 3\r\n\r\n"
                  "Foo"
                  "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 3\r\n\r\n"
                  "Bar")
             (str/replace response #"Date: (.*?)\r\n" ""))))))
