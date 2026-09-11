# Selectors, snapshots, annotations

**Use when:** Inspect page structure, resolve selectors or capture annotations. Use fresh snapshot refs for interactive actions.

Find elements, read page structure, produce visual overlays. Covers `eval-sci` (implicit page) + library (explicit `pg`).

## Selectors

Every `spel/` fn taking `sel` is polymorphic:

1. CSS string — `"#id"`, `".class"`, `"button"`
2. Snapshot ref — `"@e2yrjz"` (from `spel/capture-snapshot`; `@` prefix required)
3. Locator object (pass-through)

```clojure
;; CSS
(spel/locator "#login-form") (spel/locator ".nav-item")
(spel/locator "div > p")      (spel/locator "input[type=email]")

(spel/click "#submit")
(spel/fill  "input[name=email]" "test@example.org")
(spel/text-content "h1.title")
```

`spel/locator` returns a Playwright Locator — only needed when storing for reuse.

### Semantic

```clojure
(spel/get-by-text  "Click me")
(spel/click        (spel/get-by-text "Sign in"))

(spel/get-by-role role/button)
(spel/get-by-role role/button  {:name "Submit"})
(spel/get-by-role role/heading {:name "Installation" :exact true})
(spel/click (spel/get-by-role role/link {:name "Home"}))

(spel/get-by-label       "Email")    (spel/fill (spel/get-by-label "Email") "user@example.org")
(spel/get-by-placeholder "Search...")(spel/fill (spel/get-by-placeholder "Enter your name") "Alice")
(spel/get-by-test-id     "submit-btn")(spel/click (spel/get-by-test-id "nav-menu"))
(spel/get-by-alt-text    "Logo")
(spel/get-by-title       "Close dialog")
```

Common roles: `role/button`, `role/link`, `role/heading`, `role/textbox`, `role/checkbox`, `role/radio`, `role/combobox`, `role/navigation`, `role/dialog`, `role/tab`, `role/tabpanel`, `role/list`, `role/listitem`, `role/img`, `role/table`, `role/row`, `role/cell`.

### Snapshot refs

After `(spel/capture-snapshot)`, every interactive element gets an `@eXXXX` id:

```clojure
(def snap (spel/capture-snapshot))
;; :tree => "- heading \"Welcome\" [@e2yrjz]\n- link \"Login\" [@e9mter]"

(spel/click "@e2yrjz")                  ; `@` required
(spel/text-content "@e9mter")
(spel/fill "@ea3kf5" "hello")
```

Refs resolve via a `data-pw-ref` attribute injected during capture. Re-snapshot after any navigation/DOM change — refs are ephemeral.

## Multiple elements

Strict mode throws when a selector matches more than one element. Options:

```clojure
(locator/all (spel/locator "a"))
(locator/all (spel/locator ".card"))

(spel/all-text-contents "a")            ; => ["Home" "About" "Contact"]
(spel/all-inner-texts   ".item")

(spel/count-of "li")                    ; 12
(spel/first-element "li") (spel/last-element "li")
(spel/nth-element   "li" 2)             ; 0-indexed
```

### Filtering

```clojure
(spel/loc-locator       ".card" "h2")
(spel/loc-get-by-text   ".card"  "Premium")
(spel/loc-get-by-role   ".nav"    role/link)
(spel/loc-get-by-label  "form"    "Email")
(spel/loc-get-by-test-id ".sidebar" "menu-toggle")
(spel/loc-filter        ".card" {:has-text "Premium"})
(spel/loc-filter        ".card" {:has (spel/get-by-text "Buy now")})
```

Selector might match multiple → narrow it, use `spel/first-element`, or switch to a semantic locator.

## Accessibility snapshots

A structured view of the page as a screen reader sees it. Every interactive element gets a ref you can use as a selector.

```clojure
(def snap (spel/capture-snapshot))
```

Returns:

| Key | Type | Description |
|-----|------|-------------|
| `:tree` | string | a11y tree with `[@eN]` annotations, first line `[viewport: W×H]` |
| `:refs` | map | `{"e2yrjz" {:role "heading" :name "Welcome" :tag "h1" :type nil :bbox {…}} …}` |
| `:counter` | long | Total refs assigned |
| `:url` `:title` | string | Page URL and title at capture time |
| `:viewport` | map | `{:width 1280 :height 720}` |
| `:device` | string | Emulated device, or `nil` |
| `:raw-tree` | string | The tree without ref annotations |
| `:truncated` | map | `nil` normally; `{:reason "time"\|"nodes" :visited N :limit N}` when the walk gave up before the end (the `spel snapshot` CLI adds a `[partial snapshot: …]` line for it) |

Tree example (measured, `header`/`main`/`footer` page):

```
[viewport: 1280x720]
- body
  - banner "H" [@e2j5zc] [pos:8,8 1264×18]
  - main [@e833h0] [pos:8,42 1264×18]
    - paragraph "x" [@e1tr94] [pos:8,42 1264×18]
  - contentinfo "F" [@e4bq3f] [pos:8,76 1264×18]
```

Every element in the tree carries a ref — landmarks (banner, main, navigation,
contentinfo) included, so `@ref` works as a scope as well as a click target.
`[level=1]` = ARIA property. `[pos:X,Y W×H]` = screen position + size.

Ref entry:

```clojure
{"e2j5zc" {:role "banner" :name "H" :tag "header" :type nil
           :bbox {:x 8 :y 8 :width 1264 :height 18}}}
```

### Scoped + full

```clojure
(spel/capture-snapshot {:scope "#main"})
(spel/capture-snapshot {:scope "@e3pq7r"})
(spel/capture-full-snapshot)            ; adds each iframe under an `- iframe [f1]` block; its refs are prefixed (@f1_e5l65o)
```

### Resolve / clear

```clojure
(spel/resolve-ref "@e2yrjz")            ; => Locator
(spel/clear-refs!)                      ; remove data-pw-ref attrs
```

### Computed styles (`-S`)

Two elements with identical geometry but different look → child-level styles. Compare with `-S`:

```bash
spel snapshot -i -S --minimal -s "[data-component='ComponentA'] > div:first-child"
spel snapshot -i -S --minimal -s "[data-component='ComponentB'] > div:first-child"
```

```
- button "Sync Now" [@e8nh6a] [pos:346,112 32×28] {height:28px;padding:6px 12px;font-size:14px}
- combobox          [@e65fyp] [pos:252,112 86×34] {height:34px;padding:6px 8px; font-size:14px}
```

### Style tiers

| Flag | Props | Use |
|------|------:|-----|
| `--minimal` | 16 | Quick visual weight comparison |
| *(default)* | 31 | Layout debugging |
| `--max` | 44 | Full style audit |

| Question | Tool |
|----------|------|
| Same size? | `snapshot -i` (check `[pos:W×H]`) |
| Why do same-size elements look different? | `snapshot -i -S --minimal` |
| Visual regression baseline | `snapshot -i -S` (default 31) |

## Annotations

In-browser CSS/JS overlays — bounding boxes, ref badges, dimension labels. No external image processing.

```clojure
(def snap (spel/capture-snapshot))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png")
```

Injects overlays → screenshots → removes them. Page left clean.

Byte form: `(spel/annotated-screenshot (:refs snap))`.

Options:

```clojure
(spel/save-annotated-screenshot! refs "/tmp/nav.png"   {:scope "#navigation"})
(spel/save-annotated-screenshot! refs "/tmp/full.png"  {:full-page true})
(spel/save-annotated-screenshot! refs "/tmp/clean.png"
  {:show-badges false :show-dimensions false :show-boxes false})
```

Manual overlay (persists across screenshots):

```clojure
(spel/inject-overlays! (:refs snap))      ; returns count of annotated elements
(spel/screenshot {:path "/tmp/with-overlays.png"})
(spel/remove-overlays!)
```

### Action markers

Bright pulsing border + `-> eN` label; highlights elements *before* interaction. Uses a distinct `data-spel-action-marker` attribute so it can coexist with annotations.

```clojure
(spel/inject-action-markers! "@e2yrjz" "@ea3kf5")
(spel/screenshot {:path "/tmp/before-click.png"})
(spel/click "@ea3kf5")
(spel/remove-action-markers!)
```

### Playwright's native highlight

```clojure
(spel/highlight "@e6t2x4")                ; brief flash, doesn't persist
(spel/highlight "#submit")
```

## Audit screenshots

Screenshot with a caption bar at the bottom — good for documenting workflow steps or building visual reports.

```clojure
(spel/save-audit-screenshot! "Step 1: Login page loaded" "/tmp/step1.png")
(spel/save-audit-screenshot! "Step 2: Form filled" "/tmp/step2.png"
  {:refs (:refs snap)})
(spel/save-audit-screenshot! "Step 3: About to click Submit" "/tmp/step3.png"
  {:refs (:refs snap) :markers ["@e1x9hz"]})
```

Byte form: `(spel/audit-screenshot "Caption text")`.

## Snapshot assertions

Assert an element's a11y structure matches an expected ARIA snapshot string:

```clojure
(spel/assert-matches-aria-snapshot "h1" "- heading \"Welcome\" [level=1]")

(spel/assert-matches-aria-snapshot "#nav"
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

Library:

```clojure
(assert/matches-aria-snapshot
  (assert/assert-that (page/locator pg "#nav"))
  "- navigation \"Main\":\n  - link \"Home\"\n  - link \"About\"")
```

Changed heading level or link text → assertion fails with a clear diff.

## Complete example

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)

(def snap (spel/capture-snapshot))
(println (:tree snap))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/hn-annotated.png")

(spel/inject-action-markers! "@e9mter")
(spel/screenshot {:path "/tmp/hn-before-click.png"})
(spel/remove-action-markers!)

(spel/click "@e9mter")
(spel/wait-for-load-state)

(spel/assert-visible "h1")
(println "Now at:" (spel/url))
(spel/save-audit-screenshot! "After clicking first link" "/tmp/hn-result.png")
```

## Quick reference

| Task | `eval-sci` | Library |
|------|------------|---------|
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
