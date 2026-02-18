(ns com.blockether.spel.daemon-test
  "Tests for the daemon namespace.

   Unit tests for path functions, protocol parsing, and lifecycle checks.
   No browser or socket connections required."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [com.blockether.spel.daemon :as sut]
   [lazytest.core :refer [defdescribe describe expect it]]))

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
      (let [response (json/read-str (#'sut/process-command "not valid json"))]
        (expect (false? (get response "success")))
        (expect (str/includes? (get response "error") "Parse error"))))

    (it "returns error response for empty string"
      (let [response (json/read-str (#'sut/process-command ""))]
        (expect (false? (get response "success"))))))

  (describe "unknown action"
    (it "returns success with error message for unknown action"
      (let [response (json/read-str
                       (#'sut/process-command
                        (json/write-str {"action" "nonexistent_action"})))]
        (expect (true? (get response "success")))
        (expect (str/includes? (get-in response ["data" "error"])
                  "Unknown action")))))

  (describe "close action"
    (it "returns shutdown flag"
      (let [response (json/read-str
                       (#'sut/process-command
                        (json/write-str {"action" "close"})))]
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
        (let [cmd-str (json/write-str {"action" "close"
                                       "_flags" {"user-agent" "TestAgent"
                                                 "proxy" "http://proxy:8080"}})
              _       (#'sut/process-command cmd-str)
              flags   (get @state-atom :launch-flags)]
          ;; After processing, flags should be merged
          (expect (= "TestAgent" (get flags "user-agent")))
          (expect (= "http://proxy:8080" (get flags "proxy"))))))))

;; =============================================================================
;; Unit Tests — device-presets
;; =============================================================================

(defdescribe device-presets-test
  "Unit tests for device presets"

  (describe "presets exist"
    (it "has iphone 14"
      (expect (some? (get @#'sut/device-presets "iphone 14"))))

    (it "has pixel 7"
      (expect (some? (get @#'sut/device-presets "pixel 7"))))

    (it "has ipad"
      (expect (some? (get @#'sut/device-presets "ipad"))))

    (it "has desktop"
      (expect (some? (get @#'sut/device-presets "desktop"))))

    (it "has desktop hd"
      (expect (some? (get @#'sut/device-presets "desktop hd")))))

  (describe "preset values"
    (it "iphone 14 has mobile settings"
      (let [preset (get @#'sut/device-presets "iphone 14")]
        (expect (true? (:is-mobile preset)))
        (expect (true? (:has-touch preset)))
        (expect (= 3 (:device-scale-factor preset)))
        (expect (= 390 (:width preset)))))))

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
