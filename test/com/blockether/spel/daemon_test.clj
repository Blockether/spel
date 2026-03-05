(ns com.blockether.spel.daemon-test
  "Tests for the daemon namespace.

   Unit tests for path functions, protocol parsing, and lifecycle checks.
   No browser or socket connections required."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
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

  (describe "auto-save skips when --no-persist"
            (it "does nothing when no-persist flag is set"
                (let [state-atom (deref #'sut/!state)]
                  (reset! state-atom {:pw nil :browser nil :context nil :page nil
                                      :refs {} :counter 0 :headless true :session "test"
                                      :launch-flags {"no-persist" true}})
        ;; Should not throw
                  (#'sut/auto-save-session-state!)
                  (expect true))))

  (describe "auto-load skips when --no-persist"
            (it "does nothing when no-persist flag is set"
                (let [state-atom (deref #'sut/!state)]
                  (reset! state-atom {:pw nil :browser nil :context nil :page nil
                                      :refs {} :counter 0 :headless true :session "test"
                                      :launch-flags {"no-persist" true}})
        ;; Should not throw
                  (#'sut/auto-load-session-state!)
                  (expect true))))

  (describe "auto-load skips when file doesn't exist"
            (it "does nothing when state file missing"
                (let [state-atom (deref #'sut/!state)]
                  (reset! state-atom {:pw nil :browser nil :context nil :page nil
                                      :refs {} :counter 0 :headless true :session "nonexistent-session-xyz"
                                      :launch-flags {}})
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
;; Unit Tests — parse-playwright-error (private)
;; =============================================================================

(defdescribe parse-playwright-error-test
  "Unit tests for parse-playwright-error — extracts call log and selector from Playwright errors"

  (describe "call log extraction"
            (it "extracts call log lines between === logs === markers"
                (let [msg (str "locator.click: Timeout 30000ms exceeded.\n"
                               "=========================== logs ===========================\n"
                               "waiting for locator(\"#missing\")\n"
                               "  locator resolved to 0 elements\n"
                               "============================================================")
                      result (#'sut/parse-playwright-error msg)]
                  (expect (= ["waiting for locator(\"#missing\")" "locator resolved to 0 elements"]
                             (:call_log result)))))

            (it "handles single-line call log"
                (let [msg (str "Error: something\n"
                               "=========================== logs ===========================\n"
                               "waiting for locator(\"button\")\n"
                               "============================================================")
                      result (#'sut/parse-playwright-error msg)]
                  (expect (= ["waiting for locator(\"button\")"] (:call_log result))))))

  (describe "selector extraction"
            (it "extracts selector from locator() pattern"
                (let [msg "locator.click: Timeout 30000ms exceeded. locator(\"#submit-btn\") resolved to 0 elements"
                      result (#'sut/parse-playwright-error msg)]
                  (expect (= "#submit-btn" (:selector result)))))

            (it "extracts getByRole pattern when no locator() present"
                (let [msg "locator.click: getByRole(BUTTON, name=\"Submit\") resolved to 0 elements"
                      result (#'sut/parse-playwright-error msg)]
                  (expect (str/includes? (:selector result) "getByRole")))))

  (describe "nil and empty input"
            (it "returns nil for nil input"
                (expect (nil? (#'sut/parse-playwright-error nil))))

            (it "returns empty map for plain error without call log or selector"
                (let [result (#'sut/parse-playwright-error "Some generic error")]
                  (expect (= {} result))))))

;; =============================================================================
;; Unit Tests — error-response (private)
;; =============================================================================

(defdescribe error-response-test
  "Unit tests for error-response — creates structured error map from error message"

  (describe "basic error"
            (it "returns success=false with error message"
                (let [result (#'sut/error-response "Something went wrong")]
                  (expect (false? (:success result)))
                  (expect (= "Something went wrong" (:error result)))))

            (it "omits call_log and selector when not present"
                (let [result (#'sut/error-response "Simple error")]
                  (expect (not (contains? result :call_log)))
                  (expect (not (contains? result :selector))))))

  (describe "Playwright error with call_log and selector"
            (it "includes call_log and selector when present in error message"
                (let [msg (str "locator.click: Timeout 30000ms exceeded.\n"
                               "=========================== logs ===========================\n"
                               "waiting for locator(\"#btn\")\n"
                               "  locator resolved to 0 elements\n"
                               "============================================================")
                      result (#'sut/error-response msg)]
                  (expect (false? (:success result)))
                  (expect (= msg (:error result)))
                  (expect (vector? (:call_log result)))
                  (expect (= "#btn" (:selector result)))))))

;; =============================================================================
;; Unit Tests — unwrap-anomaly! (private)
;; =============================================================================

(defdescribe unwrap-anomaly-test
  "Unit tests for unwrap-anomaly! — converts anomaly maps to thrown exceptions"

  (describe "pass-through for non-anomaly values"
            (it "returns a string unchanged"
                (expect (= "hello" (#'sut/unwrap-anomaly! "hello"))))

            (it "returns nil unchanged"
                (expect (nil? (#'sut/unwrap-anomaly! nil))))

            (it "returns a regular map unchanged"
                (let [m {:foo "bar"}]
                  (expect (= m (#'sut/unwrap-anomaly! m))))))

  (describe "re-throws original Playwright exception"
            (it "throws the original exception when :playwright/exception is present"
                (let [original-ex (Exception. "Playwright timeout")
                      anomaly-map (assoc (anomaly/anomaly ::anomaly/busy "Timeout 30000ms exceeded"
                                                          {:playwright/error-type :playwright.error/timeout})
                                         :playwright/exception original-ex)]
                  (try
                    (#'sut/unwrap-anomaly! anomaly-map)
                    (expect false "Should have thrown")
                    (catch Exception e
                      (expect (= original-ex e)))))))

  (describe "throws ex-info for anomaly without exception"
            (it "throws ex-info with anomaly message"
                (let [anomaly-map (anomaly/anomaly ::anomaly/fault "Browser not found"
                                                   {:playwright/error-type :playwright.error/exception})]
                  (try
                    (#'sut/unwrap-anomaly! anomaly-map)
                    (expect false "Should have thrown")
                    (catch clojure.lang.ExceptionInfo e
                      (expect (= "Browser not found" (.getMessage e)))))))))

;; =============================================================================
;; Unit Tests — process-command error propagation
;; =============================================================================

(defdescribe process-command-error-propagation-test
  "Unit tests for error propagation through process-command"

  (describe "unknown action"
            (it "returns success with error field for unknown action"
                (let [response (json/read-json
                                (#'sut/process-command
                                 (json/write-json-str {"action" "nonexistent_action"})))]
                  (expect (true? (get response "success")))
                  (expect (str/includes? (get-in response ["data" "error"])
                                         "Unknown action")))))

  (describe "error response has success=false"
            (it "returns success=false for parse errors"
                (let [response (json/read-json (#'sut/process-command "invalid json!!!"))]
                  (expect (false? (get response "success")))
                  (expect (str/includes? (get response "error") "Parse error"))))))

