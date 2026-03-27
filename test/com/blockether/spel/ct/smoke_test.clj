(ns com.blockether.spel.ct.smoke-test
  "Smoke tests using standard clojure.test against example.org.

   Demonstrates that clojure.test deftest/testing/is work alongside
   Lazytest defdescribe tests, with Allure reporting for both."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]))

(use-fixtures :once (fn [f] (core/with-testing-browser (f))))

(deftest homepage-navigation-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "example.org")
  (allure/severity :critical)
  (allure/tag "smoke")
  (allure/tag "clojure.test")
  (core/with-testing-page [pg]
    (testing "navigates to example.org"
      (page/navigate pg "https://example.org")
      (is (= "Example Domain" (page/title pg))))
    (testing "h1 heading is correct"
      (let [h1 (page/locator pg "h1")]
        (is (= "Example Domain" (locator/text-content h1)))
        (is (locator/is-visible? h1))))))

(deftest playwright-assertions-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "Assertions")
  (allure/tag "clojure.test")
  (core/with-testing-page [pg]
    (testing "page-level assertions"
      (page/navigate pg "https://example.org")
      (assert/has-title pg "Example Domain")
      (is (= "Example Domain" (page/title pg))))
    (testing "locator assertions"
      (let [h1 (page/locator pg "h1")]
        (assert/has-text h1 "Example Domain")
        (assert/is-visible h1)
        (is true "all assertions passed")))))

(deftest link-test
  (allure/epic "Smoke Tests (clojure.test)")
  (allure/feature "example.org")
  (allure/tag "clojure.test")
  (core/with-testing-page [pg]
    (testing "has a link to IANA"
      (page/navigate pg "https://example.org")
      (let [link (page/locator pg "a")
            href (locator/get-attribute link "href")]
        (is (.contains ^String href "iana.org"))
        (is (= 1 (locator/count-elements link)))))))
