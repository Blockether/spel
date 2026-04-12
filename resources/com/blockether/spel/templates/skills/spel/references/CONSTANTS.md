# Constants, enums, device presets

| Namespace | Holds | Count |
|-----------|-------|------:|
| `constants/` | Playwright enum values as flat Clojure vars | 25 |
| `role/` | AriaRole constants for role-based selectors | 82 |
| `device/` | Device preset maps (viewport, UA, scale, touch) | 18 + helpers |

> **Keywords are the primary API** for Playwright enums (`:networkidle`, `:dark`, `:right`, …). `constants/` provides named vars as an alternative; Java enum interop (`LoadState/NETWORKIDLE`) also works.

## Keyword constants

Options layer converts keywords → Java enums automatically.

| Category | Keywords | Used in |
|----------|----------|---------|
| Load state | `:load`, `:domcontentloaded`, `:networkidle` | `wait-for-load-state` |
| Wait-until | `:load`, `:domcontentloaded`, `:networkidle`, `:commit` | `navigate` opts |
| Color scheme | `:light`, `:dark`, `:no-preference` | `emulate-media!`, context opts |
| Mouse button | `:left`, `:right`, `:middle` | `click` opts |
| Screenshot | `:png`, `:jpeg` | `screenshot` opts |
| Selector state | `:attached`, `:detached`, `:visible`, `:hidden` | `wait-for-selector` opts |
| Media | `:screen`, `:print` | `emulate-media!` opts |
| Forced colors | `:active`, `:none` | `emulate-media!` opts |
| Reduced motion | `:reduce`, `:no-preference` | `emulate-media!` opts |

In `eval-sci` mode, string forms also work for load states and selector states (`"networkidle"`, `"hidden"`).

```clojure
;; eval-sci
(spel/wait-for-load-state :networkidle)
(spel/navigate "https://example.org" {:wait-until :commit})
(spel/emulate-media! {:color-scheme :dark})
(spel/click "#element" {:button :right})
(spel/screenshot {:path "/tmp/shot.jpg" :type :jpeg})
(spel/wait-for-selector ".spinner" {:state :hidden})
(spel/emulate-media! {:media :print})

;; Library (same keywords)
(page/wait-for-load-state pg :networkidle)
(page/navigate pg "https://example.org" {:wait-until :commit})
(page/wait-for-selector pg ".spinner" {:state :hidden})
(core/with-testing-page {:color-scheme :dark} [pg] …)
```

## `role/` namespace

```clojure
(spel/get-by-role role/button {:name "Submit"})                    ; eval-sci
(page/get-by-role pg role/button {:name "Submit"})                 ; library
(page/get-by-role pg role/heading {:level 1})
```

### All 82 roles

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

### Common

| Finding | Role | Example |
|---------|------|---------|
| Button | `role/button` | `(spel/get-by-role role/button {:name "Save"})` |
| Link | `role/link` | `(spel/get-by-role role/link {:name "Home"})` |
| Heading | `role/heading` | `(spel/get-by-role role/heading {:level 2})` |
| Text input | `role/textbox` | `(spel/get-by-role role/textbox {:name "Email"})` |
| Checkbox | `role/checkbox` | `(spel/get-by-role role/checkbox {:name "Agree"})` |
| Dropdown | `role/combobox` | `(spel/get-by-role role/combobox {:name "Country"})` |
| Navigation | `role/navigation` | `(spel/get-by-role role/navigation)` |
| Dialog | `role/dialog` | `(spel/get-by-role role/dialog {:name "Confirm"})` |
| Table | `role/table` | `(spel/get-by-role role/table)` |
| Tab | `role/tab` | `(spel/get-by-role role/tab {:name "Settings"})` |

## Device presets

Each preset is a map with `:viewport`, `:device-scale-factor`, `:is-mobile`, `:has-touch`, `:user-agent`. Pass via `:device` keyword in option maps.

### Apple iPhones

| Keyword | Viewport | Scale |
|---------|----------|------:|
| `:iphone-se` | 375×667 | 2 |
| `:iphone-12` / `:iphone-13` / `:iphone-14` | 390×844 | 3 |
| `:iphone-14-pro` / `:iphone-15` / `:iphone-15-pro` | 393×852 | 3 |

### iPads

| Keyword | Viewport | Scale |
|---------|----------|------:|
| `:ipad` | 810×1080 | 2 |
| `:ipad-mini` | 768×1024 | 2 |
| `:ipad-pro-11` | 834×1194 | 2 |
| `:ipad-pro` | 1024×1366 | 2 |

### Android

| Keyword | Viewport | Scale |
|---------|----------|------:|
| `:pixel-5` | 393×851 | 2.75 |
| `:pixel-7` | 412×915 | 2.625 |
| `:galaxy-s24` | 360×780 | 3 |
| `:galaxy-s9` | 360×740 | 3 |

### Desktop

| Keyword | Viewport | Scale |
|---------|----------|------:|
| `:desktop-chrome` / `:desktop-firefox` / `:desktop-safari` | 1280×720 | 1 |

Mobile/tablet presets: `:is-mobile true`, `:has-touch true`. Desktop: both `false`.

```clojure
(spel/start! {:device :iphone-14})                         ; standalone
(core/with-testing-page {:device :iphone-14} [pg] …)       ; library
(spel/set-viewport-size! 390 844)                          ; daemon mode, viewport only
```

### Viewport presets (dimensions only)

| Keyword | Size |
|---------|------|
| `:mobile` / `:mobile-lg` | 375×667 / 428×926 |
| `:tablet` / `:tablet-lg` | 768×1024 / 1024×1366 |
| `:desktop` / `:desktop-hd` / `:desktop-4k` | 1280×720 / 1920×1080 / 3840×2160 |

```clojure
(core/with-testing-page {:viewport :desktop-hd}            [pg] …)
(core/with-testing-page {:viewport {:width 1440 :height 900}} [pg] …)
```

## Java enum interop

All Playwright enum classes registered; direct interop also works.

```clojure
LoadState/NETWORKIDLE      WaitUntilState/COMMIT      ColorScheme/DARK
MouseButton/RIGHT          ScreenshotType/PNG         ForcedColors/ACTIVE
ReducedMotion/REDUCE       Media/PRINT                WaitForSelectorState/HIDDEN
AriaRole/BUTTON
```

Registered classes: `AriaRole`, `ColorScheme`, `ForcedColors`, `HarContentPolicy`, `HarMode`, `HarNotFound`, `LoadState`, `Media`, `MouseButton`, `ReducedMotion`, `RouteFromHarUpdateContentPolicy`, `SameSiteAttribute`, `ScreenshotType`, `ServiceWorkerPolicy`, `WaitForSelectorState`, `WaitUntilState`.
