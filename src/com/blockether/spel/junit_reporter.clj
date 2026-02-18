(ns com.blockether.spel.junit-reporter
  "JUnit XML reporter for Lazytest.

   Produces JUnit XML output fully compliant with the Apache Ant JUnit
   schema (https://github.com/windyroad/JUnit-Schema) and compatible
   with CI systems: GitHub Actions, Jenkins, GitLab CI.

   Usage:
     clojure -M:test --output com.blockether.spel.junit-reporter/junit
     clojure -M:test --output nested --output com.blockether.spel.junit-reporter/junit

   Output file defaults to test-results/junit.xml. Override with:
     -Dlazytest.junit.output=path/to/output.xml
     LAZYTEST_JUNIT_OUTPUT=path/to/output.xml"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [lazytest.expectation-failed :refer [ex-failed?]]
   [lazytest.reporters :refer [reporter-dispatch]]
   [lazytest.suite :as s]
   [lazytest.test-case :as tc])
  (:import
   [java.io PrintWriter StringWriter]
   [java.net InetAddress]
   [java.time LocalDateTime]
   [java.time.format DateTimeFormatter]
   [java.util Locale]))

;; =============================================================================
;; Run State
;; =============================================================================

(def ^:private run-state
  "Mutable state captured during the test run. Reset on each :begin-test-run.
   Keys:
     :timestamp              - LocalDateTime when the run started
     :hostname               - machine hostname
     :original-try-test-case - saved original fn before alter-var-root"
  (atom {}))

;; =============================================================================
;; Per-Test Output Capture (alter-var-root hack)
;; =============================================================================

(defn- wrap-try-test-case
  "Returns a wrapper around try-test-case that captures *out* and *err*
   per test case execution. The captured output is assoc'd onto the
   result map as :system-out and :system-err strings."
  [original-fn]
  (fn [tc]
    (let [out-sw (StringWriter.)
          err-sw (StringWriter.)
          result (binding [*out* (PrintWriter. out-sw true)
                           *err* (PrintWriter. err-sw true)]
                   (original-fn tc))]
      (assoc result
        :system-out (str out-sw)
        :system-err (str err-sw)))))

(defn- install-output-capture!
  "Monkey-patches lazytest.test-case/try-test-case to capture per-test output.
   Saves the original function in run-state for later restoration."
  []
  (let [original (deref #'tc/try-test-case)]
    (swap! run-state assoc :original-try-test-case original)
    (alter-var-root #'tc/try-test-case wrap-try-test-case)))

(defn- uninstall-output-capture!
  "Restores the original try-test-case function."
  []
  (when-let [original (:original-try-test-case @run-state)]
    (alter-var-root #'tc/try-test-case (constantly original))))

;; =============================================================================
;; XML Helpers
;; =============================================================================

(defn- xml-escape
  "Escape special characters for XML content and attributes."
  ^String [s]
  (when s
    (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;"))))

(defn- nanos->seconds
  "Convert nanoseconds to seconds as a formatted string.
   Forces US locale to ensure dot decimal separator (JUnit XML spec)."
  ^String [nanos]
  (String/format Locale/US "%.3f" (into-array Object [(/ (double (or nanos 0)) 1e9)])))

(defn- stacktrace-str
  "Get full stacktrace as a string."
  ^String [^Throwable t]
  (when t
    (let [sw (StringWriter.)
          pw (PrintWriter. sw)]
      (.printStackTrace t pw)
      (str sw))))

(defn- iso-timestamp
  "ISO 8601 timestamp: 2026-02-14T13:30:00"
  ^String [^LocalDateTime ldt]
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss") ldt))

(defn- hostname
  "Best-effort hostname. Falls back to localhost."
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
       (catch Exception _ "localhost")))

(defn- xml-attr
  "Emit a single XML attribute: key=\"escaped-value\""
  ^String [k v]
  (str " " k "=\"" (xml-escape (str v)) "\""))

;; =============================================================================
;; Result Tree Walking
;; =============================================================================

(defn- doc-str
  "Extract a string representation from a doc value, which may be
   a Namespace, Var, or plain string."
  [doc]
  (cond
    (instance? clojure.lang.Namespace doc) (str (ns-name doc))
    (instance? clojure.lang.Var doc)       (str (:name (meta doc)))
    (and (some? doc)
      (not (str/blank? (str doc))))     (str doc)
    :else                                  nil))

(defn- ns-suite?
  "True if result is a namespace-level suite result."
  [result]
  (and (s/suite-result? result)
    (= :lazytest/ns (-> result :source :type))))

(defn- collect-test-cases
  "Walk result tree depth-first, collecting leaf test case results.
   Each result is annotated with ::path (vector of parent doc strings,
   excluding the run-level and namespace-level containers — those become
   the classname)."
  [result path]
  (if (s/suite-result? result)
    (let [source-type (-> result :source :type)
          doc (doc-str (:doc result))
          ;; Run and ns levels don't contribute to the test name path;
          ;; ns goes into classname instead
          new-path (if (and doc
                         (not= :lazytest/run source-type)
                         (not= :lazytest/ns source-type))
                     (conj path doc)
                     path)]
      (mapcat #(collect-test-cases % new-path) (:children result)))
    ;; Leaf test-case result
    [(assoc result ::path path)]))

(defn- ns-package
  "Extract package from a namespace name.
   com.blockether.spel.core-test → com.blockether.spel"
  ^String [^String ns-name]
  (let [idx (.lastIndexOf ns-name ".")]
    (if (pos? idx)
      (subs ns-name 0 idx)
      "")))

;; =============================================================================
;; Test Result Classification
;; =============================================================================

(defn- failure?
  "True if this is an assertion failure (expected behavior didn't match)."
  [tc]
  (and (= :fail (:type tc))
    (or (nil? (:thrown tc))
      (ex-failed? (:thrown tc)))))

(defn- error?
  "True if this is an unexpected error (exception, crash)."
  [tc]
  (and (= :fail (:type tc))
    (some? (:thrown tc))
    (not (ex-failed? (:thrown tc)))))

(defn- skipped?
  "True if this test was skipped/pending."
  [tc]
  (= :pending (:type tc)))

;; =============================================================================
;; Properties
;; =============================================================================

(defn- properties-xml
  "Generate <properties> element with environment metadata."
  ^String []
  (let [props [["java.version"    (System/getProperty "java.version")]
               ["java.vendor"     (System/getProperty "java.vendor")]
               ["os.name"         (System/getProperty "os.name")]
               ["os.arch"         (System/getProperty "os.arch")]
               ["os.version"      (System/getProperty "os.version")]
               ["clojure.version" (clojure-version)]
               ["file.encoding"   (System/getProperty "file.encoding")]]]
    (str "    <properties>\n"
      (->> props
        (map (fn [[n v]]
               (str "      <property"
                 (xml-attr "name" n)
                 (xml-attr "value" (or v ""))
                 " />")))
        (str/join "\n"))
      "\n    </properties>")))

;; =============================================================================
;; XML Generation
;; =============================================================================

(defn- failure-xml
  "Generate <failure> element for an assertion failure."
  ^String [tc]
  (let [msg          (or (:message tc) "Assertion failed")
        expected     (pr-str (:expected tc))
        actual       (pr-str (:actual tc))
        trace        (stacktrace-str (:thrown tc))
        failure-type (if-let [t (:thrown tc)]
                       (.getName (class t))
                       "lazytest.ExpectationFailed")]
    (str "      <failure"
      (xml-attr "message" msg)
      (xml-attr "type" failure-type)
      ">"
      (xml-escape (str "Expected: " expected "\nActual: " actual
                    (when trace (str "\n\n" trace))))
      "</failure>")))

(defn- error-xml
  "Generate <error> element for an unexpected exception."
  ^String [tc]
  (let [^Throwable thrown (:thrown tc)
        msg        (or (:message tc) (when thrown (.getMessage thrown)) "Unexpected error")
        error-type (if thrown (.getName (class thrown)) "java.lang.Exception")
        trace      (stacktrace-str thrown)]
    (str "      <error"
      (xml-attr "message" msg)
      (xml-attr "type" error-type)
      ">"
      (xml-escape (or trace ""))
      "</error>")))

(defn- skipped-xml
  "Generate <skipped> element."
  ^String [tc]
  (let [msg (or (:doc tc) "Skipped")]
    (str "      <skipped"
      (xml-attr "message" msg)
      " />")))

(defn- system-out-xml
  "Generate <system-out> element if content is non-empty."
  ^String [content]
  (when (and content (not (str/blank? content)))
    (str "      <system-out>" (xml-escape content) "</system-out>")))

(defn- system-err-xml
  "Generate <system-err> element if content is non-empty."
  ^String [content]
  (when (and content (not (str/blank? content)))
    (str "      <system-err>" (xml-escape content) "</system-err>")))

(defn- test-case-xml
  "Generate XML string for a single <testcase> element.
   Includes <system-out> and <system-err> when captured output is non-empty."
  ^String [tc classname]
  (let [name      (tc/identifier tc)
        path      (::path tc)
        ;; Full name: "var > suite > test" chain
        full-name (if (seq path)
                    (str (str/join " > " path) " > " name)
                    name)
        duration  (nanos->seconds (:lazytest.runner/duration tc))
        file      (:file tc)
        attrs     (str (xml-attr "classname" classname)
                    (xml-attr "name" full-name)
                    (xml-attr "time" duration)
                    (when file (xml-attr "file" file)))
        out-el    (system-out-xml (:system-out tc))
        err-el    (system-err-xml (:system-err tc))
        ;; Collect child elements: status element + captured output
        children  (filterv some?
                    [(cond
                       (failure? tc) (failure-xml tc)
                       (error? tc)   (error-xml tc)
                       (skipped? tc) (skipped-xml tc)
                       :else         nil)
                     out-el
                     err-el])]
    (if (seq children)
      (str "    <testcase" attrs ">\n"
        (str/join "\n" children) "\n"
        "    </testcase>")
      (str "    <testcase" attrs " />"))))

(defn- aggregate-output
  "Concatenate per-test captured output for a suite-level <system-out>/<system-err>.
   Each test's output is prefixed with a header: --- test-name ---"
  ^String [test-cases output-key]
  (let [parts (for [tc test-cases
                    :let [content (get tc output-key)]
                    :when (and content (not (str/blank? content)))]
                (str "--- " (tc/identifier tc) " ---\n" content))]
    (when (seq parts)
      (str/join "\n" parts))))

(defn- testsuite-xml
  "Generate XML string for a single <testsuite> element (one per namespace)."
  ^String [idx ns-suite-result]
  (let [ns-name    (or (doc-str (:doc ns-suite-result)) "unknown")
        test-cases (collect-test-cases ns-suite-result [])
        total      (count test-cases)
        failures   (count (filter failure? test-cases))
        errors     (count (filter error? test-cases))
        skipped    (count (filter skipped? test-cases))
        duration   (nanos->seconds (:lazytest.runner/duration ns-suite-result))
        ts         (:timestamp @run-state)
        hn         (:hostname @run-state)
        pkg        (ns-package ns-name)
        tc-xmls    (map #(test-case-xml % ns-name) test-cases)
        ;; Aggregate captured output across all test cases in this suite
        agg-out    (aggregate-output test-cases :system-out)
        agg-err    (aggregate-output test-cases :system-err)
        agg-els    (filterv some?
                     [(when agg-out
                        (str "    <system-out>" (xml-escape agg-out) "</system-out>"))
                      (when agg-err
                        (str "    <system-err>" (xml-escape agg-err) "</system-err>"))])
        attrs      (str (xml-attr "name" ns-name)
                     (xml-attr "tests" total)
                     (xml-attr "failures" failures)
                     (xml-attr "errors" errors)
                     (xml-attr "skipped" skipped)
                     (xml-attr "time" duration)
                     (xml-attr "timestamp" (if ts (iso-timestamp ts) ""))
                     (xml-attr "hostname" (or hn "localhost"))
                     (xml-attr "id" idx)
                     (xml-attr "package" pkg))]
    (str "  <testsuite" attrs ">\n"
      (properties-xml) "\n"
      (when (pos? total)
        (str (str/join "\n" tc-xmls) "\n"))
      (when (seq agg-els)
        (str (str/join "\n" agg-els) "\n"))
      "  </testsuite>")))

(defn- results->junit-xml
  "Transform lazytest result tree into a fully-compliant JUnit XML string."
  ^String [results]
  (let [ns-suites  (when (s/suite-result? results)
                     (filter ns-suite? (:children results)))
        all-cases  (mapcat #(collect-test-cases % []) ns-suites)
        total      (count all-cases)
        failures   (count (filter failure? all-cases))
        errors     (count (filter error? all-cases))
        skipped-n  (count (filter skipped? all-cases))
        duration   (nanos->seconds (:lazytest.runner/duration results))
        suite-xmls (map-indexed testsuite-xml ns-suites)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      "<testsuites"
      (xml-attr "name" "Lazytest")
      (xml-attr "tests" total)
      (xml-attr "failures" failures)
      (xml-attr "errors" errors)
      (xml-attr "skipped" skipped-n)
      (xml-attr "time" duration)
      ">\n"
      (str/join "\n" suite-xmls) "\n"
      "</testsuites>\n")))

;; =============================================================================
;; Reporter
;; =============================================================================

(defn- output-path
  "Determine the output file path. Checks system property, then env var,
   then falls back to test-results/junit.xml."
  ^String []
  (or (System/getProperty "lazytest.junit.output")
    (System/getenv "LAZYTEST_JUNIT_OUTPUT")
    "test-results/junit.xml"))

(defmulti junit
  "JUnit XML reporter for Lazytest.

   Writes test results as JUnit XML on test run completion. Silent during
   the run — designed to be combined with a visual reporter:

     --output nested --output com.blockether.spel.junit-reporter/junit

   Produces output compliant with the Apache Ant JUnit schema including:
   - <testsuites> with aggregate counts (tests, failures, errors, skipped)
   - <testsuite> with timestamp, hostname, package, id
   - <testcase> with file attribute (for GitLab CI)
   - <failure> vs <error> distinction (assertion vs unexpected exception)
   - <skipped> support for pending tests
   - <properties> with environment metadata (JVM, OS, Clojure version)"
  {:arglists '([config m])}
  #'reporter-dispatch)

(defmethod junit :default [_ _])

(defmethod junit :begin-test-run [_ _]
  (reset! run-state {:timestamp (LocalDateTime/now)
                     :hostname  (hostname)})
  (install-output-capture!))

(defmethod junit :end-test-run [_ m]
  (uninstall-output-capture!)
  (let [results (:results m)
        path    (output-path)
        file    (io/file path)
        xml     (results->junit-xml results)]
    (io/make-parents file)
    (spit file xml)
    (println (str "\nJUnit XML written to " path))))
