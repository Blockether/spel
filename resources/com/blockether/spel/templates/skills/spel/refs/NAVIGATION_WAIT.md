# Navigation and Wait Patterns

How to navigate pages and wait for things to happen. Covers both `--eval` mode (implicit page) and library mode (explicit `page` arg).

## Navigation

### `spel/goto` (eval) / `page/navigate` (library)

Navigate to a URL and optionally control when the navigation is considered "done."

```clojure
;; Basic navigation (waits for "load" event by default)
(spel/goto "https://example.com")

;; Wait until no network requests for 500ms
(spel/goto "https://example.com" {:wait-until "networkidle"})

;; Custom timeout (ms)
(spel/goto "https://example.com" {:wait-until "networkidle" :timeout 30000})
```

The `:wait-until` option controls what "loaded" means:

| Value | Fires when | Best for |
|-------|-----------|----------|
| `"commit"` | Response headers received | Fastest, just need the HTML |
| `"domcontentloaded"` | HTML parsed, deferred scripts done | Server-rendered pages |
| `"load"` (default) | All resources loaded (images, stylesheets) | Traditional multi-page sites |
| `"networkidle"` | No network requests for 500ms | SPAs, JS-heavy pages |

Library equivalent:

```clojure
(page/navigate pg "https://example.com")
(page/navigate pg "https://example.com" {:wait-until "networkidle" :timeout 30000})
```

### History Navigation

```clojure
;; --eval                          ;; Library equivalent
(spel/back)                        ;; (page/go-back pg)
(spel/forward)                     ;; (page/go-forward pg)
(spel/reload!)                     ;; (page/reload pg)
```

## Wait Strategies

Playwright is event-driven. Don't guess when something is ready. Wait for it.

### The Wait Hierarchy

Use the most specific wait available. Work down this list only when the previous option doesn't fit:

1. **`wait-for-load`** with the right state (page-level readiness)
2. **`wait-for`** on a specific element (DOM-level readiness)
3. **`wait-for-url`** for route changes (SPA navigation)
4. **`wait-for-function`** for custom JS conditions (app-level readiness)
5. **`spel/sleep`** as absolute last resort (time-based, fragile)

### 1. `spel/wait-for-load`

Waits for the page to reach a load state. Call this after `spel/goto` when you need a stricter readiness check than the default.

```clojure
;; Default: waits for "load" event
(spel/wait-for-load)

;; Wait for DOM parsed (faster than "load")
(spel/wait-for-load "domcontentloaded")

;; Wait for network to settle (best for SPAs)
(spel/wait-for-load "networkidle")

;; Wait for first response bytes
(spel/wait-for-load "commit")
```
States explained: `"load"` fires after images, stylesheets, and iframes finish. `"domcontentloaded"` fires once HTML is parsed and deferred scripts run, but images may still load. `"networkidle"` waits until no requests for 500ms, the go-to for SPAs. `"commit"` fires as soon as response headers arrive, the fastest option.

Library equivalent:

```clojure
(page/wait-for-load-state pg)                    ;; default: "load"
(page/wait-for-load-state pg :networkidle)       ;; keyword form works too
```

### 2. `spel/wait-for` (Element Waiting)

Waits for a specific element to reach a condition. This is the workhorse for most automation tasks.

```clojure
;; Wait for element to become visible (default)
(spel/wait-for ".results")

;; Explicit state + timeout
(spel/wait-for ".results" {:state "visible" :timeout 5000})

;; Wait for a spinner to disappear
(spel/wait-for ".loading-spinner" {:state "hidden"})

;; Wait for element to attach to DOM (doesn't need to be visible)
(spel/wait-for "#data-container" {:state "attached"})

;; Wait for element to detach from DOM
(spel/wait-for ".modal-overlay" {:state "detached"})
```

**States:**

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

### 3. `spel/wait-for-url`

Waits for the page URL to match a pattern. Essential for SPA navigation where clicking a link changes the route without a full page load.

```clojure
;; Glob pattern
(spel/wait-for-url "**/dashboard")

;; Exact URL
(spel/wait-for-url "https://example.com/dashboard")
```

Library equivalent:

```clojure
(page/wait-for-url pg "**/dashboard")
```

### 4. `spel/wait-for-function`

Waits for a JavaScript expression to return a truthy value. Your escape hatch for app-specific readiness checks that can't be expressed as element visibility or URL changes.

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

### 5. `spel/sleep` (Last Resort)

Pauses execution for a fixed number of milliseconds. **This is almost always the wrong choice.** Fixed delays make tests slow and flaky: too short on slow machines, wastefully long on fast ones.

```clojure
;; Don't do this unless you truly have no other option
(spel/sleep 1000)
```

The only acceptable use: waiting for a CSS animation or transition that has no observable state change you can detect. Even then, prefer `wait-for-function` with a CSS property check.

Library: `(page/wait-for-timeout pg 1000)` ... same caveat.

## Common Patterns

### SPA Navigation (Click → Wait → Verify)

Single-page apps don't trigger full page loads. After clicking a navigation link, wait for the URL to change and the new content to appear.

```clojure
(spel/goto "https://myapp.com")
(spel/wait-for-load "networkidle")
(spel/click "a[href='/dashboard']")
(spel/wait-for-url "**/dashboard")
(spel/wait-for ".dashboard-content" {:state "visible"})
(println (spel/text ".dashboard-title"))
```

The pattern: **interact → wait for URL → wait for element → proceed.**

### Content Loading (Navigate → Wait for Element → Extract)

Pages that load data asynchronously after the initial render.

```clojure
(spel/goto "https://news.ycombinator.com")
(spel/wait-for-load)
(spel/wait-for ".titleline" {:state "visible"})
(let [title (spel/text (spel/first ".titleline"))]
  (println "Top story:" title))
```

### SPA with API Data (Navigate → Network Idle → JS Check)

For apps that fetch data from APIs after mounting:

```clojure
(spel/goto "https://myapp.com/users")
(spel/wait-for-load "networkidle")
(spel/wait-for-function "() => document.querySelectorAll('tr.user-row').length > 0")
(println "Users:" (spel/all-text-contents "tr.user-row td.name"))
```

### Waiting for Popups, Downloads, and File Choosers

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
  (util/download-save-as! dl "/tmp/downloaded.pdf"))
```

## Library Quick Reference

| Eval (`spel/`) | Library (`page/`) | Purpose |
|---|---|---|
| `(spel/goto url)` | `(page/navigate pg url)` | Navigate to URL |
| `(spel/goto url opts)` | `(page/navigate pg url opts)` | Navigate with options |
| `(spel/wait-for-load)` | `(page/wait-for-load-state pg)` | Wait for load state |
| `(spel/wait-for-load state)` | `(page/wait-for-load-state pg state)` | Wait for specific state |
| `(spel/wait-for sel)` | `(page/wait-for-selector pg sel)` | Wait for element |
| `(spel/wait-for sel opts)` | `(page/wait-for-selector pg sel opts)` | Wait with options |
| `(spel/wait-for-url pat)` | `(page/wait-for-url pg pat)` | Wait for URL match |
| `(spel/wait-for-function js)` | `(page/wait-for-function pg js)` | Wait for JS truthy |
| `(spel/back)` | `(page/go-back pg)` | History back |
| `(spel/forward)` | `(page/go-forward pg)` | History forward |
| `(spel/reload!)` | `(page/reload pg)` | Reload page |
| `(spel/sleep ms)` | `(page/wait-for-timeout pg ms)` | Fixed delay (avoid) |
| `(spel/wait-for-popup f)` | `(page/wait-for-popup pg f)` | Capture popup page |
| `(spel/wait-for-download f)` | `(page/wait-for-download pg f)` | Capture download |
| `(spel/wait-for-file-chooser f)` | `(page/wait-for-file-chooser pg f)` | Capture file chooser |
