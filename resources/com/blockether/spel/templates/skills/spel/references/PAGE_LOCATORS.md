# Page locators & composable patterns

Every locator strategy returns a Playwright `Locator` — auto-waiting, auto-retrying.

## Basic locators

### Library mode (explicit page)

```clojure
(require '[com.blockether.spel.page :as page]
         '[com.blockether.spel.locator :as locator]
         '[com.blockether.spel.roles :as role])

;; CSS
(page/locator pg "#email")
(page/locator pg ".nav-item")
(page/locator pg "article >> h2")

;; ARIA role
(page/get-by-role pg role/button)
(page/get-by-role pg role/button  {:name "Submit"})
(page/get-by-role pg role/heading {:level 1})
(page/get-by-role pg role/link    {:name #"Learn.*"})

;; Text / label / placeholder / alt / title / test-id
(page/get-by-text        pg "Sign in")
(page/get-by-text        pg #"Sign\s+in")
(page/get-by-label       pg "Email address")
(page/get-by-placeholder pg "Search...")
(page/get-by-alt-text    pg "Company logo")
(page/get-by-title       pg "Close dialog")
(page/get-by-test-id     pg "login-form")
```

### SCI / eval mode (implicit page)

```clojure
(spel/locator "#email")
(locator/all  (spel/locator ".nav-item"))        ; seq of Locators
(spel/get-by-role role/button {:name "Submit"})
(spel/get-by-text  "Sign in")
(spel/get-by-label "Email address")
(spel/locator "@e6t2x4")                         ; snapshot ref (`@` required)
```

| Strategy | Library | SCI / eval |
|----------|---------|------------|
| CSS | `(page/locator pg sel)` | `(spel/locator sel)` |
| CSS (all) | `(locator/all (page/locator pg sel))` | `(locator/all (spel/locator sel))` |
| Role | `(page/get-by-role pg role opts)` | `(spel/get-by-role role opts)` |
| Text | `(page/get-by-text pg text)` | `(spel/get-by-text text)` |
| Label | `(page/get-by-label pg text)` | `(spel/get-by-label text)` |
| Test id | `(page/get-by-test-id pg id)` | `(spel/get-by-test-id id)` |
| Snapshot ref | — | `(spel/locator "@e2yrjz")` |

## Chaining

### Sub-selection with `loc-locator`

```clojure
(let [form (page/locator pg ".checkout-form")]
  (locator/loc-locator form "button"))

(-> (page/locator pg "nav")
    (locator/loc-locator "ul")
    (locator/loc-locator "li:first-child")
    (locator/click))
```

### Sub-selection by role/text/label

```clojure
(let [dialog (page/locator pg "[role=dialog]")]
  (locator/loc-get-by-role    dialog role/button)
  (locator/loc-get-by-text    dialog "Cancel")
  (locator/loc-get-by-label   dialog "Name")
  (locator/loc-get-by-test-id dialog "confirm-btn"))
```

### Filtering with `loc-filter`

```clojure
(-> (page/locator pg "tr") (locator/loc-filter {:has-text "Overdue"}))
(-> (page/locator pg "tr") (locator/loc-filter {:has (page/get-by-role pg role/button {:name "Delete"})}))
(-> (page/locator pg "tr") (locator/loc-filter {:has-not-text "Archived"}))
(-> (page/locator pg "tr") (locator/loc-filter {:has-not (page/locator pg "input[type=checkbox]")}))
(-> (page/locator pg ".card") (locator/loc-filter {:has-text #"Price: \$\d+"}))
```

### Positional

```clojure
(locator/first-element  (page/locator pg "li"))
(locator/last-element   (page/locator pg "li"))
(locator/nth-element    (page/locator pg "li") 2)     ; 0-indexed
(locator/count-elements (page/locator pg "li"))
(locator/all            (page/locator pg "li"))       ; vec of Locators
```

## Page object pattern

Locator fns each take `pg` and return a `Locator`; actions wrap them.

```clojure
(ns my-app.pages.login
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator]
            [com.blockether.spel.roles :as role]))

(defn form      [pg] (page/get-by-test-id pg "login-form"))
(defn username  [pg] (page/get-by-label   pg "Username"))
(defn password  [pg] (page/get-by-label   pg "Password"))
(defn submit    [pg] (page/get-by-role    pg role/button {:name "Log in"}))
(defn error-msg [pg] (page/locator        pg ".login-error"))

(defn login! [pg user pass]
  (locator/fill  (username pg) user)
  (locator/fill  (password pg) pass)
  (locator/click (submit   pg)))

(defn clear-form! [pg]
  (locator/clear (username pg))
  (locator/clear (password pg)))
```

```clojure
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

## Composable modules

Shared components go in their own namespace:

```clojure
(ns my-app.components.nav
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator]
            [com.blockether.spel.roles :as role]))

(defn nav-bar      [pg] (page/locator pg "nav.main"))
(defn menu-item    [pg text] (locator/loc-get-by-text (nav-bar pg) text))
(defn navigate-to! [pg section] (locator/click (menu-item pg section)))
```

```clojure
(ns my-app.pages.dashboard
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as locator]
            [com.blockether.spel.roles :as role]
            [my-app.components.nav :as nav]))

(defn stat-card    [pg label] (-> (page/locator pg ".stat-card") (locator/loc-filter {:has-text label})))
(defn stat-value   [pg label] (locator/loc-locator (stat-card pg label) ".value"))
(defn go-to-settings! [pg]    (nav/navigate-to! pg "Settings"))
```

Cross-module composition — locators are just values:

```clojure
(let [buy-btn (page/get-by-role pg role/button {:name "Buy"})]
  (-> (page/locator pg ".product-card")
      (locator/loc-filter {:has buy-btn})
      (locator/first-element)
      (locator/click)))
```

## Snapshot ref traversal

Accessibility snapshots give every interactive element a ref (`@e1`, `@e2`, …) that works as a selector until the next navigation / DOM change.

```clojure
(spel/navigate "https://example.org")
(spel/wait-for-load-state)

;; 1. See the tree
(let [snap (spel/capture-snapshot)]
  (println (:tree snap)))
;;   - heading "Example Domain" [@e1] [pos:20,50 400×40]
;;   - link    "More information..." [@e2] [pos:20,100 200×20]

;; 2. Click by ref
(spel/click "@e9mter")

;; 3. Resolve to a Locator for more ops
(let [loc (spel/locator "@e9mter")]
  (println (locator/text-content loc))
  (locator/hover loc))
```

Annotated screenshot from a snapshot:

```clojure
(let [snap (spel/capture-snapshot)]
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/annotated.png"))
```

Refs are ephemeral — re-snapshot after any navigation.

## Assertions

Assertion fns return `nil` on success, anomaly map on failure. In tests, wrap in `(expect (nil? ...))`.

```clojure
(require '[com.blockether.spel.assertions :as assert])

(let [h (page/get-by-role pg role/heading {:level 1})]
  (assert/has-text       (assert/assert-that h) "Welcome")
  (assert/contains-text  (assert/assert-that h) "Welc")
  (assert/is-visible     (assert/assert-that h))
  (assert/is-hidden      (assert/assert-that (page/locator pg ".spinner")))
  (assert/has-count      (assert/assert-that (page/locator pg ".item")) 5)
  (assert/has-attribute  (assert/assert-that h) "class" "title")
  (assert/has-css        (assert/assert-that h) "color" "rgb(0, 0, 0)"))

;; Negation
(assert/is-visible (assert/loc-not (assert/assert-that (page/locator pg ".error"))))

;; Page-level
(assert/has-title (assert/assert-that pg) "Dashboard")
(assert/has-url   (assert/assert-that pg) #".*dashboard.*")
```

```clojure
(it "shows welcome heading"
  (core/with-testing-page [page]
    (page/navigate page "https://app.example.org")
    (let [h1 (page/get-by-role page role/heading {:level 1})]
      (expect (nil? (assert/has-text  (assert/assert-that h1) "Welcome")))
      (expect (nil? (assert/is-visible (assert/assert-that h1)))))))
```

### SCI / eval assertions

```clojure
(spel/assert-title         "Dashboard")
(spel/assert-visible       "h1")
(spel/assert-text          "h1" "Welcome")
(spel/assert-contains-text ".subtitle" "version")
(spel/assert-hidden        ".loading")
```

## Report generation (typed entries → HTML / PDF)

| Entry type | Required | Optional |
|------------|----------|----------|
| `:screenshot` | `:image` (byte[]) | `:caption`, `:page-break` |
| `:section` | `:text` | `:level` 1/2/3, `:page-break` |
| `:observation` / `:issue` / `:good` | `:text` | `:items` [str…] |
| `:table` | `:headers`, `:rows` | — |
| `:meta` | `:fields [[label val]…]` | — |
| `:text` | `:text` | — |
| `:html` | `:content` (raw HTML) | — |

```clojure
;; Library: HTML string
(require '[com.blockether.spel.annotate :as annotate])
(let [html (annotate/report->html
             [{:type :section :text "Login Flow" :level 1}
              {:type :screenshot :image (page/screenshot pg) :caption "Login page"}
              {:type :good :text "Form renders correctly"
               :items ["Username field present" "Password field present"]}
              {:type :table :headers ["Field" "Status"]
               :rows [["Username" "OK"] ["Password" "OK"]]}]
             {:title "Login Test Report"})]
  (spit "report.html" html))

;; Library: PDF (Chromium headless)
(annotate/report->pdf pg
  [{:type :section :text "Login Flow"}
   {:type :screenshot :image (page/screenshot pg)}]
  {:title "Login Report" :path "report.pdf"})

;; SCI / eval
(spel/report->pdf
  [{:type :section :text "Dashboard Audit" :level 1}
   {:type :meta :fields [["Date" "2026-02-24"] ["Auditor" "CI"]]}
   {:type :screenshot :image (spel/screenshot) :caption "Dashboard overview"}
   {:type :observation :text "Layout check"
    :items ["Nav bar visible" "Cards loaded" "Footer present"]}]
  {:title "Dashboard Audit" :path "/tmp/audit.pdf"})
```

## Tips

- **Semantic first.** Role / label / text survive CSS refactors. CSS is fragile.
- **Test IDs as fallback.** No good role/label → add `data-testid`.
- **DRY.** Define each locator once in a page object; don't repeat selectors.
- **Don't over-chain.** Break into named bindings when a `->` gets opaque.
- **Locators are lazy.** Creating a locator doesn't touch the DOM — resolution happens on action/assertion.
- **Iterate with `all`.** `(doseq [item (locator/all (page/locator pg ".todo-item"))] …)`
- **Snapshot refs for exploration, stable locators for tests.** Refs shift on every nav; role/label/test-id don't.
