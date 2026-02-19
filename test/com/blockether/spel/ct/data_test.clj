(ns com.blockether.spel.ct.data-test
  "Datafy tests using standard clojure.test with browser fixtures.

   Demonstrates clojure.test + Playwright browser lifecycle + Allure
   reporting working together."
  #_{:clj-kondo/ignore [:unused-namespace]}
  (:require
   [clojure.datafy :refer [datafy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.blockether.spel.core :as core] ;; auto-loads data.clj
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* *browser* with-playwright
                                              with-browser with-page
                                              ct-fixture]])
  (:import
   [com.microsoft.playwright PlaywrightException TimeoutError]))

;; Playwright + browser shared across all tests
;; ct-fixture extracts the plain fn from Lazytest around hooks
(use-fixtures :once (ct-fixture with-playwright) (ct-fixture with-browser))

;; Fresh page per test (with-allure-context injected automatically by run-ct-tests!)
(use-fixtures :each (ct-fixture with-page))

(deftest datafy-page-test
  (testing "Page datafy returns expected keys"
    (page/set-content! *page* "<title>CT Test</title><body>hello</body>")
    (let [d (datafy *page*)]
      (is (= :page (:playwright/type d)))
      (is (string? (:page/url d)))
      (is (= "CT Test" (:page/title d)))
      (is (false? (:page/closed? d)))
      (is (map? (:page/viewport d))))))

(deftest datafy-browser-test
  (testing "Browser datafy returns expected keys"
    (let [d (datafy *browser*)]
      (is (= :browser (:playwright/type d)))
      (is (string? (:browser/version d)))
      (is (true? (:browser/connected? d)))
      (is (= "chromium" (:browser/type d))))))

(deftest datafy-exception-test
  (testing "PlaywrightException datafy"
    (let [e (PlaywrightException. "test error")
          d (datafy e)]
      (is (= :playwright-exception (:playwright/type d)))
      (is (= "test error" (:exception/message d)))))

  (testing "TimeoutError datafy"
    (let [e (TimeoutError. "timed out")
          d (datafy e)]
      (is (= :timeout-error (:playwright/type d)))
      (is (= "timed out" (:exception/message d))))))
