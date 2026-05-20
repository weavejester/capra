(ns capra.server
  (:require [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(defn- http-handler
  ([_socket]
   (transient {}))
  ([state socket ^ByteBuffer buffer]
   (when-some [line (buf/read-line buffer StandardCharsets/US_ASCII)]
     (let [[method uri protocol] (str/split line #" ")]
       (assoc! state
               :request-method method
               :uri uri
               :protocol protocol))
     (tcp/close socket)
     state))
  ([state _exception]
   (prn (persistent! state))))

(defn start-server [options]
  (tcp/start-server (assoc options :handler #'http-handler)))
