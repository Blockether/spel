<p align="center">
  <img src="logo.svg" alt="spel logo" width="320"/>
</p>

<div align="center">
<i>spel</i> - The Swiss Army Knife browser tool for AI agents and Clojure developers.
<br/>
<sub>Browser automation · E2E and API testing · Allure reporting · Accessibility snapshots · Inline Clojure scripting · Record and Generate - one native binary, three browser engines.</sub>
</div>

<div align="center">
  <h2>
    <a href="https://clojars.org/com.blockether/spel"><img src="https://img.shields.io/clojars/v/com.blockether/spel?color=%23007ec6&label=clojars" alt="Clojars version"></a>
    <a href="https://github.com/Blockether/spel/blob/main/LICENSE">
      <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License - Apache 2.0">
    </a>
  </h2>
</div>

<div align="center">
<h3>

[Rationale](#rationale) • [Quick Start](#quick-start) • [Agent Scaffolding](#agent-scaffolding)

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

Playwright's Java API is imperative and verbose — option builders, checked exceptions, manual resource cleanup. Clojure deserves better. And AI agents deserve more than a shell wrapper.

spel wraps Playwright Java with idiomatic Clojure: maps for options, anomaly maps for errors, `with-*` macros for lifecycle, and a native CLI binary for instant browser automation. It does everything a modern agentic workflow needs — in one tool.

- **Swiss Army Knife for agents**: Browser automation, API testing, test reporting, agentic search and verification, accessibility snapshots, inline code execution, and test generation — all in a single native binary. No stitching tools together.
- **Agentic by design**: Accessibility snapshots with numbered refs let AI agents see the page as a structured document, not raw HTML. Persistent daemon, `--eval` scripting, and zero brittle CSS selectors — agents reason and act in a loop without restarting.
- **Inline Clojure execution**: Run arbitrary Clojure expressions in the browser context via `--eval` — mix business logic with automation, call any GraalVM-bound function, compose scripts on the fly. No other browser tool lets an agent write and execute real code mid-session.
- **Record, then generate**: Capture any browser session to JSONL and auto-generate idiomatic Clojure tests or reusable scripts. Record once, replay forever.
- **Allure reports with network inspection**: Full Allure reporting with embedded Playwright traces, network request/response visualization (method, status, headers, JSON body), and visual diffs. Debug failures from the report, not from logs.
- **API testing built in**: Intercept, assert, and inspect HTTP traffic in the same tool as your browser tests — no separate client needed.
- **Native CLI binary**: GraalVM native image, zero JVM startup, persistent daemon — fast enough for interactive agentic loops and CI alike.
- **Not a port**: Wraps Playwright Java directly — full API coverage, all three engines (Chromium, Firefox, WebKit).

## Quick Start

### Install

**Clojure library:**

```clojure
;; deps.edn
{:deps {com.blockether/spel {:mvn/version "0.5.0"}}}
```

**Native CLI (download from GitHub releases):**

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

Add `~/.local/bin` to your PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"  # add to ~/.bashrc or ~/.zshrc
```

**macOS Gatekeeper** (binaries are not Apple-signed):

```bash
xattr -d com.apple.quarantine ~/.local/bin/spel
```

**Post-install:**

```bash
spel install   # install browsers
spel version   # verify installation
```

<details>
<summary><strong>Corporate Proxy / Custom CA Certificates</strong></summary>

Behind a corporate SSL-inspecting proxy, `spel install` may fail with "PKIX path building failed". Use these env vars to add corporate CA certs:

| Env Var | Format | On missing file | Description |
|---------|--------|----------------|-------------|
| `SPEL_CA_BUNDLE` | PEM file | Error | Extra CA certs (merged with defaults) |
| `NODE_EXTRA_CA_CERTS` | PEM file | Warning, skips | Shared with Node.js subprocess |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Error | Truststore (merged with defaults) |
| `SPEL_TRUSTSTORE_TYPE` | String | — | Default: JKS |
| `SPEL_TRUSTSTORE_PASSWORD` | String | — | Default: empty |

```bash
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
spel install --with-deps
```

All options merge with built-in defaults — public CDN certs continue to work.

</details>

### Browser Automation

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page])

(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/title pg))
;; => "Example Domain"
```

Pass an opts map for device emulation:

```clojure
(core/with-testing-page {:device :iphone-14 :locale "fr-FR"} [pg]
  (page/navigate pg "https://example.org"))
```

For explicit lifecycle control, `with-playwright`/`with-browser`/`with-context`/`with-page` nesting is available. See the [full API reference](.opencode/skills/spel/SKILL.md).

### API Testing & Writing Tests

**Browser testing:**

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/title pg))
```

**API testing:**

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (core/api-get ctx "/users"))
```

**Combined UI + API** — use `page-api` or `with-page-api` to share a single Playwright trace:

```clojure
;; page-api: same context, same trace
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/api-get (core/page-api pg) "/api/me"))

;; with-page-api: same context, different base-url
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org/login")
  (core/with-page-api pg {:base-url "https://api.example.org"} [ctx]
    (core/api-get ctx "/me")))
```

> **Important:** Do NOT nest `with-testing-page` inside `with-testing-api` (or vice versa). Each creates its own Playwright instance, browser, and context — you get two separate traces instead of one. Use `page-api`/`with-page-api` to combine UI and API testing under a single trace.

**Test example** using `spel.allure` (`defdescribe`, `describe`, `it`, `expect`):

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.org"
    {:context [with-playwright with-browser with-page]}
    (it "navigates and asserts"
      (page/navigate *page* "https://example.org")
      (expect (= "Example Domain" (page/title *page*))))))
```

```bash
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

See [SKILL.md for fixtures, steps, and attachments](.opencode/skills/spel/SKILL.md).

### Native CLI

spel compiles to a native binary via GraalVM - no JVM startup, instant execution. The CLI provides commands for browser automation (`open`, `screenshot`, `snapshot`, `annotate`), a persistent browser daemon, session recording (`codegen`), PDF generation, and an `--eval` mode for inline Clojure scripting via SCI. Run `spel --help` for the full command list.

## Agent Scaffolding

Point your AI agent at spel and let it write your E2E tests.

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --loop=vscode                # VS Code / Copilot
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only (interactive dev)
```

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

## Video Recording

Record browser sessions as WebM files for debugging and CI artifacts.

```clojure
(def ctx (core/new-context browser {:record-video-dir "videos"}))
```

See [recording options and test fixtures](.opencode/skills/spel/SKILL.md).

## Test Generation (Codegen)

Record browser sessions and transform them to idiomatic Clojure code.

```bash
spel codegen record -o recording.jsonl https://example.org
spel codegen recording.jsonl > my_test.clj
```

See [full actions and output formats](.opencode/skills/spel/SKILL.md).

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
