## Testing conventions

Use the project's `clojure.test` runner (`deftest`, `testing`, `is`, `use-fixtures`), not Lazytest CLI flags. The commands below assume a Cognitect `:test` alias; check `deps.edn` for the project's actual runner.

```bash
clojure -M:test
clojure -M:test -n {{ns}}.e2e.seed-test
# Optional Allure integration; require the reporter as documented in the skill.
ALLURE_CLOJURE_TEST_ENABLED=true clojure -M:test
```

Use `core/with-testing-page` for an isolated page with automatic teardown and `core/with-testing-api` for an API context. Both own a full Playwright stack; do not nest them to share cookies. Tracing and HAR are automatic when the Allure reporter is active.

Assert the observable requirement. Prefer exact text for exact expectations and `contains-text` only for deliberate substring expectations. Use stable role/label/test-id locators; import `[com.blockether.spel.roles :as role]` for role constants. Snapshot refs belong to interactive exploration, not persisted tests.

The public example URL below is a smoke-test placeholder, not the target for every integration test. Replace it with the requested application or a deterministic fixture. Read the skill's `references/ALLURE_REPORTING.md` only when configuring reports.

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

`core/with-testing-api` → API equivalent. Creates playwright → browser → context → API req context, auto-tracing.
Opts map first arg → set device, viewport, locale, load saved auth. Body receives page binding, runs inside managed context.

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
