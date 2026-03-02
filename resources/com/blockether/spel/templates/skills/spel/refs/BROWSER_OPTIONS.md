# Browser Options, Page Utilities & Advanced Locator Actions

Detailed reference for browser launch options, context options, device/viewport presets, page utilities, and advanced locator operations.

## Browser Launch Options

```clojure
;; Basic headless (default)
(core/launch-chromium pw {:headless true})

;; Headed mode for debugging
(core/launch-chromium pw {:headless false :slow-mo 500})

;; Use Chrome channel
(core/launch-chromium pw {:channel "chrome"})

;; Custom browser args
(core/launch-chromium pw {:args ["--disable-gpu" "--no-sandbox"]})

;; Suppress default Chromium args
(core/launch-chromium pw {:ignore-default-args ["--enable-automation"]})

;; Stealth mode args (anti-detection)
(require '[com.blockether.spel.stealth :as stealth])
(core/launch-chromium pw {:args (stealth/stealth-args)
                          :ignore-default-args (stealth/stealth-ignore-default-args)})

;; Proxy
(core/launch-chromium pw {:proxy {:server "http://proxy:8080"
                                   :username "user"
                                   :password "pass"}})

;; Custom downloads directory
(core/launch-chromium pw {:downloads-path "/tmp/downloads"})

;; All browsers
(core/launch-firefox pw {:headless true})
(core/launch-webkit pw {:headless true})
```

### Launch Option Reference

| Option | Type | Description |
|--------|------|-------------|
| `:headless` | boolean | Run without visible window (default: `true`) |
| `:channel` | string | Browser channel: `"chrome"`, `"chrome-beta"`, etc. |
| `:args` | vector | Extra Chromium CLI args |
| `:ignore-default-args` | vector | Chromium default args to suppress |
| `:ignore-all-default-args` | boolean | Suppress ALL default Chromium args |
| `:proxy` | map | `{:server "url" :username "u" :password "p" :bypass "domains"}` |
| `:executable-path` | string | Path to browser binary |
| `:downloads-path` | string | Directory for downloads |
| `:slow-mo` | number | Milliseconds to slow down operations |
| `:timeout` | number | Max ms to wait for browser launch |
| `:chromium-sandbox` | boolean | Enable Chromium sandbox |

## Browser Context Options

```clojure
;; Custom viewport
(core/new-context browser {:viewport {:width 1920 :height 1080}})

;; Mobile emulation
(core/new-context browser {:viewport {:width 375 :height 812}
                           :is-mobile true
                           :has-touch true
                           :device-scale-factor 3
                           :user-agent "Mozilla/5.0 (iPhone...)"})

;; Locale and timezone
(core/new-context browser {:locale "fr-FR"
                           :timezone-id "Europe/Paris"})

;; Geolocation
(core/new-context browser {:geolocation {:latitude 48.8566 :longitude 2.3522}
                           :permissions ["geolocation"]})

;; Dark mode
(core/new-context browser {:color-scheme :dark})

;; Offline mode
(core/new-context browser {:offline true})

;; Extra HTTP headers
(core/new-context browser {:extra-http-headers {"Authorization" "Bearer token"
                                                 "X-Custom" "value"}})

;; Base URL (for relative navigations)
(core/new-context browser {:base-url "https://example.org"})

;; Storage state (restore cookies + localStorage)
(core/new-context browser {:storage-state "state.json"})

;; Record video
(core/new-context browser {:record-video-dir "/tmp/videos"
                           :record-video-size {:width 1280 :height 720}})

;; Record HAR (HTTP Archive)
(core/new-context browser {:record-har-path "network.har"
                           :record-har-mode :minimal})

;; Ignore HTTPS errors
(core/new-context browser {:ignore-https-errors true})

;; Bypass CSP
(core/new-context browser {:bypass-csp true})

;; Context management
(core/context-grant-permissions! ctx ["clipboard-read" "clipboard-write"])
(core/context-clear-permissions! ctx)
(core/context-cookies ctx)
(core/context-clear-cookies! ctx)
(core/context-set-offline! ctx true)
(core/context-set-extra-http-headers! ctx {"X-Test" "value"})
(core/context-set-default-timeout! ctx 30000)
(core/context-set-default-navigation-timeout! ctx 60000)
```

## Standalone Testing Page

For quick tests, scripts, and standalone test cases, `with-testing-page` creates the entire Playwright stack (pw → browser → context → page) in one shot — no nesting required:

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page])

;; Minimal — headless Chromium, default viewport
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/title pg))
;; => "Example Domain"
```

Pass an opts map for device emulation, viewport presets, or browser selection:

```clojure
;; Device emulation
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.org"))

;; Viewport preset
(core/with-testing-page {:viewport :desktop-hd :locale "fr-FR"} [pg]
  (page/navigate pg "https://example.org"))

;; Firefox, headed mode
(core/with-testing-page {:browser-type :firefox :headless false} [pg]
  (page/navigate pg "https://example.org"))

;; Persistent profile (keeps login sessions across runs)
(core/with-testing-page {:profile "/tmp/my-chrome-profile"} [pg]
  (page/navigate pg "https://example.org"))

;; Custom browser executable + extra args
(core/with-testing-page {:executable-path "/usr/bin/chromium"
                         :args ["--disable-gpu"]} [pg]
  (page/navigate pg "https://example.org"))
```

### `with-testing-page` Options

| Option | Values | Default |
|--------|--------|---------|
| `:browser-type` | `:chromium`, `:firefox`, `:webkit` | `:chromium` |
| `:headless` | `true`, `false` | `true` |
| `:device` | `:iphone-14`, `:pixel-7`, `:ipad`, `:desktop-chrome`, etc. | — |
| `:viewport` | `:mobile`, `:tablet`, `:desktop-hd`, `{:width N :height N}` | browser default |
| `:slow-mo` | Millis to slow down operations | — |
| `:profile` | String path to persistent user data dir | — |
| `:executable-path` | String path to browser executable | — |
| `:channel` | `"chrome"`, `"chrome-beta"`, etc. | — |
| `:proxy` | `{:server "..." :bypass "..." :username "..." :password "..."}` | — |
| `:args` | Vector of extra browser CLI args | — |
| `:downloads-path` | String path for downloaded files | — |
| `:timeout` | Max ms to wait for browser launch | — |
| `:chromium-sandbox` | `true`, `false` | — |
| + any key accepted by `new-context` | `:locale`, `:color-scheme`, `:timezone-id`, `:storage-state`, etc. | — |

When the Allure reporter is active (either Lazytest or clojure.test), tracing (screenshots + DOM snapshots + network) and HAR recording are enabled automatically — zero configuration. Trace and HAR files are attached directly to the Allure test result.

### Device Presets

| Keyword | Viewport | Mobile |
|---------|----------|--------|
| `:iphone-se` | 375×667 | ✓ |
| `:iphone-12` | 390×844 | ✓ |
| `:iphone-14` | 390×844 | ✓ |
| `:iphone-14-pro` | 393×852 | ✓ |
| `:iphone-15` | 393×852 | ✓ |
| `:iphone-15-pro` | 393×852 | ✓ |
| `:ipad` | 810×1080 | ✓ |
| `:ipad-mini` | 768×1024 | ✓ |
| `:ipad-pro-11` | 834×1194 | ✓ |
| `:ipad-pro` | 1024×1366 | ✓ |
| `:pixel-5` | 393×851 | ✓ |
| `:pixel-7` | 412×915 | ✓ |
| `:galaxy-s24` | 360×780 | ✓ |
| `:galaxy-s9` | 360×740 | ✓ |
| `:desktop-chrome` | 1280×720 | ✗ |
| `:desktop-firefox` | 1280×720 | ✗ |
| `:desktop-safari` | 1280×720 | ✗ |

### Viewport Presets

| Keyword | Size |
|---------|------|
| `:mobile` | 375×667 |
| `:mobile-lg` | 428×926 |
| `:tablet` | 768×1024 |
| `:tablet-lg` | 1024×1366 |
| `:desktop` | 1280×720 |
| `:desktop-hd` | 1920×1080 |
| `:desktop-4k` | 3840×2160 |

## Resource Lifecycle Macros

**Always use macros for cleanup.** They nest naturally:

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.org")
        ;; returns nil on success, throws on failure
        (assert/has-title (assert/assert-that pg) "Example Domain")))))
```

| Macro | Cleans Up |
|-------|-----------|
| `with-playwright` | Playwright instance |
| `with-browser` | Browser instance |
| `with-context` | BrowserContext |
| `with-page` | Page instance |

## Error Handling

Uses `com.blockether.anomaly` instead of throwing exceptions:

```clojure
;; All wrapped functions return either a value or an anomaly map
(let [result (page/navigate pg "https://example.org")]
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

## Page Utilities

```clojure
;; Set HTML content directly (useful for tests)
(page/set-content! pg "<h1>Hello</h1><p>World</p>")

;; Emulate media
(page/emulate-media! pg {:media :screen})              ; or :print
(page/emulate-media! pg {:color-scheme :dark})          ; or :light :no-preference
(page/emulate-media! pg {:media :print :color-scheme :dark})

;; Set viewport
(page/set-viewport-size! pg 1024 768)

;; Add script/style tags
(page/add-script-tag pg {:url "https://cdn.example.org/lib.js"})
(page/add-script-tag pg {:content "window.myVar = 42;"})
(page/add-script-tag pg {:path "/path/to/local.js"})

(page/add-style-tag pg {:content "body { background: red; }"})
(page/add-style-tag pg {:url "https://cdn.example.org/style.css"})

;; Expose Clojure function to JavaScript
(page/expose-function! pg "clojureAdd" (fn [a b] (+ a b)))
;; In JS: await window.clojureAdd(1, 2)  => 3

;; Expose binding (receives BindingSource as first arg)
(page/expose-binding! pg "getPageInfo" (fn [source]
  (str "Frame: " (.frame source))))

;; Extra HTTP headers for this page
(page/set-extra-http-headers! pg {"Authorization" "Bearer token"})

;; Bring page to front (activate tab)
(page/bring-to-front pg)
```

## Page Utilities (page namespace)

Functions from the `page` namespace for handling dialogs, downloads, console messages, clock manipulation, workers, file choosers, and web errors.
```clojure
(require '[com.blockether.spel.page :as page]
         '[com.blockether.spel.core :as core])
;; Dialog handling
(page/on-dialog pg (fn [dialog]
  (println "Type:" (page/dialog-type dialog))       ; "alert", "confirm", "prompt", "beforeunload"
  (println "Message:" (page/dialog-message dialog))
  (println "Default:" (page/dialog-default-value dialog))
  (page/dialog-accept! dialog)                       ; or (page/dialog-accept! dialog "input text")
  ;; (page/dialog-dismiss! dialog)
  ))
;; Download handling
(page/on-download pg (fn [dl]
  (println "URL:" (page/download-url dl))
  (println "File:" (page/download-suggested-filename dl))
  (println "Failure:" (page/download-failure dl))
  (page/download-save-as! dl "/tmp/downloaded.pdf")
  ;; (page/download-cancel! dl)
  ;; (page/download-path dl)
  ;; (page/download-page dl)
  ))
;; Console messages
(page/on-console pg (fn [msg]
  (println (page/console-type msg) ":"       ; "log", "error", "warning", etc.
           (page/console-text msg))
  ;; (page/console-args msg)                 ; vector of JSHandle
  ;; (page/console-location msg)             ; {:url ... :line-number ... :column-number ...}
  ;; (page/console-page msg)
  ))

;; Tracing (core namespace)
(let [tracing (core/context-tracing ctx)]
  (core/tracing-start! tracing {:screenshots true :snapshots true :sources true})
  ;; ... test actions ...
  (core/tracing-stop! tracing {:path "trace.zip"}))
;; Clock manipulation (for time-dependent tests)
(page/clock-install! (page/page-clock pg))
(page/clock-set-fixed-time! (page/page-clock pg) "2024-01-01T00:00:00Z")
(page/clock-set-system-time! (page/page-clock pg) "2024-06-15T12:00:00Z")
(page/clock-fast-forward! (page/page-clock pg) 60000)   ; ms
(page/clock-pause-at! (page/page-clock pg) "2024-01-01")
(page/clock-resume! (page/page-clock pg))

;; CDP (Chrome DevTools Protocol) — core namespace
;; Requires Chromium browser
(let [session (core/cdp-send pg "Runtime.evaluate" {:expression "1+1"})]
  ;; (core/cdp-on session "Network.requestWillBeSent" handler-fn)
  ;; (core/cdp-detach! session)
  )

;; Video recording (core namespace — video-obj-* for Video object)
(let [video (page/video pg)]
  (core/video-obj-path video)
  (core/video-obj-save-as! video "/tmp/recording.webm")
  (core/video-obj-delete! video))
;; Workers (Web Workers / Service Workers)
(doseq [w (page/workers pg)]
  (println "Worker URL:" (page/worker-url w))
  (println "Eval:" (page/worker-evaluate w "self.name")))
;; File chooser
(let [fc (page/wait-for-file-chooser pg
           #(locator/click (page/locator pg "input[type=file]")))]
  (page/file-chooser-set-files! fc "/path/to/file.txt")
  ;; (page/file-chooser-page fc)
  ;; (page/file-chooser-element fc)
  ;; (page/file-chooser-is-multiple? fc)
  )

;; Selectors engine (core namespace)
(core/selectors-register! (core/selectors pg) "my-engine" {:script "..."})
;; Web errors
(page/on-page-error pg (fn [err]
  ;; (page/web-error-page err)
  ;; (page/web-error-error err)
  ))
```

## Advanced Locator Actions

```clojure
;; Drag and drop
(locator/drag-to (page/locator pg "#source") (page/locator pg "#target"))

;; Dispatch custom DOM event
(locator/dispatch-event (page/locator pg "#el") "click")
(locator/dispatch-event (page/locator pg "#el") "dragstart" {:dataTransfer {}})

;; Scroll element into view
(locator/scroll-into-view (page/locator pg "#offscreen"))

;; Tap (touch) element
(locator/tap-element (page/locator pg "#button"))

;; Evaluate JavaScript on element
(locator/evaluate-locator (page/locator pg "#el") "el => el.dataset.value")
(locator/evaluate-all (page/locator pg ".items") "els => els.length")

;; Take screenshot of specific element
(locator/locator-screenshot (page/locator pg ".card"))
(locator/locator-screenshot (page/locator pg ".card") {:path "card.png"})

;; Highlight element (visual debugging)
(locator/highlight (page/locator pg "#important"))

;; Get/set attributes
(locator/get-attribute (page/locator pg "a") "href")

;; Select dropdown option
(locator/select-option (page/locator pg "select") "value")
(locator/select-option (page/locator pg "select") ["val1" "val2"])  ; multi-select

;; Check/uncheck
(locator/check (page/locator pg "#checkbox"))
(locator/uncheck (page/locator pg "#checkbox"))

;; Hover
(locator/hover (page/locator pg ".tooltip-trigger"))
```

## Device Emulation in `--eval` Mode

There are multiple approaches to device emulation depending on what you need:

### Approach 1: Viewport Only (`spel/set-viewport-size!`)
Sets width and height but NOT device pixel ratio, user agent, or touch support.
```clojure
;; Daemon mode: just set viewport and go
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})
```

### Approach 2: Full Device Preset (CLI daemon `set device`)
Sets viewport + DPR + user agent + touch. Requires the daemon running.
```bash
# From shell (daemon must be running via spel start)
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: Restart with Device (library only)
```clojure
;; In library code (NOT --eval), use :device option
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.org"))
```

### Comparison

| Approach | Viewport | DPR | User Agent | Touch | Available in |
|---|---|---|---|---|---|
| `spel/set-viewport-size!` | ✅ | ❌ | ❌ | ❌ | `--eval` |
| `spel set device "Name"` | ✅ | ✅ | ✅ | ✅ | CLI daemon |
| `{:device :name}` option | ✅ | ✅ | ✅ | ✅ | Library only |
