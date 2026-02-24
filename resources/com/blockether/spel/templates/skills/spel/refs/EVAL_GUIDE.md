# --eval Mode Guide
spel's `--eval` mode runs Clojure code inside a [SCI](https://github.com/babashka/sci) sandbox with full access to the Playwright API. No JVM startup, no project setup. Just pass code directly:

```bash
spel --eval '(spel/goto "https://example.com") (println (spel/title))'
```

Or run a file:

```bash
spel --eval script.clj
```

Or pipe from stdin:

```bash
echo '(spel/goto "https://example.com") (println (spel/title))' | spel --eval --stdin
```

> **Daemon mode is default.** When a daemon is running (`spel open URL` or `spel start`), `--eval` reuses the existing browser — no `spel/start!` or `spel/stop!` needed. See [Session Lifecycle](#session-lifecycle) for standalone scripts that manage their own browser.

## Discovering the API: `spel/help`

`spel/help` is your primary discovery tool. The eval sandbox has 350+ functions across 14 namespaces. Don't guess signatures. Ask.

```clojure
;; List all namespaces with function counts
(spel/help)
;; => spel/     143 functions  Simplified API with implicit page
;;    snapshot/   5 functions  Accessibility snapshots
;;    annotate/   8 functions  Screenshot annotations
;;    ...

;; List every function in a namespace
(spel/help "spel")
;; => spel/goto       [url] [url opts]   Navigates the page to a URL.
;;    spel/click      [sel] [sel opts]   Clicks an element.
;;    spel/fill       [sel value] ...    Fills an input element with text.
;;    ...

;; Search by keyword across ALL namespaces
(spel/help "screenshot")
;; => spel/screenshot           [path-or-opts]  Takes a screenshot of the page.
;;    spel/locator-screenshot   [sel] [sel opts] Takes a screenshot of the element.
;;    annotate/annotated-screenshot ...
;;    ...

;; Search for snapshot-related functions
(spel/help "snapshot")
;; => spel/snapshot             [] [page-or-opts] ...
;;    snapshot/capture          [] [page-or-opts] ...
;;    snapshot/capture-full     [] [page]          ...
;;    ...

;; Get details for a specific function
(spel/help "spel/click")
;; => spel/click  [sel] [sel opts]  Clicks an element.
```

**Rule of thumb**: run `(spel/help "keyword")` before writing any code that uses a function you haven't verified.

## Viewing Source: `spel/source`

When `spel/help` shows you a function exists but you need to understand what it does under the hood:

```clojure
;; Show the SCI wrapper source and which library function it delegates to
(spel/source "spel/goto")
;; => (defn goto [url] (page/navigate (require-page!) url))
;;    Delegates to: com.blockether.spel.page/navigate

;; Search by bare name (shows candidates if ambiguous)
(spel/source "screenshot")
```

## Session Lifecycle

### Starting a Session

`spel/start!` creates the full Playwright stack: Playwright instance, browser, context, and page.

```clojure
;; Defaults: headless Chromium, standard viewport
(spel/start!)

;; With options
(spel/start! {:headless false       ;; visible browser for debugging
              :slow-mo 500          ;; slow down every action by 500ms
              :browser :firefox      ;; :chromium (default), :firefox, :webkit
              :viewport {:width 1920 :height 1080}
              :base-url "https://example.com"  ;; relative URLs resolve against this
              :user-agent "MyBot/1.0"
              :locale "fr-FR"
              :timezone-id "Europe/Paris"
              :timeout 10000})       ;; default action timeout in ms
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:headless` | boolean | `true` | Run browser without visible window |
| `:slow-mo` | number | nil | Milliseconds to wait between actions |
| `:browser` | keyword | `:chromium` | `:chromium`, `:firefox`, or `:webkit` |
| `:viewport` | map | browser default | `{:width N :height N}` |
| `:base-url` | string | nil | Base URL for relative navigations |
| `:user-agent` | string | nil | Custom user agent string |
| `:locale` | string | nil | Browser locale (e.g. `"en-US"`, `"ja-JP"`) |
| `:timezone-id` | string | nil | Timezone (e.g. `"America/New_York"`) |
| `:timeout` | number | 30000 | Default timeout for all actions (ms) |

### Stopping and Restarting

```clojure
(spel/stop!)     ;; closes browser, cleans up all resources, returns :stopped

(spel/restart!)  ;; equivalent to stop! then start! with fresh defaults
(spel/restart! {:browser :firefox :headless false})  ;; restart with new options
```

In daemon mode (`spel start` / `spel open`), the daemon owns the browser. Calling `spel/start!` is a no-op if a page already exists, so scripts written for standalone `--eval` work unchanged in daemon mode.

### Tab Management

```clojure
(spel/tabs)          ;; list all open tabs: [{:index 0 :url "..." :title "..." :active true}]
(spel/new-tab!)      ;; open a new blank tab and switch to it
(spel/switch-tab! 0) ;; switch back to the first tab
```

Each tab is a separate Page. `spel/new-tab!` creates a new page in the current context and makes it the active page for all subsequent `spel/` calls.

## Available Namespaces

Every namespace below is pre-registered. No `require` or `import` needed.

### Browser Automation

| Namespace | Functions | Purpose |
|-----------|-----------|---------|
| `spel/` | ~143 | Simplified API with implicit page. Covers navigation, clicks, fills, screenshots, assertions, snapshots, annotations, and more. This is the primary namespace for `--eval` scripts. |
| `snapshot/` | 5 | Accessibility snapshot capture and ref resolution. `capture`, `capture-full`, `clear-refs!`, `ref-bounding-box`, `resolve-ref`. |
| `annotate/` | 8 | Visual overlays on page elements. `annotated-screenshot`, `audit-screenshot`, `mark!`, `unmark!`, `save!`, `save-audit!`. |
| `stitch/` | 3 | Vertical image stitching. `stitch-vertical`, `stitch-vertical-overlap`, `read-image`. |
| `input/` | 12 | Low-level keyboard, mouse, and touchscreen control. Takes explicit device args (e.g. `(input/key-press (spel/keyboard) "Enter")`). |
| `frame/` | 22+ | Frame and iframe operations. Navigate frames, create FrameLocators, evaluate JS in frames. Takes explicit Frame args. |
| `net/` | 46 | Network request/response inspection and route handling. Inspect headers, status, body. Mock or abort requests. |
| `loc/` | 39 | Raw Locator operations with explicit Locator arg. Click, fill, hover, check, get attributes, evaluate JS on elements. |
| `assert/` | 31 | Playwright assertion functions. `assert-that`, `has-text`, `is-visible`, `has-url`, `loc-not`, `page-not`. Takes assertion objects. |
| `core/` | 29 fn + 4 macros | Browser lifecycle. `with-playwright`, `with-browser`, `with-context`, `with-page`, `with-testing-page`. |
| `page/` | 42 | Raw Page operations with explicit page arg. Same functions as the library's `com.blockether.spel.page` namespace. |
| `locator/` | (alias) | Alias of `loc/`. Both names work identically. |
| `role/` | 72 constants | AriaRole constants: `role/button`, `role/link`, `role/heading`, `role/navigation`, `role/textbox`, etc. |
| `markdown/` | 2 | Markdown table parsing. `from-markdown-table`, `to-markdown-table`. |

### When to Use Which Namespace

For most `--eval` scripts, `spel/` is all you need. It wraps the implicit page and handles locator resolution from strings, refs, and Locator objects.

Drop down to `loc/`, `page/`, `frame/`, `input/`, or `net/` when you need:
- Explicit control over which page, frame, or locator you're operating on
- Low-level mouse/keyboard sequences
- Network interception and response mocking
- Multi-frame navigation

## Clojure Standard Library
These Clojure namespaces are available without any `require`:
| Namespace | Notes |
|-----------|-------|
| `clojure.core` | Full standard library: `map`, `filter`, `reduce`, `let`, `fn`, `atom`, `swap!`, `deref`, `assert`, etc. |
| `clojure.string` | `split`, `join`, `replace`, `trim`, `lower-case`, `upper-case`, `includes?`, `starts-with?`, `blank?`. **Also available as `str/`** — e.g. `(str/upper-case "hello")` |
| `clojure.set` | `union`, `intersection`, `difference`, `rename-keys` |
| `clojure.walk` | `postwalk`, `prewalk`, `keywordize-keys`, `stringify-keys` |
| `clojure.edn` | `read-string` for safe EDN parsing |
| `clojure.repl` | `doc`, `source`, `dir` |
| `clojure.template` | `do-template`, `apply-template` |
| `clojure.pprint` | `pprint`, `print-table`, `cl-format`. **Also available as `pprint/`** — e.g. `(pprint/pprint data)` |
| `json/` | JSON via [charred](https://github.com/cnuernber/charred): `json/read-json`, `json/write-json-str`. E.g. `(json/write-json-str {:a 1})` → `"{\"a\":1}"` |

## File I/O

### `slurp` and `spit`

```clojure
;; Read entire file as string
(slurp "/tmp/data.txt")

;; Write string to file (creates or overwrites)
(spit "/tmp/output.txt" "hello world")

;; Append to file
(spit "/tmp/log.txt" "new line\n" :append true)
```

### `clojure.java.io` (aliased as `io`)

```clojure
;; Create parent directories
(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file "/tmp/deep/nested/file.txt") "content")

;; File objects
(io/file "/tmp" "subdir" "file.txt")  ;; => #<File /tmp/subdir/file.txt>

;; Readers and writers
(with-open [r (io/reader "/tmp/data.txt")]
  (line-seq r))

;; Copy streams
(io/copy (io/input-stream "/tmp/src.bin")
         (io/output-stream "/tmp/dst.bin"))

;; Delete
(io/delete-file "/tmp/old.txt" true)  ;; true = silently ignore if missing
```

Available `io/` functions: `file`, `reader`, `writer`, `input-stream`, `output-stream`, `copy`, `as-file`, `as-url`, `resource`, `make-parents`, `delete-file`.

## Java Interop

### Playwright Classes

All core Playwright Java classes are registered and support full method interop:

`Page`, `Browser`, `BrowserContext`, `Locator`, `Frame`, `Request`, `Response`, `Route`, `ElementHandle`, `JSHandle`, `ConsoleMessage`, `Dialog`, `Download`, `WebSocket`, `Tracing`, `Keyboard`, `Mouse`, `Touchscreen`

```clojure
;; Direct method calls on Playwright objects
(let [pg (spel/page)]
  (.title pg)           ;; same as (spel/title)
  (.url pg)             ;; same as (spel/url)
  (.content pg))        ;; same as (spel/html)
```

### Playwright Enums
**Prefer the `role/` namespace** for AriaRole constants — idiomatic Clojure, no Java interop needed:
```clojure
role/button              ;; preferred
role/heading
role/link
role/textbox
role/checkbox

;; Java enum form also works (all enums from com.microsoft.playwright.options):
AriaRole/BUTTON          ;; equivalent to role/button
LoadState/NETWORKIDLE
WaitUntilState/COMMIT
ScreenshotType/PNG
MouseButton/RIGHT
ColorScheme/DARK
```

The `role/` namespace has 82 constants: `role/button`, `role/link`, `role/heading`, `role/textbox`, `role/checkbox`, `role/radio`, `role/combobox`, `role/navigation`, `role/dialog`, `role/tab`, `role/tabpanel`, `role/list`, `role/listitem`, `role/img`, `role/table`, `role/row`, `role/cell`, etc.

Other enum classes (Java interop only): `ColorScheme`, `ForcedColors`, `HarContentPolicy`, `HarMode`, `HarNotFound`, `LoadState`, `Media`, `MouseButton`, `ReducedMotion`, `ScreenshotType`, `ServiceWorkerPolicy`, `WaitForSelectorState`, `WaitUntilState`.

### Java Utility Classes

```clojure
;; java.io.File
(let [f (java.io.File. "/tmp/test.txt")]
  (.exists f)
  (.getName f)
  (.getParent f))

;; java.util.Base64
(let [encoder (java.util.Base64/getEncoder)
      decoder (java.util.Base64/getDecoder)]
  (->> (.getBytes "hello")
       (.encodeToString encoder)  ;; => "aGVsbG8="
       (.decode decoder)
       (String.)))               ;; => "hello"

;; java.nio.file.Paths / Files
(let [path (java.nio.file.Paths/get "/tmp" (into-array String ["test.txt"]))]
  (java.nio.file.Files/exists path (into-array java.nio.file.LinkOption [])))
```

## What's NOT Available

The SCI sandbox has boundaries. These will fail:

- **`require`, `use`, `import`**: All namespaces are pre-registered. You can't load new ones.
- **Arbitrary Java class construction**: Only registered classes work. `(java.util.HashMap.)` will fail. `(java.io.File. "/tmp")` works because `File` is registered.
- **Macro definitions**: `defmacro` is not available. Use functions instead.
- **Loading external libraries**: No Clojure deps, no Maven artifacts. Everything you need is already in the sandbox.

If you need something that isn't available, write a `.clj` library file and use the library API (JVM mode) instead of `--eval`.

## Complete Example: Multi-Step Eval Script

This script demonstrates a realistic workflow: start a session, explore a page, capture data, annotate, and write results to disk.

```clojure
;; Save as explore.clj, run with: spel --eval explore.clj

;; 1. Start a headless browser session
(spel/start! {:viewport {:width 1280 :height 800}})

;; 2. Discover what functions are available for snapshots
(println "=== Snapshot functions ===")
(println (spel/help "snapshot"))

;; 3. Navigate and wait for the page to settle
(spel/goto "https://news.ycombinator.com")
(spel/wait-for-load)

;; 4. Grab basic page info
(println "Title:" (spel/title))
(println "URL:" (spel/url))

;; 5. Take an accessibility snapshot
(let [snap (spel/snapshot)
      tree (:tree snap)
      refs (:refs snap)]

  ;; 6. Write the snapshot tree to a file
  (spit "/tmp/hn-snapshot.txt" tree)
  (println "Snapshot saved. Ref count:" (count refs))

  ;; 7. Save an annotated screenshot with numbered overlays
  (spel/save-annotated-screenshot! refs "/tmp/hn-annotated.png")
  (println "Annotated screenshot saved."))

;; 8. Read back the snapshot we just wrote
(let [content (slurp "/tmp/hn-snapshot.txt")
      lines   (clojure.string/split-lines content)]
  (println "First 5 lines of snapshot:")  
  (doseq [line (take 5 lines)]
    (println " " line)))

;; 9. Take a plain screenshot too
(spel/screenshot {:path "/tmp/hn-plain.png"})

;; 10. Clean up
(spel/stop!)
(println "Done.")
```

Run it:

```bash
spel --eval explore.clj
```

## CLI Flags for `--eval`

| Flag | Purpose |
|------|---------|
| `--eval '<code>'` | Evaluate inline Clojure expression |
| `--eval file.clj` | Evaluate a Clojure file |
| `--eval --stdin` | Read code from stdin (pipe-friendly) |
| `--eval --interactive` | Use a visible (headed) browser |
| `--eval --load-state FILE` | Load auth/storage state before evaluation |
| `--autoclose` | Close the daemon after eval completes |
| `--timeout <ms>` | Set default action timeout |
| `--session <name>` | Use a named browser session |
| `--json` | JSON output format |

## Tips

**Console output is captured automatically.** Browser `console.log`, `console.warn`, and `console.error` messages print to stderr after your eval result. Check stderr if something fails silently in the browser.

**Daemon mode is transparent.** If a daemon is already running (`spel open URL` was called earlier), `spel/start!` becomes a no-op and your script reuses the existing browser. This means scripts work identically whether the daemon is running or not.

**Prefer `spel/` over raw namespaces.** The `spel/` namespace handles locator resolution from strings, snapshot refs (`"@e1"`), and Locator objects. Raw namespaces like `loc/` and `page/` require you to manage objects yourself.

**Use `spel/wait-for-load "networkidle"` for SPAs.** Single Page Applications render client-side after the initial `load` event. Waiting for `networkidle` ensures React, Vue, or similar frameworks have finished fetching data and rendering.

**Never use `spel/sleep` for synchronization.** It's a flaky anti-pattern. Use `spel/wait-for`, `spel/wait-for-url`, `spel/wait-for-function`, or `spel/wait-for-load` instead. The only acceptable use of sleep is waiting for a CSS animation with no observable state change.