(ns capra.http.error)

(defn- plaintext-response [status body]
  {:status  status
   :headers {"Connection" "close"
             "Content-Type" "text/plain; charset=UTF-8"}
   :body    body})

(def error-handlers
  {:unsupported-transfer-encoding
   (fn [{{:strs [transfer-encoding]} :headers}]
     (plaintext-response
      501 (str "Unsupported request transfer encoding: \"" transfer-encoding
               "\".\nOnly \"chunked\" transfer encoding supported.")))
   :uri-too-long
   (constantly (plaintext-response 414 "URI too long."))
   :request-header-field-too-large
   (constantly (plaintext-response 431 "Request header field too large."))
   :missing-host-header
   (constantly (plaintext-response 400 "Missing \"Host\" header in request."))
   :http-version-not-supported
   (fn [{:keys [bad-protocol]}]
     (plaintext-response
      505 (str "Unsupported HTTP version: \"" bad-protocol
               "\".\nOnly \"HTTP/1.0\" and \"HTTP/1.1\" supported.")))
   :invalid-request-start-line
   (constantly (plaintext-response 400 "Invalid HTTP request start line."))
   :invalid-request-header
   (fn [{:keys [bad-header]}]
     (plaintext-response
      400 (str "Invalid HTTP request header line: \"" bad-header "\".")))})
