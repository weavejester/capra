(ns capra.server-test
  (:require [capra.server :as capra]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [hato.websocket :as ws]
            [ring.websocket.protocols :as rwp])
  (:import [java.nio ByteBuffer]))

(defn- raw-http-stream [^String host ^long port f]
  (with-open [socket (java.net.Socket. host port)
              writer (io/writer (.getOutputStream socket) :encoding "US-ASCII")]
    (.setSoTimeout socket 1000)
    (f writer)
    (slurp (.getInputStream socket) :encoding "US-ASCII")))

(defn- raw-http-request [host port ^String raw-request]
  (raw-http-stream host port (fn [^java.io.Writer w]
                               (.write w raw-request)
                               (.flush w))))

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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "11"
                        "server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date"))))
      (is (re-matches #"\w{3}, \d+ \w{3} \d{4} \d\d:\d\d:\d\d GMT"
                      (get-in response [:headers "date"]))))))

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
              :headers {"content-type"   "text/plain"
                        "content-length" "11"
                        "server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
              :headers {"content-type"   "text/plain"
                        "content-length" "11"
                        "server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

(deftest multiple-response-headers-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"
                              "X-Example" ["foo" "bar"]}
                    :body    "Hello World"})
                 {:port 4324})]
    (let [response (http/get "http://localhost:4324")]
      (is (= {"content-type"   "text/plain; charset=UTF-8"
              "content-length" "11"
              "server"         "Capra"
              "x-example"      ["foo" "bar"]}
             (-> response :headers (dissoc "date")))))))

(deftest byte-array-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (.getBytes "Hello World" "UTF-8")})
                 {:port 4325})]
    (let [response (http/get "http://localhost:4325")]
      (is (= {:status  200
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "11"
                        "server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
                :headers {"content-type"   "text/plain; charset=UTF-8"
                          "content-length" "1200"
                          "server"         "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "date"))))))))

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
                :headers {"content-type"      "text/plain; charset=UTF-8"
                          "transfer-encoding" "chunked"
                          "server"            "Capra"}
                :body    large-body}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "date"))))))))

(deftest persistent-connection-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4328})]
    (let [client    (http/build-http-client {:connect-timeout 10000})
          http-get  #(http/get "http://localhost:4328" {:http-client client})
          responses (->> (repeatedly 10 http-get)
                         (doall)
                         (map #(-> %
                                   (select-keys [:status :headers :body])
                                   (update :headers dissoc "date"))))]
      (is (= 10 (count responses)))
      (is (= 1 (count (distinct responses))))
      (is (= {:status  200
              :headers {"content-type"      "text/plain; charset=UTF-8"
                        "content-length"    "11"
                        "server"            "Capra"}
              :body    "Hello World"}
             (first responses))))))

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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "5"
                        "server"         "Capra"}
              :body    "Hello"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

(deftest exception-in-handler-test
  (let [logs (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     (throw (ex-info "Error" {})))
                   {:port 4330
                    :error-logger #(swap! logs conj (ex-message %))})]
      (let [response (http/get "http://localhost:4330"
                               {:throw-exceptions? false})]
        (is (= {:status  500
                :headers {"content-type"   "text/plain; charset=UTF-8"
                          "content-length" "21"
                          "server"         "Capra"}
                :body    "Internal Server Error"}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "date"))))
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
              :headers {"content-length" "0"
                        "location"       "http://example.com"
                        "server"         "Capra"}
              :body    ""}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "10"
                        "server"         "Capra"}
              :body    "Hello Worl"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))
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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "10"
                        "server"         "Capra"}
              :body    "Hello Worl"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))
          "Shorter Content-Length cuts off body")))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type"   "text/plain; charset=UTF-8"
                              "Content-Length" "12"}
                    :body    "Hello World"})
                 {:port 4334})]
    (is (thrown-with-msg? java.io.IOException
                          #"closed"
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
    (is (thrown-with-msg? java.io.IOException
                          #"closed"
                          (http/get "http://localhost:4335"))
        "Longer Content-Length immediately closes"))
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Length" "1"}})
                 {:port 4336})]
    (is (thrown-with-msg? java.io.IOException
                          #"closed"
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
                             {:throw-exceptions? false})]
      (is (= {:status  414
              :headers {"connection"     "close"
                        "content-type"   "text/plain; charset=UTF-8"
                        "content-length" "13"
                        "server"         "Capra"}
              :body    "URI too long."}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
                                {:throw-exceptions? false
                                 :headers {"Long-Header" long-header}})]
      (is (= {:status  431
              :headers {"connection"     "close"
                        "content-type"   "text/plain; charset=UTF-8"
                        "content-length" "31"
                        "server"         "Capra"}
              :body    "Request header field too large."}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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

(deftest file-response-body-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (io/file "test/capra/test_file.txt")})
                 {:port 4342})]
    (let [response (http/get "http://localhost:4342")]
      (is (= {:status  200
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "12"
                        "server"         "Capra"}
              :body    "Hello World\n"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "15"
                        "server"         "Capra"}
              :body    "\"One,Two,Three\""}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
              :headers {"connection"        "Transfer-Encoding"
                        "content-type"      "text/plain; charset=UTF-8"
                        "transfer-encoding" "chunked"
                        "server"            "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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
              :headers {"content-type"   "text/plain; charset=UTF-8"
                        "content-length" "707328"
                        "server"         "Capra"}}
             (-> response
                 (select-keys [:status :headers])
                 (update :headers dissoc "date"))))
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
                :headers {"content-type"   "text/plain; charset=UTF-8"
                          "content-length" "11"
                          "server"         "Capra"}
                :body    "Hello World"}
               (-> response
                   (select-keys [:status :headers :body])
                   (update :headers dissoc "date"))))))))

(deftest request-with-chunked-body-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [body]}]
                   {:status  200, :headers {}, :body body})
                 {:port 4348})]
    (let [response (http/post "http://localhost:4348"
                              {:body (java.io.ByteArrayInputStream.
                                      (.getBytes "Hello World" "UTF-8"))})]
      (is (= {:status  200
              :headers {"connection"        "Transfer-Encoding"
                        "transfer-encoding" "chunked"
                        "server"            "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

(deftest response-without-content-type-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  200
                    :headers {}
                    :body    "Hello World"})
                 {:port 4349})]
    (let [response (http/get "http://localhost:4349")]
      (is (= {:status  200
              :headers {"content-length" "11"
                        "server"         "Capra"}
              :body    "Hello World"}
             (-> response
                 (select-keys [:status :headers :body])
                 (update :headers dissoc "date")))))))

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

(deftest unknown-status-code-test
  (with-open [_ (capra/run-server
                 (fn handler [_request]
                   {:status  555
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    "Hello World"})
                 {:port 4351})]
    (let [response (raw-http-request
                    "localhost" 4351
                    (str "GET / HTTP/1.1\r\n"
                         "Host: localhost\r\n"
                         "Connection: close\r\n\r\n"))]
      (is (= (str "HTTP/1.1 555 Unknown\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain; charset=UTF-8\r\n"
                  "Content-Length: 11\r\n\r\n"
                  "Hello World")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest multiple-close-test
  (dotimes [_ 1000]
    (with-open [_ (capra/run-server
                   (constantly {:status 204})
                   {:port 4352
                    :reuse-address? true})])))

(deftest request-query-string-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [uri query-string]
                               :or   {query-string :missing}}]
                   {:status  200
                    :headers {"Content-Type" "text/plain; charset=UTF-8"}
                    :body    (pr-str [uri query-string])})
                 {:port 4353})]
    (testing "valid query string"
      (let [response (http/get "http://localhost:4353/foobar?baz")]
        (is (= {:status 200
                :body   "[\"/foobar\" \"baz\"]"}
               (select-keys response [:status :body])))))
    (testing "no query string"
      (let [response (http/get "http://localhost:4353/foobar")]
        (is (= {:status 200
                :body   "[\"/foobar\" :missing]"}
               (select-keys response [:status :body])))))
    ;; Hato doesn't handle empty query strings correctly.
    (testing "empty query string"
      (let [response (raw-http-request
                      "localhost" 4353
                      (str "GET /foobar? HTTP/1.1\r\n"
                           "Host: localhost\r\n"
                           "Connection: close\r\n\r\n"))]
        (is (= (str "HTTP/1.1 200 OK\r\n"
                    "Connection: close\r\n"
                    "Server: Capra\r\n"
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                    "Content-Length: 14\r\n\r\n"
                    "[\"/foobar\" \"\"]")
               (str/replace response #"Date: (.*?)\r\n" "")))))))

(deftest large-request-body-test
  (let [large-body (byte-array (* 20 1024 1024))]
    (.nextBytes (java.util.Random. 42) large-body)
    (with-open [_ (capra/run-server
                   (fn handler [{:keys [^java.io.InputStream body]}]
                     {:status  200
                      :headers {"Content-Type" "application/octet-stream"}
                      :body    (.readAllBytes body)})
                   {:port 4355})]
      (let [response (http/post "http://localhost:4355"
                                {:body large-body :as :byte-array
                                 :timeout 5000})]
        (is (= {:status  200
                :headers {"content-type"   "application/octet-stream"
                          "content-length" (str (count large-body))
                          "server"         "Capra"}}
               (-> response
                   (select-keys [:status :headers])
                   (update :headers dissoc "date"))))
        (is (= (sha256sum large-body)
               (sha256sum (:body response))))))))

(deftest chunked-body-split-across-reads-test
  (with-open [_ (capra/run-server
                 (fn handler [{:keys [body]}]
                   {:status  200
                    :headers {"Content-Type" "text/plain"}
                    :body    (slurp body)})
                 {:port 4356})]
    (let [response (raw-http-stream
                    "localhost" 4356
                    (fn [^java.io.Writer writer]
                      (.write writer (str "POST / HTTP/1.1\r\n"
                                          "Host: localhost\r\n"
                                          "Transfer-Encoding: chunked\r\n"
                                          "Connection: close\r\n\r\n"
                                          "2\r\n20"))
                      (.flush writer)
                      (Thread/sleep 200)
                      (.write writer "\r\n0\r\n\r\n")
                      (.flush writer)))]
      (is (= (str "HTTP/1.1 200 OK\r\n"
                  "Connection: close\r\n"
                  "Server: Capra\r\n"
                  "Content-Type: text/plain\r\n"
                  "Content-Length: 2\r\n\r\n"
                  "20")
             (str/replace response #"Date: (.*?)\r\n" ""))))))

(deftest websocket-send-receive-test
  (let [server (atom [])
        client (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:ring.websocket/listener
                      (reify rwp/Listener
                        (on-open [_ sock]
                          (rwp/-send sock "Opened!"))
                        (on-message [_ sock msg]
                          (swap! server conj [:message msg])
                          (rwp/-send sock msg))
                        (on-pong [_ _ _])
                        (on-error [_ _ _])
                        (on-close [_ _ code reason]
                          (swap! server conj [:exit code reason])))})
                   {:port 4354})]
      (let [ws @(ws/websocket "ws://localhost:4354"
                              {:on-message
                               (fn [_ msg _]
                                 (swap! client conj [:message (str msg)]))})]
        (ws/send! ws "Hello World")
        (ws/close! ws 1000 "Normal exit"))
      (Thread/sleep 10)
      (is (= [[:message "Hello World"]
              [:exit 1000 "Normal exit"]]
             @server))
      (is (= [[:message "Opened!"]
              [:message "Hello World"]]
             @client)))))

(deftest websocket-ping-pong-test
  (let [server (atom [])
        client (atom [])]
    (with-open [_ (capra/run-server
                   (fn handler [_request]
                     {:ring.websocket/listener
                      (reify rwp/Listener
                        (on-open [_ sock]
                          (rwp/-ping sock
                                     (ByteBuffer/wrap (byte-array [4 5 6]))))
                        (on-message [_ _ _])
                        (on-pong [_ _ msg]
                          (swap! server conj
                                 [:pong (-> ^ByteBuffer msg .array seq)]))
                        (on-error [_ _ _])
                        (on-close [_ _ _ _])
                        rwp/PingListener
                        (on-ping [_ _ msg]
                          (swap! server conj
                                 [:ping (-> ^ByteBuffer msg .array seq)])))})
                   {:port 4357})]
      (let [ws @(ws/websocket
                 "ws://localhost:4357"
                 {:on-ping
                  (fn [_ ^ByteBuffer msg]
                    (swap! client conj [:ping (-> msg .array seq)]))
                  :on-pong
                  (fn [_ ^ByteBuffer msg]
                    (swap! client conj [:pong (-> msg .array seq)]))})]
        (Thread/sleep 10)
        (ws/ping! ws (ByteBuffer/wrap (byte-array [1 2 3])))
        (ws/close! ws 1000 "Normal exit"))
      (Thread/sleep 10)
      (is (= [[:pong [4 5 6]]
              [:ping [1 2 3]]]
             @server))
      (is (= [[:ping [4 5 6]]
              [:pong [1 2 3]]]
             @client)))))
