(ns com.blockether.spel.assertions
  "Playwright test assertions - LocatorAssertions, PageAssertions,
   APIResponseAssertions.

   Entry point is `assert-that` which returns the appropriate assertions
   object for the given Playwright type. Chain with assertion functions
   and use `not-` variants for negation.

   Every assertion takes the `Page`/`Locator`/`APIResponse` itself or the
   assertions object `assert-that` answers, and returns nil when it holds.

   A failed assertion THROWS `org.opentest4j.AssertionFailedError` — Playwright's
   own failure, and an Error, which is why `safe` never catches it. `safe` is here
   for the driver faults around the assertion (the page closed mid-assertion),
   which still come back as anomaly maps."
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

(defn- ->assertions
  "Answers the Playwright assertions object for `target`: itself when it already is
   one, `assert-that` otherwise.

   Every assertion coerces its receiver through this. A `Page` handed straight to
   `has-url` used to reach Playwright as a cast error that `safe` turned into an
   anomaly map — a refusal the caller could drop, and this repository's own smoke
   tests dropped it, asserting `#\"example\\.com\"` on an example.org page."
  [target]
  (if (or (instance? LocatorAssertions target)
        (instance? PageAssertions target)
        (instance? APIResponseAssertions target))
    target
    (assert-that target)))

(defmacro ^:private with-assertions
  "Binds `recv` to the assertions object for whatever the caller passed, then runs
   `body` under `safe`. Carry the receiver's type hint on `recv` to keep the
   interop calls reflection-free."
  [recv & body]
  `(let [~recv (->assertions ~recv)]
     (safe ~@body)))

(defn loc-not
  "Returns negated LocatorAssertions (expect the opposite).

   Params:
   `la` - Locator, or the LocatorAssertions assert-that answers.

   Returns:
   LocatorAssertions (negated)."
  ^LocatorAssertions [la]
  (.not ^LocatorAssertions (->assertions la)))

(defn has-text
  "Asserts the locator has the specified text.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `text` - String or Pattern.
   `opts` - Map, optional. {:timeout ms, :use-inner-text bool}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la text]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern text)
       (.hasText la ^java.util.regex.Pattern text)
       (.hasText la ^String (str text)))))
  ([la text opts]
   (with-assertions ^LocatorAssertions la
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
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `text` - String or Pattern.
   `opts` - Map, optional. {:timeout ms, :use-inner-text bool}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la text]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern text)
       (.containsText la ^java.util.regex.Pattern text)
       (.containsText la ^String (str text)))))
  ([la text opts]
   (with-assertions ^LocatorAssertions la
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
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `name`  - String. Attribute name.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la ^String name value]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern value)
       (.hasAttribute la name ^java.util.regex.Pattern value)
       (.hasAttribute la name ^String (str value)))))
  ([la ^String name value opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasAttributeOptions ho (LocatorAssertions$HasAttributeOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasAttribute la name ^java.util.regex.Pattern value ho)
         (.hasAttribute la name ^String (str value) ho))))))

(defn has-class
  "Asserts the locator has the specified CSS class.

   Params:
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `class` - String, Pattern, or vector of strings/patterns.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la class-val]
   (with-assertions ^LocatorAssertions la
     (cond
       (instance? java.util.regex.Pattern class-val)
       (.hasClass la ^java.util.regex.Pattern class-val)

       (sequential? class-val)
       (.hasClass la ^"[Ljava.lang.String;" (into-array String class-val))

       :else
       (.hasClass la ^String (str class-val)))))
  ([la class-val opts]
   (with-assertions ^LocatorAssertions la
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
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `class` - String or vector of strings.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la class-val]
   (with-assertions ^LocatorAssertions la
     (if (sequential? class-val)
       (.containsClass la ^java.util.List (vec class-val))
       (.containsClass la ^String (str class-val)))))
  ([la class-val opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$ContainsClassOptions co (LocatorAssertions$ContainsClassOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout co (double v)))
       (if (sequential? class-val)
         (.containsClass la ^java.util.List (vec class-val) co)
         (.containsClass la ^String (str class-val) co))))))

(defn has-count
  "Asserts the locator resolves to the expected number of elements.

   Params:
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `count` - Long. Expected element count.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la count]
   (with-assertions ^LocatorAssertions la (.hasCount la (long count))))
  ([la count opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasCountOptions ho (LocatorAssertions$HasCountOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (.hasCount la (long count) ho)))))

(defn has-css
  "Asserts the locator has the specified CSS property with value.

   Params:
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `name`  - String. CSS property name.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la ^String name value]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern value)
       (.hasCSS la name ^java.util.regex.Pattern value)
       (.hasCSS la name ^String (str value)))))
  ([la ^String name value opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasCSSOptions ho (LocatorAssertions$HasCSSOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasCSS la name ^java.util.regex.Pattern value ho)
         (.hasCSS la name ^String (str value) ho))))))

(defn has-id
  "Asserts the locator has the specified ID.

   Params:
   `la` - Locator, or the LocatorAssertions assert-that answers.
   `id` - String or Pattern.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la id]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern id)
       (.hasId la ^java.util.regex.Pattern id)
       (.hasId la ^String (str id)))))
  ([la id opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasIdOptions ho (LocatorAssertions$HasIdOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern id)
         (.hasId la ^java.util.regex.Pattern id ho)
         (.hasId la ^String (str id) ho))))))

(defn has-js-property
  "Asserts the locator has the specified JavaScript property.

   Params:
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `name`  - String. Property name.
   `value` - Object. Expected value.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la ^String name value]
  (with-assertions ^LocatorAssertions la (.hasJSProperty la name value)))

(defn has-value
  "Asserts the locator (input) has the specified value.

   Params:
   `la`    - Locator, or the LocatorAssertions assert-that answers.
   `value` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la value]
   (with-assertions ^LocatorAssertions la
     (if (instance? java.util.regex.Pattern value)
       (.hasValue la ^java.util.regex.Pattern value)
       (.hasValue la ^String (str value)))))
  ([la value opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasValueOptions ho (LocatorAssertions$HasValueOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern value)
         (.hasValue la ^java.util.regex.Pattern value ho)
         (.hasValue la ^String (str value) ho))))))

(defn has-values
  "Asserts the locator (multi-select) has the specified values.

   Params:
   `la`     - Locator, or the LocatorAssertions assert-that answers.
   `values` - Vector of strings or patterns.
   `opts`   - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la values]
   (with-assertions ^LocatorAssertions la (.hasValues la ^"[Ljava.lang.String;" (into-array String values))))
  ([la values opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$HasValuesOptions ho (LocatorAssertions$HasValuesOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (.hasValues la ^"[Ljava.lang.String;" (into-array String values) ho)))))

(defn has-role
  "Asserts the locator has the specified ARIA role.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `role` - AriaRole enum value.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la ^com.microsoft.playwright.options.AriaRole role]
  (with-assertions ^LocatorAssertions la (.hasRole la role)))

(defn has-accessible-name
  "Asserts the locator has the specified accessible name.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `name` - String or Pattern.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la name-val]
  (with-assertions ^LocatorAssertions la
    (if (instance? java.util.regex.Pattern name-val)
      (.hasAccessibleName la ^java.util.regex.Pattern name-val)
      (.hasAccessibleName la ^String (str name-val)))))

(defn has-accessible-description
  "Asserts the locator has the specified accessible description.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `desc` - String or Pattern.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la desc]
  (with-assertions ^LocatorAssertions la
    (if (instance? java.util.regex.Pattern desc)
      (.hasAccessibleDescription la ^java.util.regex.Pattern desc)
      (.hasAccessibleDescription la ^String (str desc)))))

(defn has-accessible-error-message
  "Asserts the locator has the specified accessible error message.

   Params:
   `la`  - Locator, or the LocatorAssertions assert-that answers.
   `msg` - String or Pattern.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la msg]
  (with-assertions ^LocatorAssertions la
    (if (instance? java.util.regex.Pattern msg)
      (.hasAccessibleErrorMessage la ^java.util.regex.Pattern msg)
      (.hasAccessibleErrorMessage la ^String (str msg)))))

(defn matches-aria-snapshot
  "Asserts the locator matches the ARIA snapshot.

   Params:
   `la`       - Locator, or the LocatorAssertions assert-that answers.
   `snapshot` - String. ARIA snapshot to match.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la ^String snapshot]
  (with-assertions ^LocatorAssertions la (.matchesAriaSnapshot la snapshot)))

;; -- Locator state assertions --

(defn is-attached
  "Asserts the locator is attached to the DOM.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isAttached la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsAttachedOptions io (LocatorAssertions$IsAttachedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isAttached la io)))))

(defn is-checked
  "Asserts the locator (checkbox/radio) is checked.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isChecked la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsCheckedOptions io (LocatorAssertions$IsCheckedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isChecked la io)))))

(defn is-disabled
  "Asserts the locator is disabled.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isDisabled la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsDisabledOptions io (LocatorAssertions$IsDisabledOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isDisabled la io)))))

(defn is-editable
  "Asserts the locator is editable.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isEditable la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsEditableOptions io (LocatorAssertions$IsEditableOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isEditable la io)))))

(defn is-enabled
  "Asserts the locator is enabled.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isEnabled la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsEnabledOptions io (LocatorAssertions$IsEnabledOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isEnabled la io)))))

(defn is-focused
  "Asserts the locator is focused.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isFocused la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsFocusedOptions io (LocatorAssertions$IsFocusedOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isFocused la io)))))

(defn is-hidden
  "Asserts the locator is hidden.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isHidden la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsHiddenOptions io (LocatorAssertions$IsHiddenOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isHidden la io)))))

(defn is-visible
  "Asserts the locator is visible.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isVisible la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
     (let [^LocatorAssertions$IsVisibleOptions io (LocatorAssertions$IsVisibleOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout io (double v)))
       (.isVisible la io)))))

(defn is-empty
  "Asserts the locator (input) is empty.

   Params:
   `la` - Locator, or the LocatorAssertions assert-that answers.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [la]
  (with-assertions ^LocatorAssertions la (.isEmpty la)))

(defn is-in-viewport
  "Asserts the locator is in the viewport.

   Params:
   `la`   - Locator, or the LocatorAssertions assert-that answers.
   `opts` - Map, optional. {:timeout ms, :ratio double}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([la]
   (with-assertions ^LocatorAssertions la (.isInViewport la)))
  ([la opts]
   (with-assertions ^LocatorAssertions la
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
   `pa` - Page, or the PageAssertions assert-that answers.

   Returns:
   PageAssertions (negated)."
  ^PageAssertions [pa]
  (.not ^PageAssertions (->assertions pa)))

(defn has-title
  "Asserts the page has the specified title.

   Params:
   `pa`    - Page, or the PageAssertions assert-that answers.
   `title` - String or Pattern.
   `opts`  - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([pa title]
   (with-assertions ^PageAssertions pa
     (if (instance? java.util.regex.Pattern title)
       (.hasTitle pa ^java.util.regex.Pattern title)
       (.hasTitle pa ^String (str title)))))
  ([pa title opts]
   (with-assertions ^PageAssertions pa
     (let [^PageAssertions$HasTitleOptions ho (PageAssertions$HasTitleOptions.)]
       (when-let [v (:timeout opts)]
         (.setTimeout ho (double v)))
       (if (instance? java.util.regex.Pattern title)
         (.hasTitle pa ^java.util.regex.Pattern title ho)
         (.hasTitle pa ^String (str title) ho))))))

(def ^:private url-glob-re
  "The `**` of the glob dialect `wait --url` and `network route` accept. A real URL
   never contains it, so it can only be a caller reaching for the wrong dialect."
  #"\*\*")

(defn- refuse-url-glob!
  "Throws when an expected URL is written as a glob.

   `wait --url \"**/dashboard\"` matches globs, so the same pattern looks supported
   here. Playwright compares a String URL for equality instead: it matched the glob
   literally, spent the whole assertion timeout, and then reported
   `Expected: **/dashboard` as an unequal string — an answer that never mentions
   the dialect."
  [url]
  (when (and (string? url) (re-find url-glob-re url))
    ;; One line under the renderer's 200-char ceiling: the source frame under the
    ;; message already shows which glob the caller passed.
    (throw (IllegalArgumentException.
             (str "A URL assertion matches the whole URL exactly, never as a glob. "
               "`**` is the `wait --url` dialect; assert part of a URL with a regex: "
               "(spel/assert-url #\"/page\").")))))

(defn has-url
  "Asserts the page has the specified URL.

   Params:
   `pa`  - Page, or the PageAssertions assert-that answers.
   `url` - String or Pattern.
   `opts` - Map, optional. {:timeout ms}.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  ([pa url]
   (refuse-url-glob! url)
   (with-assertions ^PageAssertions pa
     (if (instance? java.util.regex.Pattern url)
       (.hasURL pa ^java.util.regex.Pattern url)
       (.hasURL pa ^String (str url)))))
  ([pa url opts]
   (refuse-url-glob! url)
   (with-assertions ^PageAssertions pa
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
   `ara` - APIResponse, or the APIResponseAssertions assert-that answers.

   Returns:
   APIResponseAssertions (negated)."
  ^APIResponseAssertions [ara]
  (.not ^APIResponseAssertions (->assertions ara)))

(defn is-ok
  "Asserts the API response status is 2xx.

   Params:
   `ara` - APIResponse, or the APIResponseAssertions assert-that answers.

   Returns:
   nil when the assertion holds. Throws AssertionFailedError when it does not;
   only a driver fault (the page closed mid-assertion) answers an anomaly map."
  [ara]
  (with-assertions ^APIResponseAssertions ara (.isOK ara)))
