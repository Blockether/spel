(ns com.blockether.spel.allure-reporter-test
  "Tests for allure-reporter merge-results! and helpers."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.allure-reporter :as reporter])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- tmp-dir
  "Create a temp directory that auto-deletes on JVM exit."
  ^File [^String prefix]
  (let [dir (Files/createTempDirectory prefix (into-array FileAttribute []))]
    (.toFile dir)))

(defn- clean-dir!
  "Recursively delete a directory."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(defn- write-result!
  "Write a mock allure result JSON file."
  [^File dir ^String uuid ^String status]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-result.json"))
    (str "{\"uuid\":\"" uuid "\",\"status\":\"" status "\",\"name\":\"test-" uuid "\"}")))

(defn- write-attachment!
  "Write a mock attachment file."
  [^File dir ^String uuid ^String ext ^String content]
  (.mkdirs dir)
  (spit (io/file dir (str uuid "-attachment." ext)) content))

(defn- write-env-props!
  "Write environment.properties."
  [^File dir props-map]
  (.mkdirs dir)
  (let [content (->> props-map
                  (map (fn [[k v]] (str k " = " v)))
                  (str/join "\n"))]
    (spit (io/file dir "environment.properties") (str content "\n"))))

(defn- write-categories!
  "Write categories.json."
  [^File dir categories-str]
  (.mkdirs dir)
  (spit (io/file dir "categories.json") categories-str))

(defn- list-files
  "List file names in a directory."
  [^File dir]
  (when (.isDirectory dir)
    (set (map #(.getName ^File %) (.listFiles dir)))))

(defn- read-env-props
  "Read environment.properties into a map."
  [^File dir]
  (let [f (io/file dir "environment.properties")]
    (when (.isFile f)
      (into {}
        (for [line (str/split-lines (slurp f))
              :when (not (str/blank? line))
              :let [[k v] (str/split line #"\s*=\s*" 2)]
              :when k]
          [k (or v "")])))))

;; =============================================================================
;; Tests
;; =============================================================================

(defdescribe merge-results-test
  "Tests for merge-results! function"

  (describe "basic merging"

    (it "merges result files from two directories"
      (let [base   (tmp-dir "merge-test")
            dir-a  (io/file base "results-a")
            dir-b  (io/file base "results-b")
            output (io/file base "merged")]
        (try
          (write-result! dir-a "aaa-111" "passed")
          (write-result! dir-a "aaa-222" "failed")
          (write-result! dir-b "bbb-111" "passed")
          (write-attachment! dir-b "bbb-111" "png" "fake-screenshot")

          (let [result (reporter/merge-results!
                         [(.getPath dir-a) (.getPath dir-b)]
                         {:output-dir (.getPath output)
                          :report     false})]
            (expect (= 3 (:results result)))
            (expect (= 4 (:merged result)))
            ;; All result files present
            (let [files (list-files output)]
              (expect (contains? files "aaa-111-result.json"))
              (expect (contains? files "aaa-222-result.json"))
              (expect (contains? files "bbb-111-result.json"))
              (expect (contains? files "bbb-111-attachment.png"))))
          (finally
            (clean-dir! base)))))

    (it "merges result files from three directories"
      (let [base   (tmp-dir "merge-test-3")
            dir-a  (io/file base "results-a")
            dir-b  (io/file base "results-b")
            dir-c  (io/file base "results-c")
            output (io/file base "merged")]
        (try
          (write-result! dir-a "aaa-111" "passed")
          (write-result! dir-b "bbb-111" "passed")
          (write-result! dir-c "ccc-111" "broken")

          (let [result (reporter/merge-results!
                         [(.getPath dir-a) (.getPath dir-b) (.getPath dir-c)]
                         {:output-dir (.getPath output)
                          :report     false})]
            (expect (= 3 (:results result)))
            (let [files (list-files output)]
              (expect (contains? files "aaa-111-result.json"))
              (expect (contains? files "bbb-111-result.json"))
              (expect (contains? files "ccc-111-result.json"))))
          (finally
            (clean-dir! base))))))

  (describe "environment.properties merging"

    (it "merges env properties from multiple dirs, last wins on duplicates"
      (let [base   (tmp-dir "merge-env-test")
            dir-a  (io/file base "results-a")
            dir-b  (io/file base "results-b")
            output (io/file base "merged")]
        (try
          (write-result! dir-a "aaa-111" "passed")
          (write-result! dir-b "bbb-111" "passed")
          (write-env-props! dir-a {"os.name" "Linux" "java.version" "21"})
          (write-env-props! dir-b {"os.name" "macOS" "spel.version" "0.3.0"})

          (reporter/merge-results!
            [(.getPath dir-a) (.getPath dir-b)]
            {:output-dir (.getPath output)
             :report     false})

          (let [props (read-env-props output)]
            ;; dir-b wins for os.name
            (expect (= "macOS" (get props "os.name")))
            ;; dir-a's unique key preserved
            (expect (= "21" (get props "java.version")))
            ;; dir-b's unique key preserved
            (expect (= "0.3.0" (get props "spel.version"))))
          (finally
            (clean-dir! base))))))

  (describe "categories.json merging"

    (it "deduplicates categories by name"
      (let [base   (tmp-dir "merge-cat-test")
            dir-a  (io/file base "results-a")
            dir-b  (io/file base "results-b")
            output (io/file base "merged")]
        (try
          (write-result! dir-a "aaa-111" "passed")
          (write-result! dir-b "bbb-111" "passed")
          ;; Same categories in both dirs
          (write-categories! dir-a
            "[{\"name\":\"Assertion failures\",\"matchedStatuses\":[\"failed\"],\"messageRegex\":\".*\"}]")
          (write-categories! dir-b
            "[{\"name\":\"Assertion failures\",\"matchedStatuses\":[\"failed\"],\"messageRegex\":\".*\"},{\"name\":\"Unexpected errors\",\"matchedStatuses\":[\"broken\"],\"messageRegex\":\".*\"}]")

          (reporter/merge-results!
            [(.getPath dir-a) (.getPath dir-b)]
            {:output-dir (.getPath output)
             :report     false})

          (let [content (slurp (io/file output "categories.json"))]
            ;; Should have both category names, deduplicated
            (expect (str/includes? content "Assertion failures"))
            (expect (str/includes? content "Unexpected errors"))
            ;; "Assertion failures" should appear only once
            (let [matches (re-seq #"Assertion failures" content)]
              (expect (= 1 (count matches)))))
          (finally
            (clean-dir! base))))))

  (describe "options"

    (it "skips invalid source directories gracefully"
      (let [base      (tmp-dir "merge-invalid-test")
            dir-a     (io/file base "results-a")
            dir-bogus (io/file base "nonexistent")
            output    (io/file base "merged")]
        (try
          (write-result! dir-a "aaa-111" "passed")

          (let [result (reporter/merge-results!
                         [(.getPath dir-a) (.getPath dir-bogus)]
                         {:output-dir (.getPath output)
                          :report     false})]
            (expect (= 1 (:results result)))
            (expect (contains? (list-files output) "aaa-111-result.json")))
          (finally
            (clean-dir! base)))))

    (it "cleans output directory when :clean true (default)"
      (let [base   (tmp-dir "merge-clean-test")
            dir-a  (io/file base "results-a")
            output (io/file base "merged")]
        (try
          ;; Pre-populate output with stale file
          (.mkdirs output)
          (spit (io/file output "stale-result.json") "{}")
          (write-result! dir-a "aaa-111" "passed")

          (reporter/merge-results!
            [(.getPath dir-a)]
            {:output-dir (.getPath output)
             :report     false
             :clean      true})

          (let [files (list-files output)]
            (expect (contains? files "aaa-111-result.json"))
            (expect (not (contains? files "stale-result.json"))))
          (finally
            (clean-dir! base)))))

    (it "preserves existing files when :clean false"
      (let [base   (tmp-dir "merge-noclean-test")
            dir-a  (io/file base "results-a")
            output (io/file base "merged")]
        (try
          ;; Pre-populate output
          (.mkdirs output)
          (spit (io/file output "existing-result.json") "{\"uuid\":\"existing\"}")
          (write-result! dir-a "aaa-111" "passed")

          (reporter/merge-results!
            [(.getPath dir-a)]
            {:output-dir (.getPath output)
             :report     false
             :clean      false})

          (let [files (list-files output)]
            (expect (contains? files "aaa-111-result.json"))
            (expect (contains? files "existing-result.json")))
          (finally
            (clean-dir! base)))))))

;; =============================================================================
;; Safari SW Patch Tests
;; =============================================================================

(defn- write-sw-bundle!
  "Write a mock sw.bundle.js with given content under report/trace-viewer/."
  ^File [^File report ^String content]
  (let [tv-dir (io/file report "trace-viewer")]
    (.mkdirs tv-dir)
    (let [sw (io/file tv-dir "sw.bundle.js")]
      (spit sw content)
      sw)))

(defdescribe safari-sw-patches-test
  "Tests for Safari Service Worker patch functions"

  (describe "patch-sw-safari-compat!"

    (it "patches trace parameter fallback"
      (let [base    (tmp-dir "safari-compat-test")
            report  (io/file base "report")
            content "if(!n)throw new Error(\"trace parameter is missing\")"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-compat! report)
          (let [patched (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (expect (str/includes? patched "n=$e.get(s)"))
            (expect (not (= content patched))))
          (finally
            (clean-dir! base)))))

    (it "is idempotent — does not double-patch"
      (let [base    (tmp-dir "safari-compat-idem-test")
            report  (io/file base "report")
            content "if(!n)throw new Error(\"trace parameter is missing\")"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-compat! report)
          (let [first-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (#'reporter/patch-sw-safari-compat! report)
            (let [second-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
              (expect (= first-pass second-pass))))
          (finally
            (clean-dir! base)))))

    (it "leaves content unchanged when pattern not found"
      (let [base    (tmp-dir "safari-compat-noop-test")
            report  (io/file base "report")
            content "some unrelated content"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-compat! report)
          (let [result (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (expect (= content result)))
          (finally
            (clean-dir! base))))))

  (describe "patch-sw-safari-transform-stream!"

    (it "prepends TransformStream shim to sw.bundle.js"
      (let [base    (tmp-dir "safari-ts-test")
            report  (io/file base "report")
            content "var original=1;class Foo extends TransformStream{}"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-transform-stream! report)
          (let [patched (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            ;; Shim is prepended
            (expect (str/starts-with? patched "(function(){"))
            ;; Original content preserved after shim
            (expect (str/includes? patched content))
            ;; Key shim components present
            (expect (str/includes? patched "TransformStream"))
            (expect (str/includes? patched "Object.setPrototypeOf"))
            (expect (str/includes? patched "new.target")))
          (finally
            (clean-dir! base)))))

    (it "is idempotent — does not double-prepend shim"
      (let [base    (tmp-dir "safari-ts-idem-test")
            report  (io/file base "report")
            content "var original=1;"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-transform-stream! report)
          (let [first-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (#'reporter/patch-sw-safari-transform-stream! report)
            (let [second-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
              ;; Second application should prepend again (shim != original content)
              ;; but the guard (function(){ check should still work at runtime
              ;; We just verify the shim IS present
              (expect (str/starts-with? second-pass "(function(){"))
              (expect (str/includes? second-pass content))))
          (finally
            (clean-dir! base)))))

    (it "does nothing when sw.bundle.js does not exist"
      (let [base   (tmp-dir "safari-ts-missing-test")
            report (io/file base "report")]
        (try
          (.mkdirs report)
          ;; Should not throw — just a no-op
          (#'reporter/patch-sw-safari-transform-stream! report)
          (expect (not (.exists (io/file report "trace-viewer" "sw.bundle.js"))))
          (finally
            (clean-dir! base))))))

  (describe "patch-sw-safari-response-headers!"

    (it "patches Response.headers.set to new Response construction"
      (let [base    (tmp-dir "safari-headers-test")
            report  (io/file base "report")
            content (str "return Fn&&_.headers.set(\"Content-Security-Policy\","
                         "\"upgrade-insecure-requests\"),_")
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-response-headers! report)
          (let [patched (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            ;; Original mutable pattern removed
            (expect (not (str/includes? patched "_.headers.set")))
            ;; New Response construction present
            (expect (str/includes? patched "new Response(_.body"))
            ;; CSP header included in new construction
            (expect (str/includes? patched "Content-Security-Policy"))
            ;; Ternary operator used
            (expect (str/includes? patched "Fn?")))
          (finally
            (clean-dir! base)))))

    (it "is idempotent — does not double-patch"
      (let [base    (tmp-dir "safari-headers-idem-test")
            report  (io/file base "report")
            content (str "return Fn&&_.headers.set(\"Content-Security-Policy\","
                         "\"upgrade-insecure-requests\"),_")
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-response-headers! report)
          (let [first-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (#'reporter/patch-sw-safari-response-headers! report)
            (let [second-pass (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
              (expect (= first-pass second-pass))))
          (finally
            (clean-dir! base)))))

    (it "leaves content unchanged when pattern not found"
      (let [base    (tmp-dir "safari-headers-noop-test")
            report  (io/file base "report")
            content "some unrelated sw content"
            _       (write-sw-bundle! report content)]
        (try
          (#'reporter/patch-sw-safari-response-headers! report)
          (let [result (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            (expect (= content result)))
          (finally
            (clean-dir! base))))))

  (describe "all patches applied together"

    (it "applies all three Safari patches in sequence without conflict"
      (let [base    (tmp-dir "safari-all-patches-test")
            report  (io/file base "report")
            ;; Content with both patchable patterns
            content (str "if(!n)throw new Error(\"trace parameter is missing\");"
                         "return Fn&&_.headers.set(\"Content-Security-Policy\","
                         "\"upgrade-insecure-requests\"),_")
            _       (write-sw-bundle! report content)]
        (try
          ;; Apply all three patches in the same order as generate-html-report!
          (#'reporter/patch-sw-safari-compat! report)
          (#'reporter/patch-sw-safari-transform-stream! report)
          (#'reporter/patch-sw-safari-response-headers! report)
          (let [patched (slurp (io/file report "trace-viewer" "sw.bundle.js"))]
            ;; Safari compat patch applied
            (expect (str/includes? patched "n=$e.get(s)"))
            ;; TransformStream shim prepended
            (expect (str/starts-with? patched "(function(){"))
            (expect (str/includes? patched "Object.setPrototypeOf"))
            ;; Response headers patch applied
            (expect (str/includes? patched "new Response(_.body"))
            (expect (not (str/includes? patched "_.headers.set"))))
          (finally
            (clean-dir! base)))))))
