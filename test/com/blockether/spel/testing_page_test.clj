(ns com.blockether.spel.testing-page-test
  "Lazytest tests for with-testing-page macro and devices namespace.

   Tests both the unit-level device/viewport resolution and the full
   browser lifecycle integration."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.devices :as devices]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [com.microsoft.playwright Page]))

;; =============================================================================
;; Unit Tests — devices namespace
;; =============================================================================

(defdescribe device-presets-unit-test
  "Unit tests for device presets map"

  (describe "known device presets"
    (it "contains iphone-14"
      (expect (some? (get devices/device-presets :iphone-14))))

    (it "contains pixel-7"
      (expect (some? (get devices/device-presets :pixel-7))))

    (it "contains ipad"
      (expect (some? (get devices/device-presets :ipad))))

    (it "contains desktop-chrome"
      (expect (some? (get devices/device-presets :desktop-chrome)))))

  (describe "device preset structure"
    (it "has nested viewport with width and height"
      (let [preset (get devices/device-presets :iphone-14)]
        (expect (map? (:viewport preset)))
        (expect (pos-int? (get-in preset [:viewport :width])))
        (expect (pos-int? (get-in preset [:viewport :height])))))

    (it "has device-scale-factor"
      (let [preset (get devices/device-presets :iphone-14)]
        (expect (number? (:device-scale-factor preset)))))

    (it "has is-mobile and has-touch flags"
      (let [preset (get devices/device-presets :iphone-14)]
        (expect (true? (:is-mobile preset)))
        (expect (true? (:has-touch preset)))))

    (it "has user-agent string"
      (let [preset (get devices/device-presets :iphone-14)]
        (expect (string? (:user-agent preset)))
        (expect (pos? (count (:user-agent preset)))))))

  (describe "desktop presets are non-mobile"
    (it "desktop-chrome is not mobile"
      (let [preset (get devices/device-presets :desktop-chrome)]
        (expect (false? (:is-mobile preset)))
        (expect (false? (:has-touch preset)))))))

(defdescribe viewport-presets-unit-test
  "Unit tests for viewport presets map"

  (describe "known viewport presets"
    (it "contains :mobile"
      (expect (some? (get devices/viewport-presets :mobile))))

    (it "contains :desktop-hd"
      (expect (some? (get devices/viewport-presets :desktop-hd))))

    (it "contains :tablet"
      (expect (some? (get devices/viewport-presets :tablet)))))

  (describe "viewport preset structure"
    (it "has width and height only"
      (let [vp (get devices/viewport-presets :mobile)]
        (expect (= #{:width :height} (set (keys vp))))
        (expect (pos-int? (:width vp)))
        (expect (pos-int? (:height vp)))))))

(defdescribe resolve-device-test
  "Unit tests for resolve-device"

  (describe "valid keywords"
    (it "resolves :iphone-14"
      (let [d (devices/resolve-device :iphone-14)]
        (expect (= 390 (get-in d [:viewport :width])))))

    (it "resolves :pixel-7"
      (let [d (devices/resolve-device :pixel-7)]
        (expect (= 412 (get-in d [:viewport :width]))))))

  (describe "unknown keyword throws"
    (it "throws on :nonexistent"
      (let [threw? (try (devices/resolve-device :nonexistent) false
                     (catch clojure.lang.ExceptionInfo _ true))]
        (expect (true? threw?))))))

(defdescribe resolve-viewport-test
  "Unit tests for resolve-viewport"

  (describe "keyword resolution"
    (it "resolves :mobile to map"
      (let [vp (devices/resolve-viewport :mobile)]
        (expect (= 375 (:width vp)))))

    (it "resolves :desktop-hd"
      (let [vp (devices/resolve-viewport :desktop-hd)]
        (expect (= 1920 (:width vp))))))

  (describe "map passthrough"
    (it "returns map as-is"
      (let [vp (devices/resolve-viewport {:width 800 :height 600})]
        (expect (= 800 (:width vp)))
        (expect (= 600 (:height vp))))))

  (describe "nil returns nil"
    (it "returns nil for nil"
      (expect (nil? (devices/resolve-viewport nil)))))

  (describe "unknown keyword throws"
    (it "throws on :nonexistent"
      (let [threw? (try (devices/resolve-viewport :nonexistent) false
                     (catch clojure.lang.ExceptionInfo _ true))]
        (expect (true? threw?))))))

(defdescribe resolve-device-by-name-test
  "Unit tests for string-based device lookup"

  (describe "case-insensitive lookup"
    (it "resolves lowercase"
      (expect (some? (devices/resolve-device-by-name "iphone 14"))))

    (it "resolves mixed case"
      (expect (some? (devices/resolve-device-by-name "iPhone 14"))))

    (it "resolves uppercase"
      (expect (some? (devices/resolve-device-by-name "PIXEL 7")))))

  (describe "unknown device returns nil"
    (it "returns nil for unknown"
      (expect (nil? (devices/resolve-device-by-name "unknown device")))))

  (describe "available-device-names"
    (it "returns a non-empty sequence"
      (expect (seq (devices/available-device-names))))

    (it "contains iphone 14"
      (expect (some #{"iphone 14"} (devices/available-device-names))))))

;; =============================================================================
;; Integration Tests — with-testing-page
;; =============================================================================

(defdescribe with-testing-page-test
  "Integration tests for with-testing-page macro"

  (describe "basic usage — opts omitted"
    (it "creates a page and navigates without opts"
      (core/with-testing-page [pg]
        (expect (instance? Page pg))
        (page/navigate pg "https://example.com")
        (expect (= "Example Domain" (page/title pg))))))

  (describe "basic usage — empty opts"
    (it "creates a page and navigates with empty opts"
      (core/with-testing-page {} [pg]
        (expect (instance? Page pg))
        (page/navigate pg "https://example.com")
        (expect (= "Example Domain" (page/title pg))))))

  (describe "viewport preset"
    (it "uses :desktop-hd viewport"
      (core/with-testing-page {:viewport :desktop-hd} [pg]
        (page/navigate pg "https://example.com")
        (let [vp (page/viewport-size pg)]
          (expect (= 1920 (:width vp)))
          (expect (= 1080 (:height vp)))))))

  (describe "viewport map"
    (it "uses custom viewport dimensions"
      (core/with-testing-page {:viewport {:width 800 :height 600}} [pg]
        (page/navigate pg "https://example.com")
        (let [vp (page/viewport-size pg)]
          (expect (= 800 (:width vp)))
          (expect (= 600 (:height vp)))))))

  (describe "device preset"
    (it "uses :iphone-14 device emulation"
      (core/with-testing-page {:device :iphone-14} [pg]
        (page/navigate pg "https://example.com")
        (let [vp (page/viewport-size pg)]
          (expect (= 390 (:width vp)))
          (expect (= 844 (:height vp)))))))

  (describe "return value — opts omitted"
    (it "returns the body result"
      (let [result (core/with-testing-page [pg]
                     (page/navigate pg "https://example.com")
                     (page/title pg))]
        (expect (= "Example Domain" result)))))

  (describe "return value — with opts"
    (it "returns the body result"
      (let [result (core/with-testing-page {} [pg]
                     (page/navigate pg "https://example.com")
                     (page/title pg))]
        (expect (= "Example Domain" result))))))
