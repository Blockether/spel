# eval-sci mode guide

`eval-sci` runs Clojure inside a [SCI](https://github.com/babashka/sci) sandbox with full Playwright access. No JVM startup, no project setup.

```bash
spel eval-sci '(spel/navigate "https://example.org") (println (spel/title))'
spel eval-sci script.clj
echo '(spel/navigate "…") (println (spel/title))' | spel eval-sci --stdin
```

> **Daemon mode is default.** If a daemon is running (`spel open URL` or `spel start`), `eval-sci` reuses the existing browser — no `spel/start!` / `spel/stop!` needed. Standalone scripts manage their own browser (see § Session lifecycle).

## Discovery — `spel/help`

Sandbox has 350+ fns across 14 namespaces. Don't guess signatures.

```clojure
(spel/help)                  ; list namespaces + counts
(spel/help "spel")           ; list every fn in one namespace with signatures + docs
(spel/help "screenshot")     ; keyword search across namespaces
(spel/help "spel/click")     ; one fn's signature + doc
```

Rule of thumb: run `(spel/help "keyword")` before calling anything you haven't verified.

## Source — `spel/source`

```clojure
(spel/source "spel/navigate")    ; shows SCI wrapper + delegation target
(spel/source "screenshot")        ; bare name; lists candidates when ambiguous
```

## Session lifecycle

Daemon mode: call `spel/navigate`, `spel/screenshot`, … directly. `spel/start!` is a no-op when a page already exists. `spel/stop!` is only needed for standalone scripts.

### Standalone scripts (no daemon)

```clojure
(spel/start!)
(spel/start! {:headless false :slow-mo 500
              :browser :firefox            ; :chromium (default) / :webkit
              :viewport {:width 1920 :height 1080}
              :base-url "https://example.org"
              :user-agent "MyBot/1.0"
              :locale "fr-FR" :timezone-id "Europe/Paris"
              :timeout 10000})
```

| Option | Type | Default | Notes |
|--------|------|---------|-------|
| `:headless` | bool | `true` | Visible window when `false` |
| `:slow-mo` | ms | nil | Slow down every action |
| `:browser` | kw | `:chromium` | `:firefox` / `:webkit` |
| `:viewport` | `{:width :height}` | browser default | |
| `:base-url` | str | nil | Relative URLs resolve against this |
| `:user-agent` | str | nil | |
| `:locale` / `:timezone-id` | str | nil | |
| `:timeout` | ms | 30000 | Default action timeout |

```clojure
(spel/stop!)                                                ; => :stopped
(spel/restart!)                                             ; stop + start (fresh defaults)
(spel/restart! {:browser :firefox :headless false})
```

### Tabs

```clojure
(spel/tabs)                                                 ; [{:index 0 :url … :title … :active true} …]
(spel/new-tab!)                                             ; opens + switches
(spel/switch-tab! 0)
```

Each tab is a distinct Page. `new-tab!` makes the new one current for every subsequent `spel/` call.

## Available namespaces (pre-registered)

| Namespace | Fns | Purpose |
|-----------|----:|---------|
| `spel/` | ~143 | Simplified API with implicit page — the primary namespace |
| `snapshot/` | 5 | `capture`, `capture-full`, `clear-refs!`, `ref-bounding-box`, `resolve-ref` |
| `annotate/` | 8 | `annotated-screenshot`, `save!`, `mark!`, `unmark!`, `audit-screenshot`, `save-audit!`, `report->html`, `report->pdf` |
| `stitch/` | 3 | `stitch-vertical`, `stitch-vertical-overlap`, `read-image` |
| `input/` | 12 | Low-level keyboard / mouse / touchscreen (explicit device args) |
| `frame/` | 22+ | Frame + iframe ops (explicit Frame args) |
| `net/` | 46 | Request/response inspection, routing, mocking |
| `loc/` (alias `locator/`) | 39 | Raw Locator ops with explicit Locator arg |
| `assert/` | 31 | `assert-that`, `has-text`, `is-visible`, `has-url`, `loc-not`, `page-not`, … |
| `core/` | 29 fn + 4 macros | Lifecycle — `with-testing-page`/`-api`, `with-playwright`, `with-browser`, `with-context`, `with-page` |
| `page/` | 42 | Raw Page ops with explicit page arg |
| `role/` | 82 constants | `role/button`, `role/heading`, `role/navigation`, … |
| `markdown/` | 2 | `from-markdown-table`, `to-markdown-table` |
| `constants/` | 25 | Playwright enum values as named Clojure vars |
| `device/` | 20 | Device presets + `device-presets` / `viewport-presets` helper maps |

### When to drop down

Use `spel/` for almost everything — it handles locator resolution from strings, snapshot refs (`"@e2yrjz"`), and Locator objects. Fall back to `loc/`, `page/`, `frame/`, `input/`, `net/` when you need explicit control over which object you're operating on, low-level pointer/keyboard sequences, network interception, or multi-frame navigation.

### Constants & device presets

Playwright enums pass as keywords in option maps; options layer converts to Java enums. `constants/` provides named vars. Java enum interop also works.

```clojure
(spel/wait-for-load-state :networkidle)
(spel/navigate "https://example.org" {:wait-until :commit})
(spel/emulate-media! {:color-scheme :dark})
(spel/click "#el" {:button :right})

(spel/wait-for-load-state constants/load-state-networkidle)
(spel/wait-for-load-state LoadState/NETWORKIDLE)          ; interop

(spel/start! {:device :iphone-14})
(spel/start! {:device device/iphone-14})

(*json-encoder* {:a 1 :b [2 3]})                          ; => "{\"a\":1,\"b\":[2,3]}"
```

See `CONSTANTS.md` for the full keyword reference.

## Clojure stdlib (no `require` needed)

| Namespace | Notes |
|-----------|-------|
| `clojure.core` | Full stdlib |
| `clojure.string` (alias `str/`) | `split`, `join`, `replace`, `trim`, `includes?`, `starts-with?`, `blank?`, … |
| `clojure.set` / `clojure.walk` / `clojure.edn` / `clojure.repl` / `clojure.template` | Usual suspects |
| `pprint/` (alias `clojure.pprint/`) | [fipp](https://github.com/brandonbloom/fipp) — GraalVM-safe |
| `json/` | [charred](https://github.com/cnuernber/charred): `read-json`, `write-json-str` |
| `*json-encoder*` | Dynamic, defaults to `json/write-json-str`; rebind to customize |

## File I/O

```clojure
(slurp "/tmp/data.txt")
(spit "/tmp/out.txt" "hello")
(spit "/tmp/log.txt" "more\n" :append true)

;; clojure.java.io (aliased `io`)
(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file "/tmp/deep/nested/file.txt") "content")
(with-open [r (io/reader "/tmp/data.txt")] (line-seq r))
(io/copy (io/input-stream "/tmp/src.bin") (io/output-stream "/tmp/dst.bin"))
(io/delete-file "/tmp/old.txt" true)                      ; true = ignore missing
```

Available `io/` fns: `file`, `reader`, `writer`, `input-stream`, `output-stream`, `copy`, `as-file`, `as-url`, `resource`, `make-parents`, `delete-file`.

## Java interop

### Playwright classes (registered)

`Page`, `Browser`, `BrowserContext`, `Locator`, `Frame`, `Request`, `Response`, `Route`, `ElementHandle`, `JSHandle`, `ConsoleMessage`, `Dialog`, `Download`, `WebSocket`, `Tracing`, `Keyboard`, `Mouse`, `Touchscreen`.

```clojure
(let [pg (spel/page)]
  (.title pg) (.url pg) (.content pg))
```

### Enums

Prefer `role/` for AriaRole constants. `AriaRole/BUTTON` also works.

Interop-only enum classes: `ColorScheme`, `ForcedColors`, `HarContentPolicy`, `HarMode`, `HarNotFound`, `LoadState`, `Media`, `MouseButton`, `ReducedMotion`, `ScreenshotType`, `ServiceWorkerPolicy`, `WaitForSelectorState`, `WaitUntilState`.

### Registered JDK classes

`File`, `Base64`, `Files`, `Paths`, `Path`, `LinkOption`, `FileAttribute`, `Thread`, `System`.

```clojure
(let [enc (java.util.Base64/getEncoder)
      dec (java.util.Base64/getDecoder)]
  (->> (.getBytes "hello") (.encodeToString enc) (.decode dec) (String.)))    ; => "hello"

(System/getenv "HOME")
(System/currentTimeMillis)
(Thread/sleep (long 500))                ; non-browser delays only — see tip below
```

## Not available

- `require` / `use` / `import` — namespaces are pre-registered.
- Arbitrary Java class construction — only registered classes work.
- `defmacro`.
- Loading external libraries (no deps / Maven).
- STM / concurrency primitives (`ref`, `dosync`, `future`, `agent`). Use `atom`, `volatile!`, `promise`.

Need something unavailable → write a `.clj` library file and use the library API (JVM mode).

## Complete example

```clojure
(spel/start! {:viewport {:width 1280 :height 800}})

(println (spel/help "snapshot"))

(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
(println "Title:" (spel/title) "URL:" (spel/url))

(let [snap (spel/capture-snapshot)]
  (spit "/tmp/hn-snapshot.txt" (:tree snap))
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/hn-annotated.png")
  (println "Refs:" (count (:refs snap))))

(spel/screenshot {:path "/tmp/hn-plain.png"})
(spel/stop!)
```

## CLI flags

| Flag | Purpose |
|------|---------|
| `eval-sci '<code>'` | Inline expression |
| `eval-sci file.clj` | Run a file |
| `eval-sci --stdin` | Read from stdin |
| `eval-sci --interactive` | Headed browser |
| `eval-sci --load-state FILE` | Pre-load auth/storage state |
| `--autoclose` | Close daemon after eval |
| `--timeout <ms>` | Default action timeout |
| `--session <name>` | Named session |
| `--json` | JSON output |

## Tips

- Browser `console.log/warn/error` prints to stderr after the eval result.
- In daemon mode, don't call `spel/start!` / `spel/stop!` — daemon persists page state between calls, so no redundant re-navigations.
- Prefer `spel/` over raw namespaces; it resolves strings / refs / Locator objects uniformly.
- For SPAs, wait on `:networkidle` (or better, `wait-for-selector` / `wait-for-function`).
- **Never** use `spel/wait-for-timeout` or `sleep` as sync. Use event-driven waits.

| Fn | Needs browser? | What | When |
|----|:-:|------|------|
| `spel/wait-for-selector` | ✓ | Element appears/disappears | preferred |
| `spel/wait-for-url` | ✓ | URL matches | navigation |
| `spel/wait-for-load-state` | ✓ | load / networkidle | page loads |
| `spel/wait-for-function` | ✓ | JS expr truthy | async content |
| `spel/wait-for-timeout` | ✓ | Fixed delay | last resort |
| `sleep` / `(Thread/sleep (long ms))` | ✗ | JVM thread sleep | non-browser delays only |
