(ns com.blockether.spel.vault
  "Encrypted credential store for `spel auth save/login/list/delete`.

   Goal: the LLM never sees the password. Credentials are encrypted at rest
   with AES-256-GCM. The decryption key comes from:

   1. Env `SPEL_ENCRYPTION_KEY` (64-char hex = 32 bytes), or
   2. Auto-generated file at `~/.spel/.encryption-key` (chmod 600 on POSIX).

   Records live in `~/.spel/vault/<name>.json.enc` as:

     [12-byte IV][ciphertext][16-byte GCM tag]

   The JSON payload (before encryption) has `:name :url :username :password`.

   Threat model: this protects credentials from casual disk inspection and
   from the LLM driving the CLI — NOT from a local attacker with filesystem
   read. Use OS-level keyring for stronger guarantees."
  (:require
   [charred.api :as json]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions]
   [java.security SecureRandom]
   [java.util HexFormat]
   [javax.crypto Cipher]
   [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

;; =============================================================================
;; Key management
;; =============================================================================

(def ^:private ^:const key-size-bytes 32) ;; AES-256
(def ^:private ^:const iv-size-bytes  12) ;; GCM standard
(def ^:private ^:const tag-size-bits  128)

(defn- spel-config-root
  ^Path []
  (Paths/get (System/getProperty "user.home")
    (into-array String [".spel"])))

(defn- vault-dir
  ^Path []
  (.resolve (spel-config-root) "vault"))

(defn- key-file-path
  ^Path []
  (.resolve (spel-config-root) ".encryption-key"))

(defn- ensure-posix-600!
  "Best-effort chmod 600 on POSIX filesystems. No-op on Windows."
  [^Path path]
  (try
    (Files/setPosixFilePermissions path
      (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defn- generate-key-bytes
  ^bytes []
  (let [out (byte-array key-size-bytes)]
    (.nextBytes (SecureRandom.) out)
    out))

(defn- hex->bytes
  ^bytes [^String hex]
  (.parseHex (HexFormat/of) hex))

(defn- bytes->hex
  ^String [^bytes bs]
  (.formatHex (HexFormat/of) bs))

(defn- load-or-create-key
  "Resolves the encryption key as a 32-byte byte[]. Priority:
   1. Env SPEL_ENCRYPTION_KEY (hex)
   2. File ~/.spel/.encryption-key (hex), auto-created on first call."
  ^bytes []
  (if-let [hex (System/getenv "SPEL_ENCRYPTION_KEY")]
    (let [bs (hex->bytes (str/trim hex))]
      (when-not (= key-size-bytes (alength bs))
        (throw (ex-info (str "SPEL_ENCRYPTION_KEY must be " (* 2 key-size-bytes)
                          " hex chars (256-bit AES key)")
                 {:length (alength bs)})))
      bs)
    (let [path (key-file-path)]
      (if (.isFile (.toFile path))
        (hex->bytes (str/trim (slurp (.toFile path))))
        (do
          (Files/createDirectories (spel-config-root) (into-array FileAttribute []))
          (let [bs (generate-key-bytes)]
            (spit (.toFile path) (bytes->hex bs))
            (ensure-posix-600! path)
            bs))))))

;; =============================================================================
;; AES-256-GCM encrypt / decrypt
;; =============================================================================

(defn- cipher ^Cipher [] (Cipher/getInstance "AES/GCM/NoPadding"))

(defn encrypt
  "Encrypts `plaintext` (String) with AES-256-GCM using the resolved key.
   Returns a byte[] in the format `[iv (12)][ciphertext][tag (16)]`."
  ^bytes [^String plaintext]
  (let [key-bs (load-or-create-key)
        iv     (byte-array iv-size-bytes)
        _      (.nextBytes (SecureRandom.) iv)
        spec   (GCMParameterSpec. tag-size-bits iv)
        c      (cipher)
        _      (.init c Cipher/ENCRYPT_MODE
                 (SecretKeySpec. key-bs "AES")
                 spec)
        ct     (.doFinal c (.getBytes plaintext StandardCharsets/UTF_8))
        out    (byte-array (+ iv-size-bytes (alength ct)))]
    (System/arraycopy iv 0 out 0 iv-size-bytes)
    (System/arraycopy ct 0 out iv-size-bytes (alength ct))
    out))

(defn decrypt
  "Decrypts a byte[] previously produced by `encrypt`. Returns the plaintext
   String. Throws on authentication failure (tampered ciphertext or wrong key)."
  ^String [^bytes blob]
  (when (< (alength blob) (+ iv-size-bytes 16))
    (throw (ex-info "Ciphertext too short for AES-GCM envelope" {})))
  (let [key-bs (load-or-create-key)
        iv     (byte-array iv-size-bytes)
        _      (System/arraycopy blob 0 iv 0 iv-size-bytes)
        ct-len (- (alength blob) iv-size-bytes)
        ct     (byte-array ct-len)
        _      (System/arraycopy blob iv-size-bytes ct 0 ct-len)
        spec   (GCMParameterSpec. tag-size-bits iv)
        c      (cipher)
        _      (.init c Cipher/DECRYPT_MODE
                 (SecretKeySpec. key-bs "AES")
                 spec)
        pt     (.doFinal c ct)]
    (String. pt StandardCharsets/UTF_8)))

;; =============================================================================
;; Credential store (filesystem)
;; =============================================================================

(defn- record-path
  ^Path [^String name]
  (.resolve (vault-dir) (str name ".json.enc")))

(defn- safe-name?
  "Guards against path traversal. Names must be alphanumeric + `._-` only."
  [^String name]
  (boolean (re-matches #"[A-Za-z0-9._-]+" (or name ""))))

(defn save-credential!
  "Encrypts and writes a credential record to the vault.

   `record` must be a map with at least `:name :url :username :password`.
   The write is atomic: content is written to a `.tmp` file and renamed.

   Returns the absolute path (String) to the stored encrypted file."
  [record]
  (let [name (:name record)
        _    (when-not (safe-name? name)
               (throw (ex-info (str "Invalid credential name: " name
                                 " — must match [A-Za-z0-9._-]+")
                        {:name name})))
        dir  (vault-dir)
        _    (Files/createDirectories dir (into-array FileAttribute []))
        json-str (json/write-json-str record :escape-slash false)
        blob (encrypt json-str)
        dst  (record-path name)
        tmp  (.resolve dir (str name ".json.enc.tmp"))]
    (Files/write tmp blob
      ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
    (ensure-posix-600! tmp)
    (Files/move tmp dst
      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING
                                            StandardCopyOption/ATOMIC_MOVE]))
    (-> dst .toAbsolutePath .toString)))

(defn load-credential
  "Reads and decrypts the credential record for `name`. Returns the map or
   throws if the record is missing or authentication fails."
  [^String name]
  (when-not (safe-name? name)
    (throw (ex-info (str "Invalid credential name: " name) {:name name})))
  (let [path (record-path name)
        f    (.toFile path)]
    (when-not (.isFile f)
      (throw (ex-info (str "Credential not found: " name)
               {:name name :path (str path)})))
    (let [blob (Files/readAllBytes path)]
      (json/read-json (decrypt blob) :key-fn keyword))))

(defn list-credentials
  "Lists stored credentials. Returns a vector of public-safe maps with `:name
   :url :username` — **never** the password."
  []
  (let [dir (vault-dir)]
    (if-not (.isDirectory (.toFile dir))
      []
      (->> (.listFiles (.toFile dir))
        (filter (fn [^File f]
                  (and (.isFile f)
                    (str/ends-with? (.getName f) ".json.enc"))))
        (mapv (fn [^File f]
                (let [name (str/replace (.getName f) #"\.json\.enc$" "")]
                  (try
                    (let [rec (load-credential name)]
                      {:name (:name rec)
                       :url  (:url rec)
                       :username (:username rec)})
                    (catch Exception e
                      {:name name :error (.getMessage e)})))))
        (sort-by :name)
        vec))))

(defn delete-credential!
  "Removes a credential from the vault. Returns true on success, false if
   nothing was deleted."
  [^String name]
  (when-not (safe-name? name)
    (throw (ex-info (str "Invalid credential name: " name) {:name name})))
  (let [path (record-path name)]
    (Files/deleteIfExists path)))
