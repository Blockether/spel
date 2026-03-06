## Testing conventions

- Framework: `clojure.test` (`deftest`, `testing`, `is`, `use-fixtures`)
- Page setup: `core/with-testing-page` (all-in-one macro: playwright + browser + context + page)
- API testing: `core/with-testing-api` (all-in-one macro for API request contexts)
- Assertions: exact string matching (NEVER substring unless explicitly `contains-text`)
- Require: `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`). All roles are also available in `eval-sci` mode via the `role/` namespace. See the Enums table in SCI Eval API Reference below.
- Integration tests: live against `example.org`

Each test gets a fresh browser page via `with-testing-page`. The macro handles the full Playwright lifecycle so you don't need to manage playwright, browser, or context objects directly. Assertions use exact string matching by default. Never use substring unless the spec explicitly calls for `contains-text`.

Run the test suite:

```bash
# Run entire test suite (using Cognitect test-runner or your preferred runner)
clojure -M:test

# Run a single namespace
clojure -M:test -n {{ns}}.e2e.seed-test

# Run with Allure reporter (if using spel.allure integration)
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

`core/with-testing-page` creates the full Playwright stack (playwright → browser → context → page), binds the page, runs body, and tears everything down. When Allure is active, tracing and HAR are enabled automatically.

```clojure
;; Basic usage
(core/with-testing-page [page]
  (page/navigate page "https://example.org")
  (is (= "Example Domain" (page/title page))))

;; With options (device, viewport, locale, etc.)
(core/with-testing-page {:device :iphone-14} [page]
  (page/navigate page "https://example.org"))

;; Load saved auth state
(core/with-testing-page {:storage-state "auth.json"} [page]
  (page/navigate page "https://app.example.org/dashboard"))
```

`core/with-testing-api` is the equivalent for API testing. Creates playwright → browser → context → API request context with automatic tracing.
Pass an opts map as the first argument to set device, viewport, locale, or load saved auth state. The body receives the page binding and runs inside the managed context.

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (api/get ctx "/users"))
```

```clojure
(ns my-app.e2e.seed-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]))

(deftest homepage-test
  (testing "loads successfully"
    (core/with-testing-page [page]
      (page/navigate page "https://example.org")
      (is (= "Example Domain" (page/title page)))
      (is (nil? (assert/has-text (assert/assert-that (page/locator page "h1")) "Example Domain"))))))
```
