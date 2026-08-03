(ns com.blockether.spel.logging-test
  "Tests for the one logging system.

   Exercises level parsing/threshold, the canonical line format, and the
   file round-trip (`log!` → `read-lines`/`clear!`) that `spel logs` reads.
   No browser or daemon required."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.logging :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [java.nio.file Files LinkOption]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- fresh-session
  "Returns a unique session name and removes any leftover log file for it."
  []
  (let [session (str "logging-test-" (System/nanoTime))]
    (Files/deleteIfExists (sut/log-file-path session))
    session))

(defn- with-logger
  "Runs `f` with the logger pointed at `session`, then restores the defaults."
  [session component f]
  (sut/init! {:session session :component component :level :debug :mirror :off})
  (try (f)
       (finally
         (sut/init! {:session "default" :component "spel" :level :info :mirror :warn})
         (Files/deleteIfExists (sut/log-file-path session)))))

;; =============================================================================
;; Unit Tests — Paths
;; =============================================================================

(defdescribe log-file-path-test
  "Unit tests for log-file-path"

  (describe "one file per session"
    (it "contains the session name and .log"
      (let [p (str (sut/log-file-path "work"))]
        (expect (str/includes? p "spel-work.log"))))

    (it "differs per session"
      (expect (not= (str (sut/log-file-path "a"))
                (str (sut/log-file-path "b")))))))

;; =============================================================================
;; Unit Tests — Levels
;; =============================================================================

(defdescribe parse-level-test
  "Unit tests for parse-level"

  (describe "recognized names"
    (it "parses every level"
      (expect (= :debug (sut/parse-level "debug" :info)))
      (expect (= :info (sut/parse-level "INFO" :error)))
      (expect (= :warn (sut/parse-level "warning" :info)))
      (expect (= :error (sut/parse-level :error :info)))
      (expect (= :off (sut/parse-level "silent" :info)))))

  (describe "unknown names"
    (it "falls back to the default"
      (expect (= :info (sut/parse-level "loud" :info)))
      (expect (= :warn (sut/parse-level nil :warn))))))

(defdescribe enabled?-test
  "Unit tests for the level threshold"

  (describe "threshold :warn"
    (it "passes warn and error, drops debug and info"
      (let [session (fresh-session)]
        (sut/init! {:session session :component "test" :level :warn :mirror :off})
        (try
          (expect (false? (sut/enabled? :debug)))
          (expect (false? (sut/enabled? :info)))
          (expect (true? (sut/enabled? :warn)))
          (expect (true? (sut/enabled? :error)))
          (finally
            (sut/init! {:session "default" :component "spel" :level :info :mirror :warn})))))))

;; =============================================================================
;; Unit Tests — Format
;; =============================================================================

(defdescribe format-line-test
  "Unit tests for format-line"

  (describe "canonical line"
    (it "is `<ts> <LEVEL> [<component>] <message>`"
      (expect (= "2026-01-01T00:00:00.000Z INFO  [daemon] hello"
                (sut/format-line "2026-01-01T00:00:00.000Z" :info "daemon" "hello"))))

    (it "pads the level so columns line up"
      (let [a (sut/format-line "T" :warn "cli" "x")
            b (sut/format-line "T" :error "cli" "x")]
        (expect (= (str/index-of a "[") (str/index-of b "[")))))

    (it "keeps the message on one line"
      (expect (not (str/includes? (sut/format-line "T" :info "cli" "msg\n") "\n"))))))

;; =============================================================================
;; Unit Tests — File Round-Trip (what `spel logs` reads)
;; =============================================================================

(defdescribe log-file-round-trip-test
  "Unit tests for log! / read-lines / clear!"

  (describe "one sink shared by every component"
    (it "writes cli and daemon lines to the SAME file, in order"
      (let [session (fresh-session)]
        (with-logger session "cli"
          (fn []
            (sut/info! "cli line")
            (sut/init! {:session session :component "daemon" :level :debug :mirror :off})
            (sut/warn! "daemon line")
            (let [lines (sut/read-lines session {})]
              (expect (= 2 (count lines)))
              (expect (str/includes? (first lines) "[cli] cli line"))
              (expect (str/includes? (second lines) "[daemon] daemon line"))
              (expect (str/includes? (second lines) "WARN")))))))

    (it "returns [] for a session that never logged"
      (expect (= [] (sut/read-lines (str "never-" (System/nanoTime)) {}))))

    (it "tails only the last n lines"
      (let [session (fresh-session)]
        (with-logger session "cli"
          (fn []
            (doseq [i (range 5)] (sut/info! "line " i))
            (let [lines (sut/read-lines session {:lines 2})]
              (expect (= 2 (count lines)))
              (expect (str/includes? (last lines) "line 4")))))))

    (it "drops messages below the threshold"
      (let [session (fresh-session)]
        (sut/init! {:session session :component "cli" :level :warn :mirror :off})
        (try
          (sut/info! "invisible")
          (sut/error! "visible")
          (let [lines (sut/read-lines session {})]
            (expect (= 1 (count lines)))
            (expect (str/includes? (first lines) "visible")))
          (finally
            (sut/init! {:session "default" :component "spel" :level :info :mirror :warn})
            (Files/deleteIfExists (sut/log-file-path session))))))

    (it "clear! truncates the file and reports whether one existed"
      (let [session (fresh-session)]
        (with-logger session "cli"
          (fn []
            (sut/info! "before clear")
            (expect (true? (sut/log-exists? session)))
            (expect (true? (sut/clear! session)))
            (expect (= [] (sut/read-lines session {})))))
        (expect (false? (sut/clear! (str "never-" (System/nanoTime)))))))

    (it "log-exists? is false before anything is logged"
      (let [session (fresh-session)]
        (expect (false? (Files/exists (sut/log-file-path session)
                          (into-array LinkOption []))))
        (expect (false? (sut/log-exists? session)))))))

;; =============================================================================
;; Unit Tests — Throwable rendering
;; =============================================================================

(defn- throwing-fn
  "Throws from a frame owned by this repository, so `describe-throwable` has a
   `com.blockether.*` frame to prefer over JVM plumbing."
  []
  (throw (ex-info "route handler failed" {:label "route"})))

(defdescribe describe-throwable-test
  "Unit tests for describe-throwable"

  (describe "nil is rendered, never thrown on"
    (it "answers a placeholder instead of NPE-ing inside a failing handler"
      (expect (= "<no exception>" (sut/describe-throwable nil)))))

  (describe "message-less throwables still say something"
    (it "names the class and marks the missing message"
      (let [line (sut/describe-throwable (NullPointerException.))]
        (expect (str/includes? line "java.lang.NullPointerException"))
        (expect (str/includes? line "<no message>"))))

    (it "never renders as blank, which is what (.getMessage e) alone produced"
      (expect (not (str/blank? (sut/describe-throwable (NullPointerException.)))))))

  (describe "origin frame"
    (it "points at spel code rather than at JVM plumbing"
      (let [e    (try (throwing-fn) (catch Throwable t t))
            line (sut/describe-throwable e)]
        (expect (str/includes? line "clojure.lang.ExceptionInfo: route handler failed"))
        (expect (str/includes? line " at "))
        (expect (str/includes? line "com.blockether.spel")))))

  (describe "causes"
    (it "renders the cause chain"
      (let [root (Exception. "root cause")
            mid  (RuntimeException. "middle" root)
            line (sut/describe-throwable (RuntimeException. "top" mid))]
        (expect (str/includes? line "java.lang.RuntimeException: top"))
        (expect (str/includes? line " <- java.lang.RuntimeException: middle"))
        (expect (str/includes? line " <- java.lang.Exception: root cause"))))

    (it "stops after three causes so one line stays one line"
      (let [deep (reduce (fn [^Throwable c n] (RuntimeException. (str "c" n) c))
                   (Exception. "root")
                   (range 6))
            line (sut/describe-throwable deep)]
        (expect (= 3 (count (re-seq #" <- " line))))))))

;; =============================================================================
;; Unit Tests — exception!
;; =============================================================================

(defdescribe exception!-test
  "Unit tests for exception!"

  (describe "one warn line carrying the full rendering"
    (it "logs context, class and message"
      (let [session (fresh-session)]
        (with-logger session "daemon"
          (fn []
            (sut/exception! "ios-snapshot" (ex-info "device gone" {}))
            (let [lines (sut/read-lines session {})]
              (expect (= 1 (count lines)))
              (expect (str/includes? (first lines) "WARN"))
              (expect (str/includes? (first lines)
                        "ios-snapshot: clojure.lang.ExceptionInfo: device gone")))))))

    (it "never writes a bare context with nothing after it"
      (let [session (fresh-session)]
        (with-logger session "daemon"
          (fn []
            (sut/exception! "ios-snapshot" (NullPointerException.))
            (let [line (first (sut/read-lines session {}))]
              (expect (str/includes? line "java.lang.NullPointerException"))
              (expect (not (str/ends-with? line "ios-snapshot:")))
              (expect (not (str/ends-with? line "ios-snapshot: "))))))))))
