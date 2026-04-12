# Network & routing

Intercept, modify, inspect HTTP req/res. Handle WebSocket connections.

## Route handlers

Register handlers to intercept requests matching URL glob patterns.

```clojure
(require '[com.blockether.spel.network :as net])

;; Block images
(page/route! pg "**/*.{png,jpg,jpeg,gif,svg}"
             (fn [route] (net/route-abort! route)))

;; Mock API
(page/route! pg "**/api/users"
             (fn [route] (net/route-fulfill! route {:status 200
                                                    :content-type "application/json"
                                                    :body "{\"users\":[]}"})))

;; Add/modify request headers
(page/route! pg "**/*"
  (fn [route]
    (net/route-continue! route
      {:headers (merge (net/request-headers (net/route-request route))
                       {"X-Custom" "injected"})})))

;; Fetch real response, mutate, fulfill
(page/route! pg "**/api/data"
  (fn [route]
    (let [resp (net/route-fetch! route)]
      (net/route-fulfill! route
        {:status 200 :body (str (net/response-text resp) " (modified)")}))))

;; Fallback to next handler
(page/route! pg "**/*"
  (fn [route]
    (if (= "POST" (net/request-method (net/route-request route)))
      (net/route-abort! route)
      (net/route-fallback! route))))

;; Unregister
(page/unroute! pg "**/*.{png,jpg}")
```

## Request / response inspection

```clojure
(let [req some-request]
  (net/request-url            req)   ; "https://…"
  (net/request-method         req)   ; "GET"
  (net/request-headers        req)
  (net/request-post-data      req)   ; body or nil
  (net/request-resource-type  req)   ; "document" "script" "fetch" …
  (net/request-timing         req)   ; {:start-time … :response-end …}
  (net/request-is-navigation? req)
  (net/request-failure        req))  ; failure text or nil

(let [resp some-response]
  (net/response-url           resp)
  (net/response-status        resp)
  (net/response-status-text   resp)
  (net/response-ok?           resp)
  (net/response-headers       resp)
  (net/response-text          resp)
  (net/response-body          resp)  ; byte[]
  (net/response-header-value  resp "content-type"))
```

## Wait for specific response

```clojure
(let [resp (page/wait-for-response pg "**/api/users"
             (reify Runnable
               (run [_] (locator/click (page/locator pg "#load-users")))))]
  (net/response-status resp))
```

## WebSocket

```clojure
(let [ws (first (.webSockets pg))]
  (net/ws-url ws)
  (net/ws-is-closed? ws)
  (net/ws-on-message ws (fn [frame] (println "WS msg:" (net/wsf-text frame))))
  (net/ws-on-close   ws (fn [_ws]   (println "WS closed")))
  (net/ws-on-error   ws (fn [err]   (println "WS error:" err))))
```

## Quick reference

### Route actions

| Fn | Description |
|----|-------------|
| `net/route-abort!` | Abort (optional error code) |
| `net/route-continue!` | Continue (optional header override) |
| `net/route-fallback!` | Pass to next handler |
| `net/route-fetch!` | Perform request + get response |
| `net/route-fulfill!` | Fulfill with custom response |

### Request

| Fn | Returns |
|----|---------|
| `net/request-url` | URL |
| `net/request-method` | HTTP method |
| `net/request-headers` | map |
| `net/request-post-data` | body or nil |
| `net/request-resource-type` | `"document"` / `"script"` / `"image"` / `"fetch"` … |
| `net/request-timing` | `{:start-time … :response-end …}` |
| `net/request-is-navigation?` | bool |
| `net/request-failure` | failure text or nil |

### Response

| Fn | Returns |
|----|---------|
| `net/response-url` | URL |
| `net/response-status` | int |
| `net/response-status-text` | text |
| `net/response-ok?` | bool (2xx) |
| `net/response-headers` | map |
| `net/response-text` | body string |
| `net/response-body` | `byte[]` |
| `net/response-header-value` | header value |

### WebSocket

| Fn | Returns |
|----|---------|
| `net/ws-url` | URL |
| `net/ws-is-closed?` | bool |
| `net/ws-on-message` | register frame handler |
| `net/ws-on-close` | register close handler |
| `net/ws-on-error` | register error handler |

### Frame

| Fn | Returns |
|----|---------|
| `net/wsf-text` | frame as text |
| `net/wsf-binary` | frame as bytes |
