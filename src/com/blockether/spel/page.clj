(ns com.blockether.spel.page
  "Page operations - navigation, content, evaluation, screenshots,
   events, and utility classes (Dialog, Download, ConsoleMessage,
   Clock, FileChooser, Worker, WebError).
   The Page class is the central API surface of Playwright. Wraps all
   Page methods with idiomatic Clojure functions."
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Page Locator Frame
    Dialog Download ConsoleMessage Clock
    Worker FileChooser WebError BrowserContext]
   [com.microsoft.playwright.options LoadState
    FunctionCallback BindingCallback]))

;; =============================================================================
;; Navigation
;; =============================================================================

(defn navigate
  "Navigates the page to a URL.
   
   Params:
   `page` - Page instance.
   `url`  - String. URL to navigate to.
   `opts` - Map, optional. Navigation options.
   
   Returns:
   Response or nil, or anomaly map on failure."
  ([^Page page ^String url]
   (safe (.navigate page url)))
  ([^Page page ^String url nav-opts]
   (safe (.navigate page url (opts/->navigate-options nav-opts)))))
(defn validate-url
  "Validates a URL string for navigation.

   Params:
   `url`       - String. The URL to validate.
   `raw-input` - String. The original input string (for error messages).

   Returns:
   `url` if valid, or throws ex-info."
  ([url]
   (validate-url url url))
  ([url raw-input]
   (let [lower-url (str/lower-case (or url ""))]
     (if (or (str/starts-with? lower-url "http://")
           (str/starts-with? lower-url "https://")
           (str/starts-with? lower-url "file://")
           (str/starts-with? lower-url "data:")
           (str/starts-with? lower-url "about:")
           (str/starts-with? lower-url "chrome:")
           (str/starts-with? lower-url "javascript:")
           (str/starts-with? lower-url "blob:"))
       ;; Check http(s) URLs for valid domain/IP
       (if (or (str/starts-with? lower-url "http://")
             (str/starts-with? lower-url "https://"))
         (let [parts (str/split url #"/")
               host  (get parts 2 "")]
           (if (or (str/includes? host ".")
                 (= "localhost" (str/lower-case (first (str/split host #":"))))
                 (re-matches #"^(\d{1,3}\.){3}\d{1,3}(:\d+)?$" host)
                 (re-matches #"^\[[a-fA-F0-9:]+\](:\d+)?$" host))
             url
             (throw (ex-info (str "Invalid URL '" raw-input "'. URLs must include a scheme (https://) or be a valid domain.")
                      {:url url :raw-input raw-input}))))
         url)
       (throw (ex-info (str "Invalid URL '" raw-input "'. URLs must include a scheme (https://) or be a valid domain.")
                {:url url :raw-input raw-input}))))))

(defn go-back
  "Navigates back in history.
   
   Params:
   `page` - Page instance.
   `opts` - Map, optional. Navigation options.
   
   Returns:
   Response or nil, or anomaly map on failure."
  ([^Page page]
   (safe (.goBack page)))
  ([^Page page nav-opts]
   (safe (.goBack page (opts/->go-back-options nav-opts)))))

(defn go-forward
  "Navigates forward in history.
   
   Params:
   `page` - Page instance.
   `opts` - Map, optional. Navigation options.
   
   Returns:
   Response or nil, or anomaly map on failure."
  ([^Page page]
   (safe (.goForward page)))
  ([^Page page nav-opts]
   (safe (.goForward page (opts/->go-forward-options nav-opts)))))

(defn reload
  "Reloads the page.
   
   Params:
   `page` - Page instance.
   `opts` - Map, optional. Reload options.
   
   Returns:
   Response or nil, or anomaly map on failure."
  ([^Page page]
   (safe (.reload page)))
  ([^Page page nav-opts]
   (safe (.reload page (opts/->reload-options nav-opts)))))

(defn url
  "Returns the current page URL.
   
   Params:
   `page` - Page instance.
   
   Returns:
   String."
  ^String [^Page page]
  (.url page))

(defn title
  "Returns the page title.
   
   Params:
   `page` - Page instance.
   
   Returns:
   String."
  ^String [^Page page]
  (.title page))

;; =============================================================================
;; Content
;; =============================================================================

(defn content
  "Returns the full HTML content of the page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   String. HTML content."
  ^String [^Page page]
  (.content page))

(defn set-content!
  "Sets the HTML content of the page.
   
   Params:
   `page` - Page instance.
   `html` - String. HTML content.
   `opts` - Map, optional. Options."
  ([^Page page ^String html]
   (safe (.setContent page html)))
  ([^Page page ^String html set-opts]
   (safe (.setContent page html (opts/->set-content-options set-opts)))))

;; =============================================================================
;; Locators
;; =============================================================================

(defn locator
  "Creates a Locator for finding elements on the page.
   
   Params:
   `page`     - Page instance.
   `selector` - String. CSS or text selector.
   
   Returns:
   Locator instance."
  ^Locator [^Page page ^String selector]
  (.locator page selector))

(defn get-by-text
  "Locates elements by their text content.
   
   Params:
   `page` - Page instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page text]
  (if (instance? java.util.regex.Pattern text)
    (.getByText page ^java.util.regex.Pattern text)
    (.getByText page ^String (str text))))

(defn get-by-role
  "Locates elements by their ARIA role.
   
   Params:
   `page` - Page instance.
   `role` - AriaRole enum value.
   `opts` - Map, optional. GetByRoleOptions:
            :name           - String or Pattern. Accessible name to match.
            :exact          - Boolean. Exact match for name.
            :checked        - Boolean. Match checked state.
            :disabled       - Boolean. Match disabled state.
            :expanded       - Boolean. Match expanded state.
            :include-hidden - Boolean. Include hidden elements.
            :level          - Integer. Heading level.
            :pressed        - Boolean. Match pressed state.
            :selected       - Boolean. Match selected state.
   
   Returns:
   Locator instance."
  (^Locator [^Page page ^com.microsoft.playwright.options.AriaRole role]
   (.getByRole page role))
  (^Locator [^Page page ^com.microsoft.playwright.options.AriaRole role opts]
   (.getByRole page role (opts/->get-by-role-options opts))))

(defn get-by-label
  "Locates elements by their label text.
   
   Params:
   `page` - Page instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page text]
  (if (instance? java.util.regex.Pattern text)
    (.getByLabel page ^java.util.regex.Pattern text)
    (.getByLabel page ^String (str text))))

(defn get-by-placeholder
  "Locates elements by placeholder text.
   
   Params:
   `page` - Page instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page text]
  (if (instance? java.util.regex.Pattern text)
    (.getByPlaceholder page ^java.util.regex.Pattern text)
    (.getByPlaceholder page ^String (str text))))

(defn get-by-alt-text
  "Locates elements by alt text.
   
   Params:
   `page` - Page instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page text]
  (if (instance? java.util.regex.Pattern text)
    (.getByAltText page ^java.util.regex.Pattern text)
    (.getByAltText page ^String (str text))))

(defn get-by-title
  "Locates elements by title attribute.
   
   Params:
   `page` - Page instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page text]
  (if (instance? java.util.regex.Pattern text)
    (.getByTitle page ^java.util.regex.Pattern text)
    (.getByTitle page ^String (str text))))

(defn get-by-test-id
  "Locates elements by test ID attribute.
   
   Params:
   `page`    - Page instance.
   `test-id` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Page page test-id]
  (if (instance? java.util.regex.Pattern test-id)
    (.getByTestId page ^java.util.regex.Pattern test-id)
    (.getByTestId page ^String (str test-id))))

(defn get-by-ref
  "Locates an element by its snapshot ref ID (e.g. \"@e2yrjz\", \"@e9mter\").
   The element must have been tagged with data-pw-ref during capture-snapshot.

   Params:
   `page`   - Page instance.
   `ref-id` - String. Ref with @ prefix, e.g. \"@e2yrjz\", \"@e9mter\".

   Returns:
   Locator instance."
  ^Locator [^Page page ^String ref-id]
  (let [clean-ref (if (and (pos? (count ref-id)) (= \@ (first ref-id)))
                    (subs ref-id 1)
                    ref-id)]
    (.locator page (str "[data-pw-ref=\"" clean-ref "\"]") nil)))

;; =============================================================================
;; Evaluation
;; =============================================================================

(defn evaluate
  "Evaluates JavaScript expression in the page context.
   
   Params:
   `page`       - Page instance.
   `expression` - String. JavaScript expression.
   `arg`        - Optional. Argument to pass to the expression.
   
   Returns:
   Result of JavaScript evaluation or anomaly map on failure."
  ([^Page page ^String expression]
   (safe (.evaluate page expression)))
  ([^Page page ^String expression arg]
   (safe (.evaluate page expression arg))))

(defn evaluate-handle
  "Like evaluate, but returns a JSHandle.
   
   Params:
   `page`       - Page instance.
   `expression` - String. JavaScript expression.
   `arg`        - Optional. Argument.
   
   Returns:
   JSHandle or anomaly map."
  ([^Page page ^String expression]
   (safe (.evaluateHandle page expression)))
  ([^Page page ^String expression arg]
   (safe (.evaluateHandle page expression arg))))

(defn scroll
  "Scrolls the page by the given amount in the given direction.

   Params:
   `page`      - Page instance.
   `direction` - Keyword or string: :up :down :left :right (default :down).
   `opts`      - Optional map:
     :amount   - Pixels to scroll (default 500).
     :smooth?  - When true, uses smooth animated scrolling (default false).

   Returns:
   Map with :scrolled, :amount, :smooth keys."
  ([^Page page]
   (scroll page :down {}))
  ([^Page page direction]
   (scroll page direction {}))
  ([^Page page direction opts]
   (let [dir      (name (or direction :down))
         amount   (long (get opts :amount 500))
         smooth?  (boolean (get opts :smooth? false))
         [dx dy]  (case dir
                    "up"    [0 (- amount)]
                    "down"  [0 amount]
                    "left"  [(- amount) 0]
                    "right" [amount 0]
                    [0 amount])
         behavior (if smooth? "'smooth'" "'instant'")
         js-opts  (str "{left: " dx ", top: " dy ", behavior: " behavior "}")]
     (safe (.evaluate page (str "window.scrollBy(" js-opts ")")))
     (when smooth?
       (Thread/sleep (min (long (* amount 0.8)) 800)))
     {:scrolled dir :amount amount :smooth smooth?})))

;; =============================================================================
;; Screenshots & PDF
;; =============================================================================

(defn screenshot
  "Takes a screenshot of the page.
   
   Params:
   `page` - Page instance.
   `opts` - Map, optional. Screenshot options.
   
   Returns:
   byte[] of the image data, or anomaly map on failure."
  ([^Page page]
   (safe (.screenshot page)))
  ([^Page page ss-opts]
   (safe (.screenshot page (opts/->screenshot-options ss-opts)))))

(defn pdf
  "Generates a PDF of the page. Only works in Chromium headless.
   
   Params:
   `page` - Page instance.
   `opts` - Map, optional. PDF options.
   
   Returns:
   byte[] of the PDF data, or anomaly map on failure."
  ([^Page page]
   (safe (.pdf page)))
  ([^Page page pdf-opts]
   (safe (.pdf page (opts/->pdf-options pdf-opts)))))

;; =============================================================================
;; Page State
;; =============================================================================

(defn is-closed?
  "Returns true if the page has been closed.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Boolean."
  [^Page page]
  (.isClosed page))

(defn viewport-size
  "Returns the viewport size of the page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Map with :width and :height, or nil."
  [^Page page]
  (when-let [vs (.viewportSize page)]
    {:width  (.width ^com.microsoft.playwright.options.ViewportSize vs)
     :height (.height ^com.microsoft.playwright.options.ViewportSize vs)}))

(defn set-viewport-size!
  "Sets the viewport size.
   
   Params:
   `page`   - Page instance.
   `width`  - Long. Width in pixels.
   `height` - Long. Height in pixels."
  [^Page page width height]
  (.setViewportSize page (long width) (long height)))

(defn set-default-timeout!
  "Sets the default timeout for page operations.
   
   Params:
   `page`    - Page instance.
   `timeout` - Double. Timeout in milliseconds."
  [^Page page timeout]
  (.setDefaultTimeout page (double timeout)))

(defn set-default-navigation-timeout!
  "Sets the default navigation timeout.
   
   Params:
   `page`    - Page instance.
   `timeout` - Double. Timeout in milliseconds."
  [^Page page timeout]
  (.setDefaultNavigationTimeout page (double timeout)))

;; =============================================================================
;; Frames
;; =============================================================================

(defn main-frame
  "Returns the main frame of the page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Frame instance."
  ^Frame [^Page page]
  (.mainFrame page))

(defn frames
  "Returns all frames in the page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Vector of Frame instances."
  [^Page page]
  (vec (.frames page)))

(defn frame-by-name
  "Returns a frame by its name attribute.
   
   Params:
   `page` - Page instance.
   `name` - String. Frame name.
   
   Returns:
   Frame or nil."
  [^Page page ^String name]
  (.frame page name))

(defn frame-by-url
  "Returns a frame by matching URL pattern.
   
   Params:
   `page`    - Page instance.
   `pattern` - String glob, regex Pattern, or predicate.
   
   Returns:
   Frame or nil."
  [^Page page pattern]
  (cond
    (instance? java.util.regex.Pattern pattern)
    (.frameByUrl page ^java.util.regex.Pattern pattern)

    (string? pattern)
    (.frameByUrl page ^String pattern)

    :else nil))

;; =============================================================================
;; Waiting
;; =============================================================================

(defn wait-for-load-state
  "Waits for the page to reach a load state.
   
   Params:
   `page`  - Page instance.
   `state` - Keyword, optional. :load :domcontentloaded :networkidle.
   
   Returns:
   nil or anomaly map on timeout."
  ([^Page page]
   (safe (.waitForLoadState page)))
  ([^Page page state]
   (safe (.waitForLoadState page (case state
                                   :load LoadState/LOAD
                                   :domcontentloaded LoadState/DOMCONTENTLOADED
                                   :networkidle LoadState/NETWORKIDLE
                                   LoadState/LOAD)))))

(defn wait-for-url
  "Waits for the page to navigate to a URL.
   
   Params:
   `page` - Page instance.
   `url`  - String glob, regex Pattern, or predicate.
   `opts` - Map, optional. Wait options:
            :timeout    - Double. Timeout in ms.
            :wait-until - Keyword. :load :domcontentloaded :networkidle :commit."
  ([^Page page url]
   (safe
     (cond
       (instance? java.util.regex.Pattern url)
       (.waitForURL page ^java.util.regex.Pattern url)

       (string? url)
       (.waitForURL page ^String url)

       :else
       (.waitForURL page ^java.util.function.Predicate
         (reify java.util.function.Predicate
           (test [_ v] (boolean (url v))))))))
  ([^Page page url wait-opts]
   (let [pw-opts (opts/->page-wait-for-url-options wait-opts)]
     (safe
       (cond
         (instance? java.util.regex.Pattern url)
         (.waitForURL page ^java.util.regex.Pattern url pw-opts)

         (string? url)
         (.waitForURL page ^String url pw-opts)

         :else
         (.waitForURL page ^java.util.function.Predicate
           (reify java.util.function.Predicate
             (test [_ v] (boolean (url v))))
           pw-opts))))))

(defn wait-for-selector
  "Waits for a selector to satisfy a condition.
   
   Params:
   `page`     - Page instance.
   `selector` - String. CSS selector.
   `opts`     - Map, optional. Wait options.
   
   Returns:
   ElementHandle or nil, or anomaly map on timeout."
  ([^Page page ^String selector]
   (safe (.waitForSelector page selector)))
  ([^Page page ^String selector wait-opts]
   (safe (.waitForSelector page selector (opts/->wait-for-selector-options wait-opts)))))

(defn wait-for-timeout
  "Waits for the specified time in milliseconds.
   
   Params:
   `page`    - Page instance.
   `timeout` - Double. Time to wait in ms."
  [^Page page timeout]
  (.waitForTimeout page (double timeout)))

(defn wait-for-function
  "Waits for a JavaScript function to return a truthy value.
   
   Params:
   `page`       - Page instance.
   `expression` - String. JavaScript expression.
   `opts`       - Map, optional. Options:
                  :timeout  - Double. Timeout in ms (default 30000).
                  :polling  - Double. Polling interval in ms.
                  :arg      - Optional argument passed as first function param.
   
   Returns:
   JSHandle or anomaly map on timeout."
  ([^Page page ^String expression]
   (safe (.waitForFunction page expression)))
  ([^Page page ^String expression opts]
   (let [arg        (when (contains? opts :arg) (:arg opts))
         wait-opts  (dissoc opts :arg)]
     (safe (.waitForFunction page expression arg (opts/->page-wait-for-function-options wait-opts))))))

(defn wait-for-response
  "Waits for a response matching the URL or predicate.
   
   Params:
   `page`      - Page instance.
   `url-or-fn` - String or predicate fn.
   `callback`  - Runnable to trigger the response.
   
   Returns:
   Response or anomaly map on timeout."
  [^Page page url-or-fn ^Runnable callback]
  (safe
    (if (string? url-or-fn)
      (.waitForResponse page ^String url-or-fn callback)
      (.waitForResponse page
        ^java.util.function.Predicate
        (reify java.util.function.Predicate
          (test [_ v] (boolean (url-or-fn v))))
        callback))))

(defn wait-for-popup
  "Waits for a popup page to open while executing `action`.
   
   Params:
   `page`   - Page instance.
   `action` - No-arg function that triggers the popup.
   `opts`   - Optional map. {:predicate fn, :timeout ms}
   
   Returns:
   Page (the popup) or anomaly map on timeout."
  ([^Page page action]
   (safe (.waitForPopup page (reify Runnable (run [_] (action))))))
  ([^Page page action opts]
   (safe (.waitForPopup page (opts/->wait-for-popup-options opts)
           (reify Runnable (run [_] (action)))))))

(defn wait-for-download
  "Waits for a download to start while executing `action`.
   
   Params:
   `page`   - Page instance.
   `action` - No-arg function that triggers the download.
   `opts`   - Optional map. {:predicate fn, :timeout ms}
   
   Returns:
   Download or anomaly map on timeout."
  ([^Page page action]
   (safe (.waitForDownload page (reify Runnable (run [_] (action))))))
  ([^Page page action opts]
   (safe (.waitForDownload page (opts/->wait-for-download-options opts)
           (reify Runnable (run [_] (action)))))))

(defn wait-for-file-chooser
  "Waits for a file chooser dialog while executing `action`.
   
   Params:
   `page`   - Page instance.
   `action` - No-arg function that triggers the file chooser (e.g. clicking an input).
   `opts`   - Optional map. {:predicate fn, :timeout ms}
   
   Returns:
   FileChooser or anomaly map on timeout.
   
   Example:
   (let [fc (page/wait-for-file-chooser pg
              #(locator/click (page/locator pg \"input[type=file]\")))]
      (page/file-chooser-set-files! fc \"/path/to/file.txt\"))"
  ([^Page page action]
   (safe (.waitForFileChooser page (reify Runnable (run [_] (action))))))
  ([^Page page action opts]
   (safe (.waitForFileChooser page (opts/->wait-for-file-chooser-options opts)
           (reify Runnable (run [_] (action)))))))

;; =============================================================================
;; Emulation
;; =============================================================================

(defn emulate-media!
  "Emulates media type and features.
   
   Params:
   `page` - Page instance.
   `opts` - Map. Emulate media options."
  [^Page page media-opts]
  (safe (.emulateMedia page (opts/->emulate-media-options media-opts))))

;; =============================================================================
;; Page Events
;; =============================================================================

(defn on-console
  "Registers a handler for console messages.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a ConsoleMessage."
  [^Page page handler]
  (.onConsoleMessage page
    (reify java.util.function.Consumer
      (accept [_ msg] (handler msg)))))

(defn on-dialog
  "Registers a handler for dialogs.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Dialog."
  [^Page page handler]
  (.onDialog page
    (reify java.util.function.Consumer
      (accept [_ dialog] (handler dialog)))))

(defn once-dialog
  "Registers a one-time handler for the next dialog.
   The handler is automatically removed after the first dialog is handled.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Dialog."
  [^Page page handler]
  (.onceDialog page
    (reify java.util.function.Consumer
      (accept [_ dialog] (handler dialog)))))

(defn off-dialog
  "Removes a previously registered dialog handler.

   Params:
   `page`    - Page instance.
   `handler` - The handler (Consumer) to remove."
  [^Page page handler]
  (.offDialog page handler))

(defn on-page-error
  "Registers a handler for page errors.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives an error string."
  [^Page page handler]
  (.onPageError page
    (reify java.util.function.Consumer
      (accept [_ error] (handler error)))))

(defn on-request
  "Registers a handler for requests.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Request."
  [^Page page handler]
  (.onRequest page
    (reify java.util.function.Consumer
      (accept [_ req] (handler req)))))

(defn on-response
  "Registers a handler for responses.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Response."
  [^Page page handler]
  (.onResponse page
    (reify java.util.function.Consumer
      (accept [_ resp] (handler resp)))))

(defn on-close
  "Registers a handler for page close.
   
   Params:
   `page`    - Page instance.
   `handler` - Function called with the page when it closes."
  [^Page page handler]
  (.onClose page
    (reify java.util.function.Consumer
      (accept [_ p] (handler p)))))

(defn on-download
  "Registers a handler for downloads.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Download."
  [^Page page handler]
  (.onDownload page
    (reify java.util.function.Consumer
      (accept [_ dl] (handler dl)))))

(defn on-popup
  "Registers a handler for popup pages.
   
   Params:
   `page`    - Page instance.
   `handler` - Function that receives a Page."
  [^Page page handler]
  (.onPopup page
    (reify java.util.function.Consumer
      (accept [_ p] (handler p)))))

;; =============================================================================
;; Routing
;; =============================================================================

(defn route!
  "Registers a route handler for URL pattern.
   
   Params:
   `page`    - Page instance.
   `pattern` - String glob or regex Pattern.
   `handler` - Function that receives a Route."
  [^Page page pattern handler]
  (let [consumer (reify java.util.function.Consumer
                   (accept [_ route] (handler route)))]
    (if (instance? java.util.regex.Pattern pattern)
      (.route page ^java.util.regex.Pattern pattern consumer)
      (.route page ^String (str pattern) consumer))))

(defn unroute!
  "Removes a route handler.
   
   Params:
   `page`    - Page instance.
   `pattern` - String glob or regex Pattern."
  [^Page page pattern]
  (if (instance? java.util.regex.Pattern pattern)
    (.unroute page ^java.util.regex.Pattern pattern)
    (.unroute page ^String (str pattern))))

(defn route-from-har!
  "Routes requests from a HAR file. Replays recorded responses for matching requests.

   Use with :update true to record actual responses into the HAR for later replay.

   Params:
   `page` - Page instance.
   `har`  - String. Path to the HAR file.
   `opts` - Map, optional. RouteFromHAR options:
            :url            - String glob or regex Pattern. Only intercept matching URLs.
            :not-found      - Keyword. :abort or :fallback.
            :update         - Boolean. Whether to update HAR with actual network data.
            :update-content - Keyword. :embed or :attach.
            :update-mode    - Keyword. :full or :minimal."
  ([^Page page ^String har]
   (safe (.routeFromHAR page (java.nio.file.Paths/get har (into-array String [])))))
  ([^Page page ^String har route-opts]
   (safe (.routeFromHAR page
           (java.nio.file.Paths/get har (into-array String []))
           (opts/->page-route-from-har-options route-opts)))))

(defn route-web-socket!
  "Registers a handler for WebSocket connections matching a URL pattern.

   The handler receives a WebSocketRoute that can be used to mock the
   WebSocket connection (send messages, intercept client messages, etc.).

   Params:
   `page`    - Page instance.
   `pattern` - String glob, regex Pattern, or predicate fn.
   `handler` - Function that receives a WebSocketRoute."
  [^Page page pattern handler]
  (let [consumer (reify java.util.function.Consumer
                   (accept [_ wsr] (handler wsr)))]
    (cond
      (instance? java.util.regex.Pattern pattern)
      (.routeWebSocket page ^java.util.regex.Pattern pattern consumer)

      (string? pattern)
      (.routeWebSocket page ^String pattern consumer)

      :else
      (.routeWebSocket page
        ^java.util.function.Predicate
        (reify java.util.function.Predicate
          (test [_ v] (boolean (pattern v))))
        consumer))))

;; =============================================================================
;; Misc
;; =============================================================================

(defn bring-to-front
  "Brings page to front (activates tab).
   
   Params:
   `page` - Page instance."
  [^Page page]
  (.bringToFront page))

(defn page-context
  "Returns the BrowserContext that the page belongs to.
   
   Params:
   `page` - Page instance.
   
   Returns:
   BrowserContext instance."
  [^Page page]
  (.context page))

(defn add-script-tag
  "Adds a script tag to the page.
   
   Params:
   `page` - Page instance.
   `opts` - Map with :url, :path, or :content.
   
   Returns:
   ElementHandle or anomaly map."
  [^Page page opts]
  (safe (.addScriptTag page (opts/->page-add-script-tag-options opts))))

(defn add-style-tag
  "Adds a style tag to the page.
   
   Params:
   `page` - Page instance.
   `opts` - Map with :url, :path, or :content.
   
   Returns:
   ElementHandle or anomaly map."
  [^Page page opts]
  (safe (.addStyleTag page (opts/->page-add-style-tag-options opts))))

(defn page-keyboard
  "Returns the Keyboard for this page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Keyboard instance."
  [^Page page]
  (.keyboard page))

(defn page-mouse
  "Returns the Mouse for this page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Mouse instance."
  [^Page page]
  (.mouse page))

(defn page-touchscreen
  "Returns the Touchscreen for this page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Touchscreen instance."
  [^Page page]
  (.touchscreen page))

(defn video
  "Returns the Video for this page, if recording.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Video or nil."
  [^Page page]
  (.video page))

(defn workers
  "Returns all workers in the page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Vector of Worker instances."
  [^Page page]
  (vec (.workers page)))

(defn opener
  "Returns the opener page, if any.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Page or nil."
  [^Page page]
  (.opener page))

(defn set-extra-http-headers!
  "Sets extra HTTP headers for all requests on this page.
   
   Params:
   `page`    - Page instance.
   `headers` - Map of string->string."
  [^Page page headers]
  (.setExtraHTTPHeaders page ^java.util.Map headers))

(defn expose-function!
  "Exposes a Clojure function to JavaScript.
   
   Params:
   `page` - Page instance.
   `name` - String. Function name in JavaScript.
   `fn`   - Function. The Clojure function to expose."
  [^Page page ^String name f]
  (.exposeFunction page name
    (reify FunctionCallback
      (call [_ args]
        (apply f (vec args))))))

(defn expose-binding!
  "Exposes a Clojure function as a binding.
   
   Params:
   `page` - Page instance.
   `name` - String. Binding name.
   `fn`   - Function. The binding function."
  [^Page page ^String name f]
  (.exposeBinding page name
    (reify BindingCallback
      (call [_ source args]
        (apply f source (vec args))))))

;; =============================================================================
;; Dialog
;; =============================================================================

(defn dialog-type
  "Returns the dialog type (alert, confirm, prompt, beforeunload).
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.type dialog))

(defn dialog-message
  "Returns the dialog message.
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.message dialog))

(defn dialog-default-value
  "Returns the default value for prompt dialogs.
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.defaultValue dialog))

(defn dialog-accept!
  "Accepts the dialog.
   
   Params:
   `dialog`     - Dialog instance.
   `prompt-text` - String, optional. Text for prompt dialogs."
  ([^Dialog dialog]
   (safe (.accept dialog)))
  ([^Dialog dialog ^String prompt-text]
   (safe (.accept dialog prompt-text))))

(defn dialog-dismiss!
  "Dismisses the dialog.
   
   Params:
   `dialog` - Dialog instance."
  [^Dialog dialog]
  (safe (.dismiss dialog)))

;; =============================================================================
;; Download
;; =============================================================================

(defn download-url
  "Returns the download URL.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String."
  ^String [^Download download]
  (.url download))

(defn download-suggested-filename
  "Returns the suggested filename.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String."
  ^String [^Download download]
  (.suggestedFilename download))

(defn download-path
  "Returns the local path to the downloaded file.
   
   Params:
   `download` - Download instance.
   
   Returns:
   Path or nil."
  [^Download download]
  (safe (.path download)))

(defn download-save-as!
  "Saves the download to the given path.
   
   Params:
   `download` - Download instance.
   `path`     - String. Destination path."
  [^Download download ^String path]
  (safe (.saveAs download (java.nio.file.Paths/get path (into-array String [])))))

(defn download-cancel!
  "Cancels the download.
   
   Params:
   `download` - Download instance."
  [^Download download]
  (.cancel download))

(defn download-failure
  "Returns the download failure reason, or nil.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String or nil."
  [^Download download]
  (.failure download))

(defn download-page
  "Returns the page the download belongs to.
   
   Params:
   `download` - Download instance.
   
   Returns:
   Page instance."
  ^Page [^Download download]
  (.page download))

;; =============================================================================
;; ConsoleMessage
;; =============================================================================

(defn console-type
  "Returns the console message type (log, debug, info, error, warning, etc).
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String."
  ^String [^ConsoleMessage msg]
  (.type msg))

(defn console-text
  "Returns the console message text.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String."
  ^String [^ConsoleMessage msg]
  (.text msg))

(defn console-args
  "Returns the console message arguments as JSHandles.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   Vector of JSHandle."
  [^ConsoleMessage msg]
  (vec (.args msg)))

(defn console-location
  "Returns the source location of the console message.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String. The location string."
  ^String [^ConsoleMessage msg]
  (.location msg))

(defn console-page
  "Returns the page the console message belongs to.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   Page instance."
  ^Page [^ConsoleMessage msg]
  (.page msg))

;; =============================================================================
;; Clock
;; =============================================================================

(defn page-clock
  "Returns the Clock for a page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Clock instance."
  ^Clock [^Page page]
  (.clock page))

(defn clock-install!
  "Installs fake timers on the clock.
   
   Params:
   `clock` - Clock instance."
  [^Clock clock]
  (.install clock))

(defn clock-set-fixed-time!
  "Sets the clock to a fixed time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.setFixedTime clock (long time)))

(defn clock-set-system-time!
  "Sets the system time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.setSystemTime clock (long time)))

(defn clock-fast-forward!
  "Fast-forwards the clock by the given time.
   
   Params:
   `clock` - Clock instance.
   `ticks` - Long. Time to advance in ms."
  [^Clock clock ticks]
  (.fastForward clock (long ticks)))

(defn clock-pause-at!
  "Pauses the clock at the given time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.pauseAt clock (long time)))

(defn clock-resume!
  "Resumes the clock.
   
   Params:
   `clock` - Clock instance."
  ^Clock [^Clock clock]
  (.resume clock))

;; =============================================================================
;; Worker
;; =============================================================================

(defn worker-url
  "Returns the worker URL.
   
   Params:
   `worker` - Worker instance.
   
   Returns:
   String."
  ^String [^Worker worker]
  (.url worker))

(defn worker-evaluate
  "Evaluates JavaScript in the worker context.
   
   Params:
   `worker`     - Worker instance.
   `expression` - String.
   `arg`        - Optional argument.
   
   Returns:
   Result or anomaly map."
  ([^Worker worker ^String expression]
   (safe (.evaluate worker expression)))
  ([^Worker worker ^String expression arg]
   (safe (.evaluate worker expression arg))))

;; =============================================================================
;; FileChooser
;; =============================================================================

(defn file-chooser-page
  "Returns the page the file chooser belongs to.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   Page instance."
  ^Page [^FileChooser fc]
  (.page fc))

(defn file-chooser-element
  "Returns the element handle for the file input.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   ElementHandle."
  [^FileChooser fc]
  (.element fc))

(defn file-chooser-is-multiple?
  "Returns whether the file chooser accepts multiple files.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   Boolean."
  [^FileChooser fc]
  (.isMultiple fc))

(defn file-chooser-set-files!
  "Sets the files for the file chooser.
   
   Params:
   `fc`    - FileChooser instance.
   `files` - String path or vector of paths."
  [^FileChooser fc files]
  (safe
    (if (sequential? files)
      (.setFiles fc ^"[Ljava.nio.file.Path;"
        (into-array java.nio.file.Path
          (map #(java.nio.file.Paths/get ^String % (into-array String []))
            files)))
      (.setFiles fc (java.nio.file.Paths/get ^String (str files) (into-array String []))))))

;; =============================================================================
;; WebError
;; =============================================================================

(defn web-error-page
  "Returns the page that generated this web error, if any.

   Params:
   `we` - WebError instance.

   Returns:
   Page instance or nil."
  [^WebError we]
  (.page we))

(defn web-error-error
  "Returns the underlying error for this web error.

   Params:
   `we` - WebError instance.

   Returns:
   String. The error message."
  [^WebError we]
  (.error we))

;; =============================================================================
;; Clipboard
;; =============================================================================

(defn clipboard-copy
  "Writes text to the browser clipboard.

   Grants clipboard permissions on the page's context, then calls
   navigator.clipboard.writeText(). Some browsers may not support
   permission grants — the copy is attempted regardless.

   Params:
   `page` - Page instance.
   `text` - String. Text to write to clipboard.

   Returns:
   {:copied true :text text}."
  [^Page page ^String text]
  (try
    (.grantPermissions ^BrowserContext (.context page)
      (java.util.List/of "clipboard-read" "clipboard-write"))
    (catch Exception _ nil))
  (safe (.evaluate page "text => navigator.clipboard.writeText(text)" text))
  {:copied true :text text})

(defn clipboard-read
  "Reads text from the browser clipboard.

   Grants clipboard permissions on the page's context, then calls
   navigator.clipboard.readText().

   Params:
   `page` - Page instance.

   Returns:
   {:content <string>}."
  [^Page page]
  (try
    (.grantPermissions ^BrowserContext (.context page)
      (java.util.List/of "clipboard-read" "clipboard-write"))
    (catch Exception _ nil))
  (let [result (safe (.evaluate page "() => navigator.clipboard.readText()"))]
    (if (anomaly/anomaly? result)
      result
      {:content result})))

(defn clipboard-paste
  "Pastes clipboard contents into the currently focused element.

   Reads the clipboard, then dispatches Ctrl+V via the page keyboard.

   Params:
   `page` - Page instance.

   Returns:
   {:pasted true :text <clipboard-content>}."
  [^Page page]
  (try
    (.grantPermissions ^BrowserContext (.context page)
      (java.util.List/of "clipboard-read" "clipboard-write"))
    (catch Exception _ nil))
  (let [text (safe (.evaluate page "() => navigator.clipboard.readText()"))]
    (if (anomaly/anomaly? text)
      text
      (do (.press ^com.microsoft.playwright.Keyboard (.keyboard page) "Control+v")
          {:pasted true :text text}))))
