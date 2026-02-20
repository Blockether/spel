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
        (expect (number? (:annotated r)))))

    (it "annotate --full annotates at least as many as viewport-only"
      (nav! "/test-page")
      (let [viewport-r (cmd "annotate" {})
            _          (cmd "unannotate" {})
            full-r     (cmd "annotate" {"full-page" true})]
        (expect (number? (:annotated full-r)))
        (expect (>= (:annotated full-r) (:annotated viewport-r))))))

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
;; 41a. Pre-action Markers (mark/unmark)
;; =============================================================================

(defdescribe action-markers-integration-test
  "Integration tests for pre-action markers (mark/unmark) via sci_eval"

  (describe "action markers via sci_eval"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "mark returns count of marked elements"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(spel/mark \"@e1\")"})]
        (expect (= "1" (:result r)))))

    (it "mark handles multiple refs"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(spel/mark \"@e1\" \"e2\")"})]
        (expect (pos? (parse-long (:result r))))))

    (it "unmark removes all markers"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (cmd "sci_eval" {"code" "(spel/mark \"@e1\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/unmark)"})]
        (expect (= "nil" (:result r)))))

    (it "mark returns 0 for non-existent refs"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(spel/mark \"e999\")"})]
        (expect (= "0" (:result r)))))

    (it "markers coexist with annotation overlays"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (let [_snap (cmd "sci_eval" {"code" "(spel/snapshot)"})
            _      (cmd "sci_eval" {"code" "(spel/annotate (:refs (read-string (str (spel/snapshot)))))"})
            _mark  (cmd "sci_eval" {"code" "(spel/mark \"@e1\")"})
            ;; Verify markers are present (data-spel-action-marker)
            marker-check (cmd "sci_eval" {"code" "(spel/eval-js \"document.querySelectorAll('[data-spel-action-marker]').length\")"})
            ;; Verify annotations are also present (data-spel-annotate)
            annot-check  (cmd "sci_eval" {"code" "(spel/eval-js \"document.querySelectorAll('[data-spel-annotate]').length\")"})]
        (expect (pos? (parse-long (:result marker-check))))
        (expect (pos? (parse-long (:result annot-check))))
        ;; Cleanup
        (cmd "sci_eval" {"code" "(spel/unmark)"})
        (cmd "sci_eval" {"code" "(spel/unannotate)"})))))

;; =============================================================================
;; 41b. Audit Screenshots
;; =============================================================================

(defdescribe audit-screenshot-integration-test
  "Integration tests for audit-screenshot with caption"

  (describe "audit screenshot via sci_eval"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "audit-screenshot returns bytes"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(type (spel/audit-screenshot \"Test caption\"))"})]
        (expect (str/includes? (:result r) "byte"))))

    (it "audit-screenshot with markers option"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(type (spel/audit-screenshot \"With markers\" {:markers [\"e1\"]}))"})]
        (expect (str/includes? (:result r) "byte"))
        ;; Verify markers were cleaned up
        (let [check (cmd "sci_eval" {"code" "(spel/eval-js \"document.querySelectorAll('[data-spel-action-marker]').length\")"})]
          (expect (= "0" (:result check))))))

    (it "caption is cleaned up after screenshot"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/audit-screenshot \"Temporary caption\")"})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"document.querySelectorAll('[data-spel-caption]').length\")"})]
        (expect (= "0" (:result r)))))))

;; =============================================================================
;; 41c. Report Builder (report->html / report->pdf)
;; =============================================================================

(defdescribe report-builder-integration-test
  "Integration tests for report->html and report->pdf with polymorphic entries"

  (describe "report->html entry types"
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it ":screenshot entry renders image + caption"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
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
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
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
    {:context [with-playwright with-browser with-test-server with-daemon-state]}

    (it "report->pdf returns bytes from typed entries"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(let [img (spel/screenshot)]
                                          (type (spel/report->pdf
                                                  [{:type :section :text \"Report\" :level 2}
                                                   {:type :screenshot :image img :caption \"Shot\"}])))"})]
        (expect (str/includes? (:result r) "byte"))))

    (it "report->pdf with title option"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
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

    (it "persists defs between eval calls"
      (cmd "sci_eval" {"code" "(def my-val 42)"})
      (let [r (cmd "sci_eval" {"code" "my-val"})]
        (expect (= "42" (:result r)))))

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
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      ;; @e1 should resolve to a real element and click without CSS parse error
      (let [r (cmd "sci_eval" {"code" "(do (spel/click \"@e1\") :clicked)"})]
        (expect (= ":clicked" (:result r)))))

    (it "spel/$ auto-resolves @eN to a Locator"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      ;; @e1 should resolve to [data-pw-ref="e1"] locator, not throw CSS parse error
      (let [r (cmd "sci_eval" {"code" "(str (type (spel/$ \"@e1\")))"})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/$ auto-resolves eN (without @) to a Locator"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(str (type (spel/$ \"e1\")))"})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/text reads text content via @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      ;; e1 is typically the heading or first meaningful element — just verify no error
      (let [r (cmd "sci_eval" {"code" "(string? (spel/text \"@e1\"))"})]
        (expect (= "true" (:result r)))))

    (it "spel/visible? works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      (let [r (cmd "sci_eval" {"code" "(boolean? (spel/visible? \"@e1\"))"})]
        (expect (= "true" (:result r)))))

    (it "spel/highlight works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      ;; highlight returns nil but should not throw
      (let [r (cmd "sci_eval" {"code" "(do (spel/highlight \"@e1\") :highlighted)"})]
        (expect (= ":highlighted" (:result r)))))

    (it "spel/$ still works with regular CSS selectors"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (let [r (cmd "sci_eval" {"code" "(str (type (spel/$ \"h1\")))"})]
        (expect (str/includes? (:result r) "Locator"))))

    (it "spel/assert-visible works with @eN ref"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "sci_eval" {"code" "(spel/snapshot)"})
      ;; assert-visible should not throw for a visible element
      (let [r (cmd "sci_eval" {"code" "(do (spel/assert-visible \"@e1\") :passed)"})]
        (expect (= ":passed" (:result r)))))

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

    (it "preserves stdout on error via ex-data"
      (let [threw (try (cmd "sci_eval" {"code" "(println \"before boom\") (throw (ex-info \"boom\" {}))"})
                    nil
                    (catch Exception e e))]
        (expect (some? threw))
        (let [data (ex-data threw)]
          (expect (= "before boom\n" (str/replace (str (:stdout data)) "\r\n" "\n"))))))

    ;; --- Console/error auto-inclusion in sci_eval response ---

    (it "sci_eval includes console messages from page in response"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      ;; Page has console.log('test-page-loaded') on load — clear and trigger fresh
      (cmd "console_clear" {})
      (let [r (cmd "sci_eval" {"code" "(spel/eval-js \"console.log('eval-console-test')\" )"})]
        (expect (= "nil" (:result r)))
        (expect (some? (:console r)))
        (expect (some #(= "eval-console-test" (:text %)) (:console r)))))

    (it "sci_eval includes page errors in response"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "errors_clear" {})
      ;; Trigger an async error and wait inside the eval for it to fire+propagate
      (let [r (cmd "sci_eval" {"code" (str "(spel/eval-js \"setTimeout(function(){ throw new Error('test-page-err'); }, 0)\")"
                                        "(spel/sleep 300)"
                                        ":done")})]
        (expect (= ":done" (:result r)))
        ;; Page errors captured during this eval should be in the response
        (expect (some? (:page-errors r)))
        (expect (some #(str/includes? (:message %) "test-page-err") (:page-errors r)))))

    (it "sci_eval omits console/page-errors keys when none during eval"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      ;; Clear existing messages
      (cmd "console_clear" {})
      (cmd "errors_clear" {})
      ;; Eval pure computation — no console output
      (let [r (cmd "sci_eval" {"code" "(+ 100 200)"})]
        (expect (= "300" (:result r)))
        (expect (nil? (:console r)))
        (expect (nil? (:page-errors r)))))

    (it "sci_eval includes console messages even on eval error"
      (cmd "sci_eval" {"code" (str "(spel/goto \"" *test-server-url* "/test-page\")")})
      (cmd "console_clear" {})
      (let [threw (try
                    (cmd "sci_eval" {"code" "(do (spel/eval-js \"console.warn('before-error')\") (throw (ex-info \"boom\" {})))"})
                    nil
                    (catch Exception e e))]
        (expect (some? threw))
        (let [data (ex-data threw)]
          (expect (some? (:console data)))
          (expect (some #(= "before-error" (:text %)) (:console data))))))

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
