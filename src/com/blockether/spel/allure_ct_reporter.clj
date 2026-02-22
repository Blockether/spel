(ns com.blockether.spel.allure-ct-reporter
  "Global clojure.test Allure reporter via defmethod hooks.

   Activated by system property or env var:

     -Dallure.clojure-test.enabled=true
     ALLURE_CLOJURE_TEST_ENABLED=true

   Once activated, ALL clojure.test runs automatically produce Allure
   results — works with any test runner (Kaocha, Cognitect test-runner,
   plain clojure.test/run-tests, REPL).

   The defmethod overrides chain the original clojure.test handlers so
   standard test output is preserved. Our handlers run additionally when
   enabled. When disabled (default), zero overhead — just a nil check.

   In test namespaces:

     (ns my-app.test
       (:require
        [clojure.test :refer [deftest testing is use-fixtures]]
        [com.blockether.spel.allure :as allure]
        [com.blockether.spel.test-fixtures
         :refer [*page* with-playwright with-browser with-traced-page ct-fixture]]))

     (use-fixtures :once (ct-fixture with-playwright) (ct-fixture with-browser))
     (use-fixtures :each (ct-fixture with-traced-page))

     (deftest my-test
       (allure/epic \"My Epic\")
       (testing \"something\"
         (is (= 1 1))))

   `with-allure-context` is auto-injected as the outermost :each fixture
   for every namespace — test files never reference it.

   Configuration (system properties / env vars):

   | Property                         | Env Var                        | Default          |
   |----------------------------------|--------------------------------|------------------|
   | allure.clojure-test.enabled      | ALLURE_CLOJURE_TEST_ENABLED    | false            |
   | allure.clojure-test.output       | ALLURE_CLOJURE_TEST_OUTPUT     | allure-results   |
   | allure.clojure-test.report       | ALLURE_CLOJURE_TEST_REPORT     | true             |
   | allure.clojure-test.clean        | ALLURE_CLOJURE_TEST_CLEAN      | true             |"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as ct]
   [com.blockether.spel.allure :as allure])
  (:import
   [java.io File PrintWriter StringWriter]
   [java.net InetAddress]
   [java.security MessageDigest]
   [java.util UUID]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn enabled?
  "Check if the clojure.test Allure reporter is enabled."
  []
  (Boolean/parseBoolean
    (or (System/getProperty "allure.clojure-test.enabled")
      (System/getenv "ALLURE_CLOJURE_TEST_ENABLED")
      "false")))

(defn- output-dir-path
  ^String []
  (or (System/getProperty "allure.clojure-test.output")
    (System/getenv "ALLURE_CLOJURE_TEST_OUTPUT")
    "allure-results"))

(defn- report?
  "Whether to generate HTML report on summary."
  []
  (Boolean/parseBoolean
    (or (System/getProperty "allure.clojure-test.report")
      (System/getenv "ALLURE_CLOJURE_TEST_REPORT")
      "true")))

(defn- clean?
  "Whether to clean output dir on start."
  []
  (Boolean/parseBoolean
    (or (System/getProperty "allure.clojure-test.clean")
      (System/getenv "ALLURE_CLOJURE_TEST_CLEAN")
      "true")))

;; =============================================================================
;; Shared Utilities
;; =============================================================================

(defn- hostname
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
       (catch Exception _ "localhost")))

(defn- uuid
  ^String []
  (str (UUID/randomUUID)))

(defn- md5-hex
  ^String [^String s]
  (let [md (MessageDigest/getInstance "MD5")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn- stacktrace-str
  ^String [^Throwable t]
  (when t
    (let [sw (StringWriter.)
          pw (PrintWriter. sw)]
      (.printStackTrace t pw)
      (str sw))))

(defn- json-escape
  ^String [^String s]
  (-> s
    (str/replace "\\" "\\\\")
    (str/replace "\"" "\\\"")
    (str/replace "\n" "\\n")
    (str/replace "\r" "\\r")
    (str/replace "\t" "\\t")))

(defn- ->json-pretty
  ^String [v]
  (let [indent (fn indent [v depth]
                 (let [depth (long depth)
                       pad (apply str (repeat (* depth 2) " "))
                       pad1 (apply str (repeat (* (inc depth) 2) " "))]
                   (cond
                     (nil? v)     "null"
                     (string? v)  (str "\"" (json-escape v) "\"")
                     (number? v)  (str v)
                     (boolean? v) (if v "true" "false")
                     (keyword? v) (indent (name v) depth)

                     (map? v)
                     (if (empty? v)
                       "{}"
                       (str "{\n"
                         (->> v
                           (map (fn [[k val]]
                                  (str pad1
                                    (indent (if (keyword? k) (name k) (str k)) (inc depth))
                                    ": "
                                    (indent val (inc depth)))))
                           (str/join ",\n"))
                         "\n" pad "}"))

                     (sequential? v)
                     (if (empty? v)
                       "[]"
                       (str "[\n"
                         (->> v
                           (map (fn [item] (str pad1 (indent item (inc depth)))))
                           (str/join ",\n"))
                         "\n" pad "]"))

                     :else (indent (str v) depth))))]
    (indent v 0)))

(defn- write-attachment!
  [output-dir content att-name]
  (when (and content (not (str/blank? content)))
    (let [att-uuid (uuid)
          filename (str att-uuid "-attachment.txt")
          att-file (io/file output-dir filename)]
      (spit att-file content)
      {:name att-name :source filename :type "text/plain"})))

(defn- copy-file-attachment!
  [^File output-dir ^String source-path att-name ^String mime-type ^String ext]
  (let [src (io/file source-path)]
    (when (and (.exists src) (pos? (.length src)))
      (let [att-uuid (uuid)
            filename (str att-uuid "-attachment" ext)
            dest     (io/file output-dir filename)]
        (io/copy src dest)
        {:name att-name :source filename :type mime-type}))))

(defn- ns-package
  ^String [^String ns-name]
  (let [idx (.lastIndexOf ns-name ".")]
    (if (pos? idx) (subs ns-name 0 idx) "")))

(defn- common-segment-prefix
  "Longest common dot-separated prefix of a collection of package strings.
   E.g. [\"com.blockether.spel\" \"com.blockether.spel.ct\"] => \"com.blockether.spel\""
  ^String [packages]
  (let [packages (vec (distinct (remove str/blank? packages)))]
    (when (seq packages)
      (if (= 1 (count packages))
        (first packages)
        (let [segments (mapv #(str/split % #"\.") packages)
              min-len  (long (apply min (map count segments)))]
          (loop [i (long 0) acc []]
            (if (>= i min-len)
              (when (seq acc) (str/join "." acc))
              (let [seg (nth (first segments) i)]
                (if (every? #(= seg (nth % i)) (rest segments))
                  (recur (inc i) (conj acc seg))
                  (when (seq acc) (str/join "." acc)))))))))))

(defn- scan-existing-packages
  "Extract package label values from existing result JSONs in output dir.
   Used to compute common parent prefix across multiple test runs."
  [^File output-dir]
  (when (.exists output-dir)
    (->> (.listFiles output-dir)
      (filter #(str/ends-with? (.getName ^File %) "-result.json"))
      (keep (fn [^File f]
              (try
                (let [content (slurp f)]
                  (second (re-find #"\"name\"\s*:\s*\"package\"\s*,\s*\n\s*\"value\"\s*:\s*\"([^\"]+)\"" content)))
                (catch Exception _ nil))))
      distinct
      vec)))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private run-state
  "Mutable state for the current test run. nil when not active."
  (atom nil))

(def ^:private test-state
  "Mutable state for the current test var. nil between tests."
  (atom nil))

(def ^:private counters
  (atom {:test 0 :pass 0 :fail 0 :error 0}))

(def ^:private fixture-originals
  "Original :each fixtures per namespace, for restoration on :end-test-ns."
  (atom {}))

(def ^:private pending-results
  "Buffered test results. Written to disk at finalize with corrected parentSuite."
  (atom []))

;; =============================================================================
;; Supplementary Files
;; =============================================================================

(defn- spel-version []
  (some-> (io/resource "SPEL_VERSION") slurp str/trim not-empty))

(defn- project-version []
  (or (System/getProperty "lazytest.allure.version")
    (System/getenv "LAZYTEST_ALLURE_VERSION")
    (spel-version)))

(defn- write-environment-properties!
  [^File output-dir]
  (let [version       (project-version)
        commit-author (System/getenv "COMMIT_AUTHOR")
        props   (cond-> [["java.version"    (System/getProperty "java.version")]
                         ["java.vendor"     (System/getProperty "java.vendor")]
                         ["os.name"         (System/getProperty "os.name")]
                         ["os.arch"         (System/getProperty "os.arch")]
                         ["os.version"      (System/getProperty "os.version")]
                         ["clojure.version" (clojure-version)]
                         ["file.encoding"   (System/getProperty "file.encoding")]]
                  (spel-version)
                  (conj ["spel.version" (spel-version)])
                  version
                  (conj ["project.version" version])
                  commit-author
                  (conj ["commit.author" commit-author]))
        content (->> props
                  (map (fn [[k v]] (str k " = " (or v ""))))
                  (str/join "\n"))]
    (spit (io/file output-dir "environment.properties") (str content "\n"))))

(defn- write-categories-json!
  [^File output-dir]
  (let [categories [{:name "Assertion failures"
                     :matchedStatuses ["failed"]
                     :messageRegex ".*"}
                    {:name "Unexpected errors"
                     :matchedStatuses ["broken"]
                     :messageRegex ".*"}]]
    (spit (io/file output-dir "categories.json")
      (->json-pretty categories))))

;; =============================================================================
;; Result Building
;; =============================================================================

(defn- testing-context-str []
  (when (seq ct/*testing-contexts*)
    (str/join " > " (reverse ct/*testing-contexts*))))

(defn- common-testing-context
  "Extract common testing context prefix from a test's assertions.
   Returns the longest \" > \"-delimited prefix shared by all assertions
   that have a testing context, or the single context when only one exists.
   Returns nil when no assertions carry a testing context."
  [assertions]
  (let [contexts (->> assertions
                   (keep :context)
                   (remove str/blank?)
                   distinct
                   vec)]
    (when (seq contexts)
      (if (= 1 (count contexts))
        (first contexts)
        (let [parts   (mapv #(str/split % #" > ") contexts)
              min-len (long (apply min (map count parts)))]
          (loop [i (long 0) acc []]
            (if (>= i min-len)
              (when (seq acc) (str/join " > " acc))
              (let [seg (nth (first parts) i)]
                (if (every? #(= seg (nth % i)) (rest parts))
                  (recur (inc i) (conj acc seg))
                  (when (seq acc) (str/join " > " acc)))))))))))

(defn- build-labels
  [ts sub-suite]
  (let [ns-name  (:ns-name ts)
        pkg      (when ns-name (ns-package ns-name))
        hn       (:hostname @run-state)]
    (cond-> []
      ns-name   (conj {:name "suite" :value ns-name})
      pkg       (conj {:name "parentSuite" :value pkg})
      sub-suite (conj {:name "subSuite" :value sub-suite})
      hn        (conj {:name "host" :value hn})
      true      (conj {:name "thread" :value "main"})
      true      (conj {:name "language" :value "clojure"})
      true      (conj {:name "framework" :value "clojure.test"})
      true      (conj {:name "tag" :value "clojure-test"})
      pkg       (conj {:name "package" :value pkg})
      ns-name   (conj {:name "testClass" :value ns-name})
      true    (conj {:name "testMethod" :value (:test-name ts)}))))
(defn- build-display-name
  "Build a human-readable test name including testing context.
   Mirrors the Lazytest reporter's \"path > name\" convention."
  ^String [ts common-ctx]
  (if common-ctx
    (str common-ctx " > " (:test-name ts))
    (:test-name ts)))

(defn- build-steps-from-assertions
  [assertions start-ms stop-ms]
  (mapv (fn [a]
          (let [ctx    (:context a)
                name   (cond
                         (and ctx (:message a))
                         (str ctx " > " (:message a))

                         ctx
                         (str ctx " > " (pr-str (:expected a)))

                         (:message a)
                         (:message a)

                         :else
                         (pr-str (:expected a)))
                status (case (:type a)
                         :pass  "passed"
                         :fail  "failed"
                         :error "broken")]
            (cond-> {:name         name
                     :status       status
                     :start        start-ms
                     :stop         stop-ms
                     :steps        []
                     :attachments  []
                     :parameters   (cond-> []
                                     (:expected a)
                                     (conj {:name "expected"
                                            :value (pr-str (:expected a))})
                                     (some? (:actual a))
                                     (conj {:name "actual"
                                            :value (pr-str (:actual a))}))}
              (= :fail (:type a))
              (assoc :statusDetails
                {:message (or (:message a)
                            "Assertion failed")}))))
    assertions))

(defn- strip-step-stack
  [steps]
  (mapv (fn [step]
          (-> step
            (dissoc :step-stack)
            (update :steps strip-step-stack)))
    steps))

(defn- build-status-details
  [ts]
  (when-let [failure (:first-failure ts)]
    (let [^Throwable actual-t (when (instance? Throwable (:actual failure))
                                (:actual failure))
          msg (or (:message failure)
                (when actual-t (.getMessage actual-t))
                "Test failed")
          expected (when (:expected failure) (pr-str (:expected failure)))
          actual   (when (some? (:actual failure)) (pr-str (:actual failure)))
          trace    (stacktrace-str actual-t)]
      (cond-> {:message (str msg
                          (when expected
                            (str "\nExpected: " expected
                              "\nActual: " actual)))}
        trace (assoc :trace trace)))))

(defn- write-test-result!
  [ts]
  (let [dir         (:output-dir @run-state)
        result-uuid (uuid)
        full-name   (:full-name ts)
        stop-ms     (System/currentTimeMillis)
        start-ms    (:start-ms ts)
        status      (case (:status ts)
                      :pass  "passed"
                      :fail  "failed"
                      :error "broken")
        ;; Derive testing context for subSuite and display name
        common-ctx  (common-testing-context (:assertions ts))
        sub-suite   (when common-ctx
                      (first (str/split common-ctx #" > ")))
        ctx         (:allure-context ts)
        ctx-labels  (when ctx (:labels ctx))
        ctx-links   (when ctx (:links ctx))
        ctx-params  (when ctx (:parameters ctx))
        ctx-atts    (when ctx (:attachments ctx))
        ctx-steps   (when ctx (seq (:steps ctx)))
        ctx-desc    (when ctx (:description ctx))
        steps       (if ctx-steps
                      (strip-step-stack (vec ctx-steps))
                      (build-steps-from-assertions (:assertions ts) start-ms stop-ms))
        out-att     (write-attachment! dir (:system-out ts) "Full stdout log")
        err-att     (write-attachment! dir (:system-err ts) "Full stderr log")
        trace-att   (when-let [tp (:allure/trace-path ts)]
                      (copy-file-attachment! dir tp "Playwright Trace"
                        "application/vnd.allure.playwright-trace" ".zip"))
        har-att     (when-let [hp (:allure/har-path ts)]
                      (copy-file-attachment! dir hp "Network Activity (HAR)"
                        "application/json" ".har"))
        io-atts     (filterv some? [out-att err-att trace-att har-att])
        all-labels  (into (build-labels ts sub-suite) ctx-labels)
        all-links   (or (seq ctx-links) [])
        all-params  (or (seq ctx-params) [])
        all-atts    (into (vec io-atts) ctx-atts)
        status-det  (build-status-details ts)
        result      (cond-> {:uuid        result-uuid
                             :historyId   (md5-hex full-name)
                             :testCaseId  (md5-hex full-name)
                             :fullName    full-name
                             :name        (build-display-name ts common-ctx)
                             :status      status
                             :stage       "finished"
                             :start       start-ms
                             :stop        stop-ms
                             :labels      all-labels
                             :parameters  all-params
                             :links       all-links}
                      status-det     (assoc :statusDetails status-det)
                      (seq all-atts) (assoc :attachments all-atts)
                      (seq steps)    (assoc :steps steps)
                      ctx-desc       (assoc :description ctx-desc))]
    (swap! pending-results conj result)))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn- ensure-run-started!
  "Lazy-init on first :begin-test-ns. Creates output dir, resets state."
  []
  (when-not @run-state
    (let [dir-path (output-dir-path)
          dir      (io/file dir-path)]
      (when (clean?)
        (when (.exists dir)
          (doseq [^File f (reverse (file-seq dir))]
            (.delete f))))
      (.mkdirs dir)
      (reset! run-state {:hostname        (hostname)
                         :start-ms        (System/currentTimeMillis)
                         :output-dir      dir
                         :output-dir-path dir-path})
      (reset! counters {:test 0 :pass 0 :fail 0 :error 0})
      (reset! fixture-originals {})
      (reset! pending-results [])
      (allure/set-reporter-active! true))))

(defn- flush-pending-results!
  "Compute common parent namespace from all packages (existing + current run),
   update parentSuite labels, and write all buffered results to disk."
  []
  (let [dir      (:output-dir @run-state)
        results  @pending-results
        ;; Collect packages from this run's results
        current-pkgs (->> results
                       (mapcat :labels)
                       (filter #(= "package" (:name %)))
                       (map :value))
        ;; Also scan existing results from prior runs (e.g. lazytest)
        existing-pkgs (scan-existing-packages dir)
        all-pkgs      (into (vec existing-pkgs) current-pkgs)
        common-parent (common-segment-prefix all-pkgs)]
    (doseq [result results]
      (let [updated (if common-parent
                      (update result :labels
                        (fn [labels]
                          (mapv (fn [l]
                                  (if (= "parentSuite" (:name l))
                                    (assoc l :value common-parent)
                                    l))
                            labels)))
                      result)]
        (spit (io/file dir (str (:uuid updated) "-result.json"))
          (->json-pretty updated))))
    (reset! pending-results [])))

(defn- finalize-run!
  "Flush buffered results, write supplementary files, optionally generate HTML report."
  []
  (when @run-state
    (allure/set-reporter-active! false)
    (flush-pending-results!)
    (let [dir (:output-dir @run-state)]
      (write-environment-properties! dir)
      (write-categories-json! dir))
    (when (report?)
      (require 'com.blockether.spel.allure-reporter)
      (let [gen-fn (resolve 'com.blockether.spel.allure-reporter/generate-html-report!)
            out-fn (resolve 'com.blockether.spel.allure-reporter/output-dir)
            rpt-fn (resolve 'com.blockether.spel.allure-reporter/report-dir)]
        (when (and gen-fn out-fn rpt-fn)
          (gen-fn (out-fn) (rpt-fn)))))
    (reset! run-state nil)))

;; =============================================================================
;; Allure Context Fixture (auto-injected as outermost :each)
;; =============================================================================

(defn with-allure-context
  "clojure.test :each fixture that binds the Allure in-test API context.

   Enables allure/step, allure/epic, allure/screenshot, etc. within
   clojure.test deftest blocks. Also captures stdout/stderr per test.

   Auto-injected as the OUTERMOST :each fixture by the global reporter
   hooks — test files never reference this directly.

   Being outermost is critical: inner fixtures (e.g. with-traced-page)
   tear down BEFORE this fixture's post-`(f)` code runs, so trace/HAR
   files are fully written by the time we copy them into allure-results."
  [f]
  (if-not @run-state
    (f)
    (let [ctx-atom (atom (allure/make-context))
          out-sw   (StringWriter.)
          err-sw   (StringWriter.)]
      (binding [allure/*context*    ctx-atom
                allure/*output-dir* (:output-dir @run-state)
                allure/*test-title* (some-> @test-state :test-name)
                allure/*test-out*   out-sw
                allure/*test-err*   err-sw
                *out*               (PrintWriter. out-sw true)
                *err*               (PrintWriter. err-sw true)]
        (f))
      ;; After (f): inner fixtures have torn down, trace files are written.
      (when-let [ts @test-state]
        (when (:ended? ts)
          (write-test-result! ts)
          (reset! test-state nil))))))

;; =============================================================================
;; Event Handlers
;; =============================================================================

(defn- on-begin-ns [m]
  (let [ns-obj   (:ns m)
        existing (::ct/each-fixtures (meta ns-obj))]
    ;; Save original fixtures for restoration
    (swap! fixture-originals assoc ns-obj existing)
    ;; Auto-inject with-allure-context as outermost :each fixture
    (alter-meta! ns-obj assoc ::ct/each-fixtures
      (into [with-allure-context] (or existing [])))
    (swap! run-state assoc :current-ns (str (ns-name ns-obj)))))

(defn- on-end-ns [m]
  (let [ns-obj   (:ns m)
        original (get @fixture-originals ns-obj)]
    ;; Restore original fixtures
    (if original
      (alter-meta! ns-obj assoc ::ct/each-fixtures original)
      (alter-meta! ns-obj dissoc ::ct/each-fixtures))
    (swap! fixture-originals dissoc ns-obj)
    (swap! run-state dissoc :current-ns)))

(defn- on-begin-var [m]
  (let [v       (:var m)
        ns-name (str (ns-name (:ns (meta v))))
        tname   (str (:name (meta v)))]
    (swap! counters update :test inc)
    (reset! test-state
      {:var           v
       :ns-name       ns-name
       :test-name     tname
       :full-name     (str ns-name "." tname)
       :start-ms      (System/currentTimeMillis)
       :assertions    []
       :status        :pass
       :first-failure nil})))

(defn- on-pass [m]
  (swap! counters update :pass inc)
  (when @test-state
    (swap! test-state update :assertions conj
      {:type     :pass
       :context  (testing-context-str)
       :message  (:message m)
       :expected (:expected m)
       :actual   (:actual m)})))

(defn- on-fail [m]
  (swap! counters update :fail inc)
  (when @test-state
    (swap! test-state
      (fn [s]
        (-> s
          (update :assertions conj
            {:type     :fail
             :context  (testing-context-str)
             :message  (:message m)
             :expected (:expected m)
             :actual   (:actual m)})
          (assoc :status :fail)
          (cond-> (nil? (:first-failure s))
            (assoc :first-failure m)))))))

(defn- on-error [m]
  (swap! counters update :error inc)
  (when @test-state
    (swap! test-state
      (fn [s]
        (-> s
          (update :assertions conj
            {:type     :error
             :context  (testing-context-str)
             :message  (:message m)
             :expected (:expected m)
             :actual   (:actual m)})
          (assoc :status :error)
          (cond-> (nil? (:first-failure s))
            (assoc :first-failure m)))))))

(defn- on-end-var [_m]
  (when-let [ts @test-state]
    (let [ts (cond-> ts
               allure/*context*
               (assoc :allure-context @allure/*context*)

               allure/*trace-path*
               (assoc :allure/trace-path (str allure/*trace-path*))

               allure/*har-path*
               (assoc :allure/har-path (str allure/*har-path*))

               allure/*test-out*
               (assoc :system-out (str allure/*test-out*))

               allure/*test-err*
               (assoc :system-err (str allure/*test-err*)))]
      (reset! test-state (assoc ts :ended? true))
      ;; When with-allure-context is active, defer writing until after
      ;; inner fixtures tear down (trace files written).
      ;; When NOT active (shouldn't happen, but defensive), write immediately.
      (when-not allure/*context*
        (write-test-result! ts)
        (reset! test-state nil)))))

(defn- on-summary [_m]
  (let [{:keys [test pass fail error]} @counters]
    (println)
    (println (str "clojure.test Allure: "
               test " tests, "
               pass " passed, "
               fail " failed, "
               error " errors"))
    (println (str "Results written to " (:output-dir-path @run-state) "/")))
  (finalize-run!))

;; =============================================================================
;; Global defmethod hooks
;;
;; Save originals with defonce (survives REPL reloads), then install
;; chained defmethods that call original + our handler when enabled.
;; =============================================================================

(defonce ^:private originals
  {:begin-test-ns  (get-method ct/report :begin-test-ns)
   :end-test-ns    (get-method ct/report :end-test-ns)
   :begin-test-var (get-method ct/report :begin-test-var)
   :end-test-var   (get-method ct/report :end-test-var)
   :pass           (get-method ct/report :pass)
   :fail           (get-method ct/report :fail)
   :error          (get-method ct/report :error)
   :summary        (get-method ct/report :summary)})

(defmethod ct/report :begin-test-ns [m]
  (when (enabled?)
    (ensure-run-started!)
    (on-begin-ns m))
  ((:begin-test-ns originals) m))

(defmethod ct/report :end-test-ns [m]
  (when (enabled?)
    (on-end-ns m))
  ((:end-test-ns originals) m))

(defmethod ct/report :begin-test-var [m]
  (when (enabled?)
    (on-begin-var m))
  ((:begin-test-var originals) m))

(defmethod ct/report :end-test-var [m]
  (when (enabled?)
    (on-end-var m))
  ((:end-test-var originals) m))

(defmethod ct/report :pass [m]
  ((:pass originals) m)
  (when (enabled?)
    (on-pass m)))

(defmethod ct/report :fail [m]
  ((:fail originals) m)
  (when (enabled?)
    (on-fail m)))

(defmethod ct/report :error [m]
  ((:error originals) m)
  (when (enabled?)
    (on-error m)))

(defmethod ct/report :summary [m]
  ((:summary originals) m)
  (when (and (enabled?) @run-state)
    (on-summary m)))
