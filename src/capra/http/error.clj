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
     :body    "Request header field too large."})})


