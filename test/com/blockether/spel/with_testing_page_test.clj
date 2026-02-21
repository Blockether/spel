(ns com.blockether.spel.with-testing-page-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.blockether.spel.core :as core]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator])
  (:import [com.microsoft.playwright Playwright Browser BrowserContext Page]))

(deftest with-testing-page-basic-test
  (testing "basic usage with defaults"
    (core/with-testing-page {}
      [pg]
      (is (instance? Page pg))
      (page/navigate pg "https://example.com")
      (is (= "Example Domain"
             (locator/text-content (page/locator pg "h1")))))))

(deftest with-testing-page-options-test
  (testing "chromium is default browser"
    (core/with-testing-page {:browser-type :chromium}
      [pg]
      (is (instance? Page pg))))
  
  (testing "firefox browser type"
    (core/with-testing-page {:browser-type :firefox
                              :headless true}
      [pg]
      (is (instance? Page pg))))
  
  (testing "webkit browser type"
    (core/with-testing-page {:browser-type :webkit
                              :headless true}
      [pg]
      (is (instance? Page pg))))
  
  (testing "custom viewport"
    (core/with-testing-page {:viewport {:width 1920 :height 1080}}
      [pg]
      (is (instance? Page pg))
      (page/navigate pg "https://example.com"))))

(deftest with-testing-page-headless-test
  (testing "headless true (default)"
    (core/with-testing-page {:headless true}
      [pg]
      (is (instance? Page pg))))
  
  (testing "headless false"
    ;; This test would require display, skip in CI
    (when (or (System/getenv "DISPLAY") (System/getenv "WAYLAND_DISPLAY"))
      (core/with-testing-page {:headless false}
        [pg]
        (is (instance? Page pg))))))

(deftest with-testing-page-slow-mo-test
  (testing "slow-mo delays actions"
    (let [start (System/currentTimeMillis)]
      (core/with-testing-page {:slow-mo 100}
        [pg]
        (page/navigate pg "https://example.com"))
      (let [elapsed (- (System/currentTimeMillis) start)]
        ;; Should take at least 100ms due to slow-mo
        (is (>= elapsed 100))))))

(deftest with-testing-page-cleanup-test
  (testing "resources are properly cleaned up"
    (let [pw-count-before (count (Playwright/findAll))]
      (core/with-testing-page {}
        [pg]
        (page/navigate pg "https://example.com"))
      ;; Playwright instance should be cleaned up
      (is (= pw-count-before (count (Playwright/findAll)))))))
