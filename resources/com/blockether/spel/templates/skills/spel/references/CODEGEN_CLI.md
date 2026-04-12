# Codegen & CLI reference

## Codegen — record + transform

```bash
# 1. Record session (opens interactive Playwright recorder, JSONL target by default)
spel codegen record -o recording.jsonl https://example.org

# 2. Transform JSONL → Clojure
spel codegen recording.jsonl > my_test.clj
spel codegen --format=script recording.jsonl
spel codegen --format=body   recording.jsonl
```

| Format | Output |
|--------|--------|
| `:test` (default) | Full test file with `defdescribe`/`it`/`expect` + `core/with-testing-page` |
| `:script` | Standalone script with `require`/`import` + `with-testing-page` |
| `:body` | Just action lines — paste into existing code |

### Action mapping

| Action | Codegen output |
|--------|----------------|
| `navigate` | `(page/navigate pg "url")` |
| `click` | `(locator/click loc)` (+ modifiers, button, position) |
| `click` (dblclick) | `(locator/dblclick loc)` when `clickCount=2` |
| `click` (N>2) | `(locator/click loc {:click-count N})` |
| `fill` | `(locator/fill loc "text")` |
| `press` | `(locator/press loc "key")` (+ modifier combos) |
| `hover` | `(locator/hover loc)` (+ optional position) |
| `check` / `uncheck` | `(locator/check loc)` / `(locator/uncheck loc)` |
| `select` | `(locator/select-option loc "value")` |
| `setInputFiles` | `(locator/set-input-files! loc "path")` or vector |
| `assertText` | `(assert/has-text (assert/assert-that loc) "text")` |
| `assertChecked` | `(assert/is-checked (assert/assert-that loc))` |
| `assertVisible` | `(assert/is-visible (assert/assert-that loc))` |
| `assertValue` | `(assert/has-value (assert/assert-that loc) "val")` |
| `assertSnapshot` | `(assert/matches-aria-snapshot (assert/assert-that loc) "snap")` |

### Signals

| Signal | Pattern |
|--------|---------|
| `dialog` | `(page/on-dialog pg (fn [dlg] (.dismiss dlg)))` **before** action |
| `popup` | `(let [popup-pg (page/wait-for-popup pg #(action))] ...)` **around** action |
| `download` | `(let [dl (page/wait-for-download pg #(action))] ...)` **around** action |

### Frame navigation

A `framePath` array generates chained `.contentFrame()` calls:

```clojure
;; framePath: ["iframe.outer", "iframe.inner"]
(let [fl0 (.contentFrame (page/locator pg "iframe.outer"))
      fl1 (.contentFrame (.locator fl0 "iframe.inner"))]
  (locator/click (.locator fl1 "button")))
```

### Hard errors

Codegen exits immediately on unknown action types, unknown signal types, unrecognized locator formats, or missing locator/selector data. CLI: prints the full action + `System/exit 1`. Library: throws `ex-info` with `:codegen/error` and `:codegen/action`.

## CLI

Wraps Playwright CLI commands via the `spel` native binary.

> **For multi-step automation, prefer `eval-sci`.** Standalone commands (`spel open`, `spel click @e2yrjz`) are good for one-offs; anything longer should be `spel eval-sci '<code>'` or `spel eval-sci script.clj`. LLM-generated scripts: `echo '(code)' | spel eval-sci --stdin`.

> `spel install` wraps `com.microsoft.playwright.CLI` — same Node.js Playwright CLI that `npx playwright` uses. Driver version is pinned to Playwright Java (1.58.0), so browser versions always match.

```bash
spel install                        # install browsers (Chromium default)
spel install --with-deps chromium   # + system dependencies
spel codegen URL                    # record interactions
spel open URL                       # open browser
spel screenshot URL                 # take screenshot
```

### Corporate proxy / custom CA certs

Behind an SSL-inspecting proxy, `spel install` may fail with "PKIX path building failed". Supply CA certs via env vars:

| Env var | Format | On missing file | Description |
|---------|--------|-----------------|-------------|
| `SPEL_CA_BUNDLE` | PEM | Error | Extra CA certs (merged with defaults) |
| `NODE_EXTRA_CA_CERTS` | PEM | Warning, skips | Shared with Node.js subprocess |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Error | Truststore (merged with defaults) |
| `SPEL_TRUSTSTORE_TYPE` | string | — | Default: JKS |
| `SPEL_TRUSTSTORE_PASSWORD` | string | — | Default: empty |

```bash
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem
spel install --with-deps

# Or reuse Node.js var (covers driver + browser downloads)
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
spel install --with-deps
```

Public CDN certs still work — these merge with defaults.

### Playwright tools

```bash
# Inspector — headed browser with the Inspector panel
spel inspector
spel inspector https://example.org
spel inspector -b firefox https://example.org
spel inspector --device "iPhone 14" https://example.org

# Trace Viewer
spel show-trace                           # blank
spel show-trace trace.zip
spel show-trace --port 8080 trace.zip
```

Inspector options (all Playwright `open` flags supported):

| Flag | Purpose |
|------|---------|
| `-b, --browser <type>` | `cr`/`chromium`, `ff`/`firefox`, `wk`/`webkit` (default chromium) |
| `--channel <channel>` | Chromium channel (`chrome`, `chrome-beta`, `msedge-dev`, …) |
| `--device <name>` | Emulate device |
| `--color-scheme <scheme>` | `light` / `dark` |
| `--geolocation <lat,lng>` | Geo coords |
| `--lang <locale>` / `--timezone <tz>` | Locale / tz |
| `--viewport-size <w,h>` / `--user-agent <ua>` | Viewport / UA |
| `--proxy-server <url>` | Proxy |
| `--ignore-https-errors` | Skip HTTPS cert errors |
| `--load-state <file>` / `--save-state <file>` | Storage state (aliases: `--load-storage`, `--save-storage`) |
| `--save-har <file>` / `--timeout <ms>` | HAR / action timeout |

## Page exploration (CLI)

### Basic workflow

```bash
spel open https://example.org
spel snapshot                # full a11y tree
spel screenshot page.png
```

### Snapshot command

```bash
spel snapshot                # full a11y tree
spel snapshot -i             # interactive only
spel snapshot -i -c          # compact
spel snapshot -i -c -d 3     # depth limit
spel snapshot -i -C          # include cursor/pointer elements
spel snapshot -s "#main"     # scoped to selector
```

Output:

```
- heading "Example Domain" [@e2yrjz] [level=1]
- link    "More information..." [@e9mter]
- button  "Submit" [@e6t2x4]
```

### Get page info

```bash
spel get url
spel get title
spel get text  @e2yrjz
spel get html  @e2yrjz
spel get value @e9mter
spel get attr  @e2yrjz href
spel get count ".items"
spel get box   @e2yrjz       # {x, y, width, height}
```

### Check element state

```bash
spel is visible @e2yrjz
spel is enabled @e2yrjz
spel is checked @e6t2x4
```

### Find + act (semantic locators)

```bash
spel find role   button click
spel find role   button click --name "Submit"
spel find text   "Login" click
spel find label  "Email"  fill "test@example.org"
spel find first  ".item"  click
spel find last   ".item"  click
spel find nth 2  ".item"  click
```

### Visual

```bash
spel screenshot              # stdout (base64)
spel screenshot shot.png
spel screenshot -f full.png  # full page
spel pdf page.pdf            # Chromium only
spel highlight @e2yrjz
```

### Stitching

```bash
spel stitch s1.png s2.png s3.png
spel stitch s1.png s2.png -o full-page.png
spel stitch s1.png s2.png s3.png --overlap 50 -o full.png
```

Programmatic (SCI):

```clojure
(stitch/stitch-vertical ["s1.png" "s2.png" "s3.png"] "output.png")
(stitch/stitch-vertical-overlap ["s1.png" "s2.png"] "output.png" {:overlap-px 50})
(stitch/read-image "screenshot.png")
```

### Network

```bash
spel network requests                     # all
spel network requests --type fetch        # filter by type
spel network requests --method POST       # by method
spel network requests --status 2          # by status prefix (2xx, 4xx, …)
spel network requests --filter "/api"     # URL regex
spel network clear
```

### JS eval

```bash
spel eval "document.title"
spel eval "document.querySelector('h1').textContent"
spel eval "JSON.stringify([...document.querySelectorAll('a')].map(a => ({text:a.textContent, href:a.href})))" -b
```

### Console / errors

Auto-captured from the moment the page opens; no `start` needed.

```bash
spel console           spel console clear
spel errors            spel errors  clear
```

### Complete example

```bash
spel open https://example.org
spel snapshot -i
spel screenshot initial.png
spel get title
spel get url
spel get text @e9mter
spel is visible @e6t2x4
spel click @e9mter
spel snapshot -i
spel network requests
spel close
```

## Native image CLI

Library ships a GraalVM native-image binary for instant-start automation.

```bash
clojure -T:build uberjar
clojure -T:build native-image
./target/spel install
```

### Global flags

| Flag | Default | Purpose |
|------|---------|---------|
| `--timeout <ms>` | `30000` | Playwright action timeout |
| `--session <name>` | `default` | Named browser session |
| `--json` | off | JSON output |
| `--debug` | off | Debug output |
| `--autoclose` | off | Close daemon after `eval-sci` |
| `--interactive` | off | Headed browser for `eval-sci` |
| `--load-state <path>` | — | Restore storage JSON (alias `--storage-state`) |
| `--profile <path>` | — | Persistent Chrome user-data-dir |
| `--executable-path <path>` | — | Custom binary |
| `--user-agent <ua>` / `--proxy <url>` / `--proxy-bypass <domains>` | — | — |
| `--headers <json>` | — | Extra HTTP headers |
| `--args <args>` | — | Comma-separated browser args |
| `--cdp <url>` | — | Attach via CDP endpoint |
| `--ignore-https-errors` | off | — |
| `--allow-file-access` | off | Allow `file://` |

### CI assemble (`spel ci-assemble`)

Assembles Allure report sites for CI/CD. Replaces shell/Python scripts in CI workflows.

```bash
spel ci-assemble \
  --site-dir=gh-pages-site \
  --run=123 \
  --commit-sha=abc123def \
  --commit-msg="feat: add feature" \
  --report-url=https://example.github.io/repo/123/ \
  --test-passed=100 --test-failed=2
```

| Flag | Env | Purpose |
|------|-----|---------|
| `--site-dir DIR` | `SPEL_CI_SITE_DIR` | Site directory (default `gh-pages-site`) |
| `--run NUMBER` | `RUN_NUMBER` | CI run number (required) |
| `--commit-sha` / `--commit-msg` / `--commit-ts` | `COMMIT_SHA` / `COMMIT_MSG` / `COMMIT_TS` | Git info |
| `--tests-passed BOOL` | `TEST_PASSED` | Whether tests passed |
| `--repo-url` / `--run-url` | `REPO_URL` / `RUN_URL` | Repo / CI URLs |
| `--version` / `--version-badge` | `VERSION` / `VERSION_BADGE` | Version string / badge (`release` / `candidate`) |
| `--test-passed N` / `--test-failed N` / `--test-broken N` / `--test-skipped N` | `TEST_COUNTS_*` | Test counts |
| `--history-file FILE` | `ALLURE_HISTORY_FILE` | Default `.allure-history.jsonl` |
| `--report-url` | `REPORT_URL` | For history patching |
| `--logo-file` / `--index-file` | `LOGO_FILE` / `INDEX_FILE` | Assets |
| `--title` / `--subtitle` | `LANDING_TITLE` / `LANDING_SUBTITLE` | Injected into `index.html` |

Operations (in order): patch `.allure-history.jsonl` → generate `builds.json` + `builds-meta.json` + `badge.json` → patch `index.html`.

**In-progress tracking**: `register-build-start!` shows a yellow animated badge before tests finish; `finalize-build!` updates to passed/failed. Flow: register → deploy pages (yellow) → run tests → finalize → regenerate metadata → re-deploy.

```clojure
(ci/register-build-start! {:site-dir "gh-pages-site" :run-number "123" :commit-sha "abc…"
                           :commit-msg "feat: add feature" :commit-author "dev"
                           :repo-url "https://github.com/org/repo"
                           :run-url "https://github.com/org/repo/actions/runs/456"})

(ci/finalize-build! {:site-dir "gh-pages-site" :run-number "123" :passed true})
```

In CI, call via JVM (Clojure CLI) rather than native:

```clojure
clojure -M -e "
  (require '[com.blockether.spel.ci :as ci])
  (ci/generate-builds-metadata! {:site-dir \"gh-pages-site\" ...})
  (ci/patch-index-html!         {:index-file \"gh-pages-site/index.html\" ...})"
```

## API discovery in `eval-sci`

| Call | Purpose |
|------|---------|
| `(spel/help)` | List namespaces + counts |
| `(spel/help "spel")` | List every fn in a namespace |
| `(spel/help "click")` | Search by keyword |
| `(spel/help "spel/click")` | One fn's signature + doc |
| `(spel/source "spel/click")` | Show SCI wrapper + library target |
| `(spel/source "goto")` | By bare name; lists candidates on ambiguity |

Prefer these over reading SKILL.md while writing `eval-sci`.

## CLI entry points

| Command | Purpose |
|---------|---------|
| `spel <command>` | Browser automation CLI (100+ commands) |
| `spel codegen` | Record + transform sessions to Clojure |
| `spel init-agents` | Scaffold E2E agents (`--loop=opencode|claude`) |
