(ns com.blockether.spel.ct.testing-page-test
  "clojure.test tests for with-testing-page macro.

   Verifies that the macro works correctly with clojure.test deftest/testing/is,
   including both the opts-omitted and opts-provided forms."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Page]))

;; No use-fixtures — with-testing-page manages the full Playwright lifecycle itself.

(deftest basic-usage-no-opts-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Basic Usage")
  (allure/tag "clojure.test")
  (testing "creates page and navigates — opts omitted"
    (core/with-testing-page [pg]
      (is (instance? Page pg))
      (page/navigate pg "https://example.com")
      (is (= "Example Domain" (page/title pg))))))

(deftest basic-usage-empty-opts-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Basic Usage")
  (allure/tag "clojure.test")
  (testing "creates page and navigates — empty opts"
    (core/with-testing-page {} [pg]
      (is (instance? Page pg))
      (page/navigate pg "https://example.com")
      (is (= "Example Domain" (page/title pg))))))

(deftest viewport-preset-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Viewport")
  (allure/tag "clojure.test")
  (testing "uses :desktop-hd viewport preset"
    (core/with-testing-page {:viewport :desktop-hd} [pg]
      (page/navigate pg "https://example.com")
      (let [vp (page/viewport-size pg)]
        (is (= 1920 (:width vp)))
        (is (= 1080 (:height vp)))))))

(deftest viewport-map-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Viewport")
  (allure/tag "clojure.test")
  (testing "uses custom viewport map"
    (core/with-testing-page {:viewport {:width 800 :height 600}} [pg]
      (page/navigate pg "https://example.com")
      (let [vp (page/viewport-size pg)]
        (is (= 800 (:width vp)))
        (is (= 600 (:height vp)))))))

(deftest device-preset-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Device Emulation")
  (allure/tag "clojure.test")
  (testing "uses :iphone-14 device emulation"
    (core/with-testing-page {:device :iphone-14} [pg]
      (page/navigate pg "https://example.com")
      (let [vp (page/viewport-size pg)]
        (is (= 390 (:width vp)))
        (is (= 844 (:height vp)))))))

(deftest return-value-no-opts-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Return Value")
  (allure/tag "clojure.test")
  (testing "returns body result — opts omitted"
    (let [result (core/with-testing-page [pg]
                   (page/navigate pg "https://example.com")
                   (page/title pg))]
      (is (= "Example Domain" result)))))

(deftest return-value-with-opts-test
  (allure/epic "with-testing-page (clojure.test)")
  (allure/feature "Return Value")
  (allure/tag "clojure.test")
  (testing "returns body result — with opts"
    (let [result (core/with-testing-page {} [pg]
                   (page/navigate pg "https://example.com")
                   (page/title pg))]
      (is (= "Example Domain" result)))))
