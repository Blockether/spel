(ns com.blockether.spel.assertions
  "Playwright test assertions - LocatorAssertions, PageAssertions,
   APIResponseAssertions.

   Entry point is `assert-that` which returns the appropriate assertions
   object for the given Playwright type. Chain with assertion functions
   and use `not-` variants for negation.

   All assertion functions wrap calls in `safe` to return anomaly maps
   on assertion failure rather than throwing."
  (:require
   [com.blockether.spel.core :refer [safe]])
  (:import
   [com.microsoft.playwright Locator Page APIResponse]
   [com.microsoft.playwright.assertions
    PlaywrightAssertions LocatorAssertions PageAssertions
    APIResponseAssertions
    LocatorAssertions$HasTextOptions
    LocatorAssertions$ContainsTextOptions
    LocatorAssertions$HasAttributeOptions
    LocatorAssertions$HasClassOptions
    LocatorAssertions$HasCountOptions
    LocatorAssertions$HasCSSOptions
    LocatorAssertions$HasIdOptions
    LocatorAssertions$HasValueOptions
    LocatorAssertions$HasValuesOptions
    LocatorAssertions$IsAttachedOptions
    LocatorAssertions$IsCheckedOptions
    LocatorAssertions$IsDisabledOptions
    LocatorAssertions$IsEditableOptions
    LocatorAssertions$IsEnabledOptions
    LocatorAssertions$IsFocusedOptions
    LocatorAssertions$IsHiddenOptions
    LocatorAssertions$IsVisibleOptions
    LocatorAssertions$IsInViewportOptions
    LocatorAssertions$ContainsClassOptions
    PageAssertions$HasTitleOptions
    PageAssertions$HasURLOptions]))

;; =============================================================================
;; Entry Points
;; =============================================================================

(defn assert-that
  "Creates an assertion object for the given Playwright instance.

   Params:
   `target` - Locator, Page, or APIResponse instance.

   Returns:
   LocatorAssertions, PageAssertions, or APIResponseAssertions."
  [target]
  (cond
    (instance? Locator target)
    (PlaywrightAssertions/assertThat ^Locator target)

    (instance? Page target)
    (PlaywrightAssertions/assertThat ^Page target)

    (instance? APIResponse target)
    (PlaywrightAssertions/assertThat ^APIResponse target)

    :else
    (throw (IllegalArgumentException.
             (str "Expected Locator, Page, or APIResponse, got: "
               (type target))))))

(defn set-default-assertion-timeout!
  "Sets the default timeout for all assertions.

   Params:
   `timeout` - Double. Timeout in milliseconds."
  [timeout]
  (PlaywrightAssertions/setDefaultAssertionTimeout (double timeout)))

;; =============================================================================
;; Locator Assertions
;; =============================================================================

(defn loc-not
  "Returns negated LocatorAssertions (expect the opposite).

   Params:
   `la` - LocatorAssertions instance.

   Returns:
   LocatorAssertions (negated)."
  ^LocatorAssertions [^LocatorAssertions la]
  (.not la))

(defn has-text
  "Asserts the locator has the specified text.

   Params:
   `la`   - LocatorAssertions instance.
   `text` - String or Pattern.
   `opts` - Map, optional. {:timeout ms, :use-inner-text bool}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la text]
   (safe
     (if (instance? java.util.regex.Pattern text)
       (.hasText la ^java.util.regex.Pattern text)
       (.hasText la ^String (str text)))))
  ([^LocatorAssertions la text opts]
   (safe
     (let [^LocatorAssertions$HasTextOptions ho (LocatorAssertions$HasTextOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (when (contains? opts :use-inner-text)
         (.setUseInnerText ho (boolean (:use-inner-text opts))))
       (if (instance? java.util.regex.Pattern text)
         (.hasText la ^java.util.regex.Pattern text ho)
         (.hasText la ^String (str text) ho))))))

(defn contains-text
  "Asserts the locator contains the specified text.

   Params:
   `la`   - LocatorAssertions instance.
   `text` - String or Pattern.
   `opts` - Map, optional. {:timeout ms, :use-inner-text bool}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la text]
   (safe
     (if (instance? java.util.regex.Pattern text)
       (.containsText la ^java.util.regex.Pattern text)
       (.containsText la ^String (str text)))))
  ([^LocatorAssertions la text opts]
   (safe
     (let [^LocatorAssertions$ContainsTextOptions co (LocatorAssertions$ContainsTextOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout co (double v)))
       (when (contains? opts :use-inner-text)
         (.setUseInnerText co (boolean (:use-inner-text opts))))
       (if (instance? java.util.regex.Pattern text)
         (.containsText la ^java.util.regex.Pattern text co)
         (.containsText la ^String (str text) co))))))

(defn has-attribute
  "Asserts the locator has the specified attribute with value.

   Params:
   `la`    - LocatorAssertions instance.
   `name`  - String. Attribute name.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la ^String name value]
   (safe
     (if (instance? java.util.regex.Pattern value)
       (.hasAttribute la name ^java.util.regex.Pattern value)
       (.hasAttribute la name ^String (str value)))))
  ([^LocatorAssertions la ^String name value opts]
   (safe
     (let [^LocatorAssertions$HasAttributeOptions ho (LocatorAssertions$HasAttributeOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasAttribute la name ^java.util.regex.Pattern value ho)
         (.hasAttribute la name ^String (str value) ho))))))

(defn has-class
  "Asserts the locator has the specified CSS class.

   Params:
   `la`    - LocatorAssertions instance.
   `class` - String, Pattern, or vector of strings/patterns.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la class-val]
   (safe
     (cond
       (instance? java.util.regex.Pattern class-val)
       (.hasClass la ^java.util.regex.Pattern class-val)

       (sequential? class-val)
       (.hasClass la ^"[Ljava.lang.String;" (into-array String class-val))

       :else
       (.hasClass la ^String (str class-val)))))
  ([^LocatorAssertions la class-val opts]
   (safe
     (let [^LocatorAssertions$HasClassOptions ho (LocatorAssertions$HasClassOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (cond
         (instance? java.util.regex.Pattern class-val)
         (.hasClass la ^java.util.regex.Pattern class-val ho)

         (sequential? class-val)
         (.hasClass la ^"[Ljava.lang.String;" (into-array String class-val) ho)

         :else
         (.hasClass la ^String (str class-val) ho))))))

(defn contains-class
  "Asserts the locator's class attribute contains the specified class.

   Params:
   `la`    - LocatorAssertions instance.
   `class` - String or vector of strings.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la class-val]
   (safe
     (if (sequential? class-val)
       (.containsClass la ^java.util.List (vec class-val))
       (.containsClass la ^String (str class-val)))))
  ([^LocatorAssertions la class-val opts]
   (safe
     (let [^LocatorAssertions$ContainsClassOptions co (LocatorAssertions$ContainsClassOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout co (double v)))
       (if (sequential? class-val)
         (.containsClass la ^java.util.List (vec class-val) co)
         (.containsClass la ^String (str class-val) co))))))

(defn has-count
  "Asserts the locator resolves to the expected number of elements.

   Params:
   `la`    - LocatorAssertions instance.
   `count` - Long. Expected element count.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la count]
   (safe (.hasCount la (long count))))
  ([^LocatorAssertions la count opts]
   (safe
     (let [^LocatorAssertions$HasCountOptions ho (LocatorAssertions$HasCountOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (.hasCount la (long count) ho)))))

(defn has-css
  "Asserts the locator has the specified CSS property with value.

   Params:
   `la`    - LocatorAssertions instance.
   `name`  - String. CSS property name.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la ^String name value]
   (safe
     (if (instance? java.util.regex.Pattern value)
       (.hasCSS la name ^java.util.regex.Pattern value)
       (.hasCSS la name ^String (str value)))))
  ([^LocatorAssertions la ^String name value opts]
   (safe
     (let [^LocatorAssertions$HasCSSOptions ho (LocatorAssertions$HasCSSOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasCSS la name ^java.util.regex.Pattern value ho)
         (.hasCSS la name ^String (str value) ho))))))

(defn has-id
  "Asserts the locator has the specified ID.

   Params:
   `la` - LocatorAssertions instance.
   `id` - String or Pattern.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la id]
   (safe
     (if (instance? java.util.regex.Pattern id)
       (.hasId la ^java.util.regex.Pattern id)
       (.hasId la ^String (str id)))))
  ([^LocatorAssertions la id opts]
   (safe
     (let [^LocatorAssertions$HasIdOptions ho (LocatorAssertions$HasIdOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern id)
         (.hasId la ^java.util.regex.Pattern id ho)
         (.hasId la ^String (str id) ho))))))

(defn has-js-property
  "Asserts the locator has the specified JavaScript property.

   Params:
   `la`    - LocatorAssertions instance.
   `name`  - String. Property name.
   `value` - Object. Expected value.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la ^String name value]
  (safe (.hasJSProperty la name value)))

(defn has-value
  "Asserts the locator (input) has the specified value.

   Params:
   `la`    - LocatorAssertions instance.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la value]
   (safe
     (if (instance? java.util.regex.Pattern value)
       (.hasValue la ^java.util.regex.Pattern value)
       (.hasValue la ^String (str value)))))
  ([^LocatorAssertions la value opts]
   (safe
     (let [^LocatorAssertions$HasValueOptions ho (LocatorAssertions$HasValueOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasValue la ^java.util.regex.Pattern value ho)
         (.hasValue la ^String (str value) ho))))))

(defn has-values
  "Asserts the locator (multi-select) has the specified values.

   Params:
   `la`     - LocatorAssertions instance.
   `values` - Vector of strings or patterns.
   `opts`   - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la values]
   (safe (.hasValues la ^"[Ljava.lang.String;" (into-array String values))))
  ([^LocatorAssertions la values opts]
   (safe
     (let [^LocatorAssertions$HasValuesOptions ho (LocatorAssertions$HasValuesOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (.hasValues la ^"[Ljava.lang.String;" (into-array String values) ho)))))

(defn has-role
  "Asserts the locator has the specified ARIA role.

   Params:
   `la`   - LocatorAssertions instance.
   `role` - AriaRole enum value.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la ^com.microsoft.playwright.options.AriaRole role]
  (safe (.hasRole la role)))

(defn has-accessible-name
  "Asserts the locator has the specified accessible name.

   Params:
   `la`   - LocatorAssertions instance.
   `name` - String or Pattern.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la name-val]
  (safe
    (if (instance? java.util.regex.Pattern name-val)
      (.hasAccessibleName la ^java.util.regex.Pattern name-val)
      (.hasAccessibleName la ^String (str name-val)))))

(defn has-accessible-description
  "Asserts the locator has the specified accessible description.

   Params:
   `la`   - LocatorAssertions instance.
   `desc` - String or Pattern.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la desc]
  (safe
    (if (instance? java.util.regex.Pattern desc)
      (.hasAccessibleDescription la ^java.util.regex.Pattern desc)
      (.hasAccessibleDescription la ^String (str desc)))))

(defn has-accessible-error-message
  "Asserts the locator has the specified accessible error message.

   Params:
   `la`  - LocatorAssertions instance.
   `msg` - String or Pattern.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la msg]
  (safe
    (if (instance? java.util.regex.Pattern msg)
      (.hasAccessibleErrorMessage la ^java.util.regex.Pattern msg)
      (.hasAccessibleErrorMessage la ^String (str msg)))))

(defn matches-aria-snapshot
  "Asserts the locator matches the ARIA snapshot.

   Params:
   `la`       - LocatorAssertions instance.
   `snapshot` - String. ARIA snapshot to match.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la ^String snapshot]
  (safe (.matchesAriaSnapshot la snapshot)))

;; -- Locator state assertions --

(defn is-attached
  "Asserts the locator is attached to the DOM.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isAttached la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsAttachedOptions io (LocatorAssertions$IsAttachedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isAttached la io)))))

(defn is-checked
  "Asserts the locator (checkbox/radio) is checked.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isChecked la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsCheckedOptions io (LocatorAssertions$IsCheckedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isChecked la io)))))

(defn is-disabled
  "Asserts the locator is disabled.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isDisabled la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsDisabledOptions io (LocatorAssertions$IsDisabledOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isDisabled la io)))))

(defn is-editable
  "Asserts the locator is editable.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isEditable la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsEditableOptions io (LocatorAssertions$IsEditableOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isEditable la io)))))

(defn is-enabled
  "Asserts the locator is enabled.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isEnabled la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsEnabledOptions io (LocatorAssertions$IsEnabledOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isEnabled la io)))))

(defn is-focused
  "Asserts the locator is focused.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isFocused la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsFocusedOptions io (LocatorAssertions$IsFocusedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isFocused la io)))))

(defn is-hidden
  "Asserts the locator is hidden.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isHidden la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsHiddenOptions io (LocatorAssertions$IsHiddenOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isHidden la io)))))

(defn is-visible
  "Asserts the locator is visible.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isVisible la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsVisibleOptions io (LocatorAssertions$IsVisibleOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isVisible la io)))))

(defn is-empty
  "Asserts the locator (input) is empty.

   Params:
   `la` - LocatorAssertions instance.

   Returns:
   nil or anomaly map on assertion failure."
  [^LocatorAssertions la]
  (safe (.isEmpty la)))

(defn is-in-viewport
  "Asserts the locator is in the viewport.

   Params:
   `la`   - LocatorAssertions instance.
   `opts` - Map, optional. {:timeout ms, :ratio double}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^LocatorAssertions la]
   (safe (.isInViewport la)))
  ([^LocatorAssertions la opts]
   (safe
     (let [^LocatorAssertions$IsInViewportOptions io (LocatorAssertions$IsInViewportOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (when-let [v (:ratio opts)]
         (.setRatio io (double v)))
       (.isInViewport la io)))))

;; =============================================================================
;; Page Assertions
;; =============================================================================

(defn page-not
  "Returns negated PageAssertions (expect the opposite).

   Params:
   `pa` - PageAssertions instance.

   Returns:
   PageAssertions (negated)."
  ^PageAssertions [^PageAssertions pa]
  (.not pa))

(defn has-title
  "Asserts the page has the specified title.

   Params:
   `pa`    - PageAssertions instance.
   `title` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^PageAssertions pa title]
   (safe
     (if (instance? java.util.regex.Pattern title)
       (.hasTitle pa ^java.util.regex.Pattern title)
       (.hasTitle pa ^String (str title)))))
  ([^PageAssertions pa title opts]
   (safe
     (let [^PageAssertions$HasTitleOptions ho (PageAssertions$HasTitleOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern title)
         (.hasTitle pa ^java.util.regex.Pattern title ho)
         (.hasTitle pa ^String (str title) ho))))))

(defn has-url
  "Asserts the page has the specified URL.

   Params:
   `pa`  - PageAssertions instance.
   `url` - String or Pattern.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil or anomaly map on assertion failure."
  ([^PageAssertions pa url]
   (safe
     (if (instance? java.util.regex.Pattern url)
       (.hasURL pa ^java.util.regex.Pattern url)
       (.hasURL pa ^String (str url)))))
  ([^PageAssertions pa url opts]
   (safe
     (let [^PageAssertions$HasURLOptions ho (PageAssertions$HasURLOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern url)
         (.hasURL pa ^java.util.regex.Pattern url ho)
         (.hasURL pa ^String (str url) ho))))))

;; =============================================================================
;; API Response Assertions
;; =============================================================================

(defn api-not
  "Returns negated APIResponseAssertions (expect the opposite).

   Params:
   `ara` - APIResponseAssertions instance.

   Returns:
   APIResponseAssertions (negated)."
  ^APIResponseAssertions [^APIResponseAssertions ara]
  (.not ara))

(defn is-ok
  "Asserts the API response status is 2xx.

   Params:
   `ara` - APIResponseAssertions instance.

   Returns:
   nil or anomaly map on assertion failure."
  [^APIResponseAssertions ara]
  (safe (.isOK ara)))
