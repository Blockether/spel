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

(defn- load-executor
  "Load `executor.json` from the results directory. This is Allure's standard
   CI metadata file: `{name, type, url, buildOrder, buildName, buildUrl,
   reportUrl, reportName}`. Returns nil if absent or unparseable."
  [^String results-dir]
  (let [f (io/file results-dir "executor.json")]
    (when (.isFile f)
      (try
        (json/read-json (slurp f))
        (catch Exception _ nil)))))

(defn- first-non-blank [& candidates]
  (first (keep #(when-not (str/blank? (str %)) (str %)) candidates)))

(defn- short-sha [^String sha]
  (when (and sha (>= (count sha) 7))
    (subs sha 0 7)))

(defn- build-run-info
  "Normalizes run metadata from whatever sources happen to be available:
   1. Keys in `environment.properties` under `commit.*` / `run.*` / `report.*`
      (e.g. `commit.sha`, `commit.author`, `commit.subject`, `commit.timestamp`,
       `run.url`, `run.name`, `report.title`).
   2. Allure's standard `executor.json` if present.
   Returns {:commit-sha :commit-short :commit-author :commit-subject
           :commit-ts :run-url :run-name :report-title} with any missing
   fields omitted. Returns nil if no metadata at all is available."
  [env executor]
  (let [sha (first-non-blank (get env "commit.sha")
              (get executor "commit"))
        subject (first-non-blank (get env "commit.subject")
                  (get env "commit.message")
                  (get executor "buildName")
                  (get executor "reportName"))
        author (first-non-blank (get env "commit.author")
                 (get executor "commit_author"))
        ts-raw (first-non-blank (get env "commit.timestamp")
                 (get env "run.timestamp"))
        ts (when ts-raw
             (try (Long/parseLong ts-raw) (catch Exception _ nil)))
        run-url (first-non-blank (get env "run.url")
                  (get executor "buildUrl")
                  (get executor "reportUrl"))
        run-name (first-non-blank (get env "run.name")
                   (when-let [bo (get executor "buildOrder")]
                     (str "#" bo))
                   (get executor "buildName"))
        report-title (first-non-blank (get env "report.title")
                       (get executor "reportName"))
        info (cond-> {}
               sha          (assoc :commit-sha sha
                              :commit-short (short-sha sha))
               author       (assoc :commit-author author)
               subject      (assoc :commit-subject subject)
               ts           (assoc :commit-ts ts)
               run-url      (assoc :run-url run-url)
               run-name     (assoc :run-name run-name)
               report-title (assoc :report-title report-title))]
    (when (seq info) info)))

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
    (invoke-reporter-helper! 'patch-sw-safari-response-headers! out)))
;; Note: we deliberately do NOT call `inject-trace-viewer-prewarm!` here
;; (finding #1 diagnosis). That helper reads + rewrites index.html, but at
;; this point in generate! index.html hasn't been written yet, so the
;; str/replace silently no-ops and the Service Worker never registers. The
;; trace-viewer then falls through to HTTP fetches for `contexts?trace=…`
;; and the iframe ends up blank. We inline the prewarm <script> directly
;; into our <head> template below instead — see the `trace-sw-prewarm`
;; block in `generate!`.

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
      ;; Finding #1: single "Open Trace" button that opens the Playwright
      ;; trace viewer in a popup window (`window.open` with popup features).
      ;; The viewer can NOT be embedded in an iframe — its React shell and
      ;; Service Worker bootstrap require ownership of the top-level
      ;; document. A popup window is a proper top-level document so the
      ;; viewer works end-to-end.
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

(defn- suite-metadata-json
  "Emit a compact JSON array of `{n, s, d}` entries — name, status, duration —
   for the suite's tests. Used by the client-side filter so it can evaluate
   status/name matches without having to hydrate collapsed suites."
  [results]
  (json/write-json-str
    (mapv (fn [r]
            (let [start (get r "start")
                  stop (get r "stop")
                  duration (if (and start stop) (- (long stop) (long start)) 0)]
              {"n" (str/lower-case (get r "name" ""))
               "s" (get r "status" "unknown")
               "d" duration}))
      results)))

(defn- render-suite-section
  "Render a suite group with compact collapsed-by-default cards.

   Finding #3 (DOM virtualization): the test cards are emitted inside a
   `<template class=\"suite-template\">` which lives in a detached document
   fragment and does NOT count toward `document.querySelectorAll(\"*\")`.
   The suite body is left empty until the user toggles the `<details>` open,
   at which point client-side JS clones the template into the placeholder."
  [suite-name results results-dir]
  (let [cts (count-by-status results)
        meta-json (-> (suite-metadata-json results)
                    ;; Escape `</` so an inlined `</script>` can't break out.
                    (str/replace "</" "<\\/"))]
    (str "<details class=\"suite-section\" data-suite"
      " data-suite-name=\"" (str/lower-case (html-escape suite-name)) "\""
      " data-suite-total=\"" (:total cts) "\""
      " data-suite-failed=\"" (:failed cts) "\""
      " data-suite-broken=\"" (:broken cts) "\""
      " data-suite-skipped=\"" (:skipped cts) "\""
      " data-suite-passed=\"" (:passed cts) "\">"
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
      "<div class=\"suite-body\" data-suite-hydrated=\"false\">"
      "<script type=\"application/json\" class=\"suite-meta\">" meta-json "</script>"
      "<template class=\"suite-template\">"
      (str/join "" (map #(render-test-card % results-dir) results))
      "</template>"
      "</div>"
      "</details>")))

(defn- css
  "Clean neutral stylesheet for the Blockether alternative report."
  []
  "
  /* Theme tokens — driven by `data-theme` on <html>.
     Values: `auto` (follow OS), `light`, `dark`.
     Default (no attribute or `auto`) respects `prefers-color-scheme`. */
  :root,
  html[data-theme='light'],
  html[data-theme='auto'] {
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
  html[data-theme='dark'] {
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
  @media (prefers-color-scheme: dark) {
    :root,
    html[data-theme='auto'] {
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
    html[data-theme='light'] {
      /* Force light palette even when the OS is dark. Re-assert the light
         values so @media dark doesn't win by specificity. */
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
    font-size: 0.75rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--text-muted);
    font-weight: 600;
  }
  .report-title {
    font-size: clamp(1.25rem, 2.5vw, 1.75rem);
    letter-spacing: -0.02em;
    font-weight: 800;
  }
  .report-subtitle {
    color: var(--text-muted);
    font-size: 0.82rem;
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    align-items: baseline;
  }
  .report-subtitle time { font-variant-numeric: tabular-nums; }
  .report-run-link {
    color: var(--accent);
    text-decoration: none;
    font-weight: 600;
    padding: 0.15rem 0.5rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-accent);
    transition: all 0.12s ease;
  }
  .report-run-link:hover {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
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
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-muted);
    font-weight: 600;
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
    /* Match .filter-btn padding + font so search and sort line up at the
       same height in the toolbar row. Inter body font is kept (JetBrains
       Mono would look too severe in a free-text input). */
    padding: 0.35rem 0.7rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-panel-strong);
    color: var(--text);
    font-size: 0.75rem;
    line-height: 1.4;
    outline: none;
    transition: border-color 0.15s;
  }
  .toolbar-search:focus { border-color: var(--accent); }
  .toolbar-search::placeholder { color: var(--text-muted); }
  .filter-btn,
  .toolbar-btn,
  .attachment-link,
  .attachment-link-button {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--text-secondary);
    padding: 0.35rem 0.7rem;
    cursor: pointer;
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.75rem;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    font-weight: 600;
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
  .filter-btn:focus-visible,
  .toolbar-btn:focus-visible,
  .attachment-link:focus-visible,
  .attachment-link-button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  /* Stronger affordance for the Open Trace button so it reads as a real
     button — the default ghost style is too faint on the panel background. */
  .trace-launch {
    background: var(--bg-accent);
    border-color: var(--accent);
    color: var(--accent);
  }
  .trace-launch:hover {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
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
    font-size: 0.75rem;
    padding: 0.2rem 0.5rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    background: var(--bg-panel-strong);
    font-weight: 600;
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
    font-size: 0.75rem;
    font-weight: 700;
    padding: 0.2rem 0.55rem;
    border-radius: var(--radius-sm);
    color: #fff;
    text-transform: uppercase;
    letter-spacing: 0.06em;
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
    gap: 0.5rem;
    margin-top: 0.5rem;
  }
  .label-pill {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.75rem;
    padding: 0.2rem 0.55rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.03);
    font-weight: 600;
    letter-spacing: 0.02em;
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

  /* Step tree — each nesting level adds 1rem of indent via .step-tree's
     own padding, and every .step-item carries a left border rail plus a
     horizontal tick connecting it to its parent so the hierarchy is
     visually unambiguous. */
  .step-tree {
    list-style: none;
    margin: 0;
    padding: 0.35rem 0.6rem 0.6rem 1rem;
  }
  .step-item .step-tree {
    padding: 0.25rem 0 0.25rem 1.25rem;
  }
  .step-item {
    position: relative;
    margin-top: 0.35rem;
    padding: 0.3rem 0 0.3rem 1rem;
    border-left: 2px solid var(--border);
  }
  .step-item::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0.9rem;
    width: 0.75rem;
    height: 1px;
    background: var(--border);
  }
  .step-item.status-failed { border-left-color: var(--accent-red); }
  .step-item.status-failed::before { background: var(--accent-red); }
  .step-item.status-broken { border-left-color: var(--accent-yellow); }
  .step-item.status-broken::before { background: var(--accent-yellow); }
  .step-item.status-passed { border-left-color: var(--accent-green-border); }
  .step-header {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
    align-items: baseline;
  }
  .step-icon { font-size: 0.82rem; flex-shrink: 0; }
  .step-name {
    font-size: 0.82rem;
    font-weight: 500;
    color: var(--text);
  }
  .step-params {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.72rem;
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
  .empty-state strong { display: block; color: var(--text); font-size: 0.95rem; }
  .empty-state p { margin: 0.5rem 0 0.75rem; }
  .empty-state kbd {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.75rem;
    padding: 0.1rem 0.4rem;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--bg-accent);
  }

  /* Theme toggle — 3-state button (auto / light / dark) in the header.
     Icon is the glyph matching the active state; click cycles forward. */
  .theme-toggle {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    border: 1px solid var(--border);
    background: var(--bg-panel);
    color: var(--text-secondary);
    border-radius: var(--radius-sm);
    padding: 0.35rem 0.65rem;
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.75rem;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    cursor: pointer;
    transition: all 0.12s ease;
  }
  .theme-toggle:hover {
    color: var(--text);
    border-color: var(--border-strong);
    background: var(--bg-accent);
  }
  .theme-toggle:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .theme-toggle-icon { font-size: 0.95rem; line-height: 1; }

  /* Custom Sort menu — pill button + dropdown, visually aligned with
     the filter pills so the toolbar reads as one design system. */
  .toolbar-sort {
    position: relative;
  }
  .toolbar-sort-button {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
  }
  .toolbar-sort-button::after {
    content: '▾';
    font-size: 0.6rem;
    opacity: 0.7;
  }
  .toolbar-sort[aria-expanded='true'] .toolbar-sort-button {
    background: var(--bg-accent);
    border-color: var(--border-strong);
    color: var(--text);
  }
  .toolbar-sort-menu {
    position: absolute;
    top: calc(100% + 0.25rem);
    right: 0;
    min-width: 180px;
    margin: 0;
    padding: 0.25rem;
    list-style: none;
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-md);
    z-index: 50;
  }
  .toolbar-sort-menu[hidden] { display: none; }
  .toolbar-sort-menu li {
    padding: 0.4rem 0.7rem;
    border-radius: var(--radius-sm);
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.75rem;
    color: var(--text-secondary);
    cursor: pointer;
    letter-spacing: 0.02em;
  }
  .toolbar-sort-menu li:hover,
  .toolbar-sort-menu li:focus-visible {
    background: var(--bg-accent);
    color: var(--text);
    outline: none;
  }
  .toolbar-sort-menu li[aria-selected='true'] {
    background: var(--bg-accent);
    color: var(--accent);
    font-weight: 700;
  }

  /* Scrollbar */
  ::-webkit-scrollbar { width: 6px; height: 6px; }
  ::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: 999px; }
  ::-webkit-scrollbar-track { background: transparent; }

  /* Print */
  @media print {
    .toolbar { display: none !important; }
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

    // Finding #3 (virtualization): each suite's test cards live inside a
    // <template> until the user opens the <details>. We maintain a per-suite
    // metadata index (parsed from the inlined <script type=application/json>)
    // so filter + search can work across collapsed suites WITHOUT having to
    // hydrate their DOM.
    var suiteMetaCache = new WeakMap();
    function getSuiteMeta(section) {
      if (suiteMetaCache.has(section)) return suiteMetaCache.get(section);
      var script = section.querySelector('script.suite-meta');
      var meta = [];
      if (script) {
        try { meta = JSON.parse(script.textContent) || []; } catch (e) { meta = []; }
      }
      suiteMetaCache.set(section, meta);
      return meta;
    }
    function hydrateSuite(section) {
      var body = section.querySelector('.suite-body');
      if (!body || body.getAttribute('data-suite-hydrated') === 'true') return;
      var tpl = body.querySelector('template.suite-template');
      if (tpl) {
        body.appendChild(tpl.content.cloneNode(true));
        tpl.remove();
      }
      body.setAttribute('data-suite-hydrated', 'true');
    }
    function suiteMatches(section) {
      var q = currentSearch.toLowerCase();
      var meta = getSuiteMeta(section);
      for (var i = 0; i < meta.length; i++) {
        var e = meta[i];
        var statusOk = currentFilter === 'all' || e.s === currentFilter;
        var nameOk = !q || (e.n || '').indexOf(q) !== -1;
        if (statusOk && nameOk) return true;
      }
      return false;
    }

    function applyFilters() {
      var sections = document.querySelectorAll('.suite-section');
      var q = currentSearch.toLowerCase();
      var visibleSections = 0;
      sections.forEach(function(section) {
        var show = suiteMatches(section);
        section.style.display = show ? '' : 'none';
        if (show) visibleSections++;

        // If this suite has already been hydrated (user expanded it), also
        // hide the individual cards that don't match so the user sees a
        // consistent filter state after typing in the search box.
        var body = section.querySelector('.suite-body');
        if (body && body.getAttribute('data-suite-hydrated') === 'true') {
          body.querySelectorAll(':scope > .test-card').forEach(function(card) {
            var statusMatch = currentFilter === 'all' || card.getAttribute('data-status') === currentFilter;
            var nameMatch = !q || (card.getAttribute('data-name') || '').indexOf(q) !== -1;
            card.style.display = (statusMatch && nameMatch) ? '' : 'none';
          });
        }
      });

      // Finding #5: surface an explicit empty state instead of leaving the
      // suites region as dead whitespace when a filter hides everything.
      var suitesRoot = document.getElementById('suitesRoot');
      var emptyState = document.getElementById('suitesEmptyState');
      if (suitesRoot && emptyState) {
        if (visibleSections === 0) {
          suitesRoot.style.display = 'none';
          emptyState.hidden = false;
        } else {
          suitesRoot.style.display = '';
          emptyState.hidden = true;
        }
      }
    }

    function resetFilters() {
      currentFilter = 'all';
      currentSearch = '';
      var searchInput = document.getElementById('searchInput');
      if (searchInput) searchInput.value = '';
      document.querySelectorAll('.filter-btn[data-filter]').forEach(function(b) {
        b.classList.toggle('active', b.getAttribute('data-filter') === 'all');
      });
      applyFilters();
    }

    function sortCards() {
      // Only touch suites that have already been hydrated — unhydrated suites
      // keep their original test order in the <template>, which is fine
      // because their cards aren't visible yet.
      var sections = document.querySelectorAll('.suite-section');
      sections.forEach(function(section) {
        var body = section.querySelector('.suite-body');
        if (!body || body.getAttribute('data-suite-hydrated') !== 'true') return;
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

    // Hydrate each suite on first <details> toggle open (lazy DOM insertion
    // for finding #3). After hydration, reapply the current filter/sort so
    // the newly-inserted cards honour whatever the user has already chosen.
    document.querySelectorAll('.suite-section').forEach(function(section) {
      section.addEventListener('toggle', function() {
        if (section.open) {
          hydrateSuite(section);
          applyFilters();
          if (currentSort !== 'status') sortCards();
        }
      });
    });

    // Wire only status-filter pills (ALL/PASSED/FAILED/BROKEN/SKIPPED).
    // The sort button and the clear-filters button also carry .filter-btn
    // for visual consistency, but they do NOT have a data-filter attribute
    // — scoping the selector here prevents their clicks from setting
    // `currentFilter = null` and hiding every suite.
    document.querySelectorAll('.filter-btn[data-filter]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        currentFilter = btn.getAttribute('data-filter');
        document.querySelectorAll('.filter-btn[data-filter]').forEach(function(b) {
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

    // Custom Sort dropdown (finding #4) — replaces the native <select> with
    // a button + menu that visually matches the filter pills.
    var sortControl = document.getElementById('sortControl');
    if (sortControl) {
      var sortButton = sortControl.querySelector('.toolbar-sort-button');
      var sortLabel = sortControl.querySelector('.toolbar-sort-label');
      var sortMenu = sortControl.querySelector('.toolbar-sort-menu');
      var sortItems = Array.from(sortControl.querySelectorAll('[role=\"menuitem\"]'));
      var labelFor = {
        status: 'Sort: Status',
        longest: 'Sort: Longest first',
        shortest: 'Sort: Shortest first',
        name: 'Sort: Name A–Z'
      };
      function closeSortMenu() {
        sortMenu.hidden = true;
        sortControl.setAttribute('aria-expanded', 'false');
        sortButton.setAttribute('aria-expanded', 'false');
      }
      function openSortMenu() {
        sortMenu.hidden = false;
        sortControl.setAttribute('aria-expanded', 'true');
        sortButton.setAttribute('aria-expanded', 'true');
        var selected = sortControl.querySelector('[role=\"menuitem\"][aria-selected=\"true\"]');
        if (selected) selected.focus();
      }
      sortButton.addEventListener('click', function() {
        if (sortMenu.hidden) openSortMenu(); else closeSortMenu();
      });
      sortItems.forEach(function(item) {
        item.addEventListener('click', function() {
          currentSort = item.getAttribute('data-value');
          sortItems.forEach(function(i) {
            i.setAttribute('aria-selected',
              i.getAttribute('data-value') === currentSort ? 'true' : 'false');
          });
          if (sortLabel) sortLabel.textContent = labelFor[currentSort] || 'Sort';
          sortCards();
          applyFilters();
          closeSortMenu();
          sortButton.focus();
        });
        item.addEventListener('keydown', function(e) {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); item.click(); }
          else if (e.key === 'Escape') { closeSortMenu(); sortButton.focus(); }
          else if (e.key === 'ArrowDown') {
            e.preventDefault();
            var i = sortItems.indexOf(item);
            (sortItems[(i + 1) % sortItems.length] || sortItems[0]).focus();
          }
          else if (e.key === 'ArrowUp') {
            e.preventDefault();
            var i = sortItems.indexOf(item);
            (sortItems[(i - 1 + sortItems.length) % sortItems.length] || sortItems[sortItems.length - 1]).focus();
          }
        });
      });
      document.addEventListener('click', function(event) {
        if (!sortControl.contains(event.target)) closeSortMenu();
      });
      document.addEventListener('keydown', function(event) {
        if (event.key === 'Escape' && !sortMenu.hidden) {
          closeSortMenu();
          sortButton.focus();
        }
      });
    }

    // Reset-filters button in the suites empty state.
    document.querySelectorAll('[data-action=\"reset-filters\"]').forEach(function(btn) {
      btn.addEventListener('click', resetFilters);
    });

    // 3-state theme toggle (finding #9) — auto → light → dark → auto.
    // Choice persists in localStorage under key 'spel.report.theme'.
    var themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
      var ICON = { auto: '⦾', light: '☀', dark: '☾' };
      var LABEL = { auto: 'Auto', light: 'Light', dark: 'Dark' };
      var NEXT = { auto: 'light', light: 'dark', dark: 'auto' };
      function readTheme() {
        var t;
        try { t = localStorage.getItem('spel.report.theme'); } catch (e) {}
        return (t === 'light' || t === 'dark' || t === 'auto') ? t : 'auto';
      }
      function writeTheme(t) {
        try { localStorage.setItem('spel.report.theme', t); } catch (e) {}
      }
      function applyTheme(t) {
        document.documentElement.setAttribute('data-theme', t);
        var iconEl = themeToggle.querySelector('.theme-toggle-icon');
        var labelEl = themeToggle.querySelector('.theme-toggle-label');
        if (iconEl) iconEl.textContent = ICON[t] || ICON.auto;
        if (labelEl) labelEl.textContent = LABEL[t] || LABEL.auto;
      }
      applyTheme(readTheme());
      themeToggle.addEventListener('click', function() {
        var current = readTheme();
        var next = NEXT[current] || 'auto';
        writeTheme(next);
        applyTheme(next);
      });
    }

    var expandBtn = document.querySelector(\"[data-action='expand-suites']\");
    var collapseBtn = document.querySelector(\"[data-action='collapse-suites']\");

    if (expandBtn) {
      expandBtn.addEventListener('click', function() {
        // Hydrate + open in chunks so a 2000-test report doesn't freeze the
        // main thread while cloning ~22 templates at once.
        var queue = Array.from(document.querySelectorAll('.suite-section'))
          .filter(function(s) { return s.style.display !== 'none'; });
        var schedule = window.requestIdleCallback
          || function(cb) { return setTimeout(cb, 0); };
        function step() {
          var deadline = Date.now() + 12;
          while (queue.length && Date.now() < deadline) {
            var section = queue.shift();
            hydrateSuite(section);
            section.open = true;
          }
          if (queue.length) schedule(step);
        }
        schedule(step);
      });
    }

    if (collapseBtn) {
      collapseBtn.addEventListener('click', function() {
        document.querySelectorAll('.suite-section, .test-card, .test-steps, .attachment-panel').forEach(function(el) {
          el.open = false;
        });
      });
    }

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

    // Finding #1: open the Playwright trace viewer in a popup window. The
    // viewer's Service Worker + React bootstrap require ownership of the
    // top document — embedding in an iframe leaves the shell mounted but
    // blank. A popup window is a proper top-level document so the viewer
    // works end-to-end.
    //
    // Finding #1 + Pass 4 virtualization: trace-launch buttons live inside
    // per-suite <template> fragments and only materialize when the user
    // expands a suite. Attaching listeners at load time via querySelectorAll
    // would bind to zero elements. Use event delegation on document so any
    // future-cloned button is handled automatically.
    function openTrace(url) {
      var features = 'popup=yes,width=1480,height=900,menubar=no,toolbar=no,location=no,status=no';
      var w = window.open(url, '_blank', features);
      if (!w) {
        alert('Unable to open the trace viewer — browser popup blocker may have blocked the new window.');
      }
    }
    document.addEventListener('click', function(event) {
      var btn = event.target.closest && event.target.closest('.trace-launch');
      if (!btn) return;
      var traceUrl = btn.getAttribute('data-trace-url');
      var traceSource = btn.getAttribute('data-trace-source');
      var chunkCount = parseInt(btn.getAttribute('data-trace-chunks') || '0', 10);

      if (chunkCount > 1 && traceSource) {
        var original = btn.textContent;
        btn.disabled = true;
        btn.textContent = 'Preparing trace...';

        buildChunkedTraceBlobUrl(traceSource, chunkCount)
          .then(function(blobUrl) {
            var viewerUrl = 'trace-viewer/index.html?trace=' + encodeURIComponent(blobUrl);
            openTrace(viewerUrl);
          })
          .catch(function(err) {
            console.error(err);
            alert('Unable to open split trace: ' + (err.message || err));
          })
          .finally(function() {
            btn.disabled = false;
            btn.textContent = original;
          });
        return;
      }

      openTrace(traceUrl);
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
         executor (load-executor results-dir)
         ;; Caller-supplied :run-info wins; otherwise derive it from
         ;; environment.properties + executor.json if either has useful fields.
         run-info (or (:run-info opts)
                    (build-run-info env executor))
         ;; Header copy:
         ;; - title = commit subject if available, else caller's :title, else "Allure Report"
         ;; - kicker = "#<run-name> · <short-sha> · <author>" when run-info is present,
         ;;            else caller's :kicker (which is suppressed by finding #12
         ;;            logic if it equals title)
         title (or (:title opts)
                 (:report-title run-info)
                 (:commit-subject run-info)
                 "Allure Report")
         run-kicker-parts (when run-info
                            (keep identity
                              [(:run-name run-info)
                               (:commit-short run-info)
                               (:commit-author run-info)]))
         kicker (cond
                  (seq run-kicker-parts) (str/join " · " run-kicker-parts)
                  :else (or (:kicker opts) "Allure Report"))
         subtitle (or (:subtitle opts)
                    (when-let [ts (:commit-ts run-info)]
                      (format-ts ts)))
         ;; <title>: if we have a short SHA + subject, combine them so
         ;; multi-tab comparisons are distinguishable.
         doc-title (cond
                     (and (:commit-short run-info) (:commit-subject run-info))
                     (str (:commit-short run-info) " · " (:commit-subject run-info))
                     :else title)
         out (io/file output-dir)]
     (when (empty? results)
       (println (str "No allure results found in " results-dir "/"))
       (println "Generating an empty report placeholder."))
     (clean-dir! out)
     (copy-attachments! results-dir out results)
     (let [has-traces? (boolean (some trace-attachment? (collect-all-attachments results)))]
       (when has-traces?
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
           ;; Inline SVG favicon — a simple indigo orb matching --accent.
           ;; Self-contained so the report has zero external asset deps
           ;; and no /favicon.ico 404 in the console.
           favicon-data "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Ccircle cx='32' cy='32' r='28' fill='%234f46e5'/%3E%3Ccircle cx='32' cy='32' r='10' fill='%23fff'/%3E%3C/svg%3E"
           html (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>" (html-escape doc-title) "</title>
  <link rel=\"icon\" type=\"image/svg+xml\" href=\"" favicon-data "\">
  <script>
    /* Set data-theme before the stylesheet parses to avoid a flash of
       the wrong theme when the saved preference is 'light' or 'dark'. */
    (function () {
      try {
        var t = localStorage.getItem('spel.report.theme');
        if (t !== 'light' && t !== 'dark' && t !== 'auto') t = 'auto';
        document.documentElement.setAttribute('data-theme', t);
      } catch (e) {
        document.documentElement.setAttribute('data-theme', 'auto');
      }
    })();
  </script>" (if has-traces?
               "
  <script>
    /* Finding #1: pre-register the Playwright trace-viewer Service Worker
       so it is active and controlling ./trace-viewer/ before the user ever
       clicks an Open Trace button. Without this the viewer's internal
       `fetch('contexts?trace=…')` falls through to the HTTP server, returns
       HTML instead of trace data, and the iframe renders blank. Previously
       this script was injected after-the-fact by allure_reporter's
       `inject-trace-viewer-prewarm!` helper, but in the alt report code
       path the helper ran BEFORE index.html was written and silently
       no-op'd. Inlining here runs at exactly the right moment. */
    (function () {
      if (!('serviceWorker' in navigator)) return;
      navigator.serviceWorker
        .register('./trace-viewer/sw.bundle.js', { scope: './trace-viewer/' })
        .catch(function () {});
    })();
  </script>"
               "") "
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
      " (if (and (seq kicker)
              (not= (str/lower-case (str kicker))
                (str/lower-case (str title))))
          (str "<div class=\"report-kicker\">" (html-escape kicker) "</div>")
          "") "
      <h1 class=\"report-title\">" (html-escape title) "</h1>
      " (let [subtitle-html
              (cond-> []
                (seq subtitle)
                (conj (str "<time>" (html-escape subtitle) "</time>"))
                (:run-url run-info)
                (conj (str "<a class=\"report-run-link\" href=\""
                        (html-escape (:run-url run-info)) "\" target=\"_blank\" rel=\"noopener\">"
                        (html-escape (or (:run-name run-info) "Open run ↗")) "</a>")))]
          (if (seq subtitle-html)
            (str "<div class=\"report-subtitle\">" (str/join " · " subtitle-html) "</div>")
            "")) "
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
                  "<button type=\"button\" id=\"themeToggle\" class=\"theme-toggle\"
                          aria-label=\"Toggle theme (auto / light / dark)\"
                          title=\"Toggle theme — auto / light / dark\">
                    <span class=\"theme-toggle-icon\" aria-hidden=\"true\">⦾</span>
                    <span class=\"theme-toggle-label\">Auto</span>
                  </button>"
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
      <div class=\"toolbar-sort\" id=\"sortControl\" data-sort=\"status\" aria-expanded=\"false\">
        <button type=\"button\" class=\"filter-btn toolbar-sort-button\" aria-haspopup=\"menu\" aria-expanded=\"false\">
          <span class=\"toolbar-sort-label\">Sort: Status</span>
        </button>
        <ul class=\"toolbar-sort-menu\" role=\"menu\" hidden>
          <li role=\"menuitem\" tabindex=\"-1\" data-value=\"status\" aria-selected=\"true\">Status</li>
          <li role=\"menuitem\" tabindex=\"-1\" data-value=\"longest\">Longest first</li>
          <li role=\"menuitem\" tabindex=\"-1\" data-value=\"shortest\">Shortest first</li>
          <li role=\"menuitem\" tabindex=\"-1\" data-value=\"name\">Name A–Z</li>
        </ul>
      </div>
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
    <h2 class=\"section-heading\">Test suites</h2>
    <div id=\"suitesRoot\">"
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
    </div>
    <div id=\"suitesEmptyState\" class=\"empty-state\" hidden>
      <strong>No tests match the current filter.</strong>
      <p>Try clearing the search box or clicking <kbd>ALL</kbd>.</p>
      <button type=\"button\" class=\"filter-btn\" data-action=\"reset-filters\">Clear filters</button>
    </div>
  </section>
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
       output-dir)))))
