(ns com.blockether.spel.init-agents
  "CLI command to scaffold agent definitions for E2E testing.

   Supports multiple agent loop targets via --loop:
   - opencode (default) — .opencode/agents/, .opencode/prompts/, .opencode/skills/
   - claude             — .claude/agents/, .claude/prompts/, .claude/docs/
   - vscode             — .github/agents/, .github/prompts/, .github/docs/

   Also generates:
   - test-e2e/specs/ — test plans directory (colocated with tests)
   - test-e2e/<ns>/e2e/ — seed test (path derived from --ns)

   Usage:
     spel init-agents --ns my-app
     spel init-agents --ns my-app --loop=claude
     spel init-agents --ns my-app --loop=vscode
     spel init-agents --ns my-app --no-tests
     spel init-agents --ns my-app --test-dir test-e2e --specs-dir test-e2e/specs
     spel init-agents --dry-run"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private template-base
  "Base path for template resources on classpath."
  "com/blockether/spel/templates/")

(def ^:private spel-version
  "Reads the version string from the SPEL_VERSION resource file.
   This file is the single source of truth for the spel version."
  (delay
    (-> (io/resource "SPEL_VERSION")
      slurp
      str/trim)))

(def ^:private loop-targets
  "Configuration for each supported agent loop target.
   Keys: agent-dir, prompt-dir, skill-dir, agent-ext, agent-ref-fmt, desc.
   agent-ref-fmt is a format string for agent references in prompts (takes agent name)."
  {"opencode" {:agent-dir ".opencode/agents"
               :prompt-dir ".opencode/prompts"
               :skill-dir ".opencode/skills/spel"
               :agent-ext ".md"
               :agent-ref-fmt "@%s"
               :desc "OpenCode"}
   "claude"   {:agent-dir ".claude/agents"
               :prompt-dir ".claude/prompts"
               :skill-dir ".claude/docs/spel"
               :agent-ext ".md"
               :agent-ref-fmt "@%s"
               :desc "Claude Code"}
   "vscode"   {:agent-dir ".github/agents"
               :prompt-dir ".github/prompts"
               :skill-dir ".github/docs/spel"
               :agent-ext ".agent.md"
               :agent-ref-fmt "#agent:%s"
               :desc "VS Code / Copilot"}})

(defn- files-to-create
  "Returns file specs based on loop target and whether tests are included.
   Each entry: [resource-path output-path description icon agent-name-or-nil].
   agent-name is non-nil for agent templates that need frontmatter transformation.
   When no-tests is true, only the SKILL file is generated (no test agents/prompts)."
  [loop-target no-tests]
  (let [{:keys [agent-dir prompt-dir skill-dir agent-ext]} (get loop-targets loop-target)
        skill-file [["skills/spel/SKILL.md"
                     (str skill-dir "/SKILL.md")
                     "API reference skill"
                     "+"
                     nil]]
        test-files [["agents/spel-test-planner.md"
                     (str agent-dir "/spel-test-planner" agent-ext)
                     "test planner agent"
                     "+"
                     "spel-test-planner"]
                    ["agents/spel-test-generator.md"
                     (str agent-dir "/spel-test-generator" agent-ext)
                     "test generator agent"
                     "+"
                     "spel-test-generator"]
                    ["agents/spel-test-healer.md"
                     (str agent-dir "/spel-test-healer" agent-ext)
                     "test healer agent"
                     "+"
                     "spel-test-healer"]
                    ["prompts/spel-test-workflow.md"
                     (str prompt-dir "/spel-test-workflow.md")
                     "coverage workflow"
                     "+"
                     nil]]]
    (if no-tests
      skill-file
      (into test-files skill-file))))

(def ^:private seed-template-resource
  "Resource path for the seed test template (uses .template extension to
   avoid clojure-lsp trying to parse the {{ns}} placeholder as Clojure)."
  "seed_test.clj.template")

;; =============================================================================
;; Namespace Derivation
;; =============================================================================

(defn- derive-namespace
  "Derives a namespace string from the current working directory name.
   Normalizes to lowercase, replaces hyphens/underscores consistently."
  []
  (let [dir-name (.getName (io/file (System/getProperty "user.dir")))]
    (str/replace dir-name #"[^a-zA-Z0-9]+" "-")))

(defn- ns->path
  "Converts a Clojure namespace string to a file path relative to a root dir.
   E.g. (ns->path \"test\" \"unbound.e2e.seed-test\") => \"test/unbound/e2e/seed_test.clj\""
  [root-dir ns-str]
  (let [path-segments (-> ns-str
                        (str/replace "-" "_")
                        (str/split #"\."))]
    (str root-dir "/" (str/join "/" path-segments) ".clj")))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn- read-template
  "Reads a template resource from the classpath. Returns nil if not found."
  [resource-path]
  (let [full-path (str template-base resource-path)
        resource (io/resource full-path)]
    (when resource
      (slurp resource))))

(defn- file-exists?
  "Checks if a file exists at the given path."
  [path]
  (.exists (io/file path)))

(defn- ensure-parent-dirs!
  "Creates parent directories for the given file path if they don't exist."
  [path]
  (let [parent (.getParentFile (io/file path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))))

(defn- write-file!
  "Writes content to a file, creating parent directories if needed.
   Returns true on success, false if dry-run."
  [path content dry-run]
  (if dry-run
    false
    (do
      (ensure-parent-dirs! path)
      (spit path content)
      true)))

;; =============================================================================
;; Template Processing
;; =============================================================================

(defn- process-template
  "Processes a template by replacing placeholders.
   Currently only replaces {{ns}} with the derived namespace."
  [content ns-name]
  (str/replace content "{{ns}}" ns-name))

;; =============================================================================
;; Frontmatter Transformation
;; =============================================================================

(defn- extract-frontmatter
  "Extracts frontmatter string and body from a template.
   Returns [frontmatter-str body-str] where frontmatter-str does NOT include
   the --- delimiters. Returns nil if no frontmatter found."
  [content]
  (when (str/starts-with? content "---\n")
    (let [end-idx (str/index-of content "\n---\n" 1)]
      (when end-idx
        [(subs content 4 (long end-idx))
         (subs content (+ (long end-idx) 5))]))))

(defn- extract-fm-field
  "Extracts a single top-level field value from YAML-ish frontmatter string.
   Only matches non-indented lines (top-level keys)."
  [fm-str field-name]
  (when-let [match (re-find (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote field-name) ":\\s*(.*)$")) fm-str)]
    (str/trim (second match))))

(def ^:private agent-ref-names
  "Agent names that may be referenced in templates via @name syntax."
  ["spel-test-planner" "spel-test-generator" "spel-test-healer"])

(defn- transform-agent-references
  "Replaces @agent-name references in template content with the
   target-appropriate agent invocation syntax based on agent-ref-fmt."
  [content loop-target]
  (let [fmt (:agent-ref-fmt (get loop-targets loop-target))]
    (reduce (fn [c agent-name]
              (str/replace c (str "@" agent-name) (format fmt agent-name)))
      content
      agent-ref-names)))

(defn- replace-skill-instruction
  "Replaces the OpenCode skill loading instruction with a file-read instruction
   for non-OpenCode targets."
  [body skill-dir]
  (str/replace body
    "You MUST load the `spel` skill before performing any action. This skill contains the complete API reference for browser automation, assertions, locators, and test fixtures. Do not proceed without loading it first."
    (str "You MUST read the file `" skill-dir "/SKILL.md` before performing any action. This file contains the complete API reference for browser automation, assertions, locators, and test fixtures. Do not proceed without reading it first.")))

(defn- transform-for-claude
  "Transforms OpenCode agent template to Claude Code format.
   Replaces frontmatter with Claude Code fields and updates skill instruction."
  [content agent-name skill-dir]
  (if-let [[fm-str body] (extract-frontmatter content)]
    (let [description (extract-fm-field fm-str "description")
          color (or (extract-fm-field fm-str "color") "\"#22C55E\"")
          new-fm (str "---\n"
                   "name: " agent-name "\n"
                   "description: " (pr-str description) "\n"
                   "tools: Bash, Read, Write, Edit, Glob, Grep\n"
                   "color: " color "\n"
                   "---\n")
          new-body (replace-skill-instruction body skill-dir)]
      (str new-fm new-body))
    content))

(defn- transform-for-vscode
  "Transforms OpenCode agent template to VS Code / Copilot format.
   Replaces frontmatter with VS Code fields and updates skill instruction."
  [content agent-name skill-dir]
  (if-let [[fm-str body] (extract-frontmatter content)]
    (let [description (extract-fm-field fm-str "description")
          new-fm (str "---\n"
                   "name: " agent-name "\n"
                   "description: " (pr-str description) "\n"
                   "tools: ['editFiles', 'createFile', 'runInTerminal', 'readFile', 'listDirectory', 'searchFiles']\n"
                   "---\n")
          new-body (replace-skill-instruction body skill-dir)]
      (str new-fm new-body))
    content))

(defn- transform-agent-template
  "Transforms an agent template for the target loop format.
   Only transforms agent files (agent-name non-nil). Other files pass through unchanged."
  [content loop-target agent-name]
  (if (nil? agent-name)
    content
    (let [skill-dir (:skill-dir (get loop-targets loop-target))]
      (case loop-target
        "opencode" content
        "claude"   (transform-for-claude content agent-name skill-dir)
        "vscode"   (transform-for-vscode content agent-name skill-dir)
        content))))

;; =============================================================================
;; CLI Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parses command-line arguments into a map of options.
   Supports: --dry-run, --force, --ns NS, --loop TARGET, --test-dir DIR, --specs-dir DIR"
  [args]
  (loop [remaining args
         opts {:dry-run false
               :force false
               :no-tests false
               :ns nil
               :loop "opencode"
               :test-dir "test-e2e"
               :specs-dir "test-e2e/specs"}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= "--dry-run" arg)
          (recur (rest remaining) (assoc opts :dry-run true))

          (= "--force" arg)
          (recur (rest remaining) (assoc opts :force true))

          (= "--no-tests" arg)
          (recur (rest remaining) (assoc opts :no-tests true))

          (= "--ns" arg)
          (recur (drop 2 remaining)
            (assoc opts :ns (second remaining)))

          (str/starts-with? arg "--ns=")
          (recur (rest remaining)
            (assoc opts :ns (subs arg (count "--ns="))))

          (= "--loop" arg)
          (recur (drop 2 remaining)
            (assoc opts :loop (second remaining)))

          (str/starts-with? arg "--loop=")
          (recur (rest remaining)
            (assoc opts :loop (subs arg (count "--loop="))))

          (= "--test-dir" arg)
          (recur (drop 2 remaining)
            (assoc opts :test-dir (second remaining)))

          (str/starts-with? arg "--test-dir=")
          (recur (rest remaining)
            (assoc opts :test-dir (subs arg (count "--test-dir="))))

          (= "--specs-dir" arg)
          (recur (drop 2 remaining)
            (assoc opts :specs-dir (second remaining)))

          (str/starts-with? arg "--specs-dir=")
          (recur (rest remaining)
            (assoc opts :specs-dir (subs arg (count "--specs-dir="))))

          (#{"--help" "-h"} arg)
          (assoc opts :help true)

          :else
          (recur (rest remaining) opts))))))

;; =============================================================================
;; Scaffolding Logic
;; =============================================================================

(defn- scaffold-file
  "Scaffolds a single file from a template.
   Applies frontmatter transformation for non-OpenCode loop targets.
   Returns {:created true/false :skipped true/false :reason string}."
  [resource-path output-path _description _icon opts ns-name loop-target agent-name]
  (let [dry-run (:dry-run opts)
        force (:force opts)]
    (cond
      ;; Check if template exists
      (nil? (read-template resource-path))
      {:created false :skipped true :reason (str "Template not found: " resource-path)}

      ;; Check if file already exists
      (and (file-exists? output-path) (not force))
      {:created false :skipped true :reason "Already exists (use --force to overwrite)"}

      :else
      (let [content (-> (read-template resource-path)
                      (process-template ns-name)
                      (transform-agent-references loop-target)
                      (transform-agent-template loop-target agent-name))
            written? (write-file! output-path content dry-run)]
        (if dry-run
          {:created false :skipped false :dry-run true :reason "(dry-run)"}
          {:created written? :skipped false :reason nil})))))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn- print-banner
  "Prints the initialization banner with the target name."
  [loop-target no-tests]
  (let [desc (:desc (get loop-targets loop-target))]
    (if no-tests
      (println (str "Initializing Playwright skill for " desc " (interactive development, no test agents)..."))
      (println (str "Initializing Playwright E2E testing agents for " desc "...")))
    (println "")))

(defn- print-help
  "Prints CLI help."
  []
  (println "spel init-agents — Scaffold E2E testing agents for your editor")
  (println "")
  (println "Usage:")
  (println "  spel init-agents --ns my-app")
  (println "  spel init-agents --ns my-app --loop=claude")
  (println "  spel init-agents --ns my-app --loop=vscode")
  (println "  spel init-agents --ns my-app --test-dir test/e2e --specs-dir test-e2e/specs")
  (println "  spel init-agents --ns my-app --no-tests")
  (println "  spel init-agents --ns my-app --dry-run")
  (println "  spel init-agents --ns my-app --force")
  (println "")
  (println "Options:")
  (println "  --loop TARGET     Agent format: opencode (default), claude, vscode")
  (println "  --ns NS           Base namespace for generated tests (e.g. my-app → my-app.e2e.seed-test)")
  (println "                    If omitted, derived from the current directory name")
  (println "  --no-tests        Scaffold only the SKILL (API reference) — no test agents, specs, or seed test.")
  (println "                    Use this when spel is for interactive development, not E2E testing.")
  (println "  --test-dir DIR    Root test directory for E2E tests (default: test-e2e)")
  (println "  --specs-dir DIR   Test plans directory (default: test-e2e/specs)")
  (println "  --dry-run         Show what would be created without writing")
  (println "  --force           Overwrite existing files")
  (println "  -h, --help        Show this help")
  (println "")
  (println "Loop targets:")
  (println "  opencode          .opencode/agents/, .opencode/prompts/, .opencode/skills/")
  (println "  claude            .claude/agents/, .claude/prompts/, .claude/docs/")
  (println "  vscode            .github/agents/, .github/prompts/, .github/docs/")
  (println "")
  (println "Also generates (all targets, unless --no-tests):")
  (println "  test-e2e/specs/                — Test plans directory (with README)")
  (println "  test-e2e/<ns>/e2e/seed_test.clj — Seed test (path derived from --ns)"))

(defn- print-result
  "Prints a result line with icon and description."
  [icon path description result]
  (let [status (cond
                 (:dry-run result) "(dry-run)"
                 (:skipped result) (str "(" (:reason result) ")")
                 (:created result) ""
                 :else "")]
    (println (str "  " icon " " path " - " description
               (when (seq status) (str " " status))))))

(defn- print-footer
  "Prints the completion message and next steps for the user."
  [loop-target test-dir no-tests]
  (println "")
  (if no-tests
    (do
      (println "Done! Skill installed for interactive development.")
      (println "")
      (println "Next steps:")
      (println "")
      (println "  1. Install Playwright browsers:")
      (println "     spel install --with-deps chromium")
      (println "")
      (println "  2. Add spel to your deps.edn:")
      (println "")
      (println (str "     :deps {com.blockether/spel {:mvn/version \"" @spel-version "\"}}"))
      (println "")
      (println "  3. Use spel for browser automation:")
      (println "     spel open https://example.com")
      (println "     spel --eval '(page/navigate \"https://example.com\")'"))
    (do
      (if (= "opencode" loop-target)
        (println "Done! Use @spel-test-planner to start planning tests.")
        (println "Done! Use the spel-test-planner agent to start planning tests."))
      (println "")
      (println "Next steps:")
      (println "")
      (println "  1. Install Playwright browsers:")
      (println "     spel install --with-deps chromium")
      (println "")
      (println "  2. Add the :e2e alias to your deps.edn:")
      (println "")
      (println (str "     :e2e {:extra-paths [\"" test-dir "\"]"))
      (println (str "           :extra-deps {com.blockether/spel {:mvn/version \"" @spel-version "\"}}"))
      (println (str "           :main-opts [\"-m\" \"lazytest.main\" \"--dir\" \"" test-dir "\"]}"))
      (println "")
      (println "  3. Run the E2E tests:")
      (println "     clojure -M:e2e")
      (println "")
      (println "  4. Update the seed test URL in")
      (println (str "     " test-dir "/<ns>/e2e/seed_test.clj"))
      (println "     to point to your development server."))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "CLI entry point. Scaffolds agent definitions for E2E testing."
  [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (print-help)

      (not (contains? loop-targets (:loop opts)))
      (do
        (binding [*out* *err*]
          (println (str "Error: Unknown --loop target: " (:loop opts)))
          (println (str "Valid targets: " (str/join ", " (sort (keys loop-targets))))))
        (System/exit 1))

      :else
      (let [loop-target (:loop opts)
            no-tests (:no-tests opts)
            ns-name (or (:ns opts)
                      (do (println "Warning: No --ns provided, deriving from directory name.")
                        (println "         Tip: use --ns my-app to set namespace explicitly.")
                        (println "")
                        (derive-namespace)))
            test-dir (:test-dir opts)
            specs-dir (:specs-dir opts)]
        (print-banner loop-target no-tests)

        ;; Scaffold files (skill only when --no-tests, full set otherwise)
        (doseq [[resource-path output-path description icon agent-name] (files-to-create loop-target no-tests)]
          (let [result (scaffold-file resource-path output-path description icon opts ns-name loop-target agent-name)]
            (print-result icon output-path description result)))

        (when-not no-tests
          ;; Scaffold specs directory with README
          (let [specs-readme-path (str specs-dir "/README.md")
                specs-readme-result (scaffold-file "specs_readme.md" specs-readme-path "test plans directory" "+" opts ns-name loop-target nil)]
            (print-result "+" specs-readme-path "test plans directory" specs-readme-result))

          ;; Scaffold test directory with seed test
          ;; Path derived from namespace: unbound.e2e.seed-test → test/unbound/e2e/seed_test.clj
          (let [seed-ns (str ns-name ".e2e.seed-test")
                seed-path (ns->path test-dir seed-ns)
                seed-result (scaffold-file seed-template-resource seed-path "seed test" "+" opts ns-name loop-target nil)]
            (print-result "+" seed-path "seed test" seed-result)))

        (print-footer loop-target test-dir no-tests)))))
