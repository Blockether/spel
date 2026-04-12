# Browser options, page utilities & advanced locator actions

## Browser launch options

```clojure
(core/launch-chromium pw {:headless true})                       ; default
(core/launch-chromium pw {:headless false :slow-mo 500})         ; debug
(core/launch-chromium pw {:channel "chrome"})                    ; or "msedge", "chrome-beta", …
(core/launch-chromium pw {:args ["--disable-gpu" "--no-sandbox"]})
(core/launch-chromium pw {:ignore-default-args ["--enable-automation"]})

;; Stealth
(require '[com.blockether.spel.stealth :as stealth])
(core/launch-chromium pw {:args (stealth/stealth-args)
                          :ignore-default-args (stealth/stealth-ignore-default-args)})

;; Proxy
(core/launch-chromium pw {:proxy {:server "http://proxy:8080" :username "u" :password "p"}})

(core/launch-firefox pw {:headless true})
(core/launch-webkit  pw {:headless true})
```

| Option | Type | Description |
|--------|------|-------------|
| `:headless` | bool | Run without visible window (default `true`) |
| `:channel` | string | `"chrome"`, `"msedge"`, `"chrome-beta"`, … |
| `:args` / `:ignore-default-args` / `:ignore-all-default-args` | vec / bool | Chromium CLI tweaks |
| `:proxy` | map | `{:server … :username … :password … :bypass …}` |
| `:executable-path` / `:downloads-path` | string | Custom binary / downloads dir |
| `:slow-mo` / `:timeout` | number | Slow-motion ms / launch timeout |
| `:chromium-sandbox` | bool | Enable Chromium sandbox |

## Context options

```clojure
(core/new-context browser {:viewport {:width 1920 :height 1080}})

;; Mobile emulation
(core/new-context browser {:viewport {:width 375 :height 812}
                           :is-mobile true :has-touch true
                           :device-scale-factor 3
                           :user-agent "Mozilla/5.0 (iPhone...)"})

(core/new-context browser {:locale "fr-FR" :timezone-id "Europe/Paris"})
(core/new-context browser {:geolocation {:latitude 48.8566 :longitude 2.3522} :permissions ["geolocation"]})
(core/new-context browser {:color-scheme :dark})
(core/new-context browser {:offline true})
(core/new-context browser {:extra-http-headers {"Authorization" "Bearer token"}})
(core/new-context browser {:base-url "https://example.org"})
(core/new-context browser {:storage-state "state.json"})
(core/new-context browser {:record-video-dir "/tmp/videos" :record-video-size {:width 1280 :height 720}})
(core/new-context browser {:record-har-path "network.har" :record-har-mode :minimal})
(core/new-context browser {:ignore-https-errors true})
(core/new-context browser {:bypass-csp true})

;; Context management
(core/context-grant-permissions! ctx ["clipboard-read" "clipboard-write"])
(core/context-clear-permissions! ctx)
(core/context-cookies ctx) (core/context-clear-cookies! ctx)
(core/context-set-offline! ctx true)
(core/context-set-extra-http-headers! ctx {"X-Test" "value"})
(core/context-set-default-timeout! ctx 30000)
(core/context-set-default-navigation-timeout! ctx 60000)
```

## `with-testing-page` (one-shot browser)

Creates the full Playwright stack (pw → browser → context → page) in a single macro:

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/title pg))

(core/with-testing-page {:device :iphone-14} [pg] ...)
(core/with-testing-page {:viewport :desktop-hd :locale "fr-FR"} [pg] ...)
(core/with-testing-page {:browser-type :firefox :headless false} [pg] ...)
(core/with-testing-page {:profile "/tmp/my-chrome-profile"} [pg] ...)      ; persistent
(core/with-testing-page {:executable-path "/usr/bin/chromium" :args ["--disable-gpu"]} [pg] ...)
```

Accepts any launch or context option plus: `:browser-type` (`:chromium`/`:firefox`/`:webkit`), `:device`, `:viewport` (keyword preset or `{:width N :height N}`), `:profile` (path to persistent user-data-dir).

When the Allure reporter is active, tracing + HAR are auto-enabled — no config needed; files are attached to the Allure result.

### Device presets

| Keyword | Viewport | Mobile |
|---------|----------|--------|
| `:iphone-se` | 375×667 | yes |
| `:iphone-12` / `:iphone-14` | 390×844 | yes |
| `:iphone-14-pro` / `:iphone-15` / `:iphone-15-pro` | 393×852 | yes |
| `:ipad` | 810×1080 | yes |
| `:ipad-mini` | 768×1024 | yes |
| `:ipad-pro-11` / `:ipad-pro` | 834×1194 / 1024×1366 | yes |
| `:pixel-5` / `:pixel-7` | 393×851 / 412×915 | yes |
| `:galaxy-s24` / `:galaxy-s9` | 360×780 / 360×740 | yes |
| `:desktop-chrome` / `:desktop-firefox` / `:desktop-safari` | 1280×720 | no |

### Viewport presets

| Keyword | Size |
|---------|------|
| `:mobile` / `:mobile-lg` | 375×667 / 428×926 |
| `:tablet` / `:tablet-lg` | 768×1024 / 1024×1366 |
| `:desktop` / `:desktop-hd` / `:desktop-4k` | 1280×720 / 1920×1080 / 3840×2160 |

## Lifecycle macros

Always use macros — they nest and clean up automatically.

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.org")
        (assert/has-title (assert/assert-that pg) "Example Domain")))))
```

| Macro | Cleans up |
|-------|-----------|
| `with-playwright` | Playwright instance |
| `with-browser` | Browser instance |
| `with-context` | BrowserContext |
| `with-page` | Page instance |

## Error handling — anomalies

Wrapped functions return value on success, anomaly map on failure. Check with `anomaly/anomaly?`:

```clojure
(let [r (page/navigate pg "https://example.org")]
  (if (anomaly/anomaly? r)
    (println "Error:" (:cognitect.anomalies/message r))
    (println "OK")))
```

| Playwright exception | Anomaly category | Error keyword |
|----------------------|------------------|---------------|
| `TimeoutError` | `:cognitect.anomalies/busy` | `:playwright.error/timeout` |
| `TargetClosedError` | `:cognitect.anomalies/interrupted` | `:playwright.error/target-closed` |
| `PlaywrightException` | `:cognitect.anomalies/fault` | `:playwright.error/playwright` |
| Generic `Exception` | `:cognitect.anomalies/fault` | `:playwright.error/unknown` |

## Page utilities

```clojure
(page/set-content! pg "<h1>Hello</h1><p>World</p>")

(page/emulate-media! pg {:media :screen})                  ; or :print
(page/emulate-media! pg {:color-scheme :dark})             ; or :light :no-preference
(page/emulate-media! pg {:media :print :color-scheme :dark})

(page/set-viewport-size! pg 1024 768)

(page/add-script-tag pg {:url "https://cdn.example.org/lib.js"})
(page/add-script-tag pg {:content "window.myVar = 42;"})
(page/add-script-tag pg {:path "/path/to/local.js"})
(page/add-style-tag  pg {:content "body { background: red; }"})

(page/expose-function! pg "clojureAdd" (fn [a b] (+ a b)))
(page/expose-binding!  pg "getPageInfo" (fn [source] (str "Frame: " (.frame source))))

(page/set-extra-http-headers! pg {"Authorization" "Bearer token"})
(page/bring-to-front pg)
```

### Dialogs / downloads / console / tracing / clock / CDP / video / workers / file-chooser / selectors engine / web errors

```clojure
(page/on-dialog pg (fn [dlg]
  (page/dialog-type dlg)               ; "alert" "confirm" "prompt" "beforeunload"
  (page/dialog-message dlg)
  (page/dialog-default-value dlg)
  (page/dialog-accept! dlg)            ; or (page/dialog-accept! dlg "input")
  ;; (page/dialog-dismiss! dlg)
))

(page/on-download pg (fn [dl]
  (page/download-url dl)
  (page/download-suggested-filename dl)
  (page/download-failure dl)
  (page/download-save-as! dl "/tmp/out.pdf")
  ;; download-cancel! / download-path / download-page
))

(page/on-console pg (fn [msg]
  (page/console-type msg)              ; "log" "error" "warning" …
  (page/console-text msg)
  ;; console-args / console-location / console-page
))

;; Tracing (explicit)
(let [tr (core/context-tracing ctx)]
  (core/tracing-start! tr {:screenshots true :snapshots true :sources true})
  ;; ...
  (core/tracing-stop!  tr {:path "trace.zip"}))

;; Clock (time-dependent tests)
(page/clock-install!         (page/page-clock pg))
(page/clock-set-fixed-time!  (page/page-clock pg) "2024-01-01T00:00:00Z")
(page/clock-set-system-time! (page/page-clock pg) "2024-06-15T12:00:00Z")
(page/clock-fast-forward!    (page/page-clock pg) 60000)
(page/clock-pause-at!        (page/page-clock pg) "2024-01-01")
(page/clock-resume!          (page/page-clock pg))

;; CDP (Chromium only)
(let [session (core/cdp-send pg "Runtime.evaluate" {:expression "1+1"})])
;; core/cdp-on session "Network.requestWillBeSent" handler
;; core/cdp-detach! session

;; Video object
(let [v (page/video pg)]
  (core/video-obj-path v) (core/video-obj-save-as! v "/tmp/rec.webm") (core/video-obj-delete! v))

;; Workers
(doseq [w (page/workers pg)]
  (page/worker-url w) (page/worker-evaluate w "self.name"))

;; File chooser
(let [fc (page/wait-for-file-chooser pg #(locator/click (page/locator pg "input[type=file]")))]
  (page/file-chooser-set-files! fc "/path/to/file.txt"))

;; Selectors engine / web errors
(core/selectors-register! (core/selectors pg) "my-engine" {:script "..."})
(page/on-page-error pg (fn [err] ...))
```

## Advanced locator actions

```clojure
(locator/drag-to       (page/locator pg "#src") (page/locator pg "#tgt"))
(locator/dispatch-event (page/locator pg "#el") "click")
(locator/dispatch-event (page/locator pg "#el") "dragstart" {:dataTransfer {}})
(locator/scroll-into-view (page/locator pg "#offscreen"))
(locator/tap-element      (page/locator pg "#button"))

(locator/evaluate-locator (page/locator pg "#el")    "el => el.dataset.value")
(locator/evaluate-all     (page/locator pg ".items") "els => els.length")

(locator/locator-screenshot (page/locator pg ".card") {:path "card.png"})
(locator/highlight          (page/locator pg "#important"))
(locator/get-attribute      (page/locator pg "a") "href")

(locator/select-option (page/locator pg "select") "value")
(locator/select-option (page/locator pg "select") ["v1" "v2"])    ; multi-select
(locator/check   (page/locator pg "#checkbox"))
(locator/uncheck (page/locator pg "#checkbox"))
(locator/hover   (page/locator pg ".tooltip-trigger"))
```

## Device emulation in eval-sci

| Approach | Viewport | DPR | UA | Touch | Available in |
|----------|:-:|:-:|:-:|:-:|---|
| `(spel/set-viewport-size! W H)` | ✓ | — | — | — | `eval-sci` |
| `spel set device "Name"` (daemon) | ✓ | ✓ | ✓ | ✓ | CLI daemon |
| `{:device :name}` on `with-testing-page` | ✓ | ✓ | ✓ | ✓ | library only |

```clojure
(spel/set-viewport-size! 390 844)     ; iPhone 14 dims — viewport only
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})
```

```bash
# Daemon must be running
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```
