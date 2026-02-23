(ns com.blockether.spel.allure-verify-cli
  "CLI handlers for `spel verify-pr` and `spel verify-results` subcommands.

   verify-pr:      Downloads CI artifact and runs verification
   verify-results: Verifies a local allure-results directory"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure-verify :as av]))

;; =============================================================================
;; Subprocess Helpers
;; =============================================================================

(defn- run-process!
  "Run a process. Returns {:exit int :out str}."
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

;; =============================================================================
;; GitHub CLI Wrappers
;; =============================================================================

(defn- find-pr-branch
  "Find the head branch for a PR via gh CLI."
  ^String [pr-number repo]
  (let [{:keys [exit out]} (run-process!
                             ["gh" "pr" "view" (str pr-number)
                              "--repo" repo
                              "--json" "headRefName"
                              "--jq" ".headRefName"])]
    (when (zero? (long exit))
      (str/trim out))))

(defn- find-allure-run
  "Find latest successful Allure CI run for a branch."
  ^String [^String branch ^String repo]
  (let [{:keys [exit out]} (run-process!
                             ["gh" "run" "list" "--repo" repo
                              "--workflow" "allure.yml"
                              "--branch" branch
                              "--limit" "10"
                              "--json" "databaseId,conclusion"
                              "--jq" "first(.[] | select(.conclusion == \"success\")) | .databaseId"])]
    (when (and (zero? (long exit)) (not (str/blank? out)) (not= "null" (str/trim out)))
      (str/trim out))))

(defn- download-artifact!
  "Download a CI artifact via gh CLI. Returns true on success."
  [run-id ^String artifact-name ^String dest-dir ^String repo]
  (let [{:keys [exit]} (run-process!
                         ["gh" "run" "download" (str run-id)
                          "--repo" repo
                          "--name" artifact-name
                          "--dir" dest-dir])]
    (zero? (long exit))))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-verify-args
  "Parse verify subcommand arguments."
  [args]
  (loop [remaining args
         opts      {}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (or (= "--help" arg) (= "-h" arg))
          (assoc opts :help true)

          (= "--repo" arg)
          (recur (drop 2 remaining) (assoc opts :repo (second remaining)))

          (str/starts-with? arg "--repo=")
          (recur (rest remaining) (assoc opts :repo (subs arg 7)))

          (= "--no-pdf" arg)
          (recur (rest remaining) (assoc opts :generate-pdf false))

          (= "--no-comment" arg)
          (recur (rest remaining) (assoc opts :post-comment false))

          (= "--pr" arg)
          (recur (drop 2 remaining) (assoc opts :pr-number (second remaining)))

          (str/starts-with? arg "--pr=")
          (recur (rest remaining) (assoc opts :pr-number (subs arg 5)))

          (str/starts-with? arg "--")
          (recur (rest remaining) opts)

          (nil? (:positional opts))
          (recur (rest remaining) (assoc opts :positional arg))

          :else
          (recur (rest remaining) opts))))))

;; =============================================================================
;; Help Text
;; =============================================================================

(defn verify-pr-help
  "Help text for verify-pr subcommand."
  ^String []
  (str/join \newline
    ["verify-pr - Verify Allure report for a GitHub PR"
     ""
     "Usage:"
     "  spel verify-pr <PR_NUMBER> [options]"
     ""
     "Finds latest successful Allure CI run for the PR branch,"
     "downloads the allure-report artifact, and runs verification:"
     "parse results, generate HTML pages, take screenshots, produce PDF."
     ""
     "Options:"
     "  --repo OWNER/REPO    GitHub repo (default: Blockether/spel)"
     "  --no-pdf             Skip PDF generation"
     "  --no-comment         Skip posting PR comment"
     "  --help, -h           Show this help"
     ""
     "Examples:"
     "  spel verify-pr 42"
     "  spel verify-pr 42 --no-comment"
     "  spel verify-pr 42 --repo myorg/myrepo"]))

(defn verify-results-help
  "Help text for verify-results subcommand."
  ^String []
  (str/join \newline
    ["verify-results - Verify local allure results directory"
     ""
     "Usage:"
     "  spel verify-results <dir> [options]"
     ""
     "Directly verifies a local allure-results/ or data/test-results/ directory:"
     "parse results, generate HTML pages, take screenshots, produce PDF."
     ""
     "Options:"
     "  --pr N               PR number for labeling"
     "  --repo OWNER/REPO    GitHub repo (default: Blockether/spel)"
     "  --no-pdf             Skip PDF generation"
     "  --no-comment         Skip posting PR comment"
     "  --help, -h           Show this help"
     ""
     "Examples:"
     "  spel verify-results allure-results/"
     "  spel verify-results data/test-results/ --pr 42"]))

;; =============================================================================
;; Subcommand Handlers
;; =============================================================================

(defn- print-summary
  "Print verification summary."
  [result]
  (println)
  (println "=== Verification Complete ===")
  (println (str "Total: " (:total result) " | Passed: " (:passed result)
             " | Failed: " (:failed result)))
  (println (str "CT: " (:ct-total result) " | Lazytest: " (:lt-total result)))
  (when-let [screenshots (seq (:screenshots result))]
    (println (str "Screenshots: " (str/join "  " screenshots))))
  (when (:pdf result)
    (println (str "PDF: " (:pdf result)))))

(defn run-verify-pr!
  "Run the verify-pr subcommand."
  [args]
  (let [{:keys [help positional] :as opts} (parse-verify-args args)
        pr-number positional]
    (cond
      help
      (println (verify-pr-help))

      (nil? pr-number)
      (do (println "Error: PR number required")
          (println "Usage: spel verify-pr <PR_NUMBER> [options]")
          (System/exit 1))

      :else
      (let [repo   (or (:repo opts) "Blockether/spel")
            branch (find-pr-branch pr-number repo)]
        (when-not branch
          (println (str "Error: Could not find branch for PR #" pr-number))
          (System/exit 1))
        (println (str "Branch: " branch))
        (let [run-id (find-allure-run branch repo)]
          (when-not run-id
            (println (str "Error: No successful Allure CI run found for branch '" branch "'"))
            (System/exit 1))
          (println (str "CI Run ID: " run-id))
          (let [artifact-name (str "allure-report-pr-" pr-number)
                dest-dir      (str "/tmp/allure-verify-pr" pr-number)]
            (println (str "Downloading artifact '" artifact-name "'..."))
            (when-not (download-artifact! run-id artifact-name dest-dir repo)
              (println (str "Error: Failed to download artifact '" artifact-name "'"))
              (System/exit 1))
            (println "Artifact downloaded.")
            (let [results-dir (str dest-dir "/data/test-results")
                  result      (av/verify-results! results-dir
                                {:out-dir      "/tmp/allure-verify/"
                                 :pr-number    pr-number
                                 :repo         repo
                                 :post-comment (not (false? (:post-comment opts)))
                                 :generate-pdf (not (false? (:generate-pdf opts)))})]
              (print-summary result))))))))

(defn run-verify-results!
  "Run the verify-results subcommand."
  [args]
  (let [{:keys [help positional] :as opts} (parse-verify-args args)
        dir positional]
    (cond
      help
      (println (verify-results-help))

      (nil? dir)
      (do (println "Error: results directory required")
          (println "Usage: spel verify-results <dir> [options]")
          (System/exit 1))

      :else
      (let [result (av/verify-results! dir
                     (cond-> {:out-dir      "/tmp/allure-verify/"
                              :repo         (or (:repo opts) "Blockether/spel")
                              :post-comment (not (false? (:post-comment opts)))
                              :generate-pdf (not (false? (:generate-pdf opts)))}
                       (:pr-number opts)
                       (assoc :pr-number (:pr-number opts))))]
        (print-summary result)))))
