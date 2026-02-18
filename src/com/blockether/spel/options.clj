(ns com.blockether.spel.options
  "Option map to Playwright Java options object conversion.
   
   Converts idiomatic Clojure maps to Playwright's typed option objects.
   All functions use reflection-free type hints."
  (:refer-clojure :exclude [proxy])
  (:import
   [com.microsoft.playwright BrowserType$LaunchOptions
    Browser$NewContextOptions Browser$NewPageOptions
    BrowserContext$StorageStateOptions
    Page$NavigateOptions Page$ScreenshotOptions Page$PdfOptions
    Page$WaitForSelectorOptions Page$GoBackOptions Page$GoForwardOptions
    Page$ReloadOptions Page$SetContentOptions Page$AddScriptTagOptions
    Page$AddStyleTagOptions Page$EmulateMediaOptions
    Page$WaitForFunctionOptions Page$WaitForURLOptions
    Locator$ClickOptions Locator$DblclickOptions Locator$FillOptions
    Locator$HoverOptions Locator$TypeOptions Locator$PressOptions
    Locator$CheckOptions Locator$UncheckOptions Locator$SelectOptionOptions
    Locator$SetInputFilesOptions Locator$TapOptions Locator$FocusOptions
    Locator$ScrollIntoViewIfNeededOptions Locator$ScreenshotOptions
    Locator$WaitForOptions Locator$InnerHTMLOptions Locator$InnerTextOptions
    Locator$TextContentOptions Locator$InputValueOptions
    Locator$IsVisibleOptions Locator$IsHiddenOptions
    Locator$IsEnabledOptions Locator$IsDisabledOptions
    Locator$IsEditableOptions Locator$IsCheckedOptions
    Locator$GetAttributeOptions
    Locator$DragToOptions Locator$DispatchEventOptions
    Frame$NavigateOptions Frame$WaitForSelectorOptions
    Frame$SetContentOptions Frame$AddScriptTagOptions
    Frame$AddStyleTagOptions Frame$WaitForFunctionOptions
    Frame$WaitForURLOptions
    ElementHandle$ClickOptions ElementHandle$DblclickOptions
    ElementHandle$HoverOptions ElementHandle$FillOptions
    ElementHandle$TypeOptions ElementHandle$PressOptions
    ElementHandle$CheckOptions ElementHandle$UncheckOptions
    ElementHandle$SelectOptionOptions ElementHandle$SetInputFilesOptions
    ElementHandle$TapOptions ElementHandle$ScreenshotOptions
    ElementHandle$ScrollIntoViewIfNeededOptions
    ElementHandle$WaitForElementStateOptions
    Keyboard$PressOptions Keyboard$TypeOptions
    Mouse$ClickOptions Mouse$DblclickOptions Mouse$MoveOptions
    Mouse$DownOptions Mouse$UpOptions
    Tracing$StartOptions Tracing$StopOptions
    Page$WaitForPopupOptions Page$WaitForResponseOptions
    Page$WaitForRequestOptions Page$WaitForRequestFinishedOptions]
   [com.microsoft.playwright.options
    Cookie HarContentPolicy HarMode ScreenSize ViewportSize
    WaitForSelectorState WaitUntilState LoadState MouseButton]
   [java.nio.file Path Paths]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Helper Utilities
;; =============================================================================

(defn- ->path
  "Converts a string to a java.nio.file.Path."
  ^Path [^String s]
  (Paths/get s (into-array String [])))

(defn- ->wait-until-state
  "Converts keyword to WaitUntilState enum."
  ^WaitUntilState [kw]
  (case kw
    :load WaitUntilState/LOAD
    :domcontentloaded WaitUntilState/DOMCONTENTLOADED
    :networkidle WaitUntilState/NETWORKIDLE
    :commit WaitUntilState/COMMIT
    WaitUntilState/LOAD))

(defn- ->load-state
  "Converts keyword to LoadState enum."
  ^LoadState [kw]
  (case kw
    :load LoadState/LOAD
    :domcontentloaded LoadState/DOMCONTENTLOADED
    :networkidle LoadState/NETWORKIDLE
    LoadState/LOAD))

(defn- ->mouse-button
  "Converts keyword to MouseButton enum."
  ^MouseButton [kw]
  (case kw
    :left MouseButton/LEFT
    :right MouseButton/RIGHT
    :middle MouseButton/MIDDLE
    MouseButton/LEFT))

(defn- ->wait-for-selector-state
  "Converts keyword to WaitForSelectorState enum."
  ^WaitForSelectorState [kw]
  (case kw
    :attached WaitForSelectorState/ATTACHED
    :detached WaitForSelectorState/DETACHED
    :visible WaitForSelectorState/VISIBLE
    :hidden WaitForSelectorState/HIDDEN
    WaitForSelectorState/VISIBLE))

;; =============================================================================
;; Launch Options
;; =============================================================================

(defn ->launch-options
  "Converts a map to BrowserType$LaunchOptions.
   
   Params:
   `opts` - Map with optional keys:
     :headless    - Boolean. Run in headless mode (default: true).
     :slow-mo     - Double. Slow down operations by ms.
     :timeout     - Double. Maximum time in ms to wait for browser launch.
     :channel     - String. Browser channel (e.g. \"chrome\", \"msedge\").
     :args        - Vector of strings. Additional browser args.
      :chromium-sandbox - Boolean. Enable Chromium sandbox.
      :downloads-path - String. Path to download files.
     :executable-path - String. Path to browser executable.
     :proxy       - Map with :server, :bypass, :username, :password.
   
   Returns:
   BrowserType$LaunchOptions instance."
  ^BrowserType$LaunchOptions [opts]
  (let [^BrowserType$LaunchOptions lo (BrowserType$LaunchOptions.)]
    (when (contains? opts :headless)
      (.setHeadless lo (boolean (:headless opts))))
    (when-let [v (:slow-mo opts)]
      (.setSlowMo lo (double v)))
    (when-let [v (:timeout opts)]
      (.setTimeout lo (double v)))
    (when-let [v (:channel opts)]
      (.setChannel lo ^String v))
    (when-let [v (:args opts)]
      (.setArgs lo ^java.util.List v))
    (when (contains? opts :chromium-sandbox)
      (.setChromiumSandbox lo (boolean (:chromium-sandbox opts))))
    (when-let [v (:downloads-path opts)]
      (.setDownloadsPath lo (->path v)))
    (when-let [v (:executable-path opts)]
      (.setExecutablePath lo (->path v)))
    lo))

;; =============================================================================
;; Browser Context Options
;; =============================================================================

(defn ->new-context-options
  "Converts a map to Browser$NewContextOptions.
   
   Params:
   `opts` - Map with optional keys:
     :viewport        - Map with :width :height or nil to disable.
     :screen          - Map with :width :height.
     :user-agent      - String.
     :locale          - String (e.g. \"en-US\").
     :timezone-id     - String (e.g. \"America/New_York\").
     :geolocation     - Map with :latitude :longitude :accuracy.
     :permissions     - Vector of strings.
     :color-scheme    - Keyword (:light :dark :no-preference).
     :ignore-https-errors - Boolean.
     :java-script-enabled - Boolean.
     :bypass-csp      - Boolean.
     :device-scale-factor - Double.
     :is-mobile       - Boolean.
     :has-touch       - Boolean.
     :base-url        - String.
     :storage-state   - String (path or JSON).
     :accept-downloads - Boolean.
     :offline         - Boolean.
     :extra-http-headers - Map of string->string.
     :record-video-dir - String.
     :record-video-size - Map with :width :height.
      :record-har-path  - String. Path to write HAR file.
      :record-har-mode  - Keyword. :full (default) or :minimal.
      :record-har-content - Keyword. :embed (default), :attach, or :omit.
      :record-har-omit-content - Boolean. Shorthand for omitting content.
      :record-har-url-filter - String. Glob pattern to filter URLs.
    
    Returns:
    Browser$NewContextOptions instance."
  ^Browser$NewContextOptions [opts]
  (let [^Browser$NewContextOptions co (Browser$NewContextOptions.)]
    (when (contains? opts :viewport)
      (if-let [vp (:viewport opts)]
        (.setViewportSize co (long (:width vp)) (long (:height vp)))
        (.setViewportSize co nil)))
    (when-let [s (:screen opts)]
      (.setScreenSize co (long (:width s)) (long (:height s))))
    (when-let [v (:user-agent opts)]
      (.setUserAgent co ^String v))
    (when-let [v (:locale opts)]
      (.setLocale co ^String v))
    (when-let [v (:timezone-id opts)]
      (.setTimezoneId co ^String v))
    (when-let [v (:permissions opts)]
      (.setPermissions co ^java.util.List v))
    (when (contains? opts :ignore-https-errors)
      (.setIgnoreHTTPSErrors co (boolean (:ignore-https-errors opts))))
    (when (contains? opts :java-script-enabled)
      (.setJavaScriptEnabled co (boolean (:java-script-enabled opts))))
    (when (contains? opts :bypass-csp)
      (.setBypassCSP co (boolean (:bypass-csp opts))))
    (when-let [v (:device-scale-factor opts)]
      (.setDeviceScaleFactor co (double v)))
    (when (contains? opts :is-mobile)
      (.setIsMobile co (boolean (:is-mobile opts))))
    (when (contains? opts :has-touch)
      (.setHasTouch co (boolean (:has-touch opts))))
    (when-let [v (:base-url opts)]
      (.setBaseURL co ^String v))
    (when-let [v (:storage-state opts)]
      (if (or (.contains ^String v "/") (.contains ^String v "\\") (.endsWith ^String v ".json"))
        (.setStorageStatePath co (->path v))
        (.setStorageState co ^String v)))
    (when (contains? opts :accept-downloads)
      (.setAcceptDownloads co (boolean (:accept-downloads opts))))
    (when (contains? opts :offline)
      (.setOffline co (boolean (:offline opts))))
    (when-let [v (:extra-http-headers opts)]
      (.setExtraHTTPHeaders co ^java.util.Map v))
    (when-let [v (:record-video-dir opts)]
      (.setRecordVideoDir co (->path v)))
    (when-let [v (:record-video-size opts)]
      (.setRecordVideoSize co (long (:width v)) (long (:height v))))
    (when-let [v (:record-har-path opts)]
      (.setRecordHarPath co (->path v)))
    (when-let [v (:record-har-mode opts)]
      (.setRecordHarMode co (case v
                              :full    HarMode/FULL
                              :minimal HarMode/MINIMAL)))
    (when-let [v (:record-har-content opts)]
      (.setRecordHarContent co (case v
                                 :embed  HarContentPolicy/EMBED
                                 :attach HarContentPolicy/ATTACH
                                 :omit   HarContentPolicy/OMIT)))
    (when (contains? opts :record-har-omit-content)
      (.setRecordHarOmitContent co (boolean (:record-har-omit-content opts))))
    (when-let [v (:record-har-url-filter opts)]
      (.setRecordHarUrlFilter co ^String v))
    co))

;; =============================================================================
;; Navigation Options
;; =============================================================================

(defn ->navigate-options
  "Converts a map to Page$NavigateOptions.
   
   Params:
   `opts` - Map with optional keys:
     :timeout    - Double. Maximum time in ms.
     :wait-until - Keyword. :load :domcontentloaded :networkidle :commit.
     :referer    - String. Referer header.
   
   Returns:
   Page$NavigateOptions instance."
  ^Page$NavigateOptions [opts]
  (let [^Page$NavigateOptions no (Page$NavigateOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout no (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil no (->wait-until-state v)))
    (when-let [v (:referer opts)]
      (.setReferer no ^String v))
    no))

;; =============================================================================
;; Screenshot Options
;; =============================================================================

(defn ->screenshot-options
  "Converts a map to Page$ScreenshotOptions.
   
   Params:
   `opts` - Map with optional keys:
     :path       - String. File path to save screenshot.
     :full-page  - Boolean. Full scrollable page (default: false).
     :clip       - Map with :x :y :width :height.
     :type       - Keyword. :png or :jpeg.
     :quality    - Long. JPEG quality 0-100.
     :timeout    - Double. Maximum time in ms.
     :omit-background - Boolean.
   
   Returns:
   Page$ScreenshotOptions instance."
  ^Page$ScreenshotOptions [opts]
  (let [^Page$ScreenshotOptions so (Page$ScreenshotOptions.)]
    (when-let [v (:path opts)]
      (.setPath so (->path v)))
    (when (contains? opts :full-page)
      (.setFullPage so (boolean (:full-page opts))))
    (when-let [v (:type opts)]
      (.setType so (case v
                     :png com.microsoft.playwright.options.ScreenshotType/PNG
                     :jpeg com.microsoft.playwright.options.ScreenshotType/JPEG
                     com.microsoft.playwright.options.ScreenshotType/PNG)))
    (when-let [v (:quality opts)]
      (.setQuality so (long v)))
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when (contains? opts :omit-background)
      (.setOmitBackground so (boolean (:omit-background opts))))
    so))

;; =============================================================================
;; Click / Interaction Options
;; =============================================================================

(defn ->click-options
  "Converts a map to Locator$ClickOptions.
   
   Params:
   `opts` - Map with optional keys:
     :button     - Keyword. :left :right :middle.
     :click-count - Long. Number of clicks.
     :delay      - Double. Delay between mousedown/mouseup in ms.
     :force      - Boolean. Bypass actionability checks.
     :modifiers  - Vector of keywords. :alt :control :meta :shift.
     :no-wait-after - Boolean.
     :position   - Map with :x :y.
     :timeout    - Double. Maximum time in ms.
     :trial      - Boolean. Perform actionability checks only.
   
   Returns:
   Locator$ClickOptions instance."
  ^Locator$ClickOptions [opts]
  (let [^Locator$ClickOptions co (Locator$ClickOptions.)]
    (when-let [v (:button opts)]
      (.setButton co (->mouse-button v)))
    (when-let [v (:click-count opts)]
      (.setClickCount co (long v)))
    (when-let [v (:delay opts)]
      (.setDelay co (double v)))
    (when (contains? opts :force)
      (.setForce co (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout co (double v)))
    (when (contains? opts :trial)
      (.setTrial co (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter co (boolean (:no-wait-after opts))))
    (when-let [v (:position opts)]
      (.setPosition co (double (:x v)) (double (:y v))))
    co))

(defn ->fill-options
  "Converts a map to Locator$FillOptions.
   
   Params:
   `opts` - Map with optional keys:
     :force         - Boolean.
     :no-wait-after - Boolean.
     :timeout       - Double.
   
   Returns:
   Locator$FillOptions instance."
  ^Locator$FillOptions [opts]
  (let [^Locator$FillOptions fo (Locator$FillOptions.)]
    (when (contains? opts :force)
      (.setForce fo (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter fo (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout fo (double v)))
    fo))

(defn ->hover-options
  "Converts a map to Locator$HoverOptions."
  ^Locator$HoverOptions [opts]
  (let [^Locator$HoverOptions ho (Locator$HoverOptions.)]
    (when (contains? opts :force)
      (.setForce ho (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout ho (double v)))
    (when (contains? opts :trial)
      (.setTrial ho (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition ho (double (:x v)) (double (:y v))))
    ho))

(defn ->type-options
  "Converts a map to Locator$TypeOptions."
  ^Locator$TypeOptions [opts]
  (let [^Locator$TypeOptions to (Locator$TypeOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay to (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter to (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout to (double v)))
    to))

(defn ->press-options
  "Converts a map to Locator$PressOptions."
  ^Locator$PressOptions [opts]
  (let [^Locator$PressOptions po (Locator$PressOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay po (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter po (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout po (double v)))
    po))

(defn ->check-options
  "Converts a map to Locator$CheckOptions."
  ^Locator$CheckOptions [opts]
  (let [^Locator$CheckOptions co (Locator$CheckOptions.)]
    (when (contains? opts :force)
      (.setForce co (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter co (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout co (double v)))
    (when (contains? opts :trial)
      (.setTrial co (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition co (double (:x v)) (double (:y v))))
    co))

(defn ->uncheck-options
  "Converts a map to Locator$UncheckOptions."
  ^Locator$UncheckOptions [opts]
  (let [^Locator$UncheckOptions uo (Locator$UncheckOptions.)]
    (when (contains? opts :force)
      (.setForce uo (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter uo (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout uo (double v)))
    (when (contains? opts :trial)
      (.setTrial uo (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition uo (double (:x v)) (double (:y v))))
    uo))

(defn ->dblclick-options
  "Converts a map to Locator$DblclickOptions."
  ^Locator$DblclickOptions [opts]
  (let [^Locator$DblclickOptions dco (Locator$DblclickOptions.)]
    (when-let [v (:button opts)]
      (.setButton dco (->mouse-button v)))
    (when-let [v (:delay opts)]
      (.setDelay dco (double v)))
    (when (contains? opts :force)
      (.setForce dco (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout dco (double v)))
    (when (contains? opts :trial)
      (.setTrial dco (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter dco (boolean (:no-wait-after opts))))
    (when-let [v (:position opts)]
      (.setPosition dco (double (:x v)) (double (:y v))))
    dco))

(defn ->locator-screenshot-options
  "Converts a map to Locator$ScreenshotOptions."
  ^Locator$ScreenshotOptions [opts]
  (let [^Locator$ScreenshotOptions so (Locator$ScreenshotOptions.)]
    (when-let [v (:path opts)]
      (.setPath so (->path v)))
    (when-let [v (:type opts)]
      (.setType so (case v
                     :png com.microsoft.playwright.options.ScreenshotType/PNG
                     :jpeg com.microsoft.playwright.options.ScreenshotType/JPEG
                     com.microsoft.playwright.options.ScreenshotType/PNG)))
    (when-let [v (:quality opts)]
      (.setQuality so (long v)))
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when (contains? opts :omit-background)
      (.setOmitBackground so (boolean (:omit-background opts))))
    so))

(defn ->wait-for-options
  "Converts a map to Locator$WaitForOptions."
  ^Locator$WaitForOptions [opts]
  (let [^Locator$WaitForOptions wo (Locator$WaitForOptions.)]
    (when-let [v (:state opts)]
      (.setState wo (->wait-for-selector-state v)))
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

;; =============================================================================
;; Keyboard Options
;; =============================================================================

(defn ->keyboard-press-options
  "Converts a map to Keyboard$PressOptions."
  ^Keyboard$PressOptions [opts]
  (let [^Keyboard$PressOptions po (Keyboard$PressOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay po (double v)))
    po))

(defn ->keyboard-type-options
  "Converts a map to Keyboard$TypeOptions."
  ^Keyboard$TypeOptions [opts]
  (let [^Keyboard$TypeOptions to (Keyboard$TypeOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay to (double v)))
    to))

;; =============================================================================
;; Mouse Options
;; =============================================================================

(defn ->mouse-click-options
  "Converts a map to Mouse$ClickOptions."
  ^Mouse$ClickOptions [opts]
  (let [^Mouse$ClickOptions co (Mouse$ClickOptions.)]
    (when-let [v (:button opts)]
      (.setButton co (->mouse-button v)))
    (when-let [v (:click-count opts)]
      (.setClickCount co (long v)))
    (when-let [v (:delay opts)]
      (.setDelay co (double v)))
    co))

(defn ->mouse-dblclick-options
  "Converts a map to Mouse$DblclickOptions."
  ^Mouse$DblclickOptions [opts]
  (let [^Mouse$DblclickOptions dco (Mouse$DblclickOptions.)]
    (when-let [v (:button opts)]
      (.setButton dco (->mouse-button v)))
    (when-let [v (:delay opts)]
      (.setDelay dco (double v)))
    dco))

(defn ->mouse-move-options
  "Converts a map to Mouse$MoveOptions."
  ^Mouse$MoveOptions [opts]
  (let [^Mouse$MoveOptions mo (Mouse$MoveOptions.)]
    (when-let [v (:steps opts)]
      (.setSteps mo (long v)))
    mo))

;; =============================================================================
;; Tracing Options
;; =============================================================================

(defn ->tracing-start-options
  "Converts a map to Tracing$StartOptions."
  ^Tracing$StartOptions [opts]
  (let [^Tracing$StartOptions so (Tracing$StartOptions.)]
    (when-let [v (:name opts)]
      (.setName so ^String v))
    (when (contains? opts :screenshots)
      (.setScreenshots so (boolean (:screenshots opts))))
    (when (contains? opts :snapshots)
      (.setSnapshots so (boolean (:snapshots opts))))
    (when (contains? opts :sources)
      (.setSources so (boolean (:sources opts))))
    so))

(defn ->tracing-stop-options
  "Converts a map to Tracing$StopOptions."
  ^Tracing$StopOptions [opts]
  (let [^Tracing$StopOptions so (Tracing$StopOptions.)]
    (when-let [v (:path opts)]
      (.setPath so (->path v)))
    so))

;; =============================================================================
;; Page Wait Options
;; =============================================================================

(defn ->wait-for-selector-options
  "Converts a map to Page$WaitForSelectorOptions."
  ^Page$WaitForSelectorOptions [opts]
  (let [^Page$WaitForSelectorOptions wo (Page$WaitForSelectorOptions.)]
    (when-let [v (:state opts)]
      (.setState wo (->wait-for-selector-state v)))
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when (contains? opts :strict)
      (.setStrict wo (boolean (:strict opts))))
    wo))

(defn ->set-content-options
  "Converts a map to Page$SetContentOptions."
  ^Page$SetContentOptions [opts]
  (let [^Page$SetContentOptions so (Page$SetContentOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil so (->wait-until-state v)))
    so))

(defn ->reload-options
  "Converts a map to Page$ReloadOptions."
  ^Page$ReloadOptions [opts]
  (let [^Page$ReloadOptions ro (Page$ReloadOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout ro (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil ro (->wait-until-state v)))
    ro))

(defn ->go-back-options
  "Converts a map to Page$GoBackOptions."
  ^Page$GoBackOptions [opts]
  (let [^Page$GoBackOptions gbo (Page$GoBackOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout gbo (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil gbo (->wait-until-state v)))
    gbo))

(defn ->go-forward-options
  "Converts a map to Page$GoForwardOptions."
  ^Page$GoForwardOptions [opts]
  (let [^Page$GoForwardOptions gfo (Page$GoForwardOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout gfo (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil gfo (->wait-until-state v)))
    gfo))

(defn ->pdf-options
  "Converts a map to Page$PdfOptions."
  ^Page$PdfOptions [opts]
  (let [^Page$PdfOptions po (Page$PdfOptions.)]
    (when-let [v (:path opts)]
      (.setPath po (->path v)))
    (when-let [v (:scale opts)]
      (.setScale po (double v)))
    (when (contains? opts :display-header-footer)
      (.setDisplayHeaderFooter po (boolean (:display-header-footer opts))))
    (when-let [v (:header-template opts)]
      (.setHeaderTemplate po ^String v))
    (when-let [v (:footer-template opts)]
      (.setFooterTemplate po ^String v))
    (when (contains? opts :print-background)
      (.setPrintBackground po (boolean (:print-background opts))))
    (when (contains? opts :landscape)
      (.setLandscape po (boolean (:landscape opts))))
    (when-let [v (:page-ranges opts)]
      (.setPageRanges po ^String v))
    (when-let [v (:format opts)]
      (.setFormat po ^String v))
    (when-let [v (:width opts)]
      (.setWidth po ^String v))
    (when-let [v (:height opts)]
      (.setHeight po ^String v))
    (when (contains? opts :prefer-css-page-size)
      (.setPreferCSSPageSize po (boolean (:prefer-css-page-size opts))))
    po))

;; =============================================================================
;; Emulate Media Options
;; =============================================================================

(defn ->emulate-media-options
  "Converts a map to Page$EmulateMediaOptions."
  ^Page$EmulateMediaOptions [opts]
  (let [^Page$EmulateMediaOptions eo (Page$EmulateMediaOptions.)]
    (when-let [v (:media opts)]
      (.setMedia eo (case v
                      :screen com.microsoft.playwright.options.Media/SCREEN
                      :print com.microsoft.playwright.options.Media/PRINT
                      nil)))
    (when-let [v (:color-scheme opts)]
      (.setColorScheme eo (case v
                            :light com.microsoft.playwright.options.ColorScheme/LIGHT
                            :dark com.microsoft.playwright.options.ColorScheme/DARK
                            :no-preference com.microsoft.playwright.options.ColorScheme/NO_PREFERENCE
                            nil)))
    eo))

;; =============================================================================
;; Locator Additional Options
;; =============================================================================

(defn ->select-option-options
  "Converts a map to Locator$SelectOptionOptions."
  ^Locator$SelectOptionOptions [opts]
  (let [^Locator$SelectOptionOptions so (Locator$SelectOptionOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when (contains? opts :force)
      (.setForce so (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter so (boolean (:no-wait-after opts))))
    so))

(defn ->set-input-files-options
  "Converts a map to Locator$SetInputFilesOptions."
  ^Locator$SetInputFilesOptions [opts]
  (let [^Locator$SetInputFilesOptions sif (Locator$SetInputFilesOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout sif (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter sif (boolean (:no-wait-after opts))))
    sif))

(defn ->tap-options
  "Converts a map to Locator$TapOptions."
  ^Locator$TapOptions [opts]
  (let [^Locator$TapOptions to (Locator$TapOptions.)]
    (when (contains? opts :force)
      (.setForce to (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout to (double v)))
    (when (contains? opts :trial)
      (.setTrial to (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter to (boolean (:no-wait-after opts))))
    (when-let [v (:position opts)]
      (.setPosition to (double (:x v)) (double (:y v))))
    to))

(defn ->focus-options
  "Converts a map to Locator$FocusOptions."
  ^Locator$FocusOptions [opts]
  (let [^Locator$FocusOptions fo (Locator$FocusOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout fo (double v)))
    fo))

(defn ->scroll-into-view-options
  "Converts a map to Locator$ScrollIntoViewIfNeededOptions."
  ^Locator$ScrollIntoViewIfNeededOptions [opts]
  (let [^Locator$ScrollIntoViewIfNeededOptions sio (Locator$ScrollIntoViewIfNeededOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout sio (double v)))
    sio))

(defn ->inner-html-options
  "Converts a map to Locator$InnerHTMLOptions."
  ^Locator$InnerHTMLOptions [opts]
  (let [^Locator$InnerHTMLOptions io (Locator$InnerHTMLOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->inner-text-options
  "Converts a map to Locator$InnerTextOptions."
  ^Locator$InnerTextOptions [opts]
  (let [^Locator$InnerTextOptions io (Locator$InnerTextOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->text-content-options
  "Converts a map to Locator$TextContentOptions."
  ^Locator$TextContentOptions [opts]
  (let [^Locator$TextContentOptions to (Locator$TextContentOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout to (double v)))
    to))

(defn ->input-value-options
  "Converts a map to Locator$InputValueOptions."
  ^Locator$InputValueOptions [opts]
  (let [^Locator$InputValueOptions io (Locator$InputValueOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-visible-options
  "Converts a map to Locator$IsVisibleOptions."
  ^Locator$IsVisibleOptions [opts]
  (let [^Locator$IsVisibleOptions io (Locator$IsVisibleOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-hidden-options
  "Converts a map to Locator$IsHiddenOptions."
  ^Locator$IsHiddenOptions [opts]
  (let [^Locator$IsHiddenOptions io (Locator$IsHiddenOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-enabled-options
  "Converts a map to Locator$IsEnabledOptions."
  ^Locator$IsEnabledOptions [opts]
  (let [^Locator$IsEnabledOptions io (Locator$IsEnabledOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-disabled-options
  "Converts a map to Locator$IsDisabledOptions."
  ^Locator$IsDisabledOptions [opts]
  (let [^Locator$IsDisabledOptions io (Locator$IsDisabledOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-editable-options
  "Converts a map to Locator$IsEditableOptions."
  ^Locator$IsEditableOptions [opts]
  (let [^Locator$IsEditableOptions io (Locator$IsEditableOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->is-checked-options
  "Converts a map to Locator$IsCheckedOptions."
  ^Locator$IsCheckedOptions [opts]
  (let [^Locator$IsCheckedOptions io (Locator$IsCheckedOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout io (double v)))
    io))

(defn ->get-attribute-options
  "Converts a map to Locator$GetAttributeOptions."
  ^Locator$GetAttributeOptions [opts]
  (let [^Locator$GetAttributeOptions go (Locator$GetAttributeOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout go (double v)))
    go))

(defn ->drag-to-options
  "Converts a map to Locator$DragToOptions."
  ^Locator$DragToOptions [opts]
  (let [^Locator$DragToOptions d (Locator$DragToOptions.)]
    (when (contains? opts :force)
      (.setForce d (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout d (double v)))
    (when (contains? opts :trial)
      (.setTrial d (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter d (boolean (:no-wait-after opts))))
    (when-let [v (:source-position opts)]
      (.setSourcePosition d (double (:x v)) (double (:y v))))
    (when-let [v (:target-position opts)]
      (.setTargetPosition d (double (:x v)) (double (:y v))))
    d))

(defn ->dispatch-event-options
  "Converts a map to Locator$DispatchEventOptions."
  ^Locator$DispatchEventOptions [opts]
  (let [^Locator$DispatchEventOptions de (Locator$DispatchEventOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout de (double v)))
    de))

;; =============================================================================
;; Frame Options
;; =============================================================================

(defn ->frame-navigate-options
  "Converts a map to Frame$NavigateOptions."
  ^Frame$NavigateOptions [opts]
  (let [^Frame$NavigateOptions no (Frame$NavigateOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout no (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil no (->wait-until-state v)))
    no))

(defn ->frame-wait-for-selector-options
  "Converts a map to Frame$WaitForSelectorOptions."
  ^Frame$WaitForSelectorOptions [opts]
  (let [^Frame$WaitForSelectorOptions wo (Frame$WaitForSelectorOptions.)]
    (when-let [v (:state opts)]
      (.setState wo (->wait-for-selector-state v)))
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when (contains? opts :strict)
      (.setStrict wo (boolean (:strict opts))))
    wo))

(defn ->frame-set-content-options
  "Converts a map to Frame$SetContentOptions."
  ^Frame$SetContentOptions [opts]
  (let [^Frame$SetContentOptions so (Frame$SetContentOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil so (->wait-until-state v)))
    so))

(defn ->frame-add-script-tag-options
  "Converts a map to Frame$AddScriptTagOptions."
  ^Frame$AddScriptTagOptions [opts]
  (let [^Frame$AddScriptTagOptions as (Frame$AddScriptTagOptions.)]
    (when-let [v (:url opts)]
      (.setUrl as ^String v))
    (when-let [v (:path opts)]
      (.setPath as (->path v)))
    (when-let [v (:content opts)]
      (.setContent as ^String v))
    (when-let [v (:type opts)]
      (.setType as ^String v))
    as))

(defn ->frame-add-style-tag-options
  "Converts a map to Frame$AddStyleTagOptions."
  ^Frame$AddStyleTagOptions [opts]
  (let [^Frame$AddStyleTagOptions as (Frame$AddStyleTagOptions.)]
    (when-let [v (:url opts)]
      (.setUrl as ^String v))
    (when-let [v (:path opts)]
      (.setPath as (->path v)))
    (when-let [v (:content opts)]
      (.setContent as ^String v))
    as))

(defn ->frame-wait-for-function-options
  "Converts a map to Frame$WaitForFunctionOptions."
  ^Frame$WaitForFunctionOptions [opts]
  (let [^Frame$WaitForFunctionOptions wo (Frame$WaitForFunctionOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when-let [v (:polling opts)]
      (.setPollingInterval wo (double v)))
    wo))

(defn ->frame-wait-for-url-options
  "Converts a map to Frame$WaitForURLOptions."
  ^Frame$WaitForURLOptions [opts]
  (let [^Frame$WaitForURLOptions wo (Frame$WaitForURLOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil wo (->wait-until-state v)))
    wo))

;; =============================================================================
;; ElementHandle Options
;; =============================================================================

(defn- ->element-state
  "Converts keyword to ElementState enum for WaitForElementStateOptions."
  ^com.microsoft.playwright.options.ElementState [kw]
  (case kw
    :visible com.microsoft.playwright.options.ElementState/VISIBLE
    :hidden com.microsoft.playwright.options.ElementState/HIDDEN
    :stable com.microsoft.playwright.options.ElementState/STABLE
    :enabled com.microsoft.playwright.options.ElementState/ENABLED
    :disabled com.microsoft.playwright.options.ElementState/DISABLED
    :editable com.microsoft.playwright.options.ElementState/EDITABLE
    com.microsoft.playwright.options.ElementState/VISIBLE))

(defn ->eh-click-options
  "Converts a map to ElementHandle$ClickOptions."
  ^ElementHandle$ClickOptions [opts]
  (let [^ElementHandle$ClickOptions co (ElementHandle$ClickOptions.)]
    (when-let [v (:button opts)]
      (.setButton co (->mouse-button v)))
    (when-let [v (:click-count opts)]
      (.setClickCount co (long v)))
    (when-let [v (:delay opts)]
      (.setDelay co (double v)))
    (when (contains? opts :force)
      (.setForce co (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout co (double v)))
    (when (contains? opts :trial)
      (.setTrial co (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter co (boolean (:no-wait-after opts))))
    (when-let [v (:position opts)]
      (.setPosition co (double (:x v)) (double (:y v))))
    co))

(defn ->eh-dblclick-options
  "Converts a map to ElementHandle$DblclickOptions."
  ^ElementHandle$DblclickOptions [opts]
  (let [^ElementHandle$DblclickOptions dco (ElementHandle$DblclickOptions.)]
    (when-let [v (:button opts)]
      (.setButton dco (->mouse-button v)))
    (when-let [v (:delay opts)]
      (.setDelay dco (double v)))
    (when (contains? opts :force)
      (.setForce dco (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout dco (double v)))
    (when (contains? opts :trial)
      (.setTrial dco (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter dco (boolean (:no-wait-after opts))))
    (when-let [v (:position opts)]
      (.setPosition dco (double (:x v)) (double (:y v))))
    dco))

(defn ->eh-hover-options
  "Converts a map to ElementHandle$HoverOptions."
  ^ElementHandle$HoverOptions [opts]
  (let [^ElementHandle$HoverOptions ho (ElementHandle$HoverOptions.)]
    (when (contains? opts :force)
      (.setForce ho (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout ho (double v)))
    (when (contains? opts :trial)
      (.setTrial ho (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition ho (double (:x v)) (double (:y v))))
    ho))

(defn ->eh-fill-options
  "Converts a map to ElementHandle$FillOptions."
  ^ElementHandle$FillOptions [opts]
  (let [^ElementHandle$FillOptions fo (ElementHandle$FillOptions.)]
    (when (contains? opts :force)
      (.setForce fo (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter fo (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout fo (double v)))
    fo))

(defn ->eh-type-options
  "Converts a map to ElementHandle$TypeOptions."
  ^ElementHandle$TypeOptions [opts]
  (let [^ElementHandle$TypeOptions to (ElementHandle$TypeOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay to (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter to (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout to (double v)))
    to))

(defn ->eh-press-options
  "Converts a map to ElementHandle$PressOptions."
  ^ElementHandle$PressOptions [opts]
  (let [^ElementHandle$PressOptions po (ElementHandle$PressOptions.)]
    (when-let [v (:delay opts)]
      (.setDelay po (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter po (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout po (double v)))
    po))

(defn ->eh-check-options
  "Converts a map to ElementHandle$CheckOptions."
  ^ElementHandle$CheckOptions [opts]
  (let [^ElementHandle$CheckOptions co (ElementHandle$CheckOptions.)]
    (when (contains? opts :force)
      (.setForce co (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter co (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout co (double v)))
    (when (contains? opts :trial)
      (.setTrial co (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition co (double (:x v)) (double (:y v))))
    co))

(defn ->eh-uncheck-options
  "Converts a map to ElementHandle$UncheckOptions."
  ^ElementHandle$UncheckOptions [opts]
  (let [^ElementHandle$UncheckOptions uo (ElementHandle$UncheckOptions.)]
    (when (contains? opts :force)
      (.setForce uo (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter uo (boolean (:no-wait-after opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout uo (double v)))
    (when (contains? opts :trial)
      (.setTrial uo (boolean (:trial opts))))
    (when-let [v (:position opts)]
      (.setPosition uo (double (:x v)) (double (:y v))))
    uo))

(defn ->eh-select-option-options
  "Converts a map to ElementHandle$SelectOptionOptions."
  ^ElementHandle$SelectOptionOptions [opts]
  (let [^ElementHandle$SelectOptionOptions so (ElementHandle$SelectOptionOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when (contains? opts :force)
      (.setForce so (boolean (:force opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter so (boolean (:no-wait-after opts))))
    so))

(defn ->eh-set-input-files-options
  "Converts a map to ElementHandle$SetInputFilesOptions."
  ^ElementHandle$SetInputFilesOptions [opts]
  (let [^ElementHandle$SetInputFilesOptions sif (ElementHandle$SetInputFilesOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout sif (double v)))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter sif (boolean (:no-wait-after opts))))
    sif))

(defn ->eh-tap-options
  "Converts a map to ElementHandle$TapOptions."
  ^ElementHandle$TapOptions [opts]
  (let [^ElementHandle$TapOptions to (ElementHandle$TapOptions.)]
    (when (contains? opts :force)
      (.setForce to (boolean (:force opts))))
    (when-let [v (:timeout opts)]
      (.setTimeout to (double v)))
    (when (contains? opts :trial)
      (.setTrial to (boolean (:trial opts))))
    (when (contains? opts :no-wait-after)
      (.setNoWaitAfter to (boolean (:no-wait-after opts))))
    to))

(defn ->eh-screenshot-options
  "Converts a map to ElementHandle$ScreenshotOptions."
  ^ElementHandle$ScreenshotOptions [opts]
  (let [^ElementHandle$ScreenshotOptions so (ElementHandle$ScreenshotOptions.)]
    (when-let [v (:path opts)]
      (.setPath so (->path v)))
    (when-let [v (:type opts)]
      (.setType so (case v
                     :png com.microsoft.playwright.options.ScreenshotType/PNG
                     :jpeg com.microsoft.playwright.options.ScreenshotType/JPEG
                     com.microsoft.playwright.options.ScreenshotType/PNG)))
    (when-let [v (:quality opts)]
      (.setQuality so (long v)))
    (when-let [v (:timeout opts)]
      (.setTimeout so (double v)))
    (when (contains? opts :omit-background)
      (.setOmitBackground so (boolean (:omit-background opts))))
    so))

(defn ->eh-scroll-into-view-options
  "Converts a map to ElementHandle$ScrollIntoViewIfNeededOptions."
  ^ElementHandle$ScrollIntoViewIfNeededOptions [opts]
  (let [^ElementHandle$ScrollIntoViewIfNeededOptions sio (ElementHandle$ScrollIntoViewIfNeededOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout sio (double v)))
    sio))

(defn ->eh-wait-for-element-state-options
  "Converts a map to ElementHandle$WaitForElementStateOptions.
   Note: state is passed as first arg to ElementHandle.waitForElementState(),
   not set on the options object. This builder only handles timeout."
  ^ElementHandle$WaitForElementStateOptions [opts]
  (let [^ElementHandle$WaitForElementStateOptions wo (ElementHandle$WaitForElementStateOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

;; =============================================================================
;; Mouse Additional Options
;; =============================================================================

(defn ->mouse-down-options
  "Converts a map to Mouse$DownOptions."
  ^Mouse$DownOptions [opts]
  (let [^Mouse$DownOptions mdo (Mouse$DownOptions.)]
    (when-let [v (:button opts)]
      (.setButton mdo (->mouse-button v)))
    (when-let [v (:click-count opts)]
      (.setClickCount mdo (long v)))
    mdo))

(defn ->mouse-up-options
  "Converts a map to Mouse$UpOptions."
  ^Mouse$UpOptions [opts]
  (let [^Mouse$UpOptions muo (Mouse$UpOptions.)]
    (when-let [v (:button opts)]
      (.setButton muo (->mouse-button v)))
    (when-let [v (:click-count opts)]
      (.setClickCount muo (long v)))
    muo))

;; =============================================================================
;; Page Wait Options
;; =============================================================================

(defn ->wait-for-popup-options
  "Converts a map to Page$WaitForPopupOptions."
  ^Page$WaitForPopupOptions [opts]
  (let [^Page$WaitForPopupOptions wo (Page$WaitForPopupOptions.)]
    (when-let [v (:predicate opts)]
      (.setPredicate wo ^java.util.function.Predicate
        (reify java.util.function.Predicate
          (test [_ p] (boolean (v p))))))
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

(defn ->wait-for-response-options
  "Converts a map to Page$WaitForResponseOptions."
  ^Page$WaitForResponseOptions [opts]
  (let [^Page$WaitForResponseOptions wo (Page$WaitForResponseOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

(defn ->wait-for-request-options
  "Converts a map to Page$WaitForRequestOptions."
  ^Page$WaitForRequestOptions [opts]
  (let [^Page$WaitForRequestOptions wo (Page$WaitForRequestOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

(defn ->wait-for-request-finished-options
  "Converts a map to Page$WaitForRequestFinishedOptions."
  ^Page$WaitForRequestFinishedOptions [opts]
  (let [^Page$WaitForRequestFinishedOptions wo (Page$WaitForRequestFinishedOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    wo))

(defn ->page-wait-for-function-options
  "Converts a map to Page$WaitForFunctionOptions."
  ^Page$WaitForFunctionOptions [opts]
  (let [^Page$WaitForFunctionOptions wo (Page$WaitForFunctionOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when-let [v (:polling opts)]
      (.setPollingInterval wo (double v)))
    wo))

(defn ->page-wait-for-url-options
  "Converts a map to Page$WaitForURLOptions."
  ^Page$WaitForURLOptions [opts]
  (let [^Page$WaitForURLOptions wo (Page$WaitForURLOptions.)]
    (when-let [v (:timeout opts)]
      (.setTimeout wo (double v)))
    (when-let [v (:wait-until opts)]
      (.setWaitUntil wo (->wait-until-state v)))
    wo))

(defn ->page-add-script-tag-options
  "Converts a map to Page$AddScriptTagOptions."
  ^Page$AddScriptTagOptions [opts]
  (let [^Page$AddScriptTagOptions as (Page$AddScriptTagOptions.)]
    (when-let [v (:url opts)]
      (.setUrl as ^String v))
    (when-let [v (:path opts)]
      (.setPath as (->path v)))
    (when-let [v (:content opts)]
      (.setContent as ^String v))
    (when-let [v (:type opts)]
      (.setType as ^String v))
    as))

(defn ->page-add-style-tag-options
  "Converts a map to Page$AddStyleTagOptions."
  ^Page$AddStyleTagOptions [opts]
  (let [^Page$AddStyleTagOptions as (Page$AddStyleTagOptions.)]
    (when-let [v (:url opts)]
      (.setUrl as ^String v))
    (when-let [v (:path opts)]
      (.setPath as (->path v)))
    (when-let [v (:content opts)]
      (.setContent as ^String v))
    as))

;; =============================================================================
;; Browser Options
;; =============================================================================

(defn ->new-page-options
  "Converts a map to Browser$NewPageOptions.
   
   Same shape as ->new-context-options (extends the same base)."
  ^Browser$NewPageOptions [opts]
  (let [^Browser$NewPageOptions npo (Browser$NewPageOptions.)]
    (when (contains? opts :viewport)
      (if-let [vp (:viewport opts)]
        (.setViewportSize npo (long (:width vp)) (long (:height vp)))
        (.setViewportSize npo nil)))
    (when-let [s (:screen opts)]
      (.setScreenSize npo (long (:width s)) (long (:height s))))
    (when-let [v (:user-agent opts)]
      (.setUserAgent npo ^String v))
    (when-let [v (:locale opts)]
      (.setLocale npo ^String v))
    (when-let [v (:timezone-id opts)]
      (.setTimezoneId npo ^String v))
    (when-let [v (:permissions opts)]
      (.setPermissions npo ^java.util.List v))
    (when (contains? opts :ignore-https-errors)
      (.setIgnoreHTTPSErrors npo (boolean (:ignore-https-errors opts))))
    (when (contains? opts :java-script-enabled)
      (.setJavaScriptEnabled npo (boolean (:java-script-enabled opts))))
    (when (contains? opts :bypass-csp)
      (.setBypassCSP npo (boolean (:bypass-csp opts))))
    (when-let [v (:device-scale-factor opts)]
      (.setDeviceScaleFactor npo (double v)))
    (when (contains? opts :is-mobile)
      (.setIsMobile npo (boolean (:is-mobile opts))))
    (when (contains? opts :has-touch)
      (.setHasTouch npo (boolean (:has-touch opts))))
    (when-let [v (:base-url opts)]
      (.setBaseURL npo ^String v))
    (when-let [v (:storage-state opts)]
      (if (or (.contains ^String v "/") (.contains ^String v "\\") (.endsWith ^String v ".json"))
        (.setStorageStatePath npo (->path v))
        (.setStorageState npo ^String v)))
    (when (contains? opts :accept-downloads)
      (.setAcceptDownloads npo (boolean (:accept-downloads opts))))
    (when (contains? opts :offline)
      (.setOffline npo (boolean (:offline opts))))
    (when-let [v (:extra-http-headers opts)]
      (.setExtraHTTPHeaders npo ^java.util.Map v))
    (when-let [v (:record-video-dir opts)]
      (.setRecordVideoDir npo (->path v)))
    (when-let [v (:record-video-size opts)]
      (.setRecordVideoSize npo (long (:width v)) (long (:height v))))
    (when-let [v (:record-har-path opts)]
      (.setRecordHarPath npo (->path v)))
    npo))

;; =============================================================================
;; BrowserContext Options
;; =============================================================================

(defn ->storage-state-options
  "Converts a map to BrowserContext$StorageStateOptions."
  ^BrowserContext$StorageStateOptions [opts]
  (let [^BrowserContext$StorageStateOptions so (BrowserContext$StorageStateOptions.)]
    (when-let [v (:path opts)]
      (.setPath so (->path v)))
    so))

;; =============================================================================
;; Data Options (Cookie, ScreenSize, ViewportSize)
;; =============================================================================

(defn ->cookie
  "Creates a Cookie instance from a map.
   
   Params:
   `opts` - Map with optional keys:
     :name      - String. Cookie name.
     :value     - String. Cookie value.
     :domain    - String.
     :path      - String.
     :expires   - Double. Unix timestamp.
     :http-only - Boolean.
     :secure    - Boolean.
     :same-site - Keyword (:strict :lax :none).
   
   Returns:
   Cookie instance."
  ^Cookie [opts]
  (let [^Cookie c (Cookie. ^String (:name opts) ^String (:value opts))]
    (when-let [v (:url opts)]
      (.setUrl c ^String v))
    (when-let [v (:domain opts)]
      (.setDomain c ^String v))
    (when-let [v (:path opts)]
      (.setPath c ^String v))
    (when-let [v (:expires opts)]
      (.setExpires c (double v)))
    (when (contains? opts :http-only)
      (.setHttpOnly c (boolean (:http-only opts))))
    (when (contains? opts :secure)
      (.setSecure c (boolean (:secure opts))))
    (when-let [v (:same-site opts)]
      (.setSameSite c (case v
                        :strict com.microsoft.playwright.options.SameSiteAttribute/STRICT
                        :lax com.microsoft.playwright.options.SameSiteAttribute/LAX
                        :none com.microsoft.playwright.options.SameSiteAttribute/NONE
                        nil)))
    c))

(defn ->screen-size
  "Creates a ScreenSize instance.
   
   Params:
   `opts` - Map with :width and :height.
   
   Returns:
   ScreenSize instance."
  ^ScreenSize [opts]
  (ScreenSize. (long (:width opts)) (long (:height opts))))

(defn ->viewport-size
  "Creates a ViewportSize instance.
   
   Params:
   `opts` - Map with :width and :height.
   
   Returns:
   ViewportSize instance."
  ^ViewportSize [opts]
  (ViewportSize. (long (:width opts)) (long (:height opts))))
