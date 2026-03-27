(ns com.blockether.spel.data-test
  (:require
   [clojure.datafy :refer [datafy]]
   [com.blockether.spel.core :as core] ;; auto-loads data.clj
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it around]])
  (:import
   [com.microsoft.playwright PlaywrightException TimeoutError]))

;; =============================================================================
;; Datafy Tests
;; =============================================================================

(defdescribe datafy-page-test
  "Tests for datafy on Page"
  (around [f] (core/with-testing-browser (f)))

  (describe "Page datafy"
    (it "returns a map with expected keys"
      (core/with-testing-page [pg]
        (page/set-content! pg "<title>Test</title><body>hello</body>")
        (let [d (datafy pg)]
          (expect (= :page (:playwright/type d)))
          (expect (string? (:page/url d)))
          (expect (= "Test" (:page/title d)))
          (expect (false? (:page/closed? d)))
          (expect (map? (:page/viewport d))))))))

(defdescribe datafy-browser-test
  "Tests for datafy on Browser"
  (around [f] (core/with-testing-browser (f)))

  (describe "Browser datafy"
    (it "returns a map with expected keys"
      (core/with-testing-page [pg]
        (let [d (datafy (.browser (.context pg)))]
          (expect (= :browser (:playwright/type d)))
          (expect (string? (:browser/version d)))
          (expect (true? (:browser/connected? d)))
          (expect (= "chromium" (:browser/type d))))))))

(defdescribe datafy-exception-test
  "Tests for datafy on Playwright exceptions"
  (around [f] (core/with-testing-browser (f)))

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
