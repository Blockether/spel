(ns com.blockether.spel.page
  "Page operations - navigation, content, evaluation, screenshots.
   
   The Page class is the central API surface of Playwright. Wraps all
   Page methods with idiomatic Clojure functions."
  (:require
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Page Locator Frame]
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
   `url`  - String glob, regex Pattern, or predicate."
  [^Page page url]
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
   
   Returns:
   JSHandle or anomaly map on timeout."
  [^Page page ^String expression]
  (safe (.waitForFunction page expression)))

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
     (util/file-chooser-set-files! fc \"/path/to/file.txt\"))"
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
