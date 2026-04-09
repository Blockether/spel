# Page locators & composable patterns

Find elements, chain locators, build reusable page modules, write clean test code.

## Basic Locators

Every locator strategy returns Playwright `Locator` — auto-waits, auto-retries.

### Library Mode (explicit page)

```clojure
(require '[com.blockether.spel.page :as page]
         '[com.blockether.spel.locator :as locator]
         '[com.blockether.spel.roles :as role])

;; CSS selector
(page/locator pg "#email")
(page/locator pg ".nav-item")
(page/locator pg "article >> h2")

;; By ARIA role
(page/get-by-role pg role/button)
(page/get-by-role pg role/button {:name "Submit"})
(page/get-by-role pg role/heading {:level 1})
(page/get-by-role pg role/link {:name #"Learn.*"})   ;; regex

;; By visible text
(page/get-by-text pg "Sign in")
(page/get-by-text pg #"Sign\s+in")                   ;; regex

;; By label (associated <label> element)
(page/get-by-label pg "Email address")

;; By placeholder
(page/get-by-placeholder pg "Search...")

;; By alt text (images)
(page/get-by-alt-text pg "Company logo")

;; By title attribute
(page/get-by-title pg "Close dialog")

;; By data-testid
(page/get-by-test-id pg "login-form")
```

### SCI/Eval Mode (implicit daemon page)

```clojure
;; CSS
(spel/locator "#email")
(locator/all (spel/locator ".nav-item"))              ;; returns seq of Locators

;; Role, text, label
(spel/get-by-role role/button)
(spel/get-by-role role/button {:name "Submit"})
(spel/get-by-text "Sign in")
(spel/get-by-label "Email address")

;; Snapshot refs (see "Snapshot Ref Traversal" below)
(spel/locator "@e6t2x4")                      ;
snapshot ref (@ prefix required)
```

### Library vs SCI equivalents

| Strategy | Library | SCI/Eval |
|---|---|---|
| CSS | `(page/locator pg sel)` | `(spel/locator sel)` |
| CSS (all) | `(locator/all (page/locator pg sel))` | `(locator/all (spel/locator sel))` |
| Role | `(page/get-by-role pg role opts)` | `(spel/get-by-role role opts)` |
| Text | `(page/get-by-text pg text)` | `(spel/get-by-text text)` |
| Label | `(page/get-by-label pg text)` | `(spel/get-by-label text)` |
| Test ID | `(page/get-by-test-id pg id)` | `(spel/get-by-test-id id)` |
| Snapshot ref | N/A | `(spel/locator "@e2yrjz")` |

## Locator Chaining

Narrow results by sub-selecting within locator or filtering by content.

### Sub-selection with `loc-locator`

Find elements inside another element:

```clojure
;; Library: find buttons inside specific form
(let [form (page/locator pg ".checkout-form")]
  (locator/loc-locator form "button"))

;; Chain deeper
(-> (page/locator pg "nav")
    (locator/loc-locator "ul")
    (locator/loc-locator "li:first-child")
    (locator/click))
```

### Sub-selection by role/text/label

Locators have `get-by-*` variants scoping to their subtree:

```clojure
(let [dialog (page/locator pg "[role=dialog]")]
  ;; Find "Cancel" button inside this dialog only
  (locator/loc-get-by-role dialog role/button)
  (locator/loc-get-by-text dialog "Cancel")
  (locator/loc-get-by-label dialog "Name")
  (locator/loc-get-by-test-id dialog "confirm-btn"))
```

### Filtering with `loc-filter`

Narrow locator by text content or presence of child:

```clojure
;; Rows containing "Overdue"
(-> (page/locator pg "tr")
    (locator/loc-filter {:has-text "Overdue"}))

;; Rows containing "Delete" button
(-> (page/locator pg "tr")
    (locator/loc-filter {:has (page/get-by-role pg role/button {:name "Delete"})}))

;; Rows NOT containing "Archived"
(-> (page/locator pg "tr")
    (locator/loc-filter {:has-not-text "Archived"}))

;; Rows without checkbox
(-> (page/locator pg "tr")
    (locator/loc-filter {:has-not (page/locator pg "input[type=checkbox]")}))

;; Regex filter
(-> (page/locator pg ".card")
    (locator/loc-filter {:has-text #"Price: \$\d+"}))
```

### Positional Selection

Pick specific elements from multi-match locator:

```clojure
(locator/first-element (page/locator pg "li"))    ;; first <li>
(locator/last-element (page/locator pg "li"))     ;; last <li>
(locator/nth-element (page/locator pg "li") 2)    ;; third <li> (0-indexed)
(locator/count-elements (page/locator pg "li"))   ;; how many?
(locator/all (page/locator pg "li"))              ;; vec of individual Locators
```

## Page object pattern

Wrap locator creation in plain fns. Each takes `pg`, returns `Locator`.

```clojure
(ns my-app.pages.login
  (:require
   [com.blockether.spel.page :as page]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.roles :as role]))

;; Locator fns
(defn form [pg]       (page/get-by-test-id pg "login-form"))
(defn username [pg]   (page/get-by-label pg "Username"))
(defn password [pg]   (page/get-by-label pg "Password"))
(defn submit [pg]     (page/get-by-role pg role/button {:name "Log in"}))
(defn error-msg [pg]  (page/locator pg ".login-error"))

;; Action fns
(defn login! [pg user pass]
  (locator/fill (username pg) user)
  (locator/fill (password pg) pass)
  (locator/click (submit pg)))

(defn clear-form! [pg]
  (locator/clear (username pg))
  (locator/clear (password pg)))
```

Use in tests:

```clojure
(ns my-app.login-test
  (:require
   [my-app.pages.login :as login]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe login-test
  (describe "login flow"

    (it "logs in with valid credentials"
      (core/with-testing-page [page]
        (page/navigate page "https://app.example.org/login")
        (login/login! page "alice" "secret123")
        (expect (nil? (assert/has-url (assert/assert-that page) #".*dashboard.*")))))

    (it "shows error for bad password"
      (core/with-testing-page [page]
        (page/navigate page "https://app.example.org/login")
        (login/login! page "alice" "wrong")
        (expect (nil? (assert/is-visible (assert/assert-that (login/error-msg page)))))))))
```

## Composable Modules

Larger apps: one namespace per page/component. Shared components get own namespace.

```clojure
;; Shared nav component
(ns my-app.components.nav
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator]
            [com.blockether.spel.roles :as role]))

(defn nav-bar [pg]    (page/locator pg "nav.main"))
(defn menu-item [pg text]
  (locator/loc-get-by-text (nav-bar pg) text))
(defn navigate-to! [pg section]
  (locator/click (menu-item pg section)))
```

```clojure
;; Dashboard page, uses nav
(ns my-app.pages.dashboard
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator]
            [com.blockether.spel.roles :as role]
            [my-app.components.nav :as nav]))

(defn stat-card [pg label]
  (-> (page/locator pg ".stat-card")
      (locator/loc-filter {:has-text label})))

(defn stat-value [pg label]
  (locator/loc-locator (stat-card pg label) ".value"))

(defn go-to-settings! [pg]
  (nav/navigate-to! pg "Settings"))
```

### Composing locators across modules

Key insight: locator fns return `Locator` objects. Pass to any `locator/*` fn or use as `:has` / `:has-not` filters.

```clojure
;; Find cards containing "Buy" button
(let [buy-btn (page/get-by-role pg role/button {:name "Buy"})]
  (-> (page/locator pg ".product-card")
      (locator/loc-filter {:has buy-btn})
      (locator/first-element)
      (locator/click)))
```

## Snapshot ref traversal

Accessibility snapshots assign numbered refs (`e1`, `e2`, ...) to interactive elements. These work as selectors.

### Pattern: snapshot, pick, act

```clojure
;; SCI/eval mode
(spel/navigate "https://example.org")
(spel/wait-for-load-state)

;; 1. Take snapshot, see tree
(let [snap (spel/capture-snapshot)]
  (println (:tree snap)))
;; Output includes:
;;   - heading "Example Domain" [@e1] [pos:20,50 400×40]
;;   - link "More information..." [@e2] [pos:20,100 200×20]

;; 2. Click by ref
(spel/click "@e9mter")

;; 3. Or resolve to Locator for more operations
(let [loc (spel/locator "@e9mter")]
  (println (locator/text-content loc))
  (locator/hover loc))
```

Snapshot refs are ephemeral. Valid until next navigation or DOM change. Fresh snapshot if page changes.

### Annotated Screenshots

Combine snapshots + visual annotations for debugging:

```clojure
(let [snap (spel/capture-snapshot)
      refs (:refs snap)]
  (spel/save-annotated-screenshot! refs "/tmp/annotated.png"))
```

Each ref gets numbered label overlaid on screenshot, visually matching `e1`, `e2`, etc. to page elements.

## Assertions with Locators

All assertion fns return `nil` on success, anomaly map on failure. Wrap in `expect` for test assertions.

### Element Assertions

```clojure
(require '[com.blockether.spel.assertions :as assert])

(let [heading (page/get-by-role pg role/heading {:level 1})]
  ;; Text
  (assert/has-text (assert/assert-that heading) "Welcome")
  (assert/contains-text (assert/assert-that heading) "Welc")

  ;; Visibility
  (assert/is-visible (assert/assert-that heading))
  (assert/is-hidden (assert/assert-that (page/locator pg ".spinner")))

  ;; Count
  (assert/has-count (assert/assert-that (page/locator pg ".item")) 5)

  ;; Attributes
  (assert/has-attribute (assert/assert-that heading) "class" "title")
  (assert/has-css (assert/assert-that heading) "color" "rgb(0, 0, 0)"))
```

### Negation

```clojure
;; Assert something NOT true
(assert/is-visible (assert/loc-not (assert/assert-that (page/locator pg ".error"))))
;; ^ asserts .error NOT visible
```

### Page-Level Assertions

```clojure
(assert/has-title (assert/assert-that pg) "Dashboard")
(assert/has-url (assert/assert-that pg) #".*dashboard.*")
```

### In Tests (with expect)

```clojure
(it "shows welcome heading"
  (core/with-testing-page [page]
    (page/navigate page "https://app.example.org")
    (let [h1 (page/get-by-role page role/heading {:level 1})]
      (expect (nil? (assert/has-text (assert/assert-that h1) "Welcome")))
      (expect (nil? (assert/is-visible (assert/assert-that h1)))))))
```

`nil?` check works because assertion fns return `nil` on success, anomaly map on failure.

### SCI/Eval Assertions

```clojure
;; Daemon mode uses spel/ wrappers
(spel/assert-title "Dashboard")
(spel/assert-visible "h1")
(spel/assert-text "h1" "Welcome")
(spel/assert-contains-text ".subtitle" "version")
(spel/assert-hidden ".loading")
```

## Report Generation

Build rich HTML or PDF reports from test results using typed entry maps.

### Entry Types

| Type | Required Keys | Optional Keys |
|---|---|---|
| `:screenshot` | `:image` (byte[]) | `:caption`, `:page-break` |
| `:section` | `:text` | `:level` (1/2/3), `:page-break` |
| `:observation` | `:text` | `:items` [str...] |
| `:issue` | `:text` | `:items` [str...] |
| `:good` | `:text` | `:items` [str...] |
| `:table` | `:headers`, `:rows` | |
| `:meta` | `:fields` [[label val]...] | |
| `:text` | `:text` | |
| `:html` | `:content` (raw HTML) | |

### Library Mode

```clojure
(require '[com.blockether.spel.annotate :as annotate])

;; HTML string
(let [html (annotate/report->html
             [{:type :section :text "Login Flow" :level 1}
              {:type :screenshot :image (page/screenshot pg) :caption "Login page"}
              {:type :good :text "Form renders correctly"
               :items ["Username field present" "Password field present"]}
              {:type :table :headers ["Field" "Status"]
               :rows [["Username" "OK"] ["Password" "OK"]]}]
             {:title "Login Test Report"})]
  (spit "report.html" html))

;; PDF bytes (Chromium headless only)
(annotate/report->pdf pg
  [{:type :section :text "Login Flow"}
   {:type :screenshot :image (page/screenshot pg) :caption "Login page"}]
  {:title "Login Report" :path "report.pdf"})
```

### SCI/Eval Mode

```clojure
;; Generate PDF presentation from current page
(spel/navigate "https://app.example.org/dashboard")
(spel/wait-for-load-state)
(spel/report->pdf
  [{:type :section :text "Dashboard Audit" :level 1}
   {:type :meta :fields [["Date" "2026-02-24"] ["Auditor" "CI"]]}
   {:type :screenshot :image (spel/screenshot) :caption "Dashboard overview"}
   {:type :observation :text "Layout check"
    :items ["Nav bar visible" "Cards loaded" "Footer present"]}]
  {:title "Dashboard Audit" :path "/tmp/audit.pdf"})
```

## Tips

Prefer semantic selectors. Role, label, text locators match how users see page. Survive CSS refactors.

```clojure
;; Good: resilient to markup changes
(page/get-by-role pg role/button {:name "Submit"})
(page/get-by-label pg "Email")

;; Fragile: breaks when CSS classes change
(page/locator pg "button.btn-primary.submit-form")
```

Use test IDs as fallback. No good role or label → add `data-testid` to markup, use `get-by-test-id`.

Keep locators DRY. Define once in page object fns. Don't repeat selectors across tests.

Don't over-chain. Locator expression hard to read → break into named bindings:

```clojure
;; Hard to follow
(-> (page/locator pg "table")
    (locator/loc-locator "tbody tr")
    (locator/loc-filter {:has-text "Active"})
    (locator/first-element)
    (locator/loc-get-by-role role/button)
    (locator/click))

;; Clearer
(let [rows    (locator/loc-locator (page/locator pg "table") "tbody tr")
      active  (locator/loc-filter rows {:has-text "Active"})
      first-row (locator/first-element active)
      btn     (locator/loc-get-by-role first-row role/button)]
  (locator/click btn))
```

Locators are lazy. Creating locator doesn't touch DOM. Lookup happens on action (`click`, `fill`, `text-content`) or assertion. Safe to define locators at test top before page loads.

Iterate with `all`. Loop over matching elements → use `locator/all` for vector of individual `Locator` instances:

```clojure
(doseq [item (locator/all (page/locator pg ".todo-item"))]
  (println (locator/text-content item)))
```

Snapshot refs for exploration, selectors for tests. Snapshot refs (`@e2yrjz`) great for interactive `eval-sci` exploration. For test code, prefer stable selectors (role, label, test-id) that won't shift on page change.