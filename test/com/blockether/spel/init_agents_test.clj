(ns com.blockether.spel.init-agents-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.init-agents :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defn- output-paths
  "Extracts output paths from `files-to-create` specs."
  [file-specs]
  (map second file-specs))

(defn- agent-names
  "Extracts non-nil agent names from `files-to-create` specs."
  [file-specs]
  (->> file-specs (map #(nth % 4)) (remove nil?)))

;; =============================================================================
;; 1. Argument Parsing
;; =============================================================================

(defdescribe parse-args-test
  "Unit tests for CLI argument parsing"

  (describe "defaults"
    (it "returns default values when no args given"
      (let [opts (#'sut/parse-args [])]
        (expect (= false (:dry-run opts)))
        (expect (= false (:force opts)))
        (expect (= false (:no-tests opts)))
        (expect (= false (:simplified opts)))
        (expect (= false (:learnings opts)))
        (expect (= "lazytest" (:flavour opts)))
        (expect (nil? (:ns opts)))
        (expect (= "opencode" (:loop opts)))
        (expect (= "test-e2e" (:test-dir opts)))
        (expect (= "test-e2e/specs" (:specs-dir opts))))))

  (describe "boolean flags"
    (it "parses --dry-run"
      (expect (= true (:dry-run (#'sut/parse-args ["--dry-run"])))))

    (it "parses --force"
      (expect (= true (:force (#'sut/parse-args ["--force"])))))

    (it "parses --no-tests"
      (expect (= true (:no-tests (#'sut/parse-args ["--no-tests"])))))

    (it "parses --learnings"
      (expect (= true (:learnings (#'sut/parse-args ["--learnings"])))))

    (it "parses --simplified"
      (expect (= true (:simplified (#'sut/parse-args ["--simplified"])))))

    (it "parses --help"
      (expect (= true (:help (#'sut/parse-args ["--help"])))))

    (it "parses -h as help"
      (expect (= true (:help (#'sut/parse-args ["-h"]))))))

  (describe "--ns"
    (it "parses --ns with space-separated value"
      (expect (= "my-app" (:ns (#'sut/parse-args ["--ns" "my-app"])))))

    (it "parses --ns= syntax"
      (expect (= "my-app" (:ns (#'sut/parse-args ["--ns=my-app"]))))))

  (describe "--loop"
    (it "parses --loop with space-separated value"
      (expect (= "claude" (:loop (#'sut/parse-args ["--loop" "claude"])))))

    (it "parses --loop= syntax"
      (expect (= "claude" (:loop (#'sut/parse-args ["--loop=claude"]))))))

  (describe "--flavour"
    (it "parses --flavour with space-separated value"
      (expect (= "clojure-test" (:flavour (#'sut/parse-args ["--flavour" "clojure-test"])))))

    (it "parses --flavour= syntax"
      (expect (= "clojure-test" (:flavour (#'sut/parse-args ["--flavour=clojure-test"]))))))

  (describe "--test-dir and --specs-dir"
    (it "parses --test-dir with space-separated value"
      (expect (= "test/e2e" (:test-dir (#'sut/parse-args ["--test-dir" "test/e2e"])))))

    (it "parses --test-dir= syntax"
      (expect (= "test/e2e" (:test-dir (#'sut/parse-args ["--test-dir=test/e2e"])))))

    (it "parses --specs-dir with space-separated value"
      (expect (= "specs" (:specs-dir (#'sut/parse-args ["--specs-dir" "specs"])))))

    (it "parses --specs-dir= syntax"
      (expect (= "specs" (:specs-dir (#'sut/parse-args ["--specs-dir=specs"]))))))

  (describe "--only"
    (it "parses --only with space-separated value"
      (expect (= #{:test} (:only (#'sut/parse-args ["--only" "test"])))))

    (it "parses --only= syntax"
      (expect (= #{:test} (:only (#'sut/parse-args ["--only=test"])))))

    (it "parses comma-separated --only values"
      (expect (= #{:test :automation} (:only (#'sut/parse-args ["--only" "test,automation"])))))

    (it "parses comma-separated --only= values"
      (expect (= #{:test :bugfind} (:only (#'sut/parse-args ["--only=test,bugfind"]))))))

  (describe "combined flags"
    (it "parses multiple flags together"
      (let [opts (#'sut/parse-args ["--ns" "my-app" "--loop=claude" "--force" "--dry-run" "--learnings"])]
        (expect (= "my-app" (:ns opts)))
        (expect (= "claude" (:loop opts)))
        (expect (= true (:force opts)))
        (expect (= true (:dry-run opts)))
        (expect (= true (:learnings opts)))))

    (it "ignores unknown args"
      (let [opts (#'sut/parse-args ["--unknown" "--ns" "my-app"])]
        (expect (= "my-app" (:ns opts)))))))

;; =============================================================================
;; 2. --only Value Parsing
;; =============================================================================

(defdescribe parse-only-value-test
  "Unit tests for --only value parsing"

  (it "parses single value"
    (expect (= #{:test} (#'sut/parse-only-value "test"))))

  (it "parses comma-separated values"
    (expect (= #{:test :automation} (#'sut/parse-only-value "test,automation"))))

  (it "trims whitespace around values"
    (expect (= #{:test :visual} (#'sut/parse-only-value "test , visual"))))

  (it "ignores empty segments from trailing comma"
    (expect (= #{:test} (#'sut/parse-only-value "test,"))))

  (it "handles multiple groups"
    (expect (= #{:test :bugfind :discovery}
              (#'sut/parse-only-value "test,bugfind,discovery")))))

;; =============================================================================
;; 3. Namespace Handling
;; =============================================================================

(defdescribe ns-path-test
  "Unit tests for namespace-to-path conversion"

  (describe "ns->path"
    (it "converts dotted namespace to slash-separated path"
      (expect (= "test-e2e/my_app/e2e/seed_test.clj"
                (#'sut/ns->path "test-e2e" "my-app.e2e.seed-test"))))

    (it "converts hyphens to underscores in path segments"
      (expect (= "test/my_cool_app/core.clj"
                (#'sut/ns->path "test" "my-cool-app.core"))))

    (it "handles single-segment namespace"
      (expect (= "test/myapp.clj"
                (#'sut/ns->path "test" "myapp"))))

    (it "handles deeply nested namespace"
      (expect (= "src/com/blockether/spel/e2e/login_test.clj"
                (#'sut/ns->path "src" "com.blockether.spel.e2e.login-test"))))))

(defdescribe seed-template-resource-test
  "Unit tests for seed template resource selection"

  (it "returns lazytest template for lazytest flavour"
    (expect (= "seed_test.clj.template"
              (#'sut/seed-template-resource "lazytest"))))

  (it "returns clojure-test template for clojure-test flavour"
    (expect (= "seed_test_ct.clj.template"
              (#'sut/seed-template-resource "clojure-test")))))

;; =============================================================================
;; 4. Frontmatter Extraction & Transformation
;; =============================================================================

(defdescribe extract-frontmatter-test
  "Unit tests for frontmatter parsing"

  (describe "extract-frontmatter"
    (it "extracts frontmatter and body from valid content"
      (let [[fm body] (#'sut/extract-frontmatter "---\nname: test\ndescription: \"hello\"\n---\nBody content here")]
        (expect (= "name: test\ndescription: \"hello\"" fm))
        (expect (= "Body content here" body))))

    (it "returns nil when no frontmatter present"
      (expect (nil? (#'sut/extract-frontmatter "No frontmatter here"))))

    (it "returns nil when content doesn't start with ---"
      (expect (nil? (#'sut/extract-frontmatter "some text\n---\nfoo: bar\n---\nbody"))))

    (it "returns nil when closing --- is missing"
      (expect (nil? (#'sut/extract-frontmatter "---\nfoo: bar\nno closing delimiter"))))

    (it "handles empty body after frontmatter"
      (let [[fm body] (#'sut/extract-frontmatter "---\nkey: val\n---\n")]
        (expect (= "key: val" fm))
        (expect (= "" body)))))

  (describe "extract-fm-field"
    (it "extracts a top-level field"
      (expect (= "test-agent" (#'sut/extract-fm-field "name: test-agent\ndescription: hello" "name"))))

    (it "extracts a quoted field value"
      (expect (= "\"A cool description\"" (#'sut/extract-fm-field "description: \"A cool description\"" "description"))))

    (it "returns nil for missing field"
      (expect (nil? (#'sut/extract-fm-field "name: test" "missing"))))

    (it "does not match indented (nested) fields"
      (expect (nil? (#'sut/extract-fm-field "parent:\n  name: nested" "name"))))

    (it "trims whitespace from value"
      (expect (= "trimmed" (#'sut/extract-fm-field "key:   trimmed  " "key")))))

  (describe "strip-matching-quotes"
    (it "strips double quotes"
      (expect (= "hello" (#'sut/strip-matching-quotes "\"hello\""))))

    (it "strips single quotes"
      (expect (= "hello" (#'sut/strip-matching-quotes "'hello'"))))

    (it "does not strip mismatched quotes"
      (expect (= "\"hello'" (#'sut/strip-matching-quotes "\"hello'"))))

    (it "returns non-string input as-is"
      (expect (nil? (#'sut/strip-matching-quotes nil))))

    (it "returns short strings as-is"
      (expect (= "a" (#'sut/strip-matching-quotes "a"))))

    (it "returns unquoted strings as-is"
      (expect (= "hello" (#'sut/strip-matching-quotes "hello"))))))

;; =============================================================================
;; 5. Agent Reference Transformation
;; =============================================================================

(defdescribe transform-agent-references-test
  "Unit tests for agent reference replacement"

  (it "preserves @agent-name syntax for opencode target"
    (let [content "Use @spel-orchestrator to start"
          result (#'sut/transform-agent-references content "opencode")]
      (expect (= "Use @spel-orchestrator to start" result))))

  (it "replaces @agent-name for claude target"
    (let [content "Use @spel-orchestrator to start"
          result (#'sut/transform-agent-references content "claude")]
      (expect (= "Use @spel-orchestrator to start" result))))

  (it "replaces multiple agent references in one string"
    (let [content "Start with @spel-orchestrator then use @spel-test-planner and @spel-bug-hunter"
          result (#'sut/transform-agent-references content "opencode")]
      (expect (str/includes? result "@spel-orchestrator"))
      (expect (str/includes? result "@spel-test-planner"))
      (expect (str/includes? result "@spel-bug-hunter"))))

  (it "does not modify text without agent references"
    (let [content "No agent refs here, just plain text"
          result (#'sut/transform-agent-references content "opencode")]
      (expect (= content result)))))

;; =============================================================================
;; 6. Skill Instruction Replacement
;; =============================================================================

(defdescribe replace-skill-instruction-test
  "Unit tests for skill instruction replacement"

  (it "replaces short skill loading instruction"
    (let [result (#'sut/replace-skill-instruction
                  "Load the `spel` skill before any action."
                  ".claude/docs/spel")]
      (expect (= "Read `.claude/docs/spel/SKILL.md` before any action." result))))

  (it "replaces long skill loading instruction"
    (let [body "You MUST load the `spel` skill before performing any action. This skill contains the complete API reference for browser automation, assertions, locators, and test fixtures. Do not proceed without loading it first."
          result (#'sut/replace-skill-instruction body ".claude/docs/spel")]
      (expect (str/includes? result "MUST read the file `.claude/docs/spel/SKILL.md`"))
      (expect (not (str/includes? result "load the `spel` skill")))))

  (it "passes through content without skill instructions"
    (let [body "No skill instructions here"
          result (#'sut/replace-skill-instruction body ".claude/docs/spel")]
      (expect (= body result)))))

;; =============================================================================
;; 7. Claude Frontmatter Transformation
;; =============================================================================

(defdescribe transform-for-claude-test
  "Unit tests for OpenCode → Claude frontmatter transformation"

  (it "transforms frontmatter with description and default color"
    (let [content "---\ndescription: \"A test agent\"\n---\nBody text"
          result (#'sut/transform-for-claude content "spel-test-planner" ".claude/docs/spel")]
      (expect (str/includes? result "name: spel-test-planner"))
      (expect (str/includes? result "description: \"A test agent\""))
      (expect (str/includes? result "tools: Bash, Read, Write, Edit, Glob, Grep"))
      (expect (str/includes? result "color:"))
      (expect (str/includes? result "Body text"))))

  (it "preserves custom color from frontmatter"
    (let [content "---\ndescription: \"Agent\"\ncolor: \"#FF0000\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel-agent" ".claude/docs/spel")]
      (expect (str/includes? result "color: \"#FF0000\""))))

  (it "uses default color when none specified"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel-agent" ".claude/docs/spel")]
      (expect (str/includes? result "color: \"#22C55E\""))))

  (it "returns content unchanged when no frontmatter"
    (let [content "No frontmatter here"
          result (#'sut/transform-for-claude content "spel-agent" ".claude/docs/spel")]
      (expect (= content result))))

  (it "replaces skill loading instruction in body"
    (let [content "---\ndescription: \"Agent\"\n---\nLoad the `spel` skill before any action."
          result (#'sut/transform-for-claude content "spel-agent" ".claude/docs/spel")]
      (expect (str/includes? result "Read `.claude/docs/spel/SKILL.md` before any action.")))))

;; =============================================================================
;; 8. Agent Template Transformation Dispatch
;; =============================================================================

(defdescribe transform-agent-template-test
  "Unit tests for agent template transformation dispatch"

  (it "returns content unchanged for opencode target"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"]
      (expect (= content (#'sut/transform-agent-template content "opencode" "spel-test-planner")))))

  (it "transforms content for claude target"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"
          result (#'sut/transform-agent-template content "claude" "spel-test-planner")]
      (expect (str/includes? result "name: spel-test-planner"))
      (expect (not= content result))))

  (it "returns content unchanged when agent-name is nil"
    (let [content "---\ndescription: \"Workflow\"\n---\nBody"]
      (expect (= content (#'sut/transform-agent-template content "claude" nil))))))

;; =============================================================================
;; 9. Orchestrator Detection
;; =============================================================================

(defdescribe orchestrator-agent-test
  "Unit tests for orchestrator agent detection"

  (it "detects spel-orchestrator"
    (expect (true? (#'sut/orchestrator-agent? "spel-orchestrator"))))

  (it "rejects spel-test-planner"
    (expect (false? (#'sut/orchestrator-agent? "spel-test-planner"))))

  (it "rejects spel-bug-hunter"
    (expect (false? (#'sut/orchestrator-agent? "spel-bug-hunter"))))

  (it "rejects nil"
    (expect (false? (#'sut/orchestrator-agent? nil)))))

;; =============================================================================
;; 10. Template Processing
;; =============================================================================

(defdescribe process-template-test
  "Unit tests for template placeholder replacement"

  (it "replaces {{ns}} placeholder"
    (let [result (#'sut/process-template "namespace: {{ns}}" "my-app" "lazytest")]
      (expect (= "namespace: my-app" result))))

  (it "replaces {{version}} placeholder"
    (let [result (#'sut/process-template "version: {{version}}" "my-app" "lazytest")]
      (expect (str/starts-with? result "version: 0."))
      (expect (not (str/includes? result "{{version}}")))))

  (it "replaces multiple placeholders in one template"
    (let [result (#'sut/process-template "ns={{ns}} v={{version}}" "demo" "lazytest")]
      (expect (str/starts-with? result "ns=demo v=0."))
      (expect (not (str/includes? result "{{"))))))

;; =============================================================================
;; 11. File Selection Logic
;; =============================================================================

(defdescribe files-to-create-test
  "Unit tests for init-agents scaffold selection"

  (describe "learnings scaffolding"
    (it "does not scaffold LEARNINGS.md when learnings are enabled"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil true))]
        (expect (not-any? #(= "LEARNINGS.md" %) paths))))

    (it "still scaffolds skill files when learnings are enabled"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil true))]
        (expect (some #(= ".opencode/skills/spel/SKILL.md" %) paths)))))

  (describe "all agents (no --only)"
    (it "includes SKILL.md"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil false))]
        (expect (some #(= ".opencode/skills/spel/SKILL.md" %) paths))))

    (it "includes all 8 agent templates"
      (let [names (agent-names (#'sut/files-to-create "opencode" "lazytest" nil false))]
        (expect (= 8 (count names)))))

    (it "includes test agents"
      (let [names (set (agent-names (#'sut/files-to-create "opencode" "lazytest" nil false)))]
        (expect (contains? names "spel-test-planner"))
        (expect (contains? names "spel-test-writer"))))

    (it "includes orchestrator agent"
      (let [names (set (agent-names (#'sut/files-to-create "opencode" "lazytest" nil false)))]
        (expect (contains? names "spel-orchestrator"))))

    (it "includes all workflow prompts"
      (let [paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest" nil false)))]
        (expect (some #(str/includes? % "spel-test-workflow") paths))
        (expect (some #(str/includes? % "spel-visual-workflow") paths))
        (expect (some #(str/includes? % "spel-automation-workflow") paths))
        (expect (some #(str/includes? % "spel-bugfind-workflow") paths))
        (expect (some #(str/includes? % "spel-discovery-workflow") paths)))))

  (describe "--only test"
    (let [resolved #{:test}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))
          paths (set (output-paths specs))]

      (it "includes test agents"
        (expect (contains? names "spel-test-planner"))
        (expect (contains? names "spel-test-writer")))

      (it "excludes non-test agents"
        (expect (not (contains? names "spel-explorer")))
        (expect (not (contains? names "spel-orchestrator")))
        (expect (not (contains? names "spel-bug-hunter"))))

      (it "includes test workflow"
        (expect (some #(str/includes? % "spel-test-workflow") paths)))

      (it "excludes automation workflow"
        (expect (not (some #(str/includes? % "spel-automation-workflow") paths))))

      (it "always includes SKILL.md"
        (expect (some #(str/includes? % "SKILL.md") paths)))

      (it "includes core refs"
        (expect (some #(str/includes? % "FULL_API.md") paths))
        (expect (some #(str/includes? % "CONSTANTS.md") paths)))))

  (describe "--only automation"
    (let [resolved #{:explorer :automator}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))]

      (it "includes automation agents"
        (expect (contains? names "spel-explorer"))
        (expect (contains? names "spel-automator")))

      (it "excludes test agents"
        (expect (not (contains? names "spel-test-planner"))))))

  (describe "--only bugfind"
    (let [resolved #{:bug-hunter}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))
          paths (set (output-paths specs))]

      (it "includes bugfind agents"
        (expect (contains? names "spel-bug-hunter")))

      (it "includes bugfind workflow"
        (expect (some #(str/includes? % "spel-bugfind-workflow") paths)))

      (it "excludes test and automation agents"
        (expect (not (contains? names "spel-test-planner")))
        (expect (not (contains? names "spel-explorer"))))))

  (describe "--only discovery"
    (let [resolved #{:product-analyst}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))
          paths (set (output-paths specs))]

      (it "includes product-analyst"
        (expect (contains? names "spel-product-analyst")))

      (it "includes discovery workflow"
        (expect (some #(str/includes? % "spel-discovery-workflow") paths)))

      (it "excludes non-discovery agents"
        (expect (not (contains? names "spel-orchestrator")))
        (expect (not (contains? names "spel-test-planner"))))))

  (describe "--only orchestrator"
    (let [resolved #{:orchestrator}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))]

      (it "includes orchestrator"
        (expect (contains? names "spel-orchestrator")))

      (it "excludes non-orchestrator agents"
        (expect (not (contains? names "spel-test-planner")))
        (expect (not (contains? names "spel-bug-hunter"))))))

  (describe "--only core"
    (let [resolved #{:orchestrator :test :explorer
                     :bug-hunter :product-analyst}
          specs (#'sut/files-to-create "opencode" "lazytest" resolved false)
          names (set (agent-names specs))]

      (it "includes core agents"
        (expect (contains? names "spel-orchestrator"))
        (expect (contains? names "spel-test-planner"))
        (expect (contains? names "spel-test-writer"))
        (expect (contains? names "spel-explorer"))
        (expect (contains? names "spel-bug-hunter"))
        (expect (contains? names "spel-product-analyst")))

      (it "excludes non-core agents"
        (expect (not (contains? names "spel-automator")))
        (expect (not (contains? names "spel-presenter"))))))

  (describe "workflow filtering"
    (it "includes visual workflow when presenter is selected (visual-qa merged into bug-hunter)"
      (let [resolved #{:presenter}
            paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest" resolved false)))]
        (expect (some #(str/includes? % "spel-visual-workflow") paths))))

    (it "excludes automation workflow when only explorer is selected"
      (let [resolved #{:explorer}
            paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest" resolved false)))]
        (expect (not (some #(str/includes? % "spel-automation-workflow") paths)))))

    (it "includes automation workflow when all automation agents are selected"
      (let [resolved #{:explorer :automator}
            paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest" resolved false)))]
        (expect (some #(str/includes? % "spel-automation-workflow") paths))))

    (it "includes bugfind workflow when bug-hunter is selected"
      (let [resolved #{:bug-hunter}
            paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest" resolved false)))]
        (expect (some #(str/includes? % "spel-bugfind-workflow") paths)))))

  (describe "ref deduplication"
    (it "does not duplicate shared ref files across agent groups"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest" nil false))
            ref-paths (filter #(str/includes? % "/refs/") paths)]
        (expect (= (count ref-paths) (count (distinct ref-paths)))))))

  (describe "claude loop target"
    (it "uses .claude directory paths"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest" nil false))]
        (expect (some #(str/starts-with? % ".claude/") paths))
        (expect (not (some #(str/starts-with? % ".opencode/") paths)))))

    (it "includes SKILL.md under .claude/docs/spel"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest" nil false))]
        (expect (some #(= ".claude/docs/spel/SKILL.md" %) paths)))))

  (describe "clojure-test flavour"
    (it "uses clojure-test writer template"
      (let [specs (#'sut/files-to-create "opencode" "clojure-test" nil false)
            resource-paths (map first specs)]
        (expect (some #(= "agents/spel-test-writer-ct.md" %) resource-paths))))

    (it "uses clojure-test testing conventions"
      (let [specs (#'sut/files-to-create "opencode" "clojure-test" nil false)
            resource-paths (map first specs)]
        (expect (some #(str/includes? % "clojure-test/testing-conventions") resource-paths))))))

;; =============================================================================
;; 12. Learnings Contract Injection
;; =============================================================================

(defdescribe append-learnings-contract-test
  "Unit tests for learnings contract injection"

  (describe "specialist agents"
    (it "adds lazy creation instructions"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (str/includes? content "If `LEARNINGS.md` does not exist yet, create it first"))
        (expect (str/includes? content "Do NOT defer learnings until the end of the whole run."))))

    (it "includes agent name in section header"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (str/includes? content "## Agent: spel-bug-hunter"))))

    (it "includes exact reproduction template"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (str/includes? content "### Exact Reproductions"))
        (expect (str/includes? content "#### ISSUE-<id>"))))

    (it "includes root cause section"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (str/includes? content "### Root Cause and Corrective Action"))))

    (it "does not include orchestrator synthesis"
      (let [content (#'sut/append-learnings-contract "base" "spel-bug-hunter")]
        (expect (not (str/includes? content "### Orchestrator Synthesis"))))))

  (describe "orchestrator agents"
    (it "adds incremental synthesis instructions"
      (let [content (#'sut/append-learnings-contract "base" "spel-orchestrator")]
        (expect (str/includes? content "### Orchestrator Synthesis (required)"))
        (expect (str/includes? content "Append/update learnings after each completed pipeline gate"))))

    (it "requires corrective backlog"
      (let [content (#'sut/append-learnings-contract "base" "spel-orchestrator")]
        (expect (str/includes? content "## Corrective Backlog"))))

    (it "includes base contract plus orchestrator section"
      (let [content (#'sut/append-learnings-contract "base" "spel-orchestrator")]
        ;; base contract
        (expect (str/includes? content "## Meta Learnings"))
        ;; orchestrator addition
        (expect (str/includes? content "### Orchestrator Synthesis (required)")))))

  (describe "preserves original content"
    (it "prepends to existing content"
      (let [content (#'sut/append-learnings-contract "Original body text" "spel-explorer")]
        (expect (str/starts-with? content "Original body text"))))))
