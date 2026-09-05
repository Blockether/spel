## Testing conventions

Use the project's Lazytest runner. Import test forms from `[com.blockether.spel.allure :refer [defdescribe describe it expect]]` when using Spel's Allure integration.

```bash
clojure -M:test
clojure -M:test -n {{ns}}.e2e.seed-test
clojure -M:test -v {{ns}}.e2e.seed-test/seed-test
# Optional Allure output (Lazytest flags, not Cognitect flags)
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

Check the project's `:test` alias before using these commands. `-v` requires a fully qualified namespace/var. Run the changed test first, then the relevant suite; inspect failure counts, not just the process exit.

Assert the observable requirement. Prefer exact text for exact expectations and `contains-text` only for deliberate substring expectations. Use stable role/label/test-id locators; import `[com.blockether.spel.roles :as role]` for role constants. Snapshot refs belong to interactive exploration, not persisted tests.

The example URLs are smoke-test placeholders: use the requested application or a deterministic fixture for real coverage.

### with-testing-page

Creates full Playwright stack (playwright, browser, context, page), binds page, runs body, tears down. Tracing + HAR enabled when Allure active.

```clojure
;; Basic usage
(core/with-testing-page [page]
  (page/navigate page "https://example.org")
  (expect (= "Example Domain" (page/title page))))

;; With options (device, viewport, locale, etc.)
(core/with-testing-page {:device :iphone-14 :locale "fr-FR"} [page]
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

Creates a separate Playwright/browser/context stack with automatic teardown. Tracing and HAR are enabled when the Allure reporter is active. For shared browser cookies, use the page-bound API instead of nesting testing macros.

```clojure
(core/with-testing-api {:base-url "https://api.example.org"} [ctx]
  (api/get ctx "/users"))
```

### Test example

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
