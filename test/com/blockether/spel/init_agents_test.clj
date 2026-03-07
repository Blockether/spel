(ns com.blockether.spel.init-agents-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.init-agents :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defn- output-paths
  "Extracts output paths from `files-to-create` specs."
  [file-specs]
  (map second file-specs))

(defdescribe files-to-create-test
  "Unit tests for init-agents scaffold selection"

  (describe "learnings scaffolding"
    (it "does not scaffold LEARNINGS.md when learnings are enabled"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil true))]
        (expect (not-any? #(= "LEARNINGS.md" %) paths))))

    (it "still scaffolds skill files when learnings are enabled"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil true))]
        (expect (some #(= ".opencode/skills/spel/SKILL.md" %) paths))))))

(defdescribe append-learnings-contract-test
  "Unit tests for learnings contract injection"

  (describe "append-learnings-contract"
    (it "adds lazy creation instructions for specialist agents"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (str/includes? content "If `LEARNINGS.md` does not exist yet, create it first"))
        (expect (str/includes? content "Do NOT defer learnings until the end of the whole run."))))

    (it "adds incremental synthesis instructions for orchestrators"
      (let [content (#'sut/append-learnings-contract "base" "spel-orchestrator")]
        (expect (str/includes? content "### Orchestrator Synthesis (required)"))
        (expect (str/includes? content "Append/update learnings after each completed pipeline gate"))))))
