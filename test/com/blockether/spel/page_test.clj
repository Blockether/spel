(ns com.blockether.spel.page-test
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as sut]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it]])
  (:import
   [com.microsoft.playwright Locator Frame Response]
   [com.microsoft.playwright.options AriaRole]))

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

;; =============================================================================
;; once-dialog
;; =============================================================================

(defdescribe once-dialog-test
  "Tests for one-time dialog handler"

  (describe "once-dialog"
    {:context [with-playwright with-browser with-page]}

    (it "fires handler for first dialog"
      (let [captured (atom nil)]
        (sut/once-dialog *page* (fn [dialog]
                                  (reset! captured (.message dialog))
                                  (.dismiss dialog)))
        (sut/evaluate *page* "window.alert('hello once')")
        (expect (= "hello once" @captured))))

    (it "does not fire for second dialog"
      (let [once-count (atom 0)]
        ;; Register persistent handler to dismiss all dialogs
        (sut/on-dialog *page* (fn [dialog] (.dismiss dialog)))
        ;; Register one-time handler that increments counter
        (sut/once-dialog *page* (fn [_dialog]
                                  (swap! once-count inc)))
        ;; First alert — once-dialog fires
        (sut/evaluate *page* "window.alert('first')")
        (expect (= 1 @once-count))
        ;; Second alert — once-dialog already consumed, does not fire again
        (sut/evaluate *page* "window.alert('second')")
        (expect (= 1 @once-count))))))

;; =============================================================================
;; get-by-role with options
;; =============================================================================

(def ^:private role-test-html
  "<html><body>
     <h1>Title</h1><h2>Subtitle</h2><h3>Section</h3>
     <button>Cancel</button><button>Submit</button>
     <input type='checkbox' id='c1' checked/><label for='c1'>Agree</label>
     <input type='checkbox' id='c2'/><label for='c2'>Newsletter</label>
   </body></html>")

(defdescribe get-by-role-options-test
  "Tests for get-by-role with options map"

  (describe "get-by-role with :name"
    {:context [with-playwright with-browser with-page]}

    (it "finds button by name"
      (sut/set-content! *page* role-test-html)
      (let [loc (sut/get-by-role *page* AriaRole/BUTTON {:name "Submit"})]
        (expect (instance? Locator loc))
        (expect (= "Submit" (locator/text-content loc))))))

  (describe "get-by-role with :name :exact"
    {:context [with-playwright with-browser with-page]}

    (it "finds exact name match"
      (sut/set-content! *page* role-test-html)
      (let [loc (sut/get-by-role *page* AriaRole/BUTTON {:name "Submit" :exact true})]
        (expect (= "Submit" (locator/text-content loc))))))

  (describe "get-by-role with :level"
    {:context [with-playwright with-browser with-page]}

    (it "finds heading by level"
      (sut/set-content! *page* role-test-html)
      (let [loc (sut/get-by-role *page* AriaRole/HEADING {:level 2})]
        (expect (= "Subtitle" (locator/text-content loc))))))

  (describe "get-by-role with :checked"
    {:context [with-playwright with-browser with-page]}

    (it "finds checked checkbox"
      (sut/set-content! *page* role-test-html)
      (let [loc (sut/get-by-role *page* AriaRole/CHECKBOX {:checked true})]
        (expect (= 1 (locator/count-elements loc)))
        (expect (true? (locator/is-checked? loc)))))))
