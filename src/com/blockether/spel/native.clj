(ns com.blockether.spel.native
  "Native-image entry point for spel.

   Provides a CLI tool (spel) for browser automation with persistent browser sessions.

   Modes:
   - CLI commands (default): Send commands to the browser process
   - Eval: Evaluate a Clojure expression and exit

   Usage:
     spel open https://example.org   # Navigate (auto-starts browser)
     spel snapshot                    # ARIA snapshot with refs
     spel click @ref                  # Click by ref
     spel fill @ref \"search text\" # Fill input by ref
     spel screenshot shot.png         # Take screenshot
     spel close                       # Close browser
     spel eval-sci '(+ 1 2)'            # Evaluate and exit
     spel install                     # Install Playwright browsers
     spel --help                      # Show help"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure-reporter :as allure-reporter]
   [com.blockether.spel.spel-allure-alternative-html-report :as alternative-report]
   [com.blockether.spel.ci :as ci]
   [com.blockether.spel.cli :as cli]
   [com.blockether.spel.codegen :as codegen]
   [com.blockether.spel.daemon :as daemon]
   [com.blockether.spel.driver :as driver]
   [com.blockether.spel.init-agents :as init-agents]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.search :as search]
   [com.blockether.spel.stitch :as stitch])
  (:gen-class))

;; =============================================================================
;; Stderr Helpers
;; =============================================================================

(defn- eprint
  "Write to stderr and flush immediately."
  [& args]
  (let [^java.io.PrintWriter w *err*]
    (.print w ^String (apply str args))
    (.flush w)))

(defn- eprintln
  "Write line to stderr and flush immediately."
  [& args]
  (let [^java.io.PrintWriter w *err*]
    (.println w ^String (apply str args))
    (.flush w)))

;; =============================================================================
;; Driver Setup
;; =============================================================================

;; The Playwright Node.js driver (~120MB per platform, ~600MB for all platforms)
;; is NOT bundled in the native binary. Instead, it is downloaded from
;; Playwright's CDN on first use and cached at ~/.cache/spel/<version>/.
;;
;; This is handled by driver/ensure-driver! which sets the playwright.cli.dir
;; system property so Playwright Java uses PreinstalledDriver instead of
;; trying to extract from bundled resources (DriverJar).

;; =============================================================================
;; Help & Version
;; =============================================================================

(defn- print-help []
  (println "spel — Native browser automation CLI")
  (println "")
  (println "Core Commands:")
  (println "  open <url>                Navigate (aliases: goto, navigate)")
  (println "  open <url> --interactive  Navigate with visible browser")
  (println "  click <sel>               Click element by ref or selector")
  (println "  dblclick <sel>            Double-click element")
  (println "  fill <sel> \"text\"        Clear and fill input")
  (println "  type <sel> \"text\"        Type without clearing")
  (println "  press <key>               Press key (Enter, Tab, Control+a) (alias: key)")
  (println "  keydown <key>             Hold key down")
  (println "  keyup <key>               Release key")
  (println "  hover <sel>               Hover element")
  (println "  select <sel> <val>        Select dropdown option")
  (println "  check / uncheck <sel>     Toggle checkbox")
  (println "  focus <sel>               Focus element")
  (println "  clear <sel>               Clear input")
  (println "  scroll <dir> [px] [sel]   Scroll page or element (-S for smooth)")
  (println "  scrollintoview <sel>      Scroll element into view")
  (println "  drag <src> <tgt>          Drag and drop")
  (println "  upload <sel> <files...>   Upload files")
  (println "  close                     Close browser (aliases: quit, exit)")
  (println "")
  (println "Snapshot & Screenshot:")
  (println "  snapshot                  Full accessibility tree with refs")
  (println "  snapshot -i               Interactive elements only")
  (println "  snapshot -i -c -d 5       Compact, depth-limited")
  (println "  snapshot -i -C            Interactive + cursor elements")
  (println "  snapshot -S               Include computed CSS styles")
  (println "  snapshot -S --minimal     Styles: 16 essential properties")
  (println "  snapshot -S --max         Styles: all 44 tracked properties")
  (println "  snapshot -s \"#main\"       Scoped to selector")
  (println "  screenshot [path]         Take screenshot (-f full, -a annotated)")
  (println "  batch [--bail] [--json]   Run a JSON array of commands from stdin")
  (println "  stitch <imgs...>          Stitch screenshots vertically (-o, --overlap)")
  (println "  annotate                  Show annotation overlays (visible elements)")
  (println "    -s, --scope <sel|@ref>  Scope annotations to a subtree")
  (println "    --no-badges             Hide ref labels")
  (println "    --no-dimensions         Hide size labels")
  (println "    --no-boxes              Hide bounding boxes")
  (println "  unannotate                Remove annotation overlays")
  (println "  pdf <path>                Save as PDF")
  (println "  markdownify               Convert page or HTML input to Markdown")
  (println "")
  (println "Get Info:")
  (println "  get text <sel>            Get text content")
  (println "  get html <sel>            Get innerHTML")
  (println "  get value <sel>           Get input value")
  (println "  get attr <sel> <name>     Get attribute value")
  (println "  get url / get title       Get page URL or title")
  (println "  get count <sel>           Count matching elements")
  (println "  get box <sel>             Get bounding box")
  (println "")
  (println "  styles <sel> [--full]     Get computed CSS styles")
  (println "  clipboard copy <text>     Write text to clipboard")
  (println "  clipboard read            Read clipboard text")
  (println "  clipboard paste           Paste clipboard into focused element")
  (println "  diff snapshot --baseline <file>  Diff current vs baseline snapshot")
  (println "")
  (println "Check State:")
  (println "  is visible/enabled/checked <sel>")
  (println "")
  (println "Find (Semantic Locators):")
  (println "  find role <role> <action>             By ARIA role")
  (println "  find text <text> <action>             By text content")
  (println "  find label <text> <action> [value]    By label")
  (println "  find role button click --name Submit  With name filter")
  (println "  find first/last/nth <sel> <action>    Position-based")
  (println "")
  (println "Wait:")
  (println "  wait <sel>                Wait for element visible")
  (println "  wait <ms>                 Wait for timeout")
  (println "  wait --text \"Welcome\"     Wait for text to appear")
  (println "  wait --url \"**/dash\"      Wait for URL pattern")
  (println "  wait --load networkidle   Wait for load state")
  (println "  wait --fn \"window.ready\"  Wait for JS condition")
  (println "")
  (println "Mouse Control:")
  (println "  mouse move <x> <y>        Move mouse")
  (println "  mouse down/up [button]    Press/release button")
  (println "  mouse wheel <dy> [dx]     Scroll wheel")
  (println "")
  (println "Browser Settings:")
  (println "  set viewport <w> <h>      Set viewport size")
  (println "  set device <name>         Emulate device (iphone 14, pixel 7, etc.)")
  (println "  set geo <lat> <lng>       Set geolocation")
  (println "  set offline [on|off]      Toggle offline mode")
  (println "  set headers <json>        Extra HTTP headers")
  (println "  set credentials <u> <p>   HTTP basic auth")
  (println "  set media dark|light      Emulate color scheme")
  (println "")
  (println "Cookies & Storage:")
  (println "  cookies                   Get all cookies")
  (println "  cookies set <name> <val>  Set cookie")
  (println "  cookies clear             Clear cookies")
  (println "  storage local [key]       Get localStorage")
  (println "  storage local set <k> <v> Set localStorage")
  (println "  storage local clear       Clear localStorage")
  (println "  storage session [key]     Same for sessionStorage")
  (println "")
  (println "Network:")
  (println "  network route <url>              Intercept requests")
  (println "  network route <url> --abort      Block requests")
  (println "  network route <url> --body <json> Mock response")
  (println "  network unroute [url]            Remove routes")
  (println "  network requests [flags]         View requests")
  (println "    --filter <regex>                 Filter by URL regex")
  (println "    --type <type>                    Filter by type (document, script, fetch, ...)")
  (println "    --method <method>                Filter by method (GET, POST, ...)")
  (println "    --status <prefix>                Filter by status (2, 30, 404, ...)")
  (println "  network clear                    Clear tracked requests")
  (println "")
  (println "Tabs & Windows:")
  (println "  tab                       List tabs")
  (println "  tab new [url]             New tab")
  (println "  tab <n>                   Switch to tab")
  (println "  tab close                 Close tab")
  (println "")
  (println "Frames & Dialogs:")
  (println "  frame <sel>               Switch to iframe")
  (println "  frame main                Back to main frame")
  (println "  frame list                List all frames")
  (println "  dialog accept [text]      Accept dialog")
  (println "  dialog dismiss            Dismiss dialog")
  (println "")
  (println "Debug:")
  (println "  eval-js <js>              Run JavaScript")
  (println "  eval-js <js> -b           Run JavaScript, base64-encode result")
  (println "  connect <url>             Connect to browser via CDP")
  (println "  cdp disconnect|reconnect  Temporarily detach/reattach CDP")
  (println "  find-free-port            Print an available local TCP port")
  (println "  trace start / trace stop  Record trace")
  (println "  console / console clear   View/clear console (auto-captured)")
  (println "  errors / errors clear     View/clear errors (auto-captured)")
  (println "  highlight <sel>           Highlight element")
  (println "  inspector [url]           Launch Playwright Inspector (headed browser)")
  (println "  show-trace [trace]        Open Playwright Trace Viewer")
  (println "")
  (println "State Management:")
  (println "  state save [path]         Save auth/storage state")
  (println "  state load [path]         Load saved state")
  (println "  state list                List state files")
  (println "  state show <file>         Show state file contents")
  (println "  state rename <old> <new>  Rename state file")
  (println "  state clear [--all]       Clear state files")
  (println "  state clean [--older-than N]  Remove states older than N days")
  (println "")
  (println "Navigation:")
  (println "  back / forward / reload   History navigation")
  (println "")
  (println "Sessions:")
  (println "  --session <name>          Use named session")
  (println "  session                   Show current session info")
  (println "  session list              List active sessions")
  (println "")
  (println "Options:")
  (println "  --session <name>          Named session (default: \"default\")")
  (println "  --no-persist              Disable auto-persist of cookies/storage")
  (println "  --json                    JSON output (for agents)")
  (println "  --load-state <path>       Load state (cookies/localStorage JSON, alias: --storage-state)")
  (println "  --profile <path>          Chrome user data directory (persistent profile)")
  (println "  --browser <engine>        Browser engine: chromium, firefox, webkit")
  (println "  --executable-path <path>  Custom browser executable")
  (println "  --user-agent <ua>         Custom user agent string")
  (println "  --proxy <url>             Proxy server URL")
  (println "  --proxy-bypass <domains>  Proxy bypass domains")
  (println "  --headers <json>          HTTP headers")
  (println "  --args <args>             Browser args (comma-separated)")
  (println "  --cdp <url>               Connect via CDP endpoint")
  (println "  --auto-connect            Auto-discover running chromium-family browser CDP endpoint")
  (println "                            (Chrome/Edge/Brave/Vivaldi/Opera/Arc/Thorium/Chromium)")
  (println "                            Chrome/Edge 136+ requires --user-data-dir for debug port")
  (println "                            See: chrome://inspect/#remote-debugging (M144+)")
  (println "  --auto-launch             Launch browser with debug port (per-session isolation)")
  (println "                            Each session gets its own browser on a unique port (9222+)")
  (println "                            Uses temp profile; user's existing browser stays untouched")
  (println "  --ignore-https-errors     Ignore HTTPS errors")
  (println "  --allow-file-access       Allow file:// access")
  (println "  --no-stealth              Disable stealth mode (stealth is ON by default)")
  (println "  --timeout <ms>            Playwright action timeout in ms (default: 30000)")
  (println "  --debug                   Debug output")
  (println "  --help, -h                Show this help")
  (println "")
  (println "Dashboard:")
  (println "  dashboard start [port]    Start observability dashboard (default: 4848)")
  (println "  dashboard stop            Stop the dashboard")
  (println "  dashboard status          Check dashboard status")
  (println "")
  (println "Tools:")

  (println "  search <query> [opts]     Google search from the CLI (--help for details)")
  (println "  markdownify [opts]        Convert current page, URL, file, or input HTML to Markdown")
  (println "  init-agents [opts]        Scaffold E2E testing agents (--help for details)")
  (println "  codegen record [url]      Record browser session (interactive Playwright Codegen)")
  (println "  codegen [opts] [file]     Transform JSONL recording to Clojure code (--help for details)")
  (println "  ci-assemble [opts]        Assemble Allure site for CI deployment (--help for details)")
  (println "  merge-reports [dirs]      Merge N allure-results dirs into one (--help for details)")
  (println "  report [opts]             Generate Blockether-themed HTML report from allure-results (--help for details)")
  (println "")
  (println "Utility:")
  (println "  install [--with-deps]     Install Playwright browsers")
  (println "  version                   Show version")
  (println "")
  (println "Modes:")
  (println "  eval-sci '<code>'          Evaluate Clojure expression")
  (println "  eval-sci <file.clj>        Evaluate Clojure file (e.g. codegen script)")
  (println "  eval-sci --interactive     Evaluate with visible browser (headed mode)")
  (println "  eval-sci --load-state F    Load auth/state before evaluation (alias: --storage-state)")
  (println "")
  (println "Examples:")
  (println "  spel open example.org")
  (println "  spel snapshot -i --json")
  (println "  spel click @ref")
  (println "  spel find role button click --name Submit")
  (println "  spel wait --text \"Welcome\"")
  (println "  spel set viewport 1280 720")
  (println "  spel cookies")
  (println "  spel --session agent1 open site.com")
  (println "  spel find-free-port")
  (println "  spel eval-sci script.clj --load-state auth.json"))

(defn- spel-version
  "Reads the version string from the SPEL_VERSION resource file.
   This file is the single source of truth for the spel version."
  []
  (-> (clojure.java.io/resource "SPEL_VERSION")
    slurp
    str/trim))

(defn- print-version []
  (println (str "spel " (spel-version))))

;; =============================================================================
;; Self-upgrade (`spel upgrade [--check]`)
;; =============================================================================

(def ^:private upgrade-release-api
  "https://api.github.com/repos/Blockether/spel/releases/latest")

(defn- os-tag
  "Returns the release asset tag for the current host (macos-arm64,
   macos-amd64, linux-amd64, linux-arm64, windows-amd64). Matches the naming
   used by spel's GitHub Release assets."
  []
  (let [os   (str/lower-case (or (System/getProperty "os.name") ""))
        arch (str/lower-case (or (System/getProperty "os.arch") ""))
        os-key (cond
                 (str/includes? os "mac")   "macos"
                 (str/includes? os "win")   "windows"
                 :else                       "linux")
        arch-key (cond
                   (or (= arch "aarch64") (= arch "arm64")) "arm64"
                   :else                                     "amd64")]
    (str os-key "-" arch-key)))

(defn- fetch-latest-release
  "Hits the GitHub Releases API and returns a map with :tag (e.g. \"v0.7.11\"),
   :version (\"0.7.11\" — leading `v` stripped), and :assets (vector of
   {:name :url}). Returns nil on network failure so callers can degrade
   gracefully."
  []
  (try
    (let [conn (doto ^java.net.HttpURLConnection
                (.openConnection ^java.net.URL (java.net.URL. upgrade-release-api))
                 (.setRequestMethod "GET")
                 (.setRequestProperty "Accept" "application/vnd.github+json")
                 (.setRequestProperty "User-Agent" "spel-upgrade")
                 (.setConnectTimeout 5000)
                 (.setReadTimeout 10000))
          body (with-open [is (.getInputStream conn)] (slurp is))
          raw  (charred.api/read-json body)
          tag  (get raw "tag_name")
          assets (->> (get raw "assets" [])
                   (mapv (fn [a]
                           {:name (get a "name")
                            :url  (get a "browser_download_url")})))]
      (when tag
        {:tag     tag
         :version (if (str/starts-with? tag "v") (subs tag 1) tag)
         :assets  assets}))
    (catch Exception _ nil)))

(defn- compare-versions
  "Naive semver comparison on dot-separated numeric strings. Returns 1 if `a`
   is newer than `b`, -1 if older, 0 if equal. Non-numeric segments compare
   lexically. Suffixes like `-rc1` are ignored."
  [a b]
  (let [parse (fn [s]
                (->> (str/split (first (str/split (str s) #"-")) #"\.")
                  (mapv #(try (Integer/parseInt %) (catch Exception _ 0)))))
        av    (parse a)
        bv    (parse b)
        len   (max (count av) (count bv))
        pad   (fn [v] (into v (repeat (- len (count v)) 0)))]
    (compare (pad av) (pad bv))))

(defn- detect-install-method
  "Inspects the current binary path and returns one of :brew :cargo :local
   :unknown. Used to pick the right `exec`-style upgrade command."
  []
  (let [path (or (System/getenv "SPEL_BINARY_PATH")
               (when-let [pwd (System/getenv "_")]
                 pwd)
               "spel")]
    (cond
      (or (str/includes? path "/Cellar/")
        (str/includes? path "/opt/homebrew/")
        (str/includes? path "/usr/local/Cellar/"))
      :brew

      (str/includes? path ".cargo/bin")
      :cargo

      (str/includes? path ".local/bin")
      :local

      :else :unknown)))

(defn handle-upgrade!
  "Implements `spel upgrade [--check]`. Prints the current version and the
   latest GitHub release version. With `--check`, exits without making
   changes. Without `--check`, tells the user which command to run to
   upgrade based on the detected install method (brew/cargo/manual). A fully
   automatic in-place overwrite is intentionally out of scope — replacing a
   running native binary safely across macOS/Linux/Windows deserves its own
   hardening pass."
  [args]
  (let [check-only? (some #{"--check"} args)
        current     (spel-version)
        latest      (fetch-latest-release)]
    (cond
      (nil? latest)
      (do (println (str "spel " current))
          (println "spel: unable to reach GitHub Releases API (network error). Try again later.")
          (System/exit 1))

      (zero? (long (compare-versions current (:version latest))))
      (do (println (str "spel " current " — up to date."))
          (System/exit 0))

      (pos? (long (compare-versions current (:version latest))))
      (do (println (str "spel " current " is ahead of the latest release (" (:version latest) ")."))
          (System/exit 0))

      :else
      (let [method (detect-install-method)
            os-key (os-tag)
            asset  (some #(when (str/includes? (str (:name %)) os-key) %) (:assets latest))]
        (println (str "spel " current " → " (:version latest) " available"))
        (when asset
          (println (str "  asset: " (:name asset))))
        (if check-only?
          (do (println "Run `spel upgrade` to install.")
              (System/exit 0))
          (do (case method
                :brew    (println "Upgrade via Homebrew:\n  brew upgrade spel")
                :cargo   (println "Upgrade via Cargo:\n  cargo install --force spel")
                :local   (do (println "Upgrade manually (local install):")
                             (when asset
                               (println (str "  curl -L -o ~/.local/bin/spel " (:url asset)))
                               (println "  chmod +x ~/.local/bin/spel")))
                :unknown (do (println "Upgrade manually:")
                             (when asset
                               (println (str "  " (:url asset))))))
              (System/exit 0)))))))

(defn- driver-cli-path
  "Returns the path to the Playwright Node.js CLI entry point.
   The driver must already be downloaded via driver/ensure-driver!."
  ^java.nio.file.Path []
  (let [cli-dir (System/getProperty "playwright.cli.dir")]
    (when-not cli-dir
      (throw (ex-info "Playwright driver not initialized. Call driver/ensure-driver! first." {})))
    (let [dir (java.nio.file.Paths/get cli-dir (into-array String []))]
      (.resolve dir (java.nio.file.Paths/get "package" (into-array String ["cli.js"]))))))

(defn- driver-node-path
  "Returns the path to the Node.js binary bundled with the Playwright driver."
  ^java.nio.file.Path []
  (let [cli-dir (System/getProperty "playwright.cli.dir")
        dir     (java.nio.file.Paths/get cli-dir (into-array String []))
        node    (if (clojure.string/includes? (System/getProperty "os.name" "") "Windows")
                  "node.exe" "node")]
    (.resolve dir ^String node)))

(defn- needs-display?
  "Returns true if the Playwright command requires a display (X11/Wayland).
   Commands like codegen, open (inspector), and show-trace open headed browsers."
  [cmd-args]
  (let [first-cmd (first cmd-args)]
    (boolean (#{"codegen" "open" "show-trace"} first-cmd))))

(defn- has-display?
  "Returns true if a display server (X11 or Wayland) is available."
  []
  (or (not (str/blank? (System/getenv "DISPLAY")))
    (not (str/blank? (System/getenv "WAYLAND_DISPLAY")))))

(defn- xvfb-run-available?
  "Returns true if xvfb-run is available on the system."
  []
  (try
    (let [pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" "xvfb-run"]))
          proc (.start pb)
          exit (.waitFor proc)]
      (zero? exit))
    (catch Exception _ false)))

(defn- linux?
  "Returns true if running on Linux."
  []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "linux"))

(defn- run-playwright-cmd!
  "Runs a Playwright CLI command as a subprocess via the Node.js driver.
   Inherits stdout/stderr so users see output. Returns the exit code.
   Calls System/exit on non-zero exit.

   On headless Linux systems (no DISPLAY/WAYLAND_DISPLAY), automatically wraps
   display-requiring commands (codegen, open, show-trace) with xvfb-run if available."
  [cmd-args]
  (let [node (str (driver-node-path))
        cli  (str (driver-cli-path))
        base-args (into [node cli] cmd-args)
        use-xvfb? (and (linux?)
                    (needs-display? cmd-args)
                    (not (has-display?))
                    (xvfb-run-available?))
        args (if use-xvfb?
               (into ["xvfb-run" "--auto-servernum" "--server-args=-screen 0 1280x960x24"] base-args)
               base-args)]
    (when use-xvfb?
      (println "No display detected — using xvfb-run for virtual display.")
      (flush))
    (when (and (linux?)
            (needs-display? cmd-args)
            (not (has-display?))
            (not (xvfb-run-available?)))
      (eprintln "Warning: No display server detected and xvfb-run is not installed.")
      (eprintln "This command requires a display (X11/Wayland).")
      (eprintln "")
      (eprintln "To fix, install Xvfb:")
      (eprintln "  sudo apt-get install -y xvfb    # Debian/Ubuntu")
      (eprintln "  sudo dnf install -y xorg-x11-server-Xvfb  # Fedora/RHEL")
      (eprintln "")
      (eprintln "Or set DISPLAY if you have a remote display:")
      (eprintln "  export DISPLAY=:0"))
    (let [pb (doto (ProcessBuilder. ^java.util.List args)
               (.inheritIO))
          proc (.start pb)
          exit (.waitFor proc)]
      (when-not (zero? exit)
        (System/exit exit)))))

(defn- run-install!
  "Installs Playwright browsers by running the driver CLI as a subprocess.
   Inherits stdout/stderr so users see progress. Forwards extra args like --with-deps."
  [extra-args]
  (println (str "Installing Playwright browsers"
             (when (seq extra-args) (str " (" (clojure.string/join " " extra-args) ")"))
             "..."))
  (flush)
  (run-playwright-cmd! (into ["install"] extra-args))
  (println "Done.")
  (flush))

;; =============================================================================
;; Argument Normalization — typographic dash fix
;; =============================================================================

(defn- normalize-arg
  "Normalizes a single CLI argument by replacing typographic dashes.

   When users copy-paste commands from rendered Markdown, Slack, or word
   processors, double-hyphens (--) are often replaced with em-dashes (\u2014)
   or en-dashes (\u2013). This makes flags like --session silently fail.

   Handles three cases:
   - \u2014session  → --session  (em-dash replaced both hyphens)
   - \u2013-session → --session  (en-dash replaced first hyphen only)
   - \u2013session  → --session  (en-dash replaced both hyphens)"
  [^String arg]
  (cond
    ;; \u2014session → --session (most common: -- becomes em-dash)
    (str/starts-with? arg "\u2014")
    (str "--" (subs arg 1))

    ;; \u2013-session → --session (en-dash replaced first hyphen only)
    ;; Must check before plain \u2013 — more specific prefix match.
    (str/starts-with? arg "\u2013-")
    (str "--" (subs arg 2))

    ;; \u2013session → --session (en-dash replaced both hyphens)
    (str/starts-with? arg "\u2013")
    (str "--" (subs arg 1))

    :else arg))

(defn normalize-args
  "Normalizes all CLI arguments by replacing typographic dashes.
   See `normalize-arg` for details."
  [args]
  (mapv normalize-arg args))

;; =============================================================================
;; Daemon Argument Parsing
;; =============================================================================

(defn- parse-daemon-args
  "Parses daemon-specific arguments from argv.

   Supports:
     --session <name>   Session name (default \"default\")
     --headed           Run browser in headed mode (internal, set by --interactive)
     --headless         Run browser headless (default)
     --browser <type>   Browser engine: chromium, firefox, webkit (default: chromium)
     --cdp <url>        Connect via Chrome DevTools Protocol"
  [args]
  (loop [remaining (normalize-args args)
         opts {:session "default" :headless true}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= "--session" arg)
          (recur (drop 2 remaining) (assoc opts :session (second remaining)))

          (str/starts-with? arg "--session=")
          (recur (rest remaining) (assoc opts :session (subs arg 10)))

          (= "--headed" arg)
          (recur (rest remaining) (assoc opts :headless false))

          (= "--headless" arg)
          (recur (rest remaining) (assoc opts :headless true))

          (= "--browser" arg)
          (recur (drop 2 remaining) (assoc opts :browser (second remaining)))

          (str/starts-with? arg "--browser=")
          (recur (rest remaining) (assoc opts :browser (subs arg 10)))

          (= "--cdp" arg)
          (recur (drop 2 remaining) (assoc opts :cdp (second remaining)))

          (str/starts-with? arg "--cdp=")
          (recur (rest remaining) (assoc opts :cdp (subs arg 6)))

          :else
          (recur (rest remaining) opts))))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn- parse-global-flags
  "Pre-parses global flags from args.
   Returns {:timeout-ms long?, :debug? bool, :json? bool, :autoclose? bool,
            :interactive? bool, :session str?, :browser str?, :command-args vec}.
   :command-args has global flags stripped for dispatch (finding the command).
   Original args are preserved for modes that do their own parsing (CLI daemon)."
  [args]
  (loop [remaining (normalize-args args)
         cmd-args  []
         opts      {:timeout-ms nil :debug? false :json? false :autoclose? false :interactive? false :session nil :load-state nil :auto-connect (some? (System/getenv "SPEL_AUTO_CONNECT")) :auto-launch (some? (System/getenv "SPEL_AUTO_LAUNCH")) :browser (System/getenv "SPEL_BROWSER") :channel (System/getenv "SPEL_CHANNEL") :profile (System/getenv "SPEL_PROFILE") :cdp (System/getenv "SPEL_CDP")}]
    (if-not (seq remaining)
      (assoc opts :command-args cmd-args)
      (let [arg (first remaining)]
        (cond
          ;; --timeout=<ms>
          (and (string? arg) (str/starts-with? arg "--timeout="))
          (let [ms (parse-long (subs arg 10))]
            (recur (rest remaining) cmd-args
              (cond-> opts ms (assoc :timeout-ms ms))))

          ;; --timeout <ms>
          (= "--timeout" arg)
          (let [ms (some-> (second remaining) parse-long)]
            (if ms
              (recur (drop 2 remaining) cmd-args (assoc opts :timeout-ms ms))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          ;; --debug
          (= "--debug" arg)
          (recur (rest remaining) cmd-args (assoc opts :debug? true))

          ;; --json
          (= "--json" arg)
          (recur (rest remaining) cmd-args (assoc opts :json? true))

          ;; --autoclose
          (= "--autoclose" arg)
          (recur (rest remaining) cmd-args (assoc opts :autoclose? true))

          ;; --interactive / --headed (visible browser window)
          (or (= "--interactive" arg) (= "--headed" arg))
          (recur (rest remaining) cmd-args (assoc opts :interactive? true))

          ;; --browser=<type>
          (and (string? arg) (str/starts-with? arg "--browser="))
          (recur (rest remaining) cmd-args
            (assoc opts :browser (subs arg 10)))

          ;; --browser <type>
          (= "--browser" arg)
          (let [val (second remaining)]
            (if val
              (recur (drop 2 remaining) cmd-args (assoc opts :browser val))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          ;; --session=<name>
          (and (string? arg) (str/starts-with? arg "--session="))
          (recur (rest remaining) cmd-args
            (assoc opts :session (subs arg 10)))

          ;; --session <name>
          (= "--session" arg)
          (let [val (second remaining)]
            (if val
              (recur (drop 2 remaining) cmd-args (assoc opts :session val))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          ;; --load-state=<path> (alias: --storage-state)
          (and (string? arg) (or (str/starts-with? arg "--load-state=")
                               (str/starts-with? arg "--storage-state=")))
          (let [eq-idx (long (.indexOf ^String arg "="))]
            (recur (rest remaining) cmd-args
              (assoc opts :load-state (subs arg (inc eq-idx)))))

          ;; --load-state <path> (alias: --storage-state)
          (or (= "--load-state" arg) (= "--storage-state" arg))
          (let [path (second remaining)]
            (if path
              (recur (drop 2 remaining) cmd-args (assoc opts :load-state path))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          ;; --profile=<path>
          (and (string? arg) (str/starts-with? arg "--profile="))
          (recur (rest remaining) cmd-args
            (assoc opts :profile (subs arg 10)))

          ;; --profile <path>
          (= "--profile" arg)
          (let [val (second remaining)]
            (if val
              (recur (drop 2 remaining) cmd-args (assoc opts :profile val))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          ;; --cdp=<url>
          (and (string? arg) (str/starts-with? arg "--cdp="))
          (recur (rest remaining) cmd-args
            (assoc opts :cdp (subs arg 6)))

          ;; --cdp <url>
          (= "--cdp" arg)
          (let [val (second remaining)]
            (if val
              (recur (drop 2 remaining) cmd-args (assoc opts :cdp val))
              (recur (rest remaining) (conj cmd-args arg) opts)))

;; --auto-connect
          (= "--auto-connect" arg)
          (recur (rest remaining) cmd-args (assoc opts :auto-connect true))

          ;; --auto-launch
          (= "--auto-launch" arg)
          (recur (rest remaining) cmd-args (assoc opts :auto-launch true))

          ;; --channel=<name>
          (and (string? arg) (str/starts-with? arg "--channel="))
          (recur (rest remaining) cmd-args
            (assoc opts :channel (subs arg 10)))

          ;; --channel <name>
          (= "--channel" arg)
          (let [val (second remaining)]
            (if val
              (recur (drop 2 remaining) cmd-args (assoc opts :channel val))
              (recur (rest remaining) (conj cmd-args arg) opts)))

          :else
          (recur (rest remaining) (conj cmd-args arg) opts))))))

(defn- run-eval!
  "Runs eval-sci mode via daemon: sends code for evaluation, browser persists.
   The daemon lazily starts a browser on first Playwright call and keeps it
   alive between invocations. Use --autoclose to shut the daemon after eval.
   When --load-state is set, loads browser storage state (cookies/localStorage)
   from a JSON file before evaluating the code."
  [code eval-args global]
  (driver/ensure-driver!)
  (let [session   (or (:session global) "default")
        ;; Recover persisted launch flags (e.g. --cdp) so user doesn't repeat them.
        persisted (daemon/read-session-flags session)
        global    (cond-> global
                    (and (get persisted "cdp") (not (:cdp global)))
                    (assoc :cdp (get persisted "cdp"))
                    (and (get persisted "browser") (not (:browser global)))
                    (assoc :browser (get persisted "browser")))
        ;; Auto-connect: discover running Chrome if no explicit --cdp
        global    (if (and (:auto-connect global) (not (:cdp global)))
                    (assoc global :cdp (daemon/discover-cdp-endpoint))
                    global)
        ;; Bootstrap timeout: 30s for single-action setup commands (state_load, nil eval).
        ;; These are short operations that should never take long.
        boot-timeout 30000
        ;; Eval timeout: 4x the per-action timeout, floor 120s.
        ;; Each Playwright action has its own timeout (--timeout flag, default 30s).
        ;; The transport needs headroom for multi-action scripts but should fail
        ;; fast if something truly hangs — not block forever.
        action-timeout (or (:timeout-ms global) 30000)
        eval-timeout   (max 120000 (* 4 (long action-timeout)))
        ;; Build _flags for daemon (same pattern as CLI mode in cli.clj).
        ;; The daemon uses these to configure browser type, profile, etc.
        eval-flags   (cond-> {}
                       (:browser global) (assoc "browser" (:browser global))
                       (:channel global) (assoc "channel" (:channel global))
                       (:profile global) (assoc "profile" (:profile global))
                       (:cdp global)     (assoc "cdp" (:cdp global))
                       (:auto-launch global) (assoc "auto-launch" true))
        exit-code (volatile! 0)]
    (try
      ;; Ensure daemon is running (same as CLI mode)
      ;; --interactive launches headed (visible) browser for eval mode
      ;; --browser passes browser type (chromium/firefox/webkit) to daemon
      (cli/ensure-daemon! session (cond-> {:headless (not (:interactive? global))}
                                    (:browser global) (assoc :browser (:browser global))
                                    (:cdp global) (assoc :cdp (:cdp global))))
      ;; Set timeout on daemon side if provided
      (when (:timeout-ms global)
        (sci-env/set-default-timeout! (:timeout-ms global)))
      ;; Load state if --load-state specified
      (when-let [state-path (:load-state global)]
        ;; Bootstrap browser first (sci_eval triggers ensure-browser!)
        (cli/send-command! session
          (cond-> {"action" "sci_eval" "code" "nil"}
            (seq eval-flags) (assoc "_flags" eval-flags))
          boot-timeout)
        ;; Load state into context (replaces context with saved cookies/storage)
        (let [resp (cli/send-command! session
                     {"action" "state_load" "path" state-path}
                     boot-timeout)]
          (when-not (:success resp)
            (eprintln (str "Warning: failed to load state from " state-path ": "
                        (or (get-in resp [:data :error]) (:error resp)))))))
      ;; Send eval command to daemon — no transport timeout.
      ;; Playwright action timeouts are the correct control mechanism.
      ;; Include _flags so daemon knows browser type on first command.
      (let [response     (cli/send-command! session
                           (cond-> {"action" "sci_eval" "code" code}
                             (some? eval-args) (assoc "args" eval-args)
                             (seq eval-flags) (assoc "_flags" eval-flags))
                           eval-timeout)
            stdout-str   (get-in response [:data :stdout])
            stderr-str   (get-in response [:data :stderr])
            console-msgs (get-in response [:data :console])
            page-errors  (get-in response [:data :page-errors])]
        ;; Print captured stdout/stderr (from println/binding *err* in evaluated code)
        (when (and stdout-str (not (str/blank? stdout-str)))
          (print stdout-str)
          (flush))
        (when (and stderr-str (not (str/blank? stderr-str)))
          (eprint stderr-str))
        ;; Print browser console messages and page errors to stderr
        (when (and (seq console-msgs) (not (:json? global)))
          (doseq [{:keys [type text]} console-msgs]
            (eprintln (str "[console." type "] " text))))
        (when (and (seq page-errors) (not (:json? global)))
          (doseq [{:keys [message]} page-errors]
            (eprintln (str "[page-error] " message))))
        (if (and response (:success response))
          ;; Success — print the result
          (let [data       (get response :data)
                snap-tree  (get data :snapshot)
                result-str (get data :result)]
            (if (:json? global)
              (println result-str)
              (if snap-tree
                ;; Snapshot result — format like 'spel snapshot' output
                (do (when-not (str/blank? (str snap-tree))
                      (println (str/trim (str snap-tree))))
                    (when-let [url (get data :url)]
                      (println (str "\n  URL: " url)))
                    (when-let [title (get data :title)]
                      (println (str "  Title: " title)))
                    (when-let [desc (get data :description)]
                      (println (str "  Description: " desc))))
                (println result-str))))
          ;; Error from daemon
          (do (vreset! exit-code 1)
              (let [error-msg (or (get-in response [:data :error])
                                (:error response)
                                "unexpected browser error (no details from runtime)")]
                (if (:json? global)
                  ;; --json mode: structured error with call_log/selector
                  (let [err-map (cond-> {:error error-msg}
                                  (:hint response) (assoc :hint (:hint response))
                                  (:error_code response) (assoc :error_code (:error_code response))
                                  (:call_log response) (assoc :call_log (:call_log response))
                                  (:selector response) (assoc :selector (:selector response)))]
                    (println (json/write-json-str err-map :escape-slash false)))
                  ;; text mode: print full error message as-is
                  (eprintln (str "Error: " error-msg)))))))
      (catch Exception e
        (vreset! exit-code 1)
        (eprintln (str "Error: " (.getMessage e))))
      (finally
        ;; --autoclose: shut down daemon after eval
        (when (:autoclose? global)
          (try
            (cli/send-command! session {"action" "close"} 5000)
            (catch Exception _)))
        (System/exit @exit-code)))))

(defn- split-eval-tail-args
  "Splits `eval-sci` trailing args into command args and script args.

   Returns {:command-args [...], :script-args nil|[...]} where script-args
   contains everything after `--` (if present)."
  [args]
  (let [argv (vec args)
        sep-idx (.indexOf ^java.util.List argv "--")]
    (if (neg? sep-idx)
      {:command-args argv
       :script-args nil}
      {:command-args (subvec argv 0 sep-idx)
       :script-args  (subvec argv (inc sep-idx))})))

;; =============================================================================
;; merge-reports helpers
;; =============================================================================

(defn- merge-reports-help
  "Help text for the merge-reports subcommand."
  ^String []
  (str/join \newline
    ["merge-reports - Merge multiple allure-results directories into one"
     ""
     "Usage:"
     "  spel merge-reports <dir1> <dir2> ... [options]"
     ""
     "Copies all result JSON files, attachments, and supplementary files"
     "(environment.properties, categories.json) from each source directory"
     "into a single output directory. Optionally generates the combined"
     "HTML report."
     ""
     "Options:"
     "  --output DIR          Output directory (default: allure-results)"
     "  --report-dir DIR      HTML report directory (default: allure-report)"
     "  --no-report           Skip HTML report generation"
     "  --no-clean            Don't clean output directory before merging"
     "  --help, -h            Show this help"
     ""
     "Examples:"
     "  spel merge-reports results-lazytest results-ct"
     "  spel merge-reports results-* --output combined-results"
     "  spel merge-reports dir1 dir2 dir3 --no-report"
     "  spel merge-reports dir1 dir2 --output merged --report-dir report"]))

(defn- parse-merge-reports-args
  "Parse merge-reports CLI arguments into {:dirs [...] :opts {...}}."
  [args]
  (loop [args   args
         dirs   []
         opts   {}]
    (if (empty? args)
      {:dirs dirs :opts opts}
      (let [arg (first args)]
        (cond
          (or (= arg "--help") (= arg "-h"))
          {:dirs dirs :opts (assoc opts :help true)}

          (str/starts-with? arg "--output=")
          (recur (rest args) dirs (assoc opts :output-dir (subs arg 9)))

          (= arg "--output")
          (recur (drop 2 args) dirs (assoc opts :output-dir (second args)))

          (str/starts-with? arg "--report-dir=")
          (recur (rest args) dirs (assoc opts :report-dir (subs arg 13)))

          (= arg "--report-dir")
          (recur (drop 2 args) dirs (assoc opts :report-dir (second args)))

          (= arg "--no-report")
          (recur (rest args) dirs (assoc opts :report false))

          (= arg "--no-clean")
          (recur (rest args) dirs (assoc opts :clean false))

          (str/starts-with? arg "--")
          (recur (rest args) dirs opts)

          :else
          (recur (rest args) (conj dirs arg) opts))))))

;; =============================================================================
;; report helpers (Blockether native report)
;; =============================================================================

(defn- report-help
  "Help text for the report subcommand."
  ^String []
  (str/join \newline
    ["report - Generate a Blockether-themed HTML report from allure-results"
     ""
     "Usage:"
     "  spel report [options]"
     ""
     "Reads allure-results/ JSON files and generates a standalone HTML report"
     "with the Blockether design system (warm earth tones, no Node.js required)."
     ""
     "Options:"
     "  --results-dir DIR       Allure results directory (default: allure-results)"
     "  --output-dir DIR        Output directory for HTML report (default: block-report)"
     "  --title TEXT            Report title shown in <h1> (default: \"Allure Report\")"
     "  --kicker TEXT           Small mono heading above the title"
     "  --subtitle TEXT         One-line subtitle under the title"
     ""
     "Branding:"
     "  --logo SRC              Logo shown above the title. Accepts:"
     "                            • filesystem path (abs or relative to results-dir)"
     "                            • data:image/… URL"
     "                            • http(s):// URL"
     "                            • inline <svg …>…</svg> markup"
     "                          File paths are copied into <output-dir>/assets/."
     "  --logo-alt TEXT         alt text for the logo <img> (default: report title)"
     "  --description TEXT      Description block rendered under the title."
     "                          Plain text is html-escaped. Strings starting with"
     "                          '<' are treated as HTML and sanitized (scripts,"
     "                          iframes, event handlers and javascript: URLs are"
     "                          stripped)."
     "  --custom-css CSS        Extra CSS appended after the built-in stylesheet"
     "                          (hostile </style> closures are neutralized)."
     "  --custom-css-file FILE  Path to a CSS file whose contents are inlined."
     ""
     "Build metadata:"
     "  --build-id ID           Build/run identifier shown in the header kicker"
     "                          (e.g. '#544')."
     "  --build-date VALUE      Build timestamp. Accepts epoch-millis Long or"
     "                          ISO-8601 string (e.g. '2026-04-10T12:00:00Z')."
     "  --build-url URL         Link to the CI run; rendered as a chip in the"
     "                          header subtitle."
     ""
     "  --help, -h              Show this help"
     ""
     "Every branding / metadata flag can also be set via environment.properties"
     "keys under the corresponding `report.*` / `build.*` names. See"
     "`generate!` docstring in the spel-allure-alternative-html-report namespace"
     "for the full list."
     ""
     "Examples:"
     "  spel report"
     "  spel report --results-dir test-results --output-dir my-report"
     "  spel report --title 'My Project Tests' --description 'Nightly smoke run'"
     "  spel report --logo brand/logo.svg --logo-alt 'Acme Co.'"
     "  spel report --build-id '#544' --build-date 2026-04-10T12:00:00Z \\"
     "              --build-url https://ci.example/run/544"]))

(defn- parse-report-args
  "Parse report CLI arguments into {:opts {...}}.
   Accepts both `--flag VALUE` and `--flag=VALUE` forms."
  [args]
  (let [flag-keys
        ;; Map of `--flag` → [:opt-key body-length-for-prefix-form]
        ;; body-length = count of the `--flag=` prefix, used for the
        ;; `subs` call when the inline form is used.
        {"--results-dir"     [:results-dir     14]
         "--output-dir"      [:output-dir      13]
         "--title"           [:title           8]
         "--kicker"          [:kicker          9]
         "--subtitle"        [:subtitle        11]
         "--logo"            [:logo            7]
         "--logo-alt"        [:logo-alt        11]
         "--description"     [:description     14]
         "--custom-css"      [:custom-css      13]
         "--custom-css-file" [:custom-css-file 18]
         "--build-id"        [:build-id        11]
         "--build-date"      [:build-date      13]
         "--build-url"       [:build-url       12]}]
    (loop [args args
           opts {}]
      (if (empty? args)
        {:opts opts}
        (let [arg (first args)]
          (cond
            (or (= arg "--help") (= arg "-h"))
            {:opts (assoc opts :help true)}

            ;; --flag=VALUE
            (let [eq (str/index-of arg "=")]
              (and eq (contains? flag-keys (subs arg 0 eq))))
            (let [eq (long (str/index-of arg "="))
                  [k _] (get flag-keys (subs arg 0 eq))]
              (recur (rest args) (assoc opts k (subs arg (inc eq)))))

            ;; --flag VALUE
            (contains? flag-keys arg)
            (let [[k _] (get flag-keys arg)]
              (recur (drop 2 args) (assoc opts k (second args))))

            :else
            (recur (rest args) opts)))))))

(defn -main
  "Main entry point for the native-image binary.

   Dispatches to the appropriate mode based on the first argument:
   - 'eval-sci'  → evaluate expression and exit
   - 'install' → install Playwright browsers
   - 'version' → print version
   - '--help'  → print help
   - anything else → CLI command

   Global flags (--timeout, --debug, --json) are parsed first and
   apply across all modes. For eval-sci mode, --timeout sets Playwright's
   default action timeout. For CLI mode, flags pass through to cli.clj."
  [& raw-args]
  (let [args      (normalize-args raw-args)
        global    (parse-global-flags args)
        cmd-args  (:command-args global)
        first-arg (first cmd-args)
        ;; Set Playwright action timeout — sci-start! picks it up in eval-sci mode
        _         (when (:timeout-ms global) (sci-env/set-default-timeout! (:timeout-ms global)))]
    (cond
      ;; Subcommands — dispatch BEFORE global --help so each tool handles its own --help
      (= "init-agents" first-arg)
      (apply init-agents/-main (rest cmd-args))

      (= "ci-assemble" first-arg)
      (apply ci/-main (rest cmd-args))

      (= "merge-reports" first-arg)
      (let [sub-args (rest cmd-args)]
        (if (some #{"--help" "-h"} sub-args)
          (println (merge-reports-help))
          (let [{:keys [dirs opts]} (parse-merge-reports-args sub-args)]
            (if (empty? dirs)
              (do (println "Error: at least one source directory is required")
                  (println "")
                  (println "Usage: spel merge-reports <dir1> <dir2> ... [options]")
                  (println "Run 'spel merge-reports --help' for details.")
                  (System/exit 1))
              (allure-reporter/merge-results! dirs opts)))))

      (= "report" first-arg)
      (let [sub-args (rest cmd-args)]
        (if (some #{"--help" "-h"} sub-args)
          (println (report-help))
          (let [{:keys [opts]} (parse-report-args sub-args)]
            (alternative-report/generate!
              (:results-dir opts "allure-results")
              (:output-dir opts "block-report")
              (dissoc opts :results-dir :output-dir :help)))))

      (= "codegen" first-arg)
      (let [sub-args (rest cmd-args)]
        (if (= "record" (first sub-args))
          ;; Launch Playwright's interactive codegen recorder
          (let [record-args (vec (rest sub-args))]
            (if (some #{"--help" "-h"} record-args)
              (println (get cli/command-help "codegen-record"))
              (let [has-target? (some #(or (str/starts-with? % "--target")
                                         (= "-t" %)) record-args)
                    has-output? (some #(or (str/starts-with? % "--output")
                                         (str/starts-with? % "-o")) record-args)
                    ;; Default to jsonl target if not specified
                    args-with-target (if has-target?
                                       record-args
                                       (into ["--target=jsonl"] record-args))
                    ;; Auto-generate output file if not specified
                    output-file (when-not has-output?
                                  (let [ts (-> (java.time.LocalDateTime/now)
                                             (.format (java.time.format.DateTimeFormatter/ofPattern
                                                        "yyyyMMdd-HHmmss")))]
                                    (str "recording-" ts ".jsonl")))
                    final-args (if has-output?
                                 args-with-target
                                 (into [(str "-o" output-file)] args-with-target))
                    ;; Translate spel flag names to Playwright's names
                    translated-args (mapv (fn [a]
                                            (cond
                                              (= "--load-state" a) "--load-storage"
                                              (= "--save-state" a) "--save-storage"
                                              (str/starts-with? a "--load-state=") (str "--load-storage=" (subs a 13))
                                              (str/starts-with? a "--save-state=") (str "--save-storage=" (subs a 13))
                                              :else a))
                                      final-args)]
                (when output-file
                  (println (str "Recording to: " output-file))
                  (flush))
                (driver/ensure-driver!)
                (run-playwright-cmd! (into ["codegen"] translated-args))
                (when (and output-file (.exists (io/file output-file)))
                  (println (str "\nRecording saved to: " output-file))
                  (println (str "Transform with: spel codegen " output-file))
                  (flush)))))
          ;; Existing transform behavior
          (apply codegen/-main sub-args)))

      ;; Search — Google search tool
      (= "search" first-arg)
      (let [sub-args (rest cmd-args)
            ;; Re-inject --json if global parser consumed it
            sub-args (if (:json? global) (cons "--json" sub-args) sub-args)]
        (if (some #{"--help" "-h"} sub-args)
          (println (get cli/command-help "search"))
          (do (driver/ensure-driver!)
              (apply search/-main sub-args))))

      (= "install" first-arg)
      (do (driver/ensure-driver!)
          (run-install! (rest cmd-args)))

      ;; Self-upgrade — compares SPEL_VERSION to the latest GitHub release and
      ;; prints the command to run (brew/cargo/manual curl). `--check` reports
      ;; without mutating anything.
      (= "upgrade" first-arg)
      (handle-upgrade! (rest cmd-args))

      ;; Inspector — launch Playwright Inspector (wraps `playwright open`)
      (= "inspector" first-arg)
      (let [rest-args (rest cmd-args)]
        (if (some #{"--help" "-h"} rest-args)
          (println (get cli/command-help "inspector"))
          (do (driver/ensure-driver!)
              (run-playwright-cmd! (into ["open"] rest-args)))))

      ;; Show-trace — launch Playwright Trace Viewer
      (= "show-trace" first-arg)
      (let [rest-args (rest cmd-args)]
        (if (some #{"--help" "-h"} rest-args)
          (println (get cli/command-help "show-trace"))
          (do (driver/ensure-driver!)
              (run-playwright-cmd! (into ["show-trace"] rest-args)))))

      ;; Stitch — local image stitching, no daemon needed
      (= "stitch" first-arg)
      (let [rest-args (rest cmd-args)]
        (if (some #{"--help" "-h"} rest-args)
          (println (get cli/command-help "stitch"))
          (let [;; Parse -o/--output flag
                args-v     (vec rest-args)
                out-idx    (long (max (long (.indexOf ^java.util.List args-v "-o"))
                                   (long (.indexOf ^java.util.List args-v "--output"))))
                out-path   (when (>= out-idx 0) (nth args-v (inc out-idx) nil))
                ;; Parse --overlap flag
                ovl-idx    (long (.indexOf ^java.util.List args-v "--overlap"))
                overlap-px (when (>= ovl-idx 0)
                             (try (Long/parseLong (nth args-v (inc ovl-idx)))
                                  (catch Exception _ 0)))
                ;; Collect input paths (everything that's not a flag or flag value)
                skip-idxs  (cond-> #{}
                             (>= out-idx 0)   (conj out-idx (inc out-idx))
                             (>= ovl-idx 0)   (conj ovl-idx (inc ovl-idx)))
                inputs     (vec (keep-indexed (fn [i v]
                                                (when-not (or (skip-idxs i)
                                                            (str/starts-with? v "-"))
                                                  v))
                                  args-v))
                output     (or out-path
                             (str "/tmp/spel-stitched-" (System/currentTimeMillis) ".png"))]
            (cond
              (< (count inputs) 2)
              (do (eprintln "Error: stitch requires at least 2 input images")
                  (eprintln "Usage: spel stitch <img1> <img2> [img3...] [-o output.png]")
                  (System/exit 1))

              (some #(not (.exists (java.io.File. ^String %))) inputs)
              (let [missing (first (filter #(not (.exists (java.io.File. ^String %))) inputs))]
                (eprintln (str "Error: file not found: " missing))
                (System/exit 1))

              :else
              (do (driver/ensure-driver!)
                  (stitch/stitch-vertical-overlap inputs output
                    (cond-> {:overlap-px (or overlap-px 0)}
                      (:browser global) (assoc :browser-type (:browser global))))
                  (println output))))))

      ;; Help — bare `spel --help` / `spel -h` / `spel help` / `spel` (no args)
      ;; Per-command help (e.g. `spel open --help`) falls through to cli/run-cli!
      (or (nil? first-arg)
        (#{"--help" "-h" "help"} first-arg))
      (print-help)

      ;; Version
      (or (= "version" first-arg)
        (= "--version" first-arg))
      (print-version)

      ;; Daemon mode (internal — started by CLI client)
      (= "daemon" first-arg)
      (do (driver/ensure-driver!)
          (let [opts (parse-daemon-args (rest cmd-args))
                ;; parse-global-flags may have consumed --session/--browser/--headed
                ;; before they reached cmd-args; prefer global values for the daemon.
                opts (cond-> opts
                       (:session global) (assoc :session (:session global))
                       (:interactive? global) (assoc :headless false)
                       (:cdp global) (assoc :cdp (:cdp global))
                       (and (:browser global) (not (:browser opts)))
                       (assoc :browser (:browser global)))]
            (daemon/start-daemon! opts)))

      ;; Eval mode — ensure driver in case the expression uses Playwright
      ;; Supports both inline code and .clj file paths:
      ;;   spel eval-sci '(+ 1 2)'
      ;;   spel eval-sci script.clj
      (= "eval-sci" first-arg)
      (let [{:keys [command-args script-args]} (split-eval-tail-args (rest cmd-args))
            code-or-file (first command-args)]
        (if code-or-file
          (let [code (if (and (str/ends-with? code-or-file ".clj")
                           (.exists (java.io.File. ^String code-or-file)))
                       (slurp (java.io.File. ^String code-or-file))
                       code-or-file)]
            (run-eval! code script-args global))
          (do (eprintln "Error: eval-sci requires a code argument or .clj file path")
              (System/exit 1))))

      ;; CLI command — pass NORMALIZED args (cli.clj has its own flag parser)
      :else
      (do (driver/ensure-driver!)
          (cli/run-cli! args)))
    (System/exit 0)))
