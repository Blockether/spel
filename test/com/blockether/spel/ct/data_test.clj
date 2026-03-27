(ns com.blockether.spel.ct.data-test
  "Datafy tests using standard clojure.test with browser fixtures.

   Demonstrates clojure.test + Playwright browser lifecycle + Allure
   reporting working together."
  (:require
   [clojure.datafy :refer [datafy]]
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.core :as core] ;; auto-loads data.clj
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright PlaywrightException TimeoutError]))

(clojure.test/use-fixtures :once (fn [f] (core/with-testing-browser (f))))

(deftest datafy-page-test
  (testing "Page datafy returns expected keys"
    (core/with-testing-page [pg]
      (page/set-content! pg "<title>CT Test</title><body>hello</body>")
      (let [d (datafy pg)]
        (is (= :page (:playwright/type d)))
        (is (string? (:page/url d)))
        (is (= "CT Test" (:page/title d)))
        (is (false? (:page/closed? d)))
        (is (map? (:page/viewport d)))))))

(deftest datafy-browser-test
  (testing "Browser datafy returns expected keys"
    (core/with-testing-page [pg]
      (let [browser (.browser (.context pg))
            d (datafy browser)]
        (is (= :browser (:playwright/type d)))
        (is (string? (:browser/version d)))
        (is (true? (:browser/connected? d)))
        (is (= "chromium" (:browser/type d)))))))

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
