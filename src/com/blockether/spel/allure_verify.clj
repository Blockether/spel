(ns com.blockether.spel.allure-verify
  "Allure report verification: parse CT + lazytest results, generate HTML pages,
   take screenshots, produce PDF. Works on both raw allure-results/ dirs and
   downloaded CI artifact data/test-results/ dirs."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [java.io File]))

;; =============================================================================
;; JSON Parsing
;; =============================================================================

(defn- json-files
  "Get all JSON files from a directory."
  [^File dir]
  (when-let [files (.listFiles dir)]
    (filterv #(and (.isFile ^File %)
                (str/ends-with? (.getName ^File %) ".json"))
      files)))

(defn- read-json-safe
  "Read a JSON file, returning nil on error."
  [^File f]
  (try
    (json/read-json (slurp f))
    (catch Exception _ nil)))

;; =============================================================================
;; Result Parsing
;; =============================================================================

(defn- extract-labels
  "Extract labels as {name value} map from result JSON."
  [result]
  (into {}
    (map (fn [l] [(get l "name") (get l "value")])
      (get result "labels" []))))

(defn- extract-test
  "Extract test info from a parsed result JSON."
  [result]
  (let [labels (extract-labels result)]
    {:name      (or (get result "name") "unknown")
     :status    (or (get result "status") "unknown")
     :feature   (get labels "feature" "")
     :suite     (get labels "suite" "unknown")
     :framework (get labels "framework" "unknown")}))

(defn parse-results
  "Parse allure results from a directory.

   Accepts either:
     - Raw allure-results/*.json files (from lazytest/CT runners)
     - Downloaded artifact data/test-results/*.json files (from CI artifact)

   Auto-detects format by checking for *-result.json (raw) or UUID-named JSON (artifact).

   Returns map:
     {:ct-suites      {suite-name [tests]}
      :lazytest-suites {suite-name [tests]}
      :total N :passed N :failed N :ct-total N :lt-total N}

   Each test is: {:name str :status str :feature str :suite str}"
  [results-dir]
  (let [dir (io/file results-dir)]
    (if-not (.isDirectory dir)
      {:ct-suites {} :lazytest-suites {} :total 0 :passed 0 :failed 0 :ct-total 0 :lt-total 0}
      (let [files   (json-files dir)
            results (keep read-json-safe files)
            tests   (mapv extract-test results)
            ct      (filterv #(= "clojure.test" (:framework %)) tests)
            lt      (filterv #(not= "clojure.test" (:framework %)) tests)
            total   (count tests)
            passed  (count (filter #(= "passed" (:status %)) tests))]
        {:ct-suites       (group-by :suite ct)
         :lazytest-suites (group-by :suite lt)
         :total           total
         :passed          passed
         :failed          (- total passed)
         :ct-total        (count ct)
         :lt-total        (count lt)}))))

;; =============================================================================
;; HTML Generation
;; =============================================================================

(def ^:private page-style
  "CSS for verification HTML pages."
  (str "body{font-family:Arial,sans-serif;max-width:1200px;margin:0 auto;padding:20px;color:#222;background:#f7f7f7}"
    "h1,h2{color:#111;margin-top:0}"
    ".subtitle{color:#666;font-size:13px;margin-bottom:18px}"
    ".bars{display:flex;gap:12px;margin:16px 0;flex-wrap:wrap}"
    ".stat{background:white;border:1px solid #ddd;border-radius:6px;padding:12px 18px;text-align:center;min-width:80px}"
    ".stat .num{font-size:26px;font-weight:bold}"
    ".stat .lbl{font-size:11px;color:#666;margin-top:2px;text-transform:uppercase}"
    ".stat.pass .num{color:#27ae60}"
    ".stat.fail .num{color:#e74c3c}"
    ".stat.warn .num{color:#856404}"
    ".warn{background:#fff3cd;border:1px solid #ffc107;border-radius:6px;padding:10px 14px;margin:12px 0;font-size:13px}"
    ".ok{background:#d4edda;border:1px solid #c3e6cb;border-radius:6px;padding:10px 14px;margin:12px 0;font-size:13px;color:#155724}"
    ".section{font-size:16px;font-weight:bold;margin:20px 0 8px;padding-bottom:5px;border-bottom:2px solid #dee2e6}"
    ".suite{background:white;border:1px solid #ddd;border-radius:6px;padding:14px;margin:10px 0}"
    ".suite h3{margin:0 0 3px;font-size:14px}"
    ".badge{background:#e9ecef;border-radius:10px;padding:2px 7px;font-size:11px;font-weight:normal;color:#555;margin-left:5px}"
    ".ns{font-size:11px;color:#999;margin-bottom:9px;font-family:monospace}"
    "table{width:100%;border-collapse:collapse;font-size:12px}"
    "th{background:#f5f5f5;padding:5px 10px;text-align:left;border-bottom:2px solid #ddd;font-size:10px;text-transform:uppercase;color:#666}"
    "td{padding:4px 10px;border-bottom:1px solid #f0f0f0}"))

(def ^:private status-colors
  {"passed"  "#27ae60"
   "failed"  "#e74c3c"
   "broken"  "#e67e22"
   "skipped" "#95a5a6"})

(defn- html-escape
  "Escape HTML entities in a string."
  ^String [^String s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")
    (str/replace "\"" "&quot;")))

(defn- suite-html
  "Generate HTML for a single suite block."
  [suite-name tests]
  (let [short-name (last (str/split suite-name #"\."))
        rows (str/join
               (for [t (sort-by :name tests)]
                 (str "<tr><td style=\"color:" (get status-colors (:status t) "#333")
                   ";font-weight:bold;text-transform:uppercase;font-size:10px\">"
                   (html-escape (:status t)) "</td>"
                   "<td style=\"font-family:monospace\">" (html-escape (:name t)) "</td>"
                   "<td style=\"color:#888\">" (html-escape (:feature t)) "</td></tr>")))]
    (str "<div class=\"suite\"><h3>" (html-escape short-name)
      "<span class=\"badge\">" (count tests) "</span></h3>"
      "<div class=\"ns\">" (html-escape suite-name) "</div>"
      "<table><tr><th>Status</th><th>Test</th><th>Feature</th></tr>"
      rows "</table></div>")))

(defn generate-html-pages!
  "Generate verification HTML pages from parsed results.

   Generates 2 HTML files in out-dir:
     - verify-summary.html  — summary stats + CT suites (all suites, all tests)
     - verify-lazytest.html — lazytest suites (first 5 suites, 20 tests each)

   Returns list of generated absolute file paths."
  [results-map out-dir {:keys [pr-number] :as _opts}]
  (let [out (io/file out-dir)
        _   (.mkdirs out)
        {:keys [total passed failed ct-total lt-total ct-suites lazytest-suites]} results-map
        pr-label (if pr-number (str " &mdash; PR #" pr-number) "")
        ;; Page 1: Summary + CT suites
        ct-blocks (str/join
                    (for [[s tests] (sort ct-suites)]
                      (suite-html s tests)))
        lt-warning (if (zero? lt-total)
                     "<div class=\"warn\"><strong>Warning: Lazytest results missing.</strong></div>"
                     (str "<div class=\"ok\">Lazytest: " lt-total " results across "
                       (count lazytest-suites) " suites.</div>"))
        page1 (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                "<title>Allure Verify" pr-label "</title>"
                "<style>" page-style "</style></head><body>"
                "<h1>Allure Report Verification" pr-label "</h1>"
                "<div class=\"bars\">"
                "<div class=\"stat\"><div class=\"num\">" total "</div><div class=\"lbl\">Total</div></div>"
                "<div class=\"stat pass\"><div class=\"num\">" passed "</div><div class=\"lbl\">Passed</div></div>"
                "<div class=\"stat" (when (pos? failed) " fail") "\"><div class=\"num\">" failed "</div><div class=\"lbl\">Failed</div></div>"
                "<div class=\"stat\"><div class=\"num\">" ct-total "</div><div class=\"lbl\">CT</div></div>"
                "<div class=\"stat" (when (zero? lt-total) " warn") "\"><div class=\"num\">" lt-total "</div><div class=\"lbl\">Lazytest</div></div>"
                "</div>"
                lt-warning
                "<div class=\"section\">clojure.test (CT) &mdash; " ct-total " tests, " (count ct-suites) " suites</div>"
                ct-blocks
                "</body></html>")
        ;; Page 2: Lazytest suites (first 5, 20 tests each)
        lt-shown  (take 5 (sort lazytest-suites))
        lt-blocks (if (pos? lt-total)
                    (str (str/join
                           (for [[s tests] lt-shown]
                             (suite-html s (take 20 tests))))
                      (when (> (count lazytest-suites) 5)
                        (str "<p style=\"color:#666;font-size:13px\">... and "
                          (- (count lazytest-suites) 5) " more suites</p>")))
                    "<div class=\"warn\"><strong>No lazytest results.</strong></div>")
        page2 (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                "<title>Lazytest Results" pr-label "</title>"
                "<style>" page-style "</style></head><body>"
                "<h2>Lazytest Results" pr-label " (" lt-total " tests, "
                (count lazytest-suites) " suites)</h2>"
                lt-blocks
                "</body></html>")
        p1 (io/file out "verify-summary.html")
        p2 (io/file out "verify-lazytest.html")]
    (spit p1 page1)
    (spit p2 page2)
    [(.getAbsolutePath p1) (.getAbsolutePath p2)]))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defn take-screenshots!
  "Take screenshots of each HTML file using the provided spel page.

   Navigates to each file:// URL, takes a full-page screenshot, and saves it.
   Returns vector of screenshot file paths."
  [pg html-files out-dir]
  (let [out (io/file out-dir)]
    (.mkdirs out)
    (vec
      (for [[idx ^String html-path] (map-indexed vector html-files)]
        (let [ss-name (str "verify-" (inc idx) ".png")
              ss-file (io/file out ss-name)
              ss-path (.getAbsolutePath ss-file)]
          (page/navigate pg (str "file://" html-path))
          (page/screenshot pg {:path ss-path :full-page true})
          ss-path)))))

;; =============================================================================
;; Subprocess Helpers
;; =============================================================================

(defn- run-process!
  "Run a process with given args. Returns {:exit int :out str}."
  [args]
  (try
    (let [pb   (doto (ProcessBuilder. ^java.util.List (vec args))
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      {:exit exit :out out})
    (catch Exception e
      {:exit -1 :out (.getMessage e)})))

(defn- generate-pdf!
  "Generate PDF from HTML files using wkhtmltopdf. Returns path or nil."
  [html-files ^String out-path]
  (let [result (run-process! (into ["wkhtmltopdf" "--enable-local-file-access"]
                               (conj (vec html-files) out-path)))]
    (when (zero? (long (:exit result)))
      out-path)))

(defn- post-pr-comment!
  "Post a verification comment on a GitHub PR via gh CLI."
  [pr-number {:keys [total passed failed ct-total lt-total]} repo]
  (let [lt-status (if (zero? (long lt-total))
                    "0 &mdash; MISSING from artifact"
                    (str lt-total))
        body (str "## Allure Report Verified\n\n"
               "| Metric | Value |\n|--------|-------|\n"
               "| Total tests | " total " |\n"
               "| Passed | " passed " |\n"
               "| Failed | " failed " |\n"
               "| CT (clojure.test) | " ct-total " |\n"
               "| Lazytest | " lt-status " |\n")]
    (run-process! ["gh" "pr" "comment" (str pr-number)
                   "--repo" repo "--body" body])))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn verify-results!
  "Main verification entry point.

   Steps:
     1. parse-results on the dir
     2. generate-html-pages!
     3. Launch headless browser, take screenshots of each page, close browser
     4. Generate PDF via wkhtmltopdf (skip if not available)
     5. If :post-comment true, post comment via gh pr comment
     6. Return summary map

   Options:
     :out-dir       — where to write HTML/screenshots/PDF (default: /tmp/allure-verify/)
     :pr-number     — PR number for labeling (optional)
     :repo          — GitHub repo (default: Blockether/spel)
     :post-comment  — whether to post PR comment via gh CLI (default: false)
     :generate-pdf  — whether to generate PDF via wkhtmltopdf (default: true)"
  [results-dir {:keys [out-dir pr-number repo post-comment generate-pdf]
                :or   {out-dir      "/tmp/allure-verify/"
                       repo         "Blockether/spel"
                       post-comment false
                       generate-pdf true}}]
  (let [results    (parse-results results-dir)
        html-files (generate-html-pages! results out-dir {:pr-number pr-number})
        screenshots (try
                      (core/with-testing-page [pg]
                        (take-screenshots! pg html-files out-dir))
                      (catch Exception e
                        (println (str "Warning: screenshots failed: " (.getMessage e)))
                        []))
        pdf-path   (when generate-pdf
                     (let [pdf (str (.getAbsolutePath (io/file out-dir "verify-report.pdf")))]
                       (generate-pdf! html-files pdf)))]
    (when (and post-comment pr-number)
      (post-pr-comment! pr-number results repo))
    (merge results
      {:screenshots screenshots
       :pdf         pdf-path})))
