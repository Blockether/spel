---
name: spel
description: "com.blockether.spel package - Clojure wrapper for Playwright 1.58.0. Browser automation, testing, assertions, codegen, CLI. Use when user says 'test the login page', 'find bugs on this site', 'automate the checkout flow', 'explore the website', 'take a screenshot', 'write E2E tests', 'run visual regression', 'scrape data from', or asks about Playwright in Clojure. Do NOT use for general web development, backend APIs without browser context, or non-Playwright testing frameworks."
version: "{{version}}"
license: Apache-2.0
compatibility: opencode
---

# spel - Clojure Playwright wrapper

`com.blockether.spel` wraps Playwright Java 1.58.0 with idiomatic Clojure.

## Version check

This skill was generated for spel **{{version}}**. Verify compatibility:

```bash
spel version
```

If the installed version does not match **{{version}}**:
1. Show warning: **"spel skill is outdated (skill: {{version}}, installed: <actual version>)."**
2. Propose reinitialization: **"Run `spel init-agents` to reinitialize the skill for the installed version."**

## Quick reference

## Getting started: use the orchestrator

**NEVER call specialist agents directly.** Use `@spel-orchestrator` — it analyzes your request and routes to the right pipeline automatically.

```
@spel-orchestrator test the login page
@spel-orchestrator find bugs on https://example.com
@spel-orchestrator automate the checkout flow
```

The orchestrator routes to:
- E2E test writing (`@spel-test-planner` → `@spel-test-writer`) — plan with self-challenge → generate with self-heal
- Bug finding (`@spel-bug-hunter`) — explore → hunt with visual regression → self-challenge → verdict → HTML + Markdown reports
- Automation specialists via orchestrator (`@spel-explorer`, `@spel-automator`, `@spel-presenter`) — Browser automation (explore/auth → script → document)

Direct specialist invocation is deprecated in this workflow. Prefer `@spel-orchestrator` so gates, artifacts, and sequencing remain consistent.
Artifact-first rule: if you ask for JSON/report files, the orchestrator must treat those exact paths as required outputs, stop at gates, and keep `orchestration/*-pipeline.json` handoff manifests up to date.

Runtime note:
- In a fully scaffolded spel environment, `@spel-orchestrator` coordinates all specialist agents directly.
- In constrained runtimes where specialist agents are not invokable, fall back to the equivalent workflow directly with spel CLI and `eval-sci`, but keep the same artifact-first contract and write the same `orchestration/*-pipeline.json` handoffs.

Proven navigation playbook:
- ALWAYS simulate user actions: click links, buttons, and navigation elements like a real human would. NEVER use `spel open <url>` to skip navigation steps — only use it for the initial page load.
- Prefer split initial load: `spel open <url>` first, then `spel wait --load ...` as a separate command.
- Default follow-up wait: `spel wait --load load` for traditional multi-page sites.
- Heavy portal or ad/tracker pages: prefer `spel wait --load domcontentloaded` after clicks and use `spel wait --url <partial>` when the route change matters more than full resource completion.
- Treat longer click timeouts as a last resort, not the first fix.


| Command | Purpose |
|---------|---------|
| `spel --help` | CLI help |
| `spel open <url>` | Open URL (stealth mode is ON by default) |
| `spel --auto-connect open <url>` | Auto-discover running Chrome/Edge and open URL via CDP |
| `CDP_PORT=$(spel find-free-port) && spel --session agent-$(date +%s) --cdp http://127.0.0.1:$CDP_PORT open <url>` | Connect to Chrome/Edge via explicit CDP endpoint (ephemeral port + named session) |
| `spel --profile <path> open <url>` | Open URL with persistent Chrome profile |
| `spel --channel msedge --profile <path> open <url>` | Open with Edge profile |
| `spel --load-state auth.json open <url>` | Open with browser state JSON (cookies/localStorage) |
| `spel --load-state auth.json eval-sci 'script.clj'` | Run script with pre-loaded auth state |
| `spel codegen --help` | Codegen CLI help |
| `spel init-agents --help` | Agent scaffolding help |
| `spel init-agents --loop=opencode` | Scaffold E2E agents for OpenCode (default) |
| `spel init-agents --loop=claude` | Scaffold E2E agents for Claude Code |
| `spel init-agents --loop=claude` | ~~`--loop=vscode` is DEPRECATED — exits with error~~ |
| `spel search "query"` | Google search (table output) |
| `spel search "query" --json` | Google search (JSON output) |
| `spel search "cats" --images` | Google image search |
| `spel search "world news" --news` | Google news search |
| `spel search "query" --limit 5` | Show only first 5 results |
| `spel search "query" --open 1` | Navigate to result #1 |
| `spel snapshot -i` | Accessibility snapshot (interactive elements only, includes `[pos:X,Y W×H]` screen coordinates) |
| `spel snapshot -i -c` | Compact interactive snapshot (removes bare role lines) |
| `spel click @eXXXXX` | Click element by snapshot ref |
| `spel fill @eXXXXX "text"` | Fill input by snapshot ref |
| `spel screenshot name.png` | Take screenshot |
| `spel annotate` | Inject visual overlays on elements |
| `spel unannotate` | Remove visual overlays |
| `spel wait --text "..."` | Wait for text to appear |
| `spel wait --load load` | Wait for page load |
| `spel close` | Close browser session |
| `spel codegen record -o rec.jsonl <url>` | Record browser session |
| `spel stitch img1.png img2.png -o out.png` | Stitch screenshots vertically |
| `spel state save [path]` | Save current browser state |
| `spel state load [path]` | Restore saved browser state |

## Google search

Search Google from CLI, SCI, or library — no API key required. Quick example:

```bash
spel search "clojure programming"              # table output
spel search "clojure" --json                    # JSON output
spel search "cats" --images                     # image search
spel search "query" --open 1                    # navigate to result #1
```

For full CLI flags, SCI functions, and library API, see `references/SEARCH_API.md`.

## ⚠️ SCI eval vs library: key differences

In `eval-sci` mode, function names match the library. The only difference is implicit vs explicit arguments:

- Library (JVM): functions take explicit `page`/`locator` arguments.
- SCI (`eval-sci`): same function names, but the page/locator is implicit (managed by the daemon or `spel/start!`).

```clojure
;; Library
(page/navigate pg url)
(page/locator pg "#login")
(locator/click (page/locator pg "#login"))

;; SCI eval-sci (implicit page)
(spel/navigate url)
(spel/locator "#login")
(spel/click "#login")

;; Keyboard press (page-level, no selector needed)
(spel/press "Escape")
(spel/press "Enter")
(spel/press "Tab")
(spel/press "Control+a")
(spel/keyboard-press "Escape")    ;; explicit alias

;; Locator-level press (on specific element)
(spel/press "#my-input" "Enter")
```

When a daemon is running, `eval-sci` reuses its browser — no `spel/start!` or `spel/stop!` needed.

## SCI sandbox capabilities

### Available in SCI

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

### NOT available in SCI

- Arbitrary Java class construction — only registered classes work
- `require`, `use`, `import` — namespaces are pre-registered, cannot load new ones

## Rules

| Rule | Details |
|------|---------|
| Assertions | Exact string matching — NEVER substring unless explicitly `contains-text` |
| Roles | Require `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`) |
| Fixtures | Use `:context` hooks from `test-fixtures`, NEVER nest manually inside `it`/`deftest` blocks |
| Default fixture | Always use `with-traced-page` — enables tracing/HAR on every run for debugging |
| Error handling | All errors return anomaly maps `{:error :msg :data}` — check with `core/anomaly?` |
| Lifecycle | Use `with-*` macros (`with-playwright`, `with-browser`, `with-page`) — resources auto-cleaned |
| Screenshots | After visual/UI changes, ALWAYS take and display a screenshot as proof |

## Examples

Example 1: Write E2E tests
User says: "Test the login page at http://localhost:3000"
Actions:
1. @spel-orchestrator routes to test pipeline
2. @spel-test-planner explores the login page, writes test plan (with self-challenge)
3. @spel-test-writer generates Clojure E2E tests with assertions and self-heals failures
Result: Working E2E test suite in `test-e2e/` with Allure reporting

Example 2: Find bugs on a live site
User says: "Find bugs on https://example.com"
Actions:
1. @spel-orchestrator routes to bug-finding pipeline
2. @spel-explorer maps the site, captures snapshots
3. @spel-bug-hunter tests for functional, visual, and UX bugs, self-challenges each finding, and delivers final verdicts
Result: HTML + Markdown bug reports with evidence screenshots

Example 3: Automate a browser workflow
User says: "Automate filling out the registration form"
Actions:
1. @spel-orchestrator runs embedded automation pipeline
2. @spel-explorer maps the form fields and page structure
3. @spel-automator writes a reusable eval-sci script
Result: Reusable `.clj` automation script with JSON output

Example 4: Take a screenshot and explore
User says: "Open https://example.com and take a screenshot"
Actions:
1. `spel open https://example.com`
2. `spel wait --load load`
3. `spel screenshot example.png`
Result: Screenshot saved to `example.png`

## Troubleshooting

### Click times out on SPA / heavy pages
Cause: Default `load` wait hangs on SPAs that never fully "load" (ad trackers, analytics).
Solution: Use `spel wait --load domcontentloaded` after clicks. If the click itself times out, try `spel wait --url <partial>` to wait for the route change instead. NEVER skip user actions by navigating directly — always click like a human would.

### Session conflict / stale daemon
Cause: Previous session was not closed, or daemon socket is stale.
Solution: Close with `spel --session $SESSION close`. If that fails, run `spel session list` and kill stale sessions. As a last resort, remove stale socket files.

### Snapshot refs not found after navigation
Cause: Page content changed after navigation; old refs are invalid.
Solution: ALWAYS re-run `spel snapshot -i` after any navigation or page state change. Never reuse refs from a previous snapshot.

For more troubleshooting, see `references/COMMON_PROBLEMS.md`.

## Performance notes

- Take your time to verify selectors and page state thoroughly before writing assertions.
- Quality is more important than speed — flaky tests cost more than slow generation.
- Do not skip validation steps (snapshot verification, gate approvals).
- Prefer fewer, well-verified assertions over many untested ones.

## Reference documentation

Start with `references/START_HERE.md` for the shortest orientation path and `references/CAPABILITIES.md` for the compact capability map.

### Core API & patterns
| Ref | Topic |
|-----|-------|
| `references/FULL_API.md` | Complete API tables — auto-generated library API, SCI eval API, CLI commands |
| `references/PAGE_LOCATORS.md` | Page locators, selectors, get-by-* methods |
| `references/NAVIGATION_WAIT.md` | Navigation, waiting, load states |
| `references/SELECTORS_SNAPSHOTS.md` | CSS/XPath selectors, accessibility snapshots |
| `references/EVAL_GUIDE.md` | SCI eval mode guide, `eval-sci` patterns |
| `references/CONSTANTS.md` | Constants, enums, AriaRole values |
| `references/SEARCH_API.md` | Google Search CLI, SCI, and library API |

### Browser & network
| Ref | Topic |
|-----|-------|
| `references/BROWSER_OPTIONS.md` | Browser launch/context options, presets, lifecycle macros, device emulation |
| `references/NETWORK_ROUTING.md` | Page routing, request/response inspection, WebSocket |
| `references/FRAMES_INPUT.md` | Frame navigation, keyboard/mouse/touchscreen input |

### Testing & assertions
| Ref | Topic |
|-----|-------|
| `references/TESTING_CONVENTIONS.md` | Test framework conventions, fixtures, running tests (flavour-specific) |
| `references/ASSERTIONS_EVENTS.md` | Playwright assertions, events/signals, file input |
| `references/SNAPSHOT_TESTING.md` | Snapshot testing patterns |
| `references/API_TESTING.md` | API testing context, HTTP methods, hooks, retry |

### Reporting & CI
| Ref | Topic |
|-----|-------|
| `references/ALLURE_REPORTING.md` | Allure labels, steps, attachments, reporter config, trace viewer |
| `references/CI_WORKFLOWS.md` | GitHub Actions CI/CD workflows |

### Presenter & visual output
| Ref | Topic |
|-----|-------|
| `references/CSS_PATTERNS.md` | **Canonical design system** — fonts, colors, dark mode, card/table/KPI components (REQUIRED for all visual output) |
| `references/PRESENTER_SKILL.md` | Presenter workflow, content types, design token contract, quality checks |
| `references/SLIDE_PATTERNS.md` | Slide engine, scroll-snap slides, transitions, navigation chrome, presets |
| `references/LIBRARIES.md` | External libraries — Mermaid.js theming, Chart.js, anime.js, Google Fonts pairings |

### QA / exploratory testing
| Ref | Topic |
|-----|-------|
| `references/BUGFIND_GUIDE.md` | Adversarial bug-finding pipeline, scoring, schemas, Jobs Filter |
| `references/VISUAL_QA_GUIDE.md` | Visual regression methodology, baseline/diff workflow |
| `references/spel-report.html` | Unified report HTML template — QA + product discovery |
| `references/spel-report.md` | Unified report Markdown template — LLM-friendly QA + product discovery handoff |
| `references/PRODUCT_DISCOVERY.md` | Product discovery JSON schemas — product-spec.json, product-faq.json field definitions |

### CLI & tools
| Ref | Topic |
|-----|-------|
| `references/CODEGEN_CLI.md` | Codegen record/transform, CLI commands, page exploration, configuration |
| `references/PDF_STITCH_VIDEO.md` | PDF generation, image stitching, video recording |
| `references/PROFILES_AGENTS.md` | Browser profiles, stealth mode, CDP auto-connect, storage state, agent scaffolding |

### Agent shared patterns
| Ref | Topic |
|-----|-------|
| `references/AGENT_COMMON.md` | Shared agent patterns — session management, GATE contracts, error recovery |
| `references/ENVIRONMENT_VARIABLES.md` | All spel environment variables — browser, session, network, SSL, testing, advanced |

### Troubleshooting
| Ref | Topic |
|-----|-------|
| `references/COMMON_PROBLEMS.md` | Common issues, debugging tips, error patterns |
