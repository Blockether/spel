(ns com.blockether.spel.chrome-cookies
  "Extracts and decrypts cookies from a Chromium-based browser's SQLite database.

   Supports Chrome, Edge, Brave, Vivaldi, Opera, Arc, and Chromium.
   Chrome 136+ (March 2025) intentionally broke all automation approaches that
   rely on Chrome's own cookie access from subprocesses. The only proven solution
   is to decrypt cookies ourselves from the OS credential store and inject them
   into a Playwright BrowserContext via `.addCookies`.

   Supports macOS, Linux, and Windows:

   macOS:
   1. Read Safe Storage password from macOS Keychain (browser-specific entry)
   2. Derive AES-128 key via PBKDF2(password, \"saltysalt\", 1003 iterations)
   3. Decrypt cookies with AES-CBC

   Linux:
   1. Read password from GNOME Keyring (secret-tool) or fall back to \"peanuts\"
   2. Derive AES-128 key via PBKDF2(password, \"saltysalt\", 1 iteration)
   3. Decrypt cookies with AES-CBC (v10 uses \"peanuts\", v11 uses keyring key)

   Windows:
   1. Read encrypted_key from Local State JSON
   2. Base64-decode, strip DPAPI prefix, decrypt via PowerShell DPAPI
   3. Decrypt cookies with AES-256-GCM (nonce from cookie, 128-bit auth tag)

   All platforms:
   - Copy the Cookies SQLite file (browser locks it)
   - Query all cookies via `sqlite3` CLI
   - Map to Playwright Cookie objects

   References:
   - chrome-cookies-secure (Node.js, 172★)
   - yt-dlp/cookies.py
   - browser_cookie3
   - Chromium os_crypt: os_crypt_mac.mm, os_crypt_linux.cc, os_crypt_win.cc"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.leveldb :as leveldb])
  (:import
   [com.microsoft.playwright BrowserContext]
   [com.microsoft.playwright.options Cookie SameSiteAttribute]
   [java.nio.file Files Paths StandardCopyOption]
   [java.util Arrays Base64]
   [javax.crypto Cipher SecretKeyFactory]
   [javax.crypto.spec GCMParameterSpec IvParameterSpec PBEKeySpec SecretKeySpec]))

;; =============================================================================
;; Browser Detection
;; =============================================================================

(def ^:private keychain-config
  "macOS Keychain service and account names for each Chromium browser."
  {:chrome   {:service "Chrome Safe Storage"           :account "Chrome"}
   :chromium {:service "Chromium Safe Storage"          :account "Chromium"}
   :edge     {:service "Microsoft Edge Safe Storage"    :account "Microsoft Edge"}
   :brave    {:service "Brave Safe Storage"             :account "Brave"}
   :vivaldi  {:service "Vivaldi Safe Storage"           :account "Vivaldi"}
   :opera    {:service "Opera Safe Storage"             :account "Opera"}
   :arc      {:service "Arc Safe Storage"               :account "Arc"}})

(def ^:private linux-keyring-app
  "Linux keyring application names for each Chromium browser."
  {:chrome   "chrome"
   :chromium "chromium"
   :edge     "chromium"  ;; Edge on Linux uses \"chromium\" as the app name
   :brave    "brave"
   :vivaldi  "vivaldi"
   :opera    "opera"
   :arc      "arc"})

(def ^:private channel->browser
  "Maps CLI --channel values to browser keywords."
  {"msedge"   :edge
   "chrome"   :chrome
   "chromium" :chromium
   "chrome-beta" :chrome
   "chrome-dev"  :chrome
   "chrome-canary" :chrome
   "msedge-beta"  :edge
   "msedge-dev"   :edge
   "msedge-canary" :edge})

(defn- detect-browser
  "Detects the Chromium browser variant from a profile directory path and/or
   CLI channel hint. Channel hint takes priority when provided.
   Returns one of :chrome, :edge, :brave, :vivaldi, :opera, :arc, or :chromium.
   Falls back to :chrome if the browser cannot be determined."
  [^String profile-dir channel]
  (or
    ;; 1. Channel hint from CLI (--channel msedge, etc.)
    (when channel (get channel->browser (str/lower-case (str channel))))
    ;; 2. Detect from profile path
    (when profile-dir
      (let [path (str/lower-case profile-dir)]
        (cond
          (str/includes? path "microsoft edge")  :edge
          (str/includes? path "bravesoftware")   :brave
          (str/includes? path "brave-browser")   :brave
          (str/includes? path "vivaldi")          :vivaldi
          (str/includes? path "opera")            :opera
          (str/includes? path "/arc/")            :arc
          (str/includes? path "chromium")         :chromium
          :else nil)))
    ;; 3. Default
    :chrome))

;; =============================================================================
;; OS Detection
;; =============================================================================

(defn- detect-os
  "Detects the current operating system.
   Returns :macos, :linux, or :windows."
  []
  (let [os (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (str/includes? os "mac")   :macos
      (str/includes? os "linux") :linux
      (str/includes? os "win")   :windows
      :else (throw (ex-info (str "Unsupported OS for cookie decryption: " os)
                     {:os os})))))

;; =============================================================================
;; Subprocess Helper
;; =============================================================================

(defn- run-process
  "Runs a subprocess and returns {:exit int :stdout String :stderr String}.
   Does not throw on non-zero exit."
  [& args]
  (let [proc (.start (ProcessBuilder.
                       ^"[Ljava.lang.String;" (into-array String args)))
        stdout (slurp (.getInputStream proc))
        stderr (slurp (.getErrorStream proc))
        exit   (.waitFor proc)]
    {:exit exit :stdout stdout :stderr stderr}))

;; =============================================================================
;; Password Retrieval — macOS
;; =============================================================================

(defn- keychain-password
  "Retrieves Safe Storage password from macOS Keychain for the given browser.
   `browser` is a keyword from detect-browser (:chrome, :edge, etc.).
   Returns the password string. Throws on failure."
  ^String [browser]
  (let [{:keys [service account]} (get keychain-config browser (get keychain-config :chrome))
        {:keys [exit stdout]} (run-process
                                "security" "find-generic-password" "-w"
                                "-s" service "-a" account)
        password (str/trim stdout)]
    (when-not (zero? (long exit))
      (throw (ex-info (str "Failed to read " service " from Keychain. Is " account " installed?")
               {:exit-code exit :browser browser})))
    (when (str/blank? password)
      (throw (ex-info (str service " password is blank. Keychain access may have been denied.")
               {:browser browser})))
    password))

;; =============================================================================
;; Password Retrieval — Linux
;; =============================================================================

(defn- linux-keyring-password
  "Retrieves Safe Storage password from Linux keyring for the given browser.
   `browser` is a keyword from detect-browser (:chrome, :edge, etc.).
   Tries GNOME Keyring (secret-tool) with v2 and v1 schemas, then falls back
   to the hardcoded password \"peanuts\" used when no keyring is available.
   Returns the password string."
  ^String [browser]
  (let [app (get linux-keyring-app browser "chrome")
        try-secret-tool (fn [^String schema]
                          (let [{:keys [exit stdout]}
                                (run-process
                                  "secret-tool" "lookup"
                                  "xdg:schema" schema
                                  "application" app)
                                pw (str/trim stdout)]
                            (when (and (zero? (long exit)) (not (str/blank? pw)))
                              pw)))]
    (or (try-secret-tool "chrome_libsecret_os_crypt_password_v2")
      (try-secret-tool "chrome_libsecret_os_crypt_password_v1")
      ;; Hardcoded fallback — Chromium uses \"peanuts\" when no keyring backend
      ;; is available (e.g. headless servers, basic text password store).
      ;; See: Chromium os_crypt_linux.cc, browser_cookie3, yt-dlp
      "peanuts")))

;; =============================================================================
;; Password Retrieval — Dispatch
;; =============================================================================

(defn- get-encryption-password
  "Gets the browser encryption password for the current OS.
   `browser` is a keyword from detect-browser (:chrome, :edge, etc.).
   macOS → Keychain, Linux → secret-tool/peanuts, Windows → nil (uses DPAPI)."
  ^String [os browser]
  (case os
    :macos   (keychain-password browser)
    :linux   (linux-keyring-password browser)
    :windows nil))

;; =============================================================================
;; Key Derivation — PBKDF2 (macOS + Linux)
;; =============================================================================

(defn- derive-key
  "Derives an AES-128 key from a password using PBKDF2-SHA1.
   Chrome uses: salt=\"saltysalt\", key-length=128 bits.
   Iterations differ per platform: macOS=1003, Linux=1."
  ^SecretKeySpec [^String password ^long iterations]
  (let [spec    (PBEKeySpec. (.toCharArray password) (.getBytes "saltysalt") (int iterations) 128)
        factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA1")]
    (SecretKeySpec. (.getEncoded (.generateSecret factory spec)) "AES")))

;; =============================================================================
;; Key Retrieval — Windows (DPAPI + Local State JSON)
;; =============================================================================

(defn- read-local-state-key
  "Reads the encrypted_key from Chrome's Local State JSON file.
   The Local State file lives in the parent of the profile directory
   (the User Data directory). Returns the raw base64 string or nil."
  ^String [^String profile-dir]
  (let [user-data-dir (.getParent (java.io.File. profile-dir))
        local-state   (java.io.File. ^String user-data-dir "Local State")]
    (when (.exists local-state)
      (let [parsed (json/read-json (slurp (.getPath local-state)))]
        (get-in parsed ["os_crypt" "encrypted_key"])))))

(defn- windows-dpapi-decrypt-key
  "Retrieves the AES-256-GCM key for Windows Chrome cookie decryption.

   1. Read encrypted_key from Local State JSON
   2. Base64-decode it
   3. Strip the 5-byte 'DPAPI' prefix
   4. Decrypt via PowerShell using .NET ProtectedData (DPAPI)
   5. Base64-decode the result to get the raw 32-byte AES key

   Returns a SecretKeySpec for AES-256-GCM."
  ^SecretKeySpec [^String profile-dir]
  (let [b64-key (read-local-state-key profile-dir)]
    (when (str/blank? b64-key)
      (throw (ex-info "No encrypted_key found in Chrome Local State. Is Chrome installed?"
               {:profile-dir profile-dir})))
    (let [encrypted-key (.decode (Base64/getDecoder) ^String b64-key)
          ;; Strip "DPAPI" prefix (5 ASCII bytes: 0x44 0x50 0x41 0x50 0x49)
          dpapi-prefix  "DPAPI"
          _             (when-not (and (>= (alength encrypted-key) 5)
                                    (= dpapi-prefix
                                      (String. ^bytes (Arrays/copyOfRange encrypted-key 0 5) "ASCII")))
                          (throw (ex-info "encrypted_key does not have DPAPI prefix"
                                   {:profile-dir profile-dir})))
          dpapi-blob    (Arrays/copyOfRange encrypted-key 5 (alength encrypted-key))
          ;; Base64-encode the DPAPI blob for PowerShell transport
          dpapi-b64     (.encodeToString (Base64/getEncoder) dpapi-blob)
          ps-cmd        (str "Add-Type -AssemblyName System.Security;"
                          "[Convert]::ToBase64String("
                          "[System.Security.Cryptography.ProtectedData]::Unprotect("
                          "[Convert]::FromBase64String('" dpapi-b64 "'),"
                          "$null,"
                          "[System.Security.Cryptography.DataProtectionScope]::CurrentUser))")
          {:keys [exit stdout stderr]}
          (run-process "powershell" "-NoProfile" "-NonInteractive" "-Command" ps-cmd)
          result-b64 (str/trim stdout)]
      (when-not (zero? (long exit))
        (throw (ex-info (str "PowerShell DPAPI decryption failed: " (str/trim stderr))
                 {:exit-code exit :profile-dir profile-dir})))
      (when (str/blank? result-b64)
        (throw (ex-info "DPAPI decryption returned empty result"
                 {:profile-dir profile-dir})))
      (let [aes-key (.decode (Base64/getDecoder) ^String result-b64)]
        (SecretKeySpec. aes-key "AES")))))

;; =============================================================================
;; Cookie Decryption — AES-CBC (macOS + Linux)
;; =============================================================================

(def ^:private ^IvParameterSpec cbc-iv
  "Chrome's fixed AES-CBC IV: 16 bytes of 0x20 (space character)."
  (IvParameterSpec. (byte-array 16 (byte 0x20))))

(defn- decrypt-cookie-value
  "Decrypts a single Chrome cookie's encrypted_value bytes using AES-CBC.
   Used on macOS and Linux.
   - Strips the 'v10' or 'v11' prefix (3 bytes)
   - Decrypts with AES-128-CBC (PKCS5Padding)
   - Skips the first `skip-bytes` of decrypted output (hash prefix)
   Returns the plaintext string, or nil if decryption fails."
  ^String [^SecretKeySpec secret-key ^bytes enc-bytes ^long skip-bytes]
  (when (and enc-bytes (>= (alength enc-bytes) 4))
    ;; Check for v10 or v11 prefix: 0x76='v' 0x31='1' 0x30='0'/0x31='1'
    (when (and (= (aget enc-bytes 0) (byte 0x76))
            (= (aget enc-bytes 1) (byte 0x31))
            (or (= (aget enc-bytes 2) (byte 0x30))
              (= (aget enc-bytes 2) (byte 0x31))))
      (try
        (let [ciphertext (Arrays/copyOfRange enc-bytes 3 (alength enc-bytes))
              cipher     (Cipher/getInstance "AES/CBC/PKCS5Padding")
              _          (.init cipher Cipher/DECRYPT_MODE secret-key cbc-iv)
              decrypted  (.doFinal cipher ciphertext)]
          (if (>= (alength decrypted) skip-bytes)
            (String. (Arrays/copyOfRange decrypted (int skip-bytes) (alength decrypted)) "UTF-8")
            (String. decrypted "UTF-8")))
        (catch Exception _
          nil)))))

;; =============================================================================
;; Cookie Decryption — AES-GCM (Windows)
;; =============================================================================

(defn- decrypt-cookie-value-gcm
  "Decrypts a single Chrome cookie's encrypted_value bytes using AES-256-GCM.
   Used on Windows (Chrome v80+).
   Layout: v10(3 bytes) + nonce(12 bytes) + ciphertext + auth_tag(16 bytes).
   - Strips the 'v10' prefix (3 bytes)
   - Extracts 12-byte nonce
   - Decrypts remainder with AES/GCM/NoPadding (128-bit auth tag)
   - Skips the first `skip-bytes` of decrypted output (hash prefix)
   Returns the plaintext string, or nil if decryption fails."
  ^String [^SecretKeySpec secret-key ^bytes enc-bytes ^long skip-bytes]
  ;; Minimum: 3 (v10) + 12 (nonce) + 16 (tag) = 31 bytes
  (when (and enc-bytes (>= (alength enc-bytes) 31))
    ;; Check for v10 prefix
    (when (and (= (aget enc-bytes 0) (byte 0x76))
            (= (aget enc-bytes 1) (byte 0x31))
            (= (aget enc-bytes 2) (byte 0x30)))
      (try
        (let [nonce      (Arrays/copyOfRange enc-bytes 3 15)
              ciphertext (Arrays/copyOfRange enc-bytes 15 (alength enc-bytes))
              spec       (GCMParameterSpec. 128 nonce)
              cipher     (Cipher/getInstance "AES/GCM/NoPadding")]
          (.init cipher Cipher/DECRYPT_MODE secret-key spec)
          (let [decrypted (.doFinal cipher ciphertext)]
            (if (>= (alength decrypted) skip-bytes)
              (String. (Arrays/copyOfRange decrypted (int skip-bytes) (alength decrypted)) "UTF-8")
              (String. decrypted "UTF-8"))))
        (catch Exception _
          nil)))))

;; =============================================================================
;; SQLite Query
;; =============================================================================

(defn- copy-cookies-db!
  "Copies the Chrome Cookies SQLite file to a temp location.
   Chrome holds a lock on the original, so we must copy first.
   Returns the path to the temporary copy."
  ^String [^String profile-dir]
  (let [source  (Paths/get (str profile-dir "/Cookies") (into-array String []))
        tmp-db  (str (System/getProperty "java.io.tmpdir")
                  java.io.File/separator
                  "spel-cookies-" (System/currentTimeMillis) ".db")]
    (when-not (Files/exists source (into-array java.nio.file.LinkOption []))
      (throw (ex-info (str "Chrome Cookies database not found at: " source)
               {:profile-dir profile-dir})))
    (Files/copy ^java.nio.file.Path source
      (Paths/get tmp-db (into-array String []))
      ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    tmp-db))

(defn- query-meta-version
  "Queries the Chrome Cookies DB meta version.
   Version >= 24 uses a 32-byte SHA256 hash prefix in encrypted values.
   Version < 24 uses a 16-byte garbled CBC block prefix."
  [^String db-path]
  (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["sqlite3" db-path
                                                                                 "SELECT value FROM meta WHERE key='version'"])))
        output (str/trim (slurp (.getInputStream proc)))
        _      (.waitFor proc)]
    (if (str/blank? output)
      0
      (Long/parseLong output))))

(defn- hex->bytes
  "Converts a hex string to a byte array."
  ^bytes [^String hex]
  (let [len (/ (count hex) 2)
        ba  (byte-array len)]
    (dotimes [i len]
      (aset-byte ba i (unchecked-byte (Integer/parseInt (subs hex (* i 2) (+ (* i 2) 2)) 16))))
    ba))

(defn- query-cookies-raw
  "Queries all cookies from the SQLite database using the sqlite3 CLI.
   Returns a seq of maps with raw string fields.
   Uses hex encoding for encrypted_value to avoid binary transport issues."
  [^String db-path]
  (let [sql (str "SELECT host_key, name, hex(encrypted_value), path, "
              "expires_utc, is_httponly, is_secure, samesite, has_expires "
              "FROM cookies")
        proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["sqlite3" "-separator" "\t" db-path sql])))
        output (slurp (.getInputStream proc))
        err-output (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info (str "sqlite3 query failed: " (str/trim err-output))
               {:exit-code exit :db-path db-path})))
    (when-not (str/blank? output)
      (for [line (str/split-lines output)
            :when (not (str/blank? line))]
        (let [parts (str/split line #"\t" -1)]
          (when (>= (count parts) 9)
            {:host-key        (nth parts 0)
             :name            (nth parts 1)
             :encrypted-hex   (nth parts 2)
             :path            (nth parts 3)
             :expires-utc     (nth parts 4)
             :is-httponly     (nth parts 5)
             :is-secure      (nth parts 6)
             :samesite        (nth parts 7)
             :has-expires    (nth parts 8)}))))))

;; =============================================================================
;; Chrome → Playwright Cookie Mapping
;; =============================================================================

(def ^:private chrome-epoch-offset
  "Microseconds between 1601-01-01 and 1970-01-01.
   Chrome stores timestamps as microseconds since 1601-01-01."
  (* 11644473600 1000000))

(defn- chrome-expires->unix
  "Converts Chrome's expires_utc (microseconds since 1601) to Unix epoch seconds.
   Returns -1.0 for session cookies (expires_utc=0 and has_expires=0)."
  [^String expires-utc ^String has-expires]
  (let [exp (Long/parseLong expires-utc)]
    (if (and (zero? exp) (= "0" has-expires))
      -1.0
      (/ (double (- exp (long chrome-epoch-offset))) 1000000.0))))

(defn- chrome-samesite->playwright
  "Maps Chrome's samesite integer to Playwright's SameSiteAttribute enum.
   -1,0 → NONE, 1 → LAX, 2 → STRICT"
  ^SameSiteAttribute [^String samesite]
  (case samesite
    ("-1" "0") SameSiteAttribute/NONE
    "1"        SameSiteAttribute/LAX
    "2"        SameSiteAttribute/STRICT
    SameSiteAttribute/NONE))

(defn- raw-cookie->playwright
  "Converts a decrypted raw cookie map to a Playwright Cookie object.
   Returns nil if decryption failed (value is nil)."
  ^Cookie [{:keys [host-key name path expires-utc is-httponly is-secure samesite has-expires]}
           ^String value]
  (when (and value (not (str/blank? value)))
    (let [cookie (Cookie. name value)]
      ;; Domain: Chrome stores ".example.org" — Playwright expects the same
      (.setDomain cookie host-key)
      (.setPath cookie (if (str/blank? path) "/" path))
      (.setExpires cookie (chrome-expires->unix expires-utc has-expires))
      (.setHttpOnly cookie (= "1" is-httponly))
      (.setSecure cookie (= "1" is-secure))
      (.setSameSite cookie (chrome-samesite->playwright samesite))
      cookie)))

;; =============================================================================
;; localStorage Reading
;; =============================================================================

(def ^:private ^:const ls-record-prefix (byte 0x5f)) ;; '_'

(defn- decode-ls-string
  "Decodes a type-prefixed localStorage string.
   Chrome encodes localStorage keys/values with a 1-byte type prefix:
   0x00 = rest is UTF-16-LE, 0x01 = rest is ISO-8859-1 (Latin-1)."
  ^String [^bytes raw]
  (when (and raw (pos? (alength raw)))
    (let [prefix (bit-and (long (aget raw 0)) 0xFF)]
      (cond
        (zero? prefix) (String. raw 1 (- (alength raw) 1) "UTF-16LE")
        (= prefix 1)   (String. raw 1 (- (alength raw) 1) "ISO-8859-1")
        :else           (String. raw 1 (- (alength raw) 1) "UTF-8")))))

(defn- parse-ls-record-key
  "Parses a localStorage record key into [origin script-key].
   Key format: '_' + origin + '\\x00' + type-prefixed-script-key
   Returns nil if the key doesn't match the expected format."
  [^bytes key-data]
  (when (and key-data (> (alength key-data) 2)
          (= (aget key-data 0) ls-record-prefix))
    ;; Find the \x00 separator
    (let [len (alength key-data)]
      (loop [i 1]
        (if (>= i len)
          nil ;; No separator found
          (if (zero? (aget key-data i))
            ;; Found separator at position i
            (let [origin (String. key-data 1 (- i 1) "ISO-8859-1")
                  script-key-raw (Arrays/copyOfRange key-data (inc i) len)
                  script-key (decode-ls-string script-key-raw)]
              (when script-key
                [origin script-key]))
            (recur (inc i))))))))

(defn read-local-storage
  "Reads localStorage data from a Chrome profile directory.

   Parses the LevelDB database at `<profile-dir>/Local Storage/leveldb/`
   and returns a map of origin to localStorage key-value pairs:

   {\"https://x.com\" {\"token\" \"abc123\", \"theme\" \"dark\"}}

   Only returns the LATEST live value for each key (highest sequence number
   wins). Deleted keys are excluded.

   Params:
   `profile-dir` - String. Path to Chrome profile directory.

   Returns:
   Map of origin → {script-key → value}, or empty map if dir doesn't exist."
  [^String profile-dir]
  (let [ls-dir (str profile-dir
                 (if (.contains profile-dir "\\") "\\" "/")
                 "Local Storage"
                 (if (.contains profile-dir "\\") "\\" "/")
                 "leveldb")]
    (if-not (.isDirectory (java.io.File. ls-dir))
      {}
      (let [records (leveldb/read-records ls-dir)
            ;; Group by [origin key] → keep record with highest seq
            best (reduce
                   (fn [acc rec]
                     (if-let [[origin script-key] (parse-ls-record-key (:key rec))]
                       (let [compound-key [origin script-key]
                             existing (get acc compound-key)]
                         (if (or (nil? existing)
                               (> (long (:seq rec)) (long (:seq existing))))
                           (assoc! acc compound-key rec)
                           acc))
                       ;; parse failed (META: key or invalid) → return acc unchanged
                       acc))
                   (transient {})
                   records)
            final (persistent! best)]
        ;; Build origin → {key → value} map, only live records
        (reduce-kv
          (fn [result [origin script-key] rec]
            (if (= :live (:state rec))
              (let [value (decode-ls-string (:value rec))]
                (assoc-in result [origin script-key] (or value "")))
              result))
          {}
          final)))))

;; =============================================================================
;; Cookie Serialization
;; =============================================================================

(defn- cookie->map
  "Converts a Playwright Cookie object to a plain map for JSON serialization.
   Uses Playwright's storage-state field names (camelCase)."
  [^Cookie cookie]
  {"name"     (.name cookie)
   "value"    (.value cookie)
   "domain"   (.domain cookie)
   "path"     (.path cookie)
   "expires"  (.expires cookie)
   "httpOnly" (.httpOnly cookie)
   "secure"   (.secure cookie)
   "sameSite" (condp = (.sameSite cookie)
                SameSiteAttribute/LAX    "Lax"
                SameSiteAttribute/STRICT "Strict"
                "None")})

;; =============================================================================
;; Public API
;; =============================================================================

(defn extract-cookies
  "Extracts and decrypts all cookies from a Chromium browser profile directory.

   Cross-platform: works on macOS (Keychain), Linux (GNOME Keyring/peanuts),
   and Windows (DPAPI + AES-GCM). Supports Chrome, Edge, Brave, Vivaldi,
   Opera, Arc, and Chromium.

   Params:
   `profile-dir` - String. Path to browser profile directory
                    (e.g. \"~/Library/Application Support/Google/Chrome/Profile 1\"
                     or \"~/Library/Application Support/Microsoft Edge/Default\")
   `opts`        - Optional map with:
                    :channel - String. CLI channel hint (e.g. \"msedge\", \"chrome\").
                               Used to detect the browser if not obvious from path.

   Returns:
   java.util.List<Cookie> — Playwright Cookie objects with decrypted values.

   Throws:
   ex-info on credential access failure, missing Cookies DB, or sqlite3 errors."
  ([^String profile-dir]
   (extract-cookies profile-dir {}))
  ([^String profile-dir opts]
   (let [browser    (detect-browser profile-dir (:channel opts))
         os         (detect-os)
         ;; Copy DB first — fail fast if profile doesn't exist
         tmp-db      (copy-cookies-db! profile-dir)
         ;; Platform-specific key derivation (may prompt Keychain)
         secret-key (if (= os :windows)
                      (windows-dpapi-decrypt-key profile-dir)
                      (let [password   (get-encryption-password os browser)
                            iterations (if (= os :macos) 1003 1)]
                        (derive-key password iterations)))
         ;; Platform-specific decryption function
         decrypt-fn (if (= os :windows)
                      decrypt-cookie-value-gcm
                      decrypt-cookie-value)
         meta-ver    (query-meta-version tmp-db)
         skip-bytes  (if (>= (long meta-ver) 24) 32 16)
         raw-cookies (query-cookies-raw tmp-db)]
     ;; Clean up temp DB
     (try (.delete (java.io.File. tmp-db)) (catch Exception _))
     (let [cookies (->> raw-cookies
                     (keep (fn [raw]
                             (when raw
                               (let [enc-bytes (hex->bytes (:encrypted-hex raw))
                                     value     (decrypt-fn secret-key enc-bytes skip-bytes)]
                                 (raw-cookie->playwright raw value)))))
                     vec)]
       (java.util.ArrayList. ^java.util.Collection cookies)))))

(defn inject-cookies!
  "Extracts cookies from a Chromium browser profile and injects them into a
   Playwright BrowserContext. Supports Chrome, Edge, Brave, and other Chromium
   browsers. Resilient: if batch injection fails, falls back to injecting cookies
   one-by-one and skips any that CDP rejects (e.g. invalid fields, expired).

   Params:
   `context`     - BrowserContext instance.
   `profile-dir` - String. Path to browser profile directory.
   `opts`        - Optional map with:
                    :channel - String. CLI channel hint (e.g. \"msedge\")."
  ([^BrowserContext context ^String profile-dir]
   (inject-cookies! context profile-dir {}))
  ([^BrowserContext context ^String profile-dir opts]
   (let [^java.util.ArrayList cookies (extract-cookies profile-dir opts)
         n (long (.size cookies))]
     (if (zero? n)
       0
       (try
         (.addCookies context cookies)
         n
         (catch Exception _batch-err
           ;; Batch injection failed — try one-by-one to skip invalid cookies
           (let [injected (atom 0)
                 skipped  (atom 0)]
             (doseq [^Cookie c cookies]
               (try
                 (let [^java.util.Collection coll [c]
                       ^java.util.List single (java.util.ArrayList. coll)]
                   (.addCookies context single))
                 (swap! injected inc)
                 (catch Exception e
                   (swap! skipped inc)
                   (binding [*out* *err*]
                     (println (str "spel: skipped cookie " (.name c)
                                " for " (.domain c) ": " (.getMessage e)))))))
             (binding [*out* *err*]
               (println (str "spel: injected " @injected "/" n " cookies"
                          (when (pos? (long @skipped))
                            (str " (" @skipped " skipped due to invalid fields)")))))
             @injected)))))))

(defn- navigable-origin?
  "Returns true when `origin` is a valid HTTP(S) URL that Playwright can navigate to.
   Filters out non-HTTP schemes (devtools://, chrome://) and Chrome's internal
   partition-key origins that contain ^0 separators."
  [^String origin]
  (and (string? origin)
    (or (str/starts-with? origin "http://")
      (str/starts-with? origin "https://"))
    (not (str/includes? origin "\u0000"))  ;; null bytes
    (not (str/includes? origin "^0"))))    ;; Chrome partition key separator

(defn- ls-map->origins
  "Converts a localStorage map {origin → {key → val}} to Playwright's origins format:
   [{\"origin\" \"https://x.com\" \"localStorage\" [{\"name\" \"k\" \"value\" \"v\"}]}]
   Non-navigable origins (non-HTTP, partition keys with ^0) are filtered out."
  [ls-map domain-filter]
  (let [entries (cond->> ls-map
                  true (filter (fn [[origin _]] (navigable-origin? origin)))
                  (and domain-filter (not (str/blank? domain-filter)))
                  (filter (fn [[origin _]] (str/includes? origin domain-filter))))]
    (mapv (fn [[origin kv-map]]
            {"origin"       origin
             "localStorage" (mapv (fn [[k v]] {"name" k "value" v}) kv-map)})
      entries)))

(defn export-cookies-json
  "Decrypts cookies and reads localStorage from a Chromium browser profile,
   returning a Playwright-compatible storage-state JSON string. Supports Chrome,
   Edge, Brave, and other Chromium browsers. The output can be saved to a file
   and loaded with `--storage-state` on any platform.

   Params:
   `profile-dir`            - String. Path to browser profile directory.
   `domain-filter`          - String or nil. Only include cookies/localStorage
                               whose domain/origin contains this string.
   `include-local-storage?` - Boolean (default true). When true, also exports
                               localStorage from the profile's LevelDB.
   `opts`                   - Optional map with:
                               :channel - String. CLI channel hint (e.g. \"msedge\").

   Returns:
   String — Playwright storage-state JSON:
   {\"cookies\": [...], \"origins\": [{\"origin\": \"...\", \"localStorage\": [...]}]}"
  (^String [^String profile-dir ^String domain-filter]
   (export-cookies-json profile-dir domain-filter true {}))
  (^String [^String profile-dir ^String domain-filter include-local-storage?]
   (export-cookies-json profile-dir domain-filter include-local-storage? {}))
  (^String [^String profile-dir ^String domain-filter include-local-storage? opts]
   (let [^java.util.ArrayList cookies (extract-cookies profile-dir opts)
         cookie-maps (mapv cookie->map cookies)
         filtered-cookies (if (and domain-filter (not (str/blank? domain-filter)))
                            (filterv #(str/includes? (get % "domain") domain-filter) cookie-maps)
                            cookie-maps)
         origins (if include-local-storage?
                   (try
                     (let [ls-map (read-local-storage profile-dir)]
                       (ls-map->origins ls-map domain-filter))
                     (catch Exception e
                       (binding [*out* *err*]
                         (println (str "Warning: could not read localStorage: " (.getMessage e))))
                       []))
                   [])]
     (json/write-json-str {"cookies" filtered-cookies "origins" origins}))))
