# Constants, Enums, and Device Presets

Quick reference for all typed constants in spel's `--eval` sandbox and library code.

| Namespace | What it holds | Count |
|-----------|---------------|-------|
| `role/` | AriaRole constants for role-based selectors | 82 |

> **Keyword shorthand** is the primary API for all Playwright enums. Use `:networkidle`, `:dark`, `:right`, etc. directly in option maps. Java enum interop (e.g. `LoadState/NETWORKIDLE`) is also available.

## Keyword Constants (Primary API)

spel functions accept keywords for all Playwright enum values. The options layer converts automatically.

| Category | Keywords | Used in |
|----------|----------|---------|
| Load state | `:load`, `:domcontentloaded`, `:networkidle` | `wait-for-load-state` |
| Wait-until | `:load`, `:domcontentloaded`, `:networkidle`, `:commit` | `navigate` opts |
| Color scheme | `:light`, `:dark`, `:no-preference` | `emulate-media!`, context opts |
| Mouse button | `:left`, `:right`, `:middle` | `click` opts |
| Screenshot type | `:png`, `:jpeg` | `screenshot` opts |
| Selector state | `:attached`, `:detached`, `:visible`, `:hidden` | `wait-for-selector` opts |
| Media | `:screen`, `:print` | `emulate-media!` opts |
| Forced colors | `:active`, `:none` | `emulate-media!` opts |
| Reduced motion | `:reduce`, `:no-preference` | `emulate-media!` opts |

In `--eval` mode, string forms also work for load states and selector states (e.g. `"networkidle"`, `"hidden"`).

### Usage Examples

```clojure
;; --eval mode (keywords)
(spel/wait-for-load-state :networkidle)
(spel/navigate "https://example.org" {:wait-until :commit})
(spel/emulate-media! {:color-scheme :dark})
(spel/click "#element" {:button :right})
(spel/screenshot {:path "/tmp/shot.jpg" :type :jpeg})
(spel/wait-for-selector ".spinner" {:state :hidden})
(spel/emulate-media! {:media :print})

;; Library mode (same keywords)
(page/wait-for-load-state pg :networkidle)
(page/navigate pg "https://example.org" {:wait-until :commit})
(page/wait-for-selector pg ".spinner" {:state :hidden})
(core/with-testing-page {:color-scheme :dark} [pg] ...)
```

## `role/` Namespace

AriaRole constants for `page/get-by-role` and `spel/get-by-role`.

```clojure
(spel/get-by-role role/button {:name "Submit"})                    ;; --eval
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
| Buttons | `role/button` | `(spel/get-by-role role/button {:name "Save"})` |
| Links | `role/link` | `(spel/get-by-role role/link {:name "Home"})` |
| Headings | `role/heading` | `(spel/get-by-role role/heading {:level 2})` |
| Text inputs | `role/textbox` | `(spel/get-by-role role/textbox {:name "Email"})` |
| Checkboxes | `role/checkbox` | `(spel/get-by-role role/checkbox {:name "Agree"})` |
| Dropdowns | `role/combobox` | `(spel/get-by-role role/combobox {:name "Country"})` |
| Navigation | `role/navigation` | `(spel/get-by-role role/navigation)` |
| Dialogs | `role/dialog` | `(spel/get-by-role role/dialog {:name "Confirm"})` |
| Tables | `role/table` | `(spel/get-by-role role/table)` |
| Tabs | `role/tab` | `(spel/get-by-role role/tab {:name "Settings"})` |

## Device Presets

Device presets are used via the `:device` keyword in option maps. Each preset configures viewport, device scale factor, mobile flag, touch support, and user agent.

> **Note**: Device presets are used as keywords in option maps (`:iphone-14`, `:pixel-7`). They are available in library mode and `--eval` with `spel/start!`. There is no `device/` namespace in `--eval` mode.

### All Device Presets

#### Apple iPhones

| Keyword | Viewport | Scale |
|---------|----------|-------|
| `:iphone-se` | 375 x 667 | 2 |
| `:iphone-12` | 390 x 844 | 3 |
| `:iphone-13` | 390 x 844 | 3 |
| `:iphone-14` | 390 x 844 | 3 |
| `:iphone-14-pro` | 393 x 852 | 3 |
| `:iphone-15` | 393 x 852 | 3 |
| `:iphone-15-pro` | 393 x 852 | 3 |

#### Apple iPads

| Keyword | Viewport | Scale |
|---------|----------|-------|
| `:ipad` | 810 x 1080 | 2 |
| `:ipad-mini` | 768 x 1024 | 2 |
| `:ipad-pro-11` | 834 x 1194 | 2 |
| `:ipad-pro` | 1024 x 1366 | 2 |

#### Android

| Keyword | Viewport | Scale |
|---------|----------|-------|
| `:pixel-5` | 393 x 851 | 2.75 |
| `:pixel-7` | 412 x 915 | 2.625 |
| `:galaxy-s24` | 360 x 780 | 3 |
| `:galaxy-s9` | 360 x 740 | 3 |

#### Desktop

| Keyword | Viewport | Scale |
|---------|----------|-------|
| `:desktop-chrome` | 1280 x 720 | 1 |
| `:desktop-firefox` | 1280 x 720 | 1 |
| `:desktop-safari` | 1280 x 720 | 1 |

All mobile/tablet presets have `:is-mobile true` and `:has-touch true`. Desktop presets have both `false`.

### Using Devices

```clojure
;; Standalone --eval (no daemon)
(spel/start! {:device :iphone-14})

;; Library
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.org"))

;; Manual viewport sizing in daemon mode
(spel/set-viewport-size! 390 844)
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
