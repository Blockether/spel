(ns com.blockether.spel.security-test
  "Unit tests for security helpers — pure functions, no browser required.

   Covers:
   - Domain allowlist compilation + predicate behavior
   - URL → hostname extraction across schemes
   - Content boundary wrapping
   - Max-output truncation with UTF-8 safety"
  (:require
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.security :as sut]))

;; =============================================================================
;; compile-domain-patterns
;; =============================================================================

(defdescribe compile-domain-patterns-test
  "compile-domain-patterns: CSV → hostname predicate"

  (describe "exact hostname match"

    (it "matches the exact hostname"
      (let [allowed? (sut/compile-domain-patterns "example.com")]
        (expect (true? (allowed? "example.com")))))

    (it "does not match subdomains when no wildcard"
      (let [allowed? (sut/compile-domain-patterns "example.com")]
        (expect (false? (allowed? "www.example.com")))
        (expect (false? (allowed? "api.example.com")))))

    (it "does not match unrelated domains"
      (let [allowed? (sut/compile-domain-patterns "example.com")]
        (expect (false? (allowed? "evil.com")))
        (expect (false? (allowed? "notexample.com"))))))

  (describe "wildcard subdomain match"

    (it "matches the bare domain"
      (let [allowed? (sut/compile-domain-patterns "*.example.com")]
        (expect (true? (allowed? "example.com")))))

    (it "matches single-level subdomains"
      (let [allowed? (sut/compile-domain-patterns "*.example.com")]
        (expect (true? (allowed? "www.example.com")))
        (expect (true? (allowed? "api.example.com")))))

    (it "matches multi-level subdomains"
      (let [allowed? (sut/compile-domain-patterns "*.example.com")]
        (expect (true? (allowed? "a.b.c.example.com")))))

    (it "does NOT match look-alike suffix attacks"
      ;; notexample.com should NOT match *.example.com — critical security check
      (let [allowed? (sut/compile-domain-patterns "*.example.com")]
        (expect (false? (allowed? "notexample.com")))
        (expect (false? (allowed? "fakeexample.com")))))

    (it "does NOT match prefix attacks"
      (let [allowed? (sut/compile-domain-patterns "*.example.com")]
        (expect (false? (allowed? "example.com.evil.org"))))))

  (describe "comma-separated multi-pattern"

    (it "allows any matching pattern in the list"
      (let [allowed? (sut/compile-domain-patterns "example.com,*.github.io,api.service.io")]
        (expect (true? (allowed? "example.com")))
        (expect (true? (allowed? "username.github.io")))
        (expect (true? (allowed? "github.io")))   ;; *.github.io base
        (expect (true? (allowed? "api.service.io")))))

    (it "rejects everything outside the list"
      (let [allowed? (sut/compile-domain-patterns "example.com,*.github.io")]
        (expect (false? (allowed? "evil.com")))
        (expect (false? (allowed? "other.service.io"))))))

  (describe "whitespace tolerance"

    (it "trims whitespace around commas"
      (let [allowed? (sut/compile-domain-patterns "  example.com , *.github.io  ")]
        (expect (true? (allowed? "example.com")))
        (expect (true? (allowed? "user.github.io"))))))

  (describe "edge cases"

    (it "nil CSV → allow everything (no allowlist configured)"
      (let [allowed? (sut/compile-domain-patterns nil)]
        (expect (true? (allowed? "anything.com")))
        (expect (true? (allowed? "evil.com")))))

    (it "empty CSV → allow everything"
      (let [allowed? (sut/compile-domain-patterns "")]
        (expect (true? (allowed? "anything.com")))))

    (it "blank/whitespace CSV → allow everything"
      (let [allowed? (sut/compile-domain-patterns "   ")]
        (expect (true? (allowed? "anything.com")))))

    (it "CSV with only blank entries → allow everything"
      (let [allowed? (sut/compile-domain-patterns ",,,  ,")]
        (expect (true? (allowed? "anything.com")))))))

;; =============================================================================
;; extract-host
;; =============================================================================

(defdescribe extract-host-test
  "extract-host: URL → hostname string (nil for non-HTTP)"

  (describe "HTTP/HTTPS"

    (it "extracts hostname from http URL"
      (expect (= "example.com" (sut/extract-host "http://example.com/path"))))

    (it "extracts hostname from https URL"
      (expect (= "example.com" (sut/extract-host "https://example.com"))))

    (it "extracts hostname with port stripped"
      (expect (= "example.com" (sut/extract-host "https://example.com:8080/x"))))

    (it "extracts hostname from subdomain"
      (expect (= "api.example.com" (sut/extract-host "https://api.example.com/v1")))))

  (describe "WebSocket schemes"

    (it "extracts host from ws://"
      (expect (= "example.com" (sut/extract-host "ws://example.com/socket"))))

    (it "extracts host from wss://"
      (expect (= "example.com" (sut/extract-host "wss://example.com/")))))

  (describe "non-HTTP schemes return nil (pass-through)"

    (it "data: URL → nil"
      (expect (nil? (sut/extract-host "data:text/html,<h1>hi</h1>"))))

    (it "blob: URL → nil"
      (expect (nil? (sut/extract-host "blob:null/abc-123"))))

    (it "about:blank → nil"
      (expect (nil? (sut/extract-host "about:blank"))))

    (it "chrome-extension: URL → nil"
      (expect (nil? (sut/extract-host "chrome-extension://abc/page.html")))))

  (describe "malformed input"

    (it "returns nil on parse failure instead of throwing"
      (expect (nil? (sut/extract-host "not a url at all"))))))

;; =============================================================================
;; request-allowed?
;; =============================================================================

(defdescribe request-allowed-test
  "request-allowed?: integrates extract-host + predicate"

  (describe "HTTP requests respect allowlist"

    (it "allows matching domain"
      (let [pred (sut/compile-domain-patterns "example.com")]
        (expect (true? (sut/request-allowed? pred "https://example.com/x")))))

    (it "blocks non-matching domain"
      (let [pred (sut/compile-domain-patterns "example.com")]
        (expect (false? (sut/request-allowed? pred "https://evil.com/x")))))

    (it "blocks subdomain when only exact is allowed"
      (let [pred (sut/compile-domain-patterns "example.com")]
        (expect (false? (sut/request-allowed? pred "https://api.example.com/"))))))

  (describe "non-HTTP URLs always pass through"

    (it "data: URLs bypass allowlist (browser internals)"
      (let [pred (sut/compile-domain-patterns "example.com")]
        (expect (true? (sut/request-allowed? pred "data:text/html,<h1>hi</h1>")))))

    (it "about:blank bypasses allowlist"
      (let [pred (sut/compile-domain-patterns "example.com")]
        (expect (true? (sut/request-allowed? pred "about:blank")))))))

;; =============================================================================
;; wrap-boundaries
;; =============================================================================

(defdescribe wrap-boundaries-test
  "wrap-boundaries: opt-in XML-style delimiter wrapping"

  (describe "enabled"

    (it "wraps text with open/close tags"
      (let [wrapped (sut/wrap-boundaries true "hello world")]
        (expect (.contains wrapped "<untrusted-content>"))
        (expect (.contains wrapped "</untrusted-content>"))
        (expect (.contains wrapped "hello world"))))

    (it "preserves the original text verbatim between delimiters"
      (let [payload  "<html><body>pwned</body></html>"
            wrapped  (sut/wrap-boundaries true payload)]
        (expect (.contains wrapped payload))))

    (it "nil text becomes empty between delimiters (not NPE)"
      (let [wrapped (sut/wrap-boundaries true nil)]
        (expect (.contains wrapped "<untrusted-content>"))
        (expect (.contains wrapped "</untrusted-content>")))))

  (describe "disabled"

    (it "returns text unchanged when false"
      (expect (= "hello" (sut/wrap-boundaries false "hello"))))

    (it "returns text unchanged when nil"
      (expect (= "hello" (sut/wrap-boundaries nil "hello"))))))

;; =============================================================================
;; truncate
;; =============================================================================

(defdescribe truncate-test
  "truncate: cap output length with informative suffix"

  (describe "short input (no truncation)"

    (it "returns text unchanged when under limit"
      (expect (= "hello" (sut/truncate 100 "hello"))))

    (it "returns text unchanged when exactly at limit"
      (expect (= "hello" (sut/truncate 5 "hello")))))

  (describe "long input (truncated)"

    (it "truncates and appends suffix"
      (let [long-text (apply str (repeat 1000 "x"))
            result    (sut/truncate 100 long-text)]
        ;; Result must be <= max-chars
        (expect (<= (count result) 100))
        ;; Suffix indicates original length
        (expect (.contains result "1000"))
        (expect (.contains result "truncated"))))

    (it "suffix preserves original character count"
      (let [txt    (apply str (repeat 5000 "a"))
            result (sut/truncate 50 txt)]
        (expect (.contains result "5000")))))

  (describe "edge cases"

    (it "nil max-chars → no truncation"
      (expect (= "hello" (sut/truncate nil "hello"))))

    (it "zero max-chars → no truncation (feature disabled)"
      (expect (= "hello" (sut/truncate 0 "hello"))))

    (it "negative max-chars → no truncation"
      (expect (= "hello" (sut/truncate -5 "hello"))))

    (it "nil text → nil"
      (expect (nil? (sut/truncate 100 nil))))))
