(ns com.blockether.spel.alt-report-summary-test
  "Regression tests for the alternative Allure HTML report's summary.json —
   specifically the new `httpCalls[].attachment` and `logs[]` fields."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.spel-allure-alternative-html-report :as alt])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir! ^File [^String prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- clean-dir! [^File dir]
  (when (and dir (.exists dir))
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(def ^:private fixture-results
  "A passing test with one HTTP-markdown exchange and a plain-text log
   attachment; plus a failing test with a JSON event-log attachment."
  [{"name"   "t1"
    "status" "passed"
    "start"  1000
    "stop"   1200
    "steps"  [{"name"        "[API] GET /users"
               "status"      "passed"
               "start"       1010
               "stop"        1050
               "attachments" [{"name" "HTTP" "type" "text/markdown" "source" "abc-HTTP.md"}]}]
    "attachments" [{"name" "server-stderr" "type" "text/plain" "source" "log-1.txt"}]}
   {"name"   "t2"
    "status" "failed"
    "steps"  [{"name"        "Step"
               "attachments" [{"name" "event-log" "type" "application/json" "source" "log-2.json"}]}]
    "statusDetails" {"message" "boom"}}])

(defdescribe alt-report-summary-test
  (describe "summary.json — httpCalls + logs"

    (it "includes httpCalls[].attachment pointing to the HTTP markdown"
      (let [out (tmp-dir! "alt-summary-http-")]
        (try
          (alt/generate-from-results! fixture-results (.getAbsolutePath out) {:title "T"})
          (let [summary (json/read-json (slurp (io/file out "summary.json")))
                calls   (get summary "httpCalls")
                call    (first calls)]
            (expect (= 1 (count calls)))
            (expect (= "t1"                              (get call "test")))
            (expect (= "[API] GET /users"                (get call "name")))
            (expect (= "passed"                          (get call "status")))
            (expect (= 40                                (get call "durationMs")))
            (expect (= "data/attachments/abc-HTTP.md"    (get call "attachment"))))
          (finally (clean-dir! out)))))

    (it "includes logs[] entries for text/plain + application/json attachments"
      (let [out (tmp-dir! "alt-summary-logs-")]
        (try
          (alt/generate-from-results! fixture-results (.getAbsolutePath out) {:title "T"})
          (let [summary (json/read-json (slurp (io/file out "summary.json")))
                logs    (get summary "logs")
                by-test (group-by #(get % "test") logs)
                t1      (first (get by-test "t1"))
                t2      (first (get by-test "t2"))]
            (expect (= 2 (count logs)))
            (expect (= "server-stderr"              (get t1 "name")))
            (expect (= "text/plain"                 (get t1 "type")))
            (expect (= "data/attachments/log-1.txt" (get t1 "path")))
            (expect (= "event-log"                  (get t2 "name")))
            (expect (= "application/json"           (get t2 "type")))
            (expect (= "data/attachments/log-2.json" (get t2 "path"))))
          (finally (clean-dir! out)))))

    (it "excludes the HTTP markdown attachment from logs[]"
      (let [out (tmp-dir! "alt-summary-excl-")]
        (try
          (alt/generate-from-results! fixture-results (.getAbsolutePath out) {:title "T"})
          (let [summary (json/read-json (slurp (io/file out "summary.json")))
                logs    (get summary "logs")]
            ;; The HTTP exchange is surfaced under httpCalls[].attachment,
            ;; not duplicated into logs[].
            (expect (not-any? #(= "HTTP" (get % "name")) logs)))
          (finally (clean-dir! out)))))))
