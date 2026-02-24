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
(spel/fill "#email" "me@example.com")
(spel/fill "#password" "secret123")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(println "Logged in! Session saved to profile.")'
```

```bash
# Second run: session is already there
spel --profile /tmp/my-chrome-profile --eval '
(spel/navigate "https://myapp.com/dashboard")
(spel/wait-for-load)
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

## Device Emulation

Three approaches, each with different fidelity.

### Approach 1: Viewport Only

`spel/set-viewport-size!` changes width and height. No device pixel ratio, no mobile user agent, no touch support. Good enough for responsive CSS breakpoints.

```clojure
(spel/start!)
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/navigate "https://example.com")
(spel/screenshot {:path "/tmp/mobile-view.png"})
(spel/stop!)
```
### Approach 2: Full Device Preset (CLI Daemon)
The daemon's `set device` command configures viewport, DPR, user agent, and touch all at once.
```bash
spel open https://example.com
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: Library / `--eval` Options
Pass `:device` when creating the session. Sets viewport, DPR, user agent, touch, and mobile flag.

```clojure
;; --eval
(spel/start! {:device :iphone-14})
(spel/navigate "https://example.com")
(spel/screenshot {:path "/tmp/iphone14.png"})
(spel/stop!)

;; Library
(core/with-testing-page {:device :pixel-7} [pg]
  (page/navigate pg "https://example.com")
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
(spel/start! {:viewport :desktop-hd})
(core/with-testing-page {:viewport :tablet} [pg] ...)
(core/with-testing-page {:viewport {:width 1920 :height 1080}} [pg] ...)
```

Sizes: `:mobile` (375x667), `:mobile-lg` (428x926), `:tablet` (768x1024), `:tablet-lg` (1024x1366), `:desktop` (1280x720), `:desktop-hd` (1920x1080), `:desktop-4k` (3840x2160).

---

## Browser Selection

```clojure
;; --eval
(spel/start! {:browser :chromium})   ;; default
(spel/start! {:browser :firefox})
(spel/start! {:browser :webkit})

;; Library
(core/with-testing-page {:browser-type :firefox} [pg]
  (page/navigate pg "https://example.com"))

;; Headed mode (visible browser window)
(spel/start! {:headless false})
(spel/start! {:headless false :slow-mo 500})  ;; slow down for debugging
;; CLI equivalent: spel --eval --interactive '...'

;; Library headed mode
(core/with-testing-page {:headless false :slow-mo 300} [pg]
  (page/navigate pg "https://example.com"))
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
;; Save after logging in
(spel/start!)
(spel/navigate "https://myapp.com/login")
(spel/fill "#email" "me@example.com")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-url "**/dashboard")
(spel/context-save-storage-state! "/tmp/auth-state.json")
(spel/stop!)

;; Load in a later session
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
