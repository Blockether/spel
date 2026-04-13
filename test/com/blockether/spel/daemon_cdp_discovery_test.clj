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
   [com.blockether.spel.platform :as platform]
   [com.blockether.spel.sci-env :as sci-env]
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
;; platform.clj — probe-cdp, cdp-candidate-hosts, discover-cdp
;; =============================================================================

(defdescribe platform-probe-cdp-test
  "Unit tests for platform/probe-cdp — the shared CDP liveness check."

  (describe "returns nil for closed ports"
    (it "returns nil for a port nothing listens on"
      (expect (nil? (platform/probe-cdp "127.0.0.1" 19999 500)))))

  (describe "returns a map for a live CDP endpoint"
    (it "extracts :host :port :browser :ws-url from /json/version"
      (let [port 59330
            srv  (try (spin-up-fake-cdp-server! port "ProbeCDP/1.0")
                      (catch Exception _ nil))]
        (when srv
          (try
            (let [r (platform/probe-cdp "127.0.0.1" port 1000)]
              (expect (some? r))
              (expect (= "127.0.0.1" (:host r)))
              (expect (= port (:port r)))
              (expect (= "ProbeCDP/1.0" (:browser r))))
            (finally (.stop srv 0)))))))

  (describe "returns nil for non-CDP HTTP servers"
    (it "returns nil when /json/version returns non-JSON"
      (let [port 59331
            srv  (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
        (.createContext srv "/json/version"
          (reify HttpHandler
            (handle [_ ex]
              (let [body (.getBytes "not json")]
                (.sendResponseHeaders ^HttpExchange ex 200 (count body))
                (with-open [os (.getResponseBody ^HttpExchange ex)]
                  (.write os body))))))
        (.start srv)
        (try
          (expect (nil? (platform/probe-cdp "127.0.0.1" port 1000)))
          (finally (.stop srv 0)))))))

(defdescribe platform-cdp-candidate-hosts-test
  "Unit tests for platform/cdp-candidate-hosts."

  (describe "on non-WSL machines"
    (it "returns [\"127.0.0.1\"]"
      (let [hosts (platform/cdp-candidate-hosts)]
        ;; On macOS dev machines (not WSL), should be loopback only
        (expect (contains? (set hosts) "127.0.0.1"))))))

(defdescribe platform-discover-cdp-test
  "Integration tests for platform/discover-cdp."

  (describe "discovers a fake CDP server"
    (it "finds a server on a probed port"
      (let [port 9222
            srv  (try (spin-up-fake-cdp-server! port "DiscoverTest/1.0")
                      (catch Exception _ nil))]
        (when srv
          (try
            (let [r (platform/discover-cdp [port] 1000)]
              (expect (some? r))
              (expect (= port (:port r)))
              (expect (str/includes? (:browser r) "DiscoverTest")))
            (finally (.stop srv 0)))))))

  (describe "returns nil when nothing is listening"
    (it "returns nil for unused ports"
      (expect (nil? (platform/discover-cdp [19998 19999] 300))))))

;; =============================================================================
;; SCI eval — WSL, java->clj, cdp-connect, stdlib
;; =============================================================================

(defdescribe sci-eval-new-features-test
  "Tests for newly exposed SCI functions: WSL detection, java->clj,
   clojure.set, clojure.walk, zprint, ns-aliases, and cdp-connect."

  (describe "WSL functions in SCI"
    (it "spel/wsl? returns a boolean"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(spel/wsl?)")]
        (expect (or (true? r) (false? r)))))

    (it "spel/wsl-default-gateway-ip returns nil or string"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(spel/wsl-default-gateway-ip)")]
        (expect (or (nil? r) (string? r))))))

  (describe "java->clj in SCI"
    (it "converts LinkedHashMap to Clojure map"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(spel/java->clj (java.util.LinkedHashMap. {\"a\" 1 \"b\" 2}))")]
        (expect (map? r))
        (expect (= 1 (get r "a")))
        (expect (= 2 (get r "b")))))

    (it "converts nested structures recursively"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(spel/java->clj (java.util.LinkedHashMap. {\"x\" (java.util.ArrayList. [1 2 3])}))")]
        (expect (map? r))
        (expect (vector? (get r "x")))
        (expect (= [1 2 3] (get r "x"))))))

  (describe "clojure.set via ns-alias"
    (it "set/union works without require"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(set/union #{1 2} #{2 3})")]
        (expect (= #{1 2 3} r))))

    (it "set/difference works"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(set/difference #{1 2 3} #{2})")]
        (expect (= #{1 3} r)))))

  (describe "clojure.walk via ns-alias"
    (it "walk/keywordize-keys works without require"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(walk/keywordize-keys {\"a\" 1 \"b\" 2})")]
        (expect (= {:a 1 :b 2} r)))))

  (describe "zprint via ns-alias"
    (it "zp/zprint-str formats data"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(zp/zprint-str {:a 1})")]
        (expect (string? r))
        (expect (str/includes? r ":a")))))

  (describe "Math and java.math classes"
    (it "Math/PI and Math/E resolve as static fields"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (< 3.14 (double (sci-env/eval-string ctx "(Math/PI)")) 3.15))
        (expect (< 2.71 (double (sci-env/eval-string ctx "(Math/E)")) 2.72))))

    (it "Math static methods work (sqrt, pow, sin, log)"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (= 4.0 (double (sci-env/eval-string ctx "(Math/sqrt 16)"))))
        (expect (= 1024.0 (double (sci-env/eval-string ctx "(Math/pow 2 10)"))))
        (expect (= 0.0 (double (sci-env/eval-string ctx "(Math/sin 0)"))))
        (expect (< 0.69 (double (sci-env/eval-string ctx "(Math/log 2)")) 0.70))))

    (it "StrictMath is accessible"
      (let [ctx (sci-env/create-sci-ctx)
            r   (sci-env/eval-string ctx "(StrictMath/log (Math/E))")]
        (expect (< 0.999 (double r) 1.001))))

    (it "MathContext and RoundingMode resolve with static fields"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (instance? java.math.MathContext
                  (sci-env/eval-string ctx "(MathContext/DECIMAL32)")))
        (expect (instance? java.math.RoundingMode
                  (sci-env/eval-string ctx "(RoundingMode/HALF_UP)")))))

    (it "BigDecimal and BigInteger work via bare import"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (instance? java.math.BigDecimal
                  (sci-env/eval-string ctx "(BigDecimal. \"3.14\")")))
        (expect (instance? java.math.BigInteger
                  (sci-env/eval-string ctx "(BigInteger. \"100000000000000000000\")")))))

    (it "Random, SplittableRandom, ThreadLocalRandom are usable"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (integer? (sci-env/eval-string ctx "(.nextInt (Random. 42))")))
        (expect (integer? (sci-env/eval-string ctx "(.nextInt (SplittableRandom. 42))")))
        (expect (some? (sci-env/eval-string ctx "(ThreadLocalRandom/current)"))))))

  (describe "cdp-connect callable"
    (it "spel/cdp-connect with no browser throws with clear message"
      ;; cdp-connect without a running daemon will throw — but the
      ;; error message should mention "No running browser" or
      ;; "daemon mode", proving the function IS wired up and runs.
      (let [ctx (sci-env/create-sci-ctx)
            err (try (sci-env/eval-string ctx "(spel/cdp-connect)")
                     (catch Exception e (.getMessage e)))]
        (expect (string? err))
        ;; Either "No running browser" (auto-discover path) or
        ;; "daemon mode" (reconnect handler not injected)
        (expect (or (str/includes? err "browser")
                  (str/includes? err "daemon")))))))

;; =============================================================================
;; CLI-side session list (discover-sessions, active-sessions-on-disk,
;; build-session-list-data) — tested by the cli-test ns since they live there.
;; =============================================================================
