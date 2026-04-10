# Browser profiles, device emulation, agent initialization

## Browser profiles

Persistent profiles keep login sessions, cookies, localStorage across runs. Log in once, reuse forever.

Profile path points to directory. Chromium creates it automatically if missing. Everything browser stores (cookies, localStorage, IndexedDB, service workers) lives there.

### `eval-sci` / CLI daemon mode

Use CLI `--profile` flag:

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
# Second run: session already there
spel --profile /tmp/my-chrome-profile eval-sci '
(spel/navigate "https://myapp.com/dashboard")
(spel/wait-for-load-state)
(println "Title:" (spel/title))'
```

> Note: `:profile` NOT valid option for `spel/start!`. Use CLI `--profile` flag (above) or `core/launch-persistent-context` in library mode.

### Library mode

```clojure
;; with-testing-page accepts :profile directly
(core/with-testing-page {:profile "/tmp/my-profile"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title pg))
```

Lower-level: use `core/launch-persistent-context` on browser type directly.

### When to use profiles

- Authenticated automation: log in once, run scripts against protected pages
- Bypassing bot detection: reusing real profile looks less suspicious than fresh browser
- Dev workflows: keep dev tools settings, extensions, preferences between runs

Caveat: don't share profile directory between concurrent processes. Chromium locks it.

---

## Profile vs load-state: when to use which

| | `--profile` (persistent context) | `--load-state` (portable JSON) |
|---|---|---|
| How it works | Launches browser with user data dir via Playwright `launchPersistentContext` | Loads cookies + localStorage JSON into fresh context |
| Auth persists | Yes, automatically (in profile dir) | Snapshot at save time — re-save to refresh |
| Concurrent use | No (Chromium locks dir) | Yes (read-only JSON, any number of browsers) |
| Best for | Local automation, dev workflows, interactive sessions | CI pipelines, cross-platform, parallel runs |

### Quick decision

- Working locally? Use `--profile`
- Need concurrent browser sessions with same auth? Use `--load-state` (profiles lock)
- Running in CI? Use `--load-state` with saved storage-state JSON

### Edge / other Chromium browsers

Use `--channel` for non-default Chromium browsers:

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

Profiles numbered: `Default`, `Profile 1`, `Profile 2`. Check `chrome://version` or `edge://version` for exact path.

---

## Daemon launch modes

Three launch modes:

| Mode | Trigger | What Happens | Use Case |
|------|---------|-------------|----------|
| Mode 1: persistent profile | `--profile <dir>` | Playwright `launchPersistentContext` on directory | Local automation with session persistence |
| Mode 2: auto-launch | `--auto-launch` | Launches browser with `--remote-debugging-port` on unique port, connects via CDP | Per-session isolated browser for AI agents |
| Mode 3: normal / CDP | No `--profile` or `--auto-launch` | Standard `launch` + `new-context`, or `--cdp` / `--auto-connect` for CDP | One-off automation, CI, connecting to existing Chrome |

### Mode 1 details (persistent profile)

Playwright creates/manages browser data in given directory:

- Session data persists between runs (cookies, localStorage, IndexedDB)
- Session isolation per directory — don't share between concurrent processes
- Supports `--channel` for Edge, Chrome Canary, etc.

### Mode 2 details (auto-launch)

Launches dedicated browser with `--remote-debugging-port` + temp `--user-data-dir`, connects via CDP. Each session gets own browser on unique port (9222, 9223, ...).

```bash
spel --auto-launch --session test1 open https://example.com
spel --auto-launch --channel msedge --session test2 open https://example.com
```

Key properties:
- **Per-session isolation**: own browser process on own port
- **User's browser untouched**: temp profile dir, never kills existing browsers
- **Auto-cleanup**: browser killed + temp dir deleted on `spel close`
- **Port allocation**: scans 9222-9321, lock files avoid cross-session collisions
- Trade-off: fresh profile → no existing auth cookies (use `--profile` for that)

### Mode 3 details (normal / CDP)

Normal: Standard Playwright launch — fresh context every time. Use `--load-state` to inject pre-saved cookies.

CDP Connect (`--cdp <url>` or `--auto-connect`): Connects to running Chrome via CDP. Reuses browser's existing contexts, pages, sessions.

All modes support stealth (on by default), `--channel`, `--interactive`.

### Daemon lifecycle & timeouts

Daemon auto-shuts down to free resources:

- **Session idle timeout** (default 30 min): No command → shutdown. Set `SPEL_SESSION_IDLE_TIMEOUT` (ms) to override, `0` disables. Runtime: `(spel/set-session-idle-timeout! ms)`.
- **CDP idle timeout** (default 30 min): After `cdp_disconnect`, no reconnect → shutdown. Set `SPEL_CDP_IDLE_TIMEOUT` (ms) to override, `0` disables.
- **CDP route lock wait** (default 120s): Another session holds lock → commands queue, poll every 2s. Set `SPEL_CDP_LOCK_WAIT` (seconds) + `SPEL_CDP_LOCK_POLL_INTERVAL` (seconds) to override.

---

## CDP auto-connect

Connect to running Chrome/Edge via CDP. spel controls your actual browser with its real login sessions, cookies, tabs.

> **Simpler alternative**: Don't need to connect to existing browser? Use `--auto-launch`. Handles launch, port allocation, CDP connection with per-session isolation. See [Mode 2](#mode-2-details-auto-launch).

### Setup (Chrome/Edge 136+ security change)

Chrome/Edge 136+ (April 2025) intentionally ignores `--remote-debugging-port` when targeting default user data directory. Security change, not bug.

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

1. Open `chrome://inspect/#remote-debugging` (Chrome) or `edge://inspect/#remote-debugging` (Edge)
2. Toggle remote debugging ON
3. Browser creates `DevToolsActivePort` file automatically

Then connect:
```bash
spel --auto-connect open https://example.com
```

### How auto-connect discovery works

1. Checks `DevToolsActivePort` files across every chromium-family user-data directory on the current OS:
   - **Chrome**: stable, Beta, Canary, Dev, For Testing
   - **Chromium**
   - **Microsoft Edge**: stable, Beta, Dev, Canary
   - **Brave**: stable, Beta, Nightly
   - **Vivaldi**: stable, Snapshot
   - **Opera**: stable, Beta, Developer
   - **Arc**, **Thorium**
   - **Linux snap** and **Flatpak** variants of Chromium / Chrome / Brave / Vivaldi
   - Standard locations:
     - macOS: `~/Library/Application Support/<vendor>/<product>/`
     - Linux: `~/.config/<product>/`, `~/snap/<product>/…`, `~/.var/app/<product>/…`
     - Windows: `%LOCALAPPDATA%\<vendor>\<product>\User Data\` (and `%APPDATA%\Opera Software\…` for Opera)
2. Checks `ms-playwright` cache dir (finds Chrome launched by `chrome-devtools-mcp`, `agent-browser`, etc.)
3. Probes common ports: `9222`, `9229` via HTTP `GET /json/version`
4. Chrome/Edge 144+ WebSocket-only mode: falls back to direct WebSocket connection using the `DevToolsActivePort` `ws-path`

### Flag persistence

After first successful `--auto-connect`, discovered CDP URL persisted to session flags file. Subsequent commands reuse automatically:

```bash
spel --auto-connect open https://example.com   # discovers CDP, persists URL
spel snapshot                                    # reuses persisted CDP URL
spel click @eXXXX                                # still connected
```

### Environment variables

| Variable | Purpose |
|----------|---------|
| `SPEL_CDP` | CDP endpoint URL (same as `--cdp`) |
| `SPEL_AUTO_CONNECT` | Enable auto-connect (any value = `--auto-connect`) |
| `SPEL_AUTO_LAUNCH` | Enable auto-launch (any value = `--auto-launch`) |

### Limitations

- CDP = Chromium-only. Firefox/WebKit don't support it.
- Chrome/Edge must launch with `--user-data-dir` pointing to non-default dir (136+ requirement).
- Already-running browser → can't add `--remote-debugging-port` retroactively. Use `chrome://inspect/#remote-debugging` (or `edge://inspect/#remote-debugging`) instead (M144+).
- Reuse one named session per stage (`--session <name>`) + one endpoint owner; avoid concurrent multi-session attach to same CDP endpoint.
- `--user-data-dir` browser instance has fresh profile unless pointed to existing one.
---

## Stealth mode

Stealth mode ON by default for all CLI + `eval-sci` commands. Anti-detection patches hide Playwright automation signals from bot-detection (Cloudflare, DataDome, PerimeterX, etc.). Based on [puppeteer-extra-plugin-stealth](https://github.com/AhmedIbrahim336/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth). `--no-stealth` to disable.

### CLI

```bash
# Stealth automatic — no flag needed
spel open https://example.org
spel eval-sci 'script.clj'
spel --profile /path/to/profile open https://protected-site.com

# Combine with other flags
spel --channel chrome --profile ~/.config/google-chrome/Profile\ 1 open https://x.com

# Disable stealth
spel --no-stealth open https://example.org

# Environment variable
export SPEL_STEALTH=false
spel open https://example.org
```

### What stealth does

Chrome launch args:
- `--disable-blink-features=AutomationControlled` — prevents `navigator.webdriver=true`
- Suppresses `--enable-automation` — removes "Chrome is being controlled" infobar

JavaScript evasion patches (injected via `addInitScript` before page loads):

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

Max authenticity — stealth + saved browser state:

```bash
# Saved state (stealth already on by default)
spel --load-state auth.json open https://protected-site.com
```

### Library API

```clojure
(require '[com.blockether.spel.stealth :as stealth])

;; Chrome args for anti-detection
(stealth/stealth-args)
;; => ["--disable-blink-features=AutomationControlled"]

;; Default args to suppress
(stealth/stealth-ignore-default-args)
;; => ["--enable-automation"]

;; Full JS evasion script (for addInitScript)
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

- Helps with common detection but not foolproof against sophisticated fingerprinting (TLS, HTTP/2, canvas noise)
- Some sites (banks, Google login) may still detect automation
- Headed mode (`--interactive`) + stealth (on by default) = best results
- Works with all launch modes: normal, persistent profile, CDP connect

---

---

## Device emulation

Three approaches, different fidelity.

### Approach 1: viewport only

`spel/set-viewport-size!` changes width + height. No DPR, no mobile user agent, no touch. Good for responsive CSS breakpoints.

```clojure
;; Daemon mode: set viewport + go
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/mobile-view.png"})
```
### Approach 2: full device preset (CLI daemon)
Daemon's `set device` configures viewport, DPR, user agent, touch at once.
```bash
spel open https://example.org
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: library / `eval-sci` options
Pass `:device` when creating session. Sets viewport, DPR, user agent, touch, mobile flag.

```clojure
;; Daemon: use CLI to set device on existing session
;; $ spel set device "iPhone 14"
;; Then eval-sci navigates:
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

Use `:viewport` instead of `:device` for dimensions without mobile emulation:

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
;; Daemon: start with specific browser via CLI
;; $ spel start --browser firefox

;; Standalone eval-sci (no daemon): start! configures browser
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

- PDF generation only works in Chromium headless. Firefox/WebKit don't support `page/pdf`.
- CDP = Chromium-only. `core/cdp-send` won't work with Firefox/WebKit.
- WebKit matches Safari rendering engine. Cross-browser testing, but no CDP + limited video support.

---

## Storage state

Storage state captures cookies + localStorage as JSON. Lighter than full profile, easy to share between test runs or CI jobs.

### Save + load

```clojure
;; Save after logging in (daemon mode)
(spel/navigate "https://myapp.com/login")
(spel/fill "#email" "me@example.org")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(spel/context-save-storage-state! "/tmp/auth-state.json")

;; Load in later session
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

`spel init-agents` scaffolds E2E test agents for AI coding tools. Agents work together: planner writes test plans with self-challenge, test-writer generates tests + self-heals failures.

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
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude` (`vscode` deprecated) |
| `--ns NS` | directory name | Base namespace for generated tests |
| `--flavour FLAVOUR` | `lazytest` | Test framework: `lazytest` or `clojure-test` |
| `--no-tests` | off | Only scaffold SKILL (API reference), skip test agents |
| `--dry-run` | off | Preview files without writing |
| `--force` | off | Overwrite existing files |
| `--test-dir DIR` | `test-e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory |

### Generated files

| File | Purpose |
|------|---------|
| `agents/spel-test-planner` | Explores app with `spel` CLI + `eval-sci`. Catalogs pages/flows. Writes test plans to `specs/`. |
| `agents/spel-test-writer` | Reads plans from `specs/`. Generates Clojure test files. Verifies selectors, self-heals failures. |
| `prompts/spel-test-workflow` | Orchestrator prompt: plan, generate, heal cycle. |
| `skills/spel/SKILL.md` | API reference for agents. |
| `specs/README.md` | Test plans directory with planner instructions. |
| `<test-dir>/<ns>/e2e/seed_test.clj` | Seed test file with working example. |

With `--no-tests`, only SKILL file generated. Useful for interactive dev where you want API reference available to AI assistant but don't need full test pipeline.

### How agents work together
1. Planner opens target app with `spel`, takes snapshots, explores navigation flows, writes markdown test plans.
2. Test-writer reads plans, writes Clojure test files, runs them, self-heals failures.

`spel-test-workflow` prompt chains both: plan first, generate + heal second.

### File locations by target

| Target | Agents | Skills | Prompts |
|--------|--------|--------|---------|
| `opencode` | `.opencode/agents/` | `.opencode/skills/spel/` | `.opencode/prompts/` |
| `claude` | `.claude/agents/` | `.claude/docs/spel/` | `.claude/prompts/` |
| `vscode` | `.github/agents/` | `.github/docs/spel/` | `.github/prompts/` | ⚠️ DEPRECATED |