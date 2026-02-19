(ns com.blockether.spel.test-runner
  "Combined runner for Lazytest + clojure.test with unified Allure report.

   Phase 1: Runs clojure.test namespaces with the CT Allure reporter.
   Phase 2: Runs Lazytest namespaces with the Lazytest Allure reporter
            in append mode (preserves Phase 1 results).

   The Lazytest reporter generates the final HTML report containing
   results from both frameworks.

   Usage:
     clojure -M:test-all"
  (:require
   [com.blockether.spel.allure-ct-reporter :as ct-reporter]))

(def ct-namespaces
  "clojure.test namespaces to run."
  '[com.blockether.spel.ct.smoke-test
    com.blockether.spel.ct.markdown-test
    com.blockether.spel.ct.data-test])

(defn -main [& args]
  ;; Phase 1: Run clojure.test tests (writes to allure-results/, no HTML yet)
  (println "=== Phase 1: Running clojure.test tests ===")
  (let [ct-results (ct-reporter/run-ct-tests!
                     {:namespaces ct-namespaces
                      :clean?     true
                      :report?    false})]
    (println (str "  clojure.test: " (:test ct-results) " tests, "
               (:pass ct-results) " passed, "
               (:fail ct-results) " failed, "
               (:error ct-results) " errors"))
    (println))

  ;; Phase 2: Run Lazytest tests in append mode
  ;; The Lazytest Allure reporter will:
  ;;   - NOT clean allure-results/ (append mode)
  ;;   - Write its own test results alongside the CT results
  ;;   - Generate the unified HTML report with ALL results
  (println "=== Phase 2: Running Lazytest tests ===")
  (System/setProperty "lazytest.allure.append" "true")
  (let [lt-args (into ["--output" "nested"
                       "--output" "com.blockether.spel.allure-reporter/allure"]
                  args)]
    (require 'lazytest.main)
    (apply (resolve 'lazytest.main/-main) lt-args)))
