---
name: spel
description: "Clojure Playwright 1.58.0 wrapper. Browser automation, testing, assertions, codegen, CLI. Use for: E2E tests, bug-finding, checkout automation, site exploration, screenshots, scraping, visual regression. NOT for: general web dev, non-browser APIs, non-Playwright frameworks."
version: "{{version}}"
license: Apache-2.0
compatibility: opencode
---

# spel — Clojure Playwright wrapper

Skill generated for spel **{{version}}**. Verify with `spel version`. Mismatch → run `spel init-agents` to reinitialize.

## Entry point: `@spel-orchestrator`

**Never call specialists directly.** Orchestrator routes + enforces gates/artifacts:

- Tests: `@spel-test-writer` (explores → generates → self-heals in one pass)
- Bugs:  `@spel-bug-hunter` (explore → hunt → self-challenge → HTML + MD report)
- Automation: `@spel-explorer` → `@spel-automator` → `@spel-presenter`
- Discovery: `@spel-product-analyst`

Falls back to plain `spel` CLI + `eval-sci` when specialists unavailable.

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
| `spel init-agents [--loop=opencode\|claude]` | Scaffold agents (vscode DEPRECATED — errors) |
| `spel report [flags]` | **Generate alt HTML report** — see Reporting below |
| `spel merge-reports <dirs...>` | Merge multiple `allure-results/` dirs |
| `spel ci-assemble` | CI artifact assembly |

### Reporting — `spel report`

Generates a self-contained HTML report (`index.html` + `summary.json` + `report.json` + `data/`) from Allure results.

```bash
# Standard mode: read allure-results/ directory
spel report --results-dir allure-results --output-dir block-report

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

## Agent safety (opt-in flags)

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

## Rules

| Rule | Detail |
|------|--------|
| Assertions | Exact match by default; `contains-text` only when justified |
| Roles | `[com.blockether.spel.roles :as role]` → `role/button`, `role/heading` |
| Fixtures | `core/with-testing-page` / `with-testing-api` — never nest in `it`/`deftest` |
| Errors | Anomaly maps `{:error :msg :data}` — check with `core/anomaly?` |
| Screenshots | Visual/UI change → take + display screenshot as proof |

## Examples

1. **E2E tests** — "Test login at http://localhost:3000" → orchestrator → test-writer (explore → generate → heal) → Allure report.
2. **Bug audit** — "Find bugs on https://example.com" → orchestrator → explorer → bug-hunter → qa-report.{html,md}.
3. **Automation** — "Automate registration form" → orchestrator → explorer → automator → reusable `.clj` script.
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
| Snapshot testing | `SNAPSHOT_TESTING.md` |
| API testing | `API_TESTING.md` |
| **Allure reporting + `spel report`** | `ALLURE_REPORTING.md` |
| CI workflows | `CI_WORKFLOWS.md` |
| Design system (REQUIRED for visuals) | `CSS_PATTERNS.md` |
| Presenter workflow | `PRESENTER_SKILL.md` |
| Slide engine | `SLIDE_PATTERNS.md` |
| External libs (Mermaid, Chart.js, …) | `LIBRARIES.md` |
| Bug-finding pipeline + schemas | `BUGFIND_GUIDE.md` |
| Visual regression methodology | `VISUAL_QA_GUIDE.md` |
| Unified report template (HTML) | `spel-report.html` |
| Unified report template (MD) | `spel-report.md` |
| Product discovery schemas | `PRODUCT_DISCOVERY.md` |
| Codegen record/transform | `CODEGEN_CLI.md` |
| PDF / stitch / video | `PDF_STITCH_VIDEO.md` |
| Profiles, stealth, CDP | `PROFILES_AGENTS.md` |
| Shared agent patterns | `AGENT_COMMON.md` |
| Env vars | `ENVIRONMENT_VARIABLES.md` |
| Common problems | `COMMON_PROBLEMS.md` |
