---
name: spel
description: "com.blockether.spel package - Clojure wrapper for Playwright 1.58.0. Browser automation, testing, assertions, codegen, CLI. Use when working with browser automation, E2E tests, Playwright API, or visual testing in Clojure."
license: MIT
compatibility: opencode
---

# spel - Clojure Playwright Wrapper

`com.blockether.spel` wraps Playwright Java 1.58.0 with idiomatic Clojure.

## Quick Reference

| Command | Purpose |
|---------|---------|
| `spel --help` | CLI help |
| `spel codegen --help` | Codegen CLI help |
| `spel init-agents --help` | Agent scaffolding help |
| `spel init-agents --loop=opencode` | Scaffold E2E agents for OpenCode (default) |
| `spel init-agents --loop=claude` | Scaffold E2E agents for Claude Code |
| `spel init-agents --loop=vscode` | Scaffold E2E agents for VS Code / Copilot |

---

## Architecture

### Namespace Map

| Namespace | Purpose | Key Functions |
|-----------|---------|---------------|
| `core` | Lifecycle, browser, context, page | `create`, `close!`, `with-playwright`, `with-browser`, `with-context`, `with-page`, `launch`, `launch-chromium`, `launch-firefox`, `launch-webkit`, `chromium`, `firefox`, `webkit`, `browser-type-name`, `close-browser!`, `browser-connected?`, `browser-version`, `browser-contexts`, `new-context`, `close-context!`, `context-pages`, `context-browser`, `context-set-default-timeout!`, `context-set-default-navigation-timeout!`, `context-grant-permissions!`, `context-clear-permissions!`, `context-clear-cookies!`, `context-cookies`, `context-set-offline!`, `context-set-extra-http-headers!`, `new-page`, `new-page-from-context`, `close-page!`, `wrap-error`, `safe` |
| `page` | Navigation, locators, content, events | `navigate`, `go-back`, `go-forward`, `reload`, `url`, `title`, `content`, `set-content!`, `locator`, `get-by-text`, `get-by-role`, `get-by-label`, `get-by-placeholder`, `get-by-alt-text`, `get-by-title`, `get-by-test-id`, `evaluate`, `evaluate-handle`, `screenshot`, `pdf`, `is-closed?`, `viewport-size`, `set-viewport-size!`, `set-default-timeout!`, `set-default-navigation-timeout!`, `main-frame`, `frames`, `frame-by-name`, `frame-by-url`, `wait-for-load-state`, `wait-for-url`, `wait-for-selector`, `wait-for-timeout`, `wait-for-function`, `wait-for-response`, `emulate-media!`, `on-console`, `on-dialog`, `on-page-error`, `on-request`, `on-response`, `on-close`, `on-download`, `on-popup`, `route!`, `unroute!`, `bring-to-front`, `page-context`, `add-script-tag`, `add-style-tag`, `page-keyboard`, `page-mouse`, `page-touchscreen`, `video`, `workers`, `opener`, `set-extra-http-headers!`, `expose-function!`, `expose-binding!` |
| `locator` | Element interactions | `click`, `dblclick`, `fill`, `type-text`, `press`, `clear`, `check`, `uncheck`, `hover`, `tap-element`, `focus`, `blur`, `select-option`, `set-input-files!`, `scroll-into-view`, `dispatch-event`, `drag-to`, `text-content`, `inner-text`, `inner-html`, `input-value`, `get-attribute`, `is-visible?`, `is-hidden?`, `is-enabled?`, `is-disabled?`, `is-editable?`, `is-checked?`, `bounding-box`, `count-elements`, `all-text-contents`, `all-inner-texts`, `all`, `loc-filter`, `first-element`, `last-element`, `nth-element`, `loc-locator`, `loc-get-by-text`, `loc-get-by-role`, `locator-screenshot`, `highlight`, `element-handle`, `element-handles`, `eh-click`, `eh-fill`, `eh-text-content`, `eh-inner-text`, `eh-inner-html`, `eh-get-attribute`, `eh-is-visible?`, `eh-is-enabled?`, `eh-is-checked?`, `eh-bounding-box`, `eh-screenshot`, `eh-dispose!`, `js-evaluate`, `js-json-value`, `js-get-property`, `js-get-properties`, `js-as-element`, `js-dispose!` |
| `assertions` | Playwright assertions | `assert-that`, `set-default-assertion-timeout!`, `loc-not`, `page-not`, `api-not`, `has-text`, `contains-text`, `has-attribute`, `has-class`, `contains-class`, `has-count`, `has-css`, `has-id`, `has-js-property`, `has-value`, `has-values`, `has-role`, `has-accessible-name`, `has-accessible-description`, `has-accessible-error-message`, `matches-aria-snapshot`, `is-attached`, `is-checked`, `is-disabled`, `is-editable`, `is-enabled`, `is-focused`, `is-hidden`, `is-visible`, `is-empty`, `is-in-viewport`, `has-title`, `has-url`, `is-ok` |
| `frame` | Frame/iframe operations | `frame-navigate`, `frame-content`, `frame-set-content!`, `frame-url`, `frame-name`, `frame-title`, `frame-locator`, `frame-get-by-text`, `frame-get-by-role`, `frame-get-by-label`, `frame-get-by-test-id`, `frame-evaluate`, `parent-frame`, `child-frames`, `frame-page`, `is-detached?`, `frame-wait-for-load-state`, `frame-wait-for-selector`, `frame-wait-for-function`, `frame-locator-obj`, `fl-locator`, `fl-get-by-text`, `fl-get-by-role`, `fl-get-by-label`, `fl-first`, `fl-last`, `fl-nth` |
| `options` | Java option builders (80+) | `->launch-options`, `->new-context-options`, `->navigate-options`, `->screenshot-options`, `->click-options`, `->fill-options`, `->hover-options`, `->type-options`, `->press-options`, `->check-options`, `->uncheck-options`, `->dblclick-options`, `->locator-screenshot-options`, `->wait-for-options`, `->mouse-click-options`, `->mouse-down-options`, `->mouse-up-options`, `->tracing-start-options`, `->tracing-stop-options`, `->pdf-options`, `->emulate-media-options`, `->select-option-options`, `->drag-to-options`, `->wait-for-popup-options`, `->wait-for-response-options`, `->new-page-options`, `->storage-state-options`, `->cookie`, `->viewport-size`, and 40+ more |
| `input` | Keyboard, mouse, touch | `key-press`, `key-type`, `key-down`, `key-up`, `key-insert-text`, `mouse-click`, `mouse-dblclick`, `mouse-move`, `mouse-down`, `mouse-up`, `mouse-wheel`, `touchscreen-tap` |
| `network` | Request/response/routing | `request-url`, `request-method`, `request-headers`, `request-all-headers`, `request-post-data`, `request-resource-type`, `request-response`, `request-failure`, `request-frame`, `request-is-navigation?`, `request-redirected-from`, `request-redirected-to`, `request-timing`, `response-url`, `response-status`, `response-status-text`, `response-headers`, `response-all-headers`, `response-body`, `response-text`, `response-ok?`, `response-request`, `response-frame`, `response-finished`, `response-header-value`, `response-header-values`, `route-request`, `route-fulfill!`, `route-continue!`, `route-abort!`, `route-fallback!`, `route-fetch!`, `ws-url`, `ws-is-closed?`, `ws-on-message`, `ws-on-close`, `ws-on-error`, `wsf-text`, `wsf-binary` |
| `data` | Datafy protocols | Extends `clojure.core.protocols/Datafiable` for Page, Browser, BrowserContext, exceptions |
| `util` | Dialog, download, console, CDP, clock, tracing, video, workers, file chooser | `dialog-type`, `dialog-message`, `dialog-default-value`, `dialog-accept!`, `dialog-dismiss!`, `download-url`, `download-suggested-filename`, `download-path`, `download-save-as!`, `download-cancel!`, `download-failure`, `download-page`, `console-type`, `console-text`, `console-args`, `console-location`, `console-page`, `cdp-send`, `cdp-detach!`, `cdp-on`, `clock-install!`, `clock-set-fixed-time!`, `clock-set-system-time!`, `clock-fast-forward!`, `clock-pause-at!`, `clock-resume!`, `page-clock`, `context-tracing`, `tracing-start!`, `tracing-stop!`, `video-path`, `video-save-as!`, `video-delete!`, `worker-url`, `worker-evaluate`, `file-chooser-page`, `file-chooser-element`, `file-chooser-is-multiple?`, `file-chooser-set-files!`, `selectors`, `selectors-register!`, `web-error-page`, `web-error-error` |
| `api` | REST API testing | `api-request`, `new-api-context`, `with-api-context`, `with-api-contexts`, `api-get`, `api-post`, `api-put`, `api-patch`, `api-delete`, `api-head`, `api-fetch`, `api-dispose!`, `api-response-url`, `api-response-status`, `api-response-status-text`, `api-response-headers`, `api-response-body`, `api-response-text`, `api-response-ok?`, `api-response-headers-array`, `api-response-dispose!`, `api-response->map`, `request!`, `retry`, `with-retry`, `with-hooks`, `form-data`, `fd-set`, `fd-append`, `map->form-data`, `request-options` |
| `allure` | Allure test reporting | `epic`, `feature`, `story`, `severity`, `owner`, `tag`, `description`, `link`, `issue`, `tms`, `parameter`, `attach`, `attach-bytes`, `screenshot`, `step`, `ui-step`, `api-step`, `attach-api-response!`, `make-context`, `reporter-active?`, `set-reporter-active!` |
| `snapshot` | Accessibility snapshots | `capture-snapshot`, `capture-snapshot-for-frame`, `capture-full-snapshot`, `resolve-ref`, `clear-refs!`, `ref-bounding-box` |
| `annotate` | Page annotation overlays | `inject-overlays!`, `remove-overlays!`, `visible-refs`, `annotated-screenshot`, `save-annotated-screenshot!` |
| `codegen` | JSONL to Clojure transformer | `jsonl->clojure`, `jsonl-str->clojure` |
| `cli` | Native CLI client | `parse-args`, `send-command!`, `run-cli!` |
| `init-agents` | Scaffold E2E agents (opencode/claude/vscode) | `-main` |

### Error Handling

Uses `com.blockether.anomaly` instead of throwing exceptions:

```clojure
;; All wrapped functions return either a value or an anomaly map
(let [result (page/navigate pg "https://example.com")]
  (if (anomaly/anomaly? result)
    (println "Error:" (:cognitect.anomalies/message result))
    (println "Navigated!")))
```

| Playwright Exception | Anomaly Category | Error Type Keyword |
|---------------------|------------------|-------------------|
| `TimeoutError` | `:cognitect.anomalies/busy` | `:playwright.error/timeout` |
| `TargetClosedError` | `:cognitect.anomalies/interrupted` | `:playwright.error/target-closed` |
| `PlaywrightException` | `:cognitect.anomalies/fault` | `:playwright.error/playwright` |
| Generic `Exception` | `:cognitect.anomalies/fault` | `:playwright.error/unknown` |

### Resource Lifecycle Macros

**Always use macros for cleanup.** They nest naturally:

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        ;; returns nil on success, throws on failure
        (assert/has-title (assert/assert-that pg) "Example Domain")))))
```

| Macro | Cleans Up |
|-------|-----------|
| `with-playwright` | Playwright instance |
| `with-browser` | Browser instance |
| `with-context` | BrowserContext |
| `with-page` | Page instance |

---

## Common Patterns

### Locating Elements

```clojure
;; By CSS
(page/locator pg "h1")
(page/locator pg "#my-id")

;; By text
(page/get-by-text pg "Click me")

;; By role (requires AriaRole import)
(page/get-by-role pg AriaRole/BUTTON)

;; By role + name filter (no GetByRoleOptions for name yet)
(locator/loc-filter (page/get-by-role pg AriaRole/LINK) {:has-text "Learn more"})

;; By label
(page/get-by-label pg "Email")

;; By placeholder
(page/get-by-placeholder pg "Enter email")

;; By test ID
(page/get-by-test-id pg "submit-btn")

;; Sub-locators
(locator/loc-locator (page/locator pg ".card") "h2")
(locator/loc-get-by-text (page/locator pg ".card") "Title")
```

### Strict Mode & Multiple Elements

Playwright uses **strict mode by default** — all locator actions (`click`, `fill`, `text-content`, etc.) require the locator to resolve to **exactly one element**. If multiple elements match, Playwright throws a strict mode violation error.

This is intentional: it prevents accidentally interacting with the wrong element.

**Example error:**

```
Error: strict mode violation: locator("h1") resolved to 4 elements
```

**How to handle multiple matches:**

```clojure
;; WRONG — throws if multiple h1 elements exist
(locator/text-content (page/locator pg "h1"))
(spel/text "h1")  ;; same thing in SCI/eval mode

;; RIGHT — narrow to first element
(locator/text-content (locator/first-element (page/locator pg "h1")))
(spel/text (spel/first "h1"))  ;; SCI/eval equivalent

;; RIGHT — get ALL matching texts as a vector
(locator/all-text-contents (page/locator pg "h1"))
(spel/all-text-contents "h1")  ;; SCI/eval equivalent

;; RIGHT — use a more specific selector
(locator/text-content (page/locator pg "h1.display-1"))
(spel/text "h1.display-1")  ;; SCI/eval equivalent

;; RIGHT — use semantic locators (role + name filter)
(locator/text-content
  (locator/loc-filter (page/get-by-role pg AriaRole/HEADING)
    {:has-text "Installation"}))

;; RIGHT — use nth-element for a specific match
(locator/text-content (locator/nth-element (page/locator pg "h1") 0))
(spel/text (spel/nth (spel/$ "h1") 0))  ;; SCI/eval equivalent
```

**Available narrowing functions:**

| Function | Description |
|----------|-------------|
| `locator/first-element` / `spel/first` | First matching element |
| `locator/last-element` / `spel/last` | Last matching element |
| `locator/nth-element` / `spel/nth` | Nth element (0-indexed) |
| `locator/all` / `spel/$$` | All matches as a vector of Locators |
| `locator/all-text-contents` / `spel/all-text-contents` | All texts as a vector |
| `locator/all-inner-texts` / `spel/all-inner-texts` | All inner texts as a vector |
| `locator/count-elements` / `spel/count-of` | Count of matching elements |
| `locator/loc-filter` / `spel/loc-filter` | Filter by text, has-text, or sub-locator |

**Rule of thumb:** If your selector might match multiple elements, either:
1. Make the selector more specific (CSS class, test-id, role + name)
2. Use `first-element` / `spel/first` to explicitly pick the first
3. Use `all-text-contents` / `spel/all-text-contents` to get all values

### Assertions

All assertion functions require `assert-that` first. They return `nil` on success, throw on failure.
In test `it` blocks, ALWAYS wrap with `(expect (nil? ...))`.

```clojure
;; Page assertions (assert-that returns PageAssertions)
(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My Page")
  (assert/has-url pa "https://example.com"))

;; Locator assertions (assert-that returns LocatorAssertions)
(let [la (assert/assert-that (page/locator pg "h1"))]
  (assert/has-text la "Welcome")
  (assert/contains-text la "partial text")
  (assert/is-visible la)
  (assert/is-hidden la)
  (assert/is-checked la)
  (assert/is-enabled la)
  (assert/is-disabled la)
  (assert/is-editable la)
  (assert/is-focused la)
  (assert/is-empty la)
  (assert/is-attached la)
  (assert/is-in-viewport la)
  (assert/has-value la "hello")
  (assert/has-values la ["a" "b"])
  (assert/has-attribute la "href" "https://example.com")
  (assert/has-class la "active")
  (assert/contains-class la "active")
  (assert/has-css la "color" "rgb(0, 0, 0)")
  (assert/has-id la "content")
  (assert/has-role la AriaRole/NAVIGATION)
  (assert/has-count la 5)
  (assert/has-js-property la "dataset.ready" "true")
  (assert/has-accessible-name la "Submit")
  (assert/has-accessible-description la "Enter your email")
  (assert/matches-aria-snapshot la "- navigation"))

;; Locator negation (assert the opposite)
(assert/is-visible (assert/loc-not (assert/assert-that (page/locator pg ".hidden"))))
(assert/is-checked (assert/loc-not (assert/assert-that (page/locator pg "#opt-out"))))

;; Page negation (page-not takes PageAssertions, not Page)
(assert/has-title (assert/page-not (assert/assert-that pg)) "Wrong Title")
(assert/has-url (assert/page-not (assert/assert-that pg)) "https://wrong.com")

;; API response assertion (api-not takes APIResponseAssertions, not APIResponse)
(assert/is-ok (assert/assert-that api-response))
(assert/is-ok (assert/api-not (assert/assert-that api-response)))     ; assert NOT ok

;; In test `it` blocks — ALWAYS wrap with expect:
(expect (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Welcome")))
(expect (nil? (assert/has-title (assert/assert-that *page*) "My Page")))

;; Timeout override
(assert/set-default-assertion-timeout! 10000)
```

### Events & Signals

```clojure
;; Dialog handling
(page/on-dialog pg (fn [dialog] (.dismiss dialog)))

;; Download handling
(page/on-download pg (fn [dl] (println "Downloaded:" (.suggestedFilename dl))))

;; Popup handling
(page/on-popup pg (fn [popup-pg] (println "Popup URL:" (page/url popup-pg))))

;; waitForPopup / waitForDownload (Java interop - no wrapper yet)
(let [popup (.waitForPopup ^Page pg
              (reify Runnable (run [_] (locator/click (page/locator pg "a")))))]
  (page/navigate popup "..."))
```

### Frame Navigation

```clojure
;; Via FrameLocator (preferred)
(let [fl (frame/frame-locator-obj pg "iframe#main")]
  (locator/click (frame/fl-locator fl "button")))

;; Via Locator.contentFrame() (Java interop)
(let [frame-loc (.contentFrame (page/locator pg "iframe"))]
  (locator/click (.locator frame-loc "h1")))

;; Nested frames
(let [fl1 (frame/frame-locator-obj pg "iframe.outer")
      fl2 (.frameLocator (frame/fl-locator fl1 "iframe.inner") "iframe.inner")]
  (locator/click (frame/fl-locator fl2 "button")))
```

### Utility Functions (util namespace)

```clojure
(require '[com.blockether.spel.util :as util])

;; Dialog handling
(page/on-dialog pg (fn [dialog]
  (println "Type:" (util/dialog-type dialog))       ; "alert", "confirm", "prompt", "beforeunload"
  (println "Message:" (util/dialog-message dialog))
  (println "Default:" (util/dialog-default-value dialog))
  (util/dialog-accept! dialog)                       ; or (util/dialog-accept! dialog "input text")
  ;; (util/dialog-dismiss! dialog)
  ))

;; Download handling
(page/on-download pg (fn [dl]
  (println "URL:" (util/download-url dl))
  (println "File:" (util/download-suggested-filename dl))
  (println "Failure:" (util/download-failure dl))
  (util/download-save-as! dl "/tmp/downloaded.pdf")
  ;; (util/download-cancel! dl)
  ;; (util/download-path dl)
  ;; (util/download-page dl)
  ))

;; Console messages
(page/on-console pg (fn [msg]
  (println (util/console-type msg) ":"       ; "log", "error", "warning", etc.
           (util/console-text msg))
  ;; (util/console-args msg)                 ; vector of JSHandle
  ;; (util/console-location msg)             ; {:url ... :line-number ... :column-number ...}
  ;; (util/console-page msg)
  ))

;; Tracing
(let [tracing (util/context-tracing ctx)]
  (util/tracing-start! tracing {:screenshots true :snapshots true :sources true})
  ;; ... test actions ...
  (util/tracing-stop! tracing {:path "trace.zip"}))

;; Clock manipulation (for time-dependent tests)
(util/clock-install! (util/page-clock pg))
(util/clock-set-fixed-time! (util/page-clock pg) "2024-01-01T00:00:00Z")
(util/clock-set-system-time! (util/page-clock pg) "2024-06-15T12:00:00Z")
(util/clock-fast-forward! (util/page-clock pg) 60000)   ; ms
(util/clock-pause-at! (util/page-clock pg) "2024-01-01")
(util/clock-resume! (util/page-clock pg))

;; CDP (Chrome DevTools Protocol)
;; Requires Chromium browser
(let [session (util/cdp-send pg "Runtime.evaluate" {:expression "1+1"})]
  ;; (util/cdp-on session "Network.requestWillBeSent" handler-fn)
  ;; (util/cdp-detach! session)
  )

;; Video recording
(let [video (page/video pg)]
  (util/video-path video)
  (util/video-save-as! video "/tmp/recording.webm")
  (util/video-delete! video))

;; Workers (Web Workers / Service Workers)
(doseq [w (page/workers pg)]
  (println "Worker URL:" (util/worker-url w))
  (println "Eval:" (util/worker-evaluate w "self.name")))

;; File chooser (Java interop — no Clojure wrapper for waitForFileChooser)
(let [fc (.waitForFileChooser ^Page pg (reify Runnable (run [_] (locator/click (page/locator pg "input[type=file]")))))]
  (util/file-chooser-set-files! fc "/path/to/file.txt")
  ;; (util/file-chooser-page fc)
  ;; (util/file-chooser-element fc)
  ;; (util/file-chooser-is-multiple? fc)
  )

;; Selectors engine
(util/selectors-register! (util/selectors pg) "my-engine" {:script "..."})

;; Web errors
(page/on-page-error pg (fn [err]
  ;; (util/web-error-page err)
  ;; (util/web-error-error err)
  ))
```

### File Input

```clojure
;; Single file
(locator/set-input-files! (page/locator pg "input[type=file]") "/path/to/file.txt")

;; Multiple files
(locator/set-input-files! (page/locator pg "input[type=file]") ["/path/a.txt" "/path/b.txt"])
```

### API Testing (api namespace)

```clojure
(require '[com.blockether.spel.api :as api])

;; Single API context
(api/with-api-context [ctx (api/new-api-context (api/api-request pw)
                             {:base-url "https://api.example.com"
                              :extra-http-headers {"Authorization" "Bearer token"}})]
  (let [resp (api/api-get ctx "/users")]
    (println (api/api-response-status resp))     ; 200
    (println (api/api-response-text resp))))      ; JSON body

;; Multiple API contexts
(api/with-api-contexts
  [users   (api/new-api-context (api/api-request pw) {:base-url "https://users.example.com"})
   billing (api/new-api-context (api/api-request pw) {:base-url "https://billing.example.com"})]
  (api/api-get users "/me")
  (api/api-get billing "/invoices"))

;; HTTP methods
(api/api-get ctx "/users" {:params {:page 1}})
(api/api-post ctx "/users" {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
(api/api-put ctx "/users/1" {:data "{\"name\":\"Bob\"}"})
(api/api-patch ctx "/users/1" {:data "{\"name\":\"Charlie\"}"})
(api/api-delete ctx "/users/1")
(api/api-head ctx "/health")
(api/api-fetch ctx "/resource" {:method "OPTIONS"})

;; Form data
(let [fd (api/form-data)]
  (api/fd-set fd "name" "Alice")
  (api/fd-append fd "tag" "clojure")
  (api/api-post ctx "/submit" {:form fd}))
;; Or from map:
(api/api-post ctx "/submit" {:form (api/map->form-data {:name "Alice" :email "a@b.c"})})

;; Response inspection
(let [resp (api/api-get ctx "/users")]
  (api/api-response-status resp)         ; 200
  (api/api-response-status-text resp)    ; "OK"
  (api/api-response-url resp)
  (api/api-response-ok? resp)            ; true
  (api/api-response-headers resp)        ; {"content-type" "..."}
  (api/api-response-text resp)           ; body string
  (api/api-response-body resp)           ; byte[]
  (api/api-response->map resp))          ; {:status 200 :ok? true :headers {...} :body "..."}

;; Hooks (request/response interceptors)
(api/with-hooks
  {:on-request  (fn [method url opts] (println "→" method url) opts)
   :on-response (fn [method url resp] (println "←" method (api/api-response-status resp)) resp)}
  (api/api-get ctx "/users"))

;; Retry with backoff
(api/retry #(api/api-get ctx "/flaky")
  {:max-attempts 5 :delay-ms 1000 :backoff :linear
   :retry-when (fn [r] (= 429 (:status (api/api-response->map r))))})
;; Or with macro:
(api/with-retry {:max-attempts 3 :delay-ms 200}
  (api/api-post ctx "/endpoint" {:json {:action "process"}}))

;; Standalone request (no context setup needed)
(api/request! pw :get "https://api.example.com/health")
(api/request! pw :post "https://api.example.com/users"
  {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
```

### Allure Test Reporting (allure namespace)

```clojure
(require '[com.blockether.spel.allure :as allure])

;; Labels (call inside test body)
(allure/epic "E2E Testing")
(allure/feature "Authentication")
(allure/story "Login Flow")
(allure/severity :critical)          ; :blocker :critical :normal :minor :trivial
(allure/owner "team@example.com")
(allure/tag "smoke")
(allure/description "Tests the complete login flow")
(allure/link "Docs" "https://example.com/docs")
(allure/issue "BUG-123" "https://github.com/example/issues/123")
(allure/tms "TC-456" "https://tms.example.com/456")
(allure/parameter "browser" "chromium")

;; Steps
(allure/step "Navigate to login page"
  (page/navigate pg "https://example.com/login"))

;; Nested steps
(allure/step "Login flow"
  (allure/step "Enter credentials"
    (locator/fill (page/locator pg "#user") "admin")
    (locator/fill (page/locator pg "#pass") "secret"))
  (allure/step "Submit"
    (locator/click (page/locator pg "#submit"))))

;; UI step (auto-captures before/after screenshots, requires *page* binding)
(allure/ui-step "Fill login form"
  (locator/fill username-input "admin")
  (locator/fill password-input "secret")
  (locator/click submit-btn))

;; API step (auto-attaches response details: status, headers, body)
(allure/api-step "Create user"
  (api/api-post ctx "/users" {:json {:name "Alice" :age 30}}))

;; Attachments
(allure/attach "Request Body" "{\"key\":\"value\"}" "application/json")
(allure/attach-bytes "Screenshot" (page/screenshot pg) "image/png")
(allure/screenshot pg "After navigation")        ; convenience: attach PNG screenshot
(allure/attach-api-response! resp)               ; attach full API response
```

---

## Codegen - JSONL to Clojure

Transforms Playwright `codegen --target=jsonl` recordings into idiomatic Clojure.

### Workflow

```bash
# 1. Record interactions (opens browser, saves to recording.jsonl)
spel codegen --target=jsonl -o recording.jsonl https://example.com

# 2. Transform JSONL to Clojure test
spel codegen transform recording.jsonl > my_test.clj
spel codegen transform --format=script recording.jsonl
spel codegen transform --format=body recording.jsonl
```

### Formats

| Format | Output |
|--------|--------|
| `:test` (default) | Full Lazytest file with `defdescribe`/`it`, `with-playwright`/`with-browser`/`with-context`/`with-page` |
| `:script` | Standalone script with `require`/`import` + `with-playwright` chain |
| `:body` | Just action lines for pasting into existing code |

### Supported Actions

| Action | Codegen Output |
|--------|---------------|
| `navigate` | `(page/navigate pg "url")` |
| `click` | `(locator/click loc)` with modifiers, button, position |
| `click` (dblclick) | `(locator/dblclick loc)` when clickCount=2 |
| `click` (N>2) | `(locator/click loc {:click-count N})` |
| `fill` | `(locator/fill loc "text")` |
| `press` | `(locator/press loc "key")` with modifier combos |
| `hover` | `(locator/hover loc)` with optional position |
| `check`/`uncheck` | `(locator/check loc)` / `(locator/uncheck loc)` |
| `select` | `(locator/select-option loc "value")` |
| `setInputFiles` | `(locator/set-input-files! loc "path")` or vector |
| `assertText` | `(assert/has-text (assert/assert-that loc) "text")` |
| `assertChecked` | `(assert/is-checked (assert/assert-that loc))` |
| `assertVisible` | `(assert/is-visible (assert/assert-that loc))` |
| `assertValue` | `(assert/has-value (assert/assert-that loc) "val")` |
| `assertSnapshot` | `(assert/matches-aria-snapshot (assert/assert-that loc) "snapshot")` |

### Signal Handling

| Signal | Codegen Pattern |
|--------|----------------|
| `dialog` | `(page/on-dialog pg (fn [dialog] (.dismiss dialog)))` BEFORE action |
| `popup` | `(let [popup-pg (.waitForPopup ^Page pg (reify Runnable ...))] ...)` AROUND action |
| `download` | `(let [download (.waitForDownload ^Page pg (reify Runnable ...))] ...)` AROUND action |

### Frame Navigation in Codegen

`framePath` array generates chained `.contentFrame()` calls:

```clojure
;; framePath: ["iframe.outer", "iframe.inner"]
(let [fl0 (.contentFrame (page/locator pg "iframe.outer"))
      fl1 (.contentFrame (.locator fl0 "iframe.inner"))]
  (locator/click (.locator fl1 "button")))
```

### Hard Errors

Codegen dies immediately on:
- Unknown action types
- Unknown signal types
- Unrecognized locator formats
- Missing locator/selector data

In CLI mode: prints full action data + `System/exit 1`.
In library mode: throws `ex-info` with `:codegen/error` and `:codegen/action`.

---

## CLI

Wraps Playwright CLI commands via the `spel` native binary.

> **Note**: `spel install` delegates to `com.microsoft.playwright.CLI`, which is a thin shim that spawns the same Node.js Playwright CLI that `npx playwright` uses. The driver version is pinned to the Playwright Java dependency (1.58.0), so browser versions always match.

```bash
spel install                        # Install browsers (Chromium by default)
spel install --with-deps chromium   # Install with system dependencies
spel codegen URL                    # Record interactions
spel open URL                       # Open browser
spel screenshot URL                 # Take screenshot
```

---

## Page Exploration (spel)

The `spel` CLI provides comprehensive page exploration capabilities without writing code.

### Basic Exploration Workflow

```bash
# 1. Navigate to a page
spel open https://example.com

# 2. Get accessibility snapshot with numbered refs (e1, e2, etc.)
spel snapshot

# 3. Take a screenshot for visual reference
spel screenshot page.png
```

### Snapshot Command

The primary exploration tool - returns an ARIA accessibility tree with numbered refs:

```bash
spel snapshot                           # Full accessibility tree
spel snapshot -i                        # Interactive elements only
spel snapshot -i -c                     # Compact format
spel snapshot -i -c -d 3               # Limit depth to 3 levels
spel snapshot -i -C                     # Include cursor/pointer elements
spel snapshot -s "#main"               # Scoped to CSS selector
```

**Output format:**
```
- heading "Example Domain" [@e1] [level=1]
- link "More information..." [@e2]
- button "Submit" [@e3]
```

### Get Page Information

```bash
spel get url                           # Current URL
spel get title                         # Page title
spel get text @e1                      # Text content of ref e1
spel get html @e1                      # Inner HTML
spel get value @e2                     # Input value
spel get attr @e1 href                 # Attribute value
spel get count ".items"               # Count matching elements
spel get box @e1                       # Bounding box {x, y, width, height}
```

### Check Element State

```bash
spel is visible @e1                    # Check visibility
spel is enabled @e1                    # Check if enabled
spel is checked @e3                    # Check checkbox state
```

### Find Elements (Semantic Locators)

Find and interact in one command:

```bash
# Find by ARIA role
spel find role button click
spel find role button click --name "Submit"

# Find by text content
spel find text "Login" click

# Find by label
spel find label "Email" fill "test@example.com"

# Position-based
spel find first ".item" click
spel find last ".item" click
spel find nth 2 ".item" click
```

### Visual Exploration

```bash
spel screenshot                        # Screenshot to stdout (base64)
spel screenshot shot.png              # Save to file
spel screenshot -f full.png           # Full page screenshot
spel pdf page.pdf                     # Save as PDF (Chromium only)
spel highlight @e1                    # Highlight element visually
```

### Network Exploration

```bash
spel network requests                  # View all captured requests
spel network requests --type fetch    # Filter by type (document, script, fetch, image, etc.)
spel network requests --method POST   # Filter by HTTP method
spel network requests --status 2      # Filter by status prefix (2=2xx, 4=4xx)
spel network requests --filter "/api" # Filter by URL regex
spel network clear                    # Clear captured requests
```

### JavaScript Evaluation

```bash
# Run JavaScript
spel eval "document.title"
spel eval "document.querySelector('h1').textContent"

# Base64-encoded result
spel eval "JSON.stringify([...document.querySelectorAll('a')].map(a => ({text: a.textContent, href: a.href})))" -b
```

### Console & Errors

Console messages and page errors are auto-captured from the moment a page opens. No `start` command needed.

```bash
spel console                           # View captured console messages
spel console clear                     # Clear captured messages

spel errors                            # View captured page errors
spel errors clear                      # Clear captured errors
```

### Complete Exploration Example

```bash
# Open page
spel open https://example.com

# Get initial snapshot
spel snapshot -i

# Take screenshot
spel screenshot initial.png

# Get page info
spel get title
spel get url

# Check specific element
spel get text @e2
spel is visible @e3

# Interact and re-snapshot
spel click @e2
spel snapshot -i

# View network activity
spel network requests

# Close browser when done
spel close
```

---

## Agent Scaffolding (init-agents)

Scaffolds agent definitions for Playwright E2E testing into any consuming project. Equivalent to Playwright's `npx playwright init-agents --loop=<target>` but for the Clojure/Lazytest/spel stack.

Supports three editor targets via `--loop`:

```bash
spel init-agents                      # OpenCode (default)
spel init-agents --loop=claude        # Claude Code
spel init-agents --loop=vscode        # VS Code / Copilot
```

### CLI Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude`, `vscode` |
| `--ns NS` | dir name | Base namespace for generated tests (e.g. `my-app` → `my-app.e2e.seed-test`) |
| `--dry-run` | - | Preview files without writing |
| `--force` | - | Overwrite existing files |
| `--test-dir DIR` | `test/e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory (colocated with tests) |
| `-h, --help` | - | Show help |

### Generated Files by Target

**`--loop=opencode`** (default):

| File | Purpose |
|------|---------|
| `.opencode/agents/spel-test-planner.md` | Explores app, writes structured test plans to `test-e2e/specs/` |
| `.opencode/agents/spel-test-generator.md` | Reads test plans, generates Clojure Lazytest code using spel |
| `.opencode/agents/spel-test-healer.md` | Runs failing tests, diagnoses issues, applies fixes |
| `.opencode/prompts/spel-test-workflow.md` | Orchestrator prompt: plan → generate → heal cycle |
| `.opencode/skills/spel/SKILL.md` | Copy of this API reference skill (so agents work out-of-the-box) |

**`--loop=claude`**:

| File | Purpose |
|------|---------|
| `.claude/agents/spel-test-planner.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/agents/spel-test-generator.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/agents/spel-test-healer.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/prompts/spel-test-workflow.md` | Orchestrator prompt |
| `.claude/docs/spel/SKILL.md` | API reference |

**`--loop=vscode`**:

| File | Purpose |
|------|---------|
| `.github/agents/spel-test-planner.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/agents/spel-test-generator.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/agents/spel-test-healer.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/prompts/spel-test-workflow.md` | Orchestrator prompt |
| `.github/docs/spel/SKILL.md` | API reference |

**All targets** also generate:

| File | Purpose |
|------|---------|
| `test-e2e/specs/README.md` | Test plans directory README (colocated with tests) |
| `test-e2e/<ns>/e2e/seed_test.clj` | Seed test with `com.blockether.spel` replaced by `--ns` value (or directory name) |

### Agent Workflow

Three subagents work together in a plan → generate → heal loop:

1. **@spel-test-planner** — Explores the app using `spel` CLI commands (e.g., `spel snapshot`) and inline Clojure scripts with spel. Catalogs pages/flows, writes structured test plans as markdown files in `test-e2e/specs/`. Uses `spel` skill for API reference.

2. **@spel-test-generator** — Reads test plans from `test-e2e/specs/`, generates Clojure Lazytest test files using `spel` test fixtures (`{:context [with-playwright with-browser with-page]}`) and `*page*` dynamic var. Verifies selectors with inline scripts and runs tests to confirm. Outputs to `test-e2e/`.

3. **@spel-test-healer** — Runs tests via `clojure -M:test`, captures failures, uses `spel` CLI commands and inline scripts for investigation, diagnoses root causes (stale selectors, timing, missing setup), and applies targeted fixes. Loops until green.

**Orchestration**: Use the `spel-test-workflow` prompt to trigger the full cycle, or invoke individual agents with `@agent-name`.

**No external dependencies**: All agents use spel directly — no Agent Browser or external MCP tools needed.

### OpenCode Agent Frontmatter Spec

Agent markdown files use YAML frontmatter. The `color` field accepts:

| Format | Example | Notes |
|--------|---------|-------|
| **Hex color** | `"#FF5733"` | Must be quoted, include `#` prefix |
| **Theme color** | `primary`, `secondary`, `accent`, `success`, `warning`, `error`, `info` | Unquoted |

**PROHIBITED:** Named CSS colors (`blue`, `red`, `green`, etc.) are **NOT valid** and cause OpenCode validation errors: `Invalid hex color format color`.

All agent frontmatter fields:

| Field | Required | Values |
|-------|----------|--------|
| `description` | Yes | Brief description of the agent |
| `mode` | Yes | `primary`, `subagent`, or `all` |
| `color` | No | Hex (`"#RRGGBB"`) or theme color name |
| `model` | No | `provider/model-id` |
| `temperature` | No | `0.0` - `1.0` |
| `steps` | No | Max agentic iterations |
| `hidden` | No | `true`/`false` — hide from `@` menu |
| `tools` | No | Map of tool → `true`/`false` |
| `permission` | No | Map of tool → `allow`/`ask`/`deny` or glob patterns |

### Template System

Templates use `.clj.template` extension (not `.clj`) to avoid clojure-lsp parsing `com.blockether.spel` placeholders as Clojure code. The `process-template` function replaces `com.blockether.spel` with the `--ns` value (or falls back to the consuming project's directory name).

---

## Testing Conventions

- Framework: **Lazytest** (`defdescribe`, `describe`, `it`, `expect`)
- Fixtures: **Lazytest `:context`** with shared `around` hooks from `test-fixtures`
- Assertions: **Exact string matching** (NEVER substring unless explicitly `contains-text`)
- Import: `[com.microsoft.playwright.options AriaRole]` for role-based locators
- Integration tests: Live against `example.com`

### Test Fixtures

The project provides shared `around` hooks in `com.blockether.spel.test-fixtures`:

| Fixture | Binds | Scope |
|---------|-------|-------|
| `with-playwright` | `*pw*` | Shared Playwright instance |
| `with-browser` | `*browser*` | Shared headless Chromium browser |
| `with-page` | `*page*` | Fresh page per `it` block (auto-cleanup, auto-tracing with Allure) |
| `with-traced-page` | `*page*` | Like `with-page` but always enables tracing/HAR |
| `with-test-server` | `*test-server-url*` | Local HTTP test server |

Use `{:context [with-playwright with-browser with-page]}` on `describe` blocks. NEVER nest `with-playwright`/`with-browser`/`with-page` manually inside `it` blocks.

### Test Example

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [lazytest.core :refer [defdescribe describe expect it before-each]])
  (:import
   [com.microsoft.playwright.options AriaRole]))

(defdescribe my-test
  (describe "example.com"
    {:context [with-playwright with-browser with-page]}

    (it "navigates and asserts"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*)))
      (expect (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Example Domain"))))))
```

---

## Native Image CLI

The library includes a GraalVM native-image compiled binary for instant-start browser automation via CLI.

### Build & Run

```bash
# Build native binary
clojure -T:build uberjar
clojure -T:build native-image

# Install Playwright browsers
./target/spel install
```

### CLI Commands

The CLI uses a daemon process to keep the browser alive between invocations. The daemon auto-starts on first command.

```bash
# Navigation
spel open <url>                        # Navigate (aliases: goto, navigate)
spel open <url> --interactive          # Navigate with visible browser
spel back                              # Go back
spel forward                           # Go forward
spel reload                            # Reload page

# Snapshot (ARIA accessibility tree with refs)
spel snapshot                          # Full accessibility tree with refs
spel snapshot -i                       # Interactive elements only
spel snapshot -i -c -d 5              # Compact, depth-limited
spel snapshot -i -C                    # Interactive + cursor elements
spel snapshot -s "#main"              # Scoped to selector

# Element interactions (by ref from snapshot)
spel click @e1                         # Click element by ref or selector
spel dblclick @e1                      # Double-click
spel fill @e2 "text"                   # Clear and fill input
spel type @e2 "text"                   # Type without clearing
spel clear @e2                         # Clear input
spel hover @e1                         # Hover element
spel check @e3                         # Check checkbox
spel uncheck @e3                       # Uncheck checkbox
spel select @e4 "opt1"                 # Select dropdown option
spel focus @e1                         # Focus element
spel press Enter                       # Press key (Enter, Tab, Control+a)
spel press @e1 Tab                     # Press key on element (alias: key)
spel keydown Shift                     # Hold key down
spel keyup Shift                       # Release key
spel scroll down 500                   # Scroll (up/down/left/right)
spel scrollintoview @e1                # Scroll element into view
spel drag @e1 @e2                      # Drag and drop
spel upload @e1 file.txt               # Upload files

# Screenshots & PDF
spel screenshot                        # Screenshot to stdout (base64)
spel screenshot shot.png               # Screenshot to file
spel screenshot -f shot.png            # Full page screenshot
spel pdf page.pdf                      # Save as PDF (Chromium only)

# JavaScript
spel eval "document.title"             # Evaluate JavaScript
spel eval "document.title" -b          # Evaluate, base64-encode result

# Wait
spel wait @e1                          # Wait for element visible
spel wait 2000                         # Wait for timeout (ms)
spel wait --text "Welcome"             # Wait for text to appear
spel wait --url "**/dash"              # Wait for URL pattern
spel wait --load networkidle           # Wait for load state
spel wait --fn "window.ready"          # Wait for JS condition

# Get Info
spel get text @e1                      # Get text content
spel get html @e1                      # Get innerHTML
spel get value @e1                     # Get input value
spel get attr @e1 href                 # Get attribute value
spel get url                           # Get current URL
spel get title                         # Get page title
spel get count ".items"                # Count matching elements
spel get box @e1                       # Get bounding box

# Check State
spel is visible @e1                    # Check visibility
spel is enabled @e1                    # Check enabled state
spel is checked @e1                    # Check checked state

# Find (Semantic Locators)
spel find role <role> <action>         # By ARIA role
spel find text <text> <action>         # By text content
spel find label <text> <action> [val]  # By label
spel find role button click --name Submit  # With name filter
spel find first/last/nth <sel> <action>    # Position-based

# Mouse Control
spel mouse move 100 200               # Move mouse
spel mouse down                        # Press mouse button
spel mouse up                          # Release mouse button
spel mouse wheel 100                   # Scroll wheel

# Browser Settings
spel set viewport 1280 720            # Set viewport size
spel set device "iphone 14"           # Emulate device
spel set geo 37.7 -122.4              # Set geolocation
spel set offline on                    # Toggle offline mode
spel set headers '{"X-Key":"val"}'    # Extra HTTP headers
spel set credentials user pass         # HTTP basic auth
spel set media dark                    # Emulate color scheme (dark/light)

# Cookies & Storage
spel cookies                           # Get all cookies
spel cookies set name value            # Set cookie
spel cookies clear                     # Clear cookies
spel storage local [key]               # Get localStorage
spel storage local set key value       # Set localStorage
spel storage local clear               # Clear localStorage
spel storage session [key]             # Same for sessionStorage

# Network
spel network route <url>               # Intercept requests
spel network route <url> --abort       # Block requests
spel network route <url> --body <json> # Mock response
spel network unroute [url]             # Remove routes
spel network requests                  # View all tracked requests (auto-tracked, last 500)
spel network requests --filter <regex> # Filter by URL regex
spel network requests --type <type>    # Filter by type (document, script, fetch, image, stylesheet, font, xhr)
spel network requests --method <method>  # Filter by HTTP method (GET, POST, etc.)
spel network requests --status <prefix>  # Filter by status prefix (2=2xx, 30=30x, 404=exact)
spel network requests --type fetch --status 4  # Combine filters
spel network clear                     # Clear tracked requests

# Tabs & Windows
spel tab                               # List tabs
spel tab new [url]                     # New tab
spel tab <n>                           # Switch to tab
spel tab close                         # Close tab

# Frames & Dialogs
spel frame <sel>                       # Switch to iframe
spel frame main                        # Back to main frame
spel frame list                        # List all frames
spel dialog accept [text]              # Accept dialog
spel dialog dismiss                    # Dismiss dialog

# Debug
spel connect <url>                     # Connect to browser via CDP
spel trace start / trace stop          # Record trace
spel console / console clear           # View/clear console (auto-captured)
spel errors / errors clear             # View/clear errors (auto-captured)
spel highlight @e1                     # Highlight element

# State Management
spel state save [path]                 # Save auth/storage state
spel state load [path]                 # Load saved state
spel state list                        # List state files
spel state show <file>                 # Show state file contents
spel state rename <old> <new>          # Rename state file
spel state clear [--all]               # Clear state files
spel state clean [--older-than N]      # Remove states older than N days

# Sessions
spel --session <name> <cmd>            # Use named session
spel session                           # Show current session info
spel session list                      # List active sessions

# Utility
spel install [--with-deps]             # Install Playwright browsers
spel version                           # Show version
spel close                             # Close browser + daemon (aliases: quit, exit)
```

### Global Flags

```bash
spel open <url> --interactive           # Show browser window (headed mode)
spel --session work open <url>         # Named session (default: "default")
spel --json get url                    # JSON output (for agents)
spel --profile /path open <url>        # Persistent browser profile
spel --executable-path /path open <url>  # Custom browser executable
spel --user-agent "Bot/1.0" open <url> # Custom user agent string
spel --proxy http://proxy:8080 open <url>  # Proxy server URL
spel --proxy-bypass "*.local" open <url>   # Proxy bypass domains
spel --headers '{"X-Key":"val"}' open <url>  # HTTP headers
spel --args "no-sandbox,disable-gpu" open <url>  # Browser args (comma-separated)
spel --cdp ws://... open <url>         # Connect via CDP endpoint
spel --ignore-https-errors open <url>  # Ignore HTTPS errors
spel --allow-file-access open <url>    # Allow file:// access
spel --debug open <url>                # Debug output
```

### Eval / Script Mode (SCI)

`spel --eval` evaluates Clojure code via SCI (Small Clojure Interpreter) embedded in the native binary. No JVM startup needed.

**Daemon-backed**: Eval mode uses the same daemon as CLI commands. The browser persists between `--eval` invocations — no restart penalty. `spel/start!` is optional (no-op if a daemon browser is already running). `spel/stop!` does not kill the daemon's browser.

**`--autoclose` flag**: Add `--autoclose` to shut down the daemon after eval (old behavior). Without it, the browser stays alive for the next invocation.

**`--session` flag**: Use `--session <name>` to target a specific daemon session (default: "default"). Matches the CLI `--session` flag.

**Error handling**: In `--eval` mode, Playwright errors throw immediately (short-circuiting `(do ...)` forms) and the process exits with code 1.

**Timeout control**: Use `--timeout <ms>` to set Playwright's default action timeout (default: 30s):

```bash
# Basic eval — no browser needed
spel --eval '(+ 1 2)'

# Browser persists between calls (daemon-backed)
spel --eval '(spel/goto "https://example.com") (spel/title)'

# start! is optional — works but is a no-op when daemon has a page
spel --eval '(spel/start!) (spel/goto "https://example.com") (spel/title)'

# With timeout
spel --timeout 5000 --eval '(do (spel/goto "https://example.com") (spel/text "h1"))'

# Kill daemon after eval (old behavior)
spel --autoclose --eval '(spel/goto "https://example.com") (spel/title)'

# Use a named session
spel --session work --eval '(spel/goto "https://example.com")'
```

#### Available Namespaces

Nine namespaces are pre-registered:

| Namespace | Purpose |
|-----------|---------|
| `pw` | Simplified browser automation (lifecycle, navigation, actions, content, assertions — implicit page) |
| `snapshot` | Accessibility snapshot capture and ref resolution |
| `annotate` | Page annotation overlays (visible elements only) |
| `input` | Keyboard, Mouse, Touchscreen operations (explicit device arg) |
| `frame` | Frame and FrameLocator operations (explicit Frame/FrameLocator arg) |
| `net` | Network request/response/route inspection (explicit object arg) |
| `loc` | Raw locator operations (explicit Locator arg — no implicit page) |
| `assert` | Playwright assertion functions (explicit assertion object arg) |
| `core` | Browser lifecycle utilities and resource management |

#### `pw` Namespace — Full API

**Lifecycle:**

| Function | Description |
|----------|-------------|
| `(spel/start!)` | Start browser session (headless). No-op if daemon already has a page. |
| `(spel/start! {:headless false})` | Start headed browser |
| `(spel/start! {:browser :firefox})` | Start Firefox (`:chromium`, `:firefox`, `:webkit`) |
| `(spel/start! {:viewport {:width 1280 :height 720}})` | Custom viewport |
| `(spel/start! {:timeout 5000})` | Set default action timeout (ms) |
| `(spel/stop!)` | Stop browser. In daemon mode: nils SCI atoms without killing daemon's browser. |
| `(spel/restart!)` | Stop then start fresh |
| `(spel/new-tab!)` | Open new tab, switch to it |
| `(spel/switch-tab! 0)` | Switch to tab by index |
| `(spel/tabs)` | List all tabs `[{:index 0 :url "..." :title "..." :active? true}]` |

**Navigation:**

| Function | Description |
|----------|-------------|
| `(spel/goto "https://example.com")` | Navigate to URL |
| `(spel/goto "https://example.com" {:timeout 30000})` | Navigate with timeout |
| `(spel/back)` | Go back |
| `(spel/forward)` | Go forward |
| `(spel/reload!)` | Reload page |
| `(spel/url)` | Get current URL |
| `(spel/title)` | Get page title |
| `(spel/html)` | Get full page HTML |

**Locators:**

| Function | Description |
|----------|-------------|
| `(spel/$ "css-selector")` | Locate by CSS selector (also accepts Locator pass-through) |
| `(spel/$$ "css-selector")` | Locate all matching elements |
| `(spel/$text "Click me")` | Locate by text content |
| `(spel/$role AriaRole/BUTTON)` | Locate by ARIA role |
| `(spel/$label "Email")` | Locate by label |
| `(spel/$placeholder "Search")` | Locate by placeholder |
| `(spel/$test-id "submit-btn")` | Locate by test ID |
| `(spel/$alt-text "alt text")` | Locate by alt text |
| `(spel/$title-attr "title")` | Locate by title attribute |

**Actions:**

| Function | Description |
|----------|-------------|
| `(spel/click "selector")` | Click element |
| `(spel/click "selector" {:click-count 2})` | Click with options |
| `(spel/dblclick "selector")` | Double-click |
| `(spel/fill "selector" "text")` | Clear + fill input |
| `(spel/type-text "selector" "text")` | Type without clearing |
| `(spel/press "selector" "Enter")` | Press key on element |
| `(spel/clear "selector")` | Clear input |
| `(spel/check "selector")` | Check checkbox |
| `(spel/uncheck "selector")` | Uncheck checkbox |
| `(spel/hover "selector")` | Hover element |
| `(spel/focus "selector")` | Focus element |
| `(spel/select "selector" "value")` | Select dropdown option |
| `(spel/blur "selector")` | Blur element |
| `(spel/tap "selector")` | Tap element (touch) |
| `(spel/set-input-files! "selector" files)` | Set file input |
| `(spel/scroll-into-view "selector")` | Scroll element into view |
| `(spel/dispatch-event "selector" "click")` | Dispatch DOM event |
| `(spel/drag-to "source" "target")` | Drag element to target |
| `(spel/highlight "selector")` | Highlight element |
| `(spel/locator-screenshot "selector")` | Screenshot specific element |

**Content & State:**

| Function | Description |
|----------|-------------|
| `(spel/text "selector")` | Get text content |
| `(spel/inner-text "selector")` | Get inner text |
| `(spel/inner-html "selector")` | Get inner HTML |
| `(spel/attr "selector" "href")` | Get attribute value |
| `(spel/value "selector")` | Get input value |
| `(spel/count-of "selector")` | Count matching elements |
| `(spel/visible? "selector")` | Check visibility |
| `(spel/hidden? "selector")` | Check if hidden |
| `(spel/enabled? "selector")` | Check if enabled |
| `(spel/disabled? "selector")` | Check if disabled |
| `(spel/editable? "selector")` | Check if editable |
| `(spel/checked? "selector")` | Check if checked |
| `(spel/bbox "selector")` | Get bounding box `{:x :y :width :height}` |
| `(spel/all-text-contents "selector")` | Get all matching texts as vector |
| `(spel/all-inner-texts "selector")` | Get all inner texts as vector |
| `(spel/info)` | Get page info `{:url :title :viewport :closed?}` |

**Locator Filtering:**

| Function | Description |
|----------|-------------|
| `(spel/loc-filter (spel/$ "li") {:has-text "Item"})` | Filter locator |
| `(spel/first (spel/$ "li"))` | First matching element |
| `(spel/last (spel/$ "li"))` | Last matching element |
| `(spel/nth (spel/$ "li") 2)` | Nth element (0-indexed) |
| `(spel/loc-locator (spel/$ "div") "span")` | Sub-locator |
| `(spel/loc-get-by-text loc "text")` | Sub-locate by text |
| `(spel/loc-get-by-role loc AriaRole/BUTTON)` | Sub-locate by role |
| `(spel/loc-get-by-label loc "Email")` | Sub-locate by label |
| `(spel/loc-get-by-test-id loc "id")` | Sub-locate by test ID |
| `(spel/loc-wait-for loc)` | Wait for locator |
| `(spel/evaluate-locator loc "el => el.id")` | Evaluate JS on locator element |
| `(spel/evaluate-all-locs loc "els => els.length")` | Evaluate JS on all matching |

**JavaScript:**

| Function | Description |
|----------|-------------|
| `(spel/eval-js "document.title")` | Evaluate JavaScript expression |
| `(spel/eval-js "([a,b]) => a+b" [1 2])` | Evaluate with argument |
| `(spel/evaluate-handle "document.body")` | Evaluate returning JSHandle |

**Screenshots & PDF:**

| Function | Description |
|----------|-------------|
| `(spel/screenshot)` | Screenshot as bytes |
| `(spel/screenshot "path.png")` | Screenshot to file |
| `(spel/screenshot {:path "p.png" :full-page true})` | Full page screenshot |
| `(spel/pdf)` | PDF as bytes |
| `(spel/pdf "page.pdf")` | PDF to file |

**Waiting:**

| Function | Description |
|----------|-------------|
| `(spel/wait-for "selector")` | Wait for element visible |
| `(spel/wait-for "selector" {:state "hidden"})` | Wait for element hidden |
| `(spel/wait-for-load)` | Wait for load state |
| `(spel/wait-for-load "networkidle")` | Wait for specific load state |
| `(spel/sleep 1000)` | Wait for timeout (ms) |
| `(spel/wait-for-url "**/dashboard")` | Wait for URL pattern |
| `(spel/wait-for-function "() => document.ready")` | Wait for JS predicate |

**Assertions:**

| Function | Description |
|----------|-------------|
| `(spel/assert-text "selector" "expected")` | Assert element has text |
| `(spel/assert-visible "selector")` | Assert element is visible |
| `(spel/assert-hidden "selector")` | Assert element is hidden |
| `(spel/assert-title "My Page")` | Assert page title |
| `(spel/assert-url "https://...")` | Assert page URL |
| `(spel/assert-count "selector" 5)` | Assert element count |
| `(spel/assert-that loc-or-page)` | Create assertion object |
| `(spel/assert-not (spel/assert-that loc))` | Negate assertion |
| `(spel/assert-contains-text "sel" "partial")` | Assert contains text (substring) |
| `(spel/assert-attr "sel" "href" "val")` | Assert attribute value |
| `(spel/assert-class "sel" "active")` | Assert CSS class |
| `(spel/assert-contains-class "sel" "btn")` | Assert contains CSS class |
| `(spel/assert-css "sel" "color" "red")` | Assert CSS property |
| `(spel/assert-id "sel" "my-id")` | Assert element ID |
| `(spel/assert-js-property "sel" "value" "x")` | Assert JS property |
| `(spel/assert-value "sel" "text")` | Assert input value |
| `(spel/assert-values "sel" ["a" "b"])` | Assert select values |
| `(spel/assert-role "sel" AriaRole/BUTTON)` | Assert ARIA role |
| `(spel/assert-accessible-name "sel" "Submit")` | Assert accessible name |
| `(spel/assert-accessible-description "sel" "desc")` | Assert accessible description |
| `(spel/assert-accessible-error-message "sel" "err")` | Assert error message |
| `(spel/assert-matches-aria-snapshot "sel" "- button")` | Assert ARIA snapshot |
| `(spel/assert-attached "sel")` | Assert element attached to DOM |
| `(spel/assert-checked "sel")` | Assert checked |
| `(spel/assert-disabled "sel")` | Assert disabled |
| `(spel/assert-editable "sel")` | Assert editable |
| `(spel/assert-enabled "sel")` | Assert enabled |
| `(spel/assert-focused "sel")` | Assert focused |
| `(spel/assert-empty "sel")` | Assert empty |
| `(spel/assert-in-viewport "sel")` | Assert in viewport |
| `(spel/assert-page-not)` | Negate page assertion (implicit page) |
| `(spel/set-assertion-timeout! 5000)` | Set assertion timeout |

**Snapshot & Ref-based Actions:**

| Function | Description |
|----------|-------------|
| `(spel/snapshot)` | Capture accessibility snapshot with refs |
| `(spel/full-snapshot)` | Full snapshot including iframes |
| `(spel/resolve-ref "e1")` | Resolve ref to Locator |
| `(spel/clear-refs!)` | Clear ref assignments |
| `(spel/click-ref "e1")` | Click element by ref |
| `(spel/fill-ref "e2" "text")` | Fill input by ref |
| `(spel/type-ref "e2" "text")` | Type by ref |
| `(spel/hover-ref "e1")` | Hover by ref |
| `(spel/annotated-screenshot refs)` | Convenience: inject → screenshot → cleanup |
| `(spel/save-annotated-screenshot! refs "path.png")` | Convenience: annotated screenshot to file |

**Network:**

| Function | Description |
|----------|-------------|
| `(spel/last-response "url")` | Navigate and return response `{:status :ok? :url :headers}` |

**Page Functions:**

| Function | Description |
|----------|-------------|
| `(spel/set-content! "<h1>Hi</h1>")` | Set page HTML content |
| `(spel/set-viewport-size! 1280 720)` | Set viewport (width, height) |
| `(spel/viewport-size)` | Get viewport `{:width :height}` |
| `(spel/set-default-timeout! 5000)` | Set page default timeout |
| `(spel/set-default-navigation-timeout! 10000)` | Set navigation timeout |
| `(spel/emulate-media! {:media "print"})` | Emulate media type |
| `(spel/bring-to-front)` | Bring page to front |
| `(spel/set-extra-http-headers! {"X-Key" "val"})` | Set HTTP headers |
| `(spel/add-script-tag {:url "..."})` | Add script tag |
| `(spel/add-style-tag {:content "body{color:red}"})` | Add style tag |
| `(spel/expose-function! "fn" handler)` | Expose function to page JS |
| `(spel/expose-binding! "fn" handler)` | Expose binding to page JS |

**Page Events:**

| Function | Description |
|----------|-------------|
| `(spel/on-console handler)` | Listen for console messages |
| `(spel/on-dialog handler)` | Listen for dialogs |
| `(spel/on-page-error handler)` | Listen for page errors |
| `(spel/on-request handler)` | Listen for requests |
| `(spel/on-response handler)` | Listen for responses |
| `(spel/on-close handler)` | Listen for page close |
| `(spel/on-download handler)` | Listen for downloads |
| `(spel/on-popup handler)` | Listen for popups |

**Routing:**

| Function | Description |
|----------|-------------|
| `(spel/route! "**/api/**" handler)` | Intercept requests matching pattern |
| `(spel/unroute! "**/api/**")` | Remove route handler |

**Page Accessors:**

| Function | Description |
|----------|-------------|
| `(spel/page)` | Get raw Page object |
| `(spel/keyboard)` | Get Keyboard for current page |
| `(spel/mouse)` | Get Mouse for current page |
| `(spel/touchscreen)` | Get Touchscreen for current page |
| `(spel/page-context)` | Get BrowserContext from page |
| `(spel/frames)` | Get all frames |
| `(spel/main-frame)` | Get main frame |
| `(spel/frame-by-name "name")` | Find frame by name |
| `(spel/frame-by-url "url")` | Find frame by URL |

**Context & Browser:**

| Function | Description |
|----------|-------------|
| `(spel/context)` | Get raw BrowserContext |
| `(spel/browser)` | Get raw Browser |
| `(spel/context-cookies)` | Get cookies |
| `(spel/context-clear-cookies!)` | Clear cookies |
| `(spel/context-set-offline! true)` | Set offline mode |
| `(spel/context-grant-permissions! ["geolocation"])` | Grant permissions |
| `(spel/context-clear-permissions!)` | Clear permissions |
| `(spel/context-set-extra-http-headers! {"K" "V"})` | Context-level headers |
| `(spel/browser-connected?)` | Check browser connected |
| `(spel/browser-version)` | Get browser version |

#### `snapshot` Namespace

| Function | Description |
|----------|-------------|
| `(snapshot/capture)` | Capture snapshot for current page (implicit) |
| `(snapshot/capture page)` | Capture snapshot for explicit page |
| `(snapshot/capture-full)` | Full snapshot with iframes (implicit page) |
| `(snapshot/capture-full page)` | Full snapshot for explicit page |
| `(snapshot/resolve-ref "e1")` | Resolve ref to Locator (implicit page) |
| `(snapshot/clear-refs!)` | Clear refs (implicit page) |
| `(snapshot/ref-bounding-box page "e1")` | Get ref bounding box (requires explicit page) |

#### `annotate` Namespace

| Function | Description |
|----------|-------------|
| `(annotate/annotated-screenshot refs)` | Inject overlays → screenshot → cleanup (implicit page) |
| `(annotate/annotated-screenshot refs opts)` | With options (`:show-badges`, `:show-boxes`, `:show-dimensions`, `:full-page`) |
| `(annotate/save! refs "path.png")` | Annotated screenshot saved to file (implicit page) |
| `(annotate/save! refs "path.png" opts)` | Save with options |

#### `input` Namespace

Requires explicit Keyboard/Mouse/Touchscreen argument. Get devices via `(spel/keyboard)`, `(spel/mouse)`, `(spel/touchscreen)`.

| Function | Description |
|----------|-------------|
| `(input/key-press kb "Enter")` | Press key |
| `(input/key-type kb "text")` | Type text character by character |
| `(input/key-down kb "Shift")` | Hold key down |
| `(input/key-up kb "Shift")` | Release key |
| `(input/key-insert-text kb "text")` | Insert text without key events |
| `(input/mouse-click mouse 100 200)` | Click at coordinates |
| `(input/mouse-dblclick mouse 100 200)` | Double-click at coordinates |
| `(input/mouse-move mouse 100 200)` | Move mouse |
| `(input/mouse-down mouse)` | Press mouse button |
| `(input/mouse-up mouse)` | Release mouse button |
| `(input/mouse-wheel mouse 0 100)` | Scroll wheel (deltaX, deltaY) |
| `(input/touchscreen-tap ts 100 200)` | Tap at coordinates |

#### `frame` Namespace

For Frame and FrameLocator operations. Frame = a page frame; FrameLocator = CSS-based frame selector.

| Function | Description |
|----------|-------------|
| `(frame/navigate frame "url")` | Navigate frame |
| `(frame/content frame)` | Get frame HTML |
| `(frame/set-content! frame "<h1>Hi</h1>")` | Set frame HTML |
| `(frame/url frame)` | Get frame URL |
| `(frame/name frame)` | Get frame name |
| `(frame/title frame)` | Get frame title |
| `(frame/locator frame "selector")` | Create locator in frame |
| `(frame/get-by-text frame "text")` | Find by text in frame |
| `(frame/get-by-role frame AriaRole/BUTTON)` | Find by role in frame |
| `(frame/get-by-label frame "label")` | Find by label in frame |
| `(frame/get-by-test-id frame "id")` | Find by test ID in frame |
| `(frame/evaluate frame "expr")` | Evaluate JS in frame |
| `(frame/parent-frame frame)` | Get parent frame |
| `(frame/child-frames frame)` | Get child frames |
| `(frame/frame-page frame)` | Get page owning frame |
| `(frame/is-detached? frame)` | Check if frame detached |
| `(frame/wait-for-load-state frame)` | Wait for frame load |
| `(frame/wait-for-selector frame "sel")` | Wait for selector in frame |
| `(frame/wait-for-function frame "fn")` | Wait for JS predicate in frame |
| `(frame/frame-locator page "iframe")` | Create FrameLocator from CSS |
| `(frame/fl-locator fl "button")` | Locator inside FrameLocator |
| `(frame/fl-get-by-text fl "text")` | Find by text in FrameLocator |
| `(frame/fl-get-by-role fl AriaRole/BUTTON)` | Find by role in FrameLocator |
| `(frame/fl-get-by-label fl "label")` | Find by label in FrameLocator |
| `(frame/fl-first fl)` | First matching FrameLocator |
| `(frame/fl-last fl)` | Last matching FrameLocator |
| `(frame/fl-nth fl 2)` | Nth FrameLocator (0-indexed) |

#### `net` Namespace

For Request, Response, Route, WebSocket, and WebSocketRoute inspection. All take explicit objects.

**Request functions:**

| Function | Description |
|----------|-------------|
| `(net/request-url req)` | Request URL |
| `(net/request-method req)` | HTTP method |
| `(net/request-headers req)` | Request headers map |
| `(net/request-all-headers req)` | All headers (including duplicates) |
| `(net/request-post-data req)` | POST body string |
| `(net/request-post-data-buffer req)` | POST body bytes |
| `(net/request-resource-type req)` | Resource type (document, xhr, etc.) |
| `(net/request-response req)` | Get response for request |
| `(net/request-failure req)` | Get failure info |
| `(net/request-frame req)` | Frame that initiated request |
| `(net/request-is-navigation? req)` | Is navigation request? |
| `(net/request-redirected-from req)` | Redirected-from request |
| `(net/request-redirected-to req)` | Redirected-to request |
| `(net/request-timing req)` | Request timing info |

**Response functions:**

| Function | Description |
|----------|-------------|
| `(net/response-url resp)` | Response URL |
| `(net/response-status resp)` | HTTP status code |
| `(net/response-status-text resp)` | Status text |
| `(net/response-headers resp)` | Response headers map |
| `(net/response-all-headers resp)` | All headers |
| `(net/response-body resp)` | Response body bytes |
| `(net/response-text resp)` | Response body text |
| `(net/response-ok? resp)` | Status 200-299? |
| `(net/response-request resp)` | Get request for response |
| `(net/response-frame resp)` | Frame that received response |
| `(net/response-finished resp)` | Wait for response to finish |
| `(net/response-header-value resp "key")` | Single header value |
| `(net/response-header-values resp "key")` | All values for header |

**Route functions:**

| Function | Description |
|----------|-------------|
| `(net/route-request route)` | Get request being routed |
| `(net/route-fulfill! route {:status 200 :body "ok"})` | Fulfill with mock |
| `(net/route-continue! route)` | Continue to server |
| `(net/route-abort! route)` | Abort request |
| `(net/route-fallback! route)` | Fall through to next handler |
| `(net/route-fetch! route)` | Fetch and return response |

**WebSocket functions:**

| Function | Description |
|----------|-------------|
| `(net/ws-url ws)` | WebSocket URL |
| `(net/ws-is-closed? ws)` | Is closed? |
| `(net/ws-on-message ws handler)` | Listen for messages |
| `(net/ws-on-close ws handler)` | Listen for close |
| `(net/ws-on-error ws handler)` | Listen for errors |
| `(net/wsf-text frame)` | Frame text data |
| `(net/wsf-binary frame)` | Frame binary data |
| `(net/wsr-url wsr)` | WebSocketRoute URL |
| `(net/wsr-close! wsr)` | Close route |
| `(net/wsr-connect-to-server! wsr)` | Connect to server |
| `(net/wsr-on-message wsr handler)` | Route message handler |
| `(net/wsr-send! wsr data)` | Send through route |
| `(net/wsr-on-close wsr handler)` | Route close handler |

#### `loc` Namespace

Raw locator operations with explicit Locator argument. Same functions as `spel/` but you pass the Locator directly instead of a CSS selector string.

**Actions:**

| Function | Description |
|----------|-------------|
| `(loc/click loc)` | Click |
| `(loc/dblclick loc)` | Double-click |
| `(loc/fill loc "text")` | Fill input |
| `(loc/type-text loc "text")` | Type without clearing |
| `(loc/press loc "Enter")` | Press key |
| `(loc/clear loc)` | Clear input |
| `(loc/check loc)` | Check checkbox |
| `(loc/uncheck loc)` | Uncheck checkbox |
| `(loc/hover loc)` | Hover |
| `(loc/focus loc)` | Focus |
| `(loc/blur loc)` | Blur |
| `(loc/tap-element loc)` | Tap (touch) |
| `(loc/select-option loc "value")` | Select dropdown |
| `(loc/set-input-files! loc files)` | Set files |
| `(loc/scroll-into-view loc)` | Scroll into view |
| `(loc/dispatch-event loc "click")` | Dispatch event |
| `(loc/drag-to loc target-loc)` | Drag to target |

**State queries:**

| Function | Description |
|----------|-------------|
| `(loc/text-content loc)` | Text content |
| `(loc/inner-text loc)` | Inner text |
| `(loc/inner-html loc)` | Inner HTML |
| `(loc/input-value loc)` | Input value |
| `(loc/get-attribute loc "href")` | Attribute value |
| `(loc/is-visible? loc)` | Visible? |
| `(loc/is-hidden? loc)` | Hidden? |
| `(loc/is-enabled? loc)` | Enabled? |
| `(loc/is-disabled? loc)` | Disabled? |
| `(loc/is-editable? loc)` | Editable? |
| `(loc/is-checked? loc)` | Checked? |
| `(loc/bounding-box loc)` | Bounding box |
| `(loc/count-elements loc)` | Count matching |
| `(loc/all-text-contents loc)` | All texts vector |
| `(loc/all-inner-texts loc)` | All inner texts vector |
| `(loc/all loc)` | All Locator objects as list |

**Filtering & positioning:**

| Function | Description |
|----------|-------------|
| `(loc/loc-filter loc {:has-text "x"})` | Filter locator |
| `(loc/first-element loc)` | First match |
| `(loc/last-element loc)` | Last match |
| `(loc/nth-element loc 2)` | Nth match |
| `(loc/loc-locator loc "span")` | Sub-locator |
| `(loc/loc-get-by-text loc "text")` | Sub-locate by text |
| `(loc/loc-get-by-role loc AriaRole/BUTTON)` | Sub-locate by role |
| `(loc/loc-get-by-label loc "label")` | Sub-locate by label |
| `(loc/loc-get-by-test-id loc "id")` | Sub-locate by test ID |

**Waiting & evaluation:**

| Function | Description |
|----------|-------------|
| `(loc/wait-for loc)` | Wait for visible |
| `(loc/evaluate loc "el => el.id")` | Evaluate JS on element |
| `(loc/evaluate-all loc "els => els.length")` | Evaluate JS on all |
| `(loc/screenshot loc)` | Element screenshot |
| `(loc/highlight loc)` | Highlight |
| `(loc/element-handle loc)` | Get ElementHandle |
| `(loc/element-handles loc)` | Get all ElementHandles |

#### `assert` Namespace

Raw Playwright assertion functions. Requires explicit assertion object from `(assert/assert-that loc-or-page)`.

| Function | Description |
|----------|-------------|
| `(assert/assert-that loc-or-page)` | Create assertion object (LocatorAssertions, PageAssertions, or APIResponseAssertions) |
| `(assert/set-default-assertion-timeout! 5000)` | Set default assertion timeout |
| `(assert/loc-not la)` | Negate locator assertion |
| `(assert/page-not pa)` | Negate page assertion |
| `(assert/api-not aa)` | Negate API response assertion |
| `(assert/has-text la "text")` | Assert exact text |
| `(assert/contains-text la "partial")` | Assert contains text |
| `(assert/has-attribute la "attr" "val")` | Assert attribute |
| `(assert/has-class la "cls")` | Assert CSS class |
| `(assert/contains-class la "cls")` | Assert contains class |
| `(assert/has-count la 5)` | Assert element count |
| `(assert/has-css la "color" "red")` | Assert CSS property |
| `(assert/has-id la "my-id")` | Assert ID |
| `(assert/has-js-property la "value" "x")` | Assert JS property |
| `(assert/has-value la "text")` | Assert input value |
| `(assert/has-values la ["a" "b"])` | Assert select values |
| `(assert/has-role la AriaRole/BUTTON)` | Assert ARIA role |
| `(assert/has-accessible-name la "Submit")` | Assert accessible name |
| `(assert/has-accessible-description la "desc")` | Assert accessible description |
| `(assert/has-accessible-error-message la "err")` | Assert error message |
| `(assert/matches-aria-snapshot la "- button")` | Assert ARIA snapshot |
| `(assert/is-attached la)` | Assert attached to DOM |
| `(assert/is-checked la)` | Assert checked |
| `(assert/is-disabled la)` | Assert disabled |
| `(assert/is-editable la)` | Assert editable |
| `(assert/is-enabled la)` | Assert enabled |
| `(assert/is-focused la)` | Assert focused |
| `(assert/is-hidden la)` | Assert hidden |
| `(assert/is-visible la)` | Assert visible |
| `(assert/is-empty la)` | Assert empty |
| `(assert/is-in-viewport la)` | Assert in viewport |
| `(assert/has-title pa "title")` | Assert page title |
| `(assert/has-url pa "url")` | Assert page URL |
| `(assert/is-ok aa)` | Assert API response OK |

#### `core` Namespace

Browser lifecycle utilities. `with-*` macros throw helpful errors in eval mode (use `spel/start!` instead). Other functions pass through directly.

**Lifecycle stubs (throw helpful errors — use `spel/start!` instead):**

| Function | Eval Mode Behavior |
|----------|-------------------|
| `core/with-playwright` | Error: "Use spel/start! in eval mode" |
| `core/with-browser` | Error: "Use spel/start! in eval mode" |
| `core/with-context` | Error: "Use spel/start! in eval mode" |
| `core/with-page` | Error: "Use spel/start! in eval mode" |
| `core/create` | Error: "Use spel/start! in eval mode" |
| `core/launch-chromium` | Error: "Use spel/start! in eval mode" |
| `core/launch-firefox` | Error: "Use (spel/start! {:browser :firefox}) in eval mode" |
| `core/launch-webkit` | Error: "Use (spel/start! {:browser :webkit}) in eval mode" |

**Pass-through functions:**

| Function | Description |
|----------|-------------|
| `(core/close! obj)` | Close any Playwright resource |
| `(core/close-browser! browser)` | Close browser |
| `(core/close-context! ctx)` | Close context |
| `(core/close-page! page)` | Close page |
| `(core/anomaly? x)` | Check if anomaly map |
| `(core/browser-connected? browser)` | Is browser connected? |
| `(core/browser-version browser)` | Browser version string |
| `(core/browser-contexts browser)` | List contexts |
| `(core/context-pages ctx)` | List pages in context |
| `(core/context-browser ctx)` | Get browser from context |
| `(core/new-context browser)` | Create new context |
| `(core/new-page browser)` | Create new page |
| `(core/new-page-from-context ctx)` | Create page from context |
| `(core/context-cookies ctx)` | Get cookies |
| `(core/context-clear-cookies! ctx)` | Clear cookies |
| `(core/context-set-offline! ctx true)` | Set offline |
| `(core/context-grant-permissions! ctx perms)` | Grant permissions |
| `(core/context-clear-permissions! ctx)` | Clear permissions |
| `(core/context-set-extra-http-headers! ctx headers)` | Set context headers |
| `(core/context-set-default-timeout! ctx ms)` | Set context timeout |
| `(core/context-set-default-navigation-timeout! ctx ms)` | Set context nav timeout |

#### SCI Runtime Details

The eval mode uses [SCI](https://github.com/babashka/sci) (Small Clojure Interpreter). Key details:

- **Core Clojure**: `let`, `def`, `defn`, `fn`, `if`, `when`, `cond`, `loop/recur`, `map`, `filter`, `reduce`, `for`, `doseq`, `->`, `->>`, `as->`, destructuring — all work
- **Atoms**: `atom`, `deref`/`@`, `swap!`, `reset!` — all work
- **Interop**: Java interop via `.method`, `(.method obj)`, `(Class/staticMethod)` — works for registered classes
- **No macros**: Cannot define new macros with `defmacro` (SCI limitation)
- **No require**: Namespaces are pre-registered, no `require` or `use` needed

**Registered Java classes for interop:**

`Page`, `Browser`, `BrowserContext`, `BrowserType`, `Playwright`, `Locator`, `ElementHandle`, `JSHandle`, `Frame`, `FrameLocator`, `Keyboard`, `Mouse`, `Touchscreen`, `CDPSession`, `ConsoleMessage`, `Dialog`, `Download`, `Tracing`, `Request`, `Response`, `Route`, `WebSocket`, `WebSocketFrame`, `WebSocketRoute`, `APIResponse`, `LocatorAssertions`, `PageAssertions`, `APIResponseAssertions`, `PlaywrightAssertions`, `AriaRole`

#### Eval Examples

```bash
# Simple expression
spel --eval '(+ 1 2)'
# => 3

# Browser session — start! is optional (daemon auto-starts browser)
spel --eval '
  (spel/goto "https://example.com")
  (println "Title:" (spel/title))
  (println "URL:" (spel/url))'

# start! still works (no-op if daemon already has a page)
spel --eval '
  (spel/start!)
  (spel/goto "https://example.com")
  (spel/title)'

# Snapshot and interact by ref
spel --eval '
  (spel/goto "https://example.com")
  (let [snap (spel/snapshot)]
    (println (:tree snap))
    (spel/click-ref "e2"))'

# Scrape data
spel --eval '
  (spel/goto "https://example.com")
  (spel/eval-js "document.querySelectorAll(\"a\").length")'

# Kill daemon after eval (one-shot mode)
spel --autoclose --eval '
  (spel/goto "https://example.com")
  (spel/title)'

# Headed browser for debugging — use open --interactive first
# spel open --interactive https://example.com
# Then use eval to interact with the visible browser:
spel --eval '(spel/title)'
```

### Snapshot with Refs

The snapshot system walks the DOM and assigns numbered refs (`e1`, `e2`, etc.) to interactive and meaningful elements:

```clojure
(spel/start!)
(spel/goto "https://example.com")

;; Get accessibility snapshot with refs
(def snap (spel/snapshot))
(:tree snap)
;; => "- heading \"Example Domain\" [@e1] [level=1]\n- link \"More information...\" [@e2]"
(:refs snap)
;; => {"e1" {:role "heading" :name "Example Domain" :tag "h1" :bbox {:x 0 :y 0 :width 500 :height 40}}
;;     "e2" {:role "link" :name "More information..." :tag "a" :bbox {:x 10 :y 100 :width 200 :height 20}}}

;; Click by ref
(spel/click-ref "e2")

;; Fill by ref
(spel/fill-ref "e3" "hello@example.com")

;; Hover by ref
(spel/hover-ref "e1")
```

### Page Annotation

Inject visual overlays (bounding boxes, ref badges, dimension labels) onto the page for **visible elements only**:

```clojure
;; Via CLI:
;;   spel annotate              — inject overlays for visible elements
;;   spel unannotate            — remove overlays
;;   spel screenshot out.png    — capture (with or without overlays)

;; Via Clojure API:
(def snap (spel/snapshot))
(annotate/inject-overlays! *page* (:refs snap))   ;; overlays now visible on page
;; ... inspect in headed mode, take screenshots, etc.
(annotate/remove-overlays! *page*)                 ;; clean up

;; One-shot convenience (inject → screenshot → cleanup):
(spel/save-annotated-screenshot! (:refs snap) "annotated.png")
```

### Daemon Architecture

- Background process keeps browser alive between CLI invocations
- Listens on Unix domain socket (`/tmp/spel-{session}.sock`)
- PID file at `/tmp/spel-{session}.pid`
- JSON protocol over socket (newline-delimited)
- Auto-starts on first CLI command if not running
- `close` command shuts down browser + daemon + cleans up files

---

## Rules

| Rule | Detail |
|------|--------|
| **No per-file reflection warnings** | Root Makefile `validate-safe-graal-package` handles globally |
| **No `clj-kondo` CLI** | All linting via `clojure-lsp diagnostics --raw` |
| **Anomalies not exceptions** | Use `com.blockether.anomaly` pattern |
| **Error type keywords** | `:playwright.error/timeout`, `:playwright.error/target-closed`, etc. |
| **Exact string assertions** | Never use substring matching unless `contains-text` |
| **`new-page-from-context`** | For creating pages from BrowserContext (not `new-page`) |
| **AriaRole import** | Always `(:import [com.microsoft.playwright.options AriaRole])` |
| **`with-*` macros** | Always use for resource cleanup (never manual try/finally) |

---

## Missing API (Java Interop Needed)

These Playwright Java methods have no Clojure wrapper yet:

| Method | Workaround |
|--------|-----------|
| `Page.waitForPopup(Runnable)` | `(.waitForPopup ^Page pg (reify Runnable (run [_] ...)))` |
| `Page.waitForDownload(Runnable)` | `(.waitForDownload ^Page pg (reify Runnable (run [_] ...)))` |
| `Page.onceDialog(Consumer)` | Use `page/on-dialog` (registers persistent handler) |
| `Locator.contentFrame()` | `(.contentFrame loc)` |
| `Page.getByRole(role, options)` | `(locator/loc-filter (page/get-by-role pg role) {:has-text "name"})` |

---

## CLI Entry Points

The `spel` binary is the primary CLI interface:

| Command | Purpose |
|---------|---------|
| `spel <command>` | Browser automation CLI (100+ commands) |
| `spel codegen` | Record and transform browser sessions to Clojure |
| `spel init-agents` | Scaffold E2E testing agents (`--loop=opencode\|claude\|vscode`) |
