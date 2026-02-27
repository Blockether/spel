# Snapshot Testing and Visual Auditing

Structural testing with accessibility snapshots, ARIA assertions, ref-based interaction, and visual audit workflows. Covers `--eval` (implicit page) and library (explicit `page` arg) modes.

## Accessibility Snapshots

A snapshot captures the page as a screen reader sees it: roles, names, attributes. Every interactive element gets a numbered ref (`@e2yrjz`, `@e9mter`, ...) usable as a selector (the `@` prefix is required).

```clojure
;; --eval
(def snap (spel/capture-snapshot))
;; => {:tree "- heading \"Welcome\" [@e2yrjz]\n- link \"Login\" [@e9mter]"
;;     :refs {"e2yrjz" {:role "heading" :name "Welcome" :tag "h1"
;;                  :bbox {:x 20 :y 10 :width 200 :height 40}} ...}
;;     :counter 12}

;; Library
(let [{:keys [tree refs]} (snapshot/capture-snapshot pg)]
  (println tree)
  refs)
```

Scoped and full snapshots:

```clojure
(spel/capture-snapshot {:scope "#main"})        ;; subtree only
(spel/capture-snapshot {:scope "@e3pq7r"})          ;; scope to a ref
(spel/capture-full-snapshot)                    ;; includes iframes (refs: f1_e1, f2_e3)

;; Library equivalents
(snapshot/capture-snapshot pg {:scope "nav"})
(snapshot/capture-full-snapshot pg)
```

## Ref Traversal

After a snapshot, resolve refs to Locators for interaction:

```clojure
;; --eval — @ prefix required for refs
(spel/click "@e6t2x4")
(spel/text-content "@ea3kf5")
(spel/fill "@e5dw2c" "hello@example.com")

;; Library
(let [loc (snapshot/resolve-ref pg "e6t2x4")]
  (locator/click loc))

;; Bounding box from snapshot data (no page roundtrip)
(snapshot/ref-bounding-box (:refs snap) "e2yrjz")
;; => {:x 20 :y 10 :width 200 :height 40}

;; Clear stale refs after navigation
(spel/clear-refs!)                      ;; --eval
(snapshot/clear-refs! pg)               ;; library
```

## ARIA Snapshot Assertions

Assert the structural shape of a DOM subtree. Checks roles, names, and nesting against a YAML-like string. Not pixel comparison.

### The Format

```
- navigation "Main":
  - link "Home"
  - link "About"
  - link "Contact"
```

Two-space indentation for nesting. Colon means "has children." Roles without names match any name. Quoted names must match exactly. You can omit children you don't care about (partial matching).

### Asserting

```clojure
;; --eval — takes a selector + expected ARIA string
(spel/assert-matches-aria-snapshot "nav"
  "- navigation \"Main\":
     - link \"Home\"
     - link \"About\"")

;; Partial match — only check the heading exists
(spel/assert-matches-aria-snapshot "body"
  "- heading \"Example Domain\"")

;; Library — wrap with assert-that first
(let [la (assert/assert-that (page/locator pg "nav"))]
  (assert/matches-aria-snapshot la
    "- navigation \"Main\":
       - link \"Home\"
       - link \"About\""))
```

Failures produce a clear diff of expected vs. actual structure.

**Tests structure, not appearance.** Verifies role hierarchy, accessible names, ARIA attributes, nesting. Does not test layout, colors, fonts, or pixel positions.

## Visual Testing Workflow

spel has no built-in pixel-diff. Visual testing uses annotated screenshots and audit reports for manual or external comparison.

```clojure
;; Baseline screenshot
(spel/screenshot {:path "baseline/login.png"})

;; Annotated screenshot — overlays bounding boxes + ref labels
(def snap (spel/capture-snapshot))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png")
(spel/save-annotated-screenshot! (:refs snap) "/tmp/nav.png" {:scope "#nav"})
(spel/save-annotated-screenshot! (:refs snap) "/tmp/full.png" {:full-page true})

;; Audit screenshot — adds caption bar at bottom
(spel/save-audit-screenshot! "Login page loaded" "/tmp/step1.png")
(spel/save-audit-screenshot! "About to submit" "/tmp/step2.png"
  {:refs (:refs snap) :markers ["@e1x9hz"]})

;; Library equivalents
(annotate/save-annotated-screenshot! pg refs "/tmp/annotated.png")
(annotate/save-audit-screenshot! pg "Step 1" "/tmp/step1.png" {:refs refs})
```

## PDF Reports

Build multi-page audit reports from typed entries. Renders HTML, converts to PDF via Chromium.

```clojure
;; --eval
(spel/report->pdf
  [{:type :section :text "Login Audit" :level 1}
   {:type :meta :fields [["URL" (spel/url)] ["Date" (str (java.time.LocalDate/now))]]}
   {:type :screenshot :image (spel/screenshot) :caption "Current state"}
   {:type :observation :text "Form layout matches design spec"}
   {:type :table :headers ["Element" "State"] :rows [["Username" "OK"] ["Submit" "OK"]]}
   {:type :good :text "All elements present"}]
  {:path "/tmp/report.pdf" :title "Login Audit"})

;; Library
(annotate/report->pdf pg entries {:path "/tmp/report.pdf" :title "Audit"})
```

| Type | Required Keys | Purpose |
|------|--------------|---------|
| `:screenshot` | `:image` (byte[]) | Embedded PNG. Optional: `:caption`, `:page-break` |
| `:section` | `:text` | Heading. Optional: `:level` (1-3), `:page-break` |
| `:observation` | `:text` | Blue callout. Optional: `:items` [str...] |
| `:issue` | `:text` | Yellow warning. Optional: `:items` [str...] |
| `:good` | `:text` | Green success. Optional: `:items` [str...] |
| `:table` | `:headers`, `:rows` | Data table |
| `:meta` | `:fields` [[label val]...] | Key-value pairs |
| `:text` | `:text` | Plain paragraph |
| `:html` | `:content` | Raw HTML (no escaping) |

Generate HTML without a page: `(spel/report->html entries opts)` / `(annotate/report->html entries opts)`.

## Snapshot Testing in Tests

### Lazytest

```clojure
(ns my-app.snapshot-test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe nav-snapshot-test
  (describe "navigation structure"

    (it "matches expected ARIA structure"
      (core/with-testing-page [page]
        (page/navigate page "https://example.com")
        (let [la (assert/assert-that (page/locator page "body"))]
          (expect (nil? (assert/matches-aria-snapshot la
                          "- heading \"Example Domain\"
                           - paragraph
                           - link \"More information...\"\")))))))
```

### clojure.test

```clojure
(deftest nav-snapshot-test
  (core/with-testing-page [pg]
    (page/navigate pg "https://example.com")
    (let [la (assert/assert-that (page/locator pg "body"))]
      (is (nil? (assert/matches-aria-snapshot la
                  "- heading \"Example Domain\"
                   - paragraph
                   - link \"More information...\"")))))) 
```

### Explore Then Lock Down

Use snapshots during development to discover structure, then write ARIA assertions to lock it:

```clojure
(it "login form has expected structure"
  (core/with-testing-page [page]
    (page/navigate page "https://example.com/login")
    (let [{:keys [tree]} (snapshot/capture-snapshot page)]
      (println tree))  ;; inspect during development
    (let [la (assert/assert-that (page/locator page "form"))]
      (assert/matches-aria-snapshot la
        "- form:
           - textbox \"Email\"
           - textbox \"Password\"
           - button \"Sign in\""))))
```

## Ref Traversal Patterns

### Find by Role/Name in Refs

```clojure
(it "has a submit button"
  (core/with-testing-page [page]
    (page/navigate page "https://example.com/form")
    (let [{:keys [refs]} (snapshot/capture-snapshot page)
          submit-ref (->> refs
                       (some (fn [[id info]]
                               (when (and (= "button" (:role info))
                                          (= "Submit" (:name info)))
                                 id))))]
      (expect (some? submit-ref))
      (expect (locator/is-visible? (snapshot/resolve-ref page submit-ref))))))
```

### Multi-Step Workflow with Audit Trail

```clojure
;; --eval
(spel/navigate "https://example.com/checkout")
(spel/wait-for-load-state)

(def snap1 (spel/capture-snapshot))
(spel/save-audit-screenshot! "Step 1: Checkout loaded" "/tmp/s1.png" {:refs (:refs snap1)})

(spel/fill "@e6t2x4" "123 Main St")
(spel/fill "@e0k8qp" "Springfield")
(spel/save-audit-screenshot! "Step 2: Shipping filled" "/tmp/s2.png")

(spel/inject-action-markers! "@e5dw2c")
(spel/save-audit-screenshot! "Step 3: About to continue" "/tmp/s3.png")
(spel/remove-action-markers!)
(spel/click "@e5dw2c")
(spel/wait-for-load-state)

(spel/assert-matches-aria-snapshot "#payment"
  "- heading \"Payment Details\"
   - textbox \"Card number\"
   - button \"Place Order\"")
```

## Quick Reference

| Task | `--eval` | Library |
|------|----------|---------|
| Snapshot | `(spel/capture-snapshot)` | `(snapshot/capture-snapshot pg)` |
| Scoped | `(spel/capture-snapshot {:scope "sel"})` | `(snapshot/capture-snapshot pg {:scope "sel"})` |
| Full + iframes | `(spel/capture-full-snapshot)` | `(snapshot/capture-full-snapshot pg)` |
|| Resolve ref | `(spel/resolve-ref "@e2yrjz")` | `(snapshot/resolve-ref pg "e2yrjz")` |
|| Ref bbox | `(snapshot/ref-bounding-box refs "e2yrjz")` | same |
| Clear refs | `(spel/clear-refs!)` | `(snapshot/clear-refs! pg)` |
| ARIA assert | `(spel/assert-matches-aria-snapshot sel str)` | `(assert/matches-aria-snapshot la str)` |
| Annotated shot | `(spel/save-annotated-screenshot! refs path)` | `(annotate/save-annotated-screenshot! pg refs path)` |
| Audit shot | `(spel/save-audit-screenshot! caption path)` | `(annotate/save-audit-screenshot! pg caption path)` |
|| Mark refs | `(spel/inject-action-markers! "@e2yrjz" "@ea3kf5")` | `(annotate/inject-action-markers! pg ["@e2yrjz" "@ea3kf5"])` |
| Unmark | `(spel/remove-action-markers!)` | `(annotate/remove-action-markers! pg)` |
| Report PDF | `(spel/report->pdf entries opts)` | `(annotate/report->pdf pg entries opts)` |
| Report HTML | `(spel/report->html entries opts)` | `(annotate/report->html entries opts)` |

### Library Assertion Pattern

Wrap a Locator or Page with `assert-that`, call assertion functions. Returns `nil` on success, anomaly map on failure.

```clojure
(let [la (assert/assert-that (page/locator pg "h1"))]
  (assert/has-text la "Welcome")
  (assert/is-visible la))

;; Negation
(assert/is-hidden (assert/loc-not la))

;; Page-level
(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My App"))
```
