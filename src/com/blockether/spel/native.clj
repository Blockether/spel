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
     spel --eval '(+ 1 2)'            # Evaluate and exit
     spel install                     # Install Playwright browsers
     spel --help                      # Show help"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure-reporter :as allure-reporter]
   [com.blockether.spel.chrome-cookies :as chrome-cookies]
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
  (println "  snapshot -s \"#main\"       Scoped to selector")
  (println "  screenshot [path]         Take screenshot (-f full page)")
  (println "  stitch <imgs...>          Stitch screenshots vertically (-o, --overlap)")
  (println "  annotate                  Show annotation overlays (visible elements)")
  (println "    -s, --scope <sel|@ref>  Scope annotations to a subtree")
  (println "    --no-badges             Hide ref labels")
  (println "    --no-dimensions         Hide size labels")
  (println "    --no-boxes              Hide bounding boxes")
  (println "  unannotate                Remove annotation overlays")
  (println "  pdf <path>                Save as PDF")
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
  (println "  eval <js>                 Run JavaScript")
  (println "  eval <js> -b              Run JavaScript, base64-encode result")
  (println "  connect <url>             Connect to browser via CDP")
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
  (println "  --channel <name>          Browser channel (e.g. \"chrome\", \"msedge\")")
  (println "  --executable-path <path>  Custom browser executable")
  (println "  --user-agent <ua>         Custom user agent string")
  (println "  --proxy <url>             Proxy server URL")
  (println "  --proxy-bypass <domains>  Proxy bypass domains")
  (println "  --headers <json>          HTTP headers")
  (println "  --args <args>             Browser args (comma-separated)")
  (println "  --cdp <url>               Connect via CDP endpoint")
  (println "  --ignore-https-errors     Ignore HTTPS errors")
  (println "  --allow-file-access       Allow file:// access")
  (println "  --no-stealth              Disable stealth mode (stealth is ON by default)")
  (println "  --timeout <ms>            Playwright action timeout in ms (default: 30000)")
  (println "  --debug                   Debug output")
  (println "  --help, -h                Show this help")
  (println "")
  (println "Tools:")
  (println "  state export [opts]       Export Chrome cookies + localStorage to Playwright state JSON (--help)")
  (println "  search <query> [opts]     Google search from the CLI (--help for details)")
  (println "  init-agents [opts]        Scaffold E2E testing agents (--help for details)")
  (println "  codegen record [url]      Record browser session (interactive Playwright Codegen)")
  (println "  codegen [opts] [file]     Transform JSONL recording to Clojure code (--help for details)")
  (println "  ci-assemble [opts]        Assemble Allure site for CI deployment (--help for details)")
  (println "  merge-reports [dirs]      Merge N allure-results dirs into one (--help for details)")
  (println "")
  (println "Utility:")
  (println "  install [--with-deps]     Install Playwright browsers")
  (println "  version                   Show version")
  (println "")
  (println "Modes:")
  (println "  --eval '<code>'           Evaluate Clojure expression")
  (println "  --eval <file.clj>         Evaluate Clojure file (e.g. codegen script)")
  (println "  --eval --interactive      Evaluate with visible browser (headed mode)")
  (println "  --eval --load-state F      Load auth/state before evaluation (alias: --storage-state)")
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
  (println "  spel --eval script.clj --load-state auth.json"))

(defn- spel-version
  "Reads the version string from the SPEL_VERSION resource file.
   This file is the single source of truth for the spel version."
  []
  (-> (clojure.java.io/resource "SPEL_VERSION")
    slurp
    str/trim))

(defn- print-version []
  (println (str "spel " (spel-version))))

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
;; Daemon Argument Parsing
;; =============================================================================

(defn- parse-daemon-args
  "Parses daemon-specific arguments from argv.

   Supports:
     --session <name>  Session name (default \"default\")
     --headed          Run browser in headed mode (internal, set by --interactive)
     --headless        Run browser headless (default)"
  [args]
  (loop [remaining args
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

          :else
          (recur (rest remaining) opts))))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn- parse-global-flags
  "Pre-parses global flags from args.
   Returns {:timeout-ms long?, :debug? bool, :json? bool, :autoclose? bool,
            :interactive? bool, :session str?, :command-args vec}.
   :command-args has global flags stripped for dispatch (finding the command).
   Original args are preserved for modes that do their own parsing (CLI daemon)."
  [args]
  (loop [remaining args
         cmd-args  []
         opts      {:timeout-ms nil :debug? false :json? false :autoclose? false :interactive? false :session nil :load-state nil}]
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

          ;; --interactive (headed browser for --eval mode)
          (= "--interactive" arg)
          (recur (rest remaining) cmd-args (assoc opts :interactive? true))

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

          :else
          (recur (rest remaining) (conj cmd-args arg) opts))))))

(defn- run-eval!
  "Runs --eval mode via daemon: sends code for evaluation, browser persists.
   The daemon lazily starts a browser on first Playwright call and keeps it
   alive between invocations. Use --autoclose to shut the daemon after eval.
   When --load-state is set, loads browser storage state (cookies/localStorage)
   from a JSON file before evaluating the code."
  [code global]
  (driver/ensure-driver!)
  (let [session   (or (:session global) "default")
        ;; Bootstrap timeout: 30s for single-action setup commands (state_load, nil eval).
        ;; These are short operations that should never take long.
        boot-timeout 30000
        ;; Eval timeout: 4x the per-action timeout, floor 120s.
        ;; Each Playwright action has its own timeout (--timeout flag, default 30s).
        ;; The transport needs headroom for multi-action scripts but should fail
        ;; fast if something truly hangs — not block forever.
        action-timeout (or (:timeout-ms global) 30000)
        eval-timeout   (max 120000 (* 4 (long action-timeout)))
        exit-code (volatile! 0)]
    (try
      ;; Ensure daemon is running (same as CLI mode)
      ;; --interactive launches headed (visible) browser for eval mode
      (cli/ensure-daemon! session {:headless (not (:interactive? global))})
      ;; Set timeout on daemon side if provided
      (when (:timeout-ms global)
        (sci-env/set-default-timeout! (:timeout-ms global)))
      ;; Load state if --load-state specified
      (when-let [state-path (:load-state global)]
        ;; Bootstrap browser first (sci_eval triggers ensure-browser!)
        (cli/send-command! session {"action" "sci_eval" "code" "nil"} boot-timeout)
        ;; Load state into context (replaces context with saved cookies/storage)
        (let [resp (cli/send-command! session
                     {"action" "state_load" "path" state-path}
                     boot-timeout)]
          (when-not (:success resp)
            (eprintln (str "Warning: failed to load state from " state-path ": "
                        (or (get-in resp [:data :error]) (:error resp)))))))
      ;; Send eval command to daemon — no transport timeout.
      ;; Playwright action timeouts are the correct control mechanism.
      (let [response     (cli/send-command! session
                           {"action" "sci_eval" "code" code}
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
        (when (seq console-msgs)
          (doseq [{:keys [type text]} console-msgs]
            (eprintln (str "[console." type "] " text))))
        (when (seq page-errors)
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
                                "Unknown error")]
                (if (:json? global)
                  ;; --json mode: structured error with call_log/selector
                  (let [err-map (cond-> {:error error-msg}
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

(defn -main
  "Main entry point for the native-image binary.

   Dispatches to the appropriate mode based on the first argument:
   - '--eval'  → evaluate expression and exit
   - 'install' → install Playwright browsers
   - 'version' → print version
   - '--help'  → print help
   - anything else → CLI command

   Global flags (--timeout, --debug, --json) are parsed first and
   apply across all modes. For --eval mode, --timeout sets Playwright's
   default action timeout. For CLI mode, flags pass through to cli.clj."
  [& args]
  (let [global    (parse-global-flags args)
        cmd-args  (:command-args global)
        first-arg (first cmd-args)
        ;; Set Playwright action timeout — sci-start! picks it up in --eval mode
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
      (let [sub-args (rest cmd-args)]
        (if (some #{"--help" "-h"} sub-args)
          (println (get cli/command-help "search"))
          (do (driver/ensure-driver!)
              (apply search/-main sub-args))))

      (= "install" first-arg)
      (do (driver/ensure-driver!)
          (run-install! (rest cmd-args)))

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

      ;; State export (primary) / cookies-export (deprecated alias)
      (or (and (= "state" first-arg) (= "export" (second cmd-args)))
        (= "cookies-export" first-arg))
      (let [sub-args (if (= "cookies-export" first-arg)
                       (rest cmd-args)
                       (drop 2 cmd-args))]
        (if (some #{"--help" "-h"} sub-args)
          (println (str/join \newline
                     ["state export — Export Chrome cookies + localStorage to Playwright state JSON"
                      ""
                      "Decrypts cookies and reads localStorage from a real Chrome profile, writing"
                      "them as a portable JSON file compatible with --load-state. Use this to"
                      "transfer authenticated sessions between machines or platforms (e.g. macOS → Linux)."
                      ""
                      "(Alias: spel cookies-export)"
                      ""
                      "The output includes both:"
                      "  - Decrypted cookies (from Chrome's encrypted SQLite Cookies database)"
                      "  - localStorage data (from Chrome's LevelDB Local Storage directory)"
                      ""
                      "Usage:"
                      "  spel state export --profile <path> [-o <file>] [--domain <pattern>] [--no-local-storage] [--channel <name>]"
                      ""
                      "Options:"
                      "  --profile <path>       Browser profile directory (required)"
                      "  -o, --output <file>    Output file path (default: stdout)"
                      "  --domain <pattern>     Filter cookies/localStorage by domain/origin (e.g. \".x.com\")"
                      "  --no-local-storage     Skip localStorage export (cookies only)"
                      "  --channel <name>       Browser channel (e.g. \"msedge\", \"chrome\", \"brave\")"
                      "  --help, -h             Show this help"
                      ""
                      "Examples:"
                      "  # Export all cookies + localStorage to file"
                      "  spel state export --profile \"~/Library/Application Support/Google/Chrome/Profile 1\" -o state.json"
                      ""
                      "  # Export only x.com cookies + localStorage"
                      "  spel state export --profile \"~/.config/google-chrome/Default\" --domain \".x.com\" -o x-state.json"
                      ""
                      "  # Pipe to stdout (e.g. for jq)"
                      "  spel state export --profile ~/snap/chromium/common/chromium/Default --domain github.com"
                      ""
                      "  # Use exported state on another machine"
                      "  scp state.json linux-server:~/"
                      "  ssh linux-server spel --load-state state.json open https://x.com --interactive"]))
          (let [parsed (loop [args sub-args profile nil output nil domain nil include-ls true channel nil]
                         (if (empty? args)
                           {:profile profile :output output :domain domain :include-ls include-ls :channel channel}
                           (let [arg (first args)]
                             (cond
                               (= "--profile" arg)
                               (recur (drop 2 args) (second args) output domain include-ls channel)
                               (str/starts-with? arg "--profile=")
                               (recur (rest args) (subs arg 10) output domain include-ls channel)
                               (or (= "-o" arg) (= "--output" arg))
                               (recur (drop 2 args) profile (second args) domain include-ls channel)
                               (str/starts-with? arg "--output=")
                               (recur (rest args) profile (subs arg 9) domain include-ls channel)
                               (= "--domain" arg)
                               (recur (drop 2 args) profile output (second args) include-ls channel)
                               (str/starts-with? arg "--domain=")
                               (recur (rest args) profile output (subs arg 9) include-ls channel)
                               (= "--no-local-storage" arg)
                               (recur (rest args) profile output domain false channel)
                               (= "--channel" arg)
                               (recur (drop 2 args) profile output domain include-ls (second args))
                               (str/starts-with? arg "--channel=")
                               (recur (rest args) profile output domain include-ls (subs arg 10))
                               :else
                               (recur (rest args) profile output domain include-ls channel)))))]
            (when-not (:profile parsed)
              (eprintln "Error: --profile is required")
              (eprintln "Usage: spel state export --profile <path> [-o <file>] [--domain <pattern>]")
              (System/exit 1))
            (let [json-str (chrome-cookies/export-cookies-json
                             (:profile parsed) (:domain parsed) (:include-ls parsed)
                             {:channel (:channel parsed)})]
              (if (:output parsed)
                (do (spit (:output parsed) json-str)
                    (let [parsed-json (json/read-json json-str)
                          n (count (get parsed-json "cookies"))]
                      (eprintln (str "Exported " n " cookies to " (:output parsed)))))
                (println json-str))))))

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
                    {:overlap-px (or overlap-px 0)})
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
                ;; parse-global-flags may have consumed --session before it reached
                ;; cmd-args; prefer the global value so daemon gets the right session.
                opts (if-let [s (:session global)]
                       (assoc opts :session s)
                       opts)]
            (daemon/start-daemon! opts)))

      ;; Eval mode — ensure driver in case the expression uses Playwright
      ;; Supports both inline code and .clj file paths:
      ;;   spel --eval '(+ 1 2)'
      ;;   spel --eval script.clj
      (= "--eval" first-arg)
      (let [code-or-file (second cmd-args)]
        (if code-or-file
          (let [code (if (and (str/ends-with? code-or-file ".clj")
                           (.exists (java.io.File. ^String code-or-file)))
                       (slurp (java.io.File. ^String code-or-file))
                       code-or-file)]
            (run-eval! code global))
          (do (eprintln "Error: --eval requires a code argument or .clj file path")
              (System/exit 1))))

      (and (string? first-arg) (str/starts-with? first-arg "--eval="))
      (let [code-or-file (subs first-arg 7)
            code (if (and (str/ends-with? code-or-file ".clj")
                       (.exists (java.io.File. ^String code-or-file)))
                   (slurp (java.io.File. ^String code-or-file))
                   code-or-file)]
        (run-eval! code global))

      ;; CLI command — pass ORIGINAL args (cli.clj has its own flag parser)
      :else
      (do (driver/ensure-driver!)
          (cli/run-cli! args)))
    (System/exit 0)))
