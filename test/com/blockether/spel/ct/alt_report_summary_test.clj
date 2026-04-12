(ns com.blockether.spel.ct.alt-report-summary-test
  "clojure.test mirror of alt-report-summary-test — verifies summary.json's
   httpCalls[].attachment + logs[] fields via the alternative Allure report."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
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

(deftest summary-json-http-calls-and-logs
  (let [out (tmp-dir! "alt-summary-ct-")]
    (try
      (alt/generate-from-results! fixture-results (.getAbsolutePath out) {:title "T"})
      (let [summary (json/read-json (slurp (io/file out "summary.json")))
            calls   (get summary "httpCalls")
            logs    (get summary "logs")
            call    (first calls)
            by-test (group-by #(get % "test") logs)
            t1      (first (get by-test "t1"))
            t2      (first (get by-test "t2"))]

        (testing "httpCalls surfaces the call + attachment pointer"
          (is (= 1 (count calls)))
          (is (= "t1"                              (get call "test")))
          (is (= "[API] GET /users"                (get call "name")))
          (is (= "passed"                          (get call "status")))
          (is (= 40                                (get call "durationMs")))
          (is (= "data/attachments/abc-HTTP.md"    (get call "attachment"))))

        (testing "logs surfaces text/plain + application/json attachments"
          (is (= 2 (count logs)))
          (is (= "server-stderr"               (get t1 "name")))
          (is (= "text/plain"                  (get t1 "type")))
          (is (= "data/attachments/log-1.txt"  (get t1 "path")))
          (is (= "event-log"                   (get t2 "name")))
          (is (= "application/json"            (get t2 "type")))
          (is (= "data/attachments/log-2.json" (get t2 "path"))))

        (testing "HTTP markdown attachment is not duplicated into logs"
          (is (not-any? #(= "HTTP" (get % "name")) logs))))
      (finally (clean-dir! out)))))
