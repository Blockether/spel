(ns com.blockether.spel.frame
  "Frame and FrameLocator operations."
  (:require
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Frame FrameLocator Locator]
   [com.microsoft.playwright.options LoadState]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Frame Navigation
;; =============================================================================

(defn frame-navigate
  "Navigates the frame to a URL.
   
   Params:
   `frame` - Frame instance.
   `url`   - String.
   `opts`  - Map, optional. Navigation options.
   
   Returns:
   Response or nil, or anomaly map."
  ([^Frame frame ^String url]
   (safe (.navigate frame url)))
  ([^Frame frame ^String url nav-opts]
   (safe (.navigate frame url (opts/->frame-navigate-options nav-opts)))))

;; =============================================================================
;; Frame Content
;; =============================================================================

(defn frame-content
  "Returns the HTML content of the frame.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   String."
  ^String [^Frame frame]
  (.content frame))

(defn frame-set-content!
  "Sets the HTML content of the frame.
   
   Params:
   `frame` - Frame instance.
   `html`  - String."
  ([^Frame frame ^String html]
   (safe (.setContent frame html)))
  ([^Frame frame ^String html set-opts]
   (safe (.setContent frame html (opts/->frame-set-content-options set-opts)))))

(defn frame-url
  "Returns the frame URL.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   String."
  ^String [^Frame frame]
  (.url frame))

(defn frame-name
  "Returns the frame name.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   String."
  ^String [^Frame frame]
  (.name frame))

(defn frame-title
  "Returns the frame title.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   String."
  ^String [^Frame frame]
  (.title frame))

;; =============================================================================
;; Frame Locators
;; =============================================================================

(defn frame-locator
  "Creates a Locator for the frame.
   
   Params:
   `frame`    - Frame instance.
   `selector` - String. CSS selector.
   
   Returns:
   Locator instance."
  ^Locator [^Frame frame ^String selector]
  (.locator frame selector))

(defn frame-get-by-text
  "Locates elements by text in the frame.
   
   Params:
   `frame` - Frame instance.
   `text`  - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Frame frame text]
  (if (instance? java.util.regex.Pattern text)
    (.getByText frame ^java.util.regex.Pattern text)
    (.getByText frame ^String (str text))))

(defn frame-get-by-role
  "Locates elements by ARIA role in the frame.
   
   Params:
   `frame` - Frame instance.
   `role`  - AriaRole enum value.
   
   Returns:
   Locator instance."
  ^Locator [^Frame frame ^com.microsoft.playwright.options.AriaRole role]
  (.getByRole frame role))

(defn frame-get-by-label
  "Locates elements by label in the frame.
   
   Params:
   `frame` - Frame instance.
   `text`  - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Frame frame text]
  (if (instance? java.util.regex.Pattern text)
    (.getByLabel frame ^java.util.regex.Pattern text)
    (.getByLabel frame ^String (str text))))

(defn frame-get-by-test-id
  "Locates elements by test ID in the frame.
   
   Params:
   `frame`   - Frame instance.
   `test-id` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Frame frame test-id]
  (if (instance? java.util.regex.Pattern test-id)
    (.getByTestId frame ^java.util.regex.Pattern test-id)
    (.getByTestId frame ^String (str test-id))))

;; =============================================================================
;; Frame Evaluation
;; =============================================================================

(defn frame-evaluate
  "Evaluates JavaScript in the frame context.
   
   Params:
   `frame`      - Frame instance.
   `expression` - String. JavaScript expression.
   `arg`        - Optional argument.
   
   Returns:
   Result or anomaly map."
  ([^Frame frame ^String expression]
   (safe (.evaluate frame expression)))
  ([^Frame frame ^String expression arg]
   (safe (.evaluate frame expression arg))))

;; =============================================================================
;; Frame Hierarchy
;; =============================================================================

(defn parent-frame
  "Returns the parent frame.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   Frame or nil."
  [^Frame frame]
  (.parentFrame frame))

(defn child-frames
  "Returns child frames.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   Vector of Frame."
  [^Frame frame]
  (vec (.childFrames frame)))

(defn frame-page
  "Returns the page that owns this frame.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   Page instance."
  [^Frame frame]
  (.page frame))

(defn is-detached?
  "Returns whether the frame has been detached.
   
   Params:
   `frame` - Frame instance.
   
   Returns:
   Boolean."
  [^Frame frame]
  (.isDetached frame))

;; =============================================================================
;; Frame Waiting
;; =============================================================================

(defn frame-wait-for-load-state
  "Waits for the frame to reach a load state.
   
   Params:
   `frame` - Frame instance.
   `state` - Keyword. :load :domcontentloaded :networkidle."
  ([^Frame frame]
   (safe (.waitForLoadState frame)))
  ([^Frame frame state]
   (safe (.waitForLoadState frame (case state
                                    :load LoadState/LOAD
                                    :domcontentloaded LoadState/DOMCONTENTLOADED
                                    :networkidle LoadState/NETWORKIDLE
                                    LoadState/LOAD)))))

(defn frame-wait-for-selector
  "Waits for a selector in the frame.
   
   Params:
   `frame`    - Frame instance.
   `selector` - String.
   `opts`     - Map, optional.
   
   Returns:
   ElementHandle or nil, or anomaly map."
  ([^Frame frame ^String selector]
   (safe (.waitForSelector frame selector)))
  ([^Frame frame ^String selector wait-opts]
   (safe (.waitForSelector frame selector (opts/->frame-wait-for-selector-options wait-opts)))))

(defn frame-wait-for-function
  "Waits for a JavaScript function to return truthy in the frame.
   
   Params:
   `frame`      - Frame instance.
   `expression` - String. JavaScript expression.
   
   Returns:
   JSHandle or anomaly map."
  [^Frame frame ^String expression]
  (safe (.waitForFunction frame expression)))

;; =============================================================================
;; FrameLocator
;; =============================================================================

(defn frame-locator-obj
  "Creates a FrameLocator for an iframe.
   
   Params:
   `page-or-frame` - Page or Frame instance.
   `selector`      - String. CSS selector for the iframe.
   
   Returns:
   FrameLocator instance."
  [page-or-frame ^String selector]
  (cond
    (instance? com.microsoft.playwright.Page page-or-frame)
    (.frameLocator ^com.microsoft.playwright.Page page-or-frame selector)

    (instance? Frame page-or-frame)
    (.frameLocator ^Frame page-or-frame selector)

    :else
    (throw (IllegalArgumentException. "Expected Page or Frame"))))

(defn fl-locator
  "Creates a Locator within a FrameLocator.
   
   Params:
   `fl`       - FrameLocator instance.
   `selector` - String. CSS selector.
   
   Returns:
   Locator instance."
  ^Locator [^FrameLocator fl ^String selector]
  (.locator fl selector))

(defn fl-get-by-text
  "Locates by text within a FrameLocator.
   
   Params:
   `fl`   - FrameLocator instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^FrameLocator fl text]
  (if (instance? java.util.regex.Pattern text)
    (.getByText fl ^java.util.regex.Pattern text)
    (.getByText fl ^String (str text))))

(defn fl-get-by-role
  "Locates by ARIA role within a FrameLocator.
   
   Params:
   `fl`   - FrameLocator instance.
   `role` - AriaRole enum value.
   
   Returns:
   Locator instance."
  ^Locator [^FrameLocator fl ^com.microsoft.playwright.options.AriaRole role]
  (.getByRole fl role))

(defn fl-get-by-label
  "Locates by label within a FrameLocator.
   
   Params:
   `fl`   - FrameLocator instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^FrameLocator fl text]
  (if (instance? java.util.regex.Pattern text)
    (.getByLabel fl ^java.util.regex.Pattern text)
    (.getByLabel fl ^String (str text))))

(defn fl-first
  "Returns the first FrameLocator.
   
   Params:
   `fl` - FrameLocator instance.
   
   Returns:
   FrameLocator instance."
  ^FrameLocator [^FrameLocator fl]
  (.first fl))

(defn fl-last
  "Returns the last FrameLocator.
   
   Params:
   `fl` - FrameLocator instance.
   
   Returns:
   FrameLocator instance."
  ^FrameLocator [^FrameLocator fl]
  (.last fl))

(defn fl-nth
  "Returns the nth FrameLocator.
   
   Params:
   `fl`    - FrameLocator instance.
   `index` - Long. Zero-based index.
   
   Returns:
   FrameLocator instance."
  ^FrameLocator [^FrameLocator fl index]
  (.nth fl (long index)))
