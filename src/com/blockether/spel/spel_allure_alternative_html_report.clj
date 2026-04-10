(ns com.blockether.spel.spel-allure-alternative-html-report
  "Blockether Allure report renderer.
   Reads allure-results/ JSON files and generates a standalone HTML report
   with a clean neutral palette, search, sorting, compact investigation-first
   layout, and shared attachment UX reused from the standard Allure report
   helpers."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.text SimpleDateFormat]
   [java.util Date TimeZone]))

(def ^:private status-order
  {"failed" 0
   "broken" 1
   "skipped" 2
   "passed" 3
   "unknown" 4})

(defn load-results
  "Load all *-result.json files from the given directory."
  [^String results-dir]
  (let [dir (io/file results-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
        (filter #(and (.isFile ^File %)
                   (str/ends-with? (.getName ^File %) "-result.json")))
        (keep (fn [^File f]
                (try
                  (json/read-json (slurp f))
                  (catch Exception _ nil))))
        vec)
      [])))

(defn- load-environment
  "Load environment.properties from the results directory."
  [^String results-dir]
  (let [f (io/file results-dir "environment.properties")]
    (when (.isFile f)
      (into {}
        (for [line (str/split-lines (slurp f))
              :when (not (str/blank? line))
              :let [[k v] (str/split line #"\s*=\s*" 2)]
              :when k]
          [k (or v "")])))))

(defn- count-by-status [results]
  (let [freqs (frequencies (map #(get % "status" "unknown") results))]
    {:passed  (long (get freqs "passed" 0))
     :failed  (long (get freqs "failed" 0))
     :broken  (long (get freqs "broken" 0))
     :skipped (long (get freqs "skipped" 0))
     :unknown (long (get freqs "unknown" 0))
     :total   (count results)}))

(defn- total-duration-ms [results]
  (reduce + 0
    (keep (fn [r]
            (when (and (get r "start") (get r "stop"))
              (- (long (get r "stop")) (long (get r "start")))))
      results)))

(defn- format-duration [^long ms]
  (let [secs (quot ms 1000)
        mins (quot secs 60)
        hours (quot mins 60)]
    (cond
      (pos? hours) (format "%dh %dm %ds" (int hours) (int (mod mins 60)) (int (mod secs 60)))
      (pos? mins) (format "%dm %ds" (int mins) (int (mod secs 60)))
      :else (format "%.1fs" (/ (double ms) 1000.0)))))

(defn- format-ts [^long ms]
  (let [sdf (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")]
    (.setTimeZone sdf (TimeZone/getDefault))
    (.format sdf (Date. ms))))

(defn- html-escape [s]
  (when s
    (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;"))))

(defn- label-value
  "Extract a label value from the result's labels array."
  [result ^String label-name]
  (->> (get result "labels" [])
    (filter #(= label-name (get % "name")))
    (map #(get % "value"))
    first))

(defn- longest-common-prefix
  "Find the longest common dot-separated namespace prefix across suite names.
   Returns the prefix string (without trailing dot) or nil if no common prefix."
  [suite-names]
  (when (seq suite-names)
    (let [split-names (map #(str/split % #"\.") suite-names)
          shortest (long (apply min (map count split-names)))]
      (loop [i 0]
        (if (>= i shortest)
          (when (pos? i)
            (str/join "." (take i (first split-names))))
          (let [seg (nth (first split-names) i)]
            (if (every? #(= seg (nth % i)) split-names)
              (recur (inc i))
              (when (pos? i)
                (str/join "." (take i (first split-names)))))))))))

(defn- strip-prefix
  "Strip a dot-separated prefix from a suite name. If the name starts with
   prefix followed by a dot or ' / ', remove it. Returns the short name."
  [^String prefix ^String suite-name]
  (if (and prefix (not (str/blank? prefix)))
    (let [dot-prefix (str prefix ".")
          slash-prefix (str prefix " / ")]
      (cond
        (str/starts-with? suite-name slash-prefix)
        (subs suite-name (count slash-prefix))

        (str/starts-with? suite-name dot-prefix)
        (subs suite-name (count dot-prefix))

        (= suite-name prefix)
        ""

        :else suite-name))
    suite-name))

(defn- suite-prefix-candidate
  "Extract the namespace-like prefix portion from a suite label.
   For labels like `parent / suite`, use `parent` for prefix detection."
  [^String suite-name]
  (if (str/includes? suite-name " / ")
    (first (str/split suite-name #"\s+/\s+" 2))
    suite-name))

(defn- group-by-suite
  "Group results by parentSuite > suite hierarchy."
  [results]
  (let [groups (group-by
                 (fn [r]
                   (let [parent (or (label-value r "parentSuite") "")
                         suite (or (label-value r "suite") "unknown")]
                     (cond
                       (str/blank? parent) suite
                       (= parent suite) suite
                       :else (str parent " / " suite))))
                 results)]
    (into (sorted-map)
      (for [[suite-name suite-results] groups]
        [suite-name
         (sort-by (fn [r]
                    [(get status-order (get r "status" "unknown") 99)
                     (get r "name" "")])
           suite-results)]))))

(defn- status-class [^String status]
  (case status
    "passed" "status-passed"
    "failed" "status-failed"
    "broken" "status-broken"
    "skipped" "status-skipped"
    "status-unknown"))

(defn- status-icon [^String status]
  (case status
    "passed" "&#10003;"
    "failed" "&#10007;"
    "broken" "&#9888;"
    "skipped" "&#9744;"
    "?"))

(defn- detail-marker []
  "<span class=\"disclosure-marker\" aria-hidden=\"true\"></span>")

(defn- trace-attachment? [attachment]
  (= "application/vnd.allure.playwright-trace" (get attachment "type")))

(defn- markdown-attachment? [attachment]
  (= "text/markdown" (get attachment "type")))

(defn- video-attachment? [attachment]
  (str/starts-with? (or (get attachment "type") "") "video/"))

(defn- image-attachment? [attachment]
  (str/starts-with? (or (get attachment "type") "") "image/"))

(defn- json-attachment? [attachment]
  (= "application/json" (get attachment "type")))

(defn- text-attachment? [attachment]
  (str/starts-with? (or (get attachment "type") "") "text/"))

(defn- attachment-path [^String results-dir attachment]
  (when-let [source (get attachment "source")]
    (io/file results-dir source)))

(defn- attachment-href [attachment]
  (str "data/attachments/" (get attachment "source")))

(defn- trace-viewer-href [attachment]
  (str "trace-viewer/index.html?trace=../data/attachments/" (get attachment "source")))

(defn- slurp-safe [^File f]
  (when (and f (.isFile f))
    (try
      (slurp f)
      (catch Exception _ nil))))

(defn- count-nested-steps ^long [steps]
  (reduce (fn [^long total step]
            (+ total (long (inc (count-nested-steps (get step "steps"))))))
    0
    (or steps [])))

(defn- collect-step-attachments [steps]
  (mapcat (fn [step]
            (concat (get step "attachments" [])
              (collect-step-attachments (get step "steps"))))
    (or steps [])))

(defn- collect-all-attachments [results]
  (vec
    (mapcat (fn [result]
              (concat (get result "attachments" [])
                (collect-step-attachments (get result "steps"))))
      results)))

(defn- clean-dir! [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f)))
  (.mkdirs dir))

(def ^:private trace-chunk-size-prop
  "spel.report.trace.chunk.bytes")

(def ^:private trace-chunk-size-env
  "SPEL_REPORT_TRACE_CHUNK_BYTES")

(defn- parse-long-safe [^String s]
  (try (Long/parseLong (str/trim s))
    (catch Exception _ nil)))

(defn- trace-chunk-size-bytes
  "Resolve trace chunk size in bytes. Returns nil when chunking is disabled.
   Precedence:
   1. System property `spel.report.trace.chunk.bytes`
   2. Environment variable `SPEL_REPORT_TRACE_CHUNK_BYTES`
   Both must be a positive integer. When neither is set (or values are invalid
   / non-positive), chunking is disabled and traces are copied as a single file."
  []
  (let [from-prop (some-> (System/getProperty trace-chunk-size-prop) parse-long-safe)
        from-env (some-> (System/getenv trace-chunk-size-env) parse-long-safe)]
    (or (when (and from-prop (pos? from-prop)) (long from-prop))
      (when (and from-env (pos? from-env)) (long from-env)))))

(defn- trace-chunk-size-label
  "Human-readable label for the given chunk size in bytes, e.g. `9MB`."
  ^String [^long bytes]
  (let [mib (/ (double bytes) (* 1024.0 1024.0))]
    (if (== mib (Math/floor mib))
      (str (long mib) "MB")
      (format "%.1fMB" mib))))

(defn- trace-chunk-count
  "Return number of chunks needed for a file. Returns 1 when chunking is disabled
   or the file fits in a single chunk."
  ^long [^File f]
  (let [size (long (.length f))
        chunk-size (trace-chunk-size-bytes)]
    (if (or (nil? chunk-size) (<= size (long chunk-size)))
      1
      (long (Math/ceil (/ (double size) (double chunk-size)))))))

(defn- copy-file-in-chunks!
  "Copy a file to dest-dir as `<source>.partNNN` chunks."
  [^File src ^File dest-dir ^String source]
  (let [chunk-size (trace-chunk-size-bytes)]
    (with-open [in (io/input-stream src)]
      (loop [remaining (long (.length src))
             idx 0]
        (when (pos? remaining)
          (let [part-name (format "%s.part%03d" source idx)
                part-file (io/file dest-dir part-name)
                part-size (long (Math/min (long chunk-size) remaining))]
          (io/make-parents part-file)
          (with-open [out (io/output-stream part-file)]
            (let [buf (byte-array 8192)]
              (loop [left part-size]
                (when (pos? left)
                  (let [to-read (int (min left (alength buf)))
                        n (.read in buf 0 to-read)]
                    (when (neg? n)
                      (throw (ex-info "Unexpected EOF while chunking trace"
                               {:source source :part idx})))
                    (.write out buf 0 n)
                    (recur (- left n)))))))
          (recur (- remaining part-size) (inc idx))))))))

(defn- copy-attachments!
  [^String results-dir ^File out results]
  (let [dest-dir (io/file out "data" "attachments")
        chunk-size (trace-chunk-size-bytes)]
    (.mkdirs dest-dir)
    (doseq [attachment (collect-all-attachments results)
            :let [source (get attachment "source")]
            :when (seq source)]
      (let [src (io/file results-dir source)
            dest (io/file dest-dir source)]
        (when (.isFile src)
          (if (and chunk-size
                (trace-attachment? attachment)
                (> (long (.length src)) (long chunk-size)))
            (copy-file-in-chunks! src dest-dir source)
            (do
              (io/make-parents dest)
              (io/copy src dest))))))))

(defn- reporter-helper [sym]
  (require 'com.blockether.spel.allure-reporter)
  (or (ns-resolve 'com.blockether.spel.allure-reporter sym)
    (throw (ex-info (str "Missing allure reporter helper: " sym) {:symbol sym}))))

(defn- invoke-reporter-helper! [sym & args]
  (apply (deref (reporter-helper sym)) args))

(defn- ensure-trace-viewer! [^File out]
  (let [viewer-dir (io/file out "trace-viewer")]
    (invoke-reporter-helper! 'copy-trace-viewer! viewer-dir)
    (invoke-reporter-helper! 'patch-sw-safari-compat! out)
    (invoke-reporter-helper! 'patch-sw-safari-transform-stream! out)
    (invoke-reporter-helper! 'patch-sw-safari-response-headers! out)
    (invoke-reporter-helper! 'inject-trace-viewer-prewarm! out)))

(defn- enhance-report-shell! [^File out]
  (invoke-reporter-helper! 'inject-video-modal! out)
  (invoke-reporter-helper! 'inject-markdown-renderer! out))

(defn- render-summary-chip [label value class-name]
  (str "<div class=\"summary-chip " class-name "\">"
    "<span class=\"summary-chip-label\">" label "</span>"
    "<span class=\"summary-chip-value\">" value "</span>"
    "</div>"))

(defn- render-attachment-html
  [attachment results-dir]
  (let [att-name (html-escape (get attachment "name" "attachment"))
        href (attachment-href attachment)
        file (attachment-path results-dir attachment)
        content (slurp-safe file)
        source (html-escape (get attachment "source" ""))
        chunk-count (if (and (trace-attachment? attachment) file (.isFile ^File file))
                      (trace-chunk-count ^File file)
                      1)]
    (cond
      (trace-attachment? attachment)
      (if (> chunk-count 1)
        (str "<div class=\"attachment-actions attachment-actions-trace\">"
          "<button type=\"button\" class=\"attachment-link attachment-link-button trace-launch\""
          " data-trace-source=\"" source "\""
          " data-trace-chunks=\"" chunk-count "\""
          " data-trace-title=\"" att-name "\">Open Trace</button>"
          "<span class=\"attachment-link attachment-link-subtle trace-chunk-note\">"
          "Trace split into " chunk-count " parts (&lt;" (trace-chunk-size-label (trace-chunk-size-bytes)) " each)</span>"
          "</div>")
        (str "<div class=\"attachment-actions attachment-actions-trace\">"
          "<button type=\"button\" class=\"attachment-link attachment-link-button trace-launch\""
          " data-trace-url=\"" (trace-viewer-href attachment) "\""
          " data-trace-title=\"" att-name "\">Open Trace</button>"
          "<a class=\"attachment-link attachment-link-subtle\" href=\"" href "\" download>Download zip</a>"
          "</div>"))

      (markdown-attachment? attachment)
      (str "<details class=\"attachment-panel attachment-panel-markdown\">"
        "<summary>" (detail-marker) "<span>" att-name "</span></summary>"
        (if content
          (str "<pre data-testid=\"code-attachment-content\" class=\"language-md\"><code>"
            (html-escape content)
            "</code></pre>")
          "<div class=\"attachment-missing\">Attachment content unavailable.</div>")
        "<div class=\"attachment-actions\"><a class=\"attachment-link attachment-link-subtle\" href=\"" href "\" target=\"_blank\" rel=\"noopener\">Raw file</a></div>"
        "</details>")

      (image-attachment? attachment)
      (str "<details class=\"attachment-panel attachment-panel-image\">"
        "<summary>" (detail-marker) "<span>" att-name "</span></summary>"
        "<a class=\"attachment-image-link\" href=\"" href "\" target=\"_blank\" rel=\"noopener\">"
        "<img class=\"attachment-image\" src=\"" href "\" alt=\"" att-name "\" />"
        "</a>"
        "</details>")

      (video-attachment? attachment)
      (str "<div class=\"attachment-actions\">"
        "<a class=\"attachment-link\" href=\"" href "\" target=\"_blank\" rel=\"noopener\">" att-name "</a>"
        "</div>")

      (json-attachment? attachment)
      (str "<details class=\"attachment-panel attachment-panel-json\">"
        "<summary>" (detail-marker) "<span>" att-name "</span></summary>"
        (if content
          (str "<pre class=\"attachment-pre\"><code>" (html-escape content) "</code></pre>")
          "<div class=\"attachment-missing\">Attachment content unavailable.</div>")
        "<div class=\"attachment-actions\"><a class=\"attachment-link attachment-link-subtle\" href=\"" href "\" target=\"_blank\" rel=\"noopener\">Raw file</a></div>"
        "</details>")

      (text-attachment? attachment)
      (str "<details class=\"attachment-panel attachment-panel-log\">"
        "<summary>" (detail-marker) "<span>" att-name "</span></summary>"
        (if content
          (str "<pre class=\"attachment-pre\"><code>" (html-escape content) "</code></pre>")
          "<div class=\"attachment-missing\">Attachment content unavailable.</div>")
        "</details>")

      :else
      (str "<div class=\"attachment-actions\">"
        "<a class=\"attachment-link\" href=\"" href "\" target=\"_blank\" rel=\"noopener\">" att-name "</a>"
        "</div>"))))

(defn- render-attachments-html [attachments results-dir]
  (when (seq attachments)
    (str "<div class=\"attachment-list\">"
      (str/join "" (map #(render-attachment-html % results-dir) attachments))
      "</div>")))

(declare render-steps-html)

(defn- render-step-html [step results-dir]
  (let [name (html-escape (get step "name" "step"))
        st (get step "status" "unknown")
        child-steps (get step "steps")
        attachments (get step "attachments")
        params (get step "parameters")]
    (str "<li class=\"step-item " (status-class st) "\">"
      "<div class=\"step-header\">"
      "<span class=\"step-icon\">" (status-icon st) "</span>"
      "<span class=\"step-name\" data-testid=\"test-result-step-title\">" name "</span>"
      (when (seq params)
        (str "<span class=\"step-params\">"
          (str/join ", "
            (for [p params]
              (str (html-escape (get p "name")) "=" (html-escape (get p "value")))))
          "</span>"))
      "</div>"
      (when (seq child-steps)
        (render-steps-html child-steps results-dir))
      (render-attachments-html attachments results-dir)
      "</li>")))

(defn- render-steps-html [steps results-dir]
  (when (seq steps)
    (str "<ul class=\"step-tree\">"
      (str/join "" (map #(render-step-html % results-dir) steps))
      "</ul>")))

(defn- render-test-card
  "Render a single test result as a compact details card."
  [result results-dir]
  (let [name (html-escape (get result "name" "unnamed"))
        full-name (html-escape (get result "fullName" ""))
        status (get result "status" "unknown")
        start (get result "start")
        stop (get result "stop")
        duration (when (and start stop) (- (long stop) (long start)))
        steps (get result "steps")
        desc (get result "description")
        status-detail (get result "statusDetails")
        message (when status-detail (get status-detail "message"))
        trace (when status-detail (get status-detail "trace"))
        attachments (get result "attachments")
        labels (get result "labels")
        epic (label-value result "epic")
        feature (label-value result "feature")
        story (label-value result "story")
        severity (label-value result "severity")
        tags (->> labels (filter #(= "tag" (get % "name"))) (map #(get % "value")))
        step-count (count-nested-steps steps)
        attachment-count (long (count attachments))]
    (str "<details class=\"test-card " (status-class status) "\" data-status=\"" status "\""
      " data-duration=\"" (or duration 0) "\""
      " data-name=\"" (str/lower-case (or name "")) "\">"
      "<summary class=\"test-card-summary\">"
      (detail-marker)
      "<span class=\"test-status-badge " (status-class status) "\">" (status-icon status) " " (str/upper-case status) "</span>"
      "<span class=\"test-name\">" name "</span>"
      (when (pos? step-count)
        (str "<span class=\"test-chip\">" step-count " steps</span>"))
      (when (pos? attachment-count)
        (str "<span class=\"test-chip\">" attachment-count " attachments</span>"))
      (when duration
        (str "<span class=\"test-duration\">" (format-duration (long duration)) "</span>"))
      "</summary>"
      "<div class=\"test-card-body\">"
      (when (seq full-name)
        (str "<div class=\"test-full-name\">" full-name "</div>"))
      (when (or epic feature story severity (seq tags))
        (str "<div class=\"test-labels\">"
          (when epic (str "<span class=\"label-pill label-epic\">" (html-escape epic) "</span>"))
          (when feature (str "<span class=\"label-pill label-feature\">" (html-escape feature) "</span>"))
          (when story (str "<span class=\"label-pill label-story\">" (html-escape story) "</span>"))
          (when severity (str "<span class=\"label-pill label-severity\">" (html-escape severity) "</span>"))
          (str/join "" (for [t tags] (str "<span class=\"label-pill label-tag\">" (html-escape t) "</span>")))
          "</div>"))
      (when (seq desc)
        (str "<div class=\"test-description\">" (html-escape desc) "</div>"))
      (when (and message (not= status "passed"))
        (str "<div class=\"test-error\"><div class=\"error-message\">" (html-escape message) "</div></div>"))
      (when (seq trace)
        (str "<details class=\"attachment-panel attachment-panel-log stacktrace-panel\">"
          "<summary>" (detail-marker) "<span>Stack trace</span></summary>"
          "<pre class=\"attachment-pre\"><code>" (html-escape trace) "</code></pre>"
          "</details>"))
      (when (seq steps)
        (str "<details class=\"test-steps\">"
          "<summary>" (detail-marker) "<span>Execution steps</span></summary>"
          (render-steps-html steps results-dir)
          "</details>"))
      (render-attachments-html attachments results-dir)
      "</div>"
      "</details>")))

(defn- render-suite-section
  "Render a suite group with compact collapsed-by-default cards."
  [suite-name results results-dir]
  (let [cts (count-by-status results)]
    (str "<details class=\"suite-section\" data-suite>"
      "<summary class=\"suite-summary\">"
      (detail-marker)
      "<span class=\"suite-title\">" (html-escape suite-name) "</span>"
      "<span class=\"suite-summary-meta\">"
      "<span class=\"suite-stat stat-total\">" (:total cts) " total</span>"
      (when (pos? (long (:failed cts)))
        (str "<span class=\"suite-stat stat-failed\">" (:failed cts) " failed</span>"))
      (when (pos? (long (:broken cts)))
        (str "<span class=\"suite-stat stat-broken\">" (:broken cts) " broken</span>"))
      (when (pos? (long (:skipped cts)))
        (str "<span class=\"suite-stat stat-skipped\">" (:skipped cts) " skipped</span>"))
      (when (pos? (long (:passed cts)))
        (str "<span class=\"suite-stat stat-passed\">" (:passed cts) " passed</span>"))
      "</span>"
      "</summary>"
      "<div class=\"suite-body\">"
      (str/join "" (map #(render-test-card % results-dir) results))
      "</div>"
      "</details>")))

(defn- css
  "Clean neutral stylesheet for the Blockether alternative report."
  []
  "
  :root {
    --bg: #f8f9fa;
    --bg-panel: rgba(255, 255, 255, 0.97);
    --bg-panel-strong: rgba(248, 249, 250, 0.98);
    --bg-code: #f1f3f5;
    --bg-accent: rgba(55, 65, 81, 0.06);
    --border: rgba(0, 0, 0, 0.10);
    --border-strong: rgba(0, 0, 0, 0.18);
    --text: #111827;
    --text-secondary: #4b5563;
    --text-muted: #6b7280;
    --accent: #4f46e5;
    --accent-green: #16a34a;
    --accent-green-light: rgba(22, 163, 74, 0.08);
    --accent-green-border: rgba(22, 163, 74, 0.25);
    --accent-yellow: #d97706;
    --accent-red: #dc2626;
    --accent-teal: #0891b2;
    --shadow: 0 1px 3px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
    --shadow-md: 0 4px 6px rgba(0, 0, 0, 0.05), 0 2px 4px rgba(0, 0, 0, 0.03);
    --radius-lg: 12px;
    --radius-md: 8px;
    --radius-sm: 6px;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #0f1117;
      --bg-panel: rgba(22, 24, 32, 0.98);
      --bg-panel-strong: rgba(28, 31, 40, 0.98);
      --bg-code: #1e2028;
      --bg-accent: rgba(99, 102, 241, 0.10);
      --border: rgba(255, 255, 255, 0.10);
      --border-strong: rgba(255, 255, 255, 0.18);
      --text: #f3f4f6;
      --text-secondary: #d1d5db;
      --text-muted: #9ca3af;
      --accent: #818cf8;
      --accent-green: #4ade80;
      --accent-green-light: rgba(74, 222, 128, 0.08);
      --accent-green-border: rgba(74, 222, 128, 0.20);
      --accent-yellow: #fbbf24;
      --accent-red: #f87171;
      --accent-teal: #22d3ee;
      --shadow: 0 1px 3px rgba(0, 0, 0, 0.20), 0 1px 2px rgba(0, 0, 0, 0.16);
      --shadow-md: 0 4px 8px rgba(0, 0, 0, 0.24), 0 2px 4px rgba(0, 0, 0, 0.16);
    }
  }
  *, *::before, *::after { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-size: 14px;
    line-height: 1.5;
    color: var(--text);
    background: var(--bg);
  }
  h1, h2, h3, h4 {
    margin: 0;
    font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-weight: 700;
    line-height: 1.2;
    color: var(--text);
  }
  code, pre, .mono {
    font-family: 'JetBrains Mono', 'IBM Plex Mono', ui-monospace, monospace;
  }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  button, select { font: inherit; }

  /* Layout */
  .page-shell {
    max-width: 1440px;
    margin: 0 auto;
    padding: 1.25rem 1.25rem 2.5rem;
  }

  /* Header */
  .report-header {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    justify-content: space-between;
    gap: 1rem;
    padding: 1.25rem 1.5rem;
    margin-bottom: 1rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--bg-panel);
    box-shadow: var(--shadow);
  }
  .report-header-main {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    min-width: 280px;
  }
  .report-kicker {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.68rem;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--text-muted);
    font-weight: 500;
  }
  .report-title {
    font-size: clamp(1.25rem, 2.5vw, 1.75rem);
    letter-spacing: -0.02em;
    font-weight: 800;
  }
  .report-subtitle {
    color: var(--text-muted);
    font-size: 0.76rem;
  }
  .report-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    justify-content: flex-end;
  }

  /* Summary chips */
  .summary-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.35rem 0.65rem;
    border-radius: var(--radius-sm);
    background: var(--bg-accent);
    border: 1px solid var(--border);
    color: var(--text);
    white-space: nowrap;
    font-size: 0.8rem;
  }
  .summary-chip-label {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.65rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-muted);
  }
  .summary-chip-value { font-weight: 700; }
  .summary-chip-passed .summary-chip-value { color: var(--accent-green); }
  .summary-chip-failed .summary-chip-value { color: var(--accent-red); }
  .summary-chip-broken .summary-chip-value { color: var(--accent-yellow); }
  .summary-chip-skipped .summary-chip-value { color: var(--text-muted); }
  .summary-chip-total .summary-chip-value,
  .summary-chip-suites .summary-chip-value { color: var(--accent); }
  .summary-chip-duration .summary-chip-value { color: var(--accent-teal); }

  /* Toolbar */
  .toolbar {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 1rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    backdrop-filter: blur(12px);
    box-shadow: var(--shadow-md);
  }
  .filter-bar,
  .toolbar-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    align-items: center;
  }
  .toolbar-actions { margin-left: auto; }
  .toolbar-search {
    flex: 1;
    min-width: 140px;
    max-width: 280px;
    padding: 0.32rem 0.6rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-panel-strong);
    color: var(--text);
    font-size: 0.8rem;
    outline: none;
    transition: border-color 0.15s;
  }
  .toolbar-search:focus { border-color: var(--accent); }
  .toolbar-search::placeholder { color: var(--text-muted); }
  .toolbar-sort {
    padding: 0.32rem 0.5rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-panel-strong);
    color: var(--text);
    font-size: 0.75rem;
    cursor: pointer;
    font-family: 'JetBrains Mono', monospace;
  }
  .filter-btn,
  .toolbar-btn,
  .attachment-link,
  .attachment-link-button {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--text-secondary);
    padding: 0.3rem 0.6rem;
    cursor: pointer;
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.7rem;
    letter-spacing: 0.02em;
    text-transform: uppercase;
    transition: all 0.12s ease;
  }
  .filter-btn:hover,
  .toolbar-btn:hover,
  .attachment-link:hover,
  .attachment-link-button:hover {
    text-decoration: none;
    color: var(--text);
    border-color: var(--border-strong);
    background: var(--bg-accent);
  }
  .filter-btn.active { background: var(--accent); border-color: var(--accent); color: #fff; }
  .filter-btn[data-filter='passed'].active { background: var(--accent-green-light); border-color: var(--accent-green-border); color: var(--accent-green); }
  .filter-btn[data-filter='failed'].active { background: var(--accent-red); border-color: var(--accent-red); }
  .filter-btn[data-filter='broken'].active { background: var(--accent-yellow); border-color: var(--accent-yellow); }
  .filter-btn[data-filter='skipped'].active { background: var(--text-muted); border-color: var(--text-muted); }
  .attachment-link-button { display: inline-flex; align-items: center; }
  .attachment-link-subtle { color: var(--text-muted); }
  .trace-chunk-note {
    border: none;
    background: transparent;
    text-transform: none;
    letter-spacing: 0;
    font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-size: 0.72rem;
    padding: 0;
    color: var(--text-muted);
    cursor: default;
  }

  /* Panels */
  .panel {
    margin-bottom: 1rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    box-shadow: var(--shadow);
  }
  .panel > summary {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.7rem 0.85rem;
    cursor: pointer;
    list-style: none;
    font-weight: 600;
    font-size: 0.85rem;
  }
  .panel > summary::-webkit-details-marker { display: none; }
  .panel-body {
    padding: 0.75rem 0.85rem;
    border-top: 1px solid var(--border);
  }
  .env-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 0.5rem;
  }
  .env-item {
    padding: 0.5rem 0.65rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    background: var(--bg-panel-strong);
  }
  .env-key {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.65rem;
    color: var(--text-muted);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .env-value {
    margin-top: 0.15rem;
    font-weight: 600;
    font-size: 0.85rem;
  }

  /* Section headings */
  .section-heading {
    margin: 0 0 0.5rem;
    font-size: 0.95rem;
    letter-spacing: -0.01em;
  }
  .suite-common-prefix {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.7rem;
    color: var(--text-muted);
    padding: 0.3rem 0;
    margin-bottom: 0.25rem;
    letter-spacing: 0.02em;
  }

  /* Suite sections */
  .suite-section {
    margin-bottom: 0.75rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    box-shadow: var(--shadow);
    overflow: hidden;
  }
  .suite-section > summary {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    padding: 0.65rem 0.85rem;
    cursor: pointer;
    list-style: none;
  }
  .suite-section > summary::-webkit-details-marker { display: none; }
  .suite-body {
    padding: 0 0.65rem 0.65rem;
    border-top: 1px solid var(--border);
  }
  .suite-title {
    flex: 1;
    min-width: 200px;
    font-size: 0.88rem;
    font-weight: 700;
  }
  .suite-summary-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
    justify-content: flex-end;
  }
  .suite-stat,
  .test-chip {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.65rem;
    padding: 0.15rem 0.4rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    background: var(--bg-panel-strong);
  }
  .stat-passed { color: var(--accent-green); }
  .stat-failed { color: var(--accent-red); }
  .stat-broken { color: var(--accent-yellow); }
  .stat-skipped { color: var(--text-muted); }
  .stat-total { color: var(--accent); }

  /* Test cards */
  .test-card {
    margin-top: 0.5rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    box-shadow: var(--shadow);
    overflow: hidden;
    transition: box-shadow 0.12s ease;
  }
  .test-card:hover { box-shadow: var(--shadow-md); }
  .test-card.status-failed { border-left: 3px solid var(--accent-red); }
  .test-card.status-broken { border-left: 3px solid var(--accent-yellow); }
  .test-card.status-passed { border-left: 3px solid var(--accent-green-border); }
  .test-card.status-skipped { border-left: 3px solid var(--text-muted); }
  .test-card > summary {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0.7rem;
    cursor: pointer;
    list-style: none;
  }
  .test-card > summary::-webkit-details-marker { display: none; }
  .test-card-body {
    padding: 0.5rem 0.7rem 0.7rem;
    border-top: 1px solid var(--border);
  }

  /* Status badges */
  .test-status-badge {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.62rem;
    font-weight: 700;
    padding: 0.15rem 0.45rem;
    border-radius: var(--radius-sm);
    color: #fff;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    flex-shrink: 0;
  }
  .status-passed { background: var(--accent-green-light); color: var(--accent-green); }
  .status-failed { background: var(--accent-red); }
  .status-broken { background: var(--accent-yellow); }
  .status-skipped { background: var(--text-muted); }
  .status-unknown { background: var(--text-secondary); }
  .test-status-badge.status-passed { color: var(--accent-green); }
  .test-name {
    flex: 1;
    min-width: 150px;
    font-size: 0.85rem;
    font-weight: 400;
    color: var(--text);
  }
  .test-duration {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.7rem;
    color: var(--text-muted);
    white-space: nowrap;
  }
  .test-full-name {
    margin-top: 0.4rem;
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.7rem;
    color: var(--text-muted);
    word-break: break-word;
  }

  /* Labels / tags */
  .test-labels {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
    margin-top: 0.5rem;
  }
  .label-pill {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.65rem;
    padding: 0.15rem 0.45rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    font-weight: 500;
  }
  .label-epic { background: rgba(79, 70, 229, 0.08); color: var(--accent); }
  .label-feature { background: rgba(8, 145, 178, 0.08); color: var(--accent-teal); }
  .label-story { background: rgba(217, 119, 6, 0.08); color: var(--accent-yellow); }
  .label-severity { background: rgba(220, 38, 38, 0.08); color: var(--accent-red); }
  .label-tag { background: var(--bg-accent); color: var(--text-secondary); }

  /* Description / error */
  .test-description,
  .test-error {
    margin-top: 0.5rem;
    padding: 0.4rem 0.6rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    font-size: 0.75rem;
    line-height: 1.45;
  }
  .test-description { background: var(--bg-panel-strong); color: var(--text-muted); font-weight: 400; }
  .test-error { background: rgba(220, 38, 38, 0.05); border-color: rgba(220, 38, 38, 0.15); }
  .error-message { color: var(--accent-red); white-space: pre-wrap; word-break: break-word; }

  /* Steps & attachments */
  .test-steps,
  .attachment-panel {
    margin-top: 0.5rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-panel-strong);
    overflow: hidden;
  }
  .test-steps > summary,
  .attachment-panel > summary {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.45rem 0.6rem;
    cursor: pointer;
    list-style: none;
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.7rem;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.03em;
    font-weight: 600;
  }
  .test-steps > summary::-webkit-details-marker,
  .attachment-panel > summary::-webkit-details-marker { display: none; }
  .disclosure-marker {
    width: 0;
    height: 0;
    border-top: 4px solid transparent;
    border-bottom: 4px solid transparent;
    border-left: 5px solid currentColor;
    transition: transform 0.15s ease;
    flex-shrink: 0;
  }
  details[open] > summary .disclosure-marker { transform: rotate(90deg); }

  /* Step tree */
  .step-tree {
    list-style: none;
    margin: 0;
    padding: 0.25rem 0.6rem 0.6rem 0.85rem;
  }
  .step-item {
    margin-top: 0.25rem;
    padding: 0.25rem 0 0.25rem 0.6rem;
    border-left: 2px solid var(--border);
  }
  .step-header {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
    align-items: baseline;
  }
  .step-icon { font-size: 0.72rem; flex-shrink: 0; }
  .step-name {
    font-size: 0.82rem;
    font-weight: 500;
    color: var(--text);
  }
  .step-params {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.65rem;
    color: var(--text-muted);
  }

  /* Attachment list */
  .attachment-list {
    display: grid;
    gap: 0.4rem;
    margin-top: 0.5rem;
  }
  .attachment-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    margin-top: 0.4rem;
  }
  .attachment-panel-markdown .attachment-actions {
    margin-top: 0;
    padding: 0.38rem 0.6rem 0.48rem;
    border-top: 1px solid var(--border);
    justify-content: flex-end;
  }
  .attachment-panel-markdown .attachment-link-subtle {
    border: none;
    background: transparent;
    font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-size: 0.74rem;
    text-transform: none;
    letter-spacing: 0;
    padding: 0;
    color: var(--text-muted);
  }
  .attachment-panel-markdown .attachment-link-subtle:hover {
    color: var(--accent);
    text-decoration: underline;
    background: transparent;
    border: none;
  }
  .attachment-pre {
    margin: 0;
    padding: 0.65rem;
    background: var(--bg-code);
    border-top: 1px solid var(--border);
    overflow: auto;
    max-height: 28rem;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 0.72rem;
    line-height: 1.5;
    color: var(--text);
  }
  .attachment-missing {
    padding: 0.65rem;
    color: var(--text-muted);
  }
  .attachment-image-link {
    display: block;
    padding: 0.5rem 0.65rem 0.65rem;
  }
  .attachment-image {
    display: block;
    max-width: 100%;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    box-shadow: var(--shadow);
  }

  /* Empty state */
  .empty-state {
    padding: 2rem 1rem;
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    color: var(--text-muted);
    text-align: center;
  }

  /* Trace modal */
  .trace-modal {
    display: none;
    position: fixed;
    inset: 0;
    z-index: 9998;
    background: rgba(0, 0, 0, 0.6);
    padding: 1rem;
  }
  .trace-modal.show {
    display: flex;
    align-items: stretch;
    justify-content: center;
  }
  .trace-modal-content {
    width: min(1480px, 100%);
    height: calc(100vh - 2rem);
    background: #0f1117;
    border-radius: var(--radius-lg);
    overflow: hidden;
    border: 1px solid rgba(255, 255, 255, 0.1);
    display: grid;
    grid-template-rows: auto 1fr;
    box-shadow: 0 24px 48px rgba(0, 0, 0, 0.4);
  }
  .trace-modal-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.6rem 0.85rem;
    background: rgba(255, 255, 255, 0.04);
    color: #fff;
  }
  .trace-modal-title { font-size: 0.82rem; font-weight: 600; }
  .trace-modal-close {
    border: 1px solid rgba(255, 255, 255, 0.15);
    border-radius: var(--radius-sm);
    background: transparent;
    color: #fff;
    padding: 0.25rem 0.6rem;
    cursor: pointer;
    font-size: 0.75rem;
  }
  .trace-frame {
    width: 100%;
    height: 100%;
    border: 0;
    background: #0f1117;
  }

  /* Scrollbar */
  ::-webkit-scrollbar { width: 6px; height: 6px; }
  ::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: 999px; }
  ::-webkit-scrollbar-track { background: transparent; }

  /* Print */
  @media print {
    .toolbar, .trace-modal { display: none !important; }
    .page-shell { max-width: none; padding: 0; }
    .report-header, .suite-section, .panel, .test-card { box-shadow: none; }
    details[open] > summary .disclosure-marker, .disclosure-marker { display: none; }
  }

  /* Markdown HTTP & badge overrides */
  .spel-md .http-title { background: var(--bg-panel-strong) !important; border-bottom: 1px solid var(--border) !important; color: var(--text) !important; }
  .spel-md .http-url { color: var(--text) !important; }
  .spel-md .http-card { border-color: var(--border) !important; background: var(--bg-panel) !important; box-shadow: var(--shadow); }
  .spel-md .http-card.req .card-hdr { background: var(--accent-green-light) !important; color: var(--accent-green) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-card.res .card-hdr { background: rgba(8, 145, 178, 0.08) !important; color: var(--accent-teal) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-card.curl .card-hdr { background: rgba(217, 119, 6, 0.08) !important; color: var(--accent-yellow) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-section { border-top-color: var(--border) !important; }
  .spel-md .section-hdr { color: var(--text-muted) !important; }
  .spel-md .code-wrap pre { background: var(--bg-code) !important; color: var(--text) !important; border: 1px solid var(--border); }
  .spel-md .copy-btn { background: var(--bg-panel) !important; border-color: var(--border) !important; color: var(--text-secondary) !important; }
  .spel-md .copy-btn:hover { background: var(--bg-accent) !important; color: var(--text) !important; }
  .spel-badge { display: inline-flex !important; align-items: center !important; justify-content: center !important; border-radius: var(--radius-sm) !important; padding: 2px 8px !important; font-size: 0.68rem !important; font-weight: 600 !important; line-height: 1.2 !important; border: 1px solid var(--border) !important; margin-right: 6px !important; margin-bottom: 2px !important; }
  .spel-md .http-title { align-items: center !important; flex-wrap: wrap !important; }
  .spel-md .http-url { margin-left: 2px; }
  .spel-badge.api { background: rgba(8, 145, 178, 0.08) !important; color: var(--accent-teal) !important; }
  .spel-badge.ui { background: rgba(217, 119, 6, 0.08) !important; color: var(--accent-yellow) !important; }
  .spel-badge.ui-api { background: rgba(79, 70, 229, 0.08) !important; color: var(--accent) !important; }

  /* Mobile */
  @media (max-width: 768px) {
    .page-shell { padding: 0.5rem 0.5rem 1.5rem; }
    .report-header { padding: 0.75rem; gap: 0.75rem; }
    .toolbar { padding: 0.5rem; gap: 0.4rem; top: 0; }
    .toolbar-search { max-width: none; min-width: 100px; }
    .suite-section > summary, .test-card > summary { padding: 0.5rem 0.6rem; }
    .report-meta { justify-content: flex-start; }
    .test-card-body { padding: 0.4rem 0.5rem 0.5rem; }
    .attachment-pre { padding: 0.5rem; font-size: 0.68rem; }
    .summary-chip { padding: 0.25rem 0.5rem; font-size: 0.75rem; }
    .test-card > summary {
      flex-wrap: wrap;
      row-gap: 0.25rem;
    }
    .test-name {
      flex-basis: 100%;
      min-width: 0;
      order: 10;
    }
    .test-chip,
    .test-duration {
      order: 20;
    }
    /* Attachment buttons: full-width stacked on mobile */
    .attachment-actions {
      flex-direction: column;
      gap: 0.4rem;
    }
    .attachment-actions .attachment-link,
    .attachment-actions .attachment-link-button,
    .attachment-actions .attachment-link-subtle:not(.trace-chunk-note) {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      padding: 0.5rem 0.75rem;
      font-size: 0.75rem;
      text-align: center;
      box-sizing: border-box;
    }
    .attachment-panel-markdown .attachment-actions {
      flex-direction: row;
      justify-content: flex-end;
      gap: 0;
    }
    .attachment-panel-markdown .attachment-link-subtle {
      width: auto;
      padding: 0;
      display: inline;
      font-size: 0.73rem;
      text-align: right;
    }
    /* Markdown HTTP cards: prevent overflow */
    .spel-md .http-card { overflow: hidden; }
    .spel-md .http-title { flex-wrap: wrap; font-size: 0.75rem; padding: 0.5rem; }
    .spel-md .http-url { font-size: 0.68rem; word-break: break-all; }
    .spel-md .card-hdr { font-size: 0.7rem; padding: 0.4rem 0.5rem; }
    .spel-md .code-wrap pre { font-size: 0.65rem; padding: 0.5rem; }
    .spel-md .copy-btn {
      padding: 0.4rem 0.6rem;
      font-size: 0.7rem;
    }
    .spel-md .section-hdr { font-size: 0.68rem; padding: 0.35rem 0.5rem; }
  }
  ")

(defn- js-ui
  "Client-side filtering, searching, sorting, collapsing, and trace modal."
  []
  "
  (function() {
    var statusOrder = {failed: 0, broken: 1, skipped: 2, passed: 3, unknown: 4};
    var currentFilter = 'all';
    var currentSearch = '';
    var currentSort = 'status';

    function applyFilters() {
      var cards = document.querySelectorAll('.test-card');
      var sections = document.querySelectorAll('.suite-section');
      var q = currentSearch.toLowerCase();

      cards.forEach(function(card) {
        var statusMatch = currentFilter === 'all' || card.getAttribute('data-status') === currentFilter;
        var nameMatch = !q || (card.getAttribute('data-name') || '').indexOf(q) !== -1;
        card.style.display = (statusMatch && nameMatch) ? '' : 'none';
      });

      sections.forEach(function(section) {
        var visible = Array.from(section.querySelectorAll('.test-card')).filter(function(c) {
          return c.style.display !== 'none';
        });
        section.style.display = visible.length > 0 ? '' : 'none';
      });
    }

    function sortCards() {
      var sections = document.querySelectorAll('.suite-section');
      sections.forEach(function(section) {
        var body = section.querySelector('.suite-body');
        if (!body) return;
        var cards = Array.from(body.querySelectorAll(':scope > .test-card'));
        cards.sort(function(a, b) {
          if (currentSort === 'longest') {
            return parseInt(b.getAttribute('data-duration') || '0') - parseInt(a.getAttribute('data-duration') || '0');
          } else if (currentSort === 'shortest') {
            return parseInt(a.getAttribute('data-duration') || '0') - parseInt(b.getAttribute('data-duration') || '0');
          } else if (currentSort === 'name') {
            return (a.getAttribute('data-name') || '').localeCompare(b.getAttribute('data-name') || '');
          } else {
            var sa = statusOrder[a.getAttribute('data-status')] || 99;
            var sb = statusOrder[b.getAttribute('data-status')] || 99;
            if (sa !== sb) return sa - sb;
            return (a.getAttribute('data-name') || '').localeCompare(b.getAttribute('data-name') || '');
          }
        });
        cards.forEach(function(card) { body.appendChild(card); });
      });
    }

    document.querySelectorAll('.filter-btn').forEach(function(btn) {
      btn.addEventListener('click', function() {
        currentFilter = btn.getAttribute('data-filter');
        document.querySelectorAll('.filter-btn').forEach(function(b) {
          b.classList.toggle('active', b.getAttribute('data-filter') === currentFilter);
        });
        applyFilters();
      });
    });

    var searchInput = document.getElementById('searchInput');
    if (searchInput) {
      var debounceTimer;
      searchInput.addEventListener('input', function() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function() {
          currentSearch = searchInput.value;
          applyFilters();
        }, 150);
      });
    }

    var sortSelect = document.getElementById('sortSelect');
    if (sortSelect) {
      sortSelect.addEventListener('change', function() {
        currentSort = sortSelect.value;
        sortCards();
        applyFilters();
      });
    }

    var expandBtn = document.querySelector(\"[data-action='expand-suites']\");
    var collapseBtn = document.querySelector(\"[data-action='collapse-suites']\");

    if (expandBtn) {
      expandBtn.addEventListener('click', function() {
        document.querySelectorAll('.suite-section').forEach(function(section) {
          if (section.style.display !== 'none') section.open = true;
        });
      });
    }

    if (collapseBtn) {
      collapseBtn.addEventListener('click', function() {
        document.querySelectorAll('.suite-section, .test-card, .test-steps, .attachment-panel').forEach(function(el) {
          el.open = false;
        });
      });
    }

    var modal = document.getElementById('traceModal');
    var frame = document.getElementById('traceFrame');
    var title = document.getElementById('traceModalTitle');
    var activeTraceBlobUrl = null;

    function pad3(n) {
      return String(n).padStart(3, '0');
    }

    function buildChunkedTraceBlobUrl(source, chunkCount) {
      var buffers = [];
      var index = 0;

      function loadNext() {
        if (index >= chunkCount) {
          var blob = new Blob(buffers, { type: 'application/zip' });
          return Promise.resolve(URL.createObjectURL(blob));
        }

        var partPath = 'data/attachments/' + source + '.part' + pad3(index);
        return fetch(partPath).then(function(resp) {
          if (!resp.ok) {
            throw new Error('Failed to load trace chunk: ' + partPath);
          }
          return resp.arrayBuffer();
        }).then(function(buf) {
          buffers.push(buf);
          index += 1;
          return loadNext();
        });
      }

      return loadNext();
    }

    window.openTraceModal = function(url, label, blobUrl) {
      if (!modal || !frame) return;
      if (activeTraceBlobUrl) {
        URL.revokeObjectURL(activeTraceBlobUrl);
        activeTraceBlobUrl = null;
      }
      if (blobUrl) activeTraceBlobUrl = blobUrl;
      frame.src = url;
      title.textContent = label || 'Playwright Trace';
      modal.classList.add('show');
      document.body.style.overflow = 'hidden';
    };

    window.closeTraceModal = function() {
      if (!modal || !frame) return;
      frame.src = 'about:blank';
      if (activeTraceBlobUrl) {
        URL.revokeObjectURL(activeTraceBlobUrl);
        activeTraceBlobUrl = null;
      }
      modal.classList.remove('show');
      document.body.style.overflow = '';
    };

    document.querySelectorAll('.trace-launch').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var traceUrl = btn.getAttribute('data-trace-url');
        var traceTitle = btn.getAttribute('data-trace-title');
        var traceSource = btn.getAttribute('data-trace-source');
        var chunkCount = parseInt(btn.getAttribute('data-trace-chunks') || '0', 10);

        if (chunkCount > 1 && traceSource) {
          var original = btn.textContent;
          btn.disabled = true;
          btn.textContent = 'Preparing trace...';

          buildChunkedTraceBlobUrl(traceSource, chunkCount)
            .then(function(blobUrl) {
              var viewerUrl = 'trace-viewer/index.html?trace=' + encodeURIComponent(blobUrl);
              openTraceModal(viewerUrl, traceTitle, blobUrl);
            })
            .catch(function(err) {
              console.error(err);
              alert('Unable to open split trace.');
            })
            .finally(function() {
              btn.disabled = false;
              btn.textContent = original;
            });
          return;
        }

        openTraceModal(traceUrl, traceTitle, null);
      });
    });

    if (modal) {
      modal.addEventListener('click', function(event) {
        if (event.target === modal) closeTraceModal();
      });
    }

    document.addEventListener('keydown', function(event) {
      if (event.key === 'Escape') closeTraceModal();
    });
  })();
  ")

(defn generate!
  "Generate a compact Blockether-themed HTML report from allure-results.

   Parameters:
     results-dir - path to allure-results/ directory
     output-dir  - path to write the HTML report directory

   Options (opts map):
     :title       - report title (default: \"Allure Report\")
     :kicker      - small mono heading above title (default: \"Allure Report\")
     :subtitle    - optional subtitle under title (default: \"\")
     :results-dir - kept for CLI compatibility; generated report now packages
                     referenced attachments into its own output directory.

   Returns the output directory path on success."
  ([^String results-dir ^String output-dir]
   (generate! results-dir output-dir {}))
  ([^String results-dir ^String output-dir opts]
   (let [results (load-results results-dir)
         env (load-environment results-dir)
         title (or (:title opts) "Allure Report")
         kicker (or (:kicker opts) "Allure Report")
         subtitle (or (:subtitle opts) "")
         out (io/file output-dir)]
     (when (empty? results)
       (println (str "No allure results found in " results-dir "/"))
       (println "Generating an empty report placeholder."))
     (clean-dir! out)
     (copy-attachments! results-dir out results)
     (when (some trace-attachment? (collect-all-attachments results))
       (ensure-trace-viewer! out))
     (let [cts (count-by-status results)
           total-ms (total-duration-ms results)
           total (long (get cts :total 0))
           passed (long (get cts :passed 0))
           pass-rate (if (pos? total)
                       (int (* 100.0 (/ (double passed) (double total))))
                       0)
           suites (group-by-suite results)
           start-ts (reduce min Long/MAX_VALUE (keep #(get % "start") results))
           html (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>" (html-escape title) "</title>
  <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">
  <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>
  <link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap\" rel=\"stylesheet\">
  <link href=\"https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&display=swap\" rel=\"stylesheet\">
  <style>" (css) "</style>
</head>
<body>
<div class=\"page-shell\">
  <header class=\"report-header\" id=\"summary\">
    <div class=\"report-header-main\">
      <div class=\"report-kicker\">" (html-escape kicker) "</div>
      <h1 class=\"report-title\">" (html-escape title) "</h1>
      " (if (seq subtitle)
          (str "<div class=\"report-subtitle\">" (html-escape subtitle) "</div>")
          "") "
    </div>
    <div class=\"report-meta\">"
                  (render-summary-chip "Total" (:total cts) "summary-chip-total")
                  (render-summary-chip "Passed" (:passed cts) "summary-chip-passed")
                  (render-summary-chip "Failed" (:failed cts) "summary-chip-failed")
                  (render-summary-chip "Broken" (:broken cts) "summary-chip-broken")
                  (render-summary-chip "Skipped" (:skipped cts) "summary-chip-skipped")
                  (render-summary-chip "Duration" (format-duration total-ms) "summary-chip-duration")
                  (render-summary-chip "Suites" (count suites) "summary-chip-suites")
                  (render-summary-chip "Pass rate" (str pass-rate "%") "summary-chip-total")
                  "</div>
  </header>

  <div class=\"toolbar\">
    <div class=\"filter-bar\">
      <button class=\"filter-btn active\" data-filter=\"all\">All (" (:total cts) ")</button>
      <button class=\"filter-btn\" data-filter=\"passed\">Passed (" (:passed cts) ")</button>
      <button class=\"filter-btn\" data-filter=\"failed\">Failed (" (:failed cts) ")</button>
      <button class=\"filter-btn\" data-filter=\"broken\">Broken (" (:broken cts) ")</button>"
                  (when (pos? (long (get cts :skipped 0)))
                    (str "<button class=\"filter-btn\" data-filter=\"skipped\">Skipped (" (:skipped cts) ")</button>"))
                  "</div>
    <div class=\"toolbar-actions\">
      <input type=\"text\" id=\"searchInput\" class=\"toolbar-search\" placeholder=\"Search tests...\" autocomplete=\"off\" />
      <select id=\"sortSelect\" class=\"toolbar-sort\">
        <option value=\"status\">Sort: Status</option>
        <option value=\"longest\">Sort: Longest first</option>
        <option value=\"shortest\">Sort: Shortest first</option>
        <option value=\"name\">Sort: Name A-Z</option>
      </select>
      <button type=\"button\" class=\"toolbar-btn\" data-action=\"expand-suites\">Expand</button>
      <button type=\"button\" class=\"toolbar-btn\" data-action=\"collapse-suites\">Collapse</button>
    </div>
  </div>

  <details class=\"panel environment-panel\" id=\"environment\">
    <summary>" (detail-marker) "<span>Environment (" (count env) ")</span></summary>
    <div class=\"panel-body\">"
                  (if (seq env)
                    (str "<div class=\"env-grid\">"
                      (str/join ""
                        (for [[k v] (sort-by first env)]
                          (str "<div class=\"env-item\"><div class=\"env-key\">" (html-escape k) "</div>"
                            "<div class=\"env-value\">" (html-escape v) "</div></div>")))
                      "</div>")
                    "<div class=\"empty-state\"><p>No environment data</p></div>")
                  "</div>
  </details>

  <section id=\"suites\">
    <h2 class=\"section-heading\">Test suites</h2>"
                  (if (seq suites)
                    (let [suite-names (keys suites)
                          common-prefix (longest-common-prefix (map suite-prefix-candidate suite-names))]
                      (str
                        (when (and common-prefix (not (str/blank? common-prefix)))
                          (str "<div class=\"suite-common-prefix\">" (html-escape common-prefix) "</div>"))
                        (str/join ""
                          (for [[suite-name suite-results] suites]
                            (let [short-name (strip-prefix common-prefix suite-name)
                                  display-name (if (str/blank? short-name)
                                                 (last (str/split suite-name #"\."))
                                                 short-name)]
                              (render-suite-section display-name suite-results results-dir))))))
                    "<div class=\"empty-state\"><p>No test result files were found for this run.</p></div>")
                  "
  </section>
</div>

<div id=\"traceModal\" class=\"trace-modal\">
  <div class=\"trace-modal-content\">
    <div class=\"trace-modal-bar\">
      <div id=\"traceModalTitle\" class=\"trace-modal-title\">Playwright Trace</div>
      <button type=\"button\" class=\"trace-modal-close\" onclick=\"closeTraceModal()\">Close</button>
    </div>
    <iframe id=\"traceFrame\" class=\"trace-frame\" src=\"about:blank\" loading=\"eager\"></iframe>
  </div>
</div>

<script>" (js-ui) "</script>
</body>
</html>")]
       (spit (io/file out "index.html") html)
       (enhance-report-shell! out)
       (println (str "Blockether report generated: " output-dir "/index.html"))
       (println (str "  " (:total cts) " tests: "
                  (:passed cts) " passed, "
                  (:failed cts) " failed, "
                  (:broken cts) " broken, "
                  (:skipped cts) " skipped"))
       (println (str "  Duration: " (format-duration total-ms)))
       (println (str "  Pass rate: " pass-rate "%"))
       (when (seq results)
         (println (str "  Started: " (if (= Long/MAX_VALUE start-ts) "N/A" (format-ts start-ts)))))
       output-dir))))
