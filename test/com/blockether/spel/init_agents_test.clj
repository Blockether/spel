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
        (expect (= "lazytest" (:flavour opts)))
        (expect (nil? (:ns opts)))
        (expect (= "opencode" (:loop opts)))
        (expect (= "test-e2e" (:test-dir opts))))))

  (describe "boolean flags"
    (it "parses --dry-run"
      (expect (= true (:dry-run (#'sut/parse-args ["--dry-run"])))))

    (it "parses --force"
      (expect (= true (:force (#'sut/parse-args ["--force"])))))

    (it "parses --no-tests"
      (expect (= true (:no-tests (#'sut/parse-args ["--no-tests"])))))

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

  (describe "--test-dir"
    (it "parses --test-dir with space-separated value"
      (expect (= "test/e2e" (:test-dir (#'sut/parse-args ["--test-dir" "test/e2e"])))))

    (it "parses --test-dir= syntax"
      (expect (= "test/e2e" (:test-dir (#'sut/parse-args ["--test-dir=test/e2e"]))))))

  (describe "combined flags"
    (it "parses multiple flags together"
      (let [opts (#'sut/parse-args ["--ns" "my-app" "--loop=claude" "--force" "--dry-run"])]
        (expect (= "my-app" (:ns opts)))
        (expect (= "claude" (:loop opts)))
        (expect (= true (:force opts)))
        (expect (= true (:dry-run opts)))))

    (it "ignores unknown args"
      (let [opts (#'sut/parse-args ["--unknown" "--ns" "my-app"])]
        (expect (= "my-app" (:ns opts)))))))

;; =============================================================================
;; 2. Namespace Handling
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
;; 3. Frontmatter Extraction & Transformation
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
;; 4. Skill Instruction Replacement
;; =============================================================================

(defdescribe replace-skill-instruction-test
  "Unit tests for skill instruction replacement"

  (it "replaces short skill loading instruction"
    (let [result (#'sut/replace-skill-instruction
                  "Load the `spel` skill before any action."
                  ".claude/docs/spel")]
      (expect (= "Read `.claude/docs/spel/SKILL.md` before any action." result))))

  (it "passes through content without skill instructions"
    (let [body "No skill instructions here"
          result (#'sut/replace-skill-instruction body ".claude/docs/spel")]
      (expect (= body result)))))

;; =============================================================================
;; 5. Claude Frontmatter Transformation
;; =============================================================================

(defdescribe transform-for-claude-test
  "Unit tests for OpenCode → Claude frontmatter transformation"

  (it "transforms frontmatter with description and default color"
    (let [content "---\ndescription: \"A test agent\"\n---\nBody text"
          result (#'sut/transform-for-claude content "spel" ".claude/docs/spel")]
      (expect (str/includes? result "name: spel"))
      (expect (str/includes? result "description: \"A test agent\""))
      (expect (str/includes? result "tools: Bash, Read, Write, Edit, Glob, Grep"))
      (expect (str/includes? result "color:"))
      (expect (str/includes? result "Body text"))))

  (it "preserves custom color from frontmatter"
    (let [content "---\ndescription: \"Agent\"\ncolor: \"#FF0000\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel" ".claude/docs/spel")]
      (expect (str/includes? result "color: \"#FF0000\""))))

  (it "uses default color when none specified"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel" ".claude/docs/spel")]
      (expect (str/includes? result "color: \"#22C55E\""))))

  (it "returns content unchanged when no frontmatter"
    (let [content "No frontmatter here"
          result (#'sut/transform-for-claude content "spel" ".claude/docs/spel")]
      (expect (= content result))))

  (it "replaces skill loading instruction in body"
    (let [content "---\ndescription: \"Agent\"\n---\nLoad the `spel` skill before any action."
          result (#'sut/transform-for-claude content "spel" ".claude/docs/spel")]
      (expect (str/includes? result "Read `.claude/docs/spel/SKILL.md` before any action.")))))

;; =============================================================================
;; 6. Agent Template Transformation Dispatch
;; =============================================================================

(defdescribe transform-agent-template-test
  "Unit tests for agent template transformation dispatch"

  (it "returns content unchanged for opencode target"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"]
      (expect (= content (#'sut/transform-agent-template content "opencode" "spel")))))

  (it "transforms content for claude target"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"
          result (#'sut/transform-agent-template content "claude" "spel")]
      (expect (str/includes? result "name: spel"))
      (expect (not= content result))))

  (it "returns content unchanged when agent-name is nil"
    (let [content "---\ndescription: \"Workflow\"\n---\nBody"]
      (expect (= content (#'sut/transform-agent-template content "claude" nil))))))

;; =============================================================================
;; 7. Template Processing
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
;; 8. File Selection Logic
;; =============================================================================

(defdescribe files-to-create-test
  "Unit tests for init-agents scaffold selection"

  (describe "single agent scaffolding"
    (it "includes SKILL.md"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest"))]
        (expect (some #(= ".opencode/skills/spel/SKILL.md" %) paths))))

    (it "includes exactly 1 agent template"
      (let [names (agent-names (#'sut/files-to-create "opencode" "lazytest"))]
        (expect (= 1 (count names)))))

    (it "the single agent is named spel"
      (let [names (set (agent-names (#'sut/files-to-create "opencode" "lazytest")))]
        (expect (contains? names "spel"))))

    (it "does not include any old agent names"
      (let [names (set (agent-names (#'sut/files-to-create "opencode" "lazytest")))]
        (expect (not (contains? names "spel-orchestrator")))
        (expect (not (contains? names "spel-test-writer")))
        (expect (not (contains? names "spel-explorer")))
        (expect (not (contains? names "spel-bug-hunter")))))

    (it "does not include any workflow prompts"
      (let [paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest")))]
        (expect (not-any? #(str/includes? % "prompts/") paths)))))

  (describe "reference files"
    (it "includes core reference files"
      (let [paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest")))]
        (expect (some #(str/includes? % "FULL_API.md") paths))
        (expect (some #(str/includes? % "CONSTANTS.md") paths))
        (expect (some #(str/includes? % "COMMON_PROBLEMS.md") paths))))

    (it "includes all reference files"
      (let [paths (set (output-paths (#'sut/files-to-create "opencode" "lazytest")))]
        (expect (some #(str/includes? % "EVAL_GUIDE.md") paths))
        (expect (some #(str/includes? % "BUGFIND_GUIDE.md") paths))
        (expect (some #(str/includes? % "ASSERTIONS_EVENTS.md") paths))))

    (it "does not duplicate reference files"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest"))
            ref-paths (filter #(str/includes? % "/references/") paths)]
        (expect (= (count ref-paths) (count (distinct ref-paths)))))))

  (describe "claude loop target"
    (it "uses .claude directory paths"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest"))]
        (expect (some #(str/starts-with? % ".claude/") paths))
        (expect (not (some #(str/starts-with? % ".opencode/") paths)))))

    (it "includes SKILL.md under .claude/docs/spel"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest"))]
        (expect (some #(= ".claude/docs/spel/SKILL.md" %) paths)))))

  (describe "clojure-test flavour"
    (it "uses clojure-test testing conventions"
      (let [specs (#'sut/files-to-create "opencode" "clojure-test")
            resource-paths (map first specs)]
        (expect (some #(str/includes? % "clojure-test/testing-conventions") resource-paths))))))
