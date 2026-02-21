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
