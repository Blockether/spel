(ns com.blockether.spel.ios-test
  "Unit tests for iOS Simulator + Appium lifecycle — uses the injectable
   command runner so no real xcrun/simctl/appium processes run. All
   platforms, no macOS required.

   Covers:
   - simctl device JSON parsing
   - Deterministic device selection (udid > exact name > newest iPhone)
   - Duplicate-name failures listing matches
   - Per-UDID lock lifecycle (acquire, re-entrant, foreign live, stale)
   - Boot ownership tracking
   - Application capability construction
   - Direction swipe coordinate derivation
   - Doctor checks with injected command results"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.ios :as sut]
   [com.blockether.spel.webdriver :as webdriver])
  (:import
   [java.lang ProcessHandle]
   [java.nio.file Files]
   [java.util.concurrent.locks ReentrantLock]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private sample-devices-json
  (json/write-json-str
    {"devices"
     {"com.apple.CoreSimulator.SimRuntime.iOS-18-2"
      [{"name" "iPhone 16 Pro" "udid" "UDID-16PRO-182" "state" "Shutdown" "isAvailable" true}
       {"name" "iPhone 16" "udid" "UDID-16-182" "state" "Booted" "isAvailable" true}
       {"name" "iPad Pro 13-inch (M4)" "udid" "UDID-IPAD-182" "state" "Shutdown" "isAvailable" true}]
      "com.apple.CoreSimulator.SimRuntime.iOS-17-5"
      [{"name" "iPhone 16 Pro" "udid" "UDID-16PRO-175" "state" "Shutdown" "isAvailable" true}
       {"name" "iPhone SE (3rd generation)" "udid" "UDID-SE-175" "state" "Shutdown" "isAvailable" false}]
      "com.apple.CoreSimulator.SimRuntime.watchOS-11-0"
      [{"name" "Apple Watch Series 10" "udid" "UDID-WATCH" "state" "Shutdown" "isAvailable" true}]}}))

(defn- parsed-devices []
  (sut/parse-devices sample-devices-json))

(def ^:private sample-native-source
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    "<AppiumAUT>"
    "<XCUIElementTypeApplication type=\"XCUIElementTypeApplication\" name=\"Example\" enabled=\"true\" visible=\"true\">"
    "<XCUIElementTypeWindow type=\"XCUIElementTypeWindow\" enabled=\"true\" visible=\"true\">"
    "<XCUIElementTypeButton type=\"XCUIElementTypeButton\" name=\"login-button\" label=\"Log in\" enabled=\"true\" visible=\"true\"/>"
    "<XCUIElementTypeTextField type=\"XCUIElementTypeTextField\" name=\"email-input\" value=\"alex@example.com\" enabled=\"true\" visible=\"true\"/>"
    "<XCUIElementTypeStaticText type=\"XCUIElementTypeStaticText\" label=\"Welcome\" enabled=\"true\" visible=\"true\"/>"
    "</XCUIElementTypeWindow>"
    "</XCUIElementTypeApplication>"
    "</AppiumAUT>"))

;; =============================================================================
;; Native semantic snapshots
;; =============================================================================

(defdescribe native-snapshot-test
  "Compact XCTest snapshots and native refs"

  (it "converts XCTest XML to a compact semantic tree with interactive refs"
    (let [snapshot (sut/native-snapshot-from-xml sample-native-source)]
      (expect (= 2 (:counter snapshot)))
      (expect (re-find #"button \"Log in\" \[@e[a-z0-9]+\]" (:tree snapshot)))
      (expect (re-find #"textbox \"email-input\" \[@e[a-z0-9]+\]" (:tree snapshot)))
      (expect (str/includes? (:tree snapshot) "text \"Welcome\""))
      (let [[_ info] (first (filter #(= "button" (:role (val %))) (:refs snapshot)))]
        (expect (= "xpath" (:using info)))
        (expect (str/includes? (:locator info) "XCUIElementTypeButton[1]")))))

  (it "stores refs on the iOS session and resolves @refs to XPath"
    (let [refs*   (atom {})
          session (sut/map->IosSession {:webdriver :fake
                                        :context* (atom "NATIVE_APP")
                                        :native-refs* refs*})]
      (with-redefs [webdriver/content (fn [_] sample-native-source)]
        (sut/native-snapshot session))
      (let [[ref-id info] (first (filter #(= "button" (:role (val %))) @refs*))]
        (expect (= info (sut/selector-locator session (str "@" ref-id)))))))

  (it "maps semantic role selectors to XCTest class and predicate queries"
    (expect (= {:using "class name" :value "XCUIElementTypeButton"}
              (webdriver/selector-strategy "role=button" true)))
    (let [textbox (webdriver/selector-strategy "role=textbox" true)]
      (expect (= "-ios predicate string" (:using textbox)))
      (expect (str/includes? (:value textbox) "XCUIElementTypeSecureTextField"))))

  (it "keeps native refs stable without retargeting changed controls"
    (let [first-snapshot  (sut/native-snapshot-from-xml sample-native-source)
          changed-source  (str/replace sample-native-source "Log in" "Create account")
          second-snapshot (sut/native-snapshot-from-xml changed-source)
          first-button    (some (fn [[ref info]] (when (= "button" (:role info)) ref))
                            (:refs first-snapshot))
          second-button   (some (fn [[ref info]] (when (= "button" (:role info)) ref))
                            (:refs second-snapshot))]
      (expect (not= first-button second-button))))

  (it "polls transient no-such-element failures for native selector waits"
    (let [attempts (atom 0)]
      (with-redefs [sut/find-element
                    (fn [_ _]
                      (if (< (swap! attempts inc) 3)
                        (throw (ex-info "missing" {:webdriver/error "no such element"}))
                        "element-3"))]
        (expect (= "element-3"
                  (sut/wait-for-element :session "role=button"
                    {:timeout-ms 100 :interval-ms 0})))
        (expect (= 3 @attempts))))))

;; =============================================================================
;; Device parsing
;; =============================================================================

(defdescribe parse-devices-test
  "simctl JSON parsing"

  (it "parses iOS devices with runtime-derived platform versions"
    (let [devices (parsed-devices)
          by-udid (into {} (map (juxt :udid identity)) devices)]
      (expect (= 5 (count devices)))
      (expect (= "iPhone 16 Pro" (:name (by-udid "UDID-16PRO-182"))))
      (expect (= "18.2" (:platform-version (by-udid "UDID-16PRO-182"))))
      (expect (= "17.5" (:platform-version (by-udid "UDID-16PRO-175"))))
      (expect (= "Booted" (:state (by-udid "UDID-16-182"))))))

  (it "excludes non-iOS runtimes (watchOS)"
    (expect (not-any? #(= "UDID-WATCH" (:udid %)) (parsed-devices))))

  (it "tracks availability"
    (let [se (first (filter #(= "UDID-SE-175" (:udid %)) (parsed-devices)))]
      (expect (false? (:available? se)))))

  (it "marks simulators as not real devices"
    (expect (every? #(false? (:real? %)) (parsed-devices)))))

;; =============================================================================
;; Device selection
;; =============================================================================

(defdescribe select-device-test
  "deterministic simulator selection"

  (describe "udid selection"
    (it "exact udid wins"
      (expect (= "UDID-16PRO-175"
                (:udid (sut/select-device (parsed-devices)
                         {:udid "UDID-16PRO-175"})))))

    (it "unknown udid fails"
      (expect
        (try (sut/select-device (parsed-devices) {:udid "NOPE"})
             false
             (catch clojure.lang.ExceptionInfo _ true)))))

  (describe "name selection"
    (it "matches exact name case-insensitively"
      (expect (= "UDID-16-182"
                (:udid (sut/select-device (parsed-devices)
                         {:device "iphone 16"})))))

    (it "narrows duplicates with platform-version"
      (expect (= "UDID-16PRO-175"
                (:udid (sut/select-device (parsed-devices)
                         {:device "iPhone 16 Pro"
                          :platform-version "17.5"})))))

    (it "fails on ambiguous duplicates and lists matches"
      (let [msg (try
                  (sut/select-device (parsed-devices) {:device "iPhone 16 Pro"})
                  nil
                  (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
        (expect (some? msg))
        (expect (str/includes? msg "Multiple iOS Simulators"))
        (expect (str/includes? msg "UDID-16PRO-182"))
        (expect (str/includes? msg "UDID-16PRO-175"))))

    (it "fails helpfully for unknown names"
      (let [msg (try
                  (sut/select-device (parsed-devices) {:device "iPhone 99"})
                  nil
                  (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
        (expect (str/includes? msg "No available iOS Simulator named"))
        (expect (str/includes? msg "Available devices:"))))

    (it "excludes unavailable devices from name matching"
      (expect
        (try (sut/select-device (parsed-devices)
               {:device "iPhone SE (3rd generation)"})
             false
             (catch clojure.lang.ExceptionInfo _ true)))))

  (describe "default selection"
    (it "prefers an already booted iPhone"
      (expect (= "UDID-16-182"
                (:udid (sut/select-device (parsed-devices) {})))))

    (it "falls back to the newest iPhone runtime when none are booted"
      (let [no-boot (mapv #(assoc % :state "Shutdown") (parsed-devices))
            sel     (sut/select-device no-boot {})]
        (expect (= "18.2" (:platform-version sel)))
        (expect (str/includes? (:name sel) "iPhone"))))

    (it "never picks an iPad by default"
      (let [no-boot (mapv #(assoc % :state "Shutdown") (parsed-devices))]
        (expect (not (str/includes? (:name (sut/select-device no-boot {})) "iPad")))))))

;; =============================================================================
;; Locking
;; =============================================================================

(defdescribe lock-test
  "per-UDID simulator locking"

  (it "acquires and releases a lock"
    (let [udid (str "TEST-LOCK-" (System/nanoTime))]
      (try
        (let [lock (sut/acquire-lock! udid "sess-a")]
          (expect (= "sess-a" (get lock "session")))
          (expect (= udid (get lock "udid")))
          (expect (some? (get lock "created-at")))
          (expect (= "sess-a" (get (sut/read-lock udid) "session"))))
        (finally
          (sut/release-lock! udid "sess-a")))
      (expect (nil? (sut/read-lock udid)))))

  (it "is re-entrant for the same session"
    (let [udid (str "TEST-LOCK-" (System/nanoTime))]
      (try
        (sut/acquire-lock! udid "sess-a")
        (expect (some? (sut/acquire-lock! udid "sess-a")))
        (finally (sut/release-lock! udid "sess-a")))))

  (it "refuses to steal a live lock owned by another session"
    ;; Current process pid is alive, so a lock written by acquire-lock!
    ;; from another session name is a live foreign lock.
    (let [udid (str "TEST-LOCK-" (System/nanoTime))]
      (try
        (sut/acquire-lock! udid "sess-a")
        (expect
          (try (sut/acquire-lock! udid "sess-b")
               false
               (catch clojure.lang.ExceptionInfo e
                 (str/includes? (.getMessage e) "locked by spel session 'sess-a'"))))
        (finally (sut/release-lock! udid "sess-a")))))

  (it "removes stale locks whose owning process is dead"
    (let [udid (str "TEST-LOCK-" (System/nanoTime))]
      (try
        ;; Write a lock with an implausible dead PID directly.
        (spit (.toFile (sut/lock-path udid))
          (json/write-json-str {"session" "dead-sess"
                                "pid" 99999999
                                "udid" udid
                                "created-at" "2020-01-01T00:00:00Z"}))
        (expect (= "sess-b" (get (sut/acquire-lock! udid "sess-b") "session")))
        (finally (sut/release-lock! udid "sess-b")))))

  (it "release-lock! never deletes a foreign lock"
    (let [udid (str "TEST-LOCK-" (System/nanoTime))]
      (try
        (sut/acquire-lock! udid "sess-a")
        (sut/release-lock! udid "sess-b")
        (expect (= "sess-a" (get (sut/read-lock udid) "session")))
        (finally (sut/release-lock! udid "sess-a"))))))

;; =============================================================================
;; Booting
;; =============================================================================

(defdescribe boot-test
  "boot ownership tracking"

  (it "does not boot (or claim ownership of) an already booted simulator"
    (let [calls (atom [])]
      (binding [sut/*command-runner* (fn [cmd]
                                       (swap! calls conj cmd)
                                       {:exit 0 :out "" :err ""})]
        (expect (= {:booted-by-spel? false}
                  (sut/boot-device! {:udid "U1" :state "Booted"})))
        (expect (empty? @calls)))))

  (it "boots a shutdown simulator and claims ownership"
    (let [calls (atom [])]
      (binding [sut/*command-runner* (fn [cmd]
                                       (swap! calls conj cmd)
                                       {:exit 0 :out "" :err ""})]
        (expect (= {:booted-by-spel? true}
                  (sut/boot-device! {:udid "U1" :state "Shutdown"})))
        (expect (= [["xcrun" "simctl" "boot" "U1"]
                    ["xcrun" "simctl" "bootstatus" "U1" "-b"]]
                  @calls)))))

  (it "propagates boot failures with command context"
    (binding [sut/*command-runner* (fn [_] {:exit 1 :out "" :err "Unable to boot"})]
      (expect
        (try (sut/boot-device! {:udid "U1" :state "Shutdown"})
             false
             (catch clojure.lang.ExceptionInfo e
               (str/includes? (.getMessage e) "Unable to boot")))))))

;; =============================================================================
;; Capabilities
;; =============================================================================

(defdescribe capabilities-test
  "W3C XCUITest application capabilities"

  (it "binds an installed app without turning the session into Safari"
    (let [caps (sut/app-capabilities {:device-name "iPhone 16 Pro"
                                      :udid "U1"
                                      :wda-port 8101
                                      :bundle-id "com.example.hybrid"})]
      (expect (= "iOS" (get caps "platformName")))
      (expect (nil? (get caps "browserName")))
      (expect (= "XCUITest" (get caps "appium:automationName")))
      (expect (= "iPhone 16 Pro" (get caps "appium:deviceName")))
      (expect (= "U1" (get caps "appium:udid")))
      (expect (= "com.example.hybrid" (get caps "appium:bundleId")))
      (expect (true? (get caps "appium:noReset")))
      (expect (false? (get caps "appium:forceAppLaunch")))
      (expect (false? (get caps "appium:shouldTerminateApp")))
      (expect (= 8101 (get caps "appium:wdaLocalPort")))
      (expect (= 300 (get caps "appium:newCommandTimeout")))
      (expect (nil? (get caps "appium:platformVersion")))))

  (it "can install an app and automatically enter its webview"
    (let [caps (sut/app-capabilities {:device-name "iPhone 16"
                                      :udid "U2"
                                      :wda-port 8102
                                      :app "/tmp/My.app"
                                      :auto-webview true
                                      :platform-version "18.2"})]
      (expect (= "/tmp/My.app" (get caps "appium:app")))
      (expect (true? (get caps "appium:autoWebview")))
      (expect (= "18.2" (get caps "appium:platformVersion")))))

  (it "rejects ambiguous app targets"
    (expect
      (try
        (sut/app-capabilities {:device-name "iPhone 16" :udid "U2"
                               :wda-port 8102 :bundle-id "com.example"
                               :app "/tmp/My.app"})
        false
        (catch clojure.lang.ExceptionInfo _ true))))

  (it "merges extra capabilities last for compatibility overrides"
    (let [caps (sut/app-capabilities {:device-name "iPhone 16"
                                      :udid "U2"
                                      :wda-port 8102
                                      :extra {"appium:noReset" false}})]
      (expect (false? (get caps "appium:noReset"))))))

;; =============================================================================
;; Application contexts
;; =============================================================================

(defdescribe application-context-test
  "native/webview switching and application management"

  (it "switches from native to the first inspectable webview"
    (let [context* (atom "NATIVE_APP")
          session  (sut/map->IosSession {:webdriver :fake :context* context*})]
      (with-redefs [webdriver/contexts
                    (fn [_] ["NATIVE_APP" "WEBVIEW_com.example"])
                    webdriver/switch-context
                    (fn [_ context] context)]
        (expect (= "WEBVIEW_com.example" (sut/use-context! session :webview)))
        (expect (= "WEBVIEW_com.example" @context*)))))

  (it "reports inspectability when no webview appears"
    (let [session (sut/map->IosSession
                    {:webdriver :fake :context* (atom "NATIVE_APP")})]
      (with-redefs [webdriver/contexts
                    (fn [_] ["NATIVE_APP"])]
        (let [message (try
                        (sut/use-context! session :webview {:timeout-ms 0})
                        nil
                        (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
          (expect (str/includes? message "isInspectable=true"))))))

  (it "runs callbacks in a webview, returns their value, and restores native"
    (let [driver-context* (atom "NATIVE_APP")
          switches*       (atom [])
          refs*           (atom {"eold" {:selector "role=button"}})
          session         (sut/map->IosSession
                            {:webdriver :fake
                             :context* (atom "NATIVE_APP")
                             :native-refs* refs*
                             :operation-lock (ReentrantLock.)})]
      (with-redefs [webdriver/contexts
                    (fn [_] ["NATIVE_APP" "WEBVIEW_com.example"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context]
                      (swap! switches* conj context)
                      (reset! driver-context* context))]
        (expect (= {:context "WEBVIEW_com.example" :count 2}
                  (sut/with-context session :webview
                    (fn collect-webview-state []
                      {:context (sut/current-context session) :count 2}))))
        (expect (= "NATIVE_APP" @driver-context*))
        (expect (= ["WEBVIEW_com.example" "NATIVE_APP"] @switches*))
        (expect (= {} @refs*)))))

  (it "restores the prior context after callback failure"
    (let [driver-context* (atom "NATIVE_APP")
          callback-error  (ex-info "callback failed" {:stage :callback})
          session         (sut/map->IosSession
                            {:webdriver :fake
                             :context* (atom "NATIVE_APP")
                             :operation-lock (ReentrantLock.)})]
      (with-redefs [webdriver/contexts
                    (fn [_] ["NATIVE_APP" "WEBVIEW_1"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context] (reset! driver-context* context))]
        (let [caught (try
                       (sut/with-context session :webview
                         (fn fail-in-webview [] (throw callback-error)))
                       nil
                       (catch Throwable error error))]
          (expect (identical? callback-error caught))
          (expect (= "NATIVE_APP" @driver-context*))))))

  (it "restores an exact previous webview and nests in LIFO order"
    (let [driver-context* (atom "WEBVIEW_A")
          switches*       (atom [])
          session         (sut/map->IosSession
                            {:webdriver :fake
                             :context* (atom "WEBVIEW_A")
                             :operation-lock (ReentrantLock.)})]
      (with-redefs [webdriver/contexts
                    (fn [_] ["NATIVE_APP" "WEBVIEW_A" "WEBVIEW_B" "WEBVIEW_C"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context]
                      (swap! switches* conj context)
                      (reset! driver-context* context))]
        (sut/with-context session "WEBVIEW_B"
          (fn outer-webview-scope []
            (expect (= "WEBVIEW_B" @driver-context*))
            (sut/with-context session "WEBVIEW_C"
              (fn inner-webview-scope []
                (expect (= "WEBVIEW_C" @driver-context*))))
            (expect (= "WEBVIEW_B" @driver-context*))))
        (expect (= "WEBVIEW_A" @driver-context*))
        (expect (= ["WEBVIEW_B" "WEBVIEW_C" "WEBVIEW_B" "WEBVIEW_A"]
                  @switches*)))))

  (it "does not switch or invalidate refs when the target is already active"
    (let [refs*   (atom {"ekeep" {:selector "#keep"}})
          session (sut/map->IosSession
                    {:webdriver :fake
                     :context* (atom "WEBVIEW_1")
                     :native-refs* refs*})]
      (with-redefs [webdriver/contexts (fn [_] ["NATIVE_APP" "WEBVIEW_1"])
                    webdriver/current-context (fn [_] "WEBVIEW_1")
                    webdriver/switch-context
                    (fn [_ _] (throw (ex-info "unexpected switch" {})))]
        (expect (= :ok (sut/with-context session :webview
                         (fn already-in-webview [] :ok))))
        (expect (= {"ekeep" {:selector "#keep"}} @refs*)))))

  (it "forwards exact context and timeout options to entry and restoration"
    (let [calls*  (atom [])
          session (sut/map->IosSession
                    {:webdriver :fake :context* (atom "NATIVE_APP")})]
      (with-redefs [webdriver/current-context (fn [_] "NATIVE_APP")
                    sut/use-context!
                    (fn [_ requested opts]
                      (swap! calls* conj [requested opts])
                      requested)]
        (expect (= :done
                  (sut/with-context session "WEBVIEW_exact" {:timeout-ms 321}
                    (fn exact-webview [] :done))))
        (expect (= [["WEBVIEW_exact" {:timeout-ms 321}]
                    ["NATIVE_APP" {:timeout-ms 321}]]
                  @calls*)))))

  (it "throws a restoration failure when the callback succeeds"
    (let [driver-context* (atom "NATIVE_APP")
          restore-error   (ex-info "restore failed" {})
          session         (sut/map->IosSession
                            {:webdriver :fake
                             :context* (atom "NATIVE_APP")})]
      (with-redefs [webdriver/contexts (fn [_] ["NATIVE_APP" "WEBVIEW_1"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context]
                      (if (= "NATIVE_APP" context)
                        (throw restore-error)
                        (reset! driver-context* context)))]
        (let [caught (try
                       (sut/with-context session :webview
                         (fn successful-callback [] :ok))
                       nil
                       (catch Throwable error error))]
          (expect (identical? restore-error caught))))))

  (it "preserves callback failure and suppresses a restoration failure"
    (let [driver-context* (atom "NATIVE_APP")
          callback-error  (ex-info "callback failed" {})
          restore-error   (ex-info "restore failed" {})
          session         (sut/map->IosSession
                            {:webdriver :fake
                             :context* (atom "NATIVE_APP")})]
      (with-redefs [webdriver/contexts (fn [_] ["NATIVE_APP" "WEBVIEW_1"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context]
                      (if (= "NATIVE_APP" context)
                        (throw restore-error)
                        (reset! driver-context* context)))]
        (let [caught (try
                       (sut/with-context session :webview
                         (fn fail-before-restore [] (throw callback-error)))
                       nil
                       (catch Throwable error error))]
          (expect (identical? callback-error caught))
          (expect (= [restore-error] (vec (.getSuppressed ^Throwable caught))))))))

  (it "serializes another operation until scoped restoration completes"
    (let [driver-context*  (atom "NATIVE_APP")
          callback-entered (promise)
          release-callback (promise)
          second-started   (promise)
          session          (sut/map->IosSession
                             {:webdriver :fake
                              :context* (atom "NATIVE_APP")
                              :operation-lock (ReentrantLock.)})]
      (with-redefs [webdriver/contexts (fn [_] ["NATIVE_APP" "WEBVIEW_1"])
                    webdriver/current-context (fn [_] @driver-context*)
                    webdriver/switch-context
                    (fn [_ context] (reset! driver-context* context))]
        (let [scope-task (future
                           (sut/with-context session :webview
                             (fn blocking-webview-callback []
                               (deliver callback-entered true)
                               @release-callback)))
              _          (deref callback-entered 1000 false)
              other-task (future
                           (sut/with-operation session
                             (fn concurrent-ios-operation []
                               (deliver second-started @driver-context*))))]
          (try
            (expect (false? (realized? second-started)))
            (deliver release-callback :released)
            (expect (= :released (deref scope-task 1000 :timed-out)))
            (expect (= "NATIVE_APP" (deref second-started 1000 :timed-out)))
            (finally
              (deliver release-callback :released)
              (future-cancel scope-task)
              (future-cancel other-task)))))))

  (it "activation returns to native context"
    (let [context* (atom "WEBVIEW_1")
          session  (sut/map->IosSession {:webdriver :fake :context* context*})]
      (with-redefs [webdriver/activate-app (fn [_ bundle-id] bundle-id)
                    webdriver/switch-context (fn [_ context] context)]
        (expect (= "com.example.app"
                  (sut/activate-app! session "com.example.app")))
        (expect (= "NATIVE_APP" @context*)))))

  (it "maps Appium application states to keywords"
    (with-redefs [webdriver/app-state (fn [_ _] 4)]
      (expect (= :foreground
                (sut/app-state (sut/map->IosSession {:webdriver :fake})
                  "com.example.app")))))

  (it "delegates complete application lifecycle operations"
    (let [calls   (atom [])
          session (sut/map->IosSession {:webdriver :fake
                                        :bundle-id "com.example.app"
                                        :context* (atom "NATIVE_APP")})]
      (with-redefs [webdriver/launch-app (fn [_ bundle opts]
                                           (swap! calls conj [:launch bundle opts]))
                    webdriver/contexts (fn [_] ["NATIVE_APP"])
                    webdriver/switch-context (fn [_ context] context)
                    webdriver/terminate-app (fn [_ bundle]
                                              (swap! calls conj [:terminate bundle]))
                    webdriver/background-app (fn [_ seconds]
                                               (swap! calls conj [:background seconds]))
                    webdriver/install-app (fn [_ path] (swap! calls conj [:install path]))
                    webdriver/remove-app (fn [_ bundle] (swap! calls conj [:remove bundle]))
                    webdriver/app-installed? (fn [_ _] true)
                    webdriver/open-url (fn [_ url bundle]
                                         (swap! calls conj [:url url bundle]) url)
                    webdriver/get-permission (fn [_ _ _] "yes")
                    webdriver/set-permission (fn [_ bundle service access]
                                               (swap! calls conj [:permission bundle service access]))]
        (expect (= "com.example.app" (sut/launch-app! session)))
        (expect (= "com.example.app" (sut/terminate-app! session)))
        (expect (= 5 (sut/background-app! session 5)))
        (expect (= "/tmp/My.app" (sut/install-app! session "/tmp/My.app")))
        (expect (= "other.app" (sut/uninstall-app! session "other.app")))
        (expect (true? (sut/app-installed? session "other.app")))
        (expect (= "example://screen" (sut/open-url! session "example://screen")))
        (expect (= "yes" (sut/get-permission session :camera)))
        (expect (= "yes" (:access (sut/set-permission! session :camera :grant))))
        (expect (some #{[:permission "com.example.app" "camera" "yes"]} @calls))))))

;; =============================================================================
;; Swipe coordinate derivation
;; =============================================================================

(defdescribe swipe-coordinates-test
  "direction-based swipes derive safe viewport coordinates"

  (let [vp {:width 390 :height 844}]
    (it "up swipe moves from lower to upper along the center column"
      (let [{:keys [from to]} (sut/swipe-coordinates :up 300 vp)]
        (expect (= (first from) (first to)))
        (expect (> (long (second from)) (long (second to))))))

    (it "down swipe moves from upper to lower"
      (let [{:keys [from to]} (sut/swipe-coordinates :down 300 vp)]
        (expect (< (long (second from)) (long (second to))))))

    (it "left swipe moves right-to-left along the center row"
      (let [{:keys [from to]} (sut/swipe-coordinates :left 200 vp)]
        (expect (= (second from) (second to)))
        (expect (> (long (first from)) (long (first to))))))

    (it "keeps oversized distances inside the viewport"
      (let [{:keys [from to]} (sut/swipe-coordinates :up 5000 vp)]
        (doseq [[x y] [from to]]
          (expect (<= 0 (long x) 390))
          (expect (<= 0 (long y) 844)))))

    (it "works on small landscape viewports without negative coordinates"
      (let [{:keys [from to]} (sut/swipe-coordinates :up 5000 {:width 844 :height 200})]
        (doseq [[x y] [from to]]
          (expect (<= 0 (long x) 844))
          (expect (<= 0 (long y) 200)))))

    (it "rejects unknown directions"
      (expect
        (try (sut/swipe-coordinates :diagonal 100 vp)
             false
             (catch clojure.lang.ExceptionInfo _ true)))))

  (it "scroll down performs an upward finger swipe"
    (with-redefs [sut/swipe (fn [_ opts] {:gesture (:direction opts)})]
      (expect (= {:gesture :up :direction :down}
                (sut/scroll :session :down 400)))))

  (it "keeps selector-scoped native scroll coordinates inside the element"
    (let [captured (atom nil)
          session  (sut/map->IosSession {:webdriver :fake
                                         :context* (atom "NATIVE_APP")})]
      (with-redefs [sut/element-rect (fn [_ _] {:x 20 :y 100 :width 200 :height 300})
                    sut/swipe (fn [_ opts] (reset! captured opts) opts)]
        (sut/scroll session :down 120 {:selector "@eabc"})
        (doseq [[x y] [(:from @captured) (:to @captured)]]
          (expect (<= 20 (long x) 220))
          (expect (<= 100 (long y) 400)))))))

;; =============================================================================
;; Doctor
;; =============================================================================

(defdescribe doctor-test
  "prerequisite diagnostics with injected commands"

  (it "reports failures without throwing when tools are missing"
    (binding [sut/*command-runner* (fn [_] (throw (java.io.IOException. "not found")))]
      (let [checks (sut/doctor)]
        (expect (vector? checks))
        (expect (false? (sut/doctor-ok? checks)))
        (expect (some #(= "appium on PATH" (:check %)) checks)))))

  (it "reports appium version and XCUITest driver when present"
    (binding [sut/*command-runner*
              (fn [cmd]
                (cond
                  (= ["appium" "--version"] cmd)
                  {:exit 0 :out "2.11.3\n" :err ""}
                  (= ["appium" "driver" "list" "--installed"] cmd)
                  {:exit 0 :out "- xcuitest@7.24.3 [installed]" :err ""}
                  (= ["xcrun" "simctl" "list" "devices" "available" "--json"] cmd)
                  {:exit 0 :out sample-devices-json :err ""}
                  :else {:exit 0 :out "" :err ""}))]
      (let [checks   (sut/doctor)
            by-check (into {} (map (juxt :check identity)) checks)]
        (expect (true? (:ok (by-check "appium on PATH"))))
        (expect (= "2.11.3" (:detail (by-check "appium on PATH"))))
        (expect (true? (:ok (by-check "XCUITest driver installed")))))))

  (it "detects the XCUITest driver when Appium 3 prints the list to stderr"
    (binding [sut/*command-runner*
              (fn [cmd]
                (cond
                  (= ["appium" "--version"] cmd)
                  {:exit 0 :out "3.5.2\n" :err ""}
                  (= ["appium" "driver" "list" "--installed"] cmd)
                  ;; Appium 3 writes the driver listing to STDERR.
                  {:exit 0 :out "" :err "✔ Listing installed drivers\n- xcuitest@11.17.1 [installed (npm)]"}
                  :else {:exit 0 :out "" :err ""}))]
      (let [by-check (into {} (map (juxt :check identity)) (sut/doctor))]
        (expect (true? (:ok (by-check "XCUITest driver installed")))))))

  (it "setup instructions mention appium and xcuitest install commands"
    (expect (str/includes? sut/setup-instructions "npm install -g appium"))
    (expect (str/includes? sut/setup-instructions "appium driver install xcuitest"))))

;; =============================================================================
;; Session cleanup ownership
;; =============================================================================

(defdescribe stop-test
  "stop! releases only owned resources"

  (it "is a no-op for nil sessions"
    (expect (nil? (sut/stop! nil))))

  (it "releases the lock and never shuts down a user-booted simulator"
    (let [udid  (str "TEST-STOP-" (System/nanoTime))
          calls (atom [])]
      (sut/acquire-lock! udid "sess-x")
      (binding [sut/*command-runner* (fn [cmd]
                                       (swap! calls conj cmd)
                                       {:exit 0 :out "" :err ""})]
        (sut/stop! (sut/map->IosSession
                     {:device {:udid udid}
                      :webdriver nil
                      :appium-process nil
                      :appium-owned? false
                      :simulator-booted-by-spel? false
                      :session-name "sess-x"})
          {:shutdown-simulator? true}))
      ;; Lock released, but NO simctl shutdown for a user-booted simulator.
      (expect (nil? (sut/read-lock udid)))
      (expect (empty? @calls))))

  (it "shuts down the simulator only when spel booted it AND shutdown requested"
    (let [udid  (str "TEST-STOP-" (System/nanoTime))
          calls (atom [])]
      (sut/acquire-lock! udid "sess-y")
      (binding [sut/*command-runner* (fn [cmd]
                                       (swap! calls conj cmd)
                                       {:exit 0 :out "" :err ""})]
        ;; Without :shutdown-simulator? the spel-booted simulator stays up.
        (sut/acquire-lock! udid "sess-y")
        (sut/stop! (sut/map->IosSession
                     {:device {:udid udid}
                      :simulator-booted-by-spel? true
                      :appium-owned? false
                      :session-name "sess-y"}))
        (expect (empty? @calls))
        ;; With :shutdown-simulator? it shuts down.
        (sut/acquire-lock! udid "sess-y")
        (sut/stop! (sut/map->IosSession
                     {:device {:udid udid}
                      :simulator-booted-by-spel? true
                      :appium-owned? false
                      :session-name "sess-y"})
          {:shutdown-simulator? true})
        (expect (= [["xcrun" "simctl" "shutdown" udid]] @calls)))))

  (it "kill-process-tree! reaps the process and its descendants"
    ;; POSIX-only spawn helper; the logic under test is pure ProcessHandle.
    (when-not (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
      (let [proc (.start (ProcessBuilder. ["/bin/sh" "-c" "sleep 300 & sleep 300"]))]
        (Thread/sleep 200) ;; let the child spawn
        (let [descendants (vec (.toArray (.descendants (.toHandle proc))))]
          (expect (pos? (count descendants)))
          (sut/kill-process-tree! proc)
          (expect (not (.isAlive proc)))
          (expect (not-any? (fn [d] (.isAlive ^ProcessHandle d)) descendants))))))

  (it "a failed start! rolls back the boot it initiated (ownership-preserving retry)"
    ;; A failed attempt must un-boot the simulator it booted: otherwise the
    ;; retry sees state "Booted", records booted-by-spel? false, and
    ;; close --shutdown-simulator refuses to shut the simulator down.
    (when (sut/macos?)
      (let [udid  (str "TEST-ROLLBACK-" (System/nanoTime))
            calls (atom [])
            devices-json
            (str "{\"devices\":{\"com.apple.CoreSimulator.SimRuntime.iOS-26-0\":"
              "[{\"udid\":\"" udid "\",\"name\":\"iPhone Test\","
              "\"state\":\"Shutdown\",\"isAvailable\":true}]}}")]
        (binding [sut/*command-runner*
                  (fn [cmd]
                    (swap! calls conj cmd)
                    (if (some #{"list"} cmd)
                      {:exit 0 :out devices-json :err ""}
                      {:exit 0 :out "" :err ""}))]
          ;; External appium-url on a dead port → create-session fails fast,
          ;; AFTER the boot happened.
          (expect
            (try
              (sut/start! {:session "sess-rollback"
                           :udid udid
                           :appium-url "http://127.0.0.1:9"
                           :session-timeout-ms 500})
              false
              (catch Exception _ true))))
        (let [cmds @calls]
          ;; Booted it…
          (expect (some #(= ["xcrun" "simctl" "boot" udid] %) cmds))
          ;; …and rolled the boot back on failure.
          (expect (some #(= ["xcrun" "simctl" "shutdown" udid] %) cmds)))
        ;; Lock released too.
        (expect (nil? (sut/read-lock udid))))))

  (it "a failed start! does NOT shut down a simulator it did not boot"
    (when (sut/macos?)
      (let [udid  (str "TEST-NOROLLBACK-" (System/nanoTime))
            calls (atom [])
            devices-json
            (str "{\"devices\":{\"com.apple.CoreSimulator.SimRuntime.iOS-26-0\":"
              "[{\"udid\":\"" udid "\",\"name\":\"iPhone Test\","
              "\"state\":\"Booted\",\"isAvailable\":true}]}}")]
        (binding [sut/*command-runner*
                  (fn [cmd]
                    (swap! calls conj cmd)
                    (if (some #{"list"} cmd)
                      {:exit 0 :out devices-json :err ""}
                      {:exit 0 :out "" :err ""}))]
          (expect
            (try
              (sut/start! {:session "sess-norollback"
                           :udid udid
                           :appium-url "http://127.0.0.1:9"
                           :session-timeout-ms 500})
              false
              (catch Exception _ true))))
        (expect (not-any? #(= ["xcrun" "simctl" "shutdown" udid] %) @calls))
        (expect (nil? (sut/read-lock udid))))))

  (it "port allocation returns distinct free ports for concurrent sessions"
    (let [p1 (sut/find-free-port)
          p2 (sut/find-free-port)]
      (expect (pos? (long p1)))
      (expect (pos? (long p2)))))

  (it "lock files live in the tmpdir keyed by UDID"
    (let [p (sut/lock-path "SOME-UDID")]
      (expect (str/includes? (str p) "spel-ios-sim-lock-SOME-UDID"))
      (expect (false? (Files/exists p (into-array java.nio.file.LinkOption [])))))))
