(ns com.blockether.spel.trace-debug
  "Minimal repro to find where tracing hangs on Amazon."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright BrowserContext Tracing$StartOptions Tracing$StopOptions]
   [java.io File]))

(defn run-trace-debug []
  (println "\n=== TRACE DEBUG START ===")
  (let [pw      (core/create)
        browser (core/launch-chromium pw {:headless true})]
    (try
      (let [trace-file (File/createTempFile "pw-trace-" ".zip")
            har-file   (File/createTempFile "pw-har-" ".har")
            _          (println "Trace file:" (.getAbsolutePath trace-file))
            _          (println "HAR file:  " (.getAbsolutePath har-file))
            ctx        (core/new-context browser
                         {:record-har-path        (str har-file)
                          :record-har-mode        :full
                          :record-har-omit-content true})
            tracing    (.tracing ^BrowserContext ctx)]

        (println "[1] Starting tracing...")
        (.start tracing (doto (Tracing$StartOptions.)
                          (.setScreenshots true)
                          (.setSnapshots true)))
        (println "[1] Tracing started.")

        (let [pg (core/new-page-from-context ctx)]
          (println "[2] Navigating to amazon.com...")
          (page/navigate pg "https://www.amazon.com")
          (page/wait-for-load-state pg "domcontentloaded")
          (println "[2] Amazon loaded. Title:" (page/title pg))

          ;; Small pause to let background requests happen
          (Thread/sleep 2000)
          (println "[3] Background requests running for 2s, now tearing down...")

          (println "[4] Navigating to about:blank...")
          (let [t0 (System/currentTimeMillis)]
            (.navigate pg "about:blank")
            (println (str "[4] about:blank done (" (- (System/currentTimeMillis) t0) "ms)")))

          (println "[5] Stopping tracing...")
          (let [t0 (System/currentTimeMillis)]
            (try (.stop tracing (doto (Tracing$StopOptions.)
                                  (.setPath (.toPath trace-file))))
                 (println (str "[5] Tracing stopped (" (- (System/currentTimeMillis) t0) "ms)"))
                 (println "    Trace size:" (.length trace-file) "bytes")
                 (catch Exception e
                   (println "[5] tracing.stop FAILED:" (.getMessage e)))))

          (println "[6] Closing page...")
          (let [t0 (System/currentTimeMillis)]
            (core/close-page! pg)
            (println (str "[6] Page closed (" (- (System/currentTimeMillis) t0) "ms)")))

          (println "[7] Closing context (writes HAR)...")
          (let [t0 (System/currentTimeMillis)]
            (core/close-context! ctx)
            (println (str "[7] Context closed (" (- (System/currentTimeMillis) t0) "ms)"))
            (println "    HAR size:" (.length har-file) "bytes"))))

      (finally
        (println "[8] Closing browser + playwright...")
        (core/close-browser! browser)
        (core/close! pw)
        (println "=== TRACE DEBUG DONE ===")))))
