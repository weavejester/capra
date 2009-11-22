(ns capra.ssl
  (:use capra.io-utils)
  (:import java.security.KeyStore)
  (:import java.security.cert.CertificateFactory)
  (:import javax.net.ssl.SSLContext)
  (:import javax.net.ssl.TrustManagerFactory))

(def cert-factory
  (CertificateFactory/getInstance "X.509"))

(defn load-cert
  "Load a X.509 certificate from a resource path."
  [path]
  (.generateCertificate cert-factory (load-resource path)))

(defn make-keystore
  "Create a new temportary KeyStore for the supplied X.509 certificate."
  [cert-path]
  (doto (KeyStore/getInstance (KeyStore/getDefaultType))
    (.load nil nil)
    (.setCertificateEntry "server" (load-cert cert-path))))

(defn make-trust-factory
  "Create a TrustManagerFactory from a X.509 certificate."
  [cert-path]
  (doto (TrustManagerFactory/getInstance
          (TrustManagerFactory/getDefaultAlgorithm))
    (.init (make-keystore cert-path))))

(defn make-socket-factory
  "Create a custom SSL socket factory from a X.509 certificate."
  [cert-path]
  (let [manager (.getTrustManagers (make-trust-factory cert-path))
        context (doto (SSLContext/getInstance "TLS")
                  (.init nil manager nil))]
    (.getSocketFactory context)))

(def capra-socket-factory
  (make-socket-factory "capra.crt"))
