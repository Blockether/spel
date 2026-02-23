(ns com.blockether.spel.allure-verify
  "Allure report verification: parse CT + lazytest results, serve real Allure
   HTML report locally via HTTP, take screenshots with scrolling, produce PDF.
   Works on both raw allure-results/ dirs and downloaded CI artifact
   data/test-results/ dirs."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure-reporter :as reporter]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [com.sun.net.httpserver HttpServer SimpleFileServer]
   [java.io File]
   [java.net InetSocketAddress]
   [java.nio.file Path]))

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
;; HTTP Server
;; =============================================================================

(defn- start-http-server!
  "Start a local HTTP file server for the given directory.
   Returns the HttpServer instance."
  ^HttpServer [^String dir ^long port]
  (let [root    (Path/of dir (into-array String []))
        handler (SimpleFileServer/createFileHandler root)
        addr    (InetSocketAddress. port)
        server  (HttpServer/create addr 0)]
    (.createContext server "/" handler)
    (.start server)
    server))

(defn- stop-http-server!
  "Stop the local HTTP server."
  [^HttpServer server]
  (.stop server 0))

;; =============================================================================
;; Screenshots
;; =============================================================================

(defn- screenshot-url!
  "Navigate to URL, wait for load, and take a viewport screenshot."
  [pg ^String url ^String path]
  (page/navigate pg url)
  (page/wait-for-load-state pg :networkidle)
  (page/screenshot pg {:path path}))

(defn- scroll-and-screenshot!
  "Scroll the current page to scroll-y pixels and take a viewport screenshot.
   The url parameter is kept for API symmetry with screenshot-url!."
  [pg _url ^long scroll-y ^String path]
  (page/evaluate pg (str "window.scrollTo(0, " scroll-y ")"))
  (page/wait-for-timeout pg 500)
  (page/screenshot pg {:path path}))

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
     1. Parse results from the allure-results directory
     2. Generate real Allure HTML report via allure-reporter
     3. Serve the report directory locally via HTTP
     4. Launch headless browser, take 3 screenshots with scrolling
     5. Generate PDF via wkhtmltopdf (skip if not available)
     6. If :post-comment true, post comment via gh pr comment
     7. Return summary map

   Options:
     :out-dir       — where to write screenshots/PDF (default: /tmp/allure-verify/)
     :report-dir    — where to generate Allure report (default: <out-dir>/report)
     :port          — HTTP server port (default: 8299)
     :pr-number     — PR number for labeling (optional)
     :repo          — GitHub repo (default: Blockether/spel)
     :post-comment  — whether to post PR comment via gh CLI (default: false)
     :generate-pdf  — whether to generate PDF via wkhtmltopdf (default: true)"
  [results-dir {:keys [out-dir report-dir port pr-number repo post-comment generate-pdf]
                :or   {out-dir      "/tmp/allure-verify/"
                       port         8299
                       repo         "Blockether/spel"
                       post-comment false
                       generate-pdf true}}]
  (let [results     (parse-results results-dir)
        out         (io/file out-dir)
        _           (.mkdirs out)
        report-path (or report-dir
                      (.getAbsolutePath (io/file out "report")))
        _           (reporter/generate-html-report! results-dir report-path)
        server      (start-http-server! report-path (long port))
        base-url    (str "http://localhost:" port "/")
        s1-path     (.getAbsolutePath (io/file out "verify-1.png"))
        s2-path     (.getAbsolutePath (io/file out "verify-2.png"))
        s3-path     (.getAbsolutePath (io/file out "verify-3.png"))
        screenshots (try
                      (core/with-testing-page [pg]
                        (screenshot-url! pg base-url s1-path)
                        (scroll-and-screenshot! pg base-url 600 s2-path)
                        (scroll-and-screenshot! pg base-url 1200 s3-path)
                        [s1-path s2-path s3-path])
                      (catch Exception e
                        (println (str "Warning: screenshots failed: " (.getMessage e)))
                        [])
                      (finally
                        (stop-http-server! server)))
        pdf-path    (when generate-pdf
                      (let [pdf (.getAbsolutePath (io/file out "verify-report.pdf"))
                            idx (.getAbsolutePath (io/file report-path "index.html"))]
                        (generate-pdf! [idx] pdf)))]
    (when (and post-comment pr-number)
      (post-pr-comment! pr-number results repo))
    (merge results
      {:screenshots screenshots
       :pdf         pdf-path})))
