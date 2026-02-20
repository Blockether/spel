(ns com.blockether.spel.allure
  "In-test Allure API for enriching test reports with steps, metadata,
   screenshots, and attachments.

   All functions are no-ops when `*context*` is nil, meaning they are
   safe to call even when not running under the Allure reporter.

    Usage in tests:

      (ns my-app.login-test
        (:require [com.blockether.spel.allure :as allure]
                  [com.blockether.spel.page :as page]
                  [com.blockether.spel.locator :as locator]))

      (defdescribe login-flow
        (allure/epic \"Authentication\")
        (allure/feature \"Login\")
        (allure/severity :critical)

        (it \"logs in with valid credentials\"
          (allure/step \"Navigate to login page\"
            (page/navigate page \"https://example.com/login\"))
          (allure/step \"Enter credentials\"
            (allure/parameter \"username\" \"admin\")
            (locator/fill (locator/locator page \"#username\") \"admin\")
            (locator/fill (locator/locator page \"#password\") \"secret\"))
          (allure/step \"Submit and verify\"
            (locator/click (locator/locator page \"button[type=submit]\"))
            (allure/screenshot page \"After login\"))))"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [lazytest.core :as lt-core])
  (:import
   [com.microsoft.playwright Tracing Tracing$GroupOptions]
   [com.microsoft.playwright.options Location]
   [java.io File PrintWriter StringWriter Writer]
   [java.util UUID]))

;; =============================================================================
;; Tee Writer — writes to both a local capture and the parent writer
;; =============================================================================

(defn- tee-writer
  "Create a Writer that writes to both `local` and `parent`.
   Used by step capture so output goes to the step attachment AND
   bubbles up to the test-level log."
  ^Writer [^Writer local ^Writer parent]
  (proxy [Writer] []
    (write
      ([x]
       (cond
         (int? x)
         (do (.write local (int x)) (.write parent (int x)))
         (string? x)
         (do (.write local ^String x) (.write parent ^String x))
         :else
         (do (.write local ^chars x) (.write parent ^chars x))))
      ([x off len]
       (if (string? x)
         (do (.write local ^String x (int off) (int len))
             (.write parent ^String x (int off) (int len)))
         (do (.write local ^chars x (int off) (int len))
             (.write parent ^chars x (int off) (int len))))))
    (flush []
      (.flush local)
      (.flush parent))
    (close []
      (.flush local)
      (.flush parent))))

;; =============================================================================
;; Reporter Detection — allows fixtures to auto-trace under Allure
;; =============================================================================

(def ^:private -reporter-active?
  "Atom set to true when the Allure reporter is running.
   Fixtures (e.g. with-page) check this to auto-enable Playwright
   tracing and HAR recording — zero configuration needed in tests."
  (atom false))

(defn reporter-active?
  "Returns true when the Allure reporter is active (i.e. we're
   generating a report). Fixtures use this to auto-enable tracing."
  []
  @-reporter-active?)

(defn set-reporter-active!
  "Called by the Allure reporter at begin/end of test run."
  [active?]
  (reset! -reporter-active? (boolean active?)))

;; =============================================================================
;; Context — bound by the Allure reporter during test execution
;; =============================================================================

(def ^:dynamic *context*
  "Dynamic var holding the current test's Allure context atom during
   execution. Bound by the reporter's `wrap-try-test-case`. nil when
   not running under the Allure reporter.

   The atom contains:
     {:labels       [{:name \"epic\" :value \"Auth\"} ...]
      :links        [{:name \"BUG-1\" :url \"...\" :type \"issue\"} ...]
      :parameters   [{:name \"browser\" :value \"chromium\"} ...]
      :attachments  [{:name \"screenshot\" :source \"uuid.png\" :type \"image/png\"} ...]
      :steps        []   ;; completed top-level steps
      :step-stack   []   ;; stack for nesting (open steps in progress)
      :description  nil} ;; markdown description"
  nil)

(def ^:dynamic *output-dir*
  "Dynamic var holding the allure-results output directory (java.io.File).
   Bound by the reporter alongside *context*."
  nil)

(def ^:dynamic *page*
  "Dynamic var holding the current Playwright Page instance for automatic
    step screenshots. When non-nil, every lambda step automatically captures
    a \"Post: <step>\" screenshot after execution. Bind alongside the
    test-fixtures `*page*`:

     (binding [allure/*page* page] (f))

   When nil (default), no automatic screenshots are taken."
  nil)

(def ^:dynamic *trace-path*
  "Dynamic var holding the java.io.File where the Playwright trace zip
   will be written. Bound by `with-traced-page`. The Allure reporter
   captures this path and attaches the trace file (with MIME type
   application/vnd.allure.playwright-trace) after the test completes.
   When nil (default), no trace is captured."
  nil)

(def ^:dynamic *har-path*
  "Dynamic var holding the java.io.File where the HAR (HTTP Archive)
   will be written. Bound by `with-traced-page`. The HAR is written
   when the BrowserContext closes. The Allure reporter captures this
   path and attaches the HAR file as a download link after the test
   completes. When nil (default), no HAR is captured."
  nil)

(def ^:dynamic *test-title*
  "Dynamic var holding the current test's display title.
   Bound by the reporter's `wrap-try-test-case` to the test case
   identifier (e.g. \"captures screenshot and attaches to report\").
   Used by test fixtures for the Playwright trace title."
  nil)

(def ^:dynamic *tracing*
  "Dynamic var holding the current Playwright Tracing object.
   Bound by test fixtures (with-page, with-traced-page, with-api-tracing)
   when tracing is active. When bound, `step*` automatically calls
   tracing.group()/groupEnd() so steps appear as groups in the
   Playwright Trace Viewer."
  nil)

(def ^:dynamic *test-out*
  "Dynamic var holding the test-level Writer for stdout accumulation.
   Bound by the reporter's `wrap-try-test-case` to a per-test
   StringWriter. Step capture tees to this so stdout flows to both
   the step attachment AND the test-level system-out."
  nil)

(def ^:dynamic *test-err*
  "Dynamic var holding the test-level Writer for stderr accumulation.
   Same as *test-out* but for *err*."
  nil)

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- uuid-str
  ^String []
  (str (UUID/randomUUID)))

(defn- epoch-ms
  ^long []
  (System/currentTimeMillis))

(defn- with-context
  "Apply f to the context atom if *context* is bound. Returns nil otherwise."
  [f]
  (when-let [ctx *context*]
    (f ctx)))

(defn- add-label!
  [name value]
  (with-context
    (fn [ctx]
      (swap! ctx update :labels conj {:name name :value value}))))

(defn- stack->steps-path
  "Build the path to the :steps vector of the step at the top of the
   stack. For a stack [s0 s1 s2], returns [:steps s0 :steps s1 :steps s2 :steps].
   For an empty stack, returns [:steps]."
  [stack]
  (vec (concat (mapcat (fn [idx] [:steps idx]) stack) [:steps])))

(defn- stack->step-path
  "Build the path to the step map at the top of the stack.
   For a stack [s0 s1 s2], returns [:steps s0 :steps s1 :steps s2].
   Stack must not be empty."
  [stack]
  (vec (mapcat (fn [idx] [:steps idx]) stack)))

;; =============================================================================
;; Metadata — Labels
;; =============================================================================

(defn epic
  "Set the epic label for this test."
  [value]
  (add-label! "epic" value))

(defn feature
  "Set the feature label for this test."
  [value]
  (add-label! "feature" value))

(defn story
  "Set the story label for this test."
  [value]
  (add-label! "story" value))

(defn severity
  "Set the severity label. Level should be one of:
   :blocker :critical :normal :minor :trivial"
  [level]
  (add-label! "severity" (name level)))

(defn owner
  "Set the test owner."
  [value]
  (add-label! "owner" value))

(defn tag
  "Add a tag label."
  [value]
  (add-label! "tag" value))

;; =============================================================================
;; Description
;; =============================================================================

(defn description
  "Set the test description (markdown supported)."
  [text]
  (with-context
    (fn [ctx]
      (swap! ctx assoc :description text))))

;; =============================================================================
;; Links
;; =============================================================================

(defn link
  "Add a link to the test report."
  [name url]
  (with-context
    (fn [ctx]
      (swap! ctx update :links conj {:name name :url url :type "custom"}))))

(defn issue
  "Add an issue link."
  [name url]
  (with-context
    (fn [ctx]
      (swap! ctx update :links conj {:name name :url url :type "issue"}))))

(defn tms
  "Add a test management system link."
  [name url]
  (with-context
    (fn [ctx]
      (swap! ctx update :links conj {:name name :url url :type "tms"}))))

;; =============================================================================
;; Parameters
;; =============================================================================

(defn parameter
  "Add a parameter to the test or current step."
  [name value]
  (with-context
    (fn [ctx]
      (let [stack (:step-stack @ctx)]
        (if (empty? stack)
          ;; Top-level parameter: add to test
          (swap! ctx update :parameters conj {:name name :value (str value)})
          ;; Inside a step: add to the current step
          (let [step-path (stack->step-path stack)]
            (swap! ctx update-in (conj step-path :parameters)
              (fnil conj []) {:name name :value (str value)})))))))

;; =============================================================================
;; Attachments
;; =============================================================================

(defn attach
  "Attach string content to the test report.
   `content-type` is a MIME type, e.g. \"text/plain\", \"application/json\"."
  [att-name content content-type]
  (with-context
    (fn [ctx]
      (when-let [^File dir *output-dir*]
        (let [ext (case content-type
                    "text/plain"       "txt"
                    "application/json" "json"
                    "text/html"        "html"
                    "text/csv"         "csv"
                    "text/xml"         "xml"
                    "txt")
              filename (str (uuid-str) "-attachment." ext)
              att-file (io/file dir filename)]
          (spit att-file content)
          (let [attachment {:name att-name :source filename :type content-type}
                stack (:step-stack @ctx)]
            (if (empty? stack)
              (swap! ctx update :attachments conj attachment)
              (let [step-path (stack->step-path stack)]
                (swap! ctx update-in (conj step-path :attachments)
                  (fnil conj []) attachment)))))))))

(defn attach-bytes
  "Attach binary content to the test report.
   `content-type` is a MIME type, e.g. \"image/png\", \"application/pdf\"."
  [att-name ^bytes bytes content-type]
  (with-context
    (fn [ctx]
      (when-let [^File dir *output-dir*]
        (let [ext (case content-type
                    "image/png"        "png"
                    "image/jpeg"       "jpg"
                    "image/gif"        "gif"
                    "image/svg+xml"    "svg"
                    "application/pdf"  "pdf"
                    "bin")
              filename (str (uuid-str) "-attachment." ext)
              att-file (io/file dir filename)]
          (with-open [out (io/output-stream att-file)]
            (.write out bytes))
          (let [attachment {:name att-name :source filename :type content-type}
                stack (:step-stack @ctx)]
            (if (empty? stack)
              (swap! ctx update :attachments conj attachment)
              (let [step-path (stack->step-path stack)]
                (swap! ctx update-in (conj step-path :attachments)
                  (fnil conj []) attachment)))))))))

(defn attach-file
  "Attach a file from disk to the test report.
   `att-name` is the display name. `source-path` is the path to the file.
   `content-type` is the MIME type."
  [att-name ^String source-path content-type]
  (with-context
    (fn [ctx]
      (when-let [^java.io.File dir *output-dir*]
        (let [src-file (io/file source-path)]
          (when (.exists src-file)
            (let [ext (case content-type
                        "video/webm"       "webm"
                        "video/mp4"        "mp4"
                        "image/png"        "png"
                        "image/jpeg"       "jpg"
                        "application/pdf"  "pdf"
                        "bin")
                  filename (str (uuid-str) "-attachment." ext)
                  att-file (io/file dir filename)]
              (io/copy src-file att-file)
              (let [attachment {:name att-name :source filename :type content-type}
                    stack (:step-stack @ctx)]
                (if (empty? stack)
                  (swap! ctx update :attachments conj attachment)
                  (let [step-path (stack->step-path stack)]
                    (swap! ctx update-in (conj step-path :attachments)
                      (fnil conj []) attachment)))))))))))

(defn screenshot
  "Take a Playwright screenshot and attach it to the report.
   `pg` is a Playwright Page instance. `att-name` is the display name."
  [pg att-name]
  (let [img-bytes (page/screenshot pg)]
    (when (bytes? img-bytes)
      (attach-bytes att-name img-bytes "image/png"))))

;; =============================================================================
;; Steps
;; =============================================================================

(defn- resolve-source-file
  "Resolve a classpath-relative file path (e.g. \"com/blockether/spel/smoke_test.clj\")
   to a project-relative path (e.g. \"test/com/blockether/spel/smoke_test.clj\") by
   checking directories listed in PLAYWRIGHT_JAVA_SRC. Falls back to the original
   path when the file cannot be found in any source directory."
  ^String [^String classpath-file]
  (let [src-dirs (str/split (or (System/getenv "PLAYWRIGHT_JAVA_SRC") "src:test:dev")
                   #"[;:]")]
    (or (some (fn [dir]
                (let [full (str dir "/" classpath-file)]
                  (when (.exists (File. full))
                    full)))
          src-dirs)
      classpath-file)))

(defn- make-group-options
  "Build a Tracing$GroupOptions with source location when loc-map is
   provided. Resolves the classpath-relative file path against
   PLAYWRIGHT_JAVA_SRC directories so the Trace Viewer can find the
   source content in the captured trace. Returns nil when no location
   is available."
  ^Tracing$GroupOptions [loc-map]
  (when-let [{:keys [file line]} loc-map]
    (when (and file line)
      (let [resolved (resolve-source-file (str file))]
        (doto (Tracing$GroupOptions.)
          (.setLocation (doto (Location. resolved)
                          (.setLine (int line)))))))))

(defn step*
  "Internal function backing the `step` macro. Prefer the macro.

   Three arities:
   - (step* name)              — marker step (records a named checkpoint, no body)
   - (step* name f)            — lambda step (executes f, records timing and status)
   - (step* name f loc-map)    — lambda step with source location override for
                                  Playwright Trace Viewer (loc-map = {:file ... :line ...})

   Does NOT take screenshots. Use `ui-step` for steps that need
   before/after screenshots, or call `screenshot` explicitly."
  ([step-name]
   ;; Marker step — no body, instant duration
   (with-context
     (fn [ctx]
       (let [now (epoch-ms)
             marker {:name   step-name
                     :status "passed"
                     :start  now
                     :stop   now
                     :steps  []
                     :attachments []
                     :parameters  []}
             stack (:step-stack @ctx)
             parent-steps-path (stack->steps-path stack)]
         (swap! ctx update-in parent-steps-path (fnil conj []) marker)))))
  ([step-name f]
   (step* step-name f nil))
  ([step-name f loc-map]
   ;; Lambda step — execute body, track timing and nesting
   (if-not *context*
     ;; No context → just execute the function, but still group for traces
     (if-let [^Tracing tracing *tracing*]
       (let [opts (make-group-options loc-map)]
         (try
           (if opts
             (.group tracing step-name opts)
             (.group tracing step-name))
           (f)
           (finally
             (.groupEnd tracing))))
       (f))
     (let [ctx *context*
           ^Tracing tracing *tracing*
           start (epoch-ms)
           ;; Create the step skeleton
           new-step {:name   step-name
                     :status "passed"
                     :start  start
                     :stop   start
                     :steps  []
                     :attachments []
                     :parameters  []}
           ;; Determine where to place this step (parent's :steps vector)
           stack (:step-stack @ctx)
           parent-steps-path (stack->steps-path stack)
           ;; Add step to parent's steps and get its index
           _ (swap! ctx update-in parent-steps-path (fnil conj []) new-step)
           new-idx (dec (count (get-in @ctx parent-steps-path)))
           ;; Push this step's index onto the nesting stack
           _ (swap! ctx update :step-stack conj new-idx)
           ;; Per-step stdout/stderr capture.
           ;; Each step gets its own StringWriter for DIRECT output only.
           ;; Steps tee to *test-out*/*test-err* (test-level writers) for
           ;; the full log, bypassing parent steps to avoid duplicate markers.
           out-sw (StringWriter.)
           err-sw (StringWriter.)
           ^Writer tee-out (if *test-out* (tee-writer out-sw *test-out*) out-sw)
           ^Writer tee-err (if *test-err* (tee-writer err-sw *test-err*) err-sw)
           ;; Build GroupOptions with source location if available
           opts (make-group-options loc-map)]
       ;; Mirror step as a trace group in the Playwright Trace Viewer
       (when tracing
         (try
           (if opts
             (.group tracing step-name opts)
             (.group tracing step-name))
           (catch Exception _)))
       (try
         (let [result (binding [*out* (PrintWriter. tee-out true)
                                *err* (PrintWriter. tee-err true)]
                        (f))]
            ;; Convert captured stdout lines into marker sub-steps (inline log)
           (doseq [line (str/split-lines (str out-sw))]
             (when-not (str/blank? line)
               (step* (str "⏵ " line))))
             ;; Stderr lines as warning-prefixed marker sub-steps
           (doseq [line (str/split-lines (str err-sw))]
             (when-not (str/blank? line)
               (step* (str "⚠ " line))))
            ;; Success — finalize timing
           (let [stop (epoch-ms)
                 step-path (stack->step-path (:step-stack @ctx))]
             (swap! ctx update-in step-path assoc
               :status "passed"
               :stop stop))
           result)
         (catch Throwable t
           ;; Convert captured output into markers even on failure
           (doseq [line (str/split-lines (str out-sw))]
             (when-not (str/blank? line)
               (step* (str "⏵ " line))))
           (doseq [line (str/split-lines (str err-sw))]
             (when-not (str/blank? line)
               (step* (str "⚠ " line))))
            ;; Failure — mark step as failed/broken
           (let [stop (epoch-ms)
                 step-path (stack->step-path (:step-stack @ctx))
                 status (if (instance? clojure.lang.ExceptionInfo t)
                          "failed"
                          "broken")]
             (swap! ctx update-in step-path assoc
               :status status
               :stop stop
               :statusDetails {:message (.getMessage t)}))
           (throw t))
         (finally
           ;; Always close the trace group and pop the allure stack
           (when tracing
             (try (.groupEnd tracing) (catch Exception _)))
           (swap! ctx update :step-stack pop)))))))

(defmacro step
  "Add a step to the test report.

   Two arities:
   - (step name)              — marker step (checkpoint, no body)
   - (step name & body)       — lambda step (executes body, timed, nestable)

   Steps can nest arbitrarily:

     (allure/step \"Login\"
       (allure/step \"Enter username\"
         (locator/fill username-input \"admin\"))
       (allure/step \"Enter password\"
         (locator/fill password-input \"secret\"))
       (allure/step \"Click submit\"
         (locator/click submit-btn)))

   All step calls are no-ops when not running under the Allure reporter.

   When Playwright tracing is active, the step's source location is
   captured at macro expansion time (via `*file*` and `&form` metadata)
   and passed to `Tracing.group(name, GroupOptions)` so the Trace Viewer
   links to the test source file instead of allure.clj internals."
  ([step-name]
   `(step* ~step-name))
  ([step-name & body]
   (let [loc-line (-> &form meta :line)]
     `(step* ~step-name (fn [] ~@body)
        {:file ~*file* :line ~loc-line}))))

;; =============================================================================
;; UI Step — before/after screenshots as child steps
;; =============================================================================

(defmacro ui-step
  "Execute a UI step with automatic before/after screenshots.

   Creates a parent step with two nested child steps:
   - \"Before: <name>\" — screenshot captured before the action
   - \"After: <name>\"  — screenshot captured after the action

   Requires `*page*` to be bound (typically done by the reporter's
   `with-traced-page` or test fixtures).

   When Playwright tracing is active (`*trace-path*` is bound), the
   explicit screenshots are skipped — the trace already captures visual
   state on every action, so the screenshots would only add noise to
   the trace timeline without providing additional value.

   The step hierarchy in the Allure report (without tracing):

     ✓ Login to application
       ├── Before: Login to application (screenshot)
       ├── ... (your actions)
       └── After: Login to application  (screenshot)

   Usage:

     (allure/ui-step \"Fill login form\"
       (locator/fill username-input \"admin\")
       (locator/fill password-input \"secret\")
       (locator/click submit-btn))

   Falls back to a regular step when `*page*` is not bound.
   No-op when not running under the Allure reporter."
  [step-name & body]
  (let [loc-line (-> &form meta :line)]
    `(step* ~step-name
       (fn []
         (when (and *page* (not *trace-path*))
           (step* (str "Before: " ~step-name)
             (fn [] (screenshot *page* (str "Before: " ~step-name)))))
         (try
           (let [result# (do ~@body)]
             (when (and *page* (not *trace-path*))
               (step* (str "After: " ~step-name)
                 (fn [] (screenshot *page* (str "After: " ~step-name)))))
             result#)
           (catch Throwable t#
             (when (and *page* (not *trace-path*))
               (try
                 (step* (str "Error: " ~step-name)
                   (fn [] (screenshot *page* (str "Error: " ~step-name))))
                 (catch Throwable ~'_)))
             (throw t#))))
       {:file ~*file* :line ~loc-line})))

;; =============================================================================
;; API Step — auto-attach HTTP request/response details
;; =============================================================================

(defn- pretty-json
  "Minimal JSON pretty-printer for display. Dependency-free.
   Reformats well-formed JSON with 2-space indentation.
   Returns `s` as-is if it doesn't look like JSON."
  ^String [^String s]
  (if (or (nil? s) (< (.length s) 2)
        (not (or (= \{ (.charAt s 0)) (= \[ (.charAt s 0)))))
    s
    (let [sb  (StringBuilder.)
          len (.length s)]
      (loop [i 0, depth 0, in-str false, esc false]
        (if (>= i len)
          (str sb)
          (let [c (.charAt s i)]
            (cond
              esc
              (do (.append sb c) (recur (inc i) depth in-str false))

              (and in-str (= c \\))
              (do (.append sb c) (recur (inc i) depth true true))

              (and in-str (= c \"))
              (do (.append sb c) (recur (inc i) depth false false))

              in-str
              (do (.append sb c) (recur (inc i) depth true false))

              (= c \")
              (do (.append sb c) (recur (inc i) depth true false))

              (or (= c \{) (= c \[))
              (let [d   (inc depth)
                    ;; peek next non-whitespace char for empty containers
                    nxt (loop [j (inc i)]
                          (when (< j len)
                            (let [nc (.charAt s j)]
                              (if (Character/isWhitespace nc) (recur (inc j)) nc))))]
                (if (or (and (= c \{) (= nxt \}))
                      (and (= c \[) (= nxt \])))
                  ;; empty container — keep compact
                  (do (.append sb c) (recur (inc i) d false false))
                  (do (.append sb c) (.append sb \newline)
                      (dotimes [_ (* 2 d)] (.append sb \space))
                      (recur (inc i) d false false))))

              (or (= c \}) (= c \]))
              (let [d (dec depth)]
                (.append sb \newline)
                (dotimes [_ (* 2 d)] (.append sb \space))
                (.append sb c)
                (recur (inc i) d false false))

              (= c \,)
              (do (.append sb c) (.append sb \newline)
                  (dotimes [_ (* 2 depth)] (.append sb \space))
                  (recur (inc i) depth false false))

              (= c \:)
              (do (.append sb c) (.append sb \space)
                  (recur (inc i) depth false false))

              (Character/isWhitespace c)
              (recur (inc i) depth false false)

              :else
              (do (.append sb c) (recur (inc i) depth false false)))))))))

(defn- format-response-headers
  "Format a response headers map as an HTTP-style header block."
  [status status-text headers]
  (let [hdr-lines (mapv (fn [[k v]] (str k ": " v)) (sort headers))]
    (str/join "\n" (into [(str "HTTP " status " " status-text)] hdr-lines))))

(defn attach-api-response!
  "Attach APIResponse metadata to the current allure step as parameters,
   attachments, and console log output. Safe — swallows all errors.

   Captures:
     - Parameters:  status, status-text, url, ok?, content-type, content-length
     - Attachments: \"Response Headers\" (full HTTP header block),
                    \"Response Body\" (pretty-printed JSON, raw XML/HTML/text)
     - Console log: status line, url, content-type, body preview
                    (shows as ⏵ marker sub-steps in the Allure report)

   This function is public because it is referenced by the `api-step` macro
   which expands in the calling namespace."
  [resp]
  (try
    (when resp
      (let [^com.microsoft.playwright.APIResponse r resp
            status      (.status r)
            status-text (.statusText r)
            url         (.url r)
            ok?         (.ok r)
            headers     (into {} (.headers r))
            body        (try (.text r) (catch Throwable _ nil))
            ct          (get headers "content-type")
            cl          (get headers "content-length")]

        ;; ── Console log (captured by step* as ⏵ marker sub-steps) ──
        (println (str "← " status " " status-text))
        (println (str "  " url))
        (when ct (println (str "  Content-Type: " ct)))
        (when cl (println (str "  Content-Length: " cl " bytes")))
        (when (and body (> (count body) 0))
          (let [preview (if (> (count body) 120)
                          (str (subs body 0 120) "…")
                          body)]
            (println (str "  Body: " preview))))

        ;; ── Parameters ──
        (parameter "status" status)
        (parameter "status-text" status-text)
        (parameter "url" url)
        (parameter "ok?" ok?)
        (when ct (parameter "content-type" ct))
        (when cl (parameter "content-length" cl))

        ;; ── Attachments ──
        ;; Full response headers
        (attach "Response Headers"
          (format-response-headers status status-text headers)
          "text/plain")

        ;; Response body (pretty-printed if JSON)
        (when (and body (pos? (count body)))
          (let [mime (cond
                       (and ct (re-find #"json" ct)) "application/json"
                       (and ct (re-find #"xml" ct))  "text/xml"
                       (and ct (re-find #"html" ct)) "text/html"
                       :else                          "text/plain")
                display-body (if (= mime "application/json")
                               (pretty-json body)
                               body)]
            (attach "Response Body" display-body mime)))))
    (catch Throwable _ nil)))

(defmacro api-step
  "Execute an API step with automatic request/response logging.

   Wraps the body in an allure step. If the body returns a Playwright
   APIResponse, automatically attaches status, url, content-type as
   parameters and the response body as an attachment to the report.

   Usage:

     (allure/api-step \"Create user\"
       (api/api-post ctx \"/users\"
         {:data \"{\\\"name\\\": \\\"Alice\\\"}\"
          :headers {\"Content-Type\" \"application/json\"}}))

     ;; Parameters in report:
     ;;   status       → 201
     ;;   url          → https://api.example.com/users
     ;;   ok?          → true
     ;;   content-type → application/json
     ;; Attachment:
     ;;   Response Body (application/json)

   Works with any expression — only attaches metadata when the result
   is an APIResponse instance. No-op when not running under the Allure
   reporter."
  [step-name & body]
  (let [loc-line (-> &form meta :line)]
    `(step* ~step-name
       (fn []
         (let [result# (do ~@body)]
           (when (instance? com.microsoft.playwright.APIResponse result#)
             (attach-api-response! result#))
           result#))
       {:file ~*file* :line ~loc-line})))

;; =============================================================================
;; Lazytest Re-exports — single-require with auto Allure steps
;; =============================================================================
;;
;; Re-exports the full `lazytest.core` public API so test files need only:
;;
;;   (:require [com.blockether.spel.allure :refer [defdescribe describe it expect ...]])
;;
;; instead of importing from both `lazytest.core` and this namespace.
;;
;; Enhanced macros:
;;
;;   describe  — injects an `around` hook that wraps each test execution
;;               in an Allure step named after the suite's doc string.
;;               Nested describes produce nested steps automatically.
;;
;;   it        — wraps the test body in an Allure step named after the
;;               test case's doc string.
;;
;;   expect    — wraps the assertion in an Allure step named after the
;;               source expression (or custom message).
;;
;;   expect-it — combines `it` + `expect` with auto-stepping.
;;
;; Together they produce a fully nested Allure step hierarchy:
;;
;;   (defdescribe my-test
;;     (describe "login"
;;       (describe "form validation"
;;         (it "rejects empty email"
;;           (expect (= "Required" (get-error-text)))))))
;;
;;   Allure steps:
;;     ✓ login
;;       └── ✓ form validation
;;             └── ✓ rejects empty email
;;                   └── ✓ expect: (= "Required" (get-error-text))
;;
;; When not running under the Allure reporter, all macros delegate
;; directly to `lazytest.core` with zero overhead.

;; ---------------------------------------------------------------------------
;; Test definition
;; ---------------------------------------------------------------------------

(defmacro defdescribe
  "Re-export of `lazytest.core/defdescribe`.
   Defines a top-level test suite var.

   Does not create an Allure step — the defdescribe name is already
   visible as the top-level suite label in the report."
  {:arglists '([test-name & children]
               [test-name doc? attr-map? & children])}
  [& args]
  `(lt-core/defdescribe ~@args))

(defmacro describe
  "Like `lazytest.core/describe` with automatic Allure step nesting.

   Injects an `around` hook so each test case executed within this
   suite is wrapped in an Allure step named after the doc string.
   Nested describes produce nested steps.

   When not running under the Allure reporter, the step call is a
   no-op — just `(f)` — so there is zero runtime overhead."
  {:arglists '([doc & children]
               [doc attr-map? & children])}
  [doc & body]
  (let [[attr-map children] (if (map? (first body))
                              [(first body) (rest body)]
                              [nil body])
        step-name (if (string? doc) doc `(str ~doc))
        loc-line  (-> &form meta :line)]
    (if attr-map
      `(lt-core/describe ~doc ~attr-map
         (lt-core/around [f#]
           (step* ~step-name (fn [] (f#))
             {:file ~*file* :line ~loc-line}))
         ~@children)
      `(lt-core/describe ~doc
         (lt-core/around [f#]
           (step* ~step-name (fn [] (f#))
             {:file ~*file* :line ~loc-line}))
         ~@children))))

(defmacro it
  "Like `lazytest.core/it` with automatic Allure step wrapping.

   Wraps the test body in an Allure step named after the doc string,
   providing a named container for the expect steps within.

   When not running under the Allure reporter, the step call is a
   no-op — just `(body)` — so there is zero runtime overhead."
  {:arglists '([doc & body]
               [doc attr-map? & body])}
  [doc & body]
  (let [[attr-map body] (if (map? (first body))
                          [(first body) (rest body)]
                          [nil body])
        step-name (if (string? doc) doc `(str ~doc))
        loc-line  (-> &form meta :line)]
    (if attr-map
      `(lt-core/it ~doc ~attr-map
         (step* ~step-name (fn [] ~@body)
           {:file ~*file* :line ~loc-line}))
      `(lt-core/it ~doc
         (step* ~step-name (fn [] ~@body)
           {:file ~*file* :line ~loc-line})))))

(defmacro expect
  "Drop-in replacement for `lazytest.core/expect` that automatically
   creates an Allure step for each expectation.

   When running under the Allure reporter, each `expect` call renders
   as a named step in the report with its own pass/fail status. The
   step name is derived from the source expression (or the custom
   message when provided).

   When not running under the Allure reporter, delegates directly to
   `lazytest.core/expect` with zero overhead.

   With custom message:

     (expect (= 1 1) \"numbers are equal\")
     ;; Step name: \"expect: numbers are equal\""
  ([expr]
   (let [form-str (pr-str expr)
         loc-line (-> &form meta :line)]
     `(step* ~(str "expect: " form-str)
        (fn [] (lt-core/expect ~expr))
        {:file ~*file* :line ~loc-line})))
  ([expr msg]
   (let [form-str (pr-str expr)
         loc-line (-> &form meta :line)]
     `(let [msg# ~msg]
        (step* (if msg# (str "expect: " msg#) ~(str "expect: " form-str))
          (fn [] (lt-core/expect ~expr msg#))
          {:file ~*file* :line ~loc-line})))))

(defmacro expect-it
  "Like `lazytest.core/expect-it` with stepped expectations.
   Shorthand for `(it doc (expect expr))`.

   Uses `lt-core/it` directly (no extra it-level step) since
   expect-it has exactly one assertion — the expect step is
   sufficient."
  {:arglists '([doc expr]
               [doc attr-map? expr])}
  [doc & body]
  (let [[attr-map exprs] (if (map? (first body))
                           [(first body) (rest body)]
                           [nil body])]
    (assert (= 1 (count exprs)) "expect-it takes 1 expr")
    (let [expr (first exprs)]
      (if attr-map
        `(lt-core/it ~doc ~attr-map (expect ~expr ~doc))
        `(lt-core/it ~doc (expect ~expr ~doc))))))

;; ---------------------------------------------------------------------------
;; Test definition aliases
;; ---------------------------------------------------------------------------

(defmacro context
  "Alias for `describe` — includes automatic Allure step nesting."
  {:arglists '([doc & children]
               [doc attr-map? & children])}
  [doc & body]
  `(describe ~doc ~@body))

(defmacro specify
  "Alias for `it` — includes automatic Allure step wrapping."
  {:arglists '([doc & body]
               [doc attr-map? & body])}
  [doc & body]
  `(it ~doc ~@body))

(defmacro should
  "Like `lazytest.core/should` with stepped expectations.
   Alias for `expect`."
  ([expr]   `(expect ~expr))
  ([expr msg] `(expect ~expr ~msg)))

;; ---------------------------------------------------------------------------
;; Hooks
;; ---------------------------------------------------------------------------

(defmacro before
  "Re-export of `lazytest.core/before`.
   Runs body once before all nested suites and test cases."
  {:arglists '([& body])}
  [& body]
  `(lt-core/before ~@body))

(defmacro before-each
  "Re-export of `lazytest.core/before-each`.
   Runs body before each nested test case."
  {:arglists '([& body])}
  [& body]
  `(lt-core/before-each ~@body))

(defmacro after
  "Re-export of `lazytest.core/after`.
   Runs body once after all nested suites and test cases."
  {:arglists '([& body])}
  [& body]
  `(lt-core/after ~@body))

(defmacro after-each
  "Re-export of `lazytest.core/after-each`.
   Runs body after each nested test case."
  {:arglists '([& body])}
  [& body]
  `(lt-core/after-each ~@body))

(defmacro around
  "Re-export of `lazytest.core/around`.
   Wraps nested execution in a function, useful for `binding` forms."
  {:arglists '([[f] & body])}
  [param & body]
  `(lt-core/around ~param ~@body))

;; ---------------------------------------------------------------------------
;; Assertion helpers (function re-exports)
;; ---------------------------------------------------------------------------

(def ^{:doc "Re-export of `lazytest.core/throws?`.
   Calls f with no arguments; returns true if it throws an instance of
   class c. Any other exception will be re-thrown."
       :arglists '([c f])}
  throws?
  lt-core/throws?)

(def ^{:doc "Re-export of `lazytest.core/throws-with-msg?`.
   Calls f with no arguments; catches exceptions of class c. Returns
   true if the exception message matches the regex re."
       :arglists '([c re f])}
  throws-with-msg?
  lt-core/throws-with-msg?)

(def ^{:doc "Re-export of `lazytest.core/causes?`.
   Calls f with no arguments; returns true if any exception in the
   cause chain is an instance of class c."
       :arglists '([c f])}
  causes?
  lt-core/causes?)

(def ^{:doc "Re-export of `lazytest.core/causes-with-msg?`.
   Calls f with no arguments; returns true if any exception in the
   cause chain is an instance of class c with a message matching re."
       :arglists '([c re f])}
  causes-with-msg?
  lt-core/causes-with-msg?)

(def ^{:doc "Re-export of `lazytest.core/ok?`.
   Calls f with no arguments and discards its return value. Returns
   true if f does not throw. Useful for expressions that return false."
       :arglists '([f])}
  ok?
  lt-core/ok?)

(def ^{:doc "Re-export of `lazytest.core/set-ns-context!`.
   Add hooks to the namespace suite, instead of to a var or test suite."
       :arglists '([contexts])}
  set-ns-context!
  lt-core/set-ns-context!)

;; =============================================================================
;; Context Initialization (used by the reporter)
;; =============================================================================

(defn make-context
  "Create a fresh context map for a test case. Called by the reporter."
  []
  {:labels      []
   :links       []
   :parameters  []
   :attachments []
   :steps       []
   :step-stack  []
   :description nil})
