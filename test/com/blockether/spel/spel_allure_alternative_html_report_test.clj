(ns com.blockether.spel.spel-allure-alternative-html-report-test
  "Tests for the Blockether-themed Allure report renderer."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.spel-allure-alternative-html-report :as alternative-report])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir
  "Create a temp directory."
  ^File [^String prefix]
  (let [dir (Files/createTempDirectory prefix (into-array FileAttribute []))]
    (.toFile dir)))

(defn- clean-dir!
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(defn- write-result!
  [^File dir uuid status name start stop]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-result.json"))
    (str "{\"uuid\":\"" uuid
      "\",\"status\":\"" status
      "\",\"name\":\"" name
      "\",\"fullName\":\"suite." name
      "\",\"start\":" start
      ",\"stop\":" stop
      ",\"labels\":[{\"name\":\"suite\",\"value\":\"test-suite\"},{\"name\":\"parentSuite\",\"value\":\"com.example\"}]"
      ",\"steps\":[],\"attachments\":[]}")))

(defn- write-result-with-steps!
  [^File dir uuid status name start stop]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-result.json"))
    (str "{\"uuid\":\"" uuid
      "\",\"status\":\"" status
      "\",\"name\":\"" name
      "\",\"fullName\":\"suite." name
      "\",\"start\":" start
      ",\"stop\":" stop
      ",\"labels\":[{\"name\":\"suite\",\"value\":\"test-suite\"}]"
      ",\"steps\":[{\"name\":\"step 1\",\"status\":\"passed\",\"start\":" start ",\"stop\":" (+ start 100) ",\"steps\":[],\"attachments\":[]}]"
      ",\"attachments\":[]}")))

(defn- write-result-with-error!
  [^File dir uuid name start stop]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-result.json"))
    (str "{\"uuid\":\"" uuid
      "\",\"status\":\"failed"
      "\",\"name\":\"" name
      "\",\"fullName\":\"suite." name
      "\",\"start\":" start
      ",\"stop\":" stop
      ",\"labels\":[{\"name\":\"suite\",\"value\":\"test-suite\"}]"
      ",\"statusDetails\":{\"message\":\"Expected: 42\\nActual: 43\"}"
      ",\"steps\":[],\"attachments\":[]}")))

(defn- write-env-props!
  [^File dir props-map]
  (.mkdirs dir)
  (let [content (->> props-map
                  (map (fn [[k v]] (str k " = " v)))
                  (str/join "\n"))]
    (spit (io/file dir "environment.properties") (str content "\n"))))

(defn- write-result-with-attachments!
  [^File dir uuid status name start stop]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-result.json"))
    (str "{\"uuid\":\"" uuid
      "\",\"status\":\"" status
      "\",\"name\":\"" name
      "\",\"fullName\":\"suite." name
      "\",\"start\":" start
      ",\"stop\":" stop
      ",\"labels\":[{\"name\":\"suite\",\"value\":\"test-suite\"}]"
      ",\"steps\":[{\"name\":\"[API] call endpoint\",\"status\":\"passed\",\"start\":" start ",\"stop\":" (+ start 100)
      ",\"steps\":[],\"attachments\":[{\"name\":\"HTTP\",\"source\":\"" uuid "-attachment.md\",\"type\":\"text/markdown\"}]}]"
      ",\"attachments\":["
      "{\"name\":\"Playwright Trace\",\"source\":\"" uuid "-attachment.zip\",\"type\":\"application/vnd.allure.playwright-trace\"},"
      "{\"name\":\"Full stdout log\",\"source\":\"" uuid "-attachment.txt\",\"type\":\"text/plain\"},"
      "{\"name\":\"Network Activity (HAR)\",\"source\":\"" uuid "-attachment.har\",\"type\":\"application/json\"},"
      "{\"name\":\"Video Recording\",\"source\":\"" uuid "-attachment.webm\",\"type\":\"video/webm\"}]"
      "}"))
  (spit (io/file dir (str uuid "-attachment.md"))
    (str "## GET https://api.example.test/users → 200 OK\n\n"
      "### Request Headers\n```\naccept: application/json\n```\n\n"
      "### Response Body\n```json\n{\"ok\":true}\n```\n\n"
      "### cURL\n```bash\ncurl 'https://api.example.test/users'\n```\n"))
  (spit (io/file dir (str uuid "-attachment.txt")) "stdout line\n")
  (spit (io/file dir (str uuid "-attachment.har")) "{\"log\":{}}")
  (spit (io/file dir (str uuid "-attachment.webm")) "fake-webm")
  (spit (io/file dir (str uuid "-attachment.zip")) "fake-zip"))

(defdescribe block-report-load-results-test
  (describe "load-results"
    (it "returns empty vector for empty directory"
      (let [dir (tmp-dir "block-test-empty")]
        (expect (= [] (alternative-report/load-results (.getAbsolutePath dir))))
        (clean-dir! dir)))

    (it "loads result files"
      (let [dir (tmp-dir "block-test-load")]
        (write-result! dir "uuid-1" "passed" "test-1" 1000 2000)
        (write-result! dir "uuid-2" "failed" "test-2" 3000 4000)
        (let [results (alternative-report/load-results (.getAbsolutePath dir))]
          (expect (= 2 (count results)))
          (let [statuses (set (map #(get % "status") results))]
            (expect (contains? statuses "passed"))
            (expect (contains? statuses "failed"))))
        (clean-dir! dir)))))

(defdescribe block-report-generate-test
  (describe "generate!"
    (it "generates placeholder report when results directory is empty"
      (let [results-dir (tmp-dir "block-results-empty")
            output-dir (tmp-dir "block-output-empty")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (alternative-report/generate! results-path output-path)
        (let [html-file (io/file output-path "index.html")
              html (slurp html-file)]
          (expect (.isFile html-file))
          (expect (str/includes? html "No test result files were found for this run."))
          (expect (str/includes? html "summary-chip-label\">Total</span>"))
          (expect (str/includes? html "summary-chip-value\">0</span>"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-empty"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-empty")))))

    (it "generates HTML report from allure results"
      (let [results-dir (tmp-dir "block-results-gen")
            output-dir (tmp-dir "block-output-gen")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (write-result! results-dir "uuid-1" "passed" "test-pass" 1000 2000)
        (write-result! results-dir "uuid-2" "failed" "test-fail" 3000 4000)
        (write-result! results-dir "uuid-3" "broken" "test-broken" 5000 6000)
        (write-env-props! results-dir {"java.version" "21" "os.name" "Linux"})
        (alternative-report/generate! results-path output-path)
        (let [html-file (io/file output-path "index.html")]
          (expect (.isFile html-file))
          (let [html (slurp html-file)]
            (expect (str/includes? html "Allure Report"))
            (expect (str/includes? html "test-pass"))
            (expect (str/includes? html "test-fail"))
            (expect (str/includes? html "test-broken"))
            (expect (str/includes? html "PASSED"))
            (expect (str/includes? html "FAILED"))
            (expect (str/includes? html "BROKEN"))
            (expect (str/includes? html "java.version"))
            (expect (str/includes? html "Linux"))
            (expect (str/includes? html "Inter"))
            (expect (str/includes? html "JetBrains Mono")))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-gen"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-gen")))))

    (it "renders steps in test cards"
      (let [results-dir (tmp-dir "block-results-steps")
            output-dir (tmp-dir "block-output-steps")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (write-result-with-steps! results-dir "uuid-steps" "passed" "test-with-steps" 1000 2000)
        (alternative-report/generate! results-path output-path)
        (let [html (slurp (io/file output-path "index.html"))]
          (expect (str/includes? html "step 1"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-steps"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-steps")))))

    (it "renders error messages for failed tests"
      (let [results-dir (tmp-dir "block-results-err")
            output-dir (tmp-dir "block-output-err")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (write-result-with-error! results-dir "uuid-err" "test-error" 1000 2000)
        (alternative-report/generate! results-path output-path)
        (let [html (slurp (io/file output-path "index.html"))]
          (expect (str/includes? html "Expected: 42"))
          (expect (str/includes? html "Actual: 43"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-err"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-err")))))

    (it "accepts custom title"
      (let [results-dir (tmp-dir "block-results-title")
            output-dir (tmp-dir "block-output-title")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (write-result! results-dir "uuid-1" "passed" "test-1" 1000 2000)
        (alternative-report/generate! results-path output-path {:title "My Project"})
        (let [html (slurp (io/file output-path "index.html"))]
          (expect (str/includes? html "My Project"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-title"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-title")))))

    (it "renders compact collapsed layout and attachment UX"
      (let [results-dir (tmp-dir "block-results-att")
            output-dir (tmp-dir "block-output-att")
            results-path (.getAbsolutePath results-dir)
            output-path (.getAbsolutePath output-dir)]
        (write-result-with-attachments! results-dir "uuid-att" "passed" "test-with-attachments" 1000 2000)
        (alternative-report/generate! results-path output-path)
        (let [html (slurp (io/file output-path "index.html"))]
          (expect (str/includes? html "Expand"))
          (expect (str/includes? html "Collapse"))
          (expect (str/includes? html "Open Trace"))
          (expect (str/includes? html "trace-viewer/index.html?trace=../data/attachments/uuid-att-attachment.zip"))
          (expect (str/includes? html "data-testid=\"code-attachment-content\""))
          (expect (str/includes? html "class=\"language-md\""))
          (expect (str/includes? html "Full stdout log"))
          (expect (str/includes? html "Video Recording"))
          (expect (str/includes? html "data-action=\"expand-suites\""))
          ;; Finding #12: the kicker is suppressed when it exactly matches
          ;; the title (no point rendering "Allure Report" twice).
          (expect (not (str/includes? html "class=\"report-kicker\">Allure Report"))))
        (expect (.isFile (io/file output-path "data" "attachments" "uuid-att-attachment.md")))
        (expect (.isFile (io/file output-path "data" "attachments" "uuid-att-attachment.zip")))
        (expect (.isFile (io/file output-path "trace-viewer" "index.html"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-att"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-att")))))

    (it "always embeds an inline SVG favicon (no /favicon.ico 404)"
      (let [results-dir (tmp-dir "block-results-favicon")
            output-dir (tmp-dir "block-output-favicon")]
        (write-result! results-dir "uuid-f" "passed" "t" 1000 2000)
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "rel=\"icon\""))
          (expect (str/includes? html "image/svg+xml")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "renders commit metadata in the header when environment.properties has commit.* keys"
      (let [results-dir (tmp-dir "block-results-commit")
            output-dir (tmp-dir "block-output-commit")]
        (write-result! results-dir "uuid-c" "passed" "t" 1000 2000)
        (spit (io/file results-dir "environment.properties")
          (str "commit.sha=f9e04421f828d6fb24c46a405559aee3b5f3c7af\n"
            "commit.author=Test Author\n"
            "commit.subject=fix: tighten the thing\n"
            "commit.timestamp=1712707200000\n"
            "run.url=https://example.com/ci/run/42\n"
            "run.name=#544\n"))
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; Title comes from commit subject
          (expect (str/includes? html "fix: tighten the thing"))
          ;; <title> includes short-sha prefix for tab discrimination
          (expect (str/includes? html "<title>f9e0442 · fix: tighten the thing</title>"))
          ;; Kicker carries run-name / short SHA / author
          (expect (str/includes? html "#544"))
          (expect (str/includes? html "f9e0442"))
          (expect (str/includes? html "Test Author"))
          ;; Subtitle has the run link
          (expect (str/includes? html "https://example.com/ci/run/42"))
          (expect (str/includes? html "report-run-link")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "gracefully falls back to static header when no commit metadata is present"
      (let [results-dir (tmp-dir "block-results-nometa")
            output-dir (tmp-dir "block-output-nometa")]
        (write-result! results-dir "uuid-n" "passed" "t" 1000 2000)
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; Title is still the default
          (expect (str/includes? html "<h1 class=\"report-title\">Allure Report</h1>"))
          ;; No run-link anchor in the body (the .report-run-link CSS
          ;; selector is always present in the stylesheet — check for the
          ;; rendered <a …> instead).
          (expect (not (str/includes? html "<a class=\"report-run-link\"")))
          ;; Duplicate kicker is still suppressed (finding #12)
          (expect (not (str/includes? html "class=\"report-kicker\">Allure Report"))))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "defers test-card rendering into per-suite <template> elements (virtualization)"
      (let [results-dir (tmp-dir "block-results-virt")
            output-dir (tmp-dir "block-output-virt")]
        (dotimes [i 50]
          (write-result! results-dir (str "uuid-v-" i) "passed" (str "t" i)
            (* i 1000) (+ (* i 1000) 500)))
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; Every suite wraps its cards in a <template> so the initial DOM
          ;; doesn't count them.
          (expect (str/includes? html "<template class=\"suite-template\">"))
          ;; Metadata JSON is inlined per suite for filter/search across
          ;; unhydrated suites.
          (expect (str/includes? html "class=\"suite-meta\""))
          ;; The suite body starts unhydrated.
          (expect (str/includes? html "data-suite-hydrated=\"false\""))
          ;; The template MUST contain the test-card markup (so hydration can
          ;; clone it on toggle).
          (let [tpl-start (str/index-of html "<template class=\"suite-template\">")
                tpl-end (when tpl-start (str/index-of html "</template>" tpl-start))
                tpl-body (when (and tpl-start tpl-end)
                           (subs html tpl-start tpl-end))]
            (expect (and tpl-body (str/includes? tpl-body "test-card")))))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "renders the suites filter empty-state placeholder and its reset button"
      (let [results-dir (tmp-dir "block-results-empty")
            output-dir (tmp-dir "block-output-empty")]
        (write-result! results-dir "uuid-e" "passed" "t" 1000 2000)
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "id=\"suitesRoot\""))
          (expect (str/includes? html "id=\"suitesEmptyState\""))
          (expect (str/includes? html "No tests match the current filter."))
          (expect (str/includes? html "data-action=\"reset-filters\"")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "replaces the native sort <select> with a custom menu"
      (let [results-dir (tmp-dir "block-results-sort")
            output-dir (tmp-dir "block-output-sort")]
        (write-result! results-dir "uuid-s" "passed" "t" 1000 2000)
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; No native <select> with id sortSelect anymore
          (expect (not (str/includes? html "<select id=\"sortSelect\"")))
          ;; Custom menu structure is present
          (expect (str/includes? html "id=\"sortControl\""))
          (expect (str/includes? html "toolbar-sort-menu"))
          (expect (str/includes? html "aria-haspopup=\"menu\""))
          (expect (str/includes? html "data-value=\"longest\""))
          (expect (str/includes? html "data-value=\"name\"")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "exposes a 3-state theme toggle button in the header"
      (let [results-dir (tmp-dir "block-results-theme")
            output-dir (tmp-dir "block-output-theme")]
        (write-result! results-dir "uuid-t" "passed" "t" 1000 2000)
        (alternative-report/generate! (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "id=\"themeToggle\""))
          (expect (str/includes? html "class=\"theme-toggle\""))
          ;; data-theme driven CSS branches exist
          (expect (str/includes? html "html[data-theme='dark']"))
          (expect (str/includes? html "html[data-theme='light']"))
          ;; Pre-paint inline script to avoid FOUT/wrong-theme flash
          (expect (str/includes? html "spel.report.theme")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "accepts explicit :run-info opt (caller supplies metadata directly)"
      (let [results-dir (tmp-dir "block-results-runopts")
            output-dir (tmp-dir "block-output-runopts")]
        (write-result! results-dir "uuid-r" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:run-info {:commit-sha "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                      :commit-short "deadbee"
                      :commit-author "Alice"
                      :commit-subject "feat: option passthrough"
                      :run-url "https://example.com/run/99"
                      :run-name "#99"}})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "feat: option passthrough"))
          (expect (str/includes? html "deadbee"))
          (expect (str/includes? html "Alice"))
          (expect (str/includes? html "https://example.com/run/99")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "inlines an SVG logo passed as <svg> markup in :logo"
      (let [results-dir (tmp-dir "block-results-logo-svg")
            output-dir (tmp-dir "block-output-logo-svg")
            svg "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"#f0f\"/></svg>"]
        (write-result! results-dir "uuid-l1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:logo svg})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "class=\"report-logo\""))
          (expect (str/includes? html "data:image/svg+xml")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "copies a file-path logo into assets/ and references it"
      (let [results-dir (tmp-dir "block-results-logo-file")
            output-dir (tmp-dir "block-output-logo-file")
            logo-src (io/file results-dir "logo.svg")]
        (write-result! results-dir "uuid-l2" "passed" "t" 1000 2000)
        (spit logo-src "<svg xmlns='http://www.w3.org/2000/svg'><circle r='5'/></svg>")
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:logo "logo.svg"})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "class=\"report-logo\""))
          (expect (str/includes? html "src=\"assets/logo.svg\""))
          (expect (.isFile (io/file output-dir "assets" "logo.svg"))))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "passes through a data:image URL logo verbatim"
      (let [results-dir (tmp-dir "block-results-logo-data")
            output-dir (tmp-dir "block-output-logo-data")]
        (write-result! results-dir "uuid-l3" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:logo "data:image/png;base64,iVBORw0KGgo"})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "src=\"data:image/png;base64,iVBORw0KGgo\"")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "injects :custom-css as a separate <style> block after the built-in stylesheet"
      (let [results-dir (tmp-dir "block-results-css")
            output-dir (tmp-dir "block-output-css")
            css-rule ".report-title { color: hotpink !important; }"]
        (write-result! results-dir "uuid-c1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:custom-css css-rule})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "id=\"report-custom-css\""))
          (expect (str/includes? html css-rule)))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "renders :description under the title as a dedicated block"
      (let [results-dir (tmp-dir "block-results-desc")
            output-dir (tmp-dir "block-output-desc")]
        (write-result! results-dir "uuid-d1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:description "End-to-end suite, run on CI against staging."})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "class=\"report-description\""))
          (expect (str/includes? html "End-to-end suite, run on CI against staging.")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "passes through :description that already looks like HTML"
      (let [results-dir (tmp-dir "block-results-desc-html")
            output-dir (tmp-dir "block-output-desc-html")]
        (write-result! results-dir "uuid-d2" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:description "<p>Suite owned by <a href=\"https://team\">@qa</a></p>"})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "<a href=\"https://team\">@qa</a>")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "sanitizes hostile HTML in :description (scripts, handlers, js: URLs)"
      (let [results-dir (tmp-dir "block-results-desc-xss")
            output-dir (tmp-dir "block-output-desc-xss")
            hostile (str "<p>ok</p>"
                      "<script>alert('s')</script>"
                      "<iframe src=\"https://evil\"></iframe>"
                      "<img src=x onerror=\"alert('o')\">"
                      "<a href=\"javascript:alert('j')\">click</a>")]
        (write-result! results-dir "uuid-d3" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:description hostile})
        (let [html (slurp (io/file output-dir "index.html"))
              desc-start (str/index-of html "<div class=\"report-description\">")
              desc-end (when desc-start (str/index-of html "</div>" desc-start))
              desc-block (when (and desc-start desc-end)
                           (subs html desc-start (+ desc-end 6)))]
          (expect (some? desc-block))
          ;; Safe content survives
          (expect (str/includes? desc-block "<p>ok</p>"))
          ;; <script> fully stripped
          (expect (not (str/includes? desc-block "<script")))
          (expect (not (str/includes? desc-block "alert('s')")))
          ;; <iframe> fully stripped
          (expect (not (str/includes? desc-block "<iframe")))
          ;; onerror handler stripped from the <img>
          (expect (not (str/includes? desc-block "onerror")))
          (expect (not (str/includes? desc-block "alert('o')")))
          ;; javascript: URL replaced with #
          (expect (not (str/includes? desc-block "javascript:")))
          (expect (str/includes? desc-block "href=\"#\"")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "accepts :build-id / :build-date / :build-url as top-level opts"
      (let [results-dir (tmp-dir "block-results-build")
            output-dir (tmp-dir "block-output-build")]
        (write-result! results-dir "uuid-b1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:build-id "#777"
           :build-date 1712707200000
           :build-url "https://ci.example/run/777"})
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "#777"))
          (expect (str/includes? html "https://ci.example/run/777"))
          (expect (str/includes? html "class=\"report-run-link\"")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "neutralizes </style> in :custom-css so hostile CSS cannot break out"
      (let [results-dir (tmp-dir "block-results-cssxss")
            output-dir (tmp-dir "block-output-cssxss")
            hostile ".a{}</style><script>alert('xss')</script><style>.b{}"]
        (write-result! results-dir "uuid-x1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:custom-css hostile})
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; The injected <script> must NOT appear as live markup. A
          ;; literal `<script>alert('xss')</script>` substring in the
          ;; output would be the smoking gun.
          (expect (not (str/includes? html "<script>alert('xss')</script>")))
          ;; The intended CSS rules are still there.
          (expect (str/includes? html ".a{}"))
          (expect (str/includes? html ".b{}"))
          ;; The neutralized form appears instead.
          (expect (str/includes? html "<\\/style>")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "parses ISO-8601 :build-date strings (not just epoch millis)"
      (let [results-dir (tmp-dir "block-results-iso")
            output-dir (tmp-dir "block-output-iso")]
        (write-result! results-dir "uuid-i1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:build-id "#iso"
           :build-date "2026-04-10T12:00:00Z"})
        (let [html (slurp (io/file output-dir "index.html"))]
          ;; A <time> element means the date parsed successfully and the
          ;; subtitle picked it up. The exact formatted value depends on
          ;; the runner's JVM default timezone, so we just assert the
          ;; element exists.
          (expect (str/includes? html "<time>")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "accepts :logo-alt and omits aria-hidden on the logo itself"
      (let [results-dir (tmp-dir "block-results-logoalt")
            output-dir (tmp-dir "block-output-logoalt")]
        (write-result! results-dir "uuid-la1" "passed" "t" 1000 2000)
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir)
          {:logo "data:image/svg+xml,<svg/>"
           :logo-alt "Acme Co."})
        (let [html (slurp (io/file output-dir "index.html"))
              logo-tag-start (str/index-of html "<img class=\"report-logo\"")
              logo-tag-end (when logo-tag-start
                             (str/index-of html ">" logo-tag-start))
              logo-tag (when (and logo-tag-start logo-tag-end)
                         (subs html logo-tag-start (inc logo-tag-end)))]
          (expect (some? logo-tag))
          (expect (str/includes? logo-tag "alt=\"Acme Co.\""))
          ;; Critically: the logo <img> element specifically must NOT
          ;; be hidden from assistive tech.
          (expect (not (str/includes? logo-tag "aria-hidden"))))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "skips logo files larger than the 2 MB cap and logs to stderr"
      (let [results-dir (tmp-dir "block-results-logobig")
            output-dir (tmp-dir "block-output-logobig")
            big (io/file results-dir "huge.png")]
        (write-result! results-dir "uuid-lb1" "passed" "t" 1000 2000)
        ;; 2.5 MB of zeros — above the cap
        (with-open [os (java.io.FileOutputStream. big)]
          (.write os (byte-array (* 2.5 1024 1024))))
        (let [stderr (java.io.ByteArrayOutputStream.)
              err-writer (java.io.PrintWriter. stderr)]
          (binding [*err* err-writer]
            (alternative-report/generate!
              (.getAbsolutePath results-dir)
              (.getAbsolutePath output-dir)
              {:logo "huge.png"}))
          (.flush err-writer)
          (let [html (slurp (io/file output-dir "index.html"))
                err-str (str stderr)]
            ;; No logo rendered
            (expect (not (str/includes? html "class=\"report-logo\"")))
            ;; Not copied into assets
            (expect (not (.isFile (io/file output-dir "assets" "logo.png"))))
            ;; stderr warning
            (expect (str/includes? err-str "exceeds"))))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))

    (it "reads report.logo / report.description / build.* from environment.properties"
      (let [results-dir (tmp-dir "block-results-env-custom")
            output-dir (tmp-dir "block-output-env-custom")]
        (write-result! results-dir "uuid-e1" "passed" "t" 1000 2000)
        (spit (io/file results-dir "environment.properties")
          (str "report.logo=data:image/svg+xml,<svg/>\n"
            "report.description=Nightly smoke run\n"
            "build.id=#321\n"
            "build.url=https://ci.example/run/321\n"))
        (alternative-report/generate!
          (.getAbsolutePath results-dir)
          (.getAbsolutePath output-dir))
        (let [html (slurp (io/file output-dir "index.html"))]
          (expect (str/includes? html "class=\"report-logo\""))
          (expect (str/includes? html "Nightly smoke run"))
          (expect (str/includes? html "#321"))
          (expect (str/includes? html "https://ci.example/run/321")))
        (clean-dir! results-dir)
        (clean-dir! output-dir)))))
