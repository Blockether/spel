# Browser profiles, device emulation, and agent initialization

## Browser profiles

Persistent profiles keep login sessions, cookies, and localStorage across runs. Log in once, reuse that session forever.

The profile path points to a directory. Chromium creates it automatically if it doesn't exist. Everything the browser stores (cookies, localStorage, IndexedDB, service workers) lives there.

### `eval-sci` / CLI daemon mode

Use the CLI `--profile` flag to launch with a persistent profile:

```bash
# First run: log in via script (--interactive opens visible browser)
spel --profile /tmp/my-chrome-profile --interactive eval-sci '
(spel/navigate "https://myapp.com/login")
(spel/fill "#email" "me@example.org")
(spel/fill "#password" "secret123")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(println "Logged in! Session saved to profile.")'
```

```bash
# Second run: session is already there
spel --profile /tmp/my-chrome-profile eval-sci '
(spel/navigate "https://myapp.com/dashboard")
(spel/wait-for-load-state)
(println "Title:" (spel/title))'
```

> Note: `:profile` is NOT a valid option for `spel/start!`. Use the CLI `--profile` flag (shown above) or `core/launch-persistent-context` in library mode.

### Library mode

```clojure
;; with-testing-page accepts :profile directly
(core/with-testing-page {:profile "/tmp/my-profile"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title pg))
```

For lower-level control, use `core/launch-persistent-context` on the browser type directly.

### When to use profiles

- Authenticated automation: Log in once, run scripts against protected pages
- Bypassing bot detection: Reusing a real profile looks less suspicious than a fresh browser
- Development workflows: Keep dev tools settings, extensions, and preferences between runs

Caveat: Don't share a profile directory between concurrent processes. Chromium locks it.

---

## Profile vs load-state: when to use which

spel supports two auth approaches:

| | `--profile` (persistent context) | `--load-state` (portable JSON) |
|---|---|---|
| How it works | Launches browser with a user data directory via Playwright `launchPersistentContext` | Loads cookies + localStorage JSON into fresh context |
| Auth persists | Yes, automatically (in the profile dir) | Snapshot at save time — re-save to refresh |
| Concurrent use | No (Chromium locks the dir) | Yes (read-only JSON, any number of browsers) |
| Best for | Local automation, dev workflows, interactive sessions | CI pipelines, cross-platform, parallel runs |

### Quick decision

- Working locally on your machine? Use `--profile`
- Need concurrent browser sessions with same auth? Use `--load-state` (profiles lock)
- Running in CI? Use `--load-state` with a saved storage-state JSON

### Edge / other Chromium browsers

Use `--channel` to target non-default Chromium browsers:

```bash
# Persistent Edge profile
spel --channel msedge --profile ~/.config/microsoft-edge/Default open https://example.com

# Use exported state in any browser
spel --load-state auth.json open https://example.com
```

### Browser profile paths

| OS | Chrome Default | Edge Default |
|----|----------------|--------------|
| macOS | `~/Library/Application Support/Google/Chrome/Default` | `~/Library/Application Support/Microsoft Edge/Default` |
| Linux | `~/.config/google-chrome/Default` | `~/.config/microsoft-edge/Default` |
| Windows | `%LOCALAPPDATA%\Google\Chrome\User Data\Default` | `%LOCALAPPDATA%\Microsoft\Edge\User Data\Default` |

Profiles are numbered: `Default`, `Profile 1`, `Profile 2`, etc. Check `chrome://version` or `edge://version` to find the exact path.

---

## Daemon launch modes

The daemon has two launch modes:

| Mode | Trigger | What Happens | Use Case |
|------|---------|-------------|----------|
| Mode 1: persistent profile | `--profile <dir>` | Uses Playwright `launchPersistentContext` on the directory | Local automation with session persistence |
| Mode 2: normal / CDP | No `--profile` | Standard `launch` + `new-context`, or `--cdp` / `--auto-connect` for CDP | One-off automation, CI, connecting to existing Chrome |

### Mode 1 details (persistent profile)

Playwright creates/manages browser data in the given directory:

- Session data persists between runs (cookies, localStorage, IndexedDB)
- Session isolation per directory — don't share between concurrent processes
- Supports `--channel` for Edge, Chrome Canary, etc.

### Mode 2 details (normal / CDP)

Normal: Standard Playwright launch — fresh context every time. Use `--load-state` to inject pre-saved cookies.

CDP Connect (`--cdp <url>` or `--auto-connect`): Connects to an already-running Chrome via Chrome DevTools Protocol. Reuses the browser's existing contexts, pages, and sessions.

All modes support stealth (on by default), `--channel`, and `--interactive`.

---

## CDP auto-connect

Connect to a running Chrome or Edge instance via Chrome DevTools Protocol (CDP). This lets spel control your actual browser with its real login sessions, cookies, and tabs.

### Setup (Chrome/Edge 136+ security change)

Chrome/Edge 136+ (April 2025) intentionally ignores `--remote-debugging-port` when targeting the default user data directory. This is a security change, not a bug.

Two ways to enable CDP:

#### Option 1: launch browser with debug port + custom user-data-dir

```bash
SESSION="agent-$(date +%s)"
CDP_PORT=$(spel find-free-port)

# macOS
open -na "Google Chrome" --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
# or Edge:
open -na "Microsoft Edge" --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run

# Linux
google-chrome --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
# or Edge:
microsoft-edge --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
```

Then connect:
```bash
spel --session $SESSION --auto-connect open https://example.com
# or explicitly:
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open https://example.com
```

#### Option 2: enable in running browser (M144+)

1. Open `chrome://inspect/#remote-debugging` in Chrome or `edge://inspect/#remote-debugging` in Edge
2. Toggle remote debugging ON
3. Browser creates a `DevToolsActivePort` file automatically

Then connect:
```bash
spel --auto-connect open https://example.com
```

### How auto-connect discovery works

1. Checks `DevToolsActivePort` files in browser data directories:
   - macOS: `~/Library/Application Support/Google/Chrome/`, `Chrome Canary/`, `Microsoft Edge/`, `Microsoft Edge Canary/`, `Chromium/`
   - Linux: `~/.config/google-chrome/`, `microsoft-edge/`, `chromium/`, `google-chrome-unstable/`, `microsoft-edge-dev/`
2. Checks `ms-playwright` cache dirs (finds Chrome launched by `chrome-devtools-mcp` etc.)
3. Probes common ports: 9222, 9229 via HTTP `GET /json/version`
4. For Chrome/Edge 144+ WebSocket-only mode: falls back to direct WebSocket connection

### Flag persistence

After the first successful `--auto-connect`, the discovered CDP URL is persisted to a session flags file. Subsequent commands reuse it automatically:

```bash
spel --auto-connect open https://example.com   # discovers CDP, persists URL
spel snapshot                                    # reuses persisted CDP URL
spel click @eXXXX                                # still connected
```

### Environment variables

| Variable | Purpose |
|----------|---------|
| `SPEL_CDP` | CDP endpoint URL (same as `--cdp`) |
| `SPEL_AUTO_CONNECT` | Enable auto-connect (any value, same as `--auto-connect`) |

### Limitations

- CDP is Chromium-only. Firefox and WebKit don't support it.
- Chrome/Edge must be launched with `--user-data-dir` pointing to a non-default directory (136+ security requirement).
- If the browser is already running, you cannot add `--remote-debugging-port` retroactively — use `chrome://inspect/#remote-debugging` (or `edge://inspect/#remote-debugging`) instead (M144+).
- Reuse one named session per stage (`--session <name>`) and keep one endpoint owner; avoid attaching multiple sessions to the same CDP endpoint concurrently.
- The `--user-data-dir` browser instance has a fresh profile unless you point it to an existing one.
---

## Stealth mode

Stealth mode is ON by default for all CLI and `eval-sci` commands. Anti-detection patches hide Playwright's automation signals from bot-detection systems (Cloudflare, DataDome, PerimeterX, etc.). Based on [puppeteer-extra-plugin-stealth](https://github.com/AhmedIbrahim336/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth). Use `--no-stealth` to disable.

### CLI

```bash
# Stealth is automatic — no flag needed
spel open https://example.org
spel eval-sci 'script.clj'
spel --profile /path/to/profile open https://protected-site.com

# Combine with other flags
spel --channel chrome --profile ~/.config/google-chrome/Profile\ 1 open https://x.com

# Disable stealth if needed
spel --no-stealth open https://example.org

# Environment variable to disable stealth
export SPEL_STEALTH=false
spel open https://example.org
```

### What stealth does

Chrome launch args:
- `--disable-blink-features=AutomationControlled` — prevents `navigator.webdriver=true`
- Suppresses `--enable-automation` — removes "Chrome is being controlled" infobar

JavaScript evasion patches (injected via `addInitScript` before any page loads):

| Patch | What it hides |
|-------|---------------|
| `navigator.webdriver` | Returns `undefined` instead of `true` |
| `navigator.plugins` | Emulates Chrome PDF plugins (empty in headless) |
| `navigator.languages` | Returns `['en-US', 'en']` |
| `chrome.runtime` | Mocks `connect()` and `sendMessage()` |
| `permissions.query` | Fixes `Notification.permission` response |
| `WebGL renderer` | Returns realistic GPU vendor/renderer strings |
| `outerWidth/Height` | Matches inner dimensions (headless mismatch) |
| `iframe contentWindow` | Prevents iframe-based fingerprinting |

### Stealth + load-state workflow

For maximum authenticity — combine stealth with saved browser state:

```bash
# Use saved state (stealth is already on by default)
spel --load-state auth.json open https://protected-site.com
```

### Library API

```clojure
(require '[com.blockether.spel.stealth :as stealth])

;; Get Chrome args for anti-detection
(stealth/stealth-args)
;; => ["--disable-blink-features=AutomationControlled"]

;; Get default args to suppress
(stealth/stealth-ignore-default-args)
;; => ["--enable-automation"]

;; Get the full JS evasion script (for addInitScript)
(stealth/stealth-init-script)
;; => "(function() { ... })();"

;; Manual integration with Playwright
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw
                                {:args (stealth/stealth-args)
                                 :ignore-default-args (stealth/stealth-ignore-default-args)})]
    (core/with-context [ctx (core/new-context browser)]
      (.addInitScript ctx (stealth/stealth-init-script))
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.org")))))
```

### Limitations

- Stealth patches help with common detection but are not foolproof against sophisticated fingerprinting (e.g., TLS fingerprint, HTTP/2 settings, canvas noise)
- Some sites (banks, Google login) may still detect automation regardless
- Headed mode (`--interactive`) combined with stealth (which is on by default) gives the best results
- Works with all launch modes: normal, persistent profile, and CDP connect

---

---
---

## Device emulation

Three approaches, each with different fidelity.

### Approach 1: viewport only

`spel/set-viewport-size!` changes width and height. No device pixel ratio, no mobile user agent, no touch support. Good enough for responsive CSS breakpoints.

```clojure
;; Daemon mode: just set viewport and go
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/mobile-view.png"})
```
### Approach 2: full device preset (CLI daemon)
The daemon's `set device` command configures viewport, DPR, user agent, and touch all at once.
```bash
spel open https://example.org
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: library / `eval-sci` options
Pass `:device` when creating the session. Sets viewport, DPR, user agent, touch, and mobile flag.

```clojure
;; Daemon: use CLI to set device on existing session
;; $ spel set device "iPhone 14"
;; Then eval-sci just navigates:
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})

;; Standalone eval-sci (no daemon): start! with device option
(spel/start! {:device :iphone-14})
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})
(spel/stop!)

;; Library
(core/with-testing-page {:device :pixel-7} [pg]
  (page/navigate pg "https://example.org")
  (page/screenshot pg {:path "/tmp/pixel7.png"}))
```
### Comparison

| Approach | Viewport | DPR | User Agent | Touch | Available in |
|---|---|---|---|---|---|
| `spel/set-viewport-size!` | yes | no | no | no | `eval-sci` |
| `spel set device "Name"` | yes | yes | yes | yes | CLI daemon |
| `{:device :name}` option | yes | yes | yes | yes | `eval-sci` + library |

### Device presets

Mobile: `:iphone-se` (375x667), `:iphone-12` (390x844), `:iphone-14` (390x844), `:iphone-14-pro` (393x852), `:iphone-15` (393x852), `:iphone-15-pro` (393x852), `:pixel-5` (393x851), `:pixel-7` (412x915), `:galaxy-s24` (360x780), `:galaxy-s9` (360x740).

Tablet: `:ipad` (810x1080), `:ipad-mini` (768x1024), `:ipad-pro-11` (834x1194), `:ipad-pro` (1024x1366).

Desktop: `:desktop-chrome` (1280x720), `:desktop-firefox` (1280x720), `:desktop-safari` (1280x720).

### Viewport presets

Use `:viewport` instead of `:device` when you just want dimensions without mobile emulation:

```clojure
;; Standalone eval-sci (no daemon)
(spel/start! {:viewport :desktop-hd})

;; Library
(core/with-testing-page {:viewport :tablet} [pg] ...)
(core/with-testing-page {:viewport {:width 1920 :height 1080}} [pg] ...)
```

Sizes: `:mobile` (375x667), `:mobile-lg` (428x926), `:tablet` (768x1024), `:tablet-lg` (1024x1366), `:desktop` (1280x720), `:desktop-hd` (1920x1080), `:desktop-4k` (3840x2160).

---

## Browser selection

```clojure
;; Daemon: start with a specific browser via CLI
;; $ spel start --browser firefox

;; Standalone eval-sci (no daemon): start! configures the browser
(spel/start! {:browser :chromium})   ;; default
(spel/start! {:browser :firefox})
(spel/start! {:browser :webkit})

;; Library
(core/with-testing-page {:browser-type :firefox} [pg]
  (page/navigate pg "https://example.org"))

;; Headed mode (visible browser window)
;; Daemon: spel open URL (already headed)
;; Standalone eval-sci:
(spel/start! {:headless false})
(spel/start! {:headless false :slow-mo 500})  ;; slow down for debugging
;; CLI equivalent: spel eval-sci --interactive '...'

;; Library headed mode
(core/with-testing-page {:headless false :slow-mo 300} [pg]
  (page/navigate pg "https://example.org"))
```

### Browser-specific notes

- PDF generation only works in Chromium headless. Firefox and WebKit don't support `page/pdf`.
- CDP (Chrome DevTools Protocol) is Chromium-only. `core/cdp-send` won't work with Firefox or WebKit.
- WebKit matches Safari's rendering engine. Good for cross-browser testing, but no CDP and limited video support.

---

## Storage state

Storage state captures cookies and localStorage as a JSON file. Lighter than a full profile, easy to share between test runs or CI jobs.

### Save and load

```clojure
;; Save after logging in (daemon mode)
(spel/navigate "https://myapp.com/login")
(spel/fill "#email" "me@example.org")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(spel/context-save-storage-state! "/tmp/auth-state.json")

;; Load in a later session
;; Daemon: spel start --load-state /tmp/auth-state.json
;; Standalone eval-sci:
(spel/start! {:storage-state "/tmp/auth-state.json"})
(spel/navigate "https://myapp.com/dashboard")
;; already authenticated
(spel/stop!)
```

Library:

```clojure
(core/with-testing-page {:storage-state "/tmp/auth-state.json"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title pg))
```

### Profiles vs storage state

| | Profile | Storage State |
|---|---|---|
| Persists | Everything (cookies, localStorage, IndexedDB, service workers, cache) | Cookies + localStorage only |
| Format | Directory (Chromium internal) | JSON file |
| Portable | No (tied to Chromium version) | Yes (plain JSON, works across machines) |
| Concurrent use | No (locked by Chromium) | Yes (read-only file) |
| Best for | Local dev, manual login reuse | CI pipelines, shared test fixtures |

---

## Agent initialization

`spel init-agents` scaffolds E2E test agents for AI coding tools. Agents work together: planner writes test plans with self-challenge, test-writer generates tests and self-heals failures.

### Quick start

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --loop=vscode                # VS Code / Copilot (DEPRECATED — exits with error)
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only, no test agents
```

### Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude` (`vscode` is deprecated) |
| `--ns NS` | directory name | Base namespace for generated tests |
| `--flavour FLAVOUR` | `lazytest` | Test framework: `lazytest` or `clojure-test` |
| `--no-tests` | off | Only scaffold the SKILL (API reference), skip test agents |
| `--dry-run` | off | Preview files without writing |
| `--force` | off | Overwrite existing files |
| `--test-dir DIR` | `test-e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory |

### Generated files

| File | Purpose |
|------|---------|
| `agents/spel-test-planner` | Explores the app with `spel` CLI and `eval-sci`. Catalogs pages/flows. Writes test plans to `specs/`. |
| `agents/spel-test-writer` | Reads plans from `specs/`. Generates Clojure test files. Verifies selectors, self-heals failures. |
| `prompts/spel-test-workflow` | Orchestrator prompt: plan, generate, heal cycle. |
| `skills/spel/SKILL.md` | API reference so agents know spel's functions and conventions. |
| `specs/README.md` | Test plans directory with instructions for the planner. |
| `<test-dir>/<ns>/e2e/seed_test.clj` | Seed test file with a working example to build from. |

With `--no-tests`, only the SKILL file is generated. Useful for interactive development where you want the API reference available to your AI assistant but don't need the full test pipeline.

### How the agents work together
1. Planner opens the target app with `spel`, takes snapshots, explores navigation flows, and writes markdown test plans.
2. Test-writer reads those plans, writes Clojure test files, runs them, and self-heals any failures.

The `spel-test-workflow` prompt chains both: plan first, generate and heal second.

### File locations by target

| Target | Agents | Skills | Prompts |
|--------|--------|--------|---------|
| `opencode` | `.opencode/agents/` | `.opencode/skills/spel/` | `.opencode/prompts/` |
| `claude` | `.claude/agents/` | `.claude/docs/spel/` | `.claude/prompts/` |
| `vscode` | `.github/agents/` | `.github/docs/spel/` | `.github/prompts/` | ⚠️ DEPRECATED |
