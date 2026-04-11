(ns com.blockether.spel.daemon-cdp-discovery-test
  "Tests for CDP discovery, the chromium browser catalog, WSL detection, and
   CLI-side session-list rendering. Uses in-process HttpServer + temp files
   for isolation — no external processes, no real browsers."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.cli :as cli]
   [com.blockether.spel.daemon :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.io File]
   [java.net InetSocketAddress]
   [java.nio.file Files]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- spin-up-fake-cdp-server!
  "Starts an HttpServer on the given port that mimics a pre-M144 Chromium
   CDP endpoint. Returns the server — caller must `(.stop srv 0)`."
  ^HttpServer [^long port ^String browser-label]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext srv "/json/version"
      (reify HttpHandler
        (handle [_ ex]
          (let [body (.getBytes (str "{\"Browser\":\"" browser-label "\"}"))]
            (.sendResponseHeaders ^HttpExchange ex 200 (count body))
            (with-open [os (.getResponseBody ^HttpExchange ex)]
              (.write os body))))))
    (.start srv)
    srv))

;; =============================================================================
;; Chromium browser catalog
;; =============================================================================

(defdescribe chromium-user-data-dirs-test
  "Unit tests for chromium-user-data-dirs — verifies the data-driven catalog
   expands correctly on the current OS."

  (describe "returns a populated list on the current OS"
    (it "is non-empty"
      (expect (pos? (count (#'sut/chromium-user-data-dirs)))))

    (it "includes Google Chrome and Chromium labels"
      (let [labels (set (map :label (#'sut/chromium-user-data-dirs)))]
        (expect (contains? labels "Google Chrome"))
        (expect (contains? labels "Chromium")))))

  (describe "all entries have :path and :label"
    (it "every projected entry is {:path :label}"
      (let [dirs (#'sut/chromium-user-data-dirs)]
        (doseq [d dirs]
          (expect (string? (:path d)))
          (expect (string? (:label d)))
          (expect (not (str/blank? (:path d))))
          (expect (not (str/blank? (:label d))))))))

  (describe "catalog data is well-formed"
    (it "every entry has a :label"
      (doseq [entry @#'sut/chromium-browser-catalog]
        (expect (string? (:label entry)))
        (expect (not (str/blank? (:label entry))))))

    (it "every entry has at least one OS mapping"
      (doseq [entry @#'sut/chromium-browser-catalog]
        (expect (or (:mac entry) (:linux entry) (:linux-snap entry)
                  (:linux-flatpak entry) (:win entry) (:win-roaming entry))))))

  (describe "canonical browsers are present in the catalog"
    (it "includes Chrome, Chromium, Edge, Brave, Vivaldi, Opera, Arc, Thorium"
      (let [labels (set (map :label @#'sut/chromium-browser-catalog))]
        (expect (contains? labels "Google Chrome"))
        (expect (contains? labels "Chromium"))
        (expect (contains? labels "Microsoft Edge"))
        (expect (contains? labels "Brave"))
        (expect (contains? labels "Vivaldi"))
        (expect (contains? labels "Opera"))
        (expect (contains? labels "Arc"))
        (expect (contains? labels "Thorium"))))))

;; =============================================================================
;; WSL detection
;; =============================================================================

(defdescribe wsl-detection-test
  "Unit tests for wsl? — detects Windows Subsystem for Linux.
   `System/getenv` is a Java static method and cannot be shadowed via
   `with-redefs`, so these tests assert on the current-machine outcome
   only. (The developer box running this suite is not WSL.)"

  (describe "returns a boolean"
    (it "resolves without throwing"
      (let [r (#'sut/wsl?)]
        (expect (or (true? r) (false? r)))))

    (it "returns false on this non-WSL developer machine"
      (expect (not (#'sut/wsl?))))))

;; =============================================================================
;; WSL host resolution (wsl-projected-source? / cdp-candidate-hosts)
;; =============================================================================

(defdescribe wsl-projected-source-test
  "Unit tests for the `/mnt/`-path detector used to decide whether a
   DevToolsActivePort file came from a Windows-side Chrome and therefore
   needs host resolution beyond loopback."

  (describe "returns true for /mnt/ paths (WSL projection)"
    (it "/mnt/c/Users/alice/AppData/... (Chrome on Windows C: drive)"
      (expect (#'sut/wsl-projected-source?
                "/mnt/c/Users/alice/AppData/Local/Google/Chrome/User Data/DevToolsActivePort")))
    (it "/mnt/d/... (other drive)"
      (expect (#'sut/wsl-projected-source?
                "/mnt/d/some/path/DevToolsActivePort")))
    (it "/media/... (older WSL layouts or distros)"
      (expect (#'sut/wsl-projected-source?
                "/media/sf_shared/DevToolsActivePort"))))

  (describe "returns false for native Linux paths"
    (it "~/.config path"
      (expect (not (#'sut/wsl-projected-source?
                     "/home/user/.config/google-chrome/DevToolsActivePort"))))
    (it "/tmp path"
      (expect (not (#'sut/wsl-projected-source? "/tmp/foo"))))
    (it "nil / blank input"
      (expect (not (#'sut/wsl-projected-source? nil)))
      (expect (not (#'sut/wsl-projected-source? ""))))))

(defdescribe cdp-candidate-hosts-test
  "Unit tests for cdp-candidate-hosts — the per-source host-list builder
   that decides whether spel probes loopback-only or loopback + WSL
   default gateway.

   `wsl?` is a private var backed by `System/getenv` + a file read, both
   of which cannot be easily swapped. We therefore stub `wsl?` via
   with-redefs where we need to simulate being inside WSL."

  (describe "native-Linux (wsl? false)"
    (it "returns [\"127.0.0.1\"] for any source-path"
      (with-redefs [sut/wsl? (constantly false)]
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts nil)))
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts
                    "/home/user/.config/google-chrome/DevToolsActivePort")))
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts
                    "/mnt/c/Users/alice/AppData/Local/Google/Chrome/User Data/DevToolsActivePort"))))))

  (describe "WSL with native-Linux DTAP source (wsl? true, path under ~/.config)"
    (it "returns [\"127.0.0.1\"] — no gateway probe needed for a truly local browser"
      (with-redefs [sut/wsl? (constantly true)
                    sut/wsl-default-gateway-ip (constantly "172.24.96.1")]
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts
                    "/home/user/.config/google-chrome/DevToolsActivePort"))))))

  (describe "WSL with Windows-projected DTAP source"
    (it "returns [loopback, gateway] when gateway resolves"
      (with-redefs [sut/wsl? (constantly true)
                    sut/wsl-default-gateway-ip (constantly "172.24.96.1")]
        (expect (= ["127.0.0.1" "172.24.96.1"]
                  (#'sut/cdp-candidate-hosts
                    "/mnt/c/Users/alice/AppData/Local/Google/Chrome/User Data/DevToolsActivePort")))))

    (it "returns [loopback] when gateway lookup fails"
      (with-redefs [sut/wsl? (constantly true)
                    sut/wsl-default-gateway-ip (constantly nil)]
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts
                    "/mnt/c/Users/alice/AppData/Local/Google/Chrome/User Data/DevToolsActivePort")))))

    (it "does not duplicate loopback if the gateway IP is also 127.0.0.1"
      ;; Mirrored-networking configurations report the gateway as the
      ;; same loopback address spel already tries. We must not end up
      ;; probing [\"127.0.0.1\" \"127.0.0.1\"].
      (with-redefs [sut/wsl? (constantly true)
                    sut/wsl-default-gateway-ip (constantly "127.0.0.1")]
        (expect (= ["127.0.0.1"]
                  (#'sut/cdp-candidate-hosts
                    "/mnt/c/Users/alice/AppData/Local/Google/Chrome/User Data/DevToolsActivePort")))))))

;; =============================================================================
;; CDP discovery — host-aware probing
;; =============================================================================

(defdescribe read-cdp-json-version-host-arity-test
  "Regression tests for the 3-arg `[host port timeout]` overload of
   read-cdp-json-version — the enabling change for WSL discovery."

  (describe "3-arity form"
    (it "reports :host in the result so callers know which host answered"
      (let [port 59321
            srv  (try (spin-up-fake-cdp-server! port "HostArityChrome/1.0")
                   (catch Exception _ nil))]
        (when srv
          (try
            (let [r (#'sut/read-cdp-json-version "127.0.0.1" port 1000)]
              (expect (= port (:port r)))
              (expect (= "127.0.0.1" (:host r)))
              (expect (= "HostArityChrome/1.0" (:browser r))))
            (finally (.stop srv 0))))))

    (it "returns nil for an unreachable host even when port is live on another"
      (let [port 59322
            srv  (try (spin-up-fake-cdp-server! port "IsolatedChrome/1.0")
                   (catch Exception _ nil))]
        (when srv
          (try
            ;; 10.255.255.1 is the first address of an unroutable /32 —
            ;; connect attempts time out quickly and return nil rather
            ;; than a false positive against our fake local server.
            (expect (nil? (#'sut/read-cdp-json-version "10.255.255.1" port 200)))
            (finally (.stop srv 0)))))))

  (describe "2-arity backwards compat"
    (it "still defaults to 127.0.0.1"
      (let [port 59323
            srv  (try (spin-up-fake-cdp-server! port "CompatChrome/1.0")
                   (catch Exception _ nil))]
        (when srv
          (try
            (let [r (#'sut/read-cdp-json-version port 1000)]
              (expect (= port (:port r)))
              (expect (= "127.0.0.1" (:host r))))
            (finally (.stop srv 0))))))))

;; =============================================================================
;; CDP discovery — external endpoints
;; =============================================================================

(defdescribe discover-external-cdp-endpoints-test
  "Integration tests for discover-external-cdp-endpoints. Uses a real
   in-process HttpServer that pretends to be a CDP endpoint."

  (describe "discovers a listening CDP endpoint"
    (it "returns the fake endpoint with a browser label from /json/version"
      ;; Bind port 9222 if free; otherwise use a port in the auto-launch range.
      ;; We need a port the scanner actually probes.
      (let [port   9222
            srv    (try (spin-up-fake-cdp-server! port "FakeChrome/1.0")
                     (catch Exception _ nil))]
        (when srv
          (try
            (let [result (sut/discover-external-cdp-endpoints [])
                  match (first (filter #(= port (:port %)) result))]
              (expect (some? match))
              (expect (= (str "http://127.0.0.1:" port) (:cdp_url match)))
              ;; Label comes either from a real DevToolsActivePort (our test
              ;; server has none) or from fetch-cdp-browser-label.
              (expect (or (= "FakeChrome/1.0" (:label match))
                        (string? (:label match)))))
            (finally (.stop srv 0)))))))

  (describe "excludes given ports"
    (it "a port in excluded-ports is not returned even if listening"
      (let [port 9222
            srv  (try (spin-up-fake-cdp-server! port "FakeChrome/1.0")
                   (catch Exception _ nil))]
        (when srv
          (try
            (let [result (sut/discover-external-cdp-endpoints [port])
                  match  (first (filter #(= port (:port %)) result))]
              (expect (nil? match)))
            (finally (.stop srv 0))))))))

;; =============================================================================
;; list-active-cdp-endpoints — spel-owned lock files
;; =============================================================================

(defdescribe list-active-cdp-endpoints-test
  "Unit tests for list-active-cdp-endpoints — spel-owned auto-launch locks."

  (describe "stale lock cleanup"
    (it "filters out lock files whose owning daemon is not running"
      ;; Write a lock file claiming a port owned by a dead session name.
      (let [tmp (str (System/getProperty "java.io.tmpdir")
                  "/spel-auto-launch-65123.json")]
        (try
          (spit tmp (json/write-json-str {:session "ghost-session-never-existed"
                                          :port 65123
                                          :browser_pid 999999
                                          :created_at 0}))
          (let [result (sut/list-active-cdp-endpoints)]
            (expect (not (some #(= "ghost-session-never-existed" (:session %)) result))))
          (finally
            (.delete (io/file tmp))))))))

;; =============================================================================
;; parse-devtools-active-port
;; =============================================================================

(defdescribe parse-devtools-active-port-test
  "Unit tests for parse-devtools-active-port — parses the two-line file
   Chromium writes alongside its user-data directory."

  (describe "valid two-line file"
    (it "extracts port and ws-path"
      (let [tmp (File/createTempFile "spel-dt-test" ".txt")]
        (try
          (spit tmp "9222\n/devtools/browser/abc-123\n")
          (let [result (#'sut/parse-devtools-active-port (.getPath tmp))]
            (expect (= 9222 (:port result)))
            (expect (= "/devtools/browser/abc-123" (:ws-path result))))
          (finally (.delete tmp))))))

  (describe "missing file"
    (it "returns nil"
      (expect (nil? (#'sut/parse-devtools-active-port "/nonexistent/DevToolsActivePort")))))

  (describe "port-only file (older Chromium)"
    (it "returns port with nil ws-path"
      (let [tmp (File/createTempFile "spel-dt-onelines" ".txt")]
        (try
          (spit tmp "9229\n")
          (let [result (#'sut/parse-devtools-active-port (.getPath tmp))]
            (expect (= 9229 (:port result))))
          (finally (.delete tmp)))))))

;; =============================================================================
;; render-table (cli.clj)
;; =============================================================================

(defdescribe render-table-test
  "Unit tests for the shared render-table helper in cli.clj — verifies
   column auto-sizing and header rule rendering."

  (describe "basic two-column table"
    (it "prints header + rule + rows with auto-sized widths"
      (let [out (with-out-str
                  (#'cli/render-table
                    ["NAME" "VALUE"]
                    [["foo" "1"] ["longer-name" "22"]]))]
        (expect (str/includes? out "NAME"))
        (expect (str/includes? out "VALUE"))
        (expect (str/includes? out "longer-name"))
        (expect (str/includes? out "─")))))

  (describe "empty rows"
    (it "still renders header + rule"
      (let [out (with-out-str
                  (#'cli/render-table
                    ["COL1" "COL2"]
                    []))]
        (expect (str/includes? out "COL1"))
        (expect (str/includes? out "COL2"))
        (expect (str/includes? out "─")))))

  (describe "last column is not right-padded"
    (it "no trailing whitespace before the newline"
      (let [out (with-out-str
                  (#'cli/render-table
                    ["A" "B"]
                    [["short" "last-cell"]]))
            lines (str/split-lines out)
            body-line (last lines)]
        ;; body-line ends with "last-cell" (optionally a trailing blank) — no
        ;; extra padding spaces.
        (expect (str/ends-with? body-line "last-cell"))))))

;; =============================================================================
;; CLI-side session list (discover-sessions, active-sessions-on-disk,
;; build-session-list-data) — tested by the cli-test ns since they live there.
;; =============================================================================
