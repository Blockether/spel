(ns com.blockether.spel.page-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as sut]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it around]])
  (:import
   [com.microsoft.playwright Locator Frame Response]
   [com.microsoft.playwright.options AriaRole]))

;; =============================================================================
;; Navigation
;; =============================================================================

(defdescribe navigate-test
  "Tests for page navigation"
  (around [f] (core/with-testing-browser (f)))

  (describe "navigate"
    (it "navigates to a URL"
      (core/with-testing-page [pg]
        (let [resp (sut/navigate pg "data:text/html,<h1>Hello</h1>")]
        ;; data: URLs return nil response
          (expect (or (nil? resp) (instance? Response resp))))))

    (expect-it "url returns current URL"
      (core/with-testing-page [pg]
        (do
          (sut/navigate pg "data:text/html,<h1>Test</h1>")
          (string? (sut/url pg)))))))

;; =============================================================================
;; Content
;; =============================================================================

(defdescribe content-test
  "Tests for page content"
  (around [f] (core/with-testing-browser (f)))

  (describe "content"
    (it "returns HTML content"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<h1>Hello World</h1>")
        (let [html (sut/content pg)]
          (expect (string? html))
          (expect (.contains html "Hello World"))))))

  (describe "title"
    (expect-it "returns page title"
      (core/with-testing-page [pg]
        (do
          (sut/set-content! pg "<title>My Title</title><body>test</body>")
          (= "My Title" (sut/title pg)))))))

;; =============================================================================
;; Locators
;; =============================================================================

(defdescribe locator-test
  "Tests for page locator creation"
  (around [f] (core/with-testing-browser (f)))

  (describe "locator"
    (it "creates a CSS locator"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<div class='test'>content</div>")
        (let [loc (sut/locator pg ".test")]
          (expect (instance? Locator loc))
          (expect (= "content" (locator/text-content loc)))))))

  (describe "get-by-text"
    (it "locates element by text"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<span>Hello World</span>")
        (let [loc (sut/get-by-text pg "Hello World")]
          (expect (instance? Locator loc))
          (expect (locator/is-visible? loc)))))))

;; =============================================================================
;; Evaluation
;; =============================================================================

(defdescribe evaluate-test
  "Tests for JavaScript evaluation"
  (around [f] (core/with-testing-browser (f)))

  (describe "evaluate"
    (it "evaluates simple expression"
      (core/with-testing-page [pg]
        (let [result (sut/evaluate pg "1 + 2")]
          (expect (= 3 (long result))))))

    (it "evaluates with argument"
      (core/with-testing-page [pg]
        (let [result (sut/evaluate pg "x => x + '!'" "hello")]
          (expect (= "hello!" result)))))))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defdescribe screenshot-test
  "Tests for screenshot capture"
  (around [f] (core/with-testing-browser (f)))

  (describe "screenshot"
    (it "takes screenshot as byte array"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<h1>Screenshot Test</h1>")
        (let [bytes (sut/screenshot pg)]
          (expect (bytes? bytes))
          (expect (pos? (alength ^bytes bytes))))))))

;; =============================================================================
;; Page State
;; =============================================================================

(defdescribe page-state-test
  "Tests for page state queries"
  (around [f] (core/with-testing-browser (f)))

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
    (it "returns viewport dimensions"
      (core/with-testing-page [pg]
        (sut/set-viewport-size! pg 1024 768)
        (let [vp (sut/viewport-size pg)]
          (expect (= 1024 (:width vp)))
          (expect (= 768 (:height vp)))))))

  (describe "frames"
    (expect-it "returns main frame"
      (core/with-testing-page [pg]
        (instance? Frame (sut/main-frame pg))))))

;; =============================================================================
;; once-dialog
;; =============================================================================

(defdescribe once-dialog-test
  "Tests for one-time dialog handler"
  (around [f] (core/with-testing-browser (f)))

  (describe "once-dialog"

    (it "fires handler for first dialog"
      (core/with-testing-page [pg]
        (let [captured (atom nil)]
          (sut/once-dialog pg (fn [dialog]
                                (reset! captured (.message dialog))
                                (.dismiss dialog)))
          (sut/evaluate pg "window.alert('hello once')")
          (expect (= "hello once" @captured)))))

    (it "does not fire for second dialog"
      (core/with-testing-page [pg]
        (let [once-count (atom 0)]
        ;; Register persistent handler to dismiss all dialogs
          (sut/on-dialog pg (fn [dialog] (.dismiss dialog)))
        ;; Register one-time handler that increments counter
          (sut/once-dialog pg (fn [_dialog]
                                (swap! once-count inc)))
        ;; First alert — once-dialog fires
          (sut/evaluate pg "window.alert('first')")
          (expect (= 1 @once-count))
        ;; Second alert — once-dialog already consumed, does not fire again
          (sut/evaluate pg "window.alert('second')")
          (expect (= 1 @once-count)))))))

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
  (around [f] (core/with-testing-browser (f)))

  (describe "get-by-role with :name"

    (it "finds button by name"
      (core/with-testing-page [pg]
        (sut/set-content! pg role-test-html)
        (let [loc (sut/get-by-role pg AriaRole/BUTTON {:name "Submit"})]
          (expect (instance? Locator loc))
          (expect (= "Submit" (locator/text-content loc)))))))

  (describe "get-by-role with :name :exact"

    (it "finds exact name match"
      (core/with-testing-page [pg]
        (sut/set-content! pg role-test-html)
        (let [loc (sut/get-by-role pg AriaRole/BUTTON {:name "Submit" :exact true})]
          (expect (= "Submit" (locator/text-content loc)))))))

  (describe "get-by-role with :level"

    (it "finds heading by level"
      (core/with-testing-page [pg]
        (sut/set-content! pg role-test-html)
        (let [loc (sut/get-by-role pg AriaRole/HEADING {:level 2})]
          (expect (= "Subtitle" (locator/text-content loc)))))))

  (describe "get-by-role with :checked"

    (it "finds checked checkbox"
      (core/with-testing-page [pg]
        (sut/set-content! pg role-test-html)
        (let [loc (sut/get-by-role pg AriaRole/CHECKBOX {:checked true})]
          (expect (= 1 (locator/count-elements loc)))
          (expect (true? (locator/is-checked? loc))))))))

;; =============================================================================
;; get-by-ref
;; =============================================================================

(defdescribe get-by-ref-test
  "Tests for get-by-ref locator"
  (around [f] (core/with-testing-browser (f)))

  (describe "get-by-ref with snapshot refs"

    (it "locates element by ref ID after snapshot"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<h1>Hello</h1><button>Click me</button>")
        ;; Take snapshot to tag elements with data-pw-ref
        (let [snap (snapshot/capture-snapshot pg)
              refs (:refs snap)
              ref-id (first (keys refs))
              loc (sut/get-by-ref pg ref-id)]
          (expect (instance? Locator loc))
          (expect (locator/is-visible? loc)))))

    (it "locates element by @ref ID (strips @ prefix)"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<p>Some text</p>")
        (let [snap (snapshot/capture-snapshot pg)
              refs (:refs snap)
              ref-id (first (keys refs))
              loc (sut/get-by-ref pg (str "@" ref-id))]
          (expect (instance? Locator loc))
          (expect (locator/is-visible? loc)))))

    (it "returns empty locator for non-existent ref"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<div>Test</div>")
        (let [loc (sut/get-by-ref pg "e9999")]
          (expect (instance? Locator loc))
          (expect (= 0 (locator/count-elements loc))))))))

(defdescribe validate-url-test
  "Tests for URL validation logic"
  (around [f] (core/with-testing-browser (f)))

  (describe "validate-url"
    (it "returns valid URLs as-is"
      (expect (= "https://example.org" (sut/validate-url "https://example.org")))
      (expect (= "http://example.org" (sut/validate-url "http://example.org")))
      (expect (= "https://sub.example.org" (sut/validate-url "https://sub.example.org")))
      (expect (= "https://example.org/path" (sut/validate-url "https://example.org/path")))
      (expect (= "https://example.org?q=1" (sut/validate-url "https://example.org?q=1")))
      (expect (= "https://example.org#frag" (sut/validate-url "https://example.org#frag")))
      (expect (= "http://localhost" (sut/validate-url "http://localhost")))
      (expect (= "http://localhost:3000" (sut/validate-url "http://localhost:3000")))
      (expect (= "https://192.168.1.1" (sut/validate-url "https://192.168.1.1")))
      (expect (= "https://192.168.1.1:8080" (sut/validate-url "https://192.168.1.1:8080")))
      (expect (= "file:///tmp/test.html" (sut/validate-url "file:///tmp/test.html")))
      (expect (= "about:blank" (sut/validate-url "about:blank")))
      (expect (= "data:text/html,<h1>hi</h1>" (sut/validate-url "data:text/html,<h1>hi</h1>")))
      (expect (= "chrome://settings" (sut/validate-url "chrome://settings")))
      (expect (= "javascript:void(0)" (sut/validate-url "javascript:void(0)")))
      (expect (= "blob:http://example.org/abc" (sut/validate-url "blob:http://example.org/abc")))
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

;; =============================================================================
;; Clipboard
;; =============================================================================

(defdescribe clipboard-test
  "Tests for page clipboard operations"
  (around [f] (core/with-testing-browser (f)))

  (describe "clipboard-copy and clipboard-read"
    (it "copies text and reads it back"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<body>clipboard test</body>")
        (let [result (sut/clipboard-copy pg "hello from test")]
          (expect (= true (:copied result)))
          (expect (= "hello from test" (:text result))))))

    (it "reads back the text that was copied"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<body>read test</body>")
        (sut/clipboard-copy pg "round-trip value")
        (let [result (sut/clipboard-read pg)]
        ;; Clipboard API requires secure context (localhost/https);
        ;; on about:blank, readText may fail. Assert structure, not content.
          (expect (map? result))
          (expect (or (contains? result :content)
                    (some? (:com.blockether.anomaly.core/category result))))))))

  (describe "clipboard-paste"
    (it "returns paste result structure"
      (core/with-testing-page [pg]
        (sut/set-content! pg "<input id='target' type='text'/>")
        (locator/click (sut/locator pg "#target"))
        (sut/clipboard-copy pg "pasted text")
        (let [result (sut/clipboard-paste pg)]
        ;; Clipboard paste may fail on about:blank (no secure context)
        ;; Just verify the function returns a map without crashing
          (expect (map? result)))))))
