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
