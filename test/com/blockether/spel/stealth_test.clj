(ns com.blockether.spel.stealth-test
  "Tests for the stealth namespace.

   Unit tests for Chrome launch args, default-arg suppressions, and
   JavaScript init script content. Validates that all expected evasion
   patches are present in the generated script."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.stealth :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — stealth-args
;; =============================================================================

(defdescribe stealth-args-test
  "Unit tests for stealth-args — anti-detection Chrome launch args"

  (describe "args vector"
    (it "returns a non-empty vector"
      (let [args (sut/stealth-args)]
        (expect (vector? args))
        (expect (pos? (count args)))))

    (it "includes disable-blink-features=AutomationControlled"
      (let [args (sut/stealth-args)]
        (expect (some #(str/includes? % "AutomationControlled") args))))))

;; =============================================================================
;; Unit Tests — stealth-ignore-default-args
;; =============================================================================

(defdescribe stealth-ignore-default-args-test
  "Unit tests for stealth-ignore-default-args — args to suppress"

  (describe "suppressed args"
    (it "returns a non-empty vector"
      (let [args (sut/stealth-ignore-default-args)]
        (expect (vector? args))
        (expect (pos? (count args)))))

    (it "includes --enable-automation"
      (let [args (sut/stealth-ignore-default-args)]
        (expect (some #(= "--enable-automation" %) args))))))

;; =============================================================================
;; Unit Tests — stealth-init-script
;; =============================================================================

(defdescribe stealth-init-script-test
  "Unit tests for stealth-init-script — JS evasion patches"

  (describe "script content"
    (it "returns a non-empty string"
      (let [script (sut/stealth-init-script)]
        (expect (string? script))
        (expect (pos? (count script)))))

    (it "is wrapped in IIFE"
      (let [script (sut/stealth-init-script)]
        (expect (str/starts-with? script "(function()"))
        (expect (str/ends-with? script "})();"))))

    (it "contains navigator.webdriver patch"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "navigator"))
        (expect (str/includes? script "webdriver"))))

    (it "contains navigator.plugins patch"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "navigator"))
        (expect (str/includes? script "plugins"))
        (expect (str/includes? script "Chrome PDF Plugin"))))

    (it "contains navigator.languages patch"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "languages"))
        (expect (str/includes? script "en-US"))))

    (it "contains chrome.runtime mock"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "chrome.runtime"))
        (expect (str/includes? script "connect"))
        (expect (str/includes? script "sendMessage"))))

    (it "contains permissions.query override"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "permissions.query"))
        (expect (str/includes? script "notifications"))))

    (it "contains WebGL getParameter override"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "WebGLRenderingContext"))
        (expect (str/includes? script "getParameter"))
        (expect (str/includes? script "37445"))
        (expect (str/includes? script "37446"))))

    (it "contains outerWidth/outerHeight patch"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "outerWidth"))
        (expect (str/includes? script "outerHeight"))
        (expect (str/includes? script "innerWidth"))))

    (it "contains iframe contentWindow patch"
      (let [script (sut/stealth-init-script)]
        (expect (str/includes? script "HTMLIFrameElement"))
        (expect (str/includes? script "contentWindow"))))))
