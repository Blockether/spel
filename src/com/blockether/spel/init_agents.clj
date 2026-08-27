(ns com.blockether.spel.init-agents
  "CLI command to scaffold the spel agent + skill for browser automation.

   Supports multiple agent harness targets via --harness:
   - opencode (default) — .opencode/agents/, .opencode/skills/
   - claude             — .claude/agents/, .claude/skills/

   Supports test framework flavours via --flavour:
   - lazytest (default) — defdescribe/it/expect from spel.allure, :context fixtures
   - clojure-test       — deftest/testing/is from clojure.test, use-fixtures

   Also generates:
   - test-e2e/<ns>/e2e/ — seed test (path derived from --ns)

   Usage:
     spel init-agents --ns my-app
     spel init-agents --ns my-app --harness=claude
     spel init-agents --ns my-app --flavour=clojure-test
     spel init-agents --ns my-app --no-tests
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

(def ^:private harness-targets
  "Configuration for each supported agent harness target.
   Keys: agent-dir, skill-dir, agent-ext, compatibility, desc.
   The `agents` target is the tool-agnostic `.agents/skills/` layout: a single
   skill tree with the agent nested under it (compatibility: agents)."
  {"opencode" {:agent-dir ".opencode/agents"
               :skill-dir ".opencode/skills/spel"
               :agent-ext ".md"
               :compatibility "opencode"
               :desc "OpenCode"}
   "claude"   {:agent-dir ".claude/agents"
               :skill-dir ".claude/skills/spel"
               :agent-ext ".md"
               :compatibility "opencode"
               :desc "Claude Code"}
   "agents"   {:agent-dir ".agents/skills/spel/agents"
               :skill-dir ".agents/skills/spel"
               :agent-ext ".md"
               :compatibility "agents"
               :desc "Agents (tool-agnostic)"}})

(def ^:private valid-flavours
  "Supported test framework flavours."
  #{"lazytest" "clojure-test"})

(def ^:private reference-files
  "All reference files to scaffold alongside the skill and agent."
  ["START_HERE.md" "CAPABILITIES.md" "FULL_API.md" "CONSTANTS.md"
   "COMMON_PROBLEMS.md" "ENVIRONMENT_VARIABLES.md" "SESSION_COMMON.md"
   "ASSERTIONS_EVENTS.md" "API_TESTING.md"
   "ALLURE_REPORTING.md" "CI_WORKFLOWS.md"
   "EVAL_GUIDE.md" "SELECTORS_SNAPSHOTS.md" "PAGE_LOCATORS.md"
   "NAVIGATION_WAIT.md" "FRAMES_INPUT.md" "PROFILES_CDP.md"
   "BROWSER_OPTIONS.md" "NETWORK_ROUTING.md" "IOS_PROVIDER.md" "CODEGEN_CLI.md"
   "PDF_STITCH_VIDEO.md" "PRESENTER_SKILL.md" "CSS_PATTERNS.md"
   "LIBRARIES.md" "SLIDE_PATTERNS.md" "SEARCH_API.md"
   "spel-report.html" "spel-report.md"])

(defn- files-to-create
  "Returns file specs for the single agent + skill + references.
   Each entry: [resource-path output-path description icon agent-name-or-nil].
   agent-name is non-nil for the agent template (needs frontmatter transformation)."
  [harness-target flavour]
  (let [{:keys [agent-dir skill-dir agent-ext]} (get harness-targets harness-target)
        testing-conventions-resource (str "flavours/" flavour "/testing-conventions.md")
        skill-files (into [["skills/spel/SKILL.md"
                            (str skill-dir "/SKILL.md")
                            "API reference skill"
                            "+"
                            nil]
                           [testing-conventions-resource
                            (str skill-dir "/references/TESTING_CONVENTIONS.md")
                            "ref: TESTING_CONVENTIONS"
                            "+"
                            nil]]
                      (mapv (fn [filename]
                              [(str "skills/spel/references/" filename)
                               (str skill-dir "/references/" filename)
                               (str "ref: " (str/replace filename #"\.[^.]+$" ""))
                               "+"
                               nil])
                        reference-files))
        agent-file [["agents/spel.md"
                     (str agent-dir "/spel" agent-ext)
                     "spel agent"
                     "+"
                     "spel"]]]
    (into skill-files agent-file)))

(defn- seed-template-resource
  "Resource path for the seed test template based on flavour.
   Uses .template extension to avoid clojure-lsp trying to parse
   the {{ns}} placeholder as Clojure."
  [flavour]
  (if (= "clojure-test" flavour)
    "seed_test_ct.clj.template"
    "seed_test.clj.template"))

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
      ;; Normalize CRLF → LF so templates checked out on Windows (git
      ;; autocrlf) still match the `---\n` frontmatter delimiters and other
      ;; LF-anchored transforms downstream. Emitted files are LF everywhere.
      (str/replace (slurp resource) "\r\n" "\n"))))

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

(defn- read-flavour-section
  "Reads a flavour-specific template section from classpath.
   Returns the content string, or nil if not found."
  [section-name flavour]
  (let [path (str template-base "flavours/" flavour "/" section-name)
        resource (io/resource path)]
    (when resource
      (slurp resource))))

(defn- process-template
  "Processes a template by replacing placeholders.
   Replaces {{ns}}, {{version}}, and {{testing-conventions}} (flavour-specific testing section)."
  [content ns-name flavour]
  (let [testing-section (or (read-flavour-section "testing-conventions.md" flavour)
                          "")]
    (-> content
      (str/replace "{{ns}}" ns-name)
      (str/replace "{{version}}" @spel-version)
      (str/replace "{{testing-conventions}}" testing-section))))

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

(defn- strip-matching-quotes
  "Removes one matching layer of surrounding single or double quotes."
  [s]
  (if (and (string? s)
        (>= (count s) 2)
        (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
          (and (str/starts-with? s "'") (str/ends-with? s "'"))))
    (subs s 1 (dec (count s)))
    s))

(defn- replace-skill-instruction
  "Replaces the OpenCode skill loading instruction with a file-read instruction
   for non-OpenCode targets."
  [body skill-dir]
  (-> body
    (str/replace
      "load the `spel` skill first"
      (str "read `" skill-dir "/SKILL.md` first"))
    (str/replace
      "Load the `spel` skill before any action."
      (str "Read `" skill-dir "/SKILL.md` before any action."))
    (str/replace
      "load `spel` skill first"
      (str "read `" skill-dir "/SKILL.md` first"))))

(defn- transform-for-claude
  "Transforms OpenCode agent template to Claude Code format.
   Replaces frontmatter with Claude Code fields and updates skill instruction."
  [content agent-name skill-dir]
  (if-let [[fm-str body] (extract-frontmatter content)]
    (let [description (some-> (extract-fm-field fm-str "description")
                        strip-matching-quotes)
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

(defn- transform-agent-template
  "Transforms an agent template for the target harness format.
   Only transforms agent files (agent-name non-nil). Other files pass through unchanged.
   The `claude` and `agents` targets both need markdown frontmatter + a file-read
   skill instruction pointing at their own SKILL.md location."
  [content harness-target agent-name]
  (if (nil? agent-name)
    content
    (let [skill-dir (:skill-dir (get harness-targets harness-target))]
      (case harness-target
        "opencode" content
        "claude"   (transform-for-claude content agent-name skill-dir)
        "agents"   (transform-for-claude content agent-name skill-dir)
        content))))

(defn- set-skill-compatibility
  "Rewrites the top-level `compatibility:` frontmatter field of a SKILL.md to the
   target's compatibility value (e.g. opencode → agents for the .agents/ layout)."
  [content compatibility]
  (str/replace content
    #"(?m)^compatibility:.*$"
    (str "compatibility: " compatibility)))

(def ^:private reference-version-pattern
  "Machine-readable first-line marker added to every scaffolded reference."
  #"^<!-- spel-reference-version: ([^ ]+) -->$")

(defn- stamp-reference
  "Prepends the running spel release marker to a generated reference. The marker
   is an HTML comment, so it is inert in Markdown and HTML templates."
  [content]
  (str "<!-- spel-reference-version: " @spel-version " -->\n" content))

;; =============================================================================
;; Skill / Release Drift Check
;; =============================================================================

(def ^:private skill-check-env
  "Env var that silences the scaffolded-skill drift check when set to `false`."
  "SPEL_SKILL_CHECK")

(def ^:private skill-search-depth
  "How many directory levels (cwd plus ancestors) are scanned for a scaffolded
   skill tree, so running spel from a subdirectory still finds the project one."
  6)

(defn- skill-file-version
  "Reads the top-level `version:` frontmatter field of a generated SKILL.md.
   Returns nil when the file is unreadable or predates the version stamp."
  [^java.io.File file]
  (try
    (when-let [[fm-str _] (extract-frontmatter (slurp file))]
      (not-empty (strip-matching-quotes (or (extract-fm-field fm-str "version") ""))))
    (catch Exception _ nil)))

(defn- reference-file-version
  "Reads a generated reference's first-line release marker without slurping the
   potentially large reference body. Returns nil for an unreadable or unstamped
   file."
  [^java.io.File file]
  (try
    (with-open [^java.io.BufferedReader reader (io/reader file)]
      (some->> (.readLine reader)
        (re-matches reference-version-pattern)
        second))
    (catch Exception _ nil)))

(defn- scaffolded-references
  "Existing shipped references under one generated skill, stamped or legacy.
   Unknown user-added files are ignored; every reference spel itself scaffolds is
   checked."
  [dir skill-dir]
  (into []
    (keep (fn [filename]
            (let [rel  (str skill-dir "/references/" filename)
                  file (io/file dir rel)]
              (when (.isFile file)
                {:path rel
                 :file file
                 :version (reference-file-version file)}))))
    (cons "TESTING_CONVENTIONS.md" reference-files)))

(defn scaffolded-skills
  "Scaffolded spel skills sitting directly under `dir`, one entry per harness target
   whose SKILL.md exists (.opencode, .claude, .agents).

   Each entry includes {:harness-target :path :file :version :references}; every
   existing reference shipped by spel carries its independently checked release
   marker."
  [dir]
  (into []
    (keep (fn [[harness-target {:keys [skill-dir]}]]
            (let [rel  (str skill-dir "/SKILL.md")
                  file (io/file dir rel)]
              (when (.isFile file)
                {:harness-target harness-target
                 :path           rel
                 :file           file
                 :version        (skill-file-version file)
                 :references     (scaffolded-references dir skill-dir)}))))
    (sort-by key harness-targets)))

(defn find-scaffolded-skills
  "Walks up from `start-dir` (default: the process working directory) and returns
   `[root skills]` for the nearest directory that holds a scaffolded skill tree,
   or nil when none is found within `skill-search-depth` levels."
  ([] (find-scaffolded-skills (System/getProperty "user.dir")))
  ([start-dir]
   (loop [^java.io.File dir (some-> start-dir io/file .getAbsoluteFile)
          depth 0]
     (when (and dir (< (long depth) (long skill-search-depth)))
       (let [skills (scaffolded-skills dir)]
         (if (seq skills)
           [dir skills]
           (recur (.getParentFile dir) (inc (long depth)))))))))

(defn skill-drift-warning
  "Warning text when the nearest scaffolded skill tree or any of its generated
   references came from a spel version other than the running one, else nil.

   SKILL.md pins the release in frontmatter; every generated reference pins it in
   a first-line HTML comment. The check reads only that line, even for large API
   and report references. Never throws."
  ([] (skill-drift-warning (System/getProperty "user.dir")))
  ([start-dir]
   (try
     (let [running @spel-version]
       (when-let [[_ skills] (find-scaffolded-skills start-dir)]
         (let [stale (->> skills
                       (mapcat (fn [{:keys [path version references]}]
                                 (cons {:path path :version version} references)))
                       (remove #(= running (:version %)))
                       vec)]
           (when (seq stale)
             (str/join "\n"
               (concat
                 [(str "spel: agent skill is OUT OF SYNC with this spel release (" running ").")]
                 (map (fn [{:keys [path version]}]
                        (str "  " path " was generated from spel "
                          (or version "an unknown version") "."))
                   (take 8 stale))
                 (when (> (count stale) 8)
                   [(str "  ... and " (- (count stale) 8) " more stale references.")])
                 ["  Regenerate it: spel init-agents --force --no-tests"
                  (str "  Silence this check: " skill-check-env "=false")]))))))
     (catch Exception _ nil))))

(defn warn-on-skill-drift!
  "Prints `skill-drift-warning` to stderr, so JSON stdout stays machine-readable.
   Opt out with `SPEL_SKILL_CHECK=false`. Never throws."
  ([] (warn-on-skill-drift! (System/getProperty "user.dir")))
  ([start-dir]
   (try
     (when-not (= "false" (some-> (System/getenv skill-check-env) str/trim str/lower-case))
       (when-let [warning (skill-drift-warning start-dir)]
         (binding [*out* *err*]
           (println warning)
           (flush))))
     (catch Exception _ nil))
   nil))

;; =============================================================================
;; CLI Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parses command-line arguments into a map of options.
   Supports: --dry-run, --force, --ns NS, --harness TARGET, --test-dir DIR, --flavour"
  [args]
  (loop [remaining args
         opts {:dry-run false
               :force false
               :no-tests false
               :flavour "lazytest"
               :ns nil
               :harness "opencode"
               :test-dir "test-e2e"}]
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

          (= "--flavour" arg)
          (recur (drop 2 remaining)
            (assoc opts :flavour (second remaining)))

          (str/starts-with? arg "--flavour=")
          (recur (rest remaining)
            (assoc opts :flavour (subs arg (count "--flavour="))))

          (= "--ns" arg)
          (recur (drop 2 remaining)
            (assoc opts :ns (second remaining)))

          (str/starts-with? arg "--ns=")
          (recur (rest remaining)
            (assoc opts :ns (subs arg (count "--ns="))))

          (#{"--harness" "--loop"} arg)
          (recur (drop 2 remaining)
            (assoc opts :harness (second remaining)))

          (or (str/starts-with? arg "--harness=")
            (str/starts-with? arg "--loop="))
          (recur (rest remaining)
            (assoc opts :harness
              (if (str/starts-with? arg "--harness=")
                (subs arg (count "--harness="))
                (subs arg (count "--loop=")))))

          (= "--test-dir" arg)
          (recur (drop 2 remaining)
            (assoc opts :test-dir (second remaining)))

          (str/starts-with? arg "--test-dir=")
          (recur (rest remaining)
            (assoc opts :test-dir (subs arg (count "--test-dir="))))

          (#{"--help" "-h"} arg)
          (assoc opts :help true)

          :else
          (recur (rest remaining) opts))))))

;; =============================================================================
;; Scaffolding Logic
;; =============================================================================

(defn- scaffold-file
  "Scaffolds a single file from a template.
   Applies frontmatter transformation for non-OpenCode harness targets and release
   stamps every generated reference.
   Returns {:created true/false :skipped true/false :reason string}."
  [resource-path output-path _description _icon opts ns-name harness-target agent-name]
  (let [dry-run (:dry-run opts)
        force (:force opts)
        flavour (:flavour opts "lazytest")]
    (cond
      ;; Check if template exists
      (nil? (read-template resource-path))
      {:created false :skipped true :reason (str "Template not found: " resource-path)}

      ;; Check if file already exists
      (and (file-exists? output-path) (not force))
      {:created false :skipped true :reason "Already exists (use --force to overwrite)"}

      :else
      (let [content (cond-> (-> (read-template resource-path)
                              (process-template ns-name flavour)
                              (transform-agent-template harness-target agent-name))
                      (str/ends-with? resource-path "SKILL.md")
                      (set-skill-compatibility (:compatibility (get harness-targets harness-target)))

                      (str/includes? output-path "/references/")
                      (stamp-reference))
            written? (write-file! output-path content dry-run)]
        (if dry-run
          {:created false :skipped false :dry-run true :reason "(dry-run)"}
          {:created written? :skipped false :reason nil})))))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn- print-banner
  "Prints the initialization banner with the target name."
  [harness-target no-tests]
  (let [desc (:desc (get harness-targets harness-target))]
    (if no-tests
      (println (str "Initializing spel agent for " desc " (no seed test)..."))
      (println (str "Initializing spel agent for " desc "...")))
    (println "")))

(defn- print-help
  "Prints CLI help."
  []
  (println "spel init-agents — Scaffold the spel browser automation agent")
  (println "")
  (println "Usage:")
  (println "  spel init-agents --ns my-app")
  (println "  spel init-agents --ns my-app --harness=claude")
  (println "  spel init-agents --ns my-app --harness=agents")
  (println "  spel init-agents --ns my-app --test-dir test/e2e")
  (println "  spel init-agents --ns my-app --flavour=clojure-test")
  (println "  spel init-agents --ns my-app --no-tests")
  (println "  spel init-agents --ns my-app --dry-run")
  (println "  spel init-agents --ns my-app --force")
  (println "")
  (println "Options:")
  (println "  --harness TARGET  Agent format: opencode (default), claude, agents")
  (println "  --ns NS           Base namespace for generated tests (e.g. my-app → my-app.e2e.seed-test)")
  (println "                    If omitted, derived from the current directory name")
  (println "  --flavour FLAVOUR Test framework: lazytest (default), clojure-test")
  (println "                    lazytest: defdescribe/it/expect from spel.allure, :context fixtures")
  (println "                    clojure-test: deftest/testing/is from clojure.test, use-fixtures")
  (println "  --no-tests        Skip seed test — scaffold agent + skill only.")
  (println "  --test-dir DIR    Root test directory for E2E tests (default: test-e2e)")
  (println "  --dry-run         Show what would be created without writing")
  (println "  --force           Overwrite existing files")
  (println "  -h, --help        Show this help")
  (println "")
  (println "Harness targets:")
  (println "  opencode          .opencode/agents/, .opencode/skills/")
  (println "  claude            .claude/agents/, .claude/skills/")
  (println "  agents            .agents/skills/spel/ (tool-agnostic; agent nested under skill)")
  (println "")
  (println "Scaffolds:")
  (println "  - spel agent (browser automation, testing, bug finding, auto-learnings)")
  (println "  - spel skill (API reference + all reference docs)")
  (println "  - seed test (unless --no-tests)"))

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
  [harness-target test-dir no-tests flavour]
  (println "")
  (if no-tests
    (do
      (if (= "opencode" harness-target)
        (println "Done! Use @spel to get started.")
        (println "Done! Use the spel agent to get started."))
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
      (println "     spel open https://example.org"))
    (let [ct? (= "clojure-test" flavour)]
      (if (= "opencode" harness-target)
        (println "Done! Use @spel to get started.")
        (println "Done! Use the spel agent to get started."))
      (println "")
      (println "Next steps:")
      (println "")
      (println "  1. Install Playwright browsers:")
      (println "     spel install --with-deps chromium")
      (println "")
      (println "  2. Add the :e2e alias to your deps.edn:")
      (println "")
      (if ct?
        (do
          (println (str "     :e2e {:extra-paths [\"" test-dir "\"]"))
          (println (str "           :extra-deps {com.blockether/spel {:mvn/version \"" @spel-version "\"}"))
          (println "                        io.github.cognitect-labs/test-runner")
          (println "                        {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}")
          (println (str "           :main-opts [\"-m\" \"cognitect.test-runner\" \"-d\" \"" test-dir "\"]}")))
        (do
          (println (str "     :e2e {:extra-paths [\"" test-dir "\"]"))
          (println (str "           :extra-deps {com.blockether/spel {:mvn/version \"" @spel-version "\"}}"))
          (println (str "           :main-opts [\"-m\" \"lazytest.main\" \"--dir\" \"" test-dir "\"]}"))))
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
  "CLI entry point. Scaffolds agent + skill for browser automation."
  [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (print-help)

      (not (contains? harness-targets (:harness opts)))
      (do
        (binding [*out* *err*]
          (println (str "Error: Unknown --harness target: " (:harness opts)))
          (println (str "Valid targets: " (str/join ", " (sort (keys harness-targets))))))
        (System/exit 1))

      (not (contains? valid-flavours (:flavour opts)))
      (do
        (binding [*out* *err*]
          (println (str "Error: Unknown --flavour: " (:flavour opts)))
          (println (str "Valid flavours: " (str/join ", " (sort valid-flavours)))))
        (System/exit 1))

      :else
      (let [harness-target (:harness opts)
            no-tests (:no-tests opts)
            flavour (:flavour opts)
            ns-name (or (:ns opts)
                      (do (when-not no-tests
                            (println "Warning: No --ns provided, deriving from directory name.")
                            (println "         Tip: use --ns my-app to set namespace explicitly.")
                            (println ""))
                        (derive-namespace)))
            test-dir (:test-dir opts)]
        (print-banner harness-target no-tests)

        ;; Scaffold agent + skill + references
        (doseq [[resource-path output-path description icon agent-name] (files-to-create harness-target flavour)]
          (let [result (scaffold-file resource-path output-path description icon opts ns-name harness-target agent-name)]
            (print-result icon output-path description result)))

        (when-not no-tests
          (let [seed-ns (str ns-name ".e2e.seed-test")
                seed-path (ns->path test-dir seed-ns)
                seed-result (scaffold-file (seed-template-resource flavour) seed-path "seed test" "+" opts ns-name harness-target nil)]
            (print-result "+" seed-path "seed test" seed-result)))

        (print-footer harness-target test-dir no-tests flavour)))))
