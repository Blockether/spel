(ns com.blockether.spel.allure-ct-reporter-test
  "Tests for allure-ct-reporter parity functions."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.allure-ct-reporter :as ct-reporter])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Private fn accessors
;; =============================================================================

(def ^:private common-testing-context @#'ct-reporter/common-testing-context)
(def ^:private build-display-name @#'ct-reporter/build-display-name)
(def ^:private build-labels @#'ct-reporter/build-labels)
(def ^:private write-environment-properties! @#'ct-reporter/write-environment-properties!)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- tmp-dir
  "Create a temp directory that auto-deletes on JVM exit."
  ^File [^String prefix]
  (let [dir (Files/createTempDirectory prefix (into-array FileAttribute []))]
    (.toFile dir)))

(defn- clean-dir!
  "Recursively delete a directory."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(defn- read-env-props
  "Read environment.properties into a map."
  [^File dir]
  (let [f (io/file dir "environment.properties")]
    (when (.isFile f)
      (into {}
        (for [line (str/split-lines (slurp f))
              :when (not (str/blank? line))
              :let [[k v] (str/split line #"\s*=\s*" 2)]
              :when k]
          [k (or v "")])))))

;; =============================================================================
;; Tests
;; =============================================================================

(defdescribe common-testing-context-test
  "Tests for common-testing-context function"

  (describe "no assertions"
    (it "returns nil for empty assertions"
      (expect (nil? (common-testing-context []))))

    (it "returns nil when no assertions have :context"
      (expect (nil? (common-testing-context
                      [{:type :pass :expected '(= 1 1)}
                       {:type :pass :expected '(= 2 2)}])))))

  (describe "single context"
    (it "returns the single context when only one distinct context"
      (expect (= "Login"
                (common-testing-context
                  [{:type :pass :context "Login" :expected '(= 1 1)}
                   {:type :pass :context "Login" :expected '(= 2 2)}]))))

    (it "returns single context for one assertion"
      (expect (= "Login > Form"
                (common-testing-context
                  [{:type :pass :context "Login > Form" :expected '(= 1 1)}])))))

  (describe "common prefix"
    (it "extracts common prefix from multiple contexts"
      (expect (= "Login"
                (common-testing-context
                  [{:type :pass :context "Login > Form" :expected '(= 1 1)}
                   {:type :pass :context "Login > Submit" :expected '(= 2 2)}]))))

    (it "extracts multi-level common prefix"
      (expect (= "Login > Form"
                (common-testing-context
                  [{:type :pass :context "Login > Form > Username" :expected '(= 1 1)}
                   {:type :pass :context "Login > Form > Password" :expected '(= 2 2)}])))))

  (describe "no common prefix"
    (it "returns nil when contexts share no prefix"
      (expect (nil? (common-testing-context
                      [{:type :pass :context "Login" :expected '(= 1 1)}
                       {:type :pass :context "Signup" :expected '(= 2 2)}])))))

  (describe "blank contexts ignored"
    (it "ignores blank context strings"
      (expect (= "Login"
                (common-testing-context
                  [{:type :pass :context "Login" :expected '(= 1 1)}
                   {:type :pass :context "" :expected '(= 2 2)}
                   {:type :pass :context "Login" :expected '(= 3 3)}]))))))

(defdescribe build-display-name-test
  "Tests for build-display-name function"

  (it "returns test name without context"
    (expect (= "my-test"
              (build-display-name {:test-name "my-test"} nil))))

  (it "prepends context to test name"
    (expect (= "Login > my-test"
              (build-display-name {:test-name "my-test"} "Login"))))

  (it "handles multi-level context"
    (expect (= "Login > Form > my-test"
              (build-display-name {:test-name "my-test"} "Login > Form")))))

(defdescribe build-labels-test
  "Tests for build-labels subSuite support"

  (it "includes subSuite label when sub-suite is provided"
    (let [ts     {:ns-name "com.example.auth-test" :test-name "login-test"}
          labels (build-labels ts "Login")]
      (expect (some #(and (= "subSuite" (:name %))
                       (= "Login" (:value %)))
                labels))))

  (it "omits subSuite label when sub-suite is nil"
    (let [ts     {:ns-name "com.example.auth-test" :test-name "login-test"}
          labels (build-labels ts nil)]
      (expect (not-any? #(= "subSuite" (:name %)) labels))))

  (it "always includes framework=clojure.test"
    (let [ts     {:ns-name "com.example.auth-test" :test-name "login-test"}
          labels (build-labels ts nil)]
      (expect (some #(and (= "framework" (:name %))
                       (= "clojure.test" (:value %)))
                labels))))

  (it "includes suite, parentSuite, package for namespaced test"
    (let [ts     {:ns-name "com.example.auth-test" :test-name "login-test"}
          labels (build-labels ts nil)]
      (expect (some #(and (= "suite" (:name %))
                       (= "com.example.auth-test" (:value %)))
                labels))
      (expect (some #(= "parentSuite" (:name %)) labels))
      (expect (some #(= "package" (:name %)) labels)))))

(defdescribe write-environment-properties-test
  "Tests for write-environment-properties! commit.author support"

  (it "writes standard environment properties"
    (let [dir (tmp-dir "ct-env-test")]
      (try
        (write-environment-properties! dir)
        (let [props (read-env-props dir)]
          (expect (contains? props "java.version"))
          (expect (contains? props "os.name"))
          (expect (contains? props "clojure.version")))
        (finally
          (clean-dir! dir)))))

  (it "includes commit.author when COMMIT_AUTHOR env is set"
    ;; This test validates the code path exists; the env var may or may not
    ;; be set in the test environment, so we just verify the function runs
    ;; without error and produces valid properties.
    (let [dir (tmp-dir "ct-env-author-test")]
      (try
        (write-environment-properties! dir)
        (let [props   (read-env-props dir)
              author  (System/getenv "COMMIT_AUTHOR")]
          ;; If COMMIT_AUTHOR is set, it should appear in props
          (when author
            (expect (= author (get props "commit.author"))))
          ;; If not set, commit.author should be absent
          (when-not author
            (expect (not (contains? props "commit.author")))))
        (finally
          (clean-dir! dir))))))
