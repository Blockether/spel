# Constants, Enums, and Device Presets

Quick reference for all typed constants in spel's `--eval` sandbox and library code.

| Namespace | What it holds | Count |
|-----------|---------------|-------|
| `constants/` | Playwright enum values as flat Clojure vars | 25 |
| `role/` | AriaRole constants for role-based selectors | 82 |
| `device/` | Device preset maps (viewport, UA, scale, touch) | 18 |

## `constants/` Namespace

Playwright enum values exposed as Clojure vars. Flat naming: `constants/<category>-<value>`.

### Load States

| Var | Java equivalent | Fires when |
|-----|-----------------|------------|
| `constants/load-state-load` | `LoadState/LOAD` | All resources loaded |
| `constants/load-state-domcontentloaded` | `LoadState/DOMCONTENTLOADED` | HTML parsed, deferred scripts done |
| `constants/load-state-networkidle` | `LoadState/NETWORKIDLE` | No network requests for 500ms |

```clojure
(spel/wait-for-load :networkidle)                                ;; --eval
(page/wait-for-load-state pg constants/load-state-networkidle)    ;; library
```

### Wait-Until States

| Var | Java equivalent | Fires when |
|-----|-----------------|------------|
| `constants/wait-until-load` | `WaitUntilState/LOAD` | All resources loaded |
| `constants/wait-until-domcontentloaded` | `WaitUntilState/DOMCONTENTLOADED` | HTML parsed |
| `constants/wait-until-networkidle` | `WaitUntilState/NETWORKIDLE` | Network quiet for 500ms |
| `constants/wait-until-commit` | `WaitUntilState/COMMIT` | Response headers received |

```clojure
(spel/navigate "https://example.com" {:wait-until :networkidle})    ;; --eval
(page/navigate pg "https://example.com" {:wait-until :commit})    ;; library
```

### Color Schemes

| Var | Java equivalent |
|-----|-----------------|
| `constants/color-scheme-dark` | `ColorScheme/DARK` |
| `constants/color-scheme-light` | `ColorScheme/LIGHT` |
| `constants/color-scheme-no-preference` | `ColorScheme/NO_PREFERENCE` |

```clojure
(spel/emulate-media! {:color-scheme :dark})                       ;; --eval
(core/with-testing-page {:color-scheme :dark} [pg] ...)           ;; library
```

### Mouse Buttons

| Var | Java equivalent |
|-----|-----------------|
| `constants/mouse-button-left` | `MouseButton/LEFT` |
| `constants/mouse-button-right` | `MouseButton/RIGHT` |
| `constants/mouse-button-middle` | `MouseButton/MIDDLE` |

```clojure
(spel/click "#element" {:button :right})
```

### Screenshot Types

| Var | Java equivalent |
|-----|-----------------|
| `constants/screenshot-type-png` | `ScreenshotType/PNG` |
| `constants/screenshot-type-jpeg` | `ScreenshotType/JPEG` |

```clojure
(spel/screenshot {:path "/tmp/shot.jpg" :type :jpeg})
```

### Forced Colors

| Var | Java equivalent |
|-----|-----------------|
| `constants/forced-colors-active` | `ForcedColors/ACTIVE` |
| `constants/forced-colors-none` | `ForcedColors/NONE` |

### Reduced Motion

| Var | Java equivalent |
|-----|-----------------|
| `constants/reduced-motion-reduce` | `ReducedMotion/REDUCE` |
| `constants/reduced-motion-no-preference` | `ReducedMotion/NO_PREFERENCE` |

### Media Type

| Var | Java equivalent |
|-----|-----------------|
| `constants/media-screen` | `Media/SCREEN` |
| `constants/media-print` | `Media/PRINT` |

```clojure
(spel/emulate-media! {:media :print})    ;; emulate print media for PDF
```

### Selector States

| Var | Java equivalent | Meaning |
|-----|-----------------|---------|
| `constants/selector-state-attached` | `WaitForSelectorState/ATTACHED` | Element in DOM (may be hidden) |
| `constants/selector-state-detached` | `WaitForSelectorState/DETACHED` | Element not in DOM |
| `constants/selector-state-visible` | `WaitForSelectorState/VISIBLE` | Exists and visible |
| `constants/selector-state-hidden` | `WaitForSelectorState/HIDDEN` | Missing or not visible |

```clojure
(spel/wait-for ".spinner" {:state :hidden})                        ;; --eval
(page/wait-for-selector pg ".spinner" {:state :hidden})           ;; library
```

### Keyword Shorthand

Most spel functions accept keywords instead of constants. The options layer converts automatically.

| Category | Keywords |
|----------|----------|
| Load state | `:load`, `:domcontentloaded`, `:networkidle` |
| Wait-until | `:load`, `:domcontentloaded`, `:networkidle`, `:commit` |
| Color scheme | `:light`, `:dark`, `:no-preference` |
| Mouse button | `:left`, `:right`, `:middle` |
| Screenshot type | `:png`, `:jpeg` |
| Selector state | `:attached`, `:detached`, `:visible`, `:hidden` |
| Media | `:screen`, `:print` |

In `--eval` mode, string forms also work for load states and selector states (e.g. `"networkidle"`, `"hidden"`).

## `role/` Namespace

AriaRole constants for `page/get-by-role` and `spel/$role`.

```clojure
(spel/$role role/button {:name "Submit"})                         ;; --eval
(page/get-by-role pg role/button {:name "Submit"})                ;; library
(page/get-by-role pg role/heading {:level 1})                     ;; with options
```

### Complete Role List (82 constants)

| | | | |
|---|---|---|---|
| `role/alert` | `role/alertdialog` | `role/application` | `role/article` |
| `role/banner` | `role/blockquote` | `role/button` | `role/caption` |
| `role/cell` | `role/checkbox` | `role/code` | `role/columnheader` |
| `role/combobox` | `role/complementary` | `role/contentinfo` | `role/definition` |
| `role/deletion` | `role/dialog` | `role/directory` | `role/document` |
| `role/emphasis` | `role/feed` | `role/figure` | `role/form` |
| `role/generic` | `role/grid` | `role/gridcell` | `role/group` |
| `role/heading` | `role/img` | `role/insertion` | `role/link` |
| `role/list` | `role/listbox` | `role/listitem` | `role/log` |
| `role/main` | `role/marquee` | `role/math` | `role/meter` |
| `role/menu` | `role/menubar` | `role/menuitem` | `role/menuitemcheckbox` |
| `role/menuitemradio` | `role/navigation` | `role/none` | `role/note` |
| `role/option` | `role/paragraph` | `role/presentation` | `role/progressbar` |
| `role/radio` | `role/radiogroup` | `role/region` | `role/row` |
| `role/rowgroup` | `role/rowheader` | `role/scrollbar` | `role/search` |
| `role/searchbox` | `role/separator` | `role/slider` | `role/spinbutton` |
| `role/status` | `role/strong` | `role/subscript` | `role/superscript` |
| `role/switch` | `role/tab` | `role/table` | `role/tablist` |
| `role/tabpanel` | `role/term` | `role/textbox` | `role/time` |
| `role/timer` | `role/toolbar` | `role/tooltip` | `role/tree` |
| `role/treegrid` | `role/treeitem` | | |

### Common Roles

| Finding... | Role | Example |
|------------|------|---------|
| Buttons | `role/button` | `(spel/$role role/button {:name "Save"})` |
| Links | `role/link` | `(spel/$role role/link {:name "Home"})` |
| Headings | `role/heading` | `(spel/$role role/heading {:level 2})` |
| Text inputs | `role/textbox` | `(spel/$role role/textbox {:name "Email"})` |
| Checkboxes | `role/checkbox` | `(spel/$role role/checkbox {:name "Agree"})` |
| Dropdowns | `role/combobox` | `(spel/$role role/combobox {:name "Country"})` |
| Navigation | `role/navigation` | `(spel/$role role/navigation)` |
| Dialogs | `role/dialog` | `(spel/$role role/dialog {:name "Confirm"})` |
| Tables | `role/table` | `(spel/$role role/table)` |
| Tabs | `role/tab` | `(spel/$role role/tab {:name "Settings"})` |

## `device/` Namespace

Device preset maps from `com.blockether.spel.devices/device-presets`. Each var is a map:

```clojure
device/iphone-14
;; => {:viewport {:width 390 :height 844}
;;     :device-scale-factor 3
;;     :is-mobile true
;;     :has-touch true
;;     :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 ...) ..."}
```

### All Device Presets

#### Apple iPhones

| Var | Viewport | Scale |
|-----|----------|-------|
| `device/iphone-se` | 375 x 667 | 2 |
| `device/iphone-12` | 390 x 844 | 3 |
| `device/iphone-13` | 390 x 844 | 3 |
| `device/iphone-14` | 390 x 844 | 3 |
| `device/iphone-14-pro` | 393 x 852 | 3 |
| `device/iphone-15` | 393 x 852 | 3 |
| `device/iphone-15-pro` | 393 x 852 | 3 |

#### Apple iPads

| Var | Viewport | Scale |
|-----|----------|-------|
| `device/ipad` | 810 x 1080 | 2 |
| `device/ipad-mini` | 768 x 1024 | 2 |
| `device/ipad-pro-11` | 834 x 1194 | 2 |
| `device/ipad-pro` | 1024 x 1366 | 2 |

#### Android

| Var | Viewport | Scale |
|-----|----------|-------|
| `device/pixel-5` | 393 x 851 | 2.75 |
| `device/pixel-7` | 412 x 915 | 2.625 |
| `device/galaxy-s24` | 360 x 780 | 3 |
| `device/galaxy-s9` | 360 x 740 | 3 |

#### Desktop

| Var | Viewport | Scale |
|-----|----------|-------|
| `device/desktop-chrome` | 1280 x 720 | 1 |
| `device/desktop-firefox` | 1280 x 720 | 1 |
| `device/desktop-safari` | 1280 x 720 | 1 |

All mobile/tablet presets have `:is-mobile true` and `:has-touch true`. Desktop presets have both `false`.

### Using Devices

```clojure
;; Daemon: use CLI to set device
;; $ spel set device "iPhone 14"

;; Standalone --eval (no daemon)
(spel/start! {:device :iphone-14})

;; Library
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.com"))

;; Extract viewport from a preset (daemon mode)
(let [{:keys [viewport]} device/iphone-14]
  (spel/set-viewport-size! (:width viewport) (:height viewport)))
```

### Viewport Presets (dimensions only)

| Keyword | Size |
|---------|------|
| `:mobile` | 375 x 667 |
| `:mobile-lg` | 428 x 926 |
| `:tablet` | 768 x 1024 |
| `:tablet-lg` | 1024 x 1366 |
| `:desktop` | 1280 x 720 |
| `:desktop-hd` | 1920 x 1080 |
| `:desktop-4k` | 3840 x 2160 |

```clojure
(core/with-testing-page {:viewport :desktop-hd} [pg] ...)
(core/with-testing-page {:viewport {:width 1440 :height 900}} [pg] ...)
```

## Java Enum Interop

All Playwright enum classes are registered in `--eval`. Direct interop works too:

```clojure
LoadState/NETWORKIDLE    WaitUntilState/COMMIT    ColorScheme/DARK
MouseButton/RIGHT        ScreenshotType/PNG       ForcedColors/ACTIVE
ReducedMotion/REDUCE     Media/PRINT              WaitForSelectorState/HIDDEN
AriaRole/BUTTON
```

Registered classes: `AriaRole`, `ColorScheme`, `ForcedColors`, `HarContentPolicy`, `HarMode`, `HarNotFound`, `LoadState`, `Media`, `MouseButton`, `ReducedMotion`, `RouteFromHarUpdateContentPolicy`, `SameSiteAttribute`, `ScreenshotType`, `ServiceWorkerPolicy`, `WaitForSelectorState`, `WaitUntilState`.
