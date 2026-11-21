(ns com.blockether.spel.platform-test
  "Tests for the process-tree helper. They start real `/bin/sh` trees, so
   they run on POSIX hosts only."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe it expect]]
   [com.blockether.spel.platform :as sut])
  (:import
   [java.lang ProcessHandle]))

(defn- windows?
  []
  (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))

(defn- start-tree
  "Starts `/bin/sh -c script` and returns [process descendants] after the
   children had time to spawn."
  [script]
  (let [proc (.start (ProcessBuilder. ^java.util.List (vector "/bin/sh" "-c" script)))]
    (Thread/sleep 200)
    [proc (vec (.toArray (.descendants (.toHandle proc))))]))

(defn- alive-count
  [^Process proc descendants]
  (count (filter (fn [^ProcessHandle ph] (.isAlive ph))
           (conj descendants (.toHandle proc)))))

(defdescribe terminate-tree-test
  ;; Regression: a child that ignored the polite terminate was killed but not
  ;; awaited, so the call returned while that child still ran.
  (it "returns only after a child that ignores TERM is gone"
    (when-not (windows?)
      (let [[^Process proc descendants] (start-tree "(trap '' TERM; sleep 300) & sleep 300")]
        (try
          (expect (pos? (count descendants)))
          (expect (true? (sut/terminate-tree! (.toHandle proc) {:grace-ms 100})))
          (expect (zero? (alive-count proc descendants)))
          (finally
            (run! (fn [^ProcessHandle ph] (.destroyForcibly ph)) descendants)
            (.destroyForcibly proc))))))

  (it "kills at once when no grace period is given"
    (when-not (windows?)
      (let [[^Process proc descendants] (start-tree "trap '' TERM; (sleep 300) & sleep 300")
            started (System/currentTimeMillis)]
        (try
          (expect (true? (sut/terminate-tree! (.toHandle proc) {})))
          (expect (zero? (alive-count proc descendants)))
          (expect (< (- (System/currentTimeMillis) started) 2000))
          (finally
            (run! (fn [^ProcessHandle ph] (.destroyForcibly ph)) descendants)
            (.destroyForcibly proc)))))))
