---
name: spel
description: "Clojure Playwright 1.61.0 wrapper. Browser automation, testing, assertions, codegen, CLI. Use for: E2E tests, bug-finding, checkout automation, site exploration, screenshots, scraping, visual regression. NOT for: general web dev, non-browser APIs, non-Playwright frameworks."
version: "{{version}}"
license: Apache-2.0
compatibility: opencode
---

# spel — Clojure Playwright wrapper

Skill generated for spel **{{version}}**. Verify with `spel version`.

Use the `spel` CLI directly or via `eval-sci` for scripted workflows.

## CLI commands (obvious form)

```
spel --help                         # global help (always available)
spel <cmd> --help                   # help per subcommand
```

| Command | Purpose |
|---------|---------|
| `spel open <url>` | Open URL (stealth ON by default) |
| `spel --auto-launch open <url>` | Launch isolated browser with CDP debug port |
| `spel --auto-connect open <url>` | Auto-discover running Chromium-family browser via CDP |
| `spel --profile <path> open <url>` | Persistent Chrome profile |
| `spel --channel msedge --profile <p> open <url>` | Edge profile |
| `spel --load-state auth.json open <url>` | Restore cookies/localStorage |
| `spel snapshot -i` | Interactive-elements snapshot with `@eXXX` refs + `[pos:X,Y W×H]` |
| `spel snapshot -i -c` | Compact interactive (drops bare role lines) |
| `spel click @eXXX` | Click by ref |
| `spel fill @eXXX "text"` | Fill input by ref |
| `spel screenshot name.png` | Screenshot |
| `spel screenshot -a` | Annotated full-page PNG + sorted `@ref role "name"` list |
| `spel annotate` / `spel unannotate` | Inject/remove visual overlays |
| `spel batch [--bail] [--json]` | Run JSON array of sub-commands from stdin (one warm session) |
| `spel wait --text "..."` | Wait for text |
| `spel wait --load load\|domcontentloaded` | Wait for load state |
| `spel wait --url <partial>` | Wait for URL match |
| `spel close` | Close session |
| `spel search "query" [--json\|--images\|--news\|--limit N\|--open N]` | Google search |
| `spel state save/load [path]` | Persist/restore browser state |
| `spel codegen record -o rec.jsonl <url>` | Record session |
| `spel stitch a.png b.png -o out.png` | Stitch vertically |
| `spel report [flags]` | **Generate alt HTML report** — see Reporting below |
| `spel merge-reports <dirs...>` | Merge multiple `allure-results/` dirs |
| `spel ci-assemble` | CI artifact assembly |
| `spel bridge` | **CDP-free in-page automation** — serve/eject `spel.js`, route commands through a loopback bridge (see Bridge below) |
| `spel --provider ios --bundle-id <id> snapshot` | **Native iOS app + hybrid WKWebView automation** (see iOS provider below) |
| `spel click @eXXX` / `spel click <x> <y>` | Native XCTest click or coordinate touch (iOS provider) |
| `spel scroll up\|down\|left\|right [px]` | Native touch scrolling (iOS provider) |
| `spel device list` | List Playwright device emulation presets |
| `(spel/ios-devices)` / `(spel/ios-doctor)` | Discover iOS Simulators / check iOS prerequisites through SCI |

### Reporting — `spel report`

Generates a self-contained HTML report (`index.html` + `summary.json` + `report.json` + `data/`) from Allure results.

```bash
# Standard mode: read allure-results/ directory
spel report --results-dir allure-results --output-dir alternative-report

# Single-run / lambda mode: read JSON file of result maps
spel report --from-json results.json --output-dir my-report --title "Lambda Run"
```

Common flags: `--title`, `--kicker`, `--subtitle`, `--logo`, `--description`,
`--custom-css[-file]`, `--build-id`, `--build-date`, `--build-url`.
`--from-json` takes precedence over `--results-dir`. See
`references/ALLURE_REPORTING.md` for full option list and label-filtering UI.

## SCI (`eval-sci`) vs library

Same fn names; SCI manages page/context implicitly.

```clojure
;; Library (JVM): explicit args
(page/navigate pg url) (page/locator pg "#login") (locator/click (page/locator pg "#login"))

;; SCI: implicit page via daemon session
(spel/navigate url) (spel/locator "#login") (spel/click "#login")

;; Page-level keyboard press (no selector)
(spel/press "Escape") (spel/press "Control+a") (spel/keyboard-press "Enter")

;; Locator-level press
(spel/press "#my-input" "Enter")
```

Daemon running → `eval-sci` reuses the open browser. No `spel/start!` / `spel/stop!`.

### SCI sandbox — what's available

- All spel namespaces: `spel/`, `snapshot/`, `annotate/`, `stitch/`, `search/`,
  `input/`, `frame/`, `net/`, `loc/`, `assert/`, `core/`, `role/`, `markdown/`
- Clojure stdlib: `core`, `string`, `set`, `walk`, `edn`, `repl`, `template`
- IO: `clojure.java.io` (aliased `io`), `slurp`, `spit`,
  `java.io.File`, `java.nio.file.{Files,Path,Paths}`, `java.util.Base64`
- Playwright Java classes + enums (`Page`, `Browser`, `AriaRole`, …)
- `iteration` (lazy pagination)

**Not available**: arbitrary Java class construction, `require`/`use`/`import`.

## Navigation rules

- **Simulate user actions.** Click links/buttons; never `spel open <url>` to skip steps.
- Split load: `spel open <url>` then `spel wait --load …` separately.
- Traditional sites: `wait --load load`. SPA/heavy/ad-laden: `wait --load domcontentloaded` or `wait --url <partial>`.
- Longer click timeouts = last resort.
- After navigation, **re-snapshot**. Never reuse old refs.

## CLI safety (opt-in flags)

| Flag | Purpose | Env |
|------|---------|-----|
| `--content-boundaries` | Wrap stdout in `<untrusted-content>…</untrusted-content>` | `SPEL_CONTENT_BOUNDARIES` |
| `--max-output N` | Truncate stdout to N chars | `SPEL_MAX_OUTPUT` |
| `--allowed-domains LIST` | Domain allowlist (supports `*.example.com`) for nav + sub-resources | `SPEL_ALLOWED_DOMAINS` |

```bash
spel --content-boundaries --max-output 50000 \
     --allowed-domains "example.com,*.example.com" \
     open https://example.com
```

Blocked nav → anomaly `blockedbyclient`. stderr never wrapped/truncated.

## Bridge — CDP-free in-page automation

When CDP is disabled (locked-down/corporate boxes), drive a real tab by
embedding a pure-JS engine that talks to spel over a **loopback** server — no
DevTools Protocol, no extension, no bundler.

```bash
spel bridge                       # serve spel.js + SSE/POST transport on 127.0.0.1:8787
spel bridge use                   # route regular `spel <verb>` through the bridge (saved in ~/.spel/bridge.json)
spel bridge off | status          # stop routing / inspect the saved target + tab reachability
spel bridge --eject [-o f]        # unpack the embedded spel.js (ships inside the native image)
spel bridge --eject --bookmarklet # draggable javascript: loader (--console = paste into DevTools)
```

Embed (load **first** in `<head>` for full network capture):
```html
<script src="http://127.0.0.1:8787/spel.js"></script>
<script>window.__spel.connect({url:"http://127.0.0.1:8787/spel"})</script>
```

Installs `window.__spel` with one `invoke(command)` covering ~100 verbs
(click/fill/type/press, snapshot `@eXXX` refs, ARIA, checks, overflow, geometry,
in-page **network capture** + same-origin `route` mocking, dialogs,
console/error capture, cookies, `goto`, upload/tap/dispatch_event, same-origin
`frame=` drill-down, storage, the `wait_for*` family). Overlay element
picker: **Ctrl+Shift+L**; choose server: **Ctrl+Shift+K**. Limits (no CDP): no
protocol/cross-origin interception (`route` is same-origin fetch/XHR only), no
cross-origin frames, no OS-level tabs/downloads/trusted
input, no traffic before the script loads. Full detail: `references/BRIDGE.md`.

## iOS provider — native applications and hybrid WKWebViews

`--provider ios` binds Appium/XCUITest to an installed application by bundle
identifier, or installs a simulator-built `.app`. The normal outer context is
`NATIVE_APP`, where compact XCTest snapshots provide clickable `@refs`.
macOS + Xcode + Appium are required.

```bash
spel --provider ios --bundle-id com.example.app snapshot -i
spel click @e1a2b3                                # native XCTest ref
spel click 'accessibility-id=Sign in'             # id=/role=/xpath=/predicate= too
spel get text @e1a2b3 && spel get count 'role=button'
spel wait 'role=button' --timeout 10000
spel click 200 400 && spel scroll down 400
```

Provider setup and orchestration are SCI-first:

```clojure
(spel/ios-doctor)
(spel/ios-devices)

(spel/with-webview-context
  {:title (spel/title)
   :url (spel/url)
   :button-count (spel/count-of "button")
   :metadata (spel/evaluate "window.checkoutMetadata")})

(spel/with-webview-context
  {:timeout-ms 15000
   :context "WEBVIEW_com.example.app"}
  {:title (spel/title)
   :metadata (spel/evaluate "window.checkoutMetadata")})
;; The exact prior context is restored after success or failure.
```

The body is evaluated only after an inspectable WebView becomes active, so
JavaScript and DOM operations run inside the temporary scope. Its final value
is returned unchanged. WKWebView DOM access requires `isInspectable = true`
on iOS 16.4+; native XCTest automation remains available otherwise. Do not manually switch the
session's active context.

Target selection: `--bundle-id com.example.app` for an installed app or
`--app build/My.app` to install a Simulator build. Native selectors:
`accessibility-id=`, `id=`, `role=`, `xpath=`, `class-chain=`, `predicate=`;
an unprefixed native selector is an accessibility id. Device selection uses
`--device "iPhone 16 Pro"`, `--udid <UDID>`, or `--platform-version 18.2`;
`--appium-url <url>` reuses external Appium. SCI exposes read-only context
diagnostics plus application lifecycle, installation, deep-link, permission,
and keyboard functions. Native snapshots, queries, waits, `spel/click`, and
`spel/scroll` reuse the normal SCI/CLI APIs. Playwright-only CDP, tracing/HAR,
network mocking, frames, tabs, emulation, and `--allowed-domains` remain
unsupported.

## Rules

| Rule | Detail |
|------|--------|
| Assertions | Exact match by default; `contains-text` only when justified |
| Roles | `[com.blockether.spel.roles :as role]` → `role/button`, `role/heading` |
| Fixtures | `core/with-testing-page` / `with-testing-api` — never nest in `it`/`deftest` |
| Errors | Anomaly maps `{:error :msg :data}` — check with `core/anomaly?` |
| Screenshots | Visual/UI change → take + display screenshot as proof |

## Examples

1. **E2E tests** — "Test login at http://localhost:3000" → explore live app → generate test file → run → Allure report.
2. **Bug finding** — "Find bugs on https://example.com" → open → inspect → capture evidence → report.
3. **Automation** — "Automate registration form" → explore → write reusable `.clj` script.
4. **One-shot screenshot** — `spel open <url> && spel wait --load load && spel screenshot out.png`.
5. **Visual + refs in one call** — `spel screenshot -a` → PNG with labels + `@ref role "name"` list in reading order.
6. **Deterministic multi-step** —
   ```bash
   echo '[["open","https://example.com"],["wait","--load","load"],["screenshot","-a","shot.png"]]' \
     | spel batch --json --bail
   ```

## Troubleshooting

- **Click times out on SPA** → `spel wait --load domcontentloaded` after clicks; or `--url <partial>`. Never skip user actions.
- **Session conflict / stale daemon** → `spel --session $SESSION close`; then `spel session list`; remove stale socket as last resort.
- **Snapshot refs missing after nav** → ALWAYS `spel snapshot -i` after any navigation or state change.

More: `references/COMMON_PROBLEMS.md`.

## Reference docs

Start with `references/START_HERE.md` + `references/CAPABILITIES.md`.

| Topic | Ref |
|-------|-----|
| Complete API tables | `FULL_API.md` |
| Page/locators/get-by-* | `PAGE_LOCATORS.md` |
| Navigation + wait | `NAVIGATION_WAIT.md` |
| CSS/XPath + snapshots | `SELECTORS_SNAPSHOTS.md` |
| SCI eval patterns | `EVAL_GUIDE.md` |
| Constants/enums/AriaRole | `CONSTANTS.md` |
| Google search API | `SEARCH_API.md` |
| Browser options/devices | `BROWSER_OPTIONS.md` |
| Network routing/mocking | `NETWORK_ROUTING.md` |
| Frames + keyboard/mouse | `FRAMES_INPUT.md` |
| Test conventions (flavour) | `TESTING_CONVENTIONS.md` |
| Assertions + events | `ASSERTIONS_EVENTS.md` |
| API testing | `API_TESTING.md` |
| **Allure reporting + `spel report`** | `ALLURE_REPORTING.md` |
| CI workflows | `CI_WORKFLOWS.md` |
| Design system (REQUIRED for visuals) | `CSS_PATTERNS.md` |
| Presenter workflow | `PRESENTER_SKILL.md` |
| Slide engine | `SLIDE_PATTERNS.md` |
| External libs (Mermaid, Chart.js, …) | `LIBRARIES.md` |
| Unified report template (HTML) | `spel-report.html` |
| Unified report template (MD) | `spel-report.md` |
| Codegen record/transform | `CODEGEN_CLI.md` |
| **Bridge — CDP-free in-page automation** | `BRIDGE.md` |
| PDF / stitch / video | `PDF_STITCH_VIDEO.md` |
| Env vars | `ENVIRONMENT_VARIABLES.md` |
| Common problems | `COMMON_PROBLEMS.md` |
