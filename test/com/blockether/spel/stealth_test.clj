(ns com.blockether.spel.stealth-test
  "Tests for the stealth namespace.

   Unit tests for Chrome launch args, default-arg suppressions and the
   undetected-mode user agent."
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
;; Unit Tests — undetected-user-agent
;; =============================================================================

(defdescribe undetected-user-agent-test
  "Unit tests for undetected-user-agent — headless user agent from the real version"

  (describe "user agent string"
    (it "uses the reduced major version of the launched browser"
      (expect (= "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
                (sut/undetected-user-agent "154.0.7720.3" "Mac OS X"))))

    (it "never contains HeadlessChrome"
      (expect (not (str/includes? (sut/undetected-user-agent "149.0.1" "Linux") "Headless"))))

    (it "uses the frozen platform token of each OS"
      (expect (str/includes? (sut/undetected-user-agent "149" "Linux") "(X11; Linux x86_64)"))
      (expect (str/includes? (sut/undetected-user-agent "149" "Windows 11") "(Windows NT 10.0; Win64; x64)")))

    (it "returns nil without a version"
      (expect (nil? (sut/undetected-user-agent nil "Linux")))
      (expect (nil? (sut/undetected-user-agent "" "Linux"))))))
