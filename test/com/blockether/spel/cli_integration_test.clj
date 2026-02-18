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
   [com.blockether.spel.core :as core]
   [com.blockether.spel.daemon :as daemon]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :as tf
    :refer [*pw* *browser*
            with-playwright with-browser]]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]
   [lazytest.core :refer [around defdescribe describe expect it]])
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
    (let [ctx       (core/new-context *browser*)
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
      (page/on-console pg (fn [msg]
                            (swap! console-a conj
                              {:type (.type ^ConsoleMessage msg)
                               :text (.text ^ConsoleMessage msg)})))
      (page/on-page-error pg (fn [error]
                               (swap! errors-a conj
                                 {:message (str error)})))
      (reset! state-a {:pw *pw* :browser *browser* :context ctx :page pg
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

  (describe "navigate, back, forward, reload"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "navigate returns url and title"
      (let [r (nav! "/test-page")]
        (expect (str/includes? (:url r) "/test-page"))
        (expect (= "Test Page" (:title r)))
        (expect (string? (:snapshot r)))))

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
        (expect (str/includes? (:url r) "/test-page"))))))

;; =============================================================================
;; 2. Snapshot
;; =============================================================================

(defdescribe snapshot-integration-test
  "Integration tests for snapshot"

  (describe "snapshot variations"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
        (expect (string? (:snapshot r)))))))

;; =============================================================================
;; 3. Click & Double-click
;; =============================================================================

(defdescribe click-integration-test
  "Integration tests for click and dblclick"

  (describe "click and dblclick"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "click returns selector and snapshot"
      (nav! "/test-page")
      (let [r (cmd "click" {"selector" "#submit-btn"})]
        (expect (= "#submit-btn" (:clicked r)))
        (expect (string? (:snapshot r)))))

    (it "dblclick returns selector"
      (nav! "/test-page")
      (let [r (cmd "dblclick" {"selector" "#submit-btn"})]
        (expect (= "#submit-btn" (:dblclicked r)))))))

;; =============================================================================
;; 4. Fill, Type, Clear
;; =============================================================================

(defdescribe fill-type-clear-integration-test
  "Integration tests for fill, type, clear, get_value"

  (describe "fill → get_value → type → clear → get_value"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
  "Integration tests for press"

  (describe "press key"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "press Tab without selector"
      (nav! "/test-page")
      (let [r (cmd "press" {"key" "Tab"})]
        (expect (= "Tab" (:pressed r)))))

    (it "press with selector"
      (nav! "/test-page")
      (cmd "click" {"selector" "#text-input"})
      (let [r (cmd "press" {"key" "a" "selector" "#text-input"})]
        (expect (= "a" (:pressed r)))))))

;; =============================================================================
;; 6. Hover, Focus
;; =============================================================================

(defdescribe hover-focus-integration-test
  "Integration tests for hover and focus"

  (describe "hover and focus"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "select option"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "select sets dropdown value"
      (nav! "/test-page")
      (let [r (cmd "select" {"selector" "#dropdown" "values" "b"})]
        (expect (= "#dropdown" (:selected r)))))))

;; =============================================================================
;; 8. Check / Uncheck
;; =============================================================================

(defdescribe check-uncheck-integration-test
  "Integration tests for check and uncheck"

  (describe "check and uncheck checkbox"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "get_text, content, url, title, count, box, get_value, get_attribute"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "element state checks"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "evaluate JS"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "screenshot capture"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "scroll directions"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "scroll into view"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "scrolls element into view"
      (nav! "/test-page")
      (let [r (cmd "scrollintoview" {"selector" "#scroll-anchor"})]
        (expect (= "#scroll-anchor" (:scrolled_into_view r)))))))

;; =============================================================================
;; 15. KeyDown / KeyUp
;; =============================================================================

(defdescribe keydown-keyup-integration-test
  "Integration tests for keydown and keyup"

  (describe "key hold and release"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "wait conditions"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "mouse operations"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "viewport resize"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "color scheme emulation"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "offline toggle"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "extra HTTP headers"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "sets headers"
      (nav! "/test-page")
      (let [r (cmd "set_headers" {"headers" {"X-Custom" "test123"}})]
        (expect (true? (:headers_set r)))))))

;; =============================================================================
;; 22. Cookies
;; =============================================================================

(defdescribe cookies-integration-test
  "Integration tests for cookies_set, cookies_get, cookies_clear"

  (describe "cookie lifecycle"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "localStorage"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "tab lifecycle"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "console messages"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "highlight element"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "highlight returns selector"
      (nav! "/test-page")
      (let [r (cmd "highlight" {"selector" "#heading"})]
        (expect (= "#heading" (:highlighted r)))))))

;; =============================================================================
;; 27. State Save / Load / List / Clear
;; =============================================================================

(defdescribe state-management-integration-test
  "Integration tests for state_save, state_load, state_list, state_clear"

  (describe "state save and clear"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
            (Files/deleteIfExists (Path/of tmp (into-array String [])))))))))

;; =============================================================================
;; 28. Session Info / List
;; =============================================================================

(defdescribe session-integration-test
  "Integration tests for session_info, session_list"

  (describe "session info"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "semantic locators"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
        (expect (string? (:text r)))))))

;; =============================================================================
;; 30. Frame
;; =============================================================================

(defdescribe frame-integration-test
  "Integration tests for frame_list, frame_switch"

  (describe "frame operations"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "network route lifecycle"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
        (expect (true? (:all_routes_removed r)))))))

;; =============================================================================
;; 32. Network Requests
;; =============================================================================

(defdescribe network-requests-integration-test
  "Integration tests for network_requests"

  (describe "tracked requests"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "network_requests returns a vector"
      (nav! "/test-page")
      (let [r (cmd "network_requests" {})]
        (expect (vector? (:requests r)))))))

;; =============================================================================
;; 33. Dialog Accept / Dismiss
;; =============================================================================

(defdescribe dialog-integration-test
  "Integration tests for dialog_accept, dialog_dismiss"

  (describe "dialog handlers"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
;; 35. Trace Start / Stop
;; =============================================================================

(defdescribe trace-integration-test
  "Integration tests for trace_start, trace_stop"

  (describe "trace lifecycle"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "close response"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "close returns shutdown flag"
      (nav! "/test-page")
      (let [r (cmd "close" {})]
        (expect (true? (:closed r)))
        (expect (true? (:shutdown r)))))))

;; =============================================================================
;; 37. Default (unknown action)
;; =============================================================================

(defdescribe default-handler-integration-test
  "Integration tests for unknown action handler"

  (describe "unknown actions"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "returns error for unknown action"
      (let [r (cmd "nonexistent_action_xyz" {})]
        (expect (str/includes? (:error r) "Unknown action"))))))

;; =============================================================================
;; 38. Set Geo
;; =============================================================================

(defdescribe set-geo-integration-test
  "Integration tests for set_geo"

  (describe "geolocation"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "pdf export"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "annotate injects overlays"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
        (expect (number? (:annotated r))))))

  (describe "unannotate removes overlays"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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
;; 41. Console Start / Errors Start
;; =============================================================================

(defdescribe console-start-errors-start-integration-test
  "Integration tests for console_start, errors_start"

  (describe "listener registration"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

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

  (describe "sci_eval handler"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "evaluates simple expressions"
      (let [r (cmd "sci_eval" {"code" "(+ 1 2)"})]
        (expect (= "3" (:result r)))))

    (it "evaluates string expressions"
      (let [r (cmd "sci_eval" {"code" "\"hello\""})]
        (expect (= "\"hello\"" (:result r)))))

    (it "can navigate and read title via spel functions"
      (let [_ (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
            r (cmd "sci_eval" {"code" "(spel/title)"})]
        (expect (= "\"Test Page\"" (:result r)))))

    (it "start! is a no-op when daemon has page"
      (let [r (cmd "sci_eval" {"code" "(spel/start!)"})]
        (expect (= ":started" (:result r)))))

    (it "stop! does not kill daemon browser"
      (let [_ (cmd "sci_eval" {"code" "(spel/stop!)"})
            ;; After stop!, daemon re-syncs state on next call, so page still works
            r (cmd "sci_eval" {"code" "(+ 10 20)"})]
        (expect (= "30" (:result r)))))

    (it "returns error for missing code param"
      (let [threw? (try (cmd "sci_eval" {}) false
                     (catch Exception _ true))]
        (expect threw?)))

    (it "persists defs between eval calls"
      (cmd "sci_eval" {"code" "(def my-val 42)"})
      (let [r (cmd "sci_eval" {"code" "my-val"})]
        (expect (= "42" (:result r)))))))
