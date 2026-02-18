(ns com.blockether.spel.data
  "Datafy/nav extensions for Playwright Java objects.

   Require this namespace to enable `clojure.core.protocols/Datafiable`
   for key Playwright classes. After requiring, `(clojure.datafy/datafy obj)`
   returns a Clojure map representation of the object.

   Datafied classes:
   - Page, Browser, BrowserContext, BrowserType
   - Request, Response, APIResponse
   - ConsoleMessage, Download, WebError, WebSocketFrame, Worker
   - ElementHandle, Locator (basic info)
   - PlaywrightException, TimeoutError"
  (:require
   [clojure.core.protocols :as cp])
  (:import
   [com.microsoft.playwright
    Page Browser BrowserContext BrowserType
    Request Response APIResponse
    ConsoleMessage Download WebError
    WebSocket WebSocketFrame Worker
    ElementHandle Locator
    PlaywrightException TimeoutError]))

;; =============================================================================
;; Page
;; =============================================================================

(extend-protocol cp/Datafiable
  Page
  (datafy [^Page page]
    {:playwright/type :page
     :page/url        (.url page)
     :page/title      (.title page)
     :page/closed?    (.isClosed page)
     :page/viewport   (when-let [vs (.viewportSize page)]
                        {:width  (.width ^com.microsoft.playwright.options.ViewportSize vs)
                         :height (.height ^com.microsoft.playwright.options.ViewportSize vs)})})

  ;; ===========================================================================
  ;; Browser
  ;; ===========================================================================

  Browser
  (datafy [^Browser browser]
    {:playwright/type    :browser
     :browser/version    (.version browser)
     :browser/connected? (.isConnected browser)
     :browser/type       (str (.name (.browserType browser)))
     :browser/contexts   (long (.size (.contexts browser)))})

  ;; ===========================================================================
  ;; BrowserContext
  ;; ===========================================================================

  BrowserContext
  (datafy [^BrowserContext ctx]
    {:playwright/type   :browser-context
     :context/pages     (long (.size (.pages ctx)))
     :context/browser   (str (.version (.browser ctx)))})

  ;; ===========================================================================
  ;; BrowserType
  ;; ===========================================================================

  BrowserType
  (datafy [^BrowserType bt]
    {:playwright/type      :browser-type
     :browser-type/name    (.name bt)})

  ;; ===========================================================================
  ;; Request
  ;; ===========================================================================

  Request
  (datafy [^Request req]
    (let [timing (.timing req)]
      {:playwright/type    :request
       :request/url        (.url req)
       :request/method     (.method req)
       :request/headers    (into {} (.headers req))
       :request/resource-type (.resourceType req)
       :request/post-data  (.postData req)
       :request/navigation? (.isNavigationRequest req)
       :request/timing     {:start-time           (.-startTime timing)
                            :domain-lookup-start   (.-domainLookupStart timing)
                            :domain-lookup-end     (.-domainLookupEnd timing)
                            :connect-start         (.-connectStart timing)
                            :secure-connection-start (.-secureConnectionStart timing)
                            :connect-end           (.-connectEnd timing)
                            :request-start         (.-requestStart timing)
                            :response-start        (.-responseStart timing)
                            :response-end          (.-responseEnd timing)}}))

  ;; ===========================================================================
  ;; Response
  ;; ===========================================================================

  Response
  (datafy [^Response resp]
    {:playwright/type    :response
     :response/url       (.url resp)
     :response/status    (long (.status resp))
     :response/status-text (.statusText resp)
     :response/ok?       (.ok resp)
     :response/headers   (into {} (.headers resp))})

  ;; ===========================================================================
  ;; APIResponse
  ;; ===========================================================================

  APIResponse
  (datafy [^APIResponse resp]
    {:playwright/type    :api-response
     :response/url       (.url resp)
     :response/status    (long (.status resp))
     :response/status-text (.statusText resp)
     :response/ok?       (.ok resp)
     :response/headers   (into {} (.headers resp))})

  ;; ===========================================================================
  ;; ConsoleMessage
  ;; ===========================================================================

  ConsoleMessage
  (datafy [^ConsoleMessage msg]
    {:playwright/type    :console-message
     :console/type       (.type msg)
     :console/text       (.text msg)
     :console/location   (.location msg)})

  ;; ===========================================================================
  ;; Download
  ;; ===========================================================================

  Download
  (datafy [^Download dl]
    {:playwright/type      :download
     :download/url         (.url dl)
     :download/filename    (.suggestedFilename dl)
     :download/failure     (.failure dl)})

  ;; ===========================================================================
  ;; WebError
  ;; ===========================================================================

  WebError
  (datafy [^WebError we]
    {:playwright/type  :web-error
     :web-error/error  (.error we)
     :web-error/page?  (some? (.page we))})

  ;; ===========================================================================
  ;; WebSocket
  ;; ===========================================================================

  WebSocket
  (datafy [^WebSocket ws]
    {:playwright/type    :websocket
     :websocket/url      (.url ws)
     :websocket/closed?  (.isClosed ws)})

  ;; ===========================================================================
  ;; WebSocketFrame
  ;; ===========================================================================

  WebSocketFrame
  (datafy [^WebSocketFrame frame]
    {:playwright/type  :websocket-frame
     :frame/text       (.text frame)
     :frame/binary?    (some? (.binary frame))})

  ;; ===========================================================================
  ;; Worker
  ;; ===========================================================================

  Worker
  (datafy [^Worker worker]
    {:playwright/type :worker
     :worker/url      (.url worker)})

  ;; ===========================================================================
  ;; ElementHandle
  ;; ===========================================================================

  ElementHandle
  (datafy [^ElementHandle eh]
    (let [bb (.boundingBox eh)]
      {:playwright/type    :element-handle
       :element/visible?   (.isVisible eh)
       :element/enabled?   (.isEnabled eh)
       :element/text       (.textContent eh)
       :element/inner-text (.innerText eh)
       :element/bounding-box (when bb
                               {:x      (.-x bb)
                                :y      (.-y bb)
                                :width  (.-width bb)
                                :height (.-height bb)})}))

  ;; ===========================================================================
  ;; Locator (lightweight - no auto-wait side effects)
  ;; ===========================================================================

  Locator
  (datafy [^Locator loc]
    {:playwright/type :locator
     :locator/string  (str loc)})

  ;; ===========================================================================
  ;; Exceptions
  ;; ===========================================================================

  PlaywrightException
  (datafy [^PlaywrightException e]
    {:playwright/type     :playwright-exception
     :exception/message   (.getMessage e)
     :exception/class     (.getName (.getClass e))})

  TimeoutError
  (datafy [^TimeoutError e]
    {:playwright/type     :timeout-error
     :exception/message   (.getMessage e)
     :exception/class     (.getName (.getClass e))}))
