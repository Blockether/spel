# Allure test reporting

Rich HTML reports with embedded Playwright traces, steps, labels, attachments,
build history.

## Labels — add metadata inside test bodies

```clojure
(require '[com.blockether.spel.allure :as allure])

(allure/epic "E2E Testing")
(allure/feature "Authentication")
(allure/story "Login Flow")
(allure/severity :critical)       ; :blocker :critical :normal :minor :trivial
(allure/owner "team@example.org")
(allure/tag "smoke")
(allure/description "Tests the complete login flow")
(allure/link "Docs" "https://example.org/docs")
(allure/issue "BUG-123" "https://github.com/example/issues/123")
(allure/tms "TC-456" "https://tms.example.org/456")
(allure/parameter "browser" "chromium")
```

| Label | Purpose |
|-------|---------|
| `epic` / `feature` / `story` | Three-level test grouping |
| `severity` | `:blocker` `:critical` `:normal` `:minor` `:trivial` |
| `owner` / `tag` | Ownership + freeform filters |
| `description` / `link` / `issue` / `tms` | External refs |
| `parameter` | Key-value for parametrized tests |

## Steps

```clojure
(allure/step "Login flow"
  (allure/step "Enter credentials"
    (locator/fill (page/locator pg "#user") "admin")
    (locator/fill (page/locator pg "#pass") "secret"))
  (allure/step "Submit"
    (locator/click (page/locator pg "#submit"))))

;; Options map — attach screenshots and/or HTTP exchange
(allure/step "Fill form"   {:screenshots? true} ...)
(allure/step "Create user" {:http?        true} (core/api-post ctx "/users" {...}))
(allure/step "Both"        {:screenshots? true :http? true} ...)

;; Convenience wrappers (equivalent to the options above)
(allure/ui-step  "Fill login form" ...)   ; = step + :screenshots? true
(allure/api-step "Create user"     ...)   ; = step + :http? true
```

## Attachments

```clojure
(allure/attach "Request Body" "{\"k\":\"v\"}" "application/json")
(allure/attach-bytes "Screenshot" (page/screenshot pg) "image/png")
(allure/screenshot pg "After navigation")              ; capture + attach PNG
(allure/attach-http-markdown! resp {})                 ; API response as MD
```

## Running tests with Allure

```bash
# Allure only
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# With JUnit alongside
clojure -M:test --output nested \
  --output com.blockether.spel.allure-reporter/allure \
  --output com.blockether.spel.junit-reporter/junit
```

### Reporter config

| Property | Env | Default |
|----------|-----|---------|
| `lazytest.allure.output` | `LAZYTEST_ALLURE_OUTPUT` | `allure-results` |
| `lazytest.allure.report` | `LAZYTEST_ALLURE_REPORT` | `allure-report` |
| `lazytest.allure.history-limit` | `LAZYTEST_ALLURE_HISTORY_LIMIT` | `10` |
| `lazytest.allure.report-name` | `LAZYTEST_ALLURE_REPORT_NAME` | auto `"spel vX.Y.Z"` |
| `lazytest.allure.version` | `LAZYTEST_ALLURE_VERSION` | SPEL_VERSION |
| `lazytest.allure.logo` | `LAZYTEST_ALLURE_LOGO` | — |
| `spel.allure.cwd` | `SPEL_ALLURE_CWD` | inherit |

Version appears in build history + `environment.properties` (`project.version`, `spel.version`).

### Serving

Report must be served via HTTP (not `file://`) — embedded trace viewer uses a Service Worker:

```bash
npx http-server allure-report -o -p 9999
```

## Alternative HTML report — `spel report`

Self-contained alt report (lighter than the official Allure HTML, still bundles
every result). Written to `<output-dir>/` with **three companion JSON files**
alongside `index.html`:

- `index.html` — rendered report
- `summary.json` — aggregate stats (see schema below)
- `report.json` — full array of every result (labels, steps, attachments, timing)
- `data/` — per-test detail files consumed by the HTML

### `summary.json` schema

```json
{
  "name":         "Report title",
  "stats":        {"total": N, "passed": N, "failed": N, "broken": N, "skipped": N, "unknown": N},
  "status":       "passed | failed | broken",
  "duration":     12345,                 // wall-clock sum across all tests (ms)
  "testDuration": {"count": N, "totalMs": …, "meanMs": …, "maxMs": …, "minMs": …},
  "http":         {"calls": N, "totalMs": …, "meanMs": …, "maxMs": …, "minMs": …},
  "httpCalls":    [{"test": "test-name",
                    "name": "[API] GET /users",
                    "status": "passed",
                    "durationMs": 40,
                    "startedAt":  1700000001010,
                    "attachment": "data/attachments/abc-HTTP.md"}],
  "logs":         [{"test": "test-name",
                    "name": "server-stderr",
                    "type": "text/plain",
                    "path": "data/attachments/log-123.txt",
                    "size": 4096}],
  "errors":       [{"name": "test-name",
                    "status": "failed",
                    "message": "expected 200 got 500",
                    "trace":   "<stacktrace>",
                    "durationMs": 800}],
  "passRate":     100.0,
  "createdAt":    1700000000000
}
```

- **`testDuration`** — per-test wall-clock stats (count, total, mean, max, min in ms).
- **`http`** — HTTP-call stats collected from every step produced by `api-step` / `step … {:http? true}` / `attach-http-markdown!`. Counts calls + total / mean / max / min duration in ms. Zeroed when no HTTP exchanges were captured.
- **`httpCalls`** — one entry per HTTP call: `test`, `name` (keeps the `[API]` / `[UI+API]` prefix), `status`, `durationMs`, `startedAt`, `attachment` (relative path to the captured HTTP markdown exchange — full request/response/headers/body live there; `summary.json` stays small, callers load on demand). Empty array when no calls were captured.
- **`logs`** — log-like attachments (`text/plain` / `application/json` / `text/x-log`) flattened across every result + step. Each entry: `test`, `name`, `type`, `path`, `size` (bytes, when the attachment file exists on disk). Captures any `(allure/attach …)` call with a log-shaped MIME type — stdout/stderr, console dumps, structured event logs. The HTTP markdown exchange is deliberately excluded (it's already in `httpCalls[].attachment`).
- **`errors`** — one entry per failed/broken test with `name`, `status`, `message`, `trace` (when present), and per-test `durationMs`.

### Modes

```bash
# Standard: read allure-results directory
spel report --results-dir allure-results --output-dir block-report

# Single-run / Lambda: read one JSON file of result maps, no directory needed
spel report --from-json results.json --output-dir my-report --title "Lambda Run"
```

`--from-json` takes precedence over `--results-dir` when both are passed.

### Flags

| Flag | Purpose |
|------|---------|
| `--results-dir DIR` | Allure results dir (default `allure-results`) |
| `--from-json FILE` | JSON array of result maps (single-run / lambda / SCI) |
| `--output-dir DIR` | Output dir (default `block-report`) |
| `--title TEXT` | `<h1>` report title |
| `--kicker TEXT` | Mono heading above the title |
| `--subtitle TEXT` | Subtitle line |
| `--logo SRC` | Path, `data:`, `http(s):`, or inline `<svg>` |
| `--logo-alt TEXT` | Image alt text |
| `--description TEXT` | Description block (plain → escaped; `<…>` → sanitized HTML) |
| `--custom-css CSS` / `--custom-css-file FILE` | Extra CSS |
| `--build-id ID` | Build/run id shown in header |
| `--build-date VALUE` | Epoch-ms or ISO-8601 |
| `--build-url URL` | Link to CI run |

Every branding/metadata flag also maps to an `environment.properties` key
(`report.*`, `build.*`) — see the `generate!` docstring in
`com.blockether.spel.spel-allure-alternative-html-report`.

### Library API — `generate-from-results!`

Single-run / lambda / in-memory use case (no temp dir juggling):

```clojure
(require '[com.blockether.spel.spel-allure-alternative-html-report :as r])

(r/generate-from-results!
  [{"name"   "health check"
    "status" "passed"
    "start"  1000
    "stop"   1500
    "labels" [{"name" "epic"  "value" "API"}
              {"name" "suite" "value" "smoke"}]}]
  "/tmp/report"
  {:title "Lambda Run"})
```

Produces the same `index.html` + `summary.json` + `report.json` + `data/` as
`generate!`, sharing all rendering code. Use `generate!` for the standard
directory-based flow, `generate-from-results!` when results are already in
memory.

### Label filtering (in-report UI)

The rendered HTML has client-side Allure-label filters. Viewers can narrow
displayed results by `epic`, `feature`, `story`, `severity`, `owner`, `suite`,
`tag`, etc. Nothing extra to enable — filters appear automatically for every
label present in the results.

## Trace viewer

`with-testing-page` + Allure reporter → Playwright tracing auto-enabled.
Captures screenshots per action, DOM snapshots, network, sources, HAR.

Trace + HAR auto-attached with MIME `application/vnd.allure.playwright-trace`
→ opens in the embedded local trace viewer.

### Source mapping

All step/test macros capture file + line at macro expansion. `Tracing.group()`
receives the location, so the trace viewer's **Source** tab jumps to your test
code (not `allure.clj` internals).

Path resolution uses `PLAYWRIGHT_JAVA_SRC` (auto-set to `src:test:dev` by `core/create`):

```bash
PLAYWRIGHT_JAVA_SRC="src:test:test-e2e:dev" clojure -M:test ...
```

## clojure.test Allure reporter

Same reporter works under `clojure.test` for any runner (Kaocha, Cognitect,
plain `run-tests`).

```bash
# JVM property
clojure -J-Dallure.clojure-test.enabled=true -M:test
# Env var
ALLURE_CLOJURE_TEST_ENABLED=true clojure -M:test
```

```clojure
(ns my-app.test
  (:require [clojure.test :refer [deftest testing is]]
            [com.blockether.spel.allure :as allure]
            [com.blockether.spel.core :as core]
            [com.blockether.spel.page :as page]))

(deftest login-test
  (allure/epic "Auth")
  (allure/feature "Login")
  (testing "loads login page"
    (core/with-testing-page [pg]
      (page/navigate pg "https://example.com/login")
      (is (= "Login" (page/title pg))))))
```

API-only tests (no browser) — require the reporter explicitly:

```clojure
[com.blockether.spel.allure-reporter]
```

### Config

| Property | Env | Default |
|----------|-----|---------|
| `allure.clojure-test.enabled` | `ALLURE_CLOJURE_TEST_ENABLED` | `false` |
| `allure.clojure-test.output`  | `ALLURE_CLOJURE_TEST_OUTPUT`  | `allure-results` |
| `allure.clojure-test.report`  | `ALLURE_CLOJURE_TEST_REPORT`  | `true` |
| `allure.clojure-test.clean`   | `ALLURE_CLOJURE_TEST_CLEAN`   | `true` |

`with-allure-context` is auto-injected as outermost `:each` fixture — never reference directly.

## JUnit XML reporter

```bash
clojure -M:test --output com.blockether.spel.junit-reporter/junit
```

| Property | Env | Default |
|----------|-----|---------|
| `lazytest.junit.output` | `LAZYTEST_JUNIT_OUTPUT` | `test-results/junit.xml` |

Apache Ant schema. `<testsuites>` → `<testsuite>` per namespace → `<testcase>`
with classname/name/file/time, `<failure>` vs `<error>` distinction,
`<skipped>`, `<properties>` (JVM/OS/Clojure), `<system-out>` / `<system-err>`
per-test capture.
