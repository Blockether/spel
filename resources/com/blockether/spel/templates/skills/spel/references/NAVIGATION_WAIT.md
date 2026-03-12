# Page navigation and wait patterns

How to go to pages and wait for things to happen. Covers both `eval-sci` mode (implicit page) and library mode (explicit `page` arg).

## Going to pages

### `spel/navigate` (eval) / `page/navigate` (library)

Go to a URL and optionally control when the load is considered done.

```clojure
;; Basic navigation (waits for "load" event by default)
(spel/navigate "https://example.org")

;; Wait until no network requests for 500ms
(spel/navigate "https://example.org" {:wait-until :networkidle})

;; Custom timeout (ms)
(spel/navigate "https://example.org" {:wait-until :networkidle :timeout 30000})
```

The `:wait-until` option controls what "loaded" means:

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

Playwright is event-driven. Don't guess when something is ready. Wait for it.

### The wait hierarchy

Use the most specific wait available. Work down this list only when the previous option doesn't fit:

1. `wait-for-load-state` with the right state (page-level readiness)
2. `wait-for-selector` on a specific element (DOM-level readiness)
3. `wait-for-url` for route changes (SPA navigation)
4. `wait-for-function` for custom JS conditions (app-level readiness)
5. `spel/wait-for-timeout` as absolute last resort (time-based, fragile)

### `spel/wait-for-load-state`

Waits for the page to reach a load state. Call this after `spel/navigate` when you need a stricter readiness check than the default.

```clojure
;; Default: waits for :load event
(spel/wait-for-load-state)

;; Wait for DOM parsed (faster than :load)
(spel/wait-for-load-state :domcontentloaded)

;; Wait for network to settle (best for SPAs)
(spel/wait-for-load-state :networkidle)
```
States explained: `:load` fires after images, stylesheets, and iframes finish. `:domcontentloaded` fires once HTML is parsed and deferred scripts run, but images may still load. `:networkidle` waits until no requests for 500ms, the go-to for SPAs. (`:commit` is only available as a navigation option via `{:wait-until :commit}`, not for `wait-for-load-state`.)

Library equivalent:

```clojure
(page/wait-for-load-state pg)                    ;; default: "load"
(page/wait-for-load-state pg :networkidle)       ;; keyword form works too
```

### `spel/wait-for-selector` (element waiting)

Waits for a specific element to reach a condition. This is the workhorse for most automation tasks.

```clojure
;; Wait for element to become visible (default)
(spel/wait-for-selector ".results")

;; Explicit state + timeout
(spel/wait-for-selector ".results" {:state "visible" :timeout 5000})

;; Wait for a spinner to disappear
(spel/wait-for-selector ".loading-spinner" {:state "hidden"})

;; Wait for element to attach to DOM (doesn't need to be visible)
(spel/wait-for-selector "#data-container" {:state "attached"})

;; Wait for element to detach from DOM
(spel/wait-for-selector ".modal-overlay" {:state "detached"})
```

States:

| State | Meaning |
|-------|---------|
| `"visible"` (default) | Element exists in DOM and is visible (not `display:none`, not zero-size) |
| `"hidden"` | Element either doesn't exist or is not visible |
| `"attached"` | Element exists in DOM (may be hidden) |
| `"detached"` | Element does not exist in DOM |

Library equivalent:

```clojure
(page/wait-for-selector pg ".results")
(page/wait-for-selector pg ".results" {:state :visible :timeout 5000})
```

### `spel/wait-for-url`

Waits for the page URL to match a pattern. Essential for SPA navigation where clicking a link changes the route without a full page load.

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

Waits for a JavaScript expression to return a truthy value. Use this when you can't express readiness as element visibility or a URL change.

```clojure
;; Wait for a specific DOM element
(spel/wait-for-function "() => document.querySelector('#loaded')")

;; Wait for a JS variable
(spel/wait-for-function "() => window.appReady === true")

;; Wait for content to render
(spel/wait-for-function "() => document.body.innerText.length > 100")

;; Wait for a specific number of items
(spel/wait-for-function "() => document.querySelectorAll('.item').length >= 10")
```

Library: `(page/wait-for-function pg "() => window.appReady === true")`

### `spel/wait-for-timeout` (last resort)

Pauses execution for a fixed number of milliseconds. This is almost always the wrong choice. Fixed delays make tests slow and flaky: too short on slow machines, wastefully long on fast ones.

```clojure
;; Don't do this unless you truly have no other option
(spel/wait-for-timeout 1000)
```

The only acceptable use: waiting for a CSS animation or transition that has no observable state change you can detect. Even then, prefer `wait-for-function` with a CSS property check.

Library: `(page/wait-for-timeout pg 1000)` ... same caveat.

### `sleep` / `Thread/sleep` (non-browser only)

Plain JVM thread sleep. Does NOT interact with the browser event loop. Available as a global binding `(sleep ms)`, as `(spel/sleep ms)`, or as raw `(Thread/sleep (long ms))`.

```clojure
;; WRONG — never use sleep for browser synchronization:
(sleep 2000) ;; page might not be ready, flaky!
(spel/click ".button")

;; RIGHT — use page waits:
(spel/wait-for-selector ".button" {:state "visible"})
(spel/click ".button")
```

The only valid use of `sleep` is for non-browser delays: waiting for an external file to appear on disk, throttling requests to a non-browser API, polling a process. If you're touching a browser page, use a page wait.

## Common patterns

### SPA navigation (click → wait → verify)

Single-page apps don't trigger full page loads. After clicking a link, wait for the URL to change and the new content to appear.

```clojure
(spel/navigate "https://myapp.com")
(spel/wait-for-load-state :networkidle)
(spel/click "a[href='/dashboard']")
(spel/wait-for-url "**/dashboard")
(spel/wait-for-selector ".dashboard-content" {:state "visible"})
(println (spel/text-content ".dashboard-title"))
```

The pattern: interact → wait for URL → wait for element → proceed.

### Heavy portals and ad/tracker pages

Portal pages often keep loading third-party resources long after the meaningful content is ready. In those cases, waiting for full `:load` after every click is too strict.

Preferred pattern:

```clojure
(spel/navigate "https://onet.pl")
(spel/wait-for-load-state :load)

;; After clicking a heavy navigation target, relax the wait.
(spel/click "@eXXXX")
(spel/wait-for-url #".*wiadomosci.*")
(spel/wait-for-load-state :domcontentloaded)
```

Use this decision order after interactions on heavy pages:
1. `wait-for-url` when you know the route should change
2. `wait-for-selector` when you know the target content marker
3. `wait-for-load-state :domcontentloaded` when the page is content-ready but ads keep loading
4. Longer timeouts only as the final fallback

### Handling click timeouts on SPAs

If a click times out on a client-side app, the problem is almost always the wait strategy, not the click itself. NEVER skip the click and navigate directly — always simulate user actions like a human would.

```clojure
;; WRONG — skipping user actions:
;; (spel/navigate "https://www.frisco.pl/login")
;; This bypasses the actual user journey and misses real bugs.

;; RIGHT — click the element, then wait smarter:
(spel/click "@eXXXX")
(spel/wait-for-url #".*login.*")               ;; wait for route change
(spel/wait-for-load-state :domcontentloaded)   ;; don't wait for ads/trackers
```

When a click seems "unreliable":
- First: check if you're waiting for the wrong readiness signal (`:load` vs `:domcontentloaded`)
- Second: use `wait-for-url` to detect the route change after the click
- Third: use `wait-for-selector` to detect the target content appearing
- Last resort: increase the timeout — but NEVER skip the click itself
### Content loading (open page → wait for element → extract)

Pages that load data asynchronously after the initial render.

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
(spel/wait-for-selector ".titleline" {:state "visible"})
(let [title (spel/text-content (spel/first-element ".titleline"))]
  (println "Top story:" title))
```

### SPA with API data (open page → network idle → JS check)

For apps that fetch data from APIs after mounting:

```clojure
(spel/navigate "https://myapp.com/users")
(spel/wait-for-load-state :networkidle)
(spel/wait-for-function "() => document.querySelectorAll('tr.user-row').length > 0")
(println "Users:" (spel/all-text-contents "tr.user-row td.name"))
```

### Waiting for popups, downloads, and file choosers

All three follow the same pattern: pass an action callback that triggers the event. The return value is the captured object (Page, Download, or FileChooser).

```clojure
;; Popup: action opens a new tab, returns the new Page
(let [popup (spel/wait-for-popup
              #(spel/click "a[target=_blank]"))]
  (page/wait-for-load-state popup)
  (println "Popup:" (page/title popup)))

;; Download: action triggers a file download
(let [dl (spel/wait-for-download
           #(spel/click "a.download-link"))]
  (println "File:" (.suggestedFilename dl))
  (.saveAs dl (java.nio.file.Paths/get "/tmp/downloaded.pdf"
                (into-array String []))))
;; File chooser: action opens the native file dialog
(let [fc (spel/wait-for-file-chooser
           #(spel/click "input[type=file]"))]
  (.setFiles fc (into-array java.nio.file.Path
                  [(java.nio.file.Paths/get "/tmp/photo.jpg"
                     (into-array String []))])))
```

For simple file uploads, skip the file chooser entirely:

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
