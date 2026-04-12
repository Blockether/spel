# Assertions, events & signals

## Assertions

Wrap with `assert/assert-that` first. Each assertion returns `nil` on success, throws on failure. In `it`/`deftest`, always `(expect (nil? ...))` / `(is (nil? ...))`.

### Page

```clojure
(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My Page")
  (assert/has-url   pa "https://example.org"))
```

### Locator

```clojure
(let [la (assert/assert-that (page/locator pg "h1"))]
  (assert/has-text                  la "Welcome")
  (assert/contains-text             la "partial text")
  (assert/is-visible                la)
  (assert/is-hidden                 la)
  (assert/is-checked                la)
  (assert/is-enabled                la)
  (assert/is-disabled               la)
  (assert/is-editable               la)
  (assert/is-focused                la)
  (assert/is-empty                  la)
  (assert/is-attached               la)
  (assert/is-in-viewport            la)
  (assert/has-value                 la "hello")
  (assert/has-values                la ["a" "b"])
  (assert/has-attribute             la "href" "https://example.org")
  (assert/has-class                 la "active")
  (assert/contains-class            la "active")
  (assert/has-css                   la "color" "rgb(0, 0, 0)")
  (assert/has-id                    la "content")
  (assert/has-role                  la role/navigation)
  (assert/has-count                 la 5)
  (assert/has-js-property           la "dataset.ready" "true")
  (assert/has-accessible-name       la "Submit")
  (assert/has-accessible-description la "Enter your email")
  (assert/matches-aria-snapshot     la "- navigation"))
```

### Negation

Wrap the assertion target with `assert/loc-not`, `assert/page-not`, or `assert/api-not` to expect the opposite.

```clojure
(assert/is-visible (assert/loc-not  (assert/assert-that (page/locator pg ".hidden"))))
(assert/has-title  (assert/page-not (assert/assert-that pg))                  "Wrong Title")
(assert/is-ok      (assert/api-not  (assert/assert-that api-response)))
```

### In tests

```clojure
(expect (nil? (assert/has-text  (assert/assert-that (page/locator page "h1")) "Welcome")))
(expect (nil? (assert/has-title (assert/assert-that page)                     "My Page")))
```

### Global timeout

```clojure
(assert/set-default-assertion-timeout! 10000)
```

## Events & signals

### Dialogs

```clojure
(page/on-dialog   pg (fn [dlg] (.dismiss dlg)))             ; persistent
(page/once-dialog pg (fn [dlg] (println (.message dlg))     ; one-shot
                              (.accept dlg)))
```

### Downloads / popups / console / errors / requests / responses

```clojure
(page/on-download   pg (fn [dl]  (println "Downloaded:" (.suggestedFilename dl))))
(page/on-popup      pg (fn [pp]  (println "Popup URL:"  (page/url pp))))
(page/on-console    pg (fn [msg] (println (.type msg) ":" (.text msg))))
(page/on-page-error pg (fn [err] (println "Page error:" err)))
(page/on-request    pg (fn [req] (println "→" (.method req) (.url req))))
(page/on-response   pg (fn [res] (println "←" (.status res) (.url res))))
```

### Wait-for patterns

```clojure
(let [popup (page/wait-for-popup pg
              #(locator/click (page/locator pg "a")))]
  (page/navigate popup "..."))

(let [dl (page/wait-for-download pg
           #(locator/click (page/locator pg "a.download")))]
  (page/download-save-as! dl "/tmp/file.txt"))

(let [fc (page/wait-for-file-chooser pg
           #(locator/click (page/locator pg "input[type=file]")))]
  (page/file-chooser-set-files! fc "/path/to/file.txt"))
```

## File input

```clojure
(locator/set-input-files! (page/locator pg "input[type=file]") "/path/to/file.txt")
(locator/set-input-files! (page/locator pg "input[type=file]") ["/path/a.txt" "/path/b.txt"])
```
