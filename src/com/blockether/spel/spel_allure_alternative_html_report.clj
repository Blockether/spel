(ns com.blockether.spel.spel-allure-alternative-html-report
  "Blockether-themed Allure report renderer.
   Reads allure-results/ JSON files and generates a standalone HTML report
   using the Blockether design system (warm earth tones, Atkinson Hyperlegible,
   Manrope, IBM Plex Mono). No Node.js required.

   Usage:
     CLI: spel report [options]
     API: (alternative-report/generate! \"allure-results\" \"block-report\")"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.text SimpleDateFormat]
   [java.util Date TimeZone]))

(defn load-results
  "Load all *-result.json files from the given directory."
  [^String results-dir]
  (let [dir (io/file results-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
        (filter #(and (.isFile ^File %)
                   (str/ends-with? (.getName ^File %) "-result.json")))
        (keep (fn [^File f]
                (try
                  (json/read-json (slurp f))
                  (catch Exception _ nil))))
        (vec)))))

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

(defn- count-by-status
  [results]
  (let [freqs (frequencies (map #(get % "status" "unknown") results))]
    {:passed  (long (get freqs "passed" 0))
     :failed  (long (get freqs "failed" 0))
     :broken  (long (get freqs "broken" 0))
     :skipped (long (get freqs "skipped" 0))
     :unknown (long (get freqs "unknown" 0))
     :total   (count results)}))

(defn- total-duration-ms
  [results]
  (reduce + 0
    (keep (fn [r]
            (when (and (get r "start") (get r "stop"))
              (- (long (get r "stop")) (long (get r "start")))))
      results)))

(defn- format-duration
  [^long ms]
  (let [secs (quot ms 1000)
        mins (quot secs 60)
        hours (quot mins 60)]
    (cond
      (pos? hours) (format "%dh %dm %ds" (int hours) (int (mod mins 60)) (int (mod secs 60)))
      (pos? mins) (format "%dm %ds" (int mins) (int (mod secs 60)))
      :else (format "%.1fs" (/ (double ms) 1000.0)))))

(defn- format-ts
  [^long ms]
  (let [sdf (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")]
    (.setTimeZone sdf (TimeZone/getDefault))
    (.format sdf (Date. ms))))

(defn- html-escape
  [^String s]
  (when s
    (-> s
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
    (first)))

(defn- group-by-suite
  "Group results by parentSuite > suite hierarchy."
  [results]
  (let [groups (group-by
                 (fn [r]
                   (let [parent (or (label-value r "parentSuite") "")
                         suite (or (label-value r "suite") "unknown")]
                     (str parent " / " suite)))
                 results)]
    (into (sorted-map) groups)))

(defn- status-class
  [^String status]
  (case status
    "passed" "status-passed"
    "failed" "status-failed"
    "broken" "status-broken"
    "skipped" "status-skipped"
    "status-unknown"))

(defn- status-icon
  [^String status]
  (case status
    "passed" "&#10003;"
    "failed" "&#10007;"
    "broken" "&#9888;"
    "skipped" "&#9744;"
    "?"))

(defn- render-steps-html
  "Render nested step tree as HTML."
  [steps ^long depth]
  (when (seq steps)
    (str "<ul class=\"step-tree depth-" (unchecked-inc depth) "\">"
      (str/join ""
        (for [step steps]
          (let [name (html-escape (get step "name" "step"))
                st (get step "status" "unknown")
                child-steps (get step "steps")
                attachments (get step "attachments")
                params (get step "parameters")]
            (str "<li class=\"step-item " (status-class st) "\">"
              "<div class=\"step-header\">"
              "<span class=\"step-icon\">" (status-icon st) "</span>"
              "<span class=\"step-name\">" name "</span>"
              (when (seq params)
                (str "<span class=\"step-params\">"
                  (str/join ", "
                    (for [p params]
                      (str (html-escape (get p "name")) "=" (html-escape (get p "value")))))
                  "</span>"))
              "</div>"
              (when (seq child-steps)
                (render-steps-html child-steps (unchecked-inc depth)))
              (when (seq attachments)
                (str "<div class=\"step-attachments\">"
                  (str/join ""
                    (for [a attachments]
                      (str "<span class=\"attachment-badge\">"
                        (html-escape (get a "name"))
                        "</span>")))
                  "</div>"))
              "</li>"))))
      "</ul>")))

(defn- render-test-card
  "Render a single test result as an HTML card."
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
        _trace (when status-detail (get status-detail "trace"))
        attachments (get result "attachments")
        labels (get result "labels")
        epic (label-value result "epic")
        feature (label-value result "feature")
        story (label-value result "story")
        severity (label-value result "severity")
        tags (->> labels (filter #(= "tag" (get % "name"))) (map #(get % "value")))]
    (str
      "<div class=\"test-card " (status-class status) "\" data-status=\"" status "\">"
      "<div class=\"test-card-header\">"
      "<span class=\"test-status-badge " (status-class status) "\">"
      (status-icon status) " " (str/upper-case status)
      "</span>"
      "<span class=\"test-name\">" name "</span>"
      (when duration
        (str "<span class=\"test-duration\">" (format-duration (long duration)) "</span>"))
      "</div>"
      "<div class=\"test-card-meta\">"
      "<span class=\"test-full-name\">" full-name "</span>"
      "</div>"
      (when (or epic feature story severity (seq tags))
        (str "<div class=\"test-labels\">"
          (when epic (str "<span class=\"label-pill label-epic\">" (html-escape epic) "</span>"))
          (when feature (str "<span class=\"label-pill label-feature\">" (html-escape feature) "</span>"))
          (when story (str "<span class=\"label-pill label-story\">" (html-escape story) "</span>"))
          (when severity (str "<span class=\"label-pill label-severity\">" (html-escape severity) "</span>"))
          (str/join "" (for [t tags] (str "<span class=\"label-pill label-tag\">" (html-escape t) "</span>")))
          "</div>"))
      (when desc
        (str "<div class=\"test-description\">" (html-escape desc) "</div>"))
      (when (and message (not= status "passed"))
        (str "<div class=\"test-error\">"
          "<div class=\"error-message\">" (html-escape message) "</div>"
          "</div>"))
      (when (seq steps)
        (str "<details class=\"test-steps\">"
          "<summary>Show steps (" (count steps) ")</summary>"
          (render-steps-html steps 0)
          "</details>"))
      (when (seq attachments)
        (str "<div class=\"test-attachments\">"
          "<span class=\"attachments-label\">Attachments:</span>"
          (str/join ""
            (for [a attachments]
              (let [source (get a "source")
                    att-type (get a "type" "")
                    att-name (html-escape (get a "name" "attachment"))
                    is-image (str/starts-with? att-type "image/")
                    href (if results-dir (str results-dir "/" source) source)]
                (if is-image
                  (str "<a class=\"attachment-link\" href=\"" href "\" target=\"_blank\">"
                    att-name "</a>")
                  (str "<a class=\"attachment-link\" href=\"" href "\" target=\"_blank\">"
                    att-name "</a>")))))
          "</div>"))
      "</div>")))

(defn- render-suite-section
  "Render a suite group with its test cards."
  [suite-name results results-dir]
  (str
    "<section class=\"suite-section\">"
    "<h3 class=\"suite-title\">" (html-escape suite-name) "</h3>"
    "<div class=\"suite-stats\">"
    (let [cts (count-by-status results)
          failed (long (get cts :failed 0))
          broken (long (get cts :broken 0))
          skipped (long (get cts :skipped 0))]
      (str
        "<span class=\"suite-stat stat-passed\">" (:passed cts) " passed</span>"
        (when (pos? failed)
          (str "<span class=\"suite-stat stat-failed\">" (:failed cts) " failed</span>"))
        (when (pos? broken)
          (str "<span class=\"suite-stat stat-broken\">" (:broken cts) " broken</span>"))
        (when (pos? skipped)
          (str "<span class=\"suite-stat stat-skipped\">" (:skipped cts) " skipped</span>"))
        "<span class=\"suite-stat stat-total\">" (:total cts) " total</span>"))
    "</div>"
    (str/join "" (map #(render-test-card % results-dir) (sort-by #(get % "status") results)))
    "</section>"))

(defn- css
  "The complete Blockether-themed CSS for the report."
  []
  "
    :root {
      --bg: #f6f1e8;
      --bg-secondary: rgba(255, 251, 245, 0.88);
      --bg-tertiary: #ebe3d7;
      --bg-elevated: rgba(255, 255, 255, 0.94);
      --border: rgba(125, 99, 68, 0.18);
      --border-strong: rgba(125, 99, 68, 0.34);
      --text: #1f2933;
      --text-secondary: #55606e;
      --accent: #b2652a;
      --accent-green: #1f8a5c;
      --accent-yellow: #b7791f;
      --accent-red: #c44536;
      --accent-teal: #0f766e;
      --shadow: 0 18px 42px rgba(43, 33, 22, 0.08);
      --shadow-soft: 0 10px 24px rgba(43, 33, 22, 0.05);
      --radius-lg: 24px;
      --radius-md: 18px;
      --radius-sm: 10px;
      --sidebar-width: 250px;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #151a20;
        --bg-secondary: rgba(26, 32, 40, 0.88);
        --bg-tertiary: #1d2530;
        --bg-elevated: rgba(24, 30, 38, 0.96);
        --border: rgba(255, 255, 255, 0.1);
        --border-strong: rgba(255, 255, 255, 0.18);
        --text: #ecf1f7;
        --text-secondary: #a9b7c8;
        --shadow: 0 22px 48px rgba(0, 0, 0, 0.32);
        --shadow-soft: 0 12px 28px rgba(0, 0, 0, 0.24);
      }
    }
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    html { scroll-behavior: smooth; }
    body {
      font-family: 'Atkinson Hyperlegible', 'Segoe UI', sans-serif;
      font-size: 15px;
      line-height: 1.7;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(178, 101, 42, 0.12), transparent 30%),
        radial-gradient(circle at top right, rgba(15, 118, 110, 0.10), transparent 28%),
        linear-gradient(180deg, #fbf7f1 0%, var(--bg) 48%, #efe7dc 100%);
      min-height: 100vh;
    }
    @media (prefers-color-scheme: dark) {
      body {
        background:
          radial-gradient(circle at top left, rgba(178, 101, 42, 0.14), transparent 24%),
          radial-gradient(circle at top right, rgba(15, 118, 110, 0.15), transparent 22%),
          linear-gradient(180deg, #12171d 0%, #151a20 52%, #1a212a 100%);
      }
    }
    h1, h2, h3, h4 {
      font-family: 'Manrope', 'Atkinson Hyperlegible', sans-serif;
      font-weight: 800;
      color: var(--text);
      line-height: 1.15;
    }
    code, pre, .mono {
      font-family: 'IBM Plex Mono', ui-monospace, monospace;
    }
    a { color: var(--accent); text-decoration: none; }
    a:hover { text-decoration: underline; }

    /* Layout */
    .layout { display: flex; min-height: 100vh; gap: 1.5rem; padding: 1.25rem; }
    .sidebar {
      position: sticky; top: 1.25rem;
      width: var(--sidebar-width); min-width: var(--sidebar-width);
      background: linear-gradient(180deg, rgba(255,252,247,0.96), rgba(248,241,231,0.92));
      border: 1px solid var(--border); border-radius: var(--radius-lg);
      padding: 1.5rem 1rem; height: calc(100vh - 2.5rem);
      overflow-y: auto; align-self: flex-start;
      box-shadow: var(--shadow-soft); backdrop-filter: blur(12px);
    }
    @media (prefers-color-scheme: dark) {
      .sidebar { background: linear-gradient(180deg, rgba(26,32,40,0.96), rgba(20,25,32,0.92)); }
    }
    .sidebar-brand {
      display: flex; align-items: center; gap: 0.5rem;
      color: var(--accent); font-weight: 800; font-size: 0.9rem;
      font-family: 'Manrope', sans-serif; letter-spacing: 0.08em;
      text-transform: uppercase; margin-bottom: 0.5rem;
    }
    .sidebar-subtitle {
      font-size: 0.72rem; color: var(--text-secondary);
      margin-bottom: 1.25rem; padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--border);
    }
    .sidebar-nav { list-style: none; }
    .sidebar-nav li { margin-bottom: 0.2rem; }
    .sidebar-nav a {
      display: block; padding: 0.55rem 0.7rem;
      color: var(--text-secondary); font-size: 0.78rem;
      border-radius: 999px; border-left: 3px solid transparent;
      transition: all 0.15s ease;
    }
    .sidebar-nav a:hover {
      background: rgba(178,101,42,0.1); color: var(--text);
      text-decoration: none; transform: translateX(2px);
    }
    .sidebar-nav a.active {
      background: linear-gradient(90deg, rgba(178,101,42,0.14), rgba(178,101,42,0.05));
      color: var(--accent); border-left-color: var(--accent); font-weight: 700;
    }
    .sidebar-footer {
      margin-top: auto; padding-top: 0.75rem;
      border-top: 1px solid var(--border);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 10px; color: var(--text-secondary);
    }
    .main-content {
      flex: 1; max-width: calc(100% - var(--sidebar-width));
      padding: 0.25rem 0 2.5rem;
    }

    /* Report header */
    .report-header {
      margin-bottom: 2rem; padding: 1.6rem;
      border: 1px solid var(--border); border-radius: var(--radius-lg);
      background: linear-gradient(135deg, rgba(255,255,255,0.9), rgba(246,238,226,0.88)),
        linear-gradient(180deg, rgba(178,101,42,0.06), rgba(15,118,110,0.04));
      box-shadow: var(--shadow);
    }
    @media (prefers-color-scheme: dark) {
      .report-header {
        background: linear-gradient(135deg, rgba(24,30,38,0.95), rgba(20,25,32,0.92)),
          linear-gradient(180deg, rgba(178,101,42,0.08), rgba(15,118,110,0.06));
      }
    }
    .report-kicker {
      display: inline-flex; align-items: center; gap: 0.5rem;
      padding: 0.3rem 0.65rem; margin-bottom: 0.75rem; border-radius: 999px;
      background: rgba(178,101,42,0.12); color: var(--accent);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.72rem; letter-spacing: 0.08em; text-transform: uppercase;
    }
    .report-title {
      font-size: clamp(1.8rem, 4vw, 3rem);
      margin-bottom: 0.5rem; letter-spacing: -0.03em;
    }
    .report-meta {
      display: flex; flex-wrap: wrap; gap: 0.7rem;
      font-size: 0.78rem; color: var(--text-secondary); margin-top: 0.75rem;
    }
    .report-meta span {
      padding: 0.45rem 0.65rem; border: 1px solid var(--border);
      border-radius: 999px; background: rgba(255,255,255,0.55);
    }
    @media (prefers-color-scheme: dark) {
      .report-meta span { background: rgba(255,255,255,0.04); }
    }
    .report-meta strong { color: var(--text); }

    /* Summary grid */
    .summary-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 0.75rem; margin-bottom: 1.5rem;
    }
    .summary-card {
      background: linear-gradient(180deg, rgba(255,255,255,0.92), rgba(251,246,239,0.96));
      border: 1px solid var(--border); border-radius: var(--radius-md);
      padding: 1rem; text-align: left;
      border-top: 4px solid var(--border);
      box-shadow: var(--shadow-soft);
    }
    @media (prefers-color-scheme: dark) {
      .summary-card {
        background: linear-gradient(180deg, rgba(24,30,38,0.98), rgba(20,26,33,0.96));
      }
    }
    .summary-card-passed { border-top-color: var(--accent-green); }
    .summary-card-failed { border-top-color: var(--accent-red); }
    .summary-card-broken { border-top-color: var(--accent-yellow); }
    .summary-card-skipped { border-top-color: var(--text-secondary); }
    .summary-card-total { border-top-color: var(--accent); }
    .summary-card-duration { border-top-color: var(--accent-teal); }
    .summary-card-suites { border-top-color: var(--accent); }
    .summary-count {
      font-size: clamp(1.6rem, 3.5vw, 2.4rem);
      font-weight: 800; line-height: 1.2; letter-spacing: -0.04em;
    }
    .summary-label {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.68rem; color: var(--text-secondary);
      text-transform: uppercase; letter-spacing: 0.12em; margin-top: 0.3rem;
    }

    /* Filter bar */
    .filter-bar {
      display: flex; flex-wrap: wrap; gap: 0.5rem;
      margin-bottom: 1.5rem; padding: 0.75rem 1rem;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-md); box-shadow: var(--shadow-soft);
    }
    .filter-btn {
      padding: 0.4rem 0.85rem; border: 1px solid var(--border);
      border-radius: 999px; background: transparent; color: var(--text-secondary);
      font-family: 'IBM Plex Mono', monospace; font-size: 0.72rem;
      cursor: pointer; transition: all 0.15s ease;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    .filter-btn:hover { background: rgba(178,101,42,0.08); color: var(--text); }
    .filter-btn.active { background: var(--accent); color: #fff; border-color: var(--accent); }
    .filter-btn[data-filter=\"passed\"].active { background: var(--accent-green); border-color: var(--accent-green); }
    .filter-btn[data-filter=\"failed\"].active { background: var(--accent-red); border-color: var(--accent-red); }
    .filter-btn[data-filter=\"broken\"].active { background: var(--accent-yellow); border-color: var(--accent-yellow); }
    .filter-btn[data-filter=\"skipped\"].active { background: var(--text-secondary); border-color: var(--text-secondary); }

    /* Environment table */
    .env-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      gap: 0.4rem; margin-bottom: 1.5rem;
    }
    .env-item {
      padding: 0.5rem 0.75rem; background: var(--bg-elevated);
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      font-size: 0.78rem;
    }
    .env-key {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.66rem; text-transform: uppercase;
      letter-spacing: 0.08em; color: var(--text-secondary);
    }
    .env-value { color: var(--text); font-weight: 600; }

    /* Suite sections */
    .suite-section {
      margin-bottom: 2rem; padding-bottom: 1.5rem;
      border-bottom: 1px solid var(--border);
    }
    .suite-section:last-child { border-bottom: none; }
    .suite-title {
      font-size: 1.1rem; margin-bottom: 0.5rem; letter-spacing: -0.02em;
      padding-left: 0.75rem; border-left: 4px solid var(--accent);
    }
    .suite-stats { display: flex; gap: 0.65rem; margin-bottom: 1rem; font-size: 0.72rem; }
    .suite-stat {
      font-family: 'IBM Plex Mono', monospace;
      padding: 0.2rem 0.5rem; border-radius: 999px;
      background: rgba(125,99,68,0.08); color: var(--text-secondary);
    }
    .stat-passed { color: var(--accent-green); }
    .stat-failed { color: var(--accent-red); }
    .stat-broken { color: var(--accent-yellow); }
    .stat-skipped { color: var(--text-secondary); }

    /* Test cards */
    .test-card {
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-md); padding: 1.1rem 1.2rem;
      margin-bottom: 0.6rem; box-shadow: var(--shadow-soft);
      backdrop-filter: blur(10px);
      transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
    }
    .test-card:hover {
      transform: translateY(-1px); box-shadow: var(--shadow);
      border-color: var(--border-strong);
    }
    .test-card.status-passed { border-left: 4px solid var(--accent-green); }
    .test-card.status-failed { border-left: 4px solid var(--accent-red); }
    .test-card.status-broken { border-left: 4px solid var(--accent-yellow); }
    .test-card.status-skipped { border-left: 4px solid var(--text-secondary); }
    .test-card.status-unknown { border-left: 4px solid var(--border); }
    .test-card-header {
      display: flex; align-items: center; gap: 0.65rem; flex-wrap: wrap;
    }
    .test-status-badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.66rem; font-weight: 700;
      padding: 0.18rem 0.55rem; border-radius: 999px;
      text-transform: uppercase; letter-spacing: 0.06em; color: #fff;
    }
    .status-passed { background: var(--accent-green); }
    .status-failed { background: var(--accent-red); }
    .status-broken { background: var(--accent-yellow); }
    .status-skipped { background: var(--text-secondary); }
    .status-unknown { background: var(--border); }
    .test-name {
      font-weight: 700; font-size: 0.92rem; color: var(--text); flex: 1;
    }
    .test-duration {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.72rem; color: var(--text-secondary);
      background: rgba(125,99,68,0.08); padding: 0.15rem 0.5rem;
      border-radius: 999px;
    }
    .test-card-meta { margin-top: 0.3rem; }
    .test-full-name {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.7rem; color: var(--text-secondary);
    }
    .test-labels { display: flex; flex-wrap: wrap; gap: 0.3rem; margin-top: 0.45rem; }
    .label-pill {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.62rem; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.05em; padding: 0.12rem 0.45rem;
      border-radius: 999px;
    }
    .label-epic { background: rgba(178,101,42,0.12); color: var(--accent); }
    .label-feature { background: rgba(15,118,110,0.12); color: var(--accent-teal); }
    .label-story { background: rgba(183,121,31,0.12); color: var(--accent-yellow); }
    .label-severity { background: rgba(196,69,54,0.12); color: var(--accent-red); }
    .label-tag { background: rgba(125,99,68,0.1); color: var(--text-secondary); }
    .test-description {
      margin-top: 0.5rem; padding: 0.5rem 0.75rem;
      background: var(--bg-secondary); border: 1px solid var(--border);
      border-radius: 0.5rem; font-size: 0.82rem; color: var(--text-secondary);
    }
    .test-error {
      margin-top: 0.5rem; padding: 0.65rem 0.85rem;
      background: rgba(196,69,54,0.06); border: 1px solid rgba(196,69,54,0.2);
      border-radius: var(--radius-sm);
    }
    .error-message {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.76rem; color: var(--accent-red);
      white-space: pre-wrap; word-break: break-word;
    }

    /* Steps */
    .test-steps { margin-top: 0.5rem; }
    .test-steps summary {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.72rem; color: var(--accent); cursor: pointer;
      padding: 0.3rem 0; list-style: none;
    }
    .test-steps summary::-webkit-details-marker { display: none; }
    .test-steps summary::before { content: '\\25B8 '; }
    .test-steps[open] summary::before { content: '\\25BE '; }
    .step-tree {
      list-style: none; padding-left: 1.2rem; margin-top: 0.25rem;
    }
    .step-item { margin-bottom: 0.15rem; }
    .step-header { display: flex; align-items: baseline; gap: 0.4rem; flex-wrap: wrap; }
    .step-icon { font-size: 0.78rem; }
    .step-item.status-passed .step-icon { color: var(--accent-green); }
    .step-item.status-failed .step-icon { color: var(--accent-red); }
    .step-item.status-broken .step-icon { color: var(--accent-yellow); }
    .step-name { font-size: 0.82rem; color: var(--text); }
    .step-params {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.68rem; color: var(--text-secondary);
    }
    .step-attachments { margin-top: 0.15rem; margin-left: 1.2rem; }
    .attachment-badge {
      display: inline-block; font-family: 'IBM Plex Mono', monospace;
      font-size: 0.62rem; padding: 0.08rem 0.35rem;
      background: rgba(15,118,110,0.1); color: var(--accent-teal);
      border-radius: 999px; margin-right: 0.25rem;
    }
    .test-attachments {
      margin-top: 0.4rem; display: flex; align-items: center;
      gap: 0.35rem; flex-wrap: wrap;
    }
    .attachments-label {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 0.66rem; text-transform: uppercase;
      letter-spacing: 0.08em; color: var(--text-secondary);
    }
    .attachment-link {
      font-size: 0.74rem; padding: 0.12rem 0.45rem;
      background: rgba(15,118,110,0.08); border: 1px solid rgba(15,118,110,0.2);
      border-radius: 999px;
    }

    /* Footer */
    .report-footer {
      margin-top: 2rem; padding-top: 1.5rem;
      border-top: 1px solid var(--border);
      text-align: center; color: var(--text-secondary); font-size: 0.78rem;
    }

    /* Section header */
    .section-heading {
      font-size: 1.3rem; margin-bottom: 1rem; letter-spacing: -0.02em;
      padding-bottom: 0.5rem; border-bottom: 2px solid var(--accent);
      display: inline-block;
    }

    /* Empty state */
    .empty-state {
      text-align: center; padding: 3rem 1.5rem;
      background: var(--bg-elevated); border: 2px dashed var(--border);
      border-radius: var(--radius-md); color: var(--text-secondary);
    }
    .empty-state h3 { color: var(--text-secondary); margin-bottom: 0.5rem; }

    /* Pass rate bar */
    .pass-rate-bar {
      height: 10px; border-radius: 999px; overflow: hidden;
      background: rgba(125,99,68,0.12); margin-top: 0.75rem;
    }
    .pass-rate-fill {
      height: 100%; border-radius: 999px;
      background: linear-gradient(90deg, var(--accent-green), var(--accent-teal));
      transition: width 0.4s ease;
    }

    /* Scrollbar */
    ::-webkit-scrollbar { width: 8px; height: 8px; }
    ::-webkit-scrollbar-track { background: var(--bg); }
    ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 4px; }

    /* Print */
    @media print {
      .sidebar { display: none; }
      .filter-bar { display: none; }
      .main-content { max-width: 100%; padding: 0; }
      .layout { display: block; }
      .test-card { break-inside: avoid; box-shadow: none; }
      body { font-size: 11px; background: #fff; }
    }

    /* Responsive */
    @media (max-width: 768px) {
      .layout { flex-direction: column; padding: 0.75rem; gap: 0.75rem; }
      .sidebar { display: none; }
      .main-content { max-width: 100%; padding: 0.75rem; }
      .summary-grid { grid-template-columns: repeat(2, 1fr); }
      .test-card-header { font-size: 0.85rem; }
    }
  ")

(defn- js-filter
  "JavaScript for filtering test cards by status."
  []
  "
    (function() {
      function filterTests(status) {
        var cards = document.querySelectorAll('.test-card');
        var sections = document.querySelectorAll('.suite-section');
        var btns = document.querySelectorAll('.filter-btn');
        btns.forEach(function(b) {
          b.classList.toggle('active', b.getAttribute('data-filter') === status);
        });
        if (status === 'all') {
          cards.forEach(function(c) { c.style.display = ''; });
          sections.forEach(function(s) { s.style.display = ''; });
          return;
        }
        cards.forEach(function(c) {
          c.style.display = c.getAttribute('data-status') === status ? '' : 'none';
        });
        sections.forEach(function(s) {
          var visible = s.querySelectorAll('.test-card:not([style*=\"display: none\"])');
          s.style.display = visible.length > 0 ? '' : 'none';
        });
      }
      document.querySelectorAll('.filter-btn').forEach(function(btn) {
        btn.addEventListener('click', function() {
          filterTests(btn.getAttribute('data-filter'));
        });
      });
    })();
  ")

(defn generate!
  "Generate a Blockether-themed HTML report from allure-results.
   
   Parameters:
     results-dir - path to allure-results/ directory
     output-dir  - path to write the HTML report directory
   
   Options (opts map):
     :title       - report title (default: \"Test Report\")
     :results-dir - relative path to use for attachment links (default: results-dir value)
   
   Returns the output directory path on success."
  ([^String results-dir ^String output-dir]
   (generate! results-dir output-dir {}))
  ([^String results-dir ^String output-dir opts]
   (let [results (load-results results-dir)
         env (load-environment results-dir)
         title (or (:title opts) "Test Report")
         att-dir (:results-dir opts results-dir)
         out (io/file output-dir)]
      (when (empty? results)
        (println (str "No allure results found in " results-dir "/"))
        (println "Generating an empty report placeholder."))
      (.mkdirs out)
      (let [cts (count-by-status results)
            total-ms (total-duration-ms results)
            total (long (get cts :total 0))
            passed (long (get cts :passed 0))
            pass-rate (if (pos? total)
                        (int (* 100.0 (/ (double passed) (double total))))
                        0)
            suites (group-by-suite results)
           start-ts (reduce min Long/MAX_VALUE (keep #(get % "start") results))
           _stop-ts (reduce max 0 (keep #(get % "stop") results))
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
<div class=\"layout\">
  <aside class=\"sidebar\">
    <div class=\"sidebar-brand\">spel Report</div>
    <div class=\"sidebar-subtitle\">Blockether Native Report</div>
    <ul class=\"sidebar-nav\">
      <li><a href=\"#summary\">Summary</a></li>
      <li><a href=\"#environment\">Environment</a></li>
      <li><a href=\"#suites\">Test Suites</a></li>
    </ul>
    <div class=\"sidebar-footer\">
      Generated " (format-ts (System/currentTimeMillis)) "<br>
      No Node.js required
    </div>
  </aside>
  <main class=\"main-content\">
    <header class=\"report-header\">
      <div class=\"report-kicker\">Blockether Test Report</div>
      <h1 class=\"report-title\">" (html-escape title) "</h1>
      <div class=\"report-meta\">
        <span><strong>Tests:</strong> " (:total cts) "</span>
        <span><strong>Pass rate:</strong> " pass-rate "%</span>
        <span><strong>Duration:</strong> " (format-duration total-ms) "</span>
        <span><strong>Started:</strong> " (if (= Long/MAX_VALUE start-ts) "N/A" (format-ts start-ts)) "</span>
        <span><strong>Suites:</strong> " (count suites) "</span>
      </div>
      <div class=\"pass-rate-bar\"><div class=\"pass-rate-fill\" style=\"width: " pass-rate "%;\"></div></div>
    </header>

    <section id=\"summary\">
      <h2 class=\"section-heading\">Summary</h2>
      <div class=\"summary-grid\">
        <div class=\"summary-card summary-card-total\">
          <div class=\"summary-count\" style=\"color: var(--accent);\">" (:total cts) "</div>
          <div class=\"summary-label\">Total</div>
        </div>
        <div class=\"summary-card summary-card-passed\">
          <div class=\"summary-count\" style=\"color: var(--accent-green);\">" (:passed cts) "</div>
          <div class=\"summary-label\">Passed</div>
        </div>
        <div class=\"summary-card summary-card-failed\">
          <div class=\"summary-count\" style=\"color: var(--accent-red);\">" (:failed cts) "</div>
          <div class=\"summary-label\">Failed</div>
        </div>
        <div class=\"summary-card summary-card-broken\">
          <div class=\"summary-count\" style=\"color: var(--accent-yellow);\">" (:broken cts) "</div>
          <div class=\"summary-label\">Broken</div>
        </div>
        <div class=\"summary-card summary-card-skipped\">
          <div class=\"summary-count\" style=\"color: var(--text-secondary);\">" (:skipped cts) "</div>
          <div class=\"summary-label\">Skipped</div>
        </div>
        <div class=\"summary-card summary-card-duration\">
          <div class=\"summary-count\" style=\"color: var(--accent-teal);\">" (format-duration total-ms) "</div>
          <div class=\"summary-label\">Duration</div>
        </div>
      </div>
    </section>

    <section id=\"environment\">
      <h2 class=\"section-heading\">Environment</h2>"
                  (if (seq env)
                    (str "<div class=\"env-grid\">"
                      (str/join ""
                        (for [[k v] (sort-by first env)]
                          (str "<div class=\"env-item\"><div class=\"env-key\">" (html-escape k) "</div>"
                            "<div class=\"env-value\">" (html-escape v) "</div></div>")))
                      "</div>")
                    "<div class=\"empty-state\"><p>No environment data</p></div>")
                  "</section>

    <section id=\"suites\">
      <h2 class=\"section-heading\">Test Suites</h2>
      <div class=\"filter-bar\">
        <button class=\"filter-btn active\" data-filter=\"all\">All (" (:total cts) ")</button>
        <button class=\"filter-btn\" data-filter=\"passed\">Passed (" (:passed cts) ")</button>
        <button class=\"filter-btn\" data-filter=\"failed\">Failed (" (:failed cts) ")</button>
        <button class=\"filter-btn\" data-filter=\"broken\">Broken (" (:broken cts) ")</button>"
                  (when (pos? (long (get cts :skipped 0)))
                    (str "<button class=\"filter-btn\" data-filter=\"skipped\">Skipped (" (:skipped cts) ")</button>"))
                  "</div>"
                  (if (seq suites)
                    (str/join ""
                      (for [[suite-name suite-results] suites]
                        (render-suite-section suite-name suite-results att-dir)))
                    "<div class=\"empty-state\"><p>No test result files were found for this run.</p></div>")
                  "</section>

    <footer class=\"report-footer\">
      <p>Generated by spel Blockether Report Renderer</p>
      <p>No Node.js &middot; No Allure npm &middot; Pure Clojure</p>
    </footer>
  </main>
</div>
<script>" (js-filter) "</script>
</body>
</html>")]
       (spit (io/file out "index.html") html)
       (println (str "Blockether report generated: " output-dir "/index.html"))
       (println (str "  " (:total cts) " tests: "
                  (:passed cts) " passed, "
                  (:failed cts) " failed, "
                  (:broken cts) " broken, "
                  (:skipped cts) " skipped"))
       (println (str "  Duration: " (format-duration total-ms)))
       (println (str "  Pass rate: " pass-rate "%"))
       output-dir))))
