# Codegen & CLI Reference

## Codegen - Record & Transform

Record browser sessions and transform to idiomatic Clojure.

### Workflow

```bash
# 1. Record browser session (opens interactive Playwright Codegen recorder)
# Defaults to --target=jsonl for the spel transform pipeline
spel codegen record -o recording.jsonl https://example.org

# 2. Transform JSONL to Clojure test
spel codegen recording.jsonl > my_test.clj
spel codegen --format=script recording.jsonl
spel codegen --format=body recording.jsonl
```

### Formats

| Format | Output |
|--------|--------|
| `:test` (default) | Full test file with `defdescribe`/`it`/`expect` (from `spel.allure`) using `core/with-testing-page` |
| `:script` | Standalone script with `require`/`import` + `with-testing-page` |
| `:body` | Just action lines for pasting into existing code |

### Supported Actions

| Action | Codegen Output |
|--------|---------------|
| `navigate` | `(page/navigate pg "url")` |
| `click` | `(locator/click loc)` with modifiers, button, position |
| `click` (dblclick) | `(locator/dblclick loc)` when clickCount=2 |
| `click` (N>2) | `(locator/click loc {:click-count N})` |
| `fill` | `(locator/fill loc "text")` |
| `press` | `(locator/press loc "key")` with modifier combos |
| `hover` | `(locator/hover loc)` with optional position |
| `check`/`uncheck` | `(locator/check loc)` / `(locator/uncheck loc)` |
| `select` | `(locator/select-option loc "value")` |
| `setInputFiles` | `(locator/set-input-files! loc "path")` or vector |
| `assertText` | `(assert/has-text (assert/assert-that loc) "text")` |
| `assertChecked` | `(assert/is-checked (assert/assert-that loc))` |
| `assertVisible` | `(assert/is-visible (assert/assert-that loc))` |
| `assertValue` | `(assert/has-value (assert/assert-that loc) "val")` |
| `assertSnapshot` | `(assert/matches-aria-snapshot (assert/assert-that loc) "snapshot")` |

### Signal Handling

| Signal | Codegen Pattern |
|--------|----------------|
| `dialog` | `(page/on-dialog pg (fn [dialog] (.dismiss dialog)))` BEFORE action |
| `popup` | `(let [popup-pg (page/wait-for-popup pg #(action))] ...)` AROUND action |
| `download` | `(let [download (page/wait-for-download pg #(action))] ...)` AROUND action |

### Frame Navigation in Codegen

`framePath` array generates chained `.contentFrame()` calls:

```clojure
;; framePath: ["iframe.outer", "iframe.inner"]
(let [fl0 (.contentFrame (page/locator pg "iframe.outer"))
      fl1 (.contentFrame (.locator fl0 "iframe.inner"))]
  (locator/click (.locator fl1 "button")))
```

### Hard Errors

Codegen dies immediately on:
- Unknown action types
- Unknown signal types
- Unrecognized locator formats
- Missing locator/selector data

In CLI mode: prints full action data + `System/exit 1`.
In library mode: throws `ex-info` with `:codegen/error` and `:codegen/action`.

---

## CLI

Wraps Playwright CLI commands via the `spel` native binary.

> **Prefer `eval-sci` for multi-step automation.** Standalone CLI commands (`spel open`, `spel click @e2yrjz`, etc.) are useful for quick one-off actions, but for anything beyond a single command, use `spel eval-sci '<clojure-code>'` or `spel eval-sci script.clj`. This gives you full Clojure composition — loops, conditionals, variables, error handling — in a single persistent browser session. LLM-generated scripts can be piped via `echo '(code)' | spel eval-sci --stdin`.

> **Note**: `spel install` delegates to `com.microsoft.playwright.CLI`, which is a thin shim that spawns the same Node.js Playwright CLI that `npx playwright` uses. The driver version is pinned to the Playwright Java dependency (1.58.0), so browser versions always match.

```bash
spel install                        # Install browsers (Chromium by default)
spel install --with-deps chromium   # Install with system dependencies
spel codegen URL                    # Record interactions
spel open URL                       # Open browser
spel screenshot URL                 # Take screenshot
```

#### Corporate Proxy / Custom CA Certificates

Behind a corporate SSL-inspecting proxy, `spel install` may fail with "PKIX path building failed". Use these env vars to add corporate CA certs:

| Env Var | Format | On missing file | Description |
|---------|--------|----------------|-------------|
| `SPEL_CA_BUNDLE` | PEM file | Error | Extra CA certs (merged with defaults) |
| `NODE_EXTRA_CA_CERTS` | PEM file | Warning, skips | Shared with Node.js subprocess |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Error | Truststore (merged with defaults) |
| `SPEL_TRUSTSTORE_TYPE` | String | — | Default: JKS |
| `SPEL_TRUSTSTORE_PASSWORD` | String | — | Default: empty |

```bash
# Simplest — PEM file with corporate CA
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem
spel install --with-deps

# Or reuse Node.js var — covers both driver + browser downloads
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
spel install --with-deps
```

All options merge with built-in defaults — public CDN certs continue to work.

### Playwright Tools

Launch Playwright's built-in visual tools directly from `spel`:

```bash
# Inspector — opens a headed browser with the Playwright Inspector panel.
# Use to explore the page, pick locators, and record actions interactively.
spel inspector                                      # Open Inspector (blank page)
spel inspector https://example.org                  # Open Inspector on URL
spel inspector -b firefox https://example.org       # Use Firefox
spel inspector --device "iPhone 14" https://example.org  # Emulate device

# Trace Viewer — opens the Playwright Trace Viewer to inspect recorded traces.
# Traces are created via `spel trace start` / `spel trace stop` or automatically
# by test fixtures with Allure reporter active.
spel show-trace                     # Open Trace Viewer (blank)
spel show-trace trace.zip           # Open specific trace file
spel show-trace --port 8080 trace.zip  # Serve on specific port
```

**Inspector options** (all Playwright `open` flags are supported):

| Flag | Description |
|------|-------------|
| `-b, --browser <type>` | Browser engine: `cr`/`chromium`, `ff`/`firefox`, `wk`/`webkit` (default: chromium) |
| `--channel <channel>` | Chromium channel: `chrome`, `chrome-beta`, `msedge-dev`, etc. |
| `--device <name>` | Emulate device (e.g. `"iPhone 14"`, `"Pixel 7"`) |
| `--color-scheme <scheme>` | `light` or `dark` |
| `--geolocation <lat,lng>` | Geolocation coordinates |
| `--lang <locale>` | Language locale (e.g. `en-GB`) |
| `--timezone <tz>` | Timezone (e.g. `Europe/Rome`) |
| `--viewport-size <w,h>` | Viewport size (e.g. `1280,720`) |
| `--user-agent <ua>` | Custom user agent |
| `--proxy-server <url>` | Proxy server |
| `--ignore-https-errors` | Ignore HTTPS certificate errors |
| `--load-state <file>` | Load saved state (alias: `--load-storage`) |
| `--save-state <file>` | Save state on exit (alias: `--save-storage`) |
| `--save-har <file>` | Save HAR file on exit |
| `--timeout <ms>` | Action timeout in ms |

---

## Page Exploration (spel)

The `spel` CLI provides comprehensive page exploration capabilities without writing code.

### Basic Exploration Workflow

```bash
# 1. Navigate to a page
spel open https://example.org

# 2. Get accessibility snapshot with numbered refs (e1, e2, etc.)
spel snapshot

# 3. Take a screenshot for visual reference
spel screenshot page.png
```

### Snapshot Command

The primary exploration tool - returns an ARIA accessibility tree with numbered refs:

```bash
spel snapshot                           # Full accessibility tree
spel snapshot -i                        # Interactive elements only
spel snapshot -i -c                     # Compact format
spel snapshot -i -c -d 3               # Limit depth to 3 levels
spel snapshot -i -C                     # Include cursor/pointer elements
spel snapshot -s "#main"               # Scoped to CSS selector
```

**Output format:**
```
- heading "Example Domain" [@e2yrjz] [level=1]
- link "More information..." [@e9mter]
- button "Submit" [@e6t2x4]
```

### Get Page Information

```bash
spel get url                           # Current URL
spel get title                         # Page title
spel get text @e2yrjz                      # Text content of ref e2yrjz
spel get html @e2yrjz                      # Inner HTML
spel get value @e9mter                     # Input value
spel get attr @e2yrjz href                 # Attribute value
spel get count ".items"               # Count matching elements
spel get box @e2yrjz                       # Bounding box {x, y, width, height}
```

### Check Element State

```bash
spel is visible @e2yrjz                    # Check visibility
spel is enabled @e2yrjz                    # Check if enabled
spel is checked @e6t2x4                    # Check checkbox state
```

### Find Elements (Semantic Locators)

Find and interact in one command:

```bash
# Find by ARIA role
spel find role button click
spel find role button click --name "Submit"

# Find by text content
spel find text "Login" click

# Find by label
spel find label "Email" fill "test@example.org"

# Position-based
spel find first ".item" click
spel find last ".item" click
spel find nth 2 ".item" click
```

### Visual Exploration

```bash
spel screenshot                        # Screenshot to stdout (base64)
spel screenshot shot.png              # Save to file
spel screenshot -f full.png           # Full page screenshot
spel pdf page.pdf                     # Save as PDF (Chromium only)
spel highlight @e2yrjz                    # Highlight element visually
```

### Image Stitching

Stitch multiple screenshots vertically into one image. Useful for capturing full-page content from virtual-scroll pages.

```bash
# Basic stitch - combine screenshots vertically
spel stitch s1.png s2.png s3.png

# Custom output path
spel stitch s1.png s2.png -o full-page.png

# Overlap trimming - remove N pixels from top of each subsequent image
# (removes duplicate content from overlapping scroll captures)
spel stitch s1.png s2.png s3.png --overlap 50 -o full.png
```

Also available in SCI `eval-sci` mode:

```clojure
;; Stitch images programmatically
(stitch/stitch-vertical ["s1.png" "s2.png" "s3.png"] "output.png")

;; With overlap trimming (removes 50px from top of each image after first)
(stitch/stitch-vertical-overlap ["s1.png" "s2.png"] "output.png" {:overlap-px 50})

;; Read an image as BufferedImage for inspection
(stitch/read-image "screenshot.png")
```

### Network Exploration

```bash
spel network requests                  # View all captured requests
spel network requests --type fetch    # Filter by type (document, script, fetch, image, etc.)
spel network requests --method POST   # Filter by HTTP method
spel network requests --status 2      # Filter by status prefix (2=2xx, 4=4xx)
spel network requests --filter "/api" # Filter by URL regex
spel network clear                    # Clear captured requests
```

### JavaScript Evaluation

```bash
# Run JavaScript
spel eval "document.title"
spel eval "document.querySelector('h1').textContent"

# Base64-encoded result
spel eval "JSON.stringify([...document.querySelectorAll('a')].map(a => ({text: a.textContent, href: a.href})))" -b
```

### Console & Errors

Console messages and page errors are auto-captured from the moment a page opens. No `start` command needed.

```bash
spel console                           # View captured console messages
spel console clear                     # Clear captured messages

spel errors                            # View captured page errors
spel errors clear                      # Clear captured errors
```

### Complete Exploration Example

```bash
# Open page
spel open https://example.org

# Get initial snapshot
spel snapshot -i

# Take screenshot
spel screenshot initial.png

# Get page info
spel get title
spel get url

# Check specific element
spel get text @e9mter
spel is visible @e6t2x4

# Interact and re-snapshot
spel click @e9mter
spel snapshot -i

# View network activity
spel network requests

# Close browser when done
spel close
```

---

## Native Image CLI

The library includes a GraalVM native-image compiled binary for instant-start browser automation via CLI.

### Build & Run

```bash
# Build native binary
clojure -T:build uberjar
clojure -T:build native-image

# Install Playwright browsers
./target/spel install
```

### CLI Configuration

Global flags apply to all commands and modes:

| Flag | Default | Purpose |
|------|---------|---------|
| `--timeout <ms>` | `30000` | Playwright action timeout in milliseconds |
| `--session <name>` | `default` | Named browser session (isolates state between sessions) |
| `--json` | off | JSON output format (for agent/machine consumption) |
| `--debug` | off | Debug output |
| `--autoclose` | off | Close daemon after `eval-sci` completes |
| `--interactive` | off | Headed (visible) browser for `eval-sci` mode |
| `--load-state <path>` | - | Load browser state (cookies/localStorage JSON, alias: `--storage-state`) |
| `--profile <path>` | - | Chrome user data directory (persistent profile) |
| `--executable-path <path>` | - | Custom browser executable |
| `--user-agent <ua>` | - | Custom user agent string |
| `--proxy <url>` | - | Proxy server URL |
| `--proxy-bypass <domains>` | - | Proxy bypass domains |
| `--headers <json>` | - | Extra HTTP headers (JSON string) |
| `--args <args>` | - | Browser args (comma-separated) |
| `--cdp <url>` | - | Connect via Chrome DevTools Protocol endpoint |
| `--ignore-https-errors` | off | Ignore HTTPS certificate errors |
| `--allow-file-access` | off | Allow file:// access |

### CI Assemble (`spel ci-assemble`)

Assembles Allure report sites for CI/CD deployment. Replaces shell/Python scripts in CI workflows with a single Clojure command.

```bash
spel ci-assemble \
  --site-dir=gh-pages-site \
  --run=123 \
  --commit-sha=abc123def \
  --commit-msg="feat: add feature" \
  --report-url=https://example.github.io/repo/123/ \
  --test-passed=100 --test-failed=2
```

| Flag | Env Var | Purpose |
|------|---------|---------|
| `--site-dir DIR` | `SPEL_CI_SITE_DIR` | Site directory (default: `gh-pages-site`) |
| `--run NUMBER` | `RUN_NUMBER` | CI run number (required) |
| `--commit-sha SHA` | `COMMIT_SHA` | Git commit SHA |
| `--commit-msg MSG` | `COMMIT_MSG` | Commit message |
| `--commit-ts TS` | `COMMIT_TS` | Commit timestamp (ISO 8601) |
| `--tests-passed BOOL` | `TEST_PASSED` | Whether tests passed (`true`/`false`) |
| `--repo-url URL` | `REPO_URL` | Repository URL |
| `--run-url URL` | `RUN_URL` | CI run URL |
| `--version VER` | `VERSION` | Project version string |
| `--version-badge TYPE` | `VERSION_BADGE` | Badge type: `release` or `candidate` |
| `--test-passed N` | `TEST_COUNTS_PASSED` | Number of passed tests |
| `--test-failed N` | `TEST_COUNTS_FAILED` | Number of failed tests |
| `--test-broken N` | `TEST_COUNTS_BROKEN` | Number of broken tests |
| `--test-skipped N` | `TEST_COUNTS_SKIPPED` | Number of skipped tests |
| `--history-file FILE` | `ALLURE_HISTORY_FILE` | Allure history file (default: `.allure-history.jsonl`) |
| `--report-url URL` | `REPORT_URL` | Report URL for history patching |
| `--logo-file FILE` | `LOGO_FILE` | Logo SVG file path |
| `--index-file FILE` | `INDEX_FILE` | Index HTML file path |
| `--title TEXT` | `LANDING_TITLE` | Title to inject into index.html |
| `--subtitle TEXT` | `LANDING_SUBTITLE` | Subtitle to inject into index.html |

Operations performed (in order):
1. Patches `.allure-history.jsonl` with report URL and commit info (when `--report-url` set)
2. Generates `builds.json`, `builds-meta.json`, and `badge.json` (when site directory exists)
3. Patches `index.html` with logo and title placeholders (when `--index-file` set)

**In-Progress Build Tracking:**

The CI module supports tracking builds as "in progress" with a yellow animated badge on the landing page:

```clojure
;; At start of CI run — registers build with yellow "In Progress" badge
(ci/register-build-start!
  {:site-dir "gh-pages-site"
   :run-number "123"
   :commit-sha "abc123..."
   :commit-msg "feat: add feature"
   :commit-author "developer"
   :repo-url "https://github.com/org/repo"
   :run-url "https://github.com/org/repo/actions/runs/456"})

;; After tests complete — updates status to completed/failed
(ci/finalize-build!
  {:site-dir "gh-pages-site"
   :run-number "123"
   :passed true})
```

Flow: register → deploy pages (shows yellow badge) → run tests → finalize → regenerate metadata → re-deploy pages.

In CI workflows, call via JVM (Clojure CLI) rather than native binary:

```clojure
clojure -M -e "
  (require '[com.blockether.spel.ci :as ci])
  (ci/generate-builds-metadata! {:site-dir \"gh-pages-site\" ...})
  (ci/patch-index-html! {:index-file \"gh-pages-site/index.html\" ...})"
```

---

## API Discovery in `eval-sci` Mode

Use `spel/help` and `spel/source` to explore the eval API at runtime:

| Command | What it does |
|---------|-------------|
| `(spel/help)` | List all namespaces with function counts |
| `(spel/help "spel")` | List all functions in a namespace (table: name, arglists, description) |
| `(spel/help "click")` | Search across all namespaces by function name or description |
| `(spel/help "spel/click")` | Show details for a specific function (arglists, description, backing library function) |
| `(spel/source "spel/click")` | Show the SCI wrapper source code and which library function it delegates to |
| `(spel/source "goto")` | Search by bare name — shows source if unique match, lists candidates if multiple |

These are **canonical** way to discover and understand the eval API. Prefer `spel/help` over reading this SKILL file when working in `eval-sci` mode.

---

## CLI Entry Points

The `spel` binary is the primary CLI interface:

| Command | Purpose |
|---------|---------|
| `spel <command>` | Browser automation CLI (100+ commands) |
| `spel codegen` | Record and transform browser sessions to Clojure |
| `spel init-agents` | Scaffold E2E testing agents (`--loop=opencode\|claude\|vscode`) |
