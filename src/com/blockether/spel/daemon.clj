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
   [com.blockether.spel.backend :as backend]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.ios :as ios]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.errors :as errors]
   [com.blockether.spel.helpers :as helpers]
   [com.blockether.spel.input :as input]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.logging :as log]
   [com.blockether.spel.markdownify :as markdownify]
   [com.blockether.spel.network :as network]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.profile :as profile]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.options :as options]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.security :as security]
   [com.blockether.spel.stealth :as stealth]
   [com.blockether.spel.vault :as vault]
   [com.blockether.spel.visual-diff :as visual-diff]
   [com.blockether.spel.platform :as platform])
  (:import
   [com.microsoft.playwright BrowserContext ConsoleMessage Dialog Frame Keyboard Mouse Page Request Response]
   [com.microsoft.playwright.options AriaRole Cookie Geolocation Timing]
   [java.io BufferedReader File InputStreamReader OutputStreamWriter]
   [java.lang ProcessBuilder$Redirect]
   [java.net HttpURLConnection StandardProtocolFamily UnixDomainSocketAddress URL]
   [java.nio.channels Channels ServerSocketChannel SocketChannel]
   [java.nio.file FileAlreadyExistsException Files Path StandardOpenOption]
   [java.util Base64]
   [java.util.concurrent ExecutorService Executors ScheduledExecutorService ScheduledFuture TimeUnit]
   [java.util.concurrent.locks ReentrantLock]
   [javax.net.ssl HostnameVerifier HttpsURLConnection SSLContext SSLSocketFactory TrustManager X509TrustManager]))

(declare stop-daemon!)
(declare save-inflight-trace!)
(declare pg)
(declare daemon-running?)

(defn- warn
  "Logs a warning through the one log sink. Used in cleanup paths where we must
   continue despite errors but never silently swallow them."
  [^String context ^Exception e]
  (log/exception! context e))

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

(defn discover-session-files
  "Single source of truth for enumerating spel session state on disk.
   Scans the tmpdir for `spel-<name>.sock` files and returns a seq of
   `{:name :socket :alive?}` maps — `:alive?` is true when the owning PID
   file points to a running process. Pure; does NOT start any daemon."
  []
  (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir"))]
    (->> (or (.listFiles tmp-dir) (into-array java.io.File []))
      (keep (fn [^java.io.File f]
              ;; NOT `.isFile`: a Unix domain socket is not a regular file, so
              ;; `.isFile` was false for every live session and this scan found
              ;; nothing at all — `session list` showed no sessions and every
              ;; --all-sessions sweep swept nothing.
              (when (and (not (.isDirectory f))
                      (str/starts-with? (.getName f) "spel-")
                      (str/ends-with? (.getName f) ".sock"))
                (let [sess (-> (.getName f)
                             (str/replace "spel-" "")
                             (str/replace ".sock" ""))]
                  {:name sess
                   :socket (.getAbsolutePath f)
                   :alive? (boolean (daemon-running? sess))}))))
      (into []))))

(defn pid-file-path
  "Returns the PID file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".pid")
    (into-array String [])))

(defn log-file-path
  "Returns the log file path for a session.

   Delegates to `logging/log-file-path` so the daemon, the CLI, and `spel logs`
   can never disagree about where a session's log lives."
  ^Path [^String session]
  (log/log-file-path session))

(defn flags-file-path
  "Returns the launch flags persistence file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".flags.json")
    (into-array String [])))

(defn- cdp-route-lock-path
  "Returns a filesystem lock path keyed by CDP endpoint URL and the TAB whose
   requests are intercepted. Playwright installs interception on the page, so it
   is a property of one tab: keyed by the endpoint alone, one session's routes
   made every other session on that browser queue for a tab it never touches."
  ^Path [^String cdp-url ^String tab-id]
  (let [encoder (.withoutPadding (Base64/getUrlEncoder))
        token   (.encodeToString encoder (.getBytes (str cdp-url "\n" tab-id)
                                           java.nio.charset.StandardCharsets/UTF_8))]
    (Path/of (str (System/getProperty "java.io.tmpdir")
               File/separator
               "spel-cdp-route-lock-" token ".json")
      (into-array String []))))

(defn- read-cdp-route-lock
  "Reads the lock map for one tab of a CDP endpoint, or nil if absent/invalid."
  [^String cdp-url ^String tab-id]
  (let [path (cdp-route-lock-path cdp-url tab-id)]
    (when (Files/exists path (into-array java.nio.file.LinkOption []))
      (try
        (json/read-json (String. (Files/readAllBytes path)))
        (catch Exception _ nil)))))

(defn- clear-cdp-route-lock!
  "Deletes the lock file for one tab of a CDP endpoint. Best-effort."
  [^String cdp-url ^String tab-id]
  (try
    (Files/deleteIfExists (cdp-route-lock-path cdp-url tab-id))
    (catch Exception e (warn "delete-cdp-route-lock" e))))

(defn- write-cdp-route-lock!
  "Writes/overwrites the lock owner for one tab of a CDP endpoint."
  [^String cdp-url ^String tab-id ^String session]
  (let [payload {:session session
                 :cdp cdp-url
                 :tab tab-id
                 :updated_at (System/currentTimeMillis)}]
    (Files/writeString (cdp-route-lock-path cdp-url tab-id)
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

(defn- wsl?
  "Delegates to platform/wsl?. Kept as a private wrapper for call-site
   compatibility within daemon.clj."
  []
  (platform/wsl?))

(defn- wsl-windows-user-dirs
  "For each Windows user visible under /mnt/c/Users, returns the path to their
   Local AppData directory — e.g. \"/mnt/c/Users/alice/AppData/Local\". Filters
   out the built-in Public/Default/All Users pseudo-accounts."
  []
  (let [users-dir (java.io.File. "/mnt/c/Users")]
    (when (.isDirectory users-dir)
      (->> (.listFiles users-dir)
        (filter (fn [^File f]
                  (and (.isDirectory f)
                    (not (contains? #{"Public" "Default" "Default User" "All Users"
                                      "desktop.ini" "WsiAccount"}
                           (.getName f))))))
        (map (fn [^File f]
               {:user (.getName f)
                :local-appdata (str (.getPath f) "/AppData/Local")
                :roaming-appdata (str (.getPath f) "/AppData/Roaming")}))
        (into [])))))

(def ^:private chromium-browser-catalog
  "Single source of truth for chromium-family user-data directory locations.
   Each entry describes one browser; optional keys are omitted when the browser
   isn't available on an OS. Keys:
     :label         — display name
     :mac           — path fragment under ~/Library/Application Support/
     :linux         — path fragment under ~/.config/
     :linux-snap    — path fragment under ~/snap/ (optional)
     :linux-flatpak — path fragment under ~/.var/app/ (optional)
     :win           — path fragment under %LOCALAPPDATA%
     :win-roaming   — path fragment under %APPDATA% (Opera family)"
  [{:label "Google Chrome"
    :mac "Google/Chrome"
    :linux "google-chrome"
    :linux-flatpak "com.google.Chrome/config/google-chrome"
    :win "Google/Chrome/User Data"}
   {:label "Chrome Beta"
    :mac "Google/Chrome Beta"
    :linux "google-chrome-beta"
    :win "Google/Chrome Beta/User Data"}
   {:label "Chrome Canary"
    :mac "Google/Chrome Canary"
    :win "Google/Chrome SxS/User Data"}
   {:label "Chrome Dev"
    :mac "Google/Chrome Dev"
    :linux "google-chrome-unstable"
    :win "Google/Chrome Dev/User Data"}
   {:label "Chrome for Testing"
    :mac "Google/Chrome for Testing"
    :linux "google-chrome-for-testing"
    :win "Google/Chrome for Testing/User Data"}
   {:label "Chromium"
    :mac "Chromium"
    :linux "chromium"
    :linux-snap "chromium/common/chromium"
    :linux-flatpak "org.chromium.Chromium/config/chromium"
    :win "Chromium/User Data"}
   {:label "Microsoft Edge"
    :mac "Microsoft Edge"
    :linux "microsoft-edge"
    :win "Microsoft/Edge/User Data"}
   {:label "Edge Beta"
    :mac "Microsoft Edge Beta"
    :linux "microsoft-edge-beta"
    :win "Microsoft/Edge Beta/User Data"}
   {:label "Edge Dev"
    :mac "Microsoft Edge Dev"
    :linux "microsoft-edge-dev"
    :win "Microsoft/Edge Dev/User Data"}
   {:label "Edge Canary"
    :mac "Microsoft Edge Canary"
    :win "Microsoft/Edge SxS/User Data"}
   {:label "Brave"
    :mac "BraveSoftware/Brave-Browser"
    :linux "BraveSoftware/Brave-Browser"
    :linux-snap "brave/current/.config/BraveSoftware/Brave-Browser"
    :linux-flatpak "com.brave.Browser/config/BraveSoftware/Brave-Browser"
    :win "BraveSoftware/Brave-Browser/User Data"}
   {:label "Brave Beta"
    :mac "BraveSoftware/Brave-Browser-Beta"
    :linux "BraveSoftware/Brave-Browser-Beta"
    :win "BraveSoftware/Brave-Browser-Beta/User Data"}
   {:label "Brave Nightly"
    :mac "BraveSoftware/Brave-Browser-Nightly"
    :linux "BraveSoftware/Brave-Browser-Nightly"
    :win "BraveSoftware/Brave-Browser-Nightly/User Data"}
   {:label "Vivaldi"
    :mac "Vivaldi"
    :linux "vivaldi"
    :linux-flatpak "com.vivaldi.Vivaldi/config/vivaldi"
    :win "Vivaldi/User Data"}
   {:label "Vivaldi Snapshot"
    :linux "vivaldi-snapshot"}
   {:label "Opera"
    :mac "com.operasoftware.Opera"
    :linux "opera"
    :win-roaming "Opera Software/Opera Stable"}
   {:label "Opera Beta"
    :mac "com.operasoftware.OperaNext"
    :linux "opera-beta"
    :win-roaming "Opera Software/Opera Beta"}
   {:label "Opera Developer"
    :mac "com.operasoftware.OperaDeveloper"
    :linux "opera-developer"
    :win-roaming "Opera Software/Opera Developer"}
   {:label "Arc"
    :mac "Arc/User Data"
    :win "Arc/User Data"}
   {:label "Thorium"
    :mac "Thorium"
    :linux "thorium"
    :win "Thorium/User Data"}])

(defn- chromium-user-data-dirs
  "Projects `chromium-browser-catalog` to absolute user-data-dir paths for the
   current OS. Returns a seq of {:path :label}. Under WSL, also projects each
   catalog entry over every visible /mnt/c/Users/<user>/AppData/{Local,Roaming}."
  []
  (let [home    (System/getProperty "user.home")
        os-name (str/lower-case (or (System/getProperty "os.name") ""))
        mac?    (str/includes? os-name "mac")
        win?    (str/includes? os-name "windows")]
    (cond
      mac?
      (let [appsup (str home "/Library/Application Support")]
        (into []
          (for [{:keys [label mac]} chromium-browser-catalog :when mac]
            {:path (str appsup "/" mac) :label label})))

      win?
      (let [lad  (or (System/getenv "LOCALAPPDATA")
                   (str home "\\AppData\\Local"))
            rad  (or (System/getenv "APPDATA")
                   (str home "\\AppData\\Roaming"))
            norm #(str/replace % "/" "\\")]
        (into []
          (concat
            (for [{:keys [label win]} chromium-browser-catalog :when win]
              {:path (str lad "\\" (norm win)) :label label})
            (for [{:keys [label win-roaming]} chromium-browser-catalog :when win-roaming]
              {:path (str rad "\\" (norm win-roaming)) :label label}))))

      :else ;; Linux / BSD / WSL
      (let [cfg     (str home "/.config")
            snap    (str home "/snap")
            flatpak (str home "/.var/app")
            linux-dirs
            (concat
              (for [{:keys [label linux]} chromium-browser-catalog :when linux]
                {:path (str cfg "/" linux) :label label})
              (for [{:keys [label linux-snap]} chromium-browser-catalog :when linux-snap]
                {:path (str snap "/" linux-snap) :label (str label " (snap)")})
              (for [{:keys [label linux-flatpak]} chromium-browser-catalog :when linux-flatpak]
                {:path (str flatpak "/" linux-flatpak) :label (str label " (flatpak)")}))
            ;; WSL: project every Windows catalog entry over each visible
            ;; /mnt/c/Users/<user>/AppData/{Local,Roaming}/ — this surfaces
            ;; Windows-side browsers from inside the Linux shell.
            wsl-dirs
            (when (wsl?)
              (for [{:keys [user local-appdata roaming-appdata]} (wsl-windows-user-dirs)
                    {:keys [label win win-roaming]} chromium-browser-catalog
                    :let [path (cond
                                 win         (str local-appdata "/" win)
                                 win-roaming (str roaming-appdata "/" win-roaming))]
                    :when path]
                {:path path :label (str label " (WSL host user " user ")")}))]
        (into [] (concat linux-dirs (or wsl-dirs [])))))))

(defn- ms-playwright-cache-dir
  "Returns the absolute path of the ms-playwright browser cache for the current OS."
  ^String []
  (let [home    (System/getProperty "user.home")
        os-name (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/includes? os-name "mac")
      (str home "/Library/Caches/ms-playwright")
      (str/includes? os-name "windows")
      (str (or (System/getenv "LOCALAPPDATA")
             (str home "\\AppData\\Local"))
        "\\ms-playwright")
      :else (str home "/.cache/ms-playwright"))))

(defn- chromium-devtools-active-port-files
  "Returns a seq of {:file :label} maps — one per DevToolsActivePort candidate
   on the current OS: one per chromium-family user-data dir, plus any found
   one level deep under the ms-playwright cache (which is where
   chrome-devtools-mcp, agent-browser, etc. launch their Chromium)."
  []
  (let [base (map (fn [{:keys [path label]}]
                    {:file (str path "/DevToolsActivePort") :label label})
               (chromium-user-data-dirs))
        pw-cache (ms-playwright-cache-dir)
        pw-dir (java.io.File. pw-cache)
        pw-files (when (.isDirectory pw-dir)
                   (mapv (fn [^File child]
                           {:file (str (.getPath child) "/DevToolsActivePort")
                            :label (str "Playwright: " (.getName child))})
                     (filter #(.isDirectory ^File %) (.listFiles pw-dir))))]
    (into [] (concat base (or pw-files [])))))

(defn wsl-default-gateway-ip
  "Delegates to platform/wsl-default-gateway-ip. Kept as a public wrapper
   for call-site compatibility (tests, diagnostic script, etc.)."
  []
  (platform/wsl-default-gateway-ip))

(defn- wsl-projected-source?
  "True iff the given DevToolsActivePort file path lives on a Windows
   drive projected into WSL (i.e. under /mnt/<drive>/). Such a file was
   written by a Windows-side Chrome, so probing 127.0.0.1 from WSL will
   never reach it under NAT networking — we must also try the Windows
   host IP."
  [^String source-path]
  (and (string? source-path)
    (or (str/starts-with? source-path "/mnt/")
      (str/starts-with? source-path "/media/"))))

(defn- cdp-candidate-hosts
  "Given the DTAP source file path (nil for ad-hoc port scans), returns
   the list of hosts spel should probe in order. Default: [\"127.0.0.1\"].
   Under WSL when the DTAP came from a Windows-projected path AND we can
   resolve the default-gateway IP, returns [\"127.0.0.1\" <win-ip>] —
   loopback first because mirrored-networking users (where loopback IS
   unified) get the fast path, and NAT users fall through to the gateway
   IP on the second attempt. Duplicates are removed."
  [source-path]
  (let [loopback "127.0.0.1"
        win-ip   (when (and (wsl?) (wsl-projected-source? source-path))
                   (wsl-default-gateway-ip))]
    (if (and win-ip (not= win-ip loopback))
      [loopback win-ip]
      [loopback])))

(def ^:private probe-ssl-context
  "TLS context for the CDP endpoint probes.

   The probes are DIAGNOSTICS, not a security boundary: the connection itself is
   made by Playwright, which validates TLS on its own. Remote CDP grids sit behind
   internal or self-signed certificates all the time, so a probe that verified them
   would refuse endpoints Playwright connects to happily — a preflight must never be
   stricter than the connection it guards. Nothing but `GET /json/version` and a
   WebSocket upgrade ever travels over it."
  (delay
    (doto (SSLContext/getInstance "TLS")
      (.init nil
        (into-array TrustManager
          [(reify X509TrustManager
             (checkClientTrusted [_ _chain _auth] nil)
             (checkServerTrusted [_ _chain _auth] nil)
             (getAcceptedIssuers [_] (make-array java.security.cert.X509Certificate 0)))])
        (java.security.SecureRandom.)))))

(defn- open-cdp-http-connection
  "Opens a timeout-bounded HTTP(S) connection to a CDP endpoint URL. `https://`
   URLs get `probe-ssl-context`, so a TLS-fronted endpoint (a hosted browser
   service, an internal Chrome grid behind a reverse proxy) is reachable at all."
  ^HttpURLConnection [^String url timeout-ms]
  (let [conn (.openConnection (URL. url))]
    (when (instance? HttpsURLConnection conn)
      (doto ^HttpsURLConnection conn
        (.setSSLSocketFactory (.getSocketFactory ^SSLContext @probe-ssl-context))
        (.setHostnameVerifier (reify HostnameVerifier
                                (verify [_ _hostname _session] true)))))
    (doto ^HttpURLConnection conn
      (.setConnectTimeout (int timeout-ms))
      (.setReadTimeout (int timeout-ms)))))

(defn- json-version-url
  "`/json/version` URL for a CDP endpoint; `secure?` picks https over http."
  ^String [secure? ^String host port]
  (str (if secure? "https" "http") "://" host ":" port "/json/version"))

(defn- read-cdp-json-version
  "HTTP-GETs /json/version on the given host:port and returns
   `{:port N :browser \"Chrome/…\" :host H}` iff:
     1. the HTTP response is 200, AND
     2. the body parses as JSON, AND
     3. the parsed object has a non-blank `Browser` field.
   Returns nil in every other case (non-200, non-JSON, missing field,
   timeout, connection refused). Shared by `probe-http-cdp` and
   `fetch-cdp-browser-label` so both apply the same CDP-ness check.

   Two-arity preserved for backwards compat with direct test call-sites
   that pass [port timeout-ms]; those default to loopback, which matches
   the historical behaviour. The three-arity form `[host port timeout-ms]`
   lets WSL callers probe the Windows host IP as well. The four-arity form
   adds `secure?`, which speaks TLS — required by `https://` CDP endpoints.

   (Non-primitive arity — keeps the var compatible with `with-redefs` test
   stubs that don't carry `IFn$LLO` hints.)"
  ([port timeout-ms]
   (read-cdp-json-version "127.0.0.1" port timeout-ms false))
  ([^String host port timeout-ms]
   (read-cdp-json-version host port timeout-ms false))
  ([^String host port timeout-ms secure?]
   (try
     (let [conn (doto (open-cdp-http-connection (json-version-url secure? host port) timeout-ms)
                  (.connect))]
       (try
         (when (= 200 (.getResponseCode ^HttpURLConnection conn))
           (with-open [in (.getInputStream ^HttpURLConnection conn)]
             (let [body (slurp in)
                   data (try (json/read-json body) (catch Exception _ nil))
                   browser (when (map? data) (get data "Browser"))]
               (when (and (string? browser) (not (str/blank? browser)))
                 {:port port :browser browser :host host}))))
         (finally
           (.disconnect ^HttpURLConnection conn))))
     (catch Exception e
       (log/debug! "[cdp] /json/version probe failed for " host ":" port " — " (.getMessage e))
       nil))))

(defn- probe-http-cdp
  "Probes an HTTP endpoint for CDP. Returns the port only when /json/version is
   HTTP 200 **and** the JSON body carries a non-blank `Browser` field. Returns
   nil for non-200 (e.g. M144 websocket-only 404), non-JSON 200s, missing
   Browser field, or connection failures. This tighter check prevents random
   HTTP servers from being mistaken for CDP endpoints.

   Two-arity form `[port timeout-ms]` defaults to loopback (backwards compat
   with tests and with local-browser call-sites like `auto-launch-browser!`).
   Three-arity form `[host port timeout-ms]` lets WSL discovery probe the
   Windows host IP. Four-arity form adds `secure?` for `https://` endpoints."
  ([port timeout-ms]
   (probe-http-cdp "127.0.0.1" port timeout-ms false))
  ([^String host port timeout-ms]
   (probe-http-cdp host port timeout-ms false))
  ([^String host port timeout-ms secure?]
   (when (read-cdp-json-version host port timeout-ms secure?)
     port)))

(defn- cdp-http-transport-error
  "Returns the failure message when an HTTP request to /json/version cannot even
   complete — an unsupported `http` URL protocol in a misbuilt native image, a
   connection reset, a timeout, a TLS handshake that never completed — and nil
   when the exchange completed, whatever its status. `secure?` speaks HTTPS.

   Used only on the failure path of `assert-cdp-endpoint-reachable!` so a broken
   HTTP stack is never reported as a stale browser."
  [^String host port timeout-ms secure?]
  (try
    (let [conn (open-cdp-http-connection (json-version-url secure? host port) timeout-ms)]
      (try
        (.getResponseCode ^HttpURLConnection conn)
        nil
        (finally
          (.disconnect ^HttpURLConnection conn))))
    (catch Exception e
      (or (.getMessage e) (.getName (class e))))))

(defn- cdp-ready?
  "Returns true only after the browser has exposed an accepted HTTP CDP endpoint.
   A listening TCP port alone is insufficient: Chrome 144+ can accept TCP while
   it waits for the user to approve remote debugging."
  [port]
  (boolean (probe-http-cdp port 500)))

(defn- list-devtools-active-ports
  "Returns a seq of {:port :ws-path :label :source-path} parsed from every
   DevToolsActivePort file candidate on the current OS (see
   `chromium-devtools-active-port-files`). The `:label` comes from the
   file's source directory (e.g. \"Google Chrome\"); `:source-path` is
   the absolute file path we read, which callers use to decide whether
   the entry came from a Windows-projected WSL path (needs host
   resolution) or a native Linux path (loopback-only).
   Silently skips missing/unreadable files."
  []
  (->> (chromium-devtools-active-port-files)
    (keep (fn [{:keys [file label]}]
            (when-let [info (parse-devtools-active-port file)]
              (assoc info :label label :source-path file))))
    (into [])))

(defn discover-cdp-endpoint
  "Auto-discovers a running Chromium-based browser's CDP endpoint.
   Checks DevToolsActivePort files first across every known chromium-family
   user-data dir on the current OS (Chrome, Chromium, Edge, Brave, Vivaldi,
   Opera, Arc, Thorium — including snap/flatpak variants on Linux) and the
   ms-playwright cache, then probes common ports (9222, 9223, 9229).
   9223 is added to catch Windows proxy setups where 9222 is taken.
   Returns a CDP URL string (http:// or ws://) suitable for Playwright connectOverCDP.

   WSL awareness: when the DevToolsActivePort file was read from a
   Windows-projected path (`/mnt/c/Users/...`), loopback inside WSL
   doesn't reach the Windows-side Chrome under default NAT networking.
   In that case we also probe the default-gateway IP (= Windows host),
   and the winning host is baked into the returned URL so Playwright's
   `connectOverCDP` uses the right one.

   Chrome/Edge 136+ ignores --remote-debugging-port without --user-data-dir.
   Chrome/Edge 144+ chrome://inspect remote debugging uses WebSocket-only (no HTTP)."
  []
  (let [mac? (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac")
        dt-info (first (list-devtools-active-ports))]
    (if dt-info
      ;; DevToolsActivePort found — try HTTP probe across candidate hosts,
      ;; then fall back to direct WebSocket using the winning host.
      (let [port      (:port dt-info)
            ws-path   (:ws-path dt-info)
            src-path  (:source-path dt-info)
            hosts     (cdp-candidate-hosts src-path)
            ;; First host that passes the CDP-ness check wins. Falls back
            ;; to the first host in the list if none respond, so the WS
            ;; fallback still gets a non-nil host.
            winning   (or (some (fn [h] (when (probe-http-cdp h port 2000) h)) hosts)
                        (first hosts))
            http-ok?  (some? (probe-http-cdp winning port 2000))]
        (if http-ok?
          ;; Pre-M144: HTTP endpoint works
          (str "http://" winning ":" port)
          ;; M144+: WebSocket-only server (chrome://inspect remote debugging)
          ;; HTTP endpoints return 404, must connect via WebSocket directly.
          (if ws-path
            (str "ws://" winning ":" port ws-path)
            (str "http://" winning ":" port))))
      ;; No DevToolsActivePort — probe common ports on loopback only.
      ;; (Port-scanning every host×port combo would be slow and noisy;
      ;; users with a Windows-side browser but no WSL-projected DTAP file
      ;; should pass --cdp http://<win-ip>:<port> explicitly.)
      (let [found (some #(probe-http-cdp % 1000) platform/common-cdp-ports)]
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
                            "  (Works in both Chrome and Edge)\n"
                            (when (wsl?)
                              (str "\nWSL note — spel can't launch chrome.exe or msedge.exe from inside\n"
                                "the WSL shell; those binaries live on Windows. Launch the browser\n"
                                "you actually use (Chrome OR Edge) on the Windows side first, then\n"
                                "rerun spel from WSL.\n\n"
                                "Windows PowerShell — Google Chrome:\n"
                                "  & \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" `\n"
                                "    --remote-debugging-port=9222 `\n"
                                "    --remote-debugging-address=0.0.0.0 `\n"
                                "    --remote-allow-origins=* `\n"
                                "    --user-data-dir=\"$env:LOCALAPPDATA\\Google\\Chrome\\User Data\"\n\n"
                                "Windows PowerShell — Microsoft Edge:\n"
                                "  & \"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe\" `\n"
                                "    --remote-debugging-port=9222 `\n"
                                "    --remote-debugging-address=0.0.0.0 `\n"
                                "    --remote-allow-origins=* `\n"
                                "    --user-data-dir=\"$env:LOCALAPPDATA\\Microsoft\\Edge\\User Data\"\n\n"
                                "Or from inside WSL via Windows interop (either works):\n"
                                "  powershell.exe -NoProfile -Command \"Start-Process chrome -ArgumentList '--remote-debugging-port=9222','--remote-debugging-address=0.0.0.0','--remote-allow-origins=*'\"\n"
                                "  powershell.exe -NoProfile -Command \"Start-Process msedge -ArgumentList '--remote-debugging-port=9222','--remote-debugging-address=0.0.0.0','--remote-allow-origins=*'\"\n\n"
                                "Once it's up, spel auto-discovery from WSL probes both 127.0.0.1 and\n"
                                "the WSL default gateway (" (or (wsl-default-gateway-ip) "<unresolved>") ") — whichever answers /json/version first wins.\n"
                                "Run ./dev/wsl-cdp-diag.sh for a step-by-step diagnosis.\n")))
                   {:devtools-active-port-files (mapv :file (chromium-devtools-active-port-files))
                    :probed-ports               platform/common-cdp-ports
                    :wsl?                       (wsl?)
                    :wsl-gateway                (wsl-default-gateway-ip)})))))))

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

(defn list-active-cdp-endpoints
  "Scans /tmp/spel-auto-launch-*.json for active CDP endpoints owned by spel
   sessions. For each lock whose owning daemon is still alive and whose port
   responds to a CDP /json/version probe, returns {:session :port :cdp_url}.
   Stale entries are filtered out silently."
  []
  (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir"))
        lock-files (->> (.listFiles tmp-dir)
                     (filter (fn [^File f]
                               (and (.isFile f)
                                 (str/starts-with? (.getName f) "spel-auto-launch-")
                                 (str/ends-with? (.getName f) ".json")))))]
    (->> lock-files
      (keep (fn [^File f]
              (try
                (let [port (Long/parseLong
                             (-> (.getName f)
                               (str/replace "spel-auto-launch-" "")
                               (str/replace ".json" "")))
                      lock (read-auto-launch-lock port)
                      session (get lock "session")]
                  (when (and session
                          (daemon-running? session)
                          (probe-http-cdp port 300))
                    {:session session
                     :port port
                     :cdp_url (str "http://127.0.0.1:" port)}))
                (catch Exception _ nil))))
      (into []))))

(defn- fetch-cdp-browser-label
  "Returns the `Browser` string reported by /json/version on the given
   host:port (e.g. \"Chrome/144.0.7339.127\"), or nil when the endpoint
   isn't a valid CDP endpoint. Thin wrapper around `read-cdp-json-version`.
   Two-arity form defaults to loopback for backwards compat."
  (^String [^long port]
   (fetch-cdp-browser-label "127.0.0.1" port))
  (^String [^String host ^long port]
   (:browser (read-cdp-json-version host port 200))))

(defn probe-ws-target
  "Verifies that a CDP ws:// or wss:// URL points at a target that still EXISTS.

   A live TCP socket is not enough: a browser restart leaves stale
   DevToolsActivePort / session caches behind, and the old browser target id
   then 500s while the port itself keeps listening. We perform the WebSocket
   upgrade handshake by hand (no Origin header, so `--remote-allow-origins`
   isn't required) and accept only `HTTP/1.1 101`.

   `wss://` defaults to port 443 and runs the handshake through
   `probe-ssl-context`: a plaintext handshake written into a TLS listener reads
   back as a dead target, which is how remote CDP endpoints used to be refused.

   Returns true/false, never throws."
  [^String ws-url ^long timeout-ms]
  (try
    (let [uri     (java.net.URI. ws-url)
          secure? (= "wss" (some-> (.getScheme uri) str/lower-case))
          host    (or (.getHost uri) "127.0.0.1")
          port    (let [p (.getPort uri)] (if (pos? p) p (if secure? 443 80)))
          path    (let [p (.getRawPath uri)] (if (str/blank? p) "/" p))
          key     (.encodeToString (java.util.Base64/getEncoder) (byte-array 16))]
      (with-open [^java.net.Socket plain (java.net.Socket.)]
        (.connect plain (java.net.InetSocketAddress. ^String host (int port)) (int timeout-ms))
        (.setSoTimeout plain (int timeout-ms))
        (with-open [^java.net.Socket s (if secure?
                                         (doto ^java.net.Socket
                                          (.createSocket ^SSLSocketFactory (.getSocketFactory ^SSLContext @probe-ssl-context)
                                            plain host (int port) true)
                                           (.setSoTimeout (int timeout-ms)))
                                         plain)]
          (let [out (.getOutputStream s)
                req (str "GET " path " HTTP/1.1\r\n"
                      "Host: " host ":" port "\r\n"
                      "Upgrade: websocket\r\n"
                      "Connection: Upgrade\r\n"
                      "Sec-WebSocket-Version: 13\r\n"
                      "Sec-WebSocket-Key: " key "\r\n\r\n")]
            (.write out (.getBytes req "UTF-8"))
            (.flush out)
            (let [rdr    (java.io.BufferedReader.
                           (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))
                  status (.readLine rdr)]
              (boolean (and status (str/includes? status " 101"))))))))
    (catch Exception _ false)))

(defn discover-external-cdp-endpoints
  "Scans for running CDP browsers. Probes common ports (9222, 9223, 9229),
   the spel auto-launch port range, and any ports advertised in
   DevToolsActivePort files (Chrome/Edge/Chromium/Brave/Vivaldi/Opera/Arc/
   Thorium data dirs + ms-playwright cache). Excludes any ports in
   `excluded-ports`. Fast TCP liveness check first so closed ports cost
   ~microseconds, then HTTP-probes listening ports with a 200 ms timeout. If
   HTTP /json/version returns non-200 (e.g. Chrome M144+ chrome://inspect is
   WebSocket-only), falls back to the DevToolsActivePort ws-path to build a
   ws:// URL. Returns [{:port :cdp_url :label}], where :label is the browser
   identified via DevToolsActivePort source directory or the `Browser` field
   from /json/version (or \"unknown\" as a last resort).

   WSL awareness: for DTAP entries whose source path lives under /mnt/,
   both loopback and the WSL default-gateway IP are probed per port.
   The winning host is baked into the returned :cdp_url so downstream
   `connectOverCDP` talks to the host that actually answered."
  [excluded-ports]
  (let [excluded (set (map long excluded-ports))
        dt-entries (list-devtools-active-ports)
        dt-by-port (into {} (map (juxt :port identity) dt-entries))
        common-ports platform/common-cdp-ports
        base (long auto-launch-base-port)
        span (long auto-launch-port-range)
        range-ports (range base (+ base span))
        candidates (->> (concat common-ports
                          range-ports
                          (map :port dt-entries))
                     (map long)
                     distinct
                     (remove excluded))]
    (->> candidates
      ;; For port-in-use? we still check loopback first — the TCP probe
      ;; is cheap and WSL users on mirrored networking hit this fast path.
      ;; Entries backed by a /mnt/ DTAP bypass this filter since the port
      ;; isn't on WSL's loopback at all; we rely on the HTTP probe below.
      (filter (fn [^long port]
                (or (port-in-use? port)
                  (when-let [dt (get dt-by-port port)]
                    (wsl-projected-source? (:source-path dt))))))
      (keep (fn [^long port]
              (let [dt-info  (get dt-by-port port)
                    hosts    (cdp-candidate-hosts (:source-path dt-info))
                    ;; Find the first host that passes the /json/version check.
                    winner   (some (fn [h] (when (probe-http-cdp h port 200) h))
                               hosts)]
                (cond
                  winner
                  {:port port
                   :cdp_url (str "http://" winner ":" port)
                   :label   (or (:label dt-info)
                              (fetch-cdp-browser-label winner port)
                              "unknown")}

                  ;; No HTTP probe succeeded, but we have a DTAP entry.
                  ;; A DevToolsActivePort file long outlives the browser that
                  ;; wrote it, and a restarted browser keeps the port listening
                  ;; under a NEW target id — so a live TCP socket proves
                  ;; nothing. Only advertise the ws:// URL when the WebSocket
                  ;; upgrade handshake actually succeeds; otherwise the entry
                  ;; is stale and `connect` would hang on it.
                  dt-info
                  (let [ws-path (:ws-path dt-info)]
                    (some (fn [h]
                            (let [url (if ws-path
                                        (str "ws://" h ":" port ws-path)
                                        (str "http://" h ":" port))]
                              (when (and ws-path (probe-ws-target url 500))
                                {:port    port
                                 :cdp_url url
                                 :label   (:label dt-info)})))
                      hosts))))))
      (into []))))

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

(defn- which-binary
  "Returns the absolute path to an executable named `bin-name` found in PATH,
   or nil if none is found. Used by --engine lightpanda to locate the binary
   before trying to spawn a subprocess (so we fail with a clear error rather
   than a cryptic ProcessBuilder exception)."
  ^String [^String bin-name]
  (let [path-entries (str/split (or (System/getenv "PATH") "") #":")
        exe-name     (if (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "win")
                       (str bin-name ".exe")
                       bin-name)]
    (some (fn [dir]
            (let [f (java.io.File. ^String dir ^String exe-name)]
              (when (and (.isFile f) (.canExecute f))
                (.getAbsolutePath f))))
      path-entries)))

(defn launch-lightpanda!
  "Spawns a Lightpanda subprocess in CDP-server mode and returns a map with
   the CDP URL and child process info, parallel to `auto-launch-browser!` but
   scoped to the Lightpanda binary.

   Lightpanda is a Zig-based lightweight headless browser that speaks a
   subset of the CDP. The user must have `lightpanda` on PATH; if not, we
   throw a clear ex-info with install hints instead of blowing up inside
   ProcessBuilder.

   Returns:
     :cdp-url     — WebSocket CDP endpoint (ws://127.0.0.1:<port>)
     :port        — Allocated port
     :process     — java.lang.Process for the subprocess
     :browser-pid — PID of the child"
  [{:keys [session] :or {session "default"}}]
  (let [bin  (which-binary "lightpanda")
        _    (when-not bin
               (throw (ex-info (str "Lightpanda binary not found in PATH.\n"
                                 "Install it from https://lightpanda.io/ or pass --engine chrome.")
                        {:engine "lightpanda"})))
        port (find-free-cdp-port)
        cmd  [bin "serve" "--host" "127.0.0.1" "--port" (str port)]
        _    (log/info! "[engine] launching Lightpanda on port " port)
        pb   (doto (ProcessBuilder. ^java.util.List (java.util.ArrayList. ^java.util.Collection cmd))
               (.redirectOutput ProcessBuilder$Redirect/DISCARD)
               (.redirectErrorStream true))
        proc (.start pb)
        pid  (.pid proc)]
    (write-auto-launch-lock! port session pid)
    ;; Wait up to 15s for the CDP endpoint to come up
    (loop [deadline (+ (System/currentTimeMillis) 15000) wait 5]
      (cond
        (> (System/currentTimeMillis) deadline)
        (do (.destroyForcibly proc)
            (clear-auto-launch-lock! port)
            (throw (ex-info (str "Lightpanda did not start within 15 seconds on port " port)
                     {:port port :engine "lightpanda" :pid pid})))

        (not (.isAlive proc))
        (do (clear-auto-launch-lock! port)
            (throw (ex-info (str "Lightpanda process exited immediately (exit " (.exitValue proc) "). Binary: " bin)
                     {:port port :engine "lightpanda" :exit-code (.exitValue proc)})))

        (probe-http-cdp port 500)
        (do (log/info! "[engine] Lightpanda ready on port " port)
            {:cdp-url (str "ws://127.0.0.1:" port)
             :port port
             :process proc
             :browser-pid pid})

        :else
        (do (Thread/sleep (long wait))
            (recur deadline (min 100 (* 2 (long wait)))))))))

(declare kill-auto-launched-browser!)

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
        _         (do (log/info! "auto-launch: starting " channel " on port " port)
                      (log/info! "auto-launch: temp profile: " tmp-dir))
        pb        (doto (ProcessBuilder. ^java.util.List (java.util.ArrayList. ^java.util.Collection cmd))
                    (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                    (.redirectErrorStream true))
        process   (.start pb)
        pid       (.pid process)]
    ;; Write lock file immediately to claim the port
    (write-auto-launch-lock! port session pid)
    ;; Do not return until Chrome has accepted CDP. A TCP listener alone may
    ;; precede the Chrome 144+ user-approval dialog; returning then merely
    ;; moves the timeout to connectOverCDP and leaves a misleading daemon state.
    (loop [deadline (+ (System/currentTimeMillis) 15000) wait 5]
      (cond
        (> (System/currentTimeMillis) deadline)
        (do
          (kill-auto-launched-browser! {:port port :browser-pid pid :tmp-dir tmp-dir})
          (throw (ex-info (str "Auto-launched browser did not start within 15 seconds on port " port)
                   {:port port :channel channel :pid pid})))

        (not (.isAlive process))
        (do
          (kill-auto-launched-browser! {:port port :browser-pid pid :tmp-dir tmp-dir})
          (throw (ex-info (str "Auto-launched browser process exited immediately (exit code: "
                            (.exitValue process) "). Binary: " binary)
                   {:port port :channel channel :exit-code (.exitValue process)})))

        (cdp-ready? port)
        (do
          (log/info! "auto-launch: " channel " ready on port " port " (PID " pid ")")
          {:cdp-url     (str "http://127.0.0.1:" port)
           :port        port
           :browser-pid pid
           :tmp-dir     tmp-dir})

        :else
        (do (Thread/sleep (long wait))
            (recur deadline (min 100 (* 2 (long wait)))))))))

(defn kill-auto-launched-browser!
  "Kills an auto-launched browser process and cleans up its lock file and temp dir."
  [{:keys [^long port ^long browser-pid ^String tmp-dir]}]
  (when browser-pid
    (try
      (when-let [ph (.orElse (java.lang.ProcessHandle/of browser-pid) nil)]
        (when (.isAlive ^java.lang.ProcessHandle ph)
          (log/info! "auto-launch: killing browser PID " browser-pid)
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

(defn- human-duration
  "Renders a millisecond span the way a person says it: `30 min`, `90s`, `500ms`."
  [^long ms]
  (cond
    (>= ms 60000) (str (quot ms 60000) " min")
    (>= ms 1000)  (str (quot ms 1000) "s")
    :else         (str ms "ms")))

;; --- Command ledger ---
;; Every command is recorded WHILE it runs. Without it a daemon busy inside a
;; 60-second browser call is indistinguishable from a dead one: the client
;; times out, kills the daemon, and throws a live browser away. `health` reads
;; this ledger and touches no Playwright object, so it still answers while
;; every browser call is stuck.
(defonce ^:private !ledger (atom {}))
(defonce ^:private !command-seq (atom 0))
(defonce ^:private !commands-total (atom 0))
(defonce ^:private !last-command-at (atom nil))
;; nil until this process starts serving. A `System/currentTimeMillis` evaluated
;; here is baked into the native image at BUILD time, so every daemon reported
;; the age of the binary — the first #125 report diagnosed a "9-day-old daemon"
;; that was one minute old. The clock starts in `start-daemon!`.
(defonce ^:private !daemon-started-at (atom nil))
(def ^:private default-action-timeout-ms 10000)
;; Serialises browser commands. An ATOM holding the lock, not the lock itself: a
;; command parked inside a Playwright call that no interrupt reaches never gives
;; its lock back, and every command after it then waited on a holder that would
;; never let go while `health` still answered "ok" (issue #125).
;; `abandon-wedged-command!` replaces the lock; the zombie keeps the old one.
(defonce ^:private !command-lock (atom (ReentrantLock. true)))

;; Commands this daemon gave up on, newest last. A session that lost a command
;; lost its page, its refs and its capture with it, so `health` says so.
(defonce ^:private !lost-commands (atom []))

(def ^:private control-actions
  "Commands that must answer even while browser execution is wedged."
  #{"health" "cancel" "close"})

(defn- ledger-start!
  "Records a command as in-flight on the calling thread. Returns its id."
  [^String action]
  (let [id (str "c" (swap! !command-seq inc))]
    (swap! !ledger assoc id {:id      id
                             :action  action
                             :phase   (if (contains? control-actions action) "running" "queued")
                             :started (System/currentTimeMillis)
                             :thread  (Thread/currentThread)})
    id))

(defn- ledger-finish!
  "Drops a finished command, stamps the daemon's last-activity time, and returns
   the entry it removed — the caller reads its cancel flag to explain a command
   that ended only because someone cancelled it."
  [id]
  (when-let [entry (get @!ledger id)]
    (swap! !ledger dissoc id)
    (swap! !commands-total inc)
    (reset! !last-command-at (System/currentTimeMillis))
    entry))

(def ^:private observer-actions
  "Diagnostics that watch the daemon rather than do work. They are ledger
   entries like anything else, but listing them would report every idle daemon
   as busy — `health` would always find itself running."
  #{"health" "cancel"})

(defn- ledger-entries
  "Real in-flight work, longest-running first, as wire-safe maps. Observer
   actions are left out so an idle daemon never reports itself busy."
  []
  (let [now (System/currentTimeMillis)]
    (->> (vals @!ledger)
      (remove #(contains? observer-actions (:action %)))
      (sort-by :started)
      (mapv (fn [{:keys [id action phase started cancel-requested]}]
              (let [ms (- now (long started))]
                (cond-> {:id id :action action :phase phase
                         :running_ms ms :running (human-duration ms)}
                  cancel-requested (assoc :cancel_requested true))))))))

(defn- ledger-cancel!
  "Interrupts in-flight commands — one `id`, or every one for nil/\"all\".

   Never cancels itself: `cancel all` is a ledger entry of its own, and
   interrupting that thread killed the very connection waiting for the answer.
   Interruption unblocks waits and sleeps; a call already parked inside the
   browser keeps running until the browser answers, so its entry stays listed
   as cancel_requested until it really ends."
  [id]
  (let [self    (Thread/currentThread)
        entries (remove #(or (identical? self (:thread %)) (= "cancel" (:action %)))
                  (vals @!ledger))
        targets (vec (if (or (nil? id) (= "all" id))
                       entries
                       (filter #(= id (:id %)) entries)))]
    (doseq [{eid :id t :thread} targets]
      (swap! !ledger (fn [m] (if (get m eid)
                               (assoc-in m [eid :cancel-requested] true)
                               m)))
      (when t (try (.interrupt ^Thread t) (catch Throwable _ nil))))
    (mapv (fn [{:keys [id action started]}]
            {:id id :action action
             :running_ms (- (System/currentTimeMillis) (long started))})
      targets)))

;; --- Browser liveness ---
;; The browser can die OUTSIDE the daemon: the user quits Chromium, it crashes,
;; the CDP endpoint goes away. `ensure-browser!` only relaunches when :browser
;; is nil, so stale handles used to make every later command answer
;; "Target page, context or browser has been closed" — for the rest of the
;; session, with no way back short of killing the daemon.
;; --- Renderer crashes ---
;; A crashed tab is NOT a closed tab. Playwright answers `isClosed` from local
;; state, so the handle still reports itself open while every call on it fails
;; with "Target crashed" — measured: kill the renderer process and `snapshot`,
;; `eval-js` and `get text` fail for the rest of the session, `open` answers OK
;; without navigating anywhere, and `health` still says "connected, page open"
;; (issue #127). Membership in this registry is what makes such a page count as
;; not open, so the ordinary reopen path replaces it.
(defonce ^:private !crashed-pages (atom #{}))

(defn- page-crashed?
  "True when `p` is a page whose renderer died.

   Params:
   `p` - Page instance or nil.

   Returns:
   Boolean."
  [p]
  (boolean (and p (contains? @!crashed-pages p))))

(defn- note-page-crash!
  "Records that `p`'s renderer died — once per page — and says so in the log.

   Params:
   `p` - Page instance or nil.

   Returns:
   nil."
  [p]
  (when p
    (let [[seen _] (swap-vals! !crashed-pages conj p)]
      (when-not (contains? seen p)
        (log/warn! "the page's renderer crashed — out of memory, or killed from "
          "outside. The tab is dead: the next command opens a fresh one, and the "
          "DOM, the refs and the scroll position it had are gone")))))

(defn- forget-crashed-page!
  "Drops `p` from the crash registry once the daemon has stopped using it, so the
   registry cannot grow for the life of a long session.

   Params:
   `p` - Page instance or nil.

   Returns:
   nil."
  [p]
  (when p (swap! !crashed-pages disj p) nil))

(defn- browser-connected?
  "True when the launched browser is still connected. Playwright answers from
   local connection state, so this never round-trips into a wedged browser.
   An unrecognised handle counts as alive — never tear down on a guess."
  []
  (when-let [b (:browser @!state)]
    (if (instance? com.microsoft.playwright.Browser b)
      (try (.isConnected ^com.microsoft.playwright.Browser b)
           (catch Throwable _ false))
      true)))

(defn- drain-driver-events!
  "Delivers the driver events Playwright has been holding for this client.

   Its Java client dispatches driver messages only while a call of its own is in
   flight, and nothing calls into it while the daemon waits for the next command:
   everything the browser has said since the last one sits unread. Measured on a
   live session — a tab closed in the browser still answered `isClosed` false 1.5 s
   later and `url` kept handing out the dead tab's address, and a `console.log`
   fired 300 ms after load reached its listener only when some later command
   happened to touch the browser. `spel console` reads an atom, so it never did.

   One round trip (measured: 1.2 ms) delivers all of it, listeners included. The
   page is only the object the call is addressed to; a page Playwright already
   knows is gone throws instead, which says the same thing.

   Returns:
   nil."
  []
  (let [p (:page @!state)]
    (when (instance? Page p)
      (try (page/wait-for-timeout p 0)
           (catch Throwable _ nil))))
  nil)

(defn- page-open?
  "True when the current page handle is still usable: open, and not the corpse of
   a crashed renderer. Both failures are answered the same way — reopen the tab —
   and only this predicate can tell the caller they happened.

   A handle spel does not recognise (a CDP or WebDriver page) counts as alive,
   but a crash recorded against it still wins: the crash is a fact, the liveness
   of a foreign handle is only a guess.

   `isClosed` is answered from client state that only `drain-driver-events!`
   refreshes, so a tab closed in the browser reads as open until the events the
   driver queued have been delivered."
  []
  (when-let [p (:page @!state)]
    (and (not (page-crashed? p))
      (if (instance? com.microsoft.playwright.Page p)
        (try (not (.isClosed ^com.microsoft.playwright.Page p))
             (catch Throwable _ false))
        true))))

(defn- adopt-foreign-pages!
  "Marks the current CDP attachment as FOREIGN — a browser the user owns — and
   records the tabs that already existed at attach time. Those tabs stay the
   user's property: spel never closes them, not on `tab close`, not on
   disconnect, not on daemon shutdown."
  [context]
  (swap! !state assoc
    :cdp-foreign true
    :adopted-context context
    :adopted-pages (into #{} (try (.pages ^BrowserContext context)
                                  (catch Exception _ nil)))
    :spel-pages #{}))

(defn- new-spel-page!
  "Creates a tab and records it as spel-owned. Provenance is positive: only tabs
   opened through this fn may ever be closed by spel in a foreign browser.

   Playwright hands back an ANOMALY MAP instead of a page when the browser is on
   its way out, and storing that map as `:page` poisoned the session for good:
   every later command — `health` included — died in 0 ms with
   `PersistentArrayMap cannot be cast to Page`, so the session answered nothing
   anyone could act on and never recovered (issue #125). Refusing it here turns
   that into an ordinary throw, which the recovery paths already answer by
   dropping the dead handles and relaunching the browser."
  [context]
  (let [p (core/new-page-from-context context)]
    (when (core/anomaly? p)
      (throw (ex-info (str "could not open a browser tab: "
                        (or (::anomaly/message p) "the browser went away"))
               {:error_code :browser_handle_lost})))
    (swap! !state update :spel-pages (fnil conj #{}) p)
    p))

(defn- user-owned-page?
  "True when `p` is a tab in a foreign (user-owned) browser that spel did not
   itself open. Tabs adopted at attach time and tabs the user opens afterwards
   are both user-owned."
  [p]
  (let [{:keys [cdp-foreign spel-pages]} @!state]
    (boolean (and cdp-foreign p (not (contains? (or spel-pages #{}) p))))))

(defn- foreign-browser?
  "True when the browser handle points at a browser spel did not launch."
  []
  (true? (:cdp-foreign @!state)))

(defn- close-spel-owned-pages!
  "Closes only the tabs spel itself opened in a foreign browser, leaving every
   user tab open — both those adopted at attach time and those opened later."
  []
  (when-let [context (:context @!state)]
    (doseq [p (try (vec (.pages ^BrowserContext context)) (catch Exception _ nil))]
      (when-not (user-owned-page? p)
        (try (core/close-page! p) (catch Exception _ nil))))
    (swap! !state assoc :spel-pages #{})))

(defn- drop-browser-handles!
  "Throws away the current Playwright handles so the next `ensure-browser!`
   relaunches from scratch. Reaping the orphaned driver can block, so it runs
   off the command path. Always returns :dead."
  []
  (let [pw (:pw @!state)]
    (swap! !state assoc :pw nil :browser nil :context nil :page nil
      :refs {} :counter 0 :cdp-connected false :cdp-foreign false
      :adopted-context nil :adopted-pages #{} :spel-pages #{})
    ;; The relaunched browser gets fresh listeners, so failures counted against
    ;; the old ones no longer describe the live session.
    (core/reset-handler-errors!)
    (submit-virtual (fn discard-dead-playwright []
                      (try (when pw (core/close! pw)) (catch Throwable _ nil))))
    :dead))

(def ^:private browser-gone-markers
  "Playwright/CDP wordings that mean the BROWSER, not the script, ended the
   call. Each one of them used to poison the session permanently."
  ["Target page, context or browser has been closed"
   "Target closed"
   "Browser has been closed"
   "Browser closed"
   "browser has been closed"
   "has disconnected"
   "Connection closed"
   "Playwright connection closed"
   "Failed to read message from driver"
   "pipe closed"])

(defn- browser-gone-message?
  "True when an error message says the browser went away."
  [msg]
  (boolean (and msg (some #(str/includes? ^String msg ^String %) browser-gone-markers))))

(def ^:private page-crash-markers
  "Playwright wordings that mean this PAGE's renderer process died while the
   browser itself is still connected. The tab cannot be used again — but the
   browser can, so the answer is a fresh tab, not a relaunch."
  ["Target crashed"
   "Page crashed"])

(defn- page-crashed-message?
  "True when an error message says the page's renderer died.

   Params:
   `msg` - Error message string or nil.

   Returns:
   Boolean."
  [msg]
  (boolean (and msg (some #(str/includes? ^String msg ^String %) page-crash-markers))))

(defn- page-crash-message
  "What the caller is told when a renderer crash ended their command: what died,
   what spel did about it, and what is gone.

   Params:
   `action` - String action name.
   `url`    - The URL the dead page was on, or nil.

   Returns:
   String."
  [action url]
  (str "the page's renderer crashed while '" action "' was running — out of memory, "
    "or killed from outside. The tab is gone, and with it the DOM, the refs and "
    "anything typed into it"
    (if (and url (not (contains? #{"" "about:blank"} url)))
      (str "; it was on " url)
      "")
    ". Re-run the command — spel opens a fresh tab for it."))

(defn- throwable-chain-message
  "Messages of a throwable and its causes, joined — Playwright's real reason is
   often one cause down."
  [^Throwable e]
  (loop [^Throwable t e acc [] n 0]
    (if (or (nil? t) (> n 8))
      (str/join " → " (remove nil? acc))
      (recur (.getCause t) (conj acc (.getMessage t)) (inc n)))))

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

(def ^:private ^:const cdp-lock-answer-slack-ms
  "Head start the CDP lock waiter keeps over the command budget.

   `run-guarded-command!` interrupts a command that outlives `command-budget-ms`
   and answers `command_timeout` — 'raise SPEL_COMMAND_BUDGET_MS' — which says
   nothing about the session that holds the endpoint. So the waiter gives up
   this many ms early and returns the conflict itself, with the owner's name."
  1500)

(def ^:dynamic *command-deadline-ms*
  "Absolute `System/currentTimeMillis` by which the command now running must
   answer, bound by `run-guarded-command!` from that command's budget. nil when
   no budget governs the call — control actions, and handlers called directly."
  nil)
;; --- Session idle timeout ---
;; Auto-shutdown daemon if no command is received within the configured window.
;; Default 30 minutes. It used to be 5, which is shorter than the gaps an agent
;; or a human leaves between commands while reading, thinking, or running another
;; tool: most observed shutdowns fired mid-workflow, and each one costs a cold
;; browser relaunch plus the page, refs and console state of the session.
;; Set SPEL_SESSION_IDLE_TIMEOUT env var (milliseconds) to override; 0 disables.
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

;; Tracks a dialog that is currently blocking the page. The info map holds
;; :type / :message / :default-value. `dialog_status` reads this atom.
(defonce ^:private !pending-dialog (atom nil))

;; A promise that the default dialog listener blocks on, waiting for an
;; explicit response via `dialog_accept` / `dialog_dismiss`. Delivered by those
;; handlers with a `[action text]` pair.
(defonce ^:private !pending-dialog-promise (atom nil))
(defonce ^:private !tracked-requests (atom []))
(def ^:private max-tracked-requests 500)
(defonce ^:private !routes (atom {}))

(def ^:private cdp-route-lock-exempt-actions
  "Actions allowed even when another session owns route interception lock.
   These are read-only queries, session management, or local buffer operations
   that don't drive the page and should never queue."
  #{"close" "health" "cancel" "session_info" "session_list"
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

(defn- cdp-target-id
  "CDP target id of tab `p`, or nil when it cannot be asked for one.

   A tab id is what another process can compare against: interception lives in
   a tab, so another session's routes are only this session's business when they
   name one of OUR tabs. Cached per page handle — the guard runs before every
   page command, and the id only changes when the tab does."
  [^Page p]
  (when p
    (if-let [cached (get (:cdp-tabs @!state) p)]
      cached
      (let [sess (page/new-cdp-session p)]
        (when-not (core/anomaly? sess)
          (try
            (let [info (core/cdp-send sess "Target.getTargetInfo")]
              (when-not (core/anomaly? info)
                (let [tid (get-in (json/read-json (str info)) ["targetInfo" "targetId"])]
                  (when (string? tid)
                    (swap! !state update :cdp-tabs assoc p tid)
                    tid))))
            (catch Exception e (warn "cdp-target-id" e) nil)
            (finally
              (try (core/cdp-detach! sess) (catch Exception _ nil)))))))))

(defn- current-tab-id
  "CDP target id of the tab this session drives right now, or nil when it has
   none yet."
  []
  (cdp-target-id (pg)))

(defn- active-cdp-route-lock
  "Returns an active lock map for one tab of cdp-url, clearing stale locks
   automatically."
  [^String cdp-url ^String tab-id]
  (when-let [lock (read-cdp-route-lock cdp-url tab-id)]
    (let [owner (get lock "session")]
      (cond
        (str/blank? owner)
        (do (clear-cdp-route-lock! cdp-url tab-id) nil)

        ;; Keep our own lock as active.
        (= owner (:session @!state))
        lock

        ;; If owner daemon is gone, clear stale lock.
        (not (daemon-running? owner))
        (do (clear-cdp-route-lock! cdp-url tab-id) nil)

        :else
        lock))))

(defn- cdp-route-lock-conflict
  "Returns conflict details when another session intercepts requests in the very
   tab this command would drive. Sessions driving other tabs of the same browser
   never conflict, and a session without a tab yet is about to open its own."
  [^String action]
  (let [session       (:session @!state)
        cdp-connected (true? (:cdp-connected @!state))
        cdp-url       (current-cdp-url)]
    (when (and cdp-connected
            (string? cdp-url)
            (not (contains? cdp-route-lock-exempt-actions action)))
      (when-let [tab-id (current-tab-id)]
        (when-let [lock (active-cdp-route-lock cdp-url tab-id)]
          (let [owner (get lock "session")]
            (when (and owner (not= owner session))
              {:owner-session owner
               :cdp-url cdp-url
               :tab tab-id})))))))

(defn- await-cdp-route-lock
  "Waits for the CDP route lock to be released by another session.
   Polls on a millisecond deadline (tick <= `!cdp-lock-poll-interval-s`) up to `!cdp-lock-wait-s` seconds.
   Returns nil when lock is cleared (or was never held), or a conflict map on timeout.
   If wait is 0, returns conflict immediately (fail-fast).

   The wait is capped by `*command-deadline-ms*`: the configured 120s outlives
   the 25s command budget, and a waiter interrupted by the budget reports the
   clock instead of the session holding the endpoint."
  [^String action]
  (let [max-wait-s  (long @!cdp-lock-wait-s)
        poll-s      (long @!cdp-lock-poll-interval-s)
        started-at  (System/currentTimeMillis)
        budget-end  (when-let [dl *command-deadline-ms*]
                      (- (long dl) (long cdp-lock-answer-slack-ms)))
        wall-end    (+ started-at (* max-wait-s 1000))
        deadline    (long (if budget-end
                            (min wall-end (long budget-end))
                            wall-end))
        wait-ms     (max 0 (- deadline started-at))
        ;; Tick fast (<= 100ms) so a released lock is picked up immediately
        ;; instead of after a whole configured second.
        tick-ms     (max 10 (min 100 (* poll-s 1000)))]
    (if-let [conflict (cdp-route-lock-conflict action)]
      (if (or (zero? max-wait-s) (zero? wait-ms))
        ;; Fail-fast — waiting is off, or the budget leaves no room to wait.
        conflict
        ;; Queue mode — poll until lock clears or timeout
        (do
          (log/info! "CDP lock held by session '" (:owner-session conflict)
            "' — waiting (0/" (quot wait-ms 1000) "s)...")
          (loop []
            (if (>= (System/currentTimeMillis) deadline)
              ;; Timeout — return conflict for error response
              (do
                (log/info! "CDP lock timeout after " (quot wait-ms 1000)
                  "s — blocking action '" action "'")
                conflict)
              (do
                (Thread/sleep (long tick-ms))
                (if-let [_still-locked (cdp-route-lock-conflict action)]
                  (recur)
                  ;; Lock cleared!
                  (do
                    (log/info! "CDP lock acquired while waiting")
                    nil)))))))
      ;; No conflict
      nil)))

(defn- release-cdp-route-lock-if-owned!
  "Clears every tab lock this session took when it installed routes. Routes are
   session-wide, so the lock names every tab the session was driving: releasing
   only the tab in front left the others locked by a session that no longer
   intercepts anything. The keys are remembered from the write — a session also
   releases on close, when its browser is already gone and no tab can be asked
   for its id any more."
  []
  (when-let [{:keys [cdp tabs]} (:cdp-route-lock @!state)]
    (doseq [tab tabs]
      (when-let [lock (read-cdp-route-lock cdp tab)]
        (when (= (:session @!state) (get lock "session"))
          (clear-cdp-route-lock! cdp tab))))
    (swap! !state dissoc :cdp-route-lock)))

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
;; Session-wide ceiling: each tab keeps `max-window-per-page` entries of its
;; own, so what bounds a many-tab session is this, not the tab count.
(def ^:private max-window-total 5000)
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
(defonce ^:private !capture-ceiling-warned? (atom false))

(defn- capture-budget-left?
  "True while this session may still record console and network entries.

   A session that has recorded `max-session-total` of them stops recording: the
   windows only ever SHOW a few thousand, and every event costs work on the
   thread pumping the Playwright pipe. It used to stop SILENTLY and for good —
   `spel console` kept answering with entries from hours earlier, nothing said
   why, and no clear brought it back. That is the shape issue #125 reported. Now
   it says so once, and `console clear` / `network clear` give the budget back.

   Returns:
   True when there is budget left."
  []
  (or (< (long @!session-entry-count) (long max-session-total))
    (do (when (compare-and-set! !capture-ceiling-warned? false true)
          (log/warn! "this session has captured " max-session-total
            " console and network entries — capture stops here to keep the browser responsive; "
            "`spel console clear` or `spel network clear` resumes it"))
      false)))

(defn- conj-window
  "Appends `entry` to a sliding-window vector, dropping whatever falls out of
   `limit`.

   Trimming with `subvec` alone is the leak it looks like a fix for: a SubVector
   pins the vector it was cut from, so a window that reads 1000 entries kept
   every entry the session had ever seen (measured: 20000 held behind a
   1000-entry window). Copying the survivors into a fresh vector is what
   actually releases them."
  [w entry ^long limit]
  (let [updated (conj w entry)
        n       (long (count updated))]
    (if (> n limit)
      (into [] (subvec updated (- n limit)))
      updated)))

(defn- conj-tab-window
  "Appends `entry` to a sliding window that gives every TAB its own `limit`.

   One budget for the whole session let the tab a session had already left push
   out the entries of the tab it was actually driving: a page logging in a
   background tab evicted the console of the page under test. Eviction is per
   `:tab`; only the window as a whole carries a session-wide ceiling.

   Params:
   `w` - window vector.
   `entry` - entry map, carrying the `:tab` it was captured from.
   `limit` - entries kept per tab.

   Returns:
   The updated window vector."
  [w entry ^long limit]
  (let [updated (conj w entry)
        tab     (:tab entry)
        kept    (long (count (filterv #(= tab (:tab %)) updated)))
        updated (if (> kept limit)
                  (let [drop-at (long (first (keep-indexed (fn [i e] (when (= tab (:tab e)) i))
                                               updated)))]
                    (into [] (concat (subvec updated 0 drop-at)
                               (subvec updated (inc drop-at)))))
                  updated)
        n       (long (count updated))]
    (if (> n (long max-window-total))
      (into [] (subvec updated (- n (long max-window-total))))
      updated)))

(defn- dropped-entries
  "The entries `before` lost when a capture window became `after`.

   A window only ever DROPS entries — the oldest of a tab that hit its own limit,
   then whatever the session-wide ceiling pushed out — so the two vectors agree
   up to the first drop and realign after it. `expected` says how many drops to
   look for, which ends the walk at the last one instead of at the end of the
   window.

   Params:
   `before`   - Window vector before the change.
   `after`    - Window vector after it.
   `expected` - How many entries left.

   Returns:
   Vector of the dropped entries."
  [before after ^long expected]
  (if (pos? expected)
    (loop [i 0 j 0 out []]
      (cond
        (= (count out) expected) out
        (>= i (count before))    out
        (and (< j (count after))
          (identical? (nth before i) (nth after j))) (recur (inc i) (inc j) out)
        :else (recur (inc i) j (conj out (nth before i)))))
    []))

(defn- forget-detail!
  "Forgets what stands behind refs that just left a capture window.

   `network get @nN` and `console get @cN` answer from these maps, so a ref has
   to live exactly as long as the listing that shows it. Retaining by GLOBAL
   entry number was neither long enough nor short enough: every tab gets its own
   slice of the window, so a second busy tab dropped the detail of entries the
   first tab's listing still printed — refs that could no longer be fetched — and
   one shadow-cljs dev page load (~1000 module files, request and response
   headers each) stayed behind for the life of the daemon.

   Params:
   `!full`   - Atom of ref-id -> full entry.
   `!parked` - Atom of entry number -> parked Response, or nil.
   `entries` - The entries that left the window.

   Returns:
   nil."
  [!full !parked entries]
  (when (seq entries)
    (let [ref-ids (mapv #(subs (str (:ref %)) 1) entries)]
      (swap! !full (fn [m] (reduce dissoc m ref-ids)))
      (when !parked
        (let [numbers (keep #(parse-long (subs (str %) 1)) ref-ids)]
          (swap! !parked (fn [m] (reduce dissoc m numbers)))))))
  nil)

(defn- track-in-window!
  "Appends `entry` to `!window` and forgets the detail of whatever it pushed out.

   Params:
   `!window` - Atom holding the sliding window.
   `!full`   - Atom of ref-id -> full entry.
   `!parked` - Atom of entry number -> parked Response, or nil.
   `entry`   - The entry to append.

   Returns:
   nil."
  [!window !full !parked entry]
  (let [[before after] (swap-vals! !window conj-tab-window entry max-window-per-page)]
    (forget-detail! !full !parked
      (dropped-entries before after (- (inc (count before)) (count after))))))

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
    "survey" "routes" "inspect" "overview" "debug" "emulate" "markdownify"
    "back" "forward" "reload" "drag_to" "tap" "swipe" "set_input_files"})

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
    (swap! !pages conj-window {:ref (str "@" ref-id)
                               :url url
                               :status (or status 200)
                               :title (or title "")
                               :navigated_at (System/currentTimeMillis)}
      max-window-per-page)
    (str "@" ref-id)))

(defonce ^:private !network-responses (atom (sorted-map)))  ;; entry number -> Response, read off the dispatch thread

(defn- request-timing
  "Playwright's own `Timing` for a request, or nil when the driver has none.

   Reads already-materialized data, so it is safe on the event dispatch thread."
  ^Timing [^Request req]
  (try (.timing req) (catch Exception _ nil)))

(defn- request-duration-ms
  "Milliseconds from a request leaving the browser to its response starting.

   `Timing/responseStart` is measured from `startTime` and reads -1 for a phase
   that never happened — served from cache, aborted, failed — so a negative
   reading is reported as 0 rather than as a nonsensical duration."
  [^Request req]
  (if-let [^Timing t (request-timing req)]
    (let [response-start (.-responseStart t)]
      (if (pos? response-start) (long (Math/round response-start)) 0))
    0))

(defn- request-elapsed-ms
  "Milliseconds a request has been alive, from `Timing/startTime` until now.

   The only duration a failed request can report: it never reached a response,
   so `responseStart` stays -1 while the wait before the failure is exactly
   what someone debugging a refused connection or a DNS timeout is after."
  [^Request req]
  (if-let [^Timing t (request-timing req)]
    (let [start (.-startTime t)]
      (if (pos? start)
        (max 0 (- (System/currentTimeMillis) (long (Math/round start))))
        0))
    0))

(defn- track-network-entry!
  "Tracks a network request/response with full details into the sliding window.

   Runs on Playwright's event dispatch thread, so it reads only what the event
   already carries (`.headers`, `.postData`, `.status`). An API call that
   round-trips to the driver (`.allHeaders`, `.text`) re-enters the dispatch
   loop from inside the handler, stacking one nested handler frame per in-flight
   response — a burst of a few hundred subresources exhausts the stack and every
   response is lost to a StackOverflowError. The Response is parked in
   `!network-responses` so `network get @nN` can read the full headers and body
   later, from the command thread where blocking is safe."
  [^Response resp tab]
  (when (capture-budget-left?)
    (let [^Request req (.request resp)
          entry-no (long (swap! !network-counter inc))
          ref-id (str "n" entry-no)
          resource-type (.resourceType req)
          page-url (try (.url (.page (.frame req))) (catch Exception _ "unknown"))
          req-headers (try (into {} (.headers req)) (catch Exception _ {}))
          resp-headers (try (into {} (.headers resp)) (catch Exception _ {}))
          post-data (try (.postData req) (catch Exception _ nil))
          req-body-preview (safe-parse-json-body post-data 500)
          duration (request-duration-ms req)
          entry {:ref (str "@" ref-id)
                 :tab tab
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
                                      :body    nil}}}
          full-entry {:ref (str "@" ref-id)
                      :tab tab
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
                                 :body nil}}]
      (swap! !network-full assoc ref-id full-entry)
      ;; Parked responses follow the window entry by entry: only a ref the
      ;; window still lists can be fetched, and its handle is released with it.
      (swap! !network-responses assoc entry-no resp)
      (track-in-window! !network-window !network-full !network-responses entry)
      (swap! !session-entry-count inc))))

(defn- materialize-network-entry!
  "Fills in a tracked entry's full headers and response body, on demand.

   Called from a command handler — never from an event handler — because both
   reads round-trip to the Playwright driver. The enriched entry replaces the
   tracked one, so the body is fetched at most once per response.

   Params:
   `ref-id` - String ref without the leading @ (\"n3\").

   Returns:
   The entry map, enriched when the response is still readable, or nil when no
   such ref was tracked."
  [ref-id]
  (when-let [entry (get @!network-full ref-id)]
    (if-let [^Response resp (get @!network-responses (parse-long (subs ref-id 1)))]
      (let [^Request req  (.request resp)
            req-headers   (try (into {} (.allHeaders req))
                               (catch Exception _ (get-in entry [:request :headers])))
            resp-headers  (try (into {} (.allHeaders resp))
                               (catch Exception _ (get-in entry [:response :headers])))
            body          (when (should-capture-response-body? (:resource_type entry) resp-headers)
                            (try (.text resp) (catch Exception _ nil)))
            enriched      (-> entry
                            (assoc-in [:request :headers] req-headers)
                            (assoc-in [:response :headers] resp-headers)
                            (assoc-in [:response :body] body))]
        (swap! !network-full assoc ref-id enriched)
        (swap! !network-responses dissoc (parse-long (subs ref-id 1)))
        enriched)
      entry)))

(defn- track-console-entry!
  "Tracks a console message into the sliding window of the tab it came from."
  [^ConsoleMessage msg tab]
  (when (capture-budget-left?)
    (let [entry-no (long (swap! !console-counter inc))
          ref-id (str "c" entry-no)
          page-url (try (.url (.page msg)) (catch Exception _ "unknown"))
          ;; Get stack trace if available via location
          location (try
                     (let [^String loc (.location ^ConsoleMessage msg)]
                       (when (and loc (not (.isEmpty loc))) loc))
                     (catch Exception _ nil))
          entry {:ref (str "@" ref-id)
                 :tab tab
                 :type (.type msg)
                 :text (.text msg)
                 :timestamp (System/currentTimeMillis)
                 :page page-url
                 :page_ref (current-page-ref page-url)}
          entry (if location (assoc entry :stack location) entry)]
      (swap! !console-full assoc ref-id entry)
      (track-in-window! !console-window !console-full nil entry)
      (swap! !session-entry-count inc))))

(defn- track-response!
  "Appends a response summary to the tracked-requests ring buffer, capped at
   `max-tracked-requests` most-recent entries. Also feeds the TASK-013 sliding window."
  [^Response resp tab]
  (let [^Request req (.request resp)
        entry {:url    (.url req)
               :method (.method req)
               :status (.status resp)
               :resource-type (.resourceType req)
               :tab tab}]
    (swap! !tracked-requests conj-tab-window entry max-tracked-requests)
    ;; TASK-013: also track into enriched sliding window
    (track-network-entry! resp tab)))

(defn- track-failed-request!
  "Tracks a request that ended without a response: DNS failure, refused
   connection, TLS error, an aborted route.

   `Page.onResponse` never fires for these, so until this listener existed they
   were captured by nobody and `spel network` came back empty for exactly the
   request being debugged. The entry carries the browser's own text in `:error`
   (`net::ERR_CONNECTION_REFUSED`) and 0 as its status — the HAR convention for
   a response that never arrived, which keeps `:status` a number for every
   entry. Nothing is
   parked in `!network-responses` because there is no Response to read a body
   from, so `network get @nN` answers from the tracked entry alone."
  [^Request req tab]
  (let [failure (or (try (.failure req) (catch Exception _ nil)) "request failed")
        resource-type (try (.resourceType req) (catch Exception _ "other"))
        method (try (.method req) (catch Exception _ "GET"))
        url (try (.url req) (catch Exception _ ""))]
    (swap! !tracked-requests conj-tab-window
      {:url url :method method :status 0 :error failure
       :resource-type resource-type :tab tab}
      max-tracked-requests)
    (when (capture-budget-left?)
      (let [entry-no (long (swap! !network-counter inc))
            ref-id (str "n" entry-no)
            page-url (try (.url (.page (.frame req))) (catch Exception _ "unknown"))
            req-headers (try (into {} (.headers req)) (catch Exception _ {}))
            post-data (try (.postData req) (catch Exception _ nil))
            base {:ref (str "@" ref-id)
                  :tab tab
                  :method method
                  :url url
                  :resource_type resource-type
                  :status 0
                  :error failure
                  :duration_ms (request-elapsed-ms req)
                  :timestamp (System/currentTimeMillis)
                  :page page-url
                  :page_ref (current-page-ref page-url)}
            entry (assoc base :preview {:request  {:headers (truncate-keys req-headers 5)
                                                   :body    (safe-parse-json-body post-data 500)}
                                        :response {:headers {}
                                                   :body    nil}})
            full-entry (assoc base
                         :request {:headers req-headers :body post-data}
                         :response {:headers {} :body nil})]
        (swap! !network-full assoc ref-id full-entry)
        (track-in-window! !network-window !network-full !network-responses entry)
        (swap! !session-entry-count inc)))))

;; Tabs this session has driven: Page -> the tab key ("t1", "t2", ...) every
;; entry captured from it is tagged with. Playwright keeps every listener until
;; you remove it and spel removes none, so this map is also the only thing
;; standing between one page and two copies of the same handler.
(defonce ^:private !tabs (atom {:next 1 :pages {}}))

(defn- page-live?
  "True when `pg` is still open. A closed page can never fire another event, so
   it leaves the instrumented set instead of being remembered forever.

   Params:
   `pg` - Page instance.

   Returns:
   Boolean."
  [pg]
  (try (not (.isClosed ^Page pg)) (catch Throwable _ false)))

(defn- live-context-pages
  "Pages of `context` that are still open.

   `.pages` still hands back a tab that closed a moment ago, and reading its url
   or title then throws \"Target page, context or browser has been closed\" —
   the very wording `dispatch-with-recovery` reads as a DEAD BROWSER. Listing
   tabs after somebody closed one therefore relaunched the whole session.

   Params:
   `context` - BrowserContext instance.

   Returns:
   Vector of Page instances."
  [context]
  (filterv page-live? (core/context-pages context)))
(defn- tab-key!
  "Returns `[tab-key instrumented-before?]` for `pg`, assigning this session's
   stable id for that tab the first time the page is seen.

   Every console, page-error and network entry is tagged with this key, which is
   what scopes `spel console`, `spel errors` and `spel network requests` to the
   tab the session is driving. Tabs closed since the last call are forgotten on
   the way through, so a long session does not remember dead pages.

   Params:
   `pg` - Page instance.

   Returns:
   `[String Boolean]`."
  [pg]
  (let [[before after] (swap-vals! !tabs
                         (fn [{:keys [next pages]}]
                           (let [live (into {} (filter (fn [[p _]] (or (= p pg) (page-live? p)))) pages)]
                             (if (contains? live pg)
                               {:next next :pages live}
                               {:next  (inc (long next))
                                :pages (assoc live pg (str "t" next))}))))]
    [(get-in after [:pages pg]) (contains? (:pages before) pg)]))

(defn- instrument-page!
  "Registers spel's console, page-error and response listeners on `pg` — exactly
   once per page, however often it is called — and tags everything they capture
   with that page's tab key.

   Nine command paths used to re-register the whole set on the page they were
   about to use, and Playwright never removes a listener you do not remove
   yourself. Opening a window, reconnecting over CDP or calling `console_start`
   therefore left the page running two, three, four copies of every handler:
   each copy recorded the same console line again and repeated the same work on
   the same response event, on a stack already carrying the copies before it.
   That accumulation is what issue #125 saw as a session that goes quiet after
   navigations.

   Session-wide routes are (re)applied here too, so a tab this session drives is
   a tab its mocks reach.

   Params:
   `pg` - Page instance; nil is ignored.

   Returns:
   `pg`."
  [pg]
  (when pg
    (let [[tab seen?] (tab-key! pg)]
      (when-not seen?
        (page/on-console pg (fn [^ConsoleMessage msg]
                              (swap! !console-messages conj-tab-window
                                {:type (.type msg) :text (.text msg) :tab tab}
                                max-window-per-page)
                              (track-console-entry! msg tab)))
        (page/on-page-error pg (fn [error]
                                 (swap! !page-errors conj-tab-window
                                   {:message (str error) :tab tab}
                                   max-window-per-page)))
        ;; The crash listener only RECORDS the death: it runs on whichever
        ;; thread is pumping the Playwright pipe — often the very command parked
        ;; inside the call the crash just killed — and acting from there would
        ;; interrupt whatever that thread happens to be doing. The command
        ;; watchdog reads the record and interrupts its own worker.
        (page/on-crash pg (fn [_] (note-page-crash! pg)))
        (page/on-response pg (fn [^Response resp] (track-response! resp tab)))
        ;; A request that never reaches a response — refused connection, DNS
        ;; failure, TLS error, a route that aborted it — fires ONLY this event.
        ;; Listening to responses alone left `spel network` empty for exactly
        ;; the request someone opened the tool to debug.
        (page/on-request-failed pg (fn [^Request req] (track-failed-request! req tab)))
        ;; A tab the PAGE opens — target=_blank, window.open — is a tab of this
        ;; session too, and nothing listened to it until somebody switched to it:
        ;; everything it logged, threw or fetched while it loaded was lost, and
        ;; the listing had no id to switch to. Playwright dispatches this event
        ;; before any event of the new page, so instrumenting from here misses
        ;; nothing that page does.
        (page/on-popup pg (fn [^Page popup] (instrument-page! popup)))
        ;; Routes belong to the SESSION, not to whichever tab was current when
        ;; `network route` ran. Registered only there, they silently were not in
        ;; force on a tab opened afterwards — `tab new`, a popup, the tab that
        ;; replaces a crashed renderer, the page restored after a relaunch — so
        ;; the request went to the real server while the session, and the CDP
        ;; route lock it holds, still reported the mock as active.
        (doseq [[url handler] @!routes]
          (page/route! pg url handler)))))
  pg)
(defn- pg ^Page [] (:page @!state))
(defn- ctx ^BrowserContext [] (:context @!state))

(defn- tab-key-of
  "This session's stable id for tab `p` — the key every entry captured from that
   tab carries — or nil for a tab this session has never driven.

   Reads only: handing out a key is `tab-key!`'s job, and listing tabs must not
   claim ownership of one the session does not drive.

   Params:
   `p` - Page instance.

   Returns:
   String or nil."
  [p]
  (get-in @!tabs [:pages p]))

(defn- tab-by-key
  "Returns the live page this session tagged `k` (\"t3\"), or nil when no live
   tab carries that id.

   A tab id names ONE page for as long as that page lives. A tab NUMBER is a
   position in the browser's own list and shifts the moment anyone — spel, the
   site or the person at the keyboard — closes a tab before it.

   Params:
   `k` - Tab id string, or nil.

   Returns:
   Page instance or nil."
  [k]
  (when-not (str/blank? (str k))
    (some (fn [[p t]] (when (and (= t k) (page-live? p)) p)) (:pages @!tabs))))

(defn- current-tab
  "Returns the tab key of the page this session drives, or nil before one is
   instrumented."
  []
  (tab-key-of (pg)))

(defn- session-live-pages
  "Every live page this session drives — the tab in front first, then every
   other tab it has instrumented that is still open.

   What belongs to the SESSION rather than to one tab — routes, the CDP route
   lock — is applied through this. Applied to the current page alone it silently
   skipped every tab that was already open beside it.

   Scoped to the CURRENT context: `!tabs` remembers every page this session has
   ever instrumented, and a page of a context the session has left is no longer
   one of its tabs."
  []
  (let [current (pg)
        context (ctx)
        ours?   (fn [p]
                  (boolean (and context
                             (page-live? p)
                             (try (identical? context (.context ^Page p))
                                  (catch Exception _ false)))))]
    (into (if (ours? current) [current] [])
      (remove #(or (identical? % current) (not (ours? %))))
      (keys (:pages @!tabs)))))

(defn- session-fallback-page
  "The live tab this session should land on when the tab it was driving is gone —
   the highest id it still drives, which is the tab it was on before that one — or
   nil when it drives no other live tab.

   `spel tab close` has always fallen back to a live tab; the SAME tab closed with
   the mouse got a blank new one instead, so one event left the session in two
   different places and dropped an empty tab into the user's own browser every
   time it happened.

   Returns:
   Page instance or nil."
  ^Page []
  (->> (session-live-pages)
    (keep (fn [p] (when-let [t (tab-key-of p)] [p t])))
    (sort-by (fn [[_ t]] (long (or (parse-long (subs (str t) 1)) 0))))
    last
    first))

(defn- claim-cdp-route-lock!
  "Takes the CDP route lock for every tab this session drives and remembers the
   set, so the release clears exactly those tabs.

   Routes are session-wide. A lock naming only the tab that happened to be in
   front let another session take one of this session's OTHER intercepted tabs
   and be told nothing was intercepting there.

   Returns:
   The set of locked CDP target ids, or nil when this session is not on CDP."
  []
  (when (true? (:cdp-connected @!state))
    (when-let [cdp-url (current-cdp-url)]
      (let [session (:session @!state)
            tabs    (into #{} (keep cdp-target-id) (session-live-pages))]
        (doseq [tab tabs]
          (write-cdp-route-lock! cdp-url tab session))
        (when (seq tabs)
          (swap! !state update :cdp-route-lock
            (fn [prev]
              {:cdp cdp-url
               :tabs (into (if (= (:cdp prev) cdp-url) (:tabs prev #{}) #{}) tabs)})))
        tabs))))

(defn- tab-entries
  "Returns the captured entries of the tab this session is on — or every tab's
   when `all?`.

   Console messages, page errors and responses belong to the page that produced
   them, so the default answer is the tab the next command will act on; `--all`
   is how a session sees what a tab it left recorded."
  [entries all?]
  (if all?
    (vec entries)
    (let [tab (current-tab)]
      (filterv #(= tab (:tab %)) entries))))

(defn- without-tab-entries
  "Drops the current tab's entries from `entries`, or all of them when `all?`."
  [entries all?]
  (if all?
    []
    (let [tab (current-tab)]
      (filterv #(not= tab (:tab %)) entries))))

(defn- clear-window!
  "Clears the current tab's entries from `!window` — every tab's when `all?` — and
   forgets the detail behind every ref that left with them.

   `console clear` and `network clear` used to drop the listing and keep the
   detail, so `console get @c1` still answered for a message the session had just
   cleared, and every parked Response stayed alive behind it.

   A clear also hands back the session's capture budget: a session that stopped
   recording at `max-session-total` records again from here.

   Params:
   `!window` - Atom holding the sliding window.
   `!full`   - Atom of ref-id -> full entry.
   `!parked` - Atom of entry number -> parked Response, or nil.
   `all?`    - True to clear every tab.

   Returns:
   nil."
  [!window !full !parked all?]
  (reset! !session-entry-count 0)
  (reset! !capture-ceiling-warned? false)
  (let [[before after] (swap-vals! !window without-tab-entries all?)]
    (forget-detail! !full !parked
      (dropped-entries before after (- (count before) (count after))))))

(defn- focus-page!
  "Makes `p` the tab this session drives — instrumented BEFORE it becomes the
   current page.

   Playwright binds console, page-error and response events to ONE page. `tab
   new` and `tab <n>` used to move `:page` without installing them, so every
   command after a tab switch drove a tab nothing was listening to while `spel
   console` kept answering with the tab the session had left."
  [p]
  (instrument-page! p)
  (swap! !state assoc :page p)
  ;; instrument-page! puts this session's routes on the tab it focuses; the lock
  ;; that warns another session off an intercepted tab has to follow them there.
  (when (seq @!routes) (claim-cdp-route-lock!))
  p)

(defn- note-tab-loss!
  "Records that the tab this session was driving died, and where the session
   landed, for the one command that has to be told about it.

   Params:
   `dead-tab` - Tab id the session lost, or nil.
   `landed`   - Page the session drives from now on.
   `fresh?`   - True when `landed` is a tab this recovery had to open.

   Returns:
   nil."
  [dead-tab ^Page landed fresh?]
  (swap! !state assoc :tab-loss
    {:from  dead-tab
     :to    (tab-key-of landed)
     :url   (try (page/url landed) (catch Throwable _ nil))
     :fresh fresh?})
  nil)

(defn- clear-tab-loss!
  "Forgets a recorded tab loss. Every command starts without one, so a loss is
   only ever reported by the command that ran into it.

   Returns:
   nil."
  []
  (swap! !state dissoc :tab-loss)
  nil)

(defn- raise-tab-loss!
  "Fails the command that was sent to a tab which is gone.

   A command must not be answered from a page the caller never chose. The tab
   closed under it and the session moved elsewhere, so `url` used to answer
   `about:blank` as if the session had navigated there itself, `eval-js` ran
   against that blank page, and only the commands that need a loaded page said
   anything at all. Saying it once, here, is what tells the caller to navigate
   again. The record is cleared on the way out, so the next command runs
   normally on the tab named in the message.

   Returns:
   nil when no tab was lost; throws ex-info {:error_code :tab_closed} when one was."
  []
  (when-let [{:keys [from to url fresh]} (:tab-loss @!state)]
    (clear-tab-loss!)
    (throw (ex-info
             (str "The tab this session was driving"
               (when from (str " (" from ")"))
               " is gone — it was closed outside spel. "
               (if fresh
                 (str "spel opened a fresh tab (" to ") in the same browser: navigate again with `spel open <url>`.")
                 (str "spel is now driving " to
                   (when url (str " (" url ")"))
                   ": re-run the command, or pick another tab with `spel tab list`."))
               (when from
                 (str " What " from " captured is still there: `spel console --all`, `spel network --all`.")))
             {:error_code :tab_closed}))))

(defn- ensure-live-browser!
  "Reconciles daemon state with the browser that actually exists.
   Returns :dead when dead handles were dropped (the next launch recreates
   them), :page-reopened when only the page had been closed, else nil.

   Playwright flips `isConnected` only once its driver has NOTICED the
   disconnect, so this catches a browser already known to be gone; the one that
   dies mid-command is caught by `dispatch-with-recovery`.

   A tab reopened here is instrumented here. Leaving that to the caller is what
   `replace-crashed-page!` forgot: the session came back on a tab nothing was
   listening to, so console, page errors and network stayed silent for the rest
   of it while `health` kept answering \"ok\".

   A tab closed outside spel is answered with a tab this session already drives —
   a fresh one only when it drives none — and the loss is recorded, so the command
   that ran into it is told instead of being answered from a page nobody asked
   for."
  []
  (let [{:keys [browser context page]} @!state]
    (cond
      (and browser (not (browser-connected?)))
      (do
        (log/warn! "browser is gone (closed outside the daemon) — dropping dead handles; the next command relaunches it")
        (drop-browser-handles!))

      (and browser context page (not (page-open?)))
      (let [crashed? (page-crashed? page)
            dead-tab (tab-key-of page)]
        (try
          ;; A crashed tab is replaced, never fallen back on: `replace-crashed-page!`
          ;; re-navigates the tab it is handed to the URL that died, and doing that
          ;; to a live tab this session drives would throw that tab's page away.
          (let [fallback (when-not crashed? (session-fallback-page))
                landed   (or fallback (new-spel-page! context))]
            (swap! !state assoc :refs {} :counter 0)
            (forget-crashed-page! page)
            (focus-page! landed)
            (if crashed?
              (log/warn! "the page's renderer had crashed — opened a fresh tab; "
                "navigate again, the old page and its refs are gone")
              (do
                (note-tab-loss! dead-tab landed (nil? fallback))
                (if fallback
                  (log/warn! "the tab spel was driving" (when dead-tab (str " (" dead-tab ")"))
                    " was closed outside the daemon — fell back to " (tab-key-of landed)
                    ", a tab this session still drives")
                  (log/warn! "the tab spel was driving" (when dead-tab (str " (" dead-tab ")"))
                    " was closed outside the daemon — opened a fresh one (" (tab-key-of landed) ")"))))
            :page-reopened)
          (catch Throwable _
            (forget-crashed-page! page)
            (drop-browser-handles!))))

      :else nil)))

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
        ;; Merge launch-flag opts into the reloaded context so device emulation,
        ;; user-agent overrides, etc. survive state-restore. Without this, any
        ;; --device or --user-agent flag passed to the CLI gets silently dropped
        ;; when a persisted state file exists.
        (let [flags      (get @!state :launch-flags {})
              device-preset (when-let [dn (get flags "device")]
                              (devices/resolve-device-by-name dn))
              ctx-opts   (cond-> (or device-preset {})
                           true                       (assoc :storage-state-path state-path)
                           (get flags "user-agent")   (assoc :user-agent (get flags "user-agent"))
                           (get flags "ignore-https-errors") (assoc :ignore-https-errors true))
              new-ctx (core/new-context (:browser @!state) ctx-opts)
              new-pg  (new-spel-page! new-ctx)]
          (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
          (instrument-page! new-pg))))))

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

(defn- install-default-dialog-handler!
  "Installs the default Dialog listener on the page. Behavior:

   - `alert` and `beforeunload` are auto-accepted immediately, unless the user
     passed `--no-auto-dialog`. These dialogs are informational and blocking
     on them would hang every page navigation.
   - `confirm` and `prompt` are STORED in `!pending-dialog` and left open.
     The caller must respond explicitly via `dialog_accept`/`dialog_dismiss`,
     giving the agent full control over decisions.
   - When `--no-auto-dialog` is set, EVERY dialog (including alert) goes into
     `!pending-dialog` and requires explicit handling.

   Responses to other commands while a dialog is pending carry a `:warning`
   field so the LLM notices the blocked state."
  [^Page page no-auto?]
  (reset! !pending-dialog nil)
  (reset! !pending-dialog-promise nil)
  (when-let [old @!dialog-handler]
    (try (.offDialog page old) (catch Exception _ nil)))
  (let [handler (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (let [^Dialog d dialog
                          dtype   (.type d)
                          info    {:type    dtype
                                   :message (.message d)
                                   :default-value (.defaultValue d)}]
                      (if (and (not no-auto?)
                            (contains? #{"alert" "beforeunload"} dtype))
                        (try (.accept d)
                             (catch Exception _ nil))
                        ;; Park until an explicit response arrives on the
                        ;; promise. Playwright dispatches onDialog on its own
                        ;; event thread so blocking here is safe.
                        (let [p (promise)]
                          (reset! !pending-dialog info)
                          (reset! !pending-dialog-promise p)
                          (let [[action text] @p]
                            (try
                              (case action
                                :accept  (.accept d (or text ""))
                                :dismiss (.dismiss d)
                                (.dismiss d))
                              (catch Exception _ nil))
                            (reset! !pending-dialog nil)
                            (reset! !pending-dialog-promise nil)))))))]
    (reset! !dialog-handler handler)
    (.onDialog page handler)))

(defn- install-allowed-domains!
  "Installs a page-level request listener that closes the page (aborts the
   navigation) when a request targets a non-allowed host.

   Uses a REQUEST event listener rather than Playwright routing because we
   want the policy to apply to every future page created in the context, and
   event listeners run reliably in native-image. The listener runs in-process
   and synchronously inspects each request's URL.

   Blocks: navigation and sub-resources. Non-HTTP(S) URLs (data:, blob:,
   about:, chrome-extension:) always pass through.

   No-op when `csv` is nil or blank."
  [^Page page ^String csv]
  (when-not (or (nil? csv) (str/blank? csv))
    (let [allowed?  (security/compile-domain-patterns csv)
          ;; Direct reify keeps the closure visible to native-image's
          ;; ahead-of-time analysis and mirrors the idiom used by the
          ;; network_route handler (which is exercised by test-cli.sh).
          consumer  (reify java.util.function.Consumer
                      (accept [_ route]
                        (try
                          (let [^com.microsoft.playwright.Route r route
                                req (.request r)
                                url (.url ^Request req)]
                            (if (security/request-allowed? allowed? url)
                              (.resume r)
                              (.abort r "blockedbyclient")))
                          (catch Exception _
                            nil))))]
      (.route ^Page page "**/*" consumer))))

(defn- ensure-browser!
  "Lazily starts browser on first command. Uses launch-flags from !state.

   Three modes:
   1. --profile with directory → Playwright launchPersistentContext
   2. --auto-launch → launch browser with debug port, connect via CDP
   3. Normal → Playwright launch (or --cdp connect)

   Auto-loads persisted session state unless --no-persist is set."
  []
  ;; Reconcile with reality first: a browser killed outside the daemon leaves
  ;; handles that fail every command until they are dropped, and a tab reopened
  ;; by that reconcile comes back instrumented.
  (ensure-live-browser!)
  (when-not (:browser @!state)
    (let [flags       (get @!state :launch-flags {})
          ;; --profile can be either a filesystem path (existing behavior) or
          ;; a Chrome profile display name like "Default" / "Work". A name is
          ;; resolved to the user's real Chrome profile directory and cloned
          ;; to a temp dir, so the persistent context launches with existing
          ;; cookies/sessions without mutating the user's live profile.
          profile-arg (get flags "profile")
          ;; --profile accepts either a filesystem path (existing behavior) or
          ;; a Chrome profile name/display-name. For names, clone the live
          ;; profile into a fresh temp user-data dir AND capture the resolved
          ;; profile-directory — the caller must pass that to Chrome via
          ;; --profile-directory=<dir> so Chrome picks the right subdir from
          ;; the clone (without this, Chrome always defaults to 'Default').
          profile-clone   (when (and profile-arg (profile/name? profile-arg))
                            (let [result (profile/clone-profile! profile-arg)]
                              (log/info! "[profile] cloned Chrome profile '"
                                profile-arg "' (dir="
                                (:profile-directory result) ") → "
                                (:user-data-dir result))
                              ;; Track the temp clone so `close` can delete it.
                              (swap! !state assoc :profile-temp-dir (:user-data-dir result))
                              result))
          profile-dir (cond
                        profile-clone (:user-data-dir profile-clone)
                        ;; Path case: expand `~/foo` so Chrome can find it.
                        profile-arg   (profile/expand-tilde profile-arg)
                        :else         nil)
          ;; Chrome arg to select the profile subdir from the clone.
          profile-directory-arg (when profile-clone
                                  (str "--profile-directory=" (:profile-directory profile-clone)))
          extensions  (get flags "extensions")
          _           (when (seq extensions)
                        (doseq [ext extensions]
                          (when-not (.isDirectory (java.io.File. ^String ext))
                            (throw (ex-info (str "Extension path does not exist or is not a directory: " ext)
                                     {:extension-path ext}))))
                        (log/info! "Loading " (count extensions) " extension(s): "
                          (str/join ", " extensions))
                        (log/info! "Note: --extension is Chromium-only; extensions are ignored on Firefox/WebKit"))
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
                        (update :ignore-default-args (fnil conj []) "--disable-extensions")
                        ;; --profile <name> Chrome profile clone: tell Chrome
                        ;; which subdir to use inside the cloned user-data dir.
                        profile-directory-arg
                        (update :args (fnil conj []) profile-directory-arg)
                        ;; --allow-file-access: let file:// URLs read local
                        ;; files (both args are needed — agent-browser parity).
                        (get flags "allow-file-access")
                        (update :args (fnil into [])
                          ["--allow-file-access-from-files" "--allow-file-access"]))
          ;; Resolve --device "iPhone 14" etc. to a Playwright device preset.
          ;; The preset contributes viewport, device-scale-factor, is-mobile,
          ;; has-touch, and user-agent — all merged into ctx-opts. If the user
          ;; also passes --user-agent, their override wins because it comes
          ;; after in the cond->.
          device-preset (when-let [device-name (get flags "device")]
                          (or (devices/resolve-device-by-name device-name)
                            (throw (ex-info
                                     (str "Unknown device: " device-name
                                       ". Run 'spel set-device' with no args or see `devices/available-device-names`.")
                                     {:device device-name}))))
          browser-type  (get flags "browser" "chromium")
          device-opts   (if (= "firefox" browser-type)
                          ;; Firefox rejects :is-mobile / :has-touch
                          (dissoc device-preset :is-mobile :has-touch)
                          device-preset)
          ctx-opts    (cond-> (or device-opts {})
                        (get flags "user-agent")          (assoc :user-agent (get flags "user-agent"))
                        (get flags "ignore-https-errors")  (assoc :ignore-https-errors true)
                        (get flags "headers")             (assoc :extra-http-headers
                                                            (try (json/read-json (get flags "headers"))
                                                                 (catch Exception _ {})))
                        (get flags "storage-state")       (assoc :storage-state-path (get flags "storage-state"))
                        (get flags "download-path")       (assoc :accept-downloads true))
          pw          (core/create)]
      (cond
        ;; ── Mode 0: --engine lightpanda → spawn Lightpanda, connect CDP ───
        ;; Lightpanda is a non-Chromium headless browser; we run it as a
        ;; CDP server subprocess and then reuse the existing connectOverCDP
        ;; path to drive it through Playwright. The Lightpanda process is
        ;; tracked like auto-launch so it gets cleaned up on daemon stop.
        (= "lightpanda" (get flags "engine"))
        (let [result  (launch-lightpanda! {:session (:session @!state)})
              cdp-url (:cdp-url result)
              _       (swap! !state assoc-in [:launch-flags "cdp"] cdp-url)
              _       (persist-launch-flags!)
              browser (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String cdp-url)
              contexts (.contexts ^com.microsoft.playwright.Browser browser)
              context  (if (seq contexts)
                         (first contexts)
                         (check-anomaly!
                           (core/new-context browser)
                           "Lightpanda: failed to create context via CDP"))
              pages   (.pages ^com.microsoft.playwright.BrowserContext context)
              pg-inst (if (seq pages)
                        (first pages)
                        (check-anomaly!
                          (new-spel-page! context)
                          "Lightpanda: failed to create page"))]
          (swap! !state assoc
            :pw pw :browser browser :context context :page pg-inst
            :cdp-connected true
            :auto-launch-info {:port        (:port result)
                               :browser-pid (:browser-pid result)
                               :tmp-dir     nil
                               :engine      "lightpanda"}))

        ;; ── Mode 1: --profile with directory → Playwright persistent ──────
        ;; Use Playwright's launchPersistentContext for custom profile dirs.
        profile-dir
        (let [_           (log/info! "[Mode 1] Persistent context with profile: " profile-dir)
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
                                  (new-spel-page! context)
                                  "Failed to create page in persistent context"))]
          (swap! !state assoc :pw pw :browser browser :context context :page pg-inst
            :persistent-profile true))

        ;; ── Mode 2: --auto-launch → launch browser + CDP connect ─────────
        (get flags "auto-launch")
        (let [_       (log/info! "[Mode 2] Auto-launch browser with CDP")
              channel (get flags "channel" "chrome")
              session (:session @!state)
              result  (auto-launch-browser!
                        {:channel  channel
                         :session  session
                         :headless (:headless @!state)})
              cdp-url (:cdp-url result)
              _       (log/info! "auto-launch: connecting via CDP to " cdp-url)
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
                           (new-spel-page! context)
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
        (let [_       (log/info! "[Mode 3] "
                        (if (get flags "cdp")
                          (str "CDP connect: " (get flags "cdp"))
                          "Standard launch"))
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
            ;; CDP: reuse the REAL browser's existing context (login sessions,
            ;; cookies), but always drive a fresh spel-owned tab inside it so the
            ;; user's own tabs are never hijacked or navigated away.
            (let [contexts (.contexts ^com.microsoft.playwright.Browser browser)
                  context  (if (seq contexts)
                             (first contexts)
                             (check-anomaly!
                               (core/new-context browser)
                               "No existing context found via CDP and failed to create one"))
                  _        (adopt-foreign-pages! context)
                  ;; spel always works in its OWN tab: never hijack a user tab.
                  pg-inst  (check-anomaly!
                             (new-spel-page! context)
                             "Failed to open a spel-owned tab in the CDP browser")]
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
                            (new-spel-page! context)
                            "Failed to create page")]
              (swap! !state assoc :pw pw :browser browser :context context :page pg-inst)))))
      ;; Common setup for all paths
      (let [pg-inst (:page @!state)]
        (page/set-default-timeout! pg-inst
          (double (or (get flags "timeout") default-action-timeout-ms)))
        ;; Default dialog handler: auto-accept alert/beforeunload unless
        ;; --no-auto-dialog is set; queue confirm/prompt for explicit handling.
        (install-default-dialog-handler! pg-inst (boolean (get flags "no-auto-dialog")))
        (reset! !console-messages [])
        (reset! !page-errors [])
        (reset! !tracked-requests [])
        (instrument-page! pg-inst)
        ;; Auto-load persisted session state (not for persistent/CDP profiles)
        (when-not (or profile-dir (get flags "cdp"))
          (auto-load-session-state!))))))

;; =============================================================================
;; Ref Resolution
;; =============================================================================

(defn- ref? [^String s]
  (boolean (re-matches #"@e[a-z0-9]+" s)))

(defn- publish-refs!
  "Makes a captured snapshot's refs the current ones.

   The generation counter is how `dispatch-cmd` tells a command that published
   refs from one that may have invalidated them."
  [snap]
  (swap! !state
    (fn [state]
      (assoc state
        :refs (:refs snap)
        :counter (:counter snap)
        :refs-stale? false
        :refs-generation (inc (long (or (:refs-generation state) 0))))))
  snap)

(defn- refresh-snapshot!
  "Captures a fresh snapshot and updates daemon ref state."
  []
  (publish-refs! (snapshot/capture-snapshot (pg))))

(defn- ensure-refs-current!
  "Captures a snapshot only when the stored refs are missing or may be
   outdated — for a caller that needs every ref of the page as it is now."
  []
  (let [state @!state]
    (when (or (:refs-stale? state) (empty? (:refs state)))
      (refresh-snapshot!))))

(defn- missing-ref-error
  "The error for an @ref no capture can find, listing what is addressable."
  [^String selector ^String ref-id]
  (let [refs (:refs @!state)
        hint (if (seq refs)
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
    (ex-info (str "Ref " ref-id " not found.\n" hint)
      {:selector selector :found false :stale-ref true})))

(defn- ref-locator!
  "Locates the element an @ref names.

   A capture stamps data-pw-ref onto the DOM, so a ref outlives most actions and
   matching it costs one selector query, while the walk that would refresh every
   ref costs 170 ms on a 12k-element page and seconds on a large app. The walk
   is therefore paid here, when a ref is used and its stamp is gone, and never
   after an action whose tree nobody reads."
  [^String selector ^String ref-id]
  (let [state @!state
        loc   (snapshot/resolve-ref (pg) ref-id)]
    (cond
      (and (contains? (:refs state) ref-id) (not (:refs-stale? state))) loc
      (pos? (locator/count-elements loc))                               loc
      :else
      (do
        (refresh-snapshot!)
        (if (contains? (:refs @!state) ref-id)
          (snapshot/resolve-ref (pg) ref-id)
          (throw (missing-ref-error selector ref-id)))))))

(defn- resolve-selector
  "Resolves a selector — an @ref through the snapshot that stamped it, anything
   else as a regular locator."
  [^String selector]
  (if (ref? selector)
    (ref-locator! selector (str/replace selector #"^@" ""))
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
                 (locator/count-elements loc)
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
  "Clicks a selector, failing fast with diagnostics instead of waiting out a
   Playwright timeout on an element that is not on the page.

   `opts` (optional map) is passed through to `locator/click`. Commonly used
   keys: `:modifiers` (e.g. `[:meta]` for cmd-click → opens link in new tab),
   `:button`, `:position`. The default `:timeout 5000` is merged in unless the
   caller overrides it."
  ([^String selector] (click-with-ref-recovery! selector nil))
  ([^String selector opts]
   (let [loc  (resolve-selector selector)
         diag (locator-diagnostics loc)]
     (when-not (:found diag)
       (throw (ex-info
                (str "Selector not found: " selector "\n"
                  "Click failed for " selector "\n"
                  "  - Element found: No\n"
                  "  - Suggestion: run 'snapshot -i' and retry click.")
                {:selector selector :found false})))
     (try
      ;; Keep click failures fast in automation/CDP scenarios. Caller-supplied
      ;; opts (e.g. :modifiers [:meta]) are merged on top of the default timeout.
       (unwrap-anomaly! (locator/click loc (merge {:timeout 5000} opts)))
       (catch Throwable t
         (throw-click-error! selector (locator-diagnostics loc) t))))))

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

(defn- live-page
  "Returns the page a command should act on, reconciling daemon state with the
   browser that actually exists first.

   A browser killed — or a tab closed — outside the daemon leaves a stale page
   handle, and once `drop-browser-handles!` has run, a nil one. Calling into that
   threw a message-less NullPointerException which no recovery path recognised,
   so every later command failed forever while `spel session` still reported a
   healthy connection (issue #109). Reconciling here re-attaches instead, and a
   page that is still missing fails with a message that says what to do.

   A command sent to a tab that is GONE fails here too: the session has landed
   somewhere else, and answering from there would hide that the tab the caller
   addressed no longer exists."
  ^Page []
  (when-not (and (:page @!state) (page-open?) (browser-connected?))
    (ensure-browser!))
  (raise-tab-loss!)
  (or (pg)
    (throw (ex-info "No browser page available. Open one first: spel open <url>"
             {:error_code :no_page_loaded}))))

(defn- live-context
  "Returns the context a command should act on, attaching a browser first — the
   `live-page` contract for the commands that address the CONTEXT rather than one
   page: tabs, cookies, storage state, tracing, geolocation, offline.

   `(ctx)` is nil until something launches the browser, so every one of those as a
   session's FIRST command died inside Playwright with a message-less
   NullPointerException, reported as a browser that went away outside the daemon
   when none had ever been started.

   A tab lost meanwhile is deliberately NOT raised here: `spel tab list` and
   `spel tab <id>` are how a caller recovers from that loss, and the loss report
   names them.

   Returns:
   BrowserContext instance."
  ^BrowserContext []
  (when-not (and (:page @!state) (page-open?) (browser-connected?))
    (ensure-browser!))
  (or (ctx)
    (throw (ex-info "No browser context available. Open one first: spel open <url>"
             {:error_code :no_page_loaded}))))
(defn- ensure-page-loaded!
  "Throws if no page has been navigated to (still on about:blank). Reconciles
   dead handles first, so a browser that went away is re-attached rather than
   poisoning every command that follows."
  []
  (let [url (page/url (live-page))]
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

(defn- throwable-origin
  "The first stack frame belonging to spel or Playwright — the call site that
   actually failed.

   The GraalVM native image runs with helpful NullPointerExceptions OFF, so a
   null page handle surfaces as a throwable with no message at all; without the
   frame the wire could only report a bare class name and the failure was not
   diagnosable (issue #109)."
  [^Throwable e]
  (when e
    (let [ours? (fn [^StackTraceElement f]
                  (let [c (.getClassName f)]
                    (or (str/starts-with? c "com.blockether")
                      (str/starts-with? c "com.microsoft.playwright"))))
          frames (seq (.getStackTrace e))
          ^StackTraceElement f (or (first (filter ours? frames)) (first frames))]
      (when f
        (str (.getClassName f) "." (.getMethodName f)
          (when-let [file (.getFileName f)]
            (str " (" file ":" (.getLineNumber f) ")")))))))

(defn- default-error-message
  "Returns a human-friendly fallback message when runtime provided no details.
   A throwable without a message still reports WHERE it was thrown."
  ([]
   "unexpected browser error (no details from runtime)")
  ([^Throwable e]
   (if e
     (str "unexpected browser error (" (.getSimpleName (.getClass e))
       (if-let [origin (throwable-origin e)]
         (str " at " origin)
         ", no details from runtime")
       ")")
     (default-error-message))))

(defn- throwable-message
  "Best text for a throwable. A `nth` past the end of a vector arrives as an
   ExceptionInfo wrapping a message-less IndexOutOfBoundsException, so a bare
   `.getMessage` would report nothing at all; the cause's message, then the
   failing class's name AND the frame it was thrown from, still tell the caller
   what happened and where."
  [^Throwable e]
  (let [cause (.getCause e)]
    (or (.getMessage e)
      (some-> cause .getMessage)
      (let [^Throwable t (or cause e)
            cls (.getSimpleName (.getClass t))]
        (if-let [origin (throwable-origin t)]
          (str cls " at " origin)
          cls)))))

(def ^:private ^:dynamic *error-source*
  "`{:source <code> :lang :js|:clj}` for the command currently being dispatched
   (a JS `script`/`expression`, a SCI `code` form). `error-response` hands it to
   `errors/concise`, which turns the reported line:column into a numbered
   excerpt with a caret under the failing position."
  nil)

(defn- inner-eval-source
  "Walks a throwable's cause chain for a nested evaluation's `ex-data`.

   SCI re-wraps whatever a form throws in its own ex-info, so the JS source a
   Clojure form ran (`:spel/source`, tagged by `core/tag-eval-source`) is only
   reachable through the causes. Bounded, so a self-referencing cause chain
   cannot loop forever."
  [^Throwable e]
  (loop [t e n 0]
    (when (and t (< n 16))
      (let [d (ex-data t)]
        (if (:spel/source d)
          d
          (recur (.getCause t) (inc n)))))))

(defn- error-context
  "Reads the failure position — and, for a nested evaluation, the source that
   actually failed — out of an `ex-data`/anomaly map and its throwable.

   A SCI form that evaluates JS reports its own {:line :column}, but the
   failure and the `at <expression>:LINE:COLUMN` frame in its message belong to
   the inner JS (`:spel/source`). That source therefore wins, and the outer
   Clojure position is dropped so no caret lands in the wrong language."
  ([data] (error-context data nil))
  ([data ^Throwable e]
   (if-let [inner (if (:spel/source data) data (some-> e inner-eval-source))]
     {:source (:spel/source inner) :lang (or (:spel/lang inner) :js)}
     (select-keys data [:line :column]))))

(defn- strip-bare-error-prefix
  "Drops the class-less `Error: ` head the Playwright driver puts on a browser
   error, so a refusal renders `Error: Node is not an HTMLInputElement` instead
   of `Error: Error: Node is not an HTMLInputElement` — every consumer (CLI,
   JSON `:error`) states 'error' itself. A NAMED class says something the head
   alone does not (`TypeError:`, `ReferenceError:`) and is kept."
  [^String msg]
  (if (and (string? msg) (str/starts-with? msg "Error: "))
    (let [rest' (str/triml (subs msg (count "Error: ")))]
      (if (str/blank? rest') msg rest'))
    msg))
(defn- error-response
  "Creates a structured error response map from an error message string.

   The wire `:error` carries the CONCISE message (`errors/concise`): the
   repeated Playwright `Error { … }` envelopes, driver-internal stack frames
   and an over-long call log are stripped so the caller reads the failure
   instead of the runtime. The untouched original is logged at debug level,
   so `SPEL_LOG_LEVEL=debug` + `spel logs` still shows the full dump.

   Structured context (call_log, selector) and hints are parsed from the RAW
   text — those patterns match the unfiltered message.
   The evaluated source (`*error-source*`) is passed along, so an eval failure
   gains a numbered code excerpt with a caret under the offending column.
   `pos` optionally carries the {:line :column} SCI reports in `ex-data` for a
   Clojure form, which has no `at <expression>` frame to read the spot from.
   Returns {:success false :error msg} with optional :call_log and :selector."
  ([^String msg] (error-response msg nil))
  ([^String msg pos]
   (let [raw    (if (str/blank? msg) (default-error-message) msg)
         short  (strip-bare-error-prefix
                  (or (errors/concise raw (merge *error-source* pos)) raw))
         parsed (parse-playwright-error raw)
         human  (humanize-error raw)]
     (when-not (= raw short)
       (log/debug! "raw error: " raw))
     (cond-> {:success false :error short}
       (:call_log parsed) (assoc :call_log (:call_log parsed))
       (:selector parsed) (assoc :selector (:selector parsed))
       (:hint human) (assoc :hint (:hint human))
       (:error_code human) (assoc :error_code (name (:error_code human)))))))

(defn- humanize-error
  "Adds actionable, human-readable hints to known error patterns.

   Returns map with optional keys:
   - :hint string
   - :error_code keyword"
  [^String msg]
  (cond
    (or (nil? msg)
      (str/blank? msg))
    {:hint "An unexpected browser error occurred. Retry once with --debug; if it repeats, run `spel close` and try again."
     :error_code :unknown_error}

    ;; A null browser/page handle used to reach the wire as a bare
    ;; "NullPointerException" — no message, no stack, nothing to act on.
    (re-find #"\bNullPointerException\b" msg)
    {:hint "A browser handle was null — the browser or tab went away outside the daemon. spel drops dead handles and re-attaches on the next command, so retry it; if it repeats, run `spel close` and start a fresh session."
     :error_code :browser_handle_lost}

    (or (= "Unknown error" msg)
      (str/starts-with? msg "unexpected browser error"))
    {:hint "An unexpected browser error occurred. Retry once with --debug; if it repeats, run `spel close` and try again."
     :error_code :unknown_error}

    (str/includes? msg "No page loaded")
    {:hint "Open a page first, for example: `spel open https://example.org`."
     :error_code :no_page_loaded}

    (str/includes? msg "No browser")
    {:hint "Start a browser session first with `spel open <url>` or `spel eval-sci '(spel/start!)'`."
     :error_code :no_browser}

    (str/includes? msg "The tab this session was driving")
    {:hint "The tab is gone, the session is not: `spel tab list` shows the tabs it still drives, `spel console --all` still has what the closed tab captured. Re-run the command, or `spel open <url>` to navigate the tab spel landed on."
     :error_code :tab_closed}
    (re-find #"(?i)target page, context or browser has been closed|TargetClosedError" msg)
    {:hint "The browser or tab closed during the command. Re-open the page and retry. For CDP runs, verify the debug browser is still running."
     :error_code :target_closed}

    ;; Playwright's own wording is stale: headed Chromium prints a PDF fine.
    ;; What is really missing is a PDF backend in Firefox and WebKit.
    (str/includes? msg "PDF generation is only supported")
    {:hint "PDF is Chromium-only — Firefox and WebKit have no PDF backend at all. Re-run this session with --browser chromium; headed is fine."
     :error_code :pdf_unsupported}

    (re-find #"(?i)timeout .* exceeded|timed out" msg)
    {:hint "The operation timed out. Verify selector/page state and consider increasing --timeout for slow pages."
     :error_code :timeout}

    (re-find #"\b(ReferenceError|TypeError|SyntaxError|RangeError)\b" msg)
    {:hint "The page's own JavaScript threw. `at <expression>:line:col` points inside the code you passed — try it in the browser console, and guard optional elements with `?.` (e.g. `document.querySelector('#x')?.textContent`)."
     :error_code :js_error}

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

(defn- ensure-allowed-domains-installed!
  "Installs the allowed-domains route handler on the current page if the flag
   is set and the install hasn't already happened for this browser session.

   Called from navigation handlers rather than ensure-browser! because route
   handlers must be installed on a page that is ready to receive requests —
   registering on the blank initial page does not survive the first navigation
   cleanly across all Playwright states."
  []
  (let [flags (get @!state :launch-flags {})]
    (when-let [csv (get flags "allowed-domains")]
      (when-not (:allowed-domains-installed @!state)
        (install-allowed-domains! (pg) csv)
        (swap! !state assoc :allowed-domains-installed true)))))

(defmethod handle-cmd "navigate" [_ {:strs [url screenshot screenshot-path raw-input
                                            viewport-width viewport-height]}]
  (ensure-browser!)
  (ensure-allowed-domains-installed!)
  (page/validate-url url (or raw-input url))
  ;; Set viewport before navigation so the page renders at the requested size.
  (when (and viewport-width viewport-height)
    (page/set-viewport-size! (pg) (long viewport-width) (long viewport-height)))
  ;; `page/navigate` answers an anomaly instead of throwing, and a discarded one
  ;; turned a blocked, refused or unresolvable address into a successful command
  ;; reporting the URL of the tab the caller never left (issue #131).
  (unwrap-anomaly! (page/navigate (pg) url))
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
      (let [out-path (Path/of ^String path-str (into-array String []))]
        (when-let [parent (.getParent out-path)]
          (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
        (java.nio.file.Files/write out-path ss-bytes
          ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [])))
      {:url (page/url (pg)) :title (page/title (pg)) :screenshot path-str :size (alength ss-bytes)
       :viewport (page/viewport-size (pg))})
    (cond-> {:url (page/url (pg)) :title (page/title (pg))}
      viewport-width (assoc :viewport (page/viewport-size (pg)))
      (page-description) (assoc :description (page-description)))))

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
        max-nodes      (get params "max_nodes")
        no-network?    (get params "no_network")
        no-console?    (get params "no_console")
        device         (:device @!state)
        snap           (if all?
                         (snapshot/capture-full-snapshot (pg))
                         (snapshot/capture-snapshot (pg) (cond-> {}
                                                           sel           (assoc :scope sel)
                                                           styles?       (assoc :styles true)
                                                           styles-detail (assoc :styles-detail styles-detail)
                                                           max-nodes     (assoc :max-nodes max-nodes)
                                                           device        (assoc :device device))))]
    ;; A crashed renderer or an aborted capture is surfaced on the command that
    ;; caused it — never rendered as a successful snapshot with an empty tree.
    (if (anomaly/anomaly? snap)
      snap
      (let [_          (publish-refs! snap)
            tree       (cond-> (filter-snapshot-tree (:tree snap) params)
                         (:truncated snap)
                         (str "\n" (snapshot/truncation-note (:truncated snap))))
            structured (build-structured-refs (:refs snap))]
        (cond-> {:snapshot tree :refs_count (:counter snap) :url (page/url (pg)) :title (page/title (pg))
                 :refs structured :pages @!pages}
          (:truncated snap)           (assoc :truncated (:truncated snap))
          (:viewport snap)            (assoc :viewport (:viewport snap))
          (:device snap)              (assoc :device (:device snap))
          (page-description)          (assoc :description (page-description))
          no-network?                 (dissoc :network)
          no-console?                 (dissoc :console)
          (not no-network?)           (assoc :network (tab-entries @!network-window false))
          (not no-console?)           (assoc :console (tab-entries @!console-window false)))))))

(defn- new-tab-modifier
  "Returns the keyboard modifier that opens a link in a new tab on the current
   host OS. macOS uses Cmd (:meta), everywhere else uses Ctrl (:control)."
  []
  (if (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac")
    :meta
    :control))

(defmethod handle-cmd "click" [_ {:strs [selector] :as params}]
  (ensure-page-loaded!)
  (when (str/blank? (str selector))
    (throw (ex-info "click requires a selector or @ref" {})))
  (let [new-tab? (boolean (get params "new-tab"))
        opts     (cond-> {}
                   new-tab? (assoc :modifiers [(new-tab-modifier)]))]
    (click-with-ref-recovery! selector opts))
  (cond-> {:clicked selector}
    (get params "new-tab") (assoc :new-tab true)))

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
  {:filled selector})

(defmethod handle-cmd "type" [_ {:strs [selector text]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/type-text (resolve-selector selector) text))
  {:typed selector})

(defmethod handle-cmd "press" [_ {:strs [key selector]}]
  (ensure-page-loaded!)
  (if selector
    (unwrap-anomaly! (locator/press (resolve-selector selector) key))
    (.press ^Keyboard (page/page-keyboard (pg)) key))
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
  {:checked selector})

(defmethod handle-cmd "uncheck" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/uncheck (resolve-selector selector)))
  {:unchecked selector})

(defmethod handle-cmd "select" [_ {:strs [selector values]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/select-option (resolve-selector selector) values))
  {:selected selector})

(defmethod handle-cmd "dblclick" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/dblclick (resolve-selector selector)))
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
  ;; --annotate flag: delegate to helpers/overview! which injects ref labels
  ;; onto the page, captures a full-page screenshot, then cleans up. Returns
  ;; {:path :size :annotated {:count :entries}} so the caller can map visual
  ;; labels back to snapshot refs for subsequent interactions.
  (if (get params "annotate")
    (let [path-str (get params "path")
          opts     (cond-> {}
                     path-str                              (assoc :path path-str)
                     (contains? params "show-badges")      (assoc :show-badges (get params "show-badges"))
                     (contains? params "show-dimensions")  (assoc :show-dimensions (get params "show-dimensions"))
                     (contains? params "show-boxes")       (assoc :show-boxes (get params "show-boxes"))
                     (get params "scope")                  (assoc :scope (get params "scope"))
                     (get params "all")                    (assoc :all-frames? true))
          result   (helpers/overview! (pg) opts)]
      (if (:bytes result)
        (let [tmp-path (str (System/getProperty "java.io.tmpdir")
                         java.io.File/separator
                         "spel-screenshot-"
                         (System/currentTimeMillis) ".png")
              _        (Files/write
                         (Path/of ^String tmp-path (into-array String []))
                         ^bytes (:bytes result)
                         ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))]
          {:path tmp-path :size (alength ^bytes (:bytes result)) :annotated (:annotated result)})
        {:path (:path result) :size (:size result) :annotated (:annotated result)}))
    ;; Plain (non-annotated) screenshot path
    (let [launch-flags   (get @!state :launch-flags {})
          ;; Global --screenshot-format / --screenshot-quality / --screenshot-dir
          ;; flow through launch-flags and shape both the Playwright screenshot
          ;; options and the default output directory.
          fmt            (some-> (get launch-flags "screenshot-format")
                           str/lower-case
                           keyword)
          quality        (some-> (get launch-flags "screenshot-quality") long)
          default-dir    (or (get launch-flags "screenshot-dir")
                           (System/getProperty "java.io.tmpdir"))
          default-ext    (if (= :jpeg fmt) ".jpg" ".png")
          path-str       (get params "path")
          full-page?     (get params "fullPage" false)
          crop-content?  (get params "cropToContent" false)
          sel            (get params "selector")
          ss-opts        (cond-> {}
                           full-page?  (assoc :full-page true)
                           fmt         (assoc :type fmt)
                           (and (= :jpeg fmt) quality) (assoc :quality quality))
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
                              (page/screenshot (pg) ss-opts)
                              (finally
                                (when original-vp
                                  (page/set-viewport-size! (pg) (long (:width original-vp)) (long (:height original-vp))))))
                          ;; Normal path (no crop) — no viewport to restore
                            (if sel
                              (locator/locator-screenshot (resolve-selector sel))
                              (page/screenshot (pg) ss-opts)))]
      (if path-str
        (let [out-path (Path/of ^String path-str (into-array String []))]
          (when-let [parent (.getParent out-path)]
            (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
          (Files/write out-path ss-bytes
            ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
          {:path path-str :size (alength ss-bytes)})
        (let [_ (.mkdirs (java.io.File. ^String default-dir))
              tmp-path (str default-dir
                         java.io.File/separator
                         "spel-screenshot-"
                         (System/currentTimeMillis) default-ext)]
          (java.nio.file.Files/write
            (Path/of ^String tmp-path (into-array String []))
            ss-bytes
            ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
          {:path tmp-path :size (alength ss-bytes)})))))

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
        _         (ensure-refs-current!)
        refs      (:refs @!state)
        annotated (if (seq refs)
                    (annotate/inject-overlays! (pg) refs opts)
                    {:count 0 :entries []})]
    {:annotated annotated :refs_total (:counter @!state)}))

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
    (publish-refs! snap)
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
        {:path tmp-path :size (alength ^bytes (:bytes result)) :annotated (:annotated result)})
      {:path (:path result) :size (:size result) :annotated (:annotated result)})))

(defmethod handle-cmd "debug" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [page-diag    (helpers/debug! (pg))
        ;; Enrich with daemon-tracked console messages
        console-msgs (tab-entries @!console-messages false)
        console-errs (filterv #(#{"error" "warning"} (:type %)) console-msgs)
        ;; Enrich with tracked page errors
        page-errs    (tab-entries @!page-errors false)
        ;; Enrich with failed network requests (4xx/5xx)
        net-reqs     (tab-entries @!tracked-requests false)
        failed-net   (filterv #(>= (long (:status %)) 400) net-reqs)
        ;; Optionally clear after read
        _            (when (get params "clear")
                       (swap! !console-messages without-tab-entries false)
                       (swap! !page-errors without-tab-entries false))]
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
             :path tmp-path :size (alength ^bytes (:bytes ov-result)) :annotated (:annotated ov-result)})
          {:device device-name :preset (:preset result)
           :path (:path ov-result) :size (:size ov-result) :annotated (:annotated ov-result)})))))

(defmethod handle-cmd "evaluate" [_ {:strs [script base64]}]
  ;; No ensure-page-loaded! here on purpose: evaluating JS against a blank
  ;; page is legitimate (bootstrapping a fixture, probing a replacement tab
  ;; created by `tab close`), and Playwright evaluates fine on about:blank.
  (let [result (unwrap-anomaly! (page/evaluate (pg) script))]
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
                      (page/scroll (pg) direction opts)))]
    result))

(defmethod handle-cmd "back" [_ _]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/go-back (pg)))
  {:url (page/url (pg))})

(defmethod handle-cmd "forward" [_ _]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/go-forward (pg)))
  {:url (page/url (pg))})

(defmethod handle-cmd "reload" [_ _]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/reload (pg)))
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
  (let [new-pg (new-spel-page! (live-context))]
    ;; Instrument before the first navigation, or nobody is listening when the
    ;; new tab logs while it loads.
    (focus-page! new-pg)
    (when-let [url (get params "url")]
      (unwrap-anomaly! (page/navigate new-pg url)))
    {:tab (tab-key-of new-pg) :url (page/url new-pg)}))

(defmethod handle-cmd "tab_list" [_ _]
  (let [pages  (live-context-pages (live-context))
        active (pg)]
    {:tabs (mapv (fn [idx p]
                   ;; A tab can close between the listing and this read; the
                   ;; whole listing must not die with it.
                   {:index  idx
                    :tab    (tab-key-of p)
                    :url    (try (page/url p) (catch Throwable _ nil))
                    :title  (try (page/title p) (catch Throwable _ ""))
                    :active (= p active)})
             (range) pages)}))

(defn- live-tab-keys
  "The stable ids of every tab this session still drives, lowest first."
  []
  (->> (:pages @!tabs)
    (keep (fn [[p t]] (when (page-live? p) t)))
    (sort-by (fn [t] (long (or (parse-long (subs (str t) 1)) 0))))
    vec))

(defmethod handle-cmd "tab_switch" [_ {:strs [index tab]}]
  (let [pages   (live-context-pages (live-context))
        by-key  (tab-by-key tab)
        idx     (when (and (nil? by-key) (number? index)) (long index))
        target  (or by-key
                  (when (and idx (nat-int? idx) (< (long idx) (long (count pages))))
                    (nth pages (int idx))))]
    (when-not target
      (throw (ex-info
               (if (str/blank? (str tab))
                 (str "No tab " index ": this browser has " (count pages)
                   " open, numbered 0-" (max 0 (dec (count pages)))
                   ". A tab number is a position and shifts whenever a tab closes — `spel tab list` prints each tab's stable id and `spel tab t3` selects by that id.")
                 (str "No live tab " tab " in this session. It drives: "
                   (let [ks (live-tab-keys)] (if (seq ks) (str/join ", " ks) "no tab yet"))
                   ". Ids are handed out per tab and never reused, so one that is gone stays gone."))
               {:error_code "tab_not_found"})))
    (focus-page! target)
    {:tab   (tab-key-of target)
     :index (some (fn [[i p]] (when (= p target) i)) (map-indexed vector pages))
     :url   (page/url target)}))

(defmethod handle-cmd "tab_close" [_ _]
  (let [current (live-page)
        context (live-context)]
    (when (user-owned-page? current)
      (throw (ex-info
               "Refusing to close a user-owned tab. spel only closes tabs it opened itself in your browser. Switch to a tab opened by spel, or close this tab yourself in the browser."
               {:error_code "tab_not_owned"
                :hint "spel only closes tabs it created after attaching to an external CDP browser."})))
    (core/close-page! current)
    (let [remaining    (live-context-pages context)
          replacement? (empty? remaining)
          active       (if replacement?
                         (new-spel-page! context)
                         (last remaining))]
      ;; Keep every live session usable. Closing its final tab must not leave a
      ;; closed page handle or stale snapshot refs for the next command.
      ;; Through `focus-page!`: the session's routes and the CDP route lock that
      ;; warns another session off an intercepted tab follow the tab it lands on.
      (swap! !state assoc :refs {} :counter 0)
      (focus-page! active)
      {:closed true
       :remaining (if replacement? 1 (count remaining))
       :replacement replacement?
       :url (page/url active)})))

(defmethod handle-cmd "url" [_ _]
  {:url (page/url (live-page))})

(defmethod handle-cmd "title" [_ _]
  {:title (page/title (live-page))})

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
  (unwrap-anomaly! (page/clipboard-copy (pg) text)))

(defmethod handle-cmd "clipboard_read" [_ _]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/clipboard-read (pg))))

(defmethod handle-cmd "clipboard_paste" [_ _]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/clipboard-paste (pg))))

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

(defmethod handle-cmd "diff_url" [_ {:strs [url1 url2 selector wait-until screenshot threshold]}]
  (ensure-browser!)
  (when (or (str/blank? (str url1)) (str/blank? (str url2)))
    (throw (ex-info "diff url requires two URLs" {})))
  (let [wait-state  (or wait-until "load")
        _           (page/navigate (pg) url1)
        _           (page/wait-for-load-state (pg) wait-state)
        snap1       (snapshot/capture-snapshot (pg)
                      (cond-> {:interactive? true}
                        selector (assoc :scope selector)))
        shot1-bytes (when screenshot (page/screenshot (pg) {:full-page true}))
        _           (page/navigate (pg) url2)
        _           (page/wait-for-load-state (pg) wait-state)
        snap2       (snapshot/capture-snapshot (pg)
                      (cond-> {:interactive? true}
                        selector (assoc :scope selector)))
        shot2-bytes (when screenshot (page/screenshot (pg) {:full-page true}))
        snapshot-diff (snapshot/diff-snapshots
                        (str/trim (str (:tree snap1)))
                        (str/trim (str (:tree snap2))))
        base-result {:url1 url1
                     :url2 url2
                     :snapshot_diff snapshot-diff
                     :total_lines (max (count (str/split-lines (str (:tree snap1))))
                                    (count (str/split-lines (str (:tree snap2)))))}]
    (if (and shot1-bytes shot2-bytes)
      (let [threshold-val (if threshold
                            (Double/parseDouble (str/replace (str threshold) #"," "."))
                            0.1)
            pixel-result  (visual-diff/compare-screenshots shot1-bytes shot2-bytes
                            :threshold threshold-val
                            :current-refs (:refs snap2))
            diff-path     (str (System/getProperty "java.io.tmpdir")
                            java.io.File/separator
                            "spel-diff-url-" (System/currentTimeMillis) ".png")
            _             (java.nio.file.Files/write
                            (java.nio.file.Path/of ^String diff-path (into-array String []))
                            ^bytes (:diff-image pixel-result)
                            ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))]
        (assoc base-result
          :screenshot_diff {:matched      (:matched pixel-result)
                            :diff_percent (:diff-percent pixel-result)
                            :diff_count   (:diff-count pixel-result)
                            :total_pixels (:total-pixels pixel-result)
                            :diff_path    diff-path}))
      base-result)))

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
  (unwrap-anomaly! (page/pdf (pg) {:path path}))
  {:path path})

;; --- Phase 1: Core Gaps ---

(defmethod handle-cmd "keyboard_type" [_ {:strs [text]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (input/key-type (.keyboard ^Page (pg)) text))
  {:typed text})

(defmethod handle-cmd "keyboard_inserttext" [_ {:strs [text]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (input/key-insert-text (.keyboard ^Page (pg)) text))
  {:inserted text})

(defmethod handle-cmd "window_new" [_ _params]
  (ensure-browser!)
  (let [new-pg (check-anomaly!
                 (new-spel-page! (:context @!state))
                 "Failed to create new window/page")
        _      (focus-page! new-pg)]
    {:window "new" :url (try (page/url new-pg) (catch Exception _ "about:blank"))}))

(defmethod handle-cmd "keydown" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (input/key-down (page/page-keyboard (pg)) key))
  {:keydown key})

(defmethod handle-cmd "keyup" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (input/key-up (page/page-keyboard (pg)) key))
  {:keyup key})

(defmethod handle-cmd "scrollintoview" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (unwrap-anomaly! (locator/scroll-into-view (resolve-selector selector)))
  {:scrolled_into_view selector})

(defmethod handle-cmd "find_scrollable" [_ _params]
  (ensure-page-loaded!)
  {:elements (unwrap-anomaly! (page/find-scrollable (pg)))})

(defmethod handle-cmd "scroll_position" [_ _params]
  (ensure-page-loaded!)
  (unwrap-anomaly! (page/scroll-position (pg))))

(defn- drag-point
  "Absolute page point for a drag end: the element's centre, or the given
   {\"x\" _ \"y\"} offset inside its box."
  [bb position]
  (let [x (double (:x bb)) y (double (:y bb))
        w (double (:width bb)) h (double (:height bb))]
    (if-let [p (and (map? position) position)]
      [(+ x (double (or (get p "x") (:x p) (/ w 2.0))))
       (+ y (double (or (get p "y") (:y p) (/ h 2.0))))]
      [(+ x (/ w 2.0)) (+ y (/ h 2.0))])))

(defn- drag-js
  "JavaScript that drags this element onto whatever sits at the drop point,
   without any CDP input and without passing an element handle across the
   protocol (native-image builds reject handle arguments).

   `sx`/`sy` and `tx`/`ty` are viewport coordinates for the press and the
   release; `steps` intermediate moves are dispatched in between. Elements
   that would start a *native* browser drag (links, images, draggable=true)
   get a synthetic HTML5 drag sequence; everything else gets a pointer +
   mouse sequence, which is what drag-and-drop widgets listen for."
  [sx sy tx ty steps]
  (format
    "(src) => {
       const sx = %s, sy = %s, tx = %s, ty = %s, steps = %s;
       const tgt = document.elementFromPoint(tx, ty) || src;
       const base = (x, y) => ({clientX: x, clientY: y, screenX: x, screenY: y,
                               bubbles: true, cancelable: true, composed: true, view: window});
       const draggable = src.draggable === true || !!src.closest('[draggable=\"true\"]')
                         || src.tagName === 'A' || src.tagName === 'IMG';
       if (draggable) {
         const dt = new DataTransfer();
         const fire = (el, type, x, y) =>
           el.dispatchEvent(new DragEvent(type, Object.assign(base(x, y), {dataTransfer: dt})));
         fire(src, 'dragstart', sx, sy);
         fire(src, 'drag', sx, sy);
         fire(tgt, 'dragenter', tx, ty);
         fire(tgt, 'dragover', tx, ty);
         fire(tgt, 'drop', tx, ty);
         fire(src, 'dragend', tx, ty);
         return true;
       }
       const at = (x, y) => document.elementFromPoint(x, y) || tgt;
       const pointer = (el, type, x, y, buttons) =>
         el.dispatchEvent(new PointerEvent(type, Object.assign(base(x, y),
           {pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons: buttons})));
       const mouse = (el, type, x, y, buttons) =>
         el.dispatchEvent(new MouseEvent(type, Object.assign(base(x, y), {button: 0, buttons: buttons})));
       pointer(src, 'pointerdown', sx, sy, 1);
       mouse(src, 'mousedown', sx, sy, 1);
       const n = Math.max(1, steps);
       for (let i = 1; i <= n; i++) {
         const x = sx + ((tx - sx) * i) / n;
         const y = sy + ((ty - sy) * i) / n;
         const el = at(x, y);
         pointer(el, 'pointermove', x, y, 1);
         mouse(el, 'mousemove', x, y, 1);
       }
       pointer(tgt, 'pointerup', tx, ty, 0);
       mouse(tgt, 'mouseup', tx, ty, 0);
       return true;
     }"
    sx sy tx ty steps))

(defmethod handle-cmd "drag" [_ {:strs [source target steps
                                        source-position target-position]}]
  (ensure-page-loaded!)
  ;; Implemented without Playwright's `dragTo` and without CDP mouse input:
  ;; on headless Linux both have been observed to block far past their own
  ;; timeout (Chromium's drag interception never hands a mouseup back), which
  ;; wedged the daemon. Synthetic DOM events are deterministic and instant.
  (let [src-loc (resolve-selector source)
        tgt-loc (resolve-selector target)
        _       (locator/scroll-into-view src-loc)
        _       (locator/scroll-into-view tgt-loc)
        src-bb  (locator/bounding-box src-loc)
        tgt-bb  (locator/bounding-box tgt-loc)]
    (when-not src-bb
      (throw (ex-info (str "drag source is not visible: " source) {:selector source})))
    (when-not tgt-bb
      (throw (ex-info (str "drag target is not visible: " target) {:selector target})))
    (let [[sx sy] (drag-point src-bb source-position)
          [tx ty] (drag-point tgt-bb target-position)]
      (unwrap-anomaly!
        (locator/evaluate-locator src-loc
          (drag-js (double sx) (double sy) (double tx) (double ty)
            (long (or steps 10)))))))
  {:dragged {:from source :to target}})

(defmethod handle-cmd "drag-by" [_ {:strs [selector dx dy steps]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        opts (when steps {:steps (long steps)})]
    (unwrap-anomaly! (locator/drag-by (pg) loc dx dy opts))
    {:dragged_by {:selector selector :dx dx :dy dy}}))

(defmethod handle-cmd "upload" [_ {:strs [selector files]}]
  (ensure-page-loaded!)
  (let [file-paths (if (string? files) [files] files)]
    (unwrap-anomaly! (locator/set-input-files! (resolve-selector selector) file-paths))
    {:uploaded {:selector selector :files file-paths}}))

(defmethod handle-cmd "get_value" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:value (unwrap-anomaly! (locator/input-value (resolve-selector selector)))})

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
      "click"   (do (unwrap-anomaly! (locator/click loc))
                    {:found by :value value :action "click"})
      "fill"    (do (unwrap-anomaly! (locator/fill loc find_value))
                    {:found by :value value :action "fill"})
      "type"    (do (unwrap-anomaly! (locator/type-text loc find_value))
                    {:found by :value value :action "type"})
      "check"   (do (unwrap-anomaly! (locator/check loc)) {:found by :value value :action "check"})
      "uncheck" (do (unwrap-anomaly! (locator/uncheck loc)) {:found by :value value :action "uncheck"})
      "hover"   (do (unwrap-anomaly! (locator/hover loc)) {:found by :value value :action "hover"})
      "focus"   (do (unwrap-anomaly! (locator/focus loc)) {:found by :value value :action "focus"})
      "text"    {:found by :value value :text (unwrap-anomaly! (locator/text-content loc))}
      "count"   {:found by :value value :count (locator/count-elements loc)}
      "visible" {:found by :value value :visible (locator/is-visible? loc)}
      (nil)     {:found by :value value :count (locator/count-elements loc)}
      {:error (str "Unknown find action: " find_action)})))

;; --- Phase 2: Mouse Control ---

(defmethod handle-cmd "mouse_move" [_ {:strs [x y]}]
  (unwrap-anomaly! (input/mouse-move (page/page-mouse (pg)) (double x) (double y)))
  {:moved {:x x :y y}})

(defmethod handle-cmd "mouse_down" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (unwrap-anomaly! (input/mouse-down m))
      (.down m (options/->mouse-down-options {:button (keyword btn)})))
    {:mouse_down btn}))

(defmethod handle-cmd "mouse_up" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (unwrap-anomaly! (input/mouse-up m))
      (.up m (options/->mouse-up-options {:button (keyword btn)})))
    {:mouse_up btn}))

(defmethod handle-cmd "mouse_wheel" [_ {:strs [deltaX deltaY]}]
  (unwrap-anomaly! (input/mouse-wheel (page/page-mouse (pg))
    (double (or deltaX 0))
    (double (or deltaY 0))))
  {:wheel {:dx (or deltaX 0) :dy (or deltaY 0)}})

;; --- Phase 2: Browser Settings ---

(defmethod handle-cmd "set_viewport" [_ {:strs [width height]}]
  (page/set-viewport-size! (live-page) (long width) (long height))
  {:viewport {:width width :height height}})

(defmethod handle-cmd "set_offline" [_ {:strs [enabled]}]
  (let [offline (if (nil? enabled) true (boolean enabled))]
    (core/context-set-offline! (live-context) offline)
    {:offline offline}))

(defmethod handle-cmd "set_headers" [_ {:strs [headers]}]
  (page/set-extra-http-headers! (live-page) headers)
  {:headers_set true})

(defmethod handle-cmd "set_media" [_ {:strs [colorScheme]}]
  (let [scheme (case colorScheme
                 ("dark" "Dark")       :dark
                 ("light" "Light")     :light
                 ("no-preference")     :no-preference
                 :no-preference)]
    (unwrap-anomaly! (page/emulate-media! (live-page) {:color-scheme scheme}))
    {:media {:colorScheme colorScheme}}))

(defmethod handle-cmd "set_device" [_ {:strs [device]}]
  (let [preset (devices/resolve-device-by-name device)]
    (if preset
      (let [browser      (do (ensure-browser!) (:browser @!state))
            current-url  (try (page/url (pg)) (catch Exception _ nil))
            browser-type (get-in @!state [:launch-flags "browser"] "chromium")
            ctx-opts     (if (= "firefox" browser-type)
                           (dissoc preset :is-mobile)
                           preset)]
        (save-inflight-trace!)
        (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
        (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
        (let [new-ctx (check-anomaly!
                         (core/new-context browser ctx-opts)
                        "Failed to create device context")
              new-pg  (check-anomaly!
                        (new-spel-page! new-ctx)
                        "Failed to create page for device")]
          (swap! !state assoc :context new-ctx :page new-pg :tracing? false :device device)
          (reset! !console-messages [])
          (reset! !page-errors [])
          (reset! !tracked-requests [])
          (instrument-page! new-pg)
          (when current-url (page/navigate new-pg current-url))
          {:device device :preset preset}))
      {:error (str "Unknown device: " device
                ". Available: " (clojure.string/join ", " (devices/available-device-names)))})))

(defmethod handle-cmd "set_geo" [_ {:strs [latitude longitude accuracy]}]
  (let [context (live-context)]
    (core/context-grant-permissions! context ["geolocation"])
    (.setGeolocation ^BrowserContext context
      (doto (Geolocation. (double latitude) (double longitude))
        (.setAccuracy (double (or accuracy 1)))))
    {:geolocation {:latitude latitude :longitude longitude}}))

(defmethod handle-cmd "set_credentials" [_ {:strs [username password]}]
  ;; HTTP credentials require recreating the context
  (let [browser     (do (ensure-browser!) (:browser @!state))
        current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (check-anomaly!
                    (core/new-context browser
                      {:http-credentials {:username username :password password}})
                    "Failed to create context with credentials")
          new-pg  (check-anomaly!
                    (new-spel-page! new-ctx)
                    "Failed to create page with credentials")]
      (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
      (reset! !console-messages [])
      (reset! !page-errors [])
      (reset! !tracked-requests [])
      (instrument-page! new-pg)
      (when current-url (page/navigate new-pg current-url))
      {:credentials_set true})))

;; --- Phase 3: Cookies ---

(defmethod handle-cmd "cookies_get" [_ {:strs [urls]}]
  (let [context (live-context)
        cookies (if urls
                  (mapv core/cookie->map
                    (.cookies ^BrowserContext context
                      (java.util.ArrayList. ^java.util.Collection (vec urls))))
                  (core/context-cookies context))]
    {:cookies cookies}))

(defmethod handle-cmd "cookies_set" [_ {:strs [name value domain path url]}]
  (let [cookie (Cookie. name value)]
    (if domain
      (do (.setDomain cookie domain)
          (.setPath cookie (or path "/")))
      (.setUrl cookie (or url (page/url (live-page)))))
    (let [cookie-list (java.util.Collections/singletonList cookie)]
      (.addCookies ^BrowserContext (live-context) cookie-list))
    {:cookie_set {:name name :value value}}))

(defmethod handle-cmd "cookies_clear" [_ _]
  (core/context-clear-cookies! (live-context))
  {:cookies_cleared true})

;; --- Phase 3: Storage ---

(defmethod handle-cmd "storage_get" [_ {:strs [type key]}]
  (let [st (or type "local")
        js (if key
             (str st "Storage.getItem('" key "')")
             (str "JSON.stringify(Object.entries(" st "Storage))"))]
    {:storage (unwrap-anomaly! (page/evaluate (live-page) js))}))

(defmethod handle-cmd "storage_set" [_ {:strs [type key value]}]
  (let [st (or type "local")]
    (unwrap-anomaly! (page/evaluate (live-page) (str st "Storage.setItem('" key "', '" value "')")))
    {:storage_set {:key key :value value}}))

(defmethod handle-cmd "storage_clear" [_ {:strs [type]}]
  (let [st (or type "local")]
    (unwrap-anomaly! (page/evaluate (live-page) (str st "Storage.clear()")))
    {:storage_cleared st}))

;; --- Phase 3: Network ---

(defmethod handle-cmd "network_get_ref" [_ {:strs [ref]}]
  (let [ref-id (str/replace (or ref "") #"^@" "")]
    (if-let [entry (materialize-network-entry! ref-id)]
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

(defmethod handle-cmd "network_list" [_ {:strs [all]}]
  {:entries (tab-entries @!network-window all) :tab (current-tab)})

(defmethod handle-cmd "console_list" [_ {:strs [all]}]
  {:entries (tab-entries @!console-window all) :tab (current-tab)})

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
    ;; A route belongs to the SESSION. Registered on `(live-page)` alone it was
    ;; not in force on any OTHER tab already open — the request went to the real
    ;; server while the session reported the mock as active. Tabs opened later
    ;; get it from instrument-page!, and network_unroute already sweeps them all.
    (let [current (live-page)]
      (page/route! current url handler)
      (doseq [^Page p (session-live-pages)
              :when (not (identical? p current))]
        (page/route! p url handler)))
    (swap! !routes assoc url handler)
    (claim-cdp-route-lock!)
    {:route_added url}))

(defn- recreate-context-with-opts!
  "Closes the current browser context and recreates it with merged opts,
   preserving storage state and navigating back to the current URL. Used by
   HAR start/stop and other features that require context-level options.

   Returns the new page instance.

   `extra-opts` is merged on top of the existing base ctx-opts derived from
   launch-flags."
  [extra-opts]
  (let [current-url  (try (page/url (pg)) (catch Exception _ nil))
        flags        (get @!state :launch-flags {})
        browser      (:browser @!state)
        base-opts    (cond-> {}
                       (get flags "user-agent")          (assoc :user-agent (get flags "user-agent"))
                       (get flags "ignore-https-errors") (assoc :ignore-https-errors true)
                       (get flags "headers")             (assoc :extra-http-headers
                                                           (try (json/read-json (get flags "headers"))
                                                                (catch Exception _ {}))))
        ctx-opts     (merge base-opts extra-opts)]
    ;; Save in-flight trace + storage state before teardown
    (save-inflight-trace!)
    (let [storage-state (try (.storageState ^BrowserContext (:context @!state))
                             (catch Exception _ nil))]
      (when-let [p (:page @!state)]
        (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
      (when-let [c (:context @!state)]
        (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
      (let [ctx-opts-with-state (cond-> ctx-opts
                                  storage-state (assoc :storage-state storage-state))
            new-ctx (check-anomaly!
                      (core/new-context browser ctx-opts-with-state)
                      "Failed to recreate browser context")
            new-pg  (check-anomaly!
                      (new-spel-page! new-ctx)
                      "Failed to create page in recreated context")]
        (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
        (reset! !console-messages [])
        (reset! !page-errors [])
        (reset! !tracked-requests [])
        (instrument-page! new-pg)
        (when current-url (page/navigate new-pg current-url))
        new-pg))))

(defmethod handle-cmd "har_start" [_ {:strs [path mode omit-content url-filter]}]
  (ensure-browser!)
  (ensure-page-loaded!)
  (when (:har-recording? @!state)
    (throw (ex-info (str "HAR recording already active. Call 'har stop' first. Current: "
                      (:har-path @!state))
             {})))
  (let [har-path (or path
                   (str (System/getProperty "java.io.tmpdir")
                     java.io.File/separator
                     "spel-" (or (:session @!state) "default") "-"
                     (System/currentTimeMillis) ".har"))
        opts     (cond-> {:record-har-path har-path}
                   mode         (assoc :record-har-mode (keyword mode))
                   omit-content (assoc :record-har-omit-content true)
                   url-filter   (assoc :record-har-url-filter url-filter))]
    (recreate-context-with-opts! opts)
    (swap! !state assoc :har-recording? true :har-path har-path)
    {:recording true :path har-path}))

(defmethod handle-cmd "har_stop" [_ _params]
  (ensure-browser!)
  (when-not (:har-recording? @!state)
    (throw (ex-info "No HAR recording in progress. Start one with 'har start'." {})))
  (let [har-path (:har-path @!state)]
    ;; Closing the context flushes the HAR file to disk. Recreate a fresh
    ;; context without recordHar so the session stays usable.
    (recreate-context-with-opts! {})
    (swap! !state assoc :har-recording? false :har-path nil)
    (let [size (try (.length (java.io.File. ^String har-path)) (catch Exception _ -1))]
      {:recording false :path har-path :size size})))

(defmethod handle-cmd "network_unroute" [_ {:strs [url]}]
  ;; Never starts a browser: this is the documented way out of a `cdp_route_lock`,
  ;; and a session whose browser already went away still owns a lock to release.
  ;; Every LIVE tab, not just the one in front: the session's routes are applied
  ;; to every tab it drives, so unrouting the current page alone would leave the
  ;; others intercepting under a lock this session had already released.
  (let [pages (when-let [context (ctx)] (live-context-pages context))]
    (if url
      (do (doseq [p pages] (page/unroute! p url))
          (swap! !routes dissoc url)
          (when (empty? @!routes)
            (release-cdp-route-lock-if-owned!))
          {:route_removed url})
      (do (doseq [[u _] @!routes
                  p     pages]
            (page/unroute! p u))
          (reset! !routes {})
          (release-cdp-route-lock-if-owned!)
          {:all_routes_removed true}))))

(defmethod handle-cmd "network_requests" [_ {:strs [filter type method status all]}]
  (let [reqs     (tab-entries @!tracked-requests all)
        filtered (cond->> reqs
                   filter (filterv #(re-find (re-pattern filter) (str (:url %))))
                   type   (filterv #(= (:resource-type %) type))
                   method (filterv #(= (str/upper-case (:method %)) (str/upper-case method)))
                   status (filterv #(str/starts-with? (str (:status %)) status)))]
    {:requests filtered}))

(defmethod handle-cmd "network_clear" [_ {:strs [all]}]
  (swap! !tracked-requests without-tab-entries all)
  (clear-window! !network-window !network-full !network-responses all)
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
  ;; If a dialog is already pending (parked in the default handler), respond
  ;; to it via the promise. Otherwise install a one-shot handler for the NEXT
  ;; dialog — backward compat for callers that call `dialog accept` before the
  ;; dialog fires.
  (if-let [p @!pending-dialog-promise]
    (do (deliver p [:accept text])
        {:dialog_handler "accept" :text text :pending false})
    (do (when-let [old @!dialog-handler]
          (try (.offDialog ^Page (pg) old) (catch Exception _ nil)))
        (let [handler (reify java.util.function.Consumer
                        (accept [_ dialog]
                          (try (.accept ^Dialog dialog (or text ""))
                               (catch Exception _ nil))
                          (install-default-dialog-handler! (pg)
                            (boolean (get (get @!state :launch-flags {}) "no-auto-dialog")))))]
          (reset! !dialog-handler handler)
          (.onDialog ^Page (pg) handler))
        {:dialog_handler "accept" :text text :pending true})))

(defmethod handle-cmd "dialog_dismiss" [_ _]
  (if-let [p @!pending-dialog-promise]
    (do (deliver p [:dismiss nil])
        {:dialog_handler "dismiss" :pending false})
    (do (when-let [old @!dialog-handler]
          (try (.offDialog ^Page (pg) old) (catch Exception _ nil)))
        (let [handler (reify java.util.function.Consumer
                        (accept [_ dialog]
                          (try (.dismiss ^Dialog dialog)
                               (catch Exception _ nil))
                          (install-default-dialog-handler! (pg)
                            (boolean (get (get @!state :launch-flags {}) "no-auto-dialog")))))]
          (reset! !dialog-handler handler)
          (.onDialog ^Page (pg) handler))
        {:dialog_handler "dismiss" :pending true})))

(defmethod handle-cmd "dialog_status" [_ _]
  ;; Reports whether a dialog is currently blocking the page. Agents poll this
  ;; before issuing actions that would otherwise be silently stalled.
  (if-let [info @!pending-dialog]
    {:pending true
     :type (:type info)
     :message (:message info)
     :default_value (:default-value info)}
    {:pending false}))

;; --- Phase 4: Debug ---

(defmethod handle-cmd "trace_start" [_ {:strs [name]}]
  (unwrap-anomaly! (core/tracing-start! (core/context-tracing (live-context))
    (cond-> {:screenshots true :snapshots true}
      name (assoc :name name))))
  (swap! !state assoc :tracing? true)
  {:trace "started" :name name})

(defmethod handle-cmd "trace_stop" [_ {:strs [path]}]
  (let [out-path (or path "trace.zip")]
    (unwrap-anomaly! (core/tracing-stop! (core/context-tracing (live-context)) {:path out-path}))
    (swap! !state assoc :tracing? false)
    {:trace "stopped" :path out-path}))

(defmethod handle-cmd "console_get" [_ {:strs [clear all]}]
  (let [msgs (tab-entries @!console-messages all)]
    (when clear (swap! !console-messages without-tab-entries all))
    {:messages msgs}))

(defmethod handle-cmd "console_clear" [_ {:strs [all]}]
  (swap! !console-messages without-tab-entries all)
  (clear-window! !console-window !console-full nil all)
  {:console "cleared"})

(defmethod handle-cmd "errors_get" [_ {:strs [clear all]}]
  (let [errs (tab-entries @!page-errors all)]
    (when clear (swap! !page-errors without-tab-entries all))
    {:errors errs}))

(defmethod handle-cmd "errors_clear" [_ {:strs [all]}]
  (swap! !page-errors without-tab-entries all)
  {:errors "cleared"})

(defmethod handle-cmd "console_start" [_ _]
  ;; Console capture is already on for every page spel instruments; asking again
  ;; must not add a second listener that records every message twice.
  (instrument-page! (live-page))
  {:console "listening"})

(defmethod handle-cmd "errors_start" [_ _]
  (instrument-page! (live-page))
  {:errors "listening"})

;; --- Phase 4: State Management ---

(defmethod handle-cmd "state_save" [_ {:strs [path]}]
  (let [save-path (or path (str "state-" (:session @!state) ".json"))]
    (.storageState ^BrowserContext (live-context)
      (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
        (.setPath (Path/of save-path (into-array String [])))))
    {:state "saved" :path save-path}))

(defmethod handle-cmd "state_load" [_ {:strs [path]}]
  (let [state-path  (or path (str "state-" (:session @!state) ".json"))
        browser     (do (ensure-browser!) (:browser @!state))
        current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (core/new-context browser {:storage-state-path state-path})]
      (if (anomaly/anomaly? new-ctx)
        {:error (str "Failed to load state: " (:anomaly/message new-ctx))}
        (let [new-pg (new-spel-page! new-ctx)]
          (if (anomaly/anomaly? new-pg)
            (do (.close ^BrowserContext new-ctx)
                {:error (str "Failed to create page: " (:anomaly/message new-pg))})
            (do
              (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
              (reset! !console-messages [])
              (reset! !page-errors [])
              (reset! !tracked-requests [])
              (instrument-page! new-pg)
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

;; session_list handling now lives entirely on the CLI side
;; (see `build-session-list-data` in cli.clj). The daemon-side handler was
;; removed because it duplicated the logic and never ran under the new dispatch
;; short-circuit. If a raw JSON client sends {"action":"session_list"} to the
;; daemon socket it will get a "no method" error — intentional; listing is a
;; pure read of /tmp state that does not require a running daemon.

(defmethod handle-cmd "session_info" [_ _]
  (let [state @!state
        context (:context state)
        page (try (pg) (catch Exception _ nil))
        launch-flags (:launch-flags state)
        viewport (try (when page (page/viewport-size page)) (catch Exception _ nil))
        tab-count (try (when context (count (.pages ^com.microsoft.playwright.BrowserContext context)))
                       (catch Exception _ nil))
        cookies-count (try (when context (count (.cookies ^com.microsoft.playwright.BrowserContext context)))
                           (catch Exception _ nil))
        ios? (= "ios" (get launch-flags "provider"))
        ios-sess (:ios-session state)]
    (if ios?
      {:session     (:session state)
       :provider    "ios"
       :backend     "webdriver"
       :application (or (:bundle-id ios-sess) (:app ios-sess))
       :context     (when ios-sess
                      (try (ios/current-context ios-sess) (catch Exception _ nil)))
       :device      (when-let [d (:ios-device state)]
                      {:name (:name d)
                       :udid (:udid d)
                       :platform_version (:platform-version d)})
       :appium_url  (:appium-url ios-sess)
       :started     (some? (:backend state))
       :url         (when (:backend state)
                      (try (backend/current-url (:backend state)) (catch Exception _ nil)))
       :title       (when (:backend state)
                      (try (backend/page-title (:backend state)) (catch Exception _ nil)))
       :refs_count  (:counter state)
       :socket      (try (.toString (socket-path (:session state))) (catch Exception _ nil))}
      {:session        (:session state)
       :provider       "playwright"
       :browser        (get launch-flags "browser" "chromium")
       :channel        (get launch-flags "channel")
       :headless       (:headless state)
       :persist        (persist-enabled?)
       :tracing        (boolean (:tracing? state))
       :har_recording  (boolean (:har-recording? state))
       :har_path       (:har-path state)
       :cdp_url        (get launch-flags "cdp")
       :cdp_connected  (boolean (:cdp-connected state))
       :device         (:device state)
       :url            (try (when page (page/url page)) (catch Exception _ nil))
       :title          (try (when page (page/title page)) (catch Exception _ nil))
       :viewport       viewport
       :tab_count      tab-count
       :cookies_count  cookies-count
       :refs_count     (:counter state)
       :socket         (try (.toString (socket-path (:session state))) (catch Exception _ nil))})))

;; --- Shutdown state ---
;; `stop-daemon!` must run EXACTLY once. It ends in `System/exit`, which runs
;; the JVM shutdown hook, which called `stop-daemon!` again — and `System/exit`
;; from inside a hook blocks forever while the exiting thread joins that hook.
;; That three-way deadlock (main ↔ hook ↔ idle timer) left a zombie daemon whose
;; socket and PID files were already deleted, still holding its browser.
(defonce ^:private !stopping (atom false))

;; True while the JVM shutdown sequence is running our hook. Cleanup still runs
;; there; `System/exit` must not.
(defonce ^:private !in-shutdown-hook (atom false))

;; Delivered once a stop has fully finished. Stops run on VIRTUAL threads, which
;; are daemon threads: without this latch the JVM could exit mid-teardown and
;; leave the socket and PID files behind for the next client to dial into.
(defonce ^:private !stop-finished (promise))

(def ^:private stop-cleanup-timeout-ms
  "Ceiling on browser cleanup during shutdown. Chromium normally goes in under a
   second; a wedged one must not hold a daemon that was asked to stop. Override
   with SPEL_STOP_TIMEOUT (milliseconds)."
  (let [v (System/getenv "SPEL_STOP_TIMEOUT")]
    (if (str/blank? v) 5000 (Long/parseLong v))))

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
                              (stop-daemon! (str "CDP idle timeout ("
                                              (human-duration timeout-ms)
                                              ") — no reconnect")))
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
                              (stop-daemon! (str "session idle timeout ("
                                              (human-duration timeout-ms)
                                              ") — no commands received")))
                  timeout-ms
                  TimeUnit/MILLISECONDS)]
        (reset! !session-idle-future fut)))))

;; --- Phase 5: Connect CDP ---

(defn- assert-cdp-endpoint-reachable!
  "Fail-fast preflight for `connect`. Playwright's connectOverCDP has no
   connect timeout: pointing it at a dead ws:// browser URL (typical after the
   browser restarted and left a stale DevToolsActivePort/session cache behind)
   blocks the daemon command loop until the client transport times out. So we
   check the endpoint ourselves first and throw a readable error instead.

   ws:// / wss:// — requires a live TCP socket on host:port whose WebSocket
   upgrade answers 101; wss:// handshakes over TLS and defaults to port 443.
   http:// / https:// — additionally requires a valid /json/version DevTools
   response; https:// fetches it over TLS and defaults to port 443.

   Playwright accepts https:// and wss:// endpoints itself (its driver fetches
   `<endpoint>/json/version` and dials wss:// transports), so this preflight
   probes them the same way instead of downgrading them to plaintext."
  [^String url]
  (let [^java.net.URI uri (try (java.net.URI. url) (catch Exception _ nil))
        scheme  (when uri (some-> (.getScheme uri) str/lower-case))
        secure? (contains? #{"https" "wss"} scheme)
        host    (or (when uri (.getHost uri)) "127.0.0.1")
        port    (long (let [p (long (if uri (.getPort uri) -1))]
                        (if (pos? p) p (if secure? 443 80))))
        http-scheme (if secure? "https" "http")
        fail!  (fn [msg hint]
                 (throw (ex-info msg {:error_code "cdp_endpoint_unreachable"
                                      :url        url
                                      :hint       hint})))]
    (when (nil? scheme)
      (fail! (str "Invalid CDP URL: " url)
        "Expected http(s)://host:port or ws(s)://host:port/devtools/browser/<id>"))
    (when-not (try
                (with-open [^java.net.Socket s (java.net.Socket.)]
                  (.connect s (java.net.InetSocketAddress. ^String host (int port)) 1500)
                  true)
                (catch Exception _ false))
      (fail! (str "CDP browser endpoint unreachable: " host ":" port " is not accepting connections")
        (str "Start the browser with --remote-debugging-port=" port
          " --remote-debugging-address=" host " --remote-allow-origins='*', "
          "then verify: curl " http-scheme "://" host ":" port "/json/version")))
    (when (and (str/starts-with? (str scheme) "http")
            (not (probe-http-cdp host port 2000 secure?)))
      (if-let [transport-error (cdp-http-transport-error host port 2000 secure?)]
        (fail! (str "CDP probe could not complete an HTTP request to " host ":" port ": " transport-error)
          (str "The port accepts TCP but this build could not speak "
            (if secure? "HTTPS" "HTTP") " to it. "
            "Verify the endpoint with: curl " http-scheme "://" host ":" port "/json/version"))
        (fail! (str "CDP endpoint at " host ":" port " is listening but /json/version is not a DevTools endpoint")
          (str "The port is held by a stale or non-DevTools process. Fully quit the browser "
            "and relaunch it with --remote-debugging-port=" port " --remote-allow-origins='*'."))))
    (when (and (str/starts-with? (str scheme) "ws")
            (not (probe-ws-target url 2000)))
      (fail! (str "CDP browser target no longer exists at " url)
        (str "That ws:// browser id is stale — the browser was restarted since it was cached. "
          "Re-discover the current endpoint: curl " http-scheme "://" host ":" port "/json/version, "
          "or connect to " http-scheme "://" host ":" port " instead of the cached ws:// URL.")))
    true))

(defn- connect-cdp!
  "Connects daemon state to a CDP endpoint and returns connection payload."
  [^String url]
  (cancel-cdp-idle-shutdown!)
  (when (str/blank? url)
    (throw (ex-info "CDP URL is required. Usage: spel connect <url>" {:error_code "cdp_url_required"})))
  (assert-cdp-endpoint-reachable! url)
  (let [pw (or (:pw @!state) (core/create))
        browser (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String url)
        contexts (.contexts ^com.microsoft.playwright.Browser browser)
        context (if (seq contexts) (first contexts) (core/new-context browser))
        _ (adopt-foreign-pages! context)
        ;; spel always opens its own tab so user tabs are never hijacked.
        pg-inst (new-spel-page! context)]
    (swap! !state assoc :pw pw :browser browser :context context :page pg-inst :cdp-connected true)
    (swap! !state assoc-in [:launch-flags "cdp"] url)
    (persist-launch-flags!)
    (reset! !console-messages [])
    (reset! !page-errors [])
    (reset! !tracked-requests [])
    (instrument-page! pg-inst)
    {:connected url :url (page/url pg-inst)}))

(defn- disconnect-cdp!
  "Disconnects current CDP browser connection while preserving launch flags.
   This is a temporary detach operation used by anti-CDP evasion workflows."
  []
  (let [{:keys [cdp-connected pw]} @!state
        cdp-url (current-cdp-url)]
    (when cdp-connected
      ;; Closing a Playwright Browser/Context obtained via connectOverCDP can
      ;; close the user's real browser or tabs. Close only pages spel created,
      ;; then close the local Playwright driver to detach the WebSocket.
      (when (foreign-browser?)
        (close-spel-owned-pages!))
      (when pw
        (try (core/close! pw) (catch Exception e (warn "cdp-disconnect-close-playwright" e))))
      (release-cdp-route-lock-if-owned!))
    (swap! !state assoc :pw nil :browser nil :context nil :page nil
      :cdp-connected false :cdp-foreign false :adopted-context nil :adopted-pages #{}
      :spel-pages #{})
    ;; Start idle shutdown timer only when we actually disconnected a CDP session
    (when cdp-connected
      (schedule-cdp-idle-shutdown!))
    {:disconnected (boolean cdp-connected)
     :cdp cdp-url}))

(defn- cdp-http-base
  "Converts a CDP WebSocket URL like `ws://localhost:9222/devtools/browser/abc`
   into the HTTP base `http://localhost:9222` — and a `wss://` URL into an
   `https://` base, defaulting to port 443, so `/json/list` stays reachable on a
   TLS-fronted endpoint. Returns nil if the URL cannot be parsed."
  [^String ws-url]
  (when ws-url
    (try
      (let [uri     (java.net.URI. ws-url)
            secure? (= "wss" (some-> (.getScheme uri) str/lower-case))
            host    (.getHost uri)
            port    (let [p (.getPort uri)] (if (pos? p) p (if secure? 443 80)))]
        (when host
          (str (if secure? "https" "http") "://" host ":" port)))
      (catch Exception _ nil))))

(defmethod handle-cmd "auth_save" [_ {:strs [name url username password]}]
  ;; No browser needed — pure filesystem write. The LLM driving the CLI never
  ;; sees the password because it's read from stdin CLI-side and sent here
  ;; already-opaque.
  (when (str/blank? (str name))    (throw (ex-info "auth save requires a name"     {})))
  (when (str/blank? (str url))     (throw (ex-info "auth save requires --url"      {})))
  (when (str/blank? (str username)) (throw (ex-info "auth save requires --username" {})))
  (when (str/blank? (str password)) (throw (ex-info "auth save requires a password (pipe via --password-stdin)" {})))
  (let [path (vault/save-credential! {:name name :url url :username username :password password})]
    {:saved name :path path}))

(defmethod handle-cmd "auth_list" [_ _params]
  {:credentials (vault/list-credentials)})

(defmethod handle-cmd "auth_delete" [_ {:strs [name]}]
  (when (str/blank? (str name))
    (throw (ex-info "auth delete requires a name" {})))
  {:deleted name :existed (boolean (vault/delete-credential! name))})

(defmethod handle-cmd "auth_login" [_ {:strs [name]}]
  (ensure-browser!)
  (when (str/blank? (str name))
    (throw (ex-info "auth login requires a name" {})))
  (let [record   (vault/load-credential name)
        url      (:url record)
        username (:username record)
        password (:password record)
        _        (unwrap-anomaly! (page/navigate (pg) url))
        _        (unwrap-anomaly! (page/wait-for-load-state (pg) "load"))
        ;; Heuristic form detection: pick the first visible text/email input for
        ;; username and the first password input for password. Works for the
        ;; vast majority of login forms without custom selectors.
        user-loc (locator/first-element
                   (page/locator (pg) "input[type=email], input[type=text], input[type=tel], input[autocomplete*=username]"))
        pass-loc (locator/first-element
                   (page/locator (pg) "input[type=password]"))]
    (unwrap-anomaly! (locator/fill user-loc username))
    (unwrap-anomaly! (locator/fill pass-loc password))
    ;; Submit: press Enter on the password field. This works for almost every
    ;; login form and avoids flaky "find the submit button by label" heuristics.
    (unwrap-anomaly! (locator/press pass-loc "Enter"))
    (unwrap-anomaly! (page/wait-for-load-state (pg) "load"))
    {:logged_in name :url (page/url (pg)) :username username}))

(defmethod handle-cmd "profiles" [_ _params]
  ;; No browser required — purely filesystem inspection.
  {:root     (profile/chrome-user-data-root)
   :profiles (profile/list-profiles)})

(defmethod handle-cmd "devtools" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [cdp-ws    (current-cdp-url)
        http-base (cdp-http-base cdp-ws)
        current   (try (page/url (pg)) (catch Exception _ nil))]
    (cond
      (nil? cdp-ws)
      {:error "devtools requires a CDP-connected browser. Restart with --auto-launch or --cdp <url>."
       :hint  "Example: spel close && spel --auto-launch open https://example.com && spel devtools"}

      (nil? http-base)
      {:error (str "Could not derive HTTP base from CDP URL: " cdp-ws)}

      :else
      (let [list-url (str http-base "/json/list")
            body     (try
                       (let [conn (doto (open-cdp-http-connection list-url 2000)
                                    (.setRequestMethod "GET"))]
                         (with-open [is (.getInputStream conn)]
                           (slurp is)))
                       (catch Exception e
                         (throw (ex-info (str "Failed to reach CDP endpoint " list-url ": " (.getMessage e)) {}))))
            entries  (try (json/read-json body) (catch Exception _ []))
            targets  (filter #(= "page" (get % "type")) entries)
            ;; Prefer the target whose URL matches our current page; fall back
            ;; to the first page target.
            chosen   (or (some #(when (= current (get % "url")) %) targets)
                       (first targets))]
        (if chosen
          {:devtools_url (get chosen "devtoolsFrontendUrl")
           :page_url     (get chosen "url")
           :title        (get chosen "title")
           :cdp_ws       (get chosen "webSocketDebuggerUrl")}
          {:error "No page targets found at CDP endpoint"})))))

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
  ;; Install set-device handler so eval-sci can call (spel/set-device! "iPhone 14").
  ;; Routes through the existing "set_device" handler — same behavior as the CLI
  ;; `spel set device …` path, including SCI state sync.
  (reset! sci-env/!set-device-handler
    (fn set-device-handler [device-name]
      (let [result (handle-cmd "set_device" {"device" device-name})]
        (when (:error result)
          (throw (ex-info (:error result) {:device device-name})))
        (let [st @!state]
          (reset! sci-env/!context (:context st))
          (reset! sci-env/!page    (:page st))
          (reset! sci-env/!device  (:device st)))
        result)))
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

(defn- json-key
  "A map key as a JSON object key: a keyword drops its leading colon, anything
   else prints. Keys are where an encoder has to guess, so nothing is left for
   it to guess about."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (subs (str k) 1)
    (symbol? k)  (str k)
    :else        (pr-str k)))

(defn- json-result
  "Projects an evaluated SCI value onto data charred can always encode, for the
   client that sent `result_format` \"json\".

   `:result` stays the `pr-str` this protocol has always answered, and EDN is
   not JSON — so `--json` needs the value itself, projected here where it still
   exists. Total by construction: keywords and symbols answer their names, maps
   and collections answer recursively (Playwright hands back java.util ones),
   and everything with no JSON shape — a browser handle, a function, a regex, a
   non-finite double — answers its `pr-str` instead of throwing, because a
   throw here would poison the whole response line."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  v
    (boolean? v) v
    (keyword? v) (subs (str v) 1)
    (symbol? v)  (str v)
    (char? v)    (str v)
    (integer? v) v
    (float? v)   (let [d (double v)]
                   (if (Double/isFinite d) d (str v)))
    (number? v)  (str v)
    (instance? java.util.Map v)
    (persistent!
      (reduce (fn [acc e] (assoc! acc (json-key (key e)) (json-result (val e))))
        (transient {}) v))
    (or (coll? v) (instance? java.util.Collection v))
    (mapv json-result v)
    :else (pr-str v)))

(defmethod handle-cmd "sci_eval" [_ params]
  ;; iOS dispatch initializes its Appium backend before entering this shared
  ;; handler. Never launch Playwright alongside it.
  (when-not (= "ios" (get-in @!state [:launch-flags "provider"]))
    (ensure-browser!))
  (sync-state-to-sci!)
  (let [code (get params "code")
        args-vec (when-let [args (get params "args")]
                   (mapv str args))]
    (when-not code
      (throw (ex-info "sci_eval requires a 'code' parameter" {})))
    ;; Bind the evaluated source here (not only in `process-command*`) so a
    ;; direct `handle-cmd` call still renders a code frame.
    (binding [*error-source* {:source code :lang :clj}]
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
              (cond-> (error-response (::anomaly/message result)
                        (error-context (merge (::anomaly/data result) result)))
                (seq captured-stdout) (assoc :stdout captured-stdout)
                (seq captured-stderr) (assoc :stderr captured-stderr)
                (seq new-console)     (assoc :console new-console)
                (seq new-errors)      (assoc :page-errors new-errors))
              (let [base (cond-> {:result (pr-str result)}
                           ;; `--json` on the client asks for this: the pr-str
                           ;; above is EDN, and the value it was made from lives
                           ;; nowhere else.
                           (= "json" (get params "result_format"))
                           (assoc :result-data (json-result result))
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
              (cond-> (error-response (throwable-message e)
                        (error-context (ex-data e) e))
                (seq captured-stdout) (assoc :stdout captured-stdout)
                (seq captured-stderr) (assoc :stderr captured-stderr)
                (seq new-console)     (assoc :console new-console)
                (seq new-errors)      (assoc :page-errors new-errors)))))))))

;; --- Health & the command ledger ---

(defn- current-page-url
  "URL the page currently sits on, or nil when it sits on nothing.

   `.url` is a local field read on the Playwright client — never a round trip to
   the browser — so `health` keeps its promise of answering while every real
   browser call is wedged."
  []
  (try
    (when-let [p (pg)]
      (let [u (page/url p)]
        (when-not (contains? #{nil "" "about:blank"} u) u)))
    (catch Throwable _ nil)))

(defmethod handle-cmd "health" [_ _]
  ;; Deliberately free of Playwright calls — this is the ONE answer a client can
  ;; still get when every browser call is wedged. Everything below is read from
  ;; daemon-local state.
  (let [now       (System/currentTimeMillis)
        started   (long (or @!daemon-started-at now))
        in-flight (ledger-entries)
        launched? (some? (:browser @!state))
        connected (boolean (browser-connected?))
        last-at   @!last-command-at
        handlers  (core/handler-errors)
        lost      @!lost-commands
        crashed?  (page-crashed? (:page @!state))]
    {:status         (cond
                       (and launched? (not connected)) "degraded"
                       ;; Instrumentation that throws on every event is the
                       ;; failure that looks like success: commands still
                       ;; answer, console capture is empty, and the caller
                       ;; reads a blank page (issue #125). Say it out loud.
                       (seq handlers)                  "degraded"
                       ;; A command that never came back took the page and the
                       ;; refs with it — the session survives, but it is not the
                       ;; one the caller had (issue #125).
                       (seq lost)                      "degraded"
                       ;; The tab is dead and its DOM, refs and scroll position
                       ;; went with it; the next command opens a fresh one, so
                       ;; the session is usable but not the one the caller had.
                       crashed?                        "degraded"
                       (seq in-flight)                 "busy"
                       :else                           "ok")
     :session        (:session @!state)
     :pid            (.pid (java.lang.ProcessHandle/current))
     :uptime_ms      (- now started)
     :uptime         (human-duration (- now started))
     :in_flight      in-flight
     :busiest_ms     (or (:running_ms (first in-flight)) 0)
     :commands_total @!commands-total
     :idle_ms        (when last-at (- now (long last-at)))
     :browser        {:launched  launched?
                      :connected connected
                      :page_open (boolean (page-open?))
                      :page_crashed crashed?
                      :page_url  (current-page-url)
                      :type      (get-in @!state [:launch-flags "browser"] "chromium")
                      :headless  (boolean (:headless @!state))
                      :cdp       (current-cdp-url)}
     :handler_errors handlers
     :lost_commands  lost
     :socket         (try (.toString (socket-path (:session @!state))) (catch Exception _ nil))
     :session_idle_timeout_ms @!session-idle-timeout-ms}))

(defmethod handle-cmd "cancel" [_ {:strs [id]}]
  (let [cancelled (ledger-cancel! id)]
    (cond-> {:cancelled cancelled :count (count cancelled)}
      (seq cancelled)
      (assoc :note (str "interrupt sent; a call already parked inside the browser "
                     "ends when the browser answers — re-check with `spel health`")))))

;; --- Close & Default ---

(defmethod handle-cmd "close" [_ {:strs [force]}]
  (if force
    ;; Force: no state auto-save, no profile cleanup, no graceful browser close.
    ;; The caller asked for this process to be GONE, not tidy.
    {:closed true :shutdown true :force true}
    (do
      ;; Auto-save session state (unless --no-persist)
      (auto-save-session-state!)
      ;; If `--profile <name>` cloned a Chrome profile into a temp dir, delete it
      ;; now so we don't leak it. Best-effort; failures are logged but not fatal.
      (when-let [tmp (:profile-temp-dir @!state)]
        (try
          (profile/delete-tree! tmp)
          (swap! !state dissoc :profile-temp-dir)
          (catch Exception e (warn "profile-temp-cleanup" e))))
      ;; Note: in-flight trace is saved by stop-daemon! (called after this returns)
      (cond-> {:closed true :shutdown true}
        (:tracing? @!state) (assoc :trace-warning "active trace will be auto-saved on shutdown")))))

(defmethod handle-cmd :default [action _]
  {:error (str "Unknown action: " action)})

;; =============================================================================
;; iOS application provider (Appium/XCUITest in an iOS Simulator)
;; =============================================================================

(defn- ios-provider?
  "Returns true when this daemon drives the iOS provider — either its persisted
   launch flags select it, or an iOS session is already live."
  []
  (let [state @!state]
    (or (= "ios" (get-in state [:launch-flags "provider"]))
      (some? (:ios-session state)))))

(defn- stop-ios-backend!
  "Idempotent iOS cleanup. Releases only session-owned resources (WebDriver
   session, spel-started Appium, simulator lock; simulator shutdown only when
   requested AND spel booted it). Safe from both `close` and shutdown hooks."
  []
  (when-let [ios-sess (:ios-session @!state)]
    (let [flags (get @!state :launch-flags {})]
      (try
        (ios/stop! ios-sess {:shutdown-simulator? (boolean (get flags "shutdown-simulator"))})
        (catch Exception e (warn "stop-ios" e))))
    (swap! !state assoc :ios-session nil :backend nil)
    (reset! sci-env/!backend nil)
    (reset! sci-env/!ios-session nil)))

(defonce ^:private !ios-start-lock
  ;; Serializes iOS backend startup. Client connections are handled
  ;; concurrently, and a cold start takes 40s+ — a retried `open` arriving
  ;; mid-start MUST wait for the in-flight start and reuse its backend.
  ;; Without this, the duplicate start spawns a second WDA/xcodebuild for
  ;; the same UDID, which SIGTERMs the first one and severs the remote
  ;; debugger of the session that was about to succeed.
  (Object.))

(declare ensure-ios-backend!*)

(defn- ensure-ios-backend!
  "Lazily starts the iOS application backend from persisted launch flags.

   Validates iOS-only constraints, selects and locks the simulator, boots it
   when needed, starts/connects Appium, and creates the XCUITest WebDriver
   session. Startup is serialized and idempotent — concurrent callers block
   and then reuse the backend started by the winner. Playwright is NEVER
   initialized on this path."
  []
  (locking !ios-start-lock
    (ensure-ios-backend!*)))

(defn- ensure-ios-backend!*
  "Unsynchronized body of `ensure-ios-backend!` — only call under
   `!ios-start-lock`."
  []
  (when-not (:backend @!state)
    (let [flags (get @!state :launch-flags {})]
      ;; --allowed-domains containment cannot be guaranteed for an app's
      ;; native networking or before webview scripts run — reject both forms.
      (when (or (get flags "allowed-domains")
              (not (str/blank? (System/getenv "SPEL_ALLOWED_DOMAINS"))))
        (throw (ex-info (str "--allowed-domains / SPEL_ALLOWED_DOMAINS are not supported "
                          "by the iOS provider: equivalent containment cannot be "
                          "guaranteed for native app traffic or before webview scripts run. "
                          "Remove the flag and unset the env var to continue.")
                 {:error_code "unsupported_capability"
                  :backend    "ios"})))
      (let [ios-sess (ios/start! {:session          (:session @!state)
                                  :device           (get flags "device")
                                  :udid             (get flags "udid")
                                  :platform-version (get flags "platform-version")
                                  :bundle-id        (get flags "bundle-id")
                                  :app              (get flags "app")
                                  :appium-url       (get flags "appium-url")})
            b        (backend/ios-backend ios-sess)]
        (swap! !state assoc
          :backend b
          :ios-session ios-sess
          :provider "ios"
          :ios-device (select-keys (:device ios-sess) [:name :udid :platform-version]))
        ;; Keep sci-env in sync so sci_eval scripts dispatch through the
        ;; same backend instead of failing on missing Playwright atoms.
        (reset! sci-env/!backend b)
        (reset! sci-env/!ios-session ios-sess)))))

(defn- ios-backend
  "Returns the active iOS backend, starting it lazily."
  []
  (ensure-ios-backend!)
  (:backend @!state))

(defn- ios-publish-refs!
  "Makes a captured iOS snapshot's refs the current ones."
  [snap]
  (swap! !state assoc
    :refs (:refs snap) :counter (:counter snap) :refs-stale? false)
  snap)

(defn- ios-refresh-refs!
  "Recaptures the screen so @refs describe what is on it now. Returns nil when
   the capture fails; the caller then reports the ref as stale."
  [b]
  (try
    (ios-publish-refs! (backend/capture-snapshot! b {}))
    (catch Exception e
      (warn "ios-snapshot" e)
      nil)))

(defn- ios-check-ref!
  "Verifies an @ref against the screen as it is NOW: recaptures when an earlier
   command may have repainted it, then fails with a stale-ref error when the
   ref is gone.

   A native recapture reads the app's whole XCTest hierarchy — seconds on a
   webview-heavy app — so it is paid here, where a ref is about to be used,
   and never after an action that no ref follows."
  [b ^String selector]
  (when (ref? selector)
    (let [ref-id (str/replace selector #"^@" "")
          state  @!state]
      (when (or (:refs-stale? state) (not (get (:refs state) ref-id)))
        (ios-refresh-refs! b))
      (when-not (get (:refs @!state) ref-id)
        (throw (ex-info (str "Ref " ref-id " not found.\n"
                          "  - Element found: No\n"
                          "  - Suggestion: run 'snapshot -i' and retry.")
                 {:selector selector :found false :stale-ref true}))))))

(defn- ios-resolve-refs!
  "Makes an @ref selector resolvable before the command that uses it runs.

   Every iOS command names its element in the same param, so the check lives on
   the dispatch path instead of in each handler: a handler that forgot it acted
   on whatever element the stale ref now named. `snapshot` is exempt — it
   recaptures by itself and its selector only scopes that capture."
  [action params]
  (let [selector (get params "selector")]
    (when (and (string? selector) (ref? selector) (not= "snapshot" action))
      (ios-check-ref! (ios-backend) selector))))

(defmulti ^:private handle-ios-cmd
  "Command dispatch for the iOS application backend. Only backend-neutral
   commands supported by WebDriver have methods; everything else returns an
   explicit capability error via :default."
  (fn [action _params] action))

(defmethod handle-ios-cmd :default [action _]
  {:success false
   :error (str "'" action "' is not supported by the ios backend. "
            "Supported: navigate, snapshot, click, fill, clear, evaluate, "
            "screenshot, url, title, content, back, forward, reload, wait, "
            "cookies (read-only), scroll, press, keyboard, element queries, "
            "device_list, session_info, close. "
            "Use the default Playwright provider for advanced features "
            "(network, tracing, tabs, frames, emulation).")
   :error_code "unsupported_capability"
   :backend "ios"})

(defmethod handle-ios-cmd "navigate" [_ {:strs [url raw-input]}]
  (let [b (ios-backend)]
    (page/validate-url url (or raw-input url))
    (backend/navigate! b url {})
    {:url      (backend/current-url b)
     :title    (try (backend/page-title b) (catch Exception _ nil))
     :provider "ios"}))

(defmethod handle-ios-cmd "snapshot" [_ params]
  (let [b       (ios-backend)
        all?    (get params "all")
        snap    (backend/capture-snapshot! b (cond-> {}
                                               (get params "selector")
                                               (assoc :scope (get params "selector"))))
        _       (ios-publish-refs! snap)
        tree    (filter-snapshot-tree (:tree snap) params)
        context (ios/current-context (:ios-session @!state))]
    (cond-> {:snapshot tree
             :refs_count (:counter snap)
             :refs (build-structured-refs (:refs snap))
             :context context}
      (not (:native snap))
      (assoc :url (backend/current-url b)
        :title (try (backend/page-title b) (catch Exception _ nil)))
      (:native snap)
      (assoc :warning (str "Native XCTest semantic hierarchy. Use @refs or "
                        "accessibility-id=, id=, role=, xpath=, class-chain=, "
                        "and predicate= selectors."))
      (:viewport snap) (assoc :viewport (:viewport snap))
      ;; Frames are out of scope — a full/frame snapshot degrades explicitly.
      all? (assoc :warning "Frame snapshots are not supported by the ios backend; returning the active context only."))))

(defmethod handle-ios-cmd "click" [_ {:strs [selector x y]}]
  (let [b (ios-backend)]
    (if (and x y)
      (do
        (backend/tap! b [(long x) (long y)] {})
        {:clicked [(long x) (long y)]})
      (do
        (when (str/blank? (str selector))
          (throw (ex-info "click requires a selector, @ref, or x y coordinates" {})))
        (backend/click! b selector {})
        {:clicked selector}))))

(defmethod handle-ios-cmd "fill" [_ {:strs [selector value]}]
  (let [b (ios-backend)]
    (backend/fill! b selector value {})
    {:filled selector}))

(defmethod handle-ios-cmd "type" [_ {:strs [selector text]}]
  (ios-backend)
  (ios/type-element! (:ios-session @!state) selector text)
  {:typed selector})

(defmethod handle-ios-cmd "clear" [_ {:strs [selector]}]
  (let [b (ios-backend)]
    (backend/clear! b selector {})
    {:cleared selector}))

(defmethod handle-ios-cmd "evaluate" [_ {:strs [script base64]}]
  (let [b      (ios-backend)
        result (backend/evaluate! b script [])]
    (if base64
      {:result (.encodeToString (Base64/getEncoder)
                 (.getBytes (str result) "UTF-8"))}
      {:result result})))

(defmethod handle-ios-cmd "screenshot" [_ params]
  (when (get params "annotate")
    (throw (ex-info (str "Annotated screenshots are not supported by the ios "
                      "backend yet. Take a plain screenshot instead.")
             {:error_code "unsupported_capability" :backend "ios"})))
  (let [b        (ios-backend)
        ^bytes bs (backend/screenshot! b {})
        path-str (or (get params "path")
                   (str (System/getProperty "java.io.tmpdir")
                     java.io.File/separator
                     "spel-screenshot-" (System/currentTimeMillis) ".png"))
        out-path (Path/of ^String path-str (into-array String []))]
    (when-let [parent (.getParent out-path)]
      (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
    (Files/write out-path bs
      ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
    {:path path-str :size (alength bs)}))

(defmethod handle-ios-cmd "url" [_ _]
  {:url (backend/current-url (ios-backend))})

(defmethod handle-ios-cmd "title" [_ _]
  {:title (backend/page-title (ios-backend))})

(defmethod handle-ios-cmd "content" [_ _]
  {:content (backend/page-content (ios-backend))})

(defmethod handle-ios-cmd "get_text" [_ {:strs [selector]}]
  (ios-backend)
  {:text (ios/element-text (:ios-session @!state) selector)})

(defmethod handle-ios-cmd "get_attribute" [_ {:strs [selector attribute]}]
  (ios-backend)
  {:value (ios/element-attribute (:ios-session @!state) selector attribute)})

(defmethod handle-ios-cmd "get_value" [_ {:strs [selector]}]
  (ios-backend)
  {:value (ios/element-attribute (:ios-session @!state) selector "value")})

(defmethod handle-ios-cmd "get_count" [_ {:strs [selector]}]
  (ios-backend)
  {:count (ios/element-count (:ios-session @!state) selector)})

(defmethod handle-ios-cmd "count" [_ params]
  (handle-ios-cmd "get_count" params))

(defmethod handle-ios-cmd "get_box" [_ {:strs [selector]}]
  (ios-backend)
  {:box (ios/element-rect (:ios-session @!state) selector)})

(defmethod handle-ios-cmd "bounding_box" [_ params]
  (handle-ios-cmd "get_box" params))

(defmethod handle-ios-cmd "is_visible" [_ {:strs [selector]}]
  (ios-backend)
  {:visible (:visible (ios/element-state (:ios-session @!state) selector))})

(defmethod handle-ios-cmd "is_enabled" [_ {:strs [selector]}]
  (ios-backend)
  {:enabled (:enabled (ios/element-state (:ios-session @!state) selector))})

(defmethod handle-ios-cmd "is_checked" [_ {:strs [selector]}]
  (ios-backend)
  {:checked (:selected (ios/element-state (:ios-session @!state) selector))})

(defmethod handle-ios-cmd "back" [_ _]
  (let [b (ios-backend)]
    (backend/go-back! b)
    {:url (backend/current-url b)}))

(defmethod handle-ios-cmd "forward" [_ _]
  (let [b (ios-backend)]
    (backend/go-forward! b)
    {:url (backend/current-url b)}))

(defmethod handle-ios-cmd "reload" [_ _]
  (let [b (ios-backend)]
    (backend/reload! b)
    {:url (backend/current-url b)}))

(defmethod handle-ios-cmd "cookies_get" [_ {:strs [urls]}]
  ;; Cookie READ is part of the iOS supported surface (W3C GET /cookie).
  (let [b (ios-backend)]
    (cond-> {:cookies (backend/cookies b)}
      urls (assoc :warning (str "URL filtering is not supported by the "
                             "ios backend; returning all cookies "
                             "for the current page.")))))

(defmethod handle-ios-cmd "cookies_set" [_ _]
  {:success false
   :error (str "'cookies set' is not supported by the ios backend — "
            "the WebDriver webview context exposes read-only cookie access. "
            "Set non-HttpOnly cookies with eval-js "
            "(document.cookie = \"name=value\") or use the default "
            "Playwright provider.")
   :error_code "unsupported_capability"
   :backend "ios"})

(defmethod handle-ios-cmd "cookies_clear" [_ _]
  {:success false
   :error (str "'cookies clear' is not supported by the ios backend — "
            "the WebDriver webview context exposes read-only cookie access. "
            "Use the default Playwright provider to clear cookies.")
   :error_code "unsupported_capability"
   :backend "ios"})

(defn- ios-poll-until
  "Polls `pred` every 250ms until it returns truthy or `timeout-ms` expires.
   Returns true on success, throws ex-info on timeout."
  [pred timeout-ms desc]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (try (pred) (catch Exception _ false)) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "Timed out after " timeout-ms "ms waiting for " desc) {}))
        :else (do (Thread/sleep 250) (recur))))))

(defmethod handle-ios-cmd "wait" [_ params]
  (let [b          (ios-backend)
        timeout-ms (long (or (get params "timeout-ms") 30000))]
    (cond
      (get params "selector")
      (let [sel (get params "selector")]
        (if (ios/native-context? (:ios-session @!state))
          (ios/wait-for-element (:ios-session @!state) sel {:timeout-ms timeout-ms})
          (let [css (backend/resolve-css sel)
                js  (str "!!document.querySelector(" (json/write-json-str css) ")")]
            (ios-poll-until #(true? (backend/evaluate! b js [])) timeout-ms
              (str "selector " sel))))
        {:found sel})

      (get params "text")
      (let [text (get params "text")
            js   (str "(document.body ? document.body.innerText : '').includes("
                   (json/write-json-str text) ")")]
        (ios-poll-until #(true? (backend/evaluate! b js [])) timeout-ms
          (str "text " (pr-str text)))
        {:found_text text})

      (get params "url")
      (let [expected (get params "url")]
        (ios-poll-until #(str/includes? (str (backend/current-url b)) expected)
          timeout-ms (str "url containing " expected))
        {:url expected})

      (get params "function")
      (do
        (ios-poll-until #(let [r (backend/evaluate! b (get params "function") [])]
                           (and r (not (false? r))))
          timeout-ms "JavaScript condition")
        {:function_completed true})

      (get params "timeout")
      (do (Thread/sleep (long (get params "timeout")))
          {:waited (get params "timeout")})

      :else
      {:error "No wait condition specified"})))

(defmethod handle-ios-cmd "press" [_ {:strs [selector key]}]
  (ios-backend)
  (when (str/blank? key) (throw (ex-info "press requires a key" {})))
  (if selector
    (ios/press-key! (:ios-session @!state) selector key)
    (ios/press-key! (:ios-session @!state) key))
  {:pressed key})

(defmethod handle-ios-cmd "keyboard_type" [_ {:strs [text]}]
  (ios-backend)
  {:typed (ios/type-keys! (:ios-session @!state) text)})

(defmethod handle-ios-cmd "keyboard_inserttext" [_ {:strs [text]}]
  (handle-ios-cmd "keyboard_type" {"text" text}))

(defmethod handle-ios-cmd "keyboard_hide" [_ _]
  (ios-backend)
  (ios/hide-keyboard! (:ios-session @!state))
  {:hidden true})

(defmethod handle-ios-cmd "scroll" [_ {:strs [direction amount selector smooth]}]
  (ios-backend)
  (let [direction (keyword (or direction "down"))
        result    (ios/scroll (:ios-session @!state) direction (long (or amount 500))
                    {:selector selector :smooth? (boolean smooth)})]
    {:scrolled (name direction) :from (:from result) :to (:to result)}))

(defmethod handle-ios-cmd "tap" [_ {:strs [selector x y]}]
  (let [b (ios-backend)]
    (if (and x y)
      (do (backend/tap! b [(long x) (long y)] {})
          {:tapped [(long x) (long y)]})
      (do
        (when (str/blank? (str selector))
          (throw (ex-info "tap requires a selector/@ref or x y coordinates" {})))
        (let [result (backend/tap! b selector {})]
          {:tapped selector :x (:x result) :y (:y result)})))))

(defmethod handle-ios-cmd "swipe" [_ {:strs [direction distance from to duration]}]
  (let [b    (ios-backend)
        opts (cond-> {}
               direction (assoc :direction (keyword direction))
               distance  (assoc :distance (long distance))
               from      (assoc :from (mapv long from))
               to        (assoc :to (mapv long to))
               duration  (assoc :duration (long duration)))
        result (backend/swipe! b opts)]
    {:swiped (or direction "coordinates")
     :from (:from result)
     :to (:to result)}))

(defmethod handle-ios-cmd "session_info" [_ params]
  ;; Delegate to the main session_info handler — it is backend-aware.
  (handle-cmd "session_info" params))

(defmethod handle-ios-cmd "close" [_ params]
  (handle-cmd "close" params))

(def ^:private ios-passthrough-actions
  "Actions that bypass iOS dispatch and run their normal handlers even when
   the iOS provider is active: session management, diagnostics, local buffer
   reads, and SCI eval (sci-env is backend-aware)."
  #{"session_list" "device_list" "find_free_port" "health" "cancel"
    "action_log" "action_log_srt" "action_log_clear"
    "sci_eval"})

(def ^:private ios-refs-preserving-actions
  "iOS commands that cannot repaint the screen, so the ref map from the last
   snapshot still describes it. Every other command — including one added
   later — invalidates the refs, and the recapture is deferred to the next
   @ref, so a gesture nobody follows with a ref pays for no snapshot at all."
  #{"snapshot" "screenshot" "url" "title" "content" "session_info" "device_list"
    "get_text" "get_attribute" "get_value" "get_count" "count" "get_box"
    "bounding_box" "is_visible" "is_enabled" "is_checked" "cookies_get"
    "health" "session_list" "action_log" "action_log_srt" "find_free_port"})
(defn- with-ios-request-lock
  "Runs an iOS daemon request under the session operation lock when the
   session has already been started. The lock is reentrant, so scoped SCI
   callbacks and backend operations can safely acquire it again."
  [callback]
  (if-let [session (:ios-session @!state)]
    (ios/with-operation session callback)
    (callback)))

(defn- dispatch-cmd
  "Routes a command to the iOS dispatch table when the iOS provider is
   active, otherwise to the regular Playwright handlers."
  [action params]
  (if (ios-provider?)
    (try
      (if (contains? ios-passthrough-actions action)
        (do
          ;; SCI provider functions need the same lazily-created iOS session
          ;; even when sci_eval is the first command sent to the daemon.
          (when (= "sci_eval" action) (ios-backend))
          (with-ios-request-lock
            (fn dispatch-ios-passthrough []
              (handle-cmd action params))))
        (with-ios-request-lock
          (fn dispatch-ios-command []
            (ios-resolve-refs! action params)
            (handle-ios-cmd action params))))
      (finally
        ;; A failed command repaints as readily as a successful one.
        (when-not (contains? ios-refs-preserving-actions action)
          (swap! !state assoc :refs-stale? true))))
    ;; A browser ref is a stamp in the DOM, so a needless invalidation costs one
    ;; selector query, not a walk: every command that did not publish refs is
    ;; taken to have changed the page — including evaluate, which used to leave
    ;; the refs looking fresh after rewriting the document.
    (let [generation (:refs-generation @!state)]
      (try
        (handle-cmd action params)
        (finally
          (when (= generation (:refs-generation @!state))
            (swap! !state assoc :refs-stale? true)))))))

(def ^:private no-recovery-actions
  "Actions never re-run after a browser loss: lifecycle and diagnostics, where
   a second attempt is meaningless or destructive."
  #{"close" "health" "cancel" "session_info" "session_list"
    "connect" "cdp_disconnect" "cdp_reconnect" "state_save"})

(def ^:private no-drain-actions
  "Actions that must answer while the driver is wedged, so they never round-trip to
   it first. `health` is the command you run when nothing else answers: it reports
   the browser facts Playwright last delivered, which is exactly what a wedged
   session has to show. Everything else drains first, so what it reports is the
   browser's state and not a snapshot from whenever the last command ran."
  #{"close" "health" "cancel" "session_info" "session_list"})

(defn- current-url-quietly
  "The page's URL, or nil. Playwright answers from cached state, so this costs
   nothing and works even when the browser stopped answering."
  []
  (try (when-let [p (:page @!state)] (page/url p))
       (catch Throwable _ nil)))

(defn- restore-page!
  "Re-opens `url` on the freshly relaunched browser so a recovered session lands
   back where it was instead of on a blank page."
  [url]
  (when (and url (not (contains? #{"" "about:blank"} url)))
    (try
      (page/navigate (pg) url)
      (page/wait-for-load-state (pg))
      (log/info! "restored " url " on the relaunched browser")
      (catch Throwable e
        (log/warn! "could not restore " url " after relaunch: " (.getMessage e))))))

(defn- replace-crashed-page!
  "Recovers from a renderer crash: records it, opens a fresh tab, and either
   re-runs a navigation on that tab or tells the caller what their command lost.

   Without this, `open` answered rc=0 in 3 ms echoing the URL of the dead tab
   without navigating anywhere, and every other command answered \"Target
   crashed\" for the rest of the session (issue #127).

   Params:
   `action`     - String action name.
   `url-before` - URL the dead tab was on, or nil.
   `attempt`    - Thunk answering {:ok _} or {:threw _}, re-run for a navigation.

   Returns:
   The navigation result, or throws ex-info {:error_code :page_crashed}."
  [action url-before attempt]
  (note-page-crash! (:page @!state))
  (ensure-live-browser!)
  (if (= "navigate" action)
    ;; Navigation is the one command a crash cannot half-apply, and it is what
    ;; the caller would type next anyway, so it is re-run on the fresh tab.
    (let [{:keys [ok threw]} (attempt)]
      (if threw (throw threw) ok))
    (do
      (restore-page! url-before)
      (throw (ex-info (page-crash-message action url-before)
               {:error_code :page_crashed})))))

(defn- relaunch-and-retry!
  "Throws away the dead handles, brings a browser back on the page that was
   open, and runs `attempt` once more.

   Params:
   `action`     - String action name.
   `url-before` - URL the session was on, or nil.
   `attempt`    - Thunk answering {:ok _} or {:threw _}.

   Returns:
   The retried result, or throws what the retry threw."
  [action url-before attempt]
  (log/warn! "browser died during '" action "' — relaunching it and retrying once")
  (drop-browser-handles!)
  (ensure-browser!)
  (when-not (= "navigate" action)
    (restore-page! url-before))
  (let [{:keys [ok threw]} (attempt)]
    (if threw (throw threw) ok)))

(defn- dispatch-with-recovery
  "Runs a command, and when it failed ONLY because the browser died outside the
   daemon, relaunches the browser, re-opens the page that was open, and runs the
   command once more.

   `isConnected` lags the real disconnect, so the failed call is the first
   reliable signal. Without this recovery, quitting the browser left every later
   command answering 'Target page, context or browser has been closed' for the
   rest of the session — killing the daemon was the only way out.

   A renderer crash is checked BEFORE the command runs as well as after it
   failed: once the crash event has been recorded there is nothing to learn from
   sending one more call into the dead tab (issue #127)."
  [action params]
  (clear-tab-loss!)
  (when-not (contains? no-drain-actions action)
    (drain-driver-events!))
  (let [attempt      (fn run-command []
                       (try {:ok (dispatch-cmd action params)}
                            (catch Throwable e {:threw e})))
        url-before   (current-url-quietly)
        recoverable? (not (contains? no-recovery-actions action))]
    (if (and recoverable? (page-crashed? (:page @!state)))
      (replace-crashed-page! action url-before attempt)
      (let [{:keys [ok threw]} (attempt)
            anomaly (when (anomaly/anomaly? ok) ok)
            msg     (cond
                      threw   (throwable-chain-message threw)
                      anomaly (str (::anomaly/message anomaly) " "
                                (when-let [ex (:playwright/exception anomaly)]
                                  (throwable-chain-message ex)))
                      (and (map? ok) (false? (:success ok))) (str (:error ok)))]
        (cond
          ;; A dead renderer is not a dead browser: the tab is unusable and every
          ;; later command on it answered "Target crashed" forever, while `open`
          ;; reported success without navigating anywhere (issue #127). The tab is
          ;; replaced here, on the command that met the crash.
          (and (page-crashed-message? msg) recoverable?)
          (replace-crashed-page! action url-before attempt)

          (and (browser-gone-message? msg) recoverable?)
          ;; "Target page, context or browser has been closed" is also what ONE
          ;; closed TAB says. Relaunching for that threw the whole session away
          ;; — its tabs, its refs and every per-tab capture — because somebody
          ;; closed a tab spel was not even driving. Reconcile what actually
          ;; died and retry; only a browser that is really gone is relaunched.
          (let [reconciled (ensure-live-browser!)]
            ;; A closed tab is not a dead browser — but the command that met it lost
            ;; just as much, so it is not silently re-run on whatever tab the session
            ;; landed on. `navigate` is the exception: it IS the recovery, and
            ;; re-running it puts the caller where they asked to be.
            (if (= "navigate" action) (clear-tab-loss!) (raise-tab-loss!))
            (if (and (browser-connected?) (not= :dead reconciled))
              (let [{:keys [ok threw]} (attempt)]
                (cond
                  (nil? threw) ok
                  (browser-gone-message? (throwable-chain-message threw))
                  (relaunch-and-retry! action url-before attempt)
                  :else (throw threw)))
              (relaunch-and-retry! action url-before attempt)))

          :else (if threw (throw threw) ok))))))

(defmethod handle-cmd "tap" [_ _]
  ;; Playwright provider: native touch tap is iOS-only. Fail with an explicit
  ;; capability error instead of an "Unknown action" fallthrough.
  {:success false
   :error (str "'tap' (native touch) requires the iOS provider. "
            "Start the session with --provider ios, or use 'click' "
            "with the default Playwright provider.")
   :error_code "unsupported_capability"
   :backend "playwright"})

(defmethod handle-cmd "swipe" [_ _]
  {:success false
   :error (str "'swipe' (native touch) requires the iOS provider. "
            "Start the session with --provider ios, or use 'scroll' "
            "with the default Playwright provider.")
   :error_code "unsupported_capability"
   :backend "playwright"})

(defmethod handle-cmd "device_list" [_ _]
  {:provider "playwright"
   :devices (vec (sort (devices/available-device-names)))
   :count (count (devices/available-device-names))})

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

(defn- ios-flag-rejection
  "Returns a capability-error map when a command combines the iOS provider
   with --allowed-domains, else nil.

   Checked on EVERY command BEFORE the incoming flags merge into
   launch-flags — the startup-time check in `ensure-ios-backend!*` alone is
   insufficient because it only runs while no backend exists yet, so a
   later `open --allowed-domains` against a running iOS backend would be
   silently accepted (and the merged flag would poison the session)."
  [incoming-flags]
  (let [persisted (get @!state :launch-flags {})
        provider  (or (get incoming-flags "provider")
                    (get persisted "provider"))]
    (when (and (= "ios" provider)
            (or (get incoming-flags "allowed-domains")
              (get persisted "allowed-domains")))
      {:success false
       :error (str "--allowed-domains / SPEL_ALLOWED_DOMAINS are not supported "
                "by the iOS provider: equivalent containment cannot be "
                "guaranteed before Safari page scripts run. Remove the flag "
                "and unset the env var, or use the default Playwright "
                "provider for domain allowlisting.")
       :error_code "unsupported_capability"
       :backend "ios"})))

(declare process-command*)

(def ^:private logged-error-max-chars
  "Cap for error text copied into the session log."
  240)

(defn- response-failure
  "Extracts `{:code :message}` from a FAILING daemon response, else nil.

   Only failures are parsed: a successful response can be megabytes of snapshot
   JSON and carries nothing worth logging, while a failure carries the only
   account of what went wrong that anyone will ever read."
  [^String response]
  (let [head (subs response 0 (min 96 (count response)))]
    (when (str/includes? head "\"success\":false")
      (let [parsed  (try (json/read-json response) (catch Throwable _ nil))
            pick    (fn [k]
                      (when (map? parsed)
                        (let [v (or (get parsed k) (get parsed (keyword k)))]
                          (when (string? v) (not-empty (str/trim v))))))
            message (pick "error")]
        {:code    (or (pick "error_code") "unknown")
         :message (when message
                    (if (> (count message) (long logged-error-max-chars))
                      (str (subs message 0 (long logged-error-max-chars)) "…")
                      message))}))))

(defn- log-command!
  "Records one command in the session log: action, param NAMES (never values —
   they can carry credentials), outcome, duration, and, when it failed, the
   error_code plus truncated error text.

   Without that detail every failure line read `-> error in Nms`: it said a
   command failed and nothing whatsoever about why, so post-mortems from a
   session log were impossible."
  [^String action params ^String response ^long ms]
  (let [failure (response-failure response)]
    (log/log! (if failure :warn :info)
      (str "cmd " action
        (when (seq params) (str " " (pr-str (vec (sort (map str (keys params)))))))
        " -> " (if failure "error" "ok") " in " ms "ms"
        (when failure
          (str " code=" (:code failure)
            (when-let [m (:message failure)] (str " error=" (pr-str m)))))))))

(defn- budget-exceeded-response
  "The answer for a command the daemon interrupted because it outran its
   budget. It is produced from BOTH ends of that interrupt — the watchdog that
   fires it and the worker that catches the `InterruptedException` — because
   whichever answer reaches the client first must carry the same diagnosis and
   the same escape hatch."
  [^String cid ^String action ^long budget-ms]
  (json/write-json-str
    {:success    false
     :error      (str "command '" action "' exceeded the daemon budget of "
                   budget-ms "ms and was interrupted")
     :error_code "command_timeout"
     :hint       (str "Raise the ceiling with SPEL_COMMAND_BUDGET_MS=<ms> if the work is "
                   "genuinely long; the daemon is still alive and `spel health` shows "
                   "what is running.")
     :command_id cid}))

(defn- page-crash-response
  "The answer for a command the daemon stopped because the page's renderer died
   under it. Playwright never fails a call whose renderer went away — measured:
   the call sat there until the 25s command budget interrupted it, 23 of those
   seconds after the tab was already dead (issue #127) — so the watchdog ends it
   as soon as the crash event lands and reports THAT, not a timeout.

   Params:
   `cid`    - String command id.
   `action` - String action name.
   `url`    - URL the dead page was on, or nil.

   Returns:
   JSON string."
  [^String cid ^String action url]
  (json/write-json-str
    {:success    false
     :error      (page-crash-message action url)
     :error_code "page_crashed"
     :hint       "Re-run the command; `spel health` shows the fresh tab."
     :command_id cid}))

(defn- cancelled-response
  "The answer for a command that ended because it was cancelled. Its own failure
   text describes a torn-down browser call (\"Failed to read message\") and points
   a caret at the user's snippet — which reads like a bug in that snippet.

   A budget interrupt and a renderer crash are NOT cancellations, even though
   both arrive as the same `InterruptedException`: `reason` is the record of why
   the interrupt was sent, so the caller is told what actually ended their
   command instead of being sent to `spel health`, which shows nothing for a
   command that has already ended.

   The reason is passed IN rather than read from the ledger: `ledger-finish!`
   removes the entry before the last caller runs, and reading it there answered
   a renderer crash with a bare \"was cancelled\" (issue #127).

   Params:
   `cid`    - String command id.
   `action` - String action name.
   `reason` - The `:cancel-reason` map recorded before the interrupt, or nil.

   Returns:
   JSON string."
  [^String cid ^String action reason]
  (let [{:keys [budget-ms page-crashed url]} reason]
    (cond
      page-crashed (page-crash-response cid action url)
      budget-ms    (budget-exceeded-response cid action (long budget-ms))
      :else
      (json/write-json-str
        {:success    false
         :error      (str "command " cid " (" action ") was cancelled")
         :error_code "cancelled"
         :hint       "`spel health` lists what is still running"}))))

(def ^:private default-command-budget-ms
  "Hard ceiling for ONE browser command inside the daemon.

   Playwright's own timeouts cover a call that is merely slow. They do not
   cover a call that never returns — a dead driver pipe, a crashed renderer,
   a wedged input sequence. Before this budget existed, one such command kept
   `!command-lock` forever and every later command in the session timed out in
   the client with no explanation, so a single wedge failed a whole run."
  (or (some-> (System/getenv "SPEL_COMMAND_BUDGET_MS") parse-long) 25000))

(def ^:private open-ended-actions
  "Commands whose runtime is legitimately measured in minutes — user scripts,
   device provisioning, installs. They get a far larger budget instead of none,
   so even these cannot wedge the daemon permanently."
  #{"sci_eval" "script" "install" "codegen" "record"})

(defn command-budget-ms
  "Budget for `action`: at least `default-command-budget-ms`, always at least
   2x the configured action timeout, and minutes for open-ended work.

   `ios?` says the session is driven by the iOS provider, where EVERY command
   is open-ended — a `snapshot` is a WDA page-source dump of the whole native
   tree and a `click` waits on that tree — so the browser's 25s ceiling
   interrupted healthy commands mid-flight. Matching on an `ios` action NAME
   was never enough: the iOS backend answers the ordinary `snapshot`, `click`
   and `type` actions, and only the daemon's own state knows which backend
   they reach. The single-argument arity asks that state, which is empty in
   the CLI process — clients pass `ios?` explicitly.

   Public because the CLI must size its transport timeout from the same number —
   see `client-timeout-ms`."
  (^long [action] (command-budget-ms action (ios-provider?)))
  (^long [action ios?]
   (if (or ios?
         (contains? open-ended-actions action)
         (str/starts-with? (str action) "ios"))
     900000
     (max (long default-command-budget-ms)
       (* 2 (long (or (get-in @!state [:launch-flags "timeout"])
                    default-action-timeout-ms)))))))

(def ^:private client-transport-slack-ms
  "Head start the daemon keeps over its client. The daemon must report a wedged
   command FIRST; the client only gives up when the daemon itself is gone."
  5000)

(defn client-timeout-ms
  "Transport budget a client must allow for `action`: the daemon's own budget
   plus slack. `ios?` mirrors `command-budget-ms` — a CLI process cannot read
   the daemon's state, so it says so from its own flags.

   The invariant is client timeout > daemon budget. Violating it (a flat 30s
   client against the 900s budget for `sci_eval`/`ios*`) made the CLI walk away
   from commands the daemon was still executing: the work continued unwatched,
   the reply landed on a closed socket, and the session log filled with
   `handle-connection: Broken pipe` instead of an answer."
  (^long [action]
   (+ (command-budget-ms action) (long client-transport-slack-ms)))
  (^long [action ios?]
   (+ (command-budget-ms action ios?) (long client-transport-slack-ms))))

(def ^:private signal-frame-prefixes
  ["com.blockether." "com.microsoft.playwright." "sci."])

(defn- signal-frames
  "Keeps the frames that name spel, Playwright or user-script code.

   A parked thread's top frames are all `Unsafe.park` / `CompletableFuture`
   plumbing, so an unfiltered dump described the JVM's waiting machinery rather
   than the wedged call. Falls back to the raw top frames when nothing matches,
   because a noisy stack still beats no stack."
  [frames]
  (let [interesting (filterv (fn [^String f]
                               (some #(str/starts-with? f ^String %) signal-frame-prefixes))
                      frames)]
    (vec (take 12 (if (seq interesting) interesting frames)))))

(defn- stuck-command-frames
  "Top meaningful stack frames of a wedged command — the only way to learn WHERE
   a never-returning browser call is parked, since it produces no exception."
  [^Thread t]
  (signal-frames (mapv str (.getStackTrace t))))

(defn- busy-response
  [action]
  (let [in-flight (ledger-entries)]
    (json/write-json-str
      {:success    false
       :error      (str "daemon is busy: '" action "' waited for the command lock but "
                     (if-let [e (first in-flight)]
                       (str "'" (:action e) "' (" (:id e) ") has been running for " (:running e))
                       "another command is still running"))
       :error_code "daemon_busy"
       :hint       "Inspect it with `spel health`, then `spel cancel all` to clear it."
       :in_flight  in-flight})))

(def ^:private wedge-grace-ms
  "How long an interrupted command gets to answer before the daemon writes it off.
   A call still talking to a live browser answers in milliseconds; only one whose
   browser will never answer needs the whole grace."
  2000)

(def ^:private max-lost-commands
  "Lost commands kept for `health` — enough to show a pattern, never enough to
   grow without bound."
  5)

(defn- abandon-wedged-command!
  "Gives up on a command that survived its interrupt, and gives the SESSION back.

   LAST RESORT, and measured as such: every wedge class this daemon has been able
   to produce now ends with a named cause first — a crashed renderer ends the
   command in ~0.1s (kill -9 of the render process) to 1.8s (a page allocating
   until Chrome kills it), a killed browser is rejected by Playwright in ~3ms, and
   a script that never resolves is freed by the budget interrupt. None of those
   reach this function. It exists for the one thing none of them cover: a worker
   that ignores its interrupt because the call is parked in Playwright's pipe. It
   held `!command-lock` for as long as the browser stayed silent, so every later
   command answered `daemon_busy` naming a command `health` no longer listed — the
   session was dead and reported itself healthy (issue #125).

   The caller is never left to guess: the crash or budget answer is already on its
   way out when this runs. Here the lock is replaced rather than waited on, the
   browser whose pipe swallowed the call is dropped — which is also what finally
   unblocks the zombie — and the loss is recorded where `health` shows it.

   Params:
   `cid`    - String. Command id.
   `action` - String. Command name.

   Returns:
   nil."
  [cid action]
  (log/warn! "command " cid " (" action ") did not answer its interrupt — abandoning it,"
    " dropping the browser and freeing the session; the next command starts a fresh browser")
  (swap! !lost-commands conj-window
    {:id cid :action action :at (System/currentTimeMillis)}
    max-lost-commands)
  (reset! !command-lock (ReentrantLock. true))
  (drop-browser-handles!)
  nil)

(def ^:private crash-watch-ms
  "How often the command watchdog looks up from the answer it is waiting for.

   Playwright never fails a call whose renderer died — measured: kill the render
   process while an evaluate is in flight and the call sits there until the 25s
   budget interrupts it, 23 of those seconds after the tab was already dead
   (issue #127). The crash event itself arrives on the parked thread, which can
   only record it, so the watchdog reads that record between slices and ends the
   command on the cause instead of on the clock."
  100)

(defn- run-guarded-command!
  "Runs a non-control command on a worker thread under `!command-lock`, bounded
   by `command-budget-ms`. On expiry the worker is interrupted, its stack is
   logged, and the client gets an actionable error instead of a silent hang. A
   worker that does not answer its interrupt is abandoned, taking its browser
   with it, so the session serves the next command instead of dying.

   The interrupt reason is recorded in the ledger BEFORE the interrupt is sent,
   so the worker's own `InterruptedException` answer — which races this one and
   sometimes wins — reports the budget rather than a phantom cancellation."
  [cid action params]
  (let [budget   (command-budget-ms action)
        deadline (+ (System/currentTimeMillis) (long budget))
        result (promise)
        worker (Thread.
                 ^Runnable
                 (fn []
                   (deliver result
                     (try
                       (let [queued-at (System/nanoTime)
                             ^ReentrantLock lock @!command-lock]
                         (if (.tryLock lock (quot budget 2) TimeUnit/MILLISECONDS)
                           (try
                              ;; Lock wait and run time are different failures. Logging
                              ;; only their sum made commands queued behind one long
                              ;; sci_eval look like several independently slow commands.
                             (let [waited-ms (quot (- (System/nanoTime) queued-at) 1000000)]
                               (when (>= waited-ms 1000)
                                 (log/info! "command " cid " (" action ") waited "
                                   (human-duration waited-ms) " for the command lock")))
                             (swap! !ledger assoc-in [cid :phase] "running")
                              ;; Anything that waits inside the command must answer
                              ;; before this deadline — an interrupt reports the clock,
                              ;; never the cause.
                             (binding [*command-deadline-ms* deadline]
                               (process-command* action params))
                             (finally (.unlock lock)))
                           (busy-response action)))
                       (catch InterruptedException _
                         (cancelled-response cid action
                           (get-in @!ledger [cid :cancel-reason])))
                       (catch Throwable e
                         (json/write-json-str
                           {:success false
                            :error   (str "daemon command failed: "
                                       (or (.getMessage e) (str e)))})))))
                 (str "spel-cmd-" cid))]
    (.setDaemon worker true)
    (swap! !ledger assoc-in [cid :thread] worker)
    (.start worker)
    (let [r        (loop []
                     (let [r (deref result crash-watch-ms ::timeout)]
                       (cond
                         (not= r ::timeout)                       r
                         (page-crashed? (:page @!state))          ::crashed
                         (>= (System/currentTimeMillis) deadline) ::timeout
                         :else                                    (recur))))]
      (cond
        (= r ::crashed)
        (let [url (current-url-quietly)]
          (log/warn! "command " cid " (" action ") was still running when the page's "
            "renderer crashed — ending it now instead of waiting out the "
            budget "ms budget")
          (swap! !ledger update cid merge {:cancel-requested true
                                           :cancel-reason    {:page-crashed true :url url}})
          (.interrupt worker)
          (when (= ::timeout (deref result wedge-grace-ms ::timeout))
            (abandon-wedged-command! cid action))
          (page-crash-response cid action url))

        (= r ::timeout)
        (do
          (log/warn! "command " cid " (" action ") exceeded " budget
            "ms — interrupting it. Stack: " (str/join " | " (stuck-command-frames worker)))
          (swap! !ledger update cid merge {:cancel-requested true
                                           :cancel-reason    {:budget-ms budget}})
          (.interrupt worker)
          ;; An interrupt only reaches a thread that can take one. A call parked
          ;; in Playwright's pipe cannot, and it holds the lock while it waits
          ;; (issue #125), so a command that ignores its interrupt is written off
          ;; and the session is handed back to the next caller.
          (when (= ::timeout (deref result wedge-grace-ms ::timeout))
            (abandon-wedged-command! cid action))
          (budget-exceeded-response cid action budget))

        :else r))))

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
      ;; iOS + --allowed-domains is rejected BEFORE the flags merge so the
      ;; unsupported flag never poisons the persisted launch flags.
      (if-let [rejection (ios-flag-rejection flags)]
        (json/write-json-str rejection)
        (do
          ;; Store launch flags if present (used by ensure-browser!)
          ;; Persist to disk so CLI can recover them on daemon restart.
          (when (seq flags)
            (swap! !state update :launch-flags merge flags)
            (persist-launch-flags!))
          (let [t0    (System/nanoTime)
                cid   (ledger-start! action)
                resp  (try
                        (if (contains? control-actions action)
                          (process-command* action params)
                          (run-guarded-command! cid action params))
                        (catch Throwable e
                          (json/write-json-str
                            {:success false
                             :error   (str "daemon command failed: "
                                        (or (.getMessage e) (str e)))})))
                entry (ledger-finish! cid)
                resp  (if (and (:cancel-requested entry)
                            (str/includes? resp "\"success\":false"))
                        (cancelled-response cid action (:cancel-reason entry))
                        resp)]
            (log-command! action params resp (quot (- (System/nanoTime) t0) 1000000))
            resp))))
    (catch Throwable e
      (json/write-json-str {:success false :error (str "Parse error: " (.getMessage e))}))))

(defn- process-command*
  "Dispatch + response serialization half of `process-command` — runs after
   flag validation/merge."
  [action params]
  (binding [*error-source* (cond
                             (get params "script")
                             {:source (get params "script") :lang :js}

                             (get params "expression")
                             {:source (get params "expression") :lang :js}

                             (get params "code")
                             {:source (get params "code") :lang :clj})]
    (if-let [{:keys [owner-session cdp-url tab]} (await-cdp-route-lock action)]
      (json/write-json-str
        {:success false
         :error (str "Session '" owner-session "' is intercepting network requests in the tab "
                  "session '" (:session @!state) "' drives, so action '" action "' cannot run. "
                  "Timed out waiting for that session to release interception.")
         :hint (str "Interception belongs to a tab, not to the browser: this endpoint is shared, "
                 "and any other tab is free right now — `spel --session " (:session @!state)
                 " tab new` gives this session its own. To keep this tab, release the routes: "
                 "`spel --session " owner-session " network unroute all`, or close that session.")
         :error_code "cdp_route_lock"
         :owner_session owner-session
         :cdp cdp-url
         :tab tab})
      (try
        (let [_         (when-let [page (:page @!state)]
                          (page/set-default-timeout! page
                            (double (or (get-in @!state [:launch-flags "timeout"])
                                      default-action-timeout-ms))))
              result    (dispatch-with-recovery action params)
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
              (json/write-json-str (error-response error-msg (error-context anomaly-v))))
            :else
            (do
              ;; Track user-facing actions for SRT export
              (when (trackable-actions action)
                (track-action! action params result))
              (json/write-json-str {:success true :data result}))))
        (catch Throwable e
          (let [hint (reflection-error-hint e)
                msg  (or hint (throwable-message e) (default-error-message e))
                data (ex-data e)
                ;; A failure the daemon raised itself — a crashed renderer, an
                ;; unreachable CDP endpoint — carries its own code, and it is not
                ;; a fault in the caller's snippet, so it gets no source caret
                ;; (issue #127).
                code (:error_code data)]
            (json/write-json-str (cond-> (binding [*error-source* (when-not code *error-source*)]
                                           (error-response msg (when-not code (error-context data e))))
                                   code (assoc :error_code (name code))
                                   (:stdout data) (assoc :data {:stdout (:stdout data)
                                                                :stderr (:stderr data)})))))))))

;; =============================================================================
;; Socket Server
;; =============================================================================

(defn- client-gone?
  "True when `e` is the socket reporting that the client hung up before we
   answered. Distinct from a daemon fault: nothing here is broken except the
   client's patience, and the fix is its transport timeout."
  [^Throwable e]
  (let [m (str (.getMessage e))]
    (or (str/includes? m "Broken pipe")
      (str/includes? m "Connection reset"))))

(defn- handle-connection
  "Handles a single client connection — reads commands, writes responses.
   Catches Throwable, not Exception: an Error escaping this loop (OOM,
   StackOverflowError from deep SCI recursion) would close the socket with no
   reply, and the client could only report an unexplained EOF."
  [^SocketChannel client]
  (let [reader (BufferedReader. (InputStreamReader. (Channels/newInputStream client)))
        ^OutputStreamWriter writer (OutputStreamWriter. (Channels/newOutputStream client))]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (let [stop (when-not (str/blank? line)
                       (let [response (process-command line)]
                         (.write writer ^String response)
                         (.write writer "\n")
                         (.flush writer)
                         ;; Check if shutdown was requested — and whether the
                         ;; client asked for the fast, ungraceful variant.
                         (let [parsed (try (json/read-json response) (catch Exception _ nil))]
                           (when (and parsed (get-in parsed ["data" "shutdown"]))
                             (if (get-in parsed ["data" "force"]) ::force ::graceful)))))]
            (if stop
              (submit-virtual #(stop-daemon! (if (identical? ::force stop)
                                               "client requested force kill"
                                               "client requested close")
                                 {:force? (identical? ::force stop)}))
              (recur)))))
      (catch Throwable e
        (if (client-gone? e)
          (log/warn! "client disconnected before the response was written — the daemon "
            "finished work nobody is waiting for. Raise SPEL_CLIENT_TIMEOUT_MS or shorten "
            "the command so the client outlives the daemon budget: "
            (log/describe-throwable e))
          (warn "handle-connection" e))
        ;; Never leave the client with a bare EOF — it cannot tell a crash
        ;; from a clean shutdown. Answer with the failure instead.
        (try
          (.write writer ^String (json/write-json-str
                                   {:success false
                                    :error   (str "daemon connection failed: "
                                               (or (throwable-message e) (str e)))}))
          (.write writer "\n")
          (.flush writer)
          (catch Throwable _ nil)))
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

(defn- live-daemon-pid
  "Returns the PID recorded in `session`'s PID file when that process is still
   alive, else nil. A record naming a dead process is stale, not an owner."
  [^String session]
  (let [pid-path (pid-file-path session)]
    (when (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try
        (let [pid (Long/parseLong (str/trim (String. (Files/readAllBytes pid-path))))
              ph  (.orElse (java.lang.ProcessHandle/of pid) nil)]
          (when (and ph (.isAlive ^java.lang.ProcessHandle ph)) pid))
        (catch Exception _ nil)))))

(defn- claim-session!
  "Claims the session NAME for this process. Returns nil when this process now
   owns the session, or the PID of the live daemon that already owns it.

   The PID file is the ownership record for a session name, so the claim is an
   atomic CREATE_NEW that either wins or loses — never a delete-then-write. Two
   `spel --session S` invocations fired together used to start two daemons that
   both wiped the record and both bound the socket: two browsers launched, the
   first one unreachable, and every later command silently answered by the
   second.

   A record naming a dead process is stale: it is removed and the claim
   retried."
  [^String session]
  (let [pid-path (pid-file-path session)
        mine     (my-pid)]
    (loop [attempt 0]
      (let [claimed? (try
                       (Files/writeString pid-path mine
                         (into-array java.nio.file.OpenOption
                           [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
                       true
                       (catch FileAlreadyExistsException _ false)
                       (catch Exception e
                         ;; An unwritable run directory is not a competing
                         ;; owner: serve the session rather than refuse to
                         ;; start at all.
                         (warn "claim-session" e)
                         true))]
        (if claimed?
          nil
          (if-let [owner (live-daemon-pid session)]
            owner
            (if (< (long attempt) 2)
              ;; Stale record: the process it names is gone, so the socket and
              ;; flags it left behind are gone with it.
              (do (cleanup! session)
                  (recur (inc (long attempt))))
              ;; The record keeps reappearing and never names a live process —
              ;; take the name rather than spin on it.
              (do (try (Files/writeString pid-path mine (into-array java.nio.file.OpenOption []))
                       (catch Exception e (warn "claim-session" e)))
                  nil))))))))
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
          (log/info! "trace auto-saved to " out-path " (daemon shutting down)")
          (catch Exception e
            (log/warn! "failed to auto-save trace: " (.getMessage e))))))))

(defn stop-daemon!
  "Stops the daemon server and cleans up browser resources.
   Closes server socket first so new CLI invocations fail fast and start
   a fresh daemon. Only deletes PID/socket files if they still belong to
   THIS process (prevents nuking a replacement daemon's files).

   `reason` is recorded in the session log as `daemon stopping … reason=…`.
   A client that finds the daemon gone reads that line back and tells the
   user WHY it went away instead of a bare 'could not connect'.

   `opts` may carry `:force?` — skip the graceful page/context/browser closes
   and go straight for the driver. Cleanup is time-boxed either way
   (`SPEL_STOP_TIMEOUT`, default 5s): a wedged Chromium must never keep alive a
   daemon that was asked to stop."
  ([] (stop-daemon! "unspecified" {}))
  ([^String reason] (stop-daemon! reason {}))
  ([^String reason {:keys [force?]}]
   ;; First caller wins. A re-entrant call (the shutdown hook this exit just
   ;; triggered) returns immediately instead of deadlocking on System/exit.
   (when (compare-and-set! !stopping false true)
     ;; 0. Cancel idle timers
     (cancel-cdp-idle-shutdown!)
     (cancel-session-idle-shutdown!)
     (let [session (:session @!state)]
       (log/info! "daemon stopping session=" session " reason=" reason
         (when force? " force=true"))
    ;; 1. Close the server socket — reject new connections immediately — then
    ;;    delete the pid/socket files while they are still provably OURS.
    ;;    Dropping them here rather than after a slow browser teardown means a
    ;;    JVM that dies mid-cleanup cannot leave a stale socket for the next
    ;;    client to dial into.
       (when-let [server @!server]
         (try (.close ^ServerSocketChannel server) (catch Exception e (warn "close-server" e)))
         (reset! !server nil))
       (when (owns-pid-file? session)
         (cleanup! session))
    ;; 2. Browser teardown, time-boxed. Everything that can block on a browser
    ;;    that stopped answering lives inside this one bounded task.
       (let [budget  (long (if force? 2000 stop-cleanup-timeout-ms))
             cleanup (future
                       (when-not force?
                      ;; 2a. Save in-flight trace before closing browser resources
                         (save-inflight-trace!)
                      ;; 2b. iOS provider cleanup — idempotent; only session-owned
                      ;;     resources. Same function runs from explicit `close`
                      ;;     and from JVM/native shutdown hooks.
                         (try (stop-ios-backend!) (catch Exception e (warn "stop-ios-backend" e)))
                     ;; 2c. A foreign CDP browser and its pre-existing tabs are
                     ;; user-owned. Close only pages spel created, then tear down
                     ;; the local Playwright driver (which detaches the socket).
                     ;; Locally launched resources retain the normal full teardown.
                         (if (foreign-browser?)
                           (close-spel-owned-pages!)
                           (do
                             (when-let [p (:page @!state)]    (try (core/close-page! p)     (catch Exception e (warn "close-page" e))))
                             (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
                             (when-let [b (:browser @!state)] (try (core/close-browser! b)  (catch Exception e (warn "close-browser" e))))))
                     ;; 2d. Closing Playwright detaches foreign CDP connections and
                     ;; reaps browser processes owned by ordinary launch modes.
                         (when-let [pw (:pw @!state)] (try (core/close! pw) (catch Exception e (warn "close-playwright" e)))))
                    ;; 2e. Clean up temp profile directory if one was created
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
                    ;; 2f. Kill auto-launched browser process if one was started
                       (when-let [auto-info (:auto-launch-info @!state)]
                         (kill-auto-launched-browser! auto-info))
                       :done)]
         (when (= ::timeout (deref cleanup budget ::timeout))
           (future-cancel cleanup)
           (log/warn! "browser cleanup did not finish within " (human-duration budget)
             " — exiting anyway; a stray browser process may survive")))
    ;; 3. Release shared CDP route lock if we own it
       (release-cdp-route-lock-if-owned!)
    ;; 4. Reset state and exit
       (reset! !state {:pw nil :browser nil :context nil :page nil
                       :refs {} :counter 0 :headless true :session session
                       :tracing? false})
       (deliver !stop-finished true)
     ;; Exiting from inside a shutdown hook blocks forever — the JVM is already
     ;; on its way out, so returning is the whole job. Otherwise `halt` ends the
     ;; process NOW: cleanup already ran above, and re-entering the shutdown
     ;; hooks is exactly the deadlock that used to leave zombie daemons behind.
       (when-not @!in-shutdown-hook
         (.halt (Runtime/getRuntime) 0))))))

(defn- serve-session!
  "Binds the session socket and serves connections until shutdown. Only the
   process that claimed `session` may call this: binding the socket is what
   makes a daemon reachable under that name, so it happens exactly once."
  [^String session]
  (let [sock-path (socket-path session)]
    ;; A socket file outlives the process that bound it and bind refuses to
    ;; reuse the path. Deleting it is safe here and only here: the session is
    ;; already claimed, so no live daemon is listening on it.
    (try (Files/deleteIfExists sock-path) (catch Exception e (warn "delete-socket" e)))
    (let [addr   (UnixDomainSocketAddress/of (.toString sock-path))
          server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server addr)
      (reset! !server server)
      (log/info! "listening on " (.toString sock-path))

      ;; Signal handlers
      (let [shutdown-hook (Thread. ^Runnable (fn []
                                               (reset! !in-shutdown-hook true)
                                               ;; A stop already in flight runs
                                               ;; on a virtual (daemon) thread:
                                               ;; wait for it instead of racing
                                               ;; the JVM's exit past its
                                               ;; cleanup.
                                               (if @!stopping
                                                 (deref !stop-finished
                                                   (+ (long stop-cleanup-timeout-ms) 2000) nil)
                                                 (stop-daemon! "process signal — JVM shutdown hook"))))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook))

      ;; Start session idle timer
      (schedule-session-idle-shutdown!)

      ;; Accept connections. A transient accept failure (EMFILE, interrupt)
      ;; must NOT end the listener: the process would stay alive holding a
      ;; valid PID file while every client connect fails — the classic
      ;; "daemon is dying" symptom. Only an intentional close stops the loop.
      (loop []
        (let [client (try
                       (.accept server)
                       (catch Throwable e
                         (if (or (nil? @!server) (not (.isOpen server)))
                           ::closed
                           (do (log/warn! "accept failed: " (.getMessage e)
                                 " — still listening")
                               (Thread/sleep 50)
                               ::retry))))]
          (cond
            (identical? ::closed client) nil
            (identical? ::retry client)  (recur)
            :else (do (submit-virtual #(handle-connection client))
                      (recur))))))))

(defn start-daemon!
  "Starts the daemon server. Blocks until shutdown.

   One session NAME is served by one daemon. The PID file is the ownership
   record and `claim-session!` picks the winner, so a start that loses the race
   exits without launching a browser instead of stealing the socket from the
   daemon already serving that name.

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
        cdp-url  (get opts :cdp)]
    ;; One log system: the daemon logs through the same sink the CLI writes to
    ;; and `spel logs` reads. Mirroring to stderr is off — the daemon's stdout
    ;; and stderr are already redirected into that same file by the CLI, so a
    ;; mirror would duplicate every line.
    (log/init! {:session session :component "daemon" :mirror :off})
    (log/info! "daemon starting session=" session
      " pid=" (.pid (java.lang.ProcessHandle/current))
      " headless=" headless
      (when browser (str " browser=" browser))
      (when cdp-url (str " cdp=" cdp-url)))

    (if-let [owner (claim-session! session)]
      ;; Losing the race is a normal outcome, not a failure: the CLI that
      ;; spawned this process finds the winner's socket and talks to the one
      ;; browser. Starting a second one is what made spel look like it opened
      ;; the browser twice.
      (log/info! "session=" session " already served by pid " owner
        " — this daemon exits without starting a browser")
      (do
        ;; The daemon's clock starts HERE, in the process that serves the
        ;; session, so uptime is this daemon's age and not the native image's.
        (reset! !daemon-started-at (System/currentTimeMillis))
        ;; Store session config + initial launch flags (browser type from CLI args)
        (swap! !state assoc :headless headless :session session)
        (when browser
          (swap! !state assoc-in [:launch-flags "browser"] browser))
        (when cdp-url
          (swap! !state assoc-in [:launch-flags "cdp"] cdp-url))

        ;; Persist launch flags so CLI can recover them (e.g. --cdp) on subsequent commands
        (persist-launch-flags!)

        (serve-session! session)))))
