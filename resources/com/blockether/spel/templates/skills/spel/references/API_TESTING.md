# API Testing

## Single API Context

Create an isolated API request context with custom base URL and headers.

```clojure
(require '[com.blockether.spel.core :as core])

(core/with-api-context [ctx (core/new-api-context (core/api-request pw)
                             {:base-url "https://api.example.org"
                              :extra-http-headers {"Authorization" "Bearer token"}})]
  (let [resp (core/api-get ctx "/users")]
    (println (core/api-response-status resp))     ; 200
    (println (core/api-response-text resp))))      ; JSON body
```

## Multiple API Contexts

Work with multiple API contexts simultaneously, each with different base URLs.

```clojure
(core/with-api-contexts
  [users   (core/new-api-context (core/api-request pw) {:base-url "https://users.example.org"})
   billing (core/new-api-context (core/api-request pw) {:base-url "https://billing.example.org"})]
  (core/api-get users "/me")
  (core/api-get billing "/invoices"))
```

## JSON Encoding

MUST bind `*json-encoder*` before using the `:json` option. The encoder converts Clojure maps to JSON strings.

```clojure
(require '[cheshire.core :as json])

;; Per-request binding
(binding [core/*json-encoder* json/generate-string]
  (core/api-post ctx "/users" {:json {:name "Alice" :age 30}}))

;; Set globally (affects all API calls)
(alter-var-root #'core/*json-encoder* (constantly json/generate-string))

;; Using :json WITHOUT binding *json-encoder* will throw!
```

## HTTP Methods

All standard HTTP methods are supported.

```clojure
;; GET with query parameters
(core/api-get ctx "/users" {:params {:page 1}})

;; POST with JSON body
(core/api-post ctx "/users" {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})

;; PUT
(core/api-put ctx "/users/1" {:data "{\"name\":\"Bob\"}"})

;; PATCH
(core/api-patch ctx "/users/1" {:data "{\"name\":\"Charlie\"}"})

;; DELETE
(core/api-delete ctx "/users/1")

;; HEAD
(core/api-head ctx "/health")

;; Custom method (via fetch)
(core/api-fetch ctx "/resource" {:method "OPTIONS"})
```

## Form Data

Send form-encoded data using FormData instances or convert from maps.

```clojure
;; Manual form building
(let [fd (core/form-data)]
  (core/fd-set fd "name" "Alice")
  (core/fd-append fd "tag" "clojure")
  (core/api-post ctx "/submit" {:form fd}))

;; Convert Clojure map to form data
(core/api-post ctx "/submit" {:form (core/map->form-data {:name "Alice" :email "a@b.c"})})
```

## Response Inspection

Inspect all aspects of API responses.

```clojure
(let [resp (core/api-get ctx "/users")]
  (core/api-response-status resp)         ; 200
  (core/api-response-status-text resp)    ; "OK"
  (core/api-response-url resp)
  (core/api-response-ok? resp)            ; true
  (core/api-response-headers resp)        ; {"content-type" "..."}
  (core/api-response-text resp)           ; body string
  (core/api-response-body resp)           ; byte[]
  (core/api-response->map resp))          ; {:status 200 :ok? true :headers {...} :body "..."}
```

## Hooks

Intercept requests and responses with hooks for logging, modification, or testing.

```clojure
(core/with-hooks
  {:on-request  (fn [method url opts] (println "→" method url) opts)
   :on-response (fn [method url resp] (println "←" method (core/api-response-status resp)) resp)}
  (core/api-get ctx "/users"))
```

## Retry with Backoff

Retry failed requests with configurable backoff strategies. Exceptions thrown by `f` are automatically caught and retried (re-thrown on the last attempt).

### Default behavior

`retry` / `with-retry` default to 3 attempts with exponential backoff. Retries on:
- Anomalies (error maps from `safe` wrapper)
- HTTP responses with numeric `:status` >= 500
- Any exception thrown by the retried function

### Options

| Key | Default | Description |
|-----|---------|-------------|
| `:max-attempts` | `3` | Total attempts |
| `:delay-ms` | `200` | Initial delay in ms |
| `:backoff` | `:exponential` | `:fixed`, `:linear`, or `:exponential` |
| `:max-delay-ms` | `10000` | Ceiling on delay |
| `:retry-when` | anomaly/5xx/exception | `(fn [result] -> truthy)` to retry |

### Basic retry

```clojure
;; Library layer
(core/retry #(core/api-get ctx "/flaky")
  {:max-attempts 5 :delay-ms 1000 :backoff :linear
   :retry-when (fn [r] (= 429 (:status (core/api-response->map r))))})

;; Macro form
(core/with-retry {:max-attempts 3 :delay-ms 200}
  (core/api-post ctx "/endpoint" {:json {:action "process"}}))

;; SCI / eval-sci
(spel/with-retry {:max-attempts 3}
  (spel/api-get ctx "/flaky-endpoint"))
```

### retry-guard — poll until a condition is met

`retry-guard` creates a `:retry-when` predicate that retries until your predicate returns truthy. It also inherits the default error/anomaly retry behavior.

```clojure
;; Library layer — retry until job is ready
(core/with-retry {:retry-when (core/retry-guard #(= "ready" (:status %)))}
  (core/api-get ctx "/job/123"))

;; SCI / eval-sci — retry until queue has items
(spel/with-retry {:retry-when (spel/retry-guard #(> (:count %) 0))}
  (spel/api-get ctx "/queue/stats"))

;; Retry until a page element appears (non-API use case)
(spel/with-retry {:max-attempts 10 :delay-ms 500
                  :retry-when (spel/retry-guard #(:visible %))}
  (spel/inspect))
```

> **Note**: `retry-guard` retries when the predicate returns falsy OR throws. It also retries on anomalies and 5xx responses (same as default). Use it for polling/eventual-consistency scenarios.

## Standalone Request

Fire-and-forget requests without context setup. Creates an ephemeral context internally.

```clojure
;; Simple GET
(core/request! pw :get "https://api.example.org/health")

;; POST with data
(core/request! pw :post "https://api.example.org/users"
  {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
```

## Higher-Level Patterns

### Standalone API Testing

Creates a full Playwright stack for API-only testing.

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (core/api-get ctx "/users"))
```

### API from Page (shared trace)

Share browser cookies and session with API requests from a page. Uses `page-api` to extract the `APIRequestContext` from the page's `BrowserContext` — all API calls appear in the same Playwright trace as the page navigation.

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (let [resp (core/api-get (core/page-api pg) "/api/me")]
    (core/api-response-status resp)))
```

### Page-Bound API with Custom Base-URL (shared trace)

Combine UI navigation with API calls to a different domain, sharing cookies. `with-page-api` creates an `APIRequestContext` bound to the page's context with a custom `:base-url` — same trace, different domain.

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  ;; ... login via UI ...
  (core/with-page-api pg {:base-url "https://api.example.org"} [ctx]
    (core/api-get ctx "/me")))
```

## Tracing: shared vs separate Playwright stacks

> **NOTE:** `with-testing-page` and `with-testing-api` each create their own complete
> Playwright stack (Playwright → Browser → Context). Nesting one inside the other does NOT
> share a trace — you get two independent Playwright instances, two browsers, two traces.

```clojure
;; BAD: Two separate Playwright instances, two separate traces
(core/with-testing-page [pg]                        ;; Playwright #1 → Browser #1 → Context #1
  (page/navigate pg "https://example.org/login")
  (core/with-testing-api {:base-url "https://api.example.org"} [ctx]  ;; Playwright #2 → Browser #2 → Context #2
    (core/api-get ctx "/users")))

;; GOOD: One Playwright, one trace — use page-api or with-page-api
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/api-get (core/page-api pg) "/api/me"))       ;; Same context, same trace

(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/with-page-api pg {:base-url "https://api.example.org"} [ctx]  ;; Same context, different base-url
    (core/api-get ctx "/me")))
```

When to use which:

| Pattern | Playwright instances | Traces | Use case |
|---------|---------------------|--------|----------|
| `with-testing-page` alone | 1 | 1 | Browser-only testing |
| `with-testing-api` alone | 1 | 1 | API-only testing (no browser) |
| `with-testing-page` + `page-api` | 1 | 1 | Combined UI + API, same domain |
| `with-testing-page` + `with-page-api` | 1 | 1 | Combined UI + API, different base-url |
| `with-testing-page` nesting `with-testing-api` | 2 | 2 | Don't do this — use `page-api`/`with-page-api` instead |

## API Test Fixtures

| Function | Description | Auto-Traces? |
|----------|-------------|--------------|
| `with-testing-api [ctx] body` | Standalone API testing with full Playwright stack | Yes (when Allure active) |
| `page-api pg` | Extract APIRequestContext from a Page | Yes |
| `context-api ctx` | Extract APIRequestContext from a BrowserContext | Yes |
| `with-page-api pg opts [ctx] body` | Page-bound API with custom base-url + cookie sharing | No |
