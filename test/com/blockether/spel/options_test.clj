(ns com.blockether.spel.options-test
  "Unit tests for options conversion functions."
  (:require
   [com.blockether.spel.options :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright BrowserType$LaunchOptions Browser$NewContextOptions]))

;; =============================================================================
;; Launch Options
;; =============================================================================

(defdescribe launch-options-test
  "Tests for ->launch-options"

  (describe "basic options"
    (it "creates options with headless"
      (let [lo (sut/->launch-options {:headless true})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with slow-mo"
      (let [lo (sut/->launch-options {:slow-mo 100})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with args"
      (let [lo (sut/->launch-options {:args ["--no-sandbox" "--disable-gpu"]})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with executable-path"
      (let [lo (sut/->launch-options {:executable-path "/usr/bin/chromium"})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with channel"
      (let [lo (sut/->launch-options {:channel "chrome"})]
        (expect (instance? BrowserType$LaunchOptions lo)))))

  (describe "proxy options"
    (it "creates options with proxy server only"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with proxy server and bypass"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"
                                              :bypass "localhost,127.0.0.1"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "creates options with proxy server, bypass, username, and password"
      (let [lo (sut/->launch-options {:proxy {:server "http://proxy:8080"
                                              :bypass "localhost"
                                              :username "user"
                                              :password "pass"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))

    (it "does not set proxy when proxy key is absent"
      (let [lo (sut/->launch-options {:headless true})]
        (expect (instance? BrowserType$LaunchOptions lo)))))

  (describe "combined options"
    (it "creates options with multiple settings"
      (let [lo (sut/->launch-options {:headless true
                                      :slow-mo 50
                                      :args ["--no-sandbox"]
                                      :proxy {:server "http://proxy:8080"
                                              :bypass "localhost"}})]
        (expect (instance? BrowserType$LaunchOptions lo))))))

;; =============================================================================
;; Context Options
;; =============================================================================

(defdescribe context-options-test
  "Tests for ->new-context-options"

  (describe "basic options"
    (it "creates context options with viewport"
      (let [co (sut/->new-context-options {:viewport {:width 1280 :height 720}})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with user-agent"
      (let [co (sut/->new-context-options {:user-agent "TestAgent/1.0"})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with ignore-https-errors"
      (let [co (sut/->new-context-options {:ignore-https-errors true})]
        (expect (instance? Browser$NewContextOptions co))))

    (it "creates context options with extra-http-headers"
      (let [co (sut/->new-context-options {:extra-http-headers {"Authorization" "Bearer tok"}})]
        (expect (instance? Browser$NewContextOptions co))))))
