(ns com.blockether.spel.allure-verify-test
  "Tests for allure-verify: parse-results, generate-html-pages!, verify-results!."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.allure-verify :as av])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- tmp-dir
  "Create a temp directory."
  ^File [^String prefix]
  (let [dir (Files/createTempDirectory prefix (into-array FileAttribute []))]
    (.toFile dir)))

(defn- clean-dir!
  "Recursively delete a directory."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

;; =============================================================================
;; parse-results Tests
;; =============================================================================

(defdescribe parse-results-test
  "Tests for parse-results function"

  (describe "CT-only results"

    (it "parses CT results and classifies all as ct-suites"
      (let [result (av/parse-results "test/resources/allure-verify/results-ct")]
        (expect (= 3 (:total result)))
        (expect (= 3 (:ct-total result)))
        (expect (= 0 (:lt-total result)))
        (expect (= 2 (:passed result)))
        (expect (= 1 (:failed result)))
        (expect (contains? (:ct-suites result) "com.blockether.spel.ct.smoke-test"))
        (expect (contains? (:ct-suites result) "com.blockether.spel.ct.data-test"))
        (expect (contains? (:ct-suites result) "com.blockether.spel.ct.markdown-test"))
        (expect (empty? (:lazytest-suites result)))))

    (it "each test has required fields"
      (let [result (av/parse-results "test/resources/allure-verify/results-ct")
            tests  (mapcat val (:ct-suites result))]
        (doseq [t tests]
          (expect (string? (:name t)))
          (expect (string? (:status t)))
          (expect (string? (:suite t)))
          (expect (contains? t :feature))))))

  (describe "lazytest-only results"

    (it "parses lazytest results and classifies all as lazytest-suites"
      (let [result (av/parse-results "test/resources/allure-verify/results-lazytest")]
        (expect (= 3 (:total result)))
        (expect (= 0 (:ct-total result)))
        (expect (= 3 (:lt-total result)))
        (expect (= 3 (:passed result)))
        (expect (= 0 (:failed result)))
        (expect (empty? (:ct-suites result)))
        (expect (contains? (:lazytest-suites result) "com.blockether.spel.core-test"))
        (expect (contains? (:lazytest-suites result) "com.blockether.spel.page-test"))
        (expect (contains? (:lazytest-suites result) "com.blockether.spel.locator-test")))))

  (describe "mixed results"

    (it "correctly separates CT and lazytest from mixed directory"
      (let [result (av/parse-results "test/resources/allure-verify/results-mixed")]
        (expect (= 3 (:total result)))
        (expect (= 1 (:ct-total result)))
        (expect (= 2 (:lt-total result)))
        (expect (= 2 (:passed result)))
        (expect (= 1 (:failed result)))
        (expect (= 1 (count (:ct-suites result))))
        (expect (= 2 (count (:lazytest-suites result)))))))

  (describe "error handling"

    (it "returns zeroed map for nonexistent directory"
      (let [result (av/parse-results "/tmp/nonexistent-dir-allure-verify-test")]
        (expect (= 0 (:total result)))
        (expect (= 0 (:ct-total result)))
        (expect (= 0 (:lt-total result)))
        (expect (empty? (:ct-suites result)))
        (expect (empty? (:lazytest-suites result)))))

    (it "handles directory with non-JSON files gracefully"
      (let [dir (tmp-dir "av-test-nonjson")]
        (try
          (spit (io/file dir "readme.txt") "not json")
          (spit (io/file dir "data.xml") "<xml/>")
          (let [result (av/parse-results (.getPath dir))]
            (expect (= 0 (:total result))))
          (finally
            (clean-dir! dir)))))

    (it "handles malformed JSON files gracefully"
      (let [dir (tmp-dir "av-test-malformed")]
        (try
          (.mkdirs dir)
          (spit (io/file dir "bad-result.json") "{invalid json!!!")
          (spit (io/file dir "good-result.json")
            "{\"name\":\"ok\",\"status\":\"passed\",\"labels\":[{\"name\":\"framework\",\"value\":\"lazytest\"},{\"name\":\"suite\",\"value\":\"test-ns\"}]}")
          (let [result (av/parse-results (.getPath dir))]
            ;; Should parse the good file and skip the bad one
            (expect (= 1 (:total result)))
            (expect (= 1 (:passed result))))
          (finally
            (clean-dir! dir)))))))

;; =============================================================================
;; generate-html-pages! Tests
;; =============================================================================

(defdescribe generate-html-pages-test
  "Tests for generate-html-pages!"

  (describe "HTML generation"

    (it "creates both HTML files in output directory"
      (let [results (av/parse-results "test/resources/allure-verify/results-mixed")
            out-dir (tmp-dir "av-html-test")]
        (try
          (let [paths (av/generate-html-pages! results (.getPath out-dir) {})]
            (expect (= 2 (count paths)))
            (expect (.exists (io/file (first paths))))
            (expect (.exists (io/file (second paths))))
            (expect (str/ends-with? (first paths) "verify-summary.html"))
            (expect (str/ends-with? (second paths) "verify-lazytest.html")))
          (finally
            (clean-dir! out-dir)))))

    (it "includes test counts in summary HTML"
      (let [results (av/parse-results "test/resources/allure-verify/results-mixed")
            out-dir (tmp-dir "av-html-counts")]
        (try
          (let [paths   (av/generate-html-pages! results (.getPath out-dir) {})
                content (slurp (first paths))]
            (expect (str/includes? content "3"))  ;; total
            (expect (str/includes? content "clojure.test")))
          (finally
            (clean-dir! out-dir)))))

    (it "includes PR number when provided"
      (let [results (av/parse-results "test/resources/allure-verify/results-ct")
            out-dir (tmp-dir "av-html-pr")]
        (try
          (let [paths   (av/generate-html-pages! results (.getPath out-dir)
                          {:pr-number "99"})
                content (slurp (first paths))]
            (expect (str/includes? content "PR #99")))
          (finally
            (clean-dir! out-dir)))))

    (it "shows lazytest warning when no lazytest results"
      (let [results (av/parse-results "test/resources/allure-verify/results-ct")
            out-dir (tmp-dir "av-html-lt-warn")]
        (try
          (let [paths   (av/generate-html-pages! results (.getPath out-dir) {})
                content (slurp (first paths))]
            (expect (str/includes? content "Lazytest results missing")))
          (finally
            (clean-dir! out-dir)))))))

;; =============================================================================
;; verify-results! Tests (non-browser parts)
;; =============================================================================

(defdescribe verify-results-summary-test
  "Tests for verify-results! return value structure"

  (describe "return map structure"

    (it "returns expected keys from verify-results!"
      (let [results (av/parse-results "test/resources/allure-verify/results-mixed")
            out-dir (tmp-dir "av-verify-test")]
        (try
          ;; Test parse-results directly (verify-results! needs Playwright)
          (expect (contains? results :total))
          (expect (contains? results :passed))
          (expect (contains? results :failed))
          (expect (contains? results :ct-total))
          (expect (contains? results :lt-total))
          (expect (contains? results :ct-suites))
          (expect (contains? results :lazytest-suites))
          (finally
            (clean-dir! out-dir)))))))
