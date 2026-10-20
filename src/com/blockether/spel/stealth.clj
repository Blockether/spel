(ns com.blockether.spel.stealth
  "Stealth mode for browser automation anti-detection.

   Stealth mode changes only Chromium launch flags. It sets no JavaScript
   patches: patched getters and fake plugin lists are themselves detectable
   (CreepJS and rebrowser-bot-detector flag the tampering), and the launch
   flags already make `navigator.webdriver` false.

   The undetected mode (`spel --undetected`) also replaces the driver with
   Patchright, which removes the CDP leaks (`Runtime.enable`, console) that
   launch flags cannot fix. In headless mode it sets a user agent without
   `HeadlessChrome` that matches the real browser version.

   Usage:
     (stealth-args)                         ;; Chrome launch args
     (stealth-ignore-default-args)          ;; args to suppress
     (undetected-user-agent \"154.0.1.2\") ;; headless user agent"
  (:require
   [clojure.string :as str]))

(defn stealth-args
  "Returns a vector of extra Chrome launch arguments for stealth mode.
   These disable automation-detection features in Blink."
  []
  ["--disable-blink-features=AutomationControlled"])

(defn stealth-ignore-default-args
  "Returns a vector of Chromium default args to suppress in stealth mode.
   Removing --enable-automation prevents the 'Chrome is being controlled
   by automated software' infobar and related automation signals."
  []
  ["--enable-automation"])

(defn- ua-platform-token
  "Returns the frozen platform token that Chrome puts in its user agent."
  [os-name]
  (let [os (str/lower-case (or os-name ""))]
    (cond
      (str/includes? os "mac") "Macintosh; Intel Mac OS X 10_15_7"
      (str/includes? os "win") "Windows NT 10.0; Win64; x64"
      :else                    "X11; Linux x86_64")))

(defn undetected-user-agent
  "Returns the user agent of a headed Chrome for a browser version, or nil.

   Chrome reduces its user agent to `<major>.0.0.0` and a frozen platform
   token. A user agent from a different version than the running browser is
   itself a signal (CreepJS reports a platform and version mismatch), so pass
   the version of the launched browser."
  ([browser-version]
   (undetected-user-agent browser-version (System/getProperty "os.name")))
  ([browser-version os-name]
   (when-let [major (re-find #"^\d+" (str browser-version))]
     (format "Mozilla/5.0 (%s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36"
       (ua-platform-token os-name) major))))
