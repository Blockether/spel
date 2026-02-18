(ns com.blockether.spel.network
  "Request, Response, Route, WebSocket operations.

   All response and request accessor functions participate in the anomaly
   railway pattern: if passed an anomaly map (e.g. from a failed navigate),
   they pass it through unchanged instead of throwing ClassCastException."
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :refer [safe]])
  (:import
   [com.microsoft.playwright Request Response Route
    WebSocket WebSocketFrame WebSocketRoute]))

;; =============================================================================
;; Railway — anomaly pass-through
;; =============================================================================

(defmacro ^:private resp->
  "If `x` is an anomaly map, returns it unchanged (railway pass-through).
   Otherwise evaluates body with the value."
  [x & body]
  `(let [v# ~x]
     (if (anomaly/anomaly? v#)
       v#
       (do ~@body))))

;; =============================================================================
;; Request
;; =============================================================================

(defn request-url
  "Returns the request URL.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.url ^Request req)))

(defn request-method
  "Returns the request HTTP method.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.method ^Request req)))

(defn request-headers
  "Returns the request headers as a map.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (into {} (.headers ^Request req))))

(defn request-all-headers
  "Returns all request headers including redirects.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (into {} (.allHeaders ^Request req))))

(defn request-post-data
  "Returns the request POST data.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.postData ^Request req)))

(defn request-post-data-buffer
  "Returns the request POST data as bytes.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.postDataBuffer ^Request req)))

(defn request-resource-type
  "Returns the resource type (e.g. document, script, image).
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.resourceType ^Request req)))

(defn request-response
  "Returns the response for this request.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (safe (.response ^Request req))))

(defn request-failure
  "Returns the failure text if the request failed.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.failure ^Request req)))

(defn request-frame
  "Returns the frame that initiated this request.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.frame ^Request req)))

(defn request-is-navigation?
  "Returns whether this is a navigation request.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.isNavigationRequest ^Request req)))

(defn request-redirected-from
  "Returns the request that redirected to this one.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.redirectedFrom ^Request req)))

(defn request-redirected-to
  "Returns the request this was redirected to.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req (.redirectedTo ^Request req)))

(defn request-timing
  "Returns the request timing information.
   Passes through anomaly maps unchanged."
  [req]
  (resp-> req
    (let [t (.timing ^Request req)]
      {:start-time    (.-startTime t)
       :domain-lookup-start (.-domainLookupStart t)
       :domain-lookup-end   (.-domainLookupEnd t)
       :connect-start       (.-connectStart t)
       :secure-connection-start (.-secureConnectionStart t)
       :connect-end         (.-connectEnd t)
       :request-start       (.-requestStart t)
       :response-start      (.-responseStart t)
       :response-end        (.-responseEnd t)})))

;; =============================================================================
;; Response
;; =============================================================================

(defn response-url
  "Returns the response URL.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.url ^Response resp)))

(defn response-status
  "Returns the HTTP status code.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.status ^Response resp)))

(defn response-status-text
  "Returns the HTTP status text.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.statusText ^Response resp)))

(defn response-headers
  "Returns the response headers.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (into {} (.headers ^Response resp))))

(defn response-all-headers
  "Returns all response headers.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (into {} (.allHeaders ^Response resp))))

(defn response-body
  "Returns the response body as bytes.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (safe (.body ^Response resp))))

(defn response-text
  "Returns the response body as text.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (safe (.text ^Response resp))))

(defn response-ok?
  "Returns whether the response status is 2xx.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.ok ^Response resp)))

(defn response-request
  "Returns the request for this response.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.request ^Response resp)))

(defn response-frame
  "Returns the frame that received this response.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (.frame ^Response resp)))

(defn response-finished
  "Returns nil when response finishes, or the failure error string.
   Passes through anomaly maps unchanged."
  [resp]
  (resp-> resp (safe (.finished ^Response resp))))

(defn response-header-value
  "Returns the value of a specific header.
   Passes through anomaly maps unchanged."
  [resp ^String name]
  (resp-> resp (.headerValue ^Response resp name)))

(defn response-header-values
  "Returns all values for a specific header.
   Passes through anomaly maps unchanged."
  [resp ^String name]
  (resp-> resp (vec (.headerValues ^Response resp name))))

;; =============================================================================
;; Route
;; =============================================================================

(defn route-request
  "Returns the request being routed."
  ^Request [^Route route]
  (.request route))

(defn route-fulfill!
  "Fulfills the route with a custom response.
   
   Params:
   `route` - Route instance.
   `opts`  - Map with optional:
     :status  - Long. HTTP status code.
     :headers - Map. Response headers.
     :body    - String or byte[]. Response body.
     :content-type - String. Content type."
  [^Route route opts]
  (safe
    (let [fo (com.microsoft.playwright.Route$FulfillOptions.)]
      (when-let [v (:status opts)]
        (.setStatus fo (long v)))
      (when-let [v (:headers opts)]
        (.setHeaders fo ^java.util.Map v))
      (when-let [v (:body opts)]
        (if (string? v)
          (.setBody fo ^String v)
          (.setBody fo ^bytes v)))
      (when-let [v (:content-type opts)]
        (.setContentType fo ^String v))
      (.fulfill route fo))))

(defn route-continue!
  "Continues the route, optionally modifying the request.
   
   Params:
   `route` - Route instance.
   `opts`  - Map, optional. Overrides."
  ([^Route route]
   (safe (.resume route)))
  ([^Route route opts]
   (safe
     (let [co (com.microsoft.playwright.Route$ResumeOptions.)]
       (when-let [v (:url opts)]
         (.setUrl co ^String v))
       (when-let [v (:method opts)]
         (.setMethod co ^String v))
       (when-let [v (:headers opts)]
         (.setHeaders co ^java.util.Map v))
       (when-let [v (:post-data opts)]
         (if (string? v)
           (.setPostData co ^String v)
           (.setPostData co ^bytes v)))
       (.resume route co)))))

(defn route-abort!
  "Aborts the route.
   
   Params:
   `route`       - Route instance.
   `error-code`  - String, optional. Error code."
  ([^Route route]
   (safe (.abort route)))
  ([^Route route ^String error-code]
   (safe (.abort route error-code))))

(defn route-fallback!
  "Falls through to the next route handler.
   
   Params:
   `route` - Route instance."
  [^Route route]
  (safe (.fallback route)))

(defn route-fetch!
  "Performs the request and returns the response.
   
   Params:
   `route` - Route instance.
   
   Returns:
   APIResponse or anomaly map."
  [^Route route]
  (safe (.fetch route)))

;; =============================================================================
;; WebSocket
;; =============================================================================

(defn ws-url
  "Returns the WebSocket URL."
  ^String [^WebSocket ws]
  (.url ws))

(defn ws-is-closed?
  "Returns whether the WebSocket is closed."
  [^WebSocket ws]
  (.isClosed ws))

(defn ws-on-message
  "Registers a handler for incoming messages.
   
   Params:
   `ws`      - WebSocket instance.
   `handler` - Function that receives a WebSocketFrame."
  [^WebSocket ws handler]
  (.onFrameReceived ws
    (reify java.util.function.Consumer
      (accept [_ frame] (handler frame)))))

(defn ws-on-close
  "Registers a handler for WebSocket close.
   
   Params:
   `ws`      - WebSocket instance.
   `handler` - Function that receives the WebSocket."
  [^WebSocket ws handler]
  (.onClose ws
    (reify java.util.function.Consumer
      (accept [_ w] (handler w)))))

(defn ws-on-error
  "Registers a handler for WebSocket errors.
   
   Params:
   `ws`      - WebSocket instance.
   `handler` - Function that receives the error string."
  [^WebSocket ws handler]
  (.onSocketError ws
    (reify java.util.function.Consumer
      (accept [_ err] (handler err)))))

;; =============================================================================
;; WebSocketFrame
;; =============================================================================

(defn wsf-text
  "Returns the text content of a WebSocket frame."
  [^WebSocketFrame frame]
  (.text frame))

(defn wsf-binary
  "Returns the binary content of a WebSocket frame."
  [^WebSocketFrame frame]
  (.binary frame))

;; =============================================================================
;; WebSocketRoute
;; =============================================================================

(defn wsr-url
  "Returns the URL of a WebSocketRoute."
  ^String [^WebSocketRoute wsr]
  (.url wsr))

(defn wsr-close!
  "Closes the WebSocket connection from the server side.
   
   Params:
   `wsr` - WebSocketRoute instance."
  [^WebSocketRoute wsr]
  (.close wsr))

(defn wsr-connect-to-server!
  "Connects to the real server WebSocket.
   
   Params:
   `wsr` - WebSocketRoute instance.
   
   Returns:
   WebSocketRoute for the server connection."
  [^WebSocketRoute wsr]
  (.connectToServer wsr))

(defn wsr-on-message
  "Registers a handler for client messages on the route.
   
   Params:
   `wsr`     - WebSocketRoute instance.
   `handler` - Function that receives WebSocketFrame."
  [^WebSocketRoute wsr handler]
  (.onMessage wsr
    (reify java.util.function.Consumer
      (accept [_ frame] (handler frame)))))

(defn wsr-send!
  "Sends a message to the client.
   
   Params:
   `wsr`     - WebSocketRoute instance.
   `message` - String. Message to send."
  [^WebSocketRoute wsr ^String message]
  (.send wsr message))

(defn wsr-on-close
  "Registers a handler for close events.
   
   Params:
   `wsr`     - WebSocketRoute instance.
   `handler` - Function called on close."
  [^WebSocketRoute wsr handler]
  (.onClose wsr
    (reify java.util.function.Consumer
      (accept [_ _code] (handler)))))
