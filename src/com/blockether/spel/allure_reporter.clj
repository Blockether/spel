(ns com.blockether.spel.allure-reporter
  "Allure 3 reporter for Lazytest with embedded Playwright trace viewer.

   Writes JSON result files to allure-results/, then automatically generates
   the full HTML report to allure-report/ using Allure 3 CLI (pinned to 3.1.0
   via npx). The report embeds a local Playwright trace viewer so trace
   attachments load instantly without trace.playwright.dev.

   Usage:
     clojure -M:test --output com.blockether.spel.allure-reporter/allure
     clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

   Output directory defaults to allure-results/. Override with:
     -Dlazytest.allure.output=path/to/dir
     LAZYTEST_ALLURE_OUTPUT=path/to/dir"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure]
   [lazytest.expectation-failed :refer [ex-failed?]]
   [lazytest.reporters :refer [reporter-dispatch]]
   [lazytest.suite :as s]
   [lazytest.test-case :as tc])
  (:import
   [java.io File PrintWriter StringWriter]
   [java.net InetAddress]
   [java.security MessageDigest]
   [java.util UUID]))

;; =============================================================================
;; Run State
;; =============================================================================

(def ^:private run-state
  "Mutable state captured during the test run."
  (atom {}))

;; =============================================================================
;; Per-Test Output Capture (alter-var-root hack)
;; =============================================================================

(defn- wrap-try-test-case
  "Wraps try-test-case to capture *out*/*err* and bind the Allure
   in-test API context per test case. Also captures trace/HAR paths
   from the current dynamic bindings (set by with-traced-page fixture).

   When the test body does not create any explicit allure steps
   (via step/api-step/ui-step), auto-generates a single synthetic
   step from the test result so the Allure report never shows
   'No test steps information available'."
  [original-fn]
  (fn [tc]
    (let [out-sw      (StringWriter.)
          err-sw      (StringWriter.)
          ctx-atom    (atom (allure/make-context))
          ;; Capture trace/HAR paths (bound by with-traced-page fixture)
          trace-path  allure/*trace-path*
          har-path    allure/*har-path*
          start-ms    (System/currentTimeMillis)
          result      (binding [*out*                (PrintWriter. out-sw true)
                                *err*                (PrintWriter. err-sw true)
                                allure/*context*     ctx-atom
                                allure/*output-dir*  (:output-dir @run-state)
                                allure/*test-title*  (tc/identifier tc)
                                allure/*test-out*    out-sw
                                allure/*test-err*    err-sw]
                        (original-fn tc))
          stop-ms     (System/currentTimeMillis)
          ;; Auto-generate a step when the test has no explicit allure steps.
          ;; This ensures every test case shows at least one step in the
          ;; Allure report with proper status, timing, and failure details.
          _           (when (empty? (:steps @ctx-atom))
                        (let [tc-name (tc/identifier tc)
                              status  (case (:type result)
                                        :pass    "passed"
                                        :fail    (if (and (some? (:thrown result))
                                                       (not (ex-failed? (:thrown result))))
                                                   "broken"
                                                   "failed")
                                        :pending "skipped"
                                        "unknown")
                              ;; stdout lines → ⏵ marker sub-steps
                              out-str  (str out-sw)
                              out-subs (into []
                                         (comp (filter (complement str/blank?))
                                           (map (fn [line]
                                                  {:name   (str "⏵ " line)
                                                   :status "passed"
                                                   :start  stop-ms
                                                   :stop   stop-ms
                                                   :steps  []
                                                   :attachments []
                                                   :parameters  []})))
                                         (when-not (str/blank? out-str)
                                           (str/split-lines out-str)))
                              ;; stderr lines → ⚠ marker sub-steps
                              err-str  (str err-sw)
                              err-subs (into []
                                         (comp (filter (complement str/blank?))
                                           (map (fn [line]
                                                  {:name   (str "⚠ " line)
                                                   :status "passed"
                                                   :start  stop-ms
                                                   :stop   stop-ms
                                                   :steps  []
                                                   :attachments []
                                                   :parameters  []})))
                                         (when-not (str/blank? err-str)
                                           (str/split-lines err-str)))
                              ;; For failed tests: expected/actual/message as params
                              params   (cond-> []
                                         (:expected result)
                                         (conj {:name "expected" :value (pr-str (:expected result))})
                                         (some? (:actual result))
                                         (conj {:name "actual" :value (pr-str (:actual result))})
                                         (:message result)
                                         (conj {:name "message" :value (str (:message result))}))
                              auto-step (cond-> {:name         tc-name
                                                 :status       status
                                                 :start        start-ms
                                                 :stop         stop-ms
                                                 :steps        (into out-subs err-subs)
                                                 :attachments  []
                                                 :parameters   params}
                                          (= :fail (:type result))
                                          (assoc :statusDetails
                                            {:message (or (:message result)
                                                        (when-let [^Throwable t (:thrown result)]
                                                          (.getMessage t))
                                                        "Test failed")}))]
                          (swap! ctx-atom update :steps conj auto-step)))
          ctx-val     @ctx-atom]
      (cond-> (assoc result
                :system-out    (str out-sw)
                :system-err    (str err-sw)
                :allure/context ctx-val)
        trace-path (assoc :allure/trace-path (str trace-path))
        har-path   (assoc :allure/har-path   (str har-path))))))

(defn- install-output-capture!
  "Patches try-test-case for output capture. Skips if already patched
   (e.g., by the JUnit reporter running alongside)."
  []
  (when-not (:original-try-test-case @run-state)
    (let [original (deref #'tc/try-test-case)]
      (swap! run-state assoc :original-try-test-case original)
      (alter-var-root #'tc/try-test-case wrap-try-test-case))))

(defn- uninstall-output-capture!
  "Restores the original try-test-case function."
  []
  (when-let [original (:original-try-test-case @run-state)]
    (alter-var-root #'tc/try-test-case (constantly original))))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- hostname
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
    (catch Exception _ "localhost")))

(defn- uuid
  ^String []
  (str (UUID/randomUUID)))

(defn- md5-hex
  "MD5 hash of a string, returned as lowercase hex."
  ^String [^String s]
  (let [md (MessageDigest/getInstance "MD5")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn- stacktrace-str
  ^String [^Throwable t]
  (when t
    (let [sw (StringWriter.)
          pw (PrintWriter. sw)]
      (.printStackTrace t pw)
      (str sw))))

;; =============================================================================
;; JSON Emitter (no external deps)
;; =============================================================================

(defn- json-escape
  "Escape a string for JSON."
  ^String [^String s]
  (-> s
    (str/replace "\\" "\\\\")
    (str/replace "\"" "\\\"")
    (str/replace "\n" "\\n")
    (str/replace "\r" "\\r")
    (str/replace "\t" "\\t")))

(defn- ->json-pretty
  "Convert to JSON with basic indentation for readability."
  ^String [v]
  (let [indent (fn indent [v depth]
                 (let [depth (long depth)
                       pad (apply str (repeat (* depth 2) " "))
                       pad1 (apply str (repeat (* (inc depth) 2) " "))]
                   (cond
                     (nil? v)     "null"
                     (string? v)  (str "\"" (json-escape v) "\"")
                     (number? v)  (str v)
                     (boolean? v) (if v "true" "false")
                     (keyword? v) (indent (name v) depth)

                     (map? v)
                     (if (empty? v)
                       "{}"
                       (str "{\n"
                         (->> v
                           (map (fn [[k val]]
                                  (str pad1
                                    (indent (if (keyword? k) (name k) (str k)) (inc depth))
                                    ": "
                                    (indent val (inc depth)))))
                           (str/join ",\n"))
                         "\n" pad "}"))

                     (sequential? v)
                     (if (empty? v)
                       "[]"
                       (str "[\n"
                         (->> v
                           (map (fn [item] (str pad1 (indent item (inc depth)))))
                           (str/join ",\n"))
                         "\n" pad "]"))

                     :else (indent (str v) depth))))]
    (indent v 0)))

;; =============================================================================
;; Result Tree Walking (shared with JUnit reporter pattern)
;; =============================================================================

(defn- doc-str
  [doc]
  (cond
    (instance? clojure.lang.Namespace doc) (str (ns-name doc))
    (instance? clojure.lang.Var doc)       (str (:name (meta doc)))
    (and (some? doc)
      (not (str/blank? (str doc))))     (str doc)
    :else                                  nil))

(defn- ns-suite?
  [result]
  (and (s/suite-result? result)
    (= :lazytest/ns (-> result :source :type))))

(defn- collect-test-cases
  "Walk result tree depth-first, collecting leaf test case results.
   Each result is annotated with:
     ::path     - vector of describe/suite doc strings
     ::ns-name  - the namespace name string"
  [result path ns-name]
  (if (s/suite-result? result)
    (let [source-type (-> result :source :type)
          doc (doc-str (:doc result))
          new-ns (if (= :lazytest/ns source-type)
                   (or doc ns-name)
                   ns-name)
          new-path (if (and doc
                         (not= :lazytest/run source-type)
                         (not= :lazytest/ns source-type))
                     (conj path doc)
                     path)]
      (mapcat #(collect-test-cases % new-path new-ns) (:children result)))
    ;; Leaf test-case result
    [(assoc result ::path path ::ns-name ns-name)]))

(defn- ns-package
  ^String [^String ns-name]
  (let [idx (.lastIndexOf ns-name ".")]
    (if (pos? idx) (subs ns-name 0 idx) "")))

;; =============================================================================
;; Result Classification
;; =============================================================================

(defn- allure-status
  "Map Lazytest result type to Allure status string."
  ^String [tc]
  (case (:type tc)
    :pass    "passed"
    :fail    (if (and (some? (:thrown tc))
                   (not (ex-failed? (:thrown tc))))
               "broken"
               "failed")
    :pending "skipped"
    "unknown"))

;; =============================================================================
;; Allure Result Construction
;; =============================================================================

(defn- build-status-details
  "Build statusDetails map for failed/broken tests."
  [tc]
  (when (= :fail (:type tc))
    (let [^Throwable thrown (:thrown tc)
          msg (or (:message tc)
                (when thrown (.getMessage thrown))
                "Test failed")
          expected (pr-str (:expected tc))
          actual   (pr-str (:actual tc))
          trace    (stacktrace-str thrown)]
      (cond-> {:message (str msg
                          (when (:expected tc)
                            (str "\nExpected: " expected
                              "\nActual: " actual)))}
        trace (assoc :trace trace)))))

(defn- build-labels
  "Build labels array for a test case result."
  [tc]
  (let [ns-name  (::ns-name tc)
        path     (::path tc)
        pkg      (when ns-name (ns-package ns-name))
        sub      (first path)
        hn       (:hostname @run-state)]
    (cond-> []
      ns-name (conj {:name "suite" :value ns-name})
      pkg     (conj {:name "parentSuite" :value pkg})
      sub     (conj {:name "subSuite" :value sub})
      hn      (conj {:name "host" :value hn})
      true    (conj {:name "thread" :value "main"})
      true    (conj {:name "language" :value "clojure"})
      true    (conj {:name "framework" :value "lazytest"})
      pkg     (conj {:name "package" :value pkg})
      ns-name (conj {:name "testClass" :value ns-name})
      true    (conj {:name "testMethod" :value (tc/identifier tc)}))))

(defn- build-full-name
  "Build a stable fullName for historyId/testCaseId generation."
  ^String [tc]
  (let [ns-name (::ns-name tc)
        path    (::path tc)
        name    (tc/identifier tc)
        parts   (filterv some? (concat [ns-name] path [name]))]
    (str/join "." parts)))

(defn- build-display-name
  "Build a human-readable test name."
  ^String [tc]
  (let [path (::path tc)
        name (tc/identifier tc)]
    (if (seq path)
      (str (str/join " > " path) " > " name)
      name)))

(defn- write-attachment!
  "Write an attachment file, return the attachment metadata map, or nil."
  [output-dir content att-name]
  (when (and content (not (str/blank? content)))
    (let [att-uuid (uuid)
          filename (str att-uuid "-attachment.txt")
          att-file (io/file output-dir filename)]
      (spit att-file content)
      {:name att-name :source filename :type "text/plain"})))

(defn- copy-file-attachment!
  "Copy a file into the output dir and return an attachment metadata map.
   Returns nil if the source file doesn't exist or is empty."
  [^File output-dir ^String source-path att-name ^String mime-type ^String ext]
  (let [src (io/file source-path)]
    (when (and (.exists src) (pos? (.length src)))
      (let [att-uuid (uuid)
            filename (str att-uuid "-attachment" ext)
            dest     (io/file output-dir filename)]
        (io/copy src dest)
        {:name att-name :source filename :type mime-type}))))

(defn- strip-step-stack
  "Remove internal :step-stack from step trees (not part of Allure schema)."
  [steps]
  (mapv (fn [step]
          (-> step
            (dissoc :step-stack)
            (update :steps strip-step-stack)))
    steps))

(defn- build-result
  "Build a complete Allure result map for a single test case."
  [tc output-dir]
  (let [result-uuid (uuid)
        full-name   (build-full-name tc)
        duration-ns (long (or (:lazytest.runner/duration tc) 0))
        duration-ms (long (/ duration-ns 1e6))
        stop-ms     (+ (long (:start-ms @run-state 0)) duration-ms)
        ;; Use test-level start/stop approximation
        ;; Allure cares about relative ordering for timeline
        start-ms    (- stop-ms duration-ms)
        status-det  (build-status-details tc)
         ;; Write full test-level log (accumulated from all steps via tee-writer)
        out-att     (write-attachment! output-dir (:system-out tc) "Full stdout log")
        err-att     (write-attachment! output-dir (:system-err tc) "Full stderr log")
        ;; Copy trace/HAR files if present (from with-traced-page fixture)
        trace-att   (when-let [tp (:allure/trace-path tc)]
                      (copy-file-attachment! output-dir tp "Playwright Trace"
                        "application/vnd.allure.playwright-trace" ".zip"))
        har-att     (when-let [hp (:allure/har-path tc)]
                      (copy-file-attachment! output-dir hp "Network Activity (HAR)"
                        "application/json" ".har"))
        io-atts     (filterv some? [out-att err-att trace-att har-att])
        ;; In-test API context data
        ctx         (:allure/context tc)
        ctx-labels  (when ctx (:labels ctx))
        ctx-links   (when ctx (:links ctx))
        ctx-params  (when ctx (:parameters ctx))
        ctx-atts    (when ctx (:attachments ctx))
        ctx-steps   (when ctx (strip-step-stack (:steps ctx)))
        ctx-desc    (when ctx (:description ctx))
        ;; Merge reporter labels with in-test API labels
        all-labels  (into (build-labels tc) ctx-labels)
        all-links   (or (seq ctx-links) [])
        all-params  (or (seq ctx-params) [])
        all-atts    (into (vec io-atts) ctx-atts)]
    (cond-> {:uuid        result-uuid
             :historyId   (md5-hex full-name)
             :testCaseId  (md5-hex full-name)
             :fullName    full-name
             :name        (build-display-name tc)
             :status      (allure-status tc)
             :stage       "finished"
             :start       start-ms
             :stop        stop-ms
             :labels      all-labels
             :parameters  all-params
             :links       all-links}
      status-det        (assoc :statusDetails status-det)
      (seq all-atts)    (assoc :attachments all-atts)
      (seq ctx-steps)   (assoc :steps ctx-steps)
      ctx-desc          (assoc :description ctx-desc))))

;; =============================================================================
;; Supplementary Files
;; =============================================================================

(defn- write-environment-properties!
  "Write environment.properties to the allure output directory."
  [^File output-dir]
  (let [props [["java.version"    (System/getProperty "java.version")]
               ["java.vendor"     (System/getProperty "java.vendor")]
               ["os.name"         (System/getProperty "os.name")]
               ["os.arch"         (System/getProperty "os.arch")]
               ["os.version"      (System/getProperty "os.version")]
               ["clojure.version" (clojure-version)]
               ["file.encoding"   (System/getProperty "file.encoding")]]
        content (->> props
                  (map (fn [[k v]] (str k " = " (or v ""))))
                  (str/join "\n"))]
    (spit (io/file output-dir "environment.properties") (str content "\n"))))

(defn- write-categories-json!
  "Write categories.json to classify failures vs unexpected errors."
  [^File output-dir]
  (let [categories [{:name "Assertion failures"
                     :matchedStatuses ["failed"]
                     :messageRegex ".*"}
                    {:name "Unexpected errors"
                     :matchedStatuses ["broken"]
                     :messageRegex ".*"}]]
    (spit (io/file output-dir "categories.json")
      (->json-pretty categories))))

;; =============================================================================
;; HTML Report Generation & Trace Viewer Embedding
;; =============================================================================

(defn- report-dir
  ^String []
  (or (System/getProperty "lazytest.allure.report")
    (System/getenv "LAZYTEST_ALLURE_REPORT")
    "allure-report"))

(defn- copy-dir!
  "Recursively copy all files from src directory to dest directory."
  [^File src ^File dest]
  (when (.isDirectory src)
    (.mkdirs dest)
    (doseq [^File f (.listFiles src)]
      (let [target (io/file dest (.getName f))]
        (if (.isDirectory f)
          (copy-dir! f target)
          (io/copy f target))))))

(defn- patch-trace-viewer-url!
  "Rewrite the Allure app JS to point the Playwright Trace Viewer iframe
   at the local ./trace-viewer/ directory instead of trace.playwright.dev."
  [^File report]
  (doseq [^File f (.listFiles report)]
    (when (and (.isFile f)
            (str/starts-with? (.getName f) "app-")
            (str/ends-with? (.getName f) ".js"))
      (let [content (slurp f)
            patched (-> content
                      (str/replace "src:\"https://trace.playwright.dev/next/\""
                        "src:\"./trace-viewer/\"")
                      (str/replace ",\"https://trace.playwright.dev\"" ",\"*\""))]
        (when (not= content patched)
          (spit f patched))))))

(defn- patch-sw-safari-compat!
  "Patch the trace viewer Service Worker to fix Safari iframe compatibility.

   Safari's Service Worker does not reflect history.pushState() changes
   for iframe clients — self.clients.get(id).url returns the original
   URL without ?trace=<blob>. The Playwright SW's loadTrace function
   reads the trace URL from the client URL's searchParams and throws
   'trace parameter is missing' when it's absent.

   The fix: when searchParams lacks ?trace=, fall back to the cached
   clientId→traceUrl map ($e) that was populated by an earlier /contexts
   request (which DOES have ?trace= in the request URL)."
  [^File report]
  (let [sw (io/file report "trace-viewer" "sw.bundle.js")]
    (when (.isFile sw)
      (let [content (slurp sw)
            ;; Original: if(!n)throw new Error("trace parameter is missing");
            ;; Patched:  if(!n){n=$e.get(s);if(!n)throw new Error("trace parameter is missing");}
            patched (str/replace content
                      "if(!n)throw new Error(\"trace parameter is missing\")"
                      "if(!n){n=$e.get(s);if(!n)throw new Error(\"trace parameter is missing\")}")]
        (when (not= content patched)
          (spit sw patched))))))

(defn- inject-trace-viewer-prewarm!
  "Inject an inline script into the report's index.html that eagerly
   registers the Playwright trace viewer's Service Worker.

   The trace viewer relies on a SW (sw.bundle.js) to intercept fetch
   requests and serve trace data. On first visit the SW must be
   registered, installed, and controlling the scope before the viewer
   can load a trace. The viewer waits for `navigator.serviceWorker.controller`
   but Allure posts the trace blob on the iframe's `load` event — if the
   SW isn't active yet, the `fetch('contexts?...')` call inside the viewer
   falls through to the HTTP server, returning HTML instead of trace data,
   which causes 'End of central directory not found' ZIP parse errors.

   This script registers the SW directly from the parent page (no iframe
   overhead) and waits for it to claim clients. By the time the user
   clicks a trace attachment, the SW is already active and controlling
   the ./trace-viewer/ scope."
  [^File report]
  (let [idx (io/file report "index.html")]
    (when (.isFile idx)
      (let [content  (slurp idx)
            prewarm  (str "\n<script>\n"
                       "// Pre-register Playwright trace viewer Service Worker.\n"
                       "// Starts immediately on page load so the SW is active and\n"
                       "// controlling the scope before any trace attachment is opened.\n"
                       "(function(){\n"
                       "  if(!navigator.serviceWorker)return;\n"
                       "  navigator.serviceWorker.register('./trace-viewer/sw.bundle.js',\n"
                       "    {scope:'./trace-viewer/'}).catch(function(){});\n"
                       "}());\n"
                       "</script>\n")
            patched  (str/replace content "</head>" (str prewarm "</head>"))]
        (when (not= content patched)
          (spit idx patched))))))

;; ---------------------------------------------------------------------------
;; Allure CLI resolution
;; ---------------------------------------------------------------------------

(def ^:private allure-version
  "Pinned Allure CLI version — this library owns the versioning."
  "3.1.0")

(def ^:private allure-npm-pkg
  (str "allure@" allure-version))

(defn- cmd-exists?
  "Returns true when `cmd` is found on PATH."
  [^String cmd]
  (try
    (let [pb (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" cmd]))
               (.redirectErrorStream true))
          proc (.start pb)
          exit (.waitFor proc)]
      (zero? exit))
    (catch Exception _ false)))

(defn- run-proc!
  "Run a command with inherited IO and return the exit code."
  [cmd]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec cmd)) (.inheritIO))
        proc (.start pb)]
    (.waitFor proc)))

(defn- resolve-allure-cmd!
  "Determine how to invoke the Allure CLI.  Tries in order:
     1. npx with pinned version  (preferred — reproducible)
     2. Global `allure` binary   (fallback — version may differ)
     3. Install via npm globally (last resort)
   Returns a vector of command parts, or nil when unavailable."
  []
  (cond
    ;; 1. npx available → always use the pinned version
    (cmd-exists? "npx")
    (do (println (str "  Using npx " allure-npm-pkg))
      ["npx" "--yes" allure-npm-pkg])

    ;; 2. Global allure on PATH
    (cmd-exists? "allure")
    (do (println "  Using globally installed allure (version may differ from pinned)")
      ["allure"])

    ;; 3. npm available → install globally, then use allure
    (cmd-exists? "npm")
    (do (println (str "  Neither npx nor allure found. Installing " allure-npm-pkg " globally..."))
      (if (zero? (long (run-proc! ["npm" "install" "-g" allure-npm-pkg])))
        (do (println (str "  Installed " allure-npm-pkg " successfully."))
          ["allure"])
        (do (println "  x npm install failed - cannot generate report.")
          nil)))

    ;; 4. Nothing available
    :else
    (do (println "  x Cannot generate report: npx, allure, and npm are all missing.")
      (println (str "    Install Node.js (https://nodejs.org) or: npm i -g " allure-npm-pkg))
      nil)))

;; ---------------------------------------------------------------------------
;; Report generation
;; ---------------------------------------------------------------------------

(def ^:private history-file ".allure-history.jsonl")

(defn- history-limit
  ^String []
  (or (System/getProperty "lazytest.allure.history-limit")
    (System/getenv "LAZYTEST_ALLURE_HISTORY_LIMIT")
    "10"))

(defn- report-name
  ^String []
  (or (System/getProperty "lazytest.allure.report-name")
    (System/getenv "LAZYTEST_ALLURE_REPORT_NAME")))

(defn- report-logo
  ^String []
  (let [path (or (System/getProperty "lazytest.allure.logo")
               (System/getenv "LAZYTEST_ALLURE_LOGO"))]
    (when (and path (.isFile (io/file path)))
      path)))

(defn- generate-html-report!
  "Resolve the Allure CLI, run `allure awesome` (with history when
   available), embed the local trace viewer, and patch the report JS.
   Returns true on success."
  [^String results-dir ^String report-dir-path]
  (let [report    (io/file report-dir-path)
        trace-src (io/file "resources/trace-viewer")]
    (when (.exists trace-src)
      (println "Generating Allure HTML report...")
      (flush)
      (if-let [allure-cmd (resolve-allure-cmd!)]
        (do
          ;; Remove old report
          (when (.exists report)
            (doseq [^File f (reverse (file-seq report))]
              (.delete f)))
          ;; Build command — use `allure awesome` which supports --history-path
          (let [history (io/file history-file)
                cmd     (cond-> (into allure-cmd ["awesome" results-dir
                                                  "-o" report-dir-path])
                          (.isFile history)
                          (into ["-h" (.getAbsolutePath history)])
                          (report-name)
                          (into ["--name" (report-name)])
                          (report-logo)
                          (into ["--logo" (report-logo)]))
                exit    (long (run-proc! cmd))]
            (if (zero? exit)
              (do
                (copy-dir! trace-src (io/file report "trace-viewer"))
                (patch-trace-viewer-url! report)
                (inject-trace-viewer-prewarm! report)
                (patch-sw-safari-compat! report)
                (run-proc! (into allure-cmd ["history" results-dir
                                             "-h" history-file
                                             "--history-limit" (history-limit)]))
                (println (str "  Report ready at " report-dir-path "/"))
                true)
              (do
                (println (str "  x allure generate failed (exit " exit ")"))
                false))))
        false))))

;; =============================================================================
;; Reporter
;; =============================================================================

(defn- output-dir
  "Determine the output directory. Checks system property, then env var,
   then falls back to allure-results."
  ^String []
  (or (System/getProperty "lazytest.allure.output")
    (System/getenv "LAZYTEST_ALLURE_OUTPUT")
    "allure-results"))

(defn- clean-output-dir!
  "Remove old results and recreate the output directory.
   History is managed externally via `.allure-history.jsonl` (Allure 3
   JSONL mechanism), so nothing inside the results dir needs preserving."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f)))
  (.mkdirs dir))

(defmulti allure
  "Allure 3 reporter multimethod for Lazytest.

   Writes JSON results and auto-generates HTML report with embedded trace viewer.

   Usage:
     --output nested --output com.blockether.spel.allure-reporter/allure"
  {:arglists '([config m])}
  #'reporter-dispatch)

(defmethod allure :default [_ _])

(defmethod allure :begin-test-run [_ _]
  (let [dir (io/file (output-dir))]
    (clean-output-dir! dir)
    (reset! run-state {:hostname (hostname)
                       :start-ms (System/currentTimeMillis)
                       :output-dir dir})
    (allure/set-reporter-active! true)
    (install-output-capture!)))

(defmethod allure :end-test-run [_ m]
  (allure/set-reporter-active! false)
  (uninstall-output-capture!)
  (let [results    (:results m)
        dir        (:output-dir @run-state)
        ns-suites  (when (s/suite-result? results)
                     (filter ns-suite? (:children results)))
        all-cases  (mapcat #(collect-test-cases % [] nil) ns-suites)
        n          (count all-cases)]
    ;; Write individual result files
    (doseq [tc all-cases]
      (let [result (build-result tc dir)
            filename (str (:uuid result) "-result.json")]
        (spit (io/file dir filename) (->json-pretty result))))
    ;; Write supplementary files
    (write-environment-properties! dir)
    (write-categories-json! dir)
    (println (str "\nAllure results written to " (output-dir) "/ (" n " test cases)"))
    ;; Generate HTML report with embedded trace viewer
    (generate-html-report! (output-dir) (report-dir))))
