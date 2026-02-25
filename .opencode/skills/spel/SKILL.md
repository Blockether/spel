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
| `spel codegen --help` | Codegen CLI help |
| `spel init-agents --help` | Agent scaffolding help |
| `spel init-agents --loop=opencode` | Scaffold E2E agents for OpenCode (default) |
| `spel init-agents --loop=claude` | Scaffold E2E agents for Claude Code |
| `spel init-agents --loop=vscode` | Scaffold E2E agents for VS Code / Copilot |

## ⚠️ SCI Eval vs Library — Key Naming Differences

In `--eval` mode, functions live in the `spel/` namespace with **different names** than the library:

| You want to... | Library (Clojure) | SCI (`--eval` / daemon) |
|---|---|---|
| Navigate to URL | `(page/navigate pg url)` | `(spel/goto url)` or `(spel/navigate url)` |
| Get page title | `(page/title pg)` | `(spel/title)` |
| Get page URL | `(page/url pg)` | `(spel/url)` |
| Get page HTML | `(page/content pg)` | `(spel/html)` |
| Evaluate JS | `(page/evaluate pg expr)` | `(spel/eval-js expr)` |
| Get text content | `(locator/text-content loc)` | `(spel/text loc)` |
| Get inner text | `(locator/inner-text loc)` | `(spel/inner-text loc)` |
| Take screenshot | `(page/screenshot pg opts)` | `(spel/screenshot opts)` |
| Generate PDF | `(page/pdf pg opts)` | `(spel/pdf opts)` |
| Set viewport | `(page/set-viewport-size pg w h)` | `(spel/set-viewport-size! w h)` |
| CSS selector | `(page/locator pg sel)` | `(spel/$ sel)` |
| Multiple matches | `(page/locator-all pg sel)` | `(spel/$$ sel)` |
| By text | `(page/get-by-text pg text)` | `(spel/$text text)` |
| By role | `(page/get-by-role pg role)` | `(spel/$role role)` |

**Key difference**: Library functions take an explicit `page`/`locator` argument. SCI functions use the implicit page managed by the daemon (or `spel/start!` in standalone scripts). When a daemon is running, `--eval` reuses its browser — no `spel/start!` or `spel/stop!` needed.

## SCI Sandbox Capabilities

The SCI eval environment (`--eval` mode) runs in a sandbox with registered namespaces and classes.

### ✅ Available in SCI
- All `spel/`, `snapshot/`, `annotate/`, `stitch/`, `input/`, `frame/`, `net/`, `loc/`, `assert/`, `core/` namespaces
- `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.repl`, `clojure.template`
- `clojure.java.io` (aliased as `io`): `io/file`, `io/reader`, `io/writer`, `io/input-stream`, `io/output-stream`, `io/copy`, `io/as-file`, `io/as-url`, `io/resource`, `io/make-parents`, `io/delete-file`
- `slurp`, `spit` — full file read/write
- `java.io.File`, `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.file.Paths`, `java.util.Base64`
- Playwright Java classes: `Page`, `Browser`, `BrowserContext`, `Locator`, `Frame`, `Request`, `Response`, `Route`, `ElementHandle`, `JSHandle`, `ConsoleMessage`, `Dialog`, `Download`, `WebSocket`, `Tracing`, `Keyboard`, `Mouse`, `Touchscreen`
- All Playwright enums: `AriaRole`, `LoadState`, `WaitUntilState`, `ScreenshotType`, etc.
- `role/` namespace for AriaRole constants (e.g., `role/button`, `role/link`)
- `markdown/` namespace for markdown rendering
- `stitch/` namespace for vertical image stitching

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
| `refs/PROFILES_AGENTS.md` | Browser profiles, agent scaffolding |

### Troubleshooting
| Ref | Topic |
|-----|-------|
| `refs/COMMON_PROBLEMS.md` | Common issues, debugging tips, error patterns |
