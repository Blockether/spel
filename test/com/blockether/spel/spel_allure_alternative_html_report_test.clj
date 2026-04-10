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
          (expect (str/includes? html "class=\"report-kicker\">Allure Report")))
        (expect (.isFile (io/file output-path "data" "attachments" "uuid-att-attachment.md")))
        (expect (.isFile (io/file output-path "data" "attachments" "uuid-att-attachment.zip")))
        (expect (.isFile (io/file output-path "trace-viewer" "index.html"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-results-att"))))
      (clean-dir! (io/file (.getAbsolutePath (tmp-dir "block-output-att")))))))
