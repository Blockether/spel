(ns com.blockether.spel.core-test
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.allure :refer [around defdescribe describe expect expect-it it]])
  (:import
   [java.net InetAddress ServerSocket]
   [com.microsoft.playwright Browser BrowserContext BrowserType
    Page Playwright]))

(alias 'sut 'com.blockether.spel.core)

;; =============================================================================
;; Playwright Lifecycle
;; =============================================================================

(defdescribe create-test
  "Tests for Playwright creation"

  (describe "create and close"
    (it "creates a valid Playwright instance"
      (sut/with-playwright [pw (sut/create)]
        (expect (instance? Playwright pw))))

    (expect-it "close! returns nil"
      (sut/with-playwright [pw (sut/create)]
        (nil? (sut/close! pw)))))

  (describe "close! edge cases"
    (expect-it "close! with nil is safe"
      (nil? (sut/close! nil)))))

(defdescribe find-free-port-test
  "Tests for finding an available local TCP port"

  (around [f] (core/with-testing-browser (f)))

  (it "returns a valid TCP port number"
    (let [port (sut/find-free-port)]
      (expect (integer? port))
      (expect (<= 1 port))
      (expect (<= port 65535))))

  (it "returns a free port even when three ports are already occupied"
    (with-open [^ServerSocket s1 (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
                ^ServerSocket s2 (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
                ^ServerSocket s3 (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (let [occupied #{(.getLocalPort s1) (.getLocalPort s2) (.getLocalPort s3)}
            port     (sut/find-free-port)]
        (expect (integer? port))
        (expect (not (contains? occupied port)))
        (with-open [^ServerSocket verify (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))]
          (expect (= port (.getLocalPort verify))))))))

(defdescribe url-codec-test
  "Tests for URL encoding and decoding convenience helpers"

  (it "encodes query text with UTF-8"
    (expect (= "a%2Bb%3D1+%26x+y" (sut/url-encode "a+b=1 &x y"))))

  (it "decodes query text with UTF-8"
    (expect (= "a+b=1 &x y" (sut/url-decode "a%2Bb%3D1+%26x+y"))))

  (it "round-trips UTF-8 text"
    (let [raw "żółć i kawa"]
      (expect (= raw (sut/url-decode (sut/url-encode raw)))))))

(defdescribe with-playwright-test
  "Tests for with-playwright macro"

  (around [f] (core/with-testing-browser (f)))

  (describe "resource management"
    (it "binds and cleans up Playwright"
      (let [result (sut/with-playwright [pw (sut/create)]
                     (expect (instance? Playwright pw))
                     :done)]
        (expect (= :done result))))))

;; =============================================================================
;; Browser Type Access
;; =============================================================================

(defdescribe browser-type-test
  "Tests for browser type accessors"

  (around [f] (core/with-testing-browser (f)))

  (describe "browser type names"
    (it "returns chromium browser type"
      (let [bt (sut/chromium core/*testing-pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "chromium" (sut/browser-type-name bt)))))

    (it "returns firefox browser type"
      (let [bt (sut/firefox core/*testing-pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "firefox" (sut/browser-type-name bt)))))

    (it "returns webkit browser type"
      (let [bt (sut/webkit core/*testing-pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "webkit" (sut/browser-type-name bt)))))))

;; =============================================================================
;; Browser Launching
;; =============================================================================

(defdescribe launch-test
  "Tests for browser launching"

  (around [f] (core/with-testing-browser (f)))

  (describe "launch-chromium"
    (it "launches and closes chromium browser"
      (expect (instance? Browser core/*testing-browser*))
      (expect (sut/browser-connected? core/*testing-browser*))
      (expect (string? (sut/browser-version core/*testing-browser*)))))

  (describe "launch with default opts"
    (it "launch with default opts is headless"
      (sut/with-browser [browser (sut/launch-chromium core/*testing-pw*)]
        (expect (sut/browser-connected? browser))))))

;; =============================================================================
;; Interactive Mode
;; =============================================================================

(defdescribe interactive-test
  "Tests for interactive? fixture helper"

  (around [f] (core/with-testing-browser (f)))

  (describe "interactive? without env or property"
    (it "returns false by default"
      (expect (not (core/testing-interactive?)))))

  (describe "interactive? with system property"
    (it "returns true when spel.interactive is set"
      (try
        (System/setProperty "spel.interactive" "true")
        (expect (core/testing-interactive?))
        (finally
          (System/clearProperty "spel.interactive")))))

  (describe "interactive? after clearing property"
    (it "returns false again"
      (expect (not (core/testing-interactive?))))))

;; =============================================================================
;; Slow-Mo
;; =============================================================================

(defdescribe slow-mo-test
  "Tests for slow-mo fixture helper"

  (around [f] (core/with-testing-browser (f)))

  (describe "slow-mo without env or property"
    (it "returns 0 by default"
      (expect (= 0 (core/testing-slow-mo)))))

  (describe "slow-mo with system property"
    (it "returns parsed value when spel.slow-mo is set"
      (try
        (System/setProperty "spel.slow-mo" "500")
        (expect (= 500 (core/testing-slow-mo)))
        (finally
          (System/clearProperty "spel.slow-mo")))))

  (describe "slow-mo after clearing property"
    (it "returns 0 again"
      (expect (= 0 (core/testing-slow-mo))))))

;; =============================================================================
;; Browser Engine
;; =============================================================================

(defdescribe browser-engine-test
  "Tests for browser-engine fixture helper"

  (around [f] (core/with-testing-browser (f)))

  (describe "browser-engine without env or property"
    (it "returns :chromium by default"
      (expect (= :chromium (core/testing-browser-engine)))))

  (describe "browser-engine with system property"
    (it "returns :firefox when spel.browser=firefox"
      (try
        (System/setProperty "spel.browser" "firefox")
        (expect (= :firefox (core/testing-browser-engine)))
        (finally
          (System/clearProperty "spel.browser"))))

    (it "returns :webkit when spel.browser=webkit"
      (try
        (System/setProperty "spel.browser" "webkit")
        (expect (= :webkit (core/testing-browser-engine)))
        (finally
          (System/clearProperty "spel.browser")))))

  (describe "browser-engine after clearing property"
    (it "returns :chromium again"
      (expect (= :chromium (core/testing-browser-engine))))))

;; =============================================================================
;; Browser Context
;; =============================================================================

(defdescribe context-test
  "Tests for browser context operations"

  (around [f] (core/with-testing-browser (f)))

  (describe "new-context"
    (it "creates a browser context"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*)]
        (expect (instance? BrowserContext ctx))
        (expect (= core/*testing-browser* (sut/context-browser ctx)))))

    (it "creates context with options"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*
                               {:viewport {:width 800 :height 600}
                                :user-agent "test-agent"})]
        (expect (instance? BrowserContext ctx)))))

  (describe "context-pages"
    (it "returns empty list for new context"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*)]
        (expect (empty? (sut/context-pages ctx))))))

  (describe "context-storage-state"
    (it "returns storage state as JSON string"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*)]
        (let [state (sut/context-storage-state ctx)]
          (expect (string? state))
          (expect (str/includes? state "cookies"))
          (expect (str/includes? state "origins"))))))

  (describe "context-save-storage-state!"
    (it "saves storage state to a file"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*)]
        (let [tmp-file (java.io.File/createTempFile "spel-storage" ".json")
              path     (.getAbsolutePath tmp-file)]
          (try
            (sut/context-save-storage-state! ctx path)
            (expect (.exists tmp-file))
            (expect (pos? (.length tmp-file)))
            (let [content (slurp tmp-file)]
              (expect (str/includes? content "cookies"))
              (expect (str/includes? content "origins")))
            (finally
              (.delete tmp-file))))))))

;; =============================================================================
;; Page Creation
;; =============================================================================

(defdescribe page-test
  "Tests for page operations"

  (around [f] (core/with-testing-browser (f)))

  (describe "new-page"
    (it "creates a page from browser"
      (sut/with-page [page (sut/new-page core/*testing-browser*)]
        (expect (instance? Page page))))

    (it "creates a page from context"
      (sut/with-context [ctx (sut/new-context core/*testing-browser*)]
        (let [page (sut/new-page-from-context ctx)]
          (expect (instance? Page page))
          (expect (= 1 (count (sut/context-pages ctx))))))))

  (describe "close-page"
    (it "closes a page"
      (let [page (sut/new-page core/*testing-browser*)]
        (sut/close-page! page)
        ;; Page is still an instance, just closed
        (expect (instance? Page page))))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defdescribe error-handling-test
  "Tests for anomaly-based error handling"

  (around [f] (core/with-testing-browser (f)))

  (describe "wrap-error categories"
    (it "wraps TimeoutError as busy anomaly"
      (let [result (sut/wrap-error (com.microsoft.playwright.TimeoutError. "timed out"))]
        (expect (anomaly/anomaly? result))
        (expect (= ::anomaly/busy (::anomaly/category result)))
        (expect (= :playwright.error/timeout (:playwright/error-type result)))))

    (it "wraps TargetClosedError as interrupted anomaly"
      (let [result (sut/wrap-error (com.microsoft.playwright.impl.TargetClosedError. "closed"))]
        (expect (anomaly/anomaly? result))
        (expect (= ::anomaly/interrupted (::anomaly/category result)))
        (expect (= :playwright.error/target-closed (:playwright/error-type result)))))

    (it "wraps PlaywrightException as fault anomaly"
      (let [result (sut/wrap-error (com.microsoft.playwright.PlaywrightException. "failed"))]
        (expect (anomaly/anomaly? result))
        (expect (= ::anomaly/fault (::anomaly/category result)))
        (expect (= :playwright.error/exception (:playwright/error-type result)))))

    (it "wraps general Exception as fault anomaly"
      (let [result (sut/wrap-error (Exception. "error"))]
        (expect (anomaly/anomaly? result))
        (expect (= ::anomaly/fault (::anomaly/category result)))
        (expect (= :playwright.error/unknown (:playwright/error-type result))))))

  (describe "wrap-error diagnostics"
    (it "includes stacktrace as vector"
      (let [result (sut/wrap-error (Exception. "test"))]
        (expect (vector? (:playwright/stacktrace result)))
        (expect (pos? (count (:playwright/stacktrace result))))))

    (it "includes the original exception object"
      (let [e (Exception. "original")
            result (sut/wrap-error e)]
        (expect (identical? e (:playwright/exception result)))))

    (it "includes the exception class name"
      (let [result (sut/wrap-error (com.microsoft.playwright.TimeoutError. "t"))]
        (expect (= "com.microsoft.playwright.TimeoutError"
                  (:playwright/class result))))))

  (describe "safe macro"
    (expect-it "returns value on success"
      (= 42 (sut/safe 42)))

    (it "catches TimeoutError"
      (let [result (sut/safe (throw (com.microsoft.playwright.TimeoutError. "t")))]
        (expect (anomaly/anomaly? result))
        (expect (= :playwright.error/timeout (:playwright/error-type result)))))

    (it "catches TargetClosedError"
      (let [result (sut/safe (throw (com.microsoft.playwright.impl.TargetClosedError. "c")))]
        (expect (anomaly/anomaly? result))
        (expect (= :playwright.error/target-closed (:playwright/error-type result)))))

    (it "catches PlaywrightException"
      (let [result (sut/safe (throw (com.microsoft.playwright.PlaywrightException. "p")))]
        (expect (anomaly/anomaly? result))
        (expect (= :playwright.error/exception (:playwright/error-type result)))))

    (it "catches generic Exception"
      (let [result (sut/safe (throw (Exception. "e")))]
        (expect (anomaly/anomaly? result))
        (expect (= :playwright.error/unknown (:playwright/error-type result)))))))

;; =============================================================================
;; cookie->map
;; =============================================================================

(defdescribe cookie->map-test
  "Tests for cookie->map conversion"

  (describe "converts Cookie Java object to Clojure map"
    (it "returns map with all expected keys"
      (let [cookie (doto (com.microsoft.playwright.options.Cookie. "test" "val")
                     (.setDomain "example.com")
                     (.setPath "/"))
            result (sut/cookie->map cookie)]
        (expect (map? result))
        (expect (= "test" (:name result)))
        (expect (= "val" (:value result)))
        (expect (= "example.com" (:domain result)))
        (expect (= "/" (:path result)))
        (expect (string? (:sameSite result)))))))
