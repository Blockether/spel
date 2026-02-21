<p align="center">
  <img src="logo.svg" alt="spel logo" width="320"/>
</p>

<div align="center">
<i>spel</i> - Idiomatic Clojure wrapper for <a href="https://playwright.dev/">Microsoft Playwright</a>.
<br/>
<sub>Browser automation, API testing, test reporting, and native CLI — for Chromium, Firefox, and WebKit.</sub>
</div>

<div align="center">
  <h2>
    <a href="https://clojars.org/com.blockether/spel"><img src="https://img.shields.io/clojars/v/com.blockether/spel?color=%23007ec6&label=clojars" alt="Clojars version"></a>
    <a href="https://github.com/Blockether/spel/blob/main/LICENSE">
      <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License - Apache 2.0">
    </a>
    <a href="https://blockether.github.io/spel/">
      <img src="https://blockether.github.io/spel/badge.svg" alt="Allure Report">
    </a>
  </h2>
</div>

<div align="center">
<h3>

[Rationale](#rationale) • [Quick Start](#quick-start) • [Native CLI](#native-cli) • [API Testing](#api-testing) • [Allure Test Reporting](#allure-test-reporting) • [Agent Scaffolding](#agent-scaffolding)

</h3>
</div>

<table>
<tr>
<td width="25%" align="center"><b>Accessibility Snapshots</b></td>
<td width="25%" align="center"><b>Inline&nbsp;Clojure&nbsp;via&nbsp;--eval</b></td>
<td width="25%" align="center"><b>Visual Annotations</b></td>
<td width="25%" align="center"><b>Agent Scaffolding</b></td>
</tr>
<tr>
<td><img src="docs/screenshots/cli-snapshot.png" alt="spel snapshot demo"/></td>
<td><img src="docs/screenshots/cli-eval.png" alt="spel eval demo"/></td>
<td><img src="docs/screenshots/annotate-demo.png" alt="spel annotate demo"/></td>
<td><img src="docs/screenshots/agents-demo.png" alt="spel agents demo"/></td>
</tr>
</table>

## Rationale

Playwright's Java API is imperative and verbose — option builders, checked exceptions, manual resource cleanup. Clojure deserves better.

spel wraps the official Playwright Java 1.58.0 library with idiomatic Clojure: maps for options, anomaly maps for errors, `with-*` macros for lifecycle, and a native CLI binary for instant browser automation from the terminal.

- **Data-driven**: Maps for options, anomaly maps for errors — no option builders, no checked exceptions
- **Composable**: `with-*` macros for lifecycle management — resources always cleaned up
- **Agent-friendly**: Accessibility snapshots with numbered refs, persistent browser daemon, and `--eval` scripting — built for AI agents to see, decide, and act
- **Record & replay**: Record browser sessions to JSONL, transform to idiomatic Clojure tests or scripts
- **Batteries included**: API testing, Allure reporting with embedded Playwright traces, agent scaffolding for Claude/VS Code/OpenCode
- **Not a port**: Wraps the official Playwright Java library directly — full API coverage, same browser versions

## Quick Start

```clojure
;; deps.edn
{:deps {com.blockether/spel {:mvn/version "0.3.1"}}}
```

```bash
spel install  # requires spel CLI — see "Native CLI" below
```

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page]
         '[com.blockether.spel.api :as api])

;; Browser automation
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com")
  (page/title pg))
;; => "Example Domain"

;; API testing — Playwright-backed HTTP with automatic tracing
(api/with-testing-api {:base-url "https://api.example.com"} [ctx]
  (api/api-get ctx "/users"))
```

Pass an opts map for device emulation:

```clojure
(core/with-testing-page {:device :iphone-14 :locale "fr-FR"} [pg]
  (page/navigate pg "https://example.com"))
```

Need explicit control over lifecycle? `with-playwright`/`with-browser`/`with-context`/`with-page` nesting is available. The [full API reference](.opencode/skills/spel/SKILL.md) covers all options.

## Native CLI

### Releases

Download from [GitHub releases](https://github.com/Blockether/spel/releases):

```bash
# macOS (Apple Silicon)
curl -LO https://github.com/Blockether/spel/releases/latest/download/spel-macos-arm64
chmod +x spel-macos-arm64 && mv spel-macos-arm64 ~/.local/bin/spel

# Linux (amd64)
curl -LO https://github.com/Blockether/spel/releases/latest/download/spel-linux-amd64
chmod +x spel-linux-amd64 && mv spel-linux-amd64 ~/.local/bin/spel

# Linux (arm64)
curl -LO https://github.com/Blockether/spel/releases/latest/download/spel-linux-arm64
chmod +x spel-linux-arm64 && mv spel-linux-arm64 ~/.local/bin/spel

# Windows (PowerShell)
Invoke-WebRequest -Uri https://github.com/Blockether/spel/releases/latest/download/spel-windows-amd64.exe -OutFile spel.exe
Move-Item spel.exe "$env:LOCALAPPDATA\Microsoft\WindowsApps\spel.exe"
```

> **Tip:** The examples install to `~/.local/bin/` (no sudo needed). Make sure it's on your `PATH`:
> ```bash
> export PATH="$HOME/.local/bin:$PATH"  # add to ~/.bashrc or ~/.zshrc
> ```
> You can also install system-wide with `sudo mv spel-* /usr/local/bin/spel` instead.

### MacOS Gatekeeper

The binaries are not signed with an Apple Developer certificate. macOS will block the first run with *"spel can't be opened because Apple cannot check it for malicious software"*. To allow it:

```bash
# Remove the quarantine attribute (recommended)
xattr -d com.apple.quarantine ~/.local/bin/spel
```

Or: **System Settings → Privacy & Security → scroll down → click "Allow Anyway"** after the first blocked attempt.

### Post-install

Install browsers and verify:

```bash
spel install
spel version
```

## API Testing

Playwright-backed HTTP testing with automatic tracing.

### API Functions

| Function | Description | Auto-Traces? |
|----------|-------------|--------------|
| `page-api pg` | Extract APIRequestContext from a Page | ✅ Yes |
| `context-api ctx` | Extract APIRequestContext from a BrowserContext | ✅ Yes |
| `with-testing-api [ctx] body` | Standalone API testing with full Playwright stack | ✅ Yes (when Allure active) |
| `with-page-api pg opts [ctx] body` | Page-bound API with custom base-url + cookie sharing | ❌ No |

### Usage Examples

**1. Standalone API testing** — creates full Playwright stack:
```clojure
;; With base-url
(api/with-testing-api {:base-url "https://api.example.com"} [ctx]
  (api/api-get ctx "/users"))

;; Without base-url (full URLs required)
(api/with-testing-api [ctx]
  (api/api-get ctx "https://api.example.com/users"))
```

**2. API from Page** — share browser cookies/session:
```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com/login")
  ;; API calls share the browser session
  (let [resp (api/api-get (api/page-api pg) "/api/me")]
    (api/api-response-status resp)))
```

**3. API from BrowserContext** — extract from existing context:
```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw)]
    (core/with-context [ctx (core/new-context browser)]
      (let [resp (api/api-get (api/context-api ctx) "/users")]
        (api/api-response-status resp)))))
```

**4. Page-bound API with custom base-url** — cookies + different domain:
```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com/login")
  ;; ... login via UI ...
  ;; Use different base-url while sharing browser cookies
  (api/with-page-api pg {:base-url "https://api.example.com"} [ctx]
    (api/api-get ctx "/me")))

[API reference covers the full feature set](.opencode/skills/spel/SKILL.md).

## Allure Test Reporting

Integrates with [Lazytest](https://github.com/noahtheduke/lazytest) for test reports using [Allure](https://allurereport.org/). Generates the HTML report automatically with embedded Playwright traces and trace viewer.

> **[View live test report](https://blockether.github.io/spel/)** — with embedded traces.

<table>
<tr>
<td width="50%" align="center"><b>Allure&nbsp;Report</b></td>
<td width="50%" align="center"><b>Embedded&nbsp;Playwright&nbsp;Traces</b></td>
</tr>
<tr>
<td><a href="https://blockether.github.io/spel/"><img src="docs/screenshots/allure-report.png" alt="Allure Report"/></a></td>
<td><a href="https://blockether.github.io/spel/"><img src="docs/screenshots/allure-trace-viewer.png" alt="Playwright Trace Viewer embedded in Allure"/></a></td>
</tr>
</table>

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.com"
    {:context [with-playwright with-browser with-page]}
    (it "navigates and asserts"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*))))))
```

```bash
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

Automatic tracing, trace viewer, and history included. See [SKILL.md for fixtures, steps, and attachments](.opencode/skills/spel/SKILL.md).

## Video Recording

Record browser sessions as WebM files for debugging and CI artifacts.

```clojure
(def ctx (core/new-context browser {:record-video-dir "videos"}))
```

[Recording options and test fixtures are documented here](.opencode/skills/spel/SKILL.md).

## Test Generation (Codegen)

Record browser sessions and transform to idiomatic Clojure code.

```bash
spel codegen record -o recording.jsonl https://example.com
spel codegen recording.jsonl > my_test.clj
```

[Full actions and output formats in the SKILL reference](.opencode/skills/spel/SKILL.md).

## Agent Scaffolding

Point your AI agent at spel and let it write your E2E tests.

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --loop=vscode                # VS Code / Copilot
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only (interactive dev)
```

| File | Purpose |
|------|---------|
| `agents/spel-test-planner` | Explores app, writes structured test plans |
| `agents/spel-test-generator` | Reads test plans, generates Clojure test code |
| `agents/spel-test-healer` | Runs failing tests, diagnoses issues, applies fixes |
| `prompts/spel-test-workflow` | Orchestrator: plan → generate → heal cycle |
| `skills/spel/SKILL.md` | API reference for agents |

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude`, `vscode` |
| `--ns NS` | dir name | Base namespace for generated tests |
| `--flavour FLAVOUR` | `lazytest` | Test framework: `lazytest` or `clojure-test` |
| `--no-tests` | — | Scaffold only the SKILL (API reference) — no test agents |
| `--dry-run` | — | Preview files without writing |
| `--force` | — | Overwrite existing files |
| `--test-dir DIR` | `test-e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory |

## Building from Source

```bash
# Install browsers (via Playwright Java CLI)
clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

# Build JAR
clojure -T:build jar

# Build native image (requires GraalVM)
clojure -T:build native-image

# Run tests
make test
make test-allure

# Start REPL
make repl
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
