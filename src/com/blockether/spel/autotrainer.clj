(ns com.blockether.spel.autotrainer
  "Bounded autotrainer harness for spel agent training loops.

   Creates run directories, captures helper artifacts against deterministic
   targets, runs supervised OpenCode validation, and supports iterative
   improvement with feedback comparison, keep/revert decisions, and
   convergence detection."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.net HttpURLConnection URL]
   [java.time ZoneOffset ZonedDateTime]
   [java.time.format DateTimeFormatter]))

(def ^:private default-target "https://example.org")
(def ^:private default-validation-target "https://onet.pl")
(def ^:private default-run-root ".sisyphus/autotrainer")
(def ^:private default-init-agents-args
  ["init-agents" "--simplified" "--force" "--learnings" "--no-tests"])
(def ^:private default-opencode-model "zai-coding-plan/glm-5")
(def ^:private timestamp-format
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn target->slug
  "Converts a target URL or host into a filesystem-safe slug."
  [target]
  (let [trimmed (str/trim (or target ""))
        no-scheme (str/replace trimmed #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
        no-query (first (str/split no-scheme #"[/?#]"))
        lowered (str/lower-case no-query)
        dashed (str/replace lowered #"[^a-z0-9]+" "-")
        collapsed (str/replace dashed #"-+" "-")
        trimmed-dashes (str/replace collapsed #"(^-)|(-$)" "")]
    (if (str/blank? trimmed-dashes)
      "target"
      trimmed-dashes)))

(defn timestamp-stamp
  "Returns a UTC timestamp suitable for run directory names."
  []
  (.format ^DateTimeFormatter timestamp-format (ZonedDateTime/now ZoneOffset/UTC)))

(defn session-name
  "Builds a unique daemon session name for a target slug."
  [slug]
  (str "autotrainer-" slug "-" (timestamp-stamp)))

(defn parse-args
  "Parses CLI args for the baseline harness. Unknown args are ignored."
  [args]
  (loop [remaining args
         opts {:target default-target
               :validation-target default-validation-target
               :depth 1
               :max-iterations 50
               :convergence-window 3
               :model "glm-5"
               :opencode-model default-opencode-model
               :opencode-timeout-sec 900
               :preflight-timeout-ms 5000
               :run-root default-run-root
               :refresh false
               :validate false
               :capture true
               :loop false}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= "--target" arg)
          (recur (drop 2 remaining) (assoc opts :target (second remaining)))

          (str/starts-with? arg "--target=")
          (recur (rest remaining) (assoc opts :target (subs arg (count "--target="))))

          (= "--depth" arg)
          (recur (drop 2 remaining) (assoc opts :depth (Long/parseLong (or (second remaining) "1"))))

          (str/starts-with? arg "--depth=")
          (recur (rest remaining) (assoc opts :depth (Long/parseLong (subs arg (count "--depth=")))))

          (= "--validation-target" arg)
          (recur (drop 2 remaining) (assoc opts :validation-target (second remaining)))

          (str/starts-with? arg "--validation-target=")
          (recur (rest remaining) (assoc opts :validation-target (subs arg (count "--validation-target="))))

          (= "--max-iterations" arg)
          (recur (drop 2 remaining) (assoc opts :max-iterations (Long/parseLong (or (second remaining) "50"))))

          (str/starts-with? arg "--max-iterations=")
          (recur (rest remaining) (assoc opts :max-iterations (Long/parseLong (subs arg (count "--max-iterations=")))))

          (= "--model" arg)
          (recur (drop 2 remaining) (assoc opts :model (second remaining)))

          (str/starts-with? arg "--model=")
          (recur (rest remaining) (assoc opts :model (subs arg (count "--model="))))

          (= "--opencode-model" arg)
          (recur (drop 2 remaining) (assoc opts :opencode-model (second remaining)))

          (str/starts-with? arg "--opencode-model=")
          (recur (rest remaining) (assoc opts :opencode-model (subs arg (count "--opencode-model="))))

          (= "--opencode-timeout-sec" arg)
          (recur (drop 2 remaining) (assoc opts :opencode-timeout-sec (Long/parseLong (or (second remaining) "900"))))

          (str/starts-with? arg "--opencode-timeout-sec=")
          (recur (rest remaining) (assoc opts :opencode-timeout-sec (Long/parseLong (subs arg (count "--opencode-timeout-sec=")))))

          (= "--run-root" arg)
          (recur (drop 2 remaining) (assoc opts :run-root (second remaining)))

          (str/starts-with? arg "--run-root=")
          (recur (rest remaining) (assoc opts :run-root (subs arg (count "--run-root="))))

          (= "--no-capture" arg)
          (recur (rest remaining) (assoc opts :capture false))

          (= "--refresh" arg)
          (recur (rest remaining) (assoc opts :refresh true))

          (= "--validate" arg)
          (recur (rest remaining) (assoc opts :validate true))

          (= "--loop" arg)
          (recur (rest remaining) (assoc opts :loop true))

          (= "--convergence-window" arg)
          (recur (drop 2 remaining) (assoc opts :convergence-window (Long/parseLong (or (second remaining) "3"))))

          (str/starts-with? arg "--convergence-window=")
          (recur (rest remaining) (assoc opts :convergence-window (Long/parseLong (subs arg (count "--convergence-window=")))))

          (= "--preflight-timeout-ms" arg)
          (recur (drop 2 remaining) (assoc opts :preflight-timeout-ms (Long/parseLong (or (second remaining) "5000"))))

          (str/starts-with? arg "--preflight-timeout-ms=")
          (recur (rest remaining) (assoc opts :preflight-timeout-ms (Long/parseLong (subs arg (count "--preflight-timeout-ms=")))))

          :else
          (recur (rest remaining) opts))))))

(defn run-dir
  "Builds the per-run directory path."
  [{:keys [run-root target]} stamp]
  (str run-root "/" (target->slug target) "/" stamp))

(defn artifact-paths
  "Returns the expected artifact paths for a run directory."
  [run-dir]
  {:open-json (str run-dir "/artifacts/open.json")
   :audit-json (str run-dir "/artifacts/audit.json")
   :routes-json (str run-dir "/artifacts/routes.json")
   :inspect-json (str run-dir "/artifacts/inspect.json")
   :debug-json (str run-dir "/artifacts/debug.json")
   :overview-json (str run-dir "/artifacts/overview.json")
   :overview-png (str run-dir "/artifacts/overview.png")
   :validation-manifest-json (str run-dir "/validation-001.json")
   :build-log (str run-dir "/logs/build.log")
   :refresh-error-log (str run-dir "/logs/refresh-error.log")
   :init-agents-log (str run-dir "/logs/init-agents.log")
   :opencode-command-txt (str run-dir "/logs/opencode-command.txt")
   :opencode-jsonl (str run-dir "/logs/opencode-run.jsonl")
   :opencode-summary-json (str run-dir "/logs/opencode-summary.json")
   :manifest-json (str run-dir "/iteration-000-baseline.json")
   :hypothesis-md (str run-dir "/hypothesis.md")
   :readme-md (str run-dir "/README.md")})

(defn- ensure-parent-dir!
  [path]
  (let [parent (.getParentFile (io/file path))]
    (when parent
      (.mkdirs parent))))

(defn- spit-json!
  [path data]
  (ensure-parent-dir! path)
  (spit path (json/write-json-str data)))

(defn- write-text!
  [path content]
  (ensure-parent-dir! path)
  (spit path content))

(defn- safe-sh
  [cmd & args]
  (let [{:keys [exit out err]} (apply shell/sh cmd args)]
    (if (zero? (long exit))
      out
      (throw (ex-info (str "Command failed: " cmd)
               {:cmd cmd :args args :exit exit :out out :err err})))))

(defn- run-command!
  [log-path cmd & args]
  (let [{:keys [exit out err] :as result} (apply shell/sh cmd args)
        log-content (str "$ " cmd
                      (when (seq args)
                        (str " " (str/join " " args)))
                      "\n\n"
                      out
                      (when (seq err)
                        (str "\n[stderr]\n" err)))]
    (write-text! log-path log-content)
    (if (zero? (long exit))
      result
      (throw (ex-info (str "Command failed: " cmd)
               {:cmd cmd :args args :exit exit :out out :err err :log-path log-path})))))

(defn- find-spel-bin
  []
  (let [env-bin (System/getenv "SPEL_BIN")
        home-bin (str (System/getProperty "user.home") "/.local/bin/spel")
        target-bin "./target/spel"]
    (cond
      (and env-bin (.exists (io/file env-bin))) env-bin
      (.exists (io/file home-bin)) home-bin
      (.exists (io/file target-bin)) target-bin
      :else (throw (ex-info "Could not find spel binary. Set SPEL_BIN or run make install-local."
                     {:checked [env-bin home-bin target-bin]})))))

(defn hypothesis-template
  "Template for one bounded mutation hypothesis."
  [{:keys [target depth model validation-target max-iterations]}]
  (str "# Hypothesis\n\n"
    "- target: " target "\n"
    "- depth: " depth "\n"
    "- model: " model "\n\n"
    "## Validation target\n\n"
    "- live validation target: " validation-target "\n"
    "- hard iteration cap: " max-iterations "\n\n"
    "## One change\n\n"
    "Describe exactly one prompt/helper/template change for the next iteration.\n\n"
    "## Expected improvement\n\n"
    "Describe the concrete artifact or reliability improvement you expect.\n\n"
    "## Keep/Revert rule\n\n"
    "Keep only if artifacts remain complete and the run is at least as reliable as the baseline.\n"))

(defn run-readme
  "Human-readable instructions stored inside each run directory."
  [{:keys [target depth model refresh validation-target max-iterations]} run-dir artifacts]
  (str "# Example.org trainer run\n\n"
    "This run captures the baseline helper artifacts for a bounded autotrainer loop.\n\n"
    "- target: `" target "`\n"
    "- live validation target: `" validation-target "`\n"
    "- depth: `" depth "`\n"
    "- model: `" model "`\n"
    "- max supervised iterations: `" max-iterations "`\n"
    "- refresh spel before probe: `" refresh "`\n"
    "- run dir: `" run-dir "`\n\n"
    "## Refresh logs\n\n"
    "- `" (:build-log artifacts) "`\n"
    "- `" (:init-agents-log artifacts) "`\n"
    "- `" (:refresh-error-log artifacts) "` (only when refresh fails)\n\n"
    "## OpenCode supervision\n\n"
    "- command file: `" (:opencode-command-txt artifacts) "`\n"
    "- event log: `" (:opencode-jsonl artifacts) "`\n"
    "- parsed summary: `" (:opencode-summary-json artifacts) "`\n"
    "- validation manifest: `" (:validation-manifest-json artifacts) "`\n\n"
    "## Baseline artifacts\n\n"
    "- `" (:audit-json artifacts) "`\n"
    "- `" (:routes-json artifacts) "`\n"
    "- `" (:inspect-json artifacts) "`\n"
    "- `" (:debug-json artifacts) "`\n"
    "- `" (:overview-json artifacts) "`\n"
    "- `" (:overview-png artifacts) "`\n"
    "- `" (:manifest-json artifacts) "`\n\n"
    "## Next step\n\n"
    "1. Edit exactly one scaffolded prompt or agent file under `.opencode/`.\n"
    "2. Re-run this harness against the same target.\n"
    "3. Compare the new manifest to `iteration-000-baseline.json`.\n"
    "4. Use the validation manifest to confirm OpenCode really executed work.\n"
    "5. Promote successful prompt changes back into `resources/.../templates/` later.\n"))

(defn iteration-manifest
  "Builds the baseline iteration manifest from collected metrics."
  [{:keys [target depth model refresh]} artifacts metrics]
  (let [expected [(str (:audit-json artifacts))
                  (str (:routes-json artifacts))
                  (str (:inspect-json artifacts))
                  (str (:debug-json artifacts))
                  (str (:overview-json artifacts))
                  (str (:overview-png artifacts))]
        produced (filterv #(.exists (io/file %)) expected)
        completeness (if (seq expected)
                       (/ (double (count produced)) (double (count expected)))
                       0.0)]
    {:iteration 0
     :target target
     :depth depth
     :model model
     :refresh-performed refresh
     :init-agents-args default-init-agents-args
     :mutation-scope "baseline"
     :changed-files []
     :artifacts-expected expected
     :artifacts-produced produced
     :artifact-completeness-score completeness
     :verify-quick-passed nil
     :targeted-tests-passed nil
     :pages-explored 1
     :routes-found (:routes-found metrics)
     :sections-found (:sections-found metrics)
     :console-issue-count (:console-issue-count metrics)
     :network-failure-count (:network-failure-count metrics)
     :learnings-count 0
     :decision "baseline"
     :summary "Captured baseline helper artifacts for a deterministic training target."}))

(defn validation-prompt
  "Builds the live validation prompt for spel-orchestrator."
  [{:keys [validation-target depth max-iterations]}]
  (str "Validate and stress-test the current spel setup on " validation-target
    " at depth " depth ". Use @spel-orchestrator behavior and actually do work in the OpenCode process. "
    "Focus on awesome helpers, concise skills, minimal subagents, curated refs, and beautiful visualization. "
    "Treat this as supervised validation iteration 1 of at most " max-iterations ". "
    "IMPORTANT PATTERNS: "
    "(1) Use `spel open <url>` then `spel wait --load load` separately — NEVER use --timeout for navigation. "
    "(2) Use `spel fill <ref> <text>` without --timeout — let the default timeout handle it. "
    "(3) ALWAYS close your session with `spel --session $SESSION close` when done — never leave zombie daemons. "
    "(4) Use unique session names: SESSION=autotrainer-<slug>-$(date +%s). "
    "Write machine-readable handoff files under orchestration/, create LEARNINGS.md if needed, and do not claim success unless artifacts exist."))

(defn- parse-json-events
  [raw]
  (->> (str/split-lines raw)
    (keep (fn [line]
            (let [trimmed (str/trim line)]
              (when (and (not (str/blank? trimmed))
                      (str/starts-with? trimmed "{"))
                (try
                  (json/read-json trimmed :key-fn keyword)
                  (catch Exception _ nil))))))
    vec))

(defn- summarize-opencode-events
  [events]
  (let [session-id (some :sessionID (reverse events))
        last-event (last events)
        error-event (some #(when (= "error" (:type %)) %) events)
        message-count (count (filter #(= "message" (:type %)) events))]
    {:session-id session-id
     :event-count (count events)
     :message-count message-count
     :status (cond
               error-event "error"
               last-event "ok"
               :else "no-events")
     :last-event-type (:type last-event)
     :error-name (get-in error-event [:error :name])
     :error-message (or (get-in error-event [:error :data :message])
                      (get-in error-event [:error :message]))}))

(defn- validation-artifacts
  []
  (let [orchestration-dir (io/file "orchestration")
        orchestration-files (if (.exists orchestration-dir)
                              (->> (or (.listFiles orchestration-dir) (into-array java.io.File []))
                                (filter #(.isFile ^java.io.File %))
                                (map #(.getPath ^java.io.File %))
                                sort
                                vec)
                              [])
        learnings-path (when (.exists (io/file "LEARNINGS.md")) "LEARNINGS.md")]
    {:orchestration-files orchestration-files
     :learnings-file learnings-path
     :artifacts-produced (cond-> orchestration-files
                           learnings-path (conj learnings-path))
     :agent-work-confirmed (boolean (or (seq orchestration-files)
                                      (some? learnings-path)))}))

(defn cleanup-sessions!
  "Kills any lingering spel daemon sessions matching the autotrainer- prefix.
   Safe to call even if no sessions exist."
  []
  (try
    (let [spel-bin (find-spel-bin)
          {:keys [out]} (shell/sh spel-bin "session" "list" "--json")
          sessions (try (json/read-json out :key-fn keyword) (catch Exception _ []))]
      (doseq [s sessions
              :let [name (or (:name s) (:session s) "")]
              :when (str/starts-with? (str name) "autotrainer-")]
        (try
          (shell/sh spel-bin "--session" (str name) "close")
          (catch Exception _ nil)))
      (count (filter #(str/starts-with? (str (or (:name %) (:session %) "")) "autotrainer-")
               sessions)))
    (catch Exception _ 0)))

(defn run-opencode-validation!
  "Runs one supervised OpenCode validation iteration and records command/events.
   Always cleans up any lingering autotrainer daemon sessions when done."
  [opts run-dir artifacts]
  (try
    (let [prompt (validation-prompt opts)
          timeout-sec (long (:opencode-timeout-sec opts))
          cmd ["timeout" (str timeout-sec)
               "opencode" "run"
               "--model" (:opencode-model opts)
               "--agent" "spel-orchestrator"
               "--format" "json"
               "--dir" (System/getProperty "user.dir")
               prompt]
          _ (write-text! (:opencode-command-txt artifacts) (str/join " " cmd))
          {:keys [exit out err]} (apply shell/sh cmd)
          raw-output (str out (when (seq err) (str "\n" err)))
          _ (write-text! (:opencode-jsonl artifacts) raw-output)
          events (parse-json-events raw-output)
          produced (validation-artifacts)
          summary (assoc (summarize-opencode-events events)
                    :command cmd
                    :target (:validation-target opts)
                    :depth (:depth opts)
                    :max-iterations (:max-iterations opts)
                    :opencode-timeout-sec timeout-sec
                    :exit-code exit
                    :agent-work-confirmed (:agent-work-confirmed produced)
                    :artifacts-produced (:artifacts-produced produced)
                    :run-dir run-dir)
          _ (spit-json! (:opencode-summary-json artifacts) summary)
          effective-status (cond
                             (= 124 (:exit-code summary)) "timeout"
                             (not (:agent-work-confirmed produced)) "no-artifacts"
                             (= "error" (:status summary)) "error"
                             :else "ok")
          manifest {:iteration 1
                    :kind "validation"
                    :target (:validation-target opts)
                    :depth (:depth opts)
                    :max-iterations (:max-iterations opts)
                    :opencode-timeout-sec timeout-sec
                    :opencode-model (:opencode-model opts)
                    :agent "spel-orchestrator"
                    :prompt prompt
                    :session-id (:session-id summary)
                    :event-count (:event-count summary)
                    :message-count (:message-count summary)
                    :status effective-status
                    :agent-work-confirmed (:agent-work-confirmed produced)
                    :artifacts-produced (:artifacts-produced produced)
                    :error-name (:error-name summary)
                    :error-message (:error-message summary)
                    :command-log (:opencode-command-txt artifacts)
                    :jsonl-log (:opencode-jsonl artifacts)
                    :summary-log (:opencode-summary-json artifacts)}]
      (spit-json! (:validation-manifest-json artifacts) manifest)
      manifest)
    (finally
      (cleanup-sessions!))))

(defn refresh-spel!
  "Builds/install-local and force-reinitializes scaffolded agents so the next
   OpenCode run sees updated files. Writes logs into the run directory."
  [artifacts]
  (try
    (run-command! (:build-log artifacts) "make" "install-local")
    (let [spel-bin (find-spel-bin)]
      (apply run-command! (:init-agents-log artifacts) spel-bin default-init-agents-args))
    (catch Exception e
      (write-text! (:refresh-error-log artifacts)
        (str "Refresh failed: " (.getMessage e) "\n\n" (pr-str (ex-data e)) "\n"))
      (throw e))))

(defn- capture-json-command!
  [spel-bin session args out-path]
  (let [out (apply safe-sh spel-bin (concat ["--json" "--session" session] args))]
    (write-text! out-path out)
    (json/read-json out :key-fn keyword)))

(defn capture-baseline!
  "Captures helper artifacts and writes the baseline manifest for one run."
  [opts run-dir]
  (let [slug (target->slug (:target opts))
        session (session-name slug)
        spel-bin (find-spel-bin)
        artifacts (artifact-paths run-dir)]
    (.mkdirs (io/file (str run-dir "/artifacts")))
    (try
      (capture-json-command! spel-bin session ["open" (:target opts)] (:open-json artifacts))
      (let [audit (capture-json-command! spel-bin session ["audit"] (:audit-json artifacts))
            routes (capture-json-command! spel-bin session ["routes"] (:routes-json artifacts))
            _inspect (capture-json-command! spel-bin session ["inspect"] (:inspect-json artifacts))
            debug (capture-json-command! spel-bin session ["debug"] (:debug-json artifacts))
            _overview (capture-json-command! spel-bin session ["overview" (:overview-png artifacts)] (:overview-json artifacts))
            metrics {:routes-found (long (or (:count routes) 0))
                     :sections-found (long (count (or (:sections audit) [])))
                     :console-issue-count (long (+ (count (or (:console_errors debug) []))
                                                  (count (or (:page_errors debug) []))))
                     :network-failure-count (long (count (or (:failed_requests debug) [])))}
            manifest (iteration-manifest opts artifacts metrics)]
        (spit-json! (:manifest-json artifacts) manifest)
        (write-text! (:hypothesis-md artifacts) (hypothesis-template opts))
        (write-text! (:readme-md artifacts) (run-readme opts run-dir artifacts))
        {:run-dir run-dir
         :artifacts artifacts
         :manifest manifest})
      (finally
        (try
          (safe-sh spel-bin "--session" session "close")
          (catch Exception _ nil))))))

(defn preflight-check!
  "Verifies that a target URL is reachable via HTTP HEAD request.
   Returns {:reachable true :status <code> :latency-ms <ms>} on success,
   or {:reachable false :error <message>} on failure.
   Throws ex-info when :reachable is false so callers can short-circuit."
  [url timeout-ms]
  (let [start (System/nanoTime)]
    (try
      (let [conn (doto ^HttpURLConnection (.openConnection (URL. ^String url))
                   (.setRequestMethod "HEAD")
                   (.setConnectTimeout (int timeout-ms))
                   (.setReadTimeout (int timeout-ms))
                   (.setInstanceFollowRedirects true)
                   (.setRequestProperty "User-Agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))
            status (.getResponseCode conn)
            elapsed-ms (/ (double (- (System/nanoTime) start)) 1e6)]
        (.disconnect conn)
        (if (<= 200 (long status) 399)
          {:reachable true :status status :latency-ms elapsed-ms :url url}
          (let [result {:reachable false :status status :latency-ms elapsed-ms :url url
                        :error (str "HTTP " status " from " url)}]
            (throw (ex-info (str "Pre-flight failed: HTTP " status) result)))))
      (catch java.net.SocketTimeoutException _
        (throw (ex-info (str "Pre-flight timeout after " timeout-ms "ms: " url)
                 {:reachable false :url url :error (str "Timeout after " timeout-ms "ms")})))
      (catch java.net.UnknownHostException _
        (throw (ex-info (str "Pre-flight DNS failure: " url)
                 {:reachable false :url url :error "DNS resolution failed"})))
      (catch Exception e
        (throw (ex-info (str "Pre-flight failed: " (.getMessage e))
                 {:reachable false :url url :error (.getMessage e)}))))))

(defn compare-manifests
  "Computes a structured delta between two iteration manifests.
   Returns a map with improvement signals for the keep/revert decision.
   prev-manifest and curr-manifest are maps as produced by `iteration-manifest`."
  [prev-manifest curr-manifest]
  (let [prev-completeness (double (or (:artifact-completeness-score prev-manifest) 0.0))
        curr-completeness (double (or (:artifact-completeness-score curr-manifest) 0.0))
        prev-artifacts (long (count (or (:artifacts-produced prev-manifest) [])))
        curr-artifacts (long (count (or (:artifacts-produced curr-manifest) [])))
        prev-routes (long (or (:routes-found prev-manifest) 0))
        curr-routes (long (or (:routes-found curr-manifest) 0))
        prev-sections (long (or (:sections-found prev-manifest) 0))
        curr-sections (long (or (:sections-found curr-manifest) 0))
        prev-console-issues (long (or (:console-issue-count prev-manifest) 0))
        curr-console-issues (long (or (:console-issue-count curr-manifest) 0))
        prev-net-failures (long (or (:network-failure-count prev-manifest) 0))
        curr-net-failures (long (or (:network-failure-count curr-manifest) 0))
        completeness-delta (- curr-completeness prev-completeness)
        artifact-delta (- curr-artifacts prev-artifacts)
        routes-delta (- curr-routes prev-routes)
        sections-delta (- curr-sections prev-sections)
        console-delta (- curr-console-issues prev-console-issues)
        network-delta (- curr-net-failures prev-net-failures)
        ;; improvement = more artifacts/routes/sections, fewer errors
        improved? (and (>= curr-completeness prev-completeness)
                    (>= curr-artifacts prev-artifacts)
                    (<= curr-console-issues prev-console-issues)
                    (<= curr-net-failures prev-net-failures)
                    (or (> curr-completeness prev-completeness)
                      (> curr-artifacts prev-artifacts)
                      (> curr-routes prev-routes)
                      (> curr-sections prev-sections)
                      (< curr-console-issues prev-console-issues)
                      (< curr-net-failures prev-net-failures)))
        regressed? (or (< curr-completeness prev-completeness)
                     (< curr-artifacts prev-artifacts)
                     (> curr-console-issues prev-console-issues)
                     (> curr-net-failures prev-net-failures))]
    {:improved improved?
     :regressed regressed?
     :stable (and (not improved?) (not regressed?))
     :completeness-delta completeness-delta
     :artifact-delta artifact-delta
     :routes-delta routes-delta
     :sections-delta sections-delta
     :console-issue-delta console-delta
     :network-failure-delta network-delta
     :prev-iteration (or (:iteration prev-manifest) 0)
     :curr-iteration (or (:iteration curr-manifest) 0)}))

(defn keep-or-revert-decision
  "Decides whether to keep or revert based on a manifest comparison delta.
   Returns :keep, :revert, or :keep-stable.
   - :keep — improved (more artifacts, fewer errors, better completeness)
   - :keep-stable — no regression, no improvement (neutral)
   - :revert — regressed (fewer artifacts, more errors, worse completeness)"
  [delta]
  (cond
    (:regressed delta) :revert
    (:improved delta) :keep
    :else :keep-stable))

(defn keep-or-revert!
  "Executes the keep/revert decision. On :revert, runs git checkout on changed files.
   Returns the decision keyword (:keep, :keep-stable, :revert)."
  [delta changed-files]
  (let [decision (keep-or-revert-decision delta)]
    (when (and (= :revert decision) (seq changed-files))
      (doseq [f changed-files]
        (try
          (shell/sh "git" "checkout" "--" f)
          (catch Exception _ nil))))
    decision))

(defn converged?
  "Returns true when the last `window` iterations show no improvement.
   `history` is a vector of delta maps from `compare-manifests`."
  [history window]
  (let [w (long window)
        n (long (count history))]
    (and (>= n w)
      (every? #(not (:improved %)) (take-last w history)))))

(defn- iteration-run-dir
  "Builds the run directory path for iteration N."
  [run-root slug iteration-num]
  (str run-root "/" slug "/iteration-" (format "%03d" (int iteration-num))))

(defn- iteration-artifact-paths
  "Returns artifact paths for an iteration run directory."
  [iter-run-dir iteration-num]
  (let [base (artifact-paths iter-run-dir)]
    (assoc base
      :manifest-json (str iter-run-dir "/iteration-" (format "%03d" (int iteration-num)) "-manifest.json")
      :validation-manifest-json (str iter-run-dir "/validation-" (format "%03d" (int iteration-num)) ".json"))))

(defn run-iteration!
  "Runs a single iteration: refresh, preflight, capture baseline, validate.
   Returns {:iteration N :run-dir ... :manifest ... :validation ... :delta ... :decision ...}"
  [opts iteration-num prev-manifest]
  (let [slug (target->slug (:target opts))
        iter-dir (iteration-run-dir (:run-root opts) slug iteration-num)
        artifacts (iteration-artifact-paths iter-dir iteration-num)]
    (.mkdirs (io/file iter-dir))
    ;; Refresh spel binary + agents if requested
    (when (:refresh opts)
      (refresh-spel! artifacts))
    ;; Pre-flight check on validation target
    (when (:validate opts)
      (preflight-check! (:validation-target opts)
        (long (:preflight-timeout-ms opts))))
    ;; Capture baseline artifacts
    (let [baseline-result (when (:capture opts)
                            (capture-baseline! opts iter-dir))
          manifest (or (:manifest baseline-result)
                     {:iteration iteration-num
                      :target (:target opts)
                      :depth (:depth opts)
                      :model (:model opts)
                      :artifacts-produced []
                      :artifact-completeness-score 0.0
                      :routes-found 0
                      :sections-found 0
                      :console-issue-count 0
                      :network-failure-count 0})
          ;; Set correct iteration number
          manifest (assoc manifest :iteration iteration-num)
          ;; Run validation if requested
          validation (when (:validate opts)
                       (run-opencode-validation! opts iter-dir artifacts))
          ;; Compare with previous manifest
          delta (when prev-manifest
                  (compare-manifests prev-manifest manifest))
          ;; Keep/revert decision
          decision (if delta
                     (keep-or-revert-decision delta)
                     :keep)]
      {:iteration iteration-num
       :run-dir iter-dir
       :manifest manifest
       :validation validation
       :delta delta
       :decision decision})))

(defn run-loop!
  "Runs the full autotrainer loop with bounded iterations, feedback,
   keep/revert, and convergence detection.
   Returns a map with :iterations, :total-iterations, :reason, :converged."
  [opts]
  (let [max-iters (long (:max-iterations opts))
        conv-window (long (:convergence-window opts))]
    (loop [iteration 0
           prev-manifest nil
           history []
           results []]
      (if (>= iteration max-iters)
        {:iterations results
         :total-iterations (count results)
         :reason :max-iterations-reached
         :converged false}
        (let [result (try
                       (run-iteration! opts iteration prev-manifest)
                       (catch Exception e
                         {:iteration iteration
                          :error (.getMessage e)
                          :error-data (ex-data e)
                          :decision :error}))
              delta (:delta result)
              new-history (if delta (conj history delta) history)
              new-results (conj results result)]
          ;; Check convergence
          (if (converged? new-history conv-window)
            {:iterations new-results
             :total-iterations (count new-results)
             :reason :converged
             :converged true
             :convergence-window conv-window}
            ;; Check if error — stop on 3 consecutive errors
            (let [recent-errors (count (take-while #(= :error (:decision %))
                                         (reverse new-results)))]
              (if (>= recent-errors 3)
                {:iterations new-results
                 :total-iterations (count new-results)
                 :reason :consecutive-errors
                 :converged false}
                ;; Continue to next iteration
                (recur (inc iteration)
                  (or (:manifest result) prev-manifest)
                  new-history
                  new-results)))))))))

(defn bootstrap-run!
  "Creates a run directory and optionally captures the baseline artifacts."
  [opts]
  (let [stamp (timestamp-stamp)
        run-dir (run-dir opts stamp)
        artifacts (artifact-paths run-dir)]
    (.mkdirs (io/file run-dir))
    (when (:refresh opts)
      (refresh-spel! artifacts))
    (write-text! (:hypothesis-md artifacts) (hypothesis-template opts))
    (write-text! (:readme-md artifacts) (run-readme opts run-dir artifacts))
    (let [{:keys [manifest] :as result}
          (if (:capture opts)
            (capture-baseline! opts run-dir)
            {:run-dir run-dir
             :artifacts artifacts
             :manifest nil})
          validation (when (:validate opts)
                       (run-opencode-validation! opts run-dir artifacts))]
      (assoc result :validation validation))))

(defn -main
  "CLI entrypoint for autotrainer. Supports single-shot (default) and --loop mode."
  [& args]
  (let [opts (parse-args args)]
    (if (:loop opts)
      ;; Loop mode: run iterative autotrainer
      (let [result (run-loop! opts)]
        (println (json/write-json-str
                   {:mode "loop"
                    :target (:target opts)
                    :validation_target (:validation-target opts)
                    :max_iterations (:max-iterations opts)
                    :convergence_window (:convergence-window opts)
                    :total_iterations (:total-iterations result)
                    :reason (name (:reason result))
                    :converged (:converged result)
                    :iterations (mapv (fn [r]
                                        (cond-> {:iteration (:iteration r)
                                                 :decision (some-> (:decision r) name)}
                                          (:run-dir r) (assoc :run_dir (:run-dir r))
                                          (:error r) (assoc :error (:error r))
                                          (:delta r) (assoc :delta (:delta r))))
                                  (:iterations result))})))
      ;; Single-shot mode: bootstrap one run
      (let [{:keys [run-dir manifest validation]} (bootstrap-run! opts)]
        (println (json/write-json-str
                   (cond-> {:mode "single"
                            :run_dir run-dir
                            :target (:target opts)
                            :validation_target (:validation-target opts)
                            :depth (:depth opts)
                            :max_iterations (:max-iterations opts)
                            :model (:model opts)
                            :opencode_model (:opencode-model opts)
                            :opencode_timeout_sec (:opencode-timeout-sec opts)
                            :refresh (:refresh opts)
                            :validate (:validate opts)
                            :capture (:capture opts)}
                     manifest (assoc :manifest manifest)
                     validation (assoc :validation validation))))))))
