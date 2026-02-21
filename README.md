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

[Rationale](#rationale) • [Quick Start](#quick-start) • [Usage](#usage) • [API Testing](#api-testing) • [Allure Reporting](#allure-test-reporting) • [Native CLI](#native-cli)

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
{:deps {com.blockether/spel {:mvn/version "0.3.0"}}}
```

```bash
spel install  # requires spel CLI — see "As a Native CLI Binary" below
```

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page]
         '[com.blockether.spel.locator :as locator])

(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        (println (locator/text-content (page/locator pg "h1")))))))
;; => "Example Domain"
```

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

### Corporate Proxy / Custom CA Certificates

If you're behind a corporate SSL-inspecting proxy, `spel install` may fail with *"PKIX path building failed"* because the native binary uses certificates baked at build time. Set one of these environment variables to add your corporate CA:

```bash
# PEM file with corporate CA cert(s) — simplest option
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem

# Or reuse Node.js env var — covers both driver + browser downloads
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem

# Or use a JKS/PKCS12 truststore
export SPEL_TRUSTSTORE=/path/to/truststore.jks
export SPEL_TRUSTSTORE_PASSWORD=changeit    # optional
export SPEL_TRUSTSTORE_TYPE=JKS             # optional, default: JKS
```

| Env Var | Format | Behavior on missing file |
|---------|--------|-------------------------|
| `SPEL_CA_BUNDLE` | PEM file | Error (explicit config) |
| `NODE_EXTRA_CA_CERTS` | PEM file | Warning, continues with defaults |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Error (explicit config) |

All options **merge** with the built-in default certificates — public CDN certs continue to work alongside your corporate CA.

> **Tip:** `NODE_EXTRA_CA_CERTS` is shared with the Node.js subprocess that installs browsers, so one env var covers both the driver download (Java/native) and browser download (Node.js) paths.

You can also pass a truststore directly via JVM system property (GraalVM native-image supports this at runtime):

```bash
spel -Djavax.net.ssl.trustStore=/path/to/truststore.jks install
```

> **Note:** Unlike the env vars above, `-Djavax.net.ssl.trustStore` **replaces** the default truststore entirely — your truststore must include both corporate and public CA certificates.

## Usage

For the full API reference — browser lifecycle, page operations, locators, assertions, network, input, frames, accessibility snapshots, error handling, and more — see the [**SKILL reference**](.opencode/skills/spel/SKILL.md).

> The SKILL file is the single source of truth for the complete API. It's auto-generated from templates and always up to date.

### Quick Example

All browser work starts with nested `with-*` macros that guarantee resource cleanup:

```clojure
(require '[com.blockether.spel.core :as core])

(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        ;; Your code here — pg is a fresh Page
        ))))
```

Launch specific browser engines:

```clojure
(core/launch-chromium pw {:headless true})
;; Also: launch-firefox, launch-webkit

;; Browser queries
(core/browser-connected? browser)
;; => true

(core/browser-version browser)
;; => "136.0.7103.25"

(core/browser-contexts browser)
;; => [#object[BrowserContext ...]]
```

| Macro | Cleans Up |
|-------|-----------|
| `with-playwright` | Playwright instance |
| `with-browser` | Browser instance |
| `with-context` | BrowserContext |
| `with-page` | Page instance |

### Standalone Testing Page

For quick tests and scripts, `with-testing-page` creates the entire stack in one shot — no nesting required:

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page])

;; Minimal — headless Chromium, default viewport
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com")
  (page/title pg))
;; => "Example Domain"
```

Pass an opts map for device emulation, viewport presets, or browser selection:

```clojure
;; Device emulation
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.com"))

;; Viewport preset
(core/with-testing-page {:viewport :desktop-hd :locale "fr-FR"} [pg]
  (page/navigate pg "https://example.com"))

;; Firefox, headed mode
(core/with-testing-page {:browser-type :firefox :headless false} [pg]
  (page/navigate pg "https://example.com"))

;; Persistent profile (keeps login sessions across runs)
(core/with-testing-page {:profile "/tmp/my-chrome-profile"} [pg]
  (page/navigate pg "https://example.com"))

;; Custom browser executable + extra args
(core/with-testing-page {:executable-path "/usr/bin/chromium"
                         :args ["--disable-gpu"]} [pg]
  (page/navigate pg "https://example.com"))
```

| Option | Values | Default |
|--------|--------|---------|
| `:browser-type` | `:chromium`, `:firefox`, `:webkit` | `:chromium` |
| `:headless` | `true`, `false` | `true` |
| `:device` | `:iphone-14`, `:pixel-7`, `:ipad`, `:desktop-chrome`, [etc.](#device-presets) | — |
| `:viewport` | `:mobile`, `:tablet`, `:desktop-hd`, `{:width N :height N}` | browser default |
| `:slow-mo` | Millis to slow down operations | — |
| `:profile` | String path to persistent user data dir | — |
| `:executable-path` | String path to browser executable | — |
| `:channel` | `"chrome"`, `"msedge"`, etc. | — |
| `:proxy` | `{:server "..." :bypass "..." :username "..." :password "..."}` | — |
| `:args` | Vector of extra browser CLI args | — |
| `:downloads-path` | String path for downloaded files | — |
| `:timeout` | Max ms to wait for browser launch | — |
| `:chromium-sandbox` | `true`, `false` | — |
| + any key accepted by `new-context` | `:locale`, `:color-scheme`, `:timezone-id`, `:storage-state`, etc. | — |

When the [Allure reporter](#allure-test-reporting) is active, tracing (screenshots + DOM snapshots + network) and HAR recording are enabled automatically — zero configuration.

#### Device Presets

| Keyword | Viewport | Mobile |
|---------|----------|--------|
| `:iphone-se` | 375×667 | ✓ |
| `:iphone-12` | 390×844 | ✓ |
| `:iphone-14` | 390×844 | ✓ |
| `:iphone-14-pro` | 393×852 | ✓ |
| `:iphone-15` | 393×852 | ✓ |
| `:iphone-15-pro` | 393×852 | ✓ |
| `:ipad` | 810×1080 | ✓ |
| `:ipad-mini` | 768×1024 | ✓ |
| `:ipad-pro-11` | 834×1194 | ✓ |
| `:ipad-pro` | 1024×1366 | ✓ |
| `:pixel-5` | 393×851 | ✓ |
| `:pixel-7` | 412×915 | ✓ |
| `:galaxy-s24` | 360×780 | ✓ |
| `:galaxy-s9` | 360×740 | ✓ |
| `:desktop-chrome` | 1280×720 | ✗ |
| `:desktop-firefox` | 1280×720 | ✗ |
| `:desktop-safari` | 1280×720 | ✗ |

#### Viewport Presets

| Keyword | Size |
|---------|------|
| `:mobile` | 375×667 |
| `:mobile-lg` | 428×926 |
| `:tablet` | 768×1024 |
| `:tablet-lg` | 1024×1366 |
| `:desktop` | 1280×720 |
| `:desktop-hd` | 1920×1080 |
| `:desktop-4k` | 3840×2160 |

## API Testing

### Creating API Context

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.api :as api])

;; Single context
(api/with-api-context [ctx (api/new-api-context (api/api-request pw)
                             {:base-url "https://api.example.com"
                              :extra-http-headers {"Authorization" "Bearer token"}})]
  (let [resp (api/api-get ctx "/users")]
    (println (api/api-response-status resp))    ;; 200
    (println (api/api-response-text resp))))     ;; JSON body

;; Multiple contexts
(api/with-api-contexts
  [users   (api/new-api-context (api/api-request pw) {:base-url "https://users.example.com"})
   billing (api/new-api-context (api/api-request pw) {:base-url "https://billing.example.com"})]
  (api/api-get users "/me")
  (api/api-get billing "/invoices"))
```

### HTTP Methods

```clojure
;; GET with params and headers
(api/api-get ctx "/users")
(api/api-get ctx "/users" {:params {:page 1 :limit 10}
                           :headers {"Authorization" "Bearer token"}})

;; POST with JSON body
(api/api-post ctx "/users"
  {:data "{\"name\":\"Alice\"}"
   :headers {"Content-Type" "application/json"}})

;; PUT, PATCH, DELETE, HEAD
(api/api-put ctx "/users/1" {:data "{\"name\":\"Bob\"}"})
(api/api-patch ctx "/users/1" {:data "{\"name\":\"Charlie\"}"})
(api/api-delete ctx "/users/1")
(api/api-head ctx "/health")

;; Custom method
(api/api-fetch ctx "/resource" {:method "OPTIONS"})
```

### JSON Encoding

```clojure
(require '[cheshire.core :as json])

;; Bind JSON encoder for :json option support
(binding [api/*json-encoder* json/generate-string]
  (api/api-post ctx "/users" {:json {:name "Alice" :age 30}}))

;; Or set globally
(alter-var-root #'api/*json-encoder* (constantly json/generate-string))
```

### Form Data

```clojure
;; Build FormData manually
(let [fd (api/form-data)]
  (api/fd-set fd "name" "Alice")
  (api/fd-append fd "tag" "clojure")
  (api/api-post ctx "/submit" {:form fd}))

;; Or from a map
(api/api-post ctx "/submit" {:form (api/map->form-data {:name "Alice" :email "a@b.c"})})
```

### Response Inspection

```clojure
(let [resp (api/api-get ctx "/users")]
  (api/api-response-status resp)       ;; => 200
  (api/api-response-status-text resp)  ;; => "OK"
  (api/api-response-url resp)          ;; => "https://api.example.com/users"
  (api/api-response-ok? resp)          ;; => true
  (api/api-response-headers resp)      ;; => {"content-type" "application/json" ...}
  (api/api-response-text resp)         ;; => "{\"users\":[...]}"
  (api/api-response-body resp)         ;; => #bytes[...]

  ;; Convert to map
  (api/api-response->map resp))
  ;; => {:status 200, :status-text "OK", :url "...", :ok? true, :headers {...}, :body "..."}
```

### Hooks

Request/response interceptors — composable, nestable:

```clojure
;; Request logging
(api/with-hooks
  {:on-request  (fn [method url opts] (println "→" method url) opts)
   :on-response (fn [method url resp] (println "←" method (api/api-response-status resp)) resp)}
  (api/api-get ctx "/users"))

;; Auth injection
(api/with-hooks
  {:on-request (fn [_ _ opts]
                 (assoc-in opts [:headers "Authorization"]
                   (str "Bearer " (get-token))))}
  (api/api-get ctx "/protected"))

;; Composable nesting
(api/with-hooks {:on-response (fn [_ _ resp] resp)}
  (api/with-hooks {:on-request (fn [_ _ opts] opts)}
    (api/api-get ctx "/users")))
```

### Retry

```clojure
;; Default: 3 attempts, exponential backoff, retry on 5xx
(api/retry #(api/api-get ctx "/flaky"))

;; Custom config
(api/retry #(api/api-get ctx "/flaky")
  {:max-attempts 5
   :delay-ms 1000
   :backoff :linear
   :retry-when (fn [r] (= 429 (:status (api/api-response->map r))))})

;; With macro
(api/with-retry {:max-attempts 3 :delay-ms 200}
  (api/api-post ctx "/endpoint" {:json {:action "process"}}))

;; Standalone request (no context setup)
(api/request! pw :get "https://api.example.com/health")
(api/request! pw :post "https://api.example.com/users"
  {:data "{\"name\":\"Alice\"}"
   :headers {"Content-Type" "application/json"}})
```

## Allure Test Reporting

Integrates with [Lazytest](https://github.com/noahtheduke/lazytest) for comprehensive test reports using [Allure](https://allurereport.org/). Compatible with Allure 2+ result format. The built-in reporter generates the full HTML report automatically using Allure 3 (pinned to 3.2.0 via npx) with an embedded local Playwright trace viewer — no external `allure generate` step needed.

> **[View live test report](https://blockether.github.io/spel/)** — with embedded Playwright traces.

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

### Test Example

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.com"
    {:context [with-playwright with-browser with-page]}

    (it "navigates and asserts"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*)))
      (expect (nil? (assert/has-text
                      (assert/assert-that (page/locator *page* "h1"))
                      "Example Domain"))))))
```

### Test Fixtures

| Fixture | Binds | Scope |
|---------|-------|-------|
| `with-playwright` | `*pw*` | Shared Playwright instance |
| `with-browser` | `*browser*` | Shared headless Chromium browser |
| `with-page` | `*page*` | Fresh page per `it` block (auto-cleanup, auto-tracing with Allure) |
| `with-traced-page` | `*page*` | Like `with-page` but always enables tracing/HAR |
| `with-test-server` | `*test-server-url*` | Local HTTP test server |

### Metadata

```clojure
(require '[com.blockether.spel.allure :as allure])

;; Labels
(allure/epic "E2E Testing")
(allure/feature "Authentication")
(allure/story "Login Flow")
(allure/severity :critical)          ;; :blocker :critical :normal :minor :trivial
(allure/owner "team@example.com")
(allure/tag "smoke")

;; Description and links
(allure/description "Tests the complete login flow")
(allure/link "Docs" "https://example.com/docs")
(allure/issue "BUG-123" "https://github.com/example/issues/123")
(allure/tms "TC-456" "https://tms.example.com/456")

;; Parameters
(allure/parameter "browser" "chromium")
```

### Steps

```clojure
;; Lambda step with body
(allure/step "Navigate to login page"
  (page/navigate pg "https://example.com/login"))

;; Nested steps
(allure/step "Login flow"
  (allure/step "Enter credentials"
    (locator/fill (page/locator pg "#user") "admin")
    (locator/fill (page/locator pg "#pass") "secret"))
  (allure/step "Submit"
    (locator/click (page/locator pg "#submit"))))

;; UI step (auto-captures before/after screenshots, requires *page* binding)
(allure/ui-step "Fill login form"
  (locator/fill username-input "admin")
  (locator/fill password-input "secret")
  (locator/click submit-btn))

;; API step (auto-attaches response details: status, headers, body)
(allure/api-step "Create user"
  (api/api-post ctx "/users" {:json {:name "Alice" :age 30}}))
```

### Attachments

```clojure
;; String attachment
(allure/attach "Request Body" "{\"key\":\"value\"}" "application/json")

;; Binary attachment
(allure/attach-bytes "Screenshot" (page/screenshot pg) "image/png")

;; Convenience screenshot
(allure/screenshot pg "After navigation")

;; Attach API response
(allure/attach-api-response! resp)
```

### Running Tests

```bash
# Run with Allure reporter (generates JSON + HTML report + embedded trace viewer automatically)
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# Or use Make targets
make test-allure     # run tests + generate report
make allure          # run tests + generate + open in browser
```

The reporter handles the full pipeline:
1. Writes Allure JSON results to `allure-results/` (Allure 2+ compatible format)
2. Resolves Allure 3 CLI via `npx allure@3.2.0` (no manual install needed)
3. Generates HTML report to `allure-report/` using `allure awesome`
4. Embeds a local Playwright trace viewer (no dependency on `trace.playwright.dev`)
5. Patches report JS to load traces from `./trace-viewer/` and pre-registers the Service Worker for instant loading
6. Manages run history via `.allure-history.jsonl` (Allure 3 JSONL mechanism, default: last 10 builds)

#### History Configuration

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `lazytest.allure.output` | `LAZYTEST_ALLURE_OUTPUT` | `allure-results` | Results output directory |
| `lazytest.allure.report` | `LAZYTEST_ALLURE_REPORT` | `allure-report` | HTML report directory |
| `lazytest.allure.history-limit` | `LAZYTEST_ALLURE_HISTORY_LIMIT` | `10` | Max builds retained in history |
| `lazytest.allure.report-name` | `LAZYTEST_ALLURE_REPORT_NAME` | _(auto: "spel vX.Y.Z")_ | Report title (shown in header and history). Auto-includes version when not set. |
| `lazytest.allure.version` | `LAZYTEST_ALLURE_VERSION` | _(SPEL_VERSION)_ | Project version shown in build history and environment. Falls back to `SPEL_VERSION` resource. |
| `lazytest.allure.logo` | `LAZYTEST_ALLURE_LOGO` | _(none)_ | Path to logo image for report header |

```bash
# Keep last 20 builds in history
clojure -J-Dlazytest.allure.history-limit=20 -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure

# Tag build with custom version
LAZYTEST_ALLURE_VERSION=1.2.3 clojure -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure
```

### Trace Viewer Integration

When using test fixtures with Allure reporter active, Playwright tracing is automatically enabled:

- Screenshots captured on every action
- DOM snapshots included
- Network activity recorded
- Sources captured
- HAR file generated

Trace and HAR files are automatically attached to test results (MIME type `application/vnd.allure.playwright-trace`) and viewable directly in the Allure report via an embedded local trace viewer — no external service dependency. The report JS is patched to load traces from `./trace-viewer/` instead of `trace.playwright.dev`, and a Service Worker is pre-registered for instant loading.

### Merging Reports

When running multiple test suites independently (e.g. Lazytest + clojure.test, or parallel CI jobs), each suite writes to its own `allure-results` directory. Use `merge-reports` to combine them into a single unified report:

```bash
# Merge two result directories and generate combined HTML report
spel merge-reports results-lazytest results-ct

# Merge with custom output directory
spel merge-reports results-* --output combined-results

# Merge without generating HTML report (results only)
spel merge-reports dir1 dir2 dir3 --no-report

# Merge into existing directory without cleaning first
spel merge-reports dir1 dir2 --no-clean

# Custom report directory
spel merge-reports dir1 dir2 --output merged --report-dir my-report
```

| Option | Default | Description |
|--------|---------|-------------|
| `--output DIR` | `allure-results` | Output directory for merged results |
| `--report-dir DIR` | `allure-report` | HTML report output directory |
| `--no-report` | _(generate)_ | Skip HTML report generation |
| `--no-clean` | _(clean)_ | Don't clean output directory before merging |

The merge copies all UUID-prefixed result and attachment files directly (no collision risk). Supplementary files are merged intelligently: `environment.properties` combines all keys (last value wins for duplicates), `categories.json` is deduplicated by name.

#### Library API

```clojure
(require '[com.blockether.spel.allure-reporter :as reporter])

;; Merge and generate report
(reporter/merge-results!
  ["results-lazytest" "results-ct"]
  {:output-dir "allure-results"
   :report-dir "allure-report"})

;; Merge without report
(reporter/merge-results!
  ["dir1" "dir2" "dir3"]
  {:report false})

;; Append to existing results
(reporter/merge-results!
  ["new-results"]
  {:output-dir "existing-results"
   :clean false})
```

## Video Recording

Record browser sessions as video files (WebM). Useful for debugging test failures and CI artifacts.

### Library API

```clojure
;; Create context with video recording
(def ctx (core/new-context browser {:record-video-dir "videos"
                                     :record-video-size {:width 1280 :height 720}}))
(def page (core/new-page-from-context ctx))

;; Do actions...
(page/navigate page "https://example.com")

;; Get video path (available while recording)
(core/video-path page) ;=> "videos/abc123.webm"

;; Close context to finalize video
(core/close-page! page)
(core/close-context! ctx)

;; Optionally save a copy
(core/video-save-as! page "artifacts/test-recording.webm")
```

### SCI / Eval Mode

```clojure
;; Start video recording (creates new context with video enabled)
(spel/start-video-recording {:video-dir "videos"})

;; Do actions...
(spel/goto "https://example.com")

;; Check video path
(spel/video-path) ;=> "videos/abc123.webm"

;; Stop recording and get video path
(spel/finish-video-recording) ;=> {:status "stopped" :video-path "videos/abc123.webm"}
```

### Test Fixtures

```clojure
;; Use with-video-page for automatic video recording in tests
(defdescribe my-test
  (describe "with video"
    {:context [with-playwright with-browser with-video-page]}
    (it "records video"
      ;; Video is automatically attached to Allure report
      (page/navigate *page* "https://example.com"))))

;; Or with custom options
(defdescribe my-test
  (describe "with video"
    {:context [with-playwright with-browser
               (with-video-page-opts {:video-dir "my-videos"
                                       :video-size {:width 1920 :height 1080}})]}
    (it "records HD video"
      (page/navigate *page* "https://example.com"))))
```

Videos are automatically attached to Allure reports when using `with-video-page` fixtures.

## Test Generation (Codegen)

Record browser sessions and transform to idiomatic Clojure code.

### Recording

```bash
# Record browser session (opens interactive Playwright Codegen recorder)
spel codegen record -o recording.jsonl https://example.com

# Transform JSONL to Clojure test
spel codegen recording.jsonl > my_test.clj
spel codegen --format=script recording.jsonl
spel codegen --format=body recording.jsonl
```

### Library API

```clojure
(require '[com.blockether.spel.codegen :as codegen])

;; Read file and transform
(codegen/jsonl->clojure "recording.jsonl")

;; With format option
(codegen/jsonl->clojure "recording.jsonl" {:format :test})   ;; Full Lazytest test
(codegen/jsonl->clojure "recording.jsonl" {:format :script}) ;; Standalone script
(codegen/jsonl->clojure "recording.jsonl" {:format :body})   ;; Just actions

;; From string
(codegen/jsonl-str->clojure jsonl-string)
(codegen/jsonl-str->clojure jsonl-string {:format :script})
```

### Output Formats

| Format | Output |
|--------|--------|
| `:test` (default) | Full Lazytest file with `defdescribe`/`it`, lifecycle macros |
| `:script` | Standalone script with `require`/`import` + `with-playwright` chain |
| `:body` | Just action lines for pasting into existing code |

### Supported Actions

| Action | Codegen Output |
|--------|---------------|
| `navigate` | `(page/navigate pg "url")` |
| `click` | `(locator/click loc)` with modifiers, button, position |
| `click` (dblclick) | `(locator/dblclick loc)` when clickCount=2 |
| `fill` | `(locator/fill loc "text")` |
| `press` | `(locator/press loc "key")` with modifier combos |
| `hover` | `(locator/hover loc)` |
| `check`/`uncheck` | `(locator/check loc)` / `(locator/uncheck loc)` |
| `select` | `(locator/select-option loc "value")` |
| `setInputFiles` | `(locator/set-input-files! loc "path")` |
| `assertText` | `(assert/has-text (assert/assert-that loc) "text")` |
| `assertChecked` | `(assert/is-checked (assert/assert-that loc))` |
| `assertVisible` | `(assert/is-visible (assert/assert-that loc))` |
| `assertValue` | `(assert/has-value (assert/assert-that loc) "val")` |

Signals: `dialog`, `popup`, `download` — handled automatically in generated code.

## Agent Scaffolding

Scaffold E2E testing agents for OpenCode, Claude Code, or VS Code:

```bash
spel init-agents                              # OpenCode (default)
spel init-agents --loop=claude                # Claude Code
spel init-agents --loop=vscode                # VS Code / Copilot
spel init-agents --flavour=clojure-test       # clojure.test instead of Lazytest
spel init-agents --no-tests                   # SKILL only (interactive dev)
```

### Generated Files

| File | Purpose |
|------|---------|
| `agents/spel-test-planner` | Explores app, writes structured test plans |
| `agents/spel-test-generator` | Reads test plans, generates Clojure test code |
| `agents/spel-test-healer` | Runs failing tests, diagnoses issues, applies fixes |
| `prompts/spel-test-workflow` | Orchestrator: plan → generate → heal cycle |
| `skills/spel/SKILL.md` | API reference for agents |

### Options

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

### Oh My OpenCode

If you use [Oh My OpenCode](https://github.com/code-yeongyu/oh-my-opencode), disable the built-in `playwright` skill and use the scaffolded `spel` skill instead. The built-in skill is a generic MCP wrapper with no knowledge of spel's Clojure API — the `spel` skill includes the full API reference (locators, assertions, snapshots, CLI, codegen) so agents generate idiomatic code out of the box.

Add to your project's `.opencode/oh-my-opencode.json`:

```json
{
  "disabled_skills": ["playwright"]
}
```

After scaffolding with `spel init-agents`, the `spel` skill is automatically available at `.opencode/skills/spel/SKILL.md`. Agents and task delegations should use `load_skills=["spel"]` for any browser-related work.


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
