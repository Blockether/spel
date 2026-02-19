(ns com.blockether.spel.core-test
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :as sut]
   [com.blockether.spel.test-fixtures :as tf :refer [*pw* *browser* with-playwright with-browser]]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it]])
  (:import
   [com.microsoft.playwright Browser BrowserContext BrowserType
    Page Playwright]))

;; =============================================================================
;; Playwright Lifecycle
;; =============================================================================

(defdescribe create-test
  "Tests for Playwright creation"

  (describe "create and close"
    {:context [with-playwright]}
    (it "creates a valid Playwright instance"
      (expect (instance? Playwright *pw*)))

    (expect-it "close! returns nil"
      (nil? (sut/close! *pw*))))

  (describe "close! edge cases"
    (expect-it "close! with nil is safe"
      (nil? (sut/close! nil)))))

(defdescribe with-playwright-test
  "Tests for with-playwright macro"

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

  (describe "browser type names"
    {:context [with-playwright]}
    (it "returns chromium browser type"
      (let [bt (sut/chromium *pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "chromium" (sut/browser-type-name bt)))))

    (it "returns firefox browser type"
      (let [bt (sut/firefox *pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "firefox" (sut/browser-type-name bt)))))

    (it "returns webkit browser type"
      (let [bt (sut/webkit *pw*)]
        (expect (instance? BrowserType bt))
        (expect (= "webkit" (sut/browser-type-name bt)))))))

;; =============================================================================
;; Browser Launching
;; =============================================================================

(defdescribe launch-test
  "Tests for browser launching"

  (describe "launch-chromium"
    {:context [with-playwright with-browser]}
    (it "launches and closes chromium browser"
      (expect (instance? Browser *browser*))
      (expect (sut/browser-connected? *browser*))
      (expect (string? (sut/browser-version *browser*)))))

  (describe "launch with default opts"
    {:context [with-playwright]}
    (it "launch with default opts is headless"
      (sut/with-browser [browser (sut/launch-chromium *pw*)]
        (expect (sut/browser-connected? browser))))))

;; =============================================================================
;; Browser Context
;; =============================================================================

(defdescribe context-test
  "Tests for browser context operations"

  (describe "new-context"
    {:context [with-playwright with-browser]}
    (it "creates a browser context"
      (sut/with-context [ctx (sut/new-context *browser*)]
        (expect (instance? BrowserContext ctx))
        (expect (= *browser* (sut/context-browser ctx)))))

    (it "creates context with options"
      (sut/with-context [ctx (sut/new-context *browser*
                               {:viewport {:width 800 :height 600}
                                :user-agent "test-agent"})]
        (expect (instance? BrowserContext ctx)))))

  (describe "context-pages"
    {:context [with-playwright with-browser]}
    (it "returns empty list for new context"
      (sut/with-context [ctx (sut/new-context *browser*)]
        (expect (empty? (sut/context-pages ctx))))))

  (describe "context-storage-state"
    {:context [with-playwright with-browser]}
    (it "returns storage state as JSON string"
      (sut/with-context [ctx (sut/new-context *browser*)]
        (let [state (sut/context-storage-state ctx)]
          (expect (string? state))
          (expect (str/includes? state "cookies"))
          (expect (str/includes? state "origins"))))))

  (describe "context-save-storage-state!"
    {:context [with-playwright with-browser]}
    (it "saves storage state to a file"
      (sut/with-context [ctx (sut/new-context *browser*)]
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

  (describe "new-page"
    {:context [with-playwright with-browser]}
    (it "creates a page from browser"
      (sut/with-page [page (sut/new-page *browser*)]
        (expect (instance? Page page))))

    (it "creates a page from context"
      (sut/with-context [ctx (sut/new-context *browser*)]
        (let [page (sut/new-page-from-context ctx)]
          (expect (instance? Page page))
          (expect (= 1 (count (sut/context-pages ctx))))))))

  (describe "close-page"
    {:context [with-playwright with-browser]}
    (it "closes a page"
      (let [page (sut/new-page *browser*)]
        (sut/close-page! page)
        ;; Page is still an instance, just closed
        (expect (instance? Page page))))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defdescribe error-handling-test
  "Tests for anomaly-based error handling"

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
