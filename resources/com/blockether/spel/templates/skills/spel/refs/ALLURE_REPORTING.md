# Allure Test Reporting

Allure test reporting provides rich HTML reports with embedded Playwright traces, step hierarchies, labels, attachments, and build history tracking.

## Labels

Call these functions inside test bodies to add metadata to Allure test results:

```clojure
(require '[com.blockether.spel.allure :as allure])

(allure/epic "E2E Testing")
(allure/feature "Authentication")
(allure/story "Login Flow")
(allure/severity :critical)          ; :blocker :critical :normal :minor :trivial
(allure/owner "team@example.org")
(allure/tag "smoke")
(allure/description "Tests the complete login flow")
(allure/link "Docs" "https://example.org/docs")
(allure/issue "BUG-123" "https://github.com/example/issues/123")
(allure/tms "TC-456" "https://tms.example.org/456")
(allure/parameter "browser" "chromium")
```

| Label | Function | Purpose |
|-------|-----------|---------|
| Epic | `allure/epic` | High-level test grouping (e.g., "E2E Testing") |
| Feature | `allure/feature` | Feature or module under test (e.g., "Authentication") |
| Story | `allure/story` | User story or scenario (e.g., "Login Flow") |
| Severity | `allure/severity` | Priority: `:blocker`, `:critical`, `:normal`, `:minor`, `:trivial` |
| Owner | `allure/owner` | Test owner email |
| Tag | `allure/tag` | Freeform tags for filtering (e.g., "smoke", "regression") |
| Description | `allure/description` | Full test description |
| Link | `allure/link` | Named link to external resources |
| Issue | `allure/issue` | Link to bug tracker |
| TMS | `allure/tms` | Link to Test Management System |
| Parameter | `allure/parameter` | Test parameter (key-value pairs for parametrized tests) |

## Steps

Create step hierarchies for better test readability and failure debugging:

```clojure
;; Simple step
(allure/step "Navigate to login page"
  (page/navigate pg "https://example.org/login"))

;; Nested steps
(allure/step "Login flow"
  (allure/step "Enter credentials"
    (locator/fill (page/locator pg "#user") "admin")
    (locator/fill (page/locator pg "#pass") "secret"))
  (allure/step "Submit"
    (locator/click (page/locator pg "#submit"))))
```

### Step Options

`step` supports an optional opts map for composable behaviors:

| Option | Description |
|--------|-------------|
| `:screenshots?` | Take before/after screenshots (requires page binding, skipped when tracing) |
| `:http?` | Attach HTTP exchange markdown for APIResponse / browser Response |

```clojure
;; Step with screenshots (equivalent to ui-step)
(allure/step "Fill login form" {:screenshots? true}
  (locator/fill username-input "admin")
  (locator/click submit-btn))

;; Step with HTTP attachment (equivalent to api-step)
(allure/step "Create user" {:http? true}
  (core/api-post ctx "/users" {:data body}))

;; Both screenshots and HTTP attachment
(allure/step "Submit and verify API" {:screenshots? true :http? true}
  (page/wait-for-response pg "**/api/submit"
    #(locator/click submit-btn)))

;; Runtime opts via :opts keyword
(let [my-opts {:http? true}]
  (allure/step "Dynamic step" :opts my-opts
    (core/api-get ctx "/health")))
```

## UI Steps

UI steps automatically capture before/after screenshots. Equivalent to `(step name {:screenshots? true} body...)`.
Works with `core/with-testing-page` or test fixtures:

```clojure
(allure/ui-step "Fill login form"
  (locator/fill username-input "admin")
  (locator/fill password-input "secret")
  (locator/click submit-btn))
```

UI steps:
- Attach screenshots before and after the step
- Include step timing
- Capture page state changes
- Ideal for debugging visual issues

## API Steps

API steps automatically attach response details (status, headers, body). Equivalent to `(step name {:http? true} body...)`:

```clojure
(allure/api-step "Create user"
  (core/api-post ctx "/users" {:json {:name "Alice" :age 30}}))
```

API steps attach:
- HTTP status code
- Response headers
- Response body (formatted)
- Request URL and method
- Timing information

## Attachments

Add arbitrary attachments to test results:

```clojure
;; Attach text with MIME type
(allure/attach "Request Body" "{\"key\":\"value\"}" "application/json")

;; Attach binary data
(allure/attach-bytes "Screenshot" (page/screenshot pg) "image/png")

;; Convenience: capture and attach PNG screenshot
(allure/screenshot pg "After navigation")

;; Attach API response as formatted Markdown (method, URL, status, headers, body)
(allure/attach-http-markdown! resp {})
```

## Allure Reporter

The built-in Allure reporter handles JSON results, HTML generation, embedded trace viewer, and build history.

### Running Tests with Allure

```bash
# Allure reporter only
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# Combine with JUnit reporter
clojure -M:test --output nested \
  --output com.blockether.spel.allure-reporter/allure \
  --output com.blockether.spel.junit-reporter/junit
```

### Configuration

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `lazytest.allure.output` | `LAZYTEST_ALLURE_OUTPUT` | `allure-results` | Results output directory |
| `lazytest.allure.report` | `LAZYTEST_ALLURE_REPORT` | `allure-report` | HTML report directory |
| `lazytest.allure.history-limit` | `LAZYTEST_ALLURE_HISTORY_LIMIT` | `10` | Max builds retained in history |
| `lazytest.allure.report-name` | `LAZYTEST_ALLURE_REPORT_NAME` | _(auto: "spel vX.Y.Z")_ | Report title (shown in header and history) |
| `lazytest.allure.version` | `LAZYTEST_ALLURE_VERSION` | _(SPEL_VERSION)_ | Project version shown in build history |
| `lazytest.allure.logo` | `LAZYTEST_ALLURE_LOGO` | _(none)_ | Path to logo image for report header |

### Version in Build Listings

When `lazytest.allure.version` is set (or `SPEL_VERSION` is on the classpath), each build in Allure history is tagged with the version. The report name auto-generates as `"spel vX.Y.Z"` unless overridden by `report-name`. The version also appears in `environment.properties` as `project.version` and `spel.version`.

```bash
# Tag build with custom version (overrides SPEL_VERSION)
clojure -J-Dlazytest.allure.version=1.2.3 -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure

# Keep last 20 builds in history
LAZYTEST_ALLURE_HISTORY_LIMIT=20 clojure -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure
```

### Serving Reports

The report MUST be served via HTTP (not `file://`) because the embedded Playwright trace viewer uses a Service Worker:

```bash
# Serve generated report in browser (port 9999)
npx http-server allure-report -o -p 9999
```

## Trace Viewer Integration

When using `with-testing-page` (recommended) or low-level fixtures (`with-page` / `with-traced-page`) with Allure reporter active, Playwright tracing is automatically enabled.

### What's Captured

Tracing captures comprehensive test execution data:

- Screenshots captured on every action
- DOM snapshots included
- Network activity recorded
- Sources captured
- HAR file generated

### Auto-Attach to Test Results

Trace and HAR files are automatically attached to test results with MIME type `application/vnd.allure.playwright-trace`. They are viewable directly in the Allure report via an embedded local trace viewer — no external service dependency.

`with-testing-page` auto-attaches traces for both the Lazytest reporter and the clojure.test reporter — no additional configuration needed.

### Source Mapping in Trace Viewer

All step macros (`step`, `ui-step`, `api-step`, `describe`, `it`, `expect`) automatically capture source file and line number at macro expansion time. They pass this information to `Tracing.group()` via `GroupOptions.setLocation()`. This means clicking a step in the Trace Viewer **Source** tab shows the actual test code where the step was written, not allure.clj macro internals.

#### PLAYWRIGHT_JAVA_SRC

Source path resolution uses the `PLAYWRIGHT_JAVA_SRC` environment variable (auto-set to `src:test:dev` by `core/create`). This maps classpath-relative paths (e.g., `com/blockether/spel/smoke_test.clj`) to project-relative paths (e.g., `test/com/blockether/spel/smoke_test.clj`) that match the trace's captured sources.

```bash
# Custom source directories
PLAYWRIGHT_JAVA_SRC="src:test:test-e2e:dev" clojure -M:test ...
```

## JUnit XML Reporter

Produces JUnit XML output compatible with GitHub Actions, Jenkins, GitLab CI, and any CI system that consumes JUnit XML.

### Running Tests with JUnit

```bash
# JUnit reporter only
clojure -M:test --output com.blockether.spel.junit-reporter/junit

# Combine with Allure reporter
clojure -M:test --output nested \
  --output com.blockether.spel.allure-reporter/allure \
  --output com.blockether.spel.junit-reporter/junit
```

### Configuration

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `lazytest.junit.output` | `LAZYTEST_JUNIT_OUTPUT` | `test-results/junit.xml` | Output file path |

```bash
# Custom output path
clojure -J-Dlazytest.junit.output=reports/results.xml -M:test \
  --output nested --output com.blockether.spel.junit-reporter/junit

# Or via env var
LAZYTEST_JUNIT_OUTPUT=reports/results.xml clojure -M:test \
  --output nested --output com.blockether.spel.junit-reporter/junit
```

### JUnit XML Features

The JUnit reporter generates fully compliant Apache Ant JUnit schema XML:

- `<testsuites>` root element with aggregate counts (tests, failures, errors, skipped, time)
- `<testsuite>` per namespace with timestamp, hostname, package, id
- `<testcase>` with classname (namespace), name (describe > it path), time, file
- `<failure>` vs `<error>` distinction (assertion failure vs unexpected exception)
- `<skipped>` support for pending tests
- `<properties>` with environment metadata (JVM version, OS, Clojure version)
- `<system-out>` / `<system-err>` — per-test captured stdout/stderr output
