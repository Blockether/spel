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
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.data]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Browser BrowserContext BrowserType
    Page Playwright PlaywrightException TimeoutError]
   [com.microsoft.playwright.impl TargetClosedError]))

(set! *warn-on-reflection* true)

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

(defn create
  "Creates a new Playwright instance.
   
   Returns:
   Playwright instance or anomaly map on failure.
   
   Examples:
   (def pw (create))
   ;; Use pw for browser launching, then (.close pw) when done."
  []
  (safe
    (Playwright/create)))

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
