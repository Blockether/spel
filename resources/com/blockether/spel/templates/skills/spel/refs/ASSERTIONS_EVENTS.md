# Assertions, Events & Signals

Assertions provide test verification for pages, locators, and API responses. Events and signals allow you to handle dialogs, downloads, popups, console messages, page errors, and network activity.

## Assertions

All assertion functions require `assert-that` first. They return `nil` on success, throw on failure.

In test `it` blocks, ALWAYS wrap with `(expect (nil? ...))`.

### Page Assertions

Use `assert/assert-that` with a Page to get PageAssertions.

```clojure
(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My Page")
  (assert/has-url pa "https://example.org"))
```

### Locator Assertions

Use `assert/assert-that` with a Locator to get LocatorAssertions.

```clojure
(let [la (assert/assert-that (page/locator pg "h1"))]
  (assert/has-text la "Welcome")
  (assert/contains-text la "partial text")
  (assert/is-visible la)
  (assert/is-hidden la)
  (assert/is-checked la)
  (assert/is-enabled la)
  (assert/is-disabled la)
  (assert/is-editable la)
  (assert/is-focused la)
  (assert/is-empty la)
  (assert/is-attached la)
  (assert/is-in-viewport la)
  (assert/has-value la "hello")
  (assert/has-values la ["a" "b"])
  (assert/has-attribute la "href" "https://example.org")
  (assert/has-class la "active")
  (assert/contains-class la "active")
  (assert/has-css la "color" "rgb(0, 0, 0)")
  (assert/has-id la "content")
  (assert/has-role la role/navigation)
  (assert/has-count la 5)
  (assert/has-js-property la "dataset.ready" "true")
  (assert/has-accessible-name la "Submit")
  (assert/has-accessible-description la "Enter your email")
  (assert/matches-aria-snapshot la "- navigation"))
```

### Negation

#### Locator Negation

`assert/loc-not` returns negated LocatorAssertions (expect the opposite).

```clojure
(assert/is-visible (assert/loc-not (assert/assert-that (page/locator pg ".hidden"))))
(assert/is-checked (assert/loc-not (assert/assert-that (page/locator pg "#opt-out"))))
```

#### Page Negation

`assert/page-not` returns negated PageAssertions (expect the opposite).

```clojure
(assert/has-title (assert/page-not (assert/assert-that pg)) "Wrong Title")
(assert/has-url (assert/page-not (assert/assert-that pg)) "https://wrong.com")
```

#### API Response Negation

`assert/api-not` returns negated APIResponseAssertions (expect the opposite).

```clojure
(assert/is-ok (assert/assert-that api-response))
(assert/is-ok (assert/api-not (assert/assert-that api-response)))     ; assert NOT ok
```

### Using Assertions in Tests

In test `it` blocks, ALWAYS wrap with `expect`:

```clojure
(expect (nil? (assert/has-text (assert/assert-that (page/locator page "h1")) "Welcome")))
(expect (nil? (assert/has-title (assert/assert-that page) "My Page")))
```

### Timeout Override

Set the default timeout for all assertions:

```clojure
(assert/set-default-assertion-timeout! 10000)
```

## Events & Signals

### Dialog Handling

#### Persistent Dialog Handler

Fires for every dialog.

```clojure
(page/on-dialog pg (fn [dialog] (.dismiss dialog)))
```

#### One-Time Dialog Handler

Fires once, then auto-removes.

```clojure
(page/once-dialog pg (fn [dialog]
  (println "Dialog:" (.message dialog))
  (.accept dialog)))
```

### Download Handling

```clojure
(page/on-download pg (fn [dl] (println "Downloaded:" (.suggestedFilename dl))))
```

### Popup Handling

```clojure
(page/on-popup pg (fn [popup-pg] (println "Popup URL:" (page/url popup-pg))))
```

### Console Messages

```clojure
(page/on-console pg (fn [msg] (println (.type msg) ":" (.text msg))))
```

### Page Errors

```clojure
(page/on-page-error pg (fn [err] (println "Page error:" err)))
```

### Request/Response Events

```clojure
(page/on-request pg (fn [req] (println "→" (.method req) (.url req))))
(page/on-response pg (fn [resp] (println "←" (.status resp) (.url resp))))
```

### Wait For Patterns

#### Wait For Popup

```clojure
(let [popup (page/wait-for-popup pg
              #(locator/click (page/locator pg "a"))]
  (page/navigate popup "..."))
```

#### Wait For Download

```clojure
(let [dl (page/wait-for-download pg
           #(locator/click (page/locator pg "a.download"))]
  (page/download-save-as! dl "/tmp/file.txt"))
```

#### Wait For File Chooser

```clojure
(let [fc (page/wait-for-file-chooser pg
           #(locator/click (page/locator pg "input[type=file]"))]
  (page/file-chooser-set-files! fc "/path/to/file.txt"))
```

## File Input

### Single File

```clojure
(locator/set-input-files! (page/locator pg "input[type=file]") "/path/to/file.txt")
```

### Multiple Files

```clojure
(locator/set-input-files! (page/locator pg "input[type=file]") ["/path/a.txt" "/path/b.txt"])
```
