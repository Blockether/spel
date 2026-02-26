# Selectors, Snapshots, and Annotations

How to find elements, read the page structure, and produce visual overlays. Covers both `--eval` mode (implicit page) and library mode (explicit `page` arg).

## Selectors

Every `spel/` function that takes a `sel` argument is polymorphic. It accepts:

1. **CSS selector string** like `"#id"`, `".class"`, `"button"`
2. **Snapshot ref string** like `"e2yrjz"` or `"@e2yrjz"` (from `spel/capture-snapshot`)
3. **Locator object** (pass-through, no resolution needed)

So `spel/click`, `spel/fill`, `spel/text-content`, `spel/visible?`, and every other `sel`-accepting function work the same way regardless of how you specify the target.

### CSS Selectors

```clojure
(spel/locator "#login-form")          ;; by ID
(spel/locator ".nav-item")            ;; by class
(spel/locator "div > p")              ;; child combinator
(spel/locator "input[type=email]")    ;; attribute selector

;; Use directly in actions (no need to call spel/locator first)
(spel/click "#submit")
(spel/fill "input[name=email]" "test@example.com")
(spel/text-content "h1.title")
```

`spel/locator` returns a Playwright Locator. You only need it when storing a locator for reuse.

### Semantic Selectors

```clojure
;; By visible text content
(spel/get-by-text "Click me")
(spel/click (spel/get-by-text "Sign in"))

;; By ARIA role (82 constants in the role/ namespace)
(spel/get-by-role role/button)
(spel/get-by-role role/button {:name "Submit"})
(spel/get-by-role role/heading {:name "Installation" :exact true})
(spel/click (spel/get-by-role role/link {:name "Home"}))

;; By label (<label> association)
(spel/get-by-label "Email")
(spel/fill (spel/get-by-label "Email") "user@example.com")

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

### Snapshot Ref Selectors

After calling `(spel/capture-snapshot)`, every interactive element gets a ref ID like `e2yrjz`, `e9mter`, etc. Use these directly:

```clojure
(def snap (spel/capture-snapshot))
;; snap => {:tree "- heading \"Welcome\" [@e2yrjz]\n- link \"Login\" [@e9mter]" ...}

(spel/click "e2yrjz")              ;; click by ref (no @ prefix)
(spel/click "@e2yrjz")             ;; @ prefix also works
(spel/text-content "e9mter")               ;; get text of ref e9mter
(spel/fill "ea3kf5" "hello")       ;; fill input identified as ea3kf5
```

Refs are resolved via the `data-pw-ref` attribute injected during snapshot capture.

## Multiple Elements

When a selector matches more than one element, Playwright's strict mode throws. Options:

```clojure
;; Get all matches as a vector of Locators
(locator/all (spel/locator "a"))
(locator/all (spel/locator ".card"))

;; Get all texts at once
(spel/all-text-contents "a")   ;; => ["Home" "About" "Contact"]
(spel/all-inner-texts ".item") ;; => ["Item 1" "Item 2" "Item 3"]

;; Count matches
(spel/count-of "li")           ;; => 12

;; Narrow to one element
(spel/first "li")              ;; first match
(spel/last "li")               ;; last match
(spel/nth "li" 2)              ;; third match (0-indexed)
```

### Filtering Within a Locator

```clojure
(spel/loc-locator ".card" "h2")                    ;; sub-selector
(spel/loc-get-by-text ".card" "Premium")            ;; filter by text
(spel/loc-get-by-role ".nav" role/link)             ;; filter by role
(spel/loc-get-by-label "form" "Email")              ;; filter by label
(spel/loc-get-by-test-id ".sidebar" "menu-toggle")  ;; filter by test ID
(spel/loc-filter ".card" {:has-text "Premium"})     ;; generic filter
(spel/loc-filter ".card" {:has (spel/get-by-text "Buy now")})
```

**Rule of thumb**: if your selector might match multiple elements, make it more specific, use `spel/first`, or use a semantic locator (role + name).

## Accessibility Snapshots

Snapshots give you a structured view of the page, the way a screen reader sees it. Every interactive element gets a numbered ref you can use as a selector.

### Capturing a Snapshot

```clojure
(def snap (spel/capture-snapshot))
```

Returns a map with three keys:

| Key | Type | Description |
|-----|------|-------------|
| `:tree` | String | Human-readable accessibility tree with `[@eN]` annotations |
| `:refs` | Map | `{"e2yrjz" {:role "heading" :name "Welcome" :tag "h1" :bbox {...}} ...}` |
| `:counter` | Long | Total number of refs assigned |

### Tree Output

The `:tree` string is a YAML-like indented tree. Real example from a news site:

```
- banner:
  - heading "Onet" [@e2yrjz] [level=1]
  - navigation "Main":
    - link "News" [@e9mter]
    - link "Sport" [@e6t2x4]
    - link "Business" [@e0k8qp]
  - search:
    - searchbox "Search Onet" [@ea3kf5]
    - button "Search" [@e1x9hz]
- main:
  - heading "Top Stories" [@e3pq7r] [level=2]
  - article:
    - link "Breaking: Major Event" [@e5dw2c]
    - paragraph "Details about the event..."
- contentinfo:
  - link "Privacy Policy" [@e7vnw3]
  - link "Terms of Service" [@e8jy4n]
```

Each `[@eN]` tag marks an interactive or meaningful element. Structural roles (banner, main, navigation) appear without refs. Attributes like `[level=1]` show ARIA properties.

### Ref Maps

Each ref in `:refs` contains:

```clojure
{"e2yrjz" {:role "heading" :name "Onet" :tag "h1"
       :bbox {:x 20 :y 10 :width 200 :height 40}}}
```

The `:bbox` gives pixel coordinates relative to the page, useful for annotation placement.

### Scoped and Full Snapshots

```clojure
;; Scope to a subtree (CSS selector or ref)
(spel/capture-snapshot {:scope "#main"})
(spel/capture-snapshot {:scope "@e3pq7r"})

;; Capture all iframes too (refs prefixed: f1_e1, f2_e3, etc.)
(spel/capture-full-snapshot)
```

### Resolving and Clearing Refs

```clojure
(spel/resolve-ref "e2yrjz")       ;; => Playwright Locator
(spel/clear-refs!)             ;; remove data-pw-ref attributes from DOM
```

If you've navigated to a new page since the last snapshot, refs are stale. Take a fresh snapshot.

## Annotations

Annotations inject visual overlays onto the page: bounding boxes, ref badges, and dimension labels. Everything is rendered in the browser via CSS/JS. No external image processing needed.

### Annotated Screenshots

The most common workflow. Capture a snapshot, then screenshot with overlays:

```clojure
(def snap (spel/capture-snapshot))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png")
```

Injects overlays, takes the screenshot, removes them. Page is left clean.

For bytes instead of a file: `(spel/annotated-screenshot (:refs snap))`

Options:

```clojure
(spel/save-annotated-screenshot! refs "/tmp/nav.png" {:scope "#navigation"})
(spel/save-annotated-screenshot! refs "/tmp/full.png" {:full-page true})
(spel/save-annotated-screenshot! refs "/tmp/clean.png"
  {:show-badges false :show-dimensions false :show-boxes false})
```

### Manual Overlay Control

Keep overlays visible for headed mode debugging or multiple screenshots:

```clojure
(spel/inject-overlays! (:refs snap))   ;; inject (returns count of annotated elements)
(spel/screenshot {:path "/tmp/with-overlays.png"})
(spel/remove-overlays!)              ;; remove when done
```

### Pre-Action Markers

Highlight specific elements before interacting with them. Visually distinct from annotations: bright red/orange pulsing border with a `-> eN` label.

```clojure
(spel/inject-action-markers! "e2yrjz" "ea3kf5")         ;; mark elements you're about to interact with
(spel/screenshot {:path "/tmp/before-click.png"})
(spel/click "ea3kf5")
(spel/remove-action-markers!)                  ;; clean up
```

Markers use `data-spel-action-marker` and don't interfere with annotation overlays. You can have both active at once.

### Playwright's Built-in Highlight

```clojure
(spel/highlight "e6t2x4")          ;; Playwright's native highlight (brief flash)
(spel/highlight "#submit")     ;; works with CSS selectors too
```

Unlike annotations, this doesn't persist.

## Audit Screenshots

Screenshots with a caption bar at the bottom. Useful for documenting workflow steps or building visual reports.

```clojure
;; Simple caption
(spel/save-audit-screenshot! "Step 1: Login page loaded" "/tmp/step1.png")

;; Caption + annotation overlays
(spel/save-audit-screenshot! "Step 2: Form filled" "/tmp/step2.png"
  {:refs (:refs snap)})

;; Caption + action markers on specific refs
(spel/save-audit-screenshot! "Step 3: About to click Submit" "/tmp/step3.png"
  {:refs (:refs snap) :markers ["e1x9hz"]})
```

For bytes: `(spel/audit-screenshot "Caption text")`

## Snapshot Testing

Assert that an element's accessibility structure matches an expected ARIA snapshot string:

```clojure
(spel/assert-matches-aria-snapshot "h1" "- heading \"Welcome\" [level=1]")

(spel/assert-matches-aria-snapshot "#nav"
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

Useful for regression testing. If someone changes the heading level or link text, the assertion fails with a clear diff.

Library equivalent:

```clojure
(assert/matches-aria-snapshot
  (assert/assert-that (page/locator pg "#nav"))
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

## Complete Workflow Example

Navigate, understand the page, annotate, interact, verify:

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
;; 1. Capture the page structure
(def snap (spel/capture-snapshot))
(println (:tree snap))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/hn-annotated.png")
(spel/inject-action-markers! "e9mter")
(spel/screenshot {:path "/tmp/hn-before-click.png"})
(spel/remove-action-markers!)
(spel/click "e9mter")
(spel/wait-for-load-state)
;; 5. Verify we landed on the right page
(spel/assert-visible "h1")
(println "Now at:" (spel/url))
(spel/save-audit-screenshot! "After clicking first link" "/tmp/hn-result.png")
```

## Quick Reference

| Task | `--eval` | Library |
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
| Resolve ref | `(spel/resolve-ref "e2yrjz")` | `(snapshot/resolve-ref "e2yrjz")` |
| Annotated shot | `(spel/save-annotated-screenshot! refs path)` | `(annotate/save-annotated-screenshot! pg refs path)` |
| Audit shot | `(spel/save-audit-screenshot! caption path)` | `(annotate/save-audit-screenshot! pg caption path)` |
| Mark refs | `(spel/inject-action-markers! "e2yrjz" "ea3kf5")` | `(annotate/inject-action-markers! pg ["e2yrjz" "ea3kf5"])` |
| Highlight | `(spel/highlight sel)` | `(locator/highlight loc)` |
