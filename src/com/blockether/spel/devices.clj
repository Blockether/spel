(ns com.blockether.spel.devices
  "Device and viewport presets for browser context emulation.

   Provides keyword-based device presets (e.g. `:iphone-14`, `:pixel-7`)
   and viewport presets (e.g. `:desktop-hd`, `:tablet`, `:mobile`) that
   can be used with `with-testing-page` and `new-context` options.

   Each device preset includes: viewport dimensions, device-scale-factor,
   is-mobile, has-touch, and user-agent string.

   Viewport presets are simpler — just :width and :height."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Device Presets
;; =============================================================================

(def device-presets
  "Map of keyword → device descriptor map.

   Each descriptor contains keys matching `new-context` options:
     :viewport             - {:width N :height N}
     :device-scale-factor  - Double
     :is-mobile            - Boolean
     :has-touch            - Boolean
     :user-agent           - String

   Usage:
     (get device-presets :iphone-14)
     ;; => {:viewport {:width 390 :height 844} :device-scale-factor 3 ...}"
  {;; --- Apple iPhones ---
   :iphone-se       {:viewport {:width 375  :height 667}
                     :device-scale-factor 2 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1"}
   :iphone-12       {:viewport {:width 390  :height 844}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1"}
   :iphone-13       {:viewport {:width 390  :height 844}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1"}
   :iphone-14       {:viewport {:width 390  :height 844}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   :iphone-14-pro   {:viewport {:width 393  :height 852}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   :iphone-15       {:viewport {:width 393  :height 852}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"}
   :iphone-15-pro   {:viewport {:width 393  :height 852}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"}

   ;; --- Apple iPads ---
   :ipad            {:viewport {:width 810  :height 1080}
                     :device-scale-factor 2 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   :ipad-mini       {:viewport {:width 768  :height 1024}
                     :device-scale-factor 2 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   :ipad-pro-11     {:viewport {:width 834  :height 1194}
                     :device-scale-factor 2 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   :ipad-pro        {:viewport {:width 1024 :height 1366}
                     :device-scale-factor 2 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}

   ;; --- Android ---
   :pixel-5         {:viewport {:width 393  :height 851}
                     :device-scale-factor 2.75 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.0.0 Mobile Safari/537.36"}
   :pixel-7         {:viewport {:width 412  :height 915}
                     :device-scale-factor 2.625 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"}
   :galaxy-s24      {:viewport {:width 360  :height 780}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (Linux; Android 14; SM-S921U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"}
   :galaxy-s9       {:viewport {:width 360  :height 740}
                     :device-scale-factor 3 :is-mobile true :has-touch true
                     :user-agent "Mozilla/5.0 (Linux; Android 8.0.0; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"}

   ;; --- Desktop ---
   :desktop-chrome  {:viewport {:width 1280 :height 720}
                     :device-scale-factor 1 :is-mobile false :has-touch false
                     :user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"}
   :desktop-firefox {:viewport {:width 1280 :height 720}
                     :device-scale-factor 1 :is-mobile false :has-touch false
                     :user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"}
   :desktop-safari  {:viewport {:width 1280 :height 720}
                     :device-scale-factor 1 :is-mobile false :has-touch false
                     :user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"}})

;; =============================================================================
;; Viewport Presets (dimensions only — no user-agent or device flags)
;; =============================================================================

(def viewport-presets
  "Map of keyword → viewport dimensions {:width N :height N}.

   Simpler than device presets — just sets the viewport size without
   changing user-agent, device-scale-factor, etc.

   Usage:
     (get viewport-presets :mobile)
     ;; => {:width 375 :height 667}"
  {:mobile       {:width 375  :height 667}
   :mobile-lg    {:width 428  :height 926}
   :tablet       {:width 768  :height 1024}
   :tablet-lg    {:width 1024 :height 1366}
   :desktop      {:width 1280 :height 720}
   :desktop-hd   {:width 1920 :height 1080}
   :desktop-4k   {:width 3840 :height 2160}})

;; =============================================================================
;; String-keyed lookup (for CLI / daemon)
;; =============================================================================

(def ^:private string->keyword-device
  "Maps lowercase string names (e.g. \"iphone 14\") to device-preset keywords.
   Built from device-presets keys: :iphone-14 → \"iphone 14\"."
  (into {}
    (map (fn [k]
           [(-> (name k)
              (str/replace "-" " "))
            k]))
    (keys device-presets)))

(defn resolve-device-by-name
  "Resolves a case-insensitive device name string to its descriptor.

   Used by the daemon for CLI `set_device` commands.

   Params:
     `device-name` - String (e.g. \"iPhone 14\", \"pixel 7\", \"desktop chrome\").

   Returns:
     Device descriptor map (same shape as `device-presets` values),
     or nil if not found."
  [device-name]
  (when-let [kw (get string->keyword-device
                  (str/lower-case (or device-name "")))]
    (get device-presets kw)))

(defn available-device-names
  "Returns sorted sequence of string device names accepted by `resolve-device-by-name`."
  []
  (sort (keys string->keyword-device)))

;; =============================================================================
;; Resolution
;; =============================================================================

(defn resolve-device
  "Resolves a device keyword to its descriptor map.

   Params:
     `device` - Keyword (e.g. :iphone-14, :pixel-7).

   Returns:
     Device descriptor map, or throws if not found.

   Example:
     (resolve-device :iphone-14)
     ;; => {:viewport {:width 390 :height 844} :device-scale-factor 3 ...}"
  [device]
  (or (get device-presets device)
    (throw (ex-info (str "Unknown device preset: " (pr-str device)
                      ". Available: " (pr-str (sort (keys device-presets))))
             {:device device
              :available (sort (keys device-presets))}))))

(defn resolve-viewport
  "Resolves a viewport keyword or map to {:width N :height N}.

   Accepts:
     - A keyword (e.g. :mobile, :desktop-hd) → looks up viewport-presets
     - A map with :width/:height → returned as-is
     - nil → returns nil (use default)

   Returns:
     {:width N :height N}, or nil."
  [viewport]
  (cond
    (keyword? viewport) (or (get viewport-presets viewport)
                          (throw (ex-info (str "Unknown viewport preset: " (pr-str viewport)
                                            ". Available: " (pr-str (sort (keys viewport-presets))))
                                   {:viewport viewport
                                    :available (sort (keys viewport-presets))})))
    (map? viewport)     viewport
    (nil? viewport)     nil
    :else               (throw (ex-info (str "Invalid viewport value: " (pr-str viewport)
                                          ". Expected keyword, map, or nil.")
                                 {:viewport viewport}))))
