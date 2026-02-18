(ns com.blockether.spel.locator
  "Locator and ElementHandle operations.
   
   Locators are the primary way to find and interact with elements.
   They auto-wait and auto-retry, making them the preferred API."
  (:require
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Locator ElementHandle JSHandle
    Locator$FilterOptions]
   [com.microsoft.playwright.options AriaRole]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Locator Actions
;; =============================================================================

(defn click
  "Clicks an element.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Click options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc]
   (safe (.click loc)))
  ([^Locator loc click-opts]
   (safe (.click loc (opts/->click-options click-opts)))))

(defn dblclick
  "Double-clicks an element.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Double-click options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc]
   (safe (.dblclick loc)))
  ([^Locator loc dblclick-opts]
   (safe (.dblclick loc (opts/->dblclick-options dblclick-opts)))))

(defn fill
  "Fills an input element with text.
   
   Params:
   `loc`   - Locator instance.
   `value` - String. Text to fill.
   `opts`  - Map, optional. Fill options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc ^String value]
   (safe (.fill loc value)))
  ([^Locator loc ^String value fill-opts]
   (safe (.fill loc value (opts/->fill-options fill-opts)))))

(defn type-text
  "Types text into an element character by character.
   
   Params:
   `loc`  - Locator instance.
   `text` - String. Text to type.
   `opts` - Map, optional. Type options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc ^String text]
   (safe (.type loc text)))
  ([^Locator loc ^String text type-opts]
   (safe (.type loc text (opts/->type-options type-opts)))))

(defn press
  "Presses a key or key combination.
   
   Params:
   `loc` - Locator instance.
   `key` - String. Key to press (e.g. \"Enter\", \"Control+a\").
   `opts` - Map, optional. Press options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc ^String key]
   (safe (.press loc key)))
  ([^Locator loc ^String key press-opts]
   (safe (.press loc key (opts/->press-options press-opts)))))

(defn clear
  "Clears input field content.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   nil or anomaly map on failure."
  [^Locator loc]
  (safe (.clear loc)))

(defn check
  "Checks a checkbox or radio button.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Check options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc]
   (safe (.check loc)))
  ([^Locator loc check-opts]
   (safe (.check loc (opts/->check-options check-opts)))))

(defn uncheck
  "Unchecks a checkbox.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Uncheck options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc]
   (safe (.uncheck loc)))
  ([^Locator loc uncheck-opts]
   (safe (.uncheck loc (opts/->uncheck-options uncheck-opts)))))

(defn hover
  "Hovers over an element.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Hover options.
   
   Returns:
   nil or anomaly map on failure."
  ([^Locator loc]
   (safe (.hover loc)))
  ([^Locator loc hover-opts]
   (safe (.hover loc (opts/->hover-options hover-opts)))))

(defn tap-element
  "Taps an element (for touch devices).
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   nil or anomaly map on failure."
  [^Locator loc]
  (safe (.tap loc)))

(defn focus
  "Focuses the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   nil or anomaly map on failure."
  [^Locator loc]
  (safe (.focus loc)))

(defn blur
  "Blurs (removes focus from) the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   nil or anomaly map on failure."
  [^Locator loc]
  (safe (.blur loc)))

(defn select-option
  "Selects options in a select element.
   
   Params:
   `loc`    - Locator instance.
   `values` - String, vector of strings, or SelectOption.
   
   Returns:
   Vector of selected values or anomaly map."
  [^Locator loc values]
  (safe
    (vec
      (cond
        (string? values)
        (.selectOption loc ^String values)

        (sequential? values)
        (.selectOption loc ^"[Ljava.lang.String;" (into-array String values))

        :else
        (.selectOption loc ^String (str values))))))

(defn set-input-files!
  "Sets the value of a file input element.
   
   Params:
   `loc`   - Locator instance.
   `files` - String path or vector of string paths.
   
   Returns:
   nil or anomaly map."
  [^Locator loc files]
  (safe
    (if (sequential? files)
      (.setInputFiles loc ^"[Ljava.nio.file.Path;"
        (into-array java.nio.file.Path
          (map #(java.nio.file.Paths/get ^String % (into-array String []))
            files)))
      (.setInputFiles loc (java.nio.file.Paths/get ^String (str files) (into-array String []))))))

(defn scroll-into-view
  "Scrolls element into view.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   nil or anomaly map."
  [^Locator loc]
  (safe (.scrollIntoViewIfNeeded loc)))

(defn dispatch-event
  "Dispatches a DOM event on the element.
   
   Params:
   `loc`  - Locator instance.
   `type` - String. Event type (e.g. \"click\").
   
   Returns:
   nil or anomaly map."
  [^Locator loc ^String type]
  (safe (.dispatchEvent loc type)))

(defn drag-to
  "Drags this locator to another locator.
   
   Params:
   `loc`    - Locator instance (source).
   `target` - Locator instance (target).
   
   Returns:
   nil or anomaly map."
  [^Locator loc ^Locator target]
  (safe (.dragTo loc target)))

;; =============================================================================
;; Locator State
;; =============================================================================

(defn text-content
  "Returns the text content of the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   String or nil, or anomaly map."
  [^Locator loc]
  (safe (.textContent loc)))

(defn inner-text
  "Returns the inner text of the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   String, or anomaly map."
  [^Locator loc]
  (safe (.innerText loc)))

(defn inner-html
  "Returns the inner HTML of the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   String, or anomaly map."
  [^Locator loc]
  (safe (.innerHTML loc)))

(defn input-value
  "Returns the input value of an input element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   String, or anomaly map."
  [^Locator loc]
  (safe (.inputValue loc)))

(defn get-attribute
  "Returns the value of an attribute.
   
   Params:
   `loc`  - Locator instance.
   `name` - String. Attribute name.
   
   Returns:
   String or nil, or anomaly map."
  [^Locator loc ^String name]
  (safe (.getAttribute loc name)))

(defn is-visible?
  "Returns whether the element is visible.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isVisible loc))

(defn is-hidden?
  "Returns whether the element is hidden.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isHidden loc))

(defn is-enabled?
  "Returns whether the element is enabled.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isEnabled loc))

(defn is-disabled?
  "Returns whether the element is disabled.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isDisabled loc))

(defn is-editable?
  "Returns whether the element is editable.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isEditable loc))

(defn is-checked?
  "Returns whether the element is checked.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Boolean."
  [^Locator loc]
  (.isChecked loc))

(defn bounding-box
  "Returns the bounding box of the element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Map with :x :y :width :height or nil."
  [^Locator loc]
  (when-let [bb (.boundingBox loc)]
    {:x      (.-x bb)
     :y      (.-y bb)
     :width  (.-width bb)
     :height (.-height bb)}))

(defn count-elements
  "Returns the number of elements matching the locator.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Long."
  ^long [^Locator loc]
  (.count loc))

(defn all-text-contents
  "Returns all text contents for matching elements.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Vector of strings."
  [^Locator loc]
  (vec (.allTextContents loc)))

(defn all-inner-texts
  "Returns all inner texts for matching elements.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Vector of strings."
  [^Locator loc]
  (vec (.allInnerTexts loc)))

(defn all
  "Returns all elements matching the locator as individual locators.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Vector of Locator instances."
  [^Locator loc]
  (vec (.all loc)))

;; =============================================================================
;; Locator Filtering
;; =============================================================================

(defn loc-filter
  "Filters this locator to a narrower set.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map with optional:
     :has-text - String or Pattern.
     :has      - Locator.
     :has-not  - Locator.
     :has-not-text - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc opts]
  (let [^Locator$FilterOptions fo (Locator$FilterOptions.)]
    (when-let [v (:has-text opts)]
      (if (instance? java.util.regex.Pattern v)
        (.setHasText fo ^java.util.regex.Pattern v)
        (.setHasText fo ^String (str v))))
    (when-let [v (:has opts)]
      (.setHas fo ^Locator v))
    (when-let [v (:has-not opts)]
      (.setHasNot fo ^Locator v))
    (when-let [v (:has-not-text opts)]
      (if (instance? java.util.regex.Pattern v)
        (.setHasNotText fo ^java.util.regex.Pattern v)
        (.setHasNotText fo ^String (str v))))
    (.filter loc fo)))

(defn first-element
  "Returns the first element matching the locator.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Locator for the first element."
  ^Locator [^Locator loc]
  (.first loc))

(defn last-element
  "Returns the last element matching the locator.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Locator for the last element."
  ^Locator [^Locator loc]
  (.last loc))

(defn nth-element
  "Returns the nth element matching the locator.
   
   Params:
   `loc`   - Locator instance.
   `index` - Long. Zero-based index.
   
   Returns:
   Locator for the nth element."
  ^Locator [^Locator loc index]
  (.nth loc (long index)))

(defn loc-locator
  "Creates a sub-locator within this locator.
   
   Params:
   `loc`      - Locator instance.
   `selector` - String. CSS or text selector.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc ^String selector]
  (.locator loc selector))

(defn loc-get-by-text
  "Locates elements by text within this locator.
   
   Params:
   `loc`  - Locator instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc text]
  (if (instance? java.util.regex.Pattern text)
    (.getByText loc ^java.util.regex.Pattern text)
    (.getByText loc ^String (str text))))

(defn loc-get-by-role
  "Locates elements by ARIA role within this locator.
   
   Params:
   `loc`  - Locator instance.
   `role` - AriaRole enum value.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc ^AriaRole role]
  (.getByRole loc role))

(defn loc-get-by-label
  "Locates elements by label within this locator.
   
   Params:
   `loc`  - Locator instance.
   `text` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc text]
  (if (instance? java.util.regex.Pattern text)
    (.getByLabel loc ^java.util.regex.Pattern text)
    (.getByLabel loc ^String (str text))))

(defn loc-get-by-test-id
  "Locates elements by test ID within this locator.
   
   Params:
   `loc`     - Locator instance.
   `test-id` - String or Pattern.
   
   Returns:
   Locator instance."
  ^Locator [^Locator loc test-id]
  (if (instance? java.util.regex.Pattern test-id)
    (.getByTestId loc ^java.util.regex.Pattern test-id)
    (.getByTestId loc ^String (str test-id))))

;; =============================================================================
;; Locator Waiting
;; =============================================================================

(defn wait-for
  "Waits for the locator to satisfy a condition.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Wait options (:state, :timeout).
   
   Returns:
   nil or anomaly map on timeout."
  ([^Locator loc]
   (safe (.waitFor loc)))
  ([^Locator loc wait-opts]
   (safe (.waitFor loc (opts/->wait-for-options wait-opts)))))

(defn evaluate-locator
  "Evaluates JavaScript on the element found by this locator.
   
   Params:
   `loc`        - Locator instance.
   `expression` - String. JavaScript expression.
   `arg`        - Optional argument.
   
   Returns:
   Result or anomaly map."
  ([^Locator loc ^String expression]
   (safe (.evaluate loc expression)))
  ([^Locator loc ^String expression arg]
   (safe (.evaluate loc expression arg))))

(defn evaluate-all
  "Evaluates JavaScript on all elements matching the locator.
   
   Params:
   `loc`        - Locator instance.
   `expression` - String. JavaScript expression.
   
   Returns:
   Result or anomaly map."
  ([^Locator loc ^String expression]
   (safe (.evaluateAll loc expression)))
  ([^Locator loc ^String expression arg]
   (safe (.evaluateAll loc expression arg))))

;; =============================================================================
;; Locator Screenshots
;; =============================================================================

(defn locator-screenshot
  "Takes a screenshot of the element.
   
   Params:
   `loc`  - Locator instance.
   `opts` - Map, optional. Screenshot options.
   
   Returns:
   byte[] or anomaly map."
  ([^Locator loc]
   (safe (.screenshot loc)))
  ([^Locator loc ss-opts]
   (safe (.screenshot loc (opts/->locator-screenshot-options ss-opts)))))

(defn highlight
  "Highlights the element for debugging.
   
   Params:
   `loc` - Locator instance."
  [^Locator loc]
  (.highlight loc))

;; =============================================================================
;; ElementHandle (legacy, prefer Locator)
;; =============================================================================

(defn element-handle
  "Returns the ElementHandle for the first matching element.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   ElementHandle or anomaly map."
  [^Locator loc]
  (safe (.elementHandle loc)))

(defn element-handles
  "Returns all ElementHandles matching the locator.
   
   Params:
   `loc` - Locator instance.
   
   Returns:
   Vector of ElementHandle."
  [^Locator loc]
  (safe (vec (.elementHandles loc))))

(defn eh-click
  "Clicks an element handle.
   
   Params:
   `eh`   - ElementHandle instance.
   `opts` - Map, optional. Click options."
  ([^ElementHandle eh]
   (safe (.click eh)))
  ([^ElementHandle eh click-opts]
   (safe (.click eh (opts/->eh-click-options click-opts)))))

(defn eh-fill
  "Fills text into an element handle.
   
   Params:
   `eh`    - ElementHandle instance.
   `value` - String."
  [^ElementHandle eh ^String value]
  (safe (.fill eh value)))

(defn eh-text-content
  "Returns text content of an element handle.
   
   Params:
   `eh` - ElementHandle instance.
   
   Returns:
   String or nil."
  [^ElementHandle eh]
  (.textContent eh))

(defn eh-inner-text
  "Returns inner text of an element handle.
   
   Params:
   `eh` - ElementHandle instance.
   
   Returns:
   String."
  [^ElementHandle eh]
  (.innerText eh))

(defn eh-inner-html
  "Returns inner HTML of an element handle.
   
   Params:
   `eh` - ElementHandle instance.
   
   Returns:
   String."
  [^ElementHandle eh]
  (.innerHTML eh))

(defn eh-get-attribute
  "Returns an attribute value of the element handle.
   
   Params:
   `eh`   - ElementHandle instance.
   `name` - String. Attribute name.
   
   Returns:
   String or nil."
  [^ElementHandle eh ^String name]
  (.getAttribute eh name))

(defn eh-is-visible?
  "Returns whether the element handle is visible."
  [^ElementHandle eh]
  (.isVisible eh))

(defn eh-is-enabled?
  "Returns whether the element handle is enabled."
  [^ElementHandle eh]
  (.isEnabled eh))

(defn eh-is-checked?
  "Returns whether the element handle is checked."
  [^ElementHandle eh]
  (.isChecked eh))

(defn eh-bounding-box
  "Returns the bounding box of the element handle."
  [^ElementHandle eh]
  (when-let [bb (.boundingBox eh)]
    {:x      (.-x bb)
     :y      (.-y bb)
     :width  (.-width bb)
     :height (.-height bb)}))

(defn eh-screenshot
  "Takes a screenshot of the element."
  ([^ElementHandle eh]
   (safe (.screenshot eh)))
  ([^ElementHandle eh screenshot-opts]
   (safe (.screenshot eh (opts/->eh-screenshot-options screenshot-opts)))))

(defn eh-dispose!
  "Disposes the element handle.
   
   Params:
   `eh` - ElementHandle instance."
  [^ElementHandle eh]
  (.dispose eh))

;; =============================================================================
;; JSHandle
;; =============================================================================

(defn js-evaluate
  "Evaluates JavaScript on a JSHandle.
   
   Params:
   `handle`     - JSHandle instance.
   `expression` - String. JavaScript expression.
   
   Returns:
   Result or anomaly map."
  ([^JSHandle handle ^String expression]
   (safe (.evaluate handle expression)))
  ([^JSHandle handle ^String expression arg]
   (safe (.evaluate handle expression arg))))

(defn js-json-value
  "Returns the JSON value of a JSHandle.
   
   Params:
   `handle` - JSHandle instance.
   
   Returns:
   Parsed JSON value."
  [^JSHandle handle]
  (.jsonValue handle))

(defn js-get-property
  "Gets a property of a JSHandle.
   
   Params:
   `handle` - JSHandle instance.
   `name`   - String. Property name.
   
   Returns:
   JSHandle for the property."
  ^JSHandle [^JSHandle handle ^String name]
  (.getProperty handle name))

(defn js-get-properties
  "Gets all properties of a JSHandle.
   
   Params:
   `handle` - JSHandle instance.
   
   Returns:
   Map of property name to JSHandle."
  [^JSHandle handle]
  (into {} (.getProperties handle)))

(defn js-as-element
  "Casts a JSHandle to ElementHandle if possible.
   
   Params:
   `handle` - JSHandle instance.
   
   Returns:
   ElementHandle or nil."
  [^JSHandle handle]
  (.asElement handle))

(defn js-dispose!
  "Disposes the JSHandle.
   
   Params:
   `handle` - JSHandle instance."
  [^JSHandle handle]
  (.dispose handle))
