(ns com.blockether.spel.workflow-test
  "GitHub Actions workflow hygiene — the shell a job actually runs.

   A step's script must be a block scalar (`run: |`). Without the `|`, YAML
   folds the following lines into ONE line joined by spaces, so a multi-line
   script reaches bash as `if …; then echo … echo … else … fi` and dies with a
   syntax error before the job does any work. Nothing in the YAML is invalid,
   so the mistake is invisible until the job runs on a schedule nobody watches.

   A bare `run:` is legal in exactly one place: the `defaults: run: shell: …`
   mapping, which is a key/value map rather than a script."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(def ^:private workflow-dir
  (io/file ".github" "workflows"))

(defn- workflow-files
  []
  (->> (.listFiles ^java.io.File workflow-dir)
    (filter #(str/ends-with? (.getName ^java.io.File %) ".yml"))
    (sort-by #(.getName ^java.io.File %))))

(defn- folded-run-lines
  "Line numbers in `file` where a `run:` key carries neither an inline command
   nor a block indicator, and is therefore a folded script."
  [file]
  (let [lines (str/split-lines (slurp file))]
    (->> (map vector (rest (range)) lines)
      (keep (fn [[n line]]
              (when (re-matches #"\s*run:\s*" line)
                (let [prev (->> (take (dec ^long n) lines)
                             (remove str/blank?)
                             last)]
                  (when-not (= "defaults:" (str/trim (or prev "")))
                    n)))))
      vec)))

;; Regression, security-audit.yml: the "Check NVD API key is provisioned" step
;; was a bare `run:` above an if/else, so YAML folded the guard onto one line
;; and every scheduled Security audit run died with
;; `syntax error: unexpected end of file from 'if' command` — the NVD scan was
;; reported as broken tooling whether or not the API key secret existed.
(defdescribe workflow-run-scalars-test
  "Every workflow step script survives YAML folding"

  (describe "the workflow directory"

    (it "is where the tests run from"
      (expect (true? (.isDirectory ^java.io.File workflow-dir)))
      (expect (seq (workflow-files)))))

  (describe "run: is a block scalar"

    (it "no step folds its script onto a single line"
      (expect (= {}
                (into {}
                  (keep (fn [f]
                          (when-let [ls (seq (folded-run-lines f))]
                            [(.getName ^java.io.File f) (vec ls)])))
                  (workflow-files)))))))
