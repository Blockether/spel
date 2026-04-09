# Network & Routing

Intercept, modify, inspect HTTP req/res. Handle WebSocket connections.

## Route Handlers

Register handlers to intercept network requests matching URL patterns. Use glob patterns for multiple URLs.

### Block Images

```clojure
(require '[com.blockether.spel.network :as net])

(page/route! pg "**/*.{png,jpg,jpeg,gif,svg}" (fn [route]
  (net/route-abort! route)))
```

### Mock API Response

```clojure
(page/route! pg "**/api/users" (fn [route]
  (net/route-fulfill! route {:status 200
                             :content-type "application/json"
                             :body "{\"users\":[]}"})))
```

### Modify Request Headers

```clojure
(page/route! pg "**/*" (fn [route]
  (net/route-continue! route {:headers (merge (net/request-headers (net/route-request route))
                                               {"X-Custom" "injected"})})))
```

### Modify Response (Fetch Then Alter)

Fetch real response, alter, fulfill:

```clojure
(page/route! pg "**/api/data" (fn [route]
  (let [resp (net/route-fetch! route)]
    (net/route-fulfill! route {:status 200
                               :body (str (net/response-text resp) " (modified)")}))))
```

### Fallback to Next Handler

Pass control to next registered handler:

```clojure
(page/route! pg "**/*" (fn [route]
  (if (= "POST" (net/request-method (net/route-request route)))
    (net/route-abort! route)
    (net/route-fallback! route))))
```

### Remove Route

Unregister route handler by pattern:

```clojure
(page/unroute! pg "**/*.{png,jpg}")
```

## Request Inspection

Extract info from request objects.

```clojure
(let [req some-request]
  (net/request-url req)            ; "https://example.org/api"
  (net/request-method req)         ; "GET"
  (net/request-headers req)        ; {"accept" "text/html" ...}
  (net/request-post-data req)      ; POST body string or nil
  (net/request-resource-type req)  ; "document", "script", "fetch", etc.
  (net/request-timing req)         ; {:start-time ... :response-end ...}
  (net/request-is-navigation? req) ; true/false
  (net/request-failure req))       ; failure text or nil
```

## Response Inspection

Extract info from response objects.

```clojure
(let [resp some-response]
  (net/response-url resp)          ; "https://example.org/api"
  (net/response-status resp)       ; 200
  (net/response-status-text resp)  ; "OK"
  (net/response-ok? resp)          ; true
  (net/response-headers resp)      ; {"content-type" "application/json" ...}
  (net/response-text resp)         ; body string
  (net/response-body resp)         ; byte[]
  (net/response-header-value resp "content-type"))
```

## Wait for Specific Response

Wait for response matching URL pattern while executing action:

```clojure
(let [resp (page/wait-for-response pg "**/api/users"
             (reify Runnable (run [_]
               (locator/click (page/locator pg "#load-users")))))]
  (println (net/response-status resp)))
```

## WebSocket Handling

Inspect + interact with WebSocket connections.

```clojure
(let [ws (first (.webSockets pg))]
  (net/ws-url ws)
  (net/ws-is-closed? ws)
  (net/ws-on-message ws (fn [frame]
    (println "WS msg:" (net/wsf-text frame))))
  (net/ws-on-close ws (fn [_ws] (println "WS closed")))
  (net/ws-on-error ws (fn [err] (println "WS error:" err))))
```

## Route Actions

| Action | Description |
|---------|-------------|
| `net/route-abort!` | Abort request (optionally with error code) |
| `net/route-continue!` | Continue request (optionally modify headers) |
| `net/route-fallback!` | Pass to next registered handler |
| `net/route-fetch!` | Perform request + get response |
| `net/route-fulfill!` | Fulfill with custom response |

## Request Functions

| Function | Returns |
|----------|----------|
| `net/request-url` | Request URL string |
| `net/request-method` | HTTP method ("GET", "POST", etc.) |
| `net/request-headers` | Map of headers |
| `net/request-post-data` | POST body string or nil |
| `net/request-resource-type` | "document", "script", "image", "fetch", etc. |
| `net/request-timing` | Timing map with start/end timestamps |
| `net/request-is-navigation?` | true if navigation request |
| `net/request-failure` | Failure text or nil if no failure |

## Response Functions

| Function | Returns |
|----------|----------|
| `net/response-url` | Response URL string |
| `net/response-status` | HTTP status code (200, 404, etc.) |
| `net/response-status-text` | Status text ("OK", "Not Found", etc.) |
| `net/response-ok?` | true if status is 2xx |
| `net/response-headers` | Map of response headers |
| `net/response-text` | Response body as string |
| `net/response-body` | Response body as byte array |
| `net/response-header-value` | Value for specific header name |

## WebSocket Functions

| Function | Returns |
|----------|----------|
| `net/ws-url` | WebSocket URL string |
| `net/ws-is-closed?` | true if connection closed |
| `net/ws-on-message` | Register message handler (receives frame) |
| `net/ws-on-close` | Register close handler |
| `net/ws-on-error` | Register error handler |

## WebSocket Frame Functions

| Function | Returns |
|----------|----------|
| `net/wsf-text` | Frame content as text |
| `net/wsf-binary` | Frame content as bytes |