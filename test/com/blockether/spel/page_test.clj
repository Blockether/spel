(ns com.blockether.spel.page-test
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as sut]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [lazytest.core :refer [defdescribe describe expect expect-it it]])
  (:import
   [com.microsoft.playwright Locator Frame Response]))

;; =============================================================================
;; Navigation
;; =============================================================================

(defdescribe navigate-test
  "Tests for page navigation"

  (describe "navigate"
    {:context [with-playwright with-browser with-page]}
    (it "navigates to a URL"
      (let [resp (sut/navigate *page* "data:text/html,<h1>Hello</h1>")]
        ;; data: URLs return nil response
        (expect (or (nil? resp) (instance? Response resp)))))

    (expect-it "url returns current URL"
      (do
        (sut/navigate *page* "data:text/html,<h1>Test</h1>")
        (string? (sut/url *page*))))))

;; =============================================================================
;; Content
;; =============================================================================

(defdescribe content-test
  "Tests for page content"

  (describe "content"
    {:context [with-playwright with-browser with-page]}
    (it "returns HTML content"
      (sut/set-content! *page* "<h1>Hello World</h1>")
      (let [html (sut/content *page*)]
        (expect (string? html))
        (expect (.contains html "Hello World")))))

  (describe "title"
    {:context [with-playwright with-browser with-page]}
    (expect-it "returns page title"
      (do
        (sut/set-content! *page* "<title>My Title</title><body>test</body>")
        (= "My Title" (sut/title *page*))))))

;; =============================================================================
;; Locators
;; =============================================================================

(defdescribe locator-test
  "Tests for page locator creation"

  (describe "locator"
    {:context [with-playwright with-browser with-page]}
    (it "creates a CSS locator"
      (sut/set-content! *page* "<div class='test'>content</div>")
      (let [loc (sut/locator *page* ".test")]
        (expect (instance? Locator loc))
        (expect (= "content" (locator/text-content loc))))))

  (describe "get-by-text"
    {:context [with-playwright with-browser with-page]}
    (it "locates element by text"
      (sut/set-content! *page* "<span>Hello World</span>")
      (let [loc (sut/get-by-text *page* "Hello World")]
        (expect (instance? Locator loc))
        (expect (locator/is-visible? loc))))))

;; =============================================================================
;; Evaluation
;; =============================================================================

(defdescribe evaluate-test
  "Tests for JavaScript evaluation"

  (describe "evaluate"
    {:context [with-playwright with-browser with-page]}
    (it "evaluates simple expression"
      (let [result (sut/evaluate *page* "1 + 2")]
        (expect (= 3 (long result)))))

    (it "evaluates with argument"
      (let [result (sut/evaluate *page* "x => x + '!'" "hello")]
        (expect (= "hello!" result))))))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defdescribe screenshot-test
  "Tests for screenshot capture"

  (describe "screenshot"
    {:context [with-playwright with-browser with-page]}
    (it "takes screenshot as byte array"
      (sut/set-content! *page* "<h1>Screenshot Test</h1>")
      (let [bytes (sut/screenshot *page*)]
        (expect (bytes? bytes))
        (expect (pos? (alength ^bytes bytes)))))))

;; =============================================================================
;; Page State
;; =============================================================================

(defdescribe page-state-test
  "Tests for page state queries"

  (describe "is-closed?"
    (it "returns false for open page"
      (core/with-playwright [pw (core/create)]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [page (core/new-page browser)]
            (expect (false? (sut/is-closed? page)))))))

    (it "returns true after closing"
      (core/with-playwright [pw (core/create)]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (let [page (core/new-page browser)]
            (core/close-page! page)
            (expect (true? (sut/is-closed? page))))))))

  (describe "viewport-size"
    {:context [with-playwright with-browser with-page]}
    (it "returns viewport dimensions"
      (sut/set-viewport-size! *page* 1024 768)
      (let [vp (sut/viewport-size *page*)]
        (expect (= 1024 (:width vp)))
        (expect (= 768 (:height vp))))))

  (describe "frames"
    {:context [with-playwright with-browser with-page]}
    (expect-it "returns main frame"
      (instance? Frame (sut/main-frame *page*)))))
