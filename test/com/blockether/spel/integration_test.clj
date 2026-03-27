(ns com.blockether.spel.integration-test
  "Integration tests against live example.org.

   Tests real HTTP navigation, response inspection, content extraction,
   screenshot capture, locator queries, assertions, and network APIs
   against a stable public website."
  (:require
   [clojure.datafy :refer [datafy]]
   [clojure.string :as str]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   com.blockether.spel.data ;; loads datafy protocol extensions
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [around defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright BrowserContext Locator Response]))

;; =============================================================================
;; Navigation & Response
;; =============================================================================

(defdescribe navigation-integration-test
  "Tests for navigating to example.org and inspecting the response"

  (around [f] (core/with-testing-browser (f)))

  (describe "navigate to example.org"

    (it "returns a Response object"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")]
          (expect (instance? Response resp)))))

    (it "response status is 200"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")]
          (expect (= 200 (net/response-status resp))))))

    (it "response is ok"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")]
          (expect (true? (net/response-ok? resp))))))

    (it "response URL contains example.org"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")]
          (expect (.contains (net/response-url resp) "example.org")))))

    (it "response headers contain content-type"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")
              headers (net/response-headers resp)]
          (expect (some? (get headers "content-type"))))))

    (it "response body is non-empty"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")
              body (net/response-text resp)]
          (expect (string? body))
          (expect (pos? (count body))))))

    (it "response headers is a non-empty map"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")
              headers (net/response-headers resp)]
          (expect (map? headers))
          (expect (pos? (count headers))))))

    (it "response request is a navigation request"
      (core/with-testing-page [pg]
        (let [resp (page/navigate pg "https://example.org")
              req  (net/response-request resp)]
          (expect (true? (net/request-is-navigation? req)))
          (expect (= "GET" (net/request-method req))))))))

;; =============================================================================
;; Page Content & State
;; =============================================================================

(defdescribe page-content-integration-test
  "Tests for page content, title, URL after navigating to example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "page state after navigation"

    (it "page URL is example.org"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (.contains (page/url pg) "example.org"))))

    (it "page title is 'Example Domain'"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (= "Example Domain" (page/title pg)))))

    (it "page content contains expected HTML"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [html (page/content pg)]
          (expect (.contains html "Example Domain"))
          (expect (.contains html "<h1>")))))

    (it "page is not closed"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (false? (page/is-closed? pg)))))

    (it "viewport-size returns dimensions"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [vp (page/viewport-size pg)]
          (expect (pos? (:width vp)))
          (expect (pos? (:height vp))))))

    (it "main-frame is accessible"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [frame (page/main-frame pg)]
          (expect (some? frame))
          (expect (.contains (.url frame) "example.org")))))))

;; =============================================================================
;; Locators
;; =============================================================================

(defdescribe locator-integration-test
  "Tests for locator operations on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "CSS and text locators"

    (it "locates h1 by CSS"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [h1 (page/locator pg "h1")]
          (expect (instance? Locator h1))
          (expect (= "Example Domain" (locator/text-content h1))))))

    (it "locates paragraph text"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [p (locator/first-element (page/locator pg "p"))]
          (expect (locator/is-visible? p))
          (let [text (locator/text-content p)]
            (expect (.contains text "domain"))))))

    (it "locates link by CSS and verifies text"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [link (page/locator pg "a")]
          (expect (locator/is-visible? link))
          (expect (= "Learn more" (locator/text-content link))))))

    (it "locates link by CSS selector"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [a (page/locator pg "a")]
          (expect (= 1 (locator/count-elements a)))
          (let [href (locator/get-attribute a "href")]
            (expect (.contains href "iana.org"))))))

    (it "inner-html returns element markup"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [body (page/locator pg "body")
              html (locator/inner-html body)]
          (expect (.contains html "<h1>"))
          (expect (.contains html "Example Domain")))))

    (it "inner-text returns visible text"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [h1 (page/locator pg "h1")]
          (expect (= "Example Domain" (locator/inner-text h1))))))

    (it "all-text-contents returns vector"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [ps (page/locator pg "p")
              texts (locator/all-text-contents ps)]
          (expect (vector? texts))
          (expect (pos? (count texts)))))))

  (describe "locator state checks"

    (it "h1 is visible"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (true? (locator/is-visible? (page/locator pg "h1"))))))

    (it "h1 is enabled"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (true? (locator/is-enabled? (page/locator pg "h1"))))))

    (it "non-existent element is hidden"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (true? (locator/is-hidden? (page/locator pg "#does-not-exist"))))))

    (it "bounding-box returns dimensions for h1"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [bb (locator/bounding-box (page/locator pg "h1"))]
          (expect (map? bb))
          (expect (pos? (:width bb)))
          (expect (pos? (:height bb))))))))

;; =============================================================================
;; Assertions
;; =============================================================================

(defdescribe assertions-integration-test
  "Tests for Playwright assertions on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "page assertions"

    (it "has-title passes for Example Domain"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/has-title pg "Example Domain")))

    (it "has-url passes for example.org"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/has-url pg #"example\.com"))))

  (describe "locator assertions"

    (it "has-text passes for h1"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/has-text (page/locator pg "h1") "Example Domain")))

    (it "contains-text passes for paragraph"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/contains-text (locator/first-element (page/locator pg "p")) "domain")))

    (it "is-visible passes for h1"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/is-visible (page/locator pg "h1"))))

    (it "is-enabled passes for link"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/is-enabled (page/locator pg "a"))))

    (it "has-attribute passes for link href"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/has-attribute (page/locator pg "a") "href" #"iana\.org")))

    (it "has-count passes for single h1"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (assert/has-count (page/locator pg "h1") 1)))))

;; =============================================================================
;; JavaScript Evaluation
;; =============================================================================

(defdescribe evaluate-integration-test
  "Tests for JavaScript evaluation on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "evaluate on live page"

    (it "reads document.title"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (= "Example Domain" (page/evaluate pg "document.title")))))

    (it "reads DOM element text"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (= "Example Domain"
                  (page/evaluate pg "document.querySelector('h1').textContent")))))

    (it "reads link count"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (expect (= 1 (long (page/evaluate pg "document.querySelectorAll('a').length"))))))))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defdescribe screenshot-integration-test
  "Tests for screenshot capture on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "screenshot of live page"

    (it "captures non-empty screenshot bytes"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [bytes (page/screenshot pg)]
          (expect (bytes? bytes))
                  ;; PNG magic bytes: 0x89504E47
          (expect (= -119 (aget ^bytes bytes 0)))
          (expect (= 80 (aget ^bytes bytes 1)))
          (expect (= 78 (aget ^bytes bytes 2)))
          (expect (= 71 (aget ^bytes bytes 3))))))

    (it "captures full-page screenshot"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [bytes (page/screenshot pg {:full-page true})]
          (expect (bytes? bytes))
          (expect (pos? (alength ^bytes bytes))))))))

;; =============================================================================
;; Datafy Integration
;; =============================================================================

(defdescribe datafy-integration-test
  "Tests for datafy protocols on live objects"

  (around [f] (core/with-testing-browser (f)))

  (describe "datafy on page after navigation"

    (it "datafied page includes url and title"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [d (datafy pg)]
          (expect (map? d))
          (expect (.contains ^String (:page/url d) "example.org"))
          (expect (= "Example Domain" (:page/title d))))))

    (it "datafied browser includes contexts"
      (core/with-testing-page [_]
        (let [d (datafy core/*testing-browser*)]
          (expect (map? d))
          (expect (contains? d :browser/connected?))
          (expect (true? (:browser/connected? d))))))))

;; =============================================================================
;; Wait & Load State
;; =============================================================================

(defdescribe wait-integration-test
  "Tests for wait operations on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "wait-for-load-state"

    (it "waits for load state after navigation"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [result (page/wait-for-load-state pg :load)]
                  ;; Returns nil on success
          (expect (nil? result)))))

    (it "waits for domcontentloaded"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [result (page/wait-for-load-state pg :domcontentloaded)]
          (expect (nil? result))))))

  (describe "wait-for-selector"

    (it "waits for h1 to be visible"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [eh (page/wait-for-selector pg "h1")]
          (expect (some? eh)))))))

;; =============================================================================
;; Context & Multi-page
;; =============================================================================

(defdescribe context-integration-test
  "Tests for browser context operations with live navigation"

  (around [f] (core/with-testing-browser (f)))

  (describe "context with custom options"

    (it "creates context with custom user-agent and navigates"
      (core/with-testing-page [_]
        (let [ctx (core/new-context core/*testing-browser* {:user-agent "PlaywrightClj/Test"})
              pg  (core/new-page-from-context ctx)]
          (try
            (page/navigate pg "https://example.org")
            (let [ua (page/evaluate pg "navigator.userAgent")]
              (expect (= "PlaywrightClj/Test" ua)))
            (finally
              (core/close-page! pg)
              (.close ctx))))))

    (it "creates context with custom viewport"
      (core/with-testing-page [_]
        (let [ctx (core/new-context core/*testing-browser* {:viewport {:width 800 :height 600}})
              pg  (core/new-page-from-context ctx)]
          (try
            (page/navigate pg "https://example.org")
            (let [vp (page/viewport-size pg)]
              (expect (= 800 (:width vp)))
              (expect (= 600 (:height vp))))
            (finally
              (core/close-page! pg)
              (.close ctx))))))))

;; =============================================================================
;; Proxy Configuration
;; =============================================================================

(defdescribe proxy-integration-test
  "Tests that proxy configuration is actually applied to the browser"

  (around [f] (core/with-testing-browser (f)))

  (describe "launch with proxy"

    (it "proxy config routes traffic through proxy (dead proxy causes navigation failure)"
      (core/with-testing-page [_]
        ;; Launch a browser configured to use a non-existent proxy.
      ;; If proxy config is correctly applied, navigation MUST fail because the
      ;; proxy server doesn't exist. If proxy config was silently ignored (the
      ;; old bug), navigation would succeed — and this test would catch that.
        (let [browser (core/launch-chromium core/*testing-pw* {:headless true
                                                               :proxy {:server "http://127.0.0.1:19999"
                                                                       :bypass ""}})
              ctx     (core/new-context browser)
              pg      (core/new-page-from-context ctx)]
          (try
            (let [result (page/navigate pg "http://example.org"
                           {:timeout 5000})]
            ;; Navigation through dead proxy should return an anomaly
              (expect (core/anomaly? result)))
            (finally
              (core/close-page! pg)
              (.close ^BrowserContext ctx)
              (core/close-browser! browser))))))

    (it "proxy bypass allows direct access for bypassed hosts"
      (core/with-testing-page [_]
        ;; Launch with proxy pointing to dead server but bypass example.org.
      ;; Navigation to example.org should succeed because it's bypassed.
      ;; Use <-loopback> to bypass the proxy for all loopback and also
      ;; specify the target domain. Use HTTP to avoid TLS complications.
        (let [browser (core/launch-chromium core/*testing-pw* {:headless true
                                                               :proxy {:server "http://127.0.0.1:19999"
                                                                       :bypass ".example.org,example.org"}})
              ctx     (core/new-context browser)
              pg      (core/new-page-from-context ctx)]
          (try
            (let [result (page/navigate pg "http://example.org"
                           {:timeout 10000})]
            ;; Bypassed host should navigate successfully
              (expect (instance? Response result))
              (expect (= "Example Domain" (page/title pg))))
            (finally
              (core/close-page! pg)
              (.close ^BrowserContext ctx)
              (core/close-browser! browser))))))))

;; =============================================================================
;; Persistent Context (Chrome Profile)
;; =============================================================================

(defdescribe persistent-context-integration-test
  "Tests for launch-persistent-context (real Chrome profile directory)"

  (around [f] (core/with-testing-browser (f)))

  (describe "persistent context lifecycle"

    (it "launches with persistent context and navigates"
      (core/with-testing-page [_]
        (let [profile-dir (str (System/getProperty "java.io.tmpdir") "/spel-profile-test-"
                            (System/currentTimeMillis))
              context (core/launch-persistent-context
                        (core/chromium core/*testing-pw*)
                        profile-dir
                        {:headless true})
              pg (if (seq (.pages ^BrowserContext context))
                   (first (.pages ^BrowserContext context))
                   (core/new-page-from-context context))]
          (try
            (page/navigate pg "https://example.org")
            (expect (= "Example Domain" (page/title pg)))
          ;; Verify we can get the browser from the context
            (expect (some? (.browser ^BrowserContext context)))
            (finally
              (.close ^BrowserContext context))))))

    (it "persistent context preserves data across sessions"
      (core/with-testing-page [_]
        (let [profile-dir (str (System/getProperty "java.io.tmpdir") "/spel-profile-persist-"
                            (System/currentTimeMillis))]
        ;; Session 1: set a cookie
          (let [ctx1 (core/launch-persistent-context
                       (core/chromium core/*testing-pw*) profile-dir {:headless true})
                pg1  (if (seq (.pages ^BrowserContext ctx1))
                       (first (.pages ^BrowserContext ctx1))
                       (core/new-page-from-context ctx1))]
            (try
              (page/navigate pg1 "https://example.org")
              (page/evaluate pg1 "document.cookie = 'profile_test=persisted; path=/; max-age=3600'")
              (finally
                (.close ^BrowserContext ctx1))))
        ;; Session 2: verify cookie survived
          (let [ctx2 (core/launch-persistent-context
                       (core/chromium core/*testing-pw*) profile-dir {:headless true})
                pg2  (if (seq (.pages ^BrowserContext ctx2))
                       (first (.pages ^BrowserContext ctx2))
                       (core/new-page-from-context ctx2))]
            (try
              (page/navigate pg2 "https://example.org")
              (let [cookie (page/evaluate pg2 "document.cookie")]
                (expect (str/includes? (str cookie) "profile_test=persisted")))
              (finally
                (.close ^BrowserContext ctx2)))))))))

;; =============================================================================
;; Reload & Navigation History
;; =============================================================================

(defdescribe reload-integration-test
  "Tests for reload and navigation history on example.org"

  (around [f] (core/with-testing-browser (f)))

  (describe "reload"

    (it "reloads the page successfully"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [resp (page/reload pg)]
          (expect (instance? Response resp))
          (expect (= 200 (net/response-status resp)))
          (expect (= "Example Domain" (page/title pg))))))))

;; =============================================================================
;; with-page / with-traced-page context-opts
;; =============================================================================

(defdescribe fixture-context-opts-test
  "Tests that with-page and with-traced-page accept context-opts"

  (around [f] (core/with-testing-browser (f)))

  (describe "with-page-opts accepts context-opts"

    (it "applies viewport from context-opts"
      (core/with-testing-page {:viewport {:width 375 :height 812}} [pg]
        (page/navigate pg "https://example.org")
        (let [size (page/viewport-size pg)]
          (expect (= 375 (:width size)))
          (expect (= 812 (:height size))))))

    (it "binds (.context pg)"
      (core/with-testing-page [pg]
        (expect (instance? BrowserContext (.context pg))))))

  (describe "with-page without opts still works"

    (it "creates a page with default viewport"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [size (page/viewport-size pg)]
          (expect (pos? (:width size)))
          (expect (pos? (:height size)))))))

  (describe "with-traced-page-opts accepts context-opts"

    (it "applies viewport from context-opts"
      (core/with-testing-page {:viewport {:width 320 :height 568}} [pg]
        (page/navigate pg "https://example.org")
        (let [size (page/viewport-size pg)]
          (expect (= 320 (:width size)))
          (expect (= 568 (:height size))))))

    (it "binds (.context pg)"
      (core/with-testing-page [pg]
        (expect (instance? BrowserContext (.context pg))))))

  (describe "with-traced-page without opts still works"

    (it "creates a page with default viewport"
      (core/with-testing-page [pg]
        (page/navigate pg "https://example.org")
        (let [size (page/viewport-size pg)]
          (expect (pos? (:width size)))
          (expect (pos? (:height size)))))))

  (describe "with-page-opts locale context-opt"

    (it "applies locale to the context"
      (core/with-testing-page {:locale "fr-FR"} [pg]
        (page/navigate pg "https://example.org")
        ;; Verify locale was set by checking navigator.language via JS eval
        (let [lang (page/evaluate pg "navigator.language")]
          (expect (= "fr-FR" lang)))))))
