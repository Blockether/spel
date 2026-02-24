(ns com.blockether.spel.assertions-test
  "Comprehensive tests for the assertions namespace — LocatorAssertions, PageAssertions,
   negation, ARIA snapshots, form elements, CSS, viewport, and more."
  (:require
   [com.blockether.spel.assertions :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it throws? before-each]])
  (:import
   [com.microsoft.playwright.assertions LocatorAssertions PageAssertions]))

;; =============================================================================
;; HTML fixtures
;; =============================================================================

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

(def ^:private form-html
  "<html><body>
     <form>
       <input id='name' value='Alice' />
       <input id='email' type='email' />
       <input id='agree' type='checkbox' checked />
       <input id='disagree' type='checkbox' />
       <button disabled>Submit</button>
       <select id='sel'><option>A</option></select>
     </form>
   </body></html>")

(def ^:private class-html
  "<html><body><div class='foo bar baz'>test</div></body></html>")

(def ^:private css-html
  "<html><body><div style='color: rgb(255, 0, 0)'>red</div></body></html>")

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
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/has-text la "Click Me")))))

    (it "fails on text mismatch"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (throws? org.opentest4j.AssertionFailedError
                  #(sut/has-text la "Wrong Text" {:timeout 1000}))))))

  (describe "contains-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when text is contained"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/contains-text la "Click")))))

    (it "fails on text not contained"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (throws? org.opentest4j.AssertionFailedError
                  #(sut/contains-text la "Wrong" {:timeout 1000})))))))

;; =============================================================================
;; Locator Attribute Assertions
;; =============================================================================

(defdescribe locator-attribute-assertions-test
  "Tests for has-attribute, has-class, has-css, has-id"

  (describe "has-attribute"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when attribute exists with value"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/has-attribute la "data-test" "submit")))))

    (it "passes with regex pattern"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/has-attribute la "class" #"btn.*primary"))))))

  (describe "has-class"
    {:context [with-playwright with-browser with-page]}

    (it "passes for exact class string"
      (page/set-content! *page* class-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "div"))]
        (expect (nil? (sut/has-class la "foo bar baz")))))

    (it "passes for class list"
      (page/set-content! *page* class-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "div"))]
        (expect (nil? (sut/has-class la ["foo bar baz"])))))

    (it "passes for regex pattern"
      (page/set-content! *page* class-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "div"))]
        (expect (nil? (sut/has-class la #"foo.*baz"))))))

  (describe "has-css"
    {:context [with-playwright with-browser with-page]}

    (it "passes when CSS property matches"
      (page/set-content! *page* css-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "div"))]
        (expect (nil? (sut/has-css la "color" "rgb(255, 0, 0)")))))

    (it "passes with regex CSS value"
      (page/set-content! *page* css-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "div"))]
        (expect (nil? (sut/has-css la "color" #"rgb\(255.*"))))))

  (describe "has-id"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for matching id"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/has-id la "btn1")))))))

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
      (let [la (sut/assert-that (page/locator *page* ".item"))]
        (expect (nil? (sut/has-count la 2)))))

    (it "passes for single element"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/has-count la 1)))))))

;; =============================================================================
;; Locator State Assertions
;; =============================================================================

(defdescribe locator-state-assertions-test
  "Tests for is-visible, is-hidden, is-enabled, is-disabled, is-attached"

  (describe "is-visible"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for visible element"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/is-visible la))))))

  (describe "is-hidden"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for hidden element"
      (let [la (sut/assert-that (page/locator *page* "#hidden"))]
        (expect (nil? (sut/is-hidden la))))))

  (describe "is-enabled"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for enabled element"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/is-enabled la))))))

  (describe "is-disabled"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for disabled element"
      (let [la (sut/assert-that (page/locator *page* "#btn2"))]
        (expect (nil? (sut/is-disabled la))))))

  (describe "is-attached"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for attached element"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/is-attached la)))))))

;; =============================================================================
;; Form Element Assertions
;; =============================================================================

(defdescribe form-assertions-test
  "Tests for has-value, is-checked, is-editable, is-empty, is-focused"

  (describe "has-value"
    {:context [with-playwright with-browser with-page]}

    (it "passes when input value matches"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#name"))]
        (expect (nil? (sut/has-value la "Alice")))))

    (it "passes with regex value"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#name"))]
        (expect (nil? (sut/has-value la #"Ali.*"))))))

  (describe "is-checked"
    {:context [with-playwright with-browser with-page]}

    (it "passes for checked checkbox"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#agree"))]
        (expect (nil? (sut/is-checked la))))))

  (describe "is-editable"
    {:context [with-playwright with-browser with-page]}

    (it "passes for editable input"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#name"))]
        (expect (nil? (sut/is-editable la))))))

  (describe "is-empty"
    {:context [with-playwright with-browser with-page]}

    (it "passes for empty input"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#email"))]
        (expect (nil? (sut/is-empty la))))))

  (describe "is-focused"
    {:context [with-playwright with-browser with-page]}

    (it "passes after focusing an element"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (locator/focus (page/locator *page* "#name"))
      (let [la (sut/assert-that (page/locator *page* "#name"))]
        (expect (nil? (sut/is-focused la)))))))

;; =============================================================================
;; Viewport Assertions
;; =============================================================================

(defdescribe viewport-assertions-test
  "Tests for is-in-viewport"

  (describe "is-in-viewport"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes for element in viewport"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))]
        (expect (nil? (sut/is-in-viewport la)))))))

;; =============================================================================
;; Page Assertions
;; =============================================================================

(defdescribe page-assertions-test
  "Tests for has-title, has-url (string and regex)"

  (describe "has-title with set-content"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "passes when title matches string"
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-title pa "Assertion Test Page")))))

    (it "passes when title matches regex"
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-title pa #"Assertion.*Page"))))))

  (describe "has-title with example.com"
    {:context [with-playwright with-browser with-page]}

    (it "passes for example.com title"
      (page/navigate *page* "https://example.com")
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-title pa "Example Domain"))))))

  (describe "has-url with example.com"
    {:context [with-playwright with-browser with-page]}

    (it "passes with exact URL string"
      (page/navigate *page* "https://example.com")
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-url pa "https://example.com/")))))

    (it "passes with regex Pattern"
      (page/navigate *page* "https://example.com")
      (let [pa (sut/assert-that *page*)]
        (expect (nil? (sut/has-url pa #".*example\.com.*")))))))

;; =============================================================================
;; Negation — loc-not, page-not
;; =============================================================================

(defdescribe negation-test
  "Tests for loc-not and page-not negation"

  (describe "loc-not"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "negated is-visible passes for hidden element"
      (let [la (sut/assert-that (page/locator *page* "#hidden"))
            negated (sut/loc-not la)]
        (expect (nil? (sut/is-visible negated)))))

    (it "negated has-text passes for wrong text"
      (let [la (sut/assert-that (page/locator *page* "#btn1"))
            negated (sut/loc-not la)]
        (expect (nil? (sut/has-text negated "Wrong Text")))))

    (it "negated is-checked passes for unchecked"
      (page/set-content! *page* form-html)
      (page/wait-for-load-state *page* :load)
      (let [la (sut/assert-that (page/locator *page* "#disagree"))
            negated (sut/loc-not la)]
        (expect (nil? (sut/is-checked negated))))))

  (describe "page-not"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "negated has-title passes for wrong title"
      (let [pa (sut/assert-that *page*)
            negated (sut/page-not pa)]
        (expect (nil? (sut/has-title negated "Wrong Title")))))

    (it "negated has-url passes for wrong URL"
      (let [pa (sut/assert-that *page*)
            negated (sut/page-not pa)]
        (expect (nil? (sut/has-url negated "https://wrong.example.com/")))))))

;; =============================================================================
;; ARIA Snapshot Assertions
;; =============================================================================

(defdescribe aria-snapshot-assertions-test
  "Tests for matches-aria-snapshot"

  (describe "matches-aria-snapshot with example.com"
    {:context [with-playwright with-browser with-page]}

    (it "matches heading"
      (page/navigate *page* "https://example.com")
      (let [la (sut/assert-that (page/locator *page* "h1"))]
        (expect (nil? (sut/matches-aria-snapshot la "- heading \"Example Domain\"")))))

    (it "matches link"
      (page/navigate *page* "https://example.com")
      (let [la (sut/assert-that (page/locator *page* "a"))]
        (expect (nil? (sut/matches-aria-snapshot la "- link \"Learn more\"")))))

    (it "negated — does not match wrong heading"
      (page/navigate *page* "https://example.com")
      (let [la (sut/assert-that (page/locator *page* "h1"))
            negated (sut/loc-not la)]
        (expect (nil? (sut/matches-aria-snapshot negated "- heading \"Wrong Title\"")))))))
