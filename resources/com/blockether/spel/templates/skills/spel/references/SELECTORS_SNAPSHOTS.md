# Selectors, snapshots, annotations

Find elements, read page structure, produce visual overlays. Covers `eval-sci` mode (implicit page) + library mode (explicit `page` arg).

## Selectors

Every `spel/` fn taking `sel` is polymorphic. Accepts:

1. CSS selector string: `"#id"`, `".class"`, `"button"`
2. Snapshot ref string: `"@e2yrjz"` (from `spel/capture-snapshot`, `@` prefix required)
3. Locator object (pass-through, no resolution)

`spel/click`, `spel/fill`, `spel/text-content`, `spel/visible?`, every `sel`-accepting fn works regardless of target type.

### CSS Selectors

```clojure
(spel/locator "#login-form")          ;; by ID
(spel/locator ".nav-item")            ;; by class
(spel/locator "div > p")              ;; child combinator
(spel/locator "input[type=email]")    ;; attribute selector

;; Use directly in actions (no spel/locator needed)
(spel/click "#submit")
(spel/fill "input[name=email]" "test@example.org")
(spel/text-content "h1.title")
```

`spel/locator` returns Playwright Locator. Only needed when storing for reuse.

### Semantic Selectors

```clojure
;; By visible text content
(spel/get-by-text "Click me")
(spel/click (spel/get-by-text "Sign in"))

;; By ARIA role (82 constants in role/ namespace)
(spel/get-by-role role/button)
(spel/get-by-role role/button {:name "Submit"})
(spel/get-by-role role/heading {:name "Installation" :exact true})
(spel/click (spel/get-by-role role/link {:name "Home"}))

;; By label (<label> association)
(spel/get-by-label "Email")
(spel/fill (spel/get-by-label "Email") "user@example.org")

;; By placeholder text
(spel/get-by-placeholder "Search...")
(spel/fill (spel/get-by-placeholder "Enter your name") "Alice")

;; By test ID (data-testid attribute)
(spel/get-by-test-id "submit-btn")
(spel/click (spel/get-by-test-id "nav-menu"))

;; By alt text
(spel/get-by-alt-text "Logo")

;; By title attribute
(spel/get-by-title "Close dialog")
```

Common roles: `role/button`, `role/link`, `role/heading`, `role/textbox`, `role/checkbox`, `role/radio`, `role/combobox`, `role/navigation`, `role/dialog`, `role/tab`, `role/tabpanel`, `role/list`, `role/listitem`, `role/img`, `role/table`, `role/row`, `role/cell`.

### Snapshot ref selectors

After `(spel/capture-snapshot)`, every interactive element gets ref ID like `@e2yrjz`, `@e9mter`. Use directly:

```clojure
(def snap (spel/capture-snapshot))
;; snap => {:tree "- heading \"Welcome\" [@e2yrjz]\n- link \"Login\" [@e9mter]" ...}

(spel/click "@e2yrjz")              ;; click by ref (@ prefix required)
(spel/text-content "@e9mter")        ;; get text of ref @e9mter
(spel/fill "@ea3kf5" "hello")        ;; fill input @ea3kf5
```

Refs resolved via `data-pw-ref` attribute injected during snapshot capture.

## Multiple Elements

Selector matching multiple elements → Playwright strict mode throws. Options:

```clojure
;; All matches as vector of Locators
(locator/all (spel/locator "a"))
(locator/all (spel/locator ".card"))

;; All texts at once
(spel/all-text-contents "a")   ;; => ["Home" "About" "Contact"]
(spel/all-inner-texts ".item") ;; => ["Item 1" "Item 2" "Item 3"]

;; Count matches
(spel/count-of "li")           ;; => 12

;; Narrow to one element
(spel/first "li")              ;; first match
(spel/last "li")               ;; last match
(spel/nth "li" 2)              ;; third match (0-indexed)
```

### Filtering within locator

```clojure
(spel/loc-locator ".card" "h2")                    ;; sub-selector
(spel/loc-get-by-text ".card" "Premium")            ;; filter by text
(spel/loc-get-by-role ".nav" role/link)             ;; filter by role
(spel/loc-get-by-label "form" "Email")              ;; filter by label
(spel/loc-get-by-test-id ".sidebar" "menu-toggle")  ;; filter by test ID
(spel/loc-filter ".card" {:has-text "Premium"})     ;; generic filter
(spel/loc-filter ".card" {:has (spel/get-by-text "Buy now")})
```

Rule: selector might match multiple elements → make more specific, use `spel/first`, or use semantic locator (role + name).

## Accessibility Snapshots

Snapshots give structured view of page, as screen reader sees it. Every interactive element gets numbered ref usable as selector.

### Capturing

```clojure
(def snap (spel/capture-snapshot))
```

Returns map with three keys:

| Key | Type | Description |
|-----|------|-------------|
| `:tree` | String | Human-readable accessibility tree with `[@eN]` annotations |
| `:refs` | Map | `{"e2yrjz" {:role "heading" :name "Welcome" :tag "h1" :bbox {...}} ...}` |
| `:counter` | Long | Total refs assigned |

### Tree Output

`:tree` string is YAML-like indented tree. Real example from a news site:

```
- banner:
  - heading "Onet" [@e2yrjz] [level=1] [pos:20,10 200×40]
  - navigation "Main":
    - link "News" [@e9mter] [pos:50,60 80×20]
    - link "Sport" [@e6t2x4] [pos:140,60 80×20]
    - link "Business" [@e0k8qp] [pos:230,60 100×20]
  - search:
    - searchbox "Search Onet" [@ea3kf5] [pos:400,15 200×30]
    - button "Search" [@e1x9hz] [pos:610,15 60×30]
- main:
  - heading "Top Stories" [@e3pq7r] [level=2] [pos:20,100 300×35]
  - article:
    - link "Breaking: Major Event" [@e5dw2c] [pos:20,150 400×24]
    - paragraph "Details about the event..."
- contentinfo:
  - link "Privacy Policy" [@e7vnw3] [pos:20,500 100×18]
  - link "Terms of Service" [@e8jy4n] [pos:140,500 120×18]
```

Each `[@eN]` marks interactive/meaningful element. Structural roles (banner, main, navigation) appear without refs. `[level=1]` shows ARIA properties. `[pos:X,Y W×H]` shows screen position + dimensions.

### Ref Maps

Each ref in `:refs`:

```clojure
{"e2yrjz" {:role "heading" :name "Onet" :tag "h1"
       :bbox {:x 20 :y 10 :width 200 :height 40}}}
```

`:bbox` gives pixel coordinates relative to page, useful for annotation placement.

### Scoped + full snapshots

```clojure
;; Scope to subtree (CSS selector or ref)
(spel/capture-snapshot {:scope "#main"})
(spel/capture-snapshot {:scope "@e3pq7r"})

;; Capture all iframes too (refs prefixed: f1_e1, f2_e3)
(spel/capture-full-snapshot)
```

### Resolving + clearing refs

```clojure
(spel/resolve-ref "@e2yrjz")       ;; => Playwright Locator
(spel/clear-refs!)             ;; remove data-pw-ref attributes from DOM
```

Moved to new page since last snapshot → refs stale. Take fresh snapshot.

### Computed styles for visual comparison

Two elements with **identical geometry** (`[pos:X,Y W×H]`) but visually different → mismatch caused by child-level styles (padding, font-size, min-height). Use `-S` / `--styles` to surface computed CSS per element:

```bash
# Compare toolbar controls with computed styles
spel snapshot -i -S --minimal -s "[data-component='ComponentA'] > div:first-child"
spel snapshot -i -S --minimal -s "[data-component='ComponentB'] > div:first-child"
```

Output includes style data inline:
```
- button "Sync Now" [@e8nh6a] [pos:346,112 32×28] {height:28px;padding:6px 12px;font-size:14px}
- combobox [@e65fyp] [pos:252,112 86×34] {height:34px;padding:6px 8px;font-size:14px}
```

Immediately reveals: button 28px vs select 34px, button wider padding (12px vs 8px).

**Style detail levels:**

| Flag | Properties | Use when |
|---|---|---|
| `--minimal` | 16 props (height, padding, font-size, gap, border) | Quick visual weight comparison |
| *(default)* | 31 props (+ colors, display, position, overflow) | Layout debugging |
| `--max` | 44 props (+ transforms, transitions, z-index) | Full style audit |

**Styles vs geometry:**

| Question | Tool |
|---|---|
| Same size? | `snapshot -i` (check `[pos:W×H]`) |
| Why do same-size elements look different? | `snapshot -i -S --minimal` (compare padding, font, min-height) |
| Full visual regression baseline | `snapshot -i -S` (default 31 props) |

## Annotations

Annotations inject visual overlays: bounding boxes, ref badges, dimension labels. Rendered in-browser via CSS/JS. No external image processing.

### Annotated Screenshots

Most common workflow. Capture snapshot → screenshot with overlays:

```clojure
(def snap (spel/capture-snapshot))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png")
```

Injects overlays → screenshots → removes them. Page left clean.

For bytes instead of file: `(spel/annotated-screenshot (:refs snap))`

Options:

```clojure
(spel/save-annotated-screenshot! refs "/tmp/nav.png" {:scope "#navigation"})
(spel/save-annotated-screenshot! refs "/tmp/full.png" {:full-page true})
(spel/save-annotated-screenshot! refs "/tmp/clean.png"
  {:show-badges false :show-dimensions false :show-boxes false})
```

### Manual overlay control

Keep overlays visible for headed debugging or multiple screenshots:

```clojure
(spel/inject-overlays! (:refs snap))   ;; inject (returns count of annotated elements)
(spel/screenshot {:path "/tmp/with-overlays.png"})
(spel/remove-overlays!)              ;; remove when done
```

### Pre-Action Markers

Highlight elements before interaction. Visually distinct from annotations: bright red/orange pulsing border with `-> eN` label.

```clojure
(spel/inject-action-markers! "@e2yrjz" "@ea3kf5")         ;; mark elements about to interact with
(spel/screenshot {:path "/tmp/before-click.png"})
(spel/click "@ea3kf5")
(spel/remove-action-markers!)                  ;; clean up
```

Markers use `data-spel-action-marker`, don't interfere with annotation overlays. Both can be active at once.

### Playwright's built-in highlight

```clojure
(spel/highlight "@e6t2x4")          ;; Playwright native highlight (brief flash)
(spel/highlight "#submit")     ;; works with CSS selectors too
```

Unlike annotations, doesn't persist.

## Audit Screenshots

Screenshots with caption bar at bottom. Documenting workflow steps or building visual reports.

```clojure
;; Simple caption
(spel/save-audit-screenshot! "Step 1: Login page loaded" "/tmp/step1.png")

;; Caption + annotation overlays
(spel/save-audit-screenshot! "Step 2: Form filled" "/tmp/step2.png"
  {:refs (:refs snap)})

;; Caption + action markers on specific refs
(spel/save-audit-screenshot! "Step 3: About to click Submit" "/tmp/step3.png"
  {:refs (:refs snap) :markers ["@e1x9hz"]})
```

For bytes: `(spel/audit-screenshot "Caption text")`

## Snapshot Testing

Assert element's accessibility structure matches expected ARIA snapshot string:

```clojure
(spel/assert-matches-aria-snapshot "h1" "- heading \"Welcome\" [level=1]")

(spel/assert-matches-aria-snapshot "#nav"
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

Regression testing. Changed heading level or link text → assertion fails with clear diff.

Library equivalent:

```clojure
(assert/matches-aria-snapshot
  (assert/assert-that (page/locator pg "#nav"))
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

## Complete workflow example

Open page → understand structure → annotate → interact → verify:

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
;; 1. Capture page structure
(def snap (spel/capture-snapshot))
(println (:tree snap))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/hn-annotated.png")
(spel/inject-action-markers! "@e9mter")
(spel/screenshot {:path "/tmp/hn-before-click.png"})
(spel/remove-action-markers!)
(spel/click "@e9mter")
(spel/wait-for-load-state)
;; 5. Verify landed on right page
(spel/assert-visible "h1")
(println "Now at:" (spel/url))
(spel/save-audit-screenshot! "After clicking first link" "/tmp/hn-result.png")
```

## Quick Reference

| Task | `eval-sci` | Library |
|------|----------|---------|
| CSS locator | `(spel/locator "sel")` | `(page/locator pg "sel")` |
| All matches | `(locator/all (spel/locator "sel"))` | `(locator/all (page/locator pg "sel"))` |
| By text | `(spel/get-by-text "t")` | `(page/get-by-text pg "t")` |
| By role | `(spel/get-by-role role/button)` | `(page/get-by-role pg role/button)` |
| By label | `(spel/get-by-label "t")` | `(page/get-by-label pg "t")` |
| By placeholder | `(spel/get-by-placeholder "t")` | `(page/get-by-placeholder pg "t")` |
| By test ID | `(spel/get-by-test-id "id")` | `(page/get-by-test-id pg "id")` |
| By alt text | `(spel/get-by-alt-text "t")` | `(page/get-by-alt-text pg "t")` |
| Snapshot | `(spel/capture-snapshot)` | `(snapshot/capture-snapshot pg)` |
| Full snapshot | `(spel/capture-full-snapshot)` | `(snapshot/capture-full-snapshot pg)` |
| Resolve ref | `(spel/resolve-ref "@e2yrjz")` | `(snapshot/resolve-ref pg "e2yrjz")` |
| Annotated shot | `(spel/save-annotated-screenshot! refs path)` | `(annotate/save-annotated-screenshot! pg refs path)` |
| Audit shot | `(spel/save-audit-screenshot! caption path)` | `(annotate/save-audit-screenshot! pg caption path)` |
| Mark refs | `(spel/inject-action-markers! "@e2yrjz" "@ea3kf5")` | `(annotate/inject-action-markers! pg ["@e2yrjz" "@ea3kf5"])` |
| Highlight | `(spel/highlight sel)` | `(locator/highlight loc)` |