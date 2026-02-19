(ns com.blockether.spel.e2e.seed-test
  "Seed test — establishes the base setup pattern for E2E tests.
   This test tells agents how to configure the browser environment
   so that pages are already set up correctly."
  (:require
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]))

(defdescribe seed-test
  (describe "browser environment"
    {:context [with-playwright with-browser with-page]}

    (it "sets up and navigates to the application"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*))))))
