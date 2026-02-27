(ns com.blockether.spel.page-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as sut]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.snapshot :as snapshot]
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

;; =============================================================================
;; get-by-ref
;; =============================================================================

(defdescribe get-by-ref-test
  "Tests for get-by-ref locator"

  (describe "get-by-ref with snapshot refs"
    {:context [with-playwright with-browser with-page]}

    (it "locates element by ref ID after snapshot"
      (sut/set-content! *page* "<h1>Hello</h1><button>Click me</button>")
      ;; Take snapshot to tag elements with data-pw-ref
      (let [snap (snapshot/capture-snapshot *page*)
            refs (:refs snap)
            ref-id (first (keys refs))
            loc (sut/get-by-ref *page* ref-id)]
        (expect (instance? Locator loc))
        (expect (locator/is-visible? loc))))

    (it "locates element by @ref ID (strips @ prefix)"
      (sut/set-content! *page* "<p>Some text</p>")
      (let [snap (snapshot/capture-snapshot *page*)
            refs (:refs snap)
            ref-id (first (keys refs))
            loc (sut/get-by-ref *page* (str "@" ref-id))]
        (expect (instance? Locator loc))
        (expect (locator/is-visible? loc))))

    (it "returns empty locator for non-existent ref"
      (sut/set-content! *page* "<div>Test</div>")
      (let [loc (sut/get-by-ref *page* "e9999")]
        (expect (instance? Locator loc))
        (expect (= 0 (locator/count-elements loc)))))))

(defdescribe validate-url-test
  "Tests for URL validation logic"

  (describe "validate-url"
    (it "returns valid URLs as-is"
      (expect (= "https://example.com" (sut/validate-url "https://example.com")))
      (expect (= "http://example.com" (sut/validate-url "http://example.com")))
      (expect (= "https://sub.example.com" (sut/validate-url "https://sub.example.com")))
      (expect (= "https://example.com/path" (sut/validate-url "https://example.com/path")))
      (expect (= "https://example.com?q=1" (sut/validate-url "https://example.com?q=1")))
      (expect (= "https://example.com#frag" (sut/validate-url "https://example.com#frag")))
      (expect (= "http://localhost" (sut/validate-url "http://localhost")))
      (expect (= "http://localhost:3000" (sut/validate-url "http://localhost:3000")))
      (expect (= "https://192.168.1.1" (sut/validate-url "https://192.168.1.1")))
      (expect (= "https://192.168.1.1:8080" (sut/validate-url "https://192.168.1.1:8080")))
      (expect (= "file:///tmp/test.html" (sut/validate-url "file:///tmp/test.html")))
      (expect (= "about:blank" (sut/validate-url "about:blank")))
      (expect (= "data:text/html,<h1>hi</h1>" (sut/validate-url "data:text/html,<h1>hi</h1>")))
      (expect (= "chrome://settings" (sut/validate-url "chrome://settings")))
      (expect (= "javascript:void(0)" (sut/validate-url "javascript:void(0)")))
      (expect (= "blob:http://example.com/abc" (sut/validate-url "blob:http://example.com/abc")))
      (expect (= "https://example.co.uk" (sut/validate-url "https://example.co.uk"))))

    (it "throws for invalid single-word domain"
      (let [err (try (sut/validate-url "https://not-a-url") nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (expect (some? err))
        (expect (str/includes? (.getMessage err) "Invalid URL"))))

    (it "throws for invalid single word"
      (let [err (try (sut/validate-url "https://invalid") nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (expect (some? err))
        (expect (str/includes? (.getMessage err) "Invalid URL"))))

    (it "error message includes raw input"
      (let [err (try (sut/validate-url "https://notaurl" "notaurl") nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (expect (some? err))
        (expect (str/includes? (.getMessage err) "notaurl"))))))
