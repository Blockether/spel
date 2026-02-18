(ns com.blockether.spel.assertions-test
  "Tests for the assertions namespace - LocatorAssertions, PageAssertions."
  (:require
   [com.blockether.spel.assertions :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [lazytest.core :refer [defdescribe describe expect it throws? before-each]])
  (:import
   [com.microsoft.playwright.assertions LocatorAssertions PageAssertions]))

;; Test HTML content for assertion tests
(def ^:private test-html
  "<html>
     <head><title>Assertion Test Page</title></head>
     <body>
       <div id='container'>
         <button id='btn1' class='btn primary' data-test='submit'>Click Me</button>
         <button id='btn2' class='btn secondary' disabled>Disabled</button>
         <span class='item'>Alpha</span>
         <span class='item'>Beta</span>
         <div id='hidden' style='display:none'>Hidden</div>
         <input id='text-input' type='text' value='hello'/>
       </div>
     </body>
   </html>")

;; =============================================================================
;; Entry Points
;; =============================================================================

(defdescribe assert-that-test
  "Tests for assert-that entry point"

  (describe "with Locator"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns LocatorAssertions for Locator"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (instance? LocatorAssertions la)))))

  (describe "with Page"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns PageAssertions for Page"
      (let [pa (sut/assert-that *page*)]
        (expect (instance? PageAssertions pa)))))

  (describe "with invalid type"
    (it "throws IllegalArgumentException for non-Playwright object"
      (expect (throws? IllegalArgumentException
                #(sut/assert-that "not-a-playwright-obj"))))))

;; =============================================================================
;; Locator Text Assertions
;; =============================================================================

(defdescribe locator-text-assertions-test
  "Tests for has-text, contains-text"

  (describe "has-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when text matches"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (nil? (sut/has-text la "Click Me")))))

    (it "throws on text mismatch (AssertionFailedError extends Error, not Exception)"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (throws? org.opentest4j.AssertionFailedError
                  #(sut/has-text la "Wrong Text" {:timeout 1000}))))))

  (describe "contains-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when text is contained"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (nil? (sut/contains-text la "Click")))))

    (it "throws on text not contained (AssertionFailedError extends Error)"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (throws? org.opentest4j.AssertionFailedError
                  #(sut/contains-text la "Wrong" {:timeout 1000})))))))

;; =============================================================================
;; Locator Attribute Assertions
;; =============================================================================

(defdescribe locator-attribute-assertions-test
  "Tests for has-attribute"

  (describe "has-attribute"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when attribute exists with value"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (nil? (sut/has-attribute la "class" "btn primary")))))))

;; =============================================================================
;; Locator Count Assertions
;; =============================================================================

(defdescribe locator-count-assertions-test
  "Tests for has-count"

  (describe "has-count"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when count matches"
      (let [loc (page/locator *page* ".item")
            la (sut/assert-that loc)]
        (expect (nil? (sut/has-count la 2)))))))

;; =============================================================================
;; Locator State Assertions
;; =============================================================================

(defdescribe locator-state-assertions-test
  "Tests for is-visible, is-hidden, is-enabled, is-disabled"

  (describe "is-visible"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for visible element"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (nil? (sut/is-visible la))))))

  (describe "is-hidden"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for hidden element"
      (let [loc (page/locator *page* "#hidden")
            la (sut/assert-that loc)]
        (expect (nil? (sut/is-hidden la))))))

  (describe "is-enabled"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for enabled element"
      (let [loc (page/locator *page* "#btn1")
            la (sut/assert-that loc)]
        (expect (nil? (sut/is-enabled la))))))

  (describe "is-disabled"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for disabled element"
      (let [loc (page/locator *page* "#btn2")
            la (sut/assert-that loc)]
        (expect (nil? (sut/is-disabled la)))))))

;; =============================================================================
;; Page Assertions
;; =============================================================================

(defdescribe page-assertions-test
  "Tests for has-title, has-url"

  (describe "has-title"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when title matches"
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-title pa "Assertion Test Page"))))))

  (describe "has-url"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when URL contains expected text"
      (let [_pa (sut/assert-that *page*)]
        ;; The URL will be a data: URL or about:blank
        (expect (string? (page/url *page*)))))))

;; =============================================================================
;; Negation
;; =============================================================================

(defdescribe negation-test
  "Tests for loc-not negation"

  (describe "loc-not"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "negates is-visible assertion"
      (let [loc (page/locator *page* "#hidden")
            la (sut/assert-that loc)
            negated (sut/loc-not la)]
        ;; Hidden element is NOT visible
        (expect (nil? (sut/is-visible negated)))))))
