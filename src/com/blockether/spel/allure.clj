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
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Tracing]
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

(defn step*
  "Internal function backing the `step` macro. Prefer the macro.

   Two arities:
   - (step* name)      — marker step (records a named checkpoint, no body)
   - (step* name f)    — lambda step (executes f, records timing and status)

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
   ;; Lambda step — execute body, track timing and nesting
   (if-not *context*
     ;; No context → just execute the function, but still group for traces
     (if-let [^Tracing tracing *tracing*]
       (try
         (.group tracing step-name)
         (f)
         (finally
           (.groupEnd tracing)))
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
           ^Writer tee-err (if *test-err* (tee-writer err-sw *test-err*) err-sw)]
       ;; Mirror step as a trace group in the Playwright Trace Viewer
       (when tracing
         (try (.group tracing step-name) (catch Exception _)))
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

   All step calls are no-ops when not running under the Allure reporter."
  ([step-name]
   `(step* ~step-name))
  ([step-name & body]
   `(step* ~step-name (fn [] ~@body))))

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
           (throw t#))))))

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
  `(step* ~step-name
     (fn []
       (let [result# (do ~@body)]
         (when (instance? com.microsoft.playwright.APIResponse result#)
           (attach-api-response! result#))
         result#))))

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
