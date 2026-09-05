# Testing conventions: Lazytest

**Use when:** Writing JVM tests with the selected Lazytest flavour, not running SCI scripts.

Check the project's `:test` alias in `deps.edn` before using these commands. Run the changed test first, then the relevant suite; inspect failure counts as well as exit status.

```bash
clojure -M:test -n {{ns}}.e2e.seed-test
clojure -M:test
# Optional Allure output: Lazytest flags, not Cognitect flags.
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

Use stable role/label/test-id locators, not snapshot refs in persisted tests. Assert the observable requirement; use exact text unless a substring match is intentional. Replace the smoke-test URL with the requested application or a deterministic fixture.

`core/with-testing-page` owns setup/teardown; its optional first map sets device, viewport, locale or `:storage-state`. `core/with-testing-api` owns a separate stack: do not nest them to share cookies. See `references/API_TESTING.md` for page-bound requests and `references/ALLURE_REPORTING.md` only when reports are needed.

```clojure
(ns {{ns}}.e2e.seed-test
  (:require [com.blockether.spel.allure :refer [defdescribe it expect]]
            [com.blockether.spel.core :as core]
            [com.blockether.spel.page :as page]))

(defdescribe seed-test
  (it "loads the application"
    (core/with-testing-page [page]
      (page/navigate page "https://example.com")
      (expect (= "Example Domain" (page/title page))))))
```
