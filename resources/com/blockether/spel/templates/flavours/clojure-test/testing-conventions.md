# Testing conventions: clojure.test

**Use when:** Writing JVM tests with the selected clojure.test flavour, not running SCI scripts.

Use the project's runner with `deftest`, `testing`, `is` and `use-fixtures`. These commands assume a Cognitect `:test` alias; check `deps.edn`. Do not pass Lazytest's `--output` flags. Run the changed test first, then the relevant suite; inspect failure counts as well as exit status.

```bash
clojure -M:test -n {{ns}}.e2e.seed-test
clojure -M:test
# Optional: configure/require the reporter per references/ALLURE_REPORTING.md.
ALLURE_CLOJURE_TEST_ENABLED=true clojure -M:test
```

Use stable role/label/test-id locators, not snapshot refs in persisted tests. Assert the observable requirement; use exact text unless a substring match is intentional. Replace the smoke-test URL with the requested application or a deterministic fixture.

`core/with-testing-page` owns setup/teardown; its optional first map sets device, viewport, locale or `:storage-state`. `core/with-testing-api` owns a separate stack: do not nest them to share cookies. See `references/API_TESTING.md` for page-bound requests and `references/ALLURE_REPORTING.md` only when reports are needed.

```clojure
(ns {{ns}}.e2e.seed-test
  (:require [clojure.test :refer [deftest is]]
            [com.blockether.spel.core :as core]
            [com.blockether.spel.page :as page]))

(deftest seed-test
  (core/with-testing-page [page]
    (page/navigate page "https://example.com")
    (is (= "Example Domain" (page/title page)))))
```
