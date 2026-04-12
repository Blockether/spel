# API testing

## Single API context

```clojure
(require '[com.blockether.spel.core :as core])

(core/with-api-context [ctx (core/new-api-context (core/api-request pw)
                              {:base-url "https://api.example.org"
                               :extra-http-headers {"Authorization" "Bearer token"}})]
  (let [resp (core/api-get ctx "/users")]
    (core/api-response-status resp)     ; 200
    (core/api-response-text   resp)))   ; JSON body
```

## Multiple contexts

```clojure
(core/with-api-contexts
  [users   (core/new-api-context (core/api-request pw) {:base-url "https://users.example.org"})
   billing (core/new-api-context (core/api-request pw) {:base-url "https://billing.example.org"})]
  (core/api-get users   "/me")
  (core/api-get billing "/invoices"))
```

## JSON encoding

**Must bind `*json-encoder*` before using `:json`.** Converts Clojure maps → JSON strings.

```clojure
(require '[cheshire.core :as json])

;; Per-request
(binding [core/*json-encoder* json/generate-string]
  (core/api-post ctx "/users" {:json {:name "Alice" :age 30}}))

;; Globally
(alter-var-root #'core/*json-encoder* (constantly json/generate-string))
```

Using `:json` without an encoder will throw.

## HTTP methods

```clojure
(core/api-get    ctx "/users"   {:params {:page 1}})
(core/api-post   ctx "/users"   {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
(core/api-put    ctx "/users/1" {:data "{\"name\":\"Bob\"}"})
(core/api-patch  ctx "/users/1" {:data "{\"name\":\"Charlie\"}"})
(core/api-delete ctx "/users/1")
(core/api-head   ctx "/health")
(core/api-fetch  ctx "/resource" {:method "OPTIONS"})           ; custom
```

## Form data

```clojure
;; Manual
(let [fd (core/form-data)]
  (core/fd-set    fd "name" "Alice")
  (core/fd-append fd "tag"  "clojure")
  (core/api-post ctx "/submit" {:form fd}))

;; From map
(core/api-post ctx "/submit" {:form (core/map->form-data {:name "Alice" :email "a@b.c"})})
```

## Response inspection

```clojure
(let [resp (core/api-get ctx "/users")]
  (core/api-response-status      resp)       ; 200
  (core/api-response-status-text resp)       ; "OK"
  (core/api-response-url         resp)
  (core/api-response-ok?         resp)       ; true
  (core/api-response-headers     resp)
  (core/api-response-text        resp)
  (core/api-response-body        resp)       ; byte[]
  (core/api-response->map        resp))      ; {:status 200 :ok? true :headers … :body "…"}
```

## Hooks

```clojure
(core/with-hooks
  {:on-request  (fn [method url opts] (println "→" method url) opts)
   :on-response (fn [method url resp] (println "←" method (core/api-response-status resp)) resp)}
  (core/api-get ctx "/users"))
```

## Retry with backoff

Exceptions thrown by the retried fn are caught automatically (re-thrown on the last attempt).

`retry` / `with-retry` defaults: 3 attempts, exponential backoff. Retries on anomalies, HTTP responses with numeric `:status` ≥ 500, and any exception.

### Options

| Key | Default | Notes |
|-----|---------|-------|
| `:max-attempts` | 3 | Total attempts |
| `:delay-ms` | 200 | Initial delay |
| `:backoff` | `:exponential` | `:fixed`, `:linear`, `:exponential` |
| `:max-delay-ms` | 10000 | Ceiling |
| `:retry-when` | anomaly / 5xx / exception | `(fn [result] → truthy)` |

```clojure
;; Library
(core/retry #(core/api-get ctx "/flaky")
  {:max-attempts 5 :delay-ms 1000 :backoff :linear
   :retry-when (fn [r] (= 429 (:status (core/api-response->map r))))})

(core/with-retry {:max-attempts 3 :delay-ms 200}
  (core/api-post ctx "/endpoint" {:json {:action "process"}}))

;; SCI
(spel/with-retry {:max-attempts 3}
  (spel/api-get ctx "/flaky-endpoint"))
```

### `retry-guard` — poll until predicate truthy

Turns a predicate into a `:retry-when`. Also inherits the default anomaly/5xx retry behavior.

```clojure
(core/with-retry {:retry-when (core/retry-guard #(= "ready" (:status %)))}
  (core/api-get ctx "/job/123"))

(spel/with-retry {:retry-when (spel/retry-guard #(> (:count %) 0))}
  (spel/api-get ctx "/queue/stats"))

;; Retry until a page element appears (non-API)
(spel/with-retry {:max-attempts 10 :delay-ms 500
                  :retry-when (spel/retry-guard #(:visible %))}
  (spel/inspect))
```

Retries when the predicate is falsy OR throws; also retries on anomalies and 5xx.

## Standalone request

```clojure
(core/request! pw :get  "https://api.example.org/health")
(core/request! pw :post "https://api.example.org/users"
               {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
```

## Higher-level patterns

### Standalone API testing

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (core/api-get ctx "/users"))
```

### API from page (shared trace)

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (let [resp (core/api-get (core/page-api pg) "/api/me")]
    (core/api-response-status resp)))
```

### Page-bound API with custom base-url (shared trace)

Share cookies with a different domain in the same trace.

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/with-page-api pg {:base-url "https://api.example.org"} [ctx]
    (core/api-get ctx "/me")))
```

## Tracing — shared vs separate stacks

> `with-testing-page` and `with-testing-api` each create their own **complete** Playwright stack. Nesting one inside the other gives you two independent instances, two browsers, two traces — not what you want.

```clojure
;; BAD — two traces
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/with-testing-api {:base-url "https://api.example.org"} [ctx]
    (core/api-get ctx "/users")))

;; GOOD — one trace via page-api / with-page-api
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/api-get (core/page-api pg) "/api/me"))

(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/with-page-api pg {:base-url "https://api.example.org"} [ctx]
    (core/api-get ctx "/me")))
```

| Pattern | PW instances | Traces | Use case |
|---------|:-----------:|:------:|----------|
| `with-testing-page` | 1 | 1 | Browser-only |
| `with-testing-api` | 1 | 1 | API-only |
| `with-testing-page` + `page-api` | 1 | 1 | UI + API, same domain |
| `with-testing-page` + `with-page-api` | 1 | 1 | UI + API, different base-url |
| `with-testing-page` nested in `with-testing-api` | 2 | 2 | Don't — use the others |

## Fixtures

| Fn | Purpose | Auto-traces (Allure)? |
|----|---------|:---------------------:|
| `with-testing-api [ctx] body` | Standalone API testing | ✓ |
| `page-api pg` | Extract `APIRequestContext` from a Page | ✓ |
| `context-api ctx` | Extract from a `BrowserContext` | ✓ |
| `with-page-api pg opts [ctx] body` | Page-bound API with custom base-url | — |
