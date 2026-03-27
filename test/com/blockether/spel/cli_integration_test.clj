(ns com.blockether.spel.cli-integration-test
  "Integration tests for ALL daemon command handlers against a real browser.

   Each test group:
   1. Starts a real headless Chromium browser
   2. Injects browser state into daemon's private atoms
   3. Navigates to local test server HTML pages
   4. Calls handle-cmd for each handler and verifies real browser results
   5. Cleans up properly between test groups"
  (:require
   [clojure.string :as str]
   [charred.api :as json]
   [clojure.java.io :as io]
   [com.blockether.spel.codegen :as codegen]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.daemon :as daemon]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]
   [com.blockether.spel.allure :refer [around defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright BrowserContext ConsoleMessage]
   [java.nio.file Files Path]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- cmd
  "Calls a daemon handler directly. Returns the result map."
  [action params]
  (#'daemon/handle-cmd action params))

(defn- sci-eval-script
  "Evaluates generated :script code in SCI by stripping top-level require forms
   (SCI sandbox does not allow require)."
  [script]
  (let [code (->> (str/split-lines script)
               (remove #(str/starts-with? % "(require"))
               (str/join "\n"))]
    (cmd "sci_eval" {"code" code})))

(defn- nav!
  "Navigate to a test-server path. Returns the handler result."
  [path]
  (cmd "navigate" {"url" (str *test-server-url* path)}))

;; =============================================================================
;; Fixture — inject real browser into daemon state
;; =============================================================================

(def with-daemon-state
  "Around hook: creates a fresh context/page, injects into daemon's !state."
  (around [f]
    (let [ctx       (core/new-context core/*testing-browser*)
          pg        (core/new-page-from-context ctx)
          state-a   (deref #'daemon/!state)
          console-a (deref #'daemon/!console-messages)
          errors-a  (deref #'daemon/!page-errors)
          routes-a  (deref #'daemon/!routes)
          reqs-a    (deref #'daemon/!tracked-requests)
          old-state @state-a]
      (reset! console-a [])
      (reset! errors-a [])
      (reset! routes-a {})
      (reset! reqs-a [])
      ;; Reset TASK-013 sliding windows
      (reset! (deref #'daemon/!network-window) [])
      (reset! (deref #'daemon/!network-counter) 0)
      (reset! (deref #'daemon/!network-full) {})
      (reset! (deref #'daemon/!console-window) [])
      (reset! (deref #'daemon/!console-counter) 0)
      (reset! (deref #'daemon/!console-full) {})
      (reset! (deref #'daemon/!session-entry-count) 0)
      ;; Reset page tracking atoms
      (reset! (deref #'daemon/!pages) [])
      (reset! (deref #'daemon/!page-counter) 0)
      ;; Reset action log atoms
      (reset! (deref #'com.blockether.spel.sci-env/!action-log) [])
      (reset! (deref #'com.blockether.spel.sci-env/!action-counter) 0)
      (reset! (deref #'com.blockether.spel.sci-env/!action-log-start) 0)
      (page/on-console pg (fn [msg]
                            (swap! console-a conj
                              {:type (.type ^ConsoleMessage msg)
                               :text (.text ^ConsoleMessage msg)})
                            (#'daemon/track-console-entry! msg)))
      (page/on-page-error pg (fn [error]
                               (swap! errors-a conj
                                 {:message (str error)})))
      (page/on-response pg #'daemon/track-response!)
      (reset! state-a {:pw core/*testing-pw* :browser core/*testing-browser* :context ctx :page pg
                       :refs {} :counter 0 :headless true :session "integ-test"
                       :launch-flags {}})
      (try
        (f)
        (finally
          (try (core/close-page! pg) (catch Exception _))
          (try (.close ^BrowserContext ctx) (catch Exception _))
          (reset! state-a old-state))))))

;; =============================================================================
;; 1. Navigation
;; =============================================================================

(defdescribe navigation-integration-test
  "Integration tests for navigate, back, forward, reload"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "navigate, back, forward, reload"

    (it "navigate returns url and title"
      (let [r (nav! "/test-page")]
        (expect (str/includes? (:url r) "/test-page"))
        (expect (= "Test Page" (:title r)))
        (expect (not (contains? r :snapshot)))))

    (it "back returns to previous page"
      (nav! "/test-page")
      (nav! "/second-page")
      (let [r (cmd "back" {})]
        (expect (str/includes? (:url r) "/test-page"))))

    (it "forward goes to next page"
      (nav! "/test-page")
      (nav! "/second-page")
      (cmd "back" {})
      (let [r (cmd "forward" {})]
        (expect (str/includes? (:url r) "/second-page"))))

    (it "reload keeps the same page"
      (nav! "/test-page")
      (let [r (cmd "reload" {})]
        (expect (str/includes? (:url r) "/test-page"))))

    (it "rejects invalid single-word domain"
      (let [err (try
                  (cmd "navigate" {"url" "https://not-a-url" "raw-input" "not-a-url"})
                  nil
                  (catch Exception e (.getMessage e)))]
        (expect (some? err))
        (expect (str/includes? err "Invalid URL"))))))

;; =============================================================================
;; 2. Snapshot
;; =============================================================================

(defdescribe snapshot-integration-test
  "Integration tests for snapshot"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "snapshot variations"

    (it "basic snapshot returns tree and refs"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (string? (:snapshot r)))
        (expect (not (str/blank? (:snapshot r))))
        (expect (pos? (:refs_count r)))))

    (it "interactive filter returns snapshot"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {"interactive" "true"})]
        (expect (string? (:snapshot r)))))

    (it "compact filter returns snapshot"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {"compact" "true"})]
        (expect (string? (:snapshot r)))))

    (it "snapshot --all returns tree and refs"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {"all" true})]
        (expect (string? (:snapshot r)))
        (expect (not (str/blank? (:snapshot r))))
        (expect (pos? (:refs_count r)))))

    (it "snapshot --all on iframe page includes iframe content"
      (nav! "/iframe-page")
      (Thread/sleep 500) ;; wait for iframe to load
      (let [r (cmd "snapshot" {"all" true})]
        (expect (string? (:snapshot r)))
        (expect (pos? (:refs_count r)))))

    (it "snapshot shows [disabled] for aria-disabled elements (issue #88)"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (str/includes? (:snapshot r) "[disabled]"))
        (expect (str/includes? (:snapshot r) "Disabled Option"))))))

;; =============================================================================
;; 3. Click & Double-click
;; =============================================================================

(defdescribe click-integration-test
  "Integration tests for click and dblclick"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "click and dblclick"

    (it "click returns selector without snapshot"
      (nav! "/test-page")
      (let [r (cmd "click" {"selector" "#submit-btn"})]
        (expect (= "#submit-btn" (:clicked r)))
        (expect (not (contains? r :snapshot)))))

    (it "dblclick returns selector"
      (nav! "/test-page")
      (let [r (cmd "dblclick" {"selector" "#submit-btn"})]
        (expect (= "#submit-btn" (:dblclicked r)))))))

;; =============================================================================
;; 4. Fill, Type, Clear
;; =============================================================================

(defdescribe fill-type-clear-integration-test
  "Integration tests for fill, type, clear, get_value"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "fill → get_value → type → clear → get_value"

    (it "fill sets input value"
      (nav! "/test-page")
      (cmd "fill" {"selector" "#text-input" "value" "hello world"})
      (let [r (cmd "get_value" {"selector" "#text-input"})]
        (expect (= "hello world" (:value r)))))

    (it "type appends text"
      (nav! "/test-page")
      (cmd "fill" {"selector" "#text-input" "value" "base"})
      (cmd "type" {"selector" "#text-input" "text" " extra"})
      (let [r (cmd "get_value" {"selector" "#text-input"})]
        (expect (= "base extra" (:value r)))))

    (it "clear empties input"
      (nav! "/test-page")
      (cmd "fill" {"selector" "#text-input" "value" "some text"})
      (cmd "clear" {"selector" "#text-input"})
      (let [r (cmd "get_value" {"selector" "#text-input"})]
        (expect (= "" (:value r)))))))

;; =============================================================================
;; 5. Press / Key
;; =============================================================================

(defdescribe press-integration-test
  "Integration tests for press (issue #89)"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "press key"

    (it "press Tab without selector captures keydown on page"
      (nav! "/keyboard-page")
      (let [r (cmd "press" {"key" "Tab"})]
        (expect (= "Tab" (:pressed r))))
      ;; Verify the page's keydown listener actually captured the key
      (let [r (cmd "evaluate" {"script" "document.getElementById('last-key').textContent"})]
        (expect (= "Tab" (:result r)))))

    (it "press Escape without selector captures keydown on page"
      (nav! "/keyboard-page")
      (let [r (cmd "press" {"key" "Escape"})]
        (expect (= "Escape" (:pressed r))))
      (let [r (cmd "evaluate" {"script" "document.getElementById('last-key').textContent"})]
        (expect (= "Escape" (:result r)))))

    (it "press with selector types into element"
      (nav! "/keyboard-page")
      (cmd "click" {"selector" "#key-input"})
      (let [r (cmd "press" {"key" "a" "selector" "#key-input"})]
        (expect (= "a" (:pressed r))))
      ;; Verify the input actually received the character
      (let [r (cmd "get_value" {"selector" "#key-input"})]
        (expect (= "a" (:value r)))))

    (it "multiple presses accumulate in key-log"
      (nav! "/keyboard-page")
      (cmd "press" {"key" "Tab"})
      (cmd "press" {"key" "Enter"})
      (cmd "press" {"key" "Escape"})
      (let [r (cmd "evaluate" {"script" "document.getElementById('key-log').textContent"})]
        (expect (= "Tab,Enter,Escape" (:result r)))))))

;; =============================================================================
;; 6. Hover, Focus
;; =============================================================================

(defdescribe hover-focus-integration-test
  "Integration tests for hover and focus"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "hover and focus"

    (it "hover returns selector"
      (nav! "/test-page")
      (let [r (cmd "hover" {"selector" "#submit-btn"})]
        (expect (= "#submit-btn" (:hovered r)))))

    (it "focus returns selector"
      (nav! "/test-page")
      (let [r (cmd "focus" {"selector" "#text-input"})]
        (expect (= "#text-input" (:focused r)))))))

;; =============================================================================
;; 7. Select
;; =============================================================================

(defdescribe select-integration-test
  "Integration tests for select dropdown"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "select option"

    (it "select sets dropdown value"
      (nav! "/test-page")
      (let [r (cmd "select" {"selector" "#dropdown" "values" "b"})]
        (expect (= "#dropdown" (:selected r)))))))

;; =============================================================================
;; 8. Check / Uncheck
;; =============================================================================

(defdescribe check-uncheck-integration-test
  "Integration tests for check and uncheck"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "check and uncheck checkbox"

    (it "check makes checkbox checked"
      (nav! "/test-page")
      (cmd "check" {"selector" "#checkbox"})
      (let [r (cmd "is_checked" {"selector" "#checkbox"})]
        (expect (true? (:checked r)))))

    (it "uncheck makes checkbox unchecked"
      (nav! "/test-page")
      (cmd "check" {"selector" "#checkbox"})
      (cmd "uncheck" {"selector" "#checkbox"})
      (let [r (cmd "is_checked" {"selector" "#checkbox"})]
        (expect (false? (:checked r)))))

    (it "initially checked box reports checked"
      (nav! "/test-page")
      (let [r (cmd "is_checked" {"selector" "#checked-box"})]
        (expect (true? (:checked r)))))))

;; =============================================================================
;; 9. Get Text / HTML / URL / Title / Count / Box / Value / Attribute
;; =============================================================================

(defdescribe get-info-integration-test
  "Integration tests for content extraction handlers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "get_text, content, url, title, count, box, get_value, get_attribute"

    (it "get_text returns element text"
      (nav! "/test-page")
      (let [r (cmd "get_text" {"selector" "#heading"})]
        (expect (= "Test Heading" (:text r)))))

    (it "content returns innerHTML"
      (nav! "/test-page")
      (let [r (cmd "content" {"selector" "#heading"})]
        (expect (str/includes? (:html r) "Test Heading"))))

    (it "content without selector returns full page HTML"
      (nav! "/test-page")
      (let [r (cmd "content" {})]
        (expect (str/includes? (:html r) "Test Heading"))
        (expect (str/includes? (:html r) "<html"))))

    (it "url returns current page url"
      (nav! "/test-page")
      (let [r (cmd "url" {})]
        (expect (str/includes? (:url r) "/test-page"))))

    (it "title returns page title"
      (nav! "/test-page")
      (let [r (cmd "title" {})]
        (expect (= "Test Page" (:title r)))))

    (it "count returns element count"
      (nav! "/test-page")
      (let [r (cmd "count" {"selector" "input"})]
        (expect (pos? (:count r)))))

    (it "get_count returns element count"
      (nav! "/test-page")
      (let [r (cmd "get_count" {"selector" "button"})]
        (expect (pos? (:count r)))))

    (it "bounding_box returns dimensions"
      (nav! "/test-page")
      (let [r (cmd "bounding_box" {"selector" "#heading"})]
        (expect (map? (:box r)))
        (expect (pos? (:width (:box r))))))

    (it "get_box returns dimensions"
      (nav! "/test-page")
      (let [r (cmd "get_box" {"selector" "#heading"})]
        (expect (map? (:box r)))
        (expect (pos? (:width (:box r))))))

    (it "get_value returns input value"
      (nav! "/test-page")
      (let [r (cmd "get_value" {"selector" "#prefilled"})]
        (expect (= "initial value" (:value r)))))

    (it "get_attribute returns attribute value"
      (nav! "/test-page")
      (let [r (cmd "get_attribute" {"selector" "#link" "attribute" "href"})]
        (expect (str/includes? (:value r) "/second-page"))))))

;; =============================================================================
;; 10. Is Visible / Enabled / Checked
;; =============================================================================

(defdescribe element-state-integration-test
  "Integration tests for is_visible, is_enabled, is_checked"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "element state checks"

    (it "visible element returns true"
      (nav! "/test-page")
      (expect (true? (:visible (cmd "is_visible" {"selector" "#heading"})))))

    (it "hidden element returns false"
      (nav! "/test-page")
      (expect (false? (:visible (cmd "is_visible" {"selector" "#hidden-btn"})))))

    (it "enabled element returns true"
      (nav! "/test-page")
      (expect (true? (:enabled (cmd "is_enabled" {"selector" "#submit-btn"})))))

    (it "disabled element returns false"
      (nav! "/test-page")
      (expect (false? (:enabled (cmd "is_enabled" {"selector" "#disabled-btn"})))))

    (it "checked checkbox returns true"
      (nav! "/test-page")
      (expect (true? (:checked (cmd "is_checked" {"selector" "#checked-box"})))))

    (it "unchecked checkbox returns false"
      (nav! "/test-page")
      (expect (false? (:checked (cmd "is_checked" {"selector" "#checkbox"})))))))

;; =============================================================================
;; 11. Evaluate (JavaScript)
;; =============================================================================

(defdescribe evaluate-integration-test
  "Integration tests for JavaScript evaluation"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "evaluate JS"

    (it "reads document.title"
      (nav! "/test-page")
      (let [r (cmd "evaluate" {"script" "document.title"})]
        (expect (= "Test Page" (:result r)))))

    (it "evaluates arithmetic"
      (nav! "/test-page")
      (let [r (cmd "evaluate" {"script" "1 + 2"})]
        (expect (= 3 (long (:result r))))))

    (it "base64 flag returns encoded string"
      (nav! "/test-page")
      (let [r (cmd "evaluate" {"script" "document.title" "base64" true})]
        (expect (string? (:result r)))
        (expect (not (str/blank? (:result r))))))))

;; =============================================================================
;; 12. Screenshot
;; =============================================================================

(defdescribe screenshot-integration-test
  "Integration tests for screenshot"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "screenshot capture"

    (it "screenshot without path saves to temp"
      (nav! "/test-page")
      (let [r (cmd "screenshot" {})]
        (expect (some? (:path r)))
        (expect (pos? (:size r)))
        ;; Clean up
        (try (Files/deleteIfExists (Path/of (:path r) (into-array String [])))
          (catch Exception _))))

    (it "screenshot with explicit path"
      (nav! "/test-page")
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/pw-integ-ss.png")]
        (try
          (let [r (cmd "screenshot" {"path" tmp})]
            (expect (= tmp (:path r)))
            (expect (pos? (:size r)))
            (expect (Files/exists (Path/of tmp (into-array String []))
                      (into-array java.nio.file.LinkOption []))))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))

    (it "full-page screenshot"
      (nav! "/test-page")
      (let [r (cmd "screenshot" {"fullPage" true})]
        (expect (pos? (:size r)))
        (try (Files/deleteIfExists (Path/of (:path r) (into-array String [])))
          (catch Exception _))))))

;; =============================================================================
;; 13. Scroll
;; =============================================================================

(defdescribe scroll-integration-test
  "Integration tests for scroll"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "scroll directions"

    (it "scroll down"
      (nav! "/test-page")
      (let [r (cmd "scroll" {"direction" "down" "amount" 100})]
        (expect (= "down" (:scrolled r)))
        (expect (= 100 (:amount r)))))

    (it "scroll up"
      (nav! "/test-page")
      (let [r (cmd "scroll" {"direction" "up" "amount" 50})]
        (expect (= "up" (:scrolled r)))))))

;; =============================================================================
;; 14. ScrollIntoView
;; =============================================================================

(defdescribe scrollintoview-integration-test
  "Integration tests for scrollintoview"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "scroll into view"

    (it "scrolls element into view"
      (nav! "/test-page")
      (let [r (cmd "scrollintoview" {"selector" "#scroll-anchor"})]
        (expect (= "#scroll-anchor" (:scrolled_into_view r)))))))

;; =============================================================================
;; Scrollable Discovery + Scroll Position (issue #90)
;; =============================================================================

(defdescribe scrollable-integration-test
  "Integration tests for scrollable element discovery (issue #90)"

  (describe "find_scrollable command"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "finds scrollable elements with refs and metadata"
      (nav! "/scrollable-page")
      (let [r    (cmd "find_scrollable" {})
            els  (:elements r)
            ids  (set (map :id els))
            auto (first (filter #(= "auto-scroll" (:id %)) els))]
        ;; Finds the right elements
        (expect (contains? ids "auto-scroll"))
        (expect (contains? ids "y-scroll"))
        ;; Each has metadata
        (expect (some? auto))
        (expect (> (:scroll-height auto) (:client-height auto)))
        ;; Each has a ref
        (expect (string? (:ref auto)))
        (expect (clojure.string/starts-with? (:ref auto) "e"))))

    (it "finds nested containers (both outer and inner)"
      (nav! "/scrollable-page")
      (let [ids (set (map :id (:elements (cmd "find_scrollable" {}))))]
        (expect (contains? ids "nested-outer"))
        (expect (contains? ids "nested-inner"))))

    (it "excludes overflow:hidden elements"
      (nav! "/scrollable-page")
      (let [ids (set (map :id (:elements (cmd "find_scrollable" {}))))]
        (expect (not (contains? ids "hidden-overflow")))))

    (it "scrolls a discovered element via its ref"
      (nav! "/scrollable-page")
      (let [els  (:elements (cmd "find_scrollable" {}))
            auto (first (filter #(= "auto-scroll" (:id %)) els))
            ref  (:ref auto)
            sel  (str "[data-pw-ref='" ref "']")]
        ;; Scroll the discovered element down 200px via evaluate
        (cmd "evaluate" {"script" (str "document.querySelector(\"" sel "\").scrollTop = 200")})
        ;; Verify scroll took effect
        (let [r (cmd "evaluate" {"script" (str "document.querySelector(\"" sel "\").scrollTop")})]
          (expect (= 200 (long (:result r)))))))))

(defdescribe scroll-position-integration-test
  "Integration tests for scroll position (issue #90)"

  (describe "scroll_position command"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "returns zero on fresh page"
      (nav! "/scrollable-page")
      (let [r (cmd "scroll_position" {})]
        (expect (= 0 (:x r)))
        (expect (= 0 (:y r)))))

    (it "reflects position after scrolling"
      (nav! "/scrollable-page")
      (cmd "scroll" {"direction" "down" "amount" 500})
      (let [r (cmd "scroll_position" {})]
        (expect (= 0 (:x r)))
        (expect (= 500 (:y r)))))))

(defdescribe drag-integration-test
  "Integration tests for drag and drag-by"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "drag element to element"
    (it "drags source to target"
      (nav! "/test-page")
      (let [r (cmd "drag" {"source" "#submit-btn" "target" "#text-input"})]
        (expect (map? (:dragged r)))
        (expect (not (contains? r :snapshot)))))
    (it "drag with steps option"
      (nav! "/test-page")
      (let [r (cmd "drag" {"source" "#submit-btn" "target" "#text-input" "steps" 5})]
        (expect (map? (:dragged r)))))
    (it "drag-by with pixel offset"
      (nav! "/test-page")
      (let [r (cmd "drag-by" {"selector" "#submit-btn" "dx" 100 "dy" 0})]
        (expect (map? (:dragged_by r)))
        (expect (not (contains? r :snapshot)))))
    (it "drag-by with steps"
      (nav! "/test-page")
      (let [r (cmd "drag-by" {"selector" "#submit-btn" "dx" 50 "dy" 50 "steps" 10})]
        (expect (map? (:dragged_by r)))))))

;; =============================================================================
;; 15. KeyDown / KeyUp
;; =============================================================================

(defdescribe keydown-keyup-integration-test
  "Integration tests for keydown and keyup"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "key hold and release"

    (it "keydown Shift"
      (nav! "/test-page")
      (let [r (cmd "keydown" {"key" "Shift"})]
        (expect (= "Shift" (:keydown r)))))

    (it "keyup Shift"
      (nav! "/test-page")
      (cmd "keydown" {"key" "Shift"})
      (let [r (cmd "keyup" {"key" "Shift"})]
        (expect (= "Shift" (:keyup r)))))))

;; =============================================================================
;; 16. Wait
;; =============================================================================

(defdescribe wait-integration-test
  "Integration tests for wait variants"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "wait conditions"

    (it "wait for selector"
      (nav! "/test-page")
      (let [r (cmd "wait" {"selector" "#heading"})]
        (expect (= "#heading" (:found r)))))

    (it "wait for timeout"
      (nav! "/test-page")
      (let [r (cmd "wait" {"timeout" 100})]
        (expect (= 100 (:waited r)))))

    (it "wait for text"
      (nav! "/test-page")
      (let [r (cmd "wait" {"text" "Test Heading"})]
        (expect (= "Test Heading" (:found_text r)))))

    (it "wait for JS function"
      (nav! "/test-page")
      (let [r (cmd "wait" {"function" "window.testReady === true"})]
        (expect (true? (:function_completed r)))))

    (it "wait for load state"
      (nav! "/test-page")
      (let [r (cmd "wait" {"state" "load"})]
        (expect (= "load" (:state r)))))))

;; =============================================================================
;; 17. Mouse Control
;; =============================================================================

(defdescribe mouse-integration-test
  "Integration tests for mouse_move, mouse_down, mouse_up, mouse_wheel"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "mouse operations"

    (it "mouse_move"
      (nav! "/test-page")
      (let [r (cmd "mouse_move" {"x" 100 "y" 200})]
        (expect (= {:x 100 :y 200} (:moved r)))))

    (it "mouse_down left (default)"
      (nav! "/test-page")
      (let [r (cmd "mouse_down" {})]
        (expect (= "left" (:mouse_down r)))))

    (it "mouse_up left (default)"
      (nav! "/test-page")
      (let [r (cmd "mouse_up" {})]
        (expect (= "left" (:mouse_up r)))))

    (it "mouse_down right"
      (nav! "/test-page")
      (let [r (cmd "mouse_down" {"button" "right"})]
        (expect (= "right" (:mouse_down r)))))

    (it "mouse_up right"
      (nav! "/test-page")
      (cmd "mouse_down" {"button" "right"})
      (let [r (cmd "mouse_up" {"button" "right"})]
        (expect (= "right" (:mouse_up r)))))

    (it "mouse_wheel"
      (nav! "/test-page")
      (let [r (cmd "mouse_wheel" {"deltaY" 100})]
        (expect (= {:dx 0 :dy 100} (:wheel r)))))))

;; =============================================================================
;; 18. Set Viewport
;; =============================================================================

(defdescribe set-viewport-integration-test
  "Integration tests for set_viewport"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "viewport resize"

    (it "set_viewport changes size"
      (nav! "/test-page")
      (let [r (cmd "set_viewport" {"width" 800 "height" 600})]
        (expect (= {:width 800 :height 600} (:viewport r)))))

    (it "viewport change is reflected in JS"
      (nav! "/test-page")
      (cmd "set_viewport" {"width" 800 "height" 600})
      (let [w (:result (cmd "evaluate" {"script" "window.innerWidth"}))]
        (expect (= 800 (long w)))))))

;; =============================================================================
;; 19. Set Media
;; =============================================================================

(defdescribe set-media-integration-test
  "Integration tests for set_media"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "color scheme emulation"

    (it "set dark mode"
      (nav! "/test-page")
      (let [r (cmd "set_media" {"colorScheme" "dark"})]
        (expect (= "dark" (get-in r [:media :colorScheme])))))

    (it "set light mode"
      (nav! "/test-page")
      (let [r (cmd "set_media" {"colorScheme" "light"})]
        (expect (= "light" (get-in r [:media :colorScheme])))))))

;; =============================================================================
;; 20. Set Offline
;; =============================================================================

(defdescribe set-offline-integration-test
  "Integration tests for set_offline"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "offline toggle"

    (it "enable offline"
      (nav! "/test-page")
      (let [r (cmd "set_offline" {"enabled" true})]
        (expect (true? (:offline r)))))

    (it "disable offline"
      (nav! "/test-page")
      (cmd "set_offline" {"enabled" true})
      (let [r (cmd "set_offline" {"enabled" false})]
        (expect (false? (:offline r)))))))

;; =============================================================================
;; 21. Set Headers
;; =============================================================================

(defdescribe set-headers-integration-test
  "Integration tests for set_headers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "extra HTTP headers"

    (it "sets headers"
      (nav! "/test-page")
      (let [r (cmd "set_headers" {"headers" {"X-Custom" "test123"}})]
        (expect (true? (:headers_set r)))))))

;; =============================================================================
;; 22. Cookies
;; =============================================================================

(defdescribe cookies-integration-test
  "Integration tests for cookies_set, cookies_get, cookies_clear"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "cookie lifecycle"

    (it "set and get cookies"
      (nav! "/test-page")
      (let [page-url (:url (cmd "url" {}))]
        (cmd "cookies_set" {"name" "test_cookie" "value" "abc123" "url" page-url})
        (let [r (cmd "cookies_get" {})]
          (expect (some #(= "test_cookie" (:name %)) (:cookies r))))))

    (it "clear cookies"
      (nav! "/test-page")
      (let [page-url (:url (cmd "url" {}))]
        (cmd "cookies_set" {"name" "c1" "value" "v1" "url" page-url})
        (cmd "cookies_clear" {})
        (let [r (cmd "cookies_get" {})]
          (expect (empty? (:cookies r))))))))

;; =============================================================================
;; 23. Storage (localStorage, sessionStorage)
;; =============================================================================

(defdescribe storage-integration-test
  "Integration tests for storage_set, storage_get, storage_clear"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "localStorage"

    (it "set and get localStorage"
      (nav! "/test-page")
      (cmd "storage_set" {"type" "local" "key" "testKey" "value" "testVal"})
      (let [r (cmd "storage_get" {"type" "local" "key" "testKey"})]
        (expect (= "testVal" (:storage r)))))

    (it "clear localStorage"
      (nav! "/test-page")
      (cmd "storage_set" {"type" "local" "key" "k1" "value" "v1"})
      (cmd "storage_clear" {"type" "local"})
      (let [r (cmd "storage_get" {"type" "local" "key" "k1"})]
        (expect (nil? (:storage r))))))

  (describe "sessionStorage"

    (it "set and get sessionStorage"
      (nav! "/test-page")
      (cmd "storage_set" {"type" "session" "key" "sk" "value" "sv"})
      (let [r (cmd "storage_get" {"type" "session" "key" "sk"})]
        (expect (= "sv" (:storage r)))))

    (it "clear sessionStorage"
      (nav! "/test-page")
      (cmd "storage_set" {"type" "session" "key" "sk" "value" "sv"})
      (cmd "storage_clear" {"type" "session"})
      (let [r (cmd "storage_get" {"type" "session" "key" "sk"})]
        (expect (nil? (:storage r)))))))

;; =============================================================================
;; 24. Tabs
;; =============================================================================

(defdescribe tabs-integration-test
  "Integration tests for tab_list, tab_new, tab_switch, tab_close"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "tab lifecycle"

    (it "tab_list shows one tab initially"
      (nav! "/test-page")
      (let [r (cmd "tab_list" {})]
        (expect (= 1 (count (:tabs r))))
        (expect (true? (:active (first (:tabs r)))))))

    (it "tab_new creates a new tab"
      (nav! "/test-page")
      (cmd "tab_new" {})
      (let [r (cmd "tab_list" {})]
        (expect (= 2 (count (:tabs r))))))

    (it "tab_switch switches to tab"
      (nav! "/test-page")
      (cmd "tab_new" {"url" (str *test-server-url* "/second-page")})
      (let [r (cmd "tab_switch" {"index" 0})]
        (expect (= 0 (:tab r)))))

    (it "tab_close closes current tab"
      (nav! "/test-page")
      (let [before (count (:tabs (cmd "tab_list" {})))]
        (cmd "tab_new" {})
        (let [r (cmd "tab_close" {})]
          (expect (true? (:closed r)))
          (expect (= before (:remaining r))))))))

;; =============================================================================
;; 25. Console & Errors
;; =============================================================================

(defdescribe console-errors-integration-test
  "Integration tests for console_get, console_clear, errors_get, errors_clear"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "console messages"

    (it "console_get captures console.log from page"
      (nav! "/test-page")
      ;; Page has console.log('test-page-loaded')
      (Thread/sleep 200) ;; give listener time to fire
      (let [r (cmd "console_get" {})]
        (expect (some #(= "test-page-loaded" (:text %)) (:messages r)))))

    (it "console_clear empties messages"
      (nav! "/test-page")
      (Thread/sleep 200)
      (cmd "console_clear" {})
      (let [r (cmd "console_get" {})]
        (expect (empty? (:messages r)))))

    (it "errors_get returns errors list"
      (nav! "/test-page")
      (let [r (cmd "errors_get" {})]
        (expect (vector? (:errors r)))))

    (it "errors_clear empties errors"
      (nav! "/test-page")
      (cmd "errors_clear" {})
      (let [r (cmd "errors_get" {})]
        (expect (empty? (:errors r)))))))

;; =============================================================================
;; 26. Highlight
;; =============================================================================

(defdescribe highlight-integration-test
  "Integration tests for highlight"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "highlight element"

    (it "highlight returns selector"
      (nav! "/test-page")
      (let [r (cmd "highlight" {"selector" "#heading"})]
        (expect (= "#heading" (:highlighted r)))))))

;; =============================================================================
;; 27. State Save / Load / List / Clear
;; =============================================================================

(defdescribe state-management-integration-test
  "Integration tests for state_save, state_load, state_list, state_clear"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "state save and clear"

    (it "state_save writes file"
      (nav! "/test-page")
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/pw-test-state.json")]
        (try
          (let [r (cmd "state_save" {"path" tmp})]
            (expect (= "saved" (:state r)))
            (expect (Files/exists (Path/of tmp (into-array String []))
                      (into-array java.nio.file.LinkOption []))))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))

    (it "state_list returns list"
      (nav! "/test-page")
      (let [r (cmd "state_list" {})]
        (expect (vector? (:states r)))))

    (it "state_clear removes named file"
      (nav! "/test-page")
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/state-integ-test.json")]
        (try
          (cmd "state_save" {"path" tmp})
          (let [r (cmd "state_clear" {"name" tmp})]
            (expect (= tmp (:cleared r))))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))

    (it "state_load round-trip preserves cookies"
      (nav! "/test-page")
      ;; Set a cookie via JS
      (cmd "evaluate" {"script" "document.cookie = 'test_cookie=hello_state; path=/'"})
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/state-roundtrip.json")]
        (try
          ;; Save state (captures cookies)
          (let [save-r (cmd "state_save" {"path" tmp})]
            (expect (= "saved" (:state save-r))))
          ;; Load state into a new context (restores cookies)
          (let [load-r (cmd "state_load" {"path" tmp})]
            (expect (= "loaded" (:state load-r))))
          ;; Navigate and verify cookie survived the round-trip
          (nav! "/test-page")
          (let [cookie-val (:result (cmd "evaluate" {"script" "document.cookie"}))]
            (expect (str/includes? (str cookie-val) "test_cookie=hello_state")))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))))

;; =============================================================================
;; 28. Session Info / List
;; =============================================================================

(defdescribe session-integration-test
  "Integration tests for session_info, session_list"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "session info"

    (it "session_info returns session metadata"
      (nav! "/test-page")
      (let [r (cmd "session_info" {})]
        (expect (= "integ-test" (:session r)))
        (expect (true? (:headless r)))
        (expect (str/includes? (:url r) "/test-page"))
        (expect (= "Test Page" (:title r)))))

    (it "session_list returns vector"
      (let [r (cmd "session_list" {})]
        (expect (vector? (:sessions r)))))))

;; =============================================================================
;; 29. Find (semantic locators)
;; =============================================================================

(defdescribe find-integration-test
  "Integration tests for find by role, text, label, placeholder, alt, title, testid, first"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "semantic locators"

    (it "find by text"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "text" "value" "Test Heading" "find_action" "count"})]
        (expect (= 1 (:count r)))))

    (it "find by role button"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "role" "value" "button" "find_action" "count"})]
        (expect (pos? (:count r)))))

    (it "find by label"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "label" "value" "Name" "find_action" "visible"})]
        (expect (true? (:visible r)))))

    (it "find by placeholder"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "placeholder" "value" "Enter text" "find_action" "visible"})]
        (expect (true? (:visible r)))))

    (it "find by alt text"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "alt" "value" "Test Logo" "find_action" "visible"})]
        (expect (true? (:visible r)))))

    (it "find by title"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "title" "value" "Hover tooltip" "find_action" "visible"})]
        (expect (true? (:visible r)))))

    (it "find by testid"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "testid" "value" "submit" "find_action" "visible"})]
        (expect (true? (:visible r)))))

    (it "find first button"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "first" "value" "button" "find_action" "text"})]
        (expect (string? (:text r)))))

    ;; --- find with @e1 ref syntax ---

    (it "find first with @ref resolves snapshot ref"
      (nav! "/test-page")
      ;; Take snapshot to populate refs
      (cmd "snapshot" {})
      ;; Find first match within a ref selector — refs are [data-pw-ref="eN"] selectors
      (let [state @(deref #'daemon/!state)
            refs (:refs state)]
        ;; Pick any ref that maps to a visible element
        (when-let [ref-id (first (keys refs))]
          (let [r (cmd "find" {"by" "first" "value" (str "@" ref-id) "find_action" "visible"})]
            (expect (contains? r :visible))))))

    (it "find last with @ref resolves snapshot ref"
      (nav! "/test-page")
      (cmd "snapshot" {})
      (let [state @(deref #'daemon/!state)
            refs (:refs state)]
        (when-let [ref-id (first (keys refs))]
          (let [r (cmd "find" {"by" "last" "value" (str "@" ref-id) "find_action" "visible"})]
            (expect (contains? r :visible))))))

    (it "find first with CSS selector still works"
      (nav! "/test-page")
      (let [r (cmd "find" {"by" "first" "value" "h1" "find_action" "text"})]
        (expect (str/includes? (:text r) "Test Heading"))))))

;; =============================================================================
;; 30. Frame
;; =============================================================================

(defdescribe frame-integration-test
  "Integration tests for frame_list, frame_switch"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "frame operations"

    (it "frame_list shows frames"
      (nav! "/iframe-page")
      (Thread/sleep 500) ;; wait for iframe to load
      (let [r (cmd "frame_list" {})]
        (expect (> (count (:frames r)) 1))))

    (it "frame_switch to main"
      (nav! "/test-page")
      (let [r (cmd "frame_switch" {"selector" "main"})]
        (expect (= "main" (:frame r)))))))

;; =============================================================================
;; 31. Network Route
;; =============================================================================

(defdescribe network-route-integration-test
  "Integration tests for network_route, network_unroute"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "network route lifecycle"

    (it "network_route adds a route"
      (nav! "/test-page")
      (let [r (cmd "network_route" {"url" "**/health"
                                    "action_type" "fulfill"
                                    "body" "{\"mocked\":true}"
                                    "status" 200
                                    "content_type" "application/json"})]
        (expect (= "**/health" (:route_added r)))))

    (it "network_unroute removes all routes"
      (nav! "/test-page")
      (cmd "network_route" {"url" "**/test" "action_type" "abort"})
      (let [r (cmd "network_unroute" {})]
        (expect (true? (:all_routes_removed r)))))

    (it "process-command queues then times out when another CDP session owns route lock"
      (let [state-a       (deref #'daemon/!state)
            cdp-url       "http://127.0.0.1:9222"
            owner-session (str "owner-" (System/currentTimeMillis))
            owner-pid     (daemon/pid-file-path owner-session)
            lock-path      (#'daemon/cdp-route-lock-path cdp-url)
            ;; Set short wait/poll so test doesn't take 120s
            orig-wait @(deref #'daemon/!cdp-lock-wait-s)
            orig-poll @(deref #'daemon/!cdp-lock-poll-interval-s)]
        (reset! (deref #'daemon/!cdp-lock-wait-s) 2)
        (reset! (deref #'daemon/!cdp-lock-poll-interval-s) 1)
        (swap! state-a assoc
          :session "integ-test"
          :cdp-connected true
          :launch-flags {"cdp" cdp-url})
        (Files/writeString owner-pid
          (str (.pid (java.lang.ProcessHandle/current)))
          (into-array java.nio.file.OpenOption []))
        (Files/writeString lock-path
          (json/write-json-str {"session" owner-session
                                "cdp" cdp-url
                                "updated_at" (System/currentTimeMillis)})
          (into-array java.nio.file.OpenOption []))
        (try
          (let [resp (json/read-json
                       (#'daemon/process-command
                        (json/write-json-str {"action" "navigate"
                                              "url" (str *test-server-url* "/test-page")})))]
            (expect (= false (get resp "success")))
            (expect (= "cdp_route_lock" (get resp "error_code")))
            (expect (= owner-session (get resp "owner_session")))
            (expect (str/includes? (get resp "error") "Timed out"))
            ;; lock guard must still allow close/session cleanup commands
            (let [close-resp (json/read-json (#'daemon/process-command (json/write-json-str {"action" "close"})))]
              (expect (= true (get close-resp "success")))))
          (finally
            (reset! (deref #'daemon/!cdp-lock-wait-s) orig-wait)
            (reset! (deref #'daemon/!cdp-lock-poll-interval-s) orig-poll)
            (Files/deleteIfExists owner-pid)
            (Files/deleteIfExists lock-path)))))

    (it "process-command acquires lock when released during wait"
      (let [state-a       (deref #'daemon/!state)
            cdp-url       "http://127.0.0.1:9222"
            owner-session (str "owner-" (System/currentTimeMillis))
            owner-pid     (daemon/pid-file-path owner-session)
            lock-path      (#'daemon/cdp-route-lock-path cdp-url)
            orig-wait @(deref #'daemon/!cdp-lock-wait-s)
            orig-poll @(deref #'daemon/!cdp-lock-poll-interval-s)]
        (reset! (deref #'daemon/!cdp-lock-wait-s) 10)
        (reset! (deref #'daemon/!cdp-lock-poll-interval-s) 1)
        (swap! state-a assoc
          :session "integ-test"
          :cdp-connected true
          :launch-flags {"cdp" cdp-url})
        (Files/writeString owner-pid
          (str (.pid (java.lang.ProcessHandle/current)))
          (into-array java.nio.file.OpenOption []))
        (Files/writeString lock-path
          (json/write-json-str {"session" owner-session
                                "cdp" cdp-url
                                "updated_at" (System/currentTimeMillis)})
          (into-array java.nio.file.OpenOption []))
        ;; Delete the lock file after 2s in a background thread
        (future
          (Thread/sleep 2000)
          (Files/deleteIfExists lock-path)
          (Files/deleteIfExists owner-pid))
        (try
          ;; navigate should succeed because lock clears within the wait window
          (let [resp (json/read-json
                       (#'daemon/process-command
                        (json/write-json-str {"action" "navigate"
                                              "url" (str *test-server-url* "/test-page")})))]
            (expect (= true (get resp "success"))))
          (finally
            (reset! (deref #'daemon/!cdp-lock-wait-s) orig-wait)
            (reset! (deref #'daemon/!cdp-lock-poll-interval-s) orig-poll)
            (Files/deleteIfExists owner-pid)
            (Files/deleteIfExists lock-path)))))

    (it "exempt actions skip lock queue entirely"
      (let [state-a       (deref #'daemon/!state)
            cdp-url       "http://127.0.0.1:9222"
            owner-session (str "owner-" (System/currentTimeMillis))
            owner-pid     (daemon/pid-file-path owner-session)
            lock-path      (#'daemon/cdp-route-lock-path cdp-url)
            orig-wait @(deref #'daemon/!cdp-lock-wait-s)
            orig-poll @(deref #'daemon/!cdp-lock-poll-interval-s)]
        (reset! (deref #'daemon/!cdp-lock-wait-s) 2)
        (reset! (deref #'daemon/!cdp-lock-poll-interval-s) 1)
        (swap! state-a assoc
          :session "integ-test"
          :cdp-connected true
          :launch-flags {"cdp" cdp-url})
        (Files/writeString owner-pid
          (str (.pid (java.lang.ProcessHandle/current)))
          (into-array java.nio.file.OpenOption []))
        (Files/writeString lock-path
          (json/write-json-str {"session" owner-session
                                "cdp" cdp-url
                                "updated_at" (System/currentTimeMillis)})
          (into-array java.nio.file.OpenOption []))
        (try
          ;; url and title should pass through immediately (exempt)
          (let [url-resp (json/read-json (#'daemon/process-command (json/write-json-str {"action" "url"})))
                title-resp (json/read-json (#'daemon/process-command (json/write-json-str {"action" "title"})))]
            (expect (= true (get url-resp "success")))
            (expect (= true (get title-resp "success"))))
          (finally
            (reset! (deref #'daemon/!cdp-lock-wait-s) orig-wait)
            (reset! (deref #'daemon/!cdp-lock-poll-interval-s) orig-poll)
            (Files/deleteIfExists owner-pid)
            (Files/deleteIfExists lock-path)))))

    (it "connect warning helper returns owner session for active lock"
      (let [state-a       (deref #'daemon/!state)
            cdp-url       "http://127.0.0.1:9222"
            owner-session (str "owner-" (System/currentTimeMillis))
            owner-pid     (daemon/pid-file-path owner-session)
            lock-path     (#'daemon/cdp-route-lock-path cdp-url)]
        (swap! state-a assoc :session "integ-test")
        (Files/writeString owner-pid
          (str (.pid (java.lang.ProcessHandle/current)))
          (into-array java.nio.file.OpenOption []))
        (Files/writeString lock-path
          (json/write-json-str {"session" owner-session
                                "cdp" cdp-url
                                "updated_at" (System/currentTimeMillis)})
          (into-array java.nio.file.OpenOption []))
        (try
          (let [warning (#'daemon/cdp-route-lock-warning cdp-url)]
            (expect (= owner-session (:route_lock_owner warning)))
            (expect (str/includes? (:warning warning) "active network routes")))
          (finally
            (Files/deleteIfExists owner-pid)
            (Files/deleteIfExists lock-path)))))))

;; =============================================================================
;; 32. Network Requests
;; =============================================================================

(defdescribe network-requests-integration-test
  "Integration tests for network_requests"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "tracked requests"

    (it "network_requests returns a vector"
      (nav! "/test-page")
      (let [r (cmd "network_requests" {})]
        (expect (vector? (:requests r)))))))

;; =============================================================================
;; 33. Dialog Accept / Dismiss
;; =============================================================================

(defdescribe dialog-integration-test
  "Integration tests for dialog_accept, dialog_dismiss"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "dialog handlers"

    (it "dialog_accept registers handler"
      (nav! "/test-page")
      (let [r (cmd "dialog_accept" {})]
        (expect (= "accept" (:dialog_handler r)))))

    (it "dialog_accept with text"
      (nav! "/test-page")
      (let [r (cmd "dialog_accept" {"text" "yes"})]
        (expect (= "accept" (:dialog_handler r)))
        (expect (= "yes" (:text r)))))

    (it "dialog_dismiss registers handler"
      (nav! "/test-page")
      (let [r (cmd "dialog_dismiss" {})]
        (expect (= "dismiss" (:dialog_handler r)))))))

;; =============================================================================
;; 34. Dialog SCI functions (issue #85)
;; =============================================================================

(defdescribe dialog-sci-integration-test
  "Integration tests for dialog manipulation via SCI eval (issue #85)"

  (describe "dialog SCI functions"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "auto-accept-dialogs! accepts alert and returns nil"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs!)"})
      ;; Trigger alert — should auto-accept without hanging
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"window.alert('hello'); 'done'\")"})]
        (expect (= "\"done\"" (:result r)))))

    (it "auto-accept-dialogs! accepts confirm as true"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs!)"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"confirm('ok?') ? 'yes' : 'no'\")"})]
        (expect (= "\"yes\"" (:result r)))))

    (it "auto-dismiss-dialogs! dismisses confirm as false"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-dismiss-dialogs!)"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"confirm('ok?') ? 'yes' : 'no'\")"})]
        (expect (= "\"no\"" (:result r)))))

    (it "auto-accept-dialogs! with prompt-text fills prompt"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs! \"my-answer\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"prompt('name?', 'default')\")"})]
        (expect (= "\"my-answer\"" (:result r)))))

    (it "clear-dialog-handler! removes the handler"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs!)"})
      (cmd "sci_eval" {"code" "(spel/clear-dialog-handler!)"})
      ;; After clearing, we re-register via once-dialog with dialog-accept!
      (cmd "sci_eval" {"code" "(spel/once-dialog (fn [d] (spel/dialog-accept! d)))"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"window.alert('test'); 'ok'\")"})]
        (expect (= "\"ok\"" (:result r)))))

    (it "dialog-accept! works inside once-dialog handler"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/once-dialog (fn [d] (spel/dialog-accept! d)))"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"window.alert('hi'); 'accepted'\")"})]
        (expect (= "\"accepted\"" (:result r)))))

    (it "dialog-dismiss! works inside once-dialog handler"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/once-dialog (fn [d] (spel/dialog-dismiss! d)))"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"confirm('really?') ? 'yes' : 'no'\")"})]
        (expect (= "\"no\"" (:result r)))))

    (it "dialog-type returns dialog type string"
      (nav! "/dialog-page")
      (let [result-atom-code "(def !dialog-info (atom nil))
                              (spel/once-dialog (fn [d]
                                (reset! !dialog-info (spel/dialog-type d))
                                (spel/dialog-accept! d)))"]
        (cmd "sci_eval" {"code" result-atom-code})
        (cmd "sci_eval" {"code" "(spel/eval-js \"window.alert('test')\")"})
        (let [r (cmd "sci_eval" {"code" "@!dialog-info"})]
          (expect (= "\"alert\"" (:result r))))))

    (it "dialog-message returns dialog message text"
      (nav! "/dialog-page")
      (let [code "(def !dialog-msg (atom nil))
                  (spel/once-dialog (fn [d]
                    (reset! !dialog-msg (spel/dialog-message d))
                    (spel/dialog-accept! d)))"]
        (cmd "sci_eval" {"code" code})
        (cmd "sci_eval" {"code" "(spel/eval-js \"window.alert('hello-msg')\")"})
        (let [r (cmd "sci_eval" {"code" "@!dialog-msg"})]
          (expect (= "\"hello-msg\"" (:result r))))))

    (it "dialog-default-value returns prompt default"
      (nav! "/dialog-page")
      (let [code "(def !dialog-default (atom nil))
                  (spel/once-dialog (fn [d]
                    (reset! !dialog-default (spel/dialog-default-value d))
                    (spel/dialog-accept! d)))"]
        (cmd "sci_eval" {"code" code})
        (cmd "sci_eval" {"code" "(spel/eval-js \"prompt('q', 'my-default')\")"})
        (let [r (cmd "sci_eval" {"code" "@!dialog-default"})]
          (expect (= "\"my-default\"" (:result r))))))

    (it "replaces previous auto-accept handler without leaking"
      (nav! "/dialog-page")
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs! \"first\")"})
      (cmd "sci_eval" {"code" "(spel/auto-accept-dialogs! \"second\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"prompt('q', 'x')\")"})]
        (expect (= "\"second\"" (:result r)))))))

;; =============================================================================
;; 35. Trace Start / Stop
;; =============================================================================

(defdescribe trace-integration-test
  "Integration tests for trace_start, trace_stop"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "trace lifecycle"

    (it "trace start and stop"
      (nav! "/test-page")
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/pw-test-trace.zip")]
        (try
          (let [r1 (cmd "trace_start" {"name" "test-trace"})]
            (expect (= "started" (:trace r1))))
          (let [r2 (cmd "trace_stop" {"path" tmp})]
            (expect (= "stopped" (:trace r2)))
            (expect (Files/exists (Path/of tmp (into-array String []))
                      (into-array java.nio.file.LinkOption []))))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))))

;; =============================================================================
;; 36. Close (verify response shape, don't actually close)
;; =============================================================================

(defdescribe close-integration-test
  "Integration tests for close handler response shape"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "close response"

    (it "close returns shutdown flag"
      (nav! "/test-page")
      (let [r (cmd "close" {})]
        (expect (true? (:closed r)))
        (expect (true? (:shutdown r)))))))

(defdescribe cdp-lifecycle-integration-test
  "Integration tests for cdp disconnect/reconnect command behavior"

  (describe "cdp disconnect/reconnect"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "cdp_disconnect is safe when session is not CDP-connected"
      (nav! "/test-page")
      (let [r (cmd "cdp_disconnect" {})]
        (expect (= false (:disconnected r)))))

    (it "process-command reports clear error when cdp_reconnect has no previous connection"
      (let [resp (json/read-json
                   (#'daemon/process-command
                    (json/write-json-str {"action" "cdp_reconnect"})))]
        (expect (= false (get resp "success")))
        (expect (str/includes? (get resp "error") "No previous CDP connection found"))))))

;; =============================================================================
;; 37. Default (unknown action)
;; =============================================================================

(defdescribe default-handler-integration-test
  "Integration tests for unknown action handler"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "unknown actions"

    (it "returns error for unknown action"
      (let [r (cmd "nonexistent_action_xyz" {})]
        (expect (str/includes? (:error r) "Unknown action"))))))

;; =============================================================================
;; 38. Set Geo
;; =============================================================================

(defdescribe set-geo-integration-test
  "Integration tests for set_geo"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "geolocation"

    (it "set_geo sets geolocation"
      (nav! "/test-page")
      (let [r (cmd "set_geo" {"latitude" 37.7749 "longitude" -122.4194})]
        (expect (= {:latitude 37.7749 :longitude -122.4194}
                  (:geolocation r)))))))

;; =============================================================================
;; 39. PDF (basic shape test — Chromium headless supports PDF)
;; =============================================================================

(defdescribe pdf-integration-test
  "Integration tests for pdf"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "pdf export"

    (it "pdf returns path"
      (nav! "/test-page")
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/pw-test.pdf")]
        (try
          (let [r (cmd "pdf" {"path" tmp})]
            (expect (= tmp (:path r))))
          (finally
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))))

;; =============================================================================
;; 40. Annotate / Unannotate
;; =============================================================================

(defdescribe annotate-integration-test
  "Integration tests for annotate and unannotate"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "annotate injects overlays"

    (it "annotate returns annotated count"
      (nav! "/test-page")
      (let [r (cmd "annotate" {})]
        (expect (some? (:annotated r)))
        (expect (number? (:annotated r)))
        (expect (some? (:refs_total r)))))

    (it "annotate only annotates visible elements"
      (nav! "/test-page")
      (let [r (cmd "annotate" {})]
        ;; annotated count should be <= total refs
        (expect (<= (:annotated r) (:refs_total r)))))

    (it "annotate with options disabled still returns count"
      (nav! "/test-page")
      (let [r (cmd "annotate" {"show-badges" false
                               "show-dimensions" false
                               "show-boxes" false})]
        (expect (number? (:annotated r)))))

    (it "annotate --full annotates at least as many as viewport-only"
      (nav! "/test-page")
      (let [viewport-r (cmd "annotate" {})
            _          (cmd "unannotate" {})
            full-r     (cmd "annotate" {"full-page" true})]
        (expect (number? (:annotated full-r)))
        (expect (>= (:annotated full-r) (:annotated viewport-r))))))

  (describe "unannotate removes overlays"

    (it "unannotate returns removed true"
      (nav! "/test-page")
      ;; Annotate first, then remove
      (cmd "annotate" {})
      (let [r (cmd "unannotate" {})]
        (expect (true? (:removed r)))))

    (it "unannotate is safe when no overlays exist"
      (nav! "/test-page")
      (let [r (cmd "unannotate" {})]
        (expect (true? (:removed r)))))))

;; =============================================================================
;; 41. Helpers (survey, audit, routes, inspect, overview)
;; =============================================================================

(defdescribe helpers-integration-test
  "Integration tests for survey, audit, routes, inspect, overview helpers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "survey"

    (it "returns frames with paths"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "survey" {})]
        (expect (vector? (:frames result)))
        (expect (pos? (count (:frames result))))
        (expect (string? (:path (first (:frames result)))))
        (expect (= 0 (:y (first (:frames result)))))))

    (it "respects max-frames"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "survey" {"max-frames" 2})]
        (expect (<= (count (:frames result)) 2)))))

  (describe "audit"

    (it "returns page structure with sections"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "audit" {})]
        (expect (string? (:url result)))
        (expect (string? (:title result)))
        (expect (pos? (:scroll-height result)))
        (expect (map? (:viewport result)))
        (expect (vector? (:sections result)))))

    (it "runs all audits when all flag is set"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "audit" {"all" true})]
        (expect (map? (:structure result)))
        (expect (map? (:contrast result)))
        (expect (map? (:colors result)))
        (expect (map? (:layout result)))
        (expect (map? (:fonts result)))
        (expect (map? (:links result)))
        (expect (map? (:headings result)))))

    (it "runs only requested audits when only is set"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "audit" {"only" "contrast,layout"})]
        (expect (nil? (:structure result)))
        (expect (map? (:contrast result)))
        (expect (map? (:layout result)))
        (expect (nil? (:fonts result))))))

  (describe "markdownify"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "returns markdown for current page"
      (cmd "navigate" {"url" (str *test-server-url* "/test-page")})
      (let [result (cmd "markdownify" {})]
        (expect (string? (:markdown result)))
        (expect (str/includes? (:markdown result) "# Test Page"))
        (expect (str/includes? (:markdown result) "Submit"))))

    (it "returns markdown after 301 redirect (issue #86)"
      (cmd "navigate" {"url" (str *test-server-url* "/redirect-page")})
      (let [result (cmd "markdownify" {})]
        (expect (string? (:markdown result)))
        (expect (str/includes? (:markdown result) "Test Page")))))

  (describe "routes"

    (it "extracts links from page"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "routes" {})]
        (expect (string? (:url result)))
        (expect (number? (:count result)))
        (expect (pos? (:count result)))
        (expect (vector? (:links result)))
        (let [link (first (:links result))]
          (expect (string? (:href link)))
          (expect (contains? link :internal?)))))

    (it "filters internal links"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "routes" {"internal-only" true})]
        (expect (every? :internal? (:links result))))))

  (describe "inspect"

    (it "returns snapshot with styles"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "inspect" {})]
        (expect (string? (:tree result)))
        (expect (map? (:refs result)))
        (expect (pos? (:counter result))))))

  (describe "overview"

    (it "returns screenshot path and annotated count"
      (cmd "navigate" {"url" "https://example.com"})
      (let [result (cmd "overview" {})]
        (expect (string? (:path result)))
        (expect (pos? (:size result)))
        (expect (number? (:refs_annotated result)))))))

;; =============================================================================
;; 41a. Pre-action Markers (mark/unmark)
;; =============================================================================

(defdescribe action-markers-integration-test
  "Integration tests for pre-action markers (mark/unmark) via sci_eval"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "action markers via sci_eval"

    (it "mark returns count of marked elements"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(spel/inject-action-markers! \"@" ref "\")")})]
        (expect (= "1" (:result r)))))

    (it "mark handles multiple refs"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [refs-r (cmd "sci_eval" {"code" "(pr-str (vec (take 2 (keys (:refs (spel/capture-snapshot))))))"})
            refs   (read-string (read-string (:result refs-r)))
            ref1   (first refs)
            ref2   (second refs)
            r      (cmd "sci_eval" {"code" (str "(spel/inject-action-markers! \"@" ref1 "\" \"" ref2 "\")")})]
        (expect (pos? (parse-long (:result r))))))

    (it "unmark removes all markers"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))]
        (cmd "sci_eval" {"code" (str "(spel/inject-action-markers! \"@" ref "\")")})
        (let [r (cmd "sci_eval" {"code" "(spel/remove-action-markers!)"})]
          (expect (= "nil" (:result r))))))

    (it "mark returns 0 for non-existent refs"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(spel/inject-action-markers! \"e999\")"})]
        (expect (= "0" (:result r)))))

    (it "markers coexist with annotation overlays"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            _      (cmd "sci_eval" {"code" "(spel/inject-overlays! (:refs (read-string (str (spel/capture-snapshot)))))"})
            _mark  (cmd "sci_eval" {"code" (str "(spel/inject-action-markers! \"@" ref "\")")})
            ;; Verify markers are present (data-spel-action-marker)
            marker-check (cmd "sci_eval" {"code" "(spel/evaluate \"document.querySelectorAll('[data-spel-action-marker]').length\")"})
            ;; Verify annotations are also present (data-spel-annotate)
            annot-check  (cmd "sci_eval" {"code" "(spel/evaluate \"document.querySelectorAll('[data-spel-annotate]').length\")"})]
        (expect (pos? (parse-long (:result marker-check))))
        (expect (pos? (parse-long (:result annot-check))))
        ;; Cleanup
        (cmd "sci_eval" {"code" "(spel/remove-action-markers!)"})
        (cmd "sci_eval" {"code" "(spel/remove-overlays!)"})))))

;; =============================================================================
;; 41b. Audit Screenshots
;; =============================================================================

(defdescribe audit-screenshot-integration-test
  "Integration tests for audit-screenshot with caption"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "audit screenshot via sci_eval"

    (it "audit-screenshot returns bytes"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(type (spel/audit-screenshot \"Test caption\"))"})]
        (expect (str/includes? (:result r) "byte"))))

    (it "audit-screenshot with markers option"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/capture-snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(type (spel/audit-screenshot \"With markers\" {:markers [\"e1\"]}))"})]
        (expect (str/includes? (:result r) "byte"))
        ;; Verify markers were cleaned up
        (let [check (cmd "sci_eval" {"code" "(spel/evaluate \"document.querySelectorAll('[data-spel-action-marker]').length\")"})]
          (expect (= "0" (:result check))))))

    (it "caption is cleaned up after screenshot"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/audit-screenshot \"Temporary caption\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.querySelectorAll('[data-spel-caption]').length\")"})]
        (expect (= "0" (:result r)))))))

;; =============================================================================
;; 41c. Report Builder (report->html / report->pdf)
;; =============================================================================

(defdescribe report-builder-integration-test
  "Integration tests for report->html and report->pdf with polymorphic entries"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "report->html entry types"

    (it ":screenshot entry renders image + caption"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(let [img (spel/screenshot)
                                              html (spel/report->html [{:type :screenshot :image img :caption \"Test shot\"}])]
                                          (and (clojure.string/includes? html \"data:image/png;base64,\")
                                               (clojure.string/includes? html \"Test shot\")))"})]
        (expect (= "true" (:result r)))))

    (it ":section entry renders heading"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :section :text \"My Section\" :level 2}])]
                                          (clojure.string/includes? html \"<h2>My Section</h2>\"))"})]
        (expect (= "true" (:result r)))))

    (it ":section with page-break"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :section :text \"Break\" :level 2 :page-break true}])]
                                          (clojure.string/includes? html \"page-break\"))"})]
        (expect (= "true" (:result r)))))

    (it ":observation entry renders blue callout"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :observation :text \"Noted\" :items [\"Item A\" \"Item B\"]}])]
                                          (and (clojure.string/includes? html \"observation\")
                                               (clojure.string/includes? html \"Noted\")
                                               (clojure.string/includes? html \"Item A\")
                                               (clojure.string/includes? html \"Item B\")))"})]
        (expect (= "true" (:result r)))))

    (it ":issue entry renders orange callout"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :issue :text \"Problem found\"}])]
                                          (and (clojure.string/includes? html \"issue\")
                                               (clojure.string/includes? html \"Problem found\")))"})]
        (expect (= "true" (:result r)))))

    (it ":good entry renders green callout"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :good :text \"All good\" :items [\"Passed\"]}])]
                                          (and (clojure.string/includes? html \"good\")
                                               (clojure.string/includes? html \"All good\")
                                               (clojure.string/includes? html \"Passed\")))"})]
        (expect (= "true" (:result r)))))

    (it ":table entry renders headers and rows"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :table :headers [\"Name\" \"Value\"] :rows [[\"A\" \"1\"] [\"B\" \"2\"]]}])]
                                          (and (clojure.string/includes? html \"<th>Name</th>\")
                                               (clojure.string/includes? html \"<td>A</td>\")
                                               (clojure.string/includes? html \"<td>2</td>\")))"})]
        (expect (= "true" (:result r)))))

    (it ":meta entry renders field pairs"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :meta :fields [[\"Date\" \"2026-02-20\"] [\"Build\" \"#62\"]]}])]
                                          (and (clojure.string/includes? html \"Date\")
                                               (clojure.string/includes? html \"2026-02-20\")
                                               (clojure.string/includes? html \"#62\")))"})]
        (expect (= "true" (:result r)))))

    (it ":text entry renders paragraph"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :text :text \"Hello world\"}])]
                                          (clojure.string/includes? html \"<p>Hello world</p>\"))"})]
        (expect (= "true" (:result r)))))

    (it ":html entry passes raw content"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :html :content \"<div class='custom'>Raw</div>\"}])]
                                          (clojure.string/includes? html \"<div class='custom'>Raw</div>\"))"})]
        (expect (= "true" (:result r)))))

    (it "title option renders h1"
      (let [r (cmd "sci_eval" {"code" "(let [html (spel/report->html [{:type :text :text \"Body\"}] {:title \"My Report\"})]
                                          (clojure.string/includes? html \"<h1>My Report</h1>\"))"})]
        (expect (= "true" (:result r)))))

    (it "mixed entries render in order"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(let [img (spel/screenshot)
                                              html (spel/report->html
                                                     [{:type :section :text \"Section 1\" :level 2}
                                                      {:type :good :text \"All passed\"}
                                                      {:type :screenshot :image img :caption \"Overview\"}
                                                      {:type :table :headers [\"A\"] :rows [[\"1\"]]}]
                                                     {:title \"Mixed Report\"})]
                                          (and (string? html)
                                               (clojure.string/includes? html \"Mixed Report\")
                                               (clojure.string/includes? html \"Section 1\")
                                               (clojure.string/includes? html \"All passed\")
                                               (clojure.string/includes? html \"data:image/png;base64,\")))"})]
        (expect (= "true" (:result r)))))

    (it "unknown entry type throws error"
      (let [r (cmd "sci_eval" {"code" "(try (spel/report->html [{:type :bogus}]) (catch Exception e (str \"error:\" (.getMessage e))))"})]
        (expect (str/includes? (:result r) "Unknown report entry type")))))

  (describe "report->pdf rendering"

    (it "report->pdf returns bytes from typed entries"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(let [img (spel/screenshot)]
                                          (type (spel/report->pdf
                                                  [{:type :section :text \"Report\" :level 2}
                                                   {:type :screenshot :image img :caption \"Shot\"}])))"})]
        (expect (str/includes? (:result r) "byte"))))

    (it "report->pdf with title option"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(let [img (spel/screenshot)]
                                          (type (spel/report->pdf
                                                  [{:type :good :text \"Pass\"}
                                                   {:type :screenshot :image img :caption \"Done\"}]
                                                  {:title \"Final Report\"})))"})]
        (expect (str/includes? (:result r) "byte"))))))

;; =============================================================================
;; 41d. Console Start / Errors Start
;; =============================================================================

(defdescribe console-start-errors-start-integration-test
  "Integration tests for console_start, errors_start"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "listener registration"

    (it "console_start returns listening"
      (nav! "/test-page")
      (let [r (cmd "console_start" {})]
        (expect (= "listening" (:console r)))))

    (it "errors_start returns listening"
      (nav! "/test-page")
      (let [r (cmd "errors_start" {})]
        (expect (= "listening" (:errors r)))))))

;; =============================================================================
;; 42. SCI Eval (daemon handler)
;; =============================================================================

(defdescribe sci-eval-integration-test
  "Integration tests for the sci_eval daemon handler"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "sci_eval handler"

    (it "evaluates simple expressions"
      (let [r (cmd "sci_eval" {"code" "(+ 1 2)"})]
        (expect (= "3" (:result r)))))

    (it "evaluates string expressions"
      (let [r (cmd "sci_eval" {"code" "\"hello\""})]
        (expect (= "\"hello\"" (:result r)))))

    (it "can navigate and read title via spel functions"
      (let [_ (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
            r (cmd "sci_eval" {"code" "(spel/title)"})]
        (expect (= "\"Test Page\"" (:result r)))))

    (it "supports wait-for-url options in SCI"
      (let [r (cmd "sci_eval" {"code" (str "(do (spel/navigate \"" *test-server-url* "/test-page\") "
                                        "(spel/wait-for-url \"**/test-page\" {:timeout 5000}) :ok)")})]
        (expect (= ":ok" (:result r)))))

    (it "supports wait-for-function options in SCI"
      (let [r (cmd "sci_eval" {"code" "(do (spel/wait-for-function \"() => true\" {:timeout 5000 :polling 100}) :ok)"})]
        (expect (= ":ok" (:result r)))))

    (it "exposes clojure.string as string namespace in SCI"
      (let [r (cmd "sci_eval" {"code" "(string/includes? \"hello\" \"ell\")"})]
        (expect (= "true" (:result r)))))

    (it "exposes java.net.URLDecoder in SCI"
      (let [r (cmd "sci_eval" {"code" "(java.net.URLDecoder/decode \"a%2Bb%3D1\" \"UTF-8\")"})]
        (expect (= "\"a+b=1\"" (:result r)))))

    (it "exposes short URLDecoder class alias in SCI"
      (let [r (cmd "sci_eval" {"code" "(URLDecoder/decode \"a%2Bb%3D1\" \"UTF-8\")"})]
        (expect (= "\"a+b=1\"" (:result r)))))

    (it "exposes java.net.URLEncoder in SCI"
      (let [r (cmd "sci_eval" {"code" "(java.net.URLEncoder/encode \"a+b=1\" \"UTF-8\")"})]
        (expect (= "\"a%2Bb%3D1\"" (:result r)))))

    (it "exposes short URLEncoder class alias in SCI"
      (let [r (cmd "sci_eval" {"code" "(URLEncoder/encode \"a+b=1\" \"UTF-8\")"})]
        (expect (= "\"a%2Bb%3D1\"" (:result r)))))

    (it "exposes spel/url-encode convenience helper in SCI"
      (let [r (cmd "sci_eval" {"code" "(spel/url-encode \"a+b=1 &x y\")"})]
        (expect (= "\"a%2Bb%3D1+%26x+y\"" (:result r)))))

    (it "exposes spel/url-decode convenience helper in SCI"
      (let [r (cmd "sci_eval" {"code" "(spel/url-decode \"a%2Bb%3D1+%26x+y\")"})]
        (expect (= "\"a+b=1 &x y\"" (:result r)))))

    (it "exposes global url-encode convenience binding in SCI"
      (let [r (cmd "sci_eval" {"code" "(url-encode \"a+b=1 &x y\")"})]
        (expect (= "\"a%2Bb%3D1+%26x+y\"" (:result r)))))

    (it "exposes global url-decode convenience binding in SCI"
      (let [r (cmd "sci_eval" {"code" "(url-decode \"a%2Bb%3D1+%26x+y\")"})]
        (expect (= "\"a+b=1 &x y\"" (:result r)))))

    (it "context-cookies returns maps with keyword access in SCI (issue #84)"
      (nav! "/test-page")
      (let [page-url (:url (cmd "url" {}))
            _  (cmd "cookies_set" {"name" "issue84" "value" "fixed" "url" page-url})
            r  (cmd "sci_eval" {"code" "(let [cs (spel/context-cookies)] {:name (:name (first cs)) :value (:value (first cs))})"})]
        (expect (= "{:name \"issue84\", :value \"fixed\"}" (:result r)))))

    (it "cdp-disconnect is safe on non-CDP session in SCI"
      (let [r (cmd "sci_eval" {"code" "(spel/cdp-disconnect)"})]
        ;; Non-CDP session: disconnect-cdp! returns {:disconnected false} — not an error
        (expect (= "{:disconnected false, :cdp nil}" (:result r)))))

    (it "cdp-reconnect on non-CDP session tells you to connect first"
      (let [r (cmd "sci_eval" {"code" "(try (spel/cdp-reconnect) (catch Exception e (.getMessage e)))"})]
        ;; No previous CDP connection — clear error guiding the user
        (expect (clojure.string/includes? (:result r) "No previous CDP connection found"))))

    (it "cdp-idle-timeout returns current timeout value in SCI"
      (let [r (cmd "sci_eval" {"code" "(number? (spel/cdp-idle-timeout))"})]
        (expect (= "true" (:result r)))))

    (it "set-cdp-idle-timeout! changes the timeout from SCI"
      (let [_  (cmd "sci_eval" {"code" "(spel/set-cdp-idle-timeout! 120000)"})
            r  (cmd "sci_eval" {"code" "(spel/cdp-idle-timeout)"})]
        (expect (= "120000" (:result r)))
        ;; Restore default
        (cmd "sci_eval" {"code" "(spel/set-cdp-idle-timeout! 1800000)"})))

    (it "cdp-lock-wait returns current lock wait timeout in SCI"
      (let [r (cmd "sci_eval" {"code" "(number? (spel/cdp-lock-wait))"})]
        (expect (= "true" (:result r)))))

    (it "set-cdp-lock-wait! changes the lock wait timeout from SCI"
      (let [_  (cmd "sci_eval" {"code" "(spel/set-cdp-lock-wait! 60)"})
            r  (cmd "sci_eval" {"code" "(spel/cdp-lock-wait)"})]
        (expect (= "60" (:result r)))
        ;; Restore default
        (cmd "sci_eval" {"code" "(spel/set-cdp-lock-wait! 120)"})))

    (it "session-idle-timeout returns current timeout value in SCI"
      (let [r (cmd "sci_eval" {"code" "(number? (spel/session-idle-timeout))"})]
        (expect (= "true" (:result r)))))

    (it "set-session-idle-timeout! changes the timeout from SCI"
      (let [_  (cmd "sci_eval" {"code" "(spel/set-session-idle-timeout! 900000)"})
            r  (cmd "sci_eval" {"code" "(spel/session-idle-timeout)"})]
        (expect (= "900000" (:result r)))
        ;; Restore default
        (cmd "sci_eval" {"code" "(spel/set-session-idle-timeout! 1800000)"})))

    (it "exposes new spel helper functions"
      (let [_         (cmd "sci_eval" {"code" "(spel/navigate \"https://example.com\")"})
            survey-r  (cmd "sci_eval" {"code" "(vector? (spel/survey {:max-frames 1}))"})
            audit-r   (cmd "sci_eval" {"code" "(map? (spel/audit))"})
            md-r      (cmd "sci_eval" {"code" "(string? (spel/markdownify))"})
            routes-r  (cmd "sci_eval" {"code" "(map? (spel/routes))"})
            inspect-r (cmd "sci_eval" {"code" "(map? (spel/inspect))"})
            over-r    (cmd "sci_eval" {"code" "(map? (spel/overview))"})]
        (expect (= "true" (:result survey-r)))
        (expect (= "true" (:result audit-r)))
        (expect (= "true" (:result md-r)))
        (expect (= "true" (:result routes-r)))
        (expect (= "true" (:result inspect-r)))
        (expect (= "true" (:result over-r)))))

    (it "press with single arg does page-level keyboard press (issue #89)"
      (nav! "/keyboard-page")
      (cmd "sci_eval" {"code" "(spel/press \"Escape\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.getElementById('last-key').textContent\")"})]
        (expect (= "\"Escape\"" (:result r)))))

    (it "keyboard-press sends key to page (issue #89)"
      (nav! "/keyboard-page")
      (cmd "sci_eval" {"code" "(spel/keyboard-press \"Tab\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.getElementById('last-key').textContent\")"})]
        (expect (= "\"Tab\"" (:result r)))))

    (it "press two-arg form presses on specific element (issue #89)"
      (nav! "/keyboard-page")
      (cmd "sci_eval" {"code" "(spel/click \"#key-input\")"})
      (cmd "sci_eval" {"code" "(spel/press \"#key-input\" \"a\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/input-value \"#key-input\")"})]
        (expect (= "\"a\"" (:result r)))))

    (it "scrollable finds scrollable elements with refs via SCI (issue #90)"
      (nav! "/scrollable-page")
      (let [r (cmd "sci_eval" {"code" "(let [els (spel/scrollable)] (mapv :id els))"})]
        (expect (clojure.string/includes? (:result r) "auto-scroll"))
        (expect (clojure.string/includes? (:result r) "y-scroll"))
        (expect (clojure.string/includes? (:result r) "nested-outer"))
        (expect (clojure.string/includes? (:result r) "nested-inner"))
        (expect (not (clojure.string/includes? (:result r) "hidden-overflow")))))

    (it "scrollable returns refs usable for scrolling via SCI (issue #90)"
      (nav! "/scrollable-page")
      (cmd "sci_eval" {"code" "
        (let [els (spel/scrollable)
              auto (first (filter #(= \"auto-scroll\" (:id %)) els))
              sel (str \"[data-pw-ref='\" (:ref auto) \"']\")]
          (spel/evaluate (str \"document.querySelector(\\\"\" sel \"\\\").scrollTop = 200\")))
        "})
      (let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.getElementById('auto-scroll').scrollTop\")"})]
        (expect (= "200" (:result r)))))

    (it "scroll-position returns current position via SCI (issue #90)"
      (nav! "/scrollable-page")
      (let [r (cmd "sci_eval" {"code" "(spel/scroll-position)"})]
        (expect (clojure.string/includes? (:result r) ":x 0"))
        (expect (clojure.string/includes? (:result r) ":y 0"))))

    (it "smooth-scroll-to scrolls and updates position via SCI (issue #90)"
      (nav! "/scrollable-page")
      (cmd "sci_eval" {"code" "(spel/smooth-scroll-to 600)"})
      (let [r (cmd "sci_eval" {"code" "(:y (spel/scroll-position))"})]
        (expect (= "600" (:result r)))))

    (it "smooth-scroll-by scrolls relative via SCI (issue #90)"
      (nav! "/scrollable-page")
      (cmd "sci_eval" {"code" "(spel/smooth-scroll-by 300)"})
      (let [r (cmd "sci_eval" {"code" "(:y (spel/scroll-position))"})]
        (expect (= "300" (:result r)))))

    (it "snapshot with styles includes scroll metrics for overflow containers via SCI (issue #96)"
      (nav! "/scrollable-page")
      (let [r (cmd "sci_eval" {"code" "
              (let [snap (spel/capture-snapshot {:styles true})
                    refs (vals (:refs snap))
                    with-scroll (filter #(get-in % [:styles \"scroll-height\"]) refs)]
                (count with-scroll))"})]
        (expect (pos? (parse-long (:result r))))))

    (it "start! is a no-op when daemon has page"
      (let [r (cmd "sci_eval" {"code" "(spel/start!)"})]
        (expect (= ":started" (:result r)))))

    (it "persists defs between eval calls"
      (cmd "sci_eval" {"code" "(def my-val 42)"})
      (let [r (cmd "sci_eval" {"code" "my-val"})]
        (expect (= "42" (:result r)))))

    (it "binds *command-line-args* to nil when no args"
      (let [r (cmd "sci_eval" {"code" "*command-line-args*"})]
        (expect (= "nil" (:result r)))))

    (it "binds *command-line-args* to args vector"
      (let [r (cmd "sci_eval" {"code" "*command-line-args*"
                               "args" ["foo" "bar"]})]
        (expect (= "[\"foo\" \"bar\"]" (:result r)))))

    (it "command-line args reset between calls"
      (cmd "sci_eval" {"code" "*command-line-args*" "args" ["first"]})
      (let [r (cmd "sci_eval" {"code" "*command-line-args*"})]
        (expect (= "nil" (:result r)))))

    ;; --- page/ namespace (raw Page-arg functions) ---

    (it "page/navigate and page/title with explicit page arg"
      (let [_ (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
            r (cmd "sci_eval" {"code" "(page/title (spel/page))"})]
        (expect (= "\"Test Page\"" (:result r)))))

    (it "page/url returns current URL"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(page/url (spel/page))"})]
        (expect (str/includes? (:result r) "/test-page"))))

    (it "page/locator creates a locator"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(str (type (page/locator (spel/page) \"h1\")))"})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "page/get-by-role finds elements"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(locator/count-elements (page/get-by-role (spel/page) AriaRole/BUTTON))"})]
        (expect (pos? (parse-long (:result r))))))

    ;; --- locator/ namespace (raw Locator-arg functions) ---

    (it "locator/text-content reads element text"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(locator/text-content (page/locator (spel/page) \"h1\"))"})]
        (expect (str/includes? (:result r) "Test Heading"))))

    (it "locator/is-visible? checks visibility"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(locator/is-visible? (page/locator (spel/page) \"h1\"))"})]
        (expect (= "true" (:result r)))))

    (it "locator/count-elements counts matches"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(locator/count-elements (page/locator (spel/page) \"button\"))"})]
        (expect (pos? (parse-long (:result r))))))

    (it "locator/fill and locator/input-value round-trip"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(locator/fill (page/get-by-label (spel/page) \"Name\") \"test-value\")"})
      (let [r (cmd "sci_eval" {"code" "(locator/input-value (page/get-by-label (spel/page) \"Name\"))"})]
        (expect (= "\"test-value\"" (:result r)))))

    ;; --- core/ macros (with-playwright, with-browser, with-page) ---

    (it "core/with-playwright expands and binds nil"
      (let [r (cmd "sci_eval" {"code" "(core/with-playwright [pw] (nil? pw))"})]
        (expect (= "true" (:result r)))))

    (it "core/with-browser binds the daemon browser"
      (let [r (cmd "sci_eval" {"code" "(core/with-browser [b (core/launch-chromium nil)] (some? b))"})]
        (expect (= "true" (:result r)))))

    (it "core/with-context binds the daemon context"
      (let [r (cmd "sci_eval" {"code" "(core/with-context [ctx (core/new-context nil)] (some? ctx))"})]
        (expect (= "true" (:result r)))))

    (it "core/with-page binds the daemon page"
      (let [r (cmd "sci_eval" {"code" "(core/with-page [p (core/new-page-from-context nil)] (some? p))"})]
        (expect (= "true" (:result r)))))

    (it "nested core/with-* chain works like codegen output"
      (cmd "sci_eval" {"code" (str "(page/navigate (spel/page) \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code"
                               (str "(core/with-playwright [pw]"
                                 "  (core/with-browser [browser (core/launch-chromium pw)]"
                                 "    (core/with-context [ctx (core/new-context browser)]"
                                 "      (core/with-page [pg (core/new-page-from-context ctx)]"
                                 "        (page/title pg)))))")})]
        (expect (= "\"Test Page\"" (:result r)))))

    ;; --- core/ launch functions ---

    (it "core/launch-chromium returns daemon browser"
      (let [r (cmd "sci_eval" {"code" "(some? (core/launch-chromium nil))"})]
        (expect (= "true" (:result r)))))

    (it "core/new-context returns daemon context"
      (let [r (cmd "sci_eval" {"code" "(some? (core/new-context nil))"})]
        (expect (= "true" (:result r)))))

    (it "core/new-page-from-context returns daemon page"
      (let [r (cmd "sci_eval" {"code" "(some? (core/new-page-from-context nil))"})]
        (expect (= "true" (:result r)))))

    ;; --- Full qualified require ---

    (it "require com.blockether.spel.page works"
      (let [r (cmd "sci_eval" {"code" "(do (require '[com.blockether.spel.page :as p]) (fn? p/navigate))"})]
        (expect (= "true" (:result r)))))

    (it "require com.blockether.spel.locator works"
      (let [r (cmd "sci_eval" {"code" "(do (require '[com.blockether.spel.locator :as l]) (fn? l/click))"})]
        (expect (= "true" (:result r)))))

    (it "require com.blockether.spel.core works"
      (let [r (cmd "sci_eval" {"code" "(do (require '[com.blockether.spel.core :as c]) (fn? c/close!))"})]
        (expect (= "true" (:result r)))))

    ;; --- Enum access in SCI eval ---
    ;; NOTE: daemon sci_eval returns (pr-str result), so (str Enum/VALUE) produces
    ;; "\"VALUE\"" (double-quoted). Use (some? ...) which yields "true" via pr-str.

    (it "AriaRole enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? AriaRole/BUTTON)"})]
        (expect (= "true" (:result r)))))

    (it "LoadState enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? LoadState/LOAD)"})]
        (expect (= "true" (:result r)))))

    (it "WaitUntilState enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? WaitUntilState/DOMCONTENTLOADED)"})]
        (expect (= "true" (:result r)))))

    (it "MouseButton enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? MouseButton/LEFT)"})]
        (expect (= "true" (:result r)))))

    (it "ColorScheme enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? ColorScheme/DARK)"})]
        (expect (= "true" (:result r)))))

    (it "ScreenshotType enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? ScreenshotType/PNG)"})]
        (expect (= "true" (:result r)))))

    (it "WaitForSelectorState enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? WaitForSelectorState/VISIBLE)"})]
        (expect (= "true" (:result r)))))

    (it "HarMode enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? HarMode/FULL)"})]
        (expect (= "true" (:result r)))))

    (it "ServiceWorkerPolicy enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? ServiceWorkerPolicy/ALLOW)"})]
        (expect (= "true" (:result r)))))

    (it "Media enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? Media/SCREEN)"})]
        (expect (= "true" (:result r)))))

    (it "ForcedColors enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? ForcedColors/ACTIVE)"})]
        (expect (= "true" (:result r)))))

    (it "ReducedMotion enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? ReducedMotion/REDUCE)"})]
        (expect (= "true" (:result r)))))

    (it "HarContentPolicy enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? HarContentPolicy/EMBED)"})]
        (expect (= "true" (:result r)))))

    (it "HarNotFound enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? HarNotFound/ABORT)"})]
        (expect (= "true" (:result r)))))

    (it "RouteFromHarUpdateContentPolicy enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? RouteFromHarUpdateContentPolicy/EMBED)"})]
        (expect (= "true" (:result r)))))

    (it "SameSiteAttribute enum is accessible"
      (let [r (cmd "sci_eval" {"code" "(some? SameSiteAttribute/STRICT)"})]
        (expect (= "true" (:result r)))))

    ;; --- @eN ref auto-resolution in SCI ---

    (it "spel/click with @eN ref resolves via snapshot"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(do (spel/click \"@" ref "\") :clicked)")})]
        (expect (= ":clicked" (:result r)))))

    (it "spel/locator auto-resolves @eN to a Locator"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(str (type (spel/locator \"@" ref "\")))")})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/locator auto-resolves eN (without @) to a Locator"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(str (type (spel/locator \"" ref "\")))")})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/text-content reads text content via @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(string? (spel/text-content \"@" ref "\"))")})]
        (expect (= "true" (:result r)))))

    (it "spel/visible? works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(boolean? (spel/visible? \"@" ref "\"))")})]
        (expect (= "true" (:result r)))))

    (it "spel/highlight works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(do (spel/highlight \"@" ref "\") :highlighted)")})]
        (expect (= ":highlighted" (:result r)))))

    (it "spel/locator still works with regular CSS selectors"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(str (type (spel/locator \"h1\")))"})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/assert-visible works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(do (spel/assert-visible \"@" ref "\") :passed)")})]
        (expect (= ":passed" (:result r)))))

    (it "spel/get-by-ref returns Locator for valid ref"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(str (type (spel/get-by-ref \"" ref "\")))")})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/get-by-ref strips @ prefix"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(str (type (spel/get-by-ref \"@" ref "\")))")})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "page/get-by-ref works via SCI qualified require"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [snap (cmd "sci_eval" {"code" "(first (keys (:refs (spel/capture-snapshot))))"})
            ref  (read-string (:result snap))
            r    (cmd "sci_eval" {"code" (str "(str (type (page/get-by-ref (spel/page) \"" ref "\")))")})]
        (expect (str/includes? (:result r) "Locator"))))

    ;; --- stdout/stderr capture ---

    (it "captures stdout from println"
      (let [r (cmd "sci_eval" {"code" "(println \"hello stdout\") 42"})]
        (expect (= "42" (:result r)))
        (expect (= "hello stdout\n" (str/replace (str (:stdout r)) "\r\n" "\n")))))

    (it "captures stderr from binding *out* *err*"
      (let [r (cmd "sci_eval" {"code" "(binding [*out* *err*] (println \"hello stderr\")) 99"})]
        (expect (= "99" (:result r)))
        (expect (= "hello stderr\n" (str/replace (str (:stderr r)) "\r\n" "\n")))))

    (it "captures both stdout and stderr simultaneously"
      (let [r (cmd "sci_eval" {"code" "(println \"out\") (binding [*out* *err*] (println \"err\")) :done"})]
        (expect (= ":done" (:result r)))
        (expect (= "out\n" (str/replace (str (:stdout r)) "\r\n" "\n")))
        (expect (= "err\n" (str/replace (str (:stderr r)) "\r\n" "\n")))))

    (it "omits stdout/stderr keys when no output"
      (let [r (cmd "sci_eval" {"code" "(+ 1 2)"})]
        (expect (= "3" (:result r)))
        (expect (nil? (:stdout r)))
        (expect (nil? (:stderr r)))))

    (it "preserves stdout on error — included in error response map"
      (let [result (cmd "sci_eval" {"code" "(println \"before boom\") (throw (ex-info \"boom\" {}))"})]
        (expect (false? (:success result)))
        (expect (= "before boom\n" (str/replace (str (:stdout result)) "\r\n" "\n")))))

    ;; --- Console/error auto-inclusion in sci_eval response ---

    (it "sci_eval includes console messages from page in response"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      ;; Page has console.log('test-page-loaded') on load — clear and trigger fresh
      (cmd "console_clear" {})
      (let [r (cmd "sci_eval" {"code" "(spel/evaluate \"console.log('eval-console-test')\" )"})]
        (expect (= "nil" (:result r)))
        (expect (some? (:console r)))
        (expect (some #(= "eval-console-test" (:text %)) (:console r)))))

    (it "sci_eval includes page errors in response"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (cmd "errors_clear" {})
      ;; Trigger an async error and wait inside the eval for it to fire+propagate
      (let [r (cmd "sci_eval" {"code" (str "(spel/evaluate \"setTimeout(function(){ throw new Error('test-page-err'); }, 0)\")"
                                        "(spel/wait-for-timeout 300)"
                                        ":done")})]
        (expect (= ":done" (:result r)))
        ;; Page errors captured during this eval should be in the response
        (expect (some? (:page-errors r)))
        (expect (some #(str/includes? (:message %) "test-page-err") (:page-errors r)))))

    (it "sci_eval omits console/page-errors keys when none during eval"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      ;; Clear existing messages
      (cmd "console_clear" {})
      (cmd "errors_clear" {})
      ;; Eval pure computation — no console output
      (let [r (cmd "sci_eval" {"code" "(+ 100 200)"})]
        (expect (= "300" (:result r)))
        (expect (nil? (:console r)))
        (expect (nil? (:page-errors r)))))

    (it "sci_eval includes console messages even on eval error"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (cmd "console_clear" {})
      (let [result (cmd "sci_eval" {"code" "(do (spel/evaluate \"console.warn('before-error')\") (throw (ex-info \"boom\" {})))"})]
        (expect (false? (:success result)))
        (expect (some? (:console result)))
        (expect (some #(= "before-error" (:text %)) (:console result)))))

    ;; --- Help function ---
    ;; spel/help prints to stdout (captured as :stdout in daemon response)
    ;; and returns nil (captured as :result "nil")

    (it "spel/help with no args lists namespaces"
      (let [r (cmd "sci_eval" {"code" "(spel/help)"})]
        (expect (= "nil" (:result r)))
        (expect (str/includes? (:stdout r) "spel/"))
        (expect (str/includes? (:stdout r) "snapshot/"))
        (expect (str/includes? (:stdout r) "annotate/"))
        (expect (str/includes? (:stdout r) "Usage:"))))

    (it "spel/help with namespace shows function table"
      (let [r (cmd "sci_eval" {"code" "(spel/help \"spel\")"})]
        (expect (str/includes? (:stdout r) "spel/click"))
        (expect (str/includes? (:stdout r) "spel/navigate"))
        (expect (str/includes? (:stdout r) "Arglists"))))

    (it "spel/help with search term finds matches"
      (let [r (cmd "sci_eval" {"code" "(spel/help \"click\")"})]
        (expect (str/includes? (:stdout r) "click"))
        (expect (str/includes? (:stdout r) "match"))))

    (it "spel/help with ns/fn shows specific function"
      (let [r (cmd "sci_eval" {"code" "(spel/help \"spel/navigate\")"})]
        (expect (str/includes? (:stdout r) "spel/navigate"))
        (expect (str/includes? (:stdout r) "Arglists:"))))

    (it "spel/help returns nil"
      (let [r (cmd "sci_eval" {"code" "(spel/help \"spel/navigate\")"})]
        (expect (= "nil" (:result r)))))

    ;; --- spel/navigate ---

    (it "spel/navigate works"
      (let [_ (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
            r (cmd "sci_eval" {"code" "(spel/title)"})]
        (expect (= "\"Test Page\"" (:result r)))))

    (it "spel/navigate works alongside page/navigate"
      (let [_ (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
            r (cmd "sci_eval" {"code" "(page/url (spel/page))"})]
        (expect (str/includes? (:result r) "/test-page"))))

    (it "spel/drag-to drags element"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(spel/drag-to \"#submit-btn\" \"#text-input\")"})]
        (expect (= "nil" (:result r)))))

    (it "spel/drag-by drags by offset"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(spel/drag-by \"#submit-btn\" 100 0)"})]
        (expect (= "nil" (:result r)))))

    ;; --- File I/O in SCI ---

    (it "slurp reads files"
      (let [tmp-path (str (Files/createTempFile "spel-test" ".txt" (into-array java.nio.file.attribute.FileAttribute [])))
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-path (clojure.string/replace tmp-path "\\" "\\\\")
            _ (spit tmp-path "hello-sci")
            r (cmd "sci_eval" {"code" (str "(slurp \"" escaped-path "\")")})]
        (expect (= "\"hello-sci\"" (:result r)))
        (io/delete-file tmp-path true)))

    (it "spit writes files"
      (let [tmp-path (str (Files/createTempFile "spel-test" ".txt" (into-array java.nio.file.attribute.FileAttribute [])))
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-path (clojure.string/replace tmp-path "\\" "\\\\")
            _ (cmd "sci_eval" {"code" (str "(spit \"" escaped-path "\" \"written-from-sci\")")})
            content (slurp tmp-path)]
        (expect (= "written-from-sci" content))
        (io/delete-file tmp-path true)))

    (it "io/file creates File objects"
      ;; Use java.io.tmpdir for cross-platform compatibility
      (let [tmpdir (System/getProperty "java.io.tmpdir")
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-tmpdir (clojure.string/replace tmpdir "\\" "\\\\")
            r (cmd "sci_eval" {"code" (str "(str (io/file \"" escaped-tmpdir "\"))")})]
        (expect (some? (:result r)))))

    (it "io/file is available via clojure.java.io alias"
      ;; Use java.io.tmpdir for cross-platform compatibility
      (let [tmpdir (System/getProperty "java.io.tmpdir")
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-tmpdir (clojure.string/replace tmpdir "\\" "\\\\")
            r (cmd "sci_eval" {"code" (str "(do (require '[clojure.java.io :as cjio]) (str (cjio/file \"" escaped-tmpdir "\")))")})]
        (expect (some? (:result r)))))

    (it "Base64 encoder/decoder works"
      (let [r (cmd "sci_eval" {"code" "(.encodeToString (java.util.Base64/getEncoder) (.getBytes \"hello\"))"})]
        (expect (= "\"aGVsbG8=\"" (:result r)))))

    (it "java.io.File class is accessible"
      ;; Use java.io.tmpdir for cross-platform compatibility
      (let [tmpdir (System/getProperty "java.io.tmpdir")
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-tmpdir (clojure.string/replace tmpdir "\\" "\\\\")
            r (cmd "sci_eval" {"code" (str "(.exists (java.io.File. \"" escaped-tmpdir "\"))")})]
        (expect (= "true" (:result r)))))

    (it "java.nio.file.Paths creates Path"
      ;; Use java.io.tmpdir for cross-platform compatibility
      (let [tmpdir (System/getProperty "java.io.tmpdir")
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-tmpdir (clojure.string/replace tmpdir "\\" "\\\\")
            r (cmd "sci_eval" {"code" (str "(str (java.nio.file.Paths/get \"" escaped-tmpdir "\" (into-array String [])))")})]
        (expect (some? (:result r)))))

    (it "java.nio.file.Files/exists works"
      ;; Use java.io.tmpdir for cross-platform compatibility
      (let [tmpdir (System/getProperty "java.io.tmpdir")
            ;; Escape backslashes for Windows paths in SCI code strings
            escaped-tmpdir (clojure.string/replace tmpdir "\\" "\\\\")
            r (cmd "sci_eval" {"code" (str "(java.nio.file.Files/exists (java.nio.file.Paths/get \"" escaped-tmpdir "\" (into-array String [])) (into-array java.nio.file.LinkOption []))")})]
        (expect (= "true" (:result r)))))

    ;; --- Destructive tests last (stop! nils daemon page state) ---

    (it "stop! does not kill daemon browser"
      (let [_ (cmd "sci_eval" {"code" "(spel/stop!)"})
            ;; After stop!, daemon re-syncs state on next call, so page still works
            r (cmd "sci_eval" {"code" "(+ 10 20)"})]
        (expect (= "30" (:result r)))))

    (it "returns error for missing code param"
      (let [threw? (try (cmd "sci_eval" {}) false
                     (catch Exception _ true))]
        (expect threw?)))))

    ;; --- Computed styles via SCI ---

(it "spel/get-styles returns map of CSS properties"
  (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
  (let [r (cmd "sci_eval" {"code" "(map? (spel/get-styles \"h1\"))"})]
    (expect (= "true" (:result r)))))

(it "spel/get-styles with :full returns many properties"
  (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
  (let [r (cmd "sci_eval" {"code" "(> (count (spel/get-styles \"h1\" {:full true})) 20)"})]
    (expect (= "true" (:result r)))))

    ;; --- Clipboard via SCI ---

(it "spel/clipboard-copy and spel/clipboard-read round-trip"
  (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
  (let [_ (cmd "sci_eval" {"code" "(spel/clipboard-copy \"sci-clipboard-test\")"})
        r (cmd "sci_eval" {"code" "(spel/clipboard-read)"})]
    (expect (= "\"sci-clipboard-test\"" (:result r)))))

(defdescribe sci-visual-diff-integration-test
  "Integration tests for SCI screenshot comparison helpers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "spel/compare-screenshots and spel/compare-screenshot-files"

    (it "compares screenshot bytes and files in SCI"
      (cmd "sci_eval" {"code" (str "(spel/navigate \"" *test-server-url* "/test-page\")")})
      (let [tmpdir         (System/getProperty "java.io.tmpdir")
            baseline-path  (str tmpdir java.io.File/separator "spel-sci-baseline-" (System/currentTimeMillis) ".png")
            current-path   (str tmpdir java.io.File/separator "spel-sci-current-" (System/currentTimeMillis) ".png")
            escaped-base   (str/replace baseline-path "\\" "\\\\")
            escaped-current (str/replace current-path "\\" "\\\\")
            _              (cmd "sci_eval" {"code" (str "(spel/screenshot \"" escaped-base "\")")})
            _              (cmd "sci_eval" {"code" (str "(spel/screenshot \"" escaped-current "\")")})
            bytes-r        (cmd "sci_eval" {"code" "(:matched (spel/compare-screenshots (spel/screenshot) (spel/screenshot)))"})
            files-r        (cmd "sci_eval" {"code" (str "(:matched (spel/compare-screenshot-files \""
                                                     escaped-base "\" \"" escaped-current "\"))")})]
        (expect (= "true" (:result bytes-r)))
        (expect (= "true" (:result files-r)))))))

;; =============================================================================
;; 43. Codegen → SCI Eval Round-Trip (Clojure ↔ SCI compatibility)
;; =============================================================================

(defdescribe codegen-sci-eval-round-trip-test
  "Ultimate compatibility test: codegen generates Clojure, SCI evaluates it.
   Verifies that ALL codegen output formats produce valid code and that
   the :script format runs correctly in SCI eval-sci mode."

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "codegen format generation"

    (it "generates valid :body format from JSONL"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            body (binding [codegen/*exit-on-error* false]
                   (codegen/jsonl-str->clojure jsonl {:format :body}))]
        (expect (str/includes? body "page/navigate"))
        (expect (str/includes? body "assert/contains-text"))
        (expect (str/includes? body *test-server-url*))))

    (it "generates valid :test format from JSONL"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            test-code (binding [codegen/*exit-on-error* false]
                        (codegen/jsonl-str->clojure jsonl {:format :test}))]
        (expect (str/includes? test-code "defdescribe"))
        (expect (str/includes? test-code "core/with-testing-page"))
        (expect (not (str/includes? test-code "core/with-playwright")))
        (expect (str/includes? test-code "page/navigate"))
        (expect (str/includes? test-code "assert/contains-text"))))

    (it "generates valid :script format from JSONL"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))]
        (expect (str/includes? script "(require"))
        (expect (str/includes? script "core/with-testing-page"))
        (expect (not (str/includes? script "core/with-playwright")))
        (expect (str/includes? script "page/navigate"))
        (expect (str/includes? script "assert/contains-text")))))

  (describe "codegen :script format evaluates in SCI"

    (it "navigate + assertText script runs end-to-end in SCI eval"
      ;; Build JSONL recording: navigate to test page, assert heading text
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            ;; Generate script format (what `spel codegen --format=script` produces)
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            ;; Evaluate in SCI — this is the ultimate Clojure↔SCI compatibility test
            r (sci-eval-script script)]
        ;; assert/contains-text returns nil on success (throws on failure)
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "navigate + click + fill script runs in SCI eval"
      ;; More complex recording: navigate, fill a form field, click button
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"fill\",\"selector\":\"input[id=text-input]\",\"text\":\"codegen-test\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"click\",\"selector\":\"#submit-btn\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            r (sci-eval-script script)]
        ;; click returns nil on success
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with get-by-role locator runs in SCI eval"
      ;; Recording using structured locator map (Playwright 1.58+ format)
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"internal:role=heading\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"heading\",\"options\":{\"attrs\":[]}}}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            ;; Verify the generated code uses role/heading
            _ (expect (str/includes? script "role/heading"))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with get-by-label locator runs in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"fill\",\"text\":\"label-test\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"label\",\"body\":\"Name\",\"options\":{}}}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            _ (expect (str/includes? script "page/get-by-label"))
            r (sci-eval-script script)]
        ;; fill returns nil on success
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with assertVisible runs in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertVisible\",\"selector\":\"h1\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            _ (expect (str/includes? script "assert/is-visible"))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with assertChecked runs in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertChecked\",\"selector\":\"#checked-box\",\"checked\":true,\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            _ (expect (str/includes? script "assert/is-checked"))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with assertValue runs in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertValue\",\"selector\":\"#prefilled\",\"value\":\"initial value\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            _ (expect (str/includes? script "assert/has-value"))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "script with multiple actions and assertions runs in SCI eval"
      ;; Full scenario: navigate → fill → assertValue → click → assertVisible
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"fill\",\"text\":\"round-trip-test\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"label\",\"body\":\"Name\",\"options\":{}}}\n"
                    "{\"name\":\"assertValue\",\"selector\":\"#text-input\",\"value\":\"round-trip-test\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"button\",\"options\":{\"attrs\":[{\"name\":\"name\",\"value\":\"Submit\"}]}}}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Test Heading\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "project recording.jsonl generates valid script"
      ;; Verify the actual project recording.jsonl can be codegen'd
      (let [jsonl (slurp "test/com/blockether/spel/recording.jsonl")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))]
        (expect (str/includes? script "core/with-testing-page"))
        (expect (str/includes? script "page/navigate"))
        (expect (str/includes? script "role/link"))
        (expect (str/includes? script "assert/contains-text"))))

    ;; --- Negative tests: prove failures are actually detected, not swallowed ---

    (it "NEGATIVE: wrong assertText throws in SCI eval (not silently swallowed)"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"WRONG TEXT THAT DOES NOT EXIST ON PAGE\",\"substring\":true,\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            result (sci-eval-script script)]
        (expect (false? (:success result)))))

    (it "NEGATIVE: assertChecked on unchecked box throws in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertChecked\",\"selector\":\"#checkbox\",\"checked\":true,\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            result (sci-eval-script script)]
        (expect (false? (:success result)))))

    (it "NEGATIVE: assertValue with wrong value throws in SCI eval"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"assertValue\",\"selector\":\"#prefilled\",\"value\":\"COMPLETELY WRONG VALUE\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            result (sci-eval-script script)]
        (expect (false? (:success result)))))

    ;; --- Eval verification: script executes successfully in SCI ---

    (it "fill script evaluates successfully"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/test-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}\n"
                    "{\"name\":\"fill\",\"selector\":\"input[id=text-input]\",\"text\":\"proof-it-ran\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect (or (= "nil" (:result r)) (nil? (:result r))))))

    (it "navigate script evaluates successfully"
      (let [jsonl (str "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true},\"contextOptions\":{}}\n"
                    "{\"name\":\"navigate\",\"url\":\"" *test-server-url* "/second-page\",\"signals\":[],\"pageGuid\":\"page@test\",\"pageAlias\":\"page\",\"framePath\":[]}")
            script (binding [codegen/*exit-on-error* false]
                     (codegen/jsonl-str->clojure jsonl {:format :script}))
            r (sci-eval-script script)]
        (expect (nil? (:success r)))
        (expect true)))))

;; =============================================================================
;; TASK-013: Unified Snapshot Enrichment
;; =============================================================================

(defdescribe snapshot-enrichment-integration-test
  "Integration tests for TASK-013: URL annotations, structured refs, network/console windows"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "snapshot includes structured refs map"

    (it "snapshot result contains :refs map with role for each ref"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (map? (:refs r)))
        (expect (pos? (count (:refs r))))
        (doseq [[_ info] (:refs r)]
          (expect (some? (:role info))))))

    (it "link refs include url in refs map"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})
            link-ref (some (fn [[_ info]] (when (= "link" (:role info)) info))
                       (:refs r))]
        (when link-ref
          (expect (some? (:url link-ref))))))

    (it "snapshot tree contains [url=...] for links"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        ;; test-page should have links
        (when (str/includes? (:snapshot r) "link")
          (expect (str/includes? (:snapshot r) "[url="))))))

  (describe "snapshot includes network and console windows"

    (it "snapshot includes :network array"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (vector? (:network r)))))

    (it "snapshot includes :console array"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (vector? (:console r)))))

    (it "--no-network excludes network"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {"no_network" true})]
        (expect (nil? (:network r)))))

    (it "--no-console excludes console"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {"no_console" true})]
        (expect (nil? (:console r))))))

  (describe "network/console drill-down"

    (it "network_get_ref returns entry for valid ref"
      (nav! "/test-page")
      ;; Wait a bit for network entries to be captured
      (Thread/sleep 500)
      (let [window @(deref #'daemon/!network-window)]
        (when (seq window)
          (let [ref-str (:ref (first window))
                r (cmd "network_get_ref" {"ref" ref-str})]
            (expect (some? (:url r)))
            (expect (some? (:method r)))))))

    (it "network_get_ref returns error for invalid ref"
      (let [r (cmd "network_get_ref" {"ref" "@n99999"})]
        (expect (some? (:error r)))))

    (it "console_get_ref returns error for invalid ref"
      (let [r (cmd "console_get_ref" {"ref" "@c99999"})]
        (expect (some? (:error r)))))))

;; =============================================================================
;; TASK-013: Pages command
;; =============================================================================

(defdescribe pages-integration-test
  "Integration tests for pages list and get"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "page tracking"

    (it "pages_list returns visited pages"
      (nav! "/test-page")
      (nav! "/second-page")
      (let [r (cmd "pages_list" {})]
        (expect (vector? (:pages r)))
        (expect (= 2 (count (:pages r))))
        (expect (str/includes? (:url (first (:pages r))) "/test-page"))
        (expect (str/includes? (:url (second (:pages r))) "/second-page"))))

    (it "pages_get_ref returns page details"
      (nav! "/test-page")
      (let [pages (:pages (cmd "pages_list" {}))
            ref (:ref (first pages))
            r (cmd "pages_get_ref" {"ref" ref})]
        (expect (str/includes? (:url r) "/test-page"))
        (expect (some? (:navigated_at r)))))

    (it "network entries include page_ref"
      (nav! "/test-page")
      ;; Trigger a fetch AFTER page is tracked, so page_ref resolves
      (cmd "evaluate" {"script" "fetch(window.location.href).catch(() => {})"})
      (Thread/sleep 500)
      (let [entries (:entries (cmd "network_list" {}))]
        (when (seq entries)
          (expect (some :page_ref entries)))))))

;; =============================================================================
;; TASK-013: Network get / Console get syntax
;; =============================================================================

(defdescribe network-get-integration-test
  "Integration tests for network get @ref and network list"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "network list and get"

    (it "network_list returns entries vector"
      (nav! "/test-page")
      (Thread/sleep 300)
      (let [r (cmd "network_list" {})]
        (expect (vector? (:entries r)))))

    (it "network entries have preview structure"
      (nav! "/test-page")
      (Thread/sleep 300)
      (let [entries (:entries (cmd "network_list" {}))]
        (when (seq entries)
          (let [e (first entries)]
            (expect (contains? e :preview))
            (expect (map? (:preview e)))
            (expect (contains? (:preview e) :request))
            (expect (contains? (:preview e) :response))))))

    (it "network_get_ref returns full entry"
      (nav! "/test-page")
      (Thread/sleep 300)
      (let [entries (:entries (cmd "network_list" {}))]
        (when (seq entries)
          (let [ref (:ref (first entries))
                r (cmd "network_get_ref" {"ref" ref})]
            (expect (some? (:url r)))
            (expect (contains? r :request))
            (expect (contains? r :response))))))))

;; =============================================================================
;; TASK-013: Console list
;; =============================================================================

(defdescribe console-list-integration-test
  "Integration tests for console list and get"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "console list and get"

    (it "console_list returns entries vector"
      (nav! "/test-page")
      (Thread/sleep 300)
      (let [r (cmd "console_list" {})]
        (expect (vector? (:entries r)))))

    (it "console_get_ref returns entry"
      (nav! "/test-page")
      (Thread/sleep 300)
      (let [entries (:entries (cmd "console_list" {}))]
        (when (seq entries)
          (let [ref (:ref (first entries))
                r (cmd "console_get_ref" {"ref" ref})]
            (expect (some? (:text r)))))))))

;; =============================================================================
;; TASK-013: Snapshot includes pages
;; =============================================================================

(defdescribe snapshot-pages-integration-test
  "Integration tests for pages in snapshot output"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "snapshot includes pages"

    (it "snapshot output includes pages array"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (vector? (:pages r)))
        (expect (pos? (count (:pages r))))))))

;; =============================================================================
;; Computed Styles
;; =============================================================================

(defdescribe styles-integration-test
  "Integration tests for get_styles daemon handler"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "get_styles handler"

    (it "returns curated styles for element"
      (nav! "/test-page")
      (let [r (cmd "get_styles" {"selector" "h1"})]
        (expect (map? (:styles r)))
        (expect (contains? (:styles r) "fontSize"))))

    (it "returns display and position properties"
      (nav! "/test-page")
      (let [r (cmd "get_styles" {"selector" "h1"})]
        (expect (contains? (:styles r) "display"))))

    (it "returns full styles with full flag"
      (nav! "/test-page")
      (let [r (cmd "get_styles" {"selector" "h1" "full" true})]
        ;; Full mode returns all 300+ computed properties
        (expect (> (count (:styles r)) 20))))

    (it "includes selector in response"
      (nav! "/test-page")
      (let [r (cmd "get_styles" {"selector" "h1"})]
        (expect (= "h1" (:selector r)))))))

;; =============================================================================
;; Clipboard
;; =============================================================================

(defdescribe clipboard-integration-test
  "Integration tests for clipboard daemon handlers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "clipboard operations"

    (it "clipboard_copy returns copied confirmation"
      (nav! "/test-page")
      (let [r (cmd "clipboard_copy" {"text" "hello clipboard"})]
        (expect (:copied r))
        (expect (= "hello clipboard" (:text r)))))

    (it "clipboard_read returns copied text"
      (nav! "/test-page")
      (cmd "clipboard_copy" {"text" "read-me-back"})
      (let [r (cmd "clipboard_read" {})]
        (expect (= "read-me-back" (:content r)))))

    (it "clipboard_paste types clipboard into focused input"
      (nav! "/test-page")
      (cmd "clipboard_copy" {"text" "pasted-text"})
      ;; Focus an input that exists on test-page
      (cmd "click" {"selector" "#text-input"})
      (let [r (cmd "clipboard_paste" {})]
        (expect (:pasted r))))))

;; =============================================================================
;; Diff Snapshot
;; =============================================================================

(defdescribe diff-snapshot-integration-test
  "Integration tests for diff_snapshot daemon handler"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "diff_snapshot handler"

    (it "detects no changes when baseline matches current"
      (nav! "/test-page")
      (let [snap (:snapshot (cmd "snapshot" {"interactive" true}))
            r    (cmd "diff_snapshot" {"baseline" snap})]
        (expect (= 0 (:added r)))
        (expect (= 0 (:removed r)))
        (expect (= 0 (:changed r)))
        (expect (pos? (:unchanged r)))))

    (it "detects changes after page modification"
      (nav! "/test-page")
      (let [snap-before (:snapshot (cmd "snapshot" {"interactive" true}))
            _          (cmd "evaluate" {"script" "document.querySelector('h1').textContent = 'Changed'"})
            r          (cmd "diff_snapshot" {"baseline" snap-before})]
        (expect (or (pos? (:changed r)) (pos? (:added r)) (pos? (:removed r))))))

    (it "returns current snapshot in diff result"
      (nav! "/test-page")
      (let [snap-before (:snapshot (cmd "snapshot" {"interactive" true}))
            r          (cmd "diff_snapshot" {"baseline" snap-before})]
        (expect (string? (:current r)))
        (expect (pos? (:total_lines r)))))))

(defdescribe diff-screenshot-integration-test
  "Integration tests for diff_screenshot daemon handler"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "diff_screenshot handler"

    (it "detects no visual diff when baseline matches current screenshot"
      (nav! "/test-page")
      (let [tmpdir        (System/getProperty "java.io.tmpdir")
            baseline-path (str tmpdir java.io.File/separator "spel-diff-baseline-" (System/currentTimeMillis) ".png")
            out-path      (str tmpdir java.io.File/separator "spel-diff-out-" (System/currentTimeMillis) ".png")
            _             (cmd "screenshot" {"path" baseline-path})
            r             (cmd "diff_screenshot" {"baseline" baseline-path
                                                  "path" out-path})]
        (expect (true? (:matched r)))
        (expect (= 0 (:diff_count r)))
        (expect (= out-path (:diff_path r)))
        (expect (.exists (io/file (:diff_path r))))))

    (it "detects visual diff after page mutation"
      (nav! "/test-page")
      (let [tmpdir        (System/getProperty "java.io.tmpdir")
            baseline-path (str tmpdir java.io.File/separator "spel-diff-baseline-" (System/currentTimeMillis) ".png")
            _             (cmd "screenshot" {"path" baseline-path})
            _             (cmd "evaluate" {"script" "document.querySelector('h1').textContent = 'Changed visual diff'"})
            r             (cmd "diff_screenshot" {"baseline" baseline-path})]
        (expect (false? (:matched r)))
        (expect (pos? (:diff_count r)))
        (expect (.exists (io/file (:diff_path r))))))))

;; =============================================================================
;; Snapshot viewport/device/styles-detail
;; =============================================================================

(defdescribe snapshot-viewport-integration-test
  "Integration tests for snapshot viewport metadata"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "snapshot viewport response"

    (it "snapshot includes viewport map and header"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})
            vp (:viewport r)]
        (expect (map? vp))
        (expect (number? (:width vp)))
        (expect (number? (:height vp)))
        (expect (pos? (:width vp)))
        (expect (pos? (:height vp)))
        (expect (str/includes? (:snapshot r)
                  (str "viewport: " (:width vp) "x" (:height vp))))))))

(defdescribe snapshot-styles-detail-integration-test
  "Integration tests for snapshot styles_detail tiers"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "styles_detail tier behavior"

    (it "minimal returns 16 style keys per styled ref"
      (nav! "/test-page")
      (let [r      (cmd "snapshot" {"styles" true "styles_detail" "minimal"})
            styled (filter :styles (vals (:refs r)))]
        (expect (pos? (count styled)))
        (doseq [ref styled]
          (expect (= 16 (count (:styles ref)))))))

    (it "base returns 31 style keys and max returns 44"
      (nav! "/test-page")
      (let [base-r      (cmd "snapshot" {"styles" true "styles_detail" "base"})
            max-r       (cmd "snapshot" {"styles" true "styles_detail" "max"})
            base-styled (filter :styles (vals (:refs base-r)))
            max-styled  (filter :styles (vals (:refs max-r)))]
        (expect (pos? (count base-styled)))
        (expect (pos? (count max-styled)))
        (doseq [ref base-styled]
          (expect (= 31 (count (:styles ref)))))
        (doseq [ref max-styled]
          (expect (= 44 (count (:styles ref)))))))

    (it "style keys are kebab-case and tree includes inline style braces"
      (nav! "/test-page")
      (let [r      (cmd "snapshot" {"styles" true "styles_detail" "base"})
            styled (first (filter :styles (vals (:refs r))))]
        (expect (some? styled))
        (doseq [k (keys (:styles styled))]
          (expect (re-matches #"[a-z][a-z0-9-]*" k)))
        (expect (str/includes? (:snapshot r) "{"))
        (expect (str/includes? (:snapshot r) "}"))))

    (it "snapshot without styles request has no styles in refs"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (every? (comp nil? :styles) (vals (:refs r))))))))

(defdescribe snapshot-scroll-metrics-integration-test
  "Integration tests for scroll metrics in snapshot -S output (issue #96)"

  (describe "scroll metrics in styled snapshot"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "includes scroll metrics in refs for overflow:auto elements"
      (nav! "/scrollable-page")
      (let [r     (cmd "snapshot" {"styles" true})
            refs  (vals (:refs r))
            styled-with-scroll (filter #(get-in % [:styles "scroll-height"]) refs)]
        (expect (pos? (count styled-with-scroll)))
        (doseq [ref styled-with-scroll]
          (let [sh (parse-long (str/replace (get-in ref [:styles "scroll-height"]) "px" ""))
                ch (parse-long (str/replace (get-in ref [:styles "client-height"]) "px" ""))]
            (expect (pos? sh))
            (expect (pos? ch))
            (expect (> sh ch))))))

    (it "does not include scroll metrics for non-overflowing elements"
      (nav! "/scrollable-page")
      (let [r    (cmd "snapshot" {"styles" true})
            refs (vals (:refs r))
            all-scroll (filter #(get-in % [:styles "scroll-height"]) refs)
            all-styled (filter :styles refs)]
        (expect (< (count all-scroll) (count all-styled)))))

    (it "includes scroll metrics in snapshot tree text"
      (nav! "/scrollable-page")
      (let [r (cmd "snapshot" {"styles" true})]
        (expect (str/includes? (:snapshot r) "scroll-height:"))
        (expect (str/includes? (:snapshot r) "client-height:"))))

    (it "does not include scroll metrics without -S flag"
      (nav! "/scrollable-page")
      (let [r (cmd "snapshot" {})]
        (expect (not (str/includes? (:snapshot r) "scroll-height:")))
        (expect (every? (comp nil? :styles) (vals (:refs r))))))

    (it "scroll metrics work with minimal style tier"
      (nav! "/scrollable-page")
      (let [r     (cmd "snapshot" {"styles" true "styles_detail" "minimal"})
            refs  (vals (:refs r))
            with-scroll (filter #(get-in % [:styles "scroll-height"]) refs)]
        (expect (pos? (count with-scroll)))))))

(defdescribe device-tracking-integration-test
  "Integration tests for device tracking in snapshot responses"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "device metadata"

    (it "snapshot without set_device has nil or absent device"
      (nav! "/test-page")
      (let [r (cmd "snapshot" {})]
        (expect (nil? (:device r)))))

    (it "snapshot includes device after set_device"
      (nav! "/test-page")
      (let [browser-name (.name (.browserType core/*testing-browser*))
            state-a (deref #'daemon/!state)]
        (swap! state-a assoc-in [:launch-flags "browser"] browser-name)
        (cmd "set_device" {"device" "iPhone 14"})
        (let [r (cmd "snapshot" {})]
          (expect (= "iPhone 14" (:device r)))
          (expect (str/includes? (:snapshot r) "device: iPhone 14")))))))

(defn- pcmd
  "Calls process-command (which tracks actions). Returns parsed response data."
  [action params]
  (let [cmd-json (json/write-json-str (assoc params "action" action))
        resp-str (#'daemon/process-command cmd-json)
        resp     (json/read-json resp-str)]
    (if (get resp "success")
      (get resp "data")
      (throw (ex-info (str "Command failed: " (get resp "error")) resp)))))

;; =============================================================================
;; Action Log Integration Tests
;; =============================================================================

(defdescribe action-log-integration-test
  "Integration tests for action log tracking and SRT export"

  (around [f] (core/with-testing-browser ((:around with-test-server) (fn [] ((:around with-daemon-state) f)))))

  (describe "action tracking"

    (it "tracks navigate actions with URL and title"
      ;; Use pcmd to go through process-command (which triggers tracking)
      (pcmd "navigate" {"url" (str *test-server-url* "/test-page")})
      (let [r (cmd "action_log" {})]
        (expect (= 1 (:count r)))
        (expect (= 1 (count (:entries r))))
        (let [entry (first (:entries r))]
          (expect (= "navigate" (:action entry)))
          (expect (some? (:timestamp entry)))
          (expect (some? (:time entry)))
          (expect (some? (:url entry)))
          (expect (string? (:url entry))))))

    (it "tracks click actions without snapshot"
      (pcmd "navigate" {"url" (str *test-server-url* "/test-page")})
      ;; Clear the navigate entry
      (cmd "action_log_clear" {})
      (pcmd "click" {"selector" "a"})
      (let [r (cmd "action_log" {})]
        (expect (= 1 (:count r)))
        (let [entry (first (:entries r))]
          (expect (= "click" (:action entry)))
          (expect (some? (:url entry)))
          ;; Click result no longer includes snapshot
          (expect (nil? (:snapshot entry))))))

    (it "does not track read-only commands"
      (pcmd "navigate" {"url" (str *test-server-url* "/test-page")})
      (cmd "action_log_clear" {})
      ;; snapshot and url are read-only — should NOT be tracked
      (pcmd "snapshot" {})
      (pcmd "url" {})
      (let [r (cmd "action_log" {})]
        (expect (= 0 (:count r))))))

  (describe "action_log_srt export"

    (it "returns valid SRT format"
      (pcmd "navigate" {"url" (str *test-server-url* "/test-page")})
      ;; Do a couple actions
      (pcmd "click" {"selector" "a"})
      (let [r (cmd "action_log_srt" {})]
        (expect (string? (:srt r)))
        ;; Should have cue numbers
        (expect (str/includes? (:srt r) "1\n"))
        ;; Should have time markers with correct format
        (expect (re-find #"\d{2}:\d{2}:\d{2},\d{3} --> " (:srt r)))
        ;; Should include navigate and click descriptions
        (expect (str/includes? (:srt r) "navigate")))))

  (describe "action_log_clear"

    (it "clears all entries"
      (pcmd "navigate" {"url" (str *test-server-url* "/test-page")})
      (let [before (cmd "action_log" {})]
        (expect (pos? (:count before))))
      (cmd "action_log_clear" {})
      (let [after (cmd "action_log" {})]
        (expect (= 0 (:count after)))
        (expect (empty? (:entries after)))))))
