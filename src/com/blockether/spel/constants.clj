(ns com.blockether.spel.constants
  "Clojure vars wrapping Playwright enum values as flat, documented names.

   Eliminates the need for Java enum interop in eval-sci mode.
   Flat naming: `constants/<category>-<value>`.

   Usage:
     (require '[com.blockether.spel.constants :as constants])
     (page/wait-for-load-state pg constants/load-state-networkidle)
     (page/navigate pg url {:wait-until constants/wait-until-commit})"
  (:import
   [com.microsoft.playwright.options
    ColorScheme ForcedColors LoadState Media MouseButton
    ReducedMotion ScreenshotType WaitForSelectorState WaitUntilState]))

;; --- Load States ---

(def load-state-load              LoadState/LOAD)
(def load-state-domcontentloaded  LoadState/DOMCONTENTLOADED)
(def load-state-networkidle       LoadState/NETWORKIDLE)

;; --- Wait-Until States ---

(def wait-until-load              WaitUntilState/LOAD)
(def wait-until-domcontentloaded  WaitUntilState/DOMCONTENTLOADED)
(def wait-until-networkidle       WaitUntilState/NETWORKIDLE)
(def wait-until-commit            WaitUntilState/COMMIT)

;; --- Color Schemes ---

(def color-scheme-dark            ColorScheme/DARK)
(def color-scheme-light           ColorScheme/LIGHT)
(def color-scheme-no-preference   ColorScheme/NO_PREFERENCE)

;; --- Mouse Buttons ---

(def mouse-button-left            MouseButton/LEFT)
(def mouse-button-right           MouseButton/RIGHT)
(def mouse-button-middle          MouseButton/MIDDLE)

;; --- Screenshot Types ---

(def screenshot-type-png          ScreenshotType/PNG)
(def screenshot-type-jpeg         ScreenshotType/JPEG)

;; --- Forced Colors ---

(def forced-colors-active         ForcedColors/ACTIVE)
(def forced-colors-none           ForcedColors/NONE)

;; --- Reduced Motion ---

(def reduced-motion-reduce        ReducedMotion/REDUCE)
(def reduced-motion-no-preference ReducedMotion/NO_PREFERENCE)

;; --- Media Type ---

(def media-screen                 Media/SCREEN)
(def media-print                  Media/PRINT)

;; --- Selector States ---

(def selector-state-attached      WaitForSelectorState/ATTACHED)
(def selector-state-detached      WaitForSelectorState/DETACHED)
(def selector-state-visible       WaitForSelectorState/VISIBLE)
(def selector-state-hidden        WaitForSelectorState/HIDDEN)
