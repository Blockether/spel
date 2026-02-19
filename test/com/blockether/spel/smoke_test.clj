(ns com.blockether.spel.smoke-test
  "End-to-end smoke tests against example.com.

   Showcases the Allure in-test API with epics, features, stories, steps,
   screenshots, parameters, attachments, and links. Run with the Allure
   reporter for rich HTML output:

     clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                              with-browser
                                              with-traced-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright Response]))

;; =============================================================================
;; Example.com — Smoke Tests
;; =============================================================================

(defdescribe example-com-navigation-smoke-test
  "Smoke: example.com navigation and HTTP response"

  (describe "HTTP response inspection"
    {:context [with-playwright with-browser with-traced-page]}

    (it "returns HTTP 200 with valid headers"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Navigation")
      (allure/severity :blocker)
      (allure/owner "spel")
      (allure/tag "smoke")
      (allure/tag "example.com")
      (allure/link "Site" "https://example.com")
      (allure/description
        "Navigates to example.com and validates HTTP response status,
        headers, URL, and body content. This is the most basic smoke
        test — if this fails, the internet is down.")

      (allure/step "Navigate to example.com"
        (println "[navigate] Starting navigation to example.com")
        (let [resp (page/navigate *page* "https://example.com")]
          (println "[navigate] Navigation complete, response received")
          (allure/parameter "url" "https://example.com")
          (expect (instance? Response resp))

          (allure/step "Verify HTTP status 200"
            (let [status (net/response-status resp)]
              (println "[status] HTTP status:" status)
              (allure/parameter "status" status)
              (expect (= 200 status))))

          (allure/step "Verify response OK"
            (expect (true? (net/response-ok? resp))))

          (allure/step "Verify response URL"
            (let [url (net/response-url resp)]
              (println "[url] Response URL:" url)
              (allure/parameter "response-url" url)
              (expect (.contains ^String url "example.com"))))

          (allure/step "Verify content-type header"
            (let [headers (net/response-headers resp)
                  ct (get headers "content-type")]
              (println "[headers] Content-Type:" ct)
              (allure/parameter "content-type" ct)
              (expect (some? ct))
              (expect (.contains ^String ct "text/html"))))

          (allure/step "Verify response body is non-empty"
            (let [body (net/response-text resp)]
              (println "[body] Body length:" (count body) "chars")
              (allure/parameter "body-length" (count body))
              (expect (pos? (count body)))))))

      (allure/step "Screenshot after navigation"
        (allure/screenshot *page* "example.com loaded")))))

(defdescribe example-com-content-smoke-test
  "Smoke: example.com page content and DOM structure"

  (describe "page title, content, and structure"
    {:context [with-playwright with-browser with-traced-page]}

    (it "has correct title and heading"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Content Verification")
      (allure/severity :critical)
      (allure/tag "smoke")
      (allure/tag "example.com")

      (allure/step "Navigate"
        (println "Loading example.com...")
        (page/navigate *page* "https://example.com")
        (println "Page loaded successfully"))

      (allure/step "Verify page title"
        (let [title (page/title *page*)]
          (println "Page title:" (pr-str title))
          (allure/parameter "title" title)
          (expect (= "Example Domain" title))))

      (allure/step "Verify page URL"
        (let [url (page/url *page*)]
          (println "Current URL:" url)
          (allure/parameter "url" url)
          (expect (.contains ^String url "example.com"))))

      (allure/step "Verify H1 heading"
        (let [h1 (page/locator *page* "h1")
              text (locator/text-content h1)]
          (println "H1 text:" (pr-str text))
          (println "H1 visible?" (locator/is-visible? h1))
          (allure/parameter "h1.text" text)
          (expect (= "Example Domain" text))
          (expect (locator/is-visible? h1))))

      (allure/step "Verify paragraph text mentions 'domain'"
        (let [p (locator/first-element (page/locator *page* "p"))
              text (locator/text-content p)]
          (println "Paragraph text:" (subs text 0 (min 60 (count text))) "...")
          (allure/parameter "p.text" text)
          (expect (.contains ^String text "domain")))))

    (it "has a 'Learn more' link to IANA"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Content Verification")
      (allure/severity :normal)
      (allure/tag "smoke")

      (allure/step "Navigate"
        (page/navigate *page* "https://example.com"))

      (allure/step "Find the link"
        (let [link (page/locator *page* "a")]
          (allure/step "Verify link text"
            (let [text (locator/text-content link)]
              (allure/parameter "link.text" text)
              (expect (.contains ^String text "Learn more"))))

          (allure/step "Verify link href points to IANA"
            (let [href (locator/get-attribute link "href")]
              (allure/parameter "link.href" href)
              (expect (.contains ^String href "iana.org"))))

          (allure/step "Verify link is visible and enabled"
            (expect (locator/is-visible? link))
            (expect (locator/is-enabled? link)))

          (allure/step "Verify exactly 1 link on the page"
            (let [n (locator/count-elements link)]
              (allure/parameter "link.count" n)
              (expect (= 1 n))))))

      (allure/step "Screenshot of page with link"
        (allure/screenshot *page* "example.com link")))))

(defdescribe example-com-assertions-smoke-test
  "Smoke: Playwright assertions against example.com"

  (describe "Playwright built-in assertions"
    {:context [with-playwright with-browser with-traced-page]}

    (it "passes all Playwright assertions"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Playwright Assertions")
      (allure/severity :critical)
      (allure/tag "smoke")
      (allure/tag "assertions")

      (allure/step "Navigate"
        (println "Opening example.com for assertion tests...")
        (page/navigate *page* "https://example.com")
        (println "Ready for assertions"))

      (allure/step "Page assertions"
        (println "Running page-level Playwright assertions...")
        (allure/step "has-title matches 'Example Domain'"
          (println "Asserting title = 'Example Domain'")
          (assert/has-title *page* "Example Domain"))

        (allure/step "has-url contains 'example.com'"
          (println "Asserting URL contains 'example.com'")
          (assert/has-url *page* "example.com" {:substring true}))
        (println "All page assertions passed"))

      (allure/step "Locator assertions on H1"
        (let [h1 (page/locator *page* "h1")]
          (println "Locator: h1 — running 4 assertions")
          (allure/step "has-text 'Example Domain'"
            (assert/has-text h1 "Example Domain"))

          (allure/step "is-visible"
            (assert/is-visible h1))

          (allure/step "is-enabled"
            (assert/is-enabled h1))

          (allure/step "has-count 1"
            (assert/has-count h1 1))
          (println "All H1 assertions passed ✓")))

      (allure/step "Locator assertions on link"
        (let [link (page/locator *page* "a")]
          (println "Locator: a — checking text and href")
          (allure/step "contains-text 'Learn more'"
            (assert/contains-text link "Learn more"))

          (allure/step "has-attribute href containing 'iana.org'"
            (assert/has-attribute link "href" "https://iana.org/domains/example"))
          (println "Link assertions passed ✓")))

      (allure/step "Negated assertions"
        (let [ghost (page/locator *page* "#does-not-exist")
              la    (assert/assert-that ghost)
              neg   (assert/loc-not la)]
          (println "Testing negated assertion: #does-not-exist should NOT be visible")
          (allure/step "not is-visible for non-existent element"
            (assert/is-visible neg))
          (println "Negated assertion passed ✓"))))))

;; =============================================================================
;; Example.com — JavaScript & Screenshot
;; =============================================================================

(defdescribe example-com-evaluate-smoke-test
  "Smoke: JavaScript evaluation and screenshot capture"

  (describe "evaluate and screenshot"
    {:context [with-playwright with-browser with-traced-page]}

    (it "evaluates JS and captures full-page screenshot"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "JS Evaluation & Screenshots")
      (allure/severity :normal)
      (allure/tag "smoke")
      (allure/tag "javascript")

      (allure/step "Navigate"
        (println "Loading example.com for JS evaluation tests...")
        (page/navigate *page* "https://example.com"))

      (allure/step "Read document.title via JS"
        (let [title (page/evaluate *page* "document.title")]
          (println "document.title =" (pr-str title))
          (allure/parameter "document.title" title)
          (expect (= "Example Domain" title))))

      (allure/step "Read heading text via JS"
        (let [text (page/evaluate *page* "document.querySelector('h1').textContent")]
          (println "h1.textContent =" (pr-str text))
          (allure/parameter "h1.textContent" text)
          (expect (= "Example Domain" text))))

      (allure/step "Count links via JS"
        (let [count (page/evaluate *page* "document.querySelectorAll('a').length")]
          (println "Found" count "link(s) on page")
          (allure/parameter "link-count" count)
          (expect (= 1 count))))

      (allure/step "Read viewport dimensions"
        (let [vp (page/viewport-size *page*)]
          (println "Viewport:" (:width vp) "x" (:height vp))
          (allure/parameter "viewport.width" (:width vp))
          (allure/parameter "viewport.height" (:height vp))
          (expect (pos? (:width vp)))
          (expect (pos? (:height vp)))))

      (allure/step "Capture full-page screenshot"
        (println "Taking full-page screenshot...")
        (allure/screenshot *page* "example.com full page"))

      (allure/step "Attach page HTML as text"
        (let [html (page/content *page*)]
          (println "Page HTML size:" (count html) "chars")
          (allure/attach "Page HTML" html "text/html"))))))

;; =============================================================================
;; Example.com — Accessibility Snapshots & Ref-Based Interaction
;; =============================================================================

(defdescribe example-com-snapshot-smoke-test
  "Smoke: accessibility snapshots and ref-based interaction on example.com"

  (describe "capture snapshot and interact via refs"
    {:context [with-playwright with-browser with-traced-page]}

    (it "captures accessibility tree with numbered refs"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Accessibility Snapshots")
      (allure/severity :critical)
      (allure/owner "spel")
      (allure/tag "smoke")
      (allure/tag "snapshot")
      (allure/link "Site" "https://example.com")
      (allure/description
        "Captures the accessibility snapshot of example.com, verifies the
        tree structure contains expected elements with refs, and resolves
        refs back to Playwright locators for interaction.")

      (allure/step "Navigate to example.com"
        (page/navigate *page* "https://example.com")
        (page/wait-for-load-state *page* "load"))

      (allure/step "Capture accessibility snapshot"
        (let [snap (snapshot/capture-snapshot *page*)]
          (allure/parameter "ref-count" (:counter snap))
          (allure/attach "Accessibility Tree" (:tree snap) "text/plain")

          (allure/step "Verify snapshot structure"
            (expect (map? snap))
            (expect (string? (:tree snap)))
            (expect (map? (:refs snap)))
            (expect (pos? (:counter snap))))

          (allure/step "Tree contains heading 'Example Domain'"
            (expect (.contains ^String (:tree snap) "Example Domain")))

          (allure/step "Tree contains a link element"
            (expect (.contains ^String (:tree snap) "link")))

          (allure/step "Refs map has entries"
            (let [ref-count (count (:refs snap))]
              (println "Snapshot has" ref-count "refs")
              (allure/parameter "refs" ref-count)
              (expect (pos? ref-count))))))

      (allure/step "Screenshot with snapshot overlay"
        (allure/screenshot *page* "example.com accessibility")))

    (it "resolves snapshot refs to working locators"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Accessibility Snapshots")
      (allure/severity :critical)
      (allure/tag "smoke")
      (allure/tag "snapshot")

      (allure/step "Navigate"
        (page/navigate *page* "https://example.com"))

      (allure/step "Capture snapshot and resolve refs"
        (let [snap (snapshot/capture-snapshot *page*)
              refs (:refs snap)]

          (allure/step "Find the heading ref"
            (let [heading-entry (first (filter (fn [[_ v]] (= "heading" (:role v)))
                                         refs))
                  heading-ref   (first heading-entry)]
              (println "Heading ref:" heading-ref)
              (allure/parameter "heading-ref" heading-ref)
              (expect (some? heading-ref))

              (allure/step "Resolve ref to locator and read text"
                (let [loc  (snapshot/resolve-ref *page* heading-ref)
                      text (locator/text-content loc)]
                  (println "Resolved heading text:" (pr-str text))
                  (allure/parameter "heading-text" text)
                  (expect (= "Example Domain" text))
                  (expect (locator/is-visible? loc))))))

          (allure/step "Find the link ref"
            (let [link-entry (first (filter (fn [[_ v]] (= "link" (:role v)))
                                      refs))
                  link-ref   (first link-entry)]
              (println "Link ref:" link-ref)
              (allure/parameter "link-ref" link-ref)
              (expect (some? link-ref))

              (allure/step "Resolve ref to locator and verify href"
                (let [loc  (snapshot/resolve-ref *page* link-ref)
                      href (locator/get-attribute loc "href")]
                  (println "Resolved link href:" href)
                  (allure/parameter "link-href" href)
                  (expect (.contains ^String href "iana.org"))
                  (expect (locator/is-visible? loc)))))))))))

;; =============================================================================
;; Example.com — Navigation Flow & History
;; =============================================================================

(defdescribe example-com-navigation-flow-smoke-test
  "Smoke: navigation flow — click link, go back, go forward"

  (describe "full navigation cycle"
    {:context [with-playwright with-browser with-traced-page]}

    (it "clicks the IANA link, navigates away, and returns via back/forward"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Navigation Flow")
      (allure/severity :critical)
      (allure/tag "smoke")
      (allure/tag "navigation")
      (allure/description
        "Tests the full navigation lifecycle: load page → click outbound
        link → verify new page → go back → verify original page → go
        forward → verify we're back on the target page.")

      (allure/step "Navigate to example.com"
        (page/navigate *page* "https://example.com")
        (assert/has-title *page* "Example Domain")
        (allure/screenshot *page* "Step 1: example.com loaded"))

      (allure/step "Click the 'Learn more' link"
        (let [link (page/get-by-text *page* "Learn more")]
          (assert/is-visible link)
          (locator/click link)
          (page/wait-for-load-state *page* "domcontentloaded")))

      (allure/step "Verify navigated to IANA"
        (let [url (page/url *page*)]
          (println "After click URL:" url)
          (allure/parameter "iana-url" url)
          (expect (.contains ^String url "iana.org"))
          (allure/screenshot *page* "Step 2: IANA page")))

      (allure/step "Go back to example.com"
        (page/go-back *page*)
        (page/wait-for-load-state *page* "domcontentloaded")
        (let [url (page/url *page*)]
          (println "After back URL:" url)
          (allure/parameter "back-url" url)
          (expect (.contains ^String url "example.com")))
        (assert/has-title *page* "Example Domain")
        (allure/screenshot *page* "Step 3: back to example.com"))

      (allure/step "Go forward to IANA again"
        (page/go-forward *page*)
        (page/wait-for-load-state *page* "domcontentloaded")
        (let [url (page/url *page*)]
          (println "After forward URL:" url)
          (allure/parameter "forward-url" url)
          (expect (.contains ^String url "iana.org")))
        (allure/screenshot *page* "Step 4: forward to IANA")))

    (it "reload preserves the page"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Navigation Flow")
      (allure/severity :normal)
      (allure/tag "smoke")
      (allure/tag "navigation")

      (allure/step "Navigate to example.com"
        (page/navigate *page* "https://example.com"))

      (allure/step "Reload the page"
        (page/reload *page*)
        (page/wait-for-load-state *page* "load"))

      (allure/step "Verify page is intact after reload"
        (assert/has-title *page* "Example Domain")
        (assert/has-text (page/locator *page* "h1") "Example Domain")
        (let [url (page/url *page*)]
          (allure/parameter "url-after-reload" url)
          (expect (.contains ^String url "example.com"))))))

  (describe "network inspection during navigation"
    {:context [with-playwright with-browser with-traced-page]}

    (it "inspects request and response details for navigation"
      (allure/epic "Smoke Tests")
      (allure/feature "example.com")
      (allure/story "Network Inspection")
      (allure/severity :normal)
      (allure/tag "smoke")
      (allure/tag "network")
      (allure/description
        "Navigates to example.com and inspects the HTTP request/response
        chain: method, headers, timing, and body content.")

      (allure/step "Navigate and capture response"
        (let [resp (page/navigate *page* "https://example.com")]
          (expect (instance? Response resp))

          (allure/step "Inspect request details"
            (let [req    (net/response-request resp)
                  method (net/request-method req)
                  url    (net/request-url req)]
              (println "Request:" method url)
              (allure/parameter "request-method" method)
              (allure/parameter "request-url" url)
              (expect (= "GET" method))
              (expect (.contains ^String url "example.com"))
              (expect (true? (net/request-is-navigation? req)))))

          (allure/step "Inspect response headers"
            (let [headers (net/response-headers resp)]
              (println "Response headers:" (count headers) "entries")
              (allure/parameter "header-count" (count headers))
              (allure/attach "Response Headers"
                (str/join "\n" (map (fn [[k v]] (str k ": " v)) headers))
                "text/plain")
              (expect (some? (get headers "content-type")))))

          (allure/step "Inspect response body"
            (let [body (net/response-text resp)]
              (println "Response body:" (count body) "chars")
              (allure/parameter "body-length" (count body))
              (expect (pos? (count body)))
              (expect (.contains ^String body "Example Domain")))))))))
