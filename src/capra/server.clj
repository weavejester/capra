(ns capra.server
  "The namespace for running the Capra server."
  (:require [capra.http :as http]
            [teensyp.server :as tcp])
  (:import [java.io Closeable]
           [java.util.concurrent Executors]))

(defn- async-handler [handler]
  (fn [request respond raise]
    (handler request #(respond % true) raise)))

(defn- sync-handler [handler]
  (fn [request respond raise]
    (let [response (try (handler request)
                        (catch Exception ex (raise ex) ::error))]
      (when (not= response ::error)
        (respond response false)))))

(defn- default-error-handler [_request respond _exception]
  (respond {:status  500
            :headers {"Content-Type" "text/plain; charset=UTF-8"}
            :body    "Internal Server Error"}))

(defn- default-error-logger [exception]
  (locking *err* (binding [*out* *err*]) (prn exception)))

(defn- maybe-virtual-thread-executor  []
  (try (eval '(Executors/newVirtualThreadPerTaskExecutor))
       (catch Throwable _ nil)))

(defn- new-default-executor []
  (or (maybe-virtual-thread-executor)
      (Executors/newFixedThreadPool 256)))

(defn- new-default-options []
  {:direct-read-buffer?  true
   :error-handler        default-error-handler
   :error-logger         default-error-logger
   :executor             (new-default-executor)
   :port                 80
   :read-buffer-size     8192
   :response-buffer-size 32768
   :stream-buffer-size   8192})

(defn run-server
  "Start a web server in a new thread with the supplied Ring handler and
  options. Returns a `java.io.Closeable` instance that can be used to stop the
  adapter via the `close` method.
  
  Accepts the following options:

  - `:async?` - if true, expect 3-arity async Ring handlers (defaults to false)
  - `:control-queue-size` - the max number of queued control events (default 32)
  - `:direct-read-buffer?` - allocate a direct buffer for reads (default true)
  - `:error-handler` - an asynchronous Ring handler function used to handle
    uncaught exceptions (defaults to sending a 500 Internal Server Error).
  - `:error-logger` - a function that takes a single exception argument and
    logs it somehow (defaults to printing to *err*)
  - `:executor` - the ExecutorService to use for running handlers
  - `:port` - the port number to listen on (defaults to 80)
  - `:read-buffer-size` - the size of the buffer to use when reading from the
    socket (defaults to 8K)
  - `:recv-buffer-size` - the receive buffer size (i.e. the SO_RCVBUF option)
  - `:response-buffer-size` - the size of the buffer used when constructing
    the response, which must be at least large enough to contain the response
    status line and headers (defaults to 32K)
  - `:reuse-address?` - sets the SO_REUSEADDR socket option (default false)
  - `:stream-buffer-size` - the size of the buffer used to read in the body of
    the request when streaming (defaults to 8K)
  - `:write-buffer-size` - the write buffer size in bytes (default 32K)
  - `:write-queue-size` - the max number of writes in the queue (default 64)"
  ^Closeable [handler & {:as options}]
  (let [options (merge (new-default-options) options)
        handler (if (:async? options)
                  (async-handler handler)
                  (sync-handler handler))]
    (tcp/run-server
     (assoc options :handler (http/tcp-handler handler options)))))
