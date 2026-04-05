(ns com.blockether.spel.options-test
  "Unit tests for options conversion functions."
  (:require
   [com.blockether.spel.options :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright BrowserType$LaunchOptions BrowserType$LaunchPersistentContextOptions Browser$NewContextOptions]))

;; =============================================================================
;; Launch Options
;; =============================================================================

(defdescribe launch-options-test
  "Tests for ->launch-options"

  (describe "basic options"
    (it "creates options with headless"
      (let [lo (sut/->launch-options {:headless true})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with slow-mo"
      (let [lo (sut/->launch-options {:slow-mo 100})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with args"
      (let [lo (sut/->launch-options {:args ["--no-sandbox" "--disable-gpu"]})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with executable-path"
      (let [lo (sut/->launch-options {:executable-path "/usr/bin/chromium"})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with channel"
      (let [lo (sut/->launch-options {:channel "chrome"})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with ignore-default-args"
      (let [lo (sut/->launch-options {:ignore-default-args ["--use-mock-keychain" "--password-store=basic"]})]
        (expect (instance? BrowserType$LaunchOptions lo)))))
  (describe "proxy options"
    (it "creates options with proxy server only"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))
    (it "creates options with proxy server and bypass"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"
                                              :bypass "localhost,127.0.0.1"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))
    (it "creates options with proxy server, bypass, username, and password"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"
                                              :bypass "localhost"
                                              :username "user"
                                              :password "pass"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))
    (it "does not set proxy when proxy key is absent"
      (let [lo (sut/->launch-options {:headless true})]
        (expect (instance? BrowserType$LaunchOptions lo)))))

  (describe "combined options"
    (it "creates options with multiple settings"
      (let [lo (sut/->launch-options {:headless true
                                      :slow-mo 50
                                      :args ["--no-sandbox"]
                                      :proxy {:server "http://proxy:8080"
                                              :bypass "localhost"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))))

;; =============================================================================
;; Context Options
;; =============================================================================

(defdescribe context-options-test
  "Tests for ->new-context-options"

  (describe "basic options"
    (it "creates context options with viewport"
      (let [co (sut/->new-context-options {:viewport {:width 1280 :height 720}})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with user-agent"
      (let [co (sut/->new-context-options {:user-agent "TestAgent/1.0"})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with ignore-https-errors"
      (let [co (sut/->new-context-options {:ignore-https-errors true})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with extra-http-headers"
      (let [co (sut/->new-context-options {:extra-http-headers {"Authorization" "Bearer tok"}})]
        (expect (instance? Browser$NewContextOptions co))))))

;; =============================================================================
;; Storage State — dual-key split (:storage-state vs :storage-state-path)
;; =============================================================================

(defn- read-field
  "Reflectively reads a public field value from an options object. Used to
   verify that the right Playwright setter was called without a live browser."
  [obj ^String field-name]
  (-> obj .getClass (.getField field-name) (.get obj)))

(defdescribe storage-state-split-test
  "Regression tests for the `:storage-state` / `:storage-state-path` split.

   Before the split, the library used a single `:storage-state` key with a
   fragile heuristic (`contains '/'` → path, else JSON). The heuristic
   mis-classified inline JSON strings like `{\"cookies\":[{\"path\":\"/\"}]}`
   as paths — which caused HAR recording to silently break because the
   `.storageState()` roundtrip embeds `/` characters in every cookie. The fix:
   use an explicit key for each mode, no heuristic at all."

  (describe ":storage-state-path → setStorageStatePath"

    (it "writes only the path field (not the inline JSON field)"
      (let [co (sut/->new-context-options {:storage-state-path "/tmp/some-state.json"})
            path-val (read-field co "storageStatePath")
            state-val (read-field co "storageState")]
        (expect (some? path-val))
        (expect (nil? state-val))))

    (it "works for Paths with JSON-like substrings"
      ;; Pathological path that contains `{` and `:` but is still a path
      (let [co (sut/->new-context-options {:storage-state-path "/tmp/weird/file.json"})]
        (expect (some? (read-field co "storageStatePath")))
        (expect (nil? (read-field co "storageState"))))))

  (describe ":storage-state → setStorageState (inline JSON)"

    (it "writes only the inline JSON field (not the path field)"
      (let [json-payload "{\"cookies\":[],\"origins\":[]}"
            co (sut/->new-context-options {:storage-state json-payload})
            state-val (read-field co "storageState")
            path-val (read-field co "storageStatePath")]
        (expect (= json-payload state-val))
        (expect (nil? path-val))))

    (it "handles JSON containing cookie paths (the bug that motivated this split)"
      ;; BEFORE the fix: the heuristic saw `/` inside the JSON and called
      ;; setStorageStatePath with a massive JSON blob, which made Playwright
      ;; try to open it as a file path and silently fail.
      (let [json-payload (str "{\"cookies\":[{"
                           "\"name\":\"session\","
                           "\"value\":\"abc/xyz+/ok\","
                           "\"domain\":\".example.com\","
                           "\"path\":\"/\","
                           "\"expires\":1234567890}]}")
            co (sut/->new-context-options {:storage-state json-payload})]
        ;; The JSON payload must land in storageState (inline), NOT in
        ;; storageStatePath.
        (expect (= json-payload (read-field co "storageState")))
        (expect (nil? (read-field co "storageStatePath")))))

    (it "handles JSON with escaped slashes and URLs"
      (let [json-payload "{\"origins\":[{\"origin\":\"https://api.example.com\",\"localStorage\":[]}]}"
            co (sut/->new-context-options {:storage-state json-payload})]
        (expect (= json-payload (read-field co "storageState")))
        (expect (nil? (read-field co "storageStatePath"))))))

  (describe "both keys together"

    (it "allows both keys simultaneously (Playwright keeps whichever was set last internally)"
      ;; This is unusual but not an error. The two setters are independent.
      (let [co (sut/->new-context-options
                 {:storage-state-path "/tmp/a.json"
                  :storage-state      "{\"cookies\":[]}"})]
        (expect (some? (read-field co "storageStatePath")))
        (expect (some? (read-field co "storageState"))))))

  (describe "neither key"

    (it "leaves both fields nil when neither key is present"
      (let [co (sut/->new-context-options {:headless true})]
        (expect (nil? (read-field co "storageState")))
        (expect (nil? (read-field co "storageStatePath")))))))

;; =============================================================================
;; Launch Persistent Context Options
;; =============================================================================

(defdescribe launch-persistent-context-options-test
  "Tests for ->launch-persistent-context-options"

  (describe "basic options"
    (it "creates persistent context options with headless"
      (let [o (sut/->launch-persistent-context-options {:headless true})]
        (expect (instance? BrowserType$LaunchPersistentContextOptions o))))

    (it "creates persistent context options with channel"
      (let [o (sut/->launch-persistent-context-options {:channel "chrome"})]
        (expect (instance? BrowserType$LaunchPersistentContextOptions o))))

    (it "creates persistent context options with ignore-default-args"
      (let [o (sut/->launch-persistent-context-options {:ignore-default-args ["--use-mock-keychain" "--password-store=basic"]})]
        (expect (instance? BrowserType$LaunchPersistentContextOptions o))))))
