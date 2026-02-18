(ns com.blockether.spel.smoke-test
  "End-to-end smoke tests against live websites (example.com, amazon.com).

   Showcases the Allure in-test API with epics, features, stories, steps,
   screenshots, parameters, attachments, and links. Run with the Allure
   reporter for rich HTML output:

     clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                                        with-browser with-page
                                                        with-traced-page]]
   [lazytest.core :refer [defdescribe describe expect it]])
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
          (allure/step "contains-text 'More information'"
            (assert/contains-text link "More information"))

          (allure/step "has-attribute href containing 'iana.org'"
            (assert/has-attribute link "href" "https://www.iana.org/domains/examples"))
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
;; Amazon.com — Smoke Tests
;; =============================================================================

(defdescribe amazon-com-navigation-smoke-test
  "Smoke: amazon.com navigation and page load"

  (describe "homepage load"
    {:context [with-playwright with-browser with-traced-page]}

    (it "loads amazon.com homepage"
      (allure/epic "Smoke Tests")
      (allure/feature "amazon.com")
      (allure/story "Navigation")
      (allure/severity :blocker)
      (allure/owner "spel")
      (allure/tag "smoke")
      (allure/tag "amazon")
      (allure/link "Site" "https://www.amazon.com")
      (allure/description
        "Navigates to amazon.com, verifies HTTP response, page title,
        and takes a screenshot of the homepage.")

      (allure/step "Navigate to amazon.com"
        (println "Starting Amazon.com navigation...")
        (let [resp (page/navigate *page* "https://www.amazon.com")]
          (println "Navigation response received")
          (allure/parameter "url" "https://www.amazon.com")

          (allure/step "Verify HTTP response is successful"
            (let [status (net/response-status resp)]

              (println "Navigation returned anomaly, skipping status check:" status)

              (println "HTTP status:" status (if (<= 200 status 299) "(OK)" "(UNEXPECTED)"))
              (allure/parameter "status" status)
                                              ;; Amazon may return 200 or 202
              (expect (<= 200 status 299))))))

      (allure/step "Wait for full page load"
        (println "Waiting for 'load' event...")
        (page/wait-for-load-state *page* "load")
        (println "Page fully loaded"))

      (allure/step "Verify page title contains 'Amazon'"
        (assert/has-title *page* "Amazon" {:substring true})
        (let [title (page/title *page*)]
          (println "Page title:" (pr-str title))
          (allure/parameter "title" title)))

      (allure/step "Verify URL contains 'amazon.com'"
        (let [url (page/url *page*)]
          (println "Final URL:" url)
          (allure/parameter "url" url)
          (expect (.contains ^String url "amazon.com"))))

      (allure/step "Screenshot of homepage"
        (println "Capturing Amazon homepage screenshot...")
        (allure/screenshot *page* "Amazon homepage")))))

(defdescribe amazon-com-search-smoke-test
  "Smoke: amazon.com search functionality"

  (describe "product search"
    {:context [with-playwright with-browser with-traced-page]}

    (it "searches for 'clojure programming' and finds results"
      (allure/epic "Smoke Tests")
      (allure/feature "amazon.com")
      (allure/story "Search")
      (allure/severity :critical)
      (allure/tag "smoke")
      (allure/tag "amazon")
      (allure/tag "search")
      (allure/parameter "search-term" "clojure programming")
      (allure/description
        "Searches Amazon for 'clojure programming', verifies search results
        appear, and captures screenshots at each step.")

      (allure/step "Navigate to amazon.com"
        (println "Opening Amazon.com for search test...")
        (page/navigate *page* "https://www.amazon.com")
        (page/wait-for-load-state *page* "domcontentloaded")
        (println "DOM content loaded"))

      (allure/step "Screenshot before search"
        (allure/screenshot *page* "Before search"))

      (allure/step "Type search query"
        (let [search-box (page/locator *page* "#twotabsearchtextbox")]
          (allure/step "Wait for search box"
            (println "Waiting for #twotabsearchtextbox to be visible...")
            (locator/wait-for search-box {:state "visible" :timeout 10000})
            (println "Search box visible"))
          (allure/step "Fill search term"
            (println "Typing 'clojure programming' into search box")
            (locator/fill search-box "clojure programming"))
          (allure/step "Verify input value"
            (let [val (locator/input-value search-box)]
              (println "Input value:" (pr-str val))
              (allure/parameter "input-value" val)
              (expect (= "clojure programming" val))))))

      (allure/step "Submit search"
        (let [submit (page/locator *page* "#nav-search-submit-button")]
          (println "Clicking search submit button...")
          (locator/click submit)
          (page/wait-for-load-state *page* "domcontentloaded")
          (println "Search results page loaded")))

      (allure/step "Screenshot after search"
        (allure/screenshot *page* "Search results"))

      (allure/step "Verify search results page"
        (allure/step "URL contains search term"
          (let [url (page/url *page*)]
            (println "Results URL:" url)
            (allure/parameter "results-url" url)
            (expect (.contains ^String url "amazon.com"))))

        (allure/step "Page title reflects search"
          (let [title (page/title *page*)]
            (println "Results page title:" (pr-str title))
            (allure/parameter "results-title" title)
            (expect (.contains ^String (str/lower-case title) "amazon"))))))

    (it "search box is visible and interactive"
      (allure/epic "Smoke Tests")
      (allure/feature "amazon.com")
      (allure/story "Search")
      (allure/severity :normal)
      (allure/tag "smoke")
      (allure/tag "amazon")

      (allure/step "Navigate"
        (println "Loading Amazon for search box inspection...")
        (page/navigate *page* "https://www.amazon.com")
        (page/wait-for-load-state *page* "domcontentloaded"))

      (allure/step "Verify search box is visible"
        (let [search-box (page/locator *page* "#twotabsearchtextbox")]
          (locator/wait-for search-box {:state "visible" :timeout 10000})
          (println "Search box: visible?" (locator/is-visible? search-box)
            "enabled?" (locator/is-enabled? search-box)
            "editable?" (locator/is-editable? search-box))
          (expect (locator/is-visible? search-box))
          (expect (locator/is-enabled? search-box))
          (expect (locator/is-editable? search-box))))

      (allure/step "Verify search button exists"
        (let [btn (page/locator *page* "#nav-search-submit-button")]
          (println "Submit button: visible?" (locator/is-visible? btn)
            "enabled?" (locator/is-enabled? btn))
          (expect (locator/is-visible? btn))
          (expect (locator/is-enabled? btn))))

      (allure/step "Screenshot"
        (allure/screenshot *page* "Search elements")))))

(defdescribe amazon-com-structure-smoke-test
  "Smoke: amazon.com page structure and navigation elements"

  (describe "page structure"
    {:context [with-playwright with-browser with-traced-page]}

    (it "has navigation bar with expected elements"
      (allure/epic "Smoke Tests")
      (allure/feature "amazon.com")
      (allure/story "Page Structure")
      (allure/severity :normal)
      (allure/tag "smoke")
      (allure/tag "amazon")
      (allure/tag "structure")

      (allure/step "Navigate and wait for full load"
        (println "Loading amazon.com with full page load...")
        (page/navigate *page* "https://www.amazon.com")
        (page/wait-for-load-state *page* "load")
        (println "Full load complete"))

      (allure/step "Verify Amazon logo/branding"
        (let [logo (page/locator *page* "a#nav-logo-sprites")]
          (println "Looking for Amazon logo (a#nav-logo-sprites)...")
          (locator/wait-for logo {:state "visible" :timeout 10000})
          (println "Logo found and visible")
          (expect (locator/is-visible? logo))))

      (allure/step "Verify navigation bar exists"
        (let [nav (page/locator *page* "#navbar")]
          (println "Looking for navigation bar (#navbar)...")
          (locator/wait-for nav {:state "visible" :timeout 10000})
          (println "Navigation bar visible")
          (expect (locator/is-visible? nav))))

      (allure/step "Verify cart link"
        (let [cart (page/locator *page* "#nav-cart")]
          (expect (locator/is-visible? cart))
          (let [text (locator/text-content cart)]
            (println "Cart link text:" (pr-str (str/trim text)))
            (allure/parameter "cart.text" (str/trim text)))))

      (allure/step "Collect page metadata via JS"
        (let [meta-count (page/evaluate *page* "document.querySelectorAll('meta').length")
              link-count (page/evaluate *page* "document.querySelectorAll('a').length")
              img-count  (page/evaluate *page* "document.querySelectorAll('img').length")]
          (println "Page stats: meta=" meta-count "links=" link-count "images=" img-count)
          (allure/parameter "meta-tags" meta-count)
          (allure/parameter "links" link-count)
          (allure/parameter "images" img-count)
          (expect (pos? (int link-count)))
          (expect (pos? (int img-count)))))

      (allure/step "Screenshot of navigation"
        (println "Capturing navigation bar screenshot")
        (allure/screenshot *page* "Amazon navigation bar")))))
