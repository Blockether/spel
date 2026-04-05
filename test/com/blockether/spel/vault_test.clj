(ns com.blockether.spel.vault-test
  "Unit tests for the encrypted credential vault.

   Covers:
   - AES-256-GCM encrypt/decrypt roundtrip
   - Tamper detection (GCM authentication tag)
   - File-based credential store (save/load/list/delete)
   - Name validation (path-traversal guard)

   Tests reuse the ambient vault key — they touch only test-specific
   credential names so they cannot collide with real credentials."
  (:require
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.vault :as sut]))

(defn- threw?
  "Returns true if `f` threw an Exception when called with zero args."
  [f]
  (try (f) false (catch Exception _ true)))

;; =============================================================================
;; encrypt / decrypt
;; =============================================================================

(defdescribe encrypt-decrypt-test
  "AES-256-GCM roundtrip + tamper detection"

  (describe "roundtrip"

    (it "decrypts its own ciphertext back to the original string"
      (let [plaintext "the quick brown fox jumps over the lazy dog"
            blob      (sut/encrypt plaintext)
            result    (sut/decrypt blob)]
        (expect (= plaintext result))))

    (it "handles UTF-8 multibyte characters"
      (let [plaintext "żółć ñ 日本語 🔐"
            blob      (sut/encrypt plaintext)]
        (expect (= plaintext (sut/decrypt blob)))))

    (it "produces distinct ciphertexts on repeat (random IV)"
      (let [blob1 (sut/encrypt "same")
            blob2 (sut/encrypt "same")]
        ;; Same plaintext + same key MUST yield different ciphertexts because
        ;; the 12-byte IV is random each call. Reused IVs break AES-GCM.
        (expect (not (java.util.Arrays/equals ^bytes blob1 ^bytes blob2))))))

  (describe "tamper detection"

    (it "throws on flipped bit in ciphertext (GCM auth tag)"
      (let [blob (sut/encrypt "tamper me")]
        ;; Flip a byte in the middle (past the IV)
        (aset-byte blob 20 (byte (bit-xor (aget blob 20) 0x01)))
        (expect (true? (threw? #(sut/decrypt blob))))))))

;; =============================================================================
;; Credential store (filesystem)
;; =============================================================================

(defdescribe credential-store-test
  "Save/load/list/delete against the on-disk vault directory"

  (describe "save + load"

    (it "persists a record and reads it back byte-for-byte"
      (let [record {:name     "spel-test-cred"
                    :url      "https://test.example"
                    :username "alice@example.com"
                    :password "p@ssw0rd!žźż"}
            _      (sut/save-credential! record)
            loaded (sut/load-credential "spel-test-cred")]
        (expect (= (:name record)     (:name loaded)))
        (expect (= (:url record)      (:url loaded)))
        (expect (= (:username record) (:username loaded)))
        (expect (= (:password record) (:password loaded)))
        (sut/delete-credential! "spel-test-cred"))))

  (describe "list"

    (it "returns public-safe projection (no passwords)"
      (sut/save-credential! {:name "spel-test-list-a"
                             :url  "https://a.example"
                             :username "a@x"
                             :password "secret-a"})
      (sut/save-credential! {:name "spel-test-list-b"
                             :url  "https://b.example"
                             :username "b@x"
                             :password "secret-b"})
      (let [listed (sut/list-credentials)
            names  (set (map :name listed))]
        (expect (contains? names "spel-test-list-a"))
        (expect (contains? names "spel-test-list-b"))
        ;; CRITICAL: passwords must NEVER appear in list output
        (expect (not-any? :password listed)))
      (sut/delete-credential! "spel-test-list-a")
      (sut/delete-credential! "spel-test-list-b")))

  (describe "delete"

    (it "removes the file and subsequent loads fail"
      (sut/save-credential! {:name "spel-test-delete"
                             :url  "https://d.example"
                             :username "d"
                             :password "x"})
      (sut/delete-credential! "spel-test-delete")
      (expect (true? (threw? #(sut/load-credential "spel-test-delete")))))))

;; =============================================================================
;; Path-traversal guard
;; =============================================================================

(defdescribe safe-name-guard-test
  "Path-traversal and invalid-name rejection"

  (describe "rejects unsafe names"

    (it "refuses path-traversal attempts"
      (expect (true? (threw? #(sut/save-credential!
                                {:name "../etc/passwd"
                                 :url "x" :username "x" :password "x"})))))

    (it "refuses names with slashes"
      (expect (true? (threw? #(sut/save-credential!
                                {:name "foo/bar"
                                 :url "x" :username "x" :password "x"})))))

    (it "refuses empty names"
      (expect (true? (threw? #(sut/save-credential!
                                {:name ""
                                 :url "x" :username "x" :password "x"}))))))

  (describe "accepts safe names"

    (it "allows alphanumeric + underscore + dot + dash"
      (sut/save-credential! {:name "github_test-01.acc"
                             :url "https://gh.example"
                             :username "u" :password "p"})
      (expect (some? (sut/load-credential "github_test-01.acc")))
      (sut/delete-credential! "github_test-01.acc"))))
