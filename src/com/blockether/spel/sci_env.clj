(ns com.blockether.spel.sci-env
  "SCI (Small Clojure Interpreter) environment for native-image REPL.

   Registers all spel functions as SCI namespaces so they
   can be evaluated in a native-image compiled REPL without JVM startup.

   The SCI context wraps a stateful Playwright session with managed
   atoms for the Playwright, Browser, BrowserContext, and Page instances.

    Namespaces available in --eval mode:
      spel/     - Simplified API (implicit page/context from atoms)
     snapshot/ - Accessibility snapshot capture
     annotate/ - Screenshot annotation
     input/    - Keyboard, Mouse, Touchscreen (raw pass-throughs)
     frame/    - Frame and FrameLocator operations (raw pass-throughs)
     net/      - Network request/response/route (raw pass-throughs)
     loc/      - Locator operations (raw pass-throughs, explicit Locator arg)
     assert/   - Assertion functions (raw pass-throughs, explicit assertion obj)
     core/     - Lifecycle stubs + utility pass-throughs

   Usage:
     (def ctx (create-sci-ctx))
      (eval-string ctx \"(spel/start!)\")
      (eval-string ctx \"(spel/goto \\\"https://example.com\\\")\")
      (eval-string ctx \"(spel/snapshot)\")
      (eval-string ctx \"(spel/stop!)\")"
  (:require
   [sci.core :as sci]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.annotate :as annotate]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.frame :as frame]
   [com.blockether.spel.input :as input]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as roles]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.util :as util])
  (:import
   [com.microsoft.playwright
    APIResponse Browser BrowserContext BrowserType CDPSession ConsoleMessage
    Dialog Download ElementHandle Frame FrameLocator JSHandle
    Keyboard Locator Mouse Page Playwright Request Response
    Route Touchscreen Tracing WebSocket WebSocketFrame WebSocketRoute]
   [com.microsoft.playwright.assertions
    PlaywrightAssertions LocatorAssertions PageAssertions APIResponseAssertions]
   [com.microsoft.playwright.options
    AriaRole ColorScheme ForcedColors HarContentPolicy HarMode HarNotFound
    LoadState Media MouseButton ReducedMotion RouteFromHarUpdateContentPolicy
    SameSiteAttribute ScreenshotType ServiceWorkerPolicy
    WaitForSelectorState WaitUntilState]))

;; =============================================================================
;; Session State (shared with SCI)
;; =============================================================================

(defonce !pw      (atom nil))
(defonce !browser (atom nil))
(defonce !context (atom nil))
(defonce !page    (atom nil))

;; When true, stop! is a no-op — the daemon owns the browser lifecycle.
(defonce !daemon-mode? (atom false))

;; Default action timeout for Playwright operations (ms).
;; Set via --timeout flag in --eval mode. nil = Playwright default (30s).
(defonce !default-timeout (atom nil))

(defn set-default-timeout!
  "Sets the default Playwright action timeout (ms) for new pages.
   Called from --eval mode when --timeout flag is provided."
  [ms]
  (reset! !default-timeout ms))

;; When true, anomaly results throw instead of being returned.
;; Enables short-circuit error propagation in --eval mode.
(defonce !throw-on-error (atom false))

(defn set-throw-on-error!
  "When true, Playwright operations throw on error instead of returning anomaly maps.
   Called from --eval mode so errors short-circuit (do ...) forms."
  [v]
  (reset! !throw-on-error v))

(defn- throw-if-anomaly
  "If result is an anomaly and !throw-on-error is true, throws it as an exception.
   Otherwise returns result unchanged."
  [result]
  (if (and @!throw-on-error (anomaly/anomaly? result))
    (let [msg (::anomaly/message result)
          ex  (:playwright/exception result)]
      (throw (if ex ex (ex-info (or msg "Playwright operation failed") result))))
    result))

(defn- require-page! []
  (when-not @!page
    (throw (ex-info "No page. Call (spel/start!) first." {})))
  @!page)

(defn- require-context! []
  (when-not @!context
    (throw (ex-info "No context. Call (spel/start!) first." {})))
  @!context)

(defn- require-browser! []
  (when-not @!browser
    (throw (ex-info "No browser. Call (spel/start!) first." {})))
  @!browser)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ->locator-assertions
  "Coerces input to LocatorAssertions. Accepts:
   - LocatorAssertions (pass-through)
   - Locator (wraps with assert-that)
   - String/other (resolves via page/locator then assert-that)"
  [sel-or-la]
  (cond
    (instance? LocatorAssertions sel-or-la) sel-or-la
    (instance? Locator sel-or-la) (assert/assert-that sel-or-la)
    :else (assert/assert-that (page/locator (require-page!) (str sel-or-la)))))

;; =============================================================================
;; Lifecycle Functions (exposed to SCI)
;; =============================================================================

(defn sci-start!
  ([] (sci-start! {}))
  ([opts]
   ;; In daemon mode, the browser is already running — start! is a no-op.
   ;; This lets old scripts that call (spel/start!) work unchanged.
   (if @!page
     :started
     (do
       (when @!pw
         (throw (ex-info "Session already running. Call (spel/stop!) first." {})))
       (let [pw-inst      (core/create)
             launch-opts  (cond-> {:headless (get opts :headless true)}
                            (:slow-mo opts) (assoc :slow-mo (:slow-mo opts)))
             browser-type (get opts :browser :chromium)
             browser-inst (case browser-type
                            :chromium (core/launch-chromium pw-inst launch-opts)
                            :firefox  (core/launch-firefox pw-inst launch-opts)
                            :webkit   (core/launch-webkit pw-inst launch-opts))
             ctx-opts     (cond-> {}
                            (:viewport opts)    (assoc :viewport (:viewport opts))
                            (:base-url opts)    (assoc :base-url (:base-url opts))
                            (:user-agent opts)  (assoc :user-agent (:user-agent opts))
                            (:locale opts)      (assoc :locale (:locale opts))
                            (:timezone-id opts) (assoc :timezone-id (:timezone-id opts)))
             ctx          (core/new-context browser-inst (when (seq ctx-opts) ctx-opts))
             pg-inst      (core/new-page-from-context ctx)]
         ;; Set default action timeout: explicit opts > --timeout flag > Playwright default
         (when-let [timeout (or (:timeout opts) @!default-timeout)]
           (page/set-default-timeout! pg-inst timeout))
         (reset! !pw pw-inst)
         (reset! !browser browser-inst)
         (reset! !context ctx)
         (reset! !page pg-inst)
         :started)))))

(defn sci-stop!
  "Stops the Playwright session, closing browser and cleaning up resources."
  []
  ;; In daemon mode, the daemon owns the browser — just nil the SCI atoms.
  (if @!daemon-mode?
    (do (reset! !page nil) (reset! !context nil)
        (reset! !browser nil) (reset! !pw nil)
        :stopped)
    (do
      ;; Close top-down: browser cleans up all contexts/pages, playwright shuts down node.
      ;; No need to individually close page/context — they're owned by the browser.
      (when-let [b @!browser]
        (try (core/close-browser! b)
             (catch Exception e
               (binding [*out* *err*]
                 (println (str "spel: warn: close-browser failed: " (.getMessage e)))))))
      (when-let [p @!pw]
        (try (core/close! p)
             (catch Exception e
               (binding [*out* *err*]
                 (println (str "spel: warn: close-playwright failed: " (.getMessage e)))))))
      (reset! !page nil) (reset! !context nil)
      (reset! !browser nil) (reset! !pw nil)
      :stopped)))

(defn sci-restart!
  "Stops the current session and starts a new one with the given options."
  ([] (sci-restart! {}))
  ([opts] (when @!pw (sci-stop!)) (sci-start! opts)))

;; Tab management
(defn sci-new-tab!
  "Opens a new tab in the current context and switches to it."
  []
  (let [new-pg (core/new-page-from-context (require-context!))]
    (reset! !page new-pg) new-pg))

(defn sci-switch-tab!
  "Switches to the tab at the given index."
  [idx]
  (let [pages (core/context-pages (require-context!))
        pg-inst (nth pages idx)]
    (reset! !page pg-inst) pg-inst))

(defn sci-tabs
  "Returns a list of all open tabs with their index, url, title, and active status."
  []
  (let [pages (core/context-pages (require-context!))
        active @!page]
    (mapv (fn [idx pg-inst]
            {:index idx :url (page/url pg-inst)
             :title (page/title pg-inst) :active? (= pg-inst active)})
      (range) pages)))

;; =============================================================================
;; Navigation
;; =============================================================================

(defn sci-goto
  ([url] (throw-if-anomaly (page/navigate (require-page!) url)))
  ([url opts] (throw-if-anomaly (page/navigate (require-page!) url opts))))
(defn sci-back      [] (throw-if-anomaly (page/go-back (require-page!))))
(defn sci-forward   [] (throw-if-anomaly (page/go-forward (require-page!))))
(defn sci-reload!   [] (throw-if-anomaly (page/reload (require-page!))))
(defn sci-url       [] (page/url (require-page!)))
(defn sci-title     [] (page/title (require-page!)))
(defn sci-html      [] (page/content (require-page!)))

;; =============================================================================
;; Locators
;; =============================================================================

(defn sci-$            [sel-or-loc]
  (if (instance? Locator sel-or-loc)
    sel-or-loc
    (page/locator (require-page!) (str sel-or-loc))))
(defn sci-$$           [sel]  (locator/all (sci-$ sel)))
(defn sci-$text        [text] (page/get-by-text (require-page!) text))
(defn sci-$role
  ([role]      (page/get-by-role (require-page!) role))
  ([role opts] (page/get-by-role (require-page!) role opts)))
(defn sci-$label       [text] (page/get-by-label (require-page!) text))
(defn sci-$placeholder [text] (page/get-by-placeholder (require-page!) text))
(defn sci-$test-id     [id]   (page/get-by-test-id (require-page!) id))
(defn sci-$alt-text    [text] (page/get-by-alt-text (require-page!) text))
(defn sci-$title-attr  [text] (page/get-by-title (require-page!) text))

;; =============================================================================
;; Locator Actions
;; =============================================================================

(defn sci-click
  ([sel]      (throw-if-anomaly (locator/click (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/click (sci-$ sel) opts))))
(defn sci-dblclick
  ([sel]      (throw-if-anomaly (locator/dblclick (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/dblclick (sci-$ sel) opts))))
(defn sci-fill
  ([sel value]      (throw-if-anomaly (locator/fill (sci-$ sel) value)))
  ([sel value opts] (throw-if-anomaly (locator/fill (sci-$ sel) value opts))))
(defn sci-type-text
  ([sel text]      (throw-if-anomaly (locator/type-text (sci-$ sel) text)))
  ([sel text opts] (throw-if-anomaly (locator/type-text (sci-$ sel) text opts))))
(defn sci-press
  ([sel key]      (throw-if-anomaly (locator/press (sci-$ sel) key)))
  ([sel key opts] (throw-if-anomaly (locator/press (sci-$ sel) key opts))))
(defn sci-clear   [sel] (throw-if-anomaly (locator/clear (sci-$ sel))))
(defn sci-check
  ([sel]      (throw-if-anomaly (locator/check (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/check (sci-$ sel) opts))))
(defn sci-uncheck
  ([sel]      (throw-if-anomaly (locator/uncheck (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/uncheck (sci-$ sel) opts))))
(defn sci-hover
  ([sel]      (throw-if-anomaly (locator/hover (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/hover (sci-$ sel) opts))))
(defn sci-focus     [sel] (throw-if-anomaly (locator/focus (sci-$ sel))))
(defn sci-select    [sel values] (throw-if-anomaly (locator/select-option (sci-$ sel) values)))
(defn sci-blur      [sel] (throw-if-anomaly (locator/blur (sci-$ sel))))
(defn sci-tap       [sel] (throw-if-anomaly (locator/tap-element (sci-$ sel))))
(defn sci-set-input-files! [sel files] (throw-if-anomaly (locator/set-input-files! (sci-$ sel) files)))
(defn sci-scroll-into-view [sel] (throw-if-anomaly (locator/scroll-into-view (sci-$ sel))))
(defn sci-dispatch-event   [sel type] (throw-if-anomaly (locator/dispatch-event (sci-$ sel) type)))
(defn sci-drag-to          [sel target-sel] (throw-if-anomaly (locator/drag-to (sci-$ sel) (sci-$ target-sel))))
(defn sci-highlight        [sel] (locator/highlight (sci-$ sel)))
(defn sci-locator-screenshot
  ([sel]      (throw-if-anomaly (locator/locator-screenshot (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/locator-screenshot (sci-$ sel) opts))))

;; =============================================================================
;; Locator Content & State
;; =============================================================================

(defn sci-text       [sel] (throw-if-anomaly (locator/text-content (sci-$ sel))))
(defn sci-inner-text [sel] (throw-if-anomaly (locator/inner-text (sci-$ sel))))
(defn sci-inner-html [sel] (throw-if-anomaly (locator/inner-html (sci-$ sel))))
(defn sci-attr       [sel name] (throw-if-anomaly (locator/get-attribute (sci-$ sel) name)))
(defn sci-value      [sel] (throw-if-anomaly (locator/input-value (sci-$ sel))))
(defn sci-count-of   [sel] (throw-if-anomaly (locator/count-elements (sci-$ sel))))
(defn sci-visible?   [sel] (locator/is-visible? (sci-$ sel)))
(defn sci-hidden?    [sel] (locator/is-hidden? (sci-$ sel)))
(defn sci-enabled?   [sel] (locator/is-enabled? (sci-$ sel)))
(defn sci-disabled?  [sel] (locator/is-disabled? (sci-$ sel)))
(defn sci-editable?  [sel] (locator/is-editable? (sci-$ sel)))
(defn sci-checked?   [sel] (locator/is-checked? (sci-$ sel)))
(defn sci-bbox       [sel] (locator/bounding-box (sci-$ sel)))
(defn sci-all-text-contents [sel] (locator/all-text-contents (sci-$ sel)))
(defn sci-all-inner-texts   [sel] (locator/all-inner-texts (sci-$ sel)))

;; =============================================================================
;; Locator Filtering
;; =============================================================================

(defn sci-loc-filter       [sel opts] (locator/loc-filter (sci-$ sel) opts))
(defn sci-first            [sel] (locator/first-element (sci-$ sel)))
(defn sci-last             [sel] (locator/last-element (sci-$ sel)))
(defn sci-nth              [sel n] (locator/nth-element (sci-$ sel) n))
(defn sci-loc-locator      [sel sub-sel] (locator/loc-locator (sci-$ sel) sub-sel))
(defn sci-loc-get-by-text    [sel text] (locator/loc-get-by-text (sci-$ sel) text))
(defn sci-loc-get-by-role    [sel role] (locator/loc-get-by-role (sci-$ sel) role))
(defn sci-loc-get-by-label   [sel text] (locator/loc-get-by-label (sci-$ sel) text))
(defn sci-loc-get-by-test-id [sel id] (locator/loc-get-by-test-id (sci-$ sel) id))

;; =============================================================================
;; Locator Waiting & Evaluation
;; =============================================================================

(defn sci-loc-wait-for
  ([sel]      (throw-if-anomaly (locator/wait-for (sci-$ sel))))
  ([sel opts] (throw-if-anomaly (locator/wait-for (sci-$ sel) opts))))
(defn sci-evaluate-locator
  ([sel expr]     (throw-if-anomaly (locator/evaluate-locator (sci-$ sel) expr)))
  ([sel expr arg] (throw-if-anomaly (locator/evaluate-locator (sci-$ sel) expr arg))))
(defn sci-evaluate-all-locs
  ([sel expr]     (throw-if-anomaly (locator/evaluate-all (sci-$ sel) expr)))
  ([sel expr arg] (throw-if-anomaly (locator/evaluate-all (sci-$ sel) expr arg))))

;; =============================================================================
;; JavaScript
;; =============================================================================

(defn sci-eval-js
  ([expr]     (throw-if-anomaly (page/evaluate (require-page!) expr)))
  ([expr arg] (throw-if-anomaly (page/evaluate (require-page!) expr arg))))
(defn sci-evaluate-handle
  ([expr]     (throw-if-anomaly (page/evaluate-handle (require-page!) expr)))
  ([expr arg] (throw-if-anomaly (page/evaluate-handle (require-page!) expr arg))))

;; =============================================================================
;; Screenshots & PDF
;; =============================================================================

(defn sci-screenshot
  ([] (throw-if-anomaly (page/screenshot (require-page!))))
  ([path-or-opts]
   (throw-if-anomaly
     (if (string? path-or-opts)
       (page/screenshot (require-page!) {:path path-or-opts})
       (page/screenshot (require-page!) path-or-opts)))))
(defn sci-pdf
  ([] (throw-if-anomaly (page/pdf (require-page!))))
  ([path-or-opts]
   (throw-if-anomaly
     (if (string? path-or-opts)
       (page/pdf (require-page!) {:path path-or-opts})
       (page/pdf (require-page!) path-or-opts)))))

;; =============================================================================
;; Waiting
;; =============================================================================

(defn sci-wait-for
  ([sel]      (throw-if-anomaly (page/wait-for-selector (require-page!) sel)))
  ([sel opts] (throw-if-anomaly (page/wait-for-selector (require-page!) sel opts))))
(defn sci-wait-for-load
  ([]      (throw-if-anomaly (page/wait-for-load-state (require-page!))))
  ([state] (throw-if-anomaly (page/wait-for-load-state (require-page!) state))))
(defn sci-sleep [ms] (page/wait-for-timeout (require-page!) ms))
(defn sci-wait-for-url [url] (throw-if-anomaly (page/wait-for-url (require-page!) url)))
(defn sci-wait-for-function [expr] (throw-if-anomaly (page/wait-for-function (require-page!) expr)))
(defn sci-wait-for-popup
  ([action]      (page/wait-for-popup (require-page!) action))
  ([action opts] (page/wait-for-popup (require-page!) action opts)))
(defn sci-wait-for-download
  ([action]      (page/wait-for-download (require-page!) action))
  ([action opts] (page/wait-for-download (require-page!) action opts)))
(defn sci-wait-for-file-chooser
  ([action]      (page/wait-for-file-chooser (require-page!) action))
  ([action opts] (page/wait-for-file-chooser (require-page!) action opts)))

;; =============================================================================
;; Assertions (FIXED: all use assert/assert-that for proper type coercion)
;; =============================================================================

;; Entry points
(defn sci-assert-that [target] (assert/assert-that target))
(defn sci-assert-not  [sel] (assert/loc-not (->locator-assertions sel)))
(defn sci-assert-page-not [] (assert/page-not (assert/assert-that (require-page!))))
(defn sci-set-assertion-timeout! [ms] (assert/set-default-assertion-timeout! ms))

;; Locator text assertions
(defn sci-assert-text
  ([sel expected]      (throw-if-anomaly (assert/has-text (->locator-assertions sel) expected)))
  ([sel expected opts] (throw-if-anomaly (assert/has-text (->locator-assertions sel) expected opts))))
(defn sci-assert-contains-text
  ([sel expected]      (throw-if-anomaly (assert/contains-text (->locator-assertions sel) expected)))
  ([sel expected opts] (throw-if-anomaly (assert/contains-text (->locator-assertions sel) expected opts))))

;; Locator attribute/class/css assertions
(defn sci-assert-attr
  ([sel attr-name value]      (throw-if-anomaly (assert/has-attribute (->locator-assertions sel) attr-name value)))
  ([sel attr-name value opts] (throw-if-anomaly (assert/has-attribute (->locator-assertions sel) attr-name value opts))))
(defn sci-assert-class
  ([sel class-val]      (throw-if-anomaly (assert/has-class (->locator-assertions sel) class-val)))
  ([sel class-val opts] (throw-if-anomaly (assert/has-class (->locator-assertions sel) class-val opts))))
(defn sci-assert-contains-class
  ([sel class-val]      (throw-if-anomaly (assert/contains-class (->locator-assertions sel) class-val)))
  ([sel class-val opts] (throw-if-anomaly (assert/contains-class (->locator-assertions sel) class-val opts))))
(defn sci-assert-css
  ([sel css-name value]      (throw-if-anomaly (assert/has-css (->locator-assertions sel) css-name value)))
  ([sel css-name value opts] (throw-if-anomaly (assert/has-css (->locator-assertions sel) css-name value opts))))
(defn sci-assert-id
  ([sel id]      (throw-if-anomaly (assert/has-id (->locator-assertions sel) id)))
  ([sel id opts] (throw-if-anomaly (assert/has-id (->locator-assertions sel) id opts))))
(defn sci-assert-js-property [sel prop-name value]
  (throw-if-anomaly (assert/has-js-property (->locator-assertions sel) prop-name value)))

;; Locator value assertions
(defn sci-assert-value
  ([sel value]      (throw-if-anomaly (assert/has-value (->locator-assertions sel) value)))
  ([sel value opts] (throw-if-anomaly (assert/has-value (->locator-assertions sel) value opts))))
(defn sci-assert-values
  ([sel values]      (throw-if-anomaly (assert/has-values (->locator-assertions sel) values)))
  ([sel values opts] (throw-if-anomaly (assert/has-values (->locator-assertions sel) values opts))))

;; Locator count assertion
(defn sci-assert-count
  ([sel n]      (throw-if-anomaly (assert/has-count (->locator-assertions sel) n)))
  ([sel n opts] (throw-if-anomaly (assert/has-count (->locator-assertions sel) n opts))))

;; Locator ARIA assertions
(defn sci-assert-role [sel role]
  (throw-if-anomaly (assert/has-role (->locator-assertions sel) role)))
(defn sci-assert-accessible-name [sel name-val]
  (throw-if-anomaly (assert/has-accessible-name (->locator-assertions sel) name-val)))
(defn sci-assert-accessible-description [sel desc]
  (throw-if-anomaly (assert/has-accessible-description (->locator-assertions sel) desc)))
(defn sci-assert-accessible-error-message [sel msg]
  (throw-if-anomaly (assert/has-accessible-error-message (->locator-assertions sel) msg)))
(defn sci-assert-matches-aria-snapshot [sel snapshot-str]
  (throw-if-anomaly (assert/matches-aria-snapshot (->locator-assertions sel) snapshot-str)))

;; Locator state assertions
(defn sci-assert-attached
  ([sel]      (throw-if-anomaly (assert/is-attached (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-attached (->locator-assertions sel) opts))))
(defn sci-assert-checked
  ([sel]      (throw-if-anomaly (assert/is-checked (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-checked (->locator-assertions sel) opts))))
(defn sci-assert-disabled
  ([sel]      (throw-if-anomaly (assert/is-disabled (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-disabled (->locator-assertions sel) opts))))
(defn sci-assert-editable
  ([sel]      (throw-if-anomaly (assert/is-editable (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-editable (->locator-assertions sel) opts))))
(defn sci-assert-enabled
  ([sel]      (throw-if-anomaly (assert/is-enabled (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-enabled (->locator-assertions sel) opts))))
(defn sci-assert-focused
  ([sel]      (throw-if-anomaly (assert/is-focused (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-focused (->locator-assertions sel) opts))))
(defn sci-assert-visible
  ([sel]      (throw-if-anomaly (assert/is-visible (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-visible (->locator-assertions sel) opts))))
(defn sci-assert-hidden
  ([sel]      (throw-if-anomaly (assert/is-hidden (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-hidden (->locator-assertions sel) opts))))
(defn sci-assert-empty [sel]
  (throw-if-anomaly (assert/is-empty (->locator-assertions sel))))
(defn sci-assert-in-viewport
  ([sel]      (throw-if-anomaly (assert/is-in-viewport (->locator-assertions sel))))
  ([sel opts] (throw-if-anomaly (assert/is-in-viewport (->locator-assertions sel) opts))))

;; Page assertions
(defn sci-assert-title
  ([expected]      (throw-if-anomaly (assert/has-title (assert/assert-that (require-page!)) expected)))
  ([expected opts] (throw-if-anomaly (assert/has-title (assert/assert-that (require-page!)) expected opts))))
(defn sci-assert-url
  ([expected]      (throw-if-anomaly (assert/has-url (assert/assert-that (require-page!)) expected)))
  ([expected opts] (throw-if-anomaly (assert/has-url (assert/assert-that (require-page!)) expected opts))))

;; =============================================================================
;; Page Functions
;; =============================================================================

(defn sci-set-content!
  ([html]      (throw-if-anomaly (page/set-content! (require-page!) html)))
  ([html opts] (throw-if-anomaly (page/set-content! (require-page!) html opts))))
(defn sci-set-viewport-size! [width height]
  (page/set-viewport-size! (require-page!) width height))
(defn sci-set-page-default-timeout! [ms]
  (page/set-default-timeout! (require-page!) ms))
(defn sci-set-default-navigation-timeout! [ms]
  (page/set-default-navigation-timeout! (require-page!) ms))
(defn sci-emulate-media! [opts]
  (throw-if-anomaly (page/emulate-media! (require-page!) opts)))
(defn sci-bring-to-front []
  (page/bring-to-front (require-page!)))
(defn sci-set-extra-http-headers! [headers]
  (page/set-extra-http-headers! (require-page!) headers))
(defn sci-add-script-tag [opts]
  (throw-if-anomaly (page/add-script-tag (require-page!) opts)))
(defn sci-add-style-tag [opts]
  (throw-if-anomaly (page/add-style-tag (require-page!) opts)))
(defn sci-expose-function! [fn-name f]
  (page/expose-function! (require-page!) fn-name f))
(defn sci-expose-binding! [binding-name f]
  (page/expose-binding! (require-page!) binding-name f))

;; =============================================================================
;; Page Events
;; =============================================================================

(defn sci-on-console   [handler] (page/on-console (require-page!) handler))
(defn sci-on-dialog    [handler] (page/on-dialog (require-page!) handler))
(defn sci-once-dialog  [handler] (page/once-dialog (require-page!) handler))
(defn sci-on-page-error [handler] (page/on-page-error (require-page!) handler))
(defn sci-on-request   [handler] (page/on-request (require-page!) handler))
(defn sci-on-response  [handler] (page/on-response (require-page!) handler))
(defn sci-on-close     [handler] (page/on-close (require-page!) handler))
(defn sci-on-download  [handler] (page/on-download (require-page!) handler))
(defn sci-on-popup     [handler] (page/on-popup (require-page!) handler))

;; =============================================================================
;; Routing
;; =============================================================================

(defn sci-route!   [pattern handler] (page/route! (require-page!) pattern handler))
(defn sci-unroute! [pattern]         (page/unroute! (require-page!) pattern))
(defn sci-route-from-har!
  ([har]      (throw-if-anomaly (page/route-from-har! (require-page!) har)))
  ([har opts] (throw-if-anomaly (page/route-from-har! (require-page!) har opts))))
(defn sci-route-web-socket! [pattern handler]
  (page/route-web-socket! (require-page!) pattern handler))

;; =============================================================================
;; Page Accessors
;; =============================================================================

(defn sci-page
  "Returns the current Page instance."
  [] (require-page!))
(defn sci-keyboard    [] (page/page-keyboard (require-page!)))
(defn sci-mouse       [] (page/page-mouse (require-page!)))
(defn sci-touchscreen [] (page/page-touchscreen (require-page!)))
(defn sci-page-context [] (page/page-context (require-page!)))
(defn sci-frames      [] (page/frames (require-page!)))
(defn sci-main-frame  [] (page/main-frame (require-page!)))
(defn sci-frame-by-name [name] (page/frame-by-name (require-page!) name))
(defn sci-frame-by-url  [pattern] (page/frame-by-url (require-page!) pattern))

;; =============================================================================
;; Context & Browser Functions
;; =============================================================================

(defn sci-context
  "Returns the current BrowserContext instance."
  [] (require-context!))
(defn sci-browser
  "Returns the current Browser instance."
  [] (require-browser!))
(defn sci-context-cookies          [] (core/context-cookies (require-context!)))
(defn sci-context-clear-cookies!   [] (core/context-clear-cookies! (require-context!)))
(defn sci-context-storage-state    [] (core/context-storage-state (require-context!)))
(defn sci-context-save-storage-state! [path] (core/context-save-storage-state! (require-context!) path))
(defn sci-context-set-offline!     [offline] (core/context-set-offline! (require-context!) offline))
(defn sci-context-grant-permissions! [perms] (core/context-grant-permissions! (require-context!) perms))
(defn sci-context-clear-permissions! [] (core/context-clear-permissions! (require-context!)))
(defn sci-context-set-extra-http-headers! [headers] (core/context-set-extra-http-headers! (require-context!) headers))
(defn sci-context-route-from-har!
  ([har]      (throw-if-anomaly (core/context-route-from-har! (require-context!) har)))
  ([har opts] (throw-if-anomaly (core/context-route-from-har! (require-context!) har opts))))
(defn sci-context-route-web-socket! [pattern handler]
  (core/context-route-web-socket! (require-context!) pattern handler))
(defn sci-browser-connected? [] (core/browser-connected? (require-browser!)))
(defn sci-browser-version    [] (core/browser-version (require-browser!)))

;; =============================================================================
;; Tracing
;; =============================================================================

(defn sci-trace-start!
  "Starts Playwright tracing on the current context.

   Opts:
   :name        - String. Trace name (appears in Trace Viewer).
   :screenshots - Boolean. Capture screenshots (default: false).
   :snapshots   - Boolean. Capture DOM snapshots (default: false).
   :sources     - Boolean. Include source files (default: false)."
  ([]     (throw-if-anomaly (util/tracing-start! (util/context-tracing (require-context!)))))
  ([opts] (throw-if-anomaly (util/tracing-start! (util/context-tracing (require-context!)) opts))))

(defn sci-trace-stop!
  "Stops Playwright tracing and saves to a file.

   Opts:
   :path - String. Output path (default: \"trace.zip\")."
  ([]     (throw-if-anomaly (util/tracing-stop! (util/context-tracing (require-context!)))))
  ([opts] (throw-if-anomaly (util/tracing-stop! (util/context-tracing (require-context!)) opts))))

(defn sci-trace-group
  "Opens a named group in the trace. Groups nest actions visually in Trace Viewer."
  [name]
  (.group ^com.microsoft.playwright.Tracing (util/context-tracing (require-context!)) ^String name))

(defn sci-trace-group-end
  "Closes the current trace group."
  []
  (.groupEnd ^com.microsoft.playwright.Tracing (util/context-tracing (require-context!))))

;; =============================================================================
;; Network
;; =============================================================================

(defn sci-last-response
  "Navigates to URL and returns response info map with :status, :ok?, :url, :headers."
  [url]
  (let [resp (throw-if-anomaly (page/navigate (require-page!) url))]
    (when resp
      {:status  (net/response-status resp)
       :ok?     (net/response-ok? resp)
       :url     (net/response-url resp)
       :headers (net/response-headers resp)})))

;; =============================================================================
;; Info
;; =============================================================================

(defn sci-info
  "Returns a map with current page :url, :title, :viewport, and :closed? state."
  []
  {:url      (page/url (require-page!))
   :title    (page/title (require-page!))
   :viewport (page/viewport-size (require-page!))
   :closed?  (page/is-closed? (require-page!))})

;; =============================================================================
;; Snapshot + Annotate
;; =============================================================================

(defn sci-snapshot
  ([] (throw-if-anomaly (snapshot/capture-snapshot (require-page!))))
  ([page-or-opts]
   (if (map? page-or-opts)
     (throw-if-anomaly (snapshot/capture-snapshot (require-page!) page-or-opts))
     (throw-if-anomaly (snapshot/capture-snapshot page-or-opts))))
  ([page opts] (throw-if-anomaly (snapshot/capture-snapshot page opts))))
(defn sci-full-snapshot
  ([] (throw-if-anomaly (snapshot/capture-full-snapshot (require-page!))))
  ([page] (throw-if-anomaly (snapshot/capture-full-snapshot page))))
(defn sci-resolve-ref [ref-id]
  (snapshot/resolve-ref (require-page!) ref-id))
(defn sci-clear-refs! []
  (snapshot/clear-refs! (require-page!)))
(defn sci-click-ref
  "Clicks an element identified by a snapshot ref ID."
  [ref-id]
  (throw-if-anomaly (locator/click (snapshot/resolve-ref (require-page!) ref-id))))
(defn sci-fill-ref
  "Fills an input element identified by a snapshot ref ID."
  [ref-id value]
  (throw-if-anomaly (locator/fill (snapshot/resolve-ref (require-page!) ref-id) value)))
(defn sci-type-ref
  "Types text into an element identified by a snapshot ref ID."
  [ref-id text]
  (throw-if-anomaly (locator/type-text (snapshot/resolve-ref (require-page!) ref-id) text)))
(defn sci-hover-ref
  "Hovers over an element identified by a snapshot ref ID."
  [ref-id]
  (throw-if-anomaly (locator/hover (snapshot/resolve-ref (require-page!) ref-id))))
(defn sci-annotate
  "Injects annotation overlays into the current page for visible elements.
   Takes refs from snapshot and optional display opts."
  ([refs] (annotate/inject-overlays! (require-page!) refs))
  ([refs opts] (annotate/inject-overlays! (require-page!) refs opts)))
(defn sci-unannotate
  "Removes all annotation overlays from the current page."
  [] (annotate/remove-overlays! (require-page!)))
(defn sci-annotated-screenshot
  ([refs] (throw-if-anomaly (annotate/annotated-screenshot (require-page!) refs)))
  ([refs opts] (throw-if-anomaly (annotate/annotated-screenshot (require-page!) refs opts))))
(defn sci-save-annotated-screenshot!
  ([refs path] (throw-if-anomaly (annotate/save-annotated-screenshot! (require-page!) refs path)))
  ([refs path opts] (throw-if-anomaly (annotate/save-annotated-screenshot! (require-page!) refs path opts))))

;; =============================================================================
;; SCI Namespace Registration
;; =============================================================================

(defn- make-ns-map
  "Creates a SCI namespace map from a seq of [name fn] pairs.
   Preserves :sci/macro metadata from with-meta on the function."
  [sci-ns pairs]
  (into {}
    (map (fn [[sym f]]
           (let [m (meta f)
                 var-meta (cond-> {:ns sci-ns}
                            (:sci/macro m) (assoc :sci/macro true))]
             [sym (sci/new-var sym f var-meta)])))
    pairs))

(defn create-sci-ctx
  "Creates a SCI context with all spel functions registered.

   The context includes:
   - `pw` namespace: All REPL functions (start!, stop!, goto, click, etc.)
   - `snapshot` namespace: Snapshot capture and ref resolution
   - `annotate` namespace: Screenshot annotation
   - `input` namespace: Keyboard, Mouse, Touchscreen operations
   - `frame` namespace: Frame and FrameLocator operations
   - `net` namespace: Network request/response/route operations
   - `loc` namespace: Raw locator operations (explicit Locator arg)
   - `assert` namespace: Raw assertion operations (explicit assertion obj)
   - `core` namespace: Lifecycle stubs + utility pass-throughs

   All Playwright Java classes are registered for interop.

   Returns:
   SCI context ready for evaluation."
  []
  (let [pw-ns       (sci/create-ns 'spel nil)
        snap-ns     (sci/create-ns 'snapshot nil)
        ann-ns      (sci/create-ns 'annotate nil)
        input-ns    (sci/create-ns 'input nil)
        frame-ns    (sci/create-ns 'frame nil)
        net-ns      (sci/create-ns 'net nil)
        loc-ns      (sci/create-ns 'loc nil)
        assert-ns   (sci/create-ns 'assert nil)
        core-ns     (sci/create-ns 'core nil)

        ;; =================================================================
        ;; spel/ — Simplified API with implicit page/context from atoms
        ;; =================================================================
        pw-map (make-ns-map pw-ns
                 [;; Lifecycle
                  ['start!        sci-start!]
                  ['stop!         sci-stop!]
                  ['restart!      sci-restart!]
                  ['new-tab!      sci-new-tab!]
                  ['switch-tab!   sci-switch-tab!]
                  ['tabs          sci-tabs]
                  ;; Navigation
                  ['goto          sci-goto]
                  ['back          sci-back]
                  ['forward       sci-forward]
                  ['reload!       sci-reload!]
                  ['url           sci-url]
                  ['title         sci-title]
                  ['html          sci-html]
                  ;; Locators
                  ['$             sci-$]
                  ['$$            sci-$$]
                  ['$text         sci-$text]
                  ['$role         sci-$role]
                  ['$label        sci-$label]
                  ['$placeholder  sci-$placeholder]
                  ['$test-id      sci-$test-id]
                  ['$alt-text     sci-$alt-text]
                  ['$title-attr   sci-$title-attr]
                  ;; Actions
                  ['click         sci-click]
                  ['dblclick      sci-dblclick]
                  ['fill          sci-fill]
                  ['type-text     sci-type-text]
                  ['press         sci-press]
                  ['clear         sci-clear]
                  ['check         sci-check]
                  ['uncheck       sci-uncheck]
                  ['hover         sci-hover]
                  ['focus         sci-focus]
                  ['select        sci-select]
                  ['blur          sci-blur]
                  ['tap           sci-tap]
                  ['set-input-files! sci-set-input-files!]
                  ['scroll-into-view sci-scroll-into-view]
                  ['dispatch-event   sci-dispatch-event]
                  ['drag-to          sci-drag-to]
                  ['highlight        sci-highlight]
                  ['locator-screenshot sci-locator-screenshot]
                  ;; Content & State
                  ['text          sci-text]
                  ['inner-text    sci-inner-text]
                  ['inner-html    sci-inner-html]
                  ['attr          sci-attr]
                  ['value         sci-value]
                  ['count-of      sci-count-of]
                  ['visible?      sci-visible?]
                  ['hidden?       sci-hidden?]
                  ['enabled?      sci-enabled?]
                  ['disabled?     sci-disabled?]
                  ['editable?     sci-editable?]
                  ['checked?      sci-checked?]
                  ['bbox          sci-bbox]
                  ['all-text-contents sci-all-text-contents]
                  ['all-inner-texts   sci-all-inner-texts]
                  ;; Locator filtering
                  ['loc-filter       sci-loc-filter]
                  ['first            sci-first]
                  ['last             sci-last]
                  ['nth              sci-nth]
                  ['loc-locator      sci-loc-locator]
                  ['loc-get-by-text    sci-loc-get-by-text]
                  ['loc-get-by-role    sci-loc-get-by-role]
                  ['loc-get-by-label   sci-loc-get-by-label]
                  ['loc-get-by-test-id sci-loc-get-by-test-id]
                  ;; Locator waiting & evaluation
                  ['loc-wait-for       sci-loc-wait-for]
                  ['evaluate-locator   sci-evaluate-locator]
                  ['evaluate-all-locs  sci-evaluate-all-locs]
                  ;; JavaScript
                  ['eval-js       sci-eval-js]
                  ['evaluate-handle sci-evaluate-handle]
                  ;; Screenshots
                  ['screenshot    sci-screenshot]
                  ['pdf           sci-pdf]
                  ;; Waiting
                  ['wait-for      sci-wait-for]
                  ['wait-for-load sci-wait-for-load]
                  ['sleep         sci-sleep]
                  ['wait-for-url      sci-wait-for-url]
                  ['wait-for-function sci-wait-for-function]
                  ['wait-for-popup    sci-wait-for-popup]
                  ['wait-for-download sci-wait-for-download]
                  ['wait-for-file-chooser sci-wait-for-file-chooser]
                  ;; Assertions
                  ['assert-that   sci-assert-that]
                  ['assert-not    sci-assert-not]
                  ['assert-text    sci-assert-text]
                  ['assert-contains-text sci-assert-contains-text]
                  ['assert-visible sci-assert-visible]
                  ['assert-hidden  sci-assert-hidden]
                  ['assert-title   sci-assert-title]
                  ['assert-url     sci-assert-url]
                  ['assert-count   sci-assert-count]
                  ['assert-attr    sci-assert-attr]
                  ['assert-class   sci-assert-class]
                  ['assert-contains-class sci-assert-contains-class]
                  ['assert-css     sci-assert-css]
                  ['assert-id      sci-assert-id]
                  ['assert-js-property sci-assert-js-property]
                  ['assert-value   sci-assert-value]
                  ['assert-values  sci-assert-values]
                  ['assert-role    sci-assert-role]
                  ['assert-accessible-name        sci-assert-accessible-name]
                  ['assert-accessible-description sci-assert-accessible-description]
                  ['assert-accessible-error-message sci-assert-accessible-error-message]
                  ['assert-matches-aria-snapshot   sci-assert-matches-aria-snapshot]
                  ['assert-attached  sci-assert-attached]
                  ['assert-checked   sci-assert-checked]
                  ['assert-disabled  sci-assert-disabled]
                  ['assert-editable  sci-assert-editable]
                  ['assert-enabled   sci-assert-enabled]
                  ['assert-focused   sci-assert-focused]
                  ['assert-empty     sci-assert-empty]
                  ['assert-in-viewport sci-assert-in-viewport]
                  ['assert-page-not  sci-assert-page-not]
                  ['set-assertion-timeout! sci-set-assertion-timeout!]
                  ;; Page functions
                  ['set-content!     sci-set-content!]
                  ['set-viewport-size! sci-set-viewport-size!]
                  ['viewport-size  (fn [] (page/viewport-size (require-page!)))]
                  ['set-default-timeout! sci-set-page-default-timeout!]
                  ['set-default-navigation-timeout! sci-set-default-navigation-timeout!]
                  ['emulate-media!   sci-emulate-media!]
                  ['bring-to-front   sci-bring-to-front]
                  ['set-extra-http-headers! sci-set-extra-http-headers!]
                  ['add-script-tag   sci-add-script-tag]
                  ['add-style-tag    sci-add-style-tag]
                  ['expose-function! sci-expose-function!]
                  ['expose-binding!  sci-expose-binding!]
                  ;; Page events
                  ['on-console   sci-on-console]
                  ['on-dialog    sci-on-dialog]
                  ['once-dialog  sci-once-dialog]
                  ['on-page-error sci-on-page-error]
                  ['on-request   sci-on-request]
                  ['on-response  sci-on-response]
                  ['on-close     sci-on-close]
                  ['on-download  sci-on-download]
                  ['on-popup     sci-on-popup]
                  ;; Routing
                  ['route!       sci-route!]
                  ['unroute!     sci-unroute!]
                  ['route-from-har!    sci-route-from-har!]
                  ['route-web-socket!  sci-route-web-socket!]
                  ;; Page accessors
                  ['page         sci-page]
                  ['keyboard     sci-keyboard]
                  ['mouse        sci-mouse]
                  ['touchscreen  sci-touchscreen]
                  ['page-context sci-page-context]
                  ['frames       sci-frames]
                  ['main-frame   sci-main-frame]
                  ['frame-by-name sci-frame-by-name]
                  ['frame-by-url  sci-frame-by-url]
                  ;; Context & Browser
                  ['context      sci-context]
                  ['browser      sci-browser]
                  ['context-cookies          sci-context-cookies]
                  ['context-clear-cookies!   sci-context-clear-cookies!]
                  ['context-storage-state    sci-context-storage-state]
                  ['context-save-storage-state! sci-context-save-storage-state!]
                  ['context-set-offline!     sci-context-set-offline!]
                  ['context-grant-permissions!  sci-context-grant-permissions!]
                  ['context-clear-permissions!  sci-context-clear-permissions!]
                  ['context-set-extra-http-headers! sci-context-set-extra-http-headers!]
                  ['context-route-from-har!    sci-context-route-from-har!]
                  ['context-route-web-socket!  sci-context-route-web-socket!]
                  ['browser-connected? sci-browser-connected?]
                  ['browser-version    sci-browser-version]
                  ;; Tracing
                  ['trace-start!     sci-trace-start!]
                  ['trace-stop!      sci-trace-stop!]
                  ['trace-group      sci-trace-group]
                  ['trace-group-end  sci-trace-group-end]
                  ;; Network
                  ['last-response  sci-last-response]
                  ;; Info
                  ['info           sci-info]
                  ;; Snapshot + Ref-based actions
                  ['snapshot            sci-snapshot]
                  ['full-snapshot       sci-full-snapshot]
                  ['resolve-ref         sci-resolve-ref]
                  ['clear-refs!         sci-clear-refs!]
                  ['click-ref           sci-click-ref]
                  ['fill-ref            sci-fill-ref]
                  ['type-ref            sci-type-ref]
                  ['hover-ref           sci-hover-ref]
                  ['annotate                 sci-annotate]
                  ['unannotate               sci-unannotate]
                  ['annotated-screenshot     sci-annotated-screenshot]
                  ['save-annotated-screenshot! sci-save-annotated-screenshot!]])

        ;; =================================================================
        ;; snapshot/ — Snapshot capture
        ;; =================================================================
        snap-map (make-ns-map snap-ns
                   [['capture           sci-snapshot]
                    ['capture-full      sci-full-snapshot]
                    ['resolve-ref       sci-resolve-ref]
                    ['clear-refs!       sci-clear-refs!]
                    ['ref-bounding-box  snapshot/ref-bounding-box]])

        ;; =================================================================
        ;; annotate/ — Screenshot annotation
        ;; =================================================================
        ann-map  (make-ns-map ann-ns
                   [['annotated-screenshot sci-annotated-screenshot]
                    ['save!                sci-save-annotated-screenshot!]])

        ;; =================================================================
        ;; input/ — Keyboard, Mouse, Touchscreen (direct pass-throughs)
        ;; =================================================================
        input-map (make-ns-map input-ns
                    [['key-press       input/key-press]
                     ['key-type        input/key-type]
                     ['key-down        input/key-down]
                     ['key-up          input/key-up]
                     ['key-insert-text input/key-insert-text]
                     ['mouse-click     input/mouse-click]
                     ['mouse-dblclick  input/mouse-dblclick]
                     ['mouse-move      input/mouse-move]
                     ['mouse-down      input/mouse-down]
                     ['mouse-up        input/mouse-up]
                     ['mouse-wheel     input/mouse-wheel]
                     ['touchscreen-tap input/touchscreen-tap]])

        ;; =================================================================
        ;; frame/ — Frame and FrameLocator (direct pass-throughs)
        ;; =================================================================
        frame-map (make-ns-map frame-ns
                    [['navigate          frame/frame-navigate]
                     ['content           frame/frame-content]
                     ['set-content!      frame/frame-set-content!]
                     ['url               frame/frame-url]
                     ['name              frame/frame-name]
                     ['title             frame/frame-title]
                     ['locator           frame/frame-locator]
                     ['get-by-text       frame/frame-get-by-text]
                     ['get-by-role       frame/frame-get-by-role]
                     ['get-by-label      frame/frame-get-by-label]
                     ['get-by-test-id    frame/frame-get-by-test-id]
                     ['evaluate          frame/frame-evaluate]
                     ['parent-frame      frame/parent-frame]
                     ['child-frames      frame/child-frames]
                     ['frame-page        frame/frame-page]
                     ['is-detached?      frame/is-detached?]
                     ['wait-for-load-state  frame/frame-wait-for-load-state]
                     ['wait-for-selector    frame/frame-wait-for-selector]
                     ['wait-for-function    frame/frame-wait-for-function]
                     ['frame-locator     frame/frame-locator-obj]
                     ['fl-locator        frame/fl-locator]
                     ['fl-get-by-text    frame/fl-get-by-text]
                     ['fl-get-by-role    frame/fl-get-by-role]
                     ['fl-get-by-label   frame/fl-get-by-label]
                     ['fl-first          frame/fl-first]
                     ['fl-last           frame/fl-last]
                     ['fl-nth            frame/fl-nth]])

        ;; =================================================================
        ;; net/ — Network request/response/route (direct pass-throughs)
        ;; =================================================================
        net-map (make-ns-map net-ns
                  [['request-url          net/request-url]
                   ['request-method       net/request-method]
                   ['request-headers      net/request-headers]
                   ['request-all-headers  net/request-all-headers]
                   ['request-post-data    net/request-post-data]
                   ['request-post-data-buffer net/request-post-data-buffer]
                   ['request-resource-type net/request-resource-type]
                   ['request-response     net/request-response]
                   ['request-failure      net/request-failure]
                   ['request-frame        net/request-frame]
                   ['request-is-navigation? net/request-is-navigation?]
                   ['request-redirected-from net/request-redirected-from]
                   ['request-redirected-to   net/request-redirected-to]
                   ['request-timing       net/request-timing]
                   ['response-url         net/response-url]
                   ['response-status      net/response-status]
                   ['response-status-text net/response-status-text]
                   ['response-headers     net/response-headers]
                   ['response-all-headers net/response-all-headers]
                   ['response-body        net/response-body]
                   ['response-text        net/response-text]
                   ['response-ok?         net/response-ok?]
                   ['response-request     net/response-request]
                   ['response-frame       net/response-frame]
                   ['response-finished    net/response-finished]
                   ['response-header-value  net/response-header-value]
                   ['response-header-values net/response-header-values]
                   ['route-request   net/route-request]
                   ['route-fulfill!  net/route-fulfill!]
                   ['route-continue! net/route-continue!]
                   ['route-abort!    net/route-abort!]
                   ['route-fallback! net/route-fallback!]
                   ['route-fetch!    net/route-fetch!]
                   ['ws-url          net/ws-url]
                   ['ws-is-closed?   net/ws-is-closed?]
                   ['ws-on-message   net/ws-on-message]
                   ['ws-on-close     net/ws-on-close]
                   ['ws-on-error     net/ws-on-error]
                   ['wsf-text        net/wsf-text]
                   ['wsf-binary      net/wsf-binary]
                   ['wsr-url                net/wsr-url]
                   ['wsr-close!             net/wsr-close!]
                   ['wsr-connect-to-server! net/wsr-connect-to-server!]
                   ['wsr-on-message         net/wsr-on-message]
                   ['wsr-send!              net/wsr-send!]
                   ['wsr-on-close           net/wsr-on-close]])

        ;; =================================================================
        ;; loc/ — Raw locator operations (explicit Locator argument)
        ;; =================================================================
        loc-map (make-ns-map loc-ns
                  [['click          locator/click]
                   ['dblclick       locator/dblclick]
                   ['fill           locator/fill]
                   ['type-text      locator/type-text]
                   ['press          locator/press]
                   ['clear          locator/clear]
                   ['check          locator/check]
                   ['uncheck        locator/uncheck]
                   ['hover          locator/hover]
                   ['focus          locator/focus]
                   ['blur           locator/blur]
                   ['tap-element    locator/tap-element]
                   ['select-option  locator/select-option]
                   ['set-input-files! locator/set-input-files!]
                   ['scroll-into-view locator/scroll-into-view]
                   ['dispatch-event locator/dispatch-event]
                   ['drag-to        locator/drag-to]
                   ['text-content   locator/text-content]
                   ['inner-text     locator/inner-text]
                   ['inner-html     locator/inner-html]
                   ['input-value    locator/input-value]
                   ['get-attribute  locator/get-attribute]
                   ['is-visible?    locator/is-visible?]
                   ['is-hidden?     locator/is-hidden?]
                   ['is-enabled?    locator/is-enabled?]
                   ['is-disabled?   locator/is-disabled?]
                   ['is-editable?   locator/is-editable?]
                   ['is-checked?    locator/is-checked?]
                   ['bounding-box   locator/bounding-box]
                   ['count-elements locator/count-elements]
                   ['all-text-contents locator/all-text-contents]
                   ['all-inner-texts   locator/all-inner-texts]
                   ['all            locator/all]
                   ['loc-filter     locator/loc-filter]
                   ['first-element  locator/first-element]
                   ['last-element   locator/last-element]
                   ['nth-element    locator/nth-element]
                   ['loc-locator    locator/loc-locator]
                   ['content-frame  locator/content-frame]
                   ['loc-get-by-text    locator/loc-get-by-text]
                   ['loc-get-by-role    locator/loc-get-by-role]
                   ['loc-get-by-label   locator/loc-get-by-label]
                   ['loc-get-by-test-id locator/loc-get-by-test-id]
                   ['wait-for       locator/wait-for]
                   ['evaluate       locator/evaluate-locator]
                   ['evaluate-all   locator/evaluate-all]
                   ['screenshot     locator/locator-screenshot]
                   ['highlight      locator/highlight]
                   ['element-handle  locator/element-handle]
                   ['element-handles locator/element-handles]])

        ;; =================================================================
        ;; assert/ — Raw assertion operations (explicit assertion object)
        ;; =================================================================
        assert-ns-map (make-ns-map assert-ns
                        [['assert-that assert/assert-that]
                         ['set-default-assertion-timeout! assert/set-default-assertion-timeout!]
                         ['loc-not  assert/loc-not]
                         ['page-not assert/page-not]
                         ['api-not  assert/api-not]
                         ['has-text      assert/has-text]
                         ['contains-text assert/contains-text]
                         ['has-attribute assert/has-attribute]
                         ['has-class     assert/has-class]
                         ['contains-class assert/contains-class]
                         ['has-count     assert/has-count]
                         ['has-css       assert/has-css]
                         ['has-id        assert/has-id]
                         ['has-js-property assert/has-js-property]
                         ['has-value     assert/has-value]
                         ['has-values    assert/has-values]
                         ['has-role      assert/has-role]
                         ['has-accessible-name        assert/has-accessible-name]
                         ['has-accessible-description assert/has-accessible-description]
                         ['has-accessible-error-message assert/has-accessible-error-message]
                         ['matches-aria-snapshot assert/matches-aria-snapshot]
                         ['is-attached   assert/is-attached]
                         ['is-checked    assert/is-checked]
                         ['is-disabled   assert/is-disabled]
                         ['is-editable   assert/is-editable]
                         ['is-enabled    assert/is-enabled]
                         ['is-focused    assert/is-focused]
                         ['is-hidden     assert/is-hidden]
                         ['is-visible    assert/is-visible]
                         ['is-empty      assert/is-empty]
                         ['is-in-viewport assert/is-in-viewport]
                         ['has-title assert/has-title]
                         ['has-url   assert/has-url]
                         ['is-ok    assert/is-ok]])

        ;; =================================================================
        ;; core/ — Lifecycle macros + utility pass-throughs
        ;;
        ;; The with-* forms are SCI macros that work with the daemon's
        ;; existing browser instances. They discard the init expressions
        ;; (launch-chromium, new-context, etc.) and bind the variables to
        ;; the daemon's current instances via spel/ namespace helpers.
        ;; This lets codegen --format=script output run in --eval mode.
        ;; =================================================================
        core-map (make-ns-map core-ns
                   [;; Lifecycle macros — work with daemon browser
                    ['with-playwright (with-meta
                                        (fn [_&form _&env binding & body]
                                          (let [[sym] binding]
                                            (list 'do
                                              (list 'spel/start!)
                                              (list* 'let [sym nil] body))))
                                        {:sci/macro true})]
                    ['with-browser    (with-meta
                                        (fn [_&form _&env binding & body]
                                          (let [[sym _init-expr] binding]
                                            (list* 'let [sym (list 'spel/browser)] body)))
                                        {:sci/macro true})]
                    ['with-context    (with-meta
                                        (fn [_&form _&env binding & body]
                                          (let [[sym _init-expr] binding]
                                            (list* 'let [sym (list 'spel/context)] body)))
                                        {:sci/macro true})]
                    ['with-page       (with-meta
                                        (fn [_&form _&env binding & body]
                                          (let [[sym _init-expr] binding]
                                            (list* 'let [sym (list 'spel/page)] body)))
                                        {:sci/macro true})]
                    ;; Launch/create functions — return daemon's existing instances
                    ['create          (fn [] nil)]
                    ['launch-chromium (fn [_pw & _opts] (require-browser!))]
                    ['launch-firefox  (fn [_pw & _opts] (require-browser!))]
                    ['launch-webkit   (fn [_pw & _opts] (require-browser!))]
                    ['new-context        (fn [_browser & _opts] (require-context!))]
                    ['new-page           (fn [_browser] (require-page!))]
                    ['new-page-from-context (fn [_ctx] (require-page!))]
                    ;; Close & utility pass-throughs
                    ['close!          core/close!]
                    ['close-browser!  core/close-browser!]
                    ['close-context!  core/close-context!]
                    ['close-page!     core/close-page!]
                    ['anomaly?        core/anomaly?]
                    ['browser-connected? core/browser-connected?]
                    ['browser-version    core/browser-version]
                    ['browser-contexts   core/browser-contexts]
                    ['context-pages      core/context-pages]
                    ['context-browser    core/context-browser]
                    ['context-cookies          core/context-cookies]
                    ['context-clear-cookies!   core/context-clear-cookies!]
                    ['context-storage-state    core/context-storage-state]
                    ['context-save-storage-state! core/context-save-storage-state!]
                    ['context-set-offline!     core/context-set-offline!]
                    ['context-grant-permissions!  core/context-grant-permissions!]
                    ['context-clear-permissions!  core/context-clear-permissions!]
                    ['context-set-extra-http-headers! core/context-set-extra-http-headers!]
                    ['context-set-default-timeout!           core/context-set-default-timeout!]
                    ['context-set-default-navigation-timeout! core/context-set-default-navigation-timeout!]
                    ['context-route-from-har!   core/context-route-from-har!]
                    ['context-route-web-socket!  core/context-route-web-socket!]])

        ;; =================================================================
        ;; page/ — Raw page operations (explicit Page argument)
        ;;
        ;; These mirror com.blockether.spel.page functions so that code
        ;; generated by `spel codegen --format=script` works in --eval.
        ;; =================================================================
        page-raw-ns  (sci/create-ns 'page nil)
        page-raw-map (make-ns-map page-raw-ns
                       [['navigate               page/navigate]
                        ['go-back                 page/go-back]
                        ['go-forward              page/go-forward]
                        ['reload                  page/reload]
                        ['url                     page/url]
                        ['title                   page/title]
                        ['content                 page/content]
                        ['set-content!            page/set-content!]
                        ['locator                 page/locator]
                        ['get-by-text             page/get-by-text]
                        ['get-by-role             page/get-by-role]
                        ['get-by-label            page/get-by-label]
                        ['get-by-placeholder      page/get-by-placeholder]
                        ['get-by-alt-text         page/get-by-alt-text]
                        ['get-by-title            page/get-by-title]
                        ['get-by-test-id          page/get-by-test-id]
                        ['evaluate                page/evaluate]
                        ['evaluate-handle         page/evaluate-handle]
                        ['screenshot              page/screenshot]
                        ['pdf                     page/pdf]
                        ['is-closed?              page/is-closed?]
                        ['viewport-size           page/viewport-size]
                        ['set-viewport-size!      page/set-viewport-size!]
                        ['set-default-timeout!    page/set-default-timeout!]
                        ['set-default-navigation-timeout! page/set-default-navigation-timeout!]
                        ['main-frame              page/main-frame]
                        ['frames                  page/frames]
                        ['frame-by-name           page/frame-by-name]
                        ['frame-by-url            page/frame-by-url]
                        ['wait-for-load-state     page/wait-for-load-state]
                        ['wait-for-url            page/wait-for-url]
                        ['wait-for-selector       page/wait-for-selector]
                        ['wait-for-timeout        page/wait-for-timeout]
                        ['wait-for-function       page/wait-for-function]
                        ['wait-for-response       page/wait-for-response]
                        ['wait-for-popup          page/wait-for-popup]
                        ['wait-for-download       page/wait-for-download]
                        ['wait-for-file-chooser   page/wait-for-file-chooser]
                        ['emulate-media!          page/emulate-media!]
                        ['on-console              page/on-console]
                        ['on-dialog               page/on-dialog]
                        ['once-dialog             page/once-dialog]
                        ['on-page-error           page/on-page-error]
                        ['on-request              page/on-request]
                        ['on-response             page/on-response]
                        ['on-close                page/on-close]
                        ['on-download             page/on-download]
                        ['on-popup                page/on-popup]
                        ['route!                  page/route!]
                        ['unroute!                page/unroute!]
                        ['route-from-har!         page/route-from-har!]
                        ['route-web-socket!       page/route-web-socket!]
                        ['bring-to-front          page/bring-to-front]
                        ['page-context            page/page-context]
                        ['add-script-tag          page/add-script-tag]
                        ['add-style-tag           page/add-style-tag]
                        ['page-keyboard           page/page-keyboard]
                        ['page-mouse              page/page-mouse]
                        ['page-touchscreen        page/page-touchscreen]
                        ['set-extra-http-headers! page/set-extra-http-headers!]
                        ['expose-function!        page/expose-function!]
                        ['expose-binding!         page/expose-binding!]])

        ;; =================================================================
        ;; locator/ — Raw locator operations (explicit Locator argument)
        ;;
        ;; Alias of loc/ so that `locator/click` etc. from codegen works.
        ;; =================================================================
        locator-raw-ns  (sci/create-ns 'locator nil)
        locator-raw-map (make-ns-map locator-raw-ns
                          [['click          locator/click]
                           ['dblclick       locator/dblclick]
                           ['fill           locator/fill]
                           ['type-text      locator/type-text]
                           ['press          locator/press]
                           ['clear          locator/clear]
                           ['check          locator/check]
                           ['uncheck        locator/uncheck]
                           ['hover          locator/hover]
                           ['focus          locator/focus]
                           ['blur           locator/blur]
                           ['tap-element    locator/tap-element]
                           ['select-option  locator/select-option]
                           ['set-input-files! locator/set-input-files!]
                           ['scroll-into-view locator/scroll-into-view]
                           ['dispatch-event locator/dispatch-event]
                           ['drag-to        locator/drag-to]
                           ['text-content   locator/text-content]
                           ['inner-text     locator/inner-text]
                           ['inner-html     locator/inner-html]
                           ['input-value    locator/input-value]
                           ['get-attribute  locator/get-attribute]
                           ['is-visible?    locator/is-visible?]
                           ['is-hidden?     locator/is-hidden?]
                           ['is-enabled?    locator/is-enabled?]
                           ['is-disabled?   locator/is-disabled?]
                           ['is-editable?   locator/is-editable?]
                           ['is-checked?    locator/is-checked?]
                           ['bounding-box   locator/bounding-box]
                           ['count-elements locator/count-elements]
                           ['all-text-contents locator/all-text-contents]
                           ['all-inner-texts   locator/all-inner-texts]
                           ['all            locator/all]
                           ['loc-filter     locator/loc-filter]
                           ['first-element  locator/first-element]
                           ['last-element   locator/last-element]
                           ['nth-element    locator/nth-element]
                           ['loc-locator    locator/loc-locator]
                           ['content-frame  locator/content-frame]
                           ['loc-get-by-text    locator/loc-get-by-text]
                           ['loc-get-by-role    locator/loc-get-by-role]
                           ['loc-get-by-label   locator/loc-get-by-label]
                           ['loc-get-by-test-id locator/loc-get-by-test-id]
                           ['wait-for       locator/wait-for]
                           ['evaluate       locator/evaluate-locator]
                           ['evaluate-all   locator/evaluate-all]
                           ['screenshot     locator/locator-screenshot]
                           ['highlight      locator/highlight]
                           ['element-handle  locator/element-handle]
                           ['element-handles locator/element-handles]])

        ;; =================================================================
        ;; role/ — Clojure vars wrapping AriaRole enum values
        ;; =================================================================
        roles-ns  (sci/create-ns 'role nil)
        roles-map (make-ns-map roles-ns
                    [['alert            roles/alert]
                     ['alertdialog      roles/alertdialog]
                     ['application      roles/application]
                     ['article          roles/article]
                     ['banner           roles/banner]
                     ['blockquote       roles/blockquote]
                     ['button           roles/button]
                     ['caption          roles/caption]
                     ['cell             roles/cell]
                     ['checkbox         roles/checkbox]
                     ['code             roles/code]
                     ['columnheader     roles/columnheader]
                     ['combobox         roles/combobox]
                     ['complementary    roles/complementary]
                     ['contentinfo      roles/contentinfo]
                     ['definition       roles/definition]
                     ['deletion         roles/deletion]
                     ['dialog           roles/dialog]
                     ['directory        roles/directory]
                     ['document         roles/document]
                     ['emphasis         roles/emphasis]
                     ['feed             roles/feed]
                     ['figure           roles/figure]
                     ['form             roles/form]
                     ['generic          roles/generic]
                     ['grid             roles/grid]
                     ['gridcell         roles/gridcell]
                     ['group            roles/group]
                     ['heading          roles/heading]
                     ['img              roles/img]
                     ['insertion        roles/insertion]
                     ['link             roles/link]
                     ['list             roles/list]
                     ['listbox          roles/listbox]
                     ['listitem         roles/listitem]
                     ['log              roles/log]
                     ['main             roles/main]
                     ['marquee          roles/marquee]
                     ['math             roles/math]
                     ['meter            roles/meter]
                     ['menu             roles/menu]
                     ['menubar          roles/menubar]
                     ['menuitem         roles/menuitem]
                     ['menuitemcheckbox roles/menuitemcheckbox]
                     ['menuitemradio    roles/menuitemradio]
                     ['navigation       roles/navigation]
                     ['none             roles/none]
                     ['note             roles/note]
                     ['option           roles/option]
                     ['paragraph        roles/paragraph]
                     ['presentation     roles/presentation]
                     ['progressbar      roles/progressbar]
                     ['radio            roles/radio]
                     ['radiogroup       roles/radiogroup]
                     ['region           roles/region]
                     ['row              roles/row]
                     ['rowgroup         roles/rowgroup]
                     ['rowheader        roles/rowheader]
                     ['scrollbar        roles/scrollbar]
                     ['search           roles/search]
                     ['searchbox        roles/searchbox]
                     ['separator        roles/separator]
                     ['slider           roles/slider]
                     ['spinbutton       roles/spinbutton]
                     ['status           roles/status]
                     ['strong           roles/strong]
                     ['subscript        roles/subscript]
                     ['superscript      roles/superscript]
                     ['switch           roles/switch]
                     ['tab              roles/tab]
                     ['table            roles/table]
                     ['tablist          roles/tablist]
                     ['tabpanel         roles/tabpanel]
                     ['term             roles/term]
                     ['textbox          roles/textbox]
                     ['time             roles/time]
                     ['timer            roles/timer]
                     ['toolbar          roles/toolbar]
                     ['tooltip          roles/tooltip]
                     ['tree             roles/tree]
                     ['treegrid         roles/treegrid]
                     ['treeitem         roles/treeitem]])]

    (sci/init
      {:namespaces {;; Short aliases (original)
                    'spel     pw-map
                    'snapshot snap-map
                    'annotate ann-map
                    'input    input-map
                    'frame    frame-map
                    'net      net-map
                    'loc      loc-map
                    'assert   assert-ns-map
                    'core     core-map
                    'role     roles-map
                    ;; Raw namespaces for codegen script compat
                    'page     page-raw-map
                    'locator  locator-raw-map
                    ;; Full qualified names (for require support)
                    'com.blockether.spel.core       core-map
                    'com.blockether.spel.page       page-raw-map
                    'com.blockether.spel.locator    locator-raw-map
                    'com.blockether.spel.assertions assert-ns-map
                    'com.blockether.spel.roles      roles-map}
       :classes    {'Page              Page
                    'Browser           Browser
                    'BrowserContext    BrowserContext
                    'BrowserType      BrowserType
                    'Playwright        Playwright
                    'Locator           Locator
                    'ElementHandle    ElementHandle
                    'JSHandle          JSHandle
                    'Frame             Frame
                    'FrameLocator     FrameLocator
                    'Keyboard          Keyboard
                    'Mouse             Mouse
                    'Touchscreen      Touchscreen
                    'CDPSession        CDPSession
                    'ConsoleMessage   ConsoleMessage
                    'Dialog            Dialog
                    'Download          Download
                    'Tracing           Tracing
                    'Request           Request
                    'Response          Response
                    'Route             Route
                    'WebSocket         WebSocket
                    'WebSocketFrame   WebSocketFrame
                    'WebSocketRoute   WebSocketRoute
                    'APIResponse      APIResponse
                    'LocatorAssertions    LocatorAssertions
                    'PageAssertions      PageAssertions
                    'APIResponseAssertions APIResponseAssertions
                    'PlaywrightAssertions PlaywrightAssertions
                    ;; Enums (com.microsoft.playwright.options)
                    'AriaRole                       AriaRole
                    'ColorScheme                    ColorScheme
                    'ForcedColors                   ForcedColors
                    'HarContentPolicy               HarContentPolicy
                    'HarMode                        HarMode
                    'HarNotFound                    HarNotFound
                    'LoadState                      LoadState
                    'Media                          Media
                    'MouseButton                    MouseButton
                    'ReducedMotion                  ReducedMotion
                    'RouteFromHarUpdateContentPolicy RouteFromHarUpdateContentPolicy
                    'SameSiteAttribute              SameSiteAttribute
                    'ScreenshotType                 ScreenshotType
                    'ServiceWorkerPolicy            ServiceWorkerPolicy
                    'WaitForSelectorState           WaitForSelectorState
                    'WaitUntilState                 WaitUntilState
                    :allow             :all}})))

;; =============================================================================
;; Evaluation API
;; =============================================================================

(defn eval-string
  "Evaluates a Clojure string in the SCI context.

   Binds SCI's *out*/*err*/*in* to the JVM equivalents so that
   `println`, `prn`, etc. work correctly in --eval mode.

   Params:
   `ctx`  - SCI context from create-sci-ctx.
   `code` - String. Clojure code to evaluate.

   Returns:
   Result of evaluation."
  [ctx ^String code]
  (sci/with-bindings {sci/out *out*
                      sci/err *err*
                      sci/in  *in*}
    (sci/eval-string* ctx code)))
