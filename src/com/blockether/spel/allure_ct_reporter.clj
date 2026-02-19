(ns com.blockether.spel.allure-ct-reporter
  "Allure 2+ reporter for clojure.test.

   Writes JSON result files to allure-results/, compatible with the
   Lazytest Allure reporter. Both reporters write to the same directory,
   and the Allure CLI merges everything into one HTML report.

   Two usage modes:

   1. Combined (Lazytest + clojure.test) via test-runner:
        clojure -M:test-all

   2. Standalone clojure.test:
        clojure -M:test-ct

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

   `with-allure-context` is injected automatically by `run-ct-tests!` as
   the innermost :each fixture — no need to add it in test files."
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
;; Shared Utilities (duplicated from allure_reporter.clj to avoid coupling)
;; =============================================================================

(defn- hostname
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
    (catch Exception _ "localhost")))

(defn- uuid
  ^String []
  (str (UUID/randomUUID)))

(defn- md5-hex
  "MD5 hash of a string, returned as lowercase hex."
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
  "Escape a string for JSON."
  ^String [^String s]
  (-> s
    (str/replace "\\" "\\\\")
    (str/replace "\"" "\\\"")
    (str/replace "\n" "\\n")
    (str/replace "\r" "\\r")
    (str/replace "\t" "\\t")))

(defn- ->json-pretty
  "Convert to JSON with basic indentation for readability."
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
  "Write an attachment file, return the attachment metadata map, or nil."
  [output-dir content att-name]
  (when (and content (not (str/blank? content)))
    (let [att-uuid (uuid)
          filename (str att-uuid "-attachment.txt")
          att-file (io/file output-dir filename)]
      (spit att-file content)
      {:name att-name :source filename :type "text/plain"})))

(defn- copy-file-attachment!
  "Copy a file into the output dir and return an attachment metadata map.
   Returns nil if the source file doesn't exist or is empty."
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

;; =============================================================================
;; Run State & Per-Test State
;; =============================================================================

(def ^:private run-state
  "Mutable state for the current CT test run. nil when not active."
  (atom nil))

(def ^:private test-state
  "Mutable state for the current test var. nil between tests."
  (atom nil))

(def ^:private counters
  "Test result counters."
  (atom {:test 0 :pass 0 :fail 0 :error 0}))

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
  (let [version (project-version)
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
                  (conj ["project.version" version]))
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

(defn- testing-context-str
  "Build a context string from clojure.test's *testing-contexts*."
  []
  (when (seq ct/*testing-contexts*)
    (str/join " > " (reverse ct/*testing-contexts*))))

(defn- build-labels
  [ts]
  (let [ns-name  (:ns-name ts)
        pkg      (when ns-name (ns-package ns-name))
        hn       (:hostname @run-state)]
    (cond-> []
      ns-name (conj {:name "suite" :value ns-name})
      pkg     (conj {:name "parentSuite" :value pkg})
      hn      (conj {:name "host" :value hn})
      true    (conj {:name "thread" :value "main"})
      true    (conj {:name "language" :value "clojure"})
      true    (conj {:name "framework" :value "clojure.test"})
      true    (conj {:name "tag" :value "clojure-test"})
      pkg     (conj {:name "package" :value pkg})
      ns-name (conj {:name "testClass" :value ns-name})
      true    (conj {:name "testMethod" :value (:test-name ts)}))))

(defn- build-steps-from-assertions
  "Auto-generate Allure steps from the assertions list."
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
  "Remove internal :step-stack from step trees (not part of Allure schema)."
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
  "Build and write the Allure JSON result for a completed test."
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
        ;; Allure context from with-allure-context fixture
        ctx         (:allure-context ts)
        ctx-labels  (when ctx (:labels ctx))
        ctx-links   (when ctx (:links ctx))
        ctx-params  (when ctx (:parameters ctx))
        ctx-atts    (when ctx (:attachments ctx))
        ctx-steps   (when ctx (seq (:steps ctx)))
        ctx-desc    (when ctx (:description ctx))
        ;; Steps: prefer explicit allure/step calls, else auto-generate from assertions
        steps       (if ctx-steps
                      (strip-step-stack (vec ctx-steps))
                      (build-steps-from-assertions (:assertions ts) start-ms stop-ms))
        ;; IO attachments
        out-att     (write-attachment! dir (:system-out ts) "Full stdout log")
        err-att     (write-attachment! dir (:system-err ts) "Full stderr log")
        trace-att   (when-let [tp (:allure/trace-path ts)]
                      (copy-file-attachment! dir tp "Playwright Trace"
                        "application/vnd.allure.playwright-trace" ".zip"))
        har-att     (when-let [hp (:allure/har-path ts)]
                      (copy-file-attachment! dir hp "Network Activity (HAR)"
                        "application/json" ".har"))
        io-atts     (filterv some? [out-att err-att trace-att har-att])
        ;; Merge
        all-labels  (into (build-labels ts) ctx-labels)
        all-links   (or (seq ctx-links) [])
        all-params  (or (seq ctx-params) [])
        all-atts    (into (vec io-atts) ctx-atts)
        status-det  (build-status-details ts)
        result      (cond-> {:uuid        result-uuid
                             :historyId   (md5-hex full-name)
                             :testCaseId  (md5-hex full-name)
                             :fullName    full-name
                             :name        (:test-name ts)
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
    (spit (io/file dir (str result-uuid "-result.json"))
      (->json-pretty result))))

;; =============================================================================
;; Event Handlers
;; =============================================================================

(defn- on-begin-ns [m]
  (swap! run-state assoc :current-ns (str (ns-name (:ns m)))))

(defn- on-end-ns [_m]
  (swap! run-state dissoc :current-ns))

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
    ;; Capture allure data from dynamic vars — still bound because
    ;; :end-test-var fires INSIDE the fixture chain's (f) call.
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
      ;; When with-allure-context is active (outermost fixture), defer
      ;; writing until after inner fixtures tear down — so trace/HAR
      ;; files are written before we try to copy them.
      ;; When NOT active, write immediately (standalone report-fn usage).
      (when-not allure/*context*
        (write-test-result! ts)
        (reset! test-state nil)))))

(defn- on-summary [_m]
  (let [{:keys [test pass fail error]} @counters]
    (println)
    (println (str "clojure.test Allure results: "
               test " tests, "
               pass " passed, "
               fail " failed, "
               error " errors"))
    (println (str "Results written to " (:output-dir-path @run-state) "/"))))

;; =============================================================================
;; Allure Context Fixture
;; =============================================================================

(defn with-allure-context
  "clojure.test :each fixture that binds the Allure in-test API context.

   Enables allure/step, allure/epic, allure/screenshot, etc. within
   clojure.test deftest blocks. Also captures stdout/stderr per test.

   Injected automatically by `run-ct-tests!` as the OUTERMOST :each
   fixture — no need to add it in test files.

   Being outermost is critical: inner fixtures (e.g. with-traced-page)
   tear down BEFORE this fixture's post-`(f)` code runs, so trace/HAR
   files are fully written by the time we copy them into allure-results."
  [f]
  (if-not @run-state
    ;; Not running under Allure reporter -- just run the test
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
      ;; After (f): inner fixtures (with-traced-page etc.) have torn down,
      ;; so trace zips and HAR files are now written and ready to copy.
      ;; on-end-var saved all allure data but deferred writing.
      (when-let [ts @test-state]
        (when (:ended? ts)
          (write-test-result! ts)
          (reset! test-state nil))))))

;; =============================================================================
;; Report Function
;; =============================================================================

(defn- make-report-fn
  "Create the Allure report function for binding over clojure.test/report."
  []
  (fn allure-report [m]
    (case (:type m)
      :begin-test-ns  (on-begin-ns m)
      :end-test-ns    (on-end-ns m)
      :begin-test-var (on-begin-var m)
      :end-test-var   (on-end-var m)
      :pass           (on-pass m)
      :fail           (on-fail m)
      :error          (on-error m)
      :summary        (on-summary m)
      nil)))

;; =============================================================================
;; Output Directory
;; =============================================================================

(defn- output-dir-path
  ^String []
  (or (System/getProperty "ct.allure.output")
    (System/getenv "CT_ALLURE_OUTPUT")
    "allure-results"))

;; =============================================================================
;; Entry Points
;; =============================================================================

(defn begin-run!
  "Initialize the CT Allure reporter.

   Options:
     :clean?     - clean output dir before writing (default: true)
     :output-dir - override output directory path"
  ([] (begin-run! {}))
  ([{:keys [clean? output-dir] :or {clean? true}}]
   (let [dir-path (or output-dir (output-dir-path))
         dir      (io/file dir-path)]
     (when clean?
       (when (.exists dir)
         (doseq [^File f (reverse (file-seq dir))]
           (.delete f))))
     (.mkdirs dir)
     (reset! run-state {:hostname       (hostname)
                        :start-ms       (System/currentTimeMillis)
                        :output-dir     dir
                        :output-dir-path dir-path})
     (reset! counters {:test 0 :pass 0 :fail 0 :error 0})
     (allure/set-reporter-active! true))))

(defn end-run!
  "Finalize the CT Allure reporter. Writes supplementary files.

   Options:
     :report? - generate HTML report via Allure CLI (default: false)"
  ([] (end-run! {}))
  ([{:keys [report?] :or {report? false}}]
   (allure/set-reporter-active! false)
   (let [dir (:output-dir @run-state)]
     (write-environment-properties! dir)
     (write-categories-json! dir)
     (when report?
       ;; Use the public generate-html-report! from allure-reporter ns
       (require 'com.blockether.spel.allure-reporter)
       (let [gen-fn (resolve 'com.blockether.spel.allure-reporter/generate-html-report!)
             out-fn (resolve 'com.blockether.spel.allure-reporter/output-dir)
             rpt-fn (resolve 'com.blockether.spel.allure-reporter/report-dir)]
         (when (and gen-fn out-fn rpt-fn)
           (gen-fn (out-fn) (rpt-fn))))))))

(defn- inject-allure-fixtures!
  "Prepend `with-allure-context` as the OUTERMOST :each fixture for each
   namespace. Being outermost means inner fixtures (with-traced-page etc.)
   tear down before we write the result — so trace/HAR files exist.
   Returns {ns-obj -> original-seq} for restoration."
  [namespaces]
  (reduce
    (fn [originals ns-sym]
      (let [ns-obj   (the-ns ns-sym)
            existing (::ct/each-fixtures (meta ns-obj))]
        (alter-meta! ns-obj assoc ::ct/each-fixtures
          (into [with-allure-context] (or existing [])))
        (assoc originals ns-obj existing)))
    {} namespaces))

(defn- restore-fixtures!
  "Restore original :each fixtures saved by `inject-allure-fixtures!`."
  [originals]
  (doseq [[ns-obj original] originals]
    (if original
      (alter-meta! ns-obj assoc ::ct/each-fixtures original)
      (alter-meta! ns-obj dissoc ::ct/each-fixtures))))

(defn run-ct-tests!
  "Discover and run clojure.test namespaces with the Allure reporter.

   Automatically injects `with-allure-context` as the innermost :each
   fixture for every namespace — test files don't need to add it manually.

   Options:
     :namespaces - list of namespace symbols to test
     :clean?     - clean output dir before writing (default: true)
     :report?    - generate HTML report after (default: false)

   Returns the counters map {:test N :pass N :fail N :error N}."
  [{:keys [namespaces clean? report?]
    :or   {clean? true report? false}}]
  (begin-run! {:clean? clean?})
  (doseq [ns-sym namespaces]
    (require ns-sym))
  (let [originals (inject-allure-fixtures! namespaces)
        report-fn (make-report-fn)]
    (try
      (binding [ct/report report-fn]
        (apply ct/run-tests namespaces))
      (finally
        (restore-fixtures! originals))))
  (end-run! {:report? report?})
  @counters)

(defn -main
  "CLI entry point. Takes namespace name strings as arguments.

   Usage:
     clojure -M:test-ct com.blockether.spel.ct.smoke-test ..."
  [& ns-strings]
  (let [ns-syms (mapv symbol ns-strings)
        results (run-ct-tests! {:namespaces ns-syms
                                :clean?     true
                                :report?    true})]
    (when (pos? (+ (:fail results) (:error results)))
      (System/exit 1))))
