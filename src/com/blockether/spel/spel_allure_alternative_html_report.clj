(ns com.blockether.spel.spel-allure-alternative-html-report
  "Blockether-themed Allure report renderer.
   Reads allure-results/ JSON files and generates a standalone HTML report
   using a warm Blockether palette, compact investigation-first layout,
   and shared attachment UX reused from the standard Allure report helpers."
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

(defn- group-by-suite
  "Group results by parentSuite > suite hierarchy."
  [results]
  (let [groups (group-by
                 (fn [r]
                   (let [parent (or (label-value r "parentSuite") "")
                         suite (or (label-value r "suite") "unknown")]
                     (str/trim (str parent " / " suite))))
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

(defn- copy-attachments!
  [^String results-dir ^File out results]
  (let [dest-dir (io/file out "data" "attachments")]
    (.mkdirs dest-dir)
    (doseq [attachment (collect-all-attachments results)
            :let [source (get attachment "source")]
            :when (seq source)]
      (let [src (io/file results-dir source)
            dest (io/file dest-dir source)]
        (when (.isFile src)
          (io/make-parents dest)
          (io/copy src dest))))))

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
        content (slurp-safe file)]
    (cond
      (trace-attachment? attachment)
      (str "<div class=\"attachment-actions attachment-actions-trace\">"
           "<button type=\"button\" class=\"attachment-link attachment-link-button trace-launch\""
           " data-trace-url=\"" (trace-viewer-href attachment) "\""
           " data-trace-title=\"" att-name "\">Open Trace</button>"
           "<a class=\"attachment-link attachment-link-subtle\" href=\"" href "\" download>Download zip</a>"
           "</div>")

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
    (str "<details class=\"test-card " (status-class status) "\" data-status=\"" status "\">"
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
  "Compact warm Blockether stylesheet for the alternative report."
  []
  "
  :root {
    --bg: #f6f0e7;
    --bg-panel: rgba(255, 251, 245, 0.96);
    --bg-panel-strong: rgba(250, 242, 232, 0.98);
    --bg-code: #f2e8db;
    --bg-accent: rgba(140, 77, 25, 0.08);
    --border: rgba(110, 79, 47, 0.28);
    --border-strong: rgba(110, 79, 47, 0.45);
    --text: #18202a;
    --text-secondary: #394350;
    --text-muted: #596779;
    --accent: #8b4d19;
    --accent-green: #227850;
    --accent-yellow: #8d5d13;
    --accent-red: #a83228;
    --accent-teal: #0d6a62;
    --shadow: 0 8px 24px rgba(38, 26, 15, 0.08);
    --radius-lg: 16px;
    --radius-md: 12px;
    --radius-sm: 8px;
    --header-gap: 0.65rem;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #14191f;
      --bg-panel: rgba(26, 32, 39, 0.98);
      --bg-panel-strong: rgba(31, 39, 48, 0.98);
      --bg-code: #1c2430;
      --bg-accent: rgba(193, 125, 58, 0.12);
      --border: rgba(255, 255, 255, 0.16);
      --border-strong: rgba(255, 255, 255, 0.28);
      --text: #eef2f7;
      --text-secondary: #c2cbd7;
      --text-muted: #9ba8b8;
      --accent: #da8a45;
      --accent-green: #379e72;
      --accent-yellow: #d6a64a;
      --accent-red: #ef7265;
      --accent-teal: #4cbab0;
      --shadow: 0 10px 28px rgba(0, 0, 0, 0.32);
    }
  }
  *, *::before, *::after { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    font-family: 'Atkinson Hyperlegible', 'Segoe UI', sans-serif;
    font-size: 14px;
    line-height: 1.45;
    color: var(--text);
    background:
      radial-gradient(circle at top left, rgba(139, 77, 25, 0.08), transparent 24%),
      linear-gradient(180deg, #fbf7f1 0%, var(--bg) 46%, #efe6da 100%);
  }
  @media (prefers-color-scheme: dark) {
    body {
      background:
        radial-gradient(circle at top left, rgba(218, 138, 69, 0.14), transparent 20%),
        linear-gradient(180deg, #11161b 0%, var(--bg) 55%, #192028 100%);
    }
  }
  h1, h2, h3, h4 {
    margin: 0;
    font-family: 'Manrope', 'Atkinson Hyperlegible', sans-serif;
    line-height: 1.12;
    color: var(--text);
  }
  code, pre, .mono {
    font-family: 'IBM Plex Mono', ui-monospace, monospace;
  }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  button { font: inherit; }

  .page-shell {
    max-width: 1580px;
    margin: 0 auto;
    padding: 1rem 1rem 2rem;
  }

  .report-header {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    justify-content: space-between;
    gap: 1rem;
    padding: 1rem 1.1rem;
    margin-bottom: 0.85rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: linear-gradient(180deg, var(--bg-panel), var(--bg-panel-strong));
    box-shadow: var(--shadow);
  }
  .report-header-main {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    min-width: 280px;
  }
  .report-kicker {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.72rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--accent);
  }
  .report-title {
    font-size: clamp(1.35rem, 2.6vw, 2rem);
    letter-spacing: -0.03em;
  }
  .report-subtitle {
    color: var(--text-secondary);
    font-size: 0.82rem;
  }
  .report-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    justify-content: flex-end;
  }
  .summary-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    padding: 0.42rem 0.72rem;
    border-radius: 999px;
    background: var(--bg-accent);
    border: 1px solid var(--border);
    color: var(--text);
    white-space: nowrap;
  }
  .summary-chip-label {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.68rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
  }
  .summary-chip-value {
    font-weight: 800;
    font-size: 0.86rem;
  }
  .summary-chip-passed .summary-chip-value { color: var(--accent-green); }
  .summary-chip-failed .summary-chip-value { color: var(--accent-red); }
  .summary-chip-broken .summary-chip-value { color: var(--accent-yellow); }
  .summary-chip-skipped .summary-chip-value { color: var(--text-secondary); }
  .summary-chip-total .summary-chip-value,
  .summary-chip-suites .summary-chip-value { color: var(--accent); }
  .summary-chip-duration .summary-chip-value { color: var(--accent-teal); }

  .toolbar {
    position: sticky;
    top: 0.5rem;
    z-index: 6;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.65rem;
    margin-bottom: 0.85rem;
    padding: 0.65rem 0.8rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: rgba(255, 250, 244, 0.88);
    backdrop-filter: blur(10px);
    box-shadow: var(--shadow);
  }
  @media (prefers-color-scheme: dark) {
    .toolbar { background: rgba(20, 27, 34, 0.88); }
  }
  .filter-bar,
  .toolbar-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.45rem;
    align-items: center;
  }
  .filter-btn,
  .toolbar-btn,
  .attachment-link,
  .attachment-link-button {
    border: 1px solid var(--border);
    border-radius: 999px;
    background: transparent;
    color: var(--text-secondary);
    padding: 0.34rem 0.74rem;
    cursor: pointer;
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.7rem;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  .filter-btn:hover,
  .toolbar-btn:hover,
  .attachment-link:hover,
  .attachment-link-button:hover {
    text-decoration: none;
    color: var(--text);
    border-color: var(--border-strong);
    background: rgba(139, 77, 25, 0.08);
  }
  .filter-btn.active { background: var(--accent); border-color: var(--accent); color: #fff; }
  .filter-btn[data-filter='passed'].active { background: var(--accent-green); border-color: var(--accent-green); }
  .filter-btn[data-filter='failed'].active { background: var(--accent-red); border-color: var(--accent-red); }
  .filter-btn[data-filter='broken'].active { background: var(--accent-yellow); border-color: var(--accent-yellow); }
  .filter-btn[data-filter='skipped'].active { background: var(--text-secondary); border-color: var(--text-secondary); }
  .attachment-link-button { display: inline-flex; align-items: center; }
  .attachment-link-subtle { color: var(--text-muted); }

  .panel {
    margin-bottom: 0.85rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    box-shadow: var(--shadow);
  }
  .panel > summary {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.75rem 0.9rem;
    cursor: pointer;
    list-style: none;
    font-weight: 700;
  }
  .panel > summary::-webkit-details-marker { display: none; }
  .panel-body {
    padding: 0 0.9rem 0.9rem;
    border-top: 1px solid var(--border);
  }
  .env-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 0.45rem;
  }
  .env-item {
    padding: 0.55rem 0.7rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    background: rgba(255, 255, 255, 0.35);
  }
  @media (prefers-color-scheme: dark) {
    .env-item { background: rgba(255, 255, 255, 0.03); }
  }
  .env-key {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.66rem;
    color: var(--text-muted);
    text-transform: uppercase;
    letter-spacing: 0.07em;
  }
  .env-value {
    margin-top: 0.18rem;
    font-weight: 700;
  }

  .section-heading {
    margin: 0 0 0.55rem;
    font-size: 1rem;
    letter-spacing: -0.02em;
  }

  .suite-section {
    margin-bottom: 0.8rem;
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
    gap: 0.65rem;
    padding: 0.78rem 0.95rem;
    cursor: pointer;
    list-style: none;
    background: linear-gradient(180deg, rgba(139, 77, 25, 0.06), transparent);
  }
  .suite-section > summary::-webkit-details-marker { display: none; }
  .suite-body {
    padding: 0 0.75rem 0.75rem;
    border-top: 1px solid var(--border);
  }
  .suite-title {
    flex: 1;
    min-width: 240px;
    font-size: 0.96rem;
    font-weight: 800;
    letter-spacing: -0.01em;
  }
  .suite-summary-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    justify-content: flex-end;
  }
  .suite-stat,
  .test-chip {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.66rem;
    padding: 0.18rem 0.44rem;
    border-radius: 999px;
    border: 1px solid var(--border);
    color: var(--text-secondary);
    background: rgba(255, 255, 255, 0.28);
  }
  .stat-passed { color: var(--accent-green); }
  .stat-failed { color: var(--accent-red); }
  .stat-broken { color: var(--accent-yellow); }
  .stat-skipped { color: var(--text-muted); }
  .stat-total { color: var(--accent); }

  .test-card {
    margin-top: 0.6rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: rgba(255, 255, 255, 0.42);
    box-shadow: 0 4px 12px rgba(0,0,0,0.03);
    overflow: hidden;
  }
  @media (prefers-color-scheme: dark) {
    .test-card { background: rgba(255, 255, 255, 0.02); }
  }
  .test-card.status-failed { border-left: 4px solid var(--accent-red); }
  .test-card.status-broken { border-left: 4px solid var(--accent-yellow); }
  .test-card.status-passed { border-left: 4px solid var(--accent-green); }
  .test-card.status-skipped { border-left: 4px solid var(--text-muted); }
  .test-card > summary {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    padding: 0.58rem 0.72rem;
    cursor: pointer;
    list-style: none;
  }
  .test-card > summary::-webkit-details-marker { display: none; }
  .test-card-body {
    padding: 0 0.75rem 0.8rem;
    border-top: 1px solid var(--border);
  }
  .test-status-badge {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.64rem;
    font-weight: 700;
    padding: 0.16rem 0.46rem;
    border-radius: 999px;
    color: #fff;
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .status-passed { background: var(--accent-green); }
  .status-failed { background: var(--accent-red); }
  .status-broken { background: var(--accent-yellow); }
  .status-skipped { background: var(--text-muted); }
  .status-unknown { background: var(--text-secondary); }
  .test-name {
    flex: 1;
    min-width: 180px;
    font-size: 0.89rem;
    font-weight: 800;
  }
  .test-duration {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.68rem;
    color: var(--text-secondary);
    white-space: nowrap;
  }
  .test-full-name {
    margin-top: 0.55rem;
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.68rem;
    color: var(--text-muted);
    word-break: break-word;
  }
  .test-labels {
    display: flex;
    flex-wrap: wrap;
    gap: 0.28rem;
    margin-top: 0.5rem;
  }
  .label-pill {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.68rem; padding: 0.18rem 0.5rem;
    padding: 0.12rem 0.42rem;
    border-radius: 999px;
    border: 1px solid transparent;
  }
  .label-epic { background: rgba(139, 77, 25, 0.12); color: var(--accent); }
  .label-feature { background: rgba(13, 106, 98, 0.12); color: var(--accent-teal); }
  .label-story { background: rgba(141, 93, 19, 0.12); color: var(--accent-yellow); }
  .label-severity { background: rgba(168, 50, 40, 0.12); color: var(--accent-red); }
  .label-tag { background: rgba(57, 67, 80, 0.12); color: var(--text-secondary); }
  .test-description,
  .test-error {
    margin-top: 0.55rem;
    padding: 0.62rem 0.72rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
  }
  .test-description { background: rgba(255, 255, 255, 0.28); color: var(--text-secondary); }
  .test-error { background: rgba(168, 50, 40, 0.07); }
  .error-message { color: var(--accent-red); white-space: pre-wrap; word-break: break-word; }

  .test-steps,
  .attachment-panel {
    margin-top: 0.55rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: rgba(255, 255, 255, 0.18);
    overflow: hidden;
  }
  @media (prefers-color-scheme: dark) {
    .test-steps,
    .attachment-panel { background: rgba(255, 255, 255, 0.02); }
  }
  .test-steps > summary,
  .attachment-panel > summary {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    padding: 0.55rem 0.68rem;
    cursor: pointer;
    list-style: none;
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.7rem;
    color: var(--text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .test-steps > summary::-webkit-details-marker,
  .attachment-panel > summary::-webkit-details-marker { display: none; }
  .disclosure-marker {
    width: 0;
    height: 0;
    border-top: 5px solid transparent;
    border-bottom: 5px solid transparent;
    border-left: 6px solid currentColor;
    transition: transform 0.15s ease;
  }
  details[open] > summary .disclosure-marker { transform: rotate(90deg); }

  .step-tree {
    list-style: none;
    margin: 0;
    padding: 0 0.68rem 0.68rem 1rem;
  }
  .step-item {
    margin-top: 0.45rem;
    padding-left: 0.65rem;
    border-left: 1px dashed var(--border-strong);
  }
  .step-header {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    align-items: baseline;
  }
  .step-icon { font-size: 0.74rem; }
  .step-name {
    font-size: 0.88rem;
    font-weight: 500;
    color: var(--text);
  }
  .step-item { padding: 0.4rem 0.2rem; }
  .step-params {
    font-family: 'IBM Plex Mono', monospace;
    font-size: 0.66rem;
    color: var(--text-muted);
  }

  .attachment-list {
    display: grid;
    gap: 0.45rem;
    margin-top: 0.55rem;
  }
  .attachment-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    margin-top: 0.5rem;
  }
  .attachment-pre {
    margin: 0;
    padding: 0.75rem;
    background: var(--bg-code);
    border-top: 1px solid var(--border);
    overflow: auto;
    max-height: 28rem;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 0.72rem;
    line-height: 1.55;
    color: var(--text);
  }
  .attachment-missing {
    padding: 0.75rem;
    color: var(--text-muted);
  }
  .attachment-image-link {
    display: block;
    padding: 0 0.75rem 0.75rem;
  }
  .attachment-image {
    display: block;
    max-width: 100%;
    border-radius: 10px;
    border: 1px solid var(--border);
    box-shadow: var(--shadow);
    background: rgba(255,255,255,0.6);
  }

  .empty-state {
    padding: 2rem 1rem;
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--bg-panel);
    color: var(--text-secondary);
    text-align: center;
  }

  .trace-modal {
    display: none;
    position: fixed;
    inset: 0;
    z-index: 9998;
    background: rgba(10, 12, 15, 0.78);
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
    background: #0f141a;
    border-radius: 16px;
    overflow: hidden;
    border: 1px solid rgba(255,255,255,0.14);
    display: grid;
    grid-template-rows: auto 1fr;
    box-shadow: 0 24px 48px rgba(0,0,0,0.4);
  }
  .trace-modal-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.7rem 0.9rem;
    background: rgba(255,255,255,0.04);
    color: #fff;
  }
  .trace-modal-title {
    font-size: 0.85rem;
    font-weight: 700;
  }
  .trace-modal-close {
    border: 1px solid rgba(255,255,255,0.18);
    border-radius: 999px;
    background: transparent;
    color: #fff;
    padding: 0.3rem 0.7rem;
    cursor: pointer;
  }
  .trace-frame {
    width: 100%;
    height: 100%;
    border: 0;
    background: #11161c;
  }

  ::-webkit-scrollbar { width: 8px; height: 8px; }
  ::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: 999px; }
  ::-webkit-scrollbar-track { background: transparent; }

  @media print {
    .toolbar,
    .trace-modal { display: none !important; }
    .page-shell { max-width: none; padding: 0; }
    .report-header,
    .suite-section,
    .panel,
    .test-card { box-shadow: none; }
    details[open] > summary .disclosure-marker,
    .disclosure-marker { display: none; }
  }

    /* Markdown HTTP & Badges Overrides */
  .spel-md .http-title { background: var(--bg-panel-strong) !important; border-bottom: 1px solid var(--border-strong) !important; color: var(--text) !important; }
  .spel-md .http-url { color: var(--text) !important; }
  .spel-md .http-card { border-color: var(--border-strong) !important; background: var(--bg-panel) !important; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
  .spel-md .http-card.req .card-hdr { background: rgba(73,204,144,0.1) !important; color: var(--accent-green) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-card.res .card-hdr { background: rgba(97,175,254,0.1) !important; color: var(--accent-teal) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-card.curl .card-hdr { background: rgba(252,161,48,0.1) !important; color: var(--accent-yellow) !important; border-bottom-color: var(--border) !important; }
  .spel-md .http-section { border-top-color: var(--border) !important; }
  .spel-md .section-hdr { color: var(--text-muted) !important; }
  .spel-md .code-wrap pre { background: var(--bg-code) !important; color: var(--text) !important; border: 1px solid var(--border); }
  .spel-md .copy-btn { background: var(--bg-panel) !important; border-color: var(--border) !important; color: var(--text-secondary) !important; }
  .spel-md .copy-btn:hover { background: var(--bg-panel-strong) !important; color: var(--text) !important; }

  .spel-badge { border-radius: var(--radius-sm) !important; padding: 2px 8px !important; font-size: 0.75em !important; border: 1px solid var(--border-strong) !important; }
  .spel-badge.api { background: rgba(13,106,98,0.12) !important; color: var(--accent-teal) !important; }
  .spel-badge.ui { background: rgba(141,93,19,0.12) !important; color: var(--accent-yellow) !important; }
  .spel-badge.ui-api { background: rgba(139,77,25,0.12) !important; color: var(--accent) !important; }

  @media (max-width: 900px) {
    .page-shell { padding: 0.4rem; }
    .report-header { padding: 0.75rem; gap: 0.6rem; border-radius: var(--radius-md); }
    .toolbar { top: 0; padding: 0.6rem; gap: 0.5rem; border-radius: var(--radius-md); margin-bottom: 0.6rem; }
    .suite-section > summary, .test-card > summary { align-items: flex-start; padding: 0.6rem 0.75rem; }
    .report-meta { justify-content: flex-start; }
    .test-card-body { padding: 0 0.6rem 0.6rem; }
    .attachment-pre { padding: 0.6rem; font-size: 0.68rem; }
  }
  }
  ")

(defn- js-ui
  "Client-side filtering, collapsing, and trace modal behavior."
  []
  "
  (function() {
    function visibleTestCards(section) {
      return Array.from(section.querySelectorAll('.test-card')).filter(function(card) {
        return card.style.display !== 'none';
      });
    }

    function filterTests(status) {
      var cards = document.querySelectorAll('.test-card');
      var sections = document.querySelectorAll('.suite-section');
      var btns = document.querySelectorAll('.filter-btn');

      btns.forEach(function(btn) {
        btn.classList.toggle('active', btn.getAttribute('data-filter') === status);
      });

      cards.forEach(function(card) {
        var match = status === 'all' || card.getAttribute('data-status') === status;
        card.style.display = match ? '' : 'none';
      });

      sections.forEach(function(section) {
        section.style.display = visibleTestCards(section).length > 0 ? '' : 'none';
      });
    }

    document.querySelectorAll('.filter-btn').forEach(function(btn) {
      btn.addEventListener('click', function() {
        filterTests(btn.getAttribute('data-filter'));
      });
    });

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
        document.querySelectorAll('.suite-section, .test-card, .test-steps, .attachment-panel').forEach(function(section) {
          section.open = false;
        });
      });
    }

    var modal = document.getElementById('traceModal');
    var frame = document.getElementById('traceFrame');
    var title = document.getElementById('traceModalTitle');

    window.openTraceModal = function(url, label) {
      if (!modal || !frame) return;
      frame.src = url;
      title.textContent = label || 'Playwright Trace';
      modal.classList.add('show');
      document.body.style.overflow = 'hidden';
    };

    window.closeTraceModal = function() {
      if (!modal || !frame) return;
      frame.src = 'about:blank';
      modal.classList.remove('show');
      document.body.style.overflow = '';
    };

    document.querySelectorAll('.trace-launch').forEach(function(btn) {
      btn.addEventListener('click', function() {
        openTraceModal(btn.getAttribute('data-trace-url'), btn.getAttribute('data-trace-title'));
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
     :title       - report title (default: \"Test Report\")
     :results-dir - kept for CLI compatibility; generated report now packages
                    referenced attachments into its own output directory.

   Returns the output directory path on success."
  ([^String results-dir ^String output-dir]
   (generate! results-dir output-dir {}))
  ([^String results-dir ^String output-dir opts]
   (let [results (load-results results-dir)
         env (load-environment results-dir)
         title (or (:title opts) "Test Report")
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
  <link href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400;1,700&display=swap\" rel=\"stylesheet\">
  <link href=\"https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&display=swap\" rel=\"stylesheet\">
  <link href=\"https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600;700&display=swap\" rel=\"stylesheet\">
  <style>" (css) "</style>
</head>
<body>
<div class=\"page-shell\">
  <header class=\"report-header\" id=\"summary\">
    <div class=\"report-header-main\">
      <div class=\"report-kicker\">Blockether alternative report</div>
      <h1 class=\"report-title\">" (html-escape title) "</h1>
      <div class=\"report-subtitle\">Compact investigation-first view with warm Blockether palette.</div>
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
      <button type=\"button\" class=\"toolbar-btn\" data-action=\"expand-suites\">Expand visible</button>
      <button type=\"button\" class=\"toolbar-btn\" data-action=\"collapse-suites\">Collapse all</button>
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
                  (str/join ""
                    (for [[suite-name suite-results] suites]
                      (render-suite-section suite-name suite-results results-dir)))
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
