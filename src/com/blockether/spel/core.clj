(ns com.blockether.spel.core
  "Playwright lifecycle management and browser launching.
    
    Entry point for all Playwright operations. Creates Playwright instances
    and launches browsers (Chromium, Firefox, WebKit).
    
    Usage:
    (with-playwright [pw (create)]
      (with-browser [browser (launch-chromium pw {:headless true})]
        (with-page [page (new-page browser)]
          (navigate page \"https://example.com\")
          (text-content page \"h1\"))))
    
    All operations return anomaly maps on failure instead of throwing exceptions."
  (:require
   [clojure.java.io :as io]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.data]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.options :as opts])
  (:import
   [java.io File]
   [java.nio.file Path]
   [java.util UUID]
   [com.microsoft.playwright Browser BrowserContext BrowserType
    Page Playwright Playwright$CreateOptions PlaywrightException TimeoutError Tracing$StartOptions Tracing$StopOptions Video]
   [com.microsoft.playwright.impl TargetClosedError]))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn- stacktrace->vec
  "Converts an exception's stack trace to a vector of strings."
  [^Throwable e]
  (mapv str (.getStackTrace e)))

(defn wrap-error
  "Wraps Playwright exceptions into anomaly maps.

   Includes the exception message, class, stack trace, and the original
   exception object for programmatic access.
    
   Params:
   `e` - Exception from Playwright.
    
   Returns:
   Anomaly map with appropriate category."
  [^Exception e]
  (let [base {:playwright/exception   e
              :playwright/class       (.getName (.getClass e))
              :playwright/stacktrace  (stacktrace->vec e)}]
    (cond
      (instance? TimeoutError e)
      (anomaly/anomaly ::anomaly/busy
        (.getMessage e)
        (assoc base :playwright/error-type :playwright.error/timeout))

      (instance? TargetClosedError e)
      (anomaly/anomaly ::anomaly/interrupted
        (.getMessage e)
        (assoc base :playwright/error-type :playwright.error/target-closed))

      (instance? PlaywrightException e)
      (anomaly/anomaly ::anomaly/fault
        (.getMessage e)
        (assoc base :playwright/error-type :playwright.error/exception))

      :else
      (anomaly/anomaly ::anomaly/fault
        (or (.getMessage e) "Unknown error")
        (assoc base :playwright/error-type :playwright.error/unknown)))))

(defmacro safe
  "Wraps body in try/catch, returning anomaly map on Playwright errors.
    
   Returns the result of body on success, or an anomaly map on failure.
   Catches TimeoutError, TargetClosedError, PlaywrightException, and
   any other Exception. All anomaly maps include the original exception,
   its class name, and full stack trace."
  [& body]
  `(try
     ~@body
     (catch TimeoutError e#
       (wrap-error e#))
     (catch TargetClosedError e#
       (wrap-error e#))
     (catch PlaywrightException e#
       (wrap-error e#))
     (catch Exception e#
       (wrap-error e#))))

(def anomaly?
  "Returns true if x is an anomaly map (has a recognized anomaly category).
   Re-exported from com.blockether.anomaly.core for caller convenience."
  anomaly/anomaly?)

;; =============================================================================
;; Playwright Lifecycle
;; =============================================================================

(defn- detect-source-dirs
  "Auto-detects Clojure source directories that exist in the working directory.
   Returns a platform-separated path string (e.g. \"src:test:dev\") or nil."
  []
  (let [candidates ["src" "test" "test-e2e" "dev"]
        existing   (filterv #(.isDirectory (File. ^String %)) candidates)]
    (when (seq existing)
      (String/join ^CharSequence File/pathSeparator ^Iterable existing))))

(defn create
  "Creates a new Playwright instance.

   Automatically detects Clojure source directories (src, test, test-e2e, dev) and
   configures Playwright tracing to include .clj source files. When a trace
   is captured with {:sources true}, the Trace Viewer Source tab will show
   the actual Clojure source code for each action.

   Respects the PLAYWRIGHT_JAVA_SRC environment variable if already set.
   
   Returns:
   Playwright instance or anomaly map on failure.
   
   Examples:
   (def pw (create))
   ;; Use pw for browser launching, then (.close pw) when done."
  []
  (safe
    (let [src-dirs (when-not (System/getenv "PLAYWRIGHT_JAVA_SRC")
                     (detect-source-dirs))]
      (if src-dirs
        (Playwright/create (doto (Playwright$CreateOptions.)
                             (.setEnv {"PLAYWRIGHT_JAVA_SRC" src-dirs})))
        (Playwright/create)))))

(defn close!
  "Closes a Playwright instance and releases all resources.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   nil."
  [^Playwright pw]
  (when pw
    (.close pw))
  nil)

(defmacro with-playwright
  "Binds a Playwright instance and ensures cleanup.
   
   Usage:
   (with-playwright [pw]              ;; creates Playwright internally
     (launch-chromium pw))
   (with-playwright [pw (create)]     ;; uses provided expression
     (launch-chromium pw))"
  [binding-vec & body]
  (let [[sym expr] (if (= 1 (count binding-vec))
                     [(first binding-vec) `(create)]
                     binding-vec)]
    `(let [~sym ~expr]
       (try
         ~@body
         (finally
           (when (instance? Playwright ~sym)
             (close! ~sym)))))))

;; =============================================================================
;; Browser Type Access
;; =============================================================================

(defn chromium
  "Returns the Chromium BrowserType.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   BrowserType for Chromium."
  ^BrowserType [^Playwright pw]
  (.chromium pw))

(defn firefox
  "Returns the Firefox BrowserType.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   BrowserType for Firefox."
  ^BrowserType [^Playwright pw]
  (.firefox pw))

(defn webkit
  "Returns the WebKit BrowserType.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   BrowserType for WebKit."
  ^BrowserType [^Playwright pw]
  (.webkit pw))

;; =============================================================================
;; Browser Launching
;; =============================================================================

(defn launch
  "Launches a browser of the given type.
   
   Params:
   `browser-type` - BrowserType instance.
   `opts`         - Map, optional. Launch options (see options/->launch-options).
   
   Returns:
   Browser instance or anomaly map on failure."
  (^Browser [^BrowserType browser-type]
   (safe (.launch browser-type)))
  (^Browser [^BrowserType browser-type launch-opts]
   (safe (.launch browser-type (opts/->launch-options launch-opts)))))

(defn launch-chromium
  "Launches Chromium browser.
   
   Params:
   `pw`   - Playwright instance.
   `opts` - Map, optional. Launch options.
   
   Returns:
   Browser instance or anomaly map on failure.
   
   Examples:
   (launch-chromium pw)
   (launch-chromium pw {:headless false :slow-mo 100})"
  ([pw]
   (launch (chromium pw)))
  ([pw opts]
   (launch (chromium pw) opts)))

(defn launch-firefox
  "Launches Firefox browser.
   
   Params:
   `pw`   - Playwright instance.
   `opts` - Map, optional. Launch options.
   
   Returns:
   Browser instance or anomaly map on failure."
  ([pw]
   (launch (firefox pw)))
  ([pw opts]
   (launch (firefox pw) opts)))

(defn launch-webkit
  "Launches WebKit browser.
   
   Params:
   `pw`   - Playwright instance.
   `opts` - Map, optional. Launch options.
   
   Returns:
   Browser instance or anomaly map on failure."
  ([pw]
   (launch (webkit pw)))
  ([pw opts]
   (launch (webkit pw) opts)))

;; =============================================================================
;; Persistent Context (Chrome Profile)
;; =============================================================================

(defn launch-persistent-context
  "Launches a browser with a persistent user data directory (Chrome profile).

   Unlike `launch` + `new-context`, this uses a real Chrome profile directory
   that persists cookies, localStorage, extensions, saved passwords, bookmarks,
   and other browser data across sessions.

   Returns a BrowserContext directly (not a Browser). Closing the context
   automatically closes the browser.

   Params:
   `browser-type` - BrowserType instance (from `chromium`, `firefox`, `webkit`).
   `user-data-dir` - String. Path to Chrome user data directory.
                     Pass empty string for a temporary profile.
   `opts`          - Map, optional. Combined launch + context options
                     (see options/->launch-persistent-context-options).

   Returns:
   BrowserContext instance or anomaly map on failure.

   Examples:
   (launch-persistent-context (chromium pw) \"/tmp/my-profile\")
   (launch-persistent-context (chromium pw) \"/tmp/my-profile\"
     {:headless false :user-agent \"MyAgent/1.0\"})"
  (^BrowserContext [^BrowserType browser-type ^String user-data-dir]
   (safe (.launchPersistentContext browser-type
           (java.nio.file.Paths/get user-data-dir (into-array String [])))))
  (^BrowserContext [^BrowserType browser-type ^String user-data-dir opts]
   (safe (.launchPersistentContext browser-type
           (java.nio.file.Paths/get user-data-dir (into-array String []))
           (opts/->launch-persistent-context-options opts)))))

;; =============================================================================
;; Browser Operations
;; =============================================================================

(defn browser-type-name
  "Returns the name of the browser type.
   
   Params:
   `bt` - BrowserType instance.
   
   Returns:
   String. \"chromium\", \"firefox\", or \"webkit\"."
  ^String [^BrowserType bt]
  (.name bt))

(defn close-browser!
  "Closes a browser and all its pages.
   
   Params:
   `browser` - Browser instance.
   
   Returns:
   nil."
  [^Browser browser]
  (when browser
    (.close browser))
  nil)

(defmacro with-browser
  "Binds a browser instance and ensures cleanup.
   
   Usage:
   (with-browser [browser (launch-chromium pw)]
     (new-page browser))"
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try
       ~@body
       (finally
         (when (instance? Browser ~sym)
           (close-browser! ~sym))))))

(defn browser-connected?
  "Returns true if the browser is connected.
   
   Params:
   `browser` - Browser instance.
   
   Returns:
   Boolean."
  [^Browser browser]
  (.isConnected browser))

(defn browser-version
  "Returns the browser version string.
   
   Params:
   `browser` - Browser instance.
   
   Returns:
   String. Browser version."
  ^String [^Browser browser]
  (.version browser))

(defn browser-contexts
  "Returns all browser contexts.
   
   Params:
   `browser` - Browser instance.
   
   Returns:
   Vector of BrowserContext instances."
  [^Browser browser]
  (vec (.contexts browser)))

;; =============================================================================
;; Browser Context Operations
;; =============================================================================

(defn new-context
  "Creates a new browser context with optional configuration.
   
   Params:
   `browser` - Browser instance.
   `opts`    - Map, optional. Context options (see options/->new-context-options).
   
   Returns:
   BrowserContext instance or anomaly map on failure."
  (^BrowserContext [^Browser browser]
   (safe (.newContext browser)))
  (^BrowserContext [^Browser browser context-opts]
   (safe (.newContext browser (opts/->new-context-options context-opts)))))

(defn close-context!
  "Closes a browser context and all its pages.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   nil."
  [^BrowserContext context]
  (when context
    (.close context))
  nil)

(defmacro with-context
  "Binds a browser context and ensures cleanup.
   
   Usage:
   (with-context [ctx (new-context browser)]
     (new-page-from-context ctx))"
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try
       ~@body
       (finally
         (when (instance? BrowserContext ~sym)
           (close-context! ~sym))))))

(defn context-pages
  "Returns all pages in a context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Vector of Page instances."
  [^BrowserContext context]
  (vec (.pages context)))

(defn context-browser
  "Returns the browser that owns this context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Browser instance."
  ^Browser [^BrowserContext context]
  (.browser context))

(defn context-set-default-timeout!
  "Sets the default timeout for context operations.
   
   Params:
   `context` - BrowserContext instance.
   `timeout` - Double. Timeout in milliseconds."
  [^BrowserContext context timeout]
  (.setDefaultTimeout context (double timeout)))

(defn context-set-default-navigation-timeout!
  "Sets the default navigation timeout.
   
   Params:
   `context` - BrowserContext instance.
   `timeout` - Double. Timeout in milliseconds."
  [^BrowserContext context timeout]
  (.setDefaultNavigationTimeout context (double timeout)))

(defn context-grant-permissions!
  "Grants permissions to the context.
   
   Params:
   `context`     - BrowserContext instance.
   `permissions` - Collection of strings (e.g. [\"geolocation\"])."
  [^BrowserContext context permissions]
  (.grantPermissions context ^java.util.List (vec permissions)))

(defn context-clear-permissions!
  "Clears all granted permissions.
   
   Params:
   `context` - BrowserContext instance."
  [^BrowserContext context]
  (.clearPermissions context))

(defn context-clear-cookies!
  "Clears all cookies in the context.
   
   Params:
   `context` - BrowserContext instance."
  [^BrowserContext context]
  (.clearCookies context))

(defn context-cookies
  "Returns all cookies in the context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Vector of cookie maps."
  [^BrowserContext context]
  (vec (.cookies context)))

(defn context-storage-state
  "Returns the storage state (cookies, localStorage) as a JSON string.

   Params:
   `context` - BrowserContext instance.

   Returns:
   JSON string containing cookies and origins with localStorage."
  [^BrowserContext context]
  (.storageState context))

(defn context-save-storage-state!
  "Saves the storage state (cookies, localStorage) to a file.

   Params:
   `context` - BrowserContext instance.
   `path`    - String. File path to save the JSON state to.

   Returns:
   The storage state JSON string."
  [^BrowserContext context ^String path]
  (.storageState context
    (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
      (.setPath (java.nio.file.Paths/get path (into-array String []))))))

(defn context-set-offline!
  "Sets the context to offline or online mode.
   
   Params:
   `context` - BrowserContext instance.
   `offline` - Boolean."
  [^BrowserContext context offline]
  (.setOffline context (boolean offline)))

(defn context-set-extra-http-headers!
  "Sets extra HTTP headers for all requests in the context.
   
   Params:
   `context` - BrowserContext instance.
   `headers` - Map of string->string."
  [^BrowserContext context headers]
  (.setExtraHTTPHeaders context ^java.util.Map headers))

(defn context-route-from-har!
  "Routes requests in the context from a HAR file. Replays recorded responses
   for matching requests.

   Use with :update true to record actual responses into the HAR for later replay.

   Params:
   `context` - BrowserContext instance.
   `har`     - String. Path to the HAR file.
   `opts`    - Map, optional. RouteFromHAR options:
               :url            - String glob or regex Pattern.
               :not-found      - Keyword. :abort or :fallback.
               :update         - Boolean. Whether to update HAR with actual network data.
               :update-content - Keyword. :embed or :attach.
               :update-mode    - Keyword. :full or :minimal."
  ([^BrowserContext context ^String har]
   (safe (.routeFromHAR context (java.nio.file.Paths/get har (into-array String [])))))
  ([^BrowserContext context ^String har route-opts]
   (safe (.routeFromHAR context
           (java.nio.file.Paths/get har (into-array String []))
           (opts/->context-route-from-har-options route-opts)))))

(defn context-route-web-socket!
  "Registers a handler for WebSocket connections matching a URL pattern
   in the browser context (applies to all pages in this context).

   The handler receives a WebSocketRoute that can be used to mock the
   WebSocket connection (send messages, intercept client messages, etc.).

   Params:
   `context` - BrowserContext instance.
   `pattern` - String glob, regex Pattern, or predicate fn.
   `handler` - Function that receives a WebSocketRoute."
  [^BrowserContext context pattern handler]
  (let [consumer (reify java.util.function.Consumer
                   (accept [_ wsr] (handler wsr)))]
    (cond
      (instance? java.util.regex.Pattern pattern)
      (.routeWebSocket context ^java.util.regex.Pattern pattern consumer)

      (string? pattern)
      (.routeWebSocket context ^String pattern consumer)

      :else
      (.routeWebSocket context
        ^java.util.function.Predicate
        (reify java.util.function.Predicate
          (test [_ v] (boolean (pattern v))))
        consumer))))

;; =============================================================================
;; Page Operations
;; =============================================================================

(defn new-page
  "Creates a new page in a browser (creates implicit context).
   
   Params:
   `browser` - Browser instance.
   
   Returns:
   Page instance or anomaly map on failure."
  (^Page [^Browser browser]
   (safe (.newPage browser)))
  (^Page [^Browser browser context-opts]
   (safe
     (let [ctx (new-context browser context-opts)]
       (.newPage ^BrowserContext ctx)))))

(defn new-page-from-context
  "Creates a new page in the given context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Page instance or anomaly map on failure."
  ^Page [^BrowserContext context]
  (safe (.newPage context)))

(defn close-page!
  "Closes a page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   nil."
  [^Page page]
  (when page
    (.close page))
  nil)

(defmacro with-page
  "Binds a page instance and ensures cleanup.
   
   Usage:
   (with-page [page (new-page browser)]
     (navigate page \"https://example.com\"))"
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try
       ~@body
       (finally
         (when (instance? Page ~sym)
           (close-page! ~sym))))))

;; =============================================================================
;; Video Recording
;; =============================================================================

(defn video-path
  "Returns the video file path for a page, or nil if not recording.
   Video is finalized when the browser context closes.

   Params:
   `page` - Page instance.

   Returns:
   String path to video file, or nil."
  [^Page page]
  (when-let [^Video v (.video page)]
    (str (.path v))))

(defn video-save-as!
  "Saves the video to the specified path. Context must be closed first.

   Params:
   `page` - Page instance.
   `path` - String destination path.

   Returns:
   nil."
  [^Page page ^String path]
  (when-let [^Video v (.video page)]
    (.saveAs v (Path/of path (into-array String [])))
    nil))

(defn video-delete!
  "Deletes the video file for a page.

   Params:
   `page` - Page instance.

   Returns:
   nil."
  [^Page page]
  (when-let [^Video v (.video page)]
    (.delete v)
    nil))

;; =============================================================================
;; Standalone Testing Page
;; =============================================================================

(defn- ensure-not-anomaly!
  "Throws when `result` is an anomaly map (from any safe-wrapped call),
   preventing confusing ClassCastException later. Returns result on success."
  [result]
  (if (anomaly? result)
    (throw (or (:playwright/exception result)
             (ex-info (str "Playwright operation failed: "
                        (:cognitect.anomalies/message result))
               (dissoc result :playwright/exception))))
    result))

(def ^:private launch-opt-keys
  "Keys that belong to browser launch (not context). Used to split user opts."
  #{:headless :slow-mo :executable-path :channel :proxy :args
    :downloads-path :timeout :chromium-sandbox})

(def ^:private non-context-keys
  "Keys that must be stripped from context opts (launch keys + meta keys)."
  (conj launch-opt-keys :device :viewport :browser-type :profile))

(defn- resolve-context-opts
  "Resolves `:device` and `:viewport` in opts to context-ready options.

   - `:device` keyword → merges device descriptor (viewport, user-agent, etc.)
   - `:viewport` keyword → resolves to {:width :height}
   - `:viewport` map → used as-is
   - Both are removed from the returned map; their effects are merged in.
   - Launch keys and :profile are stripped."
  [opts]
  (let [device-kw  (:device opts)
        viewport   (:viewport opts)
        base-opts  (apply dissoc opts non-context-keys)
        device-map (when device-kw (devices/resolve-device device-kw))
        vp         (or (:viewport device-map)
                     (devices/resolve-viewport viewport))]
    (cond-> (merge device-map base-opts)
      vp (assoc :viewport vp))))

(defn- resolve-launch-opts
  "Extracts browser launch options from the user opts map."
  [opts]
  (reduce-kv (fn [m k v]
               (if (contains? launch-opt-keys k)
                 (assoc m k v)
                 m))
    {} opts))

(defn- resolve-launcher
  "Returns the launch fn for the given `:browser-type` keyword."
  [browser-type]
  (case (or browser-type :chromium)
    :chromium launch-chromium
    :firefox  launch-firefox
    :webkit   launch-webkit))

(defn- resolve-browser-type
  "Returns the BrowserType accessor fn for the given `:browser-type` keyword."
  [browser-type]
  (case (or browser-type :chromium)
    :chromium chromium
    :firefox  firefox
    :webkit   webkit))

(defn- attach-trace-to-allure-context!
  "When allure/*context* and *output-dir* are bound (ct reporter mode), copy
   trace/HAR files into the allure-results directory and add attachment entries
   to the context atom. This enables with-testing-page to produce trace
   attachments without relying on the Lazytest reporter's var-capture flow."
  [^File trace-file ^File har-file]
  (try
    (let [ctx-var     (resolve 'com.blockether.spel.allure/*context*)
          out-dir-var (resolve 'com.blockether.spel.allure/*output-dir*)
          ctx-atom    (when ctx-var @ctx-var)
          output-dir  (when out-dir-var @out-dir-var)]
      (when (and ctx-atom output-dir (instance? clojure.lang.Atom ctx-atom))
        ;; Attach trace zip
        (when (and (.exists trace-file) (pos? (.length trace-file)))
          (let [att-uuid (str (UUID/randomUUID))
                filename (str att-uuid "-attachment.zip")
                dest     (io/file output-dir filename)]
            (io/copy trace-file dest)
            (swap! ctx-atom update :attachments
              (fnil conj [])
              {:name "Playwright Trace"
               :source filename
               :type "application/vnd.allure.playwright-trace"})))
        ;; Attach HAR file
        (when (and (.exists har-file) (pos? (.length har-file)))
          (let [att-uuid (str (UUID/randomUUID))
                filename (str att-uuid "-attachment.har")
                dest     (io/file output-dir filename)]
            (io/copy har-file dest)
            (swap! ctx-atom update :attachments
              (fnil conj [])
              {:name "Network Activity (HAR)"
               :source filename
               :type "application/json"})))))
    (catch Exception _)))

(defn- run-traced-page
  "Runs `f` on a page with Allure tracing and HAR recording.
   `ctx` is a BrowserContext (from new-context or launch-persistent-context).
   `allure-vars` is a map of {:page :tracing-var :trace :har :title} var references."
  [^BrowserContext ctx allure-vars f]
  (let [trace-file (File/createTempFile "pw-trace-" ".zip")
        har-file   (File/createTempFile "pw-har-" ".har")
        tracing    (.tracing ctx)
        {:keys [page tracing-var trace har title]} allure-vars]
    ;; For non-persistent contexts, HAR is configured via context opts.
    ;; For persistent contexts, HAR is configured via launch-persistent-context opts.
    ;; Either way, tracing is started here.
    (.start tracing (doto (Tracing$StartOptions.)
                      (.setScreenshots true)
                      (.setSnapshots true)
                      (.setSources true)
                      (.setTitle (or (when title @title) "spel"))))
    (let [pg (new-page-from-context ctx)]
      (try
        (with-bindings (cond-> {}
                         page        (assoc page pg)
                         tracing-var (assoc tracing-var tracing)
                         trace       (assoc trace trace-file)
                         har         (assoc har har-file))
          (f pg))
        (finally
          (when (instance? Page pg) (close-page! pg))
          (try (.stop tracing (doto (Tracing$StopOptions.)
                                (.setPath (.toPath trace-file))))
               (catch Exception _))
          ;; Close context (writes HAR file) before attaching, so both
          ;; trace and HAR are fully written when we copy them.
          (let [t (doto (Thread. (fn []
                                   (try (close-context! ctx)
                                        (catch Exception _))))
                    (.setDaemon true)
                    (.start))]
            (.join t 5000))
          ;; In ct reporter mode, allure/*context* is bound but *trace-path*
          ;; exits scope before on-end-var captures it. Attach directly here
          ;; while we still have the trace/HAR files.
          (attach-trace-to-allure-context! trace-file har-file))))))

(defn- run-plain-page
  "Runs `f` on a page without tracing. Binds allure *page* if available."
  [^BrowserContext ctx allure-page-var f]
  (let [pg (new-page-from-context ctx)]
    (try
      (with-bindings (cond-> {}
                       allure-page-var (assoc allure-page-var pg))
        (f pg))
      (finally
        (when (instance? Page pg) (close-page! pg))
        (try (close-context! ctx) (catch Exception _))))))

(defn- resolve-allure-vars
  "Dynamically resolves Allure vars and the active? predicate.
   Returns [active?-fn vars-map]."
  []
  (let [active? (try @(requiring-resolve 'com.blockether.spel.allure/reporter-active?)
                     (catch Exception _ (constantly false)))]
    [active?
     {:page        (resolve 'com.blockether.spel.allure/*page*)
      :tracing-var (resolve 'com.blockether.spel.allure/*tracing*)
      :trace       (resolve 'com.blockether.spel.allure/*trace-path*)
      :har         (resolve 'com.blockether.spel.allure/*har-path*)
      :title       (resolve 'com.blockether.spel.allure/*test-title*)}]))

(defn run-with-testing-page
  "Functional core of `with-testing-page`. Sets up a complete Playwright stack
   and calls `(f page)`.

   Two modes:
   - **Normal** (no :profile): playwright → launch browser → new-context → page
   - **Persistent** (:profile given): playwright → launch-persistent-context → page

   When the Allure reporter is active, automatically enables Playwright tracing
   (screenshots + DOM snapshots + sources) and HAR recording, and binds the
   allure dynamic vars so traces/HARs are attached to the test result.

   Opts:
     :browser-type    - :chromium (default), :firefox, or :webkit
     :headless        - Boolean (default true)
     :slow-mo         - Millis to slow down operations
     :device          - Device preset keyword (e.g. :iphone-14, :pixel-7)
     :viewport        - Viewport keyword (:mobile, :desktop-hd) or {:width :height}
     :profile         - String. Path to persistent user data dir (Chrome profile).
                        Pass empty string for a temporary profile.
     :executable-path - String. Path to browser executable.
     :channel         - String. Browser channel (e.g. \"chrome\", \"msedge\").
     :proxy           - Map with :server, :bypass, :username, :password.
     :args            - Vector of additional browser args.
     :downloads-path  - String. Path to download files.
     :timeout         - Double. Max time in ms to wait for browser launch.
     :chromium-sandbox - Boolean. Enable Chromium sandbox.
     + any key accepted by `new-context` (e.g. :locale, :color-scheme,
       :timezone-id, :storage-state)

   When :device is given, its viewport/user-agent/device-scale-factor/is-mobile/has-touch
   are merged into context opts. Explicit :viewport overrides the device viewport."
  [opts f]
  (let [[allure-active? allure-vars] (resolve-allure-vars)
        profile      (:profile opts)
        launch-opts  (resolve-launch-opts opts)
        ctx-opts     (resolve-context-opts opts)]
    (with-playwright [pw]
      (if profile
        ;; Persistent context mode: launch-persistent-context returns BrowserContext directly
        (let [bt-fn    (resolve-browser-type (:browser-type opts))
              bt       (bt-fn pw)
              combined (merge launch-opts ctx-opts)
              ctx      (ensure-not-anomaly!
                         (if (allure-active?)
                           (let [har-file (File/createTempFile "pw-har-" ".har")]
                             (launch-persistent-context bt profile
                               (merge combined
                                 {:record-har-path (str har-file)
                                  :record-har-mode :full})))
                           (if (seq combined)
                             (launch-persistent-context bt profile combined)
                             (launch-persistent-context bt profile))))]
          (if (allure-active?)
            (run-traced-page ctx allure-vars f)
            (run-plain-page ctx (:page allure-vars) f)))
        ;; Normal mode: launch browser → new-context → page
        (let [launch-fn (resolve-launcher (:browser-type opts))]
          (with-browser [browser (ensure-not-anomaly!
                                   (if (seq launch-opts)
                                     (launch-fn pw launch-opts)
                                     (launch-fn pw)))]
            (if (allure-active?)
              (let [har-file (File/createTempFile "pw-har-" ".har")
                    ctx      (ensure-not-anomaly!
                               (new-context browser
                                 (merge ctx-opts
                                   {:record-har-path (str har-file)
                                    :record-har-mode :full})))]
                (run-traced-page ctx allure-vars f))
              (let [ctx (ensure-not-anomaly! (new-context browser (or ctx-opts {})))]
                (run-plain-page ctx (:page allure-vars) f)))))))))

(defmacro with-testing-page
  "All-in-one macro for quick browser testing with automatic resource management.

   Creates a complete Playwright stack (playwright → browser → context → page),
   binds the page to `sym`, executes body, and tears everything down.

   Two modes:
   - **Normal** (no :profile): playwright → launch browser → new-context → page
   - **Persistent** (:profile given): playwright → launch-persistent-context → page
     Use :profile for persistent user data (login sessions, cookies, extensions).

   When the Allure reporter is active, automatically enables Playwright tracing
   (screenshots + DOM snapshots + network) and HAR recording — zero configuration.

   Opts (an optional map expression, evaluated at runtime):
     :browser-type    - :chromium (default), :firefox, or :webkit
     :headless        - Boolean (default true)
     :slow-mo         - Millis to slow down operations
     :device          - Device preset keyword (e.g. :iphone-14, :pixel-7)
     :viewport        - Viewport keyword (:mobile, :desktop-hd) or {:width :height}
     :profile         - String. Path to persistent user data dir (Chrome profile).
                        When set, uses launch-persistent-context instead of launch + new-context.
     :executable-path - String. Path to browser executable.
     :channel         - String. Browser channel (e.g. \"chrome\", \"msedge\").
     :proxy           - Map with :server, :bypass, :username, :password.
     :args            - Vector of additional browser args.
     :downloads-path  - String. Path to download files.
     :timeout         - Double. Max time in ms to wait for browser launch.
     :chromium-sandbox - Boolean. Enable Chromium sandbox.
     + any key accepted by `new-context` (:locale, :color-scheme, :timezone-id,
       :storage-state, etc.)

   Usage:
     ;; Minimal — headless Chromium, default viewport (opts omitted)
     (with-testing-page [page]
       (page/navigate page \"https://example.com\")
       (page/title page))

     ;; With device emulation
     (with-testing-page {:device :iphone-14} [page]
       (page/navigate page \"https://example.com\"))

     ;; With viewport preset
     (with-testing-page {:viewport :desktop-hd :locale \"fr-FR\"} [page]
       (page/navigate page \"https://example.com\"))

     ;; Firefox, headed mode
     (with-testing-page {:browser-type :firefox :headless false} [page]
       (page/navigate page \"https://example.com\"))

     ;; Persistent profile (keeps login sessions across runs)
     (with-testing-page {:profile \"/tmp/my-chrome-profile\"} [page]
       (page/navigate page \"https://example.com\"))

     ;; Custom browser executable + extra args
     (with-testing-page {:executable-path \"/usr/bin/chromium\"
                         :args [\"--disable-gpu\"]} [page]
       (page/navigate page \"https://example.com\"))"
  [opts-or-binding & args]
  (if (vector? opts-or-binding)
    ;; No opts: (with-testing-page [sym] body...)
    (let [[sym] opts-or-binding
          body  args]
      `(run-with-testing-page {} (fn [~sym] ~@body)))
    ;; With opts: (with-testing-page opts [sym] body...)
    (let [opts           opts-or-binding
          [[sym] & body] args]
      `(run-with-testing-page ~opts (fn [~sym] ~@body)))))
