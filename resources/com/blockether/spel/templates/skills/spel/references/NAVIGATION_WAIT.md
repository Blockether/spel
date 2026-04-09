# Page navigation + wait patterns

Go to pages, wait for things. Covers `eval-sci` mode (implicit page) + library mode (explicit `page` arg).

## Going to pages

### `spel/navigate` (eval) / `page/navigate` (library)

Go to URL, optionally control when load considered done.

```clojure
;; Basic navigation (waits for "load" event by default)
(spel/navigate "https://example.org")

;; Wait until no network requests for 500ms
(spel/navigate "https://example.org" {:wait-until :networkidle})

;; Custom timeout (ms)
(spel/navigate "https://example.org" {:wait-until :networkidle :timeout 30000})
```

`:wait-until` controls what "loaded" means:

| Value | Fires when | Best for |
|-------|-----------|----------|
| `:commit` | Response headers received | Fastest — navigation only (`{:wait-until :commit}`), not valid for `wait-for-load-state` |
| `:domcontentloaded` | HTML parsed, deferred scripts done | Server-rendered pages |
| `:load` (default) | All resources loaded (images, stylesheets) | Traditional multi-page sites |
| `:networkidle` | No network requests for 500ms | SPAs, JS-heavy pages |

Library equivalent:

```clojure
(page/navigate pg "https://example.org")
(page/navigate pg "https://example.org" {:wait-until :networkidle :timeout 30000})
```

### History navigation

```clojure
;; eval-sci                          ;; Library equivalent
(spel/go-back)                        ;; (page/go-back pg)
(spel/go-forward)                     ;; (page/go-forward pg)
(spel/reload)                         ;; (page/reload pg)
```

## Wait strategies

Playwright is event-driven. Don't guess when ready. Wait for it.

### Wait hierarchy

Use most specific wait available. Work down only when previous doesn't fit:

1. `wait-for-load-state` with right state (page-level readiness)
2. `wait-for-selector` on specific element (DOM-level readiness)
3. `wait-for-url` for route changes (SPA navigation)
4. `wait-for-function` for custom JS conditions (app-level readiness)
5. `spel/wait-for-timeout` as absolute last resort (time-based, fragile)

### `spel/wait-for-load-state`

Waits for page to reach load state. Call after `spel/navigate` when stricter readiness needed.

```clojure
;; Default: waits for :load event
(spel/wait-for-load-state)

;; Wait for DOM parsed (faster than :load)
(spel/wait-for-load-state :domcontentloaded)

;; Wait for network to settle (best for SPAs)
(spel/wait-for-load-state :networkidle)
```
States: `:load` fires after images, stylesheets, iframes finish. `:domcontentloaded` fires once HTML parsed + deferred scripts run, images may still load. `:networkidle` waits until no requests for 500ms, go-to for SPAs. (`:commit` only available as navigation option via `{:wait-until :commit}`, not for `wait-for-load-state`.)

Library equivalent:

```clojure
(page/wait-for-load-state pg)                    ;; default: "load"
(page/wait-for-load-state pg :networkidle)       ;; keyword form works too
```

### `spel/wait-for-selector` (element waiting)

Waits for element to reach condition. Workhorse for most automation.

```clojure
;; Wait for element visible (default)
(spel/wait-for-selector ".results")

;; Explicit state + timeout
(spel/wait-for-selector ".results" {:state "visible" :timeout 5000})

;; Wait for spinner to disappear
(spel/wait-for-selector ".loading-spinner" {:state "hidden"})

;; Wait for element to attach to DOM (doesn't need to be visible)
(spel/wait-for-selector "#data-container" {:state "attached"})

;; Wait for element to detach from DOM
(spel/wait-for-selector ".modal-overlay" {:state "detached"})
```

States:

| State | Meaning |
|-------|---------|
| `"visible"` (default) | Exists in DOM + visible (not `display:none`, not zero-size) |
| `"hidden"` | Doesn't exist or not visible |
| `"attached"` | Exists in DOM (may be hidden) |
| `"detached"` | Does not exist in DOM |

Library equivalent:

```clojure
(page/wait-for-selector pg ".results")
(page/wait-for-selector pg ".results" {:state :visible :timeout 5000})
```

### `spel/wait-for-url`

Waits for page URL to match pattern. Essential for SPA navigation where clicking link changes route without full page load.

```clojure
;; Glob pattern
(spel/wait-for-url "**/dashboard")

;; Exact URL
(spel/wait-for-url "https://example.org/dashboard")
```

Library equivalent:

```clojure
(page/wait-for-url pg "**/dashboard")
```

### `spel/wait-for-function`

Waits for JS expression to return truthy value. Use when readiness can't be expressed as element visibility or URL change.

```clojure
;; Wait for specific DOM element
(spel/wait-for-function "() => document.querySelector('#loaded')")

;; Wait for JS variable
(spel/wait-for-function "() => window.appReady === true")

;; Wait for content to render
(spel/wait-for-function "() => document.body.innerText.length > 100")

;; Wait for specific number of items
(spel/wait-for-function "() => document.querySelectorAll('.item').length >= 10")
```

Library: `(page/wait-for-function pg "() => window.appReady === true")`

### `spel/wait-for-timeout` (last resort)

Pauses execution for fixed ms. Almost always wrong choice. Fixed delays → slow + flaky tests: too short on slow machines, wastefully long on fast ones.

```clojure
;; Don't do this unless truly no other option
(spel/wait-for-timeout 1000)
```

Only acceptable use: waiting for CSS animation/transition with no observable state change detectable. Even then, prefer `wait-for-function` with CSS property check.

Library: `(page/wait-for-timeout pg 1000)` ... same caveat.

### `sleep` / `Thread/sleep` (non-browser only)

Plain JVM thread sleep. Does NOT interact with browser event loop. Available as global binding `(sleep ms)`, `(spel/sleep ms)`, or `(Thread/sleep (long ms))`.

```clojure
;; WRONG — never use sleep for browser synchronization:
(sleep 2000) ;; page might not be ready, flaky!
(spel/click ".button")

;; RIGHT — use page waits:
(spel/wait-for-selector ".button" {:state "visible"})
(spel/click ".button")
```

Only valid `sleep` use: non-browser delays — waiting for external file on disk, throttling requests to non-browser API, polling a process. Touching browser page → use page wait.

## Common patterns

### SPA navigation (click → wait → verify)

SPAs don't trigger full page loads. After clicking link → wait for URL change + new content.

```clojure
(spel/navigate "https://myapp.com")
(spel/wait-for-load-state :networkidle)
(spel/click "a[href='/dashboard']")
(spel/wait-for-url "**/dashboard")
(spel/wait-for-selector ".dashboard-content" {:state "visible"})
(println (spel/text-content ".dashboard-title"))
```

Pattern: interact → wait for URL → wait for element → proceed.

### Heavy portals + ad/tracker pages

Portal pages often keep loading third-party resources long after meaningful content ready. Waiting for full `:load` after every click = too strict.

Preferred pattern:

```clojure
(spel/navigate "https://onet.pl")
(spel/wait-for-load-state :load)

;; After clicking heavy nav target, relax wait.
(spel/click "@eXXXX")
(spel/wait-for-url #".*wiadomosci.*")
(spel/wait-for-load-state :domcontentloaded)
```

Decision order after interactions on heavy pages:
1. `wait-for-url` when route should change
2. `wait-for-selector` when target content marker known
3. `wait-for-load-state :domcontentloaded` when content-ready but ads still loading
4. Longer timeouts only as final fallback

### Handling click timeouts on SPAs

Click timeout on client-side app → problem almost always wait strategy, not click itself. NEVER skip click + navigate directly — always simulate user actions like human would.

```clojure
;; WRONG — skipping user actions:
;; (spel/navigate "https://www.frisco.pl/login")
;; Bypasses actual user journey, misses real bugs.

;; RIGHT — click element, then wait smarter:
(spel/click "@eXXXX")
(spel/wait-for-url #".*login.*")               ;; wait for route change
(spel/wait-for-load-state :domcontentloaded)   ;; don't wait for ads/trackers
```

Click seems "unreliable":
- First: check waiting for wrong readiness signal (`:load` vs `:domcontentloaded`)
- Second: `wait-for-url` to detect route change after click
- Third: `wait-for-selector` to detect target content appearing
- Last resort: increase timeout — but NEVER skip click itself
### Content loading (open page → wait for element → extract)

Pages loading data async after initial render.

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
(spel/wait-for-selector ".titleline" {:state "visible"})
(let [title (spel/text-content (spel/first-element ".titleline"))]
  (println "Top story:" title))
```

### SPA with API data (open page → network idle → JS check)

Apps fetching data from APIs after mounting:

```clojure
(spel/navigate "https://myapp.com/users")
(spel/wait-for-load-state :networkidle)
(spel/wait-for-function "() => document.querySelectorAll('tr.user-row').length > 0")
(println "Users:" (spel/all-text-contents "tr.user-row td.name"))
```

### Waiting for popups, downloads, file choosers

All three follow same pattern: pass action callback triggering event. Return = captured object (Page, Download, FileChooser).

```clojure
;; Popup: action opens new tab, returns new Page
(let [popup (spel/wait-for-popup
              #(spel/click "a[target=_blank]"))]
  (page/wait-for-load-state popup)
  (println "Popup:" (page/title popup)))

;; Download: action triggers file download
(let [dl (spel/wait-for-download
           #(spel/click "a.download-link"))]
  (println "File:" (.suggestedFilename dl))
  (.saveAs dl (java.nio.file.Paths/get "/tmp/downloaded.pdf"
                (into-array String []))))
;; File chooser: action opens native file dialog
(let [fc (spel/wait-for-file-chooser
           #(spel/click "input[type=file]"))]
  (.setFiles fc (into-array java.nio.file.Path
                  [(java.nio.file.Paths/get "/tmp/photo.jpg"
                     (into-array String []))])))
```

Simple file uploads — skip file chooser entirely:

```clojure
(spel/set-input-files! "input[type=file]" "/tmp/photo.jpg")
```

Library equivalents:

```clojure
(let [popup (page/wait-for-popup pg #(locator/click (page/locator pg "a[target=_blank]")))]
  (page/title popup))
(let [dl (page/wait-for-download pg #(locator/click (page/locator pg "a.download-link")))]
  (page/download-save-as! dl "/tmp/downloaded.pdf"))
```

## Library quick reference

| Eval (`spel/`) | Library (`page/`) | Purpose |
|---|---|---|
| `(spel/navigate url)` | `(page/navigate pg url)` | Go to URL |
| `(spel/navigate url opts)` | `(page/navigate pg url opts)` | Go to URL with options |
| `(spel/wait-for-load-state)` | `(page/wait-for-load-state pg)` | Wait for load state |
| `(spel/wait-for-load-state state)` | `(page/wait-for-load-state pg state)` | Wait for specific state |
| `(spel/wait-for-selector sel)` | `(page/wait-for-selector pg sel)` | Wait for element |
| `(spel/wait-for-selector sel opts)` | `(page/wait-for-selector pg sel opts)` | Wait with options |
| `(spel/wait-for-url pat)` | `(page/wait-for-url pg pat)` | Wait for URL match |
| `(spel/wait-for-function js)` | `(page/wait-for-function pg js)` | Wait for JS truthy |
| `(spel/go-back)` | `(page/go-back pg)` | History back |
| `(spel/go-forward)` | `(page/go-forward pg)` | History forward |
| `(spel/reload)` | `(page/reload pg)` | Reload page |
| `(spel/wait-for-timeout ms)` | `(page/wait-for-timeout pg ms)` | Fixed delay (avoid) |
| `(spel/wait-for-popup f)` | `(page/wait-for-popup pg f)` | Capture popup page |
| `(spel/wait-for-download f)` | `(page/wait-for-download pg f)` | Capture download |
| `(spel/wait-for-file-chooser f)` | `(page/wait-for-file-chooser pg f)` | Capture file chooser |
| `(sleep ms)` / `(spel/sleep ms)` | `(Thread/sleep (long ms))` | Non-browser delay (**never** for page sync) |