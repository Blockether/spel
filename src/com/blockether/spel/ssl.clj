(ns com.blockether.spel.ssl
  "Custom SSL/TLS certificate support for corporate proxy environments.

   When `spel install` downloads the Playwright driver from cdn.playwright.dev,
   GraalVM native-image uses a TrustStore baked at build time. Corporate
   SSL-inspecting proxies use internal CAs not in that store, causing
   'PKIX path building failed'.

   This namespace provides a composite TrustManager that merges the built-in
   default CAs with user-provided certificates (PEM or JKS/PKCS12), so both
   public CDN certs and corporate certs are trusted.

   Environment variables (checked in order):
     SPEL_CA_BUNDLE           — PEM file with extra CA certs (merged with defaults)
     NODE_EXTRA_CA_CERTS      — same as above, also respected by Node.js subprocess
     SPEL_TRUSTSTORE          — JKS/PKCS12 truststore path (merged with defaults)
     SPEL_TRUSTSTORE_TYPE     — truststore type (default: JKS)
     SPEL_TRUSTSTORE_PASSWORD — truststore password (default: empty)"
  (:require
   [clojure.string :as str])
  (:import
   [java.io BufferedInputStream InputStream]
   [java.nio.file Files Paths]
   [java.security KeyStore]
   [java.security.cert CertificateException CertificateFactory X509Certificate]
   [javax.net.ssl SSLContext SSLSocketFactory TrustManager TrustManagerFactory X509TrustManager]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- getenv
  "Returns trimmed non-empty env var value, or nil."
  ^String [^String k]
  (let [v (System/getenv k)]
    (when (and v (not (str/blank? v)))
      (str/trim v))))

(defn- path-exists?
  "Returns true if the given path string points to an existing file."
  [^String p]
  (and p (Files/exists (Paths/get p (into-array String []))
           (into-array java.nio.file.LinkOption []))))

;; =============================================================================
;; TrustManager construction
;; =============================================================================

(defn- default-x509-trust-manager
  "Returns the default X509TrustManager (backed by the baked-in cacerts)."
  ^X509TrustManager []
  (let [algo    ^String (TrustManagerFactory/getDefaultAlgorithm)
        tmf     (TrustManagerFactory/getInstance algo)
        ^KeyStore no-ks nil]
    (.init tmf no-ks)
    (let [tms (.getTrustManagers tmf)]
      (or (first (filter #(instance? X509TrustManager %) (seq tms)))
        (throw (ex-info "No default X509TrustManager found" {:algo algo}))))))

(defn- load-pem-certs
  "Loads X.509 certificates from a PEM bundle file.
   Supports multiple concatenated PEM certificates."
  [^String path]
  (with-open [^InputStream is (-> (Paths/get path (into-array String []))
                                (Files/newInputStream (into-array java.nio.file.OpenOption []))
                                (BufferedInputStream.))]
    (let [cf    (CertificateFactory/getInstance "X.509")
          certs (.generateCertificates cf is)]
      (when (or (nil? certs) (.isEmpty certs))
        (throw (ex-info "No certificates found in PEM bundle" {:path path})))
      (vec certs))))

(defn- x509-trust-manager-from-pem
  "Creates an X509TrustManager from a PEM certificate bundle."
  ^X509TrustManager [^String path]
  (let [certs (load-pem-certs path)
        ks    (doto (KeyStore/getInstance (KeyStore/getDefaultType))
                (.load nil nil))]
    (doseq [[i ^X509Certificate c] (map-indexed vector certs)]
      (when-not (instance? X509Certificate c)
        (throw (ex-info "Non-X509 certificate in PEM bundle"
                 {:path path :class (class c)})))
      (.setCertificateEntry ks (str "ca-" i) c))
    (let [tmf (TrustManagerFactory/getInstance
                ^String (TrustManagerFactory/getDefaultAlgorithm))]
      (.init tmf ks)
      (let [tms (.getTrustManagers tmf)]
        (or (first (filter #(instance? X509TrustManager %) (seq tms)))
          (throw (ex-info "No X509TrustManager from PEM bundle" {:path path})))))))

(defn- x509-trust-manager-from-keystore
  "Creates an X509TrustManager from a JKS/PKCS12 truststore."
  ^X509TrustManager [^String path ^String ks-type ^String password]
  (let [ks-type (or ks-type (KeyStore/getDefaultType))
        pass    (when password (.toCharArray password))
        ks      (KeyStore/getInstance ks-type)]
    (with-open [is (Files/newInputStream
                     (Paths/get path (into-array String []))
                     (into-array java.nio.file.OpenOption []))]
      (.load ks is pass))
    (let [tmf (TrustManagerFactory/getInstance
                ^String (TrustManagerFactory/getDefaultAlgorithm))]
      (.init tmf ks)
      (let [tms (.getTrustManagers tmf)]
        (or (first (filter #(instance? X509TrustManager %) (seq tms)))
          (throw (ex-info "No X509TrustManager from truststore"
                   {:path path :type ks-type})))))))

;; =============================================================================
;; Composite TrustManager
;; =============================================================================

(defn- composite-x509-trust-manager
  "Creates a composite X509TrustManager that tries the primary (default) TM
   first, then falls back to the secondary (custom) TM on CertificateException.

   This is necessary because SSLContext.init() only uses the FIRST
   X509TrustManager in the array — it does NOT iterate them."
  ^X509TrustManager
  [^X509TrustManager primary ^X509TrustManager secondary]
  (reify X509TrustManager
    (getAcceptedIssuers [_]
      (let [a (.getAcceptedIssuers primary)
            b (.getAcceptedIssuers secondary)]
        (into-array X509Certificate (concat (seq a) (seq b)))))
    (checkClientTrusted [_ chain auth-type]
      (try
        (.checkClientTrusted primary chain auth-type)
        (catch CertificateException _
          (.checkClientTrusted secondary chain auth-type))))
    (checkServerTrusted [_ chain auth-type]
      (try
        (.checkServerTrusted primary chain auth-type)
        (catch CertificateException _
          (.checkServerTrusted secondary chain auth-type))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn custom-ssl-factory
  "Returns a custom SSLSocketFactory that trusts both the default CAs and
   user-provided corporate CAs, or nil if no custom CA config is present.

   Env var priority:
     1. SPEL_CA_BUNDLE          — PEM file (fail hard if missing)
     2. SPEL_TRUSTSTORE         — JKS/PKCS12 (fail hard if missing)
     3. NODE_EXTRA_CA_CERTS     — PEM file (warn + skip if missing)"
  ^SSLSocketFactory []
  (let [spel-pem (getenv "SPEL_CA_BUNDLE")
        spel-ts  (getenv "SPEL_TRUSTSTORE")
        node-pem (getenv "NODE_EXTRA_CA_CERTS")]
    (cond
      ;; SPEL_CA_BUNDLE — explicit, fail hard on bad path
      spel-pem
      (do
        (when-not (path-exists? spel-pem)
          (throw (ex-info (str "SPEL_CA_BUNDLE points to missing file: " spel-pem)
                   {:path spel-pem})))
        (let [default-tm (default-x509-trust-manager)
              custom-tm  (x509-trust-manager-from-pem spel-pem)
              tm         (composite-x509-trust-manager default-tm custom-tm)
              ctx        (SSLContext/getInstance "TLS")]
          (.init ctx nil (into-array TrustManager [tm]) nil)
          (.getSocketFactory ctx)))

      ;; SPEL_TRUSTSTORE — explicit, fail hard on bad path
      spel-ts
      (do
        (when-not (path-exists? spel-ts)
          (throw (ex-info (str "SPEL_TRUSTSTORE points to missing file: " spel-ts)
                   {:path spel-ts})))
        (let [default-tm (default-x509-trust-manager)
              custom-tm  (x509-trust-manager-from-keystore
                           spel-ts
                           (getenv "SPEL_TRUSTSTORE_TYPE")
                           (getenv "SPEL_TRUSTSTORE_PASSWORD"))
              tm         (composite-x509-trust-manager default-tm custom-tm)
              ctx        (SSLContext/getInstance "TLS")]
          (.init ctx nil (into-array TrustManager [tm]) nil)
          (.getSocketFactory ctx)))

      ;; NODE_EXTRA_CA_CERTS — best-effort, warn + skip on bad path
      node-pem
      (if-not (path-exists? node-pem)
        (do
          (binding [*out* *err*]
            (println (str "spel: warn: NODE_EXTRA_CA_CERTS points to missing file: "
                       node-pem " — ignoring")))
          nil)
        (try
          (let [default-tm (default-x509-trust-manager)
                custom-tm  (x509-trust-manager-from-pem node-pem)
                tm         (composite-x509-trust-manager default-tm custom-tm)
                ctx        (SSLContext/getInstance "TLS")]
            (.init ctx nil (into-array TrustManager [tm]) nil)
            (.getSocketFactory ctx))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "spel: warn: Failed to load NODE_EXTRA_CA_CERTS ("
                         node-pem "): " (.getMessage e) " — ignoring")))
            nil)))

      ;; No custom CA config
      :else nil)))
