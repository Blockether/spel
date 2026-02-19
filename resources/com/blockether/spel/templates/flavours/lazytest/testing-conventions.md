## Testing Conventions

- Framework: **`spel.allure`** (`defdescribe`, `describe`, `it`, `expect`) — NOT `lazytest.core`
- Fixtures: `:context` with shared `around` hooks from `com.blockether.spel.test-fixtures`
- Assertions: **Exact string matching** (NEVER substring unless explicitly `contains-text`)
- Require: `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`). All roles are also available in `--eval` mode via the `role/` namespace — see the Enums table in SCI Eval API Reference below
- Integration tests: Live against `example.com`

### Running Tests (Lazytest CLI)

```bash
# Run entire test suite
clojure -M:test

# Run a single namespace
clojure -M:test -n com.blockether.spel.core-test

# Run multiple namespaces
clojure -M:test -n com.blockether.spel.core-test -n com.blockether.spel.page-test

# Run a single test var (MUST be fully-qualified ns/var)
clojure -M:test -v com.blockether.spel.integration-test/proxy-integration-test

# Run multiple vars
clojure -M:test -v com.blockether.spel.options-test/launch-options-test \
                -v com.blockether.spel.options-test/context-options-test

# Run with metadata filter (include/exclude)
clojure -M:test -i :smoke          # only tests tagged ^:smoke
clojure -M:test -e :slow           # exclude tests tagged ^:slow

# Run with Allure reporter
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# Watch mode (re-runs on file changes)
clojure -M:test --watch

# Run tests from a specific directory
clojure -M:test -d test/com/blockether/spel
```

**IMPORTANT**: The `-v`/`--var` flag requires **fully-qualified symbols** (`namespace/var-name`), not bare var names. Using a bare name will throw `IllegalArgumentException: no conversion to symbol`.

### Test Fixtures

The project provides shared `around` hooks in `com.blockether.spel.test-fixtures`:

| Fixture | Binds | Scope |
|---------|-------|-------|
| `with-playwright` | `*pw*` | Shared Playwright instance |
| `with-browser` | `*browser*` | Shared headless Chromium browser |
| `with-traced-page` | `*page*` | **Default.** Fresh page per `it` block with tracing/HAR always enabled (auto-cleanup) |
| `with-page` | `*page*` | Fresh page per `it` block (auto-cleanup, tracing only when Allure is active) |
| `with-traced-page-opts` | `*page*` | Like `with-traced-page` but accepts context-opts map |
| `with-page-opts` | `*page*` | Like `with-page` but accepts context-opts map |
| `with-test-server` | `*test-server-url*` | Local HTTP test server |

**Always use `with-traced-page` as the default** — it enables Playwright tracing and HAR capture on every test run, so traces are always available for debugging. Use `with-page` only if you explicitly want tracing disabled outside Allure.

Use `{:context [with-playwright with-browser with-traced-page]}` on `describe` blocks. NEVER nest `with-playwright`/`with-browser`/`with-traced-page` manually inside `it` blocks.

#### Custom Context Options

To pass `Browser$NewContextOptions` (viewport, locale, color-scheme, storage-state, user-agent, etc.) use `with-page-opts` or `with-traced-page-opts`:

```clojure
;; Mobile viewport with French locale
(describe "mobile view"
  {:context [with-playwright with-browser
             (with-traced-page-opts {:viewport {:width 375 :height 812}
                                     :locale "fr-FR"})]}
  (it "renders mobile layout"
    (page/navigate *page* "https://example.com")
    (expect (= "fr-FR" (page/evaluate *page* "navigator.language")))))

;; Load saved auth state
(describe "authenticated tests"
  {:context [with-playwright with-browser
             (with-page-opts {:storage-state "auth.json"})]}
  (it "is logged in"
    (page/navigate *page* "https://app.example.com/dashboard")
    (expect (nil? (assert/has-url (assert/assert-that *page*) "dashboard")))))
```

All `*browser-context*` and `*browser-api*` bindings work the same as with the default fixtures.

### Test Example

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-traced-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.com"
    {:context [with-playwright with-browser with-traced-page]}

    (it "navigates and asserts"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*)))
      (expect (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Example Domain"))))))
```
