(ns capra.server
  (:require [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]
            [teensyp.stream :as stream])
  (:import [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private reason-phrase
  {100 "Continue"
   101 "Switching Protocols"
   200 "OK"
   201 "Created"
   202 "Accepted"
   203 "Non-Authoritative Information"
   204 "No Content"
   205 "Reset Content"
   206 "Partial Content"
   300 "Multiple Choices"
   301 "Moved Permanently"
   302 "Found"
   303 "See Other"
   304 "Not Modified"
   305 "Use Proxy"
   307 "Temporary Redirect"
   400 "Bad Request"
   401 "Unauthorized"
   402 "Payment Required"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   406 "Not Acceptable"
   407 "Proxy Authentication Required"
   408 "Request Timeout"
   409 "Conflict"
   410 "Gone"
   411 "Length Required"
   412 "Precondition Failed"
   413 "Payload Too Large"
   414 "URI Too Long"
   415 "Unsupported Media Type"
   416 "Range Not Satisfiable"
   417 "Expectation Failed"
   426 "Upgrade Required"
   500 "Internal Server Error"
   501 "Not Implemented"
   502 "Bad Gateway"
   503 "Service Unavailable"
   504 "Gateway Timeout"
   505 "HTTP Version Not Supported"})

(defn- init-request [socket]
  (let [info   (tcp/socket-info socket)
        local  ^InetSocketAddress (:local-address info)
        remote ^InetSocketAddress (:remote-address info)]
    {::state      :start-line
     :scheme      :http
     :server-port (.getPort local)
     :server-name (.getHostString local)
     :remote-addr (.getHostString remote)}))

(defn- parse-start-line [request buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)] 
    (let [[method uri protocol] (str/split line #" ")]
      (assoc! request
              ::state         :headers
              :request-method (keyword (str/lower-case method))
              :uri            uri
              :protocol       protocol
              :headers        (transient {})))))

(defn- parse-header [{:keys [headers] :as request} buffer]
  (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
    (if (str/blank? line)
      (assoc! request
              ::state  :handler
              :headers (persistent! headers))
      (let [[name value] (str/split line #":")]
        (->> (assoc! headers (str/lower-case name) (str/trim value))
             (assoc! request :headers))))))

(defn- write-ascii [^ByteBuffer buffer ^String s]
  (.put buffer (.getBytes s StandardCharsets/US_ASCII)))

(defn- ring-responder [{:keys [protocol]} socket]
  (fn [{:keys [status headers]}]
    (let [buffer (ByteBuffer/allocate 32768)
          reason (reason-phrase status)]
      (write-ascii buffer (str protocol " " status " " reason "\r\n"))
      (doseq [kv headers]
        (write-ascii buffer (str (key kv) ": " (val kv) "\r\n")))
      (write-ascii buffer "\r\n")
      (.flip buffer)
      (tcp/write socket buffer))))

(defn- run-ring-handler [ring-handler request socket stream-opts]
  (let [respond (ring-responder request socket)
        raise   (fn [_ex])
        handler (stream/stream-handler
                 (fn [in _out]
                   (prn :inside-handler)
                   (-> (assoc! request :body in)
                       (persistent!)
                       (ring-handler respond raise)))
                 stream-opts)]
    {::state   :body
     ::handler handler
     ::context (handler socket)}))

(defn- stream-body [{::keys [handler] :as context} socket buffer]
  (update context ::context handler socket buffer))

(defn- new-default-executor []
  (Executors/newFixedThreadPool 16))

(defn- http-handler [handler {:keys [handler-executor body-buffer-size]}]
  (let [opts {:executor (or handler-executor (new-default-executor))
              :read-buffer-size (or body-buffer-size 8192)}]
    (fn
      ([socket]
       (transient (init-request socket)))
      ([{::keys [state] :as request} socket buffer]
       (if-some [request
                 (case state
                   :start-line (parse-start-line request buffer)
                   :headers    (parse-header request buffer)
                   :handler    (run-ring-handler handler request socket opts)
                   nil)] 
          (recur request socket buffer)
          (case state
            :body (stream-body request socket buffer)
            request)))
      ([_state exception]
       (when exception (prn :exception exception))))))

(defn start-server [handler options]
  (tcp/start-server (-> options
                        (assoc :executor (:socket-executor options))
                        (assoc :handler (http-handler handler options)))))

(comment
  server

  (def server
    (start-server
     (fn [request respond _raise]
       (prn :request request)
       (respond {:status  200
                 :headers {"Content-Type" "text/html; charset=UTF-8"}
                 :body    "Hello World"}))
     {:port 4000, :reuse-address? true}))
  
  (.close server))
