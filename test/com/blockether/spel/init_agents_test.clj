(ns com.blockether.spel.init-agents-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.init-agents :as sut]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defn- output-paths
  "Extracts output paths from `files-to-create` specs."
  [file-specs]
  (map second file-specs))

(defn- agent-names
  "Extracts non-nil agent names from `files-to-create` specs."
  [file-specs]
  (->> file-specs (map #(nth % 4)) (remove nil?)))

(defdescribe eval-guide-contract-test
  "The SCI guide names the functions and language forms the runtime actually exposes."
  (it "documents the registered JSON encoder"
    (let [guide (#'sut/read-template "skills/spel/references/EVAL_GUIDE.md")
          ctx (sci-env/create-sci-ctx)]
      (expect (str/includes? guide "json/write-str"))
      (doseq [sym (distinct (re-seq #"json/[\w-]+" guide))]
        (expect (sci-env/eval-string ctx (str "(boolean (resolve '" sym "))"))))))
  (it "runs the guide's JSON example unchanged"
    (let [guide (#'sut/read-template "skills/spel/references/EVAL_GUIDE.md")
          code (second (re-find #"```clojure\n(\(json/write-str[^\n]+\))\n```" guide))]
      (expect (some? code))
      (when code
        (expect (= "{\"ok\":true}"
                  (sci-env/eval-string (sci-env/create-sci-ctx) code))))))
  (it "does not forbid supported registered requires or local macros"
    (let [guide (#'sut/read-template "skills/spel/references/EVAL_GUIDE.md")
          ctx (sci-env/create-sci-ctx)]
      (expect (= "OK" (sci-env/eval-string ctx
                        "(require '[clojure.string :as s]) (s/upper-case \"ok\")")))
      (expect (= 4 (sci-env/eval-string ctx
                     "(defmacro twice [x] `(+ ~x ~x)) (twice 2)")))
      (expect (not (re-find #"(?m)^- `(?:require|defmacro)`" guide))))))

(defdescribe template-task-boundaries-test
  "Generated references route by task without changing runner or ownership boundaries."
  (it "gives each instructional reference an explicit load condition"
    (let [paths (->> (#'sut/files-to-create "opencode" "lazytest")
                  (map first)
                  (filter #(and % (str/includes? % "/references/")
                             (str/ends-with? % ".md")
                             (not (str/ends-with? % "spel-report.md"))
                             (not (str/ends-with? % "FULL_API.md")))))]
      (expect (seq paths))
      (doseq [path paths]
        (expect (str/includes? (#'sut/read-template path) "**Use when:**")))))
  (it "keeps recovery scoped and does not supply a global kill command"
    (let [content (#'sut/read-template "skills/spel/references/COMMON_PROBLEMS.md")]
      (expect (not (str/includes? content "spel kill --all-sessions")))
      (expect (str/includes? content "cancel <command-id>"))))
  (it "does not give Cognitect the Lazytest reporter flags"
    (let [content (#'sut/read-template "flavours/clojure-test/testing-conventions.md")]
      (expect (not (str/includes? content "--output nested")))
      (expect (str/includes? content "ALLURE_CLOJURE_TEST_ENABLED=true"))))
  (it "renders readable testing examples for both flavours"
    (doseq [flavour ["lazytest" "clojure-test"]]
      (let [content (#'sut/process-template
                     (#'sut/read-template (str "flavours/" flavour "/testing-conventions.md"))
                     "sample-app" flavour)
            examples (re-seq #"(?s)```clojure\n(.*?)\n```" content)]
        (expect (seq examples))
        (doseq [[_ code] examples
                :let [forms (read-string (str "[" code "]"))]]
          (expect (= 2 (count forms)))
          (expect (= 'sample-app.e2e.seed-test (second (first forms))))))))
  (it "renders readable seed namespaces for both flavours"
    (doseq [path ["seed_test.clj.template" "seed_test_ct.clj.template"]]
      (let [source (str/replace (#'sut/read-template path) "{{ns}}" "sample-app")
            forms (read-string (str "[" source "]"))]
        (expect (= 2 (count forms)))
        (expect (= 'sample-app.e2e.seed-test (second (first forms))))))))

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
        (expect (= "opencode" (:harness opts)))
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

  (describe "--harness"
    (it "parses --harness with space-separated value"
      (expect (= "claude" (:harness (#'sut/parse-args ["--harness" "claude"])))))

    (it "parses --harness agents with space-separated value"
      (expect (= "agents" (:harness (#'sut/parse-args ["--harness" "agents"])))))

    (it "parses --harness=agents syntax"
      (expect (= "agents" (:harness (#'sut/parse-args ["--harness=agents"])))))

    (it "parses --harness= syntax"
      (expect (= "claude" (:harness (#'sut/parse-args ["--harness=claude"]))))))

  (it "accepts --loop as an unpromoted alias"
    (expect (= "claude" (:harness (#'sut/parse-args ["--loop" "claude"]))))
    (expect (= "agents" (:harness (#'sut/parse-args ["--loop=agents"])))))

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
      (let [opts (#'sut/parse-args ["--ns" "my-app" "--harness=claude" "--force" "--dry-run"])]
        (expect (= "my-app" (:ns opts)))
        (expect (= "claude" (:harness opts)))
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
                  ".claude/skills/spel")]
      (expect (= "Read `.claude/skills/spel/SKILL.md` before any action." result))))

  (it "passes through content without skill instructions"
    (let [body "No skill instructions here"
          result (#'sut/replace-skill-instruction body ".claude/skills/spel")]
      (expect (= body result)))))

;; =============================================================================
;; 5. Claude Frontmatter Transformation
;; =============================================================================

(defdescribe transform-for-claude-test
  "Unit tests for OpenCode → Claude frontmatter transformation"

  (it "transforms frontmatter with description and default color"
    (let [content "---\ndescription: \"A test agent\"\n---\nBody text"
          result (#'sut/transform-for-claude content "spel" ".claude/skills/spel")]
      (expect (str/includes? result "name: spel"))
      (expect (str/includes? result "description: \"A test agent\""))
      (expect (str/includes? result "tools: Bash, Read, Write, Edit, Glob, Grep"))
      (expect (str/includes? result "color:"))
      (expect (str/includes? result "Body text"))))

  (it "preserves custom color from frontmatter"
    (let [content "---\ndescription: \"Agent\"\ncolor: \"#FF0000\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel" ".claude/skills/spel")]
      (expect (str/includes? result "color: \"#FF0000\""))))

  (it "uses default color when none specified"
    (let [content "---\ndescription: \"Agent\"\n---\nBody"
          result (#'sut/transform-for-claude content "spel" ".claude/skills/spel")]
      (expect (str/includes? result "color: \"#22C55E\""))))

  (it "returns content unchanged when no frontmatter"
    (let [content "No frontmatter here"
          result (#'sut/transform-for-claude content "spel" ".claude/skills/spel")]
      (expect (= content result))))

  (it "replaces skill loading instruction in body"
    (let [content "---\ndescription: \"Agent\"\n---\nLoad the `spel` skill before any action."
          result (#'sut/transform-for-claude content "spel" ".claude/skills/spel")]
      (expect (str/includes? result "Read `.claude/skills/spel/SKILL.md` before any action.")))))

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
      (expect (= content (#'sut/transform-agent-template content "claude" nil)))))

  (it "transforms content for agents target with the .agents skill path"
    (let [content "---\ndescription: \"Agent\"\n---\nLoad the `spel` skill before any action."
          result (#'sut/transform-agent-template content "agents" "spel")]
      (expect (str/includes? result "name: spel"))
      (expect (str/includes? result "Read `.agents/skills/spel/SKILL.md` before any action.")))))

(defdescribe set-skill-compatibility-test
  "Unit tests for SKILL.md compatibility rewriting"
  (it "rewrites the compatibility frontmatter field"
    (let [content "---\nname: spel\ncompatibility: opencode\n---\nbody"
          result (#'sut/set-skill-compatibility content "agents")]
      (expect (str/includes? result "compatibility: agents"))
      (expect (not (str/includes? result "compatibility: opencode")))))

  (it "only touches the top-level compatibility line"
    (let [content "---\ncompatibility: opencode\n---\ncompatibility notes stay"
          result (#'sut/set-skill-compatibility content "agents")]
      (expect (str/includes? result "compatibility notes stay")))))

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

(defdescribe generated-skill-operating-contract-test
  "Regression coverage for the generated skill's runtime and iOS workflow contracts"

  (it "treats the runtime binary as authoritative when a generated skill is stale"
    (let [skill (#'sut/read-template
                 "skills/spel/SKILL.md")]
      (expect (str/includes? skill "This skill and each shipped reference were generated from spel **{{version}}**"))
      (expect (str/includes? skill "automatically checks their release markers"))
      (expect (not (str/includes? skill "The installed skill matches spel")))))

  (it "resolves the shared example session name once"
    (doseq [path ["skills/spel/SKILL.md" "skills/spel/references/START_HERE.md"]]
      (let [content (#'sut/read-template path)]
        (expect (= 1 (count (re-seq #"(?m)^SESSION=" content))))
        (expect (str/includes? content "export SPEL_SESSION=\"$SESSION\""))
        (expect (str/includes? content "spel --session \"$SESSION\""))
        (expect (not (re-find #"spel --session .*date" content))))))

  (it "documents the public iOS measurement and recovery loop"
    (let [ios (#'sut/read-template
               "skills/spel/references/IOS_PROVIDER.md")]
      (expect (str/includes? ios "spel/ios-background-app!"))
      (expect (str/includes? ios "spel/ios-set-orientation!"))
      (expect (str/includes? ios "spel/with-webview-context"))
      (expect (str/includes? ios "It is **not** pure app"))
      (expect (str/includes? ios "Raw Appium is diagnostic evidence"))
      (expect (str/includes? ios "before 0.9.17"))))

  (it "keeps the generated agent on the public measured iOS path"
    (let [agent (#'sut/read-template "agents/spel.md")
          skill (#'sut/read-template "skills/spel/SKILL.md")]
      (expect (str/includes? agent "spel/with-webview-context"))
      (expect (str/includes? agent "first observable matching frame"))
      (expect (str/includes? agent "raw Appium is diagnostic evidence"))
      (expect (str/includes? skill "spel --session <name> health --json"))
      (expect (str/includes? skill "spel --session <name> logs -n 100"))))

  (it "keeps diagnostic commands scoped to the named session"
    (let [common (#'sut/read-template "skills/spel/references/COMMON_PROBLEMS.md")
          env     (#'sut/read-template "skills/spel/references/ENVIRONMENT_VARIABLES.md")]
      (expect (str/includes? common "spel --session <name> logs -f"))
      (expect (str/includes? common "spel --session <name> kill"))
      (expect (str/includes? env "spel --session <name> health --json"))
      (expect (str/includes? env "spel --session <name> cancel <id>")))))

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
        (expect (some #(str/includes? % "SESSION_COMMON.md") paths))
        (expect (some #(str/includes? % "ASSERTIONS_EVENTS.md") paths))))

    (it "does not duplicate reference files"
      (let [paths (output-paths (#'sut/files-to-create "opencode" "lazytest"))
            ref-paths (filter #(str/includes? % "/references/") paths)]
        (expect (= (count ref-paths) (count (distinct ref-paths))))))

    (it "ships every referenced template with real content"
      ;; Guards against a template being emptied in place: `read-template`
      ;; returns nil for a missing resource and "" for a blank one, and both
      ;; scaffold a useless file.
      (let [specs (#'sut/files-to-create "opencode" "lazytest")
            blank (->> specs
                    (map first)
                    (remove nil?)
                    (distinct)
                    (remove #(let [c (#'sut/read-template %)]
                               (and c (>= (count (str/trim c)) 200)))))]
        (expect (= [] (vec (sort blank)))))))

  (describe "claude harness target"
    (it "uses .claude directory paths"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest"))]
        (expect (some #(str/starts-with? % ".claude/") paths))
        (expect (not (some #(str/starts-with? % ".opencode/") paths)))))

    (it "includes SKILL.md under .claude/skills/spel"
      (let [paths (output-paths (#'sut/files-to-create "claude" "lazytest"))]
        (expect (some #(= ".claude/skills/spel/SKILL.md" %) paths)))))

  (describe "agents harness target (tool-agnostic .agents/skills)"
    (it "uses .agents/skills paths only"
      (let [paths (output-paths (#'sut/files-to-create "agents" "lazytest"))]
        (expect (every? #(str/starts-with? % ".agents/skills/spel") paths))
        (expect (not (some #(str/starts-with? % ".opencode/") paths)))
        (expect (not (some #(str/starts-with? % ".claude/") paths)))))

    (it "includes SKILL.md at the skill root"
      (let [paths (output-paths (#'sut/files-to-create "agents" "lazytest"))]
        (expect (some #(= ".agents/skills/spel/SKILL.md" %) paths))))

    (it "nests the agent under the skill dir"
      (let [paths (output-paths (#'sut/files-to-create "agents" "lazytest"))]
        (expect (some #(= ".agents/skills/spel/agents/spel.md" %) paths)))))

  (describe "clojure-test flavour"
    (it "uses clojure-test testing conventions"
      (let [specs (#'sut/files-to-create "opencode" "clojure-test")
            resource-paths (map first specs)]
        (expect (some #(str/includes? % "clojure-test/testing-conventions") resource-paths))))))

;; =============================================================================
;; 9. Agents Harness — End-to-End Scaffolding
;; =============================================================================

(defn- temp-root
  "Creates a unique temp directory and returns its absolute path."
  []
  (let [dir (java.io.File. (System/getProperty "java.io.tmpdir")
              (str "spel-agents-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- scaffold!
  "Runs scaffold-file for one spec into `root` and slurps back the written file."
  [root resource-path out-rel harness-target agent-name]
  (let [out (str root "/" out-rel)]
    (#'sut/scaffold-file resource-path out "desc" "+"
                         {:force true :flavour "lazytest"}
                         "demo" harness-target agent-name)
    (slurp out)))

(defdescribe scaffold-agents-e2e-test
  "End-to-end scaffolding for the tool-agnostic --harness=agents flavour."

  (describe "SKILL.md"
    (it "is written with compatibility: agents"
      (let [content (scaffold! (temp-root) "skills/spel/SKILL.md"
                      ".agents/skills/spel/SKILL.md" "agents" nil)]
        (expect (str/includes? content "compatibility: agents"))
        (expect (not (str/includes? content "compatibility: opencode"))))))

  (describe "agent template"
    (it "is written with markdown frontmatter naming the agent"
      (let [content (scaffold! (temp-root) "agents/spel.md"
                      ".agents/skills/spel/agents/spel.md" "agents" "spel")]
        (expect (str/includes? content "name: spel"))))

    (it "points the skill instruction at the nested .agents SKILL.md"
      (let [content (scaffold! (temp-root) "agents/spel.md"
                      ".agents/skills/spel/agents/spel.md" "agents" "spel")]
        (expect (str/includes? content ".agents/skills/spel/SKILL.md"))
        (expect (not (str/includes? content "load the `spel` skill first"))))))

  (describe "reference release stamps"
    (it "stamps every generated reference with the running spel release"
      (let [content (scaffold! (temp-root) "skills/spel/references/START_HERE.md"
                      ".agents/skills/spel/references/START_HERE.md" "agents" nil)]
        (expect (str/starts-with? content
                  (str "<!-- spel-reference-version: " @@#'sut/spel-version " -->\n")))))))

(defdescribe scaffold-template-matrix-test
  "Every harness/flavour renders a self-consistent skill tree."
  (it "routes to generated references, including the selected testing conventions"
    (doseq [harness ["opencode" "claude" "agents"]
            flavour ["lazytest" "clojure-test"]]
      (let [root (java.io.File. (temp-root))
            specs (#'sut/files-to-create harness flavour)
            skill-dir (:skill-dir (get @#'sut/harness-targets harness))]
        (try
          (with-out-str
            (doseq [[resource out description icon agent] specs]
              (#'sut/scaffold-file resource (str root "/" out) description icon
                                   {:force true :flavour flavour} "sample-app" harness agent)))
          (let [skill (slurp (java.io.File. root (str skill-dir "/SKILL.md")))]
            (expect (str/includes? skill (str "compatibility: " harness)))
            (expect (str/includes? skill "references/TESTING_CONVENTIONS.md")))
          (doseq [[_ out] specs
                  :let [content (slurp (java.io.File. root out))]]
            (expect (not (re-find #"\{\{(?:ns|version|testing-conventions)\}\}" content)))
            (doseq [[_ reference] (re-seq #"references/([A-Za-z0-9_-]+\.(?:md|html))" content)]
              (expect (.isFile (java.io.File. root (str skill-dir "/references/" reference))))))
          (let [conventions (slurp (java.io.File. root
                                     (str skill-dir "/references/TESTING_CONVENTIONS.md")))]
            (expect (str/ends-with? conventions
                      (#'sut/process-template
                       (#'sut/read-template (str "flavours/" flavour "/testing-conventions.md"))
                       "sample-app" flavour))))
          (finally
            (doseq [^java.io.File f (reverse (file-seq root))]
              (.delete f))))))))

;; =============================================================================
;; 10. Skill / Release Drift Check
;; =============================================================================

(defn- write-skill!
  "Writes a minimal generated SKILL.md for `rel-dir` under `root`, stamped with
   `version` (nil writes no version frontmatter field)."
  [root rel-dir version]
  (let [dir (java.io.File. (str root "/" rel-dir))]
    (.mkdirs dir)
    (spit (java.io.File. dir "SKILL.md")
      (str "---\n"
        "name: spel\n"
        (when version (str "version: \"" version "\"\n"))
        "compatibility: agents\n"
        "---\n\n"
        "# spel\n"))
    dir))

(defn- write-reference!
  "Writes one generated reference release marker under `root`."
  [root rel-path version]
  (let [file (java.io.File. (str root "/" rel-path))]
    (.mkdirs (.getParentFile file))
    (spit file
      (str (when version
             (str "<!-- spel-reference-version: " version " -->\n"))
        "# Reference\n"))
    file))

(def ^:private running-version
  (delay @@#'sut/spel-version))

(defdescribe skill-drift-test
  "The scaffolded skill is checked against the running spel release."

  (describe "scaffolded-skills"
    (it "finds nothing in a directory without a skill tree"
      (expect (empty? (sut/scaffolded-skills (temp-root)))))

    (it "reports the harness target, path and stamped version of each skill"
      (let [root (temp-root)]
        (write-skill! root ".claude/skills/spel" "1.2.3")
        (write-skill! root ".agents/skills/spel" nil)
        (let [by-target (into {} (map (juxt :harness-target identity))
                          (sut/scaffolded-skills root))]
          (expect (= #{"agents" "claude"} (set (keys by-target))))
          (expect (= ".claude/skills/spel/SKILL.md" (:path (get by-target "claude"))))
          (expect (= "1.2.3" (:version (get by-target "claude"))))
          (expect (nil? (:version (get by-target "agents"))))))))

  (describe "find-scaffolded-skills"
    (it "walks up from a nested directory to the project root"
      (let [root (temp-root)
            nested (java.io.File. (str root "/a/b/c"))]
        (write-skill! root ".claude/skills/spel" "1.2.3")
        (.mkdirs nested)
        (let [[found skills] (sut/find-scaffolded-skills (.getAbsolutePath nested))]
          (expect (= (.getCanonicalPath (java.io.File. root)) (.getCanonicalPath found)))
          (expect (= 1 (count skills))))))

    (it "returns nil when no skill tree is anywhere above"
      (let [root (temp-root)
            nested (java.io.File. (str root "/x/y"))]
        (.mkdirs nested)
        (expect (nil? (sut/find-scaffolded-skills (.getAbsolutePath nested)))))))

  (describe "skill-drift-warning"
    (it "is silent when the skill was generated by the running version"
      (let [root (temp-root)]
        (write-skill! root ".claude/skills/spel" @running-version)
        (expect (nil? (sut/skill-drift-warning root)))))

    (it "is silent when there is no scaffolded skill at all"
      (expect (nil? (sut/skill-drift-warning (temp-root)))))

    (it "warns, naming the stale file, both versions and the fix"
      (let [root (temp-root)
            _ (write-skill! root ".claude/skills/spel" "0.0.1-ancient")
            warning (sut/skill-drift-warning root)]
        (expect (some? warning))
        (expect (str/includes? warning ".claude/skills/spel/SKILL.md"))
        (expect (str/includes? warning "0.0.1-ancient"))
        (expect (str/includes? warning @running-version))
        (expect (str/includes? warning "spel init-agents --force"))
        (expect (str/includes? warning "SPEL_SKILL_CHECK"))))

    (it "warns for a skill with no version stamp"
      (let [root (temp-root)
            _ (write-skill! root ".agents/skills/spel" nil)
            warning (sut/skill-drift-warning root)]
        (expect (some? warning))
        (expect (str/includes? warning "unknown version"))))

    (it "warns only about the skills that are out of sync"
      (let [root (temp-root)
            _ (write-skill! root ".claude/skills/spel" @running-version)
            _ (write-skill! root ".agents/skills/spel" "0.0.1-ancient")
            warning (sut/skill-drift-warning root)]
        (expect (str/includes? warning ".agents/skills/spel/SKILL.md"))
        (expect (not (str/includes? warning ".claude/skills/spel/SKILL.md"))))))

  (describe "warn-on-skill-drift!"
    (it "prints the warning to stderr, keeping stdout clean"
      (let [root (temp-root)
            _ (write-skill! root ".claude/skills/spel" "0.0.1-ancient")
            err (java.io.StringWriter.)
            out (java.io.StringWriter.)]
        (binding [*err* err *out* out]
          (sut/warn-on-skill-drift! root))
        (expect (str/includes? (str err) "OUT OF SYNC"))
        (expect (= "" (str out)))))

    (it "prints nothing when the skill matches"
      (let [root (temp-root)
            _ (write-skill! root ".claude/skills/spel" @running-version)
            err (java.io.StringWriter.)]
        (binding [*err* err]
          (sut/warn-on-skill-drift! root))
        (expect (= "" (str err))))))

  (describe "reference release drift"
    (it "discovers and reports the version of every existing shipped reference"
      (let [root (temp-root)]
        (write-skill! root ".claude/skills/spel" @running-version)
        (write-reference! root ".claude/skills/spel/references/START_HERE.md" "1.2.3")
        (let [skill (first (sut/scaffolded-skills root))
              reference (first (:references skill))]
          (expect (= ".claude/skills/spel/references/START_HERE.md" (:path reference)))
          (expect (= "1.2.3" (:version reference))))))

    (it "warns when SKILL.md matches but one generated reference is stale"
      (let [root (temp-root)]
        (write-skill! root ".claude/skills/spel" @running-version)
        (write-reference! root ".claude/skills/spel/references/START_HERE.md" "0.0.1-ancient")
        (let [warning (sut/skill-drift-warning root)]
          (expect (str/includes? warning ".claude/skills/spel/references/START_HERE.md"))
          (expect (str/includes? warning "0.0.1-ancient")))))

    (it "is silent when SKILL.md and all existing generated references match"
      (let [root (temp-root)]
        (write-skill! root ".agents/skills/spel" @running-version)
        (write-reference! root ".agents/skills/spel/references/START_HERE.md" @running-version)
        (write-reference! root ".agents/skills/spel/references/FULL_API.md" @running-version)
        (expect (nil? (sut/skill-drift-warning root)))))

    (it "warns for a legacy reference without a release marker"
      (let [root (temp-root)]
        (write-skill! root ".agents/skills/spel" @running-version)
        (write-reference! root ".agents/skills/spel/references/FULL_API.md" nil)
        (let [warning (sut/skill-drift-warning root)]
          (expect (str/includes? warning "references/FULL_API.md"))
          (expect (str/includes? warning "unknown version")))))))
