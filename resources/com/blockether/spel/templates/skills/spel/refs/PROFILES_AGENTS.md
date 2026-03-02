# Browser Profiles, Device Emulation, and Agent Initialization

## Browser Profiles

Persistent profiles keep login sessions, cookies, and localStorage across runs. Log in once, reuse that session forever.

The profile path points to a directory. Chromium creates it automatically if it doesn't exist. Everything the browser stores (cookies, localStorage, IndexedDB, service workers) lives there.

### `--eval` / CLI Daemon Mode

Use the CLI `--profile` flag to launch with a persistent profile:

```bash
# First run: log in via script (--interactive opens visible browser)
spel --profile /tmp/my-chrome-profile --interactive --eval '
(spel/navigate "https://myapp.com/login")
(spel/fill "#email" "me@example.org")
(spel/fill "#password" "secret123")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(println "Logged in! Session saved to profile.")'
```

```bash
# Second run: session is already there
spel --profile /tmp/my-chrome-profile --eval '
(spel/navigate "https://myapp.com/dashboard")
(spel/wait-for-load-state)
(println "Title:" (spel/title))'
```

> **Note:** `:profile` is NOT a valid option for `spel/start!`. Use the CLI `--profile` flag (shown above) or `core/launch-persistent-context` in library mode.

### Library Mode

```clojure
;; with-testing-page accepts :profile directly
(core/with-testing-page {:profile "/tmp/my-profile"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title pg))
```

For lower-level control, use `core/launch-persistent-context` on the browser type directly.

### When to Use Profiles

- **Authenticated automation**: Log in once, run scripts against protected pages
- **Bypassing bot detection**: Reusing a real profile looks less suspicious than a fresh browser
- **Development workflows**: Keep dev tools settings, extensions, and preferences between runs

**Caveat**: Don't share a profile directory between concurrent processes. Chromium locks it.

---

## Profile vs State: When to Use Which

spel supports two auth approaches. Use this table to pick the right one:

| | `--profile` (persistent context) | `state export` + `--load-state` (portable JSON) |
|---|---|---|
| **How it works** | Launches browser with a real Chrome/Edge user data dir | Exports cookies + localStorage to JSON, loads into fresh context |
| **Auth persists** | Yes, automatically (in the profile dir) | Snapshot at export time — re-export to refresh |
| **Cross-machine** | No (cookies encrypted with OS keychain) | Yes (decrypted portable JSON) |
| **Extensions/prefs** | Yes (full Chrome profile) | No (cookies + localStorage only) |
| **Concurrent use** | No (Chromium locks the dir) | Yes (read-only JSON, any number of browsers) |
| **Edge support** | `--channel msedge --profile <path>` | `--channel msedge` on `state export` |
| **Best for** | Local automation, dev workflows, interactive sessions | CI pipelines, cross-platform, agent automation, parallel runs |

### Quick Decision

- **Working locally on your machine?** Use `--profile`
- **Running in CI or sharing auth across machines?** Use `state export` + `--load-state`
- **Need concurrent browser sessions with same auth?** Use `--load-state` (profiles lock)
- **Need extensions or browser preferences?** Use `--profile`

### Edge / Other Chromium Browsers

Use `--channel` to target non-default Chromium browsers:

```bash
# Persistent Edge profile
spel --channel msedge --profile ~/.config/microsoft-edge/Default open https://example.com

# Export Edge cookies
spel state export --channel msedge --profile ~/.config/microsoft-edge/Default -o edge-auth.json

# Use exported Edge cookies in any browser
spel --load-state edge-auth.json open https://example.com
```

### Browser Profile Paths

| OS | Chrome Default | Edge Default |
|----|----------------|--------------|
| macOS | `~/Library/Application Support/Google/Chrome/Default` | `~/Library/Application Support/Microsoft Edge/Default` |
| Linux | `~/.config/google-chrome/Default` | `~/.config/microsoft-edge/Default` |
| Windows | `%LOCALAPPDATA%\Google\Chrome\User Data\Default` | `%LOCALAPPDATA%\Microsoft\Edge\User Data\Default` |

Profiles are numbered: `Default`, `Profile 1`, `Profile 2`, etc. Check `chrome://version` or `edge://version` to find the exact path.

---

## Stealth Mode

Stealth mode is **ON by default** for all CLI and `--eval` commands. Anti-detection patches hide Playwright's automation signals from bot-detection systems (Cloudflare, DataDome, PerimeterX, etc.). Based on [puppeteer-extra-plugin-stealth](https://github.com/AhmedIbrahim336/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth). Use `--no-stealth` to disable.

### CLI

```bash
# Stealth is automatic — no flag needed
spel open https://example.org
spel --eval 'script.clj'
spel --profile /path/to/profile open https://protected-site.com

# Combine with other flags
spel --channel chrome --profile ~/.config/google-chrome/Profile\ 1 open https://x.com

# Disable stealth if needed
spel --no-stealth open https://example.org

# Environment variable to disable stealth
export SPEL_STEALTH=false
spel open https://example.org
```

### What Stealth Does

**Chrome launch args:**
- `--disable-blink-features=AutomationControlled` — prevents `navigator.webdriver=true`
- Suppresses `--enable-automation` — removes "Chrome is being controlled" infobar

**JavaScript evasion patches** (injected via `addInitScript` before any page loads):

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

### Stealth + Cookies Export Workflow

For maximum authenticity — combine stealth with real Chrome cookies:

```bash
# 1. Export cookies from your real Chrome profile
spel state export --profile ~/.config/google-chrome/Default -o auth.json

# 2. Use exported state (stealth is already on by default)
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

- Stealth patches help with common detection but are **not foolproof** against sophisticated fingerprinting (e.g., TLS fingerprint, HTTP/2 settings, canvas noise)
- Some sites (banks, Google login) may still detect automation regardless
- **Headed mode** (`--interactive`) combined with stealth (which is on by default) gives the best results
- Works with all three daemon modes: normal launch, persistent profile, and Chrome cookie injection

---

## State Export (`state export`)

Export cookies and localStorage from a real Chrome profile to a portable Playwright state JSON file. Works cross-platform (macOS, Linux, Windows).

(Alias: `spel cookies-export`)

### CLI

```bash
# Export from default Chrome profile
spel state export --profile ~/Library/Application\ Support/Google/Chrome/Default

# Export to a specific file
spel state export --profile ~/.config/google-chrome/Profile\ 1 -o auth.json

# Export cookies only (skip localStorage)
spel state export --profile /path/to/profile --no-local-storage

# Pipe to stdout (default when no -o)
spel state export --profile /path/to/profile > cookies.json
```

### Output Format

Standard Playwright storage-state JSON:

```json
{
  "cookies": [
    {"name": "session_id", "value": "abc123", "domain": ".example.org", "path": "/", ...}
  ],
  "origins": [
    {
      "origin": "https://example.org",
      "localStorage": [
        {"name": "theme", "value": "dark"},
        {"name": "user_prefs", "value": "{...}"}
      ]
    }
  ]
}
```

### How It Works

1. Copies Chrome's `Cookies` SQLite database to a temp file (avoids locking)
2. Decrypts cookie values using the OS keychain:
   - **macOS**: Reads encryption key from Keychain via `security` CLI
   - **Linux**: Reads from GNOME Keyring (`secret-tool`) or falls back to `"peanuts"`
   - **Windows**: Reads DPAPI-encrypted key from `Local State` JSON
3. Reads localStorage from Chrome's LevelDB files (Snappy-compressed SSTables)
4. Outputs standard Playwright storage-state JSON

### Cross-Platform Transfer

Export on macOS, use on Linux CI:

```bash
# On macOS (your dev machine)
spel state export --profile ~/Library/Application\ Support/Google/Chrome/Profile\ 1 -o auth.json

# Copy to Linux CI server
scp auth.json ci-server:/tmp/

# On Linux CI
spel --load-state /tmp/auth.json --eval 'script.clj'
```

### Common Profile Paths

| OS | Default Profile |
|----|----------------|
| macOS | `~/Library/Application Support/Google/Chrome/Default` |
| Linux | `~/.config/google-chrome/Default` |
| Windows | `%LOCALAPPDATA%\Google\Chrome\User Data\Default` |

Chrome profiles are numbered: `Default`, `Profile 1`, `Profile 2`, etc. Check `chrome://version` in your browser to find the exact path.

### CLI Aliases

`--load-state` is the primary flag. `--storage-state` is kept as an alias for Playwright familiarity:

```bash
spel --load-state auth.json open https://example.org
spel --storage-state auth.json open https://example.org   # alias, same thing
spel --eval --load-state auth.json 'script.clj'
spel --eval --storage-state auth.json 'script.clj'        # alias, same thing
```

---

## Device Emulation

Three approaches, each with different fidelity.

### Approach 1: Viewport Only

`spel/set-viewport-size!` changes width and height. No device pixel ratio, no mobile user agent, no touch support. Good enough for responsive CSS breakpoints.

```clojure
;; Daemon mode: just set viewport and go
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/mobile-view.png"})
```
### Approach 2: Full Device Preset (CLI Daemon)
The daemon's `set device` command configures viewport, DPR, user agent, and touch all at once.
```bash
spel open https://example.org
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: Library / `--eval` Options
Pass `:device` when creating the session. Sets viewport, DPR, user agent, touch, and mobile flag.

```clojure
;; Daemon: use CLI to set device on existing session
;; $ spel set device "iPhone 14"
;; Then --eval just navigates:
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})

;; Standalone --eval (no daemon): start! with device option
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
| `spel/set-viewport-size!` | yes | no | no | no | `--eval` |
| `spel set device "Name"` | yes | yes | yes | yes | CLI daemon |
| `{:device :name}` option | yes | yes | yes | yes | `--eval` + library |

### Device Presets

Mobile: `:iphone-se` (375x667), `:iphone-12` (390x844), `:iphone-14` (390x844), `:iphone-14-pro` (393x852), `:iphone-15` (393x852), `:iphone-15-pro` (393x852), `:pixel-5` (393x851), `:pixel-7` (412x915), `:galaxy-s24` (360x780), `:galaxy-s9` (360x740).

Tablet: `:ipad` (810x1080), `:ipad-mini` (768x1024), `:ipad-pro-11` (834x1194), `:ipad-pro` (1024x1366).

Desktop: `:desktop-chrome` (1280x720), `:desktop-firefox` (1280x720), `:desktop-safari` (1280x720).

### Viewport Presets

Use `:viewport` instead of `:device` when you just want dimensions without mobile emulation:

```clojure
;; Standalone --eval (no daemon)
(spel/start! {:viewport :desktop-hd})

;; Library
(core/with-testing-page {:viewport :tablet} [pg] ...)
(core/with-testing-page {:viewport {:width 1920 :height 1080}} [pg] ...)
```

Sizes: `:mobile` (375x667), `:mobile-lg` (428x926), `:tablet` (768x1024), `:tablet-lg` (1024x1366), `:desktop` (1280x720), `:desktop-hd` (1920x1080), `:desktop-4k` (3840x2160).

---

## Browser Selection

```clojure
;; Daemon: start with a specific browser via CLI
;; $ spel start --browser firefox

;; Standalone --eval (no daemon): start! configures the browser
(spel/start! {:browser :chromium})   ;; default
(spel/start! {:browser :firefox})
(spel/start! {:browser :webkit})

;; Library
(core/with-testing-page {:browser-type :firefox} [pg]
  (page/navigate pg "https://example.org"))

;; Headed mode (visible browser window)
;; Daemon: spel open URL (already headed)
;; Standalone --eval:
(spel/start! {:headless false})
(spel/start! {:headless false :slow-mo 500})  ;; slow down for debugging
;; CLI equivalent: spel --eval --interactive '...'

;; Library headed mode
(core/with-testing-page {:headless false :slow-mo 300} [pg]
  (page/navigate pg "https://example.org"))
```

### Browser-Specific Notes

- **PDF generation** only works in Chromium headless. Firefox and WebKit don't support `page/pdf`.
- **CDP (Chrome DevTools Protocol)** is Chromium-only. `core/cdp-send` won't work with Firefox or WebKit.
- **WebKit** matches Safari's rendering engine. Good for cross-browser testing, but no CDP and limited video support.

---

## Storage State

Storage state captures cookies and localStorage as a JSON file. Lighter than a full profile, easy to share between test runs or CI jobs.

### Save and Load

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
;; Standalone --eval:
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

### Profiles vs Storage State

| | Profile | Storage State |
|---|---|---|
| Persists | Everything (cookies, localStorage, IndexedDB, service workers, cache) | Cookies + localStorage only |
| Format | Directory (Chromium internal) | JSON file |
| Portable | No (tied to Chromium version) | Yes (plain JSON, works across machines) |
| Concurrent use | No (locked by Chromium) | Yes (read-only file) |
| Best for | Local dev, manual login reuse | CI pipelines, shared test fixtures |

---

## Agent Initialization

`spel init-agents` scaffolds E2E test agents for AI coding tools. Three agents work together in a plan, generate, heal loop.

### Quick Start

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --loop=vscode                # VS Code / Copilot
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only, no test agents
```

### Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude`, `vscode` |
| `--ns NS` | directory name | Base namespace for generated tests |
| `--flavour FLAVOUR` | `lazytest` | Test framework: `lazytest` or `clojure-test` |
| `--no-tests` | off | Only scaffold the SKILL (API reference), skip test agents |
| `--dry-run` | off | Preview files without writing |
| `--force` | off | Overwrite existing files |
| `--test-dir DIR` | `test-e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory |

### Generated Files

| File | Purpose |
|------|---------|
| `agents/spel-test-planner` | Explores the app with `spel` CLI and `--eval`. Catalogs pages/flows. Writes test plans to `specs/`. |
| `agents/spel-test-generator` | Reads plans from `specs/`. Generates Clojure test files. Verifies selectors before committing. |
| `agents/spel-test-healer` | Runs failing tests, investigates with `spel` CLI, diagnoses root causes, applies targeted fixes. |
| `prompts/spel-test-workflow` | Orchestrator prompt: plan, generate, heal cycle. |
| `skills/spel/SKILL.md` | API reference so agents know spel's functions and conventions. |
| `specs/README.md` | Test plans directory with instructions for the planner. |
| `<test-dir>/<ns>/e2e/seed_test.clj` | Seed test file with a working example to build from. |

With `--no-tests`, only the SKILL file is generated. Useful for interactive development where you want the API reference available to your AI assistant but don't need the full test pipeline.

### How the Agents Work Together
1. **Planner** opens the target app with `spel`, takes snapshots, explores navigation flows, and writes markdown test plans.
2. **Generator** reads those plans, writes Clojure test files, and runs them to confirm they pass.
3. **Healer** picks up failures, investigates with `spel snapshot` and `--eval`, identifies why the test broke, and patches the code.

The `spel-test-workflow` prompt chains all three: plan first, generate second, heal until green.

### File Locations by Target

| Target | Agents | Skills | Prompts |
|--------|--------|--------|---------|
| `opencode` | `.opencode/agents/` | `.opencode/skills/spel/` | `.opencode/prompts/` |
| `claude` | `.claude/agents/` | `.claude/docs/spel/` | `.claude/prompts/` |
| `vscode` | `.github/agents/` | `.github/docs/spel/` | `.github/prompts/` |
