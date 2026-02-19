(ns com.blockether.spel.native
  "Native-image entry point for spel.

   Provides a CLI tool (spel) for browser automation with persistent browser sessions.

   Modes:
   - CLI commands (default): Send commands to the browser process
   - Eval: Evaluate a Clojure expression and exit

   Usage:
     spel open https://example.com   # Navigate (auto-starts browser)
     spel snapshot                    # ARIA snapshot with refs
     spel click @e1                   # Click by ref
     spel fill @e2 \"search text\"      # Fill input by ref
     spel screenshot shot.png         # Take screenshot
     spel close                       # Close browser
     spel --eval '(+ 1 2)'            # Evaluate and exit
     spel install                     # Install Playwright browsers
     spel --help                      # Show help"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.cli :as cli]
   [com.blockether.spel.codegen :as codegen]
   [com.blockether.spel.daemon :as daemon]
   [com.blockether.spel.driver :as driver]
   [com.blockether.spel.init-agents :as init-agents]
   [com.blockether.spel.sci-env :as sci-env])
  (:gen-class))

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
  (println "  click @e1                 Click element by ref or selector")
  (println "  dblclick <sel>            Double-click element")
  (println "  fill @e2 \"text\"           Clear and fill input")
  (println "  type @e2 \"text\"           Type without clearing")
  (println "  press <key>               Press key (Enter, Tab, Control+a) (alias: key)")
  (println "  keydown <key>             Hold key down")
  (println "  keyup <key>               Release key")
  (println "  hover <sel>               Hover element")
  (println "  select <sel> <val>        Select dropdown option")
  (println "  check / uncheck <sel>     Toggle checkbox")
  (println "  focus <sel>               Focus element")
  (println "  clear <sel>               Clear input")
  (println "  scroll <dir> [px]         Scroll (up/down/left/right)")
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
  (println "  annotate                  Show annotation overlays (visible elements)")
  (println "    -s, --scope <sel|@ref>  Scope annotations to a subtree")
  (println "    --no-badges             Hide ref labels")
  (println "    --no-dimensions         Hide size labels")
  (println "    --no-boxes              Hide bounding boxes")
  (println "  unannotate                Remove annotation overlays")
  (println "  pdf <path>                Save as PDF")
  (println "")
  (println "Get Info:")
  (println "  get text @e1              Get text content")
  (println "  get html @e1              Get innerHTML")
  (println "  get value @e1             Get input value")
  (println "  get attr @e1 <name>       Get attribute value")
  (println "  get url / get title       Get page URL or title")
  (println "  get count <sel>           Count matching elements")
  (println "  get box <sel>             Get bounding box")
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
  (println "  --json                    JSON output (for agents)")
  (println "  --storage-state <path>    Load storage state (cookies/localStorage JSON)")
  (println "  --profile <path>          Chrome user data directory (persistent profile)")
  (println "  --executable-path <path>  Custom browser executable")
  (println "  --user-agent <ua>         Custom user agent string")
  (println "  --proxy <url>             Proxy server URL")
  (println "  --proxy-bypass <domains>  Proxy bypass domains")
  (println "  --headers <json>          HTTP headers")
  (println "  --args <args>             Browser args (comma-separated)")
  (println "  --cdp <url>               Connect via CDP endpoint")
  (println "  --ignore-https-errors     Ignore HTTPS errors")
  (println "  --allow-file-access       Allow file:// access")
  (println "  --timeout <ms>            Command timeout in ms (default: 30000)")
  (println "  --debug                   Debug output")
  (println "  --help, -h                Show this help")
  (println "")
  (println "Tools:")
  (println "  init-agents [opts]        Scaffold E2E testing agents (--help for details)")
  (println "  codegen record [url]      Record browser session (interactive Playwright Codegen)")
  (println "  codegen [opts] [file]     Transform JSONL recording to Clojure code (--help for details)")
  (println "")
  (println "Utility:")
  (println "  install [--with-deps]     Install Playwright browsers")
  (println "  version                   Show version")
  (println "")
  (println "Modes:")
  (println "  --eval '<code>'           Evaluate Clojure expression")
  (println "  --eval <file.clj>         Evaluate Clojure file (e.g. codegen script)")
  (println "  --eval --interactive      Evaluate with visible browser (headed mode)")
  (println "  --eval --load-state FILE  Load auth/storage state before evaluation")
  (println "")
  (println "Examples:")
  (println "  spel open example.com")
  (println "  spel snapshot -i --json")
  (println "  spel click @e2")
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

(defn- run-playwright-cmd!
  "Runs a Playwright CLI command as a subprocess via the Node.js driver.
   Inherits stdout/stderr so users see output. Returns the exit code.
   Calls System/exit on non-zero exit."
  [cmd-args]
  (let [node (str (driver-node-path))
        cli  (str (driver-cli-path))
        args (into [node cli] cmd-args)
        pb   (doto (ProcessBuilder. ^java.util.List args)
               (.inheritIO))
        proc (.start pb)
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (System/exit exit))))

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

          ;; --load-state=<path>
          (and (string? arg) (str/starts-with? arg "--load-state="))
          (recur (rest remaining) cmd-args
            (assoc opts :load-state (subs arg 13)))

          ;; --load-state <path>
          (= "--load-state" arg)
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
  (let [session  (or (:session global) "default")
        timeout  (or (:timeout-ms global) 30000)
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
        (cli/send-command! session {"action" "sci_eval" "code" "nil"} timeout)
        ;; Load state into context (replaces context with saved cookies/storage)
        (let [resp (cli/send-command! session
                     {"action" "state_load" "path" state-path}
                     timeout)]
          (when-not (:success resp)
            (binding [*out* *err*]
              (println (str "Warning: failed to load state from " state-path ": "
                         (or (get-in resp [:data :error]) (:error resp))))))))
      ;; Send eval command to daemon
      (let [response (cli/send-command! session
                       {"action" "sci_eval" "code" code}
                       timeout)]
        (if (and response (:success response))
          ;; Success — print the result
          (let [result-str (get-in response [:data :result])]
            (if (:json? global)
              (println result-str)
              (println result-str)))
          ;; Error from daemon
          (do (vreset! exit-code 1)
              (binding [*out* *err*]
                (println (str "Error: " (or (get-in response [:data :error])
                                          (:error response)
                                          "Unknown error")))))))
      (catch Exception e
        (vreset! exit-code 1)
        (binding [*out* *err*]
          (println (str "Error: " (.getMessage e)))))
      (finally
        ;; --autoclose: shut down daemon after eval
        (when (:autoclose? global)
          (try
            (cli/send-command! session {"action" "close"} 5000)
            (catch Exception _)))
        (System/exit @exit-code)))))

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

      (= "codegen" first-arg)
      (let [sub-args (rest cmd-args)]
        (if (= "record" (first sub-args))
          ;; Launch Playwright's interactive codegen recorder
          (let [record-args (vec (rest sub-args))]
            (if (some #{"--help" "-h"} record-args)
              (println (get cli/command-help "codegen-record"))
              (let [has-target? (some #(or (str/starts-with? % "--target")
                                         (= "-t" %)) record-args)
                    final-args (if has-target?
                                 record-args
                                 (into ["--target=jsonl"] record-args))]
                (driver/ensure-driver!)
                (run-playwright-cmd! (into ["codegen"] final-args)))))
          ;; Existing transform behavior
          (apply codegen/-main sub-args)))

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
          (let [opts (parse-daemon-args (rest cmd-args))]
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
          (do (binding [*out* *err*]
                (println "Error: --eval requires a code argument or .clj file path"))
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
