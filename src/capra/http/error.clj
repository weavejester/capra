(ns capra.http.error)

(def error-handlers
  {:unsupported-transfer-encoding
   (fn [{{:strs [transfer-encoding]} :headers}]
     {:status  501
      :headers {"Connection" "close"
                "Content-Type" "text/plain; charset=UTF-8"}
      :body    (str "Unsupported request transfer encoding: \""
                    transfer-encoding "\".\n"
                    "Only \"chunked\" transfer encoding supported.")})
   :uri-too-long
   (constantly
    {:status  414
     :headers {"Connection" "close"
               "Content-Type" "text/plain; charset=UTF-8"}
     :body    "URI too long."})
   :request-header-field-too-large
   (constantly
    {:status  431
     :headers {"Connection" "close"
               "Content-Type" "text/plain; charset=UTF-8"}
     :body    "Request header field too large."})
   :missing-host-header
   (constantly
    {:status  400
     :headers {"Connection" "close"
               "Content-Type" "text/plain; charset=UTF-8"}
     :body    "Missing \"Host\" header in request."})
   :http-version-not-supported
   (fn [{:keys [bad-protocol]}]
     {:status  505
      :headers {"Connection" "close"
                "Content-Type" "text/plain; charset=UTF-8"}
      :body    (str "Unsupported HTTP version: \"" bad-protocol "\".\n"
                    "Only \"HTTP/1.0\" and \"HTTP/1.1\" supported.")})
   :invalid-request-start-line
   (constantly
    {:status  400
     :headers {"Connection" "close"
               "Content-Type" "text/plain; charset=UTF-8"}
     :body    "Invalid HTTP request start line."})
   :invalid-request-header
   (fn [{:keys [bad-header]}]
     {:status  400
      :headers {"Connection" "close"
                "Content-Type" "text/plain; charset=UTF-8"}
      :body    (str "Invalid HTTP request header line: \"" bad-header "\".")})})


