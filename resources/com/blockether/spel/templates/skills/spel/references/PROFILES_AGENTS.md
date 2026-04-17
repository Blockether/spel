# Browser profiles, device emulation, agent initialization

## Profiles

Persistent profiles keep login sessions, cookies, localStorage, IndexedDB, service workers across runs. Chromium creates the directory if missing.

### eval-sci / CLI daemon

Use `--profile`:

```bash
# First run (--interactive opens visible browser)
spel --profile /tmp/my-chrome-profile --interactive eval-sci '
  (spel/navigate "https://myapp.com/login")
  (spel/fill "#email" "me@example.org")
  (spel/fill "#password" "secret123")
  (spel/click "button[type=submit]")
  (spel/wait-for-url "**/dashboard")
  (println "Logged in! Session saved to profile.")'

# Later runs — session already there
spel --profile /tmp/my-chrome-profile eval-sci '
  (spel/navigate "https://myapp.com/dashboard")
  (spel/wait-for-load-state)
  (println "Title:" (spel/title))'
```

> `:profile` is not a valid option for `spel/start!`. Use the CLI `--profile` flag or `core/launch-persistent-context`.

### Library

```clojure
(core/with-testing-page {:profile "/tmp/my-profile"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title    pg))
```

### When to use profiles

- Authenticated automation: log in once, run against protected pages.
- Less suspicious to bot detection than a fresh browser.
- Keep dev-tools settings / extensions / preferences between runs.

Caveat: never share a profile dir between concurrent processes — Chromium locks it.

### Profile vs `--load-state`

| | `--profile` (persistent context) | `--load-state` (portable JSON) |
|---|---|---|
| How | `launchPersistentContext` on a user-data dir | Loads cookies + localStorage JSON into a fresh context |
| Auth persists | Auto | Snapshot at save time — re-save to refresh |
| Concurrent use | No (dir is locked) | Yes (JSON is read-only) |
| Best for | Local automation, dev workflows | CI pipelines, cross-platform, parallel runs |

Quick pick: local → `--profile`. Concurrent / CI → `--load-state`.

### Edge / other Chromium browsers

```bash
spel --channel msedge --profile ~/.config/microsoft-edge/Default open https://example.com
spel --load-state auth.json open https://example.com
```

### Default profile paths

| OS | Chrome Default | Edge Default |
|----|----------------|--------------|
| macOS | `~/Library/Application Support/Google/Chrome/Default` | `~/Library/Application Support/Microsoft Edge/Default` |
| Linux | `~/.config/google-chrome/Default` | `~/.config/microsoft-edge/Default` |
| Windows | `%LOCALAPPDATA%\Google\Chrome\User Data\Default` | `%LOCALAPPDATA%\Microsoft\Edge\User Data\Default` |

Additional profiles numbered `Profile 1`, `Profile 2`, … Check `chrome://version` / `edge://version`.

## Daemon launch modes

| Mode | Trigger | What happens | Use case |
|------|---------|--------------|----------|
| 1 · Persistent profile | `--profile <dir>` | `launchPersistentContext` on the dir | Local automation with session persistence |
| 2 · Auto-launch | `--auto-launch` | Dedicated browser with `--remote-debugging-port` on a unique port, connects via CDP | Per-session isolated browser for AI agents |
| 3 · Normal / CDP | No `--profile` / `--auto-launch` | Standard `launch` + `new-context`, or `--cdp` / `--auto-connect` | One-off automation, CI, attach-to-existing |

### Mode 2 key properties

- **Per-session isolation**: own browser process on a unique port.
- **User browser untouched**: temp profile dir, never kills existing browsers.
- **Auto-cleanup**: browser killed + temp dir deleted on `spel close`.
- **Port allocation**: scans 9222–9321; lock files prevent cross-session collisions.
- Trade-off: fresh profile → no existing auth cookies. Use `--profile` if you need them.

```bash
spel --auto-launch --session test1 open https://example.com
spel --auto-launch --channel msedge --session test2 open https://example.com
```

### Daemon lifecycle & timeouts

| Variable | Default | Notes |
|----------|--------:|-------|
| `SPEL_SESSION_IDLE_TIMEOUT` | 30 min (1800000 ms) | No command → shutdown. `0` disables. Runtime: `(spel/set-session-idle-timeout! ms)`. |
| `SPEL_CDP_IDLE_TIMEOUT` | 30 min (1800000 ms) | After CDP disconnect, no reconnect → shutdown. `0` disables. |
| `SPEL_CDP_LOCK_WAIT` | 120 s | Another session holds lock → commands queue, poll every 2 s. |
| `SPEL_CDP_LOCK_POLL_INTERVAL` | 2 s | Poll interval when waiting on a CDP lock. |

## CDP auto-connect

Connect to a running Chrome/Edge via CDP — spel controls the real browser with its sessions, cookies, tabs.

> Simpler alternative: `--auto-launch` handles launch, port allocation, and connection with per-session isolation.

### Chrome/Edge 136+ requirement

Chrome/Edge ≥ 136 (April 2025) ignores `--remote-debugging-port` on the default user-data-dir (security, not a bug).

#### Option 1 — launch with a non-default user-data-dir

```bash
SESSION="agent-$(date +%s)"
CDP_PORT=$(spel find-free-port)

# macOS
open -na "Google Chrome"   --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
open -na "Microsoft Edge" --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run

# Linux
google-chrome  --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
microsoft-edge --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run

# Connect
spel --session $SESSION --auto-connect open https://example.com
# or
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open https://example.com
```

#### Option 2 — enable in a running browser (M144+)

1. Open `chrome://inspect/#remote-debugging` (or `edge://inspect/#remote-debugging`).
2. Toggle remote debugging ON.
3. Browser creates `DevToolsActivePort` automatically.
4. `spel --auto-connect open https://example.com`.

### Auto-connect discovery

1. Scans `DevToolsActivePort` across chromium-family user-data dirs on the current OS (Chrome stable/Beta/Canary/Dev/For Testing, Chromium, Edge variants, Brave, Vivaldi, Opera, Arc, Thorium; Linux snap + Flatpak variants).
2. Checks `ms-playwright` cache dir (finds Chrome launched by other tools like `chrome-devtools-mcp`).
3. Probes common ports (`9222`, `9229`) via `GET /json/version`.
4. Chrome/Edge 144+ WebSocket-only mode: falls back to direct WebSocket using `DevToolsActivePort`'s ws-path.

### Flag persistence

First successful `--auto-connect` persists the discovered URL to the session flags file — subsequent commands reuse it:

```bash
spel --auto-connect open https://example.com     # discovers + persists
spel snapshot                                    # reuses
spel click @eXXXX
```

### CDP env vars

| Var | Purpose |
|-----|---------|
| `SPEL_CDP` | CDP endpoint URL (same as `--cdp`) |
| `SPEL_AUTO_CONNECT` | Any value enables `--auto-connect` |
| `SPEL_AUTO_LAUNCH` | Any value enables `--auto-launch` |

### CDP limitations

- Chromium-only (no Firefox/WebKit).
- Chrome/Edge ≥ 136 requires `--user-data-dir` pointing to a non-default dir.
- Can't add `--remote-debugging-port` retroactively — use `chrome://inspect/#remote-debugging` (M144+) instead.
- One named session per stage + one endpoint owner — no concurrent multi-session attach to the same endpoint.
- Non-default `--user-data-dir` = fresh profile unless pointed at an existing one.

## Stealth mode

On by default for all CLI + `eval-sci`. Based on [puppeteer-extra-plugin-stealth](https://github.com/AhmedIbrahim336/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth). `--no-stealth` / `SPEL_STEALTH=false` to disable.

```bash
spel open https://example.org                             # stealth auto
spel --profile /path/to/profile open https://protected-site.com
spel --channel chrome --profile ~/.config/google-chrome/Profile\ 1 open https://x.com
spel --no-stealth open https://example.org                # off
```

### What it does

Launch args:

- `--disable-blink-features=AutomationControlled` → hides `navigator.webdriver=true`.
- Suppresses `--enable-automation` → removes the "Chrome is being controlled" infobar.

JS evasion (via `addInitScript` before page loads):

| Patch | Hides |
|-------|-------|
| `navigator.webdriver` | returns `undefined` instead of `true` |
| `navigator.plugins` | emulates Chrome PDF plugins (empty in headless) |
| `navigator.languages` | `['en-US', 'en']` |
| `chrome.runtime` | mocks `connect()` / `sendMessage()` |
| `permissions.query` | fixes `Notification.permission` |
| WebGL renderer | realistic GPU vendor/renderer strings |
| `outerWidth/Height` | matches inner dims (headless mismatch) |
| `iframe.contentWindow` | prevents iframe fingerprinting |

### Library API

```clojure
(require '[com.blockether.spel.stealth :as stealth])

(stealth/stealth-args)                  ; ["--disable-blink-features=AutomationControlled"]
(stealth/stealth-ignore-default-args)   ; ["--enable-automation"]
(stealth/stealth-init-script)           ; "(function() { ... })();"

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

- Helps with common detection — not foolproof against TLS, HTTP/2, or canvas-noise fingerprinting.
- Some sites (banks, Google login) may still detect automation.
- Headed (`--interactive`) + stealth gives the best results.
- Works across all launch modes: normal, persistent profile, CDP connect.

## Device emulation

Four approaches, different fidelity:

| Approach | Viewport | DPR | User Agent | Touch | Available in |
|----------|:-:|:-:|:-:|:-:|---|
| `spel/set-viewport-size!` | ✓ | — | — | — | `eval-sci` |
| `spel/set-device!` | ✓ | ✓ | ✓ | ✓ | `eval-sci` (programmatic) |
| `spel set device "Name"` | ✓ | ✓ | ✓ | ✓ | CLI daemon |
| `{:device :name}` | ✓ | ✓ | ✓ | ✓ | `eval-sci` + library |

```clojure
;; Viewport only
(spel/set-viewport-size! 390 844)
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/mobile-view.png"})

;; Programmatic full device from SCI (keyword or name string,
;; matching is case/separator/punctuation insensitive):
(spel/set-device! :iphone-14)
(spel/set-device! "Pixel 7")
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})
```

Daemon mode: `set-device!` delegates to the daemon's `set_device` handler
(saves URL, recreates context + page, re-registers listeners, renavigates).
Standalone SCI: recreates the context on the existing browser. Throws with
the available preset list when the name is unknown.

```bash
# Full device preset via daemon CLI (unchanged)
spel open https://example.org
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

```clojure
;; Standalone eval-sci
(spel/start! {:device :iphone-14})
(spel/navigate "https://example.org")
(spel/screenshot {:path "/tmp/iphone14.png"})
(spel/stop!)

;; Library
(core/with-testing-page {:device :pixel-7} [pg]
  (page/navigate pg "https://example.org")
  (page/screenshot pg {:path "/tmp/pixel7.png"}))
```

Device + viewport presets → `CONSTANTS.md` / `BROWSER_OPTIONS.md`.

## Browser selection

```clojure
;; Standalone eval-sci
(spel/start! {:browser :chromium})   ; default
(spel/start! {:browser :firefox})
(spel/start! {:browser :webkit})
(spel/start! {:headless false :slow-mo 500})

;; Library
(core/with-testing-page {:browser-type :firefox} [pg] …)
(core/with-testing-page {:headless false :slow-mo 300} [pg] …)
```

### Browser-specific notes

- PDF generation works **only** in Chromium headless. Firefox/WebKit don't support `page/pdf`.
- CDP is Chromium-only.
- WebKit matches Safari's rendering engine (cross-browser testing, limited video support, no CDP).

## Storage state

Capture cookies + localStorage as JSON. Lighter than a profile, easy to share between runs / CI jobs.

```clojure
;; Save after logging in
(spel/navigate "https://myapp.com/login")
(spel/fill "#email"    "me@example.org")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(spel/context-save-storage-state! "/tmp/auth-state.json")

;; Reuse — standalone eval-sci
(spel/start! {:storage-state "/tmp/auth-state.json"})
(spel/navigate "https://myapp.com/dashboard")
(spel/stop!)

;; Library
(core/with-testing-page {:storage-state "/tmp/auth-state.json"} [pg]
  (page/navigate pg "https://myapp.com/dashboard")
  (page/title pg))
```

### Profile vs storage state

| | Profile | Storage state |
|---|---|---|
| Persists | Cookies, localStorage, IndexedDB, service workers, cache | Cookies + localStorage only |
| Format | Directory (Chromium-internal) | JSON file |
| Portable | No (tied to Chromium version) | Yes |
| Concurrent use | No (locked by Chromium) | Yes (read-only) |
| Best for | Local dev, manual login reuse | CI pipelines, shared test fixtures |

## Agent initialization

`spel init-agents` scaffolds E2E test agents for AI coding tools. The test-writer explores the live app, generates tests directly, and self-heals failures — one agent, one pass.

### Quick start

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only, no test agents
```

### Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | `opencode`, `claude` |
| `--ns NS` | directory name | Base namespace for generated tests |
| `--flavour FLAVOUR` | `lazytest` | `lazytest` or `clojure-test` |
| `--no-tests` | off | Only SKILL (API reference), skip test agents |
| `--dry-run` | off | Preview files without writing |
| `--force` | off | Overwrite existing files |
| `--test-dir DIR` | `test-e2e` | E2E test output dir |

### Generated files

| File | Purpose |
|------|---------|
| `agents/spel` | Explores, generates tests, finds bugs, automates browser tasks |
| `skills/spel/SKILL.md` | API reference for agents |
| `<test-dir>/<ns>/e2e/seed_test.clj` | Seed test file with a working example |

With `--no-tests`, only SKILL is generated — useful when you want the API reference available to an AI assistant but don't need a test writer.

### File locations by target

| Target | Agents | Skills |
|--------|--------|--------|
| `opencode` | `.opencode/agents/` | `.opencode/skills/spel/` |
| `claude` | `.claude/agents/` | `.claude/docs/spel/` |
