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
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

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

;; Native and extension releases share this repository. Extension tags must not
;; truncate the native release notes or the committed changelog.
(defdescribe native-release-tag-selection-test
  "Native release changelogs compare native tags only"

  (it "ignores extension tags in both changelog steps"
    (let [selectors (re-seq #"PREV_TAG=\$\(([^\n]+)\)"
                      (slurp (io/file workflow-dir "release.yml")))
          dir (.toFile (Files/createTempDirectory "spel-release-tags-"
                         (into-array FileAttribute [])))
          run! (fn [& args]
                 (let [{:keys [exit out err]} (apply shell/sh (concat args [:dir dir]))]
                   (assert (zero? exit) err)
                   (str/trim out)))]
      (try
        (expect (= 2 (count selectors)))
        (run! "git" "init" "--quiet")
        (run! "git" "-c" "user.name=Release test" "-c" "user.email=release@example.com"
          "-c" "commit.gpgsign=false" "-c" "core.hooksPath=disabled-hooks"
          "commit" "--quiet" "--allow-empty" "-m" "Release tag fixture")
        (doseq [tag ["v0.9.33" "v0.9.34"]]
          (run! "git" "tag" tag))
        (doseq [[_ command] selectors]
          (expect (= "v0.9.33" (run! "bash" "-c" command))))
        (doseq [tag ["vis-spel/v0.1.0" "vis-spel/v0.1.1" "vis-spel/v0.1.2"]]
          (run! "git" "tag" tag))
        (doseq [[_ command] selectors]
          (expect (= "v0.9.33" (run! "bash" "-c" command))))
        (finally
          (doseq [file (reverse (file-seq dir))]
            (io/delete-file file true)))))))
