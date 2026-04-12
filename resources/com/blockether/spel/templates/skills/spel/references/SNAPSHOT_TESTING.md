# Snapshot testing & visual auditing

Structural testing via accessibility snapshots, ARIA assertions, ref-based interaction, visual audit workflows. Covers `eval-sci` (implicit page) + library (explicit `pg`).

## Accessibility snapshots

Every interactive element gets a ref (`@e2yrjz`, `@e9mter`, …) usable as a selector — `@` prefix required.

```clojure
;; eval-sci
(def snap (spel/capture-snapshot))
;; => {:tree "- heading \"Welcome\" [@e2yrjz] [pos:20,10 200×40]\n- link \"Login\" [@e9mter] [pos:20,60 80×24]"
;;     :refs {"e2yrjz" {:role "heading" :name "Welcome" :tag "h1"
;;                      :bbox {:x 20 :y 10 :width 200 :height 40}} …}
;;     :counter 12}

;; Library
(let [{:keys [tree refs]} (snapshot/capture-snapshot pg)]
  (println tree)
  refs)
```

Scoped + full:

```clojure
(spel/capture-snapshot {:scope "#main"})      ; subtree only
(spel/capture-snapshot {:scope "@e3pq7r"})    ; scope to ref
(spel/capture-full-snapshot)                  ; includes iframes — refs f1_e1, f2_e3, …

(snapshot/capture-snapshot      pg {:scope "nav"})
(snapshot/capture-full-snapshot pg)
```

## Ref traversal

```clojure
;; eval-sci — `@` prefix required
(spel/click        "@e6t2x4")
(spel/text-content "@ea3kf5")
(spel/fill         "@e5dw2c" "hello@example.org")

;; Library
(let [loc (snapshot/resolve-ref pg "e6t2x4")]
  (locator/click loc))

;; Bbox straight from snapshot data (no page roundtrip)
(snapshot/ref-bounding-box (:refs snap) "e2yrjz")
;; => {:x 20 :y 10 :width 200 :height 40}

(spel/clear-refs!)               ; eval-sci
(snapshot/clear-refs! pg)        ; library
```

## ARIA snapshot assertions

Assert the **structure** of a subtree — roles, names, nesting. Not a pixel comparison.

```
- navigation "Main":
  - link "Home"
  - link "About"
  - link "Contact"
```

Two-space indentation for nesting. Colon = "has children". Roles without names match any name. Quoted names must match exactly. Omit children you don't care about (partial matching).

```clojure
;; eval-sci — selector + expected ARIA string
(spel/assert-matches-aria-snapshot "nav"
  "- navigation \"Main\":
     - link \"Home\"
     - link \"About\"")

;; Partial match — only check heading exists
(spel/assert-matches-aria-snapshot "body" "- heading \"Example Domain\"")

;; Library — wrap with assert-that first
(let [la (assert/assert-that (page/locator pg "nav"))]
  (assert/matches-aria-snapshot la
    "- navigation \"Main\":
       - link \"Home\"
       - link \"About\""))
```

Failures produce a diff of expected vs actual. Tests structure only — not layout, colors, fonts, or pixel positions.

## Visual testing workflow

No built-in pixel-diff. Use annotated screenshots + audit reports for manual / external comparison.

```clojure
(spel/screenshot {:path "baseline/login.png"})

(def snap (spel/capture-snapshot))
(spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png")
(spel/save-annotated-screenshot! (:refs snap) "/tmp/nav.png"  {:scope "#nav"})
(spel/save-annotated-screenshot! (:refs snap) "/tmp/full.png" {:full-page true})

(spel/save-audit-screenshot! "Login page loaded" "/tmp/step1.png")
(spel/save-audit-screenshot! "About to submit"   "/tmp/step2.png"
  {:refs (:refs snap) :markers ["@e1x9hz"]})

;; Library
(annotate/save-annotated-screenshot! pg refs  "/tmp/annotated.png")
(annotate/save-audit-screenshot!     pg "Step 1" "/tmp/step1.png" {:refs refs})
```

## PDF reports

Multi-page audit reports from typed entries → Chromium renders HTML to PDF.

```clojure
(spel/report->pdf
  [{:type :section :text "Login Audit" :level 1}
   {:type :meta :fields [["URL" (spel/url)] ["Date" (str (java.time.LocalDate/now))]]}
   {:type :screenshot :image (spel/screenshot) :caption "Current state"}
   {:type :observation :text "Form layout matches design spec"}
   {:type :table :headers ["Element" "State"] :rows [["Username" "OK"] ["Submit" "OK"]]}
   {:type :good :text "All elements present"}]
  {:path "/tmp/report.pdf" :title "Login Audit"})

(annotate/report->pdf pg entries {:path "/tmp/report.pdf" :title "Audit"})
```

| Type | Required | Notes |
|------|----------|-------|
| `:screenshot` | `:image` (byte[]) | `:caption`, `:page-break` |
| `:section` | `:text` | `:level` 1–3, `:page-break` |
| `:observation` / `:issue` / `:good` | `:text` | `:items [str…]` |
| `:table` | `:headers`, `:rows` | — |
| `:meta` | `:fields [[label val]…]` | — |
| `:text` | `:text` | Plain paragraph |
| `:html` | `:content` | Raw HTML (no escaping) |

HTML only (no page): `(spel/report->html entries opts)` / `(annotate/report->html entries opts)`.

## Snapshot tests

### Lazytest

```clojure
(defdescribe nav-snapshot-test
  (describe "navigation structure"
    (it "matches expected ARIA structure"
      (core/with-testing-page [page]
        (page/navigate page "https://example.org")
        (let [la (assert/assert-that (page/locator page "body"))]
          (expect (nil? (assert/matches-aria-snapshot la
                          "- heading \"Example Domain\"
                           - paragraph
                           - link \"More information...\"")))))))
```

### clojure.test

```clojure
(deftest nav-snapshot-test
  (core/with-testing-page [pg]
    (page/navigate pg "https://example.org")
    (let [la (assert/assert-that (page/locator pg "body"))]
      (is (nil? (assert/matches-aria-snapshot la
                  "- heading \"Example Domain\"
                   - paragraph
                   - link \"More information...\""))))))
```

### Explore → lock down

Use snapshots during dev to discover structure, then write an ARIA assertion to lock it:

```clojure
(it "login form has expected structure"
  (core/with-testing-page [page]
    (page/navigate page "https://example.org/login")
    (println (:tree (snapshot/capture-snapshot page)))   ; inspect during dev
    (let [la (assert/assert-that (page/locator page "form"))]
      (assert/matches-aria-snapshot la
        "- form:
           - textbox \"Email\"
           - textbox \"Password\"
           - button \"Sign in\""))))
```

## Ref traversal patterns

### Find by role/name in refs

```clojure
(it "has a submit button"
  (core/with-testing-page [page]
    (page/navigate page "https://example.org/form")
    (let [{:keys [refs]} (snapshot/capture-snapshot page)
          submit-ref (->> refs
                          (some (fn [[id info]]
                                  (when (and (= "button" (:role info))
                                             (= "Submit" (:name info)))
                                    id))))]
      (expect (some? submit-ref))
      (expect (locator/is-visible? (snapshot/resolve-ref page submit-ref))))))
```

### Multi-step workflow with audit trail

```clojure
(spel/navigate "https://example.org/checkout") (spel/wait-for-load-state)

(def snap1 (spel/capture-snapshot))
(spel/save-audit-screenshot! "Checkout loaded" "/tmp/s1.png" {:refs (:refs snap1)})

(spel/fill "@e6t2x4" "123 Main St")
(spel/fill "@e0k8qp" "Springfield")
(spel/save-audit-screenshot! "Shipping filled" "/tmp/s2.png")

(spel/inject-action-markers! "@e5dw2c")
(spel/save-audit-screenshot! "About to continue" "/tmp/s3.png")
(spel/remove-action-markers!)
(spel/click "@e5dw2c") (spel/wait-for-load-state)

(spel/assert-matches-aria-snapshot "#payment"
  "- heading \"Payment Details\"
   - textbox \"Card number\"
   - button \"Place Order\"")
```

## Quick reference

| Task | `eval-sci` | Library |
|------|------------|---------|
| Snapshot | `(spel/capture-snapshot)` | `(snapshot/capture-snapshot pg)` |
| Scoped | `(spel/capture-snapshot {:scope "sel"})` | `(snapshot/capture-snapshot pg {:scope "sel"})` |
| Full + iframes | `(spel/capture-full-snapshot)` | `(snapshot/capture-full-snapshot pg)` |
| Resolve ref | `(spel/resolve-ref "@e2yrjz")` | `(snapshot/resolve-ref pg "e2yrjz")` |
| Ref bbox | `(snapshot/ref-bounding-box refs "e2yrjz")` | same |
| Clear refs | `(spel/clear-refs!)` | `(snapshot/clear-refs! pg)` |
| ARIA assert | `(spel/assert-matches-aria-snapshot sel str)` | `(assert/matches-aria-snapshot la str)` |
| Annotated shot | `(spel/save-annotated-screenshot! refs path)` | `(annotate/save-annotated-screenshot! pg refs path)` |
| Audit shot | `(spel/save-audit-screenshot! caption path)` | `(annotate/save-audit-screenshot! pg caption path)` |
| Mark refs | `(spel/inject-action-markers! "@e2yrjz" "@ea3kf5")` | `(annotate/inject-action-markers! pg ["@e2yrjz" "@ea3kf5"])` |
| Unmark | `(spel/remove-action-markers!)` | `(annotate/remove-action-markers! pg)` |
| Report PDF | `(spel/report->pdf entries opts)` | `(annotate/report->pdf pg entries opts)` |
| Report HTML | `(spel/report->html entries opts)` | `(annotate/report->html entries opts)` |

### Library assertion pattern

Wrap Locator/Page with `assert-that`, then call assertion fns. Returns `nil` on success, anomaly map on failure.

```clojure
(let [la (assert/assert-that (page/locator pg "h1"))]
  (assert/has-text   la "Welcome")
  (assert/is-visible la))

(assert/is-hidden (assert/loc-not la))               ; negation

(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My App"))                    ; page-level
```
