(ns com.blockether.spel.core
  "Playwright lifecycle management and browser launching.
    
    Entry point for all Playwright operations. Creates Playwright instances
    and launches browsers (Chromium, Firefox, WebKit).
    
    Usage:
    (with-playwright [pw (create)]
      (with-browser [browser (launch-chromium pw {:headless true})]
        (with-page [page (new-page browser)]
          (navigate page \"https://example.org\")
          (text-content page \"h1\"))))
    
    All operations return anomaly maps on failure instead of throwing exceptions."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.data]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.options :as opts])
  (:import
   [java.io File]
   [java.net InetAddress ServerSocket URLDecoder URLEncoder]
   [java.nio.charset StandardCharsets]
   [java.nio.file Path]
   [java.util UUID]
   [com.microsoft.playwright APIRequest APIRequest$NewContextOptions APIRequestContext APIResponse
    Browser BrowserContext BrowserType CDPSession Page Playwright Playwright$CreateOptions
    PlaywrightException Selectors TimeoutError Tracing Tracing$StartOptions Tracing$StopOptions Video]
   [com.microsoft.playwright.impl TargetClosedError]
   [com.microsoft.playwright.options FormData RequestOptions]
   [com.google.gson JsonObject]))

;; =============================================================================
;; Java → Clojure conversion
;; =============================================================================

(defn java->clj
  "Recursively converts Java collection types returned by Playwright's
   `.evaluate` into idiomatic Clojure persistent data structures.

   Playwright marshals JavaScript objects as `java.util.LinkedHashMap`
   and arrays as `java.util.ArrayList`. Clojure's interop lets you
   *read* from these (`get`, `keys`, `seq`), but *write* operations
   like `assoc`, `merge`, `update`, `conj` throw because they expect
   `clojure.lang.Associative` / `clojure.lang.IPersistentCollection`.

   This function makes evaluate results work seamlessly with core
   Clojure — no manual `(into {} ...)` required. See issue #105."
  [obj]
  (cond
    (instance? java.util.Map obj)
    (persistent!
      (reduce (fn [m ^java.util.Map$Entry e]
                (assoc! m (.getKey e) (java->clj (.getValue e))))
        (transient {})
        (.entrySet ^java.util.Map obj)))

    (instance? java.util.List obj)
    (mapv java->clj ^java.util.List obj)

    :else obj))

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
        (or (.getMessage e)
          (str "Unknown Playwright error (" (.getName (.getClass e)) ")"))
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
  (let [candidates ["src" "test" "test-e2e" "dev"
                    "src/clj" "test/clj" "src/cljc" "test/cljc"]
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
      (Playwright/create (doto (Playwright$CreateOptions.)
                           (.setEnv (cond-> {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"}
                                      src-dirs (assoc "PLAYWRIGHT_JAVA_SRC" src-dirs))))))))

(defn find-free-port
  "Finds an available local TCP port and returns it as an integer.

   Binds to loopback with port 0 so the OS allocates a free ephemeral port,
   then immediately closes the socket and returns the selected port.

   Returns:
   - integer port on success
   - anomaly map on failure"
  []
  (safe
    (with-open [^ServerSocket socket (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (.setReuseAddress socket true)
      (.getLocalPort socket))))

(defn url-encode
  "Encodes text for use in URL query strings using UTF-8.

   Space characters are encoded as `+` and reserved characters are percent-encoded.

   Params:
   `s` - Any value coercible to string.

   Returns:
   URL-encoded string."
  [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn url-decode
  "Decodes URL-encoded text using UTF-8.

   Decodes `%XX` escapes and `+` back to spaces.

   Params:
   `s` - Any value coercible to string.

   Returns:
   Decoded string."
  [s]
  (URLDecoder/decode (str s) StandardCharsets/UTF_8))

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

(defn cookie->map
  "Converts a Playwright Cookie Java object to a Clojure map.

   Params:
   `cookie` - com.microsoft.playwright.options.Cookie instance.

   Returns:
   Map with keys :name, :value, :domain, :path, :expires, :httpOnly, :secure, :sameSite."
  [^com.microsoft.playwright.options.Cookie cookie]
  {:name     (.name cookie)
   :value    (.value cookie)
   :domain   (.domain cookie)
   :path     (.path cookie)
   :expires  (.expires cookie)
   :httpOnly (.httpOnly cookie)
   :secure   (.secure cookie)
   :sameSite (str (.sameSite cookie))})

(defn context-cookies
  "Returns all cookies in the context as Clojure maps.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Vector of cookie maps with keys :name, :value, :domain, :path, :expires, :httpOnly, :secure, :sameSite."
  [^BrowserContext context]
  (mapv cookie->map (.cookies context)))

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
     (navigate page \"https://example.org\"))"
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
  "Saves the video to the specified path.
   IMPORTANT: The page AND context must be closed first to finalize the video file.
   Calling this on an open page will throw 'Page is not yet closed'.
   Prefer using `sci-finish-video-recording` with `:save-as` opt instead.

   Params:
   `page` - Page instance (must be closed).
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

;; =============================================================================
;; Shared Testing Browser
;; =============================================================================

(def ^:dynamic *testing-pw*
  "Dynamic var holding a shared Playwright instance created by `with-testing-browser`.
   Bound automatically — do not bind manually."
  nil)

(def ^:dynamic *testing-browser*
  "Dynamic var holding a shared Browser instance created by `with-testing-browser`.
   When bound, `with-testing-page` reuses this browser instead of launching a new one.
   Bound automatically — do not bind manually."
  nil)

(defn testing-interactive?
  "Returns true when tests should run in interactive (headed) mode.
   Checks system property `spel.interactive` first, then env var `SPEL_INTERACTIVE`.
   Any truthy string value (e.g. \"true\", \"1\", \"yes\") enables interactive mode.

   Usage:
     clojure -J-Dspel.interactive=true -M:test
     SPEL_INTERACTIVE=true clojure -M:test"
  []
  (some? (or (System/getProperty "spel.interactive")
           (System/getenv "SPEL_INTERACTIVE"))))

(defn testing-slow-mo
  "Returns the slow-mo delay in milliseconds for browser actions.
   Checks system property `spel.slow-mo` first, then env var `SPEL_SLOW_MO`.
   Returns 0 when unset.

   Usage:
     clojure -J-Dspel.slow-mo=500 -J-Dspel.interactive=true -M:test
     SPEL_SLOW_MO=500 SPEL_INTERACTIVE=true clojure -M:test"
  []
  (let [v (or (System/getProperty "spel.slow-mo")
            (System/getenv "SPEL_SLOW_MO"))]
    (if v (parse-long v) 0)))

(defn testing-browser-engine
  "Returns the browser engine keyword to launch.
   Checks system property `spel.browser` first, then env var `SPEL_BROWSER`.
   Returns :chromium when unset. Supported: :chromium, :firefox, :webkit.

   Usage:
     clojure -J-Dspel.browser=firefox -M:test
     SPEL_BROWSER=webkit clojure -M:test"
  []
  (let [v (or (System/getProperty "spel.browser")
            (System/getenv "SPEL_BROWSER"))]
    (if v (keyword v) :chromium)))

(defn run-with-testing-browser
  "Functional core of `with-testing-browser`. Creates a Playwright instance and
   launches a browser, then calls `(f)` with both bound to dynamic vars.

   Opts (merged with env-var defaults):
     :browser-type    - :chromium (default), :firefox, or :webkit
     :headless        - Boolean (default: true, or false when SPEL_INTERACTIVE is set)
     :slow-mo         - Millis to slow down operations (default from SPEL_SLOW_MO)
     :args            - Vector of additional browser launch args
     :executable-path - String. Path to browser executable.
     :channel         - String. Browser channel (e.g. \"chrome\", \"msedge\").
     :timeout         - Double. Max time in ms to wait for browser launch.
     :chromium-sandbox - Boolean. Enable Chromium sandbox."
  [opts f]
  (let [;; Merge env-var defaults under explicit opts
        effective (merge {:browser-type (testing-browser-engine)
                          :headless     (not (testing-interactive?))}
                    (when (pos? (long (testing-slow-mo)))
                      {:slow-mo (testing-slow-mo)})
                    opts)
        launch-opts (resolve-launch-opts effective)
        launch-fn   (resolve-launcher (:browser-type effective))]
    (with-playwright [pw]
      (let [browser (ensure-not-anomaly!
                      (if (seq launch-opts)
                        (launch-fn pw launch-opts)
                        (launch-fn pw)))]
        (try
          (binding [*testing-pw*      pw
                    *testing-browser* browser]
            (f))
          (finally
            (close-browser! browser)))))))

(defmacro with-testing-browser
  "All-in-one macro that creates a shared Playwright + Browser for a group of tests.

   When active, `with-testing-page` reuses the browser instead of launching a new
   one per test — giving you fast per-test page isolation with shared browser.

   Respects env-var / system-property defaults:
     SPEL_INTERACTIVE / spel.interactive — headed mode
     SPEL_SLOW_MO    / spel.slow-mo     — action delay in ms
     SPEL_BROWSER    / spel.browser     — chromium|firefox|webkit

   Usage:
     ;; Lazytest: shared browser per namespace
     (defdescribe my-tests
       (around [f] (core/with-testing-browser (f)))
       (it \"test\"
         (core/with-testing-page [pg]
           (page/navigate pg \"https://example.org\"))))

     ;; clojure.test: shared browser per namespace
     (use-fixtures :once (fn [f] (core/with-testing-browser (f))))
     (deftest my-test
       (core/with-testing-page [pg]
         (page/navigate pg \"https://example.org\")))

     ;; With custom opts
     (core/with-testing-browser {:headless false :slow-mo 100}
       body...)"
  [maybe-opts & body]
  (if (map? maybe-opts)
    `(run-with-testing-browser ~maybe-opts (fn [] ~@body))
    `(run-with-testing-browser {} (fn [] ~maybe-opts ~@body))))

(defn run-with-testing-page
  "Functional core of `with-testing-page`. Sets up a complete Playwright stack
   and calls `(f page)`.

   When `*testing-browser*` is bound (from `with-testing-browser`), reuses that
   browser — only creating a fresh context + page. This gives per-test isolation
   with shared browser startup cost.

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
        ctx-opts     (resolve-context-opts opts)]
    (if (and *testing-browser* (not profile))
      ;; Reuse shared browser from with-testing-browser
      (if (allure-active?)
        (let [har-file (File/createTempFile "pw-har-" ".har")
              ctx      (ensure-not-anomaly!
                         (new-context *testing-browser*
                           (merge ctx-opts
                             {:record-har-path (str har-file)
                              :record-har-mode :full})))]
          (run-traced-page ctx allure-vars f))
        (let [ctx (ensure-not-anomaly! (new-context *testing-browser* (or ctx-opts {})))]
          (run-plain-page ctx (:page allure-vars) f)))
      ;; Standalone: create full stack
      (let [launch-opts (resolve-launch-opts opts)]
        (with-playwright [pw]
          (if profile
            ;; Persistent context mode
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
            ;; Normal standalone mode
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
                    (run-plain-page ctx (:page allure-vars) f)))))))))))

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
       (page/navigate page \"https://example.org\")
       (page/title page))

     ;; With device emulation
     (with-testing-page {:device :iphone-14} [page]
       (page/navigate page \"https://example.org\"))

     ;; With viewport preset
     (with-testing-page {:viewport :desktop-hd :locale \"fr-FR\"} [page]
       (page/navigate page \"https://example.org\"))

     ;; Firefox, headed mode
     (with-testing-page {:browser-type :firefox :headless false} [page]
       (page/navigate page \"https://example.org\"))

     ;; Persistent profile (keeps login sessions across runs)
     (with-testing-page {:profile \"/tmp/my-chrome-profile\"} [page]
       (page/navigate page \"https://example.org\"))

     ;; Custom browser executable + extra args
     (with-testing-page {:executable-path \"/usr/bin/chromium\"
                         :args [\"--disable-gpu\"]} [page]
       (page/navigate page \"https://example.org\"))"
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

;; =============================================================================
;; JSON Encoding
;; =============================================================================

(def ^:dynamic *json-encoder*
  "Function that encodes Clojure data to a JSON string.
   Bind to your preferred JSON library's encode function.

   Must accept any Clojure data (maps, vectors, strings, numbers, etc.)
   and return a String.

   Examples:
   ;; cheshire
   (binding [*json-encoder* cheshire.core/generate-string]
     (api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; charred
   (binding [*json-encoder* charred.api/write-json-str]
     (api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; jsonista
   (binding [*json-encoder* jsonista.core/write-value-as-string]
     (api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; Set globally for convenience
   (alter-var-root #'*json-encoder* (constantly cheshire.core/generate-string))"
  nil)

;; =============================================================================
;; Request Capture — for Allure HTTP exchange reporting
;; =============================================================================

(def ^:dynamic *request-capture*
  "When bound to an atom, `execute-request` stores request metadata here.
   Used by `allure/api-step` to capture request details (method, URL, headers,
   body) alongside the response for rich HTTP exchange reporting.

   The atom is reset to a map with keys:
     :method          - String. HTTP method (\"GET\", \"POST\", etc.).
     :url             - String. Request URL.
     :request-headers - Map or nil. Request headers from opts.
     :request-body    - String or nil. Request body (from :data or encoded :json)."
  nil)

;; =============================================================================
;; Hooks
;; =============================================================================

(def ^:dynamic *hooks*
  "Map of hook functions invoked during the API request lifecycle.

   All hooks are optional (nil = no-op). Each hook receives context
   about the current operation and can observe or transform it.

   Keys:

   :on-request   (fn [method url opts]) -> opts
                 Called before every request. Receives the HTTP method keyword
                 (:get, :post, ...), the URL string, and the opts map.
                 Return value replaces opts. Return nil to keep original.
                 Use for: logging, auth token injection, request transformation.

   :on-response  (fn [method url response]) -> response
                 Called after every successful request. Receives the method,
                 URL, and the APIResponse (or response map from `request!`).
                 Return value replaces the response. Return nil to keep original.
                 Use for: logging, metrics, response transformation.

   :on-error     (fn [method url anomaly]) -> anomaly
                 Called when a request returns an anomaly (network error,
                 timeout, etc.). Return value replaces the anomaly.
                 Return nil to keep original.
                 Use for: error logging, alerting, error transformation.

   :on-retry     (fn [{:keys [attempt max-attempts delay-ms result]}])
                 Called before each retry sleep. Side-effect only, return
                 value ignored.
                 Use for: logging retry attempts, metrics.

   Examples:

   ;; Global logging
   (alter-var-root #'*hooks*
     (constantly
       {:on-request  (fn [m url _] (println \"→\" m url))
        :on-response (fn [m url r] (println \"←\" m url (.status r)) r)}))

   ;; Per-scope auth injection
   (binding [*hooks* {:on-request (fn [_ _ opts]
                                    (update opts :headers
                                      assoc \"Authorization\" (str \"Bearer \" (get-token))))}]
     (api-get ctx \"/protected\"))"
  {:on-request  nil
   :on-response nil
   :on-error    nil
   :on-retry    nil})

(defmacro with-hooks
  "Execute body with the given hooks merged into `*hooks*`.

   Merges with (not replaces) any existing hooks so outer bindings
   are preserved for keys you don't override.

   Usage:
   (with-hooks {:on-request  (fn [m url opts] (log/info m url) opts)
                :on-response (fn [m url resp] (metrics/inc! :api-calls) resp)}
     (api-get ctx \"/users\")
     (api-post ctx \"/users\" {:json {:name \"Alice\"}}))"
  [hooks & body]
  `(binding [*hooks* (merge *hooks* ~hooks)]
     ~@body))

;; =============================================================================
;; FormData
;; =============================================================================

(defn form-data
  "Creates a new FormData instance.
   
   Returns:
   FormData instance."
  ^FormData []
  (FormData/create))

(defn fd-set
  "Sets a field in FormData.
   
   Params:
   `fd`    - FormData instance.
   `name`  - String. Field name.
   `value` - String. Field value.
   
   Returns:
   FormData instance."
  ^FormData [^FormData fd ^String name ^String value]
  (.set fd name value))

(defn fd-append
  "Appends a field to FormData.
   
   Params:
   `fd`    - FormData instance.
   `name`  - String. Field name.
   `value` - String. Field value.
   
   Returns:
   FormData instance."
  ^FormData [^FormData fd ^String name ^String value]
  (.append fd name value))

(defn map->form-data
  "Converts a Clojure map to FormData.
   
   Params:
   `m` - Map of string->string.
   
   Returns:
   FormData instance."
  ^FormData [m]
  (let [fd (form-data)]
    (doseq [[k v] m]
      (fd-set fd (name k) (str v)))
    fd))

;; =============================================================================
;; RequestOptions
;; =============================================================================

(defn request-options
  "Creates RequestOptions from a map.

   Params:
   `opts` - Map with optional:
     :method              - String. HTTP method override.
     :headers             - Map. HTTP headers ({\"Authorization\" \"Bearer ...\"}).
     :data                - String, bytes, or Object. Request body.
                            Objects are auto-serialized to JSON by Playwright.
     :json                - Any Clojure data. Encoded via `*json-encoder*`,
                            sets Content-Type to application/json automatically.
                            Mutually exclusive with :data, :form, :multipart.
     :form                - FormData. URL-encoded form data.
     :multipart           - FormData. Multipart form data (file uploads).
     :timeout             - Double. Timeout in ms (default: 30000).
     :params              - Map. Query parameters.
     :max-redirects       - Long. Max redirects to follow (default: 20).
     :max-retries         - Long. Retry network errors (default: 0).
     :fail-on-status-code - Boolean. Throw on non-2xx/3xx status.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.

   The :json key requires `*json-encoder*` to be bound:

     (binding [*json-encoder* cheshire.core/generate-string]
       (api-post ctx \"/users\" {:json {:name \"Alice\" :age 30}}))

   Returns:
   RequestOptions instance."
  ^RequestOptions [opts]
  (let [;; Handle :json → encode and merge into :data + Content-Type header
        opts (if-let [json-data (:json opts)]
               (do
                 (when-not *json-encoder*
                   (throw (ex-info (str "Cannot use :json without binding *json-encoder*. "
                                     "Set it to your JSON library's encode function, e.g.:\n"
                                     "  (binding [*json-encoder* cheshire.core/generate-string] ...)\n"
                                     "  (alter-var-root #'*json-encoder* (constantly cheshire.core/generate-string))")
                            {:key :json :value json-data})))
                 (-> opts
                   (dissoc :json)
                   (assoc :data (*json-encoder* json-data))
                   (update :headers (fn [h]
                                      (merge {"Content-Type" "application/json"} h)))))
               opts)
        ^RequestOptions ro (RequestOptions/create)]
    (when-let [v (:method opts)]
      (.setMethod ro ^String v))
    (when-let [v (:headers opts)]
      (doseq [[k val] v]
        (.setHeader ro (name k) (str val))))
    (when-let [v (:data opts)]
      (cond
        (string? v) (.setData ro ^String v)
        (bytes? v)  (.setData ro ^bytes v)
        :else       (.setData ro ^Object v)))
    (when-let [v (:form opts)]
      (.setForm ro ^FormData v))
    (when-let [v (:multipart opts)]
      (.setMultipart ro ^FormData v))
    (when-let [v (:timeout opts)]
      (.setTimeout ro (double v)))
    (when-let [v (:params opts)]
      (doseq [[k val] v]
        (.setQueryParam ro (name k) (str val))))
    (when-let [v (:max-redirects opts)]
      (.setMaxRedirects ro (long v)))
    (when-let [v (:max-retries opts)]
      (.setMaxRetries ro (long v)))
    (when (contains? opts :fail-on-status-code)
      (.setFailOnStatusCode ro (boolean (:fail-on-status-code opts))))
    (when (contains? opts :ignore-https-errors)
      (.setIgnoreHTTPSErrors ro (boolean (:ignore-https-errors opts))))
    ro))

;; =============================================================================
;; APIRequest
;; =============================================================================

(defn api-request
  "Returns the APIRequest for the Playwright instance.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   APIRequest instance."
  ^APIRequest [^Playwright pw]
  (.request pw))

(defn new-api-context
  "Creates a new APIRequestContext.

   Thread-safe: serializes .newContext calls per APIRequest instance via locking.
   Playwright Java's Connection internals are not thread-safe (plain HashMap,
   non-atomic message IDs). Concurrent .newContext calls on the same APIRequest
   corrupt internal state, causing silent anomaly maps instead of real contexts.

   Params:
   `api-req` - APIRequest instance (from `api-request`).
   `opts`    - Map, optional. Context options:
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.
     :timeout             - Double. Default timeout in ms (default: 30000).
     :user-agent          - String. User-Agent header.
     :max-redirects       - Long. Max redirects to follow (default: 20).
     :fail-on-status-code - Boolean. Throw on non-2xx/3xx status.
     :storage-state       - String. Storage state JSON or path.

   Returns:
   APIRequestContext or anomaly map.

   Examples:
   (new-api-context (api-request pw)
     {:base-url \"https://api.example.org\"
      :extra-http-headers {\"Authorization\" \"Bearer token\"}
      :timeout 10000})"
  (^APIRequestContext [^APIRequest api-req]
   (locking api-req
     (safe (.newContext api-req))))
  (^APIRequestContext [^APIRequest api-req opts]
   (locking api-req
     (safe
       (let [co (APIRequest$NewContextOptions.)]
         (when-let [v (:base-url opts)]
           (.setBaseURL co ^String v))
         (when-let [v (:extra-http-headers opts)]
           (.setExtraHTTPHeaders co ^java.util.Map v))
         (when (contains? opts :ignore-https-errors)
           (.setIgnoreHTTPSErrors co (boolean (:ignore-https-errors opts))))
         (when-let [v (:timeout opts)]
           (.setTimeout co (double v)))
         (when-let [v (:user-agent opts)]
           (.setUserAgent co ^String v))
         (when-let [v (:max-redirects opts)]
           (.setMaxRedirects co (long v)))
         (when (contains? opts :fail-on-status-code)
           (.setFailOnStatusCode co (boolean (:fail-on-status-code opts))))
         (when-let [v (:storage-state opts)]
           (.setStorageState co ^String v))
         (.newContext api-req co))))))

(defn api-dispose!
  "Disposes the APIRequestContext and all responses.

   Params:
   `ctx` - APIRequestContext instance.

   Returns:
   nil."
  [^APIRequestContext ctx]
  (.dispose ctx)
  nil)

(defmacro with-api-context
  "Binds a single APIRequestContext and ensures disposal.

   Usage:
   (with-api-context [ctx (new-api-context (api-request pw) {:base-url \"https://api.example.org\"})]
     (api-get ctx \"/users\"))"
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try
       ~@body
       (finally
         (when (instance? APIRequestContext ~sym)
           (api-dispose! ~sym))))))

(defmacro with-api-contexts
  "Binds multiple APIRequestContexts and disposes all on exit.

   Same shape as `with-open` — flat pairs of [name expr].

   Usage:
   (with-api-contexts [users   (new-api-context (api-request pw)
                                 {:base-url \"https://users.example.org\"
                                  :extra-http-headers {\"Authorization\" \"Bearer token\"}})
                       billing (new-api-context (api-request pw)
                                 {:base-url \"https://billing.example.org\"
                                  :extra-http-headers {\"X-API-Key\" \"key\"}})
                       public  (new-api-context (api-request pw)
                                 {:base-url \"https://public.example.org\"})]
     (api-get users \"/me\")
     (api-get billing \"/invoices\")
     (api-get public \"/catalog\"))"
  [bindings & body]
  (assert (vector? bindings) "bindings must be a vector")
  (assert (even? (count bindings)) "bindings must have an even number of forms")
  (let [pairs (partition 2 bindings)
        syms  (mapv first pairs)]
    `(let [~@bindings]
       (try
         ~@body
         (finally
           ~@(for [s (reverse syms)]
               `(when (instance? APIRequestContext ~s)
                  (try (api-dispose! ~s) (catch Exception ~'_)))))))))

;; =============================================================================
;; Internal — Options Coercion & Request Execution
;; =============================================================================

(defn- ->request-options
  "Coerces opts to RequestOptions. If already a RequestOptions instance,
   returns it unchanged. If a map, converts via `request-options`."
  ^RequestOptions [opts]
  (if (instance? RequestOptions opts)
    opts
    (request-options opts)))

(defn- fire-hook
  "Invoke a hook function if present. Returns the hook's return value
   or the original value if the hook returns nil or doesn't exist."
  [hook-key original & args]
  (if-let [hook-fn (get *hooks* hook-key)]
    (let [result (apply hook-fn args)]
      (if (nil? result) original result))
    original))

(defn- execute-request
  "Central request executor. All HTTP methods route through here.
   Handles hook lifecycle: on-request → Playwright call → on-response/on-error.
   When `*request-capture*` is bound to an atom, stores request metadata
   (method, url, headers, body) for Allure HTTP exchange reporting."
  [^APIRequestContext ctx method ^String url opts]
  (let [opts   (when opts (fire-hook :on-request opts method url opts))
        ;; Capture request metadata for Allure reporting when active
        _      (when-let [cap *request-capture*]
                 (reset! cap {:method          (str/upper-case (name method))
                              :url             url
                              :request-headers (when (map? opts) (:headers opts))
                              :request-body    (when (map? opts)
                                                 (or (:data opts)
                                                   (when-let [j (:json opts)]
                                                     (if *json-encoder*
                                                       (*json-encoder* j)
                                                       (pr-str j)))))}))
        ro     (when opts (->request-options opts))
        result (safe
                 (case method
                   :get    (if ro (.get ctx url ro)    (.get ctx url))
                   :post   (if ro (.post ctx url ro)   (.post ctx url))
                   :put    (if ro (.put ctx url ro)    (.put ctx url))
                   :patch  (if ro (.patch ctx url ro)  (.patch ctx url))
                   :delete (if ro (.delete ctx url ro) (.delete ctx url))
                   :head   (if ro (.head ctx url ro)   (.head ctx url))
                   (if ro (.fetch ctx url ro) (.fetch ctx url))))]
    (if (anomaly/anomaly? result)
      (fire-hook :on-error result method url result)
      (fire-hook :on-response result method url result))))

;; =============================================================================
;; APIRequestContext — HTTP Methods
;; =============================================================================

(defn api-get
  "Sends a GET request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path (resolved against base-url if set).
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-get ctx \"/users\")
   (api-get ctx \"/users\" {:params {:page 1 :limit 10}
                            :headers {\"Authorization\" \"Bearer token\"}})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :get url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :get url opts)))

(defn api-post
  "Sends a POST request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-post ctx \"/users\" {:data \"{\\\"name\\\": \\\"Alice\\\"}\"
                              :headers {\"Content-Type\" \"application/json\"}})
   (api-post ctx \"/login\" {:form (map->form-data {:user \"admin\" :pass \"secret\"})})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :post url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :post url opts)))

(defn api-put
  "Sends a PUT request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :put url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :put url opts)))

(defn api-patch
  "Sends a PATCH request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :patch url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :patch url opts)))

(defn api-delete
  "Sends a DELETE request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :delete url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :delete url opts)))

(defn api-head
  "Sends a HEAD request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :head url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :head url opts)))

(defn api-fetch
  "Sends a request with custom method (set via :method in opts).

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-fetch ctx \"/resource\" {:method \"OPTIONS\"})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :fetch url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :fetch url opts)))

;; =============================================================================
;; APIResponse
;; =============================================================================

(defn api-response-url
  "Returns the response URL.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.url ^APIResponse resp)))

(defn api-response-status
  "Returns the HTTP status code.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.status ^APIResponse resp)))

(defn api-response-status-text
  "Returns the HTTP status text.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.statusText ^APIResponse resp)))

(defn api-response-headers
  "Returns the response headers.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (into {} (.headers ^APIResponse resp))))

(defn api-response-body
  "Returns the response body as bytes.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.body ^APIResponse resp)))

(defn api-response-text
  "Returns the response body as text.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.text ^APIResponse resp)))

(defn api-response-ok?
  "Returns whether the response is OK (2xx).
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.ok ^APIResponse resp)))

(defn api-response-headers-array
  "Returns the response headers as a vector of {:name :value} maps.
   Preserves duplicate headers (unlike `api-response-headers` which
   keeps only the last value for each name).
   Passes through anomaly maps unchanged.

   Params:
   `resp` - APIResponse instance.

   Returns:
   Vector of maps, or anomaly map."
  [resp]
  (if (anomaly/anomaly? resp)
    resp
    (mapv (fn [^com.microsoft.playwright.options.HttpHeader h]
            {:name (.-name h) :value (.-value h)})
      (.headersArray ^APIResponse resp))))

(defn api-response-dispose!
  "Disposes the APIResponse.
   No-op on anomaly maps.

   Params:
   `resp` - APIResponse instance.

   Returns:
   nil."
  [resp]
  (when-not (anomaly/anomaly? resp)
    (.dispose ^APIResponse resp))
  nil)

;; =============================================================================
;; Response Convenience
;; =============================================================================

(defn api-response->map
  "Converts an APIResponse to a Clojure map.

   Reads the response body as text and includes all metadata.
   Useful for logging, debugging, and test assertions.

   Params:
   `resp` - APIResponse instance.

   Returns:
   Map with keys:
     :status      - Long. HTTP status code.
     :status-text - String. HTTP status text.
     :url         - String. Response URL.
     :ok?         - Boolean. True if status is 2xx.
     :headers     - Map. Response headers.
     :body        - String. Response body text (nil on read failure).

   Examples:
   (let [resp (api-get ctx \"/users\")]
     (api-response->map resp))
   ;; => {:status 200
   ;;     :status-text \"OK\"
   ;;     :url \"https://api.example.org/users\"
   ;;     :ok? true
   ;;     :headers {\"content-type\" \"application/json\"}
    ;;     :body \"{\\\"users\\\": [...]}\"}"
  [resp]
  (if (anomaly/anomaly? resp)
    resp
    {:status      (long (.status ^APIResponse resp))
     :status-text (.statusText ^APIResponse resp)
     :url         (.url ^APIResponse resp)
     :ok?         (.ok ^APIResponse resp)
     :headers     (into {} (.headers ^APIResponse resp))
     :body        (try (.text ^APIResponse resp) (catch Exception _ nil))}))

;; =============================================================================
;; Standalone Request — no pre-built context needed
;; =============================================================================

(defn request!
  "Fire-and-forget HTTP request. Creates an ephemeral context, makes the
   request, reads the full response into a Clojure map, and disposes
   everything. No context management needed.

   Params:
   `pw`     - Playwright instance.
   `method` - Keyword. :get :post :put :patch :delete :head.
   `url`    - String. Full URL (not relative — no base-url).
   `opts`   - Map, optional. See `request-options` for all keys.

   Returns:
   Response map (same shape as `api-response->map`) or anomaly map.

   Examples:
   ;; Simple GET — no setup, no cleanup
   (request! pw :get \"https://api.example.org/health\")
   ;; => {:status 200 :ok? true :body \"OK\" ...}

   ;; POST with JSON body
   (request! pw :post \"https://api.example.org/users\"
     {:data    \"{\\\"name\\\": \\\"Alice\\\"}\"
      :headers {\"Content-Type\" \"application/json\"
                \"Authorization\" \"Bearer token\"}})

   ;; Hit multiple domains without any context setup
   (let [users    (request! pw :get \"https://users.example.org/me\"
                    {:headers {\"Authorization\" \"Bearer user-token\"}})
         invoices (request! pw :get \"https://billing.example.org/invoices\"
                    {:headers {\"X-API-Key\" \"billing-key\"}})]
     [users invoices])"
  ([^Playwright pw method ^String url]
   (request! pw method url {}))
  ([^Playwright pw method ^String url opts]
   (let [ctx (new-api-context (api-request pw))]
     (try
       (let [result (execute-request ctx method url opts)]
         (if (anomaly/anomaly? result)
           result
           (api-response->map result)))
       (finally
         (try (api-dispose! ctx)
              (catch Exception e
                (binding [*out* *err*]
                  (println (str "spel: warn: api-dispose failed: " (.getMessage e)))))))))))

;; =============================================================================
;; Retry
;; =============================================================================

(def default-retry-opts
  "Default retry configuration.

   :max-attempts  - Total attempts including the first (default: 3).
   :delay-ms      - Initial delay between retries in ms (default: 200).
   :backoff       - Backoff strategy (default: :exponential).
                    :fixed        — constant delay.
                    :linear       — delay * attempt.
                    :exponential  — delay * 2^attempt.
   :max-delay-ms  - Ceiling on delay (default: 10000).
   :retry-when    - Predicate (fn [result] -> truthy to retry).
                    Default: retries on anomalies and 5xx status codes."
  {:max-attempts 3
   :delay-ms     200
   :backoff      :exponential
   :max-delay-ms 10000
   :retry-when   (fn [result]
                   (or (anomaly/anomaly? result)
                     (and (map? result)
                       (contains? result :status)
                       (number? (:status result))
                       (>= (long (:status result)) 500))))})

(defn retry-guard
  "Creates a :retry-when predicate that retries until `pred` is satisfied.

   `pred` is a function (fn [result] -> truthy). Retries while `pred` returns
   falsy. Stops when `pred` returns truthy. Also retries on anomalies and
   exceptions thrown by `pred` itself.

   Use with `retry` / `with-retry` to poll until a condition is met:

   ;; Retry until job status is 'ready'
   (with-retry {:retry-when (retry-guard #(= \"ready\" (:status %)))}
     (api-get ctx \"/job/123\"))

   ;; Combine with default error retry
   (with-retry {:retry-when (retry-guard #(> (:count %) 0))}
     (api-get ctx \"/queue/stats\"))

   The returned predicate retries when:
   - result is an anomaly (same as default)
   - result is a 5xx HTTP response (same as default)
   - `pred` returns falsy for the result
   - `pred` throws an exception"
  [pred]
  (let [default-retry (:retry-when default-retry-opts)]
    (fn [result]
      (or (default-retry result)
        (try (not (pred result))
             (catch Throwable _ true))))))

(defn- compute-delay
  "Compute delay in ms for the given attempt number (0-based)."
  ^long [backoff ^long delay-ms ^long max-delay-ms ^long attempt]
  (let [raw (case backoff
              :fixed       delay-ms
              :linear      (* delay-ms (inc attempt))
              :exponential (* delay-ms (long (Math/pow 2 attempt)))
              (* delay-ms (long (Math/pow 2 attempt))))]
    (min raw max-delay-ms)))

(defn retry
  "Execute `f` (a no-arg function) with retry logic.

   Params:
   `f`    - No-arg function that returns a result.
   `opts` - Map, optional. Override keys from `default-retry-opts`:
     :max-attempts  - Total attempts (default: 3).
     :delay-ms      - Initial delay in ms (default: 200).
     :backoff       - :fixed, :linear, or :exponential (default).
     :max-delay-ms  - Max delay ceiling in ms (default: 10000).
     :retry-when    - (fn [result]) → truthy to retry. Exceptions are always retried.

   Returns:
   The result of the last successful attempt.
   Throws the last exception if all attempts fail with exceptions.

   Examples:
   ;; Retry a flaky endpoint
   (retry #(api-get ctx \"/flaky\"))

   ;; Custom: retry on 429 Too Many Requests with linear backoff
   (retry #(api-get ctx \"/rate-limited\")
     {:max-attempts 5
      :delay-ms     1000
      :backoff      :linear
      :retry-when   (fn [r] (= 429 (:status (api-response->map r))))})"
  ([f] (retry f {}))
  ([f opts]
   (let [{:keys [max-attempts delay-ms backoff max-delay-ms retry-when]}
         (merge default-retry-opts opts)
         max-attempts (long max-attempts)
         delay-ms     (long delay-ms)
         max-delay-ms (long max-delay-ms)]
     (loop [attempt 0]
       (let [result (try (f) (catch Throwable t t))]
         (if (and (< (inc attempt) max-attempts)
               (or (instance? Throwable result)
                 (retry-when result)))
           (let [sleep-ms (compute-delay backoff delay-ms max-delay-ms attempt)]
             (fire-hook :on-retry nil
               {:attempt      (inc attempt)
                :max-attempts max-attempts
                :delay-ms     sleep-ms
                :result       (if (instance? Throwable result)
                                {:error (.getMessage ^Throwable result)}
                                result)})
             (Thread/sleep sleep-ms)
             (recur (inc attempt)))
           ;; Last attempt or retry-when returned false — return or re-throw
           (if (instance? Throwable result)
             (throw result)
             result)))))))

(defmacro with-retry
  "Execute body with retry logic.

   Usage:
   ;; Default: 3 attempts, exponential backoff, retry on anomalies + 5xx
   (with-retry {}
     (api-get ctx \"/flaky-endpoint\"))

   ;; Custom retry config
   (with-retry {:max-attempts 5
                :delay-ms     500
                :backoff      :linear
                :retry-when   (fn [r] (and (map? r) (number? (:status r)) (>= (:status r) 500)))}
     (api-post ctx \"/idempotent-endpoint\"
       {:json {:action \"process\"}}))

   ;; Retry standalone requests too
   (with-retry {:max-attempts 3}
     (request! pw :get \"https://api.example.org/health\"))"
  [opts-or-body & body]
  (if (and (map? opts-or-body) (seq body))
    `(retry (fn [] ~@body) ~opts-or-body)
    `(retry (fn [] ~opts-or-body ~@body))))

;; =============================================================================
;; Page / Context API Access
;; =============================================================================

(defn page-api
  "Returns the APIRequestContext for a Page.

   The returned context shares cookies and storage with the page's browser
   context. API calls through it appear in Playwright traces automatically.

   Params:
   `pg` - Page instance.

   Returns:
   APIRequestContext bound to the page's browser context.

   Examples:
   (with-testing-page [pg]
     (page/navigate pg \"https://example.org/login\")
     ;; API calls share the browser session (cookies, auth)
     (let [resp (api-get (page-api pg) \"/api/me\")]
       (api-response-status resp)))"
  ^APIRequestContext [^Page pg]
  (.request pg))

(defn context-api
  "Returns the APIRequestContext for a BrowserContext.

   The returned context shares cookies and storage with the browser context.
   API calls through it appear in Playwright traces automatically.

   Params:
   `ctx` - BrowserContext instance.

   Returns:
   APIRequestContext bound to the browser context.

   Examples:
   (with-playwright [pw]
     (with-browser [browser (launch-chromium pw)]
       (with-context [ctx (new-context browser {:base-url \"https://api.example.org\"})]
         (let [resp (api-get (context-api ctx) \"/users\")]
           (api-response-status resp)))))"
  ^APIRequestContext [^BrowserContext ctx]
  (.request ctx))

;; =============================================================================
;; Standalone API Testing — with-testing-api
;; =============================================================================

(defn run-with-testing-api
  "Functional core of `with-testing-api`. Sets up a complete Playwright stack
   for API testing and calls `(f api-request-context)`.

   Creates: Playwright → Browser (headless Chromium) → BrowserContext → APIRequestContext.
   The APIRequestContext comes from `BrowserContext.request()`, so all API calls
   share cookies with the context and appear in Playwright traces.

   When the Allure reporter is active, automatically enables tracing
   (DOM snapshots + sources) and HAR recording — zero configuration.

   Opts:
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.
     :json-encoder        - Function. Binds `*json-encoder*` for the body.
     :storage-state       - String. Storage state JSON or path.
     + any key accepted by `new-context` (:locale, :timezone-id, etc.)

   Examples:
   (run-with-testing-api {:base-url \"https://api.example.org\"}
     (fn [ctx]
       (api-get ctx \"/users\")))"
  [opts f]
  (let [json-enc  (:json-encoder opts)
        ctx-opts  (dissoc opts :json-encoder)
        [allure-active? allure-vars] (resolve-allure-vars)]
    (with-playwright [pw]
      (with-browser [browser (launch-chromium pw {:headless true})]
        (if (allure-active?)
          ;; Traced mode: HAR recording + Playwright tracing
          (let [trace-file (File/createTempFile "pw-trace-" ".zip")
                har-file   (File/createTempFile "pw-har-" ".har")
                ctx        (ensure-not-anomaly!
                             (new-context browser
                               (merge ctx-opts
                                 {:record-har-path (str har-file)
                                  :record-har-mode :full})))
                tracing    (.tracing ^BrowserContext ctx)
                api-ctx    (.request ^BrowserContext ctx)
                {:keys [tracing-var trace har title]} allure-vars]
            (.start tracing (doto (Tracing$StartOptions.)
                              (.setScreenshots false)
                              (.setSnapshots true)
                              (.setSources true)
                              (.setTitle (or (when title @title) "spel"))))
            (try
              (with-bindings (cond-> {}
                               tracing-var (assoc tracing-var tracing)
                               trace       (assoc trace trace-file)
                               har         (assoc har har-file)
                               json-enc    (assoc #'*json-encoder* json-enc))
                (f api-ctx))
              (finally
                (try (.stop tracing (doto (Tracing$StopOptions.)
                                      (.setPath (.toPath trace-file))))
                     (catch Exception _))
                (let [t (doto (Thread. (fn []
                                         (try (close-context! ctx)
                                              (catch Exception _))))
                          (.setDaemon true)
                          (.start))]
                  (.join t 5000))
                (attach-trace-to-allure-context! trace-file har-file))))
          ;; Non-traced mode
          (let [ctx     (ensure-not-anomaly!
                          (new-context browser (or ctx-opts {})))
                api-ctx (.request ^BrowserContext ctx)]
            (try
              (if json-enc
                (binding [*json-encoder* json-enc]
                  (f api-ctx))
                (f api-ctx))
              (finally
                (try (close-context! ctx) (catch Exception _))))))))))

(defmacro with-testing-api
  "All-in-one macro for API testing with automatic resource management.

   Creates a complete Playwright stack (playwright → browser → context),
   extracts the context-bound APIRequestContext, binds it to `sym`,
   executes body, and tears everything down.

   The APIRequestContext comes from `BrowserContext.request()`, so all API
   calls share cookies with the context and appear in Playwright traces.

   When the Allure reporter is active, tracing (DOM snapshots + sources)
   and HAR recording are enabled automatically — zero configuration.

   Opts (an optional map expression, evaluated at runtime):
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.
     :json-encoder        - Function. Binds `*json-encoder*` for the body.
     :storage-state       - String. Storage state JSON or path.
     + any key accepted by `new-context` (:locale, :timezone-id, etc.)

   Usage:
     ;; Minimal — hit a base URL
     (with-testing-api {:base-url \"https://api.example.org\"} [ctx]
       (api-get ctx \"/users\"))

     ;; With custom headers and JSON encoder
     (with-testing-api {:base-url \"https://api.example.org\"
                        :extra-http-headers {\"Authorization\" \"Bearer token\"}
                        :json-encoder cheshire.core/generate-string} [ctx]
       (api-post ctx \"/users\" {:json {:name \"Alice\"}}))

     ;; Minimal — no opts
     (with-testing-api [ctx]
       (api-get ctx \"https://api.example.org/health\"))"
  [opts-or-binding & args]
  (if (vector? opts-or-binding)
    ;; No opts: (with-testing-api [sym] body...)
    (let [[sym] opts-or-binding
          body  args]
      `(run-with-testing-api {} (fn [~sym] ~@body)))
    ;; With opts: (with-testing-api opts [sym] body...)
    (let [opts           opts-or-binding
          [[sym] & body] args]
      `(run-with-testing-api ~opts (fn [~sym] ~@body)))))

;; =============================================================================
;; Page-bound API with custom base-url — with-page-api
;; =============================================================================

(defn run-with-page-api
  "Functional core of `with-page-api`. Creates an APIRequestContext from a Page
   with custom options (base-url, headers, etc.) while sharing cookies via
   storage-state.

   Copies storage-state (cookies, localStorage) from the page's browser context,
   creates a new APIRequestContext with the provided opts, and disposes it after.

   Params:
   `pg`   - Page instance to extract cookies from.
   `opts` - Map. Options for the new APIRequestContext:
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean.
     :json-encoder        - Function. Binds `*json-encoder*` for the body.
   `f`    - Function receiving the new APIRequestContext.

   Returns:
   Result of calling `f`.

   Examples:
   (run-with-page-api pg {:base-url \"https://api.example.org\"}
     (fn [ctx]
       (api-get ctx \"/users\")))"
  [^Page pg opts f]
  (let [json-enc      (:json-encoder opts)
        ctx-opts     (dissoc opts :json-encoder)
        browser-ctx  (.context pg)
        storage-state (.storageState browser-ctx)
        ;; Create fresh Playwright just for this API context - it will be closed
        pw          (create)
        api-req     (api-request pw)
        merged-opts (merge ctx-opts {:storage-state storage-state})]
    (try
      (with-api-context [ctx (new-api-context api-req merged-opts)]
        (if json-enc
          (binding [*json-encoder* json-enc]
            (f ctx))
          (f ctx)))
      (finally
        (try (close! pw)
             (catch Exception _))))))

(defmacro with-page-api
  "Create an APIRequestContext from a Page with custom options.

   Copies cookies from the page's browser context (via storage-state), creates
   a new APIRequestContext with the provided options, and disposes it after.

   This lets you use a custom base-url while still sharing the browser's
   login session / cookies.

   Params:
   `pg`   - Page instance (shares cookies from its browser context).
   `opts` - Map of options for the new context:
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean.
     :json-encoder        - Function. Binds `*json-encoder*` for the body.

   Usage:
     ;; API calls to different domain, sharing browser cookies
     (with-testing-page [pg]
       (page/navigate pg \"https://example.org/login\")
       ;; ... login via UI ...
       (with-page-api pg {:base-url \"https://api.example.org\"} [ctx]
         ;; ctx has cookies from the browser session
         (api-get ctx \"/me\")))

     ;; With JSON encoder
     (with-page-api pg {:base-url \"https://api.example.org\"
                       :json-encoder cheshire.core/generate-string} [ctx]
       (api-post ctx \"/users\" {:json {:name \"Alice\"}}))"

  [pg opts binding-vec & body]
  (let [[sym] binding-vec]
    `(run-with-page-api ~pg ~opts (fn [~sym] ~@body))))

;; =============================================================================
;; CDPSession
;; =============================================================================

(defn cdp-send
  "Sends a Chrome DevTools Protocol command.
   
   Params:
   `session` - CDPSession instance.
   `method`  - String. CDP method name.
   `params`  - Map, optional. CDP parameters.
   
   Returns:
   JSON result or anomaly map."
  ([^CDPSession session ^String method]
   (safe (.send session method)))
  ([^CDPSession session ^String method params]
   (let [json-obj (JsonObject.)]
     (doseq [[k v] params]
       (.addProperty json-obj (name k) (str v)))
     (safe (.send session method json-obj)))))

(defn cdp-detach!
  "Detaches the CDP session.
   
   Params:
   `session` - CDPSession instance."
  [^CDPSession session]
  (.detach session))

(defn cdp-on
  "Registers a handler for CDP events.
   
   Params:
   `session` - CDPSession instance.
   `event`   - String. Event name.
   `handler` - Function that receives the event data."
  [^CDPSession session ^String event handler]
  (.on session event
    (reify java.util.function.Consumer
      (accept [_ data] (handler data)))))

;; =============================================================================
;; Tracing
;; =============================================================================

(defn context-tracing
  "Returns the Tracing for a context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Tracing instance."
  ^Tracing [^BrowserContext context]
  (.tracing context))

(defn tracing-start!
  "Starts tracing.
   
   Params:
   `tracing` - Tracing instance.
   `opts`    - Map, optional. Tracing options."
  ([^Tracing tracing]
   (safe (.start tracing)))
  ([^Tracing tracing trace-opts]
   (safe (.start tracing (opts/->tracing-start-options trace-opts)))))

(defn tracing-stop!
  "Stops tracing and saves the trace file.
   
   Params:
   `tracing` - Tracing instance.
   `opts`    - Map, optional. {:path \"trace.zip\"}."
  ([^Tracing tracing]
   (safe (.stop tracing)))
  ([^Tracing tracing stop-opts]
   (safe (.stop tracing (opts/->tracing-stop-options stop-opts)))))

;; =============================================================================
;; Selectors
;; =============================================================================

(defn selectors
  "Returns the Selectors for a Playwright instance.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   Selectors instance."
  ^Selectors [^com.microsoft.playwright.Playwright pw]
  (.selectors pw))

(defn selectors-register!
  "Registers a custom selector engine.
   
   Params:
   `sels`   - Selectors instance.
   `name`   - String. Selector engine name.
   `script` - String. JavaScript for the selector engine."
  [^Selectors sels ^String name ^String script]
  (safe (.register sels name script)))

;; =============================================================================
;; Video (on Video object)
;; =============================================================================

(defn video-obj-path
  "Returns the path to the video file from a Video instance.
   
   Params:
   `video` - Video instance.
   
   Returns:
   Path or anomaly map."
  [^Video video]
  (safe (.path video)))

(defn video-obj-save-as!
  "Saves the video to the given path from a Video instance.
   
   Params:
   `video` - Video instance.
   `path`  - String. Destination path."
  [^Video video ^String path]
  (safe (.saveAs video (java.nio.file.Paths/get path (into-array String [])))))

(defn video-obj-delete!
  "Deletes the video file from a Video instance.
   
   Params:
   `video` - Video instance."
  [^Video video]
  (safe (.delete video)))
