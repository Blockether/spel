(ns com.blockether.spel.daemon
  "Background daemon that keeps a Playwright browser alive between CLI calls.

   Listens on a Unix domain socket for JSON commands, executes them against
   the browser, and returns JSON responses. Each command is one JSON line;
   each response is one JSON line.

   Usage:
     (start-daemon! {:session \"default\" :headless true})   ;; blocks
     (daemon-running? \"default\")                           ;; check
     (stop-daemon!)                                         ;; cleanup"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [sci.core :as sci]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.action-log :as action-log]
   [com.blockether.spel.annotate :as annotate]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.helpers :as helpers]
   [com.blockether.spel.input :as input]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.markdownify :as markdownify]
   [com.blockether.spel.network :as network]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.options :as options]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.stealth :as stealth]
   [com.blockether.spel.visual-diff :as visual-diff])
  (:import
   [com.microsoft.playwright BrowserContext ConsoleMessage Dialog Frame Keyboard Mouse Page Request Response]
   [com.microsoft.playwright.options AriaRole Cookie Geolocation]
   [java.io BufferedReader File InputStreamReader OutputStreamWriter]
   [java.lang ProcessBuilder$Redirect]
   [java.net HttpURLConnection StandardProtocolFamily UnixDomainSocketAddress URL]
   [java.nio.channels Channels ServerSocketChannel SocketChannel]
   [java.nio.file Files Path]
   [java.util Base64]
   [java.util.concurrent ExecutorService Executors ScheduledExecutorService ScheduledFuture TimeUnit]))

(declare stop-daemon!)
(declare save-inflight-trace!)
(declare pg)
(declare daemon-running?)

(defn- warn
  "Logs a warning to stderr. Used in cleanup paths where we must continue
   despite errors but never silently swallow them."
  [^String context ^Exception e]
  (binding [*out* *err*]
    (println (str "warn: " context ": " (.getMessage e)))))

;; =============================================================================
;; Paths
;; =============================================================================

(defn socket-path
  "Returns the Unix socket path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".sock")
    (into-array String [])))

(defn pid-file-path
  "Returns the PID file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".pid")
    (into-array String [])))

(defn log-file-path
  "Returns the log file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".log")
    (into-array String [])))

(defn flags-file-path
  "Returns the launch flags persistence file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".flags.json")
    (into-array String [])))

(defn- cdp-route-lock-path
  "Returns a filesystem lock path keyed by CDP endpoint URL.
   Used to prevent multi-session route interception on the same CDP browser."
  ^Path [^String cdp-url]
  (let [encoder (.withoutPadding (Base64/getUrlEncoder))
        token   (.encodeToString encoder (.getBytes cdp-url java.nio.charset.StandardCharsets/UTF_8))]
    (Path/of (str (System/getProperty "java.io.tmpdir")
               File/separator
               "spel-cdp-route-lock-" token ".json")
      (into-array String []))))

(defn- read-cdp-route-lock
  "Reads the lock map for a CDP endpoint, or nil if absent/invalid."
  [^String cdp-url]
  (let [path (cdp-route-lock-path cdp-url)]
    (when (Files/exists path (into-array java.nio.file.LinkOption []))
      (try
        (json/read-json (String. (Files/readAllBytes path)))
        (catch Exception _ nil)))))

(defn- clear-cdp-route-lock!
  "Deletes the lock file for a CDP endpoint. Best-effort."
  [^String cdp-url]
  (try
    (Files/deleteIfExists (cdp-route-lock-path cdp-url))
    (catch Exception e (warn "delete-cdp-route-lock" e))))

(defn- write-cdp-route-lock!
  "Writes/overwrites the lock owner for a CDP endpoint."
  [^String cdp-url ^String session]
  (let [payload {:session session
                 :cdp cdp-url
                 :updated_at (System/currentTimeMillis)}]
    (Files/writeString (cdp-route-lock-path cdp-url)
      (json/write-json-str payload)
      (into-array java.nio.file.OpenOption []))))

(defn- parse-devtools-active-port
  "Parses a DevToolsActivePort file, returning {:port N :ws-path \"/devtools/...\"} or nil."
  [^String path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (try
        (let [content (slurp f)
              lines   (str/split-lines content)
              port    (parse-long (str/trim (first lines)))
              ws-path (when (>= (count lines) 2)
                        (str/trim (second lines)))]
          (when (and port (pos? (long port)) (<= (long port) 65535))
            {:port port :ws-path ws-path}))
        (catch Exception _ nil)))))

(defn- scan-playwright-devtools
  "Scans ms-playwright cache dir for subdirectories containing DevToolsActivePort.
   Returns first match or nil. Finds Chrome launched by chrome-devtools-mcp etc."
  [^String cache-dir]
  (let [parent (java.io.File. cache-dir)]
    (when (.isDirectory parent)
      (some (fn [^java.io.File child]
              (when (.isDirectory child)
                (parse-devtools-active-port
                  (str (.getPath child) "/DevToolsActivePort"))))
        (.listFiles parent)))))

(defn- probe-http-cdp
  "Probes an HTTP endpoint for CDP. Returns the port only when /json/version is HTTP 200.
   Returns nil for non-200 (e.g. M144 websocket-only 404) or connection failures."
  [port timeout-ms]
  (try
    (let [url  (URL. (str "http://127.0.0.1:" port "/json/version"))
          conn (doto (.openConnection url)
                 (.setConnectTimeout (int timeout-ms))
                 (.setReadTimeout (int timeout-ms))
                 (.connect))]
      (try
        (when (= 200 (.getResponseCode ^HttpURLConnection conn))
          port)
        (finally
          (.disconnect ^HttpURLConnection conn))))
    (catch Exception _ nil)))

(defn discover-cdp-endpoint
  "Auto-discovers a running Chromium-based browser's CDP endpoint.
   Checks DevToolsActivePort files first (Chrome, Edge, Chromium data dirs +
   ms-playwright cache), then probes common ports (9222, 9229).
   Returns a CDP URL string (http:// or ws://) suitable for Playwright connectOverCDP.

   Chrome/Edge 136+ ignores --remote-debugging-port without --user-data-dir.
   Chrome/Edge 144+ chrome://inspect remote debugging uses WebSocket-only (no HTTP)."
  []
  (let [home    (System/getProperty "user.home")
        os-name (str/lower-case (System/getProperty "os.name"))
        mac?    (str/includes? os-name "mac")
        ;; Standard browser data directories (Chrome, Edge, Chromium)
        browser-candidates
        (if mac?
          [(str home "/Library/Application Support/Google/Chrome/DevToolsActivePort")
           (str home "/Library/Application Support/Google/Chrome Canary/DevToolsActivePort")
           (str home "/Library/Application Support/Microsoft Edge/DevToolsActivePort")
           (str home "/Library/Application Support/Microsoft Edge Canary/DevToolsActivePort")
           (str home "/Library/Application Support/Chromium/DevToolsActivePort")]
          [(str home "/.config/google-chrome/DevToolsActivePort")
           (str home "/.config/microsoft-edge/DevToolsActivePort")
           (str home "/.config/chromium/DevToolsActivePort")
           (str home "/.config/google-chrome-unstable/DevToolsActivePort")
           (str home "/.config/microsoft-edge-dev/DevToolsActivePort")])
        ;; ms-playwright cache (chrome-devtools-mcp, agent-browser, etc.)
        pw-cache (if mac?
                   (str home "/Library/Caches/ms-playwright")
                   (str home "/.cache/ms-playwright"))
        ;; Try browser data dirs first, then ms-playwright subdirs
        dt-info  (or (some parse-devtools-active-port browser-candidates)
                   (scan-playwright-devtools pw-cache))]
    (if dt-info
      ;; DevToolsActivePort found — try HTTP probe first, fall back to direct WebSocket
      (let [port    (:port dt-info)
            ws-path (:ws-path dt-info)]
        (if (probe-http-cdp port 2000)
          ;; Pre-M144: HTTP endpoint works
          (str "http://127.0.0.1:" port)
          ;; M144+: WebSocket-only server (chrome://inspect remote debugging)
          ;; HTTP endpoints return 404, must connect via WebSocket directly.
          (if ws-path
            (str "ws://127.0.0.1:" port ws-path)
            (str "http://127.0.0.1:" port))))
      ;; No DevToolsActivePort — probe common ports
      (let [found (some #(probe-http-cdp % 1000) [9222 9229])]
        (if found
          (let [http-url (str "http://127.0.0.1:" found)
                 ;; M144+ returns 404 for /json/version (WebSocket-only).
                 ;; If /json/version returns non-200, fall back to raw ws:// URL.
                ws? (try
                      (let [url  (URL. (str http-url "/json/version"))
                            conn (doto (.openConnection url)
                                   (.setConnectTimeout 1000)
                                   (.setReadTimeout 1000)
                                   (.connect))]
                        (try
                          (not= 200 (.getResponseCode ^HttpURLConnection conn))
                          (finally
                            (.disconnect ^HttpURLConnection conn))))
                      (catch Exception _ true))]
            (if ws?
              (str "ws://127.0.0.1:" found)
              http-url))
          (throw (ex-info (str "No running browser with remote debugging found.\n\n"
                            "Chrome/Edge 136+ requires --user-data-dir for --remote-debugging-port to work.\n\n"
                            "Option 1 — Launch browser with debug port:\n"
                            "  " (if mac?
                                   "open -na \"Google Chrome\" --args --remote-debugging-port=9222 --user-data-dir=\"$HOME/chrome-debug\" --no-first-run"
                                   "google-chrome --remote-debugging-port=9222 --user-data-dir=\"$HOME/chrome-debug\" --no-first-run")
                            "\n"
                            "  " (if mac?
                                   "open -na \"Microsoft Edge\" --args --remote-debugging-port=9222 --user-data-dir=\"$HOME/edge-debug\" --no-first-run"
                                   "microsoft-edge --remote-debugging-port=9222 --user-data-dir=\"$HOME/edge-debug\" --no-first-run")
                            "\n\n"
                            "Option 2 — Enable in running browser (M144+):\n"
                            "  Open chrome://inspect/#remote-debugging and toggle it on.\n"
                            "  (Works in both Chrome and Edge)\n")
                   {:browser-candidates browser-candidates
                    :pw-cache           pw-cache
                    :probed-ports       [9222 9229]})))))))

;; =============================================================================
;; Auto-Launch: browser lifecycle for --auto-launch
;; =============================================================================

(def ^:private ^:const auto-launch-base-port
  "Base port for auto-launched browser debug ports. Each session gets a unique
   port starting from this value."
  9222)

(def ^:private ^:const auto-launch-port-range
  "Maximum number of ports to scan when looking for a free CDP port."
  100)

(defn- auto-launch-lock-path
  "Returns the lock file path for an auto-launched browser on a given port.
   Used to track port<->session ownership so other sessions avoid collisions."
  ^Path [^long port]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-auto-launch-" port ".json")
    (into-array String [])))

(defn- read-auto-launch-lock
  "Reads the auto-launch lock for a port. Returns map or nil."
  [^long port]
  (let [path (auto-launch-lock-path port)]
    (when (Files/exists path (into-array java.nio.file.LinkOption []))
      (try
        (json/read-json (String. (Files/readAllBytes path)))
        (catch Exception _ nil)))))

(defn- write-auto-launch-lock!
  "Writes a lock file claiming a CDP port for a session."
  [^long port ^String session ^long browser-pid]
  (let [payload {:session session
                 :port port
                 :browser_pid browser-pid
                 :created_at (System/currentTimeMillis)}]
    (Files/writeString (auto-launch-lock-path port)
      (json/write-json-str payload)
      (into-array java.nio.file.OpenOption []))))

(defn- clear-auto-launch-lock!
  "Deletes the auto-launch lock file for a port. Best-effort."
  [^long port]
  (try
    (Files/deleteIfExists (auto-launch-lock-path port))
    (catch Exception e (warn "clear-auto-launch-lock" e))))

(defn- port-in-use?
  "Checks if a TCP port is in use by attempting to connect to it."
  [^long port]
  (try
    (with-open [^java.net.Socket sock (java.net.Socket.)]
      (.connect sock (java.net.InetSocketAddress. "127.0.0.1" (int port)) 500)
      true)
    (catch Exception _ false)))

(defn- auto-launch-lock-active?
  "Returns true if the lock file exists AND the owning daemon is still alive."
  [^long port]
  (when-let [lock (read-auto-launch-lock port)]
    (let [owner (get lock "session")]
      (if (and owner (daemon-running? owner))
        true
        (do (clear-auto-launch-lock! port) false)))))

(defn find-free-cdp-port
  "Finds an available CDP port starting from 9222. Checks both the OS-level port
   availability and spel auto-launch lock files to avoid collisions with other
   sessions. Returns the port number or throws if none found."
  []
  (let [base  (long auto-launch-base-port)
        range (long auto-launch-port-range)]
    (loop [port base
           tried 0]
      (if (>= tried range)
        (throw (ex-info (str "No free CDP port found in range "
                          base "-" (+ base range -1))
                 {:base-port base :range range}))
        (if (or (port-in-use? port) (auto-launch-lock-active? port))
          (recur (inc port) (inc tried))
          port)))))

(defn resolve-browser-binary
  "Resolves the filesystem path to a Chrome/Edge binary based on the channel name.
   Supports: chrome, msedge, chrome-beta, chrome-canary, msedge-beta, msedge-dev.
   Falls back to 'chrome' if channel is nil.
   Returns the binary path string, or throws if not found."
  [channel]
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        mac?    (str/includes? os-name "mac")
        win?    (str/includes? os-name "windows")
        ch      (or channel "chrome")
        path
        (cond
          mac?
          (case ch
            "chrome"        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
            "chrome-beta"   "/Applications/Google Chrome Beta.app/Contents/MacOS/Google Chrome Beta"
            "chrome-canary" "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary"
            "msedge"        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
            "msedge-beta"   "/Applications/Microsoft Edge Beta.app/Contents/MacOS/Microsoft Edge Beta"
            "msedge-dev"    "/Applications/Microsoft Edge Dev.app/Contents/MacOS/Microsoft Edge Dev"
            "chromium"      "/Applications/Chromium.app/Contents/MacOS/Chromium"
            (throw (ex-info (str "Unknown browser channel: " ch)
                     {:channel ch :os "macos"})))

          win?
          (let [pf      (System/getenv "PROGRAMFILES")
                pf-x86  (System/getenv "PROGRAMFILES(X86)")
                local   (System/getenv "LOCALAPPDATA")]
            (case ch
              "chrome"        (str pf "\\Google\\Chrome\\Application\\chrome.exe")
              "chrome-beta"   (str pf "\\Google\\Chrome Beta\\Application\\chrome.exe")
              "chrome-canary" (str local "\\Google\\Chrome SxS\\Application\\chrome.exe")
              "msedge"        (str pf-x86 "\\Microsoft\\Edge\\Application\\msedge.exe")
              "msedge-beta"   (str pf-x86 "\\Microsoft\\Edge Beta\\Application\\msedge.exe")
              "msedge-dev"    (str pf-x86 "\\Microsoft\\Edge Dev\\Application\\msedge.exe")
              (throw (ex-info (str "Unknown browser channel: " ch)
                       {:channel ch :os "windows"}))))

          :else ;; Linux
          (case ch
            "chrome"        "google-chrome"
            "chrome-beta"   "google-chrome-beta"
            "chrome-canary" "google-chrome-unstable"
            "msedge"        "microsoft-edge"
            "msedge-beta"   "microsoft-edge-beta"
            "msedge-dev"    "microsoft-edge-dev"
            "chromium"      "chromium-browser"
            (throw (ex-info (str "Unknown browser channel: " ch)
                     {:channel ch :os "linux"}))))

        exists? (if (or mac? win?)
                  (.exists (java.io.File. ^String path))
                  ;; On Linux, check if command is on PATH
                  (try
                    (let [^java.util.List which-cmd (doto (java.util.ArrayList.) (.add "which") (.add path))
                          pb (ProcessBuilder. which-cmd)
                          p  (.start pb)]
                      (zero? (.waitFor p)))
                    (catch Exception _ false)))]
    (when-not exists?
      (throw (ex-info (str "Browser binary not found: " path
                        "\nInstall " ch " or specify a different --channel.")
               {:channel ch :path path})))
    path))

(defn auto-launch-browser!
  "Launches a browser with --remote-debugging-port on a free port.
   Uses a temp user-data-dir so the user's existing browser stays untouched.

   Params:
     `channel`  - Browser channel (e.g. 'chrome', 'msedge'). Defaults to 'chrome'.
     `session`  - Session name, used for lock file ownership.
     `headless` - Boolean, whether to launch headless.

   Returns a map:
     :cdp-url      - CDP endpoint URL (http://127.0.0.1:<port>)
     :port         - The allocated port
     :browser-pid  - PID of the launched browser process
     :tmp-dir      - Path to the temp user-data-dir (for cleanup)"
  [{:keys [channel session headless]
    :or {channel "chrome" session "default" headless true}}]
  (let [port      (find-free-cdp-port)
        binary    (resolve-browser-binary channel)
        tmp-dir   (str (Files/createTempDirectory "spel-auto-launch-"
                         (into-array java.nio.file.attribute.FileAttribute [])))
        browser-args
        (cond-> [(str "--remote-debugging-port=" port)
                 (str "--user-data-dir=" tmp-dir)
                 "--no-first-run"
                 "--no-default-browser-check"]
          headless (conj "--headless=new"))
        ;; On macOS we need to use the binary path directly (not `open -a`)
        ;; because `open` spawns in background and we can't get the PID
        cmd       (into [binary] browser-args)
        _         (binding [*out* *err*]
                    (println (str "spel: auto-launch: starting " channel " on port " port))
                    (println (str "spel: auto-launch: temp profile: " tmp-dir)))
        pb        (doto (ProcessBuilder. ^java.util.List (java.util.ArrayList. ^java.util.Collection cmd))
                    (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                    (.redirectErrorStream true))
        process   (.start pb)
        pid       (.pid process)]
    ;; Write lock file immediately to claim the port
    (write-auto-launch-lock! port session pid)
    ;; Wait for the CDP endpoint to be ready (up to 15s)
    (loop [attempts 0]
      (cond
        (>= attempts 150)
        (do
          ;; Cleanup on failure
          (.destroyForcibly process)
          (clear-auto-launch-lock! port)
          (throw (ex-info (str "Auto-launched browser did not start within 15 seconds on port " port)
                   {:port port :channel channel :pid pid})))

        (not (.isAlive process))
        (do
          (clear-auto-launch-lock! port)
          (throw (ex-info (str "Auto-launched browser process exited immediately (exit code: "
                            (.exitValue process) "). Binary: " binary)
                   {:port port :channel channel :exit-code (.exitValue process)})))

        (probe-http-cdp port 500)
        (do
          (binding [*out* *err*]
            (println (str "spel: auto-launch: " channel " ready on port " port " (PID " pid ")")))
          {:cdp-url     (str "http://127.0.0.1:" port)
           :port        port
           :browser-pid pid
           :tmp-dir     tmp-dir})

        :else
        (do (Thread/sleep 100)
            (recur (inc attempts)))))))

(defn kill-auto-launched-browser!
  "Kills an auto-launched browser process and cleans up its lock file and temp dir."
  [{:keys [^long port ^long browser-pid ^String tmp-dir]}]
  (when browser-pid
    (try
      (when-let [ph (.orElse (java.lang.ProcessHandle/of browser-pid) nil)]
        (when (.isAlive ^java.lang.ProcessHandle ph)
          (binding [*out* *err*]
            (println (str "spel: auto-launch: killing browser PID " browser-pid)))
          ;; Kill the process tree (browser + child processes)
          (.descendants ^java.lang.ProcessHandle ph)
          (run! (fn [^java.lang.ProcessHandle child]
                  (try (.destroyForcibly child) (catch Exception _)))
            (iterator-seq (.iterator (.descendants ^java.lang.ProcessHandle ph))))
          (.destroyForcibly ^java.lang.ProcessHandle ph)))
      (catch Exception e (warn "kill-auto-launched-browser" e))))
  (when port
    (clear-auto-launch-lock! port))
  (when tmp-dir
    (try
      (let [tmp-path (java.nio.file.Paths/get ^String tmp-dir (into-array String []))]
        (when (Files/exists tmp-path (into-array java.nio.file.LinkOption []))
          (java.nio.file.Files/walkFileTree tmp-path
            (proxy [java.nio.file.SimpleFileVisitor] []
              (visitFile [^java.nio.file.Path file ^java.nio.file.attribute.BasicFileAttributes _attrs]
                (java.nio.file.Files/deleteIfExists file)
                java.nio.file.FileVisitResult/CONTINUE)
              (postVisitDirectory [^java.nio.file.Path dir ^java.io.IOException _exc]
                (java.nio.file.Files/deleteIfExists dir)
                java.nio.file.FileVisitResult/CONTINUE)))))
      (catch Exception e (warn "cleanup-auto-launch-tmp-dir" e)))))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !state
  (atom {:pw       nil
         :browser  nil
         :context  nil
         :page     nil
         :refs     {}
         :counter  0
         :headless true
         :session  "default"
         :tracing? false}))

(defonce ^:private !server (atom nil))
(defonce ^:private ^ExecutorService !vthread-executor
  (Executors/newVirtualThreadPerTaskExecutor))

(defn- submit-virtual
  "Submits a task to the virtual thread executor."
  [^Runnable f]
  (.submit !vthread-executor f))

;; --- CDP idle timeout ---
;; After explicit cdp_disconnect, auto-shutdown the daemon if no reconnect
;; occurs within the configured window. Default 30 minutes.
;; Set SPEL_CDP_IDLE_TIMEOUT env var (milliseconds) to override; 0 disables.
(defonce ^:private !cdp-idle-timeout-ms
  (atom (let [env-val (System/getenv "SPEL_CDP_IDLE_TIMEOUT")]
          (if (str/blank? env-val)
            1800000
            (Long/parseLong env-val)))))
(defonce ^:private ^ScheduledExecutorService !cdp-idle-scheduler
  (Executors/newSingleThreadScheduledExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r "spel-cdp-idle-timer")
          (.setDaemon true))))))
(defonce ^:private !cdp-idle-future (atom nil))

;; --- CDP route lock wait ---
;; When another session holds the CDP route lock, wait instead of failing fast.
;; Mirrors browser-lock's queuing behavior: poll every 2s, up to 120s.
;; Set SPEL_CDP_LOCK_WAIT (seconds) and SPEL_CDP_LOCK_POLL_INTERVAL (seconds) to override; 0 disables wait.
(defonce ^:private !cdp-lock-wait-s
  (atom (let [env-val (System/getenv "SPEL_CDP_LOCK_WAIT")]
          (if (str/blank? env-val)
            120
            (Long/parseLong env-val)))))
(defonce ^:private !cdp-lock-poll-interval-s
  (atom (let [env-val (System/getenv "SPEL_CDP_LOCK_POLL_INTERVAL")]
          (if (str/blank? env-val)
            2
            (Long/parseLong env-val)))))

;; --- Session idle timeout ---
;; Auto-shutdown daemon if no command is received within the configured window.
;; Default 30 minutes. Set SPEL_SESSION_IDLE_TIMEOUT env var (milliseconds) to override; 0 disables.
(defonce ^:private !session-idle-timeout-ms
  (atom (let [env-val (System/getenv "SPEL_SESSION_IDLE_TIMEOUT")]
          (if (str/blank? env-val)
            1800000
            (Long/parseLong env-val)))))
(defonce ^:private ^ScheduledExecutorService !session-idle-scheduler
  (Executors/newSingleThreadScheduledExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r "spel-session-idle-timer")
          (.setDaemon true))))))
(defonce ^:private !session-idle-future (atom nil))

(defonce ^:private !console-messages (atom []))
(defonce ^:private !page-errors (atom []))
(defonce ^:private !dialog-handler (atom nil))
(defonce ^:private !tracked-requests (atom []))
(def ^:private max-tracked-requests 500)
(defonce ^:private !routes (atom {}))

(def ^:private cdp-route-lock-exempt-actions
  "Actions allowed even when another session owns route interception lock.
   These are read-only queries, session management, or local buffer operations
   that don't drive the page and should never queue."
  #{"close" "session_info" "session_list"
    "cdp_disconnect" "cdp_reconnect"
    "network_list" "network_requests" "network_get_ref"
    "console_list" "console_get_ref"
    "pages_list" "pages_get_ref"
    "network_unroute"
    "action_log" "action_log_srt" "action_log_clear"
    ;; Read-only page queries — safe, and should not queue for 120s
    "url" "title"
    "tab_list"
    "find_free_port"
    ;; Local buffer reads/clears — no browser interaction
    "console_get" "console_clear"
    "errors_get" "errors_clear"
    "network_clear"
    ;; State file operations — filesystem only
    "state_list" "state_show" "state_rename" "state_clear" "state_clean"})

(defn- current-cdp-url
  "Returns currently configured CDP URL from daemon launch flags, if any."
  []
  (get-in @!state [:launch-flags "cdp"]))

(defn- active-cdp-route-lock
  "Returns an active lock map for cdp-url, clearing stale locks automatically."
  [^String cdp-url]
  (when-let [lock (read-cdp-route-lock cdp-url)]
    (let [owner (get lock "session")]
      (cond
        (str/blank? owner)
        (do (clear-cdp-route-lock! cdp-url) nil)

        ;; Keep our own lock as active.
        (= owner (:session @!state))
        lock

        ;; If owner daemon is gone, clear stale lock.
        (not (daemon-running? owner))
        (do (clear-cdp-route-lock! cdp-url) nil)

        :else
        lock))))

(defn- cdp-route-lock-conflict
  "Returns conflict details when current command should be blocked due to
   another session owning CDP route interception lock on the same endpoint."
  [^String action]
  (let [session       (:session @!state)
        cdp-connected (true? (:cdp-connected @!state))
        cdp-url       (current-cdp-url)]
    (when (and cdp-connected
            (string? cdp-url)
            (not (contains? cdp-route-lock-exempt-actions action)))
      (when-let [lock (active-cdp-route-lock cdp-url)]
        (let [owner (get lock "session")]
          (when (and owner (not= owner session))
            {:owner-session owner
             :cdp-url cdp-url}))))))

(defn- await-cdp-route-lock
  "Waits for the CDP route lock to be released by another session.
   Polls every `!cdp-lock-poll-interval-s` seconds up to `!cdp-lock-wait-s` seconds.
   Returns nil when lock is cleared (or was never held), or a conflict map on timeout.
   If wait is 0, returns conflict immediately (fail-fast)."
  [^String action]
  (let [max-wait-s  (long @!cdp-lock-wait-s)
        poll-s      (long @!cdp-lock-poll-interval-s)
        poll-ms     (* poll-s 1000)]
    (if-let [conflict (cdp-route-lock-conflict action)]
      (if (zero? max-wait-s)
        ;; Fail-fast mode
        conflict
        ;; Queue mode — poll until lock clears or timeout
        (do
          (binding [*out* *err*]
            (println (str "spel: CDP lock held by session '" (:owner-session conflict)
                       "' — waiting (0/" max-wait-s "s)...")))
          (loop [waited 0]
            (if (>= waited max-wait-s)
              ;; Timeout — return conflict for error response
              (do
                (binding [*out* *err*]
                  (println (str "spel: CDP lock timeout after " max-wait-s
                             "s — blocking action '" action "'")))
                conflict)
              (do
                (Thread/sleep poll-ms)
                (let [waited' (+ waited poll-s)]
                  (if-let [_still-locked (cdp-route-lock-conflict action)]
                    (recur waited')
                    ;; Lock cleared!
                    (do
                      (binding [*out* *err*]
                        (println (str "spel: CDP lock acquired after " waited' "s wait")))
                      nil))))))))
      ;; No conflict
      nil)))

(defn- cdp-route-lock-warning
  "Returns a warning payload when another session owns active CDP route lock.
   Used by the `connect` command so users get proactive guidance before hangs."
  [^String cdp-url]
  (let [session (:session @!state)]
    (when-let [lock (active-cdp-route-lock cdp-url)]
      (let [owner (get lock "session")]
        (when (and owner (not= owner session))
          {:warning (str "Another session ('" owner "') already has active network routes on this CDP endpoint. "
                      "Use one session per --cdp endpoint for route interception to avoid hangs.")
           :route_lock_owner owner})))))

(defn cdp-route-lock-owner
  "Returns the owning session name when `cdp-url` currently has an active
   cross-session route lock, otherwise nil. Stale locks are cleared lazily."
  [^String cdp-url]
  (when-let [lock (active-cdp-route-lock cdp-url)]
    (get lock "session")))

(defn- release-cdp-route-lock-if-owned!
  "Clears CDP route lock if this daemon session currently owns it."
  []
  (when-let [cdp-url (current-cdp-url)]
    (when-let [lock (read-cdp-route-lock cdp-url)]
      (when (= (:session @!state) (get lock "session"))
        (clear-cdp-route-lock! cdp-url)))))

(defn- persist-launch-flags!
  "Writes current launch-flags to the session's flags file for CLI to read.
   Called after flags are stored in !state so subsequent commands and daemon
   restarts can recover the flags (e.g. --cdp URL) without the user re-typing them."
  []
  (try
    (let [session (:session @!state)
          flags   (get @!state :launch-flags {})]
      (when (and session (seq flags))
        (Files/writeString (flags-file-path session)
          (json/write-json-str flags)
          (into-array java.nio.file.OpenOption []))))
    (catch Exception e (warn "persist-launch-flags" e))))

(defn read-session-flags
  "Reads persisted launch flags for a session from the flags file.
   Returns a map of flag-name->value, or empty map if file doesn't exist.
   Used by CLI to recover flags like --cdp without requiring them on every command."
  [^String session]
  (let [path (flags-file-path session)]
    (if (Files/exists path (into-array java.nio.file.LinkOption []))
      (try
        (json/read-json (String. (Files/readAllBytes path)))
        (catch Exception _ {}))
      {})))

;; =============================================================================
;; Network + Console Sliding Window (TASK-013)
;; =============================================================================

(def ^:private max-window-per-page 1000)
(def ^:private max-session-total 1000000)

(defonce ^:private !network-window (atom []))
(defonce ^:private !network-counter (atom 0))
(defonce ^:private !network-full (atom {}))  ;; ref-id -> full entry with body/headers

(defonce ^:private !console-window (atom []))
(defonce ^:private !console-counter (atom 0))
(defonce ^:private !console-full (atom {}))  ;; ref-id -> full entry

(defonce ^:private !pages (atom []))
(defonce ^:private !page-counter (atom 0))

(defonce ^:private !session-entry-count (atom 0))

(def ^:private max-preview-body-bytes (long 65536))
(def ^:private preview-body-resource-types #{"fetch" "xhr"})

(defn- parse-long-safe
  "Parses a string into a long, returning nil on invalid input."
  [s]
  (when (some? s)
    (try
      (Long/parseLong (str s))
      (catch Exception _ nil))))

(defn- should-capture-response-body?
  "Returns true when response body preview is likely cheap/useful enough to capture.
   Avoids expensive reads for large/non-text/static assets that can stall CDP-heavy sessions."
  [resource-type resp-headers]
  (let [content-type   (some-> (get resp-headers "content-type") str/lower-case)
        content-length (parse-long-safe (get resp-headers "content-length"))]
    (and (contains? preview-body-resource-types resource-type)
      (or (nil? content-length)
        (<= (long content-length) (long max-preview-body-bytes)))
      (or (str/blank? content-type)
        (re-find #"json|text|javascript|xml|x-www-form-urlencoded" content-type)))))

;; Action Log — user-facing browser commands tracked for SRT export.
;; Atoms live in sci-env (alongside !page, !context, etc.) to avoid circular deps.

(def ^:private trackable-actions
  "Set of user-facing browser commands that should be recorded in the action log."
  #{"navigate" "click" "fill" "type" "press" "hover" "check" "uncheck"
    "select" "dblclick" "focus" "clear" "screenshot" "scroll"
    "survey" "audit" "routes" "inspect" "overview" "debug" "emulate" "markdownify"
    "back" "forward" "reload" "drag_to" "tap" "set_input_files"})

(defn- track-action!
  "Records a user-facing command in the action log with timestamp and page context.
   Called from process-command after successful handle-cmd for trackable actions.
   Captures: action, target, args, page URL, page title, and the post-action
   snapshot tree (when the handler returns one)."
  [^String action params result]
  (let [now (System/currentTimeMillis)
        idx (swap! sci-env/!action-counter inc)
        ;; Set start time on first action
        _   (compare-and-set! sci-env/!action-log-start 0 now)
        ;; ISO timestamp for human-readable JSON export
        iso (str (java.time.Instant/ofEpochMilli now))
        ;; Extract target: prefer ref/selector from params
        target (or (get params "ref")
                 (get params "selector")
                 (get params "text")  ;; for click-by-text style
                 nil)
        ;; Build args map (exclude bulky/redundant keys)
        args   (not-empty (dissoc params "ref" "selector" "text"
                            "raw-input" "action"))
        ;; Grab page context (safe — page may not exist yet for navigate)
        url    (try (page/url (pg)) (catch Exception _ nil))
        title  (try (page/title (pg)) (catch Exception _ nil))
        ;; Extract snapshot from result if the handler returned one
        snap   (when (map? result) (:snapshot result))
        entry  (cond-> {:idx       idx
                        :timestamp now
                        :time      iso
                        :action    action
                        :target    target
                        :args      args
                        :url       url
                        :title     title}
                 snap (assoc :snapshot snap))]
    (swap! sci-env/!action-log conj entry)))

(defn- truncate-keys
  "Returns a map with at most n top-level keys, values not expanded."
  [m n]
  (when (map? m)
    (into {} (take n m))))

(defn- safe-parse-json-body
  "Tries to parse a string as JSON, returns parsed map or the raw string (truncated)."
  [^String s ^long max-len]
  (when s
    (try
      (let [parsed (json/read-json s)]
        (if (map? parsed)
          (truncate-keys parsed 5)
          (let [s-trunc (if (> (long (count s)) max-len) (subs s 0 max-len) s)]
            s-trunc)))
      (catch Exception _
        (let [s-trunc (if (> (long (count s)) max-len) (subs s 0 max-len) s)]
          s-trunc)))))

(defn- current-page-ref
  "Returns the page ref for the given URL, or nil."
  [page-url]
  (when-let [pages (seq @!pages)]
    (:ref (last (filter #(= (:url %) page-url) pages)))))

(defn- track-page-navigation!
  "Tracks a page navigation into the pages list."
  [url status title]
  (let [ref-id (str "p" (swap! !page-counter inc))]
    (swap! !pages conj {:ref (str "@" ref-id)
                        :url url
                        :status (or status 200)
                        :title (or title "")
                        :navigated_at (System/currentTimeMillis)})
    (str "@" ref-id)))

(defn- track-network-entry!
  "Tracks a network request/response with full details into the sliding window."
  [^Response resp]
  (when (< (long @!session-entry-count) (long max-session-total))
    (let [^Request req (.request resp)
          ref-id (str "n" (swap! !network-counter inc))
          resource-type (.resourceType req)
          page-url (try (.url (.page (.frame req))) (catch Exception _ "unknown"))
          req-headers (try (into {} (.allHeaders req)) (catch Exception _ {}))
          resp-headers (try (into {} (.allHeaders resp)) (catch Exception _ {}))
          post-data (try (.postData req) (catch Exception _ nil))
          resp-body (when (should-capture-response-body? resource-type resp-headers)
                      (try (.text resp) (catch Exception _ nil)))
          req-body-preview (safe-parse-json-body post-data 500)
          resp-body-preview (safe-parse-json-body resp-body 500)
          duration 0
          entry {:ref (str "@" ref-id)
                 :method (.method req)
                 :url (.url req)
                 :resource_type resource-type
                 :status (.status resp)
                 :duration_ms duration
                 :timestamp (System/currentTimeMillis)
                 :page page-url
                 :page_ref (current-page-ref page-url)
                 :preview {:request  {:headers (truncate-keys req-headers 5)
                                      :body    req-body-preview}
                           :response {:headers (truncate-keys resp-headers 5)
                                      :body    resp-body-preview}}}
          full-entry {:ref (str "@" ref-id)
                      :method (.method req)
                      :url (.url req)
                      :resource_type resource-type
                      :status (.status resp)
                      :duration_ms duration
                      :timestamp (System/currentTimeMillis)
                      :page page-url
                      :page_ref (current-page-ref page-url)
                      :request {:headers req-headers
                                :body post-data}
                      :response {:headers resp-headers
                                 :body resp-body}}]
      (swap! !network-full assoc ref-id full-entry)
      (swap! !network-window
        (fn [w]
          (let [updated (conj w entry)
                n (count updated)]
            (if (> (long n) (long max-window-per-page))
              (subvec updated (- (long n) (long max-window-per-page)))
              updated))))
      (swap! !session-entry-count inc))))

(defn- track-console-entry!
  "Tracks a console message into the sliding window."
  [^ConsoleMessage msg]
  (when (< (long @!session-entry-count) (long max-session-total))
    (let [ref-id (str "c" (swap! !console-counter inc))
          page-url (try (.url (.page msg)) (catch Exception _ "unknown"))
          ;; Get stack trace if available via location
          location (try
                     (let [^String loc (.location ^ConsoleMessage msg)]
                       (when (and loc (not (.isEmpty loc))) loc))
                     (catch Exception _ nil))
          entry {:ref (str "@" ref-id)
                 :type (.type msg)
                 :text (.text msg)
                 :timestamp (System/currentTimeMillis)
                 :page page-url
                 :page_ref (current-page-ref page-url)}
          entry (if location (assoc entry :stack location) entry)]
      (swap! !console-full assoc ref-id entry)
      (swap! !console-window
        (fn [w]
          (let [updated (conj w entry)
                n (count updated)]
            (if (> (long n) (long max-window-per-page))
              (subvec updated (- (long n) (long max-window-per-page)))
              updated))))
      (swap! !session-entry-count inc))))

(defn- track-response!
  "Appends a response summary to the tracked-requests ring buffer, capped at
   `max-tracked-requests` most-recent entries. Also feeds the TASK-013 sliding window."
  [^Response resp]
  (let [^Request req (.request resp)
        entry {:url    (.url req)
               :method (.method req)
               :status (.status resp)
               :resource-type (.resourceType req)}]
    (swap! !tracked-requests
      (fn [reqs]
        (let [updated (conj reqs entry)
              n (long (count updated))]
          (if (> n (long max-tracked-requests))
            (subvec updated (- n (long max-tracked-requests)))
            updated))))
    ;; TASK-013: also track into enriched sliding window
    (track-network-entry! resp)))

(defn- pg ^Page [] (:page @!state))
(defn- ctx ^BrowserContext [] (:context @!state))

(defn- str->aria-role
  "Converts a lowercase role string to AriaRole enum.
   Throws ex-info with a helpful message if the role name is invalid."
  ^AriaRole [^String s]
  (try
    (AriaRole/valueOf (.toUpperCase s))
    (catch IllegalArgumentException _
      (throw (ex-info (str "Unknown ARIA role: " s
                        ". Valid roles include: alert, button, checkbox, combobox, dialog, grid, "
                        "heading, img, link, list, listbox, listitem, menu, menuitem, navigation, "
                        "option, paragraph, progressbar, radio, region, row, search, searchbox, "
                        "separator, slider, spinbutton, switch, tab, table, tabpanel, textbox, "
                        "toolbar, tooltip, tree, treeitem")
               {})))))

(defn- filter-snapshot-tree
  "Applies snapshot filters to the tree string."
  [tree {:strs [interactive cursor compact depth flat]}]
  (if (or (nil? tree) (str/blank? tree))
    tree
    (let [lines (str/split-lines tree)
          lines (if interactive
                  (if cursor
                    ;; -C cursor mode: include interactive elements + cursor-related generic elements
                    (filter #(or (str/includes? % "[@")
                               (re-find #"role=\"(textbox|combobox|searchbox|spinbutton|slider)\"" %)
                               (re-find #"\[focused\]" %))
                      lines)
                    (filter #(str/includes? % "[@") lines))
                  lines)
          lines (if compact
                  (remove #(re-matches #"\s*- \w+\s*" %) lines)
                  lines)
          lines (if depth
                  (let [max-indent (* 2 (long depth))]
                    (filter (fn [line]
                              (<= (count (take-while #{\ } line)) max-indent))
                      lines))
                  lines)
          lines (if flat
                  (map str/triml lines)
                  lines)]
      (str/join "\n" lines))))

(defn- session-state-path
  "Returns the state file path for a named session."
  ^String [^String session-name]
  (str (System/getProperty "java.io.tmpdir")
    File/separator
    "spel-session-" session-name ".json"))

(defn- persist-enabled?
  "Returns true if session state persistence is enabled.
   Persistence is ON by default for all sessions. Disabled by --no-persist flag."
  []
  (let [flags (get @!state :launch-flags {})]
    (not (get flags "no-persist"))))

(defn- auto-load-session-state!
  "If persistence is enabled, loads saved cookies/storage from a previous session.
   Uses the session name from !state as the persistence key.
   Called after browser/context creation to restore state across daemon restarts."
  []
  (when (persist-enabled?)
    (let [sn         (:session @!state)
          state-path (session-state-path sn)]
      (when (Files/exists (Path/of state-path (into-array String []))
              (into-array java.nio.file.LinkOption []))
        ;; Save in-flight trace before destroying context
        (save-inflight-trace!)
        ;; Close current page and context, re-create with saved state
        (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
        (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
        (let [new-ctx (core/new-context (:browser @!state) {:storage-state state-path})
              new-pg  (core/new-page-from-context new-ctx)]
          (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
          ;; Re-register console, error, and request listeners on new page
          (page/on-console new-pg (fn [msg]
                                    (swap! !console-messages conj
                                      {:type (.type ^ConsoleMessage msg)
                                       :text (.text ^ConsoleMessage msg)})))
          (page/on-page-error new-pg (fn [error]
                                       (swap! !page-errors conj
                                         {:message (str error)})))
          (page/on-response new-pg track-response!))))))

(defn- auto-save-session-state!
  "Saves the current browser context state (cookies/storage) to a file.
   Uses the session name as the persistence key. Called on close.
   Disabled when --no-persist flag is set."
  []
  (when (and (persist-enabled?) (:context @!state))
    (try
      (let [state-path (session-state-path (:session @!state))]
        (.storageState ^BrowserContext (ctx)
          (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
            (.setPath (Path/of state-path (into-array String []))))))
      (catch Exception _
        ;; Best-effort — don't fail close on state save error
        nil))))

(defn- check-anomaly!
  "Checks if result is an anomaly map. If so, throws ex-info with the
   original Playwright error message and cause. Otherwise returns result.
   Used in ensure-browser! to surface meaningful errors instead of ClassCastException."
  [result context-msg]
  (if (anomaly/anomaly? result)
    (throw (ex-info (str context-msg ": " (::anomaly/message result))
             (dissoc result :playwright/exception)
             (:playwright/exception result)))
    result))

(defn- ensure-browser!
  "Lazily starts browser on first command. Uses launch-flags from !state.

   Three modes:
   1. --profile with directory → Playwright launchPersistentContext
   2. --auto-launch → launch browser with debug port, connect via CDP
   3. Normal → Playwright launch (or --cdp connect)

   Auto-loads persisted session state unless --no-persist is set."
  []
  (when-not (:browser @!state)
    (let [flags       (get @!state :launch-flags {})
          profile-dir (get flags "profile")
          extensions  (get flags "extensions")
          _           (when (seq extensions)
                        (doseq [ext extensions]
                          (when-not (.isDirectory (java.io.File. ^String ext))
                            (throw (ex-info (str "Extension path does not exist or is not a directory: " ext)
                                     {:extension-path ext}))))
                        (binding [*out* *err*]
                          (println (str "spel: Loading " (count extensions) " extension(s): "
                                     (str/join ", " extensions)))
                          (println "spel: Note: --extension is Chromium-only; extensions are ignored on Firefox/WebKit")))
          launch-opts (cond-> {:headless (:headless @!state)}
                        (get flags "channel")          (assoc :channel (get flags "channel"))
                        (get flags "executable-path") (assoc :executable-path (get flags "executable-path"))
                        (get flags "args")            (assoc :args (clojure.string/split (get flags "args") #","))
                        (get flags "proxy")           (assoc :proxy {:server (get flags "proxy")
                                                                     :bypass (get flags "proxy-bypass" "")})
                        (get flags "cdp")             (assoc :cdp (get flags "cdp"))
                        (get flags "stealth")         (update :args (fnil into []) (stealth/stealth-args))
                        (get flags "stealth")         (update :ignore-default-args (fnil into []) (stealth/stealth-ignore-default-args))
                        (seq extensions)
                        (update :args (fnil conj [])
                          (str "--load-extension=" (str/join "," extensions)))
                        (seq extensions)
                        (update :ignore-default-args (fnil conj []) "--disable-extensions"))
          ctx-opts    (cond-> {}
                        (get flags "user-agent")          (assoc :user-agent (get flags "user-agent"))
                        (get flags "ignore-https-errors")  (assoc :ignore-https-errors true)
                        (get flags "headers")             (assoc :extra-http-headers
                                                            (try (json/read-json (get flags "headers"))
                                                                 (catch Exception _ {})))
                        (get flags "storage-state")       (assoc :storage-state (get flags "storage-state")))
          pw          (core/create)]
      (cond
        ;; ── Mode 1: --profile with directory → Playwright persistent ──────
        ;; Use Playwright's launchPersistentContext for custom profile dirs.
        profile-dir
        (let [_           (binding [*out* *err*]
                            (println (str "spel: [Mode 1] Persistent context with profile: " profile-dir)))
              launch-opts (update launch-opts :ignore-default-args
                            (fnil into [])
                            ["--use-mock-keychain" "--password-store=basic"])
              persistent-opts (merge launch-opts ctx-opts)
              context         (check-anomaly!
                                (core/launch-persistent-context
                                  (.chromium ^com.microsoft.playwright.Playwright pw)
                                  profile-dir
                                  persistent-opts)
                                "Failed to launch persistent browser context")
              _               (when (get flags "stealth")
                                (.addInitScript ^BrowserContext context ^String (stealth/stealth-init-script)))
              browser         (.browser ^BrowserContext context)
              pg-inst         (if (seq (.pages ^BrowserContext context))
                                (first (.pages ^BrowserContext context))
                                (check-anomaly!
                                  (core/new-page-from-context context)
                                  "Failed to create page in persistent context"))]
          (swap! !state assoc :pw pw :browser browser :context context :page pg-inst
            :persistent-profile true))

        ;; ── Mode 2: --auto-launch → launch browser + CDP connect ─────────
        (get flags "auto-launch")
        (let [_       (binding [*out* *err*]
                        (println "spel: [Mode 2] Auto-launch browser with CDP"))
              channel (get flags "channel" "chrome")
              session (:session @!state)
              result  (auto-launch-browser!
                        {:channel  channel
                         :session  session
                         :headless (:headless @!state)})
              cdp-url (:cdp-url result)
              _       (binding [*out* *err*]
                        (println (str "spel: auto-launch: connecting via CDP to " cdp-url)))
              browser (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String cdp-url)
              contexts (.contexts ^com.microsoft.playwright.Browser browser)
              context  (if (seq contexts)
                         (first contexts)
                         (check-anomaly!
                           (core/new-context browser)
                           "Auto-launch: failed to create browser context"))
              pages    (.pages ^com.microsoft.playwright.BrowserContext context)
              pg-inst  (if (seq pages)
                         (first pages)
                         (check-anomaly!
                           (core/new-page-from-context context)
                           "Auto-launch: failed to create page"))]
          ;; Store CDP URL in launch flags so subsequent commands know we're CDP-connected
          (swap! !state assoc-in [:launch-flags "cdp"] cdp-url)
          (persist-launch-flags!)
          ;; Track auto-launch info for cleanup on stop-daemon!
          (swap! !state assoc
            :pw pw :browser browser :context context :page pg-inst
            :cdp-connected true
            :auto-launch-info {:port        (:port result)
                               :browser-pid (:browser-pid result)
                               :tmp-dir     (:tmp-dir result)}))

        ;; ── Mode 3: Normal launch or CDP connect ─────────────────────────
        :else
        (let [_       (binding [*out* *err*]
                        (println (str "spel: [Mode 3] "
                                   (if (get flags "cdp")
                                     (str "CDP connect: " (get flags "cdp"))
                                     "Standard launch"))))
              browser-type (get flags "browser" "chromium")
              launch-fn   (case browser-type
                            "firefox" core/launch-firefox
                            "webkit"  core/launch-webkit
                            core/launch-chromium)
              cdp-url     (get flags "cdp")
              browser     (if cdp-url
                            (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String cdp-url)
                            (check-anomaly!
                              (launch-fn pw launch-opts)
                              "Failed to launch browser"))]
          (if cdp-url
            ;; CDP: reuse the REAL browser's existing contexts and pages.
            ;; The whole point of CDP is to control the user's actual Chrome
            ;; with its real login sessions, cookies, tabs — NOT create new ones.
            (let [contexts (.contexts ^com.microsoft.playwright.Browser browser)
                  context  (if (seq contexts)
                             (first contexts)
                             (check-anomaly!
                               (core/new-context browser)
                               "No existing context found via CDP and failed to create one"))
                  pages    (.pages ^com.microsoft.playwright.BrowserContext context)
                  pg-inst  (if (seq pages)
                             (first pages)
                             (check-anomaly!
                               (core/new-page-from-context context)
                               "No existing page found via CDP and failed to create one"))]
              (swap! !state assoc :pw pw :browser browser :context context :page pg-inst :cdp-connected true))
            ;; Normal launch: create fresh context and page as before.
            (let [context (check-anomaly!
                            (if (seq ctx-opts)
                              (core/new-context browser ctx-opts)
                              (core/new-context browser))
                            "Failed to create browser context")
                  _       (when (get flags "stealth")
                            (.addInitScript ^BrowserContext context ^String (stealth/stealth-init-script)))
                  pg-inst (check-anomaly!
                            (core/new-page-from-context context)
                            "Failed to create page")]
              (swap! !state assoc :pw pw :browser browser :context context :page pg-inst)))))
      ;; Common setup for all paths
      (let [pg-inst (:page @!state)]
        (reset! !console-messages [])
        (page/on-console pg-inst (fn [^ConsoleMessage msg]
                                   (swap! !console-messages conj
                                     {:type (.type msg)
                                      :text (.text msg)})
                                   (track-console-entry! msg)))
        (reset! !page-errors [])
        (page/on-page-error pg-inst (fn [error]
                                      (swap! !page-errors conj
                                        {:message (str error)})))
        (reset! !tracked-requests [])
        (page/on-response pg-inst track-response!)
        ;; Auto-load persisted session state (not for persistent/CDP profiles)
        (when-not (or profile-dir (get flags "cdp"))
          (auto-load-session-state!))))))

;; =============================================================================
;; Ref Resolution
;; =============================================================================

(defn- ref? [^String s]
  (boolean (re-matches #"@e[a-z0-9]+" s)))

(defn- resolve-selector
  "Resolves a selector — if it's a ref (@e2yrjz) resolve via snapshot,
   otherwise return a regular CSS locator.
   Throws immediately if the ref was never captured in a snapshot."
  [^String selector]
  (if (ref? selector)
    (let [ref-id (str/replace selector #"^@" "")
          refs   (:refs @!state)]
      (when-not (get refs ref-id)
        (let [hint (if (seq refs)
                     (let [rows (for [[k v] (sort-by key refs)]
                                  (str "  @" k "  " (:role v)
                                    (when-let [n (:name v)]
                                      (when-not (str/blank? n)
                                        (str " \"" (if (> (count n) 40)
                                                     (str (subs n 0 37) "...")
                                                     n) "\"")))))]
                       (str "Available refs:\n" (str/join "\n" rows)
                         "\nRun 'snapshot' to refresh."))
                     "No refs available. Run 'snapshot' first to assign refs (@e2yrjz, @e9mter, \u2026).")]
          (throw (ex-info (str "Ref " ref-id " not found.\n" hint) {}))))
      (snapshot/resolve-ref (pg) ref-id))
    (page/locator (pg) selector)))

(declare unwrap-anomaly!)

(defn- yes-no
  "Formats booleans as Yes/No for human-readable diagnostics."
  [v]
  (if v "Yes" "No"))

(defn- locator-diagnostics
  "Collects lightweight click diagnostics for a locator.

   Returns:
   {:count long :found boolean :visible boolean? :enabled boolean?}"
  [loc]
  (let [countv (try
                 (long (locator/count-elements loc))
                 (catch Exception _ 0))
        found? (clojure.core/pos? (long countv))]
    {:count   countv
     :found   found?
     :visible (when found?
                (try (boolean (locator/is-visible? loc))
                     (catch Exception _ nil)))
     :enabled (when found?
                (try (boolean (locator/is-enabled? loc))
                     (catch Exception _ nil)))}))

(defn- refresh-snapshot!
  "Captures a fresh snapshot and updates daemon ref state."
  []
  (let [snap (snapshot/capture-snapshot (pg))]
    (swap! !state assoc :refs (:refs snap) :counter (:counter snap))
    snap))

(defn- throw-click-error!
  "Throws an ex-info with rich click diagnostics and the original cause."
  [selector {:keys [found visible enabled]} cause]
  (let [msg (str "Click failed for " selector "\n"
              "  - Element found: " (yes-no found) "\n"
              "  - Element visible: " (if (nil? visible) "Unknown" (yes-no visible)) "\n"
              "  - Element enabled: " (if (nil? enabled) "Unknown" (yes-no enabled))
              (when-let [m (.getMessage ^Throwable cause)]
                (str "\n  - Playwright: " m)))]
    (throw (ex-info msg {:selector selector
                         :found found
                         :visible visible
                         :enabled enabled}
             cause))))

(defn- click-with-ref-recovery!
  "Clicks a selector with stale-ref recovery and fail-fast diagnostics.

   For @e refs:
   1) preflight locator existence
   2) refresh snapshot once if missing
   3) fail fast with diagnostics when still missing

   For non-ref selectors, still performs preflight diagnostics before click."
  [^String selector]
  (let [ref-selector? (and selector (ref? selector))
        ref-id        (when ref-selector? (str/replace selector #"^@" ""))
        loc           (if ref-selector?
                        (let [refs          (:refs @!state)
                              ref-present?  (contains? refs ref-id)
                              fresh-snap    (when-not ref-present? (refresh-snapshot!))
                              fresh-present (if fresh-snap
                                              (contains? (:refs fresh-snap) ref-id)
                                              ref-present?)]
                          (when-not fresh-present
                            (throw (ex-info
                                     (str "Ref " ref-id " not found.\n"
                                       "Click failed for " selector "\n"
                                       "  - Element found: No\n"
                                       "  - Suggestion: run 'snapshot -i' and retry click.")
                                     {:selector selector :found false :stale-ref true})))
                          (snapshot/resolve-ref (pg) ref-id))
                        (resolve-selector selector))
        diag (locator-diagnostics loc)]
    (when-not (:found diag)
      (throw (ex-info
               (str "Selector not found: " selector "\n"
                 "Click failed for " selector "\n"
                 "  - Element found: No\n"
                 "  - Suggestion: run 'snapshot -i' and retry click.")
               {:selector selector :found false})))
    (try
      ;; Keep click failures fast in automation/CDP scenarios.
      (unwrap-anomaly! (locator/click loc {:timeout 5000}))
      (catch Throwable t
        (throw-click-error! selector (locator-diagnostics loc) t)))))

(defn- describe-element
  "Returns a short human-readable description of the element behind a locator.
   e.g. 'h1 \"Example Domain\"', 'button \"Submit\"', 'input[type=text][name=email]'.
   Returns nil on failure (element detached, timeout, etc.)."
  [loc]
  (try
    (locator/evaluate-locator loc
      (str "el => {"
        "  const tag = el.tagName.toLowerCase();"
        "  const text = (el.innerText || '').trim().replace(/\\s+/g, ' ');"
        "  const cls = el.className ? '.' + el.className.trim().split(/\\s+/)[0] : '';"
        "  const name = el.getAttribute('name');"
        "  const type = el.getAttribute('type');"
        "  let desc = tag;"
        "  if (cls && !text) desc += cls;"
        "  if (type) desc += '[type=' + type + ']';"
        "  if (name) desc += '[name=' + name + ']';"
        "  const dt = text.length > 30 ? text.slice(0, 30) + '…' : text;"
        "  if (dt) desc += ' \"' + dt + '\"';"
        "  return desc;"
        "}"))
    (catch Exception _ nil)))

;; =============================================================================
;; Snapshot Helper
;; =============================================================================

(defn- ensure-page-loaded!
  "Throws if no page has been navigated to (still on about:blank)."
  []
  (let [url (page/url (pg))]
    (when (or (nil? url) (#{"about:blank" ""} url))
      (throw (ex-info "No page loaded. Navigate first: spel open <url>" {})))))

(defn- page-description
  "Extracts the meta description from the current page, or nil."
  []
  (try
    (let [desc (page/evaluate (pg)
                 "document.querySelector('meta[name=description]')?.content || ''")]
      (when-not (str/blank? desc) desc))
    (catch Exception _ nil)))

(defn- snapshot-after-action!
  "Captures a snapshot, stores refs in state, returns the tree string.

   Accepts optional opts map with :scope (CSS selector) to restrict
   the snapshot to a DOM subtree."
  ([]
   (snapshot-after-action! {}))
  ([opts]
   (let [snap (snapshot/capture-snapshot (pg) opts)]
     (swap! !state assoc :refs (:refs snap) :counter (:counter snap))
     (:tree snap))))

;; =============================================================================
;; Error Helpers — used by command handlers and process-command
;; =============================================================================

(defn- parse-playwright-error
  "Parses a Playwright error message to extract structured call log and selector.
   Handles two Playwright log formats:
     - Structured: lines between \"=== logs ===\" markers (locator timeout errors)
     - Inline: lines after \"Call log:\" header (raw Playwright exceptions)
   Selector extracted from locator(\"...\") or getByRole/getByText/etc patterns.
   Returns map with :call_log (vector of strings) and :selector (string or nil).
   Returns empty map when msg is nil or has no parseable structure."
  [msg]
  (when msg
    (let [;; Format 1: structured === logs === block
          log-match (re-find #"(?s)={3,}\s*logs\s*={3,}\n(.*?)\n={3,}" msg)
          ;; Format 2: inline "Call log:" section (Playwright raw exception format)
          call-log-raw (when-not log-match
                         (second (re-find #"(?s)Call log:\n(.*)" msg)))
          call-log  (cond
                      log-match
                      (let [lines (->> (str/split-lines (second log-match))
                                    (mapv str/trim)
                                    (filterv (complement str/blank?)))]
                        (when (seq lines) lines))
                      call-log-raw
                      (let [lines (->> (str/split-lines call-log-raw)
                                    ;; Strip leading dashes+spaces (format: "-   - msg")
                                    (mapv #(str/replace % #"^[-\s]+" ""))
                                    (mapv str/trim)
                                    (filterv (complement str/blank?)))]
                        (when (seq lines) lines)))
          ;; Greedy match handles inner escaped quotes: locator("[data-pw-ref=\"x\"]")
          sel-match (second (re-find #"locator\(\"(.+)\"\)" msg))
          ;; Also try getByRole, getByText, etc.
          get-by-match (when-not sel-match
                         (re-find #"(getBy\w+)\(([^)]+)\)" msg))
          selector  (or sel-match
                      (when get-by-match
                        (str (second get-by-match) "(" (nth get-by-match 2) ")")))]
      (cond-> {}
        call-log (assoc :call_log call-log)
        selector (assoc :selector selector)))))

(declare humanize-error)

(defn- default-error-message
  "Returns a human-friendly fallback message when runtime provided no details."
  ([]
   "unexpected browser error (no details from runtime)")
  ([^Throwable e]
   (if e
     (str "unexpected browser error (" (.getSimpleName (.getClass e)) ", no details from runtime)")
     (default-error-message))))

(defn- error-response
  "Creates a structured error response map from an error message string.
   Parses Playwright error context (call_log, selector) when present.
   Returns {:success false :error msg} with optional :call_log and :selector."
  [^String msg]
  (let [msg    (if (str/blank? msg) (default-error-message) msg)
        parsed (parse-playwright-error msg)
        human  (humanize-error msg)]
    (cond-> {:success false :error msg}
      (:call_log parsed) (assoc :call_log (:call_log parsed))
      (:selector parsed) (assoc :selector (:selector parsed))
      (:hint human) (assoc :hint (:hint human))
      (:error_code human) (assoc :error_code (name (:error_code human))))))

(defn- humanize-error
  "Adds actionable, human-readable hints to known error patterns.

   Returns map with optional keys:
   - :hint string
   - :error_code keyword"
  [^String msg]
  (cond
    (or (nil? msg)
      (str/blank? msg)
      (= "Unknown error" msg)
      (str/starts-with? msg "unexpected browser error"))
    {:hint "An unexpected browser error occurred. Retry once with --debug; if it repeats, run `spel close` and try again."
     :error_code :unknown_error}

    (str/includes? msg "No page loaded")
    {:hint "Open a page first, for example: `spel open https://example.org`."
     :error_code :no_page_loaded}

    (str/includes? msg "No browser")
    {:hint "Start a browser session first with `spel open <url>` or `spel eval-sci '(spel/start!)'`."
     :error_code :no_browser}

    (re-find #"(?i)target page, context or browser has been closed|TargetClosedError" msg)
    {:hint "The browser or tab closed during the command. Re-open the page and retry. For CDP runs, verify the debug browser is still running."
     :error_code :target_closed}

    (re-find #"(?i)timeout .* exceeded|timed out" msg)
    {:hint "The operation timed out. Verify selector/page state and consider increasing --timeout for slow pages."
     :error_code :timeout}

    (str/includes? msg "Ref ")
    {:hint "The element ref is stale or missing. Run `spel snapshot -i` and retry with a fresh @ref."
     :error_code :stale_ref}

    (str/includes? msg "Unknown action:")
    {:hint "The command action is not supported by the daemon. Check `spel --help` for valid commands."
     :error_code :unknown_action}

    :else nil))

(defn- unwrap-anomaly!
  "Checks if x is an anomaly map and re-throws the underlying error.
   - Anomaly with :playwright/exception → re-throws the original exception.
   - Anomaly without exception → throws ex-info with the anomaly message.
   - Non-anomaly value → returns x unchanged.
   Use this to wrap locator action results so errors propagate instead of
   being silently discarded."
  [x]
  (if (anomaly/anomaly? x)
    (if-let [ex (:playwright/exception x)]
      (throw ex)
      (throw (ex-info (or (::anomaly/message x) (default-error-message))
               (dissoc x ::anomaly/message ::anomaly/category))))
    x))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(defmulti ^:private handle-cmd (fn [action _params] action))

(defmethod handle-cmd "navigate" [_ {:strs [url screenshot screenshot-path raw-input
                                            viewport-width viewport-height]}]
  (ensure-browser!)
  (page/validate-url url (or raw-input url))
  ;; Set viewport before navigation so the page renders at the requested size.
  (when (and viewport-width viewport-height)
    (page/set-viewport-size! (pg) (long viewport-width) (long viewport-height)))
  (page/navigate (pg) url)
  (page/wait-for-load-state (pg))
  ;; Track page navigation for page refs
  (track-page-navigation! (page/url (pg)) 200 (try (page/title (pg)) (catch Exception _ "")))
  (if screenshot
    ;; --screenshot flag: capture page and save to disk after navigation.
    ;; Uses the provided path, or generates a timestamped file in the system
    ;; temp directory when no path is given. Throws on write failure so the
    ;; daemon surfaces the error to the CLI (never silently fails).
    (let [path-str    (or screenshot-path
                        (str (System/getProperty "java.io.tmpdir")
                          java.io.File/separator
                          "spel-screenshot-"
                          (System/currentTimeMillis) ".png"))
          ^bytes ss-bytes (page/screenshot (pg))]
      (snapshot-after-action!)
      (let [out-path (Path/of ^String path-str (into-array String []))]
        (when-let [parent (.getParent out-path)]
          (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
        (java.nio.file.Files/write out-path ss-bytes
          ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [])))
      {:url (page/url (pg)) :title (page/title (pg)) :screenshot path-str :size (alength ss-bytes)
       :viewport (page/viewport-size (pg))})
    (do
      (snapshot-after-action!)
      (cond-> {:url (page/url (pg)) :title (page/title (pg))}
        viewport-width (assoc :viewport (page/viewport-size (pg)))
        (page-description) (assoc :description (page-description))))))

(defn- build-structured-refs
  "Builds the structured refs map for JSON output (AC-5/AC-6)."
  [refs]
  (into {}
    (map (fn [[ref-id info]]
           [ref-id
            (cond-> {:role (:role info)}
              (seq (:name info))        (assoc :name (:name info))
              (:url info)               (assoc :url (:url info))
              (:type info)              (assoc :type (:type info))
              (some? (:checked info))   (assoc :checked (:checked info))
              (:level info)             (assoc :level (:level info))
              (:value info)             (assoc :value (:value info))
              (:styles info)            (assoc :styles (:styles info)))]))
    refs))

(defmethod handle-cmd "snapshot" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [sel            (get params "selector")
        all?           (get params "all")
        styles?        (get params "styles")
        styles-detail  (get params "styles_detail")
        no-network?    (get params "no_network")
        no-console?    (get params "no_console")
        device         (:device @!state)
        snap           (if all?
                         (snapshot/capture-full-snapshot (pg))
                         (snapshot/capture-snapshot (pg) (cond-> {}
                                                           sel           (assoc :scope sel)
                                                           styles?       (assoc :styles true)
                                                           styles-detail (assoc :styles-detail styles-detail)
                                                           device        (assoc :device device))))
        _              (swap! !state assoc :refs (:refs snap) :counter (:counter snap))
        tree           (filter-snapshot-tree (:tree snap) params)
        structured     (build-structured-refs (:refs snap))]
    (cond-> {:snapshot tree :refs_count (:counter snap) :url (page/url (pg)) :title (page/title (pg))
             :refs structured :pages @!pages}
      (:viewport snap)            (assoc :viewport (:viewport snap))
      (:device snap)              (assoc :device (:device snap))
      (page-description)          (assoc :description (page-description))
      no-network?                 (dissoc :network)
      no-console?                 (dissoc :console)
      (not no-network?)           (assoc :network @!network-window)
      (not no-console?)           (assoc :console @!console-window))))

(defmethod handle-cmd "click" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (when (str/blank? (str selector))
    (throw (ex-info "click requires a selector or @ref" {})))
  (click-with-ref-recovery! selector)
  (snapshot-after-action!)
  {:clicked selector})

(defmethod handle-cmd "download" [_ {:strs [selector save-path timeout-ms]}]
  (ensure-page-loaded!)
  (let [loc      (resolve-selector selector)
        dl-opts  (when timeout-ms {:timeout (double timeout-ms)})
        download (unwrap-anomaly!
                   (if dl-opts
                     (page/wait-for-download (pg) #(unwrap-anomaly! (locator/click loc)) dl-opts)
                     (page/wait-for-download (pg) #(unwrap-anomaly! (locator/click loc)))))
        filename (page/download-suggested-filename download)
        _        (unwrap-anomaly! (page/download-save-as! download save-path))
        size     (try (.length (java.io.File. ^String save-path)) (catch Exception _ -1))]
    {:filename filename
     :size     size
     :path     save-path}))

(defmethod handle-cmd "fill" [_ {:strs [selector value]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/fill (resolve-selector selector) value))
  (snapshot-after-action!)
  {:filled selector})

(defmethod handle-cmd "type" [_ {:strs [selector text]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/type-text (resolve-selector selector) text))
  (snapshot-after-action!)
  {:typed selector})

(defmethod handle-cmd "press" [_ {:strs [key selector]}]
  (ensure-page-loaded!)
  (if selector
    (unwrap-anomaly! (locator/press (resolve-selector selector) key))
    (.press ^Keyboard (page/page-keyboard (pg)) key))
  (snapshot-after-action!)
  {:pressed key})

(defmethod handle-cmd "hover" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (unwrap-anomaly! (locator/hover loc))
        desc (describe-element loc)]
    (cond-> {:hovered selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "check" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/check (resolve-selector selector)))
  (snapshot-after-action!)
  {:checked selector})

(defmethod handle-cmd "uncheck" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/uncheck (resolve-selector selector)))
  (snapshot-after-action!)
  {:unchecked selector})

(defmethod handle-cmd "select" [_ {:strs [selector values]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/select-option (resolve-selector selector) values))
  (snapshot-after-action!)
  {:selected selector})

(defmethod handle-cmd "dblclick" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/dblclick (resolve-selector selector)))
  (snapshot-after-action!)
  {:dblclicked selector})

(defmethod handle-cmd "focus" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (unwrap-anomaly! (locator/focus loc))
        desc (describe-element loc)]
    (cond-> {:focused selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "clear" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (unwrap-anomaly! (locator/clear loc))
        desc (describe-element loc)]
    (cond-> {:cleared selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "screenshot" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [path-str       (get params "path")
        full-page?     (get params "fullPage" false)
        crop-content?  (get params "cropToContent" false)
        sel            (get params "selector")
        ;; Skip crop-to-content when a selector is given — locator screenshots
        ;; capture only the element, so viewport resize is pointless.
        crop?          (and crop-content? (not sel))
        ;; When --crop-to-content: resize viewport to content height, take normal
        ;; screenshot, then restore. Uses try/finally to guarantee viewport restore
        ;; even if the screenshot throws (timeout, etc.).
        original-vp    (when crop? (page/viewport-size (pg)))
        _              (when crop?
                         (let [content-h (check-anomaly!
                                           (page/evaluate (pg) "Math.min(document.body.scrollHeight, Math.max(document.body.offsetHeight, document.body.clientHeight))")
                                           "Failed to evaluate content height")
                               vp-w      (:width original-vp)]
                           (page/set-viewport-size! (pg) (long vp-w) (max 1 (long content-h)))))
        ^bytes ss-bytes (if crop?
                          ;; try/finally guarantees viewport restore on any exception
                          (try
                            (page/screenshot (pg))
                            (finally
                              (when original-vp
                                (page/set-viewport-size! (pg) (long (:width original-vp)) (long (:height original-vp))))))
                          ;; Normal path (no crop) — no viewport to restore
                          (if sel
                            (locator/locator-screenshot (resolve-selector sel))
                            (page/screenshot (pg) (cond-> {}
                                                    full-page? (assoc :full-page true)))))]
    (if path-str
      (let [out-path (Path/of ^String path-str (into-array String []))]
        (when-let [parent (.getParent out-path)]
          (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
        (Files/write out-path ss-bytes
          ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
        {:path path-str :size (alength ss-bytes)})
      (let [tmp-path (str (System/getProperty "java.io.tmpdir")
                       java.io.File/separator
                       "spel-screenshot-"
                       (System/currentTimeMillis) ".png")]
        (java.nio.file.Files/write
          (Path/of ^String tmp-path (into-array String []))
          ss-bytes
          ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
        {:path tmp-path :size (alength ss-bytes)}))))

(defmethod handle-cmd "annotate" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [scope (get params "selector")
        opts (cond-> {}
               scope
               (assoc :scope scope)
               (get params "full-page")              (assoc :full-page true)
               (contains? params "show-badges")     (assoc :show-badges (get params "show-badges"))
               (contains? params "show-dimensions") (assoc :show-dimensions (get params "show-dimensions"))
               (contains? params "show-boxes")      (assoc :show-boxes (get params "show-boxes")))
        ;; Capture fresh snapshot for refs
        _    (snapshot-after-action!)
        refs (:refs @!state)
        n    (if (seq refs)
               (annotate/inject-overlays! (pg) refs opts)
               0)]
    {:annotated n :refs_total (:counter @!state)}))

(defmethod handle-cmd "unannotate" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (annotate/remove-overlays! (pg))
  {:removed true})

(defmethod handle-cmd "survey" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [opts (cond-> {}
               (get params "output-dir") (assoc :output-dir (get params "output-dir"))
               (get params "prefix")     (assoc :prefix (get params "prefix"))
               (get params "overlap")    (assoc :overlap (long (get params "overlap")))
               (get params "annotate")   (assoc :annotate? true)
               (get params "max-frames") (assoc :max-frames (long (get params "max-frames"))))
        results (helpers/survey! (pg) opts)]
    {:frames results :count (count results)}))

(defmethod handle-cmd "audit" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [all? (boolean (get params "all"))
        only (when-let [o (get params "only")]
               (if (string? o) (set (str/split o #",")) (set o)))
        pg   (pg)
        run? (fn [k] (or all? (nil? only) (contains? only (name k))))
        safe (fn [f] (try (f) (catch Exception e {:error (.getMessage e)})))]
    (if (or all? only)
      (cond-> {}
        (run? :structure) (assoc :structure (safe #(helpers/audit! pg)))
        (run? :contrast)  (assoc :contrast  (safe #(helpers/text-contrast! pg)))
        (run? :colors)    (assoc :colors    (safe #(helpers/color-palette! pg)))
        (run? :layout)    (assoc :layout    (safe #(helpers/layout-check! pg)))
        (run? :fonts)     (assoc :fonts     (safe #(helpers/font-audit! pg)))
        (run? :links)     (assoc :links     (safe #(helpers/link-health! pg)))
        (run? :headings)  (assoc :headings  (safe #(helpers/heading-structure! pg))))
      (helpers/audit! pg))))

(defmethod handle-cmd "markdownify" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [params (or params {})
        opts   {:title?    (if (contains? params "title")
                             (boolean (get params "title"))
                             true)
                :readable? (if (contains? params "readable")
                             (boolean (get params "readable"))
                             true)
                :a11y?     (if (contains? params "a11y")
                             (boolean (get params "a11y"))
                             true)}
        md     (if (and (:readable? opts) (:a11y? opts))
                 (markdownify/page->markdown (pg) {:title? (:title? opts)})
                 (markdownify/html->markdown (pg) (page/content (pg)) opts))]
    {:markdown (unwrap-anomaly! md)}))

(defmethod handle-cmd "routes" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [opts (cond-> {}
               (get params "internal-only") (assoc :internal-only? true)
               (get params "visible-only")  (assoc :visible-only? true))]
    (helpers/routes! (pg) opts)))

(defmethod handle-cmd "inspect" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [opts (cond-> {}
               (contains? params "compact")      (assoc :compact? (get params "compact"))
               (get params "style-detail")       (assoc :style-detail (get params "style-detail"))
               (get params "scope")              (assoc :scope (get params "scope"))
               (get params "device")             (assoc :device (get params "device")))
        snap (helpers/inspect! (pg) opts)]
    (snapshot-after-action!)
    {:tree (:tree snap)
     :refs (:refs snap)
     :counter (:counter snap)
     :viewport (:viewport snap)}))

(defmethod handle-cmd "overview" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [path   (get params "path")
        opts   (cond-> {}
                 path                             (assoc :path path)
                 (get params "all")                  (assoc :all-frames? true)
                 (contains? params "show-badges")     (assoc :show-badges (get params "show-badges"))
                 (contains? params "show-dimensions") (assoc :show-dimensions (get params "show-dimensions"))
                 (contains? params "show-boxes")      (assoc :show-boxes (get params "show-boxes"))
                 (get params "scope")                 (assoc :scope (get params "scope")))
        result (helpers/overview! (pg) opts)]
    (if (:bytes result)
      (let [tmp-path (str (System/getProperty "java.io.tmpdir")
                       java.io.File/separator
                       "spel-overview-"
                       (System/currentTimeMillis) ".png")
            _        (Files/write
                       (Path/of ^String tmp-path (into-array String []))
                       ^bytes (:bytes result)
                       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))]
        {:path tmp-path :size (alength ^bytes (:bytes result)) :refs_annotated (:refs-annotated result)})
      {:path (:path result) :size (:size result) :refs_annotated (:refs-annotated result)})))

(defmethod handle-cmd "debug" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [page-diag    (helpers/debug! (pg))
        ;; Enrich with daemon-tracked console messages
        console-msgs @!console-messages
        console-errs (filterv #(#{"error" "warning"} (:type %)) console-msgs)
        ;; Enrich with tracked page errors
        page-errs    @!page-errors
        ;; Enrich with failed network requests (4xx/5xx)
        net-reqs     @!tracked-requests
        failed-net   (filterv #(>= (long (:status %)) 400) net-reqs)
        ;; Optionally clear after read
        _            (when (get params "clear")
                       (reset! !console-messages [])
                       (reset! !page-errors []))]
    (merge page-diag
      {:console_errors  console-errs
       :page_errors     page-errs
       :failed_requests failed-net
       :summary {:console_error_count  (count console-errs)
                 :page_error_count    (count page-errs)
                 :failed_request_count (count failed-net)
                 :total_issues (+ (count console-errs)
                                 (count page-errs)
                                 (count failed-net))}})))

(defmethod handle-cmd "text-contrast" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/text-contrast! (pg)))

(defmethod handle-cmd "color-palette" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/color-palette! (pg)))

(defmethod handle-cmd "layout-check" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/layout-check! (pg)))

(defmethod handle-cmd "font-audit" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/font-audit! (pg)))

(defmethod handle-cmd "link-health" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/link-health! (pg)))

(defmethod handle-cmd "heading-structure" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (helpers/heading-structure! (pg)))

(defmethod handle-cmd "emulate" [_ params]
  (ensure-browser!)
  (let [device-name (get params "device")
        result      (handle-cmd "set_device" {"device" device-name})]
    (if (:error result)
      result
      (let [overview-opts (cond-> {}
                            (get params "path")                           (assoc :path (get params "path"))
                            (get params "all")                            (assoc :all-frames? true)
                            (contains? params "show-badges")              (assoc :show-badges (get params "show-badges"))
                            (contains? params "show-dimensions")          (assoc :show-dimensions (get params "show-dimensions"))
                            (contains? params "show-boxes")               (assoc :show-boxes (get params "show-boxes")))
            ov-result   (helpers/overview! (pg) overview-opts)]
        (if (:bytes ov-result)
          (let [tmp-path (str (System/getProperty "java.io.tmpdir")
                           java.io.File/separator
                           "spel-emulate-"
                           (System/currentTimeMillis) ".png")
                _        (Files/write
                           (Path/of ^String tmp-path (into-array String []))
                           ^bytes (:bytes ov-result)
                           ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))]
            {:device device-name :preset (:preset result)
             :path tmp-path :size (alength ^bytes (:bytes ov-result)) :refs_annotated (:refs-annotated ov-result)})
          {:device device-name :preset (:preset result)
           :path (:path ov-result) :size (:size ov-result) :refs_annotated (:refs-annotated ov-result)})))))

(defmethod handle-cmd "evaluate" [_ {:strs [script base64]}]
  (ensure-page-loaded!)
  (let [result (page/evaluate (pg) script)]
    (if base64
      {:result (.encodeToString (Base64/getEncoder)
                 (.getBytes (str result) "UTF-8"))}
      {:result result})))

(defmethod handle-cmd "scroll" [_ params]
  (ensure-page-loaded!)
  (let [direction (get params "direction" "down")
        amount    (long (get params "amount" 500))
        sel       (get params "selector")
        smooth?   (get params "smooth" false)
        opts      {:amount amount :smooth? smooth?}
        result    (unwrap-anomaly!
                    (if sel
                      (locator/scroll (resolve-selector sel) direction opts)
                      (page/scroll (pg) direction opts)))
        _         (snapshot-after-action!)]
    result))

(defmethod handle-cmd "back" [_ _]
  (ensure-page-loaded!)
  (page/go-back (pg))
  (snapshot-after-action!)
  {:url (page/url (pg))})

(defmethod handle-cmd "forward" [_ _]
  (ensure-page-loaded!)
  (page/go-forward (pg))
  (snapshot-after-action!)
  {:url (page/url (pg))})

(defmethod handle-cmd "reload" [_ _]
  (ensure-page-loaded!)
  (page/reload (pg))
  (snapshot-after-action!)
  {:url (page/url (pg))})

(defmethod handle-cmd "wait" [_ params]
  (cond
    (get params "text")
    (do (unwrap-anomaly! (page/wait-for-selector (pg) (str "text=" (get params "text"))))
        {:found_text (get params "text")})

    (get params "url")
    (do (unwrap-anomaly! (page/wait-for-url (pg) (get params "url")))
        {:url (get params "url")})

    (get params "function")
    (do (unwrap-anomaly! (page/wait-for-function (pg) (get params "function")))
        {:function_completed true})

    (get params "selector")
    (let [sel (get params "selector")]
      (if (ref? sel)
        (unwrap-anomaly! (locator/wait-for (resolve-selector sel)))
        (unwrap-anomaly! (page/wait-for-selector (pg) sel)))
      {:found sel})

    (get params "state")
    (do (unwrap-anomaly! (page/wait-for-load-state (pg) (keyword (get params "state"))))
        {:state (get params "state")})

    (get params "timeout")
    (do (unwrap-anomaly! (page/wait-for-timeout (pg) (double (get params "timeout"))))
        {:waited (get params "timeout")})

    :else
    {:error "No wait condition specified"}))

(defmethod handle-cmd "tab_new" [_ params]
  (let [new-pg (core/new-page-from-context (ctx))]
    (swap! !state assoc :page new-pg)
    (when-let [url (get params "url")]
      (page/navigate new-pg url))
    {:tab "new" :url (page/url new-pg)}))

(defmethod handle-cmd "tab_list" [_ _]
  (let [pages  (core/context-pages (ctx))
        active (pg)]
    {:tabs (mapv (fn [idx p]
                   {:index idx :url (page/url p) :title (page/title p)
                    :active (= p active)})
             (range) pages)}))

(defmethod handle-cmd "tab_switch" [_ {:strs [index]}]
  (let [pages (core/context-pages (ctx))
        pg-inst (nth pages (int index))]
    (swap! !state assoc :page pg-inst)
    (snapshot-after-action!)
    {:tab index :url (page/url pg-inst)}))

(defmethod handle-cmd "tab_close" [_ _]
  (let [current (pg)]
    (core/close-page! current)
    (let [remaining (core/context-pages (ctx))]
      (when (seq remaining)
        (swap! !state assoc :page (last remaining)))
      {:closed true :remaining (count remaining)})))

(defmethod handle-cmd "url" [_ _]
  {:url (page/url (pg))})

(defmethod handle-cmd "title" [_ _]
  {:title (page/title (pg))})

(defmethod handle-cmd "content" [_ params]
  (ensure-page-loaded!)
  (if-let [sel (get params "selector")]
    {:html (unwrap-anomaly! (locator/inner-html (resolve-selector sel)))}
    {:html (page/content (pg))}))

(defmethod handle-cmd "get_text" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:text (unwrap-anomaly! (locator/text-content (resolve-selector selector)))})

(defmethod handle-cmd "get_attribute" [_ {:strs [selector attribute]}]
  (ensure-page-loaded!)
  {:value (unwrap-anomaly! (locator/get-attribute (resolve-selector selector) attribute))})

(defmethod handle-cmd "is_visible" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:visible (unwrap-anomaly! (locator/is-visible? (resolve-selector selector)))})

(defmethod handle-cmd "is_enabled" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:enabled (unwrap-anomaly! (locator/is-enabled? (resolve-selector selector)))})

(defmethod handle-cmd "is_checked" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:checked (unwrap-anomaly! (locator/is-checked? (resolve-selector selector)))})

;; --- Computed Styles ---

(defmethod handle-cmd "get_styles" [_ {:strs [selector full]}]
  (ensure-page-loaded!)
  (let [loc    (resolve-selector selector)
        styles (unwrap-anomaly! (locator/computed-styles loc (when full {:full true})))]
    {:styles styles :selector (str selector)}))

;; --- Clipboard ---

(defmethod handle-cmd "clipboard_copy" [_ {:strs [text]}]
  (ensure-page-loaded!)
  (page/clipboard-copy (pg) text))

(defmethod handle-cmd "clipboard_read" [_ _]
  (ensure-page-loaded!)
  (page/clipboard-read (pg)))

(defmethod handle-cmd "clipboard_paste" [_ _]
  (ensure-page-loaded!)
  (page/clipboard-paste (pg)))

;; --- Diff Engine ---

(defmethod handle-cmd "diff_snapshot" [_ {:strs [baseline selector compact maxDepth no-network no-console]}]
  (ensure-page-loaded!)
  (let [current-snap (:snapshot (handle-cmd "snapshot"
                                  {"interactive" true
                                   "compact" compact
                                   "maxDepth" maxDepth
                                   "selector" selector
                                   "no-network" no-network
                                   "no-console" no-console}))
        diffs        (snapshot/diff-snapshots (str/trim baseline) (str/trim current-snap))]
    (assoc diffs
      :current current-snap
      :total_lines (max (count (str/split-lines baseline))
                     (count (str/split-lines current-snap))))))

(defmethod handle-cmd "diff_screenshot" [_ {:strs [baseline path threshold]}]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [baseline-bytes (java.nio.file.Files/readAllBytes
                         (java.nio.file.Path/of ^String baseline (into-array String [])))
        current-bytes  (page/screenshot (pg))
        current-snap   (snapshot/capture-snapshot (pg))
        threshold-val  (if threshold
                         (Double/parseDouble (str/replace (str threshold) #"," "."))
                         0.1)
        result         (visual-diff/compare-screenshots baseline-bytes current-bytes
                         :threshold threshold-val
                         :current-refs (:refs current-snap))
        diff-path      (or path
                         (str (System/getProperty "java.io.tmpdir")
                           java.io.File/separator
                           "spel-diff-" (System/currentTimeMillis) ".png"))]
    (java.nio.file.Files/write
      (java.nio.file.Path/of ^String diff-path (into-array String []))
      ^bytes (:diff-image result)
      ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
    (let [raw (-> (dissoc result :diff-image)
                (assoc :diff-path diff-path))]
      {:matched             (:matched raw)
       :diff_count          (:diff-count raw)
       :total_pixels        (:total-pixels raw)
       :diff_percent        (:diff-percent raw)
       :width               (:width raw)
       :height              (:height raw)
       :diff_path           (:diff-path raw)
       :regions             (mapv (fn [r]
                                    (cond-> {:id (:id r)
                                             :label (:label r)
                                             :pixels (:pixels r)
                                             :bounding_box (:bounding-box r)}
                                      (:element r) (assoc :element (:element r))
                                      (:elements r) (assoc :elements (:elements r))
                                      (:semantic-label r) (assoc :semantic_label (:semantic-label r))))
                              (:regions result))
       :baseline_dimensions (:baseline-dimensions raw)
       :current_dimensions  (:current-dimensions raw)
       :dimension_mismatch  (:dimension-mismatch raw)})))

(defmethod handle-cmd "count" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:count (unwrap-anomaly! (locator/count-elements (page/locator (pg) selector)))})

(defmethod handle-cmd "bounding_box" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:box (unwrap-anomaly! (locator/bounding-box (resolve-selector selector)))})

(defmethod handle-cmd "pdf" [_ {:strs [path]}]
  (ensure-page-loaded!)
  (page/pdf (pg) {:path path})
  {:path path})

;; --- Phase 1: Core Gaps ---

(defmethod handle-cmd "keydown" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (input/key-down (page/page-keyboard (pg)) key)
  {:keydown key})

(defmethod handle-cmd "keyup" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (input/key-up (page/page-keyboard (pg)) key)
  {:keyup key})

(defmethod handle-cmd "scrollintoview" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/scroll-into-view (resolve-selector selector))
  (snapshot-after-action!)
  {:scrolled_into_view selector})

(defmethod handle-cmd "find_scrollable" [_ _params]
  (ensure-page-loaded!)
  {:elements (page/find-scrollable (pg))})

(defmethod handle-cmd "scroll_position" [_ _params]
  (ensure-page-loaded!)
  (page/scroll-position (pg)))

(defmethod handle-cmd "drag" [_ {:strs [source target force steps timeout
                                        source-position target-position]}]
  (ensure-page-loaded!)
  (let [src-loc    (resolve-selector source)
        tgt-loc    (resolve-selector target)
        opts       (cond-> {}
                     (some? force)           (assoc :force (boolean force))
                     (some? steps)           (assoc :steps (long steps))
                     (some? timeout)         (assoc :timeout (double timeout))
                     (some? source-position) (assoc :source-position source-position)
                     (some? target-position) (assoc :target-position target-position))]
    (if (seq opts)
      (unwrap-anomaly! (locator/drag-to src-loc tgt-loc opts))
      (unwrap-anomaly! (locator/drag-to src-loc tgt-loc)))
    (snapshot-after-action!)
    {:dragged {:from source :to target}}))

(defmethod handle-cmd "drag-by" [_ {:strs [selector dx dy steps]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        opts (when steps {:steps (long steps)})]
    (unwrap-anomaly! (locator/drag-by (pg) loc dx dy opts))
    (snapshot-after-action!)
    {:dragged_by {:selector selector :dx dx :dy dy}}))

(defmethod handle-cmd "upload" [_ {:strs [selector files]}]
  (ensure-page-loaded!)
  (let [file-paths (if (string? files) [files] files)]
    (locator/set-input-files! (resolve-selector selector) file-paths)
    (snapshot-after-action!)
    {:uploaded {:selector selector :files file-paths}}))

(defmethod handle-cmd "get_value" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:value (locator/input-value (resolve-selector selector))})

(defmethod handle-cmd "get_count" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:count (locator/count-elements (page/locator (pg) selector))})

(defmethod handle-cmd "get_box" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:box (locator/bounding-box (resolve-selector selector))})

(defmethod handle-cmd "highlight" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (locator/highlight loc)
        desc (describe-element loc)]
    (cond-> {:highlighted selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "find" [_ {:strs [by value find_action find_value name exact selector]}]
  (ensure-page-loaded!)
  (let [loc (case by
              "role"        (if name
                               ;; Use role selector string to avoid GetByRoleOptions reflection issues in native-image
                              (let [name-part (if exact
                                                (str "[name=\"" name "\" s]")
                                                (str "[name=/" name "/i]"))]
                                (page/locator (pg) (str "role=" value name-part)))
                              (page/get-by-role (pg) (str->aria-role value)))
              "text"        (page/get-by-text (pg) value)
              "label"       (page/get-by-label (pg) value)
              "placeholder" (page/get-by-placeholder (pg) value)
              "alt"         (page/get-by-alt-text (pg) value)
              "title"       (page/get-by-title (pg) value)
              "testid"      (page/get-by-test-id (pg) value)
              "first"       (locator/first-element (resolve-selector value))
              "last"        (locator/last-element (resolve-selector value))
              "nth"         (locator/nth-element (resolve-selector selector)
                              (Integer/parseInt value))
              (throw (ex-info (str "Unknown find type: " by) {})))]
    (case find_action
      "click"   (do (locator/click loc)
                    (snapshot-after-action!)
                    {:found by :value value :action "click"})
      "fill"    (do (locator/fill loc find_value)
                    (snapshot-after-action!)
                    {:found by :value value :action "fill"})
      "type"    (do (locator/type-text loc find_value)
                    {:found by :value value :action "type"})
      "check"   (do (locator/check loc) {:found by :value value :action "check"})
      "uncheck" (do (locator/uncheck loc) {:found by :value value :action "uncheck"})
      "hover"   (do (locator/hover loc) {:found by :value value :action "hover"})
      "focus"   (do (locator/focus loc) {:found by :value value :action "focus"})
      "text"    {:found by :value value :text (locator/text-content loc)}
      "count"   {:found by :value value :count (locator/count-elements loc)}
      "visible" {:found by :value value :visible (locator/is-visible? loc)}
      (nil)     {:found by :value value :count (locator/count-elements loc)}
      {:error (str "Unknown find action: " find_action)})))

;; --- Phase 2: Mouse Control ---

(defmethod handle-cmd "mouse_move" [_ {:strs [x y]}]
  (input/mouse-move (page/page-mouse (pg)) (double x) (double y))
  {:moved {:x x :y y}})

(defmethod handle-cmd "mouse_down" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (input/mouse-down m)
      (.down m (options/->mouse-down-options {:button (keyword btn)})))
    {:mouse_down btn}))

(defmethod handle-cmd "mouse_up" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (input/mouse-up m)
      (.up m (options/->mouse-up-options {:button (keyword btn)})))
    {:mouse_up btn}))

(defmethod handle-cmd "mouse_wheel" [_ {:strs [deltaX deltaY]}]
  (input/mouse-wheel (page/page-mouse (pg))
    (double (or deltaX 0))
    (double (or deltaY 0)))
  {:wheel {:dx (or deltaX 0) :dy (or deltaY 0)}})

;; --- Phase 2: Browser Settings ---

(defmethod handle-cmd "set_viewport" [_ {:strs [width height]}]
  (page/set-viewport-size! (pg) (long width) (long height))
  {:viewport {:width width :height height}})

(defmethod handle-cmd "set_offline" [_ {:strs [enabled]}]
  (let [offline (if (nil? enabled) true (boolean enabled))]
    (core/context-set-offline! (ctx) offline)
    {:offline offline}))

(defmethod handle-cmd "set_headers" [_ {:strs [headers]}]
  (page/set-extra-http-headers! (pg) headers)
  {:headers_set true})

(defmethod handle-cmd "set_media" [_ {:strs [colorScheme]}]
  (let [scheme (case colorScheme
                 ("dark" "Dark")       :dark
                 ("light" "Light")     :light
                 ("no-preference")     :no-preference
                 :no-preference)]
    (page/emulate-media! (pg) {:color-scheme scheme})
    {:media {:colorScheme colorScheme}}))

(defmethod handle-cmd "set_device" [_ {:strs [device]}]
  (let [preset (devices/resolve-device-by-name device)]
    (if preset
      (let [current-url  (try (page/url (pg)) (catch Exception _ nil))
            browser-type (get-in @!state [:launch-flags "browser"] "chromium")
            ctx-opts     (if (= "firefox" browser-type)
                           (dissoc preset :is-mobile)
                           preset)]
        (save-inflight-trace!)
        (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
        (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
        (let [new-ctx (check-anomaly!
                        (core/new-context (:browser @!state) ctx-opts)
                        "Failed to create device context")
              new-pg  (check-anomaly!
                        (core/new-page-from-context new-ctx)
                        "Failed to create page for device")]
          (swap! !state assoc :context new-ctx :page new-pg :tracing? false :device device)
          (reset! !console-messages [])
          (page/on-console new-pg (fn [msg]
                                    (swap! !console-messages conj
                                      {:type (.type ^ConsoleMessage msg)
                                       :text (.text ^ConsoleMessage msg)})))
          (reset! !page-errors [])
          (page/on-page-error new-pg (fn [error]
                                       (swap! !page-errors conj
                                         {:message (str error)})))
          (reset! !tracked-requests [])
          (page/on-response new-pg track-response!)
          (when current-url (page/navigate new-pg current-url))
          {:device device :preset preset}))
      {:error (str "Unknown device: " device
                ". Available: " (clojure.string/join ", " (devices/available-device-names)))})))

(defmethod handle-cmd "set_geo" [_ {:strs [latitude longitude accuracy]}]
  (core/context-grant-permissions! (ctx) ["geolocation"])
  (.setGeolocation ^BrowserContext (ctx)
    (doto (Geolocation. (double latitude) (double longitude))
      (.setAccuracy (double (or accuracy 1)))))
  {:geolocation {:latitude latitude :longitude longitude}})

(defmethod handle-cmd "set_credentials" [_ {:strs [username password]}]
  ;; HTTP credentials require recreating the context
  (let [current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (check-anomaly!
                    (core/new-context (:browser @!state)
                      {:http-credentials {:username username :password password}})
                    "Failed to create context with credentials")
          new-pg  (check-anomaly!
                    (core/new-page-from-context new-ctx)
                    "Failed to create page with credentials")]
      (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
      (reset! !console-messages [])
      (page/on-console new-pg (fn [msg]
                                (swap! !console-messages conj
                                  {:type (.type ^ConsoleMessage msg)
                                   :text (.text ^ConsoleMessage msg)})))
      (reset! !page-errors [])
      (page/on-page-error new-pg (fn [error]
                                   (swap! !page-errors conj
                                     {:message (str error)})))
      (reset! !tracked-requests [])
      (page/on-response new-pg track-response!)
      (when current-url (page/navigate new-pg current-url))
      {:credentials_set true})))

;; --- Phase 3: Cookies ---

(defmethod handle-cmd "cookies_get" [_ {:strs [urls]}]
  (let [cookies (if urls
                  (mapv core/cookie->map
                    (.cookies ^BrowserContext (ctx)
                      (java.util.ArrayList. ^java.util.Collection (vec urls))))
                  (core/context-cookies (ctx)))]
    {:cookies cookies}))

(defmethod handle-cmd "cookies_set" [_ {:strs [name value domain path url]}]
  (let [cookie (Cookie. name value)]
    (if domain
      (do (.setDomain cookie domain)
          (.setPath cookie (or path "/")))
      (.setUrl cookie (or url (page/url (pg)))))
    (let [cookie-list (java.util.Collections/singletonList cookie)]
      (.addCookies ^BrowserContext (ctx) cookie-list))
    {:cookie_set {:name name :value value}}))

(defmethod handle-cmd "cookies_clear" [_ _]
  (core/context-clear-cookies! (ctx))
  {:cookies_cleared true})

;; --- Phase 3: Storage ---

(defmethod handle-cmd "storage_get" [_ {:strs [type key]}]
  (let [st (or type "local")
        js (if key
             (str st "Storage.getItem('" key "')")
             (str "JSON.stringify(Object.entries(" st "Storage))"))]
    {:storage (page/evaluate (pg) js)}))

(defmethod handle-cmd "storage_set" [_ {:strs [type key value]}]
  (let [st (or type "local")]
    (page/evaluate (pg) (str st "Storage.setItem('" key "', '" value "')"))
    {:storage_set {:key key :value value}}))

(defmethod handle-cmd "storage_clear" [_ {:strs [type]}]
  (let [st (or type "local")]
    (page/evaluate (pg) (str st "Storage.clear()"))
    {:storage_cleared st}))

;; --- Phase 3: Network ---

(defmethod handle-cmd "network_get_ref" [_ {:strs [ref]}]
  (let [ref-id (str/replace (or ref "") #"^@" "")]
    (if-let [entry (get @!network-full ref-id)]
      entry
      {:error (str "Network ref @" ref-id " not found")})))

(defmethod handle-cmd "console_get_ref" [_ {:strs [ref]}]
  (let [ref-id (str/replace (or ref "") #"^@" "")]
    (if-let [entry (get @!console-full ref-id)]
      entry
      {:error (str "Console ref @" ref-id " not found")})))

(defmethod handle-cmd "pages_list" [_ _]
  {:pages @!pages})

(defmethod handle-cmd "pages_get_ref" [_ {:strs [ref]}]
  (let [ref-id (str/replace (or ref "") #"^@" "")]
    (if-let [entry (some #(when (= (:ref %) (str "@" ref-id)) %) @!pages)]
      entry
      {:error (str "Page ref @" ref-id " not found")})))

(defmethod handle-cmd "network_list" [_ _]
  {:entries @!network-window})

(defmethod handle-cmd "console_list" [_ _]
  {:entries @!console-window})

(defmethod handle-cmd "network_route" [_ {:strs [url action_type body status content_type]}]
  (let [handler (fn [route]
                  (case action_type
                    "abort"   (network/route-abort! route)
                    "fulfill" (network/route-fulfill! route
                                (cond-> {}
                                  status       (assoc :status (long status))
                                  body         (assoc :body body)
                                  content_type (assoc :content-type content_type)))
                    ;; default: continue
                    (network/route-continue! route)))]
    (page/route! (pg) url handler)
    (swap! !routes assoc url handler)
    (when (and (:cdp-connected @!state) (current-cdp-url))
      (write-cdp-route-lock! (current-cdp-url) (:session @!state)))
    {:route_added url}))

(defmethod handle-cmd "network_unroute" [_ {:strs [url]}]
  (if url
    (do (page/unroute! (pg) url)
        (swap! !routes dissoc url)
        (when (empty? @!routes)
          (release-cdp-route-lock-if-owned!))
        {:route_removed url})
    (do (doseq [[u _] @!routes]
          (page/unroute! (pg) u))
        (reset! !routes {})
        (release-cdp-route-lock-if-owned!)
        {:all_routes_removed true})))

(defmethod handle-cmd "network_requests" [_ {:strs [filter type method status]}]
  (let [reqs     @!tracked-requests
        filtered (cond->> reqs
                   filter (filterv #(re-find (re-pattern filter) (str (:url %))))
                   type   (filterv #(= (:resource-type %) type))
                   method (filterv #(= (str/upper-case (:method %)) (str/upper-case method)))
                   status (filterv #(str/starts-with? (str (:status %)) status)))]
    {:requests filtered}))

(defmethod handle-cmd "network_clear" [_ _]
  (reset! !tracked-requests [])
  {:network "cleared"})

;; --- Phase 4: Frames ---

(defmethod handle-cmd "frame_switch" [_ {:strs [selector]}]
  (if (= selector "main")
    {:frame "main"}
    (let [frames (page/frames (pg))
          target (or (page/frame-by-name (pg) selector)
                   (some #(when (str/includes? (.url ^Frame %) selector) %) frames))]
      (if target
        {:frame selector :url (.url ^Frame target)}
        {:error (str "Frame not found: " selector)}))))

(defmethod handle-cmd "frame_list" [_ _]
  {:frames (mapv (fn [f]
                   {:name (.name ^Frame f) :url (.url ^Frame f)})
             (page/frames (pg)))})

;; --- Phase 4: Dialogs ---

(defmethod handle-cmd "dialog_accept" [_ {:strs [text]}]
  (when-let [old @!dialog-handler]
    (.offDialog ^Page (pg) old))
  (let [handler (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (.accept ^Dialog dialog (or text ""))))]
    (reset! !dialog-handler handler)
    (.onDialog ^Page (pg) handler))
  {:dialog_handler "accept" :text text})

(defmethod handle-cmd "dialog_dismiss" [_ _]
  (when-let [old @!dialog-handler]
    (.offDialog ^Page (pg) old))
  (let [handler (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (.dismiss ^Dialog dialog)))]
    (reset! !dialog-handler handler)
    (.onDialog ^Page (pg) handler))
  {:dialog_handler "dismiss"})

;; --- Phase 4: Debug ---

(defmethod handle-cmd "trace_start" [_ {:strs [name]}]
  (core/tracing-start! (core/context-tracing (ctx))
    (cond-> {:screenshots true :snapshots true}
      name (assoc :name name)))
  (swap! !state assoc :tracing? true)
  {:trace "started" :name name})

(defmethod handle-cmd "trace_stop" [_ {:strs [path]}]
  (let [out-path (or path "trace.zip")]
    (core/tracing-stop! (core/context-tracing (ctx)) {:path out-path})
    (swap! !state assoc :tracing? false)
    {:trace "stopped" :path out-path}))

(defmethod handle-cmd "console_get" [_ {:strs [clear]}]
  (let [msgs @!console-messages]
    (when clear (reset! !console-messages []))
    {:messages msgs}))

(defmethod handle-cmd "console_clear" [_ _]
  (reset! !console-messages [])
  {:console "cleared"})

(defmethod handle-cmd "errors_get" [_ {:strs [clear]}]
  (let [errs @!page-errors]
    (when clear (reset! !page-errors []))
    {:errors errs}))

(defmethod handle-cmd "errors_clear" [_ _]
  (reset! !page-errors [])
  {:errors "cleared"})

(defmethod handle-cmd "console_start" [_ _]
  (page/on-console (pg) (fn [msg]
                          (swap! !console-messages conj
                            {:type (.type ^ConsoleMessage msg)
                             :text (.text ^ConsoleMessage msg)})))
  {:console "listening"})

(defmethod handle-cmd "errors_start" [_ _]
  (page/on-page-error (pg) (fn [error]
                             (swap! !page-errors conj
                               {:message (str error)})))
  {:errors "listening"})

;; --- Phase 4: State Management ---

(defmethod handle-cmd "state_save" [_ {:strs [path]}]
  (let [save-path (or path (str "state-" (:session @!state) ".json"))]
    (.storageState ^BrowserContext (ctx)
      (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
        (.setPath (Path/of save-path (into-array String [])))))
    {:state "saved" :path save-path}))

(defmethod handle-cmd "state_load" [_ {:strs [path]}]
  (let [state-path (or path (str "state-" (:session @!state) ".json"))
        current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (core/new-context (:browser @!state) {:storage-state state-path})]
      (if (anomaly/anomaly? new-ctx)
        {:error (str "Failed to load state: " (:anomaly/message new-ctx))}
        (let [new-pg (core/new-page-from-context new-ctx)]
          (if (anomaly/anomaly? new-pg)
            (do (.close ^BrowserContext new-ctx)
                {:error (str "Failed to create page: " (:anomaly/message new-pg))})
            (do
              (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
               ;; Re-register console, error, and request listeners on new page
              (reset! !console-messages [])
              (page/on-console new-pg (fn [msg]
                                        (swap! !console-messages conj
                                          {:type (.type ^ConsoleMessage msg)
                                           :text (.text ^ConsoleMessage msg)})))
              (reset! !page-errors [])
              (page/on-page-error new-pg (fn [error]
                                           (swap! !page-errors conj
                                             {:message (str error)})))
              (reset! !tracked-requests [])
              (page/on-response new-pg track-response!)
              (when current-url (page/navigate new-pg current-url))
              {:state "loaded" :path state-path})))))))

(defmethod handle-cmd "state_list" [_ _]
  (let [dir (java.io.File. ".")
        files (->> (.listFiles dir)
                (filter (fn [^File f] (and (.isFile f)
                                        (str/ends-with? (.getName f) ".json")
                                        (str/starts-with? (.getName f) "state-")))))]
    {:states (mapv (fn [^File f] (.getName f)) files)}))

(defmethod handle-cmd "state_show" [_ {:strs [file]}]
  (let [content (String. ^bytes (Files/readAllBytes (Path/of ^String file (into-array String []))))]
    {:state (json/read-json content)}))

(defmethod handle-cmd "state_rename" [_ {:strs [old_name new_name]}]
  (Files/move (Path/of ^String old_name (into-array String []))
    (Path/of ^String new_name (into-array String []))
    ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption []))
  {:renamed {:from old_name :to new_name}})

(defmethod handle-cmd "state_clear" [_ {:strs [name all]}]
  (if all
    (let [dir (java.io.File. ".")
          files (->> (.listFiles dir)
                  (filter (fn [^File f] (and (.isFile f)
                                          (str/ends-with? (.getName f) ".json")
                                          (str/starts-with? (.getName f) "state-")))))]
      (doseq [^File f files] (.delete f))
      {:cleared (count files)})
    (let [file-name ^String (or name (str "state-" (:session @!state) ".json"))]
      (Files/deleteIfExists (Path/of file-name (into-array String [])))
      {:cleared file-name})))

(defmethod handle-cmd "state_clean" [_ {:strs [older_than_days]}]
  (let [days     (long (or older_than_days 30))
        cutoff   (- (System/currentTimeMillis) (* days 24 60 60 1000))
        dir      (java.io.File. ".")
        files    (->> (.listFiles dir)
                   (filter (fn [^File f] (and (.isFile f)
                                           (str/ends-with? (.getName f) ".json")
                                           (str/starts-with? (.getName f) "state-")
                                           (< (.lastModified f) ^long cutoff)))))]
    (doseq [^File f files] (.delete f))
    {:cleaned (count files) :older_than_days days}))

;; --- Phase 5: Sessions ---

(defmethod handle-cmd "session_list" [_ _]
  (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir"))
        socks   (->> (.listFiles tmp-dir)
                  (filter (fn [^File f]
                            (and (.exists f)
                              (str/starts-with? (.getName f) "spel-")
                              (str/ends-with? (.getName f) ".sock")))))]
    {:sessions (mapv (fn [^File f]
                       (let [n (-> (.getName f)
                                 (str/replace "spel-" "")
                                 (str/replace ".sock" ""))]
                         {:name n :socket (.getAbsolutePath f)}))
                 socks)
     :current (:session @!state)}))

(defmethod handle-cmd "session_info" [_ _]
  {:session    (:session @!state)
   :headless   (:headless @!state)
   :persist    (persist-enabled?)
   :tracing    (:tracing? @!state)
   :url        (try (page/url (pg)) (catch Exception _ nil))
   :title      (try (page/title (pg)) (catch Exception _ nil))
   :refs_count (:counter @!state)})

;; --- CDP idle timeout scheduling ---

(defn- cancel-cdp-idle-shutdown!
  "Cancels any pending CDP idle shutdown timer."
  []
  (when-let [^ScheduledFuture fut @!cdp-idle-future]
    (.cancel fut false)
    (reset! !cdp-idle-future nil)))

(defn- schedule-cdp-idle-shutdown!
  "Schedules daemon auto-shutdown after CDP idle timeout.
   Called when CDP disconnects. Cancelled on reconnect, close, or manual cancel.
   Does nothing if timeout is 0 (disabled) or if there was no active CDP connection."
  []
  (cancel-cdp-idle-shutdown!)
  (let [timeout-ms (long @!cdp-idle-timeout-ms)]
    (when (pos? timeout-ms)
      (let [fut (.schedule !cdp-idle-scheduler
                  ^Runnable (fn cdp-idle-shutdown []
                              (binding [*out* *err*]
                                (println (str "spel: CDP idle timeout ("
                                           (quot timeout-ms 60000)
                                           " min) — no reconnect, shutting down daemon")))
                              (stop-daemon!))
                  timeout-ms
                  TimeUnit/MILLISECONDS)]
        (reset! !cdp-idle-future fut)))))

;; --- Session idle timeout scheduling ---

(defn- cancel-session-idle-shutdown!
  "Cancels any pending session idle shutdown timer."
  []
  (when-let [^ScheduledFuture fut @!session-idle-future]
    (.cancel fut false)
    (reset! !session-idle-future nil)))

(defn- schedule-session-idle-shutdown!
  "Schedules daemon auto-shutdown after session idle timeout.
   Called on daemon start and reset on every command.
   Does nothing if timeout is 0 (disabled)."
  []
  (cancel-session-idle-shutdown!)
  (let [timeout-ms (long @!session-idle-timeout-ms)]
    (when (pos? timeout-ms)
      (let [fut (.schedule !session-idle-scheduler
                  ^Runnable (fn session-idle-shutdown []
                              (binding [*out* *err*]
                                (println (str "spel: session idle timeout ("
                                           (quot timeout-ms 60000)
                                           " min) — no commands received, shutting down daemon")))
                              (stop-daemon!))
                  timeout-ms
                  TimeUnit/MILLISECONDS)]
        (reset! !session-idle-future fut)))))

;; --- Phase 5: Connect CDP ---

(defn- connect-cdp!
  "Connects daemon state to a CDP endpoint and returns connection payload."
  [^String url]
  (cancel-cdp-idle-shutdown!)
  (when (str/blank? url)
    (throw (ex-info "CDP URL is required. Usage: spel connect <url>" {:error_code "cdp_url_required"})))
  (let [warning-payload (cdp-route-lock-warning url)
        pw (or (:pw @!state) (core/create))
        browser (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String url)
        contexts (.contexts ^com.microsoft.playwright.Browser browser)
        context (if (seq contexts) (first contexts) (core/new-context browser))
        pages (core/context-pages context)
        pg-inst (if (seq pages) (first pages) (core/new-page-from-context context))]
    (swap! !state assoc :pw pw :browser browser :context context :page pg-inst :cdp-connected true)
    (swap! !state assoc-in [:launch-flags "cdp"] url)
    (persist-launch-flags!)
    ;; Auto-register console, error, and request listeners
    (reset! !console-messages [])
    (page/on-console pg-inst (fn [msg]
                               (swap! !console-messages conj
                                 {:type (.type ^ConsoleMessage msg)
                                  :text (.text ^ConsoleMessage msg)})))
    (reset! !page-errors [])
    (page/on-page-error pg-inst (fn [error]
                                  (swap! !page-errors conj
                                    {:message (str error)})))
    (reset! !tracked-requests [])
    (page/on-response pg-inst track-response!)
    (cond-> {:connected url :url (page/url pg-inst)}
      warning-payload (merge warning-payload))))

(defn- disconnect-cdp!
  "Disconnects current CDP browser connection while preserving launch flags.
   This is a temporary detach operation used by anti-CDP evasion workflows."
  []
  (let [{:keys [cdp-connected page context browser pw]} @!state
        cdp-url (current-cdp-url)]
    (when cdp-connected
      (when page
        (try (core/close-page! page) (catch Exception e (warn "cdp-disconnect-close-page" e))))
      (when context
        (try (.close ^BrowserContext context) (catch Exception e (warn "cdp-disconnect-close-context" e))))
      (when browser
        (try (core/close-browser! browser) (catch Exception e (warn "cdp-disconnect-close-browser" e))))
      (when pw
        (try (core/close! pw) (catch Exception e (warn "cdp-disconnect-close-playwright" e))))
      (release-cdp-route-lock-if-owned!))
    (swap! !state assoc :pw nil :browser nil :context nil :page nil :cdp-connected false)
    ;; Start idle shutdown timer only when we actually disconnected a CDP session
    (when cdp-connected
      (schedule-cdp-idle-shutdown!))
    {:disconnected (boolean cdp-connected)
     :cdp cdp-url}))

(defmethod handle-cmd "connect" [_ {:strs [url]}]
  (connect-cdp! url))

(defmethod handle-cmd "cdp_disconnect" [_ _]
  (disconnect-cdp!))

(defmethod handle-cmd "cdp_reconnect" [_ {:strs [url]}]
  (let [target-url (or url (current-cdp-url))]
    (when (str/blank? target-url)
      (throw (ex-info "No previous CDP connection found. Connect first: spel connect <url>" {:error_code "cdp_url_required"})))
    (disconnect-cdp!)
    (assoc (connect-cdp! target-url) :reconnected true)))

(defmethod handle-cmd "find_free_port" [_ _]
  {:port (unwrap-anomaly! (core/find-free-port))})

;; --- SCI Eval ---

;; Cached SCI evaluation context. Created once per daemon lifetime and reused
;; across eval invocations so that def'd vars persist between calls.
(defonce ^:private !sci-ctx (atom nil))

(defn- sci-cdp-disconnect-handler
  "CDP disconnect handler for SCI eval — disconnects and syncs nil state to SCI atoms."
  []
  (let [result (disconnect-cdp!)]
    (reset! sci-env/!pw nil)
    (reset! sci-env/!browser nil)
    (reset! sci-env/!context nil)
    (reset! sci-env/!page nil)
    result))

(defn- sci-cdp-reconnect-handler
  "CDP reconnect handler for SCI eval — reconnects using last known CDP URL (or explicit override), syncs new state to SCI atoms."
  [url]
  (let [target-url (or url (current-cdp-url))]
    (when (str/blank? target-url)
      (throw (ex-info "No previous CDP connection found. Connect first: spel connect <url>" {:error_code "cdp_url_required"})))
    (disconnect-cdp!)
    (let [result (connect-cdp! target-url)
          st @!state]
      (reset! sci-env/!pw (:pw st))
      (reset! sci-env/!browser (:browser st))
      (reset! sci-env/!context (:context st))
      (reset! sci-env/!page (:page st))
      (assoc result :reconnected true))))

(defn- sync-state-to-sci!
  "Copies daemon's Playwright objects into SCI atoms so user code sees them."
  []
  (reset! sci-env/!daemon-mode? true)
  (let [st @!state]
    (reset! sci-env/!pw (:pw st))
    (reset! sci-env/!browser (:browser st))
    (reset! sci-env/!context (:context st))
    (reset! sci-env/!page (:page st))
    (reset! sci-env/!device (:device st)))
  ;; Install CDP handlers so eval-sci scripts can call (spel/cdp-disconnect) / (spel/cdp-reconnect)
  (reset! sci-env/!cdp-disconnect-handler sci-cdp-disconnect-handler)
  (reset! sci-env/!cdp-reconnect-handler sci-cdp-reconnect-handler)
  ;; Sync CDP idle timeout value and setter
  (reset! sci-env/!cdp-idle-timeout-ms @!cdp-idle-timeout-ms)
  (reset! sci-env/!set-cdp-idle-timeout-handler
    (fn set-cdp-idle-timeout-handler [ms]
      (reset! !cdp-idle-timeout-ms ms)
      (reset! sci-env/!cdp-idle-timeout-ms ms)
      ms))
  ;; Sync CDP lock wait value and setter
  (reset! sci-env/!cdp-lock-wait-s @!cdp-lock-wait-s)
  (reset! sci-env/!set-cdp-lock-wait-handler
    (fn set-cdp-lock-wait-handler [s]
      (reset! !cdp-lock-wait-s s)
      (reset! sci-env/!cdp-lock-wait-s s)
      s))
  ;; Sync session idle timeout value and setter
  (reset! sci-env/!session-idle-timeout-ms @!session-idle-timeout-ms)
  (reset! sci-env/!set-session-idle-timeout-handler
    (fn set-session-idle-timeout-handler [ms]
      (reset! !session-idle-timeout-ms ms)
      (reset! sci-env/!session-idle-timeout-ms ms)
      ;; Reset the timer immediately with the new value
      (schedule-session-idle-shutdown!)
      ms)))

(defn- sync-sci-to-state!
  "After SCI eval, syncs SCI atoms back to daemon state in case user code
   changed the page (e.g. navigated, opened new tab)."
  []
  (swap! !state assoc
    :page @sci-env/!page
    :context @sci-env/!context))

(defmethod handle-cmd "sci_eval" [_ params]
  (ensure-browser!)
  (sync-state-to-sci!)
  (let [code (get params "code")
        args-vec (when-let [args (get params "args")]
                   (mapv str args))]
    (when-not code
      (throw (ex-info "sci_eval requires a 'code' parameter" {})))
    (let [ctx (or @!sci-ctx
                (let [c (sci-env/create-sci-ctx)]
                  (reset! !sci-ctx c)
                  c))
          ;; Capture stdout and stderr during evaluation so println/prn work in eval-sci mode
          stdout-writer (java.io.StringWriter.)
          stderr-writer (java.io.StringWriter.)
          ;; Snapshot console/error state before eval to detect new messages
          console-before (count @!console-messages)
          errors-before  (count @!page-errors)]
      (sci-env/set-throw-on-error! true)
      (try
        (let [result (binding [*out* stdout-writer
                               *err* stderr-writer]
                       (sci/binding [sci-env/sci-command-line-args-var args-vec]
                         (let [r (sci-env/eval-string ctx code)]
                           (sync-sci-to-state!)
                           r)))
              captured-stdout (str stdout-writer)
              captured-stderr (str stderr-writer)
              ;; Collect NEW console messages and page errors from this eval
              new-console (subvec @!console-messages console-before)
              new-errors  (subvec @!page-errors errors-before)]
          (if (anomaly/anomaly? result)
            (cond-> (error-response (::anomaly/message result))
              (seq captured-stdout) (assoc :stdout captured-stdout)
              (seq captured-stderr) (assoc :stderr captured-stderr)
              (seq new-console)     (assoc :console new-console)
              (seq new-errors)      (assoc :page-errors new-errors))
            (let [base (cond-> {:result (pr-str result)}
                         (seq captured-stdout) (assoc :stdout captured-stdout)
                         (seq captured-stderr) (assoc :stderr captured-stderr)
                         (seq new-console)     (assoc :console new-console)
                         (seq new-errors)      (assoc :page-errors new-errors))]
              ;; If result looks like a snapshot map, include formatted data
              ;; so the CLI can display tree + metadata instead of raw EDN.
              (if (and (map? result) (:tree result))
                (cond-> (assoc base :snapshot (:tree result)
                          :url (:url result)
                          :title (:title result))
                  (:description result) (assoc :description (:description result)))
                base))))
        (catch Exception e
          (sync-sci-to-state!)
          (let [captured-stdout (str stdout-writer)
                captured-stderr (str stderr-writer)
                new-console (subvec @!console-messages console-before)
                new-errors  (subvec @!page-errors errors-before)]
            (cond-> (error-response (.getMessage e))
              (seq captured-stdout) (assoc :stdout captured-stdout)
              (seq captured-stderr) (assoc :stderr captured-stderr)
              (seq new-console)     (assoc :console new-console)
              (seq new-errors)      (assoc :page-errors new-errors))))))))

;; --- Close & Default ---

(defmethod handle-cmd "close" [_ _]
  ;; Auto-save session state (unless --no-persist)
  (auto-save-session-state!)
  ;; Note: in-flight trace is saved by stop-daemon! (called after this returns)
  (cond-> {:closed true :shutdown true}
    (:tracing? @!state) (assoc :trace-warning "active trace will be auto-saved on shutdown")))

(defmethod handle-cmd :default [action _]
  {:error (str "Unknown action: " action)})

;; =============================================================================
;; Protocol
;; =============================================================================

(defn- reflection-error-hint
  "Detects GraalVM reflection errors from Gson/UnsafeAllocator failures and
   returns a user-friendly message with the offending class name. Returns nil
   if the exception is not reflection-related."
  [^Throwable e]
  (let [msgs (loop [^Throwable t e, acc []]
               (if t
                 (recur (.getCause t) (conj acc (or (.getMessage t) "")))
                 acc))
        combined (str/join " " msgs)]
    (when (or (str/includes? combined "Unable to invoke no-args constructor")
            (str/includes? combined "UnsafeAllocator")
            (str/includes? combined "InstantiationException")
            (and (str/includes? combined "reflection")
              (str/includes? combined "registered")))
      (let [class-name (second (re-find #"for class ([\w.$]+)" combined))]
        (str (.getMessage e)
          "\n\n[GraalVM native-image] "
          (if class-name
            (str "Class '" class-name "' needs reflection registration. "
              "Add to reflect-config.json: "
              "{\"name\": \"" class-name "\", \"unsafeAllocated\": true, "
              "\"allDeclaredFields\": true, \"allDeclaredConstructors\": true, "
              "\"allDeclaredMethods\": true}")
            "A class may need reflection registration in reflect-config.json with \"unsafeAllocated\": true"))))))

;; =============================================================================
;; Action Log Commands
;; =============================================================================

(defmethod handle-cmd "action_log" [_ _params]
  {:entries @sci-env/!action-log
   :count   (count @sci-env/!action-log)
   :start   @sci-env/!action-log-start})

(defmethod handle-cmd "action_log_srt" [_ params]
  (let [opts (cond-> {}
               (get params "min-duration-ms")
               (assoc :min-duration-ms (long (get params "min-duration-ms")))
               (get params "max-duration-ms")
               (assoc :max-duration-ms (long (get params "max-duration-ms"))))]
    {:srt (action-log/actions->srt @sci-env/!action-log opts)}))

(defmethod handle-cmd "action_log_clear" [_ _params]
  (reset! sci-env/!action-log [])
  (reset! sci-env/!action-counter 0)
  (reset! sci-env/!action-log-start 0)
  {:cleared true})

(defn- process-command
  "Processes a single JSON command string. Returns a JSON response string."
  [^String line]
  (try
    (let [raw-cmd (json/read-json line)
          cmd     (if (map? raw-cmd)
                    (reduce-kv (fn [m k v]
                                 (assoc m (if (keyword? k) (name k) k) v))
                      {}
                      raw-cmd)
                    raw-cmd)
          action  (get cmd "action")
          flags   (get cmd "_flags")
          params  (dissoc cmd "action" "_flags")]
      ;; Reset session idle timer — any command counts as activity
      (schedule-session-idle-shutdown!)
      ;; Store launch flags if present (used by ensure-browser!)
      ;; Persist to disk so CLI can recover them on daemon restart.
      (when (seq flags)
        (swap! !state update :launch-flags merge flags)
        (persist-launch-flags!))
      (if-let [{:keys [owner-session cdp-url]} (await-cdp-route-lock action)]
        (json/write-json-str
          {:success false
           :error (str "CDP endpoint is currently controlled by session '" owner-session
                    "' with active network routes. Timed out waiting for lock release — blocking action '" action
                    "' in session '" (:session @!state) "'.")
           :hint (str "Use one session per --cdp endpoint when routes are active. "
                   "Either run `spel --session " owner-session " network unroute all` "
                   "or close that session before retrying.")
           :error_code "cdp_route_lock"
           :owner_session owner-session
           :cdp cdp-url})
        (try
          (let [result    (handle-cmd action params)
                anomaly-v (cond
                            (anomaly/anomaly? result)
                            result
                            (map? result)
                            (some (fn [[_ v]] (when (anomaly/anomaly? v) v)) result))]
            (cond
            ;; Handler returned an explicit failure map (e.g. sci_eval error path)
              (and (map? result) (false? (:success result)))
              (json/write-json-str result)
              anomaly-v
              (let [msg  (::anomaly/message anomaly-v)
                    ex   (:playwright/exception anomaly-v)
                    hint (when ex (reflection-error-hint ex))
                    error-msg (or hint msg (when ex (.getMessage ^Throwable ex)) (default-error-message ex))]
                (json/write-json-str (error-response error-msg)))
              :else
              (do
              ;; Track user-facing actions for SRT export
                (when (trackable-actions action)
                  (track-action! action params result))
                (json/write-json-str {:success true :data result}))))
          (catch Throwable e
            (let [hint (reflection-error-hint e)
                  msg  (or hint (.getMessage e) (default-error-message e))
                  data (ex-data e)]
              (json/write-json-str (cond-> (error-response msg)
                                     (:stdout data) (assoc :data {:stdout (:stdout data)
                                                                  :stderr (:stderr data)}))))))))
    (catch Throwable e
      (json/write-json-str {:success false :error (str "Parse error: " (.getMessage e))}))))

;; =============================================================================
;; Socket Server
;; =============================================================================

(defn- handle-connection
  "Handles a single client connection — reads commands, writes responses."
  [^SocketChannel client]
  (let [reader (BufferedReader. (InputStreamReader. (Channels/newInputStream client)))
        ^OutputStreamWriter writer (OutputStreamWriter. (Channels/newOutputStream client))]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (let [shutdown? (when-not (str/blank? line)
                            (let [response (process-command line)]
                              (.write writer ^String response)
                              (.write writer "\n")
                              (.flush writer)
                              ;; Check if shutdown was requested
                              (let [parsed (try (json/read-json response) (catch Exception _ nil))]
                                (and parsed (get-in parsed ["data" "shutdown"])))))]
            (if shutdown?
              (submit-virtual stop-daemon!)
              (recur)))))
      (catch Exception e (warn "handle-connection" e))
      (finally
        (try (.close client) (catch Exception e (warn "close-client" e)))))))

(defn- cleanup!
  "Removes socket, PID, and flags files."
  [^String session]
  (try (Files/deleteIfExists (socket-path session)) (catch Exception e (warn "delete-socket" e)))
  (try (Files/deleteIfExists (pid-file-path session)) (catch Exception e (warn "delete-pid" e)))
  (try (Files/deleteIfExists (flags-file-path session)) (catch Exception e (warn "delete-flags" e))))

(defn daemon-running?
  "Checks if a daemon is running for the given session."
  [^String session]
  (let [pid-path (pid-file-path session)]
    (when (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try
        (let [pid-text (str/trim (String. (Files/readAllBytes pid-path)))
              pid      (Long/parseLong pid-text)]
          (if-let [ph (.orElse (java.lang.ProcessHandle/of pid) nil)]
            (.isAlive ^java.lang.ProcessHandle ph)
            false))
        (catch Exception _
          (cleanup! session)
          false)))))

(defn- my-pid
  "Returns this process's PID as a string."
  []
  (str (.pid (java.lang.ProcessHandle/current))))

(defn- owns-pid-file?
  "Returns true if the PID file for `session` contains THIS process's PID."
  [^String session]
  (let [pid-path (pid-file-path session)]
    (and (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try
        (= (str/trim (String. (Files/readAllBytes pid-path))) (my-pid))
        (catch Exception _ false)))))

(defn- save-inflight-trace!
  "If tracing is active, stops the trace and saves it to an auto-generated path.
   Logs a warning to stderr so the user knows where the trace file went.
   Called during daemon shutdown to avoid losing in-flight traces."
  []
  (when (:tracing? @!state)
    (when-let [c (:context @!state)]
      (let [out-path (str "trace-autosave-" (System/currentTimeMillis) ".zip")]
        (try
          (core/tracing-stop! (core/context-tracing c) {:path out-path})
          (swap! !state assoc :tracing? false)
          (binding [*out* *err*]
            (println (str "spel: trace auto-saved to " out-path " (daemon shutting down)")))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "spel: warn: failed to auto-save trace: " (.getMessage e))))))))))

(defn stop-daemon!
  "Stops the daemon server and cleans up browser resources.
   Closes server socket first so new CLI invocations fail fast and start
   a fresh daemon. Only deletes PID/socket files if they still belong to
   THIS process (prevents nuking a replacement daemon's files)."
  []
  ;; 0. Cancel idle timers (prevents re-entry if shutdown is called manually)
  (cancel-cdp-idle-shutdown!)
  (cancel-session-idle-shutdown!)
  (let [session (:session @!state)]
    ;; 1. Close server socket — reject new connections immediately
    (when-let [server @!server]
      (try (.close ^ServerSocketChannel server) (catch Exception e (warn "close-server" e)))
      (reset! !server nil))
    ;; 2. Save in-flight trace before closing browser resources
    (save-inflight-trace!)
    ;; 3. Close browser resources (may take seconds — Chromium shutdown)
    (when-let [p  (:page @!state)]    (try (core/close-page! p)    (catch Exception e (warn "close-page" e))))
    (when-let [c  (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (when-let [b  (:browser @!state)] (try (core/close-browser! b) (catch Exception e (warn "close-browser" e))))
    (when-let [pw (:pw @!state)]      (try (core/close! pw)        (catch Exception e (warn "close-playwright" e))))
    ;; 3b. Clean up temp profile directory if one was created
    (when-let [tmp-dir (:tmp-profile-dir @!state)]
      (try
        (let [tmp-path (java.nio.file.Paths/get ^String tmp-dir (into-array String []))]
          (java.nio.file.Files/walkFileTree tmp-path
            (proxy [java.nio.file.SimpleFileVisitor] []
              (visitFile [^java.nio.file.Path file ^java.nio.file.attribute.BasicFileAttributes _attrs]
                (java.nio.file.Files/deleteIfExists file)
                java.nio.file.FileVisitResult/CONTINUE)
              (postVisitDirectory [^java.nio.file.Path dir ^java.io.IOException _exc]
                (java.nio.file.Files/deleteIfExists dir)
                java.nio.file.FileVisitResult/CONTINUE))))
        (catch Exception e (warn "cleanup-tmp-profile" e))))
    ;; 3c. Kill auto-launched browser process if one was started
    (when-let [auto-info (:auto-launch-info @!state)]
      (kill-auto-launched-browser! auto-info))
    ;; 3d. Release shared CDP route lock if we own it
    (release-cdp-route-lock-if-owned!)
    ;; 4. Only delete files if they still belong to US (a new daemon may
    ;;    have already replaced them during our slow browser cleanup)
    (when (owns-pid-file? session)
      (cleanup! session))
    ;; 5. Reset state and exit
    (reset! !state {:pw nil :browser nil :context nil :page nil
                    :refs {} :counter 0 :headless true :session session
                    :tracing? false})
    (System/exit 0)))

(defn start-daemon!
  "Starts the daemon server. Blocks until shutdown.

   Params:
   `opts` - Map:
     :session  - String (default 'default')
     :headless - Boolean (default true)
     :browser  - String (optional, e.g. 'firefox', 'webkit')
     :cdp      - String (optional, CDP endpoint URL)"
  [opts]
  (let [session  (get opts :session "default")
        headless (get opts :headless true)
        browser  (get opts :browser)
        cdp-url  (get opts :cdp)
        sock-path (socket-path session)
        pid-path  (pid-file-path session)]
    ;; Store session config + initial launch flags (browser type from CLI args)
    (swap! !state assoc :headless headless :session session)
    (when browser
      (swap! !state assoc-in [:launch-flags "browser"] browser))
    (when cdp-url
      (swap! !state assoc-in [:launch-flags "cdp"] cdp-url))

    ;; Persist launch flags so CLI can recover them (e.g. --cdp) on subsequent commands
    (persist-launch-flags!)

    ;; Clean up stale socket
    (cleanup! session)

    ;; Write PID file
    (Files/writeString pid-path
      (str (.pid (java.lang.ProcessHandle/current)))
      (into-array java.nio.file.OpenOption []))

    ;; Create Unix domain socket server
    (let [addr   (UnixDomainSocketAddress/of (.toString sock-path))
          server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server addr)
      (reset! !server server)

      ;; Signal handlers
      (let [shutdown-hook (Thread. ^Runnable (fn [] (stop-daemon!)))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook))

      ;; Start session idle timer
      (schedule-session-idle-shutdown!)

      ;; Accept connections
      (try
        (loop []
          (let [client (.accept server)]
            (submit-virtual #(handle-connection client)))
          (recur))
        (catch Exception _
          ;; Server closed
          nil)))))
