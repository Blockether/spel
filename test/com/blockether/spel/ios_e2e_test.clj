(ns com.blockether.spel.ios-e2e-test
  "Opt-in end-to-end tests against a REAL iOS Simulator + Appium.

   Run with:
     SPEL_IOS_E2E=1 SPEL_IOS_UDID=<udid> clojure -M:test -n com.blockether.spel.ios-e2e-test

   ALWAYS set SPEL_IOS_UDID on shared machines: without it, device selection
   prefers an already-Booted iPhone — which may be a simulator someone else
   is actively using.

   Skips (with a clear message) when SPEL_IOS_E2E is absent. When enabled,
   missing Xcode/Appium/XCUITest prerequisites are FAILURES, not skips —
   the point of enabling E2E is proving the full stack.

   The suite explicitly binds Mobile Safari as its installed application
   fixture, then enters its WEBVIEW context. The provider itself has no Safari
   default. Assertions verify observable DOM state on /touch-page — never just
   \"no error\"."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.backend :as backend]
   [com.blockether.spel.ios :as ios]
   [com.blockether.spel.test-server :as ts]
   [com.blockether.spel.webdriver :as webdriver]))

(def ^:private e2e-enabled?
  (some? (System/getenv "SPEL_IOS_E2E")))

(def ^:private skip-message
  "SPEL_IOS_E2E not set — skipping real-simulator E2E. Enable with SPEL_IOS_E2E=1 (requires macOS + Xcode + Appium + XCUITest).")

(defonce ^:private !shared (atom nil))

(defn- start-shared-session!
  "Starts (once) a shared iOS session + local test server for the suite.
   Returns {:ios-session :backend :server-url}."
  []
  (or @!shared
    (let [checks (ios/doctor)]
      (when-not (ios/doctor-ok? checks)
        (throw (ex-info (str "iOS E2E enabled but prerequisites are missing:\n"
                          (str/join "\n"
                            (for [{:keys [check ok detail]} checks
                                  :when (not ok)]
                              (str "  FAIL " check (when detail (str " (" detail ")")))))
                          "\n\n" ios/setup-instructions)
                 {:checks checks})))
      (let [server      (ts/start-test-server)
            ;; The simulator reaches the host loopback via 127.0.0.1 for
            ;; iOS Simulators (they share the host network stack).
            server-url  (str "http://127.0.0.1:" (ts/server-port server))
            session     (str "ios-e2e-" (System/currentTimeMillis))
            ;; Pin the device when SPEL_IOS_UDID is set — the default
            ;; selection prefers an already-Booted iPhone, which on a shared
            ;; machine can be a simulator the developer is actively using.
            ios-session (ios/start!
                          (cond-> {:session session
                                   ;; Safari is an explicit E2E fixture, not a
                                   ;; provider default. This keeps the existing
                                   ;; local DOM behavior tests useful.
                                   :bundle-id "com.apple.mobilesafari"
                                   :auto-webview true
                                   :extra-capabilities
                                   {"appium:includeSafariInWebviews" true}}
                            (System/getenv "SPEL_IOS_UDID")
                            (assoc :udid (System/getenv "SPEL_IOS_UDID"))))
            b           (backend/ios-backend ios-session)
            state       {:ios-session ios-session
                         :backend     b
                         :server-url  server-url
                         :server      server}]
        (.addShutdownHook (Runtime/getRuntime)
          (Thread. ^Runnable (fn [] (ios/stop! ios-session
                                      {:shutdown-simulator? true}))))
        (reset! !shared state)
        state))))

(defmacro ^:private when-e2e
  "Runs body only when SPEL_IOS_E2E is set; otherwise records a passing
   expectation and prints the skip message once."
  [& body]
  `(if e2e-enabled?
     (do ~@body)
     (do (binding [*out* *err*] (println ~skip-message))
         (expect (true? true)))))

(defdescribe ios-e2e-test
  "explicit Safari app fixture in WEBVIEW context (opt-in)"

  (describe "navigation and observable interaction"

    (it "switches between the bound app's native and webview contexts"
      (when-e2e
        (let [{:keys [ios-session backend]} (start-shared-session!)]
          (expect (= "NATIVE_APP" (ios/use-context! ios-session :native)))
          (let [snap (backend/capture-snapshot! backend {})]
            (expect (true? (:native snap)))
            (expect (str/includes? (:tree snap) "application"))
            (expect (pos? (long (:counter snap)))))
          (expect (str/starts-with?
                    (ios/use-context! ios-session :webview {:timeout-ms 20000})
                    "WEBVIEW")))))

    (it "navigates to the local touch page and reads title + viewport"
      (when-e2e
        (let [{:keys [backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          (expect (= "Touch Test Page" (backend/page-title backend)))
          (let [vp (backend/evaluate! backend
                     "({w: window.innerWidth, h: window.innerHeight})" [])]
            (expect (pos? (long (get vp "w"))))
            (expect (pos? (long (get vp "h"))))))))

    (it "snapshot -i returns interactive refs from the bound webview"
      (when-e2e
        (let [{:keys [backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          (let [snap (backend/capture-snapshot! backend {})]
            (expect (pos? (long (:counter snap))))
            (expect (str/includes? (str (:tree snap)) "Tap Me"))
            ;; A button ref must exist for the tap test below.
            (expect (some (fn [[_ info]] (= "button" (:role info)))
                      (:refs snap)))))))

    (it "click @ref triggers the button's click handler (observable DOM)"
      (when-e2e
        (let [{:keys [backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          (let [snap    (backend/capture-snapshot! backend {})
                btn-ref (some (fn [[ref-id info]]
                                (when (and (= "button" (:role info))
                                        (= "Tap Me" (:name info)))
                                  ref-id))
                          (:refs snap))]
            (expect (some? btn-ref))
            (backend/click! backend (str "@" btn-ref) {})
            (expect (= "button-tapped"
                      (backend/evaluate! backend
                        "document.getElementById('last-action').textContent" [])))))))

    (it "fill @ref updates the observable input mirror"
      (when-e2e
        (let [{:keys [backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          (let [snap      (backend/capture-snapshot! backend {})
                input-ref (some (fn [[ref-id info]]
                                  (when (= "textbox" (:role info)) ref-id))
                            (:refs snap))]
            (expect (some? input-ref))
            (backend/fill! backend (str "@" input-ref) "hello ios" {})
            (expect (= "hello ios"
                      (backend/evaluate! backend
                        "document.getElementById('input-value').textContent" [])))))))

    (it "scroll down uses a native gesture (scroll-position increases)"
      (when-e2e
        (let [{:keys [ios-session backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          (let [before (long (backend/evaluate! backend "window.scrollY" []))]
            (ios/scroll ios-session :down 400)
            (Thread/sleep 1000)
            (let [after (long (or (parse-long
                                    (str (backend/evaluate! backend
                                           "document.getElementById('scroll-position').textContent" [])))
                                0))]
              (expect (> after before)))))))

    (it "cookies set via document.cookie are readable through WebDriver"
      (when-e2e
        (let [{:keys [backend server-url]} (start-shared-session!)]
          (backend/navigate! backend (str server-url "/touch-page") {})
          ;; Observable setup: plant a cookie in the page, then read it back
          ;; through the W3C cookie endpoint (the iOS supported surface is
          ;; read-only — write goes through the page itself).
          (backend/evaluate! backend
            "document.cookie = 'spel_e2e=ios-cookie; path=/'" [])
          (let [cs (backend/cookies backend)
                c  (some #(when (= "spel_e2e" (:name %)) %) cs)]
            (expect (some? c))
            (expect (= "ios-cookie" (:value c)))))))

    (it "screenshot captures non-empty PNG bytes"
      (when-e2e
        (let [{:keys [backend]} (start-shared-session!)
              ^bytes bs (backend/screenshot! backend {})]
          (expect (pos? (alength bs)))
          ;; PNG magic header
          (expect (= [-119 80 78 71] (vec (take 4 bs)))))))

    (it "stale refs produce an explicit error, never coordinate fallback"
      (when-e2e
        (let [{:keys [backend]} (start-shared-session!)]
          (expect
            (try
              (backend/click! backend "@e00000" {})
              false
              (catch clojure.lang.ExceptionInfo e
                (true? (:stale-ref (ex-data e)))))))))

    (it "cleans up the shared session"
      (when-e2e
        (when-let [{:keys [ios-session server]} @!shared]
          ;; Shut the simulator down too — ownership semantics make this a
          ;; no-op unless THIS suite booted it, so a developer's own booted
          ;; simulator is never touched.
          (ios/stop! ios-session {:shutdown-simulator? true})
          (when server (ts/stop-test-server server))
          (reset! !shared nil)
          ;; Idempotent: second stop must not throw.
          (expect (nil? (ios/stop! ios-session))))))))

;; Silence unused-warning for webdriver alias (kept for REPL debugging).
(comment (webdriver/ready? "http://127.0.0.1:4723"))
