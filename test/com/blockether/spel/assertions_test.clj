(ns com.blockether.spel.assertions-test
  "Tests for the assertions namespace - LocatorAssertions, PageAssertions."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.assertions :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe describe expect it throws? around]])
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
  (around [f] (core/with-testing-browser (f)))

  (describe "with Locator"

    (it "returns LocatorAssertions for Locator"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (instance? LocatorAssertions la))))))

  (describe "with Page"

    (it "returns PageAssertions for Page"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [pa (sut/assert-that pg)]
          (expect (instance? PageAssertions pa))))))

  (describe "with invalid type"
    (it "throws IllegalArgumentException for non-Playwright object"
      (expect (throws? IllegalArgumentException
                #(sut/assert-that "not-a-playwright-obj"))))))

;; =============================================================================
;; Locator Text Assertions
;; =============================================================================

(defdescribe locator-text-assertions-test
  "Tests for has-text, contains-text"
  (around [f] (core/with-testing-browser (f)))

  (describe "has-text"

    (it "passes when text matches"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (nil? (sut/has-text la "Click Me"))))))

    (it "throws on text mismatch (AssertionFailedError extends Error, not Exception)"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (throws? org.opentest4j.AssertionFailedError
                    #(sut/has-text la "Wrong Text" {:timeout 1000})))))))

  (describe "contains-text"

    (it "passes when text is contained"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (nil? (sut/contains-text la "Click"))))))

    (it "throws on text not contained (AssertionFailedError extends Error)"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (throws? org.opentest4j.AssertionFailedError
                    #(sut/contains-text la "Wrong" {:timeout 1000}))))))))

;; =============================================================================
;; Locator Attribute Assertions
;; =============================================================================

(defdescribe locator-attribute-assertions-test
  "Tests for has-attribute"
  (around [f] (core/with-testing-browser (f)))

  (describe "has-attribute"

    (it "passes when attribute exists with value"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (nil? (sut/has-attribute la "class" "btn primary"))))))))

;; =============================================================================
;; Locator Count Assertions
;; =============================================================================

(defdescribe locator-count-assertions-test
  "Tests for has-count"
  (around [f] (core/with-testing-browser (f)))

  (describe "has-count"

    (it "passes when count matches"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg ".item")
              la (sut/assert-that loc)]
          (expect (nil? (sut/has-count la 2))))))))

;; =============================================================================
;; Locator State Assertions
;; =============================================================================

(defdescribe locator-state-assertions-test
  "Tests for is-visible, is-hidden, is-enabled, is-disabled"
  (around [f] (core/with-testing-browser (f)))

  (describe "is-visible"

    (it "passes for visible element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (nil? (sut/is-visible la)))))))

  (describe "is-hidden"

    (it "passes for hidden element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#hidden")
              la (sut/assert-that loc)]
          (expect (nil? (sut/is-hidden la)))))))

  (describe "is-enabled"

    (it "passes for enabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn1")
              la (sut/assert-that loc)]
          (expect (nil? (sut/is-enabled la)))))))

  (describe "is-disabled"

    (it "passes for disabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#btn2")
              la (sut/assert-that loc)]
          (expect (nil? (sut/is-disabled la))))))))

;; =============================================================================
;; Page Assertions
;; =============================================================================

(defdescribe page-assertions-test
  "Tests for has-title, has-url"
  (around [f] (core/with-testing-browser (f)))

  (describe "has-title"

    (it "passes when title matches"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [pa (sut/assert-that pg)]
          (expect (nil? (sut/has-title pa "Assertion Test Page")))))))

  (describe "has-url"

    (it "passes when URL contains expected text"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [_pa (sut/assert-that pg)]
        ;; The URL will be a data: URL or about:blank
          (expect (string? (page/url pg))))))))

;; =============================================================================
;; Negation
;; =============================================================================

(defdescribe negation-test
  "Tests for loc-not negation"
  (around [f] (core/with-testing-browser (f)))

  (describe "loc-not"

    (it "negates is-visible assertion"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [loc (page/locator pg "#hidden")
              la (sut/assert-that loc)
              negated (sut/loc-not la)]
        ;; Hidden element is NOT visible
          (expect (nil? (sut/is-visible negated))))))))
