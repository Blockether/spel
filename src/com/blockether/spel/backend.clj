(ns com.blockether.spel.backend
  "Narrow browser-backend abstraction shared by the Playwright and iOS
   application (Appium/WebDriver) providers.

   Covers only the common agent-facing surface: navigate, snapshot,
   click/fill/clear, evaluate, screenshot, history, cookies, tap/swipe.
   Playwright-specific advanced APIs (CDP, tracing, HAR, routing, frames,
   tabs, ...) intentionally stay OUTSIDE the protocol — callers must check
   `backend-capabilities` and fail with an explicit capability error.

   Ref actions (`@e2yrjz`) resolve through the shared snapshot
   `data-pw-ref` attribute system on both backends."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.ios :as ios]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.webdriver :as webdriver])
  (:import
   [com.microsoft.playwright Page]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol BrowserBackend
  (backend-kind [backend]
    "Returns a keyword identifying the backend (:playwright or :ios).")
  (backend-capabilities [backend]
    "Returns the set of supported capability keywords.")
  (navigate! [backend url opts]
    "Navigates to `url`. Returns {:url current-url}.")
  (current-url [backend]
    "Returns the current URL string.")
  (page-title [backend]
    "Returns the current page title string.")
  (page-content [backend]
    "Returns the page HTML source string.")
  (evaluate! [backend script args]
    "Evaluates JavaScript, returning the decoded result.")
  (capture-snapshot! [backend opts]
    "Captures a spel accessibility snapshot. Returns the snapshot map.")
  (click! [backend selector opts]
    "Clicks the element for a CSS selector or @ref.")
  (fill! [backend selector value opts]
    "Clears then fills the element for a CSS selector or @ref.")
  (clear! [backend selector opts]
    "Clears the element for a CSS selector or @ref.")
  (screenshot! [backend opts]
    "Returns PNG screenshot bytes.")
  (go-back! [backend]
    "History back.")
  (go-forward! [backend]
    "History forward.")
  (reload! [backend]
    "Reloads the current page.")
  (cookies [backend]
    "Returns the cookie list.")
  (tap! [backend selector-or-coords opts]
    "Performs a native touch tap on a selector/@ref or [x y] coordinates.")
  (swipe! [backend opts]
    "Performs a native touch swipe ({:direction ...} or {:from ... :to ...}).")
  (close-backend! [backend]
    "Closes backend-owned resources. Idempotent."))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn ref-selector?
  "Returns true when a selector string is a snapshot ref (@e2yrjz)."
  [^String s]
  (boolean (and s (re-matches #"@e[a-z0-9]+" s))))

(defn resolve-css
  "Resolves a selector-or-ref to a plain CSS selector.
   Refs become [data-pw-ref=\"...\"] selectors; CSS passes through."
  ^String [^String selector]
  (if (ref-selector? selector)
    (snapshot/ref-css-selector selector)
    selector))

(defn stale-ref-error
  "Builds the standard stale-ref ex-info for a missing @ref."
  [^String selector ^Throwable cause]
  (ex-info (str "Ref " (str/replace selector #"^@" "") " not found.\n"
             "The element ref is stale or missing.\n"
             "  - Suggestion: run 'snapshot -i' and retry with a fresh @ref.")
    {:selector selector :found false :stale-ref true}
    cause))

(defn unsupported!
  "Throws the standard capability error for an unsupported operation.

   Params:
   `backend`     - the active backend (for kind + capability set).
   `operation`   - String. The command/operation name.
   `alternative` - String, optional. Suggested alternative."
  ([backend operation] (unsupported! backend operation nil))
  ([backend operation alternative]
   (throw (ex-info (str "'" operation "' is not supported by the "
                     (name (backend-kind backend)) " backend."
                     (when alternative (str " " alternative)))
            {:error_code   "unsupported_capability"
             :backend      (name (backend-kind backend))
             :operation    operation
             :capabilities (mapv name (sort (map name (backend-capabilities backend))))}))))

;; =============================================================================
;; Playwright backend
;; =============================================================================

(def playwright-capability-set
  "Capabilities supported by the Playwright backend."
  #{:navigate :evaluate :snapshot :click :fill :clear :screenshot
    :back :forward :reload :cookies :frames :tabs :network :tracing :har
    :cdp :emulation :storage-state})

(defrecord PlaywrightBackend [^Page pw-page]
  BrowserBackend
  (backend-kind [_] :playwright)
  (backend-capabilities [_] playwright-capability-set)
  (navigate! [_ url _opts]
    (page/navigate pw-page url)
    (page/wait-for-load-state pw-page)
    {:url (page/url pw-page)})
  (current-url [_] (page/url pw-page))
  (page-title [_] (page/title pw-page))
  (page-content [_] (page/content pw-page))
  (evaluate! [_ script _args] (page/evaluate pw-page script))
  (capture-snapshot! [_ opts] (snapshot/capture-snapshot pw-page opts))
  (click! [_ selector _opts]
    (locator/click (page/locator pw-page (resolve-css selector))))
  (fill! [_ selector value _opts]
    (locator/fill (page/locator pw-page (resolve-css selector)) value))
  (clear! [_ selector _opts]
    (locator/clear (page/locator pw-page (resolve-css selector))))
  (screenshot! [_ opts]
    (if (:full-page opts)
      (page/screenshot pw-page {:full-page true})
      (page/screenshot pw-page)))
  (go-back! [_] (page/go-back pw-page))
  (go-forward! [_] (page/go-forward pw-page))
  (reload! [_] (page/reload pw-page))
  (cookies [_]
    (when-let [ctx (.context pw-page)]
      (.cookies ctx)))
  (tap! [this _selector-or-coords _opts]
    (unsupported! this "tap" "Use 'click' with the Playwright backend."))
  (swipe! [this _opts]
    (unsupported! this "swipe" "Use 'scroll' with the Playwright backend."))
  (close-backend! [_]
    ;; Playwright lifecycle is owned by the daemon/core layer, not the
    ;; backend wrapper — closing the page here would break tab handling.
    nil))

(defn playwright-backend
  "Wraps a Playwright Page in a PlaywrightBackend."
  [^Page pw-page]
  (->PlaywrightBackend pw-page))

;; =============================================================================
;; iOS application backend
;; =============================================================================

(def ios-capability-set
  "Capabilities supported by the iOS application (WebDriver) backend.
   Web-only capabilities become usable after switching to a WEBVIEW context."
  #{:native :contexts :applications :navigate :evaluate :snapshot :click
    :fill :clear :screenshot :back :forward :reload :cookies :scroll
    :element-query :element-wait :keyboard :deep-link :permissions})

(defn- wd-cookie->map
  "Normalizes a W3C WebDriver cookie object (string-keyed map, `expiry`) to
   the spel cookie shape produced by the Playwright backend
   (:name :value :domain :path :expires :httpOnly :secure :sameSite)."
  [c]
  {:name     (get c "name")
   :value    (get c "value")
   :domain   (get c "domain")
   :path     (get c "path")
   :expires  (get c "expiry")
   :httpOnly (get c "httpOnly")
   :secure   (get c "secure")
   :sameSite (get c "sameSite")})

(defn- wd-find-ref-element
  "Finds an element using context-aware selector semantics, including native
   and DOM snapshot refs."
  [ios-session ^String selector]
  (try
    (ios/find-element ios-session selector)
    (catch clojure.lang.ExceptionInfo e
      (if (and (ref-selector? selector)
            (= "no such element" (:webdriver/error (ex-data e))))
        (throw (stale-ref-error selector e))
        (throw e)))))

(def ^:private ios-navigate-attempts
  "Max navigation attempts on the iOS backend. The FIRST navigation after a
   cold simulator/WebDriverAgent start can race Safari's Web Inspector
   attachment ('no connected web application') — transient, so retry."
  3)

(def ^:private ios-navigate-retry-delay-ms 2000)

(defn- ios-debugger-not-attached?
  "True when a navigation failure looks like Safari's remote debugger not
   having a connected web application yet (cold-start race)."
  [e]
  (let [msg (str/lower-case
              (str (ex-message e) " " (:webdriver/message (ex-data e))))]
    (or (str/includes? msg "connected web application")
      (str/includes? msg "remote debugger"))))

(defn- ios-navigate-with-retry!
  "Navigates, retrying transient debugger-attachment failures."
  [wd url]
  (loop [attempt 1]
    (let [outcome (try
                    (webdriver/navigate wd url)
                    ::ok
                    (catch clojure.lang.ExceptionInfo e
                      (if (and (< attempt (long ios-navigate-attempts))
                            (ios-debugger-not-attached? e))
                        ::retry
                        (throw e))))]
      (when (= ::retry outcome)
        (Thread/sleep (long ios-navigate-retry-delay-ms))
        (recur (inc attempt))))))

(defrecord IosAppBackend [ios-session]
  BrowserBackend
  (backend-kind [_] :ios)
  (backend-capabilities [_] ios-capability-set)
  (navigate! [_ url _opts]
    (ios/with-operation ios-session
      (fn ios-backend-navigate []
        (let [wd (:webdriver ios-session)]
          (ios-navigate-with-retry! wd url)
          {:url (webdriver/url wd)}))))
  (current-url [_]
    (ios/with-operation ios-session
      (fn ios-backend-current-url []
        (webdriver/url (:webdriver ios-session)))))
  (page-title [_]
    (ios/with-operation ios-session
      (fn ios-backend-page-title []
        (webdriver/title (:webdriver ios-session)))))
  (page-content [_]
    (ios/with-operation ios-session
      (fn ios-backend-page-content []
        (webdriver/content (:webdriver ios-session)))))
  (evaluate! [_ script args]
    (ios/with-operation ios-session
      (fn ios-backend-evaluate []
        (webdriver/evaluate (:webdriver ios-session) script (or args [])))))
  (capture-snapshot! [_ opts]
    (ios/with-operation ios-session
      (fn ios-backend-snapshot []
        (let [wd (:webdriver ios-session)]
          (if (ios/native-context? ios-session)
            (ios/native-snapshot ios-session)
            ;; Frames are out of scope for the iOS backend — main-frame only.
            (snapshot/capture-webdriver wd (dissoc opts :all)))))))
  (click! [_ selector _opts]
    (ios/with-operation ios-session
      (fn ios-backend-click []
        (let [wd (:webdriver ios-session)]
          (webdriver/click-element wd (wd-find-ref-element ios-session selector))))))
  (fill! [_ selector value _opts]
    (ios/with-operation ios-session
      (fn ios-backend-fill []
        (let [wd (:webdriver ios-session)
              el (wd-find-ref-element ios-session selector)]
          (webdriver/clear-element wd el)
          (webdriver/send-keys wd el value)))))
  (clear! [_ selector _opts]
    (ios/with-operation ios-session
      (fn ios-backend-clear []
        (let [wd (:webdriver ios-session)]
          (webdriver/clear-element wd (wd-find-ref-element ios-session selector))))))
  (screenshot! [_ _opts]
    (ios/with-operation ios-session
      (fn ios-backend-screenshot []
        (webdriver/screenshot (:webdriver ios-session)))))
  (go-back! [_]
    (ios/with-operation ios-session
      (fn ios-backend-back []
        (webdriver/back (:webdriver ios-session)))))
  (go-forward! [_]
    (ios/with-operation ios-session
      (fn ios-backend-forward []
        (webdriver/forward (:webdriver ios-session)))))
  (reload! [_]
    (ios/with-operation ios-session
      (fn ios-backend-reload []
        (webdriver/reload (:webdriver ios-session)))))
  (cookies [_]
    (ios/with-operation ios-session
      (fn ios-backend-cookies []
        (mapv wd-cookie->map (webdriver/cookies (:webdriver ios-session))))))
  (tap! [_ selector-or-coords _opts]
    (ios/with-operation ios-session
      (fn ios-backend-tap []
        (let [wd      (:webdriver ios-session)
              native? (ios/native-context? ios-session)]
          (if (vector? selector-or-coords)
            (let [[x y] selector-or-coords]
              (if native?
                (webdriver/tap-screen wd x y)
                (webdriver/tap wd x y))
              {:x (long x) :y (long y)})
            (let [el     (wd-find-ref-element ios-session selector-or-coords)
                  ;; DOM centers are viewport-relative; native element rectangles
                  ;; are already absolute screen coordinates.
                  center (or (when-not native?
                               (webdriver/element-viewport-center wd el))
                           (let [rect (webdriver/element-rect wd el)]
                             {:x (long (+ (double (:x rect)) (/ (double (:width rect)) 2.0)))
                              :y (long (+ (double (:y rect)) (/ (double (:height rect)) 2.0)))}))]
              (if native?
                (webdriver/tap-screen wd (:x center) (:y center))
                (webdriver/tap wd (:x center) (:y center)))
              (assoc center :selector selector-or-coords)))))))
  (swipe! [_ opts]
    (ios/with-operation ios-session
      (fn ios-backend-swipe []
        (ios/swipe ios-session opts))))
  (close-backend! [_]
    (ios/with-operation ios-session
      (fn ios-backend-close []
        (ios/stop! ios-session)))))

(defn ios-backend
  "Wraps an ios/IosSession in an IosAppBackend."
  [ios-session]
  (->IosAppBackend ios-session))

(defn supports?
  "Returns true when the backend supports a capability keyword."
  [backend capability]
  (contains? (backend-capabilities backend) capability))
