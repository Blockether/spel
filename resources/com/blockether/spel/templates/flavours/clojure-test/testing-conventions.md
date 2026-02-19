## Testing Conventions

- Framework: **`clojure.test`** (`deftest`, `testing`, `is`, `use-fixtures`)
- Fixtures: `use-fixtures` with shared hooks from `com.blockether.spel.test-fixtures`
- Assertions: **Exact string matching** (NEVER substring unless explicitly `contains-text`)
- Require: `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`). All roles are also available in `--eval` mode via the `role/` namespace — see the Enums table in SCI Eval API Reference below
- Integration tests: Live against `example.com`

### Running Tests (clojure.test)

```bash
# Run entire test suite (using Cognitect test-runner or your preferred runner)
clojure -M:test

# Run a single namespace
clojure -M:test -n {{ns}}.e2e.seed-test

# Run with Allure reporter (if using spel.allure integration)
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

### Test Fixtures

The project provides shared fixture functions in `com.blockether.spel.test-fixtures`:

| Fixture | Binds | Scope |
|---------|-------|-------|
| `with-playwright` | `*pw*` | Shared Playwright instance |
| `with-browser` | `*browser*` | Shared headless Chromium browser |
| `with-traced-page` | `*page*` | **Default.** Fresh page per `deftest` with tracing/HAR always enabled (auto-cleanup) |
| `with-page` | `*page*` | Fresh page per `deftest` (auto-cleanup, tracing only when Allure is active) |
| `with-traced-page-opts` | `*page*` | Like `with-traced-page` but accepts context-opts map (use `:around` key) |
| `with-page-opts` | `*page*` | Like `with-page` but accepts context-opts map (use `:around` key) |
| `with-test-server` | `*test-server-url*` | Local HTTP test server |

**Always use `with-traced-page` as the default** — it enables Playwright tracing and HAR capture on every test run, so traces are always available for debugging. Use `with-page` only if you explicitly want tracing disabled outside Allure.

Use `(use-fixtures :once with-playwright with-browser)` and `(use-fixtures :each with-traced-page)` at namespace level. NEVER nest `with-playwright`/`with-browser`/`with-traced-page` manually inside `deftest` blocks.

#### Custom Context Options

To pass `Browser$NewContextOptions` (viewport, locale, color-scheme, storage-state, user-agent, etc.) use `with-page-opts` or `with-traced-page-opts`:

```clojure
;; Mobile viewport with French locale — extract :around from opts map
(use-fixtures :each (:around (with-traced-page-opts {:viewport {:width 375 :height 812}
                                                      :locale "fr-FR"})))

(deftest renders-mobile-layout
  (page/navigate *page* "https://example.com")
  (is (= "fr-FR" (page/evaluate *page* "navigator.language"))))
```

All `*browser-context*` and `*browser-api*` bindings work the same as with the default fixtures.

### Test Example

```clojure
(ns my-app.e2e.seed-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-traced-page]]))

;; Playwright + browser shared across all tests in this namespace
(use-fixtures :once with-playwright with-browser)

;; Fresh page (with tracing/HAR) for each test
(use-fixtures :each with-traced-page)

(deftest homepage-test
  (testing "loads successfully"
    (page/navigate *page* "https://example.com")
    (is (= "Example Domain" (page/title *page*)))
    (is (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Example Domain")))))
```
