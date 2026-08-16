(ns com.blockether.spel.daemon-test
  "Tests for the daemon namespace.

   Unit tests for path functions, protocol parsing, and lifecycle checks.
   No browser or socket connections required."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.backend :as backend]
   [com.blockether.spel.cli :as cli]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.daemon :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server :as test-server]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.logging :as logging]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.allure :refer [around defdescribe describe expect it]])
  (:import
   [com.sun.net.httpserver HttpHandler HttpsConfigurator HttpsServer]
   [java.io BufferedReader File FileInputStream InputStreamReader]
   [java.net InetAddress InetSocketAddress]
   [java.nio.file Files]
   [java.security KeyStore]
   [javax.net.ssl KeyManagerFactory SSLContext]))

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
      (expect (not (sut/daemon-running? "nonexistent-session-12345")))))

  (describe "returns truthy for current process pid"
    (it "detects current process from PID file"
      (let [session  (str "daemon-running-current-" (System/currentTimeMillis))
            pid-path (sut/pid-file-path session)]
        (try
          (java.nio.file.Files/writeString
            pid-path
            (str (.pid (java.lang.ProcessHandle/current)))
            (into-array java.nio.file.OpenOption []))
          (expect (true? (sut/daemon-running? session)))
          (finally
            (java.nio.file.Files/deleteIfExists pid-path)))))))

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

(defdescribe ios-flag-rejection-test
  "Unit tests for the per-command iOS --allowed-domains rejection. It must
   fire on EVERY command — including against an already-running iOS backend
   — and must never merge the rejected flag into launch-flags."

  (describe "provider=ios + allowed-domains in the same command"
    (it "rejects with unsupported_capability before the flag merge"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:launch-flags {} :session "ios-flags-test"
                            :refs {} :counter 0})
        (let [response (json/read-json
                         (#'sut/process-command
                          (json/write-json-str
                            {"action" "url"
                             "_flags" {"provider" "ios"
                                       "allowed-domains" "example.com"}})))]
          (expect (false? (get response "success")))
          (expect (= "unsupported_capability" (get response "error_code")))
          (expect (str/includes? (get response "error") "allowed-domains"))
          ;; The rejected flag must NOT poison the persisted launch flags.
          (expect (nil? (get-in @state-atom [:launch-flags "allowed-domains"])))))))

  (describe "allowed-domains against a running iOS session"
    (it "rejects even when the backend already exists (startup check bypassed)"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:launch-flags {"provider" "ios"}
                            :session "ios-flags-test2"
                            :refs {} :counter 0
                            :backend :fake-running-backend})
        (let [response (json/read-json
                         (#'sut/process-command
                          (json/write-json-str
                            {"action" "navigate"
                             "url" "https://example.com"
                             "_flags" {"allowed-domains" "example.com"}})))]
          (expect (false? (get response "success")))
          (expect (= "unsupported_capability" (get response "error_code")))))))

  (describe "Playwright provider is unaffected"
    (it "allowed-domains with the default provider is not rejected here"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:launch-flags {} :session "ios-flags-test3"
                            :refs {} :counter 0})
        (let [response (json/read-json
                         (#'sut/process-command
                          (json/write-json-str
                            {"action" "device_list"
                             "_flags" {"allowed-domains" "example.com"}})))]
          (expect (true? (get response "success"))))))))

(defdescribe cdp-lifecycle-command-test
  "Unit tests for cdp_disconnect/cdp_reconnect command handling"

  (describe "cdp_disconnect"
    (it "returns not disconnected when no active cdp session"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "cdp-disconnect-test"
                            :launch-flags {}})
        (let [resp (#'sut/handle-cmd "cdp_disconnect" {})]
          (expect (= false (:disconnected resp)))))))

  (describe "cdp_reconnect"
    (it "uses provided URL and marks reconnected"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "cdp-reconnect-test"
                            :launch-flags {}})
        (with-redefs [sut/disconnect-cdp! (fn [] {:disconnected true})
                      sut/connect-cdp! (fn [url] {:connected url :url "https://example.org"})]
          (let [resp (#'sut/handle-cmd "cdp_reconnect" {"url" "ws://localhost:9222"})]
            (expect (= true (:reconnected resp)))
            (expect (= "ws://localhost:9222" (:connected resp)))))))

    (it "uses launch-flags cdp URL when explicit URL is missing"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "cdp-reconnect-flag-test"
                            :launch-flags {"cdp" "http://127.0.0.1:9222"}})
        (with-redefs [sut/disconnect-cdp! (fn [] {:disconnected true})
                      sut/connect-cdp! (fn [url] {:connected url})]
          (let [resp (#'sut/handle-cmd "cdp_reconnect" {})]
            (expect (= true (:reconnected resp)))
            (expect (= "http://127.0.0.1:9222" (:connected resp)))))))

    (it "throws when no explicit or persisted cdp URL exists"
      (let [state-atom (deref #'sut/!state)]
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "cdp-reconnect-error-test"
                            :launch-flags {}})
        (try
          (#'sut/handle-cmd "cdp_reconnect" {})
          (expect false)
          (catch clojure.lang.ExceptionInfo e
            (expect (str/includes? (.getMessage e) "No previous CDP connection found"))))))))

(defdescribe cdp-idle-timeout-test
  "Unit tests for CDP idle auto-shutdown timer"

  (describe "schedule and cancel"
    (it "schedules a future on disconnect when cdp-connected is true"
      (let [state-atom (deref #'sut/!state)
            future-atom (deref #'sut/!cdp-idle-future)
            timeout-atom (deref #'sut/!cdp-idle-timeout-ms)]
        ;; Use short timeout for testing
        (reset! timeout-atom 60000)
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "idle-schedule-test"
                            :cdp-connected true
                            :launch-flags {"cdp" "ws://test"}})
        (with-redefs [sut/release-cdp-route-lock-if-owned! (fn [])]
          (#'sut/disconnect-cdp!))
        ;; Future should be scheduled
        (let [fut @future-atom]
          (expect (some? fut))
          (expect (not (.isCancelled ^java.util.concurrent.ScheduledFuture fut)))
          ;; Clean up
          (.cancel ^java.util.concurrent.ScheduledFuture fut false)
          (reset! future-atom nil))))

    (it "does not schedule when cdp-connected was false"
      (let [state-atom (deref #'sut/!state)
            future-atom (deref #'sut/!cdp-idle-future)
            timeout-atom (deref #'sut/!cdp-idle-timeout-ms)]
        (reset! timeout-atom 60000)
        (reset! future-atom nil)
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "idle-no-schedule-test"
                            :cdp-connected false
                            :launch-flags {}})
        (#'sut/disconnect-cdp!)
        ;; No future scheduled — wasn't a CDP session
        (expect (nil? @future-atom))))

    (it "does not schedule when timeout is 0 (disabled)"
      (let [state-atom (deref #'sut/!state)
            future-atom (deref #'sut/!cdp-idle-future)
            timeout-atom (deref #'sut/!cdp-idle-timeout-ms)]
        (reset! timeout-atom 0)
        (reset! future-atom nil)
        (reset! state-atom {:pw nil :browser nil :context nil :page nil
                            :refs {} :counter 0 :headless true
                            :session "idle-disabled-test"
                            :cdp-connected true
                            :launch-flags {"cdp" "ws://test"}})
        (with-redefs [sut/release-cdp-route-lock-if-owned! (fn [])]
          (#'sut/disconnect-cdp!))
        (expect (nil? @future-atom))))

    (it "cancels scheduled future on connect"
      (let [future-atom (deref #'sut/!cdp-idle-future)
            timeout-atom (deref #'sut/!cdp-idle-timeout-ms)]
        (reset! timeout-atom 60000)
        ;; Pre-schedule a dummy future
        (#'sut/schedule-cdp-idle-shutdown!)
        (let [fut @future-atom]
          (expect (some? fut))
          ;; Cancel via connect path
          (#'sut/cancel-cdp-idle-shutdown!)
          (expect (.isCancelled ^java.util.concurrent.ScheduledFuture fut))
          (expect (nil? @future-atom)))))))

(defdescribe session-idle-timeout-test
  "Unit tests for session idle auto-shutdown timer"

  (describe "schedule and cancel"
    (it "schedules a future when timeout is positive"
      (let [future-atom (deref #'sut/!session-idle-future)
            timeout-atom (deref #'sut/!session-idle-timeout-ms)
            orig @timeout-atom]
        (reset! timeout-atom 60000)
        (reset! future-atom nil)
        (try
          (#'sut/schedule-session-idle-shutdown!)
          (let [fut @future-atom]
            (expect (some? fut))
            (expect (not (.isCancelled ^java.util.concurrent.ScheduledFuture fut)))
            ;; Clean up
            (.cancel ^java.util.concurrent.ScheduledFuture fut false)
            (reset! future-atom nil))
          (finally
            (reset! timeout-atom orig)))))

    (it "does not schedule when timeout is 0 (disabled)"
      (let [future-atom (deref #'sut/!session-idle-future)
            timeout-atom (deref #'sut/!session-idle-timeout-ms)
            orig @timeout-atom]
        (reset! timeout-atom 0)
        (reset! future-atom nil)
        (try
          (#'sut/schedule-session-idle-shutdown!)
          (expect (nil? @future-atom))
          (finally
            (reset! timeout-atom orig)))))

    (it "reschedule cancels previous future"
      (let [future-atom (deref #'sut/!session-idle-future)
            timeout-atom (deref #'sut/!session-idle-timeout-ms)
            orig @timeout-atom]
        (reset! timeout-atom 60000)
        (try
          (#'sut/schedule-session-idle-shutdown!)
          (let [fut1 @future-atom]
            (expect (some? fut1))
            ;; Reschedule — previous future should be cancelled
            (#'sut/schedule-session-idle-shutdown!)
            (expect (.isCancelled ^java.util.concurrent.ScheduledFuture fut1))
            (let [fut2 @future-atom]
              (expect (some? fut2))
              (expect (not= fut1 fut2))
              ;; Clean up
              (.cancel ^java.util.concurrent.ScheduledFuture fut2 false)
              (reset! future-atom nil)))
          (finally
            (reset! timeout-atom orig)))))

    (it "cancel-session-idle-shutdown! clears the future"
      (let [future-atom (deref #'sut/!session-idle-future)
            timeout-atom (deref #'sut/!session-idle-timeout-ms)
            orig @timeout-atom]
        (reset! timeout-atom 60000)
        (try
          (#'sut/schedule-session-idle-shutdown!)
          (let [fut @future-atom]
            (expect (some? fut))
            (#'sut/cancel-session-idle-shutdown!)
            (expect (.isCancelled ^java.util.concurrent.ScheduledFuture fut))
            (expect (nil? @future-atom)))
          (finally
            (reset! timeout-atom orig)))))))

(defdescribe click-diagnostics-test
  "Unit tests for click error diagnostics helpers"

  (describe "yes-no formatting"
    (it "renders booleans as Yes/No"
      (expect (= "Yes" (#'sut/yes-no true)))
      (expect (= "No" (#'sut/yes-no false)))))

  (describe "throw-click-error!"
    (it "throws ex-info containing structured diagnostics"
      (let [thrown? (try
                      (#'sut/throw-click-error!
                       "@e123"
                       {:found false :visible nil :enabled nil}
                       (ex-info "original cause" {}))
                      false
                      (catch clojure.lang.ExceptionInfo e
                        (let [m (.getMessage e)
                              d (ex-data e)]
                          (expect (str/includes? m "Click failed for @e123"))
                          (expect (str/includes? m "Element found: No"))
                          (expect (str/includes? m "Element visible: Unknown"))
                          (expect (str/includes? m "Element enabled: Unknown"))
                          (expect (= "@e123" (:selector d)))
                          (expect (false? (:found d)))
                          true)))]
        (expect thrown?)))))

(defdescribe error-response-humanization-test
  "Unit tests for humanized error responses"

  (describe "error-response"
    (it "adds hint and error_code for No page loaded errors"
      (let [resp (#'sut/error-response "No page loaded. Navigate first: spel open <url>")]
        (expect (false? (:success resp)))
        (expect (= "No page loaded. Navigate first: spel open <url>" (:error resp)))
        (expect (string? (:hint resp)))
        (expect (= "no_page_loaded" (:error_code resp)))))

    (it "adds generic hint for Unknown error"
      (let [resp (#'sut/error-response "Unknown error")]
        (expect (false? (:success resp)))
        (expect (= "Unknown error" (:error resp)))
        (expect (string? (:hint resp)))
        (expect (= "unknown_error" (:error_code resp)))))

    (it "uses humanized fallback when message is missing"
      (let [resp (#'sut/error-response nil)]
        (expect (false? (:success resp)))
        (expect (str/includes? (:error resp) "unexpected browser error"))
        (expect (string? (:hint resp)))
        (expect (= "unknown_error" (:error_code resp))))))

  (describe "find_free_port action"
    (it "returns valid port"
      (let [resp (#'sut/handle-cmd "find_free_port" {})
            port (:port resp)]
        (expect (integer? port))
        (expect (<= 1 port))
        (expect (<= port 65535))))

    (it "process-command dispatches find_free_port action"
      (let [response (json/read-json
                       (#'sut/process-command
                        (json/write-json-str {"action" "find_free_port"})))
            port (get-in response ["data" "port"])]
        (expect (true? (get response "success")))
        (expect (integer? port))
        (expect (<= 1 port))
        (expect (<= port 65535))))))

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

(defdescribe flags-file-path-test
  "Unit tests for flags-file-path"

  (describe "returns path with session name and .flags.json extension"
    (it "contains session name and .flags.json"
      (let [p (str (sut/flags-file-path "test-session"))]
        (expect (clojure.string/includes? p "test-session"))
        (expect (clojure.string/ends-with? p ".flags.json"))))

    (it "uses the spel prefix"
      (let [p (str (sut/flags-file-path "default"))]
        (expect (clojure.string/includes? p "spel-default.flags.json"))))))

(defdescribe persist-and-read-launch-flags-test
  "Unit tests for launch flags persistence (write to file + read back)"

  (describe "round-trips flags through file"
    (it "persists flags and reads them back"
      (let [state-atom (deref #'sut/!state)
            session    "flags-test-roundtrip"]
        (try
          (reset! state-atom {:pw nil :browser nil :context nil :page nil
                              :refs {} :counter 0 :headless true
                              :session session
                              :launch-flags {"cdp" "http://127.0.0.1:9222"
                                             "browser" "chromium"}})
          (#'sut/persist-launch-flags!)
          (let [read-back (sut/read-session-flags session)]
            (expect (= "http://127.0.0.1:9222" (get read-back "cdp")))
            (expect (= "chromium" (get read-back "browser"))))
          (finally
            (java.nio.file.Files/deleteIfExists (sut/flags-file-path session)))))))

  (describe "read returns empty map for nonexistent session"
    (it "returns {} when no flags file"
      (expect (= {} (sut/read-session-flags "nonexistent-flags-session-xyz")))))

  (describe "process-command persists flags to file"
    (it "writes flags file when _flags present in command"
      (let [state-atom (deref #'sut/!state)
            session    "flags-test-process-cmd"]
        (try
          (reset! state-atom {:pw nil :browser nil :context nil :page nil
                              :refs {} :counter 0 :headless true
                              :session session
                              :launch-flags {}})
          (#'sut/process-command
           (charred.api/write-json-str
             {"action" "close"
              "_flags" {"cdp" "http://localhost:9222"}}))
          (let [read-back (sut/read-session-flags session)]
            (expect (= "http://localhost:9222" (get read-back "cdp"))))
          (finally
            (java.nio.file.Files/deleteIfExists (sut/flags-file-path session))))))))

(defdescribe discover-cdp-endpoint-test
  "Unit tests for discover-cdp-endpoint"

  (describe "returns valid CDP URL or throws when no Chrome is running"
    (it "returns a string starting with http:// or ws:// or throws ex-info"
      (try
        (let [url (sut/discover-cdp-endpoint)]
                    ;; If Chrome is running locally, we get a valid URL
          (expect (string? url))
          (expect (or (str/starts-with? url "http://127.0.0.1:")
                    (str/starts-with? url "ws://127.0.0.1:"))))
        (catch clojure.lang.ExceptionInfo e
          ;; If no Chrome is running, we get a descriptive error
          (expect (str/includes? (.getMessage e) "No running browser"))
          (expect (contains? (ex-data e) :probed-ports)))))

    (it "falls back to ws URL when DevToolsActivePort has ws-path and HTTP probe fails"
      (with-redefs-fn {#'sut/parse-devtools-active-port (fn [_]
                                                          {:port 9222 :ws-path "/devtools/browser/ws-id"})
                       #'sut/probe-http-cdp              (fn [& _] nil)}
        #(expect (= "ws://127.0.0.1:9222/devtools/browser/ws-id"
                   (sut/discover-cdp-endpoint)))))

    (it "falls back to http URL when ws-path is missing"
      (with-redefs-fn {#'sut/parse-devtools-active-port (fn [_]
                                                          {:port 9222 :ws-path nil})
                       #'sut/probe-http-cdp              (fn [& _] nil)}
        #(expect (= "http://127.0.0.1:9222"
                   (sut/discover-cdp-endpoint))))))

  (describe "parse-devtools-active-port"
    (it "parses a valid DevToolsActivePort file"
      (let [tmp-dir (java.io.File/createTempFile "spel-dt-test" "")
            _      (.delete tmp-dir)
            _      (.mkdirs tmp-dir)
            dt-file (java.io.File. tmp-dir "DevToolsActivePort")]
        (try
          (spit dt-file "9222\n/devtools/browser/abc-123\n")
          (let [result (#'sut/parse-devtools-active-port (.getPath dt-file))]
            (expect (= 9222 (:port result)))
            (expect (= "/devtools/browser/abc-123" (:ws-path result))))
          (finally
            (.delete dt-file)
            (.delete tmp-dir)))))

    (it "returns nil for missing file"
      (expect (nil? (#'sut/parse-devtools-active-port "/nonexistent/DevToolsActivePort"))))

    (it "returns nil for invalid content"
      (let [tmp (java.io.File/createTempFile "spel-dt-bad" ".txt")]
        (try
          (spit tmp "not-a-port\n")
          (expect (nil? (#'sut/parse-devtools-active-port (.getPath tmp))))
          (finally
            (.delete tmp)))))

    (it "handles port-only file (no ws-path)"
      (let [tmp (java.io.File/createTempFile "spel-dt-port" ".txt")]
        (try
          (spit tmp "9222\n")
          (let [result (#'sut/parse-devtools-active-port (.getPath tmp))]
            (expect (= 9222 (:port result)))
            (expect (nil? (:ws-path result))))
          (finally
            (.delete tmp))))))

  (describe "probe-http-cdp"
    (it "returns nil for ports not listening"
      (expect (nil? (#'sut/probe-http-cdp 19999 500))))

    (it "returns nil when endpoint responds non-200"
      (let [server (com.sun.net.httpserver.HttpServer/create
                     (java.net.InetSocketAddress. "127.0.0.1" 0)
                     0)]
        (try
          (.createContext server "/json/version"
            (reify com.sun.net.httpserver.HttpHandler
              (handle [_ exchange]
                (.sendResponseHeaders exchange 404 -1)
                (.close (.getResponseBody exchange)))))
          (.start server)
          (let [port (.getPort (.getAddress server))]
            (expect (nil? (#'sut/probe-http-cdp port 1000))))
          (finally
            (.stop server 0)))))

    (it "returns port when endpoint responds 200"
      (let [server (com.sun.net.httpserver.HttpServer/create
                     (java.net.InetSocketAddress. "127.0.0.1" 0)
                     0)]
        (try
          (.createContext server "/json/version"
            (reify com.sun.net.httpserver.HttpHandler
              (handle [_ exchange]
                ;; probe-http-cdp requires a non-blank `Browser` field in the
                ;; JSON body, not just HTTP 200 — this prevents false positives
                ;; from random HTTP servers running on CDP ports.
                (let [body (.getBytes "{\"Browser\":\"FakeChrome/1.0\"}"
                             java.nio.charset.StandardCharsets/UTF_8)]
                  (.sendResponseHeaders exchange 200 (alength body))
                  (with-open [os (.getResponseBody exchange)]
                    (.write os body))))))
          (.start server)
          (let [port (.getPort (.getAddress server))]
            (expect (= port (#'sut/probe-http-cdp port 1000))))
          (finally
            (.stop server 0)))))

    (it "requires an accepted CDP endpoint rather than only an open TCP port"
      (with-redefs-fn {#'sut/probe-http-cdp (fn [& _] nil)
                       #'sut/port-in-use?  (fn [& _]
                                             (throw (ex-info "must not use TCP readiness" {})))}
        #(expect (false? (#'sut/cdp-ready? 9222)))))

    (it "accepts a port after Chrome exposes its CDP endpoint"
      (with-redefs-fn {#'sut/probe-http-cdp (fn [_ _] 9222)}
        #(expect (true? (#'sut/cdp-ready? 9222)))))))

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

;; =============================================================================
;; Unit Tests — Auto-Launch Port Allocation
;; =============================================================================

(defdescribe find-free-cdp-port-test
  "Unit tests for find-free-cdp-port"

  (describe "returns a port number"
    (it "returns a number >= 9222"
      (let [port (sut/find-free-cdp-port)]
        (expect (integer? port))
        (expect (>= port 9222)))))

  (describe "returns different ports when locks are held"
    (it "skips ports with active lock files"
      (let [session  (str "port-test-" (System/currentTimeMillis))
            pid      (.pid (java.lang.ProcessHandle/current))
            ;; Create a lock for port 9222 owned by a session with current PID
            ;; (so it looks alive)
            _        (#'sut/write-auto-launch-lock! 9222 session pid)
            ;; Also write a PID file so daemon-running? returns true
            pid-path (sut/pid-file-path session)]
        (try
          (java.nio.file.Files/writeString pid-path (str pid)
            (into-array java.nio.file.OpenOption []))
          (let [port (sut/find-free-cdp-port)]
            (expect (> port 9222)))
          (finally
            (#'sut/clear-auto-launch-lock! 9222)
            (java.nio.file.Files/deleteIfExists pid-path)))))))

;; =============================================================================
;; Unit Tests — resolve-browser-binary
;; =============================================================================

(defdescribe resolve-browser-binary-test
  "Unit tests for resolve-browser-binary"

  (describe "throws for unknown channel"
    (it "throws ex-info for invalid channel name"
      (try
        (sut/resolve-browser-binary "netscape-navigator")
        (expect false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (str/includes? (.getMessage e) "Unknown browser channel"))
          (expect (= "netscape-navigator" (:channel (ex-data e))))))))

  (describe "returns a string path for known channels"
    (it "returns a string for chrome channel (may not exist on CI)"
      ;; We test the path resolution, not whether the binary exists
      ;; since CI may not have Chrome installed.
      ;; Just verify the function doesn't throw for format issues.
      (let [os-name (str/lower-case (System/getProperty "os.name"))
            linux?  (str/includes? os-name "linux")]
        (when linux?
          ;; On Linux, the binary name is just a command name
          ;; which may or may not be on PATH. Test that the function
          ;; either returns a string or throws with a clear message.
          (try
            (let [path (sut/resolve-browser-binary "chrome")]
              (expect (string? path))
              (expect (= "google-chrome" path)))
            (catch clojure.lang.ExceptionInfo e
              ;; Expected if Chrome is not installed
              (expect (str/includes? (.getMessage e) "Browser binary not found")))))))))

;; =============================================================================
;; Unit Tests — Auto-Launch Lock Files
;; =============================================================================

(defdescribe auto-launch-lock-test
  "Unit tests for auto-launch lock file management"

  (describe "write and read lock"
    (it "creates a lock file that can be read back"
      (let [port 19222
            session "lock-test-session"
            pid 12345]
        (try
          (#'sut/write-auto-launch-lock! port session pid)
          (let [lock (#'sut/read-auto-launch-lock port)]
            (expect (some? lock))
            (expect (= session (get lock "session")))
            (expect (= port (get lock "port")))
            (expect (= pid (get lock "browser_pid"))))
          (finally
            (#'sut/clear-auto-launch-lock! port))))))

  (describe "clear lock"
    (it "removes the lock file"
      (let [port 19223]
        (#'sut/write-auto-launch-lock! port "test" 99999)
        (#'sut/clear-auto-launch-lock! port)
        (expect (nil? (#'sut/read-auto-launch-lock port))))))

  (describe "stale lock cleanup"
    (it "clears lock when owning session is not running"
      (let [port 19224]
        (#'sut/write-auto-launch-lock! port "dead-session-99999" 99999)
        ;; dead-session-99999 has no PID file, so daemon-running? returns false
        (expect (not (#'sut/auto-launch-lock-active? port)))
        ;; Lock should have been cleaned up
        (expect (nil? (#'sut/read-auto-launch-lock port)))))))
(defdescribe cdp-tab-ownership-test
  "Foreign CDP attachments preserve pre-existing user tabs."

  (it "treats every tab spel did not open as user-owned"
    (let [state-atom (deref #'sut/!state)
          user-tab (Object.)
          spel-tab (Object.)]
      (reset! state-atom {:cdp-foreign true :adopted-pages #{user-tab}
                          :spel-pages #{spel-tab}})
      (expect (true? (#'sut/user-owned-page? user-tab)))
      (expect (false? (#'sut/user-owned-page? spel-tab)))
      (expect (true? (#'sut/foreign-browser?)))))

  (it "protects tabs the user opens AFTER spel attached"
    (let [state-atom (deref #'sut/!state)
          spel-tab (Object.)
          later-user-tab (Object.)]
      (reset! state-atom {:cdp-foreign true :adopted-pages #{}
                          :spel-pages #{spel-tab}})
      ;; not adopted at attach time and not opened by spel -> still the user's
      (expect (true? (#'sut/user-owned-page? later-user-tab)))
      (expect (false? (#'sut/user-owned-page? spel-tab)))))

  (it "does not treat pages as user-owned outside foreign CDP mode"
    (let [state-atom (deref #'sut/!state)
          tab (Object.)]
      (reset! state-atom {:cdp-foreign false :adopted-pages #{tab} :spel-pages #{}})
      (expect (false? (#'sut/user-owned-page? tab)))
      (expect (false? (#'sut/foreign-browser?))))))

;; =============================================================================
;; Unit Tests — issue #109: a dead browser handle must not poison the session
;; =============================================================================

(defn- message-less-throwable
  "A NullPointerException carrying no message — exactly what the GraalVM native
   image throws when a nil page handle is called into (helpful NPEs are off in
   the shipped binary), and what issue #109 saw on the wire as a bare
   {\"error\":\"NullPointerException\"}."
  ^Throwable []
  (doto (NullPointerException.) (.fillInStackTrace)))

(defdescribe message-less-throwable-diagnostics-test
  "A throwable with no message still has to say WHERE it was thrown."

  (it "throwable-origin names a spel/Playwright frame"
    (let [origin (#'sut/throwable-origin (message-less-throwable))]
      (expect (string? origin))
      (expect (str/includes? origin "com.blockether"))))

  (it "throwable-origin is nil for no throwable"
    (expect (nil? (#'sut/throwable-origin nil))))

  (it "throwable-message upgrades a bare class name with its call site"
    (let [msg (#'sut/throwable-message (message-less-throwable))]
      (expect (str/starts-with? msg "NullPointerException at "))
      (expect (str/includes? msg "com.blockether"))))

  (it "throwable-message still prefers a real message"
    (expect (= "boom" (#'sut/throwable-message (Exception. "boom")))))

  (it "default-error-message points at the failing frame"
    (let [msg (#'sut/default-error-message (message-less-throwable))]
      (expect (str/includes? msg "NullPointerException"))
      (expect (str/includes? msg " at "))
      (expect (not (str/includes? msg "no details from runtime")))))

  (it "default-error-message keeps its no-throwable fallback"
    (expect (= "unexpected browser error (no details from runtime)"
              (#'sut/default-error-message))))

  (it "error-response classifies a null handle as browser_handle_lost"
    (let [result (#'sut/error-response (#'sut/throwable-message (message-less-throwable)))]
      (expect (false? (:success result)))
      (expect (= "browser_handle_lost" (:error_code result)))
      (expect (string? (:hint result)))
      (expect (str/includes? (:hint result) "retry")))))

(defdescribe live-page-reconciliation-test
  "issue #109 — a dead or nil page handle is re-attached, never called into."

  (it "relaunches and returns the fresh page when handles were dropped"
    (let [state-atom (deref #'sut/!state)
          fresh (Object.)
          calls (atom 0)]
      (reset! state-atom {:page nil :browser nil})
      (with-redefs-fn {#'sut/ensure-browser! (fn []
                                               (swap! calls inc)
                                               (swap! state-atom assoc
                                                 :page fresh :browser (Object.)))}
        (fn []
          (expect (identical? fresh (#'sut/live-page)))
          (expect (= 1 @calls))))))

  (it "re-attaches when the tab was closed outside the daemon"
    (let [state-atom (deref #'sut/!state)
          fresh (Object.)
          calls (atom 0)]
      (reset! state-atom {:page (Object.) :browser (Object.)})
      (with-redefs-fn {#'sut/page-open? (constantly false)
                       #'sut/browser-connected? (constantly true)
                       #'sut/ensure-browser! (fn []
                                               (swap! calls inc)
                                               (swap! state-atom assoc :page fresh))}
        (fn []
          (expect (identical? fresh (#'sut/live-page)))
          (expect (= 1 @calls))))))

  (it "re-attaches when the browser itself is gone"
    (let [state-atom (deref #'sut/!state)
          fresh (Object.)
          calls (atom 0)]
      (reset! state-atom {:page (Object.) :browser (Object.)})
      (with-redefs-fn {#'sut/page-open? (constantly true)
                       #'sut/browser-connected? (constantly false)
                       #'sut/ensure-browser! (fn []
                                               (swap! calls inc)
                                               (swap! state-atom assoc :page fresh))}
        (fn []
          (expect (identical? fresh (#'sut/live-page)))
          (expect (= 1 @calls))))))

  (it "leaves a healthy page untouched"
    (let [state-atom (deref #'sut/!state)
          page (Object.)
          calls (atom 0)]
      (reset! state-atom {:page page :browser (Object.)})
      (with-redefs-fn {#'sut/page-open? (constantly true)
                       #'sut/browser-connected? (constantly true)
                       #'sut/ensure-browser! (fn [] (swap! calls inc))}
        (fn []
          (expect (identical? page (#'sut/live-page)))
          (expect (zero? @calls))))))

  (it "fails with an actionable message instead of a NullPointerException"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:page nil :browser nil})
      (with-redefs-fn {#'sut/ensure-browser! (fn [] nil)}
        (fn []
          (try
            (#'sut/live-page)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "No browser page available"))
              (expect (= :no_page_loaded (:error_code (ex-data e))))))))))

  (it "ensure-page-loaded! reports the missing page instead of dereferencing nil"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:page nil :browser nil})
      (with-redefs-fn {#'sut/ensure-browser! (fn [] nil)}
        (fn []
          (try
            (#'sut/ensure-page-loaded!)
            (expect false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (expect (str/includes? (.getMessage e) "No browser page available")))))))))

;; =============================================================================
;; Unit Tests — Failure diagnosis in the session log
;; =============================================================================

(defn- capture-daemon-log
  "Runs `f` with the logger pointed at a private session file and returns the
   lines it wrote."
  [f]
  (let [session (str "daemon-test-" (System/nanoTime))]
    (logging/init! {:session session :component "daemon" :level :debug :mirror :off})
    (try
      (f)
      (logging/read-lines session {})
      (finally
        (logging/init! {:session "default" :component "spel" :level :info :mirror :warn})
        (try (.delete (.toFile (logging/log-file-path session))) (catch Exception _ nil))))))

(defdescribe response-failure-test
  "Unit tests for response-failure"

  (describe "successful responses carry nothing worth logging"
    (it "returns nil for a success payload"
      (expect (nil? (#'sut/response-failure "{\"success\":true,\"data\":{\"x\":1}}"))))

    (it "returns nil for an empty response"
      (expect (nil? (#'sut/response-failure "")))))

  (describe "failing responses are mined for the only account of what broke"
    (it "extracts error_code and error text"
      (let [f (#'sut/response-failure
               "{\"success\":false,\"error_code\":\"browser_handle_lost\",\"error\":\"page was closed\"}")]
        (expect (= "browser_handle_lost" (:code f)))
        (expect (= "page was closed" (:message f)))))

    (it "falls back to unknown when the payload carries no code"
      (expect (= "unknown" (:code (#'sut/response-failure
                                   "{\"success\":false,\"error\":\"boom\"}")))))

    (it "survives an unparseable failure payload"
      (let [f (#'sut/response-failure "{\"success\":false, this is not json")]
        (expect (= "unknown" (:code f)))
        (expect (nil? (:message f)))))

    (it "truncates a runaway error message"
      (let [long-msg (apply str (repeat 400 "x"))
            f        (#'sut/response-failure
                      (str "{\"success\":false,\"error_code\":\"e\",\"error\":\"" long-msg "\"}"))]
        (expect (= 241 (count (:message f))))
        (expect (str/ends-with? (:message f) "…"))))))

(defdescribe log-command!-test
  "Unit tests for log-command! — a failure line must say WHY"

  (describe "failing commands"
    (it "logs error_code and the error text at warn level"
      (let [line (first (capture-daemon-log
                          (fn []
                            (#'sut/log-command! "sci_eval" {"code" "(spel/route! ...)"}
                                                "{\"success\":false,\"error_code\":\"handler_arity\",\"error\":\"Wrong number of args (1) passed to: sci.impl.fns/fun/arity-2\"}"
                                                42))))]
        (expect (str/includes? line "WARN"))
        (expect (str/includes? line "cmd sci_eval"))
        (expect (str/includes? line "-> error in 42ms"))
        (expect (str/includes? line "code=handler_arity"))
        (expect (str/includes? line "Wrong number of args (1)")))))

  (describe "successful commands"
    (it "logs parameter NAMES and never their values"
      (let [line (first (capture-daemon-log
                          (fn []
                            (#'sut/log-command! "goto" {"password" "hunter2"}
                                                "{\"success\":true,\"data\":{}}" 3))))]
        (expect (str/includes? line "INFO"))
        (expect (str/includes? line "cmd goto"))
        (expect (str/includes? line "\"password\""))
        (expect (not (str/includes? line "hunter2")))
        (expect (str/includes? line "-> ok in 3ms"))
        (expect (not (str/includes? line "code=")))))))

;; =============================================================================
;; Unit Tests — Client/daemon timeout invariant
;; =============================================================================

(defdescribe command-budget-test
  "Unit tests for command-budget-ms and client-timeout-ms"

  (describe "open-ended work gets minutes"
    (it "gives sci_eval and every ios action the long budget"
      (expect (= 900000 (sut/command-budget-ms "sci_eval")))
      (expect (= 900000 (sut/command-budget-ms "script")))
      (expect (= 900000 (sut/command-budget-ms "ios_tap")))
      (expect (= 900000 (sut/command-budget-ms "ios-snapshot")))))

  (describe "ordinary commands"
    (it "never drop below the default budget"
      (expect (>= (sut/command-budget-ms "goto") 25000))
      (expect (>= (sut/command-budget-ms "snapshot") 25000))))

  (describe "the invariant: the client must outlive the daemon budget"
    (it "holds for every action shape"
      (doseq [action ["sci_eval" "script" "ios_tap" "ios-snapshot" "goto" "snapshot" "unknown"]]
        (expect (> (sut/client-timeout-ms action)
                  (sut/command-budget-ms action)))))

    (it "adds a fixed slack so the daemon always reports first"
      (expect (= (+ 5000 (sut/command-budget-ms "sci_eval"))
                (sut/client-timeout-ms "sci_eval"))))))

;; =============================================================================
;; Unit Tests — Wedged-command diagnostics
;; =============================================================================

(defdescribe signal-frames-test
  "Unit tests for signal-frames"

  (describe "parking plumbing is noise"
    (it "keeps only spel, Playwright and SCI frames"
      (let [frames ["jdk.internal.misc.Unsafe.park(Native Method)"
                    "java.util.concurrent.locks.LockSupport.park(LockSupport.java:341)"
                    "java.util.concurrent.CompletableFuture.waitingGet(CompletableFuture.java:1)"
                    "com.microsoft.playwright.impl.PipeTransport.poll(PipeTransport.java:60)"
                    "com.blockether.spel.daemon$process_command.invoke(daemon.clj:1)"
                    "sci.impl.fns$fun$arity_2.invoke(fns.clj:1)"]
            kept   (#'sut/signal-frames frames)]
        (expect (= 3 (count kept)))
        (expect (every? (fn [f] (or (str/starts-with? f "com.")
                                  (str/starts-with? f "sci.")))
                  kept)))))

  (describe "a noisy stack still beats no stack"
    (it "falls back to the raw frames when nothing matches"
      (let [frames ["jdk.internal.misc.Unsafe.park(Native Method)"
                    "java.lang.Thread.run(Thread.java:1)"]]
        (expect (= frames (#'sut/signal-frames frames))))))

  (describe "bounded output"
    (it "caps the dump at twelve frames"
      (let [frames (mapv (fn [n] (str "com.blockether.spel.daemon$f" n ".invoke(daemon.clj:" n ")"))
                     (range 30))]
        (expect (= 12 (count (#'sut/signal-frames frames))))))))

(defdescribe client-gone?-test
  "Unit tests for client-gone?"

  (describe "socket errors that only mean the client hung up"
    (it "recognises broken pipe and connection reset"
      (expect (true? (boolean (#'sut/client-gone? (java.io.IOException. "Broken pipe")))))
      (expect (true? (boolean (#'sut/client-gone?
                               (java.io.IOException. "Connection reset by peer")))))))

  (describe "real daemon faults"
    (it "is false for anything else, including a message-less throwable"
      (expect (false? (boolean (#'sut/client-gone? (NullPointerException.)))))
      (expect (false? (boolean (#'sut/client-gone? (ex-info "browser closed" {}))))))))

;; =============================================================================
;; Regression, issue #113: under a burst of concurrent responses the Playwright
;; `response` listener threw java.lang.StackOverflowError once per response —
;; hundreds of WARN lines from a single page load, and the listener's work
;; silently skipped for every affected response. The handler read
;; `.allHeaders`/`.text`, which round-trip to the driver and re-enter the event
;; dispatch loop from inside the handler, so every in-flight response stacked
;; one more nested handler frame.
;; =============================================================================

(defn- burst-handler-nesting
  "Runs `handler` as `pg`'s response listener while the page fires `n`
   concurrent fetches, and reports how deeply the handler nested.

   Params:
   `pg`      - Page instance, already on the test server's origin.
   `handler` - Function receiving a Response.
   `n`       - Long. Number of concurrent fetches to fire.

   Returns:
   Map with :max-nesting (deepest simultaneous handler depth) and :handled."
  [pg handler n]
  (let [!depth (atom 0)
        !max   (atom 0)
        !seen  (atom 0)]
    (page/on-response pg
      (fn [resp]
        (let [d (swap! !depth inc)]
          (swap! !max max d)
          (try
            (handler resp)
            (finally
              (swap! !seen inc)
              (swap! !depth dec))))))
    (page/evaluate pg (str "(()=>{for(let i=0;i<" n ";i++){fetch('/health?i='+i);}return 'fired';})()"))
    (let [deadline (+ (System/currentTimeMillis) 60000)]
      (while (and (< (long @!seen) (long n))
               (< (System/currentTimeMillis) (long deadline)))
        (page/wait-for-timeout pg 100)))
    {:max-nesting @!max :handled @!seen}))

(defdescribe response-listener-burst-test
  "The response listener under a burst of concurrent responses."
  (around [f] (core/with-testing-browser ((:around test-server/with-test-server) f)))

  (describe "400 concurrent responses"
    (it "handles every response without nesting the handler"
      (core/with-testing-page [pg]
        (page/navigate pg (str test-server/*test-server-url* "/health"))
        (let [network-full (var-get #'sut/!network-full)
              before       (count @network-full)
              n            400
              {:keys [max-nesting handled]}
              (burst-handler-nesting pg (var-get #'sut/track-response!) n)]
          (expect (= n handled))
          ;; Pre-fix this reached the burst size itself — one nested frame per
          ;; in-flight response — and every handler died with StackOverflowError.
          (expect (= 1 max-nesting))
          ;; The work the listener exists for actually happened.
          (expect (<= (long n) (long (- (count @network-full) before)))))))))

;; =============================================================================
;; TLS-fronted CDP endpoints — https:// and wss://
;; =============================================================================
;;
;; Regression: the `connect` preflight probed every endpoint in plaintext. An
;; https:// CDP URL was refused with "CDP probe could not complete an HTTP
;; request … Unexpected end of file from server", a live wss:// URL with "CDP
;; browser target no longer exists", and a port-less wss:// URL was probed on
;; port 80 — even though Playwright itself fetches <endpoint>/json/version over
;; TLS and dials wss:// transports, so hosted/remote CDP endpoints could not be
;; reached at all.

(defn- self-signed-ssl-context
  "SSLContext holding a throwaway self-signed localhost certificate, generated by
   the JDK's own keytool so the suite needs no extra dependency and no checked-in
   key material."
  ^SSLContext []
  (let [store   (doto (File/createTempFile "spel-tls-test" ".p12") (.delete))
        keytool (str (System/getProperty "java.home") File/separator "bin" File/separator "keytool")
        proc    (-> (ProcessBuilder. [keytool "-genkeypair" "-alias" "spel" "-keyalg" "RSA"
                                      "-keysize" "2048" "-dname" "CN=localhost" "-validity" "1"
                                      "-ext" "SAN=ip:127.0.0.1,dns:localhost"
                                      "-keystore" (.getAbsolutePath store) "-storetype" "PKCS12"
                                      "-storepass" "spelspel" "-keypass" "spelspel"])
                  (.redirectErrorStream true)
                  (.start))
        output  (slurp (.getInputStream proc))]
    (when-not (zero? (.waitFor proc))
      (throw (ex-info (str "keytool could not generate a test certificate: " output) {})))
    (try
      (let [pass (.toCharArray "spelspel")
            ks   (doto (KeyStore/getInstance "PKCS12")
                   (.load (FileInputStream. store) pass))
            kmf  (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                   (.init ks pass))]
        (doto (SSLContext/getInstance "TLS")
          (.init (.getKeyManagers kmf) nil nil)))
      (finally
        (.delete store)))))

(defn- answer-websocket-upgrade!
  "Reads the HTTP upgrade request off the socket and answers 101, the way a CDP
   browser target does."
  [^java.net.Socket sock]
  (try
    (with-open [^java.net.Socket s sock]
      (let [in (BufferedReader. (InputStreamReader. (.getInputStream s) "UTF-8"))]
        (loop [line (.readLine in)]
          (when (and line (not (str/blank? line)))
            (recur (.readLine in))))
        (doto (.getOutputStream s)
          (.write (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\n"
                               "Upgrade: websocket\r\n"
                               "Connection: Upgrade\r\n\r\n")
                    "UTF-8"))
          (.flush))))
    (catch Exception _ nil)))

(defn- with-tls-cdp-endpoint
  "Starts a TLS /json/version responder and a TLS WebSocket-upgrade responder on
   ephemeral loopback ports — a hosted CDP endpoint in miniature — and calls
   `(f {:https-port p :wss-port p})`."
  [f]
  (let [ctx     (self-signed-ssl-context)
        https   (doto (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                  (.setHttpsConfigurator (HttpsConfigurator. ctx))
                  (.createContext "/json/version"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [body (.getBytes (json/write-json-str {"Browser" "Chrome/141.0.0.0"}) "UTF-8")]
                          (.sendResponseHeaders exchange 200 (alength body))
                          (with-open [os (.getResponseBody exchange)]
                            (.write os body))))))
                  (.start))
        wss     (.createServerSocket (.getServerSocketFactory ctx) 0 16
                  (InetAddress/getByName "127.0.0.1"))
        accepts (future
                  (try
                    (loop []
                      (let [sock (.accept wss)]
                        (future (answer-websocket-upgrade! sock)))
                      (recur))
                    (catch Exception _ nil)))]
    (try
      (f {:https-port (.getPort (.getAddress https))
          :wss-port   (.getLocalPort wss)})
      (finally
        (.stop https 0)
        (.close wss)
        (future-cancel accepts)))))

(defdescribe tls-cdp-endpoint-test
  "The connect preflight against TLS-fronted CDP endpoints."

  (describe "https:// endpoint"
    (it "passes the preflight and reads /json/version over TLS"
      (with-tls-cdp-endpoint
        (fn [{:keys [https-port]}]
          (expect (true? (@#'sut/assert-cdp-endpoint-reachable!
                          (str "https://127.0.0.1:" https-port))))
          (expect (= "Chrome/141.0.0.0"
                    (:browser (@#'sut/read-cdp-json-version "127.0.0.1" https-port 2000 true))))))))

  (describe "wss:// endpoint"
    (it "passes the preflight through a TLS WebSocket handshake"
      (with-tls-cdp-endpoint
        (fn [{:keys [wss-port]}]
          (expect (sut/probe-ws-target (str "wss://127.0.0.1:" wss-port "/devtools/browser/abc") 2000))
          (expect (true? (@#'sut/assert-cdp-endpoint-reachable!
                          (str "wss://127.0.0.1:" wss-port "/devtools/browser/abc"))))))))

  (describe "plaintext http:// against a TLS port"
    (it "still fails, and the hint names the scheme that was tried"
      (with-tls-cdp-endpoint
        (fn [{:keys [https-port]}]
          (let [err (try (@#'sut/assert-cdp-endpoint-reachable! (str "http://127.0.0.1:" https-port))
                         (catch Exception e e))]
            (expect (instance? Exception err))
            (expect (str/includes? (str (:hint (ex-data err)))
                      (str "http://127.0.0.1:" https-port))))))))

  (describe "port-less CDP URLs"
    (it "derives the HTTP base from the WebSocket scheme, wss:// on 443"
      (expect (= "https://grid.example.com:443"
                (@#'sut/cdp-http-base "wss://grid.example.com/devtools/browser/abc")))
      (expect (= "http://grid.example.com:80"
                (@#'sut/cdp-http-base "ws://grid.example.com/devtools/browser/abc"))))))

;; =============================================================================
;; Unit Tests — iOS budgets and budget-interrupt reporting
;; =============================================================================

;; Regression, issue #121: only actions NAMED ios* were treated as open-ended,
;; but the iOS backend answers the ordinary `snapshot`/`click`/`type` actions —
;; so a healthy iOS snapshot was interrupted after the browser's 25s ceiling.
(defdescribe ios-command-budget-test
  "The iOS provider makes every command open-ended (issue #121)"

  (it "gives ordinary actions the long budget on an iOS session"
    (expect (= 900000 (sut/command-budget-ms "snapshot" true)))
    (expect (= 900000 (sut/command-budget-ms "click" true)))
    (expect (= 900000 (sut/command-budget-ms "type" true))))

  (it "leaves the browser budget where it was"
    (expect (< (sut/command-budget-ms "snapshot" false) 900000)))

  (it "keeps the client outside the daemon budget for iOS"
    (expect (> (sut/client-timeout-ms "snapshot" true)
              (sut/command-budget-ms "snapshot" true)))))

;; Regression, issue #122: a command interrupted for outrunning its budget
;; answered "was cancelled" with a `spel health` hint — which lists nothing for
;; a command that has already ended — and never mentioned the budget or
;; SPEL_COMMAND_BUDGET_MS, so the ceiling was invisible to the caller.
(defdescribe budget-interrupt-reporting-test
  "A budget interrupt reports the budget, whichever answer wins (issue #122)"

  (it "reports command_timeout when the interrupt was sent by the budget"
    (let [resp (json/read-json
                 (#'sut/cancelled-response "test-budget-interrupt" "snapshot"
                                           {:budget-ms 25000}))]
      (expect (= "command_timeout" (get resp "error_code")))
      (expect (str/includes? (get resp "error") "25000ms"))
      (expect (str/includes? (get resp "hint") "SPEL_COMMAND_BUDGET_MS"))))

  (it "still reports a genuine cancellation as cancelled"
    (let [resp (json/read-json
                 (#'sut/cancelled-response "test-plain-cancel" "snapshot" nil))]
      (expect (= "cancelled" (get resp "error_code"))))))

;; =============================================================================
;; Regression, issue #126: two `spel --session s` commands that started together
;; each concluded that no daemon was running and each spawned one. The second
;; daemon deleted the first's socket and PID file and bound its own socket at
;; the same path, so the first kept a browser nobody could reach while every
;; later command answered from a blank session — and all of it reported success.
;; =============================================================================

(defn- session-daemon-pids
  "PIDs of the live daemon processes started for `session`, as the shipped
   process scan sees them.

   The PID file names exactly one owner by construction, so only the process
   table can witness two processes answering to the same session name. It is
   read through `cli/orphan-daemon-processes` so this test exercises the code
   `session list` and `kill --all-sessions` run: a private copy of the scan hid
   a Linux blind spot for four releases (issue #132).

   Params:
   `session` - String session name.

   Returns:
   Vector of Long PIDs."
  [^String session]
  (#'cli/invalidate-process-scan!)
  (->> (cli/orphan-daemon-processes)
    (filter (fn [entry] (= session (:session entry))))
    (mapv (fn [entry] (Long/parseLong ^String (:pid entry))))))

(defn- kill-session-daemons!
  "Force-kills every daemon process for `session` and removes its files."
  [^String session]
  (doseq [pid (session-daemon-pids session)]
    (when-let [ph (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
      (.destroyForcibly ^java.lang.ProcessHandle ph)))
  (doseq [^java.nio.file.Path p [(sut/socket-path session) (sut/pid-file-path session)]]
    (try (Files/deleteIfExists p) (catch Exception _ nil))))

(defdescribe session-ownership-test
  "One session name is served by one daemon (issue #126)"

  (it "answers two simultaneous starts with a single daemon"
    (let [session (str "spel-ownership-" (System/currentTimeMillis))]
      (try
        (run! deref (mapv (fn [_] (future (cli/ensure-daemon! session {}))) (range 2)))
        ;; The loser exits on its own, and on a loaded machine it is still on
        ;; its way out when the winner is already serving: settle the process
        ;; table instead of snapshotting it.
        (let [deadline (+ (System/currentTimeMillis) 15000)]
          (loop []
            (when (and (> (count (session-daemon-pids session)) 1)
                    (< (System/currentTimeMillis) deadline))
              (Thread/sleep 100)
              (recur))))
        (expect (= 1 (count (session-daemon-pids session))))
        ;; The log is the second witness: only the owner may reach the socket.
        (let [text (str/join "\n" (logging/read-lines session {:lines 200}))]
          (expect (= 1 (count (re-seq #"listening on" text)))))
        (finally
          (kill-session-daemons! session)))))

  (it "takes over a session whose recorded owner is gone and says so"
    (let [session (str "spel-stale-owner-" (System/currentTimeMillis))
          err     (java.io.StringWriter.)]
      (try
        ;; Above every platform's pid_max, so the file names a process that
        ;; cannot be alive — a daemon killed with SIGKILL leaves exactly this.
        (Files/writeString (sut/pid-file-path session) "999999"
          (into-array java.nio.file.OpenOption []))
        (binding [*err* err]
          (cli/ensure-daemon! session {}))
        (expect (= 1 (count (session-daemon-pids session))))
        ;; Replacing a dead daemon in silence is what made the fresh, blank
        ;; browser look like a second one nobody asked for.
        (expect (str/includes? (str err) "999999"))
        (expect (str/includes? (str err) "fresh browser"))
        (finally
          (kill-session-daemons! session))))))

;; =============================================================================
;; Retained detail under a long-lived session (issue #125)
;; =============================================================================
;;
;; Regression, issue #125: the daemon kept the FULL detail of every response and
;; console message it had ever seen — `!network-full`, `!console-full` and
;; `!pages` only ever grew, capped by nothing a session reaches. A shadow-cljs
;; dev build streams ~1000 module files per page load, so 14 reloads of one page
;; walked the measured daemon from 150 MB to 455 MB of RSS; commands then
;; overran their budget and the page answered blank while `health` said "ok".

(defdescribe retained-detail-window-test
  "Full network, console and page detail follows the sliding window."
  (around [f] (core/with-testing-browser ((:around test-server/with-test-server) f)))

  (describe "more responses than the window holds"
    (it "retains at most one window of full entries"
      (core/with-testing-page [pg]
        (page/navigate pg (str test-server/*test-server-url* "/health"))
        (let [network-full (var-get #'sut/!network-full)
              window       (long (var-get #'sut/max-window-per-page))
              n            (+ window 200)
              _            (reset! network-full {})
              {:keys [handled]} (burst-handler-nesting pg (var-get #'sut/track-response!) n)]
          (expect (= n handled))
          (expect (<= (count @network-full) window))))))

  (describe "more console messages than the window holds"
    (it "retains at most one window of full entries"
      (core/with-testing-page [pg]
        (page/navigate pg (str test-server/*test-server-url* "/health"))
        (let [console-full    (var-get #'sut/!console-full)
              console-counter (var-get #'sut/!console-counter)
              window          (long (var-get #'sut/max-window-per-page))
              n               (+ window 200)
              before          (long @console-counter)]
          (reset! console-full {})
          (page/on-console pg (var-get #'sut/track-console-entry!))
          (page/evaluate pg
            (str "(()=>{for(let i=0;i<" n ";i++){console.log('spel-retention',i);}return 'logged';})()"))
          (let [deadline (+ (System/currentTimeMillis) 60000)]
            (while (and (< (- (long @console-counter) before) n)
                     (< (System/currentTimeMillis) (long deadline)))
              (page/wait-for-timeout pg 100)))
          (expect (= n (- (long @console-counter) before)))
          (expect (<= (count @console-full) window))))))

  (describe "more navigations than the window holds"
    (it "retains at most one window of pages"
      (let [pages  (var-get #'sut/!pages)
            window (long (var-get #'sut/max-window-per-page))
            track  (var-get #'sut/track-page-navigation!)]
        (reset! pages [])
        (dotimes [i (+ window 200)]
          (track (str "http://example.test/" i) 200 (str "page " i)))
        (expect (<= (count @pages) window))))))
;; Regression, issue #125: every command that touched the page registered ANOTHER
;; console, page-error and response listener on it. `console_start` twice left two
;; live console listeners, and a long-lived session accumulated one more copy of
;; every handler per navigation, so one arriving response ran N handlers on an
;; ever deeper stack — the "per-load state accumulation" the report suspected.
(defn- counting-page
  "A Playwright `Page` that only counts listener registrations, so a test can ask
   how many listeners spel put on one page without launching a browser.

   Params:
   `!counts` - Atom holding {:console n :page-error n :response n}.

   Returns:
   A `com.microsoft.playwright.Page` proxy; every other method is unsupported."
  [!counts]
  (proxy [com.microsoft.playwright.Page] []
    (onConsoleMessage [_] (swap! !counts update :console (fnil inc 0)))
    (onPageError [_] (swap! !counts update :page-error (fnil inc 0)))
    (onResponse [_] (swap! !counts update :response (fnil inc 0)))
    (onCrash [_] (swap! !counts update :crash (fnil inc 0)))))

(defdescribe page-instrumentation-test
  "Tests for page instrumentation — one page carries one set of spel listeners"

  (describe "console_start and errors_start on an instrumented page"
    (it "never adds a second listener for the same event"
      (let [counts   (atom {})
            page     (counting-page counts)
            !state   (var-get #'sut/!state)
            restore  @!state
            handle   (var-get #'sut/handle-cmd)]
        (try
          (reset! !state (assoc restore :page page))
          ((var-get #'sut/instrument-page!) page)
          (dotimes [_ 3]
            (handle "console_start" {})
            (handle "errors_start" {}))
          (expect (= 1 (:console @counts)))
          (expect (= 1 (:page-error @counts)))
          (finally (reset! !state restore))))))

  (describe "repeated instrumentation of one page"
    (it "registers each listener exactly once"
      (let [counts (atom {})
            page   (counting-page counts)]
        (dotimes [_ 5] ((var-get #'sut/instrument-page!) page))
        (expect (= {:console 1 :page-error 1 :response 1 :crash 1} @counts))))

    (it "instruments a second page on its own"
      (let [counts (atom {})
            first-page  (counting-page counts)
            second-page (counting-page counts)]
        ((var-get #'sut/instrument-page!) first-page)
        ((var-get #'sut/instrument-page!) second-page)
        (expect (= {:console 2 :page-error 2 :response 2 :crash 2} @counts))))))

;; =============================================================================
;; Health reports broken instrumentation
;; =============================================================================

;; Regression, issue #125: while the response handler threw on every event the
;; daemon kept answering "ok", so an agent concluded "blank page, no console
;; output" instead of "instrumentation is broken" and kept driving a dead
;; session.
(defdescribe health-handler-error-test
  "`health` after an event handler has been failing"

  (describe "a handler that has thrown"
    (it "answers degraded and names the label, the class and the count"
      (let [session (str "daemon-test-" (System/nanoTime))]
        (core/reset-handler-errors!)
        (logging/init! {:session session :component "daemon" :mirror :off})
        (try
          (let [h (core/guarded-handler "response"
                    (fn [_] (throw (StackOverflowError.))))]
            (dotimes [_ 3] (h :resp)))
          (let [data (get (json/read-json
                            (#'sut/process-command
                             (json/write-json-str {"action" "health"})))
                       "data")
                [failure] (get data "handler_errors")]
            (expect (= "degraded" (get data "status")))
            (expect (= "response" (get failure "label")))
            (expect (= 3 (get failure "count")))
            (expect (= "java.lang.StackOverflowError" (get failure "error"))))
          (finally
            (core/reset-handler-errors!)
            (logging/init! {:session "default" :component "daemon" :mirror :off})
            (try (.delete (.toFile (logging/log-file-path session)))
                 (catch Exception _ nil)))))))

  (describe "a daemon whose handlers are fine"
    (it "answers ok with an empty tally"
      (core/reset-handler-errors!)
      (let [data (get (json/read-json
                        (#'sut/process-command
                         (json/write-json-str {"action" "health"})))
                   "data")]
        (expect (= "ok" (get data "status")))
        (expect (empty? (get data "handler_errors")))))))

;; =============================================================================
;; Uptime measures this daemon
;; =============================================================================

;; Regression, issue #125: `System/currentTimeMillis` evaluated at namespace load
;; is frozen into the native image, so every daemon reported the age of the
;; BINARY. The report's "leftover daemon with about 9 days of uptime" was in
;; fact one minute old, which sent the diagnosis after state accumulated over
;; days instead of the burst in front of it.
(defdescribe daemon-uptime-test
  "`health` reports the age of this daemon, not of the binary it runs"

  (describe "before the daemon serves anything"
    (it "has no clock yet and reports no uptime"
      (let [!started (var-get #'sut/!daemon-started-at)
            restore  @!started]
        (try
          (reset! !started nil)
          (let [data (get (json/read-json
                            (#'sut/process-command
                             (json/write-json-str {"action" "health"})))
                       "data")]
            (expect (= 0 (get data "uptime_ms"))))
          (finally (reset! !started restore))))))

  (describe "once it is serving"
    (it "counts from the moment it started"
      (let [!started (var-get #'sut/!daemon-started-at)
            restore  @!started]
        (try
          (reset! !started (- (System/currentTimeMillis) 5000))
          (let [data (get (json/read-json
                            (#'sut/process-command
                             (json/write-json-str {"action" "health"})))
                       "data")]
            (expect (<= 5000 (long (get data "uptime_ms")) 20000)))
          (finally (reset! !started restore)))))))

;; Regression, issue #125: a browser on its way out answered `newPage` with an
;; anomaly MAP; the daemon stored that map as its page, and every command after
;; it — `health` included — died in 0 ms with "PersistentArrayMap cannot be cast
;; to Page". Nothing ever cleared it, so the session stayed dead until it was
;; killed by hand.
(defdescribe poisoned-page-handle-test
  "a tab that could not be created never becomes the daemon's page"

  (describe "when the browser answers with an anomaly instead of a tab"
    (it "throws, and says what failed"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            failure    (anomaly/anomaly ::anomaly/fault
                         "Target page, context or browser has been closed"
                         {})]
        (try
          (with-redefs [core/new-page-from-context (fn [_] failure)]
            (let [msg (try
                        (#'sut/new-spel-page! ::context)
                        nil
                        (catch clojure.lang.ExceptionInfo e (ex-message e)))]
              (expect (some? msg))
              (expect (str/includes? msg "could not open a browser tab"))
              (expect (str/includes? msg "Target page, context or browser has been closed"))))
          (finally (reset! state-atom before)))))

    (it "leaves no map where a Playwright page belongs"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            failure    (anomaly/anomaly ::anomaly/fault "Browser has been closed" {})]
        (try
          (with-redefs [core/new-page-from-context (fn [_] failure)]
            (try (#'sut/new-spel-page! ::context)
                 (catch clojure.lang.ExceptionInfo _ nil)))
          (expect (not-any? map? (:spel-pages @state-atom)))
          (expect (not (map? (:page @state-atom))))
          (finally (reset! state-atom before))))))

  (describe "when the browser answers with a tab"
    (it "records it as spel-owned"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            tab        (Object.)]
        (try
          (with-redefs [core/new-page-from-context (fn [_] tab)]
            (expect (identical? tab (#'sut/new-spel-page! ::context))))
          (expect (contains? (:spel-pages @state-atom) tab))
          (finally (reset! state-atom before)))))))

;; Regression, issue #125: `health` reported "connected, page open" for a browser
;; relaunched onto about:blank, so a session that answered "No page loaded" to
;; every page command still looked healthy.
(defdescribe health-page-url-test
  "`health` reports which URL the page sits on"

  (describe "with no page open"
    (it "carries the key with no url"
      (let [browser (:browser (#'sut/handle-cmd "health" {}))]
        (expect (contains? browser :page_url))
        (expect (nil? (:page_url browser)))))))

;; Regression, issue #125: a command parked inside a Playwright call ignored its
;; interrupt and kept the command lock, so every command after it answered
;; "daemon is busy" naming a command `health` no longer listed — the session was
;; dead for good while reporting itself healthy.
(defdescribe wedged-command-test
  "a command that will not die costs its browser, not the whole session"

  (describe "when a command ignores its interrupt"
    (it "answers the caller and still serves the next command"
      (let [!stop   (atom false)
            !lost   (deref #'sut/!lost-commands)
            !ledger (deref #'sut/!ledger)
            restore @!lost]
        (try
          (with-redefs [sut/command-budget-ms (fn ^long [_] 300)]
            (let [wedged (with-redefs-fn
                           {#'sut/process-command*
                            (fn [_ _]
                              (while (not @!stop)
                                (try (Thread/sleep 20)
                                     (catch InterruptedException _ nil)))
                              "{\"success\":true}")}
                           (fn [] (#'sut/run-guarded-command! "c-wedge" "reload" {})))
                  next-one (with-redefs-fn
                             {#'sut/process-command*
                              (fn [_ _] "{\"success\":true,\"data\":\"fresh\"}")}
                             (fn [] (#'sut/run-guarded-command! "c-next" "reload" {})))]
              (expect (str/includes? wedged "budget"))
              (expect (str/includes? next-one "fresh"))
              (expect (= ["reload"] (mapv :action @!lost)))))
          (finally
            (reset! !stop true)
            (reset! !lost restore)
            (swap! !ledger dissoc "c-wedge" "c-next"))))))

  (describe "once a command has been abandoned"
    (it "says so in health instead of reporting a healthy session"
      (let [!lost   (deref #'sut/!lost-commands)
            restore @!lost]
        (try
          (reset! !lost [{:id "c9" :action "reload" :at (System/currentTimeMillis)}])
          (let [resp (#'sut/handle-cmd "health" {})]
            (expect (= "degraded" (:status resp)))
            (expect (= "reload" (:action (first (:lost_commands resp))))))
          (finally (reset! !lost restore)))))))

;; Regression, issue #127: Playwright never fails a call whose renderer died.
;; Measured: kill the render process while an `evaluate` is parked and the call
;; is STILL parked 33 s later, while the crash event lands on that same thread
;; within milliseconds. The daemon therefore waited out its full 25 s budget,
;; then answered "Target crashed" to every later command forever — `open`
;; answered rc=0 in 3 ms echoing the old URL without navigating, and `health`
;; kept saying "ok — connected, page open".
(defdescribe renderer-crash-recovery-test
  "a tab whose renderer died is replaced instead of being reported as open"
  (around [f] (core/with-testing-browser (f)))

  (describe "a page killed by its own renderer crash"
    (it "stops counting as open and is swapped for a fresh tab"
      (core/with-testing-page [pg]
        (let [state-atom (deref #'sut/!state)
              before     @state-atom
              ctx        (.context ^com.microsoft.playwright.Page pg)]
          (try
            (reset! state-atom (assoc before :browser (.browser ctx) :context ctx :page pg))
            ((var-get #'sut/instrument-page!) pg)
            (page/navigate pg "chrome://crash")
            ;; Playwright dispatches events on the thread of a call in flight, so
            ;; the crash record lands on the first call that touches the dead tab.
            (page/evaluate pg "1+1")
            (expect ((var-get #'sut/page-crashed?) pg))
            (expect (not ((var-get #'sut/page-open?))))
            ;; A command that meets a crash already recorded is told what it
            ;; lost, instead of being sent into the dead tab for one more
            ;; "Target crashed".
            (let [thrown (try (#'sut/dispatch-with-recovery "evaluate" {"script" "1+1"})
                              nil
                              (catch clojure.lang.ExceptionInfo e e))]
              (expect (some? thrown))
              (expect (= :page_crashed (:error_code (ex-data thrown))))
              (expect (str/includes? (ex-message thrown) "renderer crashed")))
            (let [fresh (:page @state-atom)]
              (expect (not (identical? pg fresh)))
              (expect ((var-get #'sut/page-open?)))
              (expect (not ((var-get #'sut/page-crashed?) pg))))
            ;; And the navigation the caller retries really navigates, instead of
            ;; answering rc=0 with the URL of the tab that died.
            (let [resp (json/read-json
                         (#'sut/process-command
                          (json/write-json-str {"action" "navigate"
                                                "url"    "data:text/html,<h1>fresh</h1>"})))]
              (expect (true? (get resp "success")))
              (expect (str/includes? (str (get-in resp ["data" "url"])) "fresh")))
            (finally
              ((var-get #'sut/forget-crashed-page!) pg)
              (reset! state-atom before))))))))

(defdescribe crashed-command-test
  "the answer a caller gets when the renderer dies under their command"

  (describe "a command still running when the crash lands"
    (it "ends on the crash instead of waiting out the command budget"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            !ledger    (deref #'sut/!ledger)
            page       (Object.)
            !stop      (atom false)]
        (try
          (swap! state-atom assoc :page page)
          (doto (Thread. ^Runnable (fn []
                                     (Thread/sleep 300)
                                     ((var-get #'sut/note-page-crash!) page)))
            (.setDaemon true)
            (.start))
          (let [started (System/currentTimeMillis)
                resp    (with-redefs-fn
                          {#'sut/process-command*
                           (fn [_ _]
                             (while (not @!stop)
                               (try (Thread/sleep 20)
                                    (catch InterruptedException _ (reset! !stop true))))
                             "{\"success\":true}")}
                          (fn [] (#'sut/run-guarded-command! "c-crash" "evaluate" {})))
                elapsed (- (System/currentTimeMillis) started)
                data    (json/read-json resp)]
            (expect (= "page_crashed" (get data "error_code")))
            (expect (str/includes? (get data "error") "renderer crashed"))
            ;; The budget is 25 s; ending on the crash costs one watch slice.
            (expect (< elapsed 5000)))
          (finally
            (reset! !stop true)
            ((var-get #'sut/forget-crashed-page!) page)
            (reset! state-atom before)
            (swap! !ledger dissoc "c-crash"))))))

  (describe "the worker's own interrupt answer"
    (it "reports the crash and the page it happened on, not a phantom cancellation"
      (let [data (json/read-json
                   (#'sut/cancelled-response "c-x" "snapshot"
                                             {:page-crashed true :url "https://example.org/app"}))]
        (expect (= "page_crashed" (get data "error_code")))
        (expect (str/includes? (get data "error") "renderer crashed"))
        (expect (str/includes? (get data "error") "https://example.org/app"))))

    ;; Regression, issue #127: `ledger-finish!` removes the entry before the last
    ;; caller runs, so a cancelled answer that re-read the reason from the ledger
    ;; found nothing and told the user their command "was cancelled" — 90 ms after
    ;; the daemon had logged the renderer crash that ended it.
    (it "survives the ledger entry being finished first"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            page       (Object.)
            !stop      (atom false)]
        (try
          (swap! state-atom assoc :page page)
          (doto (Thread. ^Runnable (fn []
                                     (Thread/sleep 300)
                                     ((var-get #'sut/note-page-crash!) page)))
            (.setDaemon true)
            (.start))
          (let [data (with-redefs-fn
                       {#'sut/process-command*
                        (fn [_ _]
                          (while (not @!stop)
                            (try (Thread/sleep 20)
                                 (catch InterruptedException _ (reset! !stop true))))
                          "{\"success\":false,\"error\":\"Failed to read message\"}")}
                       (fn [] (json/read-json
                                (#'sut/process-command
                                 (json/write-json-str {"action" "evaluate" "script" "1+1"})))))]
            (expect (= "page_crashed" (get data "error_code"))))
          (finally
            (reset! !stop true)
            ((var-get #'sut/forget-crashed-page!) page)
            (reset! state-atom before))))))

  (describe "health while the tab is dead"
    (it "answers degraded and calls the page crashed instead of open"
      (let [state-atom (deref #'sut/!state)
            before     @state-atom
            page       (Object.)]
        (try
          (swap! state-atom assoc :page page)
          ((var-get #'sut/note-page-crash!) page)
          (let [resp (#'sut/handle-cmd "health" {})]
            (expect (= "degraded" (:status resp)))
            (expect (true? (get-in resp [:browser :page_crashed])))
            (expect (false? (get-in resp [:browser :page_open]))))
          (finally
            ((var-get #'sut/forget-crashed-page!) page)
            (reset! state-atom before)))))))

;; =============================================================================
;; iOS ref freshness — what a gesture is allowed to cost
;; =============================================================================

;; Regression, issue #128: every iOS gesture captured a full snapshot nobody
;; asked for — 62 s per capture on a webview app, 7 of 15 captures in one
;; session — and the refs it published were only ever read by a later @ref.
(defdescribe ios-ref-freshness-test
  "iOS refs are recaptured where an @ref is used, not after every action"

  (it "uses a ref map that no command has invalidated"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {"e1" {:selector "xpath=/x[1]"}} :counter 1
                          :refs-stale? false})
      (with-redefs [backend/capture-snapshot! (fn [_ _] (swap! captures* inc)
                                                {:refs {} :counter 0})]
        (#'sut/ios-check-ref! :backend "@e1"))
      (expect (zero? @captures*))))

  (it "recaptures once when an earlier command may have repainted the screen"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {"e1" {:selector "xpath=/old[1]"}} :counter 1
                          :refs-stale? true})
      (with-redefs [backend/capture-snapshot!
                    (fn [_ _] (swap! captures* inc)
                      {:refs {"e1" {:selector "xpath=/new[1]"}} :counter 1})]
        (#'sut/ios-check-ref! :backend "@e1")
        (#'sut/ios-check-ref! :backend "@e1"))
      (expect (= 1 @captures*))
      (expect (= "xpath=/new[1]" (get-in @state-atom [:refs "e1" :selector])))
      (expect (false? (:refs-stale? @state-atom)))))

  (it "reports a ref the recapture cannot find as stale"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {} :counter 0 :refs-stale? true})
      (with-redefs [backend/capture-snapshot! (fn [_ _] {:refs {"e9" {}} :counter 1})]
        (let [error (try
                      (#'sut/ios-check-ref! :backend "@e1")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (expect (some? error))
          (expect (true? (:stale-ref (ex-data error))))))))

  (it "invalidates refs after a command that can repaint, but not after a read"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {"e1" {}} :counter 1 :refs-stale? false})
      (with-redefs [sut/ios-provider? (constantly true)
                    sut/handle-ios-cmd (fn [_ _] {:ok true})]
        (#'sut/dispatch-cmd "scroll" {})
        (expect (true? (:refs-stale? @state-atom)))
        (swap! state-atom assoc :refs-stale? false)
        (#'sut/dispatch-cmd "get_text" {})
        (expect (false? (:refs-stale? @state-atom))))))

  (it "invalidates refs even when the command fails"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {"e1" {}} :counter 1 :refs-stale? false})
      (with-redefs [sut/ios-provider? (constantly true)
                    sut/handle-ios-cmd (fn [_ _] (throw (ex-info "tap failed" {})))]
        (try (#'sut/dispatch-cmd "tap" {}) (catch Exception _ nil)))
      (expect (true? (:refs-stale? @state-atom)))))

  (it "resolves an @ref for a command whose handler never checked one"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {"e1" {:selector "xpath=/old[1]"}} :counter 1
                          :refs-stale? true})
      (with-redefs [sut/ios-provider? (constantly true)
                    sut/ios-backend (constantly :backend)
                    backend/capture-snapshot! (fn [_ _] (swap! captures* inc)
                                                {:refs {"e1" {:selector "xpath=/new[1]"}}
                                                 :counter 1})
                    sut/handle-ios-cmd (fn [_ _] {:text "hello"})]
        (#'sut/dispatch-cmd "get_text" {"selector" "@e1"}))
      (expect (= 1 @captures*))))

  (it "leaves a plain selector alone"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {} :counter 0 :refs-stale? true})
      (with-redefs [sut/ios-provider? (constantly true)
                    sut/ios-backend (constantly :backend)
                    backend/capture-snapshot! (fn [_ _] (swap! captures* inc) {:refs {} :counter 0})
                    sut/handle-ios-cmd (fn [_ _] {:tapped "name=Send"})]
        (#'sut/dispatch-cmd "tap" {"selector" "name=Send"}))
      (expect (zero? @captures*)))))

;; Regression, issue #130: every browser action captured a full snapshot whose
;; tree was thrown away — 90% of a click on a 12k-element page — while evaluate
;; rewrote the document and left the refs looking fresh.
(defdescribe web-ref-freshness-test
  "Browser refs are captured where an @ref is used, not after every action"

  (it "matches a stamp that outlived the action instead of walking again"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {"e1" {:role "button"}} :counter 1 :refs-stale? true})
      (with-redefs [snapshot/resolve-ref       (fn [_ _] :loc)
                    locator/count-elements     (fn ^long [_] 1)
                    snapshot/capture-snapshot  (fn [& _] (swap! captures* inc) {:refs {} :counter 0})]
        (with-redefs-fn {#'sut/pg (constantly :page)}
          (fn [] (expect (= :loc (#'sut/resolve-selector "@e1"))))))
      (expect (zero? @captures*))))

  (it "walks once when the stamp is gone"
    (let [state-atom (deref #'sut/!state)
          captures*  (atom 0)]
      (reset! state-atom {:refs {"e1" {:role "button"}} :counter 1 :refs-stale? true})
      (with-redefs [snapshot/resolve-ref      (fn [_ _] :loc)
                    locator/count-elements    (fn ^long [_] 0)
                    snapshot/capture-snapshot (fn [& _] (swap! captures* inc)
                                                {:refs {"e1" {:role "button"}} :counter 1})]
        (with-redefs-fn {#'sut/pg (constantly :page)}
          (fn [] (expect (= :loc (#'sut/resolve-selector "@e1"))))))
      (expect (= 1 @captures*))
      (expect (false? (:refs-stale? @state-atom)))))

  (it "reports a ref no capture can find as stale, and lists what is addressable"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {} :counter 0 :refs-stale? true})
      (with-redefs [snapshot/resolve-ref      (fn [_ _] :loc)
                    locator/count-elements    (fn ^long [_] 0)
                    snapshot/capture-snapshot (fn [& _] {:refs {"e9" {:role "button" :name "Submit"}}
                                                         :counter 1})]
        (with-redefs-fn {#'sut/pg (constantly :page)}
          (fn []
            (let [error (try (#'sut/resolve-selector "@e1")
                             nil
                             (catch clojure.lang.ExceptionInfo e e))]
              (expect (some? error))
              (expect (true? (:stale-ref (ex-data error))))
              (expect (str/includes? (ex-message error) "@e9"))))))))

  (it "invalidates refs after a command that published none"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {"e1" {}} :counter 1 :refs-stale? false :refs-generation 3})
      (with-redefs-fn {#'sut/ios-provider? (constantly false)
                       #'sut/handle-cmd    (fn [_ _] {:ok true})}
        (fn [] (#'sut/dispatch-cmd "evaluate" {})))
      (expect (true? (:refs-stale? @state-atom)))))

  (it "leaves the refs a command published alone"
    (let [state-atom (deref #'sut/!state)]
      (reset! state-atom {:refs {} :counter 0 :refs-stale? true :refs-generation 3})
      (with-redefs [snapshot/capture-snapshot (fn [& _] {:refs {"e2" {:role "link"}} :counter 1})]
        (with-redefs-fn {#'sut/pg            (constantly :page)
                         #'sut/ios-provider? (constantly false)
                         #'sut/handle-cmd    (fn [_ _] (#'sut/refresh-snapshot!) {:ok true})}
          (fn [] (#'sut/dispatch-cmd "snapshot" {}))))
      (expect (false? (:refs-stale? @state-atom)))
      (expect (= {"e2" {:role "link"}} (:refs @state-atom))))))

;; Regression, issue #131: a failed navigation was reported as a successful one.
;; `handle-cmd "navigate"` discarded the anomaly `page/navigate` returns, so
;; `spel open` answered rc=0 with the URL of the tab it never left — a blocked,
;; refused or unresolvable address looked exactly like a page that loaded.
(defdescribe navigation-failure-test
  "a navigation that never arrived is reported as a failure"
  (around [f] (core/with-testing-browser (f)))

  (describe "a URL the browser cannot reach"
    (it "answers an error instead of the URL of the tab that stayed put"
      (core/with-testing-page [pg]
        (let [state-atom (deref #'sut/!state)
              before     @state-atom
              ctx        (.context ^com.microsoft.playwright.Page pg)]
          (try
            (reset! state-atom (assoc before :browser (.browser ctx) :context ctx :page pg))
            (let [resp (json/read-json
                         (#'sut/process-command
                          (json/write-json-str {"action" "navigate"
                                                "url"    "http://127.0.0.1:1/"})))]
              (expect (false? (get resp "success")))
              (expect (str/includes? (str (get resp "error")) "127.0.0.1:1")))
            (finally
              (reset! state-atom before))))))))
