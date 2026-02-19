(ns com.blockether.spel.ct.smoke-test
  "Smoke tests using standard clojure.test against example.com.

   Demonstrates that clojure.test deftest/testing/is/use-fixtures work
   alongside Lazytest defdescribe tests, with Allure reporting for both."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                              with-browser
                                              with-traced-page
                                              ct-fixture]]))

;; Playwright + browser shared across all tests in this namespace
;; ct-fixture extracts the plain fn from Lazytest around hooks
(use-fixtures :once (ct-fixture with-playwright) (ct-fixture with-browser))

;; Fresh traced page per test (binds *page*, *trace-path*, *har-path*)
;; with-allure-context is injected automatically by run-ct-tests!
(use-fixtures :each (ct-fixture with-traced-page))

(deftest homepage-navigation-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "example.com")
  (allure/severity :critical)
  (allure/tag "smoke")
  (allure/tag "clojure.test")
  (testing "navigates to example.com"
    (page/navigate *page* "https://example.com")
    (is (= "Example Domain" (page/title *page*))))
  (testing "h1 heading is correct"
    (let [h1 (page/locator *page* "h1")]
      (is (= "Example Domain" (locator/text-content h1)))
      (is (locator/is-visible? h1)))))

(deftest playwright-assertions-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "Assertions")
  (allure/tag "clojure.test")
  (testing "page-level assertions"
    (page/navigate *page* "https://example.com")
    (assert/has-title *page* "Example Domain")
    (is (= "Example Domain" (page/title *page*))))
  (testing "locator assertions"
    (let [h1 (page/locator *page* "h1")]
      (assert/has-text h1 "Example Domain")
      (assert/is-visible h1)
      (is true "all assertions passed"))))

(deftest link-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "example.com")
  (allure/tag "clojure.test")
  (testing "has a link to IANA"
    (page/navigate *page* "https://example.com")
    (let [link (page/locator *page* "a")
          href (locator/get-attribute link "href")]
      (is (.contains ^String href "iana.org"))
      (is (= 1 (locator/count-elements link))))))
