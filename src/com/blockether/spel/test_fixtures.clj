(ns com.blockether.spel.test-fixtures
  "Shared Playwright test fixtures using Lazytest around hooks.

   Provides dynamic vars for Playwright instance, browser, and page,
   along with around hooks that can be used with Lazytest's :context metadata.

   Usage:
   (ns my-test
     (:require
      [com.blockether.spel.allure :refer [defdescribe describe it expect]]
      [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]))

   (defdescribe my-test
     (describe \"with full setup\" {:context [with-playwright with-browser with-page]}
       (it \"can access page\"
         (expect (some? *page*)))))

   Import `defdescribe`, `describe`, `it`, and `expect` from
   `com.blockether.spel.allure` instead of `lazytest.core` for a single
   require. `expect` auto-creates an Allure step per expectation with
   its own pass/fail status. The other three delegate to `lazytest.core`
   unchanged. All are zero-overhead when not running under the Allure
   reporter."
  (:require
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.core :as core]
   [lazytest.core :refer [around]]
   [lazytest.test-case :as tc])
  (:import
   [com.microsoft.playwright Tracing$StartOptions Tracing$StopOptions]
   [java.io File]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ensure!
  "Throws the original Playwright exception when `result` is an anomaly
   map (from any safe-wrapped call), instead of letting a confusing
   ClassCastException surface later. Returns `result` unchanged on success."
  [result]
  (if (core/anomaly? result)
    (throw (or (:playwright/exception result)
             (ex-info (str "Playwright operation failed: "
                        (:cognitect.anomalies/message result))
               (dissoc result :playwright/exception))))
    result))

;; =============================================================================
;; Lightweight try-test-case wrapper (binds *test-title* without Allure)
;; =============================================================================

(def ^:private test-info-ref-count
  "Ref counter so nested with-playwright calls don't double-patch."
  (atom 0))

(def ^:private original-try-test-case
  "Stores the original try-test-case fn before patching."
  (atom nil))

(defn- wrap-test-info
  "Wraps try-test-case to bind `allure/*test-title*` from the test case's
   `:doc` field. Only binds when `*test-title*` is not already set (i.e.,
   the Allure reporter hasn't bound it). This ensures test title is
   available inside test bodies for trace groups even without Allure."
  [original-fn]
  (fn [tc]
    (if allure/*test-title*
      ;; Allure reporter already bound *test-title* — don't override
      (original-fn tc)
      ;; No Allure — bind from the test case doc string
      (binding [allure/*test-title* (tc/identifier tc)]
        (original-fn tc)))))

(defn- install-test-info!
  "Patches `lazytest.test-case/try-test-case` to bind `allure/*test-title*`
   for all tests. Ref-counted so multiple `with-playwright` calls are safe."
  []
  (when (zero? (long (swap! test-info-ref-count inc)))
    ;; unreachable since inc starts from 0 → 1, but defensive
    nil)
  (when (= 1 (long @test-info-ref-count))
    (let [original (deref #'tc/try-test-case)]
      (reset! original-try-test-case original)
      (alter-var-root #'tc/try-test-case wrap-test-info))))

(defn- uninstall-test-info!
  "Restores the original `try-test-case` when the last ref is released."
  []
  (when (zero? (long (swap! test-info-ref-count dec)))
    (when-let [original @original-try-test-case]
      (alter-var-root #'tc/try-test-case (constantly original))
      (reset! original-try-test-case nil))))

;; =============================================================================
;; Interactive Mode
;; =============================================================================

(defn interactive?
  "Returns true when tests should run in interactive (headed) mode.
   Checks system property `spel.interactive` first, then env var `SPEL_INTERACTIVE`.
   Any truthy string value (e.g. \"true\", \"1\", \"yes\") enables interactive mode.

   Usage:
     clojure -J-Dspel.interactive=true -M:test
     SPEL_INTERACTIVE=true clojure -M:test"
  []
  (some? (or (System/getProperty "spel.interactive")
           (System/getenv "SPEL_INTERACTIVE"))))

;; =============================================================================
;; Dynamic Vars
;; =============================================================================

(def ^{:dynamic true :doc "Dynamic var holding the current Playwright instance."}
  *pw* nil)

(def ^{:dynamic true :doc "Dynamic var holding the current Browser instance."}
  *browser* nil)

(def ^{:dynamic true :doc "Dynamic var holding the current Page instance."}
  *page* nil)

(def ^{:dynamic true :doc "Dynamic var holding the current BrowserContext.
  Bound by with-page (auto-tracing) and with-traced-page. nil otherwise.
  Useful for accessing context-level APIs like (.request ctx) for traced
  API calls, cookies, routes, etc."}
  *browser-context* nil)

(def ^{:dynamic true :doc "Dynamic var holding an APIRequestContext bound to the
  current BrowserContext. API calls through this context automatically
  appear in Playwright traces (unlike standalone APIRequestContexts).
  Bound by with-page (auto-tracing) and with-traced-page. nil otherwise."}
  *browser-api* nil)

;; =============================================================================
;; Around Hooks
;; =============================================================================

(def with-playwright
  "Around hook: creates and closes a Playwright instance.

   Binds the Playwright instance to *pw*.
   Also installs a lightweight try-test-case wrapper that binds
   `allure/*test-title*` from the test case doc string, so trace
   groups and step names work even without the Allure reporter."
  (around [f]
    (install-test-info!)
    (let [pw (ensure! (core/create))]
      (try
        (binding [*pw* pw]
          (f))
        (finally
          (core/close! pw)
          (uninstall-test-info!))))))

(def with-browser
  "Around hook: launches and closes a Chromium browser.

   Headless by default. Set `SPEL_INTERACTIVE=true` env var or
   `-Dspel.interactive=true` system property to run headed (interactive).

   Requires *pw* to be bound (use with with-playwright).
   Binds the Browser instance to *browser*."
  (around [f]
    (let [browser (ensure! (core/launch-chromium *pw* {:headless (not (interactive?))}))]
      (try
        (binding [*browser* browser]
          (f))
        (finally
          (when (instance? com.microsoft.playwright.Browser browser)
            (core/close-browser! browser)))))))

(def with-page
  "Around hook: creates and closes a page.

   Requires *browser* to be bound (use with with-browser).
   Binds the Page instance to *page* and allure/*page* (for automatic
   step screenshots).

   When the Allure reporter is active, automatically enables Playwright
   tracing (screenshots + DOM snapshots + sources) and HAR recording.
   Also binds *browser-context* and *browser-api* so API calls made
   through *browser-api* appear in the Playwright trace.
   The Allure reporter picks up the trace and HAR files and attaches
   them to the test result — zero configuration needed in tests."
  (around [f]
    (if (allure/reporter-active?)
            ;; Auto-trace: create context with HAR + tracing, then page
      (let [trace-file (File/createTempFile "pw-trace-" ".zip")
            har-file   (File/createTempFile "pw-har-" ".har")
            ctx        (ensure!
                         (core/new-context *browser*
                           {:record-har-path (str har-file)
                            :record-har-mode :full}))
            tracing    (.tracing ^com.microsoft.playwright.BrowserContext ctx)]
        (.start tracing (doto (Tracing$StartOptions.)
                          (.setScreenshots true)
                          (.setSnapshots true)
                          (.setSources true)
                          (.setTitle (or allure/*test-title* "spel"))))
        (let [page (core/new-page-from-context ctx)]
          (try
            (binding [*page*              page
                      *browser-context*   ctx
                      *browser-api*       (.request ^com.microsoft.playwright.BrowserContext ctx)
                      allure/*page*       page
                      allure/*tracing*    tracing
                      allure/*trace-path* trace-file
                      allure/*har-path*   har-file]
              (f))
            (finally
              (when (instance? com.microsoft.playwright.Page page)
                (core/close-page! page))
              (try (.stop tracing (doto (Tracing$StopOptions.)
                                    (.setPath (.toPath trace-file))))
                   (catch Exception _))
              (let [t (doto (Thread. (fn []
                                       (try (core/close-context! ctx)
                                            (catch Exception _))))
                        (.setDaemon true)
                        (.start))]
                (.join t 5000))))))
            ;; Normal mode: plain page, no tracing overhead
      (let [ctx  (ensure! (core/new-context *browser*))
            page (core/new-page-from-context ctx)]
        (try
          (binding [*page*            page
                    *browser-context* ctx
                    *browser-api*     (.request ^com.microsoft.playwright.BrowserContext ctx)
                    allure/*page*     page]
            (f))
          (finally
            (when (instance? com.microsoft.playwright.Page page)
              (core/close-page! page))
            (try (core/close-context! ctx) (catch Exception _))))))))

(def with-traced-page
  "Around hook: creates a BrowserContext with HAR recording and Playwright
   tracing enabled, then creates a page from that context.

   Requires *browser* to be bound (use with with-browser).
   Binds:
     *page*             — the Page instance
     allure/*page*      — for automatic step screenshots
     allure/*trace-path* — where the trace zip will be written
     allure/*har-path*   — where the HAR file will be written

    On teardown:
      1. Closes page (removes page-level activity)
      2. Stops tracing (writes trace.zip, decrements tracingCount)
      3. Closes context on a daemon thread with a 5s timeout (writes HAR).
        BrowserContextImpl.close() can hang on harExport(NO_TIMEOUT)
        when tracing was active on the same context. The daemon thread
        approach means: if it completes in time, HAR is written; if it
        hangs, the process exits normally and browser.close() cleans up.

   The Allure reporter picks up *trace-path* and *har-path* and attaches
   them to the test result automatically."
  (around [f]
    (let [trace-file (File/createTempFile "pw-trace-" ".zip")
          har-file   (File/createTempFile "pw-har-" ".har")
          ctx        (ensure!
                       (core/new-context *browser*
                         {:record-har-path        (str har-file)
                          :record-har-mode        :full}))
          tracing    (.tracing ^com.microsoft.playwright.BrowserContext ctx)]
            ;; Start tracing with screenshots + DOM snapshots + sources
      (.start tracing (doto (Tracing$StartOptions.)
                        (.setScreenshots true)
                        (.setSnapshots true)
                        (.setSources true)
                        (.setTitle (or allure/*test-title* "spel"))))
      (let [page (core/new-page-from-context ctx)]
        (try
          (binding [*page*              page
                    *browser-context*   ctx
                    *browser-api*       (.request ^com.microsoft.playwright.BrowserContext ctx)
                    allure/*page*       page
                    allure/*tracing*    tracing
                    allure/*trace-path* trace-file
                    allure/*har-path*   har-file]
            (f))
          (finally
                  ;; Close page first (matches Playwright's own test patterns)
            (when (instance? com.microsoft.playwright.Page page)
              (core/close-page! page))
                  ;; Stop tracing → writes trace zip, decrements Connection.tracingCount
            (try (.stop tracing (doto (Tracing$StopOptions.)
                                  (.setPath (.toPath trace-file))))
                 (catch Exception _))
                  ;; Close context → writes HAR via harExport.
                  ;; BrowserContextImpl.close() calls harExport with NO_TIMEOUT,
                  ;; which can hang indefinitely when tracing was active on the
                  ;; same context. Run on a daemon thread with a timeout — if it
                  ;; completes, HAR is written; if it hangs, the daemon dies on
                  ;; JVM exit and browser.close() from with-browser cleans up.
            (let [t (doto (Thread. (fn []
                                     (try (core/close-context! ctx)
                                          (catch Exception _))))
                      (.setDaemon true)
                      (.start))]
              (.join t 5000))))))))

(def with-api-tracing
  "Around hook: creates a BrowserContext with Playwright tracing
   for API-only tests (no page needed).

   Headless by default. Set `SPEL_INTERACTIVE=true` env var or
   `-Dspel.interactive=true` system property to run headed (interactive).

   Requires *pw* to be bound (use with with-playwright).
   Binds:
     *browser-context*   — the BrowserContext (for advanced use)
     *browser-api*       — APIRequestContext from the context; all HTTP calls
                           through this appear in the Playwright trace

   When the Allure reporter is active, also enables tracing and HAR recording:
     allure/*trace-path* — where the trace zip will be written
     allure/*har-path*   — where the HAR file will be written

   When the Allure reporter is NOT active, *browser-api* is still bound
   (backed by a real BrowserContext) so tests work consistently — just
   without trace overhead.

   Usage:
     {:context [with-playwright with-test-server with-api-tracing]}

     (it \"calls API with tracing\"
       (let [resp (api/api-get *browser-api*
                               (str *test-server-url* \"/health\"))]
         (expect (= 200 (api/api-response-status resp)))))"
  (around [f]
    (let [browser (ensure! (core/launch-chromium *pw* {:headless (not (interactive?))}))]
      (try
        (if (allure/reporter-active?)
                ;; Traced mode: HAR + tracing, no screenshots/snapshots (no page)
          (let [trace-file (File/createTempFile "pw-trace-" ".zip")
                har-file   (File/createTempFile "pw-har-" ".har")
                ctx        (ensure!
                             (core/new-context browser
                               {:record-har-path (str har-file)
                                :record-har-mode :full}))
                tracing    (.tracing ^com.microsoft.playwright.BrowserContext ctx)]
            (.start tracing (doto (Tracing$StartOptions.)
                              (.setScreenshots false)
                              (.setSnapshots true)
                              (.setSources true)
                              (.setTitle (or allure/*test-title* "spel"))))
            (try
              (binding [*browser-context*   ctx
                        *browser-api*       (.request ^com.microsoft.playwright.BrowserContext ctx)
                        allure/*tracing*    tracing
                        allure/*trace-path* trace-file
                        allure/*har-path*   har-file]
                (f))
              (finally
                (try (.stop tracing (doto (Tracing$StopOptions.)
                                      (.setPath (.toPath trace-file))))
                     (catch Exception _))
                (let [t (doto (Thread. (fn []
                                         (try (core/close-context! ctx)
                                              (catch Exception _))))
                          (.setDaemon true)
                          (.start))]
                  (.join t 5000)))))
                ;; Non-traced mode: provide *browser-api* without overhead
          (let [ctx (ensure! (core/new-context browser {}))]
            (try
              (binding [*browser-context* ctx
                        *browser-api*     (.request ^com.microsoft.playwright.BrowserContext ctx)]
                (f))
              (finally
                (core/close-context! ctx)))))
        (finally
          (when (instance? com.microsoft.playwright.Browser browser)
            (core/close-browser! browser)))))))


