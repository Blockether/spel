# Page navigation + wait patterns

Go to pages, wait for things. `eval-sci` (implicit page) + library (explicit `pg`).

## Navigation

```clojure
;; Defaults to `:load`
(spel/navigate "https://example.org")

;; Network idle (no requests for 500ms)
(spel/navigate "https://example.org" {:wait-until :networkidle})

;; With timeout
(spel/navigate "https://example.org" {:wait-until :networkidle :timeout 30000})

;; Library
(page/navigate pg "https://example.org")
(page/navigate pg "https://example.org" {:wait-until :networkidle :timeout 30000})
```

`:wait-until` values:

| Value | Fires when | Best for |
|-------|-----------|----------|
| `:commit` | Response headers received | Fastest — navigation only (not valid for `wait-for-load-state`) |
| `:domcontentloaded` | HTML parsed, deferred scripts done | Server-rendered pages |
| `:load` (default) | Images + stylesheets loaded | Traditional multi-page sites |
| `:networkidle` | No network requests for 500ms | SPAs, JS-heavy pages |

### History

| `eval-sci` | Library |
|------------|---------|
| `(spel/go-back)` | `(page/go-back pg)` |
| `(spel/go-forward)` | `(page/go-forward pg)` |
| `(spel/reload)` | `(page/reload pg)` |

## Wait strategies

Playwright is event-driven. Don't guess — wait for the event.

Pick the most specific wait available; work down only when the previous doesn't fit:

1. `wait-for-load-state` (page-level readiness)
2. `wait-for-selector` (DOM-level)
3. `wait-for-url` (SPA route change)
4. `wait-for-function` (custom JS condition)
5. `spel/wait-for-timeout` — last resort, fragile

### `wait-for-load-state`

```clojure
(spel/wait-for-load-state)                     ; :load
(spel/wait-for-load-state :domcontentloaded)
(spel/wait-for-load-state :networkidle)

;; Library
(page/wait-for-load-state pg)
(page/wait-for-load-state pg :networkidle)
```

States: `:load` fires after images + stylesheets + iframes finish. `:domcontentloaded` fires once HTML is parsed + deferred scripts run (images may still load). `:networkidle` waits until no requests for 500 ms — go-to for SPAs. `:commit` is only a `navigate` option, not a `wait-for-load-state` target.

### `wait-for-selector`

```clojure
(spel/wait-for-selector ".results")                                      ; default "visible"
(spel/wait-for-selector ".results"        {:state "visible" :timeout 5000})
(spel/wait-for-selector ".loading-spinner" {:state "hidden"})
(spel/wait-for-selector "#data-container"  {:state "attached"})
(spel/wait-for-selector ".modal-overlay"   {:state "detached"})

;; Library
(page/wait-for-selector pg ".results")
(page/wait-for-selector pg ".results" {:state :visible :timeout 5000})
```

| State | Meaning |
|-------|---------|
| `"visible"` (default) | In DOM + visible (not `display:none`, non-zero size) |
| `"hidden"` | Absent or not visible |
| `"attached"` | In DOM (possibly hidden) |
| `"detached"` | Not in DOM |

### `wait-for-url`

Essential for SPA route changes (no full page load).

```clojure
(spel/wait-for-url "**/dashboard")
(spel/wait-for-url "https://example.org/dashboard")
(page/wait-for-url pg "**/dashboard")
```

### `wait-for-function`

When readiness isn't expressible as element visibility or URL:

```clojure
(spel/wait-for-function "() => document.querySelector('#loaded')")
(spel/wait-for-function "() => window.appReady === true")
(spel/wait-for-function "() => document.body.innerText.length > 100")
(spel/wait-for-function "() => document.querySelectorAll('.item').length >= 10")

(page/wait-for-function pg "() => window.appReady === true")
```

### `wait-for-timeout` (last resort)

```clojure
(spel/wait-for-timeout 1000)                   ; avoid — fragile
```

Only acceptable when waiting for a CSS animation with no observable state change. Even then, prefer `wait-for-function` with a property check.

### `sleep` / `Thread/sleep`

Plain JVM thread sleep — **does not** interact with the browser event loop.

```clojure
;; WRONG — never for page sync
(sleep 2000)
(spel/click ".button")

;; RIGHT
(spel/wait-for-selector ".button" {:state "visible"})
(spel/click ".button")
```

Use `sleep` only for non-browser delays (external file, non-browser API throttling, polling a process).

## Common patterns

### SPA navigation (click → wait → verify)

```clojure
(spel/navigate "https://myapp.com")
(spel/wait-for-load-state :networkidle)

(spel/click "a[href='/dashboard']")
(spel/wait-for-url      "**/dashboard")
(spel/wait-for-selector ".dashboard-content" {:state "visible"})
(println (spel/text-content ".dashboard-title"))
```

Pattern: interact → `wait-for-url` → `wait-for-selector` → proceed.

### Heavy / ad-laden portals

Portal pages often keep loading third-party resources long after meaningful content is ready. Full `:load` after every click is too strict:

```clojure
(spel/navigate "https://onet.pl")
(spel/wait-for-load-state :load)

(spel/click "@eXXXX")
(spel/wait-for-url          #".*wiadomosci.*")
(spel/wait-for-load-state   :domcontentloaded)
```

Decision order after interactions on heavy pages:

1. `wait-for-url` when the route should change.
2. `wait-for-selector` when a target content marker is known.
3. `wait-for-load-state :domcontentloaded` when content is ready but ads still load.
4. Longer timeouts only as final fallback.

### Click timeouts on SPAs

Usually a wait-strategy problem, not the click. **Never skip the click and navigate directly** — simulate real user actions.

```clojure
;; WRONG — bypasses the user journey
;; (spel/navigate "https://www.frisco.pl/login")

;; RIGHT
(spel/click "@eXXXX")
(spel/wait-for-url        #".*login.*")
(spel/wait-for-load-state :domcontentloaded)
```

Order of attack: (1) fix the readiness signal, (2) `wait-for-url`, (3) `wait-for-selector`, (4) finally raise timeout.

### Content loading

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
(spel/wait-for-selector ".titleline" {:state "visible"})
(println "Top story:" (spel/text-content (spel/first-element ".titleline")))
```

### SPA with API data

```clojure
(spel/navigate "https://myapp.com/users")
(spel/wait-for-load-state :networkidle)
(spel/wait-for-function  "() => document.querySelectorAll('tr.user-row').length > 0")
(println "Users:" (spel/all-text-contents "tr.user-row td.name"))
```

### Popups / downloads / file choosers

All three take an action callback that triggers the event; the return value is the captured object (Page, Download, FileChooser).

```clojure
(let [popup (spel/wait-for-popup #(spel/click "a[target=_blank]"))]
  (page/wait-for-load-state popup)
  (println "Popup:" (page/title popup)))

(let [dl (spel/wait-for-download #(spel/click "a.download-link"))]
  (println "File:" (.suggestedFilename dl))
  (.saveAs dl (java.nio.file.Paths/get "/tmp/downloaded.pdf"
                                       (into-array String []))))

(let [fc (spel/wait-for-file-chooser #(spel/click "input[type=file]"))]
  (.setFiles fc (into-array java.nio.file.Path
                            [(java.nio.file.Paths/get "/tmp/photo.jpg"
                                                      (into-array String []))])))

;; Simple uploads — skip the chooser entirely
(spel/set-input-files! "input[type=file]" "/tmp/photo.jpg")
```

Library:

```clojure
(let [popup (page/wait-for-popup pg #(locator/click (page/locator pg "a[target=_blank]")))]
  (page/title popup))

(let [dl (page/wait-for-download pg #(locator/click (page/locator pg "a.download-link")))]
  (page/download-save-as! dl "/tmp/downloaded.pdf"))
```

## Quick reference

| `eval-sci` | Library | Purpose |
|------------|---------|---------|
| `(spel/navigate url)` / `(spel/navigate url opts)` | `(page/navigate pg url)` / `(page/navigate pg url opts)` | Go to URL |
| `(spel/wait-for-load-state)` / `(spel/wait-for-load-state state)` | `(page/wait-for-load-state pg)` / `(page/wait-for-load-state pg state)` | Page-level readiness |
| `(spel/wait-for-selector sel)` / `(spel/wait-for-selector sel opts)` | `(page/wait-for-selector pg sel)` / `(page/wait-for-selector pg sel opts)` | DOM element |
| `(spel/wait-for-url pat)` | `(page/wait-for-url pg pat)` | URL match |
| `(spel/wait-for-function js)` | `(page/wait-for-function pg js)` | JS truthy |
| `(spel/go-back)` / `(spel/go-forward)` / `(spel/reload)` | `(page/go-back pg)` / … | History / reload |
| `(spel/wait-for-timeout ms)` | `(page/wait-for-timeout pg ms)` | Fixed delay (avoid) |
| `(spel/wait-for-popup f)` / `(spel/wait-for-download f)` / `(spel/wait-for-file-chooser f)` | `(page/wait-for-popup pg f)` / … | Capture popup/download/chooser |
| `(sleep ms)` / `(Thread/sleep (long ms))` | same | Non-browser delay (**never** for page sync) |
