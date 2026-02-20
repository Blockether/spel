(ns com.blockether.spel.ci
  "CI utilities for assembling Allure report sites.

   Replaces Python scripts in GitHub Actions workflow with native Clojure.

   Usage:
     spel ci-assemble --help
     spel ci-assemble --site-dir=gh-pages-site --run=123 ..."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.time Instant ZonedDateTime]
   [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Allure History Patching
;; =============================================================================

(defn patch-allure-history!
  "Patch .allure-history.jsonl with URL and commit info for the latest entry.
   
   Options:
     :history-file - path to .allure-history.jsonl (default: .allure-history.jsonl)
     :report-url   - URL to inject into latest entry
     :commit-sha   - full commit SHA
     :commit-msg   - commit message (first line used)
     :run-number   - CI run number"
  [{:keys [history-file report-url commit-sha commit-msg run-number]}]
  (let [history-path (io/file (or history-file ".allure-history.jsonl"))]
    (when (.isFile history-path)
      (let [lines (vec (->> (slurp history-path)
                         str/split-lines
                         (remove str/blank?)))
            sha-short (when commit-sha (subs commit-sha 0 (min 8 (count commit-sha))))
            msg-first (when commit-msg (first (str/split-lines commit-msg)))
            msg-truncated (when msg-first
                            (if (> (count msg-first) 100)
                              (subs msg-first 0 100)
                              msg-first))
            name (str "#" run-number " · " sha-short " · " msg-truncated)
            patched (map-indexed
                      (fn [idx line]
                        (if (= idx (dec (count lines)))
                          ;; Last entry — patch it
                          (let [entry (json/read-json line)]
                            (-> entry
                              (assoc "url" report-url)
                              (assoc "name" name)
                              (update "testResults"
                                (fn [results]
                                  (when results
                                    (reduce-kv
                                      (fn [m k v]
                                        (assoc m k (assoc v "url" report-url)))
                                      {}
                                      results))))
                              (json/write-json-str)))
                          line))
                      lines)]
        (spit history-path (str (str/join "\n" patched) "\n"))
        (println (str "Injected URL " report-url " and commit info into latest history entry"))))))

;; =============================================================================
;; Builds Metadata
;; =============================================================================

(defn- parse-iso-timestamp
  "Parse ISO 8601 timestamp to epoch millis."
  [ts-str]
  (when (and ts-str (not (str/blank? ts-str)))
    (try
      (let [zdt (ZonedDateTime/parse ts-str DateTimeFormatter/ISO_DATE_TIME)]
        (.toEpochMilli (.toInstant zdt)))
      (catch Exception _
        (try
          ;; Try with 'Z' suffix replaced
          (let [normalized (str/replace ts-str "Z" "+00:00")
                zdt (ZonedDateTime/parse normalized DateTimeFormatter/ISO_DATE_TIME)]
            (.toEpochMilli (.toInstant zdt)))
          (catch Exception _
            (.toEpochMilli (Instant/now))))))))

(defn- list-report-dirs
  "List numeric report directories in site-dir, sorted descending."
  [^File site-dir]
  (->> (.listFiles site-dir)
    (filter #(.isDirectory ^File %))
    (filter #(re-matches #"\d+" (.getName ^File %)))
    (sort-by #(parse-long (.getName ^File %)))
    reverse
    (map #(.getName ^File %))))

(defn generate-builds-metadata!
  "Generate builds.json and update builds-meta.json with current run info.
   
   Options:
     :site-dir     - path to gh-pages-site directory
     :run-number   - current CI run number
     :commit-sha   - full commit SHA
      :commit-msg   - commit message
      :commit-author - commit author name
      :commit-ts    - commit timestamp (ISO 8601)
      :tests-passed - whether tests passed (boolean)
      :repo-url     - repository URL
      :run-url      - CI run URL
      :version      - project version
      :version-badge - version badge type (release/candidate)
      :test-counts  - map with :passed :failed :broken :skipped :total"
  [{:keys [site-dir run-number commit-sha commit-msg commit-author commit-ts
           tests-passed repo-url run-url version version-badge test-counts]}]
  (let [site (io/file site-dir)
        meta-file (io/file site "builds-meta.json")
        builds-file (io/file site "builds.json")
        badge-file (io/file site "badge.json")

        ;; Load existing metadata
        meta (if (.isFile meta-file)
               (json/read-json (slurp meta-file))
               {})

        ;; Parse timestamp
        ts (or (parse-iso-timestamp commit-ts)
             (.toEpochMilli (Instant/now)))

        ;; First line of commit message
        msg-first (when commit-msg
                    (first (str/split-lines commit-msg)))

        ;; Current run metadata
        run-meta {"sha" (or commit-sha "")
                  "message" (or msg-first "")
                  "author" (or commit-author "")
                  "timestamp" ts
                  "passed" (boolean tests-passed)
                  "repo_url" (or repo-url "")
                  "run_url" (or run-url "")
                  "version" (or version "")
                  "badge" (or version-badge "")
                  "tests" {"passed" (get test-counts :passed 0)
                           "failed" (get test-counts :failed 0)
                           "broken" (get test-counts :broken 0)
                           "skipped" (get test-counts :skipped 0)
                           "total" (get test-counts :total 0)}}

        ;; Update metadata
        meta' (assoc meta run-number run-meta)

        ;; Get available report directories
        dirs (list-report-dirs site)

        ;; Prune metadata to only existing dirs
        pruned (select-keys meta' dirs)

        ;; Generate builds array
        builds (vec
                 (for [d dirs
                       :let [entry (get pruned d {})
                             tests (get entry "tests" {})]]
                   {"run" d
                    "sha" (get entry "sha" "")
                    "message" (get entry "message" "")
                    "author" (get entry "author" "")
                    "timestamp" (get entry "timestamp" 0)
                    "passed" (get entry "passed" true)
                    "repo_url" (get entry "repo_url" "")
                    "run_url" (get entry "run_url" "")
                    "version" (get entry "version" "")
                    "badge" (get entry "badge" "")
                    "tests" tests}))]

    ;; Write metadata files
    (spit meta-file (json/write-json-str pruned))
    (spit builds-file (json/write-json-str builds))

    ;; Generate badge.json for latest build
    (when (seq builds)
      (let [latest (first builds)
            latest-tests (get latest "tests" {})
            tp (long (get latest-tests "passed" 0))
            tf (+ (long (get latest-tests "failed" 0))
                 (long (get latest-tests "broken" 0)))
            badge-msg (if (pos? tf)
                        (str tp " passed, " tf " failed")
                        (str tp " passed"))
            badge-color (if (pos? tf) "red" "brightgreen")
            badge {"schemaVersion" 1
                   "label" "Allure Report"
                   "message" badge-msg
                   "color" badge-color}]
        (spit badge-file (json/write-json-str badge))
        (println (str "Generated badge.json: " badge-msg))))

    (println (str "Generated builds.json with " (count builds) " entries"))))

;; =============================================================================
;; Index HTML Patching
;; =============================================================================

(defn patch-index-html!
  "Patch index.html with logo and title placeholders.
   
   Options:
     :index-file    - path to index.html
     :logo-file     - path to logo.svg (optional)
     :title         - title to inject (optional)
     :subtitle      - subtitle to inject (optional)"
  [{:keys [index-file logo-file title subtitle]}]
  (let [index (io/file index-file)]
    (when (.isFile index)
      (let [html (slurp index)

            ;; Inject logo if present
            html' (if (and logo-file (.isFile (io/file logo-file)))
                    (let [logo (slurp (io/file logo-file))
                          ;; Adjust viewBox for better display
                          logo' (-> logo
                                  (str/replace "viewBox=\"0 0 400 350\""
                                    "viewBox=\"60 10 280 230\"")
                                  (str/replace "width=\"400\" height=\"350\"" ""))]
                      (str/replace html "<!--LOGO_PLACEHOLDER-->" logo'))
                    html)

            ;; Inject title if provided
            html'' (if (and title (not (str/blank? title)))
                     (str/replace html'
                       #"<!--TITLE_PLACEHOLDER-->.*?<!--/TITLE_PLACEHOLDER-->"
                       title)
                     html')

            ;; Inject subtitle if provided
            html''' (if (and subtitle (not (str/blank? subtitle)))
                      (str/replace html''
                        #"<!--SUBTITLE_PLACEHOLDER-->.*?<!--/SUBTITLE_PLACEHOLDER-->"
                        subtitle)
                      html'')]

        (spit index html''')
        (println "Patched index.html")))))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(def ^:private cli-help
  "ci-assemble - Assemble Allure report site for CI deployment

Usage:
  spel ci-assemble [options]

This command replaces Python scripts in CI workflows. It:
  1. Patches .allure-history.jsonl with URL and commit info
  2. Generates builds.json and builds-meta.json
   3. Generates badge.json for test result badge
  4. Patches index.html with logo and titles

Options:
  --site-dir DIR         Site directory (default: gh-pages-site)
  --run NUMBER           CI run number (required)
  --commit-sha SHA       Git commit SHA
  --commit-msg MSG       Git commit message
  --commit-author AUTHOR Git commit author
  --commit-ts TS         Commit timestamp (ISO 8601)
  --tests-passed BOOL    Whether tests passed (true/false)
  --repo-url URL         Repository URL
  --run-url URL          CI run URL
  --version VER          Project version
  --version-badge TYPE   Version badge type (release/candidate)
  --test-passed N        Number of passed tests
  --test-failed N        Number of failed tests
  --test-broken N        Number of broken tests
  --test-skipped N       Number of skipped tests
  --history-file FILE    Allure history file (default: .allure-history.jsonl)
  --report-url URL       Report URL for history patching
  --logo-file FILE       Logo SVG file path
  --index-file FILE      Index HTML file path (for patching)
  --title TEXT           Title to inject into index.html
  --subtitle TEXT        Subtitle to inject into index.html
  --help                 Show this help

Environment Variables:
  All options can also be set via environment variables with SPEL_CI_ prefix.
  For example: SPEL_CI_RUN_NUMBER, SPEL_CI_COMMIT_SHA, etc.
  Command-line options take precedence over environment variables.

Example:
  spel ci-assemble \\
    --site-dir=gh-pages-site \\
    --run=123 \\
    --commit-sha=abc123def \\
    --commit-msg=\"feat: add feature\" \\
    --report-url=https://example.github.io/repo/123/ \\
    --test-passed=100 \\
    --test-failed=2")

(defn- parse-bool [s]
  (when s
    (contains? #{"true" "1" "yes"} (str/lower-case (str s)))))

(defn parse-args
  "Parse CLI arguments into options map."
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          (or (= arg "--help") (= arg "-h"))
          (assoc opts :help true)

          (str/starts-with? arg "--site-dir=")
          (recur (rest args) (assoc opts :site-dir (subs arg 11)))

          (str/starts-with? arg "--run=")
          (recur (rest args) (assoc opts :run-number (subs arg 6)))

          (str/starts-with? arg "--commit-sha=")
          (recur (rest args) (assoc opts :commit-sha (subs arg 13)))

          (str/starts-with? arg "--commit-msg=")
          (recur (rest args) (assoc opts :commit-msg (subs arg 13)))

          (str/starts-with? arg "--commit-author=")
          (recur (rest args) (assoc opts :commit-author (subs arg 16)))

          (str/starts-with? arg "--commit-ts=")
          (recur (rest args) (assoc opts :commit-ts (subs arg 12)))

          (str/starts-with? arg "--tests-passed=")
          (recur (rest args) (assoc opts :tests-passed (parse-bool (subs arg 15))))

          (str/starts-with? arg "--repo-url=")
          (recur (rest args) (assoc opts :repo-url (subs arg 11)))

          (str/starts-with? arg "--run-url=")
          (recur (rest args) (assoc opts :run-url (subs arg 10)))

          (str/starts-with? arg "--version=")
          (recur (rest args) (assoc opts :version (subs arg 10)))

          (str/starts-with? arg "--version-badge=")
          (recur (rest args) (assoc opts :version-badge (subs arg 16)))

          (str/starts-with? arg "--test-passed=")
          (recur (rest args) (assoc opts :test-passed (parse-long (subs arg 14))))

          (str/starts-with? arg "--test-failed=")
          (recur (rest args) (assoc opts :test-failed (parse-long (subs arg 14))))

          (str/starts-with? arg "--test-broken=")
          (recur (rest args) (assoc opts :test-broken (parse-long (subs arg 14))))

          (str/starts-with? arg "--test-skipped=")
          (recur (rest args) (assoc opts :test-skipped (parse-long (subs arg 15))))

          (str/starts-with? arg "--history-file=")
          (recur (rest args) (assoc opts :history-file (subs arg 15)))

          (str/starts-with? arg "--report-url=")
          (recur (rest args) (assoc opts :report-url (subs arg 13)))

          (str/starts-with? arg "--logo-file=")
          (recur (rest args) (assoc opts :logo-file (subs arg 12)))

          (str/starts-with? arg "--index-file=")
          (recur (rest args) (assoc opts :index-file (subs arg 13)))

          (str/starts-with? arg "--title=")
          (recur (rest args) (assoc opts :title (subs arg 8)))

          (str/starts-with? arg "--subtitle=")
          (recur (rest args) (assoc opts :subtitle (subs arg 11)))

          :else
          (recur (rest args) opts))))))

(defn -main
  "Main entry point for ci-assemble command."
  [& args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (println cli-help)
      (let [;; Resolve options with env var fallbacks
            site-dir (or (:site-dir opts)
                       (System/getenv "SPEL_CI_SITE_DIR")
                       "gh-pages-site")
            run-number (or (:run-number opts)
                         (System/getenv "RUN_NUMBER")
                         (System/getenv "GITHUB_RUN_NUMBER"))
            commit-sha (or (:commit-sha opts)
                         (System/getenv "COMMIT_SHA")
                         (System/getenv "GITHUB_SHA"))
            commit-msg (or (:commit-msg opts)
                         (System/getenv "COMMIT_MSG"))
            commit-author (or (:commit-author opts)
                            (System/getenv "COMMIT_AUTHOR")
                            (System/getenv "GITHUB_ACTOR"))
            commit-ts (or (:commit-ts opts)
                        (System/getenv "COMMIT_TS"))
            tests-passed (if (contains? opts :tests-passed)
                           (:tests-passed opts)
                           (parse-bool (or (System/getenv "TEST_PASSED")
                                         (System/getenv "TESTS_PASSED"))))
            repo-url (or (:repo-url opts)
                       (System/getenv "REPO_URL"))
            run-url (or (:run-url opts)
                      (System/getenv "RUN_URL"))
            version (or (:version opts)
                      (System/getenv "VERSION"))
            version-badge (or (:version-badge opts)
                            (System/getenv "VERSION_BADGE"))
            tc-passed (or (:test-passed opts)
                        (some-> (System/getenv "TEST_COUNTS_PASSED") parse-long)
                        0)
            tc-failed (or (:test-failed opts)
                        (some-> (System/getenv "TEST_COUNTS_FAILED") parse-long)
                        0)
            tc-broken (or (:test-broken opts)
                        (some-> (System/getenv "TEST_COUNTS_BROKEN") parse-long)
                        0)
            tc-skipped (or (:test-skipped opts)
                         (some-> (System/getenv "TEST_COUNTS_SKIPPED") parse-long)
                         0)
            test-counts {:passed tc-passed
                         :failed tc-failed
                         :broken tc-broken
                         :skipped tc-skipped
                         :total (+ (long tc-passed) (long tc-failed) (long tc-broken) (long tc-skipped))}
            history-file (or (:history-file opts)
                           (System/getenv "ALLURE_HISTORY_FILE")
                           ".allure-history.jsonl")
            report-url (or (:report-url opts)
                         (System/getenv "REPORT_URL"))
            logo-file (or (:logo-file opts)
                        (System/getenv "LOGO_FILE"))
            index-file (or (:index-file opts)
                         (System/getenv "INDEX_FILE"))
            title (or (:title opts)
                    (System/getenv "LANDING_TITLE"))
            subtitle (or (:subtitle opts)
                       (System/getenv "LANDING_SUBTITLE"))]

        (when-not run-number
          (println "Error: --run is required (or set RUN_NUMBER env var)")
          (System/exit 1))

        ;; 1. Patch allure history
        (when report-url
          (patch-allure-history! {:history-file history-file
                                  :report-url report-url
                                  :commit-sha commit-sha
                                  :commit-msg commit-msg
                                  :run-number run-number}))

        ;; 2. Generate builds metadata
        (when (.isDirectory (io/file site-dir))
          (generate-builds-metadata! {:site-dir site-dir
                                      :run-number run-number
                                      :commit-sha commit-sha
                                      :commit-msg commit-msg
                                      :commit-author commit-author
                                      :commit-ts commit-ts
                                      :tests-passed tests-passed
                                      :repo-url repo-url
                                      :run-url run-url
                                      :version version
                                      :version-badge version-badge
                                      :test-counts test-counts}))

        ;; 3. Patch index.html
        (when index-file
          (patch-index-html! {:index-file index-file
                              :logo-file logo-file
                              :title title
                              :subtitle subtitle}))

        (println "CI assembly complete")))))
