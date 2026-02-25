(ns com.blockether.spel.chrome-cookies-test
  "Tests for the chrome-cookies namespace.

   Unit tests for key derivation, cookie decryption, Chrome epoch conversion,
   SameSite mapping, hex-to-bytes conversion, AES-GCM decryption (Windows),
   OS detection, and Local State JSON parsing. Tests the internal functions
   via private var access — no Keychain, DPAPI, or SQLite access required.

   Integration test for extract-cookies requires a real Chrome profile
   and is gated on macOS + Chrome being installed."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.chrome-cookies :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright.options Cookie SameSiteAttribute]
   [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

;; =============================================================================
;; Unit Tests — hex->bytes
;; =============================================================================

(defdescribe hex->bytes-test
  "Unit tests for hex->bytes — converts hex strings to byte arrays"

  (describe "basic conversions"
    (it "converts empty hex to empty array"
      (let [result (#'sut/hex->bytes "")]
        (expect (zero? (alength result)))))

    (it "converts single byte"
      (let [result (#'sut/hex->bytes "FF")]
        (expect (= 1 (alength result)))
        (expect (= -1 (aget result 0)))))

    (it "converts v10 prefix hex"
      (let [result (#'sut/hex->bytes "763130")]
        (expect (= 3 (alength result)))
        ;; v=0x76, 1=0x31, 0=0x30
        (expect (= 0x76 (bit-and (aget result 0) 0xff)))
        (expect (= 0x31 (bit-and (aget result 1) 0xff)))
        (expect (= 0x30 (bit-and (aget result 2) 0xff)))))

    (it "converts longer hex string"
      (let [result (#'sut/hex->bytes "DEADBEEF")]
        (expect (= 4 (alength result)))
        (expect (= 0xDE (bit-and (aget result 0) 0xff)))
        (expect (= 0xAD (bit-and (aget result 1) 0xff)))
        (expect (= 0xBE (bit-and (aget result 2) 0xff)))
        (expect (= 0xEF (bit-and (aget result 3) 0xff)))))))

;; =============================================================================
;; Unit Tests — derive-key
;; =============================================================================

(defdescribe derive-key-test
  "Unit tests for derive-key — PBKDF2 key derivation"

  (describe "derives AES key from password (macOS iterations)"
    (it "returns a SecretKeySpec with AES algorithm"
      (let [key (#'sut/derive-key "test-password" 1003)]
        (expect (instance? SecretKeySpec key))
        (expect (= "AES" (.getAlgorithm ^SecretKeySpec key)))))

    (it "produces 16-byte (128-bit) key"
      (let [key (#'sut/derive-key "test-password" 1003)]
        (expect (= 16 (alength (.getEncoded ^SecretKeySpec key))))))

    (it "is deterministic — same password gives same key"
      (let [k1 (#'sut/derive-key "same-password" 1003)
            k2 (#'sut/derive-key "same-password" 1003)]
        (expect (java.util.Arrays/equals
                  (.getEncoded ^SecretKeySpec k1)
                  (.getEncoded ^SecretKeySpec k2)))))

    (it "different passwords give different keys"
      (let [k1 (#'sut/derive-key "password-one" 1003)
            k2 (#'sut/derive-key "password-two" 1003)]
        (expect (not (java.util.Arrays/equals
                       (.getEncoded ^SecretKeySpec k1)
                       (.getEncoded ^SecretKeySpec k2))))))))

;; =============================================================================
;; Unit Tests — decrypt-cookie-value
;; =============================================================================

(defdescribe decrypt-cookie-value-test
  "Unit tests for decrypt-cookie-value — AES-CBC decryption"

  (describe "handles invalid input"
    (it "returns nil for nil bytes"
      (expect (nil? (#'sut/decrypt-cookie-value
                     (#'sut/derive-key "test" 1003) nil 0))))

    (it "returns nil for short bytes (< 4)"
      (expect (nil? (#'sut/decrypt-cookie-value
                     (#'sut/derive-key "test" 1003) (byte-array [1 2 3]) 0))))

    (it "returns nil for non-v10 prefix"
      (expect (nil? (#'sut/decrypt-cookie-value
                     (#'sut/derive-key "test" 1003) (byte-array [0x00 0x00 0x00 0x00 0x00]) 0)))))

  (describe "round-trip encrypt → decrypt"
    (it "decrypts a value encrypted with the same key"
      ;; Create a known plaintext, encrypt it, then verify decryption
      (let [password   "test-password"
            secret-key (#'sut/derive-key password 1003)
            plaintext  "hello-world-cookie-value"
            ;; Encrypt: add 32 zero bytes (simulating hash prefix) + plaintext
            to-encrypt (byte-array (concat (repeat 32 (byte 0))
                                     (.getBytes plaintext "UTF-8")))
            ;; Create IV matching Chrome's: 16 bytes of 0x20
            iv         (javax.crypto.spec.IvParameterSpec. (byte-array 16 (byte 0x20)))
            cipher     (javax.crypto.Cipher/getInstance "AES/CBC/PKCS5Padding")
            _          (.init cipher (int javax.crypto.Cipher/ENCRYPT_MODE) secret-key iv)
            ciphertext (.doFinal cipher to-encrypt)
            ;; Add v10 prefix
            enc-bytes  (byte-array (concat [0x76 0x31 0x30] (seq ciphertext)))
            ;; Decrypt with skip-bytes=32
            result     (#'sut/decrypt-cookie-value secret-key enc-bytes 32)]
        (expect (= plaintext result))))))

;; =============================================================================
;; Unit Tests — chrome-expires->unix
;; =============================================================================

(defdescribe chrome-expires->unix-test
  "Unit tests for chrome-expires->unix — Chrome epoch to Unix epoch"

  (describe "session cookies"
    (it "returns -1.0 for session cookie (expires=0, has_expires=0)"
      (expect (= -1.0 (#'sut/chrome-expires->unix "0" "0")))))

  (describe "normal cookies"
    (it "converts a known Chrome timestamp correctly"
      ;; Chrome timestamp for 2025-01-01 00:00:00 UTC
      ;; Unix: 1735689600 seconds since 1970-01-01
      ;; Chrome: (+ (* 1735689600 1000000) (* 11644473600 1000000)) = 13380163200000000
      (let [chrome-ts "13380163200000000"
            expected  1735689600.0
            result    (#'sut/chrome-expires->unix chrome-ts "1")]
        (expect (< (Math/abs (- expected result)) 1.0))))

    (it "handles expires=0 with has_expires=1 (treated as epoch start)"
      (let [result (#'sut/chrome-expires->unix "0" "1")]
        ;; 0 Chrome microseconds = 1601-01-01 → negative Unix timestamp
        (expect (< result 0.0))))))

;; =============================================================================
;; Unit Tests — chrome-samesite->playwright
;; =============================================================================

(defdescribe chrome-samesite->playwright-test
  "Unit tests for chrome-samesite->playwright — maps Chrome integers to Playwright enum"

  (describe "maps all values"
    (it "maps -1 to NONE"
      (expect (= SameSiteAttribute/NONE (#'sut/chrome-samesite->playwright "-1"))))

    (it "maps 0 to NONE"
      (expect (= SameSiteAttribute/NONE (#'sut/chrome-samesite->playwright "0"))))

    (it "maps 1 to LAX"
      (expect (= SameSiteAttribute/LAX (#'sut/chrome-samesite->playwright "1"))))

    (it "maps 2 to STRICT"
      (expect (= SameSiteAttribute/STRICT (#'sut/chrome-samesite->playwright "2"))))

    (it "defaults to NONE for unknown values"
      (expect (= SameSiteAttribute/NONE (#'sut/chrome-samesite->playwright "99"))))))

;; =============================================================================
;; Unit Tests — raw-cookie->playwright
;; =============================================================================

(defdescribe raw-cookie->playwright-test
  "Unit tests for raw-cookie->playwright — builds Playwright Cookie objects"

  (describe "builds cookie from raw data"
    (it "creates a valid Cookie object"
      (let [raw {:host-key    ".example.com"
                 :name        "session_id"
                 :path        "/"
                 :expires-utc "13380508800000000"
                 :is-httponly "1"
                 :is-secure   "1"
                 :samesite    "1"
                 :has-expires "1"}
            cookie (#'sut/raw-cookie->playwright raw "my-session-value")]
        (expect (instance? Cookie cookie))
        (expect (= "session_id" (.name cookie)))
        (expect (= "my-session-value" (.value cookie)))
        (expect (= ".example.com" (.domain cookie)))
        (expect (= "/" (.path cookie)))
        (expect (true? (.httpOnly cookie)))
        (expect (true? (.secure cookie)))
        (expect (= SameSiteAttribute/LAX (.sameSite cookie))))))

  (describe "returns nil for invalid values"
    (it "returns nil for nil value"
      (expect (nil? (#'sut/raw-cookie->playwright
                     {:host-key ".example.com" :name "test"
                      :path "/" :expires-utc "0" :is-httponly "0"
                      :is-secure "0" :samesite "0" :has-expires "0"}
                     nil))))

    (it "returns nil for blank value"
      (expect (nil? (#'sut/raw-cookie->playwright
                     {:host-key ".example.com" :name "test"
                      :path "/" :expires-utc "0" :is-httponly "0"
                      :is-secure "0" :samesite "0" :has-expires "0"}
                     ""))))))

;; =============================================================================
;; Unit Tests — copy-cookies-db!
;; =============================================================================

(defdescribe copy-cookies-db-test
  "Unit tests for copy-cookies-db! — copies Chrome Cookies SQLite file"

  (describe "throws when Cookies file missing"
    (it "throws ex-info for non-existent profile"
      (try
        (#'sut/copy-cookies-db! "/tmp/nonexistent-spel-test-profile")
        (expect false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (str/includes? (.getMessage e) "Cookies database not found")))))))

;; =============================================================================
;; Unit Tests — detect-os
;; =============================================================================

(defdescribe detect-os-test
  "Unit tests for detect-os — OS detection"

  (describe "returns a valid OS keyword"
    (it "returns one of :macos :linux :windows"
      (let [os (#'sut/detect-os)]
        (expect (contains? #{:macos :linux :windows} os))))))

;; =============================================================================
;; Unit Tests — derive-key with different iterations
;; =============================================================================

(defdescribe derive-key-iterations-test
  "Unit tests for derive-key with platform-specific iteration counts"

  (describe "respects iterations parameter"
    (it "produces 16-byte key with 1 iteration (Linux)"
      (let [key (#'sut/derive-key "peanuts" 1)]
        (expect (= 16 (alength (.getEncoded ^SecretKeySpec key))))))

    (it "produces 16-byte key with 1003 iterations (macOS)"
      (let [key (#'sut/derive-key "test-password" 1003)]
        (expect (= 16 (alength (.getEncoded ^SecretKeySpec key))))))

    (it "different iterations produce different keys"
      (let [k1 (#'sut/derive-key "same-pass" 1)
            k2 (#'sut/derive-key "same-pass" 1003)]
        (expect (not (java.util.Arrays/equals
                       (.getEncoded ^SecretKeySpec k1)
                       (.getEncoded ^SecretKeySpec k2))))))

    (it "Linux peanuts key is deterministic"
      (let [k1 (#'sut/derive-key "peanuts" 1)
            k2 (#'sut/derive-key "peanuts" 1)]
        (expect (java.util.Arrays/equals
                  (.getEncoded ^SecretKeySpec k1)
                  (.getEncoded ^SecretKeySpec k2)))))))

;; =============================================================================
;; Unit Tests — decrypt-cookie-value v11 prefix (Linux)
;; =============================================================================

(defdescribe decrypt-cookie-value-v11-test
  "Unit tests for decrypt-cookie-value with v11 prefix (Linux keyring cookies)"

  (describe "round-trip encrypt → decrypt with v11 prefix"
    (it "decrypts a v11-prefixed value"
      (let [password   "test-keyring-password"
            secret-key (#'sut/derive-key password 1)
            plaintext  "linux-keyring-cookie-value"
            to-encrypt (byte-array (concat (repeat 32 (byte 0))
                                     (.getBytes plaintext "UTF-8")))
            iv         (javax.crypto.spec.IvParameterSpec. (byte-array 16 (byte 0x20)))
            cipher     (javax.crypto.Cipher/getInstance "AES/CBC/PKCS5Padding")
            _          (.init cipher (int javax.crypto.Cipher/ENCRYPT_MODE) secret-key iv)
            ciphertext (.doFinal cipher to-encrypt)
            ;; v11 prefix: 0x76 0x31 0x31
            enc-bytes  (byte-array (concat [0x76 0x31 0x31] (seq ciphertext)))
            result     (#'sut/decrypt-cookie-value secret-key enc-bytes 32)]
        (expect (= plaintext result))))))

;; =============================================================================
;; Unit Tests — decrypt-cookie-value-gcm (Windows AES-256-GCM)
;; =============================================================================

(defdescribe decrypt-cookie-value-gcm-test
  "Unit tests for decrypt-cookie-value-gcm — AES-256-GCM decryption (Windows)"

  (describe "handles invalid input"
    (it "returns nil for nil bytes"
      (let [key (SecretKeySpec. (byte-array 32 (byte 1)) "AES")]
        (expect (nil? (#'sut/decrypt-cookie-value-gcm key nil 0)))))

    (it "returns nil for short bytes (< 31)"
      (let [key (SecretKeySpec. (byte-array 32 (byte 1)) "AES")]
        (expect (nil? (#'sut/decrypt-cookie-value-gcm key (byte-array [1 2 3]) 0)))))

    (it "returns nil for non-v10 prefix"
      (let [key (SecretKeySpec. (byte-array 32 (byte 1)) "AES")]
        (expect (nil? (#'sut/decrypt-cookie-value-gcm key (byte-array 35) 0))))))

  (describe "round-trip encrypt → decrypt"
    (it "decrypts a GCM-encrypted value with skip-bytes"
      (let [raw-key    (byte-array 32 (byte 42))
            secret-key (SecretKeySpec. raw-key "AES")
            plaintext  "windows-cookie-value"
            ;; Prepend 32 zero bytes for hash prefix simulation
            to-encrypt (byte-array (concat (repeat 32 (byte 0))
                                     (.getBytes plaintext "UTF-8")))
            nonce      (byte-array 12 (byte 99))
            gcm-spec   (GCMParameterSpec. 128 nonce)
            cipher     (javax.crypto.Cipher/getInstance "AES/GCM/NoPadding")
            _          (.init cipher (int javax.crypto.Cipher/ENCRYPT_MODE) secret-key gcm-spec)
            ciphertext (.doFinal cipher to-encrypt)
            ;; Build v10 + nonce + ciphertext (which includes GCM auth tag)
            enc-bytes  (byte-array (concat [0x76 0x31 0x30] (seq nonce) (seq ciphertext)))
            result     (#'sut/decrypt-cookie-value-gcm secret-key enc-bytes 32)]
        (expect (= plaintext result))))

    (it "decrypts a GCM-encrypted value without skip-bytes"
      (let [raw-key    (byte-array 32 (byte 7))
            secret-key (SecretKeySpec. raw-key "AES")
            plaintext  "simple-value"
            to-encrypt (.getBytes plaintext "UTF-8")
            nonce      (byte-array 12 (byte 55))
            gcm-spec   (GCMParameterSpec. 128 nonce)
            cipher     (javax.crypto.Cipher/getInstance "AES/GCM/NoPadding")
            _          (.init cipher (int javax.crypto.Cipher/ENCRYPT_MODE) secret-key gcm-spec)
            ciphertext (.doFinal cipher to-encrypt)
            enc-bytes  (byte-array (concat [0x76 0x31 0x30] (seq nonce) (seq ciphertext)))
            result     (#'sut/decrypt-cookie-value-gcm secret-key enc-bytes 0)]
        (expect (= plaintext result))))))

;; =============================================================================
;; Unit Tests — read-local-state-key (Windows Local State JSON parsing)
;; =============================================================================

(defdescribe read-local-state-key-test
  "Unit tests for read-local-state-key — parses Chrome's Local State JSON"

  (describe "reads encrypted_key from Local State"
    (it "returns the base64 key from valid JSON"
      (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                       java.io.File/separator
                       "spel-test-local-state-" (System/currentTimeMillis))
            fake-profile (str tmp-dir java.io.File/separator "Profile 1")
            local-state  (str tmp-dir java.io.File/separator "Local State")]
        (try
          (.mkdirs (java.io.File. fake-profile))
          (spit local-state "{\"os_crypt\":{\"encrypted_key\":\"RFBBSUJTYW1wbGVLZXk=\"}}")
          (let [result (#'sut/read-local-state-key fake-profile)]
            (expect (= "RFBBSUJTYW1wbGVLZXk=" result)))
          (finally
            (.delete (java.io.File. local-state))
            (.delete (java.io.File. fake-profile))
            (.delete (java.io.File. tmp-dir))))))

    (it "returns nil when Local State file is missing"
      (expect (nil? (#'sut/read-local-state-key "/tmp/nonexistent-spel-test-dir/Profile 1"))))

    (it "returns nil when os_crypt key is missing from JSON"
      (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                       java.io.File/separator
                       "spel-test-no-key-" (System/currentTimeMillis))
            fake-profile (str tmp-dir java.io.File/separator "Profile 1")
            local-state  (str tmp-dir java.io.File/separator "Local State")]
        (try
          (.mkdirs (java.io.File. fake-profile))
          (spit local-state "{\"other_data\":\"value\"}")
          (let [result (#'sut/read-local-state-key fake-profile)]
            (expect (nil? result)))
          (finally
            (.delete (java.io.File. local-state))
            (.delete (java.io.File. fake-profile))
            (.delete (java.io.File. tmp-dir))))))))

;; =============================================================================
;; =============================================================================
;; localStorage Tests
;; =============================================================================

(defdescribe decode-ls-string-test
  "Tests for decode-ls-string — type-prefixed localStorage string decoding"

  (describe "ISO-8859-1 prefix"
    (it "decodes a Latin-1 string with 0x01 prefix"
      (let [raw (byte-array (concat [0x01] (seq (.getBytes "hello" "ISO-8859-1"))))
            result (#'sut/decode-ls-string raw)]
        (expect (= "hello" result)))))

  (describe "UTF-16LE prefix"
    (it "decodes a UTF-16LE string with 0x00 prefix"
      (let [utf16-data (.getBytes "test" "UTF-16LE")
            raw (byte-array (concat [0x00] (seq utf16-data)))
            result (#'sut/decode-ls-string raw)]
        (expect (= "test" result)))))

  (describe "nil and empty"
    (it "returns nil for nil input"
      (expect (nil? (#'sut/decode-ls-string nil))))

    (it "returns nil for empty byte array"
      (expect (nil? (#'sut/decode-ls-string (byte-array 0)))))))

(defdescribe parse-ls-record-key-test
  "Tests for parse-ls-record-key — localStorage key parsing"

  (describe "valid keys"
    (it "parses a standard record key"
      (let [;; "_https://x.com\x00\x01token"
            key-data (byte-array (concat [0x5F] ;; '_'
                                   (seq (.getBytes "https://x.com" "ISO-8859-1"))
                                   [0x00 0x01] ;; separator + type prefix
                                   (seq (.getBytes "token" "ISO-8859-1"))))
            [origin script-key] (#'sut/parse-ls-record-key key-data)]
        (expect (= "https://x.com" origin))
        (expect (= "token" script-key)))))

  (describe "invalid keys"
    (it "returns nil for META: prefix keys"
      (let [key-data (.getBytes "META:https://x.com" "ISO-8859-1")]
        (expect (nil? (#'sut/parse-ls-record-key key-data)))))

    (it "returns nil for nil"
      (expect (nil? (#'sut/parse-ls-record-key nil))))

    (it "returns nil for empty array"
      (expect (nil? (#'sut/parse-ls-record-key (byte-array 0)))))))

(defdescribe read-local-storage-test
  "Tests for read-local-storage — reads Chrome localStorage from LevelDB"

  (describe "nonexistent directory"
    (it "returns empty map for missing profile"
      (expect (= {} (sut/read-local-storage "/nonexistent/chrome/profile")))))

  (describe "real Chrome profile (macOS integration)"
    (it "reads localStorage data from real Chrome profile"
      (let [profile-dir (str (System/getProperty "user.home")
                          "/Library/Application Support/Google/Chrome/Profile 1")]
        ;; Only run on macOS with a real Chrome profile
        (when (.exists (java.io.File. (str profile-dir "/Local Storage/leveldb")))
          (let [ls-map (sut/read-local-storage profile-dir)]
            (expect (pos? (count ls-map)))
            ;; Every entry should be origin → {key → value}
            (doseq [[origin kv-map] (take 3 ls-map)]
              (expect (string? origin))
              (expect (map? kv-map))
              (doseq [[k v] kv-map]
                (expect (string? k))
                (expect (string? v))))))))))

(defdescribe navigable-origin-test
  "Tests for navigable-origin? — filters non-HTTP and Chrome internal origins"

  (describe "valid HTTP(S) origins"
    (it "accepts https origin"
      (expect (true? (#'sut/navigable-origin? "https://x.com"))))
    (it "accepts http origin"
      (expect (true? (#'sut/navigable-origin? "http://localhost:3000"))))
    (it "accepts https origin with path"
      (expect (true? (#'sut/navigable-origin? "https://www.example.com/page")))))

  (describe "non-HTTP schemes"
    (it "rejects devtools:// origin"
      (expect (false? (#'sut/navigable-origin? "devtools://devtools"))))
    (it "rejects chrome:// origin"
      (expect (false? (#'sut/navigable-origin? "chrome://settings"))))
    (it "rejects chrome-extension:// origin"
      (expect (false? (#'sut/navigable-origin? "chrome-extension://abcdef"))))
    (it "rejects file:// origin"
      (expect (false? (#'sut/navigable-origin? "file:///tmp/foo.html")))))

  (describe "Chrome partition key origins"
    (it "rejects origin containing ^0 separator"
      (expect (false? (#'sut/navigable-origin? "https://www.google.com/^0https://x.com"))))
    (it "rejects origin with ^0 in the middle"
      (expect (false? (#'sut/navigable-origin? "https://challenges.cloudflare.com/^0https://x.com")))))

  (describe "edge cases"
    (it "rejects nil"
      (expect (false? (#'sut/navigable-origin? nil))))
    (it "rejects empty string"
      (expect (false? (#'sut/navigable-origin? ""))))
    (it "rejects non-string"
      (expect (false? (#'sut/navigable-origin? 42))))))

(defdescribe ls-map-origins-filtering-test
  "Tests that ls-map->origins filters out non-navigable origins"

  (describe "filters non-navigable origins"
    (it "keeps only HTTP(S) origins and drops devtools/partition keys"
      (let [ls-map {"https://x.com"                              {"key1" "val1"}
                    "devtools://devtools"                         {"dk" "dv"}
                    "https://www.google.com/^0https://x.com"     {"gk" "gv"}
                    "chrome://settings"                           {"ck" "cv"}
                    "https://example.com"                         {"ek" "ev"}}
            result (#'sut/ls-map->origins ls-map nil)]
        (expect (= 2 (count result)))
        (let [origins (set (map #(get % "origin") result))]
          (expect (contains? origins "https://x.com"))
          (expect (contains? origins "https://example.com"))
          (expect (not (contains? origins "devtools://devtools")))
          (expect (not (contains? origins "https://www.google.com/^0https://x.com")))
          (expect (not (contains? origins "chrome://settings")))))))

  (describe "filtering combined with domain filter"
    (it "applies both navigability and domain filters"
      (let [ls-map {"https://x.com"            {"key1" "val1"}
                    "https://example.com"       {"ek" "ev"}
                    "devtools://devtools"        {"dk" "dv"}}
            result (#'sut/ls-map->origins ls-map "x.com")]
        (expect (= 1 (count result)))
        (expect (= "https://x.com" (get (first result) "origin"))))))

  (describe "empty input"
    (it "returns empty vector for empty map"
      (expect (= [] (#'sut/ls-map->origins {} nil))))
    (it "returns empty vector when all origins are non-navigable"
      (let [ls-map {"devtools://devtools" {"k" "v"}
                    "chrome://settings"   {"k" "v"}}]
        (expect (= [] (#'sut/ls-map->origins ls-map nil)))))))

(defdescribe export-cookies-json-format-test
  "Tests for export-cookies-json — verifies the output includes origins"

  (describe "output format"
    (it "includes origins array in output when localStorage is available"
      (let [profile-dir (str (System/getProperty "user.home")
                          "/Library/Application Support/Google/Chrome/Profile 1")]
        (when (and (= "Mac OS X" (System/getProperty "os.name"))
                (.exists (java.io.File. (str profile-dir "/Cookies"))))
          (let [json-str (sut/export-cookies-json profile-dir nil)
                parsed (json/read-json json-str)]
            (expect (contains? parsed "cookies"))
            (expect (contains? parsed "origins"))
            (expect (vector? (get parsed "origins")))
            ;; Each origin entry should have 'origin' and 'localStorage'
            (when (pos? (count (get parsed "origins")))
              (let [first-origin (first (get parsed "origins"))]
                (expect (contains? first-origin "origin"))
                (expect (contains? first-origin "localStorage"))))))))

    (it "returns empty origins when --no-local-storage"
      (let [profile-dir (str (System/getProperty "user.home")
                          "/Library/Application Support/Google/Chrome/Profile 1")]
        (when (and (= "Mac OS X" (System/getProperty "os.name"))
                (.exists (java.io.File. (str profile-dir "/Cookies"))))
          (let [json-str (sut/export-cookies-json profile-dir nil false)
                parsed (json/read-json json-str)]
            (expect (= [] (get parsed "origins")))))))))

;; =============================================================================
;; Integration Test — extract-cookies (macOS + Chrome required)
;; =============================================================================

(defdescribe extract-cookies-integration-test
  "Integration test for extract-cookies — requires macOS with Chrome installed.
   Gated on the existence of a real Chrome profile directory."

  (describe "extracts cookies from real Chrome profile"
    (it "returns a non-empty list of cookies when profile exists"
      (let [profile-dir (str (System/getProperty "user.home")
                          "/Library/Application Support/Google/Chrome/Profile 1")]
        ;; Only run on macOS with a real Chrome profile
        (when (and (= "Mac OS X" (System/getProperty "os.name"))
                (.exists (java.io.File. (str profile-dir "/Cookies"))))
          (let [cookies (sut/extract-cookies profile-dir)]
            (expect (pos? (.size cookies)))
            ;; Every cookie should have a name and domain
            (doseq [^Cookie c cookies]
              (expect (some? (.name c)))
              (expect (some? (.domain c))))))))))
