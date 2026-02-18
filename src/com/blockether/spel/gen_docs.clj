(ns com.blockether.spel.gen-docs
  "Generates API reference markdown from source code introspection.

   Three categories of API docs:
   1. Library API — public vars from all spel namespaces
   2. SCI eval API — functions available in `spel --eval` mode
   3. CLI commands — commands available via the `spel` binary

   Usage:
     clojure -T:build gen-docs
     make gen-docs"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; =============================================================================
;; Library API Generation
;; =============================================================================

(def ^:private library-namespaces
  "Namespaces to document, in display order.
   Each entry: [ns-symbol short-name description]"
  [['com.blockether.spel.core       "core"       "Lifecycle, browser, context, page"]
   ['com.blockether.spel.page       "page"       "Navigation, locators, content, events"]
   ['com.blockether.spel.locator    "locator"    "Element interactions"]
   ['com.blockether.spel.assertions "assertions" "Playwright assertions"]
   ['com.blockether.spel.frame      "frame"      "Frame/iframe operations"]
   ['com.blockether.spel.input      "input"      "Keyboard, mouse, touchscreen"]
   ['com.blockether.spel.network    "network"    "Request/response/routing"]
   ['com.blockether.spel.api        "api"        "REST API testing"]
   ['com.blockether.spel.allure     "allure"     "Allure test reporting"]
   ['com.blockether.spel.snapshot   "snapshot"   "Accessibility snapshots"]
   ['com.blockether.spel.annotate   "annotate"   "Page annotation overlays"]
   ['com.blockether.spel.codegen    "codegen"    "JSONL to Clojure transformer"]
   ['com.blockether.spel.util       "util"       "Dialog, download, console, CDP, clock, tracing, video, workers"]
   ['com.blockether.spel.options    "options"    "Java option builders (80+)"]
   ['com.blockether.spel.data       "data"       "Datafy protocols"]])

(defn- first-doc-line
  "Extracts the first non-blank line from a docstring."
  [doc]
  (when doc
    (let [line (first (remove str/blank? (str/split-lines doc)))]
      (when line (str/trim line)))))

(defn- escape-md
  "Escapes pipe characters for markdown tables."
  [s]
  (when s (str/replace s "|" "\\|")))

(defn- format-arglists
  "Formats arglists for display. Multiple arities shown as a/b/c."
  [arglists]
  (when (seq arglists)
    (str/join " | " (map pr-str arglists))))

(defn- var-entry
  "Extracts documentation entry from a var."
  [v]
  (let [m (meta v)]
    (when (:arglists m)
      {:name (str (:name m))
       :arglists (:arglists m)
       :doc (first-doc-line (:doc m))
       :macro? (:macro m)})))

(defn- ns-api-table
  "Generates a markdown table for a single namespace."
  [ns-sym short-name description]
  (require ns-sym)
  (let [publics (ns-publics (find-ns ns-sym))
        entries (->> (vals publics)
                  (keep var-entry)
                  (sort-by :name))]
    (when (seq entries)
      (str "### `" short-name "` — " description "\n\n"
        "| Function | Args | Description |\n"
        "|----------|------|-------------|\n"
        (str/join "\n"
          (for [{:keys [name arglists doc macro?]} entries]
            (str "| "
              (if macro? (str "_(macro)_ `" name "`") (str "`" name "`"))
              " | "
              (escape-md (format-arglists arglists))
              " | "
              (escape-md (or doc ""))
              " |")))
        "\n"))))

(defn generate-library-api
  "Generates markdown for all library namespace API tables."
  []
  (str "## Library API Reference\n\n"
    "Auto-generated from source code. Each namespace lists public functions with args and description.\n\n"
    (str/join "\n"
      (for [[ns-sym short-name desc] library-namespaces]
        (try
          (ns-api-table ns-sym short-name desc)
          (catch Exception e
            (str "### `" short-name "` — " desc "\n\n"
              "_Failed to load: " (.getMessage e) "_\n\n")))))))

;; =============================================================================
;; SCI Eval API Generation
;; =============================================================================

(def ^:private sci-namespaces
  "SCI namespace registrations to document.
   Each entry: [sci-ns-name section-in-sci-env description]
   The actual function mappings are extracted from sci_env.clj source."
  [["spel"     "Simplified API with implicit page (lifecycle, navigation, actions, assertions)"]
   ["snapshot" "Accessibility snapshot capture and ref resolution"]
   ["annotate" "Page annotation overlays"]
   ["input"    "Keyboard, mouse, touchscreen (explicit device arg)"]
   ["frame"    "Frame and FrameLocator operations (explicit Frame arg)"]
   ["net"      "Network request/response/route (explicit object arg)"]
   ["loc"      "Raw locator operations (explicit Locator arg)"]
   ["assert"   "Assertion functions (explicit assertion object arg)"]
   ["core"     "Browser lifecycle utilities and resource management"]])

(def ^:private alias->full-ns
  "Maps require aliases used in sci_env.clj to full namespace names."
  {"input"    "com.blockether.spel.input"
   "net"      "com.blockether.spel.network"
   "frame"    "com.blockether.spel.frame"
   "locator"  "com.blockether.spel.locator"
   "assert"   "com.blockether.spel.assertions"
   "core"     "com.blockether.spel.core"
   "page"     "com.blockether.spel.page"
   "snapshot" "com.blockether.spel.snapshot"
   "annotate" "com.blockether.spel.annotate"
   "allure"   "com.blockether.spel.allure"})

(defn- resolve-qualified-fn
  "Resolves an alias-qualified function name (e.g. 'page/navigate') to its var."
  [qualified-name]
  (when (str/includes? qualified-name "/")
    (let [[ns-alias fn-part] (str/split qualified-name #"/" 2)
          full-ns (or (get alias->full-ns ns-alias)
                    (str "com.blockether.spel." ns-alias))]
      (try
        (require (symbol full-ns))
        (ns-resolve (find-ns (symbol full-ns)) (symbol fn-part))
        (catch Exception _ nil)))))

(defn- resolve-backing-var
  "Resolves a backing function reference to its var for metadata extraction.
   Handles both local refs (sci-click) and namespaced refs (input/key-press)."
  [backing-name sci-env-publics]
  (let [backing-sym (symbol backing-name)]
    (or
      ;; Local var in sci-env namespace
      (get sci-env-publics backing-sym)
      ;; Namespaced ref like input/key-press, net/response-url, etc.
      (resolve-qualified-fn backing-name))))

(defn- extract-library-fn-from-source
  "Given a sci-env backing function name (e.g. 'sci-click'), parses its
   source in sci_env.clj to find the first library function it delegates to.
   
   Looks for qualified calls like page/navigate, locator/click, etc.
   Returns the resolved var or nil."
  [backing-name src]
  (let [;; Find the defn form for this function
        defn-marker (str "(defn " backing-name)
        defn-start (str/index-of src defn-marker)]
    (when defn-start
      (let [;; Find the end: next top-level (defn or (def at column 0, or EOF
            defn-end (or (str/index-of src "\n(def" (+ (long defn-start) 10))
                       (count src))
            defn-body (subs src defn-start defn-end)
            ;; Find first qualified library function call in the body
            ;; Match: alias/fn-name where alias is a known spel namespace
            lib-fn-pattern (re-pattern
                             (str "(?:"
                               (str/join "|" (keys alias->full-ns))
                               ")/([\\w\\-\\!\\?]+)"))
            match (re-find lib-fn-pattern defn-body)]
        (when match
          (resolve-qualified-fn (first match)))))))

(defn- parse-sci-registrations
  "Parses SCI function registrations from sci_env.clj source.
   
   Strategy: Find each ';; nsname/ —' section comment, then scan from that 
   position to the next section comment (or end of create-sci-ctx) to collect
   all ['exposed-name backing-fn] pairs.
   
   Returns a map of {ns-name [{:exposed sym :backing-var var} ...]}"
  []
  (require 'com.blockether.spel.sci-env)
  (let [sci-env-ns (find-ns 'com.blockether.spel.sci-env)
        publics (ns-publics sci-env-ns)
        src (slurp (io/resource "com/blockether/spel/sci_env.clj"))
        ;; Find all section markers: ;; name/ — description
        ;; These appear between ===== separator lines
        ns-markers (vec (re-seq #"(?m);;\s+([\w\-]+)/\s+\u2014" src))
        ;; Get byte positions of each marker
        marker-positions (loop [remaining ns-markers
                                start-pos 0
                                result []]
                           (if (empty? remaining)
                             result
                             (let [[full-match ns-prefix] (first remaining)
                                   pos (str/index-of src full-match start-pos)]
                               (recur (rest remaining)
                                 (if pos (+ (long pos) (count full-match)) start-pos)
                                 (if pos
                                   (conj result {:ns-prefix ns-prefix :pos pos})
                                   result)))))
        ;; For each marker, extract the text from its position to the next marker
        ;; and parse ['sym fn] pairs from it
        pair-pattern #"\['([\w\-\!\?\$\*]+)\s+([\w\-\!\?\.\$/]+)\]"]
    (reduce
      (fn [acc i]
        (let [i (long i)
              {:keys [ns-prefix pos]} (nth marker-positions i)
              end-pos (if (< (inc i) (count marker-positions))
                        (long (:pos (nth marker-positions (inc i))))
                        (count src))
              section-text (subs src pos end-pos)
              pairs (re-seq pair-pattern section-text)
              entries (vec
                        (for [[_ exposed-name backing-name] pairs]
                          {:exposed exposed-name
                           :backing-var (resolve-backing-var backing-name publics)
                           :backing-name backing-name}))]
          (if (seq entries)
            (assoc acc ns-prefix entries)
            acc)))
      {}
      (range (count marker-positions)))))

(defn- sci-var-entry
  "Extracts SCI function documentation from its backing var.
   When the backing var has no docstring (common for sci-* wrappers),
   follows through to the underlying library function and uses its docstring."
  [{:keys [exposed backing-var backing-name]} src]
  (if backing-var
    (let [m (meta backing-var)
          doc (first-doc-line (:doc m))
          ;; If no docstring on the wrapper, try the underlying library function
          resolved-doc (or doc
                         (when (and (not doc) backing-name src)
                           (when-let [lib-var (extract-library-fn-from-source backing-name src)]
                             (first-doc-line (:doc (meta lib-var))))))]
      {:name exposed
       :arglists (:arglists m)
       :doc resolved-doc})
    {:name exposed
     :arglists nil
     :doc nil}))

(defn- sci-ns-table
  "Generates a markdown table for a single SCI namespace."
  [ns-name description entries src]
  (let [docs (->> entries
               (map #(sci-var-entry % src))
               (sort-by :name))]
    (when (seq docs)
      (str "### `" ns-name "/` — " description "\n\n"
        "| Function | Args | Description |\n"
        "|----------|------|-------------|\n"
        (str/join "\n"
          (for [{:keys [name arglists doc]} docs]
            (str "| `" ns-name "/" name "` | "
              (escape-md (or (format-arglists arglists) ""))
              " | "
              (escape-md (or doc ""))
              " |")))
        "\n"))))

(defn generate-sci-api
  "Generates markdown for SCI eval API tables."
  []
  (let [registrations (parse-sci-registrations)
        src (slurp (io/resource "com/blockether/spel/sci_env.clj"))]
    (str "## SCI Eval API Reference (`spel --eval`)\n\n"
      "Auto-generated from SCI namespace registrations. "
      "All functions are available in `spel --eval` mode without JVM startup.\n\n"
      (str/join "\n"
        (for [[ns-name description] sci-namespaces
              :let [entries (get registrations ns-name)]
              :when (seq entries)]
          (sci-ns-table ns-name description entries src))))))

;; =============================================================================
;; CLI Commands Generation
;; =============================================================================

(defn- parse-cli-help
  "Parses CLI help text from native.clj source to extract commands.
   Returns sections of [{:section name :commands [{:cmd :desc}]}]"
  []
  (let [src (slurp (io/file "src/com/blockether/spel/native.clj"))
        ;; Extract all println lines from print-help
        help-start (str/index-of src "(defn- print-help")
        help-end (when help-start
                   ;; Find matching closing paren — scan for next top-level defn
                   (str/index-of src "\n(def" (+ (long help-start) 10)))
        help-body (when (and help-start help-end)
                    (subs src help-start help-end))
        lines (when help-body
                (->> (str/split-lines help-body)
                  (keep (fn [line]
                          (when-let [m (re-find #"println\s+\"(.+)\"" line)]
                            (second m))))
                  ;; Remove the header line and empty-ish lines
                  (remove #(re-matches #"spel .*" %))
                  (remove str/blank?)))]
    ;; Parse into sections
    (loop [remaining lines
           current-section nil
           sections []
           commands []]
      (if (empty? remaining)
        ;; Flush last section
        (if current-section
          (conj sections {:section current-section :commands commands})
          sections)
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (cond
            ;; Section header (no leading spaces, ends with :)
            (re-matches #"[A-Z][^:]+:" line)
            (let [new-sections (if current-section
                                 (conj sections {:section current-section :commands commands})
                                 sections)]
              (recur rest-lines (str/replace line #":$" "") new-sections []))

            ;; Command line (leading spaces, command + description)
            (re-matches #"\s+\S.*" line)
            (let [trimmed (str/trim line)
                  ;; Split on 2+ spaces (command  description)
                  parts (str/split trimmed #"\s{2,}" 2)]
              (if (= 2 (count parts))
                (recur rest-lines current-section sections
                  (conj commands {:cmd (first parts) :desc (second parts)}))
                (recur rest-lines current-section sections commands)))

            :else
            (recur rest-lines current-section sections commands)))))))

(defn generate-cli-commands
  "Generates markdown for CLI command tables."
  []
  (let [sections (parse-cli-help)]
    (str "## CLI Commands Reference (`spel`)\n\n"
      "Auto-generated from CLI help text. Run `spel --help` for the full reference.\n\n"
      (str/join "\n"
        (for [{:keys [section commands]} sections
              :when (seq commands)]
          (str "### " section "\n\n"
            "| Command | Description |\n"
            "|---------|-------------|\n"
            (str/join "\n"
              (for [{:keys [cmd desc]} commands]
                (str "| `" (escape-md cmd) "` | " (escape-md desc) " |")))
            "\n"))))))

;; =============================================================================
;; SKILL.md Generation
;; =============================================================================

(def ^:private template-input-path
  "SKILL.md.template contains {{placeholders}} — never overwritten."
  "resources/com/blockether/spel/templates/skills/spel/SKILL.md.template")

(def ^:private template-output-path
  "SKILL.md is the generated output — always regenerated from .template."
  "resources/com/blockether/spel/templates/skills/spel/SKILL.md")

(defn generate-skill-md
  "Reads the SKILL.md.template, replaces {{library-api}}, {{sci-api}},
   and {{cli-commands}} placeholders with generated content, writes to SKILL.md."
  []
  (let [template (slurp (io/file template-input-path))
        library-api (generate-library-api)
        sci-api (generate-sci-api)
        cli-commands (generate-cli-commands)
        result (-> template
                 (str/replace "{{library-api}}" library-api)
                 (str/replace "{{sci-api}}" sci-api)
                 (str/replace "{{cli-commands}}" cli-commands))]
    (spit (io/file template-output-path) result)
    (println "Generated SKILL.md with:")
    (println "  - Library API:" (count (re-seq #"\| `" library-api)) "functions")
    (println "  - SCI eval API:" (count (re-seq #"\| `" sci-api)) "functions")
    (println "  - CLI commands:" (count (re-seq #"\| `" cli-commands)) "commands")
    (println "  Written to:" template-output-path)))

(defn -main
  "CLI entry point."
  [& _args]
  (generate-skill-md))
