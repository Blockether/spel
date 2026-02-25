---
name: spel
description: "com.blockether.spel package - Clojure wrapper for Playwright 1.58.0. Browser automation, testing, assertions, codegen, CLI. Use when working with browser automation, E2E tests, Playwright API, or visual testing in Clojure."
license: Apache-2.0
compatibility: opencode
---

# spel - Clojure Playwright Wrapper

`com.blockether.spel` wraps Playwright Java 1.58.0 with idiomatic Clojure.

## Quick Reference

| Command | Purpose |
|---------|---------|
| `spel --help` | CLI help |
| `spel --stealth open <url>` | Open URL with anti-detection stealth mode |
| `spel state export --help` | State export help (cookies + localStorage) |
| `spel state export --profile <path> -o auth.json` | Export Chrome cookies + localStorage to Playwright JSON |
| `spel codegen --help` | Codegen CLI help |
| `spel init-agents --help` | Agent scaffolding help |
| `spel init-agents --loop=opencode` | Scaffold E2E agents for OpenCode (default) |
| `spel init-agents --loop=claude` | Scaffold E2E agents for Claude Code |
| `spel init-agents --loop=vscode` | Scaffold E2E agents for VS Code / Copilot |
| `spel search "query"` | Google search (table output) |
| `spel search "query" --json` | Google search (JSON output) |
| `spel search "query" --images` | Google image search |
| `spel search "query" --news` | Google news search |
| `spel search "query" --limit 5` | Show only first 5 results |
| `spel search "query" --open 1` | Navigate to result #1 |

## Google Search

Search Google from the CLI, SCI `--eval` mode, or Clojure library — no API key required. Uses Playwright with stealth mode.

### CLI

```bash
spel search "clojure programming"                    # table output (default)
spel search "clojure" --json                          # JSON output
spel search "cats" --images                           # image search
spel search "world news" --news                       # news search
spel search "query" --page 2 --num 20                 # pagination
spel search "query" --max-pages 3 --json              # multi-page collect
spel search "query" --limit 5                         # first 5 results only
spel search "query" --open 1                          # navigate to result #1
spel search "query" --lang en --time-range week       # language + time filter
spel search "query" --screenshot results.png          # save screenshot
spel search "query" --no-stealth                      # disable stealth mode
```

| Flag | Description |
|------|-------------|
| `--images` | Image search |
| `--news` | News search |
| `--page N` | Results page (default: 1) |
| `--num N` | Results per page (default: 10) |
| `--max-pages N` | Collect N pages |
| `--limit N` | Show first N results |
| `--open N` | Navigate to result #N |
| `--json` | JSON output |
| `--screenshot PATH` | Save screenshot |
| `--lang LANG` | Language code |
| `--safe MODE` | Safe search: off, medium, high |
| `--time-range RANGE` | Time: day, week, month, year |
| `--no-stealth` | Disable stealth mode |

### SCI `--eval` Mode

```clojure
;; Basic search (returns Clojure map)
(def r (search/search! "clojure programming"))
(:results r)    ;; => [{:title "..." :url "..." :snippet "..." :position 1} ...]
(:stats r)      ;; => "About 1,234,567 results (0.42 seconds)"

;; Image / news search
(search/search! "cats" {:type :images})
(search/search! "news" {:type :news})

;; Extract from current page (after search!)
(search/extract-web-results)          ;; web results
(search/extract-image-results)        ;; image results
(search/extract-news-results)         ;; news results
(search/extract-people-also-ask)      ;; PAA questions
(search/extract-related-searches)     ;; related queries
(search/extract-result-stats)         ;; result stats

;; Pagination
(search/has-next-page?)   ;; => true/false
(search/next-page!)       ;; navigate + extract next page
(search/go-to-page! "query" 3) ;; jump to page 3

;; Lazy pagination with iteration
(->> (search/search-pages "clojure")
     (take 3)
     (mapcat :results)
     (map :title))

;; Build URL only
(search/search-url "test" {:type :images :page 2})
```

### Library API

```clojure
(require '[com.blockether.spel.search :as search])

;; Single page search
(search/search! page "clojure" {:type :web :num 20})

;; Multi-page collect
(search/search-and-collect! page "clojure" {:type :web :max-pages 3})

;; Lazy pagination with iteration
(->> (search/search-pages page "clojure")
     (take 3)
     (mapcat :results))

;; Individual extractors (after navigating to Google results)
(search/extract-web-results page)
(search/extract-image-results page)
(search/extract-news-results page)
(search/extract-people-also-ask page)
(search/extract-related-searches page)
(search/has-next-page? page)
(search/next-page! page)
```

## ⚠️ SCI Eval vs Library — Key Differences

In `--eval` mode, function names **match the library**. The only difference is **implicit vs explicit arguments**:

- **Library (JVM)**: functions take explicit `page`/`locator` arguments.
- **SCI (`--eval`)**: same function names, but the page/locator is implicit (managed by the daemon or `spel/start!`).

Example:

```clojure
;; Library
(page/navigate pg url)
(page/locator pg "#login")
(locator/click (page/locator pg "#login"))

;; SCI --eval (implicit page)
(spel/navigate url)
(spel/locator "#login")
(spel/click "#login")
```

When a daemon is running, `--eval` reuses its browser — no `spel/start!` or `spel/stop!` needed.

## SCI Sandbox Capabilities

The SCI eval environment (`--eval` mode) runs in a sandbox with registered namespaces and classes.

### ✅ Available in SCI
- All `spel/`, `snapshot/`, `annotate/`, `stitch/`, `search/`, `input/`, `frame/`, `net/`, `loc/`, `assert/`, `core/` namespaces
- `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.repl`, `clojure.template`
- `clojure.java.io` (aliased as `io`): `io/file`, `io/reader`, `io/writer`, `io/input-stream`, `io/output-stream`, `io/copy`, `io/as-file`, `io/as-url`, `io/resource`, `io/make-parents`, `io/delete-file`
- `slurp`, `spit` — full file read/write
- `java.io.File`, `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.file.Paths`, `java.util.Base64`
- Playwright Java classes: `Page`, `Browser`, `BrowserContext`, `Locator`, `Frame`, `Request`, `Response`, `Route`, `ElementHandle`, `JSHandle`, `ConsoleMessage`, `Dialog`, `Download`, `WebSocket`, `Tracing`, `Keyboard`, `Mouse`, `Touchscreen`
- All Playwright enums: `AriaRole`, `LoadState`, `WaitUntilState`, `ScreenshotType`, etc.
- `role/` namespace for AriaRole constants (e.g., `role/button`, `role/link`)
- `markdown/` namespace for markdown rendering
- `stitch/` namespace for vertical image stitching
- `search/` namespace for Google Search (web, images, news, pagination, `iteration`-based lazy pages)
- `iteration` binding — `clojure.core/iteration` for lazy pagination

### ❌ NOT Available in SCI
- Arbitrary Java class construction — only registered classes work
- `require`, `use`, `import` — namespaces are pre-registered, cannot load new ones

## Rules

| Rule | Details |
|------|---------|
| **Assertions** | Exact string matching — NEVER substring unless explicitly `contains-text` |
| **Roles** | Require `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`) |
| **Fixtures** | Use `:context` hooks from `test-fixtures`, NEVER nest manually inside `it`/`deftest` blocks |
| **Default fixture** | Always use `with-traced-page` — enables tracing/HAR on every run for debugging |
| **Error handling** | All errors return anomaly maps `{:error :msg :data}` — check with `core/anomaly?` |
| **Lifecycle** | Use `with-*` macros (`with-playwright`, `with-browser`, `with-page`) — resources auto-cleaned |
| **Screenshots** | After visual/UI changes, ALWAYS take and display a screenshot as proof |

## Reference Documentation

Detailed documentation is split into topic-specific reference files:

### Core API & Patterns
| Ref | Topic |
|-----|-------|
| `refs/FULL_API.md` | **Complete API tables** — auto-generated library API, SCI eval API, CLI commands |
| `refs/PAGE_LOCATORS.md` | Page locators, selectors, get-by-* methods |
| `refs/NAVIGATION_WAIT.md` | Navigation, waiting, load states |
| `refs/SELECTORS_SNAPSHOTS.md` | CSS/XPath selectors, accessibility snapshots |
| `refs/EVAL_GUIDE.md` | SCI eval mode guide, `--eval` patterns |
| `refs/CONSTANTS.md` | Constants, enums, AriaRole values |

### Browser & Network
| Ref | Topic |
|-----|-------|
| `refs/BROWSER_OPTIONS.md` | Browser launch/context options, presets, lifecycle macros, device emulation |
| `refs/NETWORK_ROUTING.md` | Page routing, request/response inspection, WebSocket |
| `refs/FRAMES_INPUT.md` | Frame navigation, keyboard/mouse/touchscreen input |

### Testing & Assertions
| Ref | Topic |
|-----|-------|
| `refs/TESTING_CONVENTIONS.md` | Test framework conventions, fixtures, running tests (flavour-specific) |
| `refs/ASSERTIONS_EVENTS.md` | Playwright assertions, events/signals, file input |
| `refs/SNAPSHOT_TESTING.md` | Snapshot testing patterns |
| `refs/API_TESTING.md` | API testing context, HTTP methods, hooks, retry |

### Reporting & CI
| Ref | Topic |
|-----|-------|
| `refs/ALLURE_REPORTING.md` | Allure labels, steps, attachments, reporter config, trace viewer |
| `refs/CI_WORKFLOWS.md` | GitHub Actions CI/CD workflows |

### CLI & Tools
| Ref | Topic |
|-----|-------|
| `refs/CODEGEN_CLI.md` | Codegen record/transform, CLI commands, page exploration, configuration |
| `refs/PDF_STITCH_VIDEO.md` | PDF generation, image stitching, video recording |
| `refs/PROFILES_AGENTS.md` | Browser profiles, **stealth mode**, **cookie export**, storage state, agent scaffolding |

### Troubleshooting
| Ref | Topic |
|-----|-------|
| `refs/COMMON_PROBLEMS.md` | Common issues, debugging tips, error patterns |
