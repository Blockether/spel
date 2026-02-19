(ns com.blockether.spel.data-test
  #_{:clj-kondo/ignore [:unused-namespace]}
  (:require
   [clojure.datafy :refer [datafy]]
   [com.blockether.spel.core :as core] ;; auto-loads data.clj
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* *browser* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it]])
  (:import
   [com.microsoft.playwright PlaywrightException TimeoutError]))

;; =============================================================================
;; Datafy Tests
;; =============================================================================

(defdescribe datafy-page-test
  "Tests for datafy on Page"

  (describe "Page datafy"
    {:context [with-playwright with-browser with-page]}
    (it "returns a map with expected keys"
      (page/set-content! *page* "<title>Test</title><body>hello</body>")
      (let [d (datafy *page*)]
        (expect (= :page (:playwright/type d)))
        (expect (string? (:page/url d)))
        (expect (= "Test" (:page/title d)))
        (expect (false? (:page/closed? d)))
        (expect (map? (:page/viewport d)))))))

(defdescribe datafy-browser-test
  "Tests for datafy on Browser"

  (describe "Browser datafy"
    {:context [with-playwright with-browser]}
    (it "returns a map with expected keys"
      (let [d (datafy *browser*)]
        (expect (= :browser (:playwright/type d)))
        (expect (string? (:browser/version d)))
        (expect (true? (:browser/connected? d)))
        (expect (= "chromium" (:browser/type d)))))))

(defdescribe datafy-exception-test
  "Tests for datafy on Playwright exceptions"

  (describe "PlaywrightException"
    (expect-it "returns a map with message"
      (let [e (PlaywrightException. "test error")
            d (datafy e)]
        (and (= :playwright-exception (:playwright/type d))
          (= "test error" (:exception/message d))))))

  (describe "TimeoutError"
    (expect-it "returns a map with message"
      (let [e (TimeoutError. "timed out")
            d (datafy e)]
        (and (= :timeout-error (:playwright/type d))
          (= "timed out" (:exception/message d)))))))
