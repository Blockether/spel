(ns com.blockether.spel.ios
  "iOS Simulator + Appium lifecycle for the application provider.

   The provider binds XCUITest to an installed application by bundle id (or
   installs a simulator-built .app supplied by path). It normally starts in
   native context; callers can enter an inspectable WKWebView temporarily to
   use DOM, CSS, and JavaScript operations.

   Responsibilities:
   - Validate platform prerequisites (macOS, xcrun/simctl, Appium + XCUITest)
   - Discover available simulators via `xcrun simctl list devices --json`
   - Select one deterministically (udid > exact name > newest iPhone)
   - Acquire a per-device (UDID) lock so unrelated spel sessions never share
     a simulator
   - Boot and wait for the simulator
   - Start (or connect to) Appium on per-session loopback ports
   - Create an XCUITest application WebDriver session
   - Track ownership of processes/simulator state and close ONLY owned
     resources (never a user's Appium or an already-booted simulator)

   Unit tests inject a command runner via `*command-runner*` so no real
   `xcrun` process is required."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [clojure.xml :as xml]
   [com.blockether.spel.webdriver :as webdriver])
  (:import
   [java.io ByteArrayInputStream File]
   [java.lang ProcessBuilder$Redirect ProcessHandle]
   [java.net InetAddress ServerSocket]
   [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
   [java.time Instant]
   [java.util.concurrent.locks ReentrantLock]))

;; =============================================================================
;; Data model
;; =============================================================================

(defrecord IosDevice
           [name udid state runtime platform-version available? real?])

(defrecord IosSession
           [device
            webdriver
            appium-url
            appium-process
            appium-owned?
            simulator-booted-by-spel?
            simulator-lock
            session-name
            wda-port
            bundle-id
            app
            context*
            native-refs*
            operation-lock])

;; =============================================================================
;; Injectable command runner
;; =============================================================================

(defn run-command
  "Default command runner. Executes `cmd` (vector of strings) and returns
   {:exit long :out string :err string}. Blocks until the process exits."
  [cmd]
  (let [pb   (ProcessBuilder. ^java.util.List (vec cmd))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    {:exit (long exit) :out out :err err}))

(def ^:dynamic *command-runner*
  "Command runner fn: (fn [cmd-vector]) -> {:exit :out :err}.
   Rebind in tests to avoid executing real xcrun/simctl processes."
  run-command)

(defn- run!*
  "Runs a command through the injectable runner, throwing ex-info on
   non-zero exit."
  [cmd]
  (let [{:keys [exit out err] :as result} (*command-runner* cmd)]
    (if (zero? (long exit))
      result
      (throw (ex-info (str "Command failed (" exit "): " (str/join " " cmd)
                        (when-not (str/blank? err) (str "\n" (str/trim err))))
               {:ios/command cmd :ios/exit exit :ios/out out :ios/err err})))))

;; =============================================================================
;; Prerequisites / doctor
;; =============================================================================

(defn macos?
  "Returns true when running on macOS."
  []
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(defn- command-ok?
  "Returns true when `cmd` runs with exit 0 (runner-injected, exceptions →
   false)."
  [cmd]
  (try
    (zero? (long (:exit (*command-runner* cmd))))
    (catch Exception _ false)))

(defn doctor
  "Runs iOS provider diagnostics. Returns a vector of check maps:
   {:check string :ok boolean :detail string?}.

   Does NOT install anything — reports actionable state only."
  []
  (let [os-ok    (macos?)
        xcrun-ok (and os-ok (command-ok? ["xcrun" "--version"]))
        xcode-ok (and xcrun-ok (command-ok? ["xcode-select" "-p"]))
        simctl-ok (and xcrun-ok (command-ok? ["xcrun" "simctl" "help"]))
        devices  (when simctl-ok
                   (try
                     (let [{:keys [out]} (run!* ["xcrun" "simctl" "list" "devices" "available" "--json"])]
                       (count (filter :available?
                                (let [parsed (json/read-json out)]
                                  (mapcat (fn [[runtime devs]]
                                            (map (fn [d]
                                                   {:available? (get d "isAvailable")
                                                    :runtime runtime})
                                              devs))
                                    (get parsed "devices"))))))
                     (catch Exception _ nil)))
        appium-version (try
                         (let [{:keys [exit out]} (*command-runner* ["appium" "--version"])]
                           (when (zero? (long exit)) (str/trim out)))
                         (catch Exception _ nil))
        xcuitest-ok (when appium-version
                      (try
                        ;; Appium 3 prints the installed-driver list to STDERR
                        ;; (Appium 2 used stdout) — check both streams.
                        (let [{:keys [exit out err]} (*command-runner* ["appium" "driver" "list" "--installed"])]
                          (and (zero? (long exit))
                            (str/includes? (str/lower-case (str out "\n" err)) "xcuitest")))
                        (catch Exception _ false)))]
    [{:check "macOS" :ok os-ok
      :detail (or (System/getProperty "os.name") "unknown")}
     {:check "xcrun available" :ok (boolean xcrun-ok)}
     {:check "Xcode developer directory selected" :ok (boolean xcode-ok)}
     {:check "simctl runs" :ok (boolean simctl-ok)}
     {:check "iOS Simulators available" :ok (boolean (and devices (pos? (long devices))))
      :detail (when devices (str devices " available simulator(s)"))}
     {:check "appium on PATH" :ok (boolean appium-version)
      :detail appium-version}
     {:check "XCUITest driver installed" :ok (boolean xcuitest-ok)}]))

(def setup-instructions
  "Human-facing setup instructions for the iOS provider."
  (str/join \newline
    ["Appium with the XCUITest driver is required for the iOS provider."
     ""
     "Install it with:"
     "  npm install -g appium"
     "  appium driver install xcuitest"
     ""
     "Then verify from SCI with:"
     "  (spel/ios-doctor)"]))

(defn doctor-ok?
  "Returns true when every doctor check passes."
  [checks]
  (every? :ok checks))

;; =============================================================================
;; Device discovery
;; =============================================================================

(defn- runtime->platform-version
  "Extracts a platform version from a simctl runtime identifier.
   e.g. \"com.apple.CoreSimulator.SimRuntime.iOS-18-2\" → \"18.2\"."
  [^String runtime]
  (when runtime
    (when-let [[_ ver] (re-find #"iOS-([\d-]+)$" runtime)]
      (str/replace ver "-" "."))))

(defn- ios-runtime?
  "Returns true when a simctl runtime identifier is an iOS runtime."
  [^String runtime]
  (boolean (and runtime (re-find #"SimRuntime\.iOS" runtime))))

(defn parse-devices
  "Parses `xcrun simctl list devices --json` output into IosDevice records.
   Only iOS runtimes are included; real devices are never in simctl output."
  [^String json-str]
  (let [parsed (json/read-json json-str)]
    (into []
      (for [[runtime devs] (get parsed "devices")
            :when (ios-runtime? runtime)
            d devs]
        (->IosDevice
          (get d "name")
          (get d "udid")
          (get d "state")
          runtime
          (runtime->platform-version runtime)
          (boolean (get d "isAvailable"))
          false)))))

(defn list-devices
  "Lists available iOS Simulators via simctl. Returns vector of IosDevice."
  []
  (let [{:keys [out]} (run!* ["xcrun" "simctl" "list" "devices" "available" "--json"])]
    (parse-devices out)))

(defn- version-sort-key
  "Builds a sortable vector from a dotted version string (\"18.2\" → [18 2])."
  [^String v]
  (if (str/blank? v)
    [0]
    (mapv #(or (parse-long %) 0) (str/split v #"\."))))

(defn- describe-devices
  "Formats devices for error messages."
  [devices]
  (str/join "\n"
    (map (fn [{:keys [name udid platform-version state]}]
           (str "  " name " (iOS " platform-version ", " state ", " udid ")"))
      devices)))

(defn select-device
  "Selects a simulator deterministically.

   Params:
   `devices` - Seq of IosDevice (from list-devices/parse-devices).
   `opts`    - Map:
     :udid             - String. Exact UDID match (wins over everything).
     :device           - String. Exact case-insensitive name match.
     :platform-version - String. Narrows duplicate names.

   Rules:
   1. :udid exact match wins.
   2. :device prefers an exact case-insensitive name.
   3. :platform-version narrows duplicates.
   4. Remaining duplicates → ex-info listing matches (never guess).
   5. No request → prefer an already Booted iPhone; else the newest
      available iPhone runtime (deterministic tiebreak by name).

   Returns an IosDevice. Throws ex-info when nothing matches."
  [devices {:keys [udid device platform-version]}]
  (let [available (filterv :available? devices)]
    (cond
      udid
      (or (first (filter #(= udid (:udid %)) available))
        (throw (ex-info (str "No available iOS Simulator with UDID " udid)
                 {:ios/udid udid})))

      device
      (let [lc      (str/lower-case device)
            by-name (filterv #(= lc (str/lower-case (str (:name %)))) available)
            by-ver  (if platform-version
                      (filterv #(= platform-version (:platform-version %)) by-name)
                      by-name)]
        (cond
          (empty? by-name)
          (throw (ex-info (str "No available iOS Simulator named \"" device "\".\n"
                            "Available devices:\n" (describe-devices available))
                   {:ios/device device}))

          (empty? by-ver)
          (throw (ex-info (str "No iOS Simulator named \"" device
                            "\" with platform version " platform-version ".\n"
                            "Matching names:\n" (describe-devices by-name))
                   {:ios/device device :ios/platform-version platform-version}))

          (> (count by-ver) 1)
          (throw (ex-info (str "Multiple iOS Simulators match \"" device "\"."
                            " Narrow with --platform-version or --udid:\n"
                            (describe-devices by-ver))
                   {:ios/device device
                    :ios/matches (mapv :udid by-ver)}))

          :else (first by-ver)))

      :else
      (let [iphones (filterv #(str/includes? (str/lower-case (str (:name %))) "iphone")
                      available)
            booted  (filterv #(= "Booted" (:state %)) iphones)
            pool    (if (seq booted) booted iphones)]
        (when (empty? pool)
          (throw (ex-info (str "No available iPhone simulator found.\n"
                            "Available devices:\n" (describe-devices available))
                   {})))
        (->> pool
          (sort-by (fn [d] [(version-sort-key (:platform-version d)) (:name d)]))
          last)))))

;; =============================================================================
;; Device locking
;; =============================================================================

(defn lock-path
  "Returns the per-UDID simulator lock file path."
  ^Path [^String udid]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-ios-sim-lock-" udid ".json")
    (into-array String [])))

(defn- pid-alive?
  "Returns true when a PID belongs to a live process."
  [pid]
  (try
    (if-let [ph (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
      (.isAlive ^java.lang.ProcessHandle ph)
      false)
    (catch Exception _ false)))

(defn read-lock
  "Reads a simulator lock file. Returns the lock map or nil."
  [^String udid]
  (let [p (lock-path udid)]
    (when (Files/exists p (into-array LinkOption []))
      (try
        (json/read-json (String. (Files/readAllBytes p)))
        (catch Exception _ nil)))))

(defn acquire-lock!
  "Acquires the per-UDID simulator lock for `session`.

   - No lock, or a stale lock whose owning PID is dead → acquire.
   - A live lock owned by the SAME session → keep (re-entrant).
   - A live lock owned by another session → throws ex-info. Never steals.

   Returns the lock map on success."
  [^String udid ^String session]
  (let [existing (read-lock udid)
        owner-pid (some-> (get existing "pid") long)
        live?     (and owner-pid (pid-alive? owner-pid))]
    (cond
      (and existing live? (= session (get existing "session")))
      existing

      (and existing live?)
      (throw (ex-info (str "Simulator " udid " is locked by spel session '"
                        (get existing "session") "' (pid " owner-pid ").\n"
                        "Use a different simulator or close that session first.")
               {:ios/udid udid :ios/lock existing}))

      :else
      (let [lock {"session"    session
                  "pid"        (.pid (java.lang.ProcessHandle/current))
                  "udid"       udid
                  "created-at" (str (Instant/now))}]
        (Files/writeString (lock-path udid)
          (json/write-json-str lock)
          ^"[Ljava.nio.file.OpenOption;"
          (into-array OpenOption [StandardOpenOption/CREATE
                                  StandardOpenOption/TRUNCATE_EXISTING
                                  StandardOpenOption/WRITE]))
        lock))))

(defn release-lock!
  "Releases the per-UDID lock, but only when owned by `session`.
   Idempotent — missing or foreign locks are left untouched."
  [^String udid ^String session]
  (when-let [lock (read-lock udid)]
    (when (= session (get lock "session"))
      (try
        (Files/deleteIfExists (lock-path udid))
        (catch Exception _ nil)))))

;; =============================================================================
;; Booting
;; =============================================================================

(defn boot-device!
  "Boots the simulator when not already booted and waits for boot completion.

   Returns {:booted-by-spel? boolean} — true only when spel initiated the
   boot (ownership drives shutdown policy)."
  [{:keys [udid state]}]
  (if (= "Booted" state)
    {:booted-by-spel? false}
    (do
      (run!* ["xcrun" "simctl" "boot" udid])
      (run!* ["xcrun" "simctl" "bootstatus" udid "-b"])
      {:booted-by-spel? true})))

(defn shutdown-device!
  "Shuts down a simulator by UDID. Best-effort."
  [^String udid]
  (try
    (run!* ["xcrun" "simctl" "shutdown" udid])
    true
    (catch Exception _ false)))

(defn kill-process-tree!
  "Destroys a process and ALL of its descendants. Best-effort: TERM
   everything, wait briefly, then KILL survivors.

   A plain Process.destroy on Appium leaves its xcodebuild/WebDriverAgent
   children running — a surviving WDA startup from a FAILED attempt later
   kills any newer WDA on the same UDID, breaking the retry's native
   gestures (mobile: tap / swipe) while web-context commands keep working."
  [^Process proc]
  (when proc
    (let [handle      (.toHandle proc)
          descendants (vec (.toArray (.descendants handle)))]
      (doseq [d descendants]
        (try (.destroy ^ProcessHandle d) (catch Exception _ nil)))
      (try (.destroy proc) (catch Exception _ nil))
      (try (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS)
           (catch Exception _ nil))
      (doseq [d descendants]
        (try
          (when (.isAlive ^ProcessHandle d)
            (.destroyForcibly ^ProcessHandle d))
          (catch Exception _ nil)))
      (when (.isAlive proc)
        (try (.destroyForcibly proc) (catch Exception _ nil)))
      nil)))

;; =============================================================================
;; Appium lifecycle
;; =============================================================================

(defn find-free-port
  "Allocates a free loopback TCP port via bind(0)."
  []
  (with-open [sock (ServerSocket. 0 1 (InetAddress/getLoopbackAddress))]
    (.getLocalPort sock)))

(defn- appium-log-path
  "Per-session Appium log file path."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".appium.log")
    (into-array String [])))

(defn start-appium!
  "Starts the installed `appium` executable on a free loopback port.

   - Never runs `npx appium` (implicit network install).
   - Never passes `--relaxed-security`.
   - Output is redirected to the per-session Appium log file (avoids pipe
     backpressure blocking a verbose process).
   - Polls GET /status until ready or `:startup-timeout-ms` expires.

   Params:
   `opts` - Map:
     :session            - String. Named spel session (log naming).
     :port               - Long, optional. Defaults to a free port.
     :startup-timeout-ms - Long, default 30000.

   Returns {:process Process :port long :url string :log-path string}.
   Throws ex-info when Appium fails to become ready (process is killed)."
  [{:keys [session port startup-timeout-ms]}]
  (let [port     (long (or port (find-free-port)))
        url      (str "http://127.0.0.1:" port)
        log-file (.toFile (appium-log-path (or session "default")))
        cmd      ["appium" "server"
                  "--address" "127.0.0.1"
                  "--port" (str port)]
        pb       (doto (ProcessBuilder. ^java.util.List (vec cmd))
                   (.redirectOutput (ProcessBuilder$Redirect/appendTo log-file))
                   (.redirectErrorStream true))
        ^Process proc
        (try
          (.start pb)
          (catch java.io.IOException e
            (throw (ex-info (str "Failed to start appium: " (.getMessage e)
                              "\n\n" setup-instructions)
                     {:ios/appium-cmd cmd}
                     e))))
        deadline (+ (System/currentTimeMillis) (long (or startup-timeout-ms 30000)))]
    (loop []
      (cond
        (webdriver/ready? url)
        {:process proc :port port :url url :log-path (str (.getAbsolutePath log-file))}

        (not (.isAlive proc))
        (throw (ex-info (str "Appium exited during startup (exit " (.exitValue proc)
                          "). See log: " (.getAbsolutePath log-file))
                 {:ios/appium-log (str (.getAbsolutePath log-file))}))

        (> (System/currentTimeMillis) deadline)
        (do
          (kill-process-tree! proc)
          (throw (ex-info (str "Appium did not become ready within "
                            (or startup-timeout-ms 30000) "ms. See log: "
                            (.getAbsolutePath log-file))
                   {:ios/appium-log (str (.getAbsolutePath log-file))})))

        :else
        (do (Thread/sleep 250) (recur))))))

;; =============================================================================
;; Application capabilities
;; =============================================================================

(defn app-capabilities
  "Builds W3C-compliant XCUITest application capabilities.

   Unlike a Safari session, this intentionally omits `browserName`. Supply
   either `:bundle-id` to bind to an installed app or `:app` to install and
   launch a simulator-built .app. With neither target, XCUITest starts on the Home
   screen so the caller can activate an app later.

   The non-destructive defaults preserve an already-running app when binding:
   no reset, no forced relaunch, and no termination when the session closes.

   Params:
   `opts` - Map:
     :device-name       - String. Simulator name.
     :udid              - String. Simulator UDID.
     :wda-port          - Long. WebDriverAgent local port.
     :platform-version  - String, optional.
     :bundle-id         - String, optional. Installed app bundle identifier.
     :app               - String, optional. Absolute simulator .app path or URL.
     :auto-webview      - Boolean. Enter the first inspectable webview.
     :extra             - Map, optional. Merged last."
  [{:keys [device-name udid wda-port platform-version bundle-id app
           auto-webview extra]}]
  (when (and bundle-id app)
    (throw (ex-info "Use either :bundle-id or :app for an iOS session, not both."
             {:ios/bundle-id bundle-id :ios/app app})))
  (cond-> {"platformName"               "iOS"
           "appium:automationName"      "XCUITest"
           "appium:deviceName"          device-name
           "appium:udid"                udid
           "appium:noReset"             true
           "appium:forceAppLaunch"      false
           "appium:shouldTerminateApp"  false
           "appium:wdaLocalPort"        (long wda-port)
           "appium:newCommandTimeout"   300}
    platform-version (assoc "appium:platformVersion" platform-version)
    bundle-id        (assoc "appium:bundleId" bundle-id)
    app              (assoc "appium:app" app)
    auto-webview     (assoc "appium:autoWebview" true)
    (seq extra)      (merge extra)))

;; =============================================================================
;; Session lifecycle
;; =============================================================================

(defn start!
  "Starts a full iOS application session: select → lock → boot → Appium →
   WebDriver session.

   Params:
   `opts` - Map:
     :session            - String. Named spel session (required for locking).
     :device             - String, optional. Simulator name.
     :udid               - String, optional. Simulator UDID.
     :platform-version   - String, optional.
     :bundle-id          - String, optional. Bind an installed application.
     :app                - String, optional. Install/bind a simulator .app.
     :auto-webview       - Boolean. Enter the first inspectable WKWebView.
     :extra-capabilities - Map, optional. Advanced Appium capabilities.
     :appium-url         - String, optional. Connect to an EXTERNAL Appium —
                           spel will never kill it.
     :startup-timeout-ms - Long, optional. Appium startup timeout.
     :session-timeout-ms - Long, optional. WebDriver session-creation timeout.

   Returns an IosSession record."
  [{:keys [session device udid platform-version bundle-id app auto-webview
           extra-capabilities appium-url startup-timeout-ms session-timeout-ms]
    :as _opts}]
  (when-not (macos?)
    (throw (ex-info (str "The iOS provider requires macOS with Xcode. "
                      "Current OS: " (System/getProperty "os.name"))
             {:ios/os (System/getProperty "os.name")})))
  (let [session-name (or session "default")
        selected     (select-device (list-devices)
                       {:udid udid :device device :platform-version platform-version})
        lock         (acquire-lock! (:udid selected) session-name)
        ;; Rollback state — a FAILED start must leave NO trace. Surviving
        ;; xcodebuild/WDA work from this attempt would later kill a retry's
        ;; WDA on the same UDID, and a leaked boot would make the retry see
        ;; the simulator as "already Booted" and lose shutdown ownership.
        booted?*     (volatile! false)
        appium-proc* (volatile! nil)]
    (try
      (let [{:keys [booted-by-spel?]} (boot-device! selected)
            _         (vreset! booted?* (boolean booted-by-spel?))
            appium    (when-not appium-url
                        (start-appium! {:session session-name
                                        :startup-timeout-ms startup-timeout-ms}))
            _         (vreset! appium-proc* (:process appium))
            base-url  (or appium-url (:url appium))
            wda-port  (long (find-free-port))
            caps      (app-capabilities {:device-name      (:name selected)
                                         :udid             (:udid selected)
                                         :wda-port         wda-port
                                         :platform-version (:platform-version selected)
                                         :bundle-id        bundle-id
                                         :app              app
                                         :auto-webview     auto-webview
                                         :extra            extra-capabilities})
            wd        (webdriver/create-session base-url caps
                        {:timeout-ms (or session-timeout-ms 180000)})]
        (->IosSession selected wd base-url (:process appium)
          (boolean appium)          ;; appium-owned? — false for external URL
          (boolean booted-by-spel?)
          lock
          session-name
          wda-port
          (or bundle-id (get (:capabilities wd) "appium:bundleId"))
          app
          (atom (try (webdriver/current-context wd)
                     (catch Exception _
                       ;; Application sessions start natively unless Appium
                       ;; was asked to auto-enter a webview.
                       (when-not auto-webview "NATIVE_APP"))))
          (atom {})
          (ReentrantLock. true)))
      (catch Exception e
        ;; Full rollback, in dependency order: reap the entire Appium tree
        ;; (incl. xcodebuild/WDA), un-boot the simulator IF this attempt
        ;; booted it (also reaps the on-device WDA runner), release the lock.
        (when-let [^Process p @appium-proc*]
          (try (kill-process-tree! p) (catch Exception _ nil)))
        (when @booted?*
          (shutdown-device! (:udid selected)))
        (release-lock! (:udid selected) session-name)
        (throw e)))))

(defn stop!
  "Stops an iOS session idempotently, releasing ONLY owned resources:

   1. Delete the WebDriver session.
   2. Kill the ENTIRE Appium process tree (incl. xcodebuild/WDA children)
      only when :appium-owned? is true (never external).
   3. Release the simulator lock.
   4. Shut down the simulator only when `shutdown-simulator?` is requested
      AND spel booted it.

   Safe to call multiple times and from shutdown hooks."
  ([ios-session] (stop! ios-session {}))
  ([{:keys [device webdriver appium-process appium-owned?
            simulator-booted-by-spel? session-name] :as ios-session}
    {:keys [shutdown-simulator?]}]
   (when ios-session
     (when webdriver
       (try (webdriver/delete-session! webdriver) (catch Exception _ nil)))
     (when (and appium-owned? appium-process)
       (try (kill-process-tree! appium-process) (catch Exception _ nil)))
     (when (:udid device)
       (release-lock! (:udid device) (or session-name "default")))
     (when (and shutdown-simulator? simulator-booted-by-spel? (:udid device))
       (shutdown-device! (:udid device)))
     nil)))

;; =============================================================================
;; Application contexts
;; =============================================================================

(defn with-operation
  "Runs callback while holding the iOS session's reentrant operation lock.
   Sessions created before operation locking (and lightweight test sessions)
   may omit the lock; those callbacks execute directly."
  [ios-session callback]
  (if-let [^ReentrantLock lock (:operation-lock ios-session)]
    (do
      (.lock lock)
      (try
        (callback)
        (finally
          (.unlock lock))))
    (callback)))

(defn contexts
  "Returns the available automation contexts for an iOS session. The result
   always contains NATIVE_APP and may contain inspectable WEBVIEW_* entries."
  [{:keys [webdriver]}]
  (webdriver/contexts webdriver))

(defn context-details
  "Returns detailed native/webview context records. Webview records include
   URL, title, and bundle metadata when XCUITest can inspect them."
  [{:keys [webdriver]}]
  (mapv (fn [record]
          (into {}
            (map (fn [[k v]] [(keyword k) v]))
            record))
    (webdriver/context-details webdriver)))

(defn- read-current-context
  "Reads Appium's exact current context and synchronizes the local cache.
   Falls back to the cache only when Appium cannot answer."
  [{:keys [webdriver context*]}]
  (let [context (try
                  (webdriver/current-context webdriver)
                  (catch Exception _
                    (when context* @context*)))]
    (when (and context context*)
      (reset! context* context))
    context))

(defn current-context
  "Returns the active automation context name."
  [ios-session]
  (with-operation ios-session
    #(read-current-context ios-session)))

(defn native-context?
  "Returns true when the iOS session is in NATIVE_APP context."
  [{:keys [context*]}]
  (= "NATIVE_APP" (when context* @context*)))

(defn- resolve-context-name
  "Resolves :native/:webview aliases against the available context names."
  [available requested]
  (let [requested (if (keyword? requested) (name requested) (str requested))
        lc        (str/lower-case requested)]
    (cond
      (= "native" lc) "NATIVE_APP"
      (= "native_app" lc) "NATIVE_APP"
      (= "webview" lc) (first (remove #(= "NATIVE_APP" %) available))
      :else (first (filter #(= (str/lower-case %) lc) available)))))

(defn use-context!
  "Switches to a native or webview automation context.

   `requested` may be :native, :webview (first available webview), or an
   exact context name. Waits up to `:timeout-ms` (default 10000) for a
   matching webview to appear. Returns the selected context name. Does not
   issue a WebDriver switch when the requested context is already active."
  ([ios-session requested] (use-context! ios-session requested {}))
  ([{:keys [webdriver] :as ios-session} requested {:keys [timeout-ms]}]
   (with-operation ios-session
     (fn use-context-operation []
       (let [deadline (+ (System/currentTimeMillis) (long (or timeout-ms 10000)))]
         (loop []
           (let [available (contexts ios-session)]
             (if-let [target (resolve-context-name available requested)]
               (if (= target (read-current-context ios-session))
                 target
                 (let [selected (webdriver/switch-context webdriver target)]
                   (when-let [context* (:context* ios-session)]
                     (reset! context* selected))
                   (when-let [refs* (:native-refs* ios-session)]
                     (reset! refs* {}))
                   selected))
               (if (> (System/currentTimeMillis) deadline)
                 (throw (ex-info (str "iOS context " (pr-str requested)
                                   " is not available. Available contexts: "
                                   (str/join ", " available)
                                   ". WKWebView DOM access requires isInspectable=true.")
                          {:ios/context requested :ios/contexts available}))
                 (do (Thread/sleep 250) (recur)))))))))))

(defn with-context
  "Runs callback in a requested Appium context and restores the exact prior
   context before returning. The complete switch/callback/restore sequence is
   serialized by the session operation lock.

   `requested` accepts the same aliases and exact names as `use-context!`.
   Options currently support `:timeout-ms`. Callback failures remain primary
   when restoration also fails; the restoration error is attached as a
   suppressed exception."
  ([ios-session requested callback]
   (with-context ios-session requested {} callback))
  ([ios-session requested opts callback]
   (with-operation ios-session
     (fn scoped-context-operation []
       (let [previous (read-current-context ios-session)
             entered? (volatile! false)
             outcome  (try
                        (use-context! ios-session requested opts)
                        (vreset! entered? true)
                        {:value (callback)}
                        (catch Throwable callback-error
                          {:error callback-error}))
             restore-error
             (when @entered?
               (try
                 (use-context! ios-session previous opts)
                 nil
                 (catch Throwable error
                   error)))]
         (if-let [^Throwable callback-error (:error outcome)]
           (do
             (when restore-error
               (.addSuppressed callback-error ^Throwable restore-error))
             (throw callback-error))
           (if restore-error
             (throw restore-error)
             (:value outcome))))))))

(def app-states
  "Appium numeric application-state values mapped to descriptive keywords."
  {0 :unknown
   1 :not-running
   2 :background-suspended
   3 :background
   4 :foreground})

(defn activate-app!
  "Activates an installed application by bundle identifier without creating
   a new session. The session returns to NATIVE_APP so stale webview state is
   never carried across applications. Returns the bundle identifier."
  [{:keys [webdriver context* native-refs*] :as ios-session} bundle-id]
  (let [bundle (or bundle-id (:bundle-id ios-session)
                 (throw (ex-info "Application activation requires a bundle identifier." {})))]
    (webdriver/activate-app webdriver bundle)
    (webdriver/switch-context webdriver "NATIVE_APP")
    (when context* (reset! context* "NATIVE_APP"))
    (when native-refs* (reset! native-refs* {}))
    bundle))

(defn app-state
  "Returns a descriptive application-state keyword for a bundle identifier."
  [{:keys [webdriver]} bundle-id]
  (get app-states (long (or (webdriver/app-state webdriver bundle-id) 0)) :unknown))

(defn- target-bundle-id
  "Resolves an explicit or session-bound bundle identifier."
  [{:keys [bundle-id]} requested]
  (or requested bundle-id
    (throw (ex-info "This operation requires a bundle identifier." {}))))

(defn launch-app!
  "Launches the session-bound or requested installed application."
  ([ios-session] (launch-app! ios-session nil {}))
  ([ios-session bundle-id] (launch-app! ios-session bundle-id {}))
  ([{:keys [webdriver] :as ios-session} bundle-id opts]
   (let [bundle (target-bundle-id ios-session bundle-id)]
     (webdriver/launch-app webdriver bundle opts)
     (use-context! ios-session :native)
     bundle)))

(defn terminate-app!
  "Terminates the session-bound or requested application."
  ([ios-session] (terminate-app! ios-session nil))
  ([{:keys [webdriver] :as ios-session} bundle-id]
   (let [bundle (target-bundle-id ios-session bundle-id)]
     (webdriver/terminate-app webdriver bundle)
     bundle)))

(defn background-app!
  "Moves the active application to the background for `seconds`."
  [{:keys [webdriver]} seconds]
  (webdriver/background-app webdriver (or seconds 3))
  (long (or seconds 3)))

(defn install-app!
  "Installs a simulator-compatible .app path through Appium."
  [{:keys [webdriver]} app-path]
  (webdriver/install-app webdriver app-path)
  app-path)

(defn uninstall-app!
  "Uninstalls an application by bundle identifier."
  [{:keys [webdriver]} bundle-id]
  (webdriver/remove-app webdriver bundle-id)
  bundle-id)

(defn app-installed?
  "Returns true when a bundle identifier is installed on the simulator."
  [{:keys [webdriver]} bundle-id]
  (webdriver/app-installed? webdriver bundle-id))

(defn open-url!
  "Opens a deep or universal URL, optionally targeting a bundle identifier."
  ([ios-session url] (open-url! ios-session url nil))
  ([{:keys [webdriver] :as ios-session} url bundle-id]
   (webdriver/open-url webdriver url (or bundle-id (:bundle-id ios-session)))))

(def permission-access
  "Friendly permission actions mapped to XCUITest access values."
  {:grant "yes" :allow "yes" :yes "yes"
   :revoke "no" :deny "no" :no "no"
   :reset "unset" :unset "unset"
   :limited "limited"})

(defn get-permission
  "Returns the current permission value for an application service."
  ([ios-session service] (get-permission ios-session nil service))
  ([{:keys [webdriver] :as ios-session} bundle-id service]
   (webdriver/get-permission webdriver
     (target-bundle-id ios-session bundle-id) (name service))))

(defn set-permission!
  "Sets, revokes, or resets an application permission.
   `access` accepts :grant/:revoke/:reset and XCUITest access values."
  ([ios-session service access]
   (set-permission! ios-session nil service access))
  ([{:keys [webdriver] :as ios-session} bundle-id service access]
   (let [bundle (target-bundle-id ios-session bundle-id)
         access (or (get permission-access (keyword access)) (name access))]
     (webdriver/set-permission webdriver bundle (name service) access)
     {:bundle-id bundle :service (name service) :access access})))

;; =============================================================================
;; Native snapshots and element queries
;; =============================================================================

(def native-type-roles
  "XCTest element types mapped to compact semantic snapshot roles."
  {"XCUIElementTypeApplication" "application"
   "XCUIElementTypeButton" "button"
   "XCUIElementTypeCell" "cell"
   "XCUIElementTypeCheckBox" "checkbox"
   "XCUIElementTypeCollectionView" "list"
   "XCUIElementTypeImage" "image"
   "XCUIElementTypeKeyboard" "keyboard"
   "XCUIElementTypeLink" "link"
   "XCUIElementTypeNavigationBar" "navigation"
   "XCUIElementTypeRadioButton" "radio"
   "XCUIElementTypeScrollView" "group"
   "XCUIElementTypeSearchField" "searchbox"
   "XCUIElementTypeSecureTextField" "textbox"
   "XCUIElementTypeSlider" "slider"
   "XCUIElementTypeStaticText" "text"
   "XCUIElementTypeSwitch" "switch"
   "XCUIElementTypeTabBar" "tablist"
   "XCUIElementTypeTable" "list"
   "XCUIElementTypeTextField" "textbox"
   "XCUIElementTypeTextView" "textbox"
   "XCUIElementTypeWebView" "webview"
   "XCUIElementTypeWindow" "window"})

(def native-interactive-types
  "XCTest types that always receive native snapshot refs."
  #{"XCUIElementTypeButton" "XCUIElementTypeCell" "XCUIElementTypeCheckBox"
    "XCUIElementTypeLink" "XCUIElementTypeRadioButton" "XCUIElementTypeSearchField"
    "XCUIElementTypeSecureTextField" "XCUIElementTypeSlider" "XCUIElementTypeSwitch"
    "XCUIElementTypeTextField" "XCUIElementTypeTextView"})

(defn- true-attribute?
  [value]
  (= "true" (str/lower-case (str value))))

(defn- node-role
  [type]
  (or (get native-type-roles type)
    (some-> type (str/replace "XCUIElementType" "") str/lower-case)
    "node"))

(defn- node-name
  [attrs]
  (some #(let [value (get attrs %)]
           (when-not (str/blank? (str value)) (str value)))
    [:label :name]))

(defn- native-ref-node?
  [type attrs]
  (and (not= "false" (str/lower-case (str (get attrs :visible "true"))))
    (or (contains? native-interactive-types type)
      (and (true-attribute? (get attrs :accessible))
        (some? (node-name attrs))))))

(defn- native-ref-id
  "Creates a content/path-derived native ref so refreshed snapshots do not
   silently retarget a sequential ref to a different control."
  [identity refs]
  (loop [probe (long 0)]
    (let [key    (if (zero? probe) identity (str identity "#" probe))
          ref-id (str "e" (Integer/toUnsignedString (.hashCode ^String key) 36))]
      (if (contains? refs ref-id)
        (recur (inc (long probe)))
        ref-id))))

(defn- native-node-line
  [depth role name value ref-id attrs]
  (str (apply str (repeat (* 2 (long depth)) " ")) "- " role
    (when name (str " " (pr-str name)))
    (when ref-id (str " [@" ref-id "]"))
    (when (and value (not= value name)) (str " [value=" (pr-str value) "]"))
    (when (= "false" (str/lower-case (str (get attrs :enabled "true")))) " [disabled]")
    (when (true-attribute? (get attrs :selected)) " [selected]")))

(defn native-snapshot-from-xml
  "Converts Appium's XCTest XML source into a compact semantic tree with
   snapshot-scoped native refs. Each ref stores an XPath locator.

   Returns {:tree :refs :counter :native true :context \"NATIVE_APP\"}."
  [source]
  (let [root    (xml/parse (ByteArrayInputStream. (.getBytes (str source) "UTF-8")))
        refs*   (atom {})
        counter (atom 0)
        lines*  (atom [])]
    (letfn [(walk [node path depth]
              (when (map? node)
                (let [tag      (name (:tag node))
                      attrs    (:attrs node)
                      type     (or (get attrs :type)
                                 (when (str/starts-with? tag "XCUIElementType") tag))
                      role     (node-role type)
                      label    (node-name attrs)
                      value    (some-> (get attrs :value) str)
                      ref?     (native-ref-node? type attrs)
                      selector (str "xpath=" path)
                      ref-id   (when ref?
                                 (swap! counter inc)
                                 (native-ref-id (str type "|" label "|" path) @refs*))]
                  (swap! lines* conj (native-node-line depth role label value ref-id attrs))
                  (when ref-id
                    (swap! refs* assoc ref-id
                      {:role role :name label :type type :value value
                       :selector selector :using "xpath" :locator path}))
                  (loop [children (filter map? (:content node))
                         tag-counts {}]
                    (when-let [child (first children)]
                      (let [child-tag (name (:tag child))
                            index     (inc (long (get tag-counts child-tag 0)))]
                        (walk child (str path "/" child-tag "[" index "]") (inc (long depth)))
                        (recur (rest children) (assoc tag-counts child-tag index))))))))]
      (walk root (str "/" (name (:tag root)) "[1]") 0)
      {:tree (str/join "\n" @lines*)
       :refs @refs*
       :counter @counter
       :native true
       :context "NATIVE_APP"})))

(defn native-snapshot
  "Captures and compacts the current XCTest hierarchy, updating native refs."
  [{:keys [webdriver native-refs*]}]
  (let [snapshot (native-snapshot-from-xml (webdriver/content webdriver))]
    (when native-refs* (reset! native-refs* (:refs snapshot)))
    snapshot))

(defn selector-locator
  "Resolves a native/web selector or snapshot ref to WebDriver strategy data."
  [{:keys [native-refs*] :as ios-session} selector]
  (let [selector (str selector)]
    (if (and (native-context? ios-session)
          (re-matches #"@e[a-z0-9]+" selector))
      (or (get (when native-refs* @native-refs*) (subs selector 1))
        (throw (ex-info (str "Native ref " selector " is stale or missing. Run snapshot -i again.")
                 {:selector selector :found false :stale-ref true})))
      (let [selector (if (and (not (native-context? ios-session))
                           (re-matches #"@e[a-z0-9]+" selector))
                       (str "[data-pw-ref=\"" (subs selector 1) "\"]")
                       selector)]
        (webdriver/selector-strategy selector (native-context? ios-session))))))

(defn find-element
  "Finds one native/webview element using selectors or @refs."
  [{:keys [webdriver] :as ios-session} selector]
  (let [{:keys [using locator value]} (selector-locator ios-session selector)]
    (webdriver/find-element webdriver using (or locator value))))

(defn find-elements
  "Finds all matching native/webview elements."
  [{:keys [webdriver] :as ios-session} selector]
  (let [{:keys [using locator value]} (selector-locator ios-session selector)]
    (webdriver/find-elements webdriver using (or locator value))))

(defn type-element!
  "Types text into an element without clearing its existing value."
  [{:keys [webdriver] :as ios-session} selector text]
  (webdriver/send-keys webdriver (find-element ios-session selector) (str text))
  selector)

(defn element-text
  "Returns element text/accessibility label."
  [{:keys [webdriver] :as ios-session} selector]
  (webdriver/element-text webdriver (find-element ios-session selector)))

(defn element-attribute
  "Returns an element attribute. Native attributes include name, label, value,
   type, visible, enabled, selected, rect, and accessibilityContainer."
  [{:keys [webdriver] :as ios-session} selector attribute]
  (webdriver/element-attribute webdriver (find-element ios-session selector) (name attribute)))

(defn element-count
  "Returns the number of matching native/webview elements."
  [ios-session selector]
  (count (find-elements ios-session selector)))

(defn element-rect
  "Returns an element rectangle."
  [{:keys [webdriver] :as ios-session} selector]
  (webdriver/element-rect webdriver (find-element ios-session selector)))

(defn element-state
  "Returns displayed, enabled, selected, and editable state for an element."
  [{:keys [webdriver] :as ios-session} selector]
  (let [element-id (find-element ios-session selector)
        type       (webdriver/element-attribute webdriver element-id "type")]
    {:visible (webdriver/element-displayed? webdriver element-id)
     :enabled (webdriver/element-enabled? webdriver element-id)
     :selected (webdriver/element-selected? webdriver element-id)
     :editable (contains? #{"XCUIElementTypeTextField" "XCUIElementTypeSecureTextField"
                            "XCUIElementTypeTextView" "XCUIElementTypeSearchField"} type)}))

(defn wait-for-element
  "Waits for a native/webview selector or ref and returns its element id."
  ([ios-session selector] (wait-for-element ios-session selector {}))
  ([ios-session selector {:keys [timeout-ms interval-ms]}]
   (let [timeout-ms  (long (or timeout-ms 30000))
         interval-ms (long (or interval-ms 250))
         deadline    (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [result (try {:element (find-element ios-session selector)}
                         (catch clojure.lang.ExceptionInfo e
                           (if (contains? #{"no such element" "stale element reference"}
                                 (:webdriver/error (ex-data e)))
                             nil
                             (throw e))))]
         (if result
           (:element result)
           (if (> (System/currentTimeMillis) deadline)
             (throw (ex-info (str "Timed out after " timeout-ms "ms waiting for " selector)
                      {:selector selector :timeout-ms timeout-ms}))
             (do (Thread/sleep interval-ms) (recur)))))))))

(defn press-key!
  "Presses a key on a selected or currently focused element."
  ([{:keys [webdriver]} key]
   (webdriver/press-key webdriver key))
  ([{:keys [webdriver] :as ios-session} selector key]
   (webdriver/press-key webdriver (find-element ios-session selector) key)))

(defn type-keys!
  "Types text into the currently focused element."
  [{:keys [webdriver]} text]
  (webdriver/type-keys webdriver text))

(defn hide-keyboard!
  "Dismisses the iOS software keyboard."
  [{:keys [webdriver]}]
  (webdriver/hide-keyboard webdriver))

;; =============================================================================
;; Gestures
;; =============================================================================

(defn viewport-size
  "Returns {:width :height} of the web viewport via JavaScript evaluation."
  [wd-session]
  (let [result (webdriver/evaluate wd-session
                 "({width: window.innerWidth, height: window.innerHeight})")]
    {:width  (long (or (get result "width") 375))
     :height (long (or (get result "height") 667))}))

(defn swipe-coordinates
  "Derives safe swipe start/end coordinates for a direction inside a
   viewport. Keeps the gesture inside the middle band of the screen so it
   works on iPads and landscape viewports.

   Params:
   `direction` - Keyword. :up, :down, :left, or :right.
   `distance`  - Long. Swipe distance in px (clamped to viewport).
   `viewport`  - Map. {:width w :height h}.

   Returns {:from [x y] :to [x y]}."
  [direction distance {:keys [width height]}]
  (let [w    (long width)
        h    (long height)
        cx   (quot w 2)
        cy   (quot h 2)
        max-v (long (max 1 (- h (quot h 5))))
        max-h (long (max 1 (- w (quot w 5))))
        dv   (min (long distance) (- max-v (quot h 5)))
        dh   (min (long distance) (- max-h (quot w 5)))
        dv   (max 1 (long dv))
        dh   (max 1 (long dh))]
    (case direction
      :up    {:from [cx (+ cy (quot dv 2))] :to [cx (- cy (quot dv 2))]}
      :down  {:from [cx (- cy (quot dv 2))] :to [cx (+ cy (quot dv 2))]}
      :left  {:from [(+ cx (quot dh 2)) cy] :to [(- cx (quot dh 2)) cy]}
      :right {:from [(- cx (quot dh 2)) cy] :to [(+ cx (quot dh 2)) cy]}
      (throw (ex-info (str "Unknown swipe direction: " direction
                        ". Use up, down, left, or right.")
               {:ios/direction direction})))))

(defn swipe
  "Performs a low-level native touch swipe on an iOS session.

   Two forms:
     (swipe session {:direction :up :distance 500 :duration 800})
     (swipe session {:from [200 600] :to [200 100] :duration 800})

   Direction-based swipes derive coordinates from the live web viewport."
  [{:keys [webdriver] :as ios-session} {:keys [direction distance from to duration]}]
  (let [native?  (native-context? ios-session)
        viewport (if native?
                   (select-keys (webdriver/window-rect webdriver) [:width :height])
                   (viewport-size webdriver))
        coords   (if (and from to)
                   {:from from :to to}
                   (swipe-coordinates direction (or distance 300) viewport))
        opts     (assoc coords :duration (or duration 800))]
    (if native?
      (webdriver/swipe-screen webdriver opts)
      (webdriver/swipe webdriver opts))
    {:from (:from coords) :to (:to coords)}))

(defn scroll
  "Scrolls content in a semantic direction. Native contexts use a touch
   gesture (scroll down means finger swipe up); webview element scrolling
   uses DOM scrollBy. Options accept :selector and :smooth?."
  ([ios-session direction] (scroll ios-session direction 500 {}))
  ([ios-session direction amount] (scroll ios-session direction amount {}))
  ([{:keys [webdriver] :as ios-session} direction amount {:keys [selector smooth?]}]
   (let [direction (keyword direction)
         amount    (long (or amount 500))
         gesture   (get {:down :up :up :down :left :right :right :left}
                     direction direction)]
     (if (and selector (not (native-context? ios-session)))
       (webdriver/scroll-element webdriver (find-element ios-session selector)
         direction amount smooth?)
       (let [gesture-opts
             (if (and selector (native-context? ios-session))
               (let [{:keys [x y width height]} (element-rect ios-session selector)
                     {:keys [from to]} (swipe-coordinates gesture amount
                                         {:width width :height height})
                     offset (fn [[px py]] [(+ (long x) (long px))
                                           (+ (long y) (long py))])]
                 {:from (offset from) :to (offset to)})
               {:direction gesture :distance amount})]
         (assoc (swipe ios-session gesture-opts) :direction direction))))))
