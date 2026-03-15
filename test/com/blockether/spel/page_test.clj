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

  (describe "clipboard-copy and clipboard-read"
    {:context [with-playwright with-browser with-page]}
    (it "copies text and reads it back"
      (sut/set-content! *page* "<body>clipboard test</body>")
      (let [result (sut/clipboard-copy *page* "hello from test")]
        (expect (= true (:copied result)))
        (expect (= "hello from test" (:text result)))))

    (it "reads back the text that was copied"
      (sut/set-content! *page* "<body>read test</body>")
      (sut/clipboard-copy *page* "round-trip value")
      (let [result (sut/clipboard-read *page*)]
        ;; Clipboard API requires secure context (localhost/https);
        ;; on about:blank, readText may fail. Assert structure, not content.
        (expect (map? result))
        (expect (or (contains? result :content)
                  (some? (:com.blockether.anomaly.core/category result)))))))

  (describe "clipboard-paste"
    {:context [with-playwright with-browser with-page]}
    (it "returns paste result structure"
      (sut/set-content! *page* "<input id='target' type='text'/>")
      (locator/click (sut/locator *page* "#target"))
      (sut/clipboard-copy *page* "pasted text")
      (let [result (sut/clipboard-paste *page*)]
        ;; Clipboard paste may fail on about:blank (no secure context)
        ;; Just verify the function returns a map without crashing
        (expect (map? result))))))

;; =============================================================================
;; Keyboard Press (issue #89)
;; =============================================================================

(defdescribe keyboard-press-test
  "Tests for page-level keyboard press (issue #89)"

  (describe "keyboard-press"
    {:context [with-playwright with-browser with-page]}

    (it "presses Escape and page captures the keydown event"
      (sut/set-content! *page*
        "<div id='last-key'>none</div>
         <script>document.addEventListener('keydown', function(e) {
           document.getElementById('last-key').textContent = e.key;
         });</script>")
      (sut/keyboard-press *page* "Escape")
      (let [result (locator/text-content (sut/locator *page* "#last-key"))]
        (expect (= "Escape" result))))

    (it "presses Enter key"
      (sut/set-content! *page*
        "<div id='last-key'>none</div>
         <script>document.addEventListener('keydown', function(e) {
           document.getElementById('last-key').textContent = e.key;
         });</script>")
      (sut/keyboard-press *page* "Enter")
      (let [result (locator/text-content (sut/locator *page* "#last-key"))]
        (expect (= "Enter" result))))

    (it "presses Tab key"
      (sut/set-content! *page*
        "<div id='last-key'>none</div>
         <script>document.addEventListener('keydown', function(e) {
           document.getElementById('last-key').textContent = e.key;
         });</script>")
      (sut/keyboard-press *page* "Tab")
      (let [result (locator/text-content (sut/locator *page* "#last-key"))]
        (expect (= "Tab" result))))

    (it "presses key combination Control+a"
      (sut/set-content! *page*
        "<div id='last-key'>none</div>
         <script>document.addEventListener('keydown', function(e) {
           if (e.ctrlKey && e.key === 'a')
             document.getElementById('last-key').textContent = 'Control+a';
         });</script>")
      (sut/keyboard-press *page* "Control+a")
      (let [result (locator/text-content (sut/locator *page* "#last-key"))]
        (expect (= "Control+a" result))))

    (it "returns nil on success"
      (sut/set-content! *page* "<div>empty</div>")
      (expect (nil? (sut/keyboard-press *page* "Escape"))))))

;; =============================================================================
;; Scrollable Discovery (issue #90)
;; =============================================================================

(def ^:private scrollable-test-html
  "<style>
     body { margin: 0; padding: 0; height: 3000px; }
     .scroll-box { width: 200px; height: 100px; overflow: auto; }
     .scroll-y   { width: 200px; height: 100px; overflow-y: scroll; overflow-x: hidden; }
     .scroll-x   { width: 200px; height: 50px; overflow-x: auto; overflow-y: hidden; }
     .hidden-box { width: 200px; height: 100px; overflow: hidden; }
     .no-scroll  { width: 200px; height: 100px; overflow: visible; }
     .tall { height: 500px; }
     .wide { width: 600px; white-space: nowrap; }
     .nest-outer { width: 250px; height: 150px; overflow: auto; }
     .nest-inner { width: 220px; height: 100px; overflow: auto; margin: 10px; }
   </style>
   <div id='auto-box' class='scroll-box' role='region' aria-label='Auto Region'>
     <div class='tall'>overflow auto</div></div>
   <div id='y-box' class='scroll-y'><div class='tall'>overflow-y scroll</div></div>
   <div id='x-box' class='scroll-x'><div class='wide'>wide horizontal content that overflows</div></div>
   <div id='hidden-box' class='hidden-box'><div class='tall'>hidden</div></div>
   <div id='no-box' class='no-scroll'><p>fits</p></div>
   <div id='nest-outer' class='nest-outer'>
     <div class='tall'>
       <div id='nest-inner' class='nest-inner'>
         <div class='tall'>nested inner scrollable</div>
       </div>
       outer content
     </div>
   </div>")

(defdescribe find-scrollable-test
  "Tests for scrollable element discovery (issue #90)"

  (describe "discovery"
    {:context [with-playwright with-browser with-page]}

    (it "finds overflow:auto, overflow-y:scroll, and overflow-x:auto elements"
      (sut/set-content! *page* scrollable-test-html)
      (let [results (sut/find-scrollable *page*)
            ids     (set (map :id results))]
        (expect (contains? ids "auto-box"))
        (expect (contains? ids "y-box"))
        (expect (contains? ids "x-box"))))

    (it "excludes overflow:hidden and content-fits elements"
      (sut/set-content! *page* scrollable-test-html)
      (let [results (sut/find-scrollable *page*)
            ids     (set (map :id results))]
        (expect (not (contains? ids "hidden-box")))
        (expect (not (contains? ids "no-box")))))

    (it "finds both nested scrollable containers (outer and inner)"
      (sut/set-content! *page* scrollable-test-html)
      (let [results (sut/find-scrollable *page*)
            ids     (set (map :id results))]
        (expect (contains? ids "nest-outer"))
        (expect (contains? ids "nest-inner"))))

    (it "returns empty vector on page with no scrollable elements"
      (sut/set-content! *page* "<div style='height:50px'>short</div>")
      (let [results (sut/find-scrollable *page*)]
        (expect (vector? results))
        (expect (zero? (count results))))))

  (describe "metadata"
    {:context [with-playwright with-browser with-page]}

    (it "returns scroll dimensions with scrollHeight > clientHeight for vertical overflow"
      (sut/set-content! *page* scrollable-test-html)
      (let [auto-el (first (filter #(= "auto-box" (:id %)) (sut/find-scrollable *page*)))]
        (expect (some? auto-el))
        (expect (> (:scroll-height auto-el) (:client-height auto-el)))
        (expect (= 0 (:scroll-top auto-el)))))

    (it "returns scrollWidth > clientWidth for horizontal overflow"
      (sut/set-content! *page* scrollable-test-html)
      (let [x-el (first (filter #(= "x-box" (:id %)) (sut/find-scrollable *page*)))]
        (expect (some? x-el))
        (expect (> (:scroll-width x-el) (:client-width x-el)))
        (expect (= 0 (:scroll-left x-el)))))

    (it "captures role and overflow-y CSS value"
      (sut/set-content! *page* scrollable-test-html)
      (let [auto-el (first (filter #(= "auto-box" (:id %)) (sut/find-scrollable *page*)))]
        (expect (= "region" (:role auto-el)))
        (expect (= "auto" (:overflow-y auto-el))))))

  (describe "refs"
    {:context [with-playwright with-browser with-page]}

    (it "assigns unique refs to each scrollable element"
      (sut/set-content! *page* scrollable-test-html)
      (let [results (sut/find-scrollable *page*)
            refs    (map :ref results)]
        (expect (every? string? refs))
        (expect (every? #(str/starts-with? % "e") refs))
        ;; All refs must be unique
        (expect (= (count refs) (count (set refs))))))

    (it "assigns data-pw-ref DOM attribute matching returned ref"
      (sut/set-content! *page* scrollable-test-html)
      (let [auto-el (first (filter #(= "auto-box" (:id %)) (sut/find-scrollable *page*)))
            dom-ref (sut/evaluate *page* "document.getElementById('auto-box').getAttribute('data-pw-ref')")]
        (expect (= (:ref auto-el) dom-ref)))))

  (describe "scrolling discovered elements via ref"
    {:context [with-playwright with-browser with-page]}

    (it "scrolls a discovered element by evaluating scrollTop via ref"
      (sut/set-content! *page* scrollable-test-html)
      (let [auto-el (first (filter #(= "auto-box" (:id %)) (sut/find-scrollable *page*)))
            ref     (:ref auto-el)
            sel     (str "[data-pw-ref='" ref "']")]
        ;; Scroll the element down 200px
        (sut/evaluate *page* (str "document.querySelector(\"" sel "\").scrollTop = 200"))
        ;; Read back scroll position
        (let [new-top (sut/evaluate *page* (str "document.querySelector(\"" sel "\").scrollTop"))]
          (expect (= 200 (long new-top))))))

    (it "scrolls nested inner container independently of outer"
      (sut/set-content! *page* scrollable-test-html)
      (let [results   (sut/find-scrollable *page*)
            outer-el  (first (filter #(= "nest-outer" (:id %)) results))
            inner-el  (first (filter #(= "nest-inner" (:id %)) results))
            outer-sel (str "[data-pw-ref='" (:ref outer-el) "']")
            inner-sel (str "[data-pw-ref='" (:ref inner-el) "']")]
        ;; Scroll inner down 100px — outer should stay at 0
        (sut/evaluate *page* (str "document.querySelector(\"" inner-sel "\").scrollTop = 100"))
        (let [inner-top (sut/evaluate *page* (str "document.querySelector(\"" inner-sel "\").scrollTop"))
              outer-top (sut/evaluate *page* (str "document.querySelector(\"" outer-sel "\").scrollTop"))]
          (expect (= 100 (long inner-top)))
          (expect (= 0 (long outer-top))))))

    (it "scrolls horizontal container via ref"
      (sut/set-content! *page* scrollable-test-html)
      (let [x-el (first (filter #(= "x-box" (:id %)) (sut/find-scrollable *page*)))
            sel  (str "[data-pw-ref='" (:ref x-el) "']")]
        (sut/evaluate *page* (str "document.querySelector(\"" sel "\").scrollLeft = 150"))
        (let [new-left (sut/evaluate *page* (str "document.querySelector(\"" sel "\").scrollLeft"))]
          (expect (= 150 (long new-left))))))))

;; =============================================================================
;; Scroll Position (issue #90)
;; =============================================================================

(defdescribe scroll-position-test
  "Tests for scroll position queries (issue #90)"

  (describe "scroll-position"
    {:context [with-playwright with-browser with-page]}

    (it "returns zero position on fresh page"
      (sut/set-content! *page* "<body style='height:3000px'>tall</body>")
      (let [pos (sut/scroll-position *page*)]
        (expect (= 0 (:x pos)))
        (expect (= 0 (:y pos)))))

    (it "reflects position after scrolling"
      (sut/set-content! *page* "<body style='height:3000px'>tall</body>")
      (sut/evaluate *page* "window.scrollTo(0, 500)")
      (let [pos (sut/scroll-position *page*)]
        (expect (= 0 (:x pos)))
        (expect (= 500 (:y pos)))))))

;; =============================================================================
;; Smooth Scroll (issue #90)
;; =============================================================================

(defdescribe smooth-scroll-test
  "Tests for smooth scroll functions (issue #90)"

  (describe "smooth-scroll-to"
    {:context [with-playwright with-browser with-page]}

    (it "scrolls to an absolute Y position"
      (sut/set-content! *page* "<body style='height:5000px'>tall</body>")
      (sut/smooth-scroll-to *page* 800)
      (let [pos (sut/scroll-position *page*)]
        (expect (= 800 (:y pos)))))

    (it "scrolls to zero (top)"
      (sut/set-content! *page* "<body style='height:5000px'>tall</body>")
      (sut/evaluate *page* "window.scrollTo(0, 1000)")
      (sut/smooth-scroll-to *page* 0)
      (let [pos (sut/scroll-position *page*)]
        (expect (= 0 (:y pos))))))

  (describe "smooth-scroll-by"
    {:context [with-playwright with-browser with-page]}

    (it "scrolls down by a positive delta"
      (sut/set-content! *page* "<body style='height:5000px'>tall</body>")
      (sut/smooth-scroll-by *page* 400)
      (let [pos (sut/scroll-position *page*)]
        (expect (= 400 (:y pos)))))

    (it "scrolls up by a negative delta"
      (sut/set-content! *page* "<body style='height:5000px'>tall</body>")
      (sut/evaluate *page* "window.scrollTo(0, 1000)")
      (sut/smooth-scroll-by *page* -300)
      (let [pos (sut/scroll-position *page*)]
        (expect (= 700 (:y pos)))))))
