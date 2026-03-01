## Testing Conventions

- Framework: **`spel.allure`** (`defdescribe`, `describe`, `it`, `expect`) — NOT `lazytest.core`
- Page setup: **`core/with-testing-page`** — all-in-one macro (playwright + browser + context + page)
- API testing: **`core/with-testing-api`** — all-in-one macro for API request contexts
- Assertions: **Exact string matching** (NEVER substring unless explicitly `contains-text`)
- Require: `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`). All roles are also available in `--eval` mode via the `role/` namespace — see the Enums table in SCI Eval API Reference below
- Integration tests: Live against `example.org`

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

### with-testing-page

All-in-one macro that creates the full Playwright stack (playwright → browser → context → page), binds the page, runs body, and tears everything down automatically. When Allure is active, tracing and HAR are enabled automatically.

```clojure
;; Basic usage
(core/with-testing-page [page]
  (page/navigate page "https://example.org")
  (expect (= "Example Domain" (page/title page))))

;; With options (device, viewport, locale, etc.)
(core/with-testing-page {:device :iphone-14} [page]
  (page/navigate page "https://example.org")
  (expect (= "fr-FR" (page/evaluate page "navigator.language"))))

;; Desktop HD viewport with locale
(core/with-testing-page {:viewport :desktop-hd :locale "fr-FR"} [page]
  (page/navigate page "https://example.org"))

;; Firefox with visible browser
(core/with-testing-page {:browser-type :firefox :headless false} [page]
  (page/navigate page "https://example.org"))

;; Load saved auth state
(core/with-testing-page {:storage-state "auth.json"} [page]
  (page/navigate page "https://app.example.org/dashboard"))
```

### with-testing-api

All-in-one macro for API testing. Creates playwright → browser → context → API request context with automatic tracing.

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (api/get ctx "/users"))
```

### Test Example

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.org"

    (it "navigates and asserts"
      (core/with-testing-page [page]
        (page/navigate page "https://example.org")
        (expect (= "Example Domain" (page/title page)))
        (expect (nil? (assert/has-text (assert/assert-that (page/locator page "h1")) "Example Domain")))))))
```
