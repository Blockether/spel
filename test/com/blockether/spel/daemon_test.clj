(ns com.blockether.spel.daemon-test
  "Tests for the daemon namespace.

   Unit tests for path functions, protocol parsing, and lifecycle checks.
   No browser or socket connections required."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.daemon :as sut]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — Path Functions
;; =============================================================================

(defdescribe socket-path-test
  "Unit tests for socket-path"

  (describe "returns path with session name and .sock extension"
    (it "contains session name"
      (let [p (str (sut/socket-path "mysession"))]
        (expect (str/includes? p "mysession"))
        (expect (str/ends-with? p ".sock"))))

    (it "uses default session name"
      (let [p (str (sut/socket-path "default"))]
        (expect (str/includes? p "spel-default.sock"))))

    (it "uses custom session name"
      (let [p (str (sut/socket-path "work"))]
        (expect (str/includes? p "spel-work.sock"))))))

(defdescribe pid-file-path-test
  "Unit tests for pid-file-path"

  (describe "returns path with session name and .pid extension"
    (it "contains session name and .pid"
      (let [p (str (sut/pid-file-path "test-session"))]
        (expect (str/includes? p "test-session"))
        (expect (str/ends-with? p ".pid"))))

    (it "uses the spel prefix"
      (let [p (str (sut/pid-file-path "default"))]
        (expect (str/includes? p "spel-default.pid"))))))

(defdescribe log-file-path-test
  "Unit tests for log-file-path"

  (describe "returns path with session name and .log extension"
    (it "contains session name and .log"
      (let [p (str (sut/log-file-path "mylog"))]
        (expect (str/includes? p "mylog"))
        (expect (str/ends-with? p ".log"))))

    (it "uses the spel prefix"
      (let [p (str (sut/log-file-path "default"))]
        (expect (str/includes? p "spel-default.log"))))))

;; =============================================================================
;; Unit Tests — daemon-running?
;; =============================================================================

(defdescribe daemon-running-test
  "Unit tests for daemon-running?"

  (describe "returns falsy for non-existent session"
    (it "returns nil/false when no PID file exists"
      (expect (not (sut/daemon-running? "nonexistent-session-12345"))))))

;; =============================================================================
;; Unit Tests — process-command (private)
;; =============================================================================

(defdescribe process-command-test
  "Unit tests for process-command (private)"

  (describe "invalid JSON input"
    (it "returns error response for garbage input"
      (let [response (json/read-json (#'sut/process-command "not valid json"))]
        (expect (false? (get response "success")))
        (expect (str/includes? (get response "error") "Parse error"))))

    (it "returns error response for empty string"
      (let [response (json/read-json (#'sut/process-command ""))]
        (expect (false? (get response "success"))))))

  (describe "unknown action"
    (it "returns success with error message for unknown action"
      (let [response (json/read-json
                       (#'sut/process-command
                        (json/write-json-str {"action" "nonexistent_action"})))]
        (expect (true? (get response "success")))
        (expect (str/includes? (get-in response ["data" "error"])
                  "Unknown action")))))

  (describe "close action"
    (it "returns shutdown flag"
      (let [response (json/read-json
                       (#'sut/process-command
                        (json/write-json-str {"action" "close"})))]
        (expect (true? (get response "success")))
        (expect (true? (get-in response ["data" "closed"])))
        (expect (true? (get-in response ["data" "shutdown"])))))))

;; =============================================================================
;; Unit Tests — filter-snapshot-tree
;; =============================================================================

(defdescribe filter-snapshot-tree-test
  "Unit tests for filter-snapshot-tree (private)"

  (describe "nil/blank input"
    (it "returns nil for nil"
      (expect (nil? (#'sut/filter-snapshot-tree nil {}))))

    (it "returns blank for blank"
      (expect (= "" (#'sut/filter-snapshot-tree "" {})))))

  (describe "interactive filter"
    (it "filters to lines with [@]"
      (let [tree "- heading\n  - button [@e1]\n  - text\n  - link [@e2]"
            result (#'sut/filter-snapshot-tree tree {"interactive" true})]
        (expect (str/includes? result "[@e1]"))
        (expect (str/includes? result "[@e2]"))
        (expect (not (str/includes? result "- heading"))))))

  (describe "cursor filter"
    (it "includes focused elements with cursor"
      (let [tree "- heading\n  - button [@e1]\n  - textbox [focused]\n  - text"
            result (#'sut/filter-snapshot-tree tree {"interactive" true "cursor" true})]
        (expect (str/includes? result "[@e1]"))
        (expect (str/includes? result "[focused]")))))

  (describe "compact filter"
    (it "removes single-word lines"
      (let [tree "- heading\n  - button\n  - Click me [@e1]"
            result (#'sut/filter-snapshot-tree tree {"compact" true})]
        (expect (not (str/includes? result "- heading")))
        (expect (not (str/includes? result "- button")))
        (expect (str/includes? result "Click me")))))

  (describe "depth filter"
    (it "limits indent depth"
      (let [tree "top\n  level1\n    level2\n      level3"
            result (#'sut/filter-snapshot-tree tree {"depth" 1})]
        (expect (str/includes? result "top"))
        (expect (str/includes? result "level1"))
        (expect (not (str/includes? result "level3")))))))

;; =============================================================================
;; Unit Tests — process-command with _flags
;; =============================================================================

(defdescribe process-command-flags-test
  "Unit tests for process-command handling _flags"

  (describe "stores launch flags"
    (it "stores _flags in state"
      ;; Reset state first
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        (let [cmd-str (json/write-json-str {"action" "close"
                                            "_flags" {"user-agent" "TestAgent"
                                                      "proxy" "http://proxy:8080"}})
              _       (#'sut/process-command cmd-str)
              flags   (get @state-atom :launch-flags)]
          ;; After processing, flags should be merged
          (expect (= "TestAgent" (get flags "user-agent")))
          (expect (= "http://proxy:8080" (get flags "proxy"))))))))

;; =============================================================================
;; Unit Tests — device-presets (shared from devices.clj)
;; =============================================================================

(defdescribe device-presets-test
  "Unit tests for device presets"

  (describe "presets exist via string lookup"
    (it "has iphone 14"
      (expect (some? (devices/resolve-device-by-name "iphone 14"))))

    (it "has pixel 7"
      (expect (some? (devices/resolve-device-by-name "pixel 7"))))

    (it "has ipad"
      (expect (some? (devices/resolve-device-by-name "ipad"))))

    (it "has desktop chrome"
      (expect (some? (devices/resolve-device-by-name "desktop chrome"))))

    (it "has desktop safari"
      (expect (some? (devices/resolve-device-by-name "desktop safari")))))

  (describe "case-insensitive lookup"
    (it "resolves iPhone 14"
      (expect (some? (devices/resolve-device-by-name "iPhone 14"))))

    (it "resolves PIXEL 7"
      (expect (some? (devices/resolve-device-by-name "PIXEL 7")))))

  (describe "preset values"
    (it "iphone 14 has mobile settings"
      (let [preset (devices/resolve-device-by-name "iphone 14")]
        (expect (true? (:is-mobile preset)))
        (expect (true? (:has-touch preset)))
        (expect (= 3 (:device-scale-factor preset)))
        (expect (= 390 (get-in preset [:viewport :width])))))))

;; =============================================================================
;; Unit Tests — session-state-path
;; =============================================================================

(defdescribe session-state-path-test
  "Unit tests for session-state-path"

  (describe "returns correct path"
    (it "includes session name in path"
      (let [path (#'sut/session-state-path "myapp")]
        (expect (clojure.string/includes? path "myapp"))))

    (it "uses .json extension"
      (let [path (#'sut/session-state-path "test-session")]
        (expect (clojure.string/ends-with? path ".json"))))

    (it "uses spel-session prefix"
      (let [path (#'sut/session-state-path "demo")]
        (expect (clojure.string/includes? path "spel-session-demo.json"))))))

;; =============================================================================
;; Unit Tests — auto-save/load session state
;; =============================================================================

(defdescribe auto-session-state-test
  "Unit tests for auto session state save/load behavior"

  (describe "auto-save skips when no session-name"
    (it "does nothing when no session-name flag"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        ;; Should not throw
        (#'sut/auto-save-session-state!)
        (expect true))))

  (describe "auto-load skips when no session-name"
    (it "does nothing when no session-name flag"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        ;; Should not throw
        (#'sut/auto-load-session-state!)
        (expect true))))

  (describe "auto-load skips when file doesn't exist"
    (it "does nothing when state file missing"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {"session-name" "nonexistent-session-xyz"}})
        ;; Should not throw — file doesn't exist so it just returns
        (#'sut/auto-load-session-state!)
        (expect true)))))

;; =============================================================================
;; Unit Tests — check-anomaly! (private)
;; =============================================================================

(defdescribe check-anomaly-test
  "Unit tests for check-anomaly! — surfaces meaningful errors from anomaly maps"

  (describe "passes through non-anomaly values"
    (it "returns a string unchanged"
      (expect (= "hello" (#'sut/check-anomaly! "hello" "context"))))

    (it "returns a number unchanged"
      (expect (= 42 (#'sut/check-anomaly! 42 "context"))))

    (it "returns nil unchanged"
      (expect (nil? (#'sut/check-anomaly! nil "context"))))

    (it "returns a regular map unchanged"
      (let [m {:foo "bar"}]
        (expect (= m (#'sut/check-anomaly! m "context"))))))

  (describe "throws ex-info for anomaly maps"
    (it "throws with context message and anomaly message"
      (let [anomaly-map (anomaly/anomaly ::anomaly/fault "Browser executable not found"
                          {:playwright/error-type :playwright.error/exception})]
        (try
          (#'sut/check-anomaly! anomaly-map "Failed to launch browser")
          (expect false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (expect (str/includes? (.getMessage e) "Failed to launch browser"))
            (expect (str/includes? (.getMessage e) "Browser executable not found"))))))

    (it "includes anomaly data in ex-data"
      (let [anomaly-map (anomaly/anomaly ::anomaly/fault "Some error"
                          {:playwright/error-type :playwright.error/exception})]
        (try
          (#'sut/check-anomaly! anomaly-map "Launch failed")
          (expect false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (expect (= ::anomaly/fault (::anomaly/category data))))
            (expect (nil? (:playwright/exception (ex-data e))))))))

    (it "preserves original exception as cause"
      (let [original-ex (Exception. "underlying cause")
            anomaly-map (assoc (anomaly/anomaly ::anomaly/fault "Wrapper message"
                                 {:playwright/error-type :playwright.error/exception})
                          :playwright/exception original-ex)]
        (try
          (#'sut/check-anomaly! anomaly-map "Context msg")
          (expect false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (expect (= original-ex (.getCause e)))
            (expect (str/includes? (.getMessage e) "Context msg"))
            (expect (str/includes? (.getMessage e) "Wrapper message"))))))))

;; =============================================================================
;; Unit Tests — ensure-browser! anomaly handling
;; =============================================================================

(defdescribe ensure-browser-anomaly-test
  "Tests that ensure-browser! surfaces meaningful errors when core/* calls fail.
   Mocks core functions to return anomaly maps and verifies the error message
   is descriptive instead of a raw ClassCastException."

  (describe "normal path — launch-chromium returns anomaly"
    (it "throws with 'Failed to launch browser' and the underlying error message"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        (with-redefs [core/create (fn [] (Object.))
                      core/launch-chromium (fn [_ _]
                                             (core/wrap-error
                                               (Exception. "Chromium not found at /bad/path")))]
          (try
            (#'sut/ensure-browser!)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "Failed to launch browser"))
              (expect (str/includes? (.getMessage e) "Chromium not found at /bad/path"))))))))

  (describe "normal path — new-context returns anomaly"
    (it "throws with 'Failed to create browser context' and the underlying message"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        (with-redefs [core/create (fn [] (Object.))
                      core/launch-chromium (fn [_ _] (Object.))
                      core/new-context (fn [_ & _]
                                         (core/wrap-error
                                           (Exception. "Context creation failed")))]
          (try
            (#'sut/ensure-browser!)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "Failed to create browser context"))
              (expect (str/includes? (.getMessage e) "Context creation failed"))))))))

  (describe "normal path — new-page-from-context returns anomaly"
    (it "throws with 'Failed to create page' and the underlying message"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {}})
        (with-redefs [core/create (fn [] (Object.))
                      core/launch-chromium (fn [_ _] (Object.))
                      core/new-context (fn [_ & _] (Object.))
                      core/new-page-from-context (fn [_]
                                                   (core/wrap-error
                                                     (Exception. "Page allocation failed")))]
          (try
            (#'sut/ensure-browser!)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "Failed to create page"))
              (expect (str/includes? (.getMessage e) "Page allocation failed"))))))))

  (describe "persistent path — launch-persistent-context returns anomaly"
    (it "throws with 'Failed to launch persistent browser context' and the underlying message"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true :session "test"
                            :launch-flags {"profile" "/tmp/test-profile"}})
        (with-redefs [core/create (fn [] (reify com.microsoft.playwright.Playwright
                                           (chromium [_] (reify com.microsoft.playwright.BrowserType
                                                           (name [_] "chromium")))))
                      core/launch-persistent-context (fn [_ _ _]
                                                       (core/wrap-error
                                                         (Exception. "Profile dir locked")))]
          (try
            (#'sut/ensure-browser!)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "Failed to launch persistent browser context"))
              (expect (str/includes? (.getMessage e) "Profile dir locked")))))))))
