(ns com.blockether.spel.integration-test
  "Integration tests against live example.com.

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
   [com.blockether.spel.test-fixtures :refer [*pw* *browser* *page*
                                              with-browser with-page with-playwright]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright BrowserContext Locator Response]))

;; =============================================================================
;; Navigation & Response
;; =============================================================================

(defdescribe navigation-integration-test
  "Tests for navigating to example.com and inspecting the response"

  (describe "navigate to example.com"
    {:context [with-playwright with-browser with-page]}

    (it "returns a Response object"
      (let [resp (page/navigate *page* "https://example.com")]
        (expect (instance? Response resp))))

    (it "response status is 200"
      (let [resp (page/navigate *page* "https://example.com")]
        (expect (= 200 (net/response-status resp)))))

    (it "response is ok"
      (let [resp (page/navigate *page* "https://example.com")]
        (expect (true? (net/response-ok? resp)))))

    (it "response URL contains example.com"
      (let [resp (page/navigate *page* "https://example.com")]
        (expect (.contains (net/response-url resp) "example.com"))))

    (it "response headers contain content-type"
      (let [resp (page/navigate *page* "https://example.com")
            headers (net/response-headers resp)]
        (expect (some? (get headers "content-type")))))

    (it "response body is non-empty"
      (let [resp (page/navigate *page* "https://example.com")
            body (net/response-text resp)]
        (expect (string? body))
        (expect (pos? (count body)))))

    (it "response headers is a non-empty map"
      (let [resp (page/navigate *page* "https://example.com")
            headers (net/response-headers resp)]
        (expect (map? headers))
        (expect (pos? (count headers)))))

    (it "response request is a navigation request"
      (let [resp (page/navigate *page* "https://example.com")
            req  (net/response-request resp)]
        (expect (true? (net/request-is-navigation? req)))
        (expect (= "GET" (net/request-method req)))))))

;; =============================================================================
;; Page Content & State
;; =============================================================================

(defdescribe page-content-integration-test
  "Tests for page content, title, URL after navigating to example.com"

  (describe "page state after navigation"
    {:context [with-playwright with-browser with-page]}

    (it "page URL is example.com"
      (page/navigate *page* "https://example.com")
      (expect (.contains (page/url *page*) "example.com")))

    (it "page title is 'Example Domain'"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*))))

    (it "page content contains expected HTML"
      (page/navigate *page* "https://example.com")
      (let [html (page/content *page*)]
        (expect (.contains html "Example Domain"))
        (expect (.contains html "<h1>"))))

    (it "page is not closed"
      (page/navigate *page* "https://example.com")
      (expect (false? (page/is-closed? *page*))))

    (it "viewport-size returns dimensions"
      (page/navigate *page* "https://example.com")
      (let [vp (page/viewport-size *page*)]
        (expect (pos? (:width vp)))
        (expect (pos? (:height vp)))))

    (it "main-frame is accessible"
      (page/navigate *page* "https://example.com")
      (let [frame (page/main-frame *page*)]
        (expect (some? frame))
        (expect (.contains (.url frame) "example.com"))))))

;; =============================================================================
;; Locators
;; =============================================================================

(defdescribe locator-integration-test
  "Tests for locator operations on example.com"

  (describe "CSS and text locators"
    {:context [with-playwright with-browser with-page]}

    (it "locates h1 by CSS"
      (page/navigate *page* "https://example.com")
      (let [h1 (page/locator *page* "h1")]
        (expect (instance? Locator h1))
        (expect (= "Example Domain" (locator/text-content h1)))))

    (it "locates paragraph text"
      (page/navigate *page* "https://example.com")
      (let [p (locator/first-element (page/locator *page* "p"))]
        (expect (locator/is-visible? p))
        (let [text (locator/text-content p)]
          (expect (.contains text "domain")))))

    (it "locates link by CSS and verifies text"
      (page/navigate *page* "https://example.com")
      (let [link (page/locator *page* "a")]
        (expect (locator/is-visible? link))
        (expect (= "Learn more" (locator/text-content link)))))

    (it "locates link by CSS selector"
      (page/navigate *page* "https://example.com")
      (let [a (page/locator *page* "a")]
        (expect (= 1 (locator/count-elements a)))
        (let [href (locator/get-attribute a "href")]
          (expect (.contains href "iana.org")))))

    (it "inner-html returns element markup"
      (page/navigate *page* "https://example.com")
      (let [body (page/locator *page* "body")
            html (locator/inner-html body)]
        (expect (.contains html "<h1>"))
        (expect (.contains html "Example Domain"))))

    (it "inner-text returns visible text"
      (page/navigate *page* "https://example.com")
      (let [h1 (page/locator *page* "h1")]
        (expect (= "Example Domain" (locator/inner-text h1)))))

    (it "all-text-contents returns vector"
      (page/navigate *page* "https://example.com")
      (let [ps (page/locator *page* "p")
            texts (locator/all-text-contents ps)]
        (expect (vector? texts))
        (expect (pos? (count texts))))))

  (describe "locator state checks"
    {:context [with-playwright with-browser with-page]}

    (it "h1 is visible"
      (page/navigate *page* "https://example.com")
      (expect (true? (locator/is-visible? (page/locator *page* "h1")))))

    (it "h1 is enabled"
      (page/navigate *page* "https://example.com")
      (expect (true? (locator/is-enabled? (page/locator *page* "h1")))))

    (it "non-existent element is hidden"
      (page/navigate *page* "https://example.com")
      (expect (true? (locator/is-hidden? (page/locator *page* "#does-not-exist")))))

    (it "bounding-box returns dimensions for h1"
      (page/navigate *page* "https://example.com")
      (let [bb (locator/bounding-box (page/locator *page* "h1"))]
        (expect (map? bb))
        (expect (pos? (:width bb)))
        (expect (pos? (:height bb)))))))

;; =============================================================================
;; Assertions
;; =============================================================================

(defdescribe assertions-integration-test
  "Tests for Playwright assertions on example.com"

  (describe "page assertions"
    {:context [with-playwright with-browser with-page]}

    (it "has-title passes for Example Domain"
      (page/navigate *page* "https://example.com")
      (assert/has-title *page* "Example Domain"))

    (it "has-url passes for example.com"
      (page/navigate *page* "https://example.com")
      (assert/has-url *page* #"example\.com")))

  (describe "locator assertions"
    {:context [with-playwright with-browser with-page]}

    (it "has-text passes for h1"
      (page/navigate *page* "https://example.com")
      (assert/has-text (page/locator *page* "h1") "Example Domain"))

    (it "contains-text passes for paragraph"
      (page/navigate *page* "https://example.com")
      (assert/contains-text (locator/first-element (page/locator *page* "p")) "domain"))

    (it "is-visible passes for h1"
      (page/navigate *page* "https://example.com")
      (assert/is-visible (page/locator *page* "h1")))

    (it "is-enabled passes for link"
      (page/navigate *page* "https://example.com")
      (assert/is-enabled (page/locator *page* "a")))

    (it "has-attribute passes for link href"
      (page/navigate *page* "https://example.com")
      (assert/has-attribute (page/locator *page* "a") "href" #"iana\.org"))

    (it "has-count passes for single h1"
      (page/navigate *page* "https://example.com")
      (assert/has-count (page/locator *page* "h1") 1))))

;; =============================================================================
;; JavaScript Evaluation
;; =============================================================================

(defdescribe evaluate-integration-test
  "Tests for JavaScript evaluation on example.com"

  (describe "evaluate on live page"
    {:context [with-playwright with-browser with-page]}

    (it "reads document.title"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/evaluate *page* "document.title"))))

    (it "reads DOM element text"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain"
                (page/evaluate *page* "document.querySelector('h1').textContent"))))

    (it "reads link count"
      (page/navigate *page* "https://example.com")
      (expect (= 1 (long (page/evaluate *page* "document.querySelectorAll('a').length")))))))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defdescribe screenshot-integration-test
  "Tests for screenshot capture on example.com"

  (describe "screenshot of live page"
    {:context [with-playwright with-browser with-page]}

    (it "captures non-empty screenshot bytes"
      (page/navigate *page* "https://example.com")
      (let [bytes (page/screenshot *page*)]
        (expect (bytes? bytes))
                  ;; PNG magic bytes: 0x89504E47
        (expect (= -119 (aget ^bytes bytes 0)))
        (expect (= 80 (aget ^bytes bytes 1)))
        (expect (= 78 (aget ^bytes bytes 2)))
        (expect (= 71 (aget ^bytes bytes 3)))))

    (it "captures full-page screenshot"
      (page/navigate *page* "https://example.com")
      (let [bytes (page/screenshot *page* {:full-page true})]
        (expect (bytes? bytes))
        (expect (pos? (alength ^bytes bytes)))))))

;; =============================================================================
;; Datafy Integration
;; =============================================================================

(defdescribe datafy-integration-test
  "Tests for datafy protocols on live objects"

  (describe "datafy on page after navigation"
    {:context [with-playwright with-browser with-page]}

    (it "datafied page includes url and title"
      (page/navigate *page* "https://example.com")
      (let [d (datafy *page*)]
        (expect (map? d))
        (expect (.contains ^String (:page/url d) "example.com"))
        (expect (= "Example Domain" (:page/title d)))))

    (it "datafied browser includes contexts"
      (let [d (datafy *browser*)]
        (expect (map? d))
        (expect (contains? d :browser/connected?))
        (expect (true? (:browser/connected? d)))))))

;; =============================================================================
;; Wait & Load State
;; =============================================================================

(defdescribe wait-integration-test
  "Tests for wait operations on example.com"

  (describe "wait-for-load-state"
    {:context [with-playwright with-browser with-page]}

    (it "waits for load state after navigation"
      (page/navigate *page* "https://example.com")
      (let [result (page/wait-for-load-state *page* :load)]
                  ;; Returns nil on success
        (expect (nil? result))))

    (it "waits for domcontentloaded"
      (page/navigate *page* "https://example.com")
      (let [result (page/wait-for-load-state *page* :domcontentloaded)]
        (expect (nil? result)))))

  (describe "wait-for-selector"
    {:context [with-playwright with-browser with-page]}

    (it "waits for h1 to be visible"
      (page/navigate *page* "https://example.com")
      (let [eh (page/wait-for-selector *page* "h1")]
        (expect (some? eh))))))

;; =============================================================================
;; Context & Multi-page
;; =============================================================================

(defdescribe context-integration-test
  "Tests for browser context operations with live navigation"

  (describe "context with custom options"
    {:context [with-playwright with-browser]}

    (it "creates context with custom user-agent and navigates"
      (let [ctx (core/new-context *browser* {:user-agent "PlaywrightClj/Test"})
            pg  (core/new-page-from-context ctx)]
        (try
          (page/navigate pg "https://example.com")
          (let [ua (page/evaluate pg "navigator.userAgent")]
            (expect (= "PlaywrightClj/Test" ua)))
          (finally
            (core/close-page! pg)
            (.close ctx)))))

    (it "creates context with custom viewport"
      (let [ctx (core/new-context *browser* {:viewport {:width 800 :height 600}})
            pg  (core/new-page-from-context ctx)]
        (try
          (page/navigate pg "https://example.com")
          (let [vp (page/viewport-size pg)]
            (expect (= 800 (:width vp)))
            (expect (= 600 (:height vp))))
          (finally
            (core/close-page! pg)
            (.close ctx)))))))

;; =============================================================================
;; Proxy Configuration
;; =============================================================================

(defdescribe proxy-integration-test
  "Tests that proxy configuration is actually applied to the browser"

  (describe "launch with proxy"
    {:context [with-playwright]}

    (it "proxy config routes traffic through proxy (dead proxy causes navigation failure)"
      ;; Launch a browser configured to use a non-existent proxy.
      ;; If proxy config is correctly applied, navigation MUST fail because the
      ;; proxy server doesn't exist. If proxy config was silently ignored (the
      ;; old bug), navigation would succeed — and this test would catch that.
      (let [browser (core/launch-chromium *pw* {:headless true
                                                :proxy {:server "http://127.0.0.1:19999"
                                                        :bypass ""}})
            ctx     (core/new-context browser)
            pg      (core/new-page-from-context ctx)]
        (try
          (let [result (page/navigate pg "http://example.com"
                         {:timeout 5000})]
            ;; Navigation through dead proxy should return an anomaly
            (expect (core/anomaly? result)))
          (finally
            (core/close-page! pg)
            (.close ^BrowserContext ctx)
            (core/close-browser! browser)))))

    (it "proxy bypass allows direct access for bypassed hosts"
      ;; Launch with proxy pointing to dead server but bypass example.com.
      ;; Navigation to example.com should succeed because it's bypassed.
      ;; Use <-loopback> to bypass the proxy for all loopback and also
      ;; specify the target domain. Use HTTP to avoid TLS complications.
      (let [browser (core/launch-chromium *pw* {:headless true
                                                :proxy {:server "http://127.0.0.1:19999"
                                                        :bypass ".example.com,example.com"}})
            ctx     (core/new-context browser)
            pg      (core/new-page-from-context ctx)]
        (try
          (let [result (page/navigate pg "http://example.com"
                         {:timeout 10000})]
            ;; Bypassed host should navigate successfully
            (expect (instance? Response result))
            (expect (= "Example Domain" (page/title pg))))
          (finally
            (core/close-page! pg)
            (.close ^BrowserContext ctx)
            (core/close-browser! browser)))))))

;; =============================================================================
;; Persistent Context (Chrome Profile)
;; =============================================================================

(defdescribe persistent-context-integration-test
  "Tests for launch-persistent-context (real Chrome profile directory)"

  (describe "persistent context lifecycle"
    {:context [with-playwright]}

    (it "launches with persistent context and navigates"
      (let [profile-dir (str (System/getProperty "java.io.tmpdir") "/spel-profile-test-"
                          (System/currentTimeMillis))
            context (core/launch-persistent-context
                      (core/chromium *pw*)
                      profile-dir
                      {:headless true})
            pg (if (seq (.pages ^BrowserContext context))
                 (first (.pages ^BrowserContext context))
                 (core/new-page-from-context context))]
        (try
          (page/navigate pg "https://example.com")
          (expect (= "Example Domain" (page/title pg)))
          ;; Verify we can get the browser from the context
          (expect (some? (.browser ^BrowserContext context)))
          (finally
            (.close ^BrowserContext context)))))

    (it "persistent context preserves data across sessions"
      (let [profile-dir (str (System/getProperty "java.io.tmpdir") "/spel-profile-persist-"
                          (System/currentTimeMillis))]
        ;; Session 1: set a cookie
        (let [ctx1 (core/launch-persistent-context
                     (core/chromium *pw*) profile-dir {:headless true})
              pg1  (if (seq (.pages ^BrowserContext ctx1))
                     (first (.pages ^BrowserContext ctx1))
                     (core/new-page-from-context ctx1))]
          (try
            (page/navigate pg1 "https://example.com")
            (page/evaluate pg1 "document.cookie = 'profile_test=persisted; path=/; max-age=3600'")
            (finally
              (.close ^BrowserContext ctx1))))
        ;; Session 2: verify cookie survived
        (let [ctx2 (core/launch-persistent-context
                     (core/chromium *pw*) profile-dir {:headless true})
              pg2  (if (seq (.pages ^BrowserContext ctx2))
                     (first (.pages ^BrowserContext ctx2))
                     (core/new-page-from-context ctx2))]
          (try
            (page/navigate pg2 "https://example.com")
            (let [cookie (page/evaluate pg2 "document.cookie")]
              (expect (str/includes? (str cookie) "profile_test=persisted")))
            (finally
              (.close ^BrowserContext ctx2))))))))

;; =============================================================================
;; Reload & Navigation History
;; =============================================================================

(defdescribe reload-integration-test
  "Tests for reload and navigation history on example.com"

  (describe "reload"
    {:context [with-playwright with-browser with-page]}

    (it "reloads the page successfully"
      (page/navigate *page* "https://example.com")
      (let [resp (page/reload *page*)]
        (expect (instance? Response resp))
        (expect (= 200 (net/response-status resp)))
        (expect (= "Example Domain" (page/title *page*)))))))
