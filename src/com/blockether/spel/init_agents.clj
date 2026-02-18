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

(def ^:private loop-targets
  "Configuration for each supported agent loop target.
   Keys: agent-dir, prompt-dir, skill-dir, agent-ext, desc."
  {"opencode" {:agent-dir ".opencode/agents"
               :prompt-dir ".opencode/prompts"
               :skill-dir ".opencode/skills/spel"
               :agent-ext ".md"
               :desc "OpenCode"}
   "claude"   {:agent-dir ".claude/agents"
               :prompt-dir ".claude/prompts"
               :skill-dir ".claude/docs/spel"
               :agent-ext ".md"
               :desc "Claude Code"}
   "vscode"   {:agent-dir ".github/agents"
               :prompt-dir ".github/prompts"
               :skill-dir ".github/docs/spel"
               :agent-ext ".agent.md"
               :desc "VS Code / Copilot"}})

(defn- files-to-create
  "Returns file specs based on loop target.
   Each entry: [resource-path output-path description icon agent-name-or-nil].
   agent-name is non-nil for agent templates that need frontmatter transformation."
  [loop-target]
  (let [{:keys [agent-dir prompt-dir skill-dir agent-ext]} (get loop-targets loop-target)]
    [["agents/spel-test-planner.md"
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
      nil]
     ["skills/spel/SKILL.md"
      (str skill-dir "/SKILL.md")
      "API reference skill"
      "+"
      nil]]))

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

(defn- replace-skill-instruction
  "Replaces the OpenCode skill loading instruction with a file-read instruction
   for non-OpenCode targets."
  [body skill-dir]
  (str/replace body
    "Load the `spel` skill first for API reference."
    (str "Read the file `" skill-dir "/SKILL.md` for the full spel API reference.")))

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
  [loop-target]
  (let [desc (:desc (get loop-targets loop-target))]
    (println (str "Initializing Playwright E2E testing agents for " desc "..."))
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
  (println "  spel init-agents --ns my-app --dry-run")
  (println "  spel init-agents --ns my-app --force")
  (println "")
  (println "Options:")
  (println "  --loop TARGET     Agent format: opencode (default), claude, vscode")
  (println "  --ns NS           Base namespace for generated tests (e.g. my-app → my-app.e2e.seed-test)")
  (println "                    If omitted, derived from the current directory name")
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
  (println "Also generates (all targets):")
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
  [loop-target test-dir]
  (println "")
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
  (println "           :extra-deps {com.blockether/spel {:mvn/version \"0.0.1-SNAPSHOT\"}}")
  (println "           :main-opts [\"-m\" \"lazytest.main\"]}")
  (println "")
  (println "  3. Run the E2E tests:")
  (println (str "     clojure -M:e2e --dir " test-dir))
  (println "")
  (println "  4. Update the seed test URL in")
  (println (str "     " test-dir "/<ns>/e2e/seed_test.clj"))
  (println "     to point to your development server."))

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
            ns-name (or (:ns opts)
                      (do (println "Warning: No --ns provided, deriving from directory name.")
                          (println "         Tip: use --ns my-app to set namespace explicitly.")
                          (println "")
                          (derive-namespace)))
            test-dir (:test-dir opts)
            specs-dir (:specs-dir opts)]
        (print-banner loop-target)

        ;; Scaffold agent, prompt, and skill files
        (doseq [[resource-path output-path description icon agent-name] (files-to-create loop-target)]
          (let [result (scaffold-file resource-path output-path description icon opts ns-name loop-target agent-name)]
            (print-result icon output-path description result)))

        ;; Scaffold specs directory with README
        (let [specs-readme-path (str specs-dir "/README.md")
              specs-readme-result
              (if (file-exists? specs-readme-path)
                {:created false :skipped true :reason "Already exists"}
                (scaffold-file "specs_readme.md" specs-readme-path "test plans directory" "+" opts ns-name loop-target nil))]
          (when (or (:dry-run opts) (not (:skipped specs-readme-result)))
            (print-result "+" specs-dir "test plans directory" specs-readme-result)))

        ;; Scaffold test directory with seed test
        ;; Path derived from namespace: unbound.e2e.seed-test → test/unbound/e2e/seed_test.clj
        (let [seed-ns (str ns-name ".e2e.seed-test")
              seed-path (ns->path test-dir seed-ns)
              seed-result (scaffold-file seed-template-resource seed-path "seed test" "+" opts ns-name loop-target nil)]
          (print-result "+" seed-path "seed test" seed-result))

        (print-footer loop-target test-dir)))))
