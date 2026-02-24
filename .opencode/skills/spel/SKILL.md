---
name: spel
description: "com.blockether.spel package - Clojure wrapper for Playwright 1.58.0. Browser automation, testing, assertions, codegen, CLI. Use when working with browser automation, E2E tests, Playwright API, or visual testing in Clojure."
license: Apache-2.0
compatibility: opencode
---

# spel - Clojure Playwright Wrapper

`com.blockether.spel` wraps Playwright Java 1.58.0 with idiomatic Clojure.

## Quick Reference

| Command | Purpose |
|---------|---------|
| `spel --help` | CLI help |
| `spel codegen --help` | Codegen CLI help |
| `spel init-agents --help` | Agent scaffolding help |
| `spel init-agents --loop=opencode` | Scaffold E2E agents for OpenCode (default) |
| `spel init-agents --loop=claude` | Scaffold E2E agents for Claude Code |
| `spel init-agents --loop=vscode` | Scaffold E2E agents for VS Code / Copilot |

## ⚠️ SCI Eval vs Library — Key Naming Differences

In `--eval` mode, functions live in the `spel/` namespace with **different names** than the library:

| You want to... | Library (Clojure) | SCI (`--eval` / daemon) |
|---|---|---|
| Navigate to URL | `(page/navigate pg url)` | `(spel/goto url)` or `(spel/navigate url)` |
| Get page title | `(page/title pg)` | `(spel/title)` |
| Get page URL | `(page/url pg)` | `(spel/url)` |
| Get page HTML | `(page/content pg)` | `(spel/html)` |
| Evaluate JS | `(page/evaluate pg expr)` | `(spel/eval-js expr)` |
| Get text content | `(locator/text-content loc)` | `(spel/text loc)` |
| Get inner text | `(locator/inner-text loc)` | `(spel/inner-text loc)` |
| Take screenshot | `(page/screenshot pg opts)` | `(spel/screenshot opts)` |
| Generate PDF | `(page/pdf pg opts)` | `(spel/pdf opts)` |
| Set viewport | `(page/set-viewport-size pg w h)` | `(spel/set-viewport-size! w h)` |
| CSS selector | `(page/locator pg sel)` | `(spel/$ sel)` |
| Multiple matches | `(page/locator-all pg sel)` | `(spel/$$ sel)` |
| By text | `(page/get-by-text pg text)` | `(spel/$text text)` |
| By role | `(page/get-by-role pg role)` | `(spel/$role role)` |

**Key difference**: Library functions take an explicit `page`/`locator` argument. SCI functions use the implicit page from `spel/start!`.

## SCI Sandbox Capabilities

The SCI eval environment (`--eval` mode) runs in a sandbox with registered namespaces and classes.
### ✅ Available in SCI
- All `spel/`, `snapshot/`, `annotate/`, `input/`, `frame/`, `net/`, `loc/`, `assert/`, `core/` namespaces
- `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.repl`, `clojure.template`
- `clojure.java.io` (aliased as `io`): `io/file`, `io/reader`, `io/writer`, `io/input-stream`, `io/output-stream`, `io/copy`, `io/as-file`, `io/as-url`, `io/resource`, `io/make-parents`, `io/delete-file`
- `slurp`, `spit` — full file read/write
- `java.io.File` — file path operations
- `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.file.Paths` — NIO file operations
- `java.util.Base64` — encoding/decoding
- Playwright Java classes: `Page`, `Browser`, `BrowserContext`, `Locator`, `Frame`, `Request`, `Response`, `Route`, `ElementHandle`, `JSHandle`, `ConsoleMessage`, `Dialog`, `Download`, `WebSocket`, `Tracing`, `Keyboard`, `Mouse`, `Touchscreen`
- All Playwright enums: `AriaRole`, `LoadState`, `WaitUntilState`, `ScreenshotType`, etc.
- Method calls on registered classes (`:allow :all`)
- `role/` namespace for AriaRole constants (e.g., `role/button`, `role/link`)
- `markdown/` namespace for markdown rendering
### File I/O Examples in `--eval`
```clojure
;; Read a file
(slurp "/tmp/data.txt")

;; Write a file
(spit "/tmp/output.txt" "hello world")

;; Use clojure.java.io
(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file "/tmp/deep/nested/file.txt") "content")

;; Base64 encode/decode
(let [encoder (java.util.Base64/getEncoder)
      decoder (java.util.Base64/getDecoder)]
  (->> (.getBytes "hello")
       (.encodeToString encoder)
       (.decode decoder)
       (String.)))

;; NIO file operations
(java.nio.file.Files/exists
  (java.nio.file.Paths/get "/tmp" (into-array String []))
  (into-array java.nio.file.LinkOption []))
```
### ❌ NOT Available in SCI
- Arbitrary Java class construction — only registered classes work
- `require`, `use`, `import` — namespaces are pre-registered, cannot load new ones

## Device Emulation in `--eval` Mode

There are multiple approaches to device emulation depending on what you need:

### Approach 1: Viewport Only (`spel/set-viewport-size!`)
Sets width and height but NOT device pixel ratio, user agent, or touch support.
```clojure
(spel/start!)
(spel/set-viewport-size! 390 844)  ;; iPhone 14 dimensions
(spel/goto "https://example.com")
(spel/screenshot {:path "/tmp/iphone14.png"})
```

### Approach 2: Full Device Preset (CLI daemon `set device`)
Sets viewport + DPR + user agent + touch. Requires the daemon running.
```bash
# From shell (daemon must be running via spel start)
spel set device "iPhone 14"
spel screenshot /tmp/iphone14.png
```

### Approach 3: Restart with Device (library only)
```clojure
;; In library code (NOT --eval), use :device option
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.com"))
```

### Comparison

| Approach | Viewport | DPR | User Agent | Touch | Available in |
|---|---|---|---|---|---|
| `spel/set-viewport-size!` | ✅ | ❌ | ❌ | ❌ | `--eval` |
| `spel set device "Name"` | ✅ | ✅ | ✅ | ✅ | CLI daemon |
| `{:device :name}` option | ✅ | ✅ | ✅ | ✅ | Library only |

---

## Architecture

## Library API Reference

Auto-generated from source code. Each namespace lists public functions with args and description.

### `core` — Lifecycle, browser, context, page

| Function | Args | Description |
|----------|------|-------------|
| `browser-connected?` | [browser] | Returns true if the browser is connected. |
| `browser-contexts` | [browser] | Returns all browser contexts. |
| `browser-type-name` | [bt] | Returns the name of the browser type. |
| `browser-version` | [browser] | Returns the browser version string. |
| `chromium` | [pw] | Returns the Chromium BrowserType. |
| `close!` | [pw] | Closes a Playwright instance and releases all resources. |
| `close-browser!` | [browser] | Closes a browser and all its pages. |
| `close-context!` | [context] | Closes a browser context and all its pages. |
| `close-page!` | [page] | Closes a page. |
| `context-browser` | [context] | Returns the browser that owns this context. |
| `context-clear-cookies!` | [context] | Clears all cookies in the context. |
| `context-clear-permissions!` | [context] | Clears all granted permissions. |
| `context-cookies` | [context] | Returns all cookies in the context. |
| `context-grant-permissions!` | [context permissions] | Grants permissions to the context. |
| `context-pages` | [context] | Returns all pages in a context. |
| `context-route-from-har!` | [context har] \| [context har route-opts] | Routes requests in the context from a HAR file. Replays recorded responses |
| `context-route-web-socket!` | [context pattern handler] | Registers a handler for WebSocket connections matching a URL pattern |
| `context-save-storage-state!` | [context path] | Saves the storage state (cookies, localStorage) to a file. |
| `context-set-default-navigation-timeout!` | [context timeout] | Sets the default navigation timeout. |
| `context-set-default-timeout!` | [context timeout] | Sets the default timeout for context operations. |
| `context-set-extra-http-headers!` | [context headers] | Sets extra HTTP headers for all requests in the context. |
| `context-set-offline!` | [context offline] | Sets the context to offline or online mode. |
| `context-storage-state` | [context] | Returns the storage state (cookies, localStorage) as a JSON string. |
| `create` | [] | Creates a new Playwright instance. |
| `firefox` | [pw] | Returns the Firefox BrowserType. |
| `launch` | [browser-type] \| [browser-type launch-opts] | Launches a browser of the given type. |
| `launch-chromium` | [pw] \| [pw opts] | Launches Chromium browser. |
| `launch-firefox` | [pw] \| [pw opts] | Launches Firefox browser. |
| `launch-persistent-context` | [browser-type user-data-dir] \| [browser-type user-data-dir opts] | Launches a browser with a persistent user data directory (Chrome profile). |
| `launch-webkit` | [pw] \| [pw opts] | Launches WebKit browser. |
| `new-context` | [browser] \| [browser context-opts] | Creates a new browser context with optional configuration. |
| `new-page` | [browser] \| [browser context-opts] | Creates a new page in a browser (creates implicit context). |
| `new-page-from-context` | [context] | Creates a new page in the given context. |
| `run-with-testing-page` | [opts f] | Functional core of `with-testing-page`. Sets up a complete Playwright stack |
| _(macro)_ `safe` | [& body] | Wraps body in try/catch, returning anomaly map on Playwright errors. |
| `video-delete!` | [page] | Deletes the video file for a page. |
| `video-path` | [page] | Returns the video file path for a page, or nil if not recording. |
| `video-save-as!` | [page path] | Saves the video to the specified path. Context must be closed first. |
| `webkit` | [pw] | Returns the WebKit BrowserType. |
| _(macro)_ `with-browser` | [[sym expr] & body] | Binds a browser instance and ensures cleanup. |
| _(macro)_ `with-context` | [[sym expr] & body] | Binds a browser context and ensures cleanup. |
| _(macro)_ `with-page` | [[sym expr] & body] | Binds a page instance and ensures cleanup. |
| _(macro)_ `with-playwright` | [binding-vec & body] | Binds a Playwright instance and ensures cleanup. |
| _(macro)_ `with-testing-page` | [opts-or-binding & args] | All-in-one macro for quick browser testing with automatic resource management. |
| `wrap-error` | [e] | Wraps Playwright exceptions into anomaly maps. |

### `page` — Navigation, locators, content, events

| Function | Args | Description |
|----------|------|-------------|
| `add-script-tag` | [page opts] | Adds a script tag to the page. |
| `add-style-tag` | [page opts] | Adds a style tag to the page. |
| `bring-to-front` | [page] | Brings page to front (activates tab). |
| `content` | [page] | Returns the full HTML content of the page. |
| `emulate-media!` | [page media-opts] | Emulates media type and features. |
| `evaluate` | [page expression] \| [page expression arg] | Evaluates JavaScript expression in the page context. |
| `evaluate-handle` | [page expression] \| [page expression arg] | Like evaluate, but returns a JSHandle. |
| `expose-binding!` | [page name f] | Exposes a Clojure function as a binding. |
| `expose-function!` | [page name f] | Exposes a Clojure function to JavaScript. |
| `frame-by-name` | [page name] | Returns a frame by its name attribute. |
| `frame-by-url` | [page pattern] | Returns a frame by matching URL pattern. |
| `frames` | [page] | Returns all frames in the page. |
| `get-by-alt-text` | [page text] | Locates elements by alt text. |
| `get-by-label` | [page text] | Locates elements by their label text. |
| `get-by-placeholder` | [page text] | Locates elements by placeholder text. |
| `get-by-role` | [page role] \| [page role opts] | Locates elements by their ARIA role. |
| `get-by-test-id` | [page test-id] | Locates elements by test ID attribute. |
| `get-by-text` | [page text] | Locates elements by their text content. |
| `get-by-title` | [page text] | Locates elements by title attribute. |
| `go-back` | [page] \| [page nav-opts] | Navigates back in history. |
| `go-forward` | [page] \| [page nav-opts] | Navigates forward in history. |
| `is-closed?` | [page] | Returns true if the page has been closed. |
| `locator` | [page selector] | Creates a Locator for finding elements on the page. |
| `main-frame` | [page] | Returns the main frame of the page. |
| `navigate` | [page url] \| [page url nav-opts] | Navigates the page to a URL. |
| `on-close` | [page handler] | Registers a handler for page close. |
| `on-console` | [page handler] | Registers a handler for console messages. |
| `on-dialog` | [page handler] | Registers a handler for dialogs. |
| `on-download` | [page handler] | Registers a handler for downloads. |
| `on-page-error` | [page handler] | Registers a handler for page errors. |
| `on-popup` | [page handler] | Registers a handler for popup pages. |
| `on-request` | [page handler] | Registers a handler for requests. |
| `on-response` | [page handler] | Registers a handler for responses. |
| `once-dialog` | [page handler] | Registers a one-time handler for the next dialog. |
| `opener` | [page] | Returns the opener page, if any. |
| `page-context` | [page] | Returns the BrowserContext that the page belongs to. |
| `page-keyboard` | [page] | Returns the Keyboard for this page. |
| `page-mouse` | [page] | Returns the Mouse for this page. |
| `page-touchscreen` | [page] | Returns the Touchscreen for this page. |
| `pdf` | [page] \| [page pdf-opts] | Generates a PDF of the page. Only works in Chromium headless. |
| `reload` | [page] \| [page nav-opts] | Reloads the page. |
| `route!` | [page pattern handler] | Registers a route handler for URL pattern. |
| `route-from-har!` | [page har] \| [page har route-opts] | Routes requests from a HAR file. Replays recorded responses for matching requests. |
| `route-web-socket!` | [page pattern handler] | Registers a handler for WebSocket connections matching a URL pattern. |
| `screenshot` | [page] \| [page ss-opts] | Takes a screenshot of the page. |
| `set-content!` | [page html] \| [page html set-opts] | Sets the HTML content of the page. |
| `set-default-navigation-timeout!` | [page timeout] | Sets the default navigation timeout. |
| `set-default-timeout!` | [page timeout] | Sets the default timeout for page operations. |
| `set-extra-http-headers!` | [page headers] | Sets extra HTTP headers for all requests on this page. |
| `set-viewport-size!` | [page width height] | Sets the viewport size. |
| `title` | [page] | Returns the page title. |
| `unroute!` | [page pattern] | Removes a route handler. |
| `url` | [page] | Returns the current page URL. |
| `video` | [page] | Returns the Video for this page, if recording. |
| `viewport-size` | [page] | Returns the viewport size of the page. |
| `wait-for-download` | [page action] \| [page action opts] | Waits for a download to start while executing `action`. |
| `wait-for-file-chooser` | [page action] \| [page action opts] | Waits for a file chooser dialog while executing `action`. |
| `wait-for-function` | [page expression] | Waits for a JavaScript function to return a truthy value. |
| `wait-for-load-state` | [page] \| [page state] | Waits for the page to reach a load state. |
| `wait-for-popup` | [page action] \| [page action opts] | Waits for a popup page to open while executing `action`. |
| `wait-for-response` | [page url-or-fn callback] | Waits for a response matching the URL or predicate. |
| `wait-for-selector` | [page selector] \| [page selector wait-opts] | Waits for a selector to satisfy a condition. |
| `wait-for-timeout` | [page timeout] | Waits for the specified time in milliseconds. |
| `wait-for-url` | [page url] | Waits for the page to navigate to a URL. |
| `workers` | [page] | Returns all workers in the page. |

### `locator` — Element interactions

| Function | Args | Description |
|----------|------|-------------|
| `all` | [loc] | Returns all elements matching the locator as individual locators. |
| `all-inner-texts` | [loc] | Returns all inner texts for matching elements. |
| `all-text-contents` | [loc] | Returns all text contents for matching elements. |
| `blur` | [loc] | Blurs (removes focus from) the element. |
| `bounding-box` | [loc] | Returns the bounding box of the element. |
| `check` | [loc] \| [loc check-opts] | Checks a checkbox or radio button. |
| `clear` | [loc] | Clears input field content. |
| `click` | [loc] \| [loc click-opts] | Clicks an element. |
| `content-frame` | [loc] | Returns a FrameLocator pointing to the same iframe as this locator. |
| `count-elements` | [loc] | Returns the number of elements matching the locator. |
| `dblclick` | [loc] \| [loc dblclick-opts] | Double-clicks an element. |
| `dispatch-event` | [loc type] | Dispatches a DOM event on the element. |
| `drag-to` | [loc target] | Drags this locator to another locator. |
| `eh-bounding-box` | [eh] | Returns the bounding box of the element handle. |
| `eh-click` | [eh] \| [eh click-opts] | Clicks an element handle. |
| `eh-dispose!` | [eh] | Disposes the element handle. |
| `eh-fill` | [eh value] | Fills text into an element handle. |
| `eh-get-attribute` | [eh name] | Returns an attribute value of the element handle. |
| `eh-inner-html` | [eh] | Returns inner HTML of an element handle. |
| `eh-inner-text` | [eh] | Returns inner text of an element handle. |
| `eh-is-checked?` | [eh] | Returns whether the element handle is checked. |
| `eh-is-enabled?` | [eh] | Returns whether the element handle is enabled. |
| `eh-is-visible?` | [eh] | Returns whether the element handle is visible. |
| `eh-screenshot` | [eh] \| [eh screenshot-opts] | Takes a screenshot of the element. |
| `eh-text-content` | [eh] | Returns text content of an element handle. |
| `element-handle` | [loc] | Returns the ElementHandle for the first matching element. |
| `element-handles` | [loc] | Returns all ElementHandles matching the locator. |
| `evaluate-all` | [loc expression] \| [loc expression arg] | Evaluates JavaScript on all elements matching the locator. |
| `evaluate-locator` | [loc expression] \| [loc expression arg] | Evaluates JavaScript on the element found by this locator. |
| `fill` | [loc value] \| [loc value fill-opts] | Fills an input element with text. |
| `first-element` | [loc] | Returns the first element matching the locator. |
| `focus` | [loc] | Focuses the element. |
| `get-attribute` | [loc name] | Returns the value of an attribute. |
| `highlight` | [loc] | Highlights the element for debugging. |
| `hover` | [loc] \| [loc hover-opts] | Hovers over an element. |
| `inner-html` | [loc] | Returns the inner HTML of the element. |
| `inner-text` | [loc] | Returns the inner text of the element. |
| `input-value` | [loc] | Returns the input value of an input element. |
| `is-checked?` | [loc] | Returns whether the element is checked. |
| `is-disabled?` | [loc] | Returns whether the element is disabled. |
| `is-editable?` | [loc] | Returns whether the element is editable. |
| `is-enabled?` | [loc] | Returns whether the element is enabled. |
| `is-hidden?` | [loc] | Returns whether the element is hidden. |
| `is-visible?` | [loc] | Returns whether the element is visible. |
| `js-as-element` | [handle] | Casts a JSHandle to ElementHandle if possible. |
| `js-dispose!` | [handle] | Disposes the JSHandle. |
| `js-evaluate` | [handle expression] \| [handle expression arg] | Evaluates JavaScript on a JSHandle. |
| `js-get-properties` | [handle] | Gets all properties of a JSHandle. |
| `js-get-property` | [handle name] | Gets a property of a JSHandle. |
| `js-json-value` | [handle] | Returns the JSON value of a JSHandle. |
| `last-element` | [loc] | Returns the last element matching the locator. |
| `loc-filter` | [loc opts] | Filters this locator to a narrower set. |
| `loc-get-by-label` | [loc text] | Locates elements by label within this locator. |
| `loc-get-by-role` | [loc role] | Locates elements by ARIA role within this locator. |
| `loc-get-by-test-id` | [loc test-id] | Locates elements by test ID within this locator. |
| `loc-get-by-text` | [loc text] | Locates elements by text within this locator. |
| `loc-locator` | [loc selector] | Creates a sub-locator within this locator. |
| `locator-screenshot` | [loc] \| [loc ss-opts] | Takes a screenshot of the element. |
| `nth-element` | [loc index] | Returns the nth element matching the locator. |
| `press` | [loc key] \| [loc key press-opts] | Presses a key or key combination. |
| `scroll-into-view` | [loc] | Scrolls element into view. |
| `select-option` | [loc values] | Selects options in a select element. |
| `set-input-files!` | [loc files] | Sets the value of a file input element. |
| `tap-element` | [loc] | Taps an element (for touch devices). |
| `text-content` | [loc] | Returns the text content of the element. |
| `type-text` | [loc text] \| [loc text type-opts] | Types text into an element character by character. |
| `uncheck` | [loc] \| [loc uncheck-opts] | Unchecks a checkbox. |
| `wait-for` | [loc] \| [loc wait-opts] | Waits for the locator to satisfy a condition. |

### `assertions` — Playwright assertions

| Function | Args | Description |
|----------|------|-------------|
| `api-not` | [ara] | Returns negated APIResponseAssertions (expect the opposite). |
| `assert-that` | [target] | Creates an assertion object for the given Playwright instance. |
| `contains-class` | [la class-val] \| [la class-val opts] | Asserts the locator's class attribute contains the specified class. |
| `contains-text` | [la text] \| [la text opts] | Asserts the locator contains the specified text. |
| `has-accessible-description` | [la desc] | Asserts the locator has the specified accessible description. |
| `has-accessible-error-message` | [la msg] | Asserts the locator has the specified accessible error message. |
| `has-accessible-name` | [la name-val] | Asserts the locator has the specified accessible name. |
| `has-attribute` | [la name value] \| [la name value opts] | Asserts the locator has the specified attribute with value. |
| `has-class` | [la class-val] \| [la class-val opts] | Asserts the locator has the specified CSS class. |
| `has-count` | [la count] \| [la count opts] | Asserts the locator resolves to the expected number of elements. |
| `has-css` | [la name value] \| [la name value opts] | Asserts the locator has the specified CSS property with value. |
| `has-id` | [la id] \| [la id opts] | Asserts the locator has the specified ID. |
| `has-js-property` | [la name value] | Asserts the locator has the specified JavaScript property. |
| `has-role` | [la role] | Asserts the locator has the specified ARIA role. |
| `has-text` | [la text] \| [la text opts] | Asserts the locator has the specified text. |
| `has-title` | [pa title] \| [pa title opts] | Asserts the page has the specified title. |
| `has-url` | [pa url] \| [pa url opts] | Asserts the page has the specified URL. |
| `has-value` | [la value] \| [la value opts] | Asserts the locator (input) has the specified value. |
| `has-values` | [la values] \| [la values opts] | Asserts the locator (multi-select) has the specified values. |
| `is-attached` | [la] \| [la opts] | Asserts the locator is attached to the DOM. |
| `is-checked` | [la] \| [la opts] | Asserts the locator (checkbox/radio) is checked. |
| `is-disabled` | [la] \| [la opts] | Asserts the locator is disabled. |
| `is-editable` | [la] \| [la opts] | Asserts the locator is editable. |
| `is-empty` | [la] | Asserts the locator (input) is empty. |
| `is-enabled` | [la] \| [la opts] | Asserts the locator is enabled. |
| `is-focused` | [la] \| [la opts] | Asserts the locator is focused. |
| `is-hidden` | [la] \| [la opts] | Asserts the locator is hidden. |
| `is-in-viewport` | [la] \| [la opts] | Asserts the locator is in the viewport. |
| `is-ok` | [ara] | Asserts the API response status is 2xx. |
| `is-visible` | [la] \| [la opts] | Asserts the locator is visible. |
| `loc-not` | [la] | Returns negated LocatorAssertions (expect the opposite). |
| `matches-aria-snapshot` | [la snapshot] | Asserts the locator matches the ARIA snapshot. |
| `page-not` | [pa] | Returns negated PageAssertions (expect the opposite). |
| `set-default-assertion-timeout!` | [timeout] | Sets the default timeout for all assertions. |

### `frame` — Frame/iframe operations

| Function | Args | Description |
|----------|------|-------------|
| `child-frames` | [frame] | Returns child frames. |
| `fl-first` | [fl] | Returns the first FrameLocator. |
| `fl-get-by-label` | [fl text] | Locates by label within a FrameLocator. |
| `fl-get-by-role` | [fl role] | Locates by ARIA role within a FrameLocator. |
| `fl-get-by-text` | [fl text] | Locates by text within a FrameLocator. |
| `fl-last` | [fl] | Returns the last FrameLocator. |
| `fl-locator` | [fl selector] | Creates a Locator within a FrameLocator. |
| `fl-nth` | [fl index] | Returns the nth FrameLocator. |
| `frame-content` | [frame] | Returns the HTML content of the frame. |
| `frame-evaluate` | [frame expression] \| [frame expression arg] | Evaluates JavaScript in the frame context. |
| `frame-get-by-label` | [frame text] | Locates elements by label in the frame. |
| `frame-get-by-role` | [frame role] | Locates elements by ARIA role in the frame. |
| `frame-get-by-test-id` | [frame test-id] | Locates elements by test ID in the frame. |
| `frame-get-by-text` | [frame text] | Locates elements by text in the frame. |
| `frame-locator` | [frame selector] | Creates a Locator for the frame. |
| `frame-locator-obj` | [page-or-frame selector] | Creates a FrameLocator for an iframe. |
| `frame-name` | [frame] | Returns the frame name. |
| `frame-navigate` | [frame url] \| [frame url nav-opts] | Navigates the frame to a URL. |
| `frame-page` | [frame] | Returns the page that owns this frame. |
| `frame-set-content!` | [frame html] \| [frame html set-opts] | Sets the HTML content of the frame. |
| `frame-title` | [frame] | Returns the frame title. |
| `frame-url` | [frame] | Returns the frame URL. |
| `frame-wait-for-function` | [frame expression] | Waits for a JavaScript function to return truthy in the frame. |
| `frame-wait-for-load-state` | [frame] \| [frame state] | Waits for the frame to reach a load state. |
| `frame-wait-for-selector` | [frame selector] \| [frame selector wait-opts] | Waits for a selector in the frame. |
| `is-detached?` | [frame] | Returns whether the frame has been detached. |
| `parent-frame` | [frame] | Returns the parent frame. |

### `input` — Keyboard, mouse, touchscreen

| Function | Args | Description |
|----------|------|-------------|
| `key-down` | [keyboard key] | Dispatches a keydown event. |
| `key-insert-text` | [keyboard text] | Inserts text without key events. |
| `key-press` | [keyboard key] \| [keyboard key press-opts] | Presses a key on the keyboard. |
| `key-type` | [keyboard text] \| [keyboard text type-opts] | Types text character by character. |
| `key-up` | [keyboard key] | Dispatches a keyup event. |
| `mouse-click` | [mouse x y] \| [mouse x y click-opts] | Clicks at the given coordinates. |
| `mouse-dblclick` | [mouse x y] \| [mouse x y dblclick-opts] | Double-clicks at the given coordinates. |
| `mouse-down` | [mouse] | Dispatches a mousedown event. |
| `mouse-move` | [mouse x y] \| [mouse x y move-opts] | Moves the mouse to the given coordinates. |
| `mouse-up` | [mouse] | Dispatches a mouseup event. |
| `mouse-wheel` | [mouse delta-x delta-y] | Dispatches a wheel event. |
| `touchscreen-tap` | [ts x y] | Taps at the given coordinates. |

### `network` — Request/response/routing

| Function | Args | Description |
|----------|------|-------------|
| `request-all-headers` | [req] | Returns all request headers including redirects. |
| `request-failure` | [req] | Returns the failure text if the request failed. |
| `request-frame` | [req] | Returns the frame that initiated this request. |
| `request-headers` | [req] | Returns the request headers as a map. |
| `request-is-navigation?` | [req] | Returns whether this is a navigation request. |
| `request-method` | [req] | Returns the request HTTP method. |
| `request-post-data` | [req] | Returns the request POST data. |
| `request-post-data-buffer` | [req] | Returns the request POST data as bytes. |
| `request-redirected-from` | [req] | Returns the request that redirected to this one. |
| `request-redirected-to` | [req] | Returns the request this was redirected to. |
| `request-resource-type` | [req] | Returns the resource type (e.g. document, script, image). |
| `request-response` | [req] | Returns the response for this request. |
| `request-timing` | [req] | Returns the request timing information. |
| `request-url` | [req] | Returns the request URL. |
| `response-all-headers` | [resp] | Returns all response headers. |
| `response-body` | [resp] | Returns the response body as bytes. |
| `response-finished` | [resp] | Returns nil when response finishes, or the failure error string. |
| `response-frame` | [resp] | Returns the frame that received this response. |
| `response-header-value` | [resp name] | Returns the value of a specific header. |
| `response-header-values` | [resp name] | Returns all values for a specific header. |
| `response-headers` | [resp] | Returns the response headers. |
| `response-ok?` | [resp] | Returns whether the response status is 2xx. |
| `response-request` | [resp] | Returns the request for this response. |
| `response-status` | [resp] | Returns the HTTP status code. |
| `response-status-text` | [resp] | Returns the HTTP status text. |
| `response-text` | [resp] | Returns the response body as text. |
| `response-url` | [resp] | Returns the response URL. |
| `route-abort!` | [route] \| [route error-code] | Aborts the route. |
| `route-continue!` | [route] \| [route opts] | Continues the route, optionally modifying the request. |
| `route-fallback!` | [route] | Falls through to the next route handler. |
| `route-fetch!` | [route] | Performs the request and returns the response. |
| `route-fulfill!` | [route opts] | Fulfills the route with a custom response. |
| `route-request` | [route] | Returns the request being routed. |
| `ws-is-closed?` | [ws] | Returns whether the WebSocket is closed. |
| `ws-on-close` | [ws handler] | Registers a handler for WebSocket close. |
| `ws-on-error` | [ws handler] | Registers a handler for WebSocket errors. |
| `ws-on-message` | [ws handler] | Registers a handler for incoming messages. |
| `ws-url` | [ws] | Returns the WebSocket URL. |
| `wsf-binary` | [frame] | Returns the binary content of a WebSocket frame. |
| `wsf-text` | [frame] | Returns the text content of a WebSocket frame. |
| `wsr-close!` | [wsr] | Closes the WebSocket connection from the server side. |
| `wsr-connect-to-server!` | [wsr] | Connects to the real server WebSocket. |
| `wsr-on-close` | [wsr handler] | Registers a handler for close events. |
| `wsr-on-message` | [wsr handler] | Registers a handler for client messages on the route. |
| `wsr-send!` | [wsr message] | Sends a message to the client. |
| `wsr-url` | [wsr] | Returns the URL of a WebSocketRoute. |

### `api` — REST API testing

| Function | Args | Description |
|----------|------|-------------|
| `api-delete` | [ctx url] \| [ctx url opts] | Sends a DELETE request. |
| `api-dispose!` | [ctx] | Disposes the APIRequestContext and all responses. |
| `api-fetch` | [ctx url] \| [ctx url opts] | Sends a request with custom method (set via :method in opts). |
| `api-get` | [ctx url] \| [ctx url opts] | Sends a GET request. |
| `api-head` | [ctx url] \| [ctx url opts] | Sends a HEAD request. |
| `api-patch` | [ctx url] \| [ctx url opts] | Sends a PATCH request. |
| `api-post` | [ctx url] \| [ctx url opts] | Sends a POST request. |
| `api-put` | [ctx url] \| [ctx url opts] | Sends a PUT request. |
| `api-request` | [pw] | Returns the APIRequest for the Playwright instance. |
| `api-response->map` | [resp] | Converts an APIResponse to a Clojure map. |
| `api-response-body` | [resp] | Returns the response body as bytes. |
| `api-response-dispose!` | [resp] | Disposes the APIResponse. |
| `api-response-headers` | [resp] | Returns the response headers. |
| `api-response-headers-array` | [resp] | Returns the response headers as a vector of {:name :value} maps. |
| `api-response-ok?` | [resp] | Returns whether the response is OK (2xx). |
| `api-response-status` | [resp] | Returns the HTTP status code. |
| `api-response-status-text` | [resp] | Returns the HTTP status text. |
| `api-response-text` | [resp] | Returns the response body as text. |
| `api-response-url` | [resp] | Returns the response URL. |
| `context-api` | [ctx] | Returns the APIRequestContext for a BrowserContext. |
| `fd-append` | [fd name value] | Appends a field to FormData. |
| `fd-set` | [fd name value] | Sets a field in FormData. |
| `form-data` | [] | Creates a new FormData instance. |
| `map->form-data` | [m] | Converts a Clojure map to FormData. |
| `new-api-context` | [api-req] \| [api-req opts] | Creates a new APIRequestContext. |
| `page-api` | [pg] | Returns the APIRequestContext for a Page. |
| `request!` | [pw method url] \| [pw method url opts] | Fire-and-forget HTTP request. Creates an ephemeral context, makes the |
| `request-options` | [opts] | Creates RequestOptions from a map. |
| `retry` | [f] \| [f opts] | Execute `f` (a no-arg function) with retry logic. |
| `run-with-page-api` | [pg opts f] | Functional core of `with-page-api`. Creates an APIRequestContext from a Page |
| `run-with-testing-api` | [opts f] | Functional core of `with-testing-api`. Sets up a complete Playwright stack |
| _(macro)_ `with-api-context` | [[sym expr] & body] | Binds a single APIRequestContext and ensures disposal. |
| _(macro)_ `with-api-contexts` | [bindings & body] | Binds multiple APIRequestContexts and disposes all on exit. |
| _(macro)_ `with-hooks` | [hooks & body] | Execute body with the given hooks merged into `*hooks*`. |
| _(macro)_ `with-page-api` | [pg opts binding-vec & body] | Create an APIRequestContext from a Page with custom options. |
| _(macro)_ `with-retry` | [opts-or-body & body] | Execute body with retry logic. |
| _(macro)_ `with-testing-api` | [opts-or-binding & args] | All-in-one macro for API testing with automatic resource management. |

### `allure` — Allure test reporting

_Failed to load: Syntax error macroexpanding at (com/blockether/spel/allure.clj:1:1)._


### `snapshot` — Accessibility snapshots

| Function | Args | Description |
|----------|------|-------------|
| `capture-full-snapshot` | [page] | Captures a snapshot of the page and all its iframes. |
| `capture-snapshot` | [page] \| [page opts] | Captures an accessibility snapshot of the page with numbered refs. |
| `capture-snapshot-for-frame` | [_frame frame-ordinal] | Captures an accessibility snapshot for a specific frame. |
| `clear-refs!` | [page] | Removes all data-pw-ref attributes from the page. |
| `ref-bounding-box` | [refs ref-id] | Returns the bounding box for a ref from the last snapshot. |
| `resolve-ref` | [page ref-id] | Resolves a ref ID to a Playwright Locator. |

### `annotate` — Page annotation overlays

| Function | Args | Description |
|----------|------|-------------|
| `annotated-screenshot` | [page refs] \| [page refs opts] | Takes a screenshot with annotation overlays (convenience function). |
| `audit-screenshot` | [page caption] \| [page caption opts] | Takes a screenshot with an optional caption bar at the bottom. |
| `check-visible-refs` | [page refs] | Runs JavaScript in the page to determine which refs are truly visible. |
| `filter-annotatable` | [refs] | Filters refs to only those worth rendering as overlays. |
| `inject-action-markers!` | [page ref-ids] | Injects prominent pre-action markers on specific snapshot refs. |
| `inject-overlays!` | [page refs] \| [page refs opts] | Injects annotation overlays into the page DOM for visible elements only. |
| `remove-action-markers!` | [page] | Removes all pre-action markers from the page DOM. |
| `remove-containers` | [refs] | Removes refs whose bbox fully contains another ref's bbox. |
| `remove-overlays!` | [page] | Removes all annotation overlays from the page DOM. |
| `report->html` | [entries] \| [entries opts] | Builds a rich HTML report from a sequence of typed entries. |
| `report->pdf` | [page entries] \| [page entries opts] | Renders a rich HTML report to PDF via Playwright's page.pdf(). |
| `save-annotated-screenshot!` | [page refs path] \| [page refs path opts] | Takes an annotated screenshot and saves it to a file. |
| `save-audit-screenshot!` | [page caption path] \| [page caption path opts] | Takes an audit screenshot and saves it to a file. |
| `visible-refs` | [viewport refs] | Filters refs to only those whose bbox is at least partially visible |

### `codegen` — JSONL to Clojure transformer

| Function | Args | Description |
|----------|------|-------------|
| `-main` | [& args] | CLI entry point. Transforms JSONL recording to Clojure code. |
| `jsonl->clojure` | [path] \| [path opts] | Reads a JSONL file and returns Clojure test code as a string. |
| `jsonl-str->clojure` | [jsonl-str] \| [jsonl-str opts] | Transforms a JSONL string into Clojure source code. |

### `util` — Dialog, download, console, CDP, clock, tracing, video, workers

| Function | Args | Description |
|----------|------|-------------|
| `cdp-detach!` | [session] | Detaches the CDP session. |
| `cdp-on` | [session event handler] | Registers a handler for CDP events. |
| `cdp-send` | [session method] \| [session method params] | Sends a Chrome DevTools Protocol command. |
| `clock-fast-forward!` | [clock ticks] | Fast-forwards the clock by the given time. |
| `clock-install!` | [clock] | Installs fake timers on the clock. |
| `clock-pause-at!` | [clock time] | Pauses the clock at the given time. |
| `clock-resume!` | [clock] | Resumes the clock. |
| `clock-set-fixed-time!` | [clock time] | Sets the clock to a fixed time. |
| `clock-set-system-time!` | [clock time] | Sets the system time. |
| `console-args` | [msg] | Returns the console message arguments as JSHandles. |
| `console-location` | [msg] | Returns the source location of the console message. |
| `console-page` | [msg] | Returns the page the console message belongs to. |
| `console-text` | [msg] | Returns the console message text. |
| `console-type` | [msg] | Returns the console message type (log, debug, info, error, warning, etc). |
| `context-tracing` | [context] | Returns the Tracing for a context. |
| `dialog-accept!` | [dialog] \| [dialog prompt-text] | Accepts the dialog. |
| `dialog-default-value` | [dialog] | Returns the default value for prompt dialogs. |
| `dialog-dismiss!` | [dialog] | Dismisses the dialog. |
| `dialog-message` | [dialog] | Returns the dialog message. |
| `dialog-type` | [dialog] | Returns the dialog type (alert, confirm, prompt, beforeunload). |
| `download-cancel!` | [download] | Cancels the download. |
| `download-failure` | [download] | Returns the download failure reason, or nil. |
| `download-page` | [download] | Returns the page the download belongs to. |
| `download-path` | [download] | Returns the local path to the downloaded file. |
| `download-save-as!` | [download path] | Saves the download to the given path. |
| `download-suggested-filename` | [download] | Returns the suggested filename. |
| `download-url` | [download] | Returns the download URL. |
| `file-chooser-element` | [fc] | Returns the element handle for the file input. |
| `file-chooser-is-multiple?` | [fc] | Returns whether the file chooser accepts multiple files. |
| `file-chooser-page` | [fc] | Returns the page the file chooser belongs to. |
| `file-chooser-set-files!` | [fc files] | Sets the files for the file chooser. |
| `page-clock` | [page] | Returns the Clock for a page. |
| `selectors` | [pw] | Returns the Selectors for a Playwright instance. |
| `selectors-register!` | [sels name script] | Registers a custom selector engine. |
| `tracing-start!` | [tracing] \| [tracing trace-opts] | Starts tracing. |
| `tracing-stop!` | [tracing] \| [tracing stop-opts] | Stops tracing and saves the trace file. |
| `video-delete!` | [video] | Deletes the video file. |
| `video-path` | [video] | Returns the path to the video file. |
| `video-save-as!` | [video path] | Saves the video to the given path. |
| `web-error-error` | [we] | Returns the underlying error for this web error. |
| `web-error-page` | [we] | Returns the page that generated this web error, if any. |
| `worker-evaluate` | [worker expression] \| [worker expression arg] | Evaluates JavaScript in the worker context. |
| `worker-url` | [worker] | Returns the worker URL. |

### `options` — Java option builders (80+)

| Function | Args | Description |
|----------|------|-------------|
| `->check-options` | [opts] | Converts a map to Locator$CheckOptions. |
| `->click-options` | [opts] | Converts a map to Locator$ClickOptions. |
| `->context-route-from-har-options` | [opts] | Converts a map to BrowserContext$RouteFromHAROptions. |
| `->cookie` | [opts] | Creates a Cookie instance from a map. |
| `->dblclick-options` | [opts] | Converts a map to Locator$DblclickOptions. |
| `->dispatch-event-options` | [opts] | Converts a map to Locator$DispatchEventOptions. |
| `->drag-to-options` | [opts] | Converts a map to Locator$DragToOptions. |
| `->eh-check-options` | [opts] | Converts a map to ElementHandle$CheckOptions. |
| `->eh-click-options` | [opts] | Converts a map to ElementHandle$ClickOptions. |
| `->eh-dblclick-options` | [opts] | Converts a map to ElementHandle$DblclickOptions. |
| `->eh-fill-options` | [opts] | Converts a map to ElementHandle$FillOptions. |
| `->eh-hover-options` | [opts] | Converts a map to ElementHandle$HoverOptions. |
| `->eh-press-options` | [opts] | Converts a map to ElementHandle$PressOptions. |
| `->eh-screenshot-options` | [opts] | Converts a map to ElementHandle$ScreenshotOptions. |
| `->eh-scroll-into-view-options` | [opts] | Converts a map to ElementHandle$ScrollIntoViewIfNeededOptions. |
| `->eh-select-option-options` | [opts] | Converts a map to ElementHandle$SelectOptionOptions. |
| `->eh-set-input-files-options` | [opts] | Converts a map to ElementHandle$SetInputFilesOptions. |
| `->eh-tap-options` | [opts] | Converts a map to ElementHandle$TapOptions. |
| `->eh-type-options` | [opts] | Converts a map to ElementHandle$TypeOptions. |
| `->eh-uncheck-options` | [opts] | Converts a map to ElementHandle$UncheckOptions. |
| `->eh-wait-for-element-state-options` | [opts] | Converts a map to ElementHandle$WaitForElementStateOptions. |
| `->emulate-media-options` | [opts] | Converts a map to Page$EmulateMediaOptions. |
| `->fill-options` | [opts] | Converts a map to Locator$FillOptions. |
| `->focus-options` | [opts] | Converts a map to Locator$FocusOptions. |
| `->frame-add-script-tag-options` | [opts] | Converts a map to Frame$AddScriptTagOptions. |
| `->frame-add-style-tag-options` | [opts] | Converts a map to Frame$AddStyleTagOptions. |
| `->frame-navigate-options` | [opts] | Converts a map to Frame$NavigateOptions. |
| `->frame-set-content-options` | [opts] | Converts a map to Frame$SetContentOptions. |
| `->frame-wait-for-function-options` | [opts] | Converts a map to Frame$WaitForFunctionOptions. |
| `->frame-wait-for-selector-options` | [opts] | Converts a map to Frame$WaitForSelectorOptions. |
| `->frame-wait-for-url-options` | [opts] | Converts a map to Frame$WaitForURLOptions. |
| `->get-attribute-options` | [opts] | Converts a map to Locator$GetAttributeOptions. |
| `->get-by-role-options` | [opts] | Converts a map to Page$GetByRoleOptions. |
| `->go-back-options` | [opts] | Converts a map to Page$GoBackOptions. |
| `->go-forward-options` | [opts] | Converts a map to Page$GoForwardOptions. |
| `->hover-options` | [opts] | Converts a map to Locator$HoverOptions. |
| `->inner-html-options` | [opts] | Converts a map to Locator$InnerHTMLOptions. |
| `->inner-text-options` | [opts] | Converts a map to Locator$InnerTextOptions. |
| `->input-value-options` | [opts] | Converts a map to Locator$InputValueOptions. |
| `->is-checked-options` | [opts] | Converts a map to Locator$IsCheckedOptions. |
| `->is-disabled-options` | [opts] | Converts a map to Locator$IsDisabledOptions. |
| `->is-editable-options` | [opts] | Converts a map to Locator$IsEditableOptions. |
| `->is-enabled-options` | [opts] | Converts a map to Locator$IsEnabledOptions. |
| `->is-hidden-options` | [opts] | Converts a map to Locator$IsHiddenOptions. |
| `->is-visible-options` | [opts] | Converts a map to Locator$IsVisibleOptions. |
| `->keyboard-press-options` | [opts] | Converts a map to Keyboard$PressOptions. |
| `->keyboard-type-options` | [opts] | Converts a map to Keyboard$TypeOptions. |
| `->launch-options` | [opts] | Converts a map to BrowserType$LaunchOptions. |
| `->launch-persistent-context-options` | [opts] | Converts a map to BrowserType$LaunchPersistentContextOptions. |
| `->locator-screenshot-options` | [opts] | Converts a map to Locator$ScreenshotOptions. |
| `->mouse-click-options` | [opts] | Converts a map to Mouse$ClickOptions. |
| `->mouse-dblclick-options` | [opts] | Converts a map to Mouse$DblclickOptions. |
| `->mouse-down-options` | [opts] | Converts a map to Mouse$DownOptions. |
| `->mouse-move-options` | [opts] | Converts a map to Mouse$MoveOptions. |
| `->mouse-up-options` | [opts] | Converts a map to Mouse$UpOptions. |
| `->navigate-options` | [opts] | Converts a map to Page$NavigateOptions. |
| `->new-context-options` | [opts] | Converts a map to Browser$NewContextOptions. |
| `->new-page-options` | [opts] | Converts a map to Browser$NewPageOptions. |
| `->page-add-script-tag-options` | [opts] | Converts a map to Page$AddScriptTagOptions. |
| `->page-add-style-tag-options` | [opts] | Converts a map to Page$AddStyleTagOptions. |
| `->page-route-from-har-options` | [opts] | Converts a map to Page$RouteFromHAROptions. |
| `->page-wait-for-function-options` | [opts] | Converts a map to Page$WaitForFunctionOptions. |
| `->page-wait-for-url-options` | [opts] | Converts a map to Page$WaitForURLOptions. |
| `->pdf-options` | [opts] | Converts a map to Page$PdfOptions. |
| `->press-options` | [opts] | Converts a map to Locator$PressOptions. |
| `->reload-options` | [opts] | Converts a map to Page$ReloadOptions. |
| `->screen-size` | [opts] | Creates a ScreenSize instance. |
| `->screenshot-options` | [opts] | Converts a map to Page$ScreenshotOptions. |
| `->scroll-into-view-options` | [opts] | Converts a map to Locator$ScrollIntoViewIfNeededOptions. |
| `->select-option-options` | [opts] | Converts a map to Locator$SelectOptionOptions. |
| `->set-content-options` | [opts] | Converts a map to Page$SetContentOptions. |
| `->set-input-files-options` | [opts] | Converts a map to Locator$SetInputFilesOptions. |
| `->storage-state-options` | [opts] | Converts a map to BrowserContext$StorageStateOptions. |
| `->tap-options` | [opts] | Converts a map to Locator$TapOptions. |
| `->text-content-options` | [opts] | Converts a map to Locator$TextContentOptions. |
| `->tracing-start-options` | [opts] | Converts a map to Tracing$StartOptions. |
| `->tracing-stop-options` | [opts] | Converts a map to Tracing$StopOptions. |
| `->type-options` | [opts] | Converts a map to Locator$TypeOptions. |
| `->uncheck-options` | [opts] | Converts a map to Locator$UncheckOptions. |
| `->viewport-size` | [opts] | Creates a ViewportSize instance. |
| `->wait-for-download-options` | [opts] | Converts a map to Page$WaitForDownloadOptions. |
| `->wait-for-file-chooser-options` | [opts] | Converts a map to Page$WaitForFileChooserOptions. |
| `->wait-for-options` | [opts] | Converts a map to Locator$WaitForOptions. |
| `->wait-for-popup-options` | [opts] | Converts a map to Page$WaitForPopupOptions. |
| `->wait-for-request-finished-options` | [opts] | Converts a map to Page$WaitForRequestFinishedOptions. |
| `->wait-for-request-options` | [opts] | Converts a map to Page$WaitForRequestOptions. |
| `->wait-for-response-options` | [opts] | Converts a map to Page$WaitForResponseOptions. |
| `->wait-for-selector-options` | [opts] | Converts a map to Page$WaitForSelectorOptions. |



### Error Handling

Uses `com.blockether.anomaly` instead of throwing exceptions:

```clojure
;; All wrapped functions return either a value or an anomaly map
(let [result (page/navigate pg "https://example.com")]
  (if (anomaly/anomaly? result)
    (println "Error:" (:cognitect.anomalies/message result))
    (println "Navigated!")))
```

| Playwright Exception | Anomaly Category | Error Type Keyword |
|---------------------|------------------|-------------------|
| `TimeoutError` | `:cognitect.anomalies/busy` | `:playwright.error/timeout` |
| `TargetClosedError` | `:cognitect.anomalies/interrupted` | `:playwright.error/target-closed` |
| `PlaywrightException` | `:cognitect.anomalies/fault` | `:playwright.error/playwright` |
| Generic `Exception` | `:cognitect.anomalies/fault` | `:playwright.error/unknown` |

### Resource Lifecycle Macros

**Always use macros for cleanup.** They nest naturally:

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser)]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        ;; returns nil on success, throws on failure
        (assert/has-title (assert/assert-that pg) "Example Domain")))))
```

| Macro | Cleans Up |
|-------|-----------|
| `with-playwright` | Playwright instance |
| `with-browser` | Browser instance |
| `with-context` | BrowserContext |
| `with-page` | Page instance |

### Standalone Testing Page

For quick tests, scripts, and standalone test cases, `with-testing-page` creates the entire Playwright stack (pw → browser → context → page) in one shot — no nesting required:

```clojure
(require '[com.blockether.spel.core :as core]
         '[com.blockether.spel.page :as page])

;; Minimal — headless Chromium, default viewport
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com")
  (page/title pg))
;; => "Example Domain"
```

Pass an opts map for device emulation, viewport presets, or browser selection:

```clojure
;; Device emulation
(core/with-testing-page {:device :iphone-14} [pg]
  (page/navigate pg "https://example.com"))

;; Viewport preset
(core/with-testing-page {:viewport :desktop-hd :locale "fr-FR"} [pg]
  (page/navigate pg "https://example.com"))

;; Firefox, headed mode
(core/with-testing-page {:browser-type :firefox :headless false} [pg]
  (page/navigate pg "https://example.com"))

;; Persistent profile (keeps login sessions across runs)
(core/with-testing-page {:profile "/tmp/my-chrome-profile"} [pg]
  (page/navigate pg "https://example.com"))

;; Custom browser executable + extra args
(core/with-testing-page {:executable-path "/usr/bin/chromium"
                         :args ["--disable-gpu"]} [pg]
  (page/navigate pg "https://example.com"))
```

| Option | Values | Default |
|--------|--------|---------|
| `:browser-type` | `:chromium`, `:firefox`, `:webkit` | `:chromium` |
| `:headless` | `true`, `false` | `true` |
| `:device` | `:iphone-14`, `:pixel-7`, `:ipad`, `:desktop-chrome`, etc. | — |
| `:viewport` | `:mobile`, `:tablet`, `:desktop-hd`, `{:width N :height N}` | browser default |
| `:slow-mo` | Millis to slow down operations | — |
| `:profile` | String path to persistent user data dir | — |
| `:executable-path` | String path to browser executable | — |
| `:channel` | `"chrome"`, `"msedge"`, etc. | — |
| `:proxy` | `{:server "..." :bypass "..." :username "..." :password "..."}` | — |
| `:args` | Vector of extra browser CLI args | — |
| `:downloads-path` | String path for downloaded files | — |
| `:timeout` | Max ms to wait for browser launch | — |
| `:chromium-sandbox` | `true`, `false` | — |
| + any key accepted by `new-context` | `:locale`, `:color-scheme`, `:timezone-id`, `:storage-state`, etc. | — |

When the Allure reporter is active (either Lazytest or clojure.test), tracing (screenshots + DOM snapshots + network) and HAR recording are enabled automatically — zero configuration. Trace and HAR files are attached directly to the Allure test result.

#### Device Presets

| Keyword | Viewport | Mobile |
|---------|----------|--------|
| `:iphone-se` | 375×667 | ✓ |
| `:iphone-12` | 390×844 | ✓ |
| `:iphone-14` | 390×844 | ✓ |
| `:iphone-14-pro` | 393×852 | ✓ |
| `:iphone-15` | 393×852 | ✓ |
| `:iphone-15-pro` | 393×852 | ✓ |
| `:ipad` | 810×1080 | ✓ |
| `:ipad-mini` | 768×1024 | ✓ |
| `:ipad-pro-11` | 834×1194 | ✓ |
| `:ipad-pro` | 1024×1366 | ✓ |
| `:pixel-5` | 393×851 | ✓ |
| `:pixel-7` | 412×915 | ✓ |
| `:galaxy-s24` | 360×780 | ✓ |
| `:galaxy-s9` | 360×740 | ✓ |
| `:desktop-chrome` | 1280×720 | ✗ |
| `:desktop-firefox` | 1280×720 | ✗ |
| `:desktop-safari` | 1280×720 | ✗ |

#### Viewport Presets

| Keyword | Size |
|---------|------|
| `:mobile` | 375×667 |
| `:mobile-lg` | 428×926 |
| `:tablet` | 768×1024 |
| `:tablet-lg` | 1024×1366 |
| `:desktop` | 1280×720 |
| `:desktop-hd` | 1920×1080 |
| `:desktop-4k` | 3840×2160 |

---

## Common Patterns

### Locating Elements

```clojure
;; By CSS
(page/locator pg "h1")
(page/locator pg "#my-id")

;; By text
(page/get-by-text pg "Click me")

;; By role (requires [com.blockether.spel.roles :as role])
(page/get-by-role pg role/button)

;; By role + name filter
(page/get-by-role pg role/link {:name "Learn more"})

;; By role + exact name match
(page/get-by-role pg role/button {:name "Submit" :exact true})

;; By label
(page/get-by-label pg "Email")

;; By placeholder
(page/get-by-placeholder pg "Enter email")

;; By test ID
(page/get-by-test-id pg "submit-btn")

;; Sub-locators
(locator/loc-locator (page/locator pg ".card") "h2")
(locator/loc-get-by-text (page/locator pg ".card") "Title")
```

### Strict Mode & Multiple Elements

Playwright uses **strict mode by default** — all locator actions (`click`, `fill`, `text-content`, etc.) require the locator to resolve to **exactly one element**. If multiple elements match, Playwright throws a strict mode violation error.

This is intentional: it prevents accidentally interacting with the wrong element.

**Example error:**

```
Error: strict mode violation: locator("h1") resolved to 4 elements
```

**How to handle multiple matches:**

```clojure
;; WRONG — throws if multiple h1 elements exist
(locator/text-content (page/locator pg "h1"))
(spel/text "h1")  ;; same thing in SCI/eval mode

;; RIGHT — narrow to first element
(locator/text-content (locator/first-element (page/locator pg "h1")))
(spel/text (spel/first "h1"))  ;; SCI/eval equivalent

;; RIGHT — get ALL matching texts as a vector
(locator/all-text-contents (page/locator pg "h1"))
(spel/all-text-contents "h1")  ;; SCI/eval equivalent

;; RIGHT — use a more specific selector
(locator/text-content (page/locator pg "h1.display-1"))
(spel/text "h1.display-1")  ;; SCI/eval equivalent

;; RIGHT — use semantic locators (role + name filter)
(locator/text-content (page/get-by-role pg role/heading {:name "Installation"}))

;; RIGHT — use nth-element for a specific match
(locator/text-content (locator/nth-element (page/locator pg "h1") 0))
(spel/text (spel/nth (spel/$ "h1") 0))  ;; SCI/eval equivalent
```

**Available narrowing functions:**

| Function | Description |
|----------|-------------|
| `locator/first-element` / `spel/first` | First matching element |
| `locator/last-element` / `spel/last` | Last matching element |
| `locator/nth-element` / `spel/nth` | Nth element (0-indexed) |
| `locator/all` / `spel/$$` | All matches as a vector of Locators |
| `locator/all-text-contents` / `spel/all-text-contents` | All texts as a vector |
| `locator/all-inner-texts` / `spel/all-inner-texts` | All inner texts as a vector |
| `locator/count-elements` / `spel/count-of` | Count of matching elements |
| `locator/loc-filter` / `spel/loc-filter` | Filter by text, has-text, or sub-locator |

**Rule of thumb:** If your selector might match multiple elements, either:
1. Make the selector more specific (CSS class, test-id, role + name)
2. Use `first-element` / `spel/first` to explicitly pick the first
3. Use `all-text-contents` / `spel/all-text-contents` to get all values

### Assertions

All assertion functions require `assert-that` first. They return `nil` on success, throw on failure.
In test `it` blocks, ALWAYS wrap with `(expect (nil? ...))`.

```clojure
;; Page assertions (assert-that returns PageAssertions)
(let [pa (assert/assert-that pg)]
  (assert/has-title pa "My Page")
  (assert/has-url pa "https://example.com"))

;; Locator assertions (assert-that returns LocatorAssertions)
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
  (assert/has-attribute la "href" "https://example.com")
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

;; Locator negation (assert the opposite)
(assert/is-visible (assert/loc-not (assert/assert-that (page/locator pg ".hidden"))))
(assert/is-checked (assert/loc-not (assert/assert-that (page/locator pg "#opt-out"))))

;; Page negation (page-not takes PageAssertions, not Page)
(assert/has-title (assert/page-not (assert/assert-that pg)) "Wrong Title")
(assert/has-url (assert/page-not (assert/assert-that pg)) "https://wrong.com")

;; API response assertion (api-not takes APIResponseAssertions, not APIResponse)
(assert/is-ok (assert/assert-that api-response))
(assert/is-ok (assert/api-not (assert/assert-that api-response)))     ; assert NOT ok

;; In test `it` blocks — ALWAYS wrap with expect:
(expect (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Welcome")))
(expect (nil? (assert/has-title (assert/assert-that *page*) "My Page")))

;; Timeout override
(assert/set-default-assertion-timeout! 10000)
```

### Events & Signals

```clojure
;; Dialog handling (persistent — fires for every dialog)
(page/on-dialog pg (fn [dialog] (.dismiss dialog)))

;; One-time dialog handler (fires once, then auto-removes)
(page/once-dialog pg (fn [dialog]
  (println "Dialog:" (.message dialog))
  (.accept dialog)))

;; Download handling
(page/on-download pg (fn [dl] (println "Downloaded:" (.suggestedFilename dl))))

;; Popup handling
(page/on-popup pg (fn [popup-pg] (println "Popup URL:" (page/url popup-pg))))

;; Console messages
(page/on-console pg (fn [msg] (println (.type msg) ":" (.text msg))))

;; Page errors
(page/on-page-error pg (fn [err] (println "Page error:" err)))

;; Request/Response events
(page/on-request pg (fn [req] (println "→" (.method req) (.url req))))
(page/on-response pg (fn [resp] (println "←" (.status resp) (.url resp))))

;; waitForPopup / waitForDownload / waitForFileChooser
(let [popup (page/wait-for-popup pg
              #(locator/click (page/locator pg "a")))]
  (page/navigate popup "..."))

(let [dl (page/wait-for-download pg
           #(locator/click (page/locator pg "a.download")))]
  (util/download-save-as! dl "/tmp/file.txt"))

(let [fc (page/wait-for-file-chooser pg
           #(locator/click (page/locator pg "input[type=file]")))]
  (util/file-chooser-set-files! fc "/path/to/file.txt"))
```

### Frame Navigation

```clojure
;; Via FrameLocator (preferred)
(let [fl (frame/frame-locator-obj pg "iframe#main")]
  (locator/click (frame/fl-locator fl "button")))

;; Via Locator.contentFrame()
(let [fl (locator/content-frame (page/locator pg "iframe"))]
  (locator/click (.locator fl "h1")))

;; Nested frames
(let [fl1 (frame/frame-locator-obj pg "iframe.outer")
      fl2 (.frameLocator (frame/fl-locator fl1 "iframe.inner") "iframe.inner")]
  (locator/click (frame/fl-locator fl2 "button")))

;; Frame hierarchy
(let [main-frame (page/main-frame pg)
      children (frame/child-frames main-frame)]
  (doseq [f children]
    (println "Frame:" (frame/frame-name f) "URL:" (frame/frame-url f))))

;; Frame locator methods (same as page)
(let [f (first (page/frames pg))]
  (frame/frame-locator f "button")
  (frame/frame-get-by-text f "Click me")
  (frame/frame-get-by-role f role/button)
  (frame/frame-get-by-label f "Email")
  (frame/frame-evaluate f "document.title"))

;; FrameLocator sub-locators
(let [fl (frame/frame-locator-obj pg "iframe")]
  (frame/fl-locator fl "button")
  (frame/fl-get-by-text fl "Submit")
  (frame/fl-get-by-role fl role/link)
  (frame/fl-get-by-label fl "Password")
  (frame/fl-first fl)
  (frame/fl-last fl)
  (frame/fl-nth fl 0))
```

### Utility Functions (util namespace)

```clojure
(require '[com.blockether.spel.util :as util])

;; Dialog handling
(page/on-dialog pg (fn [dialog]
  (println "Type:" (util/dialog-type dialog))       ; "alert", "confirm", "prompt", "beforeunload"
  (println "Message:" (util/dialog-message dialog))
  (println "Default:" (util/dialog-default-value dialog))
  (util/dialog-accept! dialog)                       ; or (util/dialog-accept! dialog "input text")
  ;; (util/dialog-dismiss! dialog)
  ))

;; Download handling
(page/on-download pg (fn [dl]
  (println "URL:" (util/download-url dl))
  (println "File:" (util/download-suggested-filename dl))
  (println "Failure:" (util/download-failure dl))
  (util/download-save-as! dl "/tmp/downloaded.pdf")
  ;; (util/download-cancel! dl)
  ;; (util/download-path dl)
  ;; (util/download-page dl)
  ))

;; Console messages
(page/on-console pg (fn [msg]
  (println (util/console-type msg) ":"       ; "log", "error", "warning", etc.
           (util/console-text msg))
  ;; (util/console-args msg)                 ; vector of JSHandle
  ;; (util/console-location msg)             ; {:url ... :line-number ... :column-number ...}
  ;; (util/console-page msg)
  ))

;; Tracing
(let [tracing (util/context-tracing ctx)]
  (util/tracing-start! tracing {:screenshots true :snapshots true :sources true})
  ;; ... test actions ...
  (util/tracing-stop! tracing {:path "trace.zip"}))

;; Clock manipulation (for time-dependent tests)
(util/clock-install! (util/page-clock pg))
(util/clock-set-fixed-time! (util/page-clock pg) "2024-01-01T00:00:00Z")
(util/clock-set-system-time! (util/page-clock pg) "2024-06-15T12:00:00Z")
(util/clock-fast-forward! (util/page-clock pg) 60000)   ; ms
(util/clock-pause-at! (util/page-clock pg) "2024-01-01")
(util/clock-resume! (util/page-clock pg))

;; CDP (Chrome DevTools Protocol)
;; Requires Chromium browser
(let [session (util/cdp-send pg "Runtime.evaluate" {:expression "1+1"})]
  ;; (util/cdp-on session "Network.requestWillBeSent" handler-fn)
  ;; (util/cdp-detach! session)
  )

;; Video recording
(let [video (page/video pg)]
  (util/video-path video)
  (util/video-save-as! video "/tmp/recording.webm")
  (util/video-delete! video))

;; Workers (Web Workers / Service Workers)
(doseq [w (page/workers pg)]
  (println "Worker URL:" (util/worker-url w))
  (println "Eval:" (util/worker-evaluate w "self.name")))

;; File chooser
(let [fc (page/wait-for-file-chooser pg
           #(locator/click (page/locator pg "input[type=file]")))]
  (util/file-chooser-set-files! fc "/path/to/file.txt")
  ;; (util/file-chooser-page fc)
  ;; (util/file-chooser-element fc)
  ;; (util/file-chooser-is-multiple? fc)
  )

;; Selectors engine
(util/selectors-register! (util/selectors pg) "my-engine" {:script "..."})

;; Web errors
(page/on-page-error pg (fn [err]
  ;; (util/web-error-page err)
  ;; (util/web-error-error err)
  ))
```

### File Input

```clojure
;; Single file
(locator/set-input-files! (page/locator pg "input[type=file]") "/path/to/file.txt")

;; Multiple files
(locator/set-input-files! (page/locator pg "input[type=file]") ["/path/a.txt" "/path/b.txt"])
```

### Browser Launch Options

```clojure
;; Basic headless (default)
(core/launch-chromium pw {:headless true})

;; Headed mode for debugging
(core/launch-chromium pw {:headless false :slow-mo 500})

;; Use Chrome/Edge channel
(core/launch-chromium pw {:channel "chrome"})
(core/launch-chromium pw {:channel "msedge"})

;; Custom browser args
(core/launch-chromium pw {:args ["--disable-gpu" "--no-sandbox"]})

;; Proxy
(core/launch-chromium pw {:proxy {:server "http://proxy:8080"
                                   :username "user"
                                   :password "pass"}})

;; Custom downloads directory
(core/launch-chromium pw {:downloads-path "/tmp/downloads"})

;; All browsers
(core/launch-firefox pw {:headless true})
(core/launch-webkit pw {:headless true})
```

### Browser Context Options

```clojure
;; Custom viewport
(core/new-context browser {:viewport {:width 1920 :height 1080}})

;; Mobile emulation
(core/new-context browser {:viewport {:width 375 :height 812}
                           :is-mobile true
                           :has-touch true
                           :device-scale-factor 3
                           :user-agent "Mozilla/5.0 (iPhone...)"})

;; Locale and timezone
(core/new-context browser {:locale "fr-FR"
                           :timezone-id "Europe/Paris"})

;; Geolocation
(core/new-context browser {:geolocation {:latitude 48.8566 :longitude 2.3522}
                           :permissions ["geolocation"]})

;; Dark mode
(core/new-context browser {:color-scheme :dark})

;; Offline mode
(core/new-context browser {:offline true})

;; Extra HTTP headers
(core/new-context browser {:extra-http-headers {"Authorization" "Bearer token"
                                                 "X-Custom" "value"}})

;; Base URL (for relative navigations)
(core/new-context browser {:base-url "https://example.com"})

;; Storage state (restore cookies + localStorage)
(core/new-context browser {:storage-state "state.json"})

;; Record video
(core/new-context browser {:record-video-dir "/tmp/videos"
                           :record-video-size {:width 1280 :height 720}})

;; Record HAR (HTTP Archive)
(core/new-context browser {:record-har-path "network.har"
                           :record-har-mode :minimal})

;; Ignore HTTPS errors
(core/new-context browser {:ignore-https-errors true})

;; Bypass CSP
(core/new-context browser {:bypass-csp true})

;; Context management
(core/context-grant-permissions! ctx ["clipboard-read" "clipboard-write"])
(core/context-clear-permissions! ctx)
(core/context-cookies ctx)
(core/context-clear-cookies! ctx)
(core/context-set-offline! ctx true)
(core/context-set-extra-http-headers! ctx {"X-Test" "value"})
(core/context-set-default-timeout! ctx 30000)
(core/context-set-default-navigation-timeout! ctx 60000)
```

### Page Routing (network namespace)

```clojure
(require '[com.blockether.spel.network :as net])

;; Block images
(page/route! pg "**/*.{png,jpg,jpeg,gif,svg}" (fn [route]
  (net/route-abort! route)))

;; Mock API response
(page/route! pg "**/api/users" (fn [route]
  (net/route-fulfill! route {:status 200
                             :content-type "application/json"
                             :body "{\"users\":[]}"})))

;; Modify request headers
(page/route! pg "**/*" (fn [route]
  (net/route-continue! route {:headers (merge (net/request-headers (net/route-request route))
                                               {"X-Custom" "injected"})})))

;; Modify response (fetch, then alter)
(page/route! pg "**/api/data" (fn [route]
  (let [resp (net/route-fetch! route)]
    (net/route-fulfill! route {:status 200
                               :body (str (net/response-text resp) " (modified)")}))))

;; Fallback to next handler
(page/route! pg "**/*" (fn [route]
  (if (= "POST" (net/request-method (net/route-request route)))
    (net/route-abort! route)
    (net/route-fallback! route))))

;; Remove route
(page/unroute! pg "**/*.{png,jpg}")

;; Request/Response inspection
(let [req some-request]
  (net/request-url req)            ; "https://example.com/api"
  (net/request-method req)         ; "GET"
  (net/request-headers req)        ; {"accept" "text/html" ...}
  (net/request-post-data req)      ; POST body string or nil
  (net/request-resource-type req)  ; "document", "script", "fetch", etc.
  (net/request-timing req)         ; {:start-time ... :response-end ...}
  (net/request-is-navigation? req) ; true/false
  (net/request-failure req))       ; failure text or nil

(let [resp some-response]
  (net/response-url resp)          ; "https://example.com/api"
  (net/response-status resp)       ; 200
  (net/response-status-text resp)  ; "OK"
  (net/response-ok? resp)          ; true
  (net/response-headers resp)      ; {"content-type" "application/json" ...}
  (net/response-text resp)         ; body string
  (net/response-body resp)         ; byte[]
  (net/response-header-value resp "content-type"))

;; Wait for specific response
(let [resp (page/wait-for-response pg "**/api/users"
             (reify Runnable (run [_]
               (locator/click (page/locator pg "#load-users")))))]
  (println (net/response-status resp)))

;; WebSocket
(let [ws (first (.webSockets pg))]
  (net/ws-url ws)
  (net/ws-is-closed? ws)
  (net/ws-on-message ws (fn [frame]
    (println "WS msg:" (net/wsf-text frame))))
  (net/ws-on-close ws (fn [_ws] (println "WS closed")))
  (net/ws-on-error ws (fn [err] (println "WS error:" err))))
```

### Waiting

```clojure
;; Wait for element to appear
(page/wait-for-selector pg ".loaded")
(page/wait-for-selector pg ".modal" {:state :visible :timeout 10000})
;; :state values — :attached :detached :visible :hidden

;; Wait for URL change
(page/wait-for-url pg "https://example.com/dashboard")
(page/wait-for-url pg #".*dashboard.*")               ; regex
(page/wait-for-url pg #(clojure.string/includes? % "dash"))  ; predicate

;; Wait for JS function to return truthy
(page/wait-for-function pg "() => document.querySelector('.ready')")

;; Wait for load state
(page/wait-for-load-state pg)                    ; default: load
(page/wait-for-load-state pg :domcontentloaded)
(page/wait-for-load-state pg :networkidle)

;; Wait for timeout (sleep) — AVOID: prefer event-driven waits above
;; (page/wait-for-timeout pg 1000)  ; Use only as absolute last resort

;; Locator wait-for (wait for locator to satisfy condition)
(locator/wait-for (page/locator pg ".spinner") {:state :hidden})
(locator/wait-for (page/locator pg ".content") {:state :visible :timeout 5000})
```

### Page Utilities

```clojure
;; Set HTML content directly (useful for tests)
(page/set-content! pg "<h1>Hello</h1><p>World</p>")

;; Emulate media
(page/emulate-media! pg {:media :screen})              ; or :print
(page/emulate-media! pg {:color-scheme :dark})          ; or :light :no-preference
(page/emulate-media! pg {:media :print :color-scheme :dark})

;; Set viewport
(page/set-viewport-size! pg 1024 768)

;; Add script/style tags
(page/add-script-tag pg {:url "https://cdn.example.com/lib.js"})
(page/add-script-tag pg {:content "window.myVar = 42;"})
(page/add-script-tag pg {:path "/path/to/local.js"})

(page/add-style-tag pg {:content "body { background: red; }"})
(page/add-style-tag pg {:url "https://cdn.example.com/style.css"})

;; Expose Clojure function to JavaScript
(page/expose-function! pg "clojureAdd" (fn [a b] (+ a b)))
;; In JS: await window.clojureAdd(1, 2)  => 3

;; Expose binding (receives BindingSource as first arg)
(page/expose-binding! pg "getPageInfo" (fn [source]
  (str "Frame: " (.frame source))))

;; Extra HTTP headers for this page
(page/set-extra-http-headers! pg {"Authorization" "Bearer token"})

;; Bring page to front (activate tab)
(page/bring-to-front pg)
```

### Advanced Locator Actions

```clojure
;; Drag and drop
(locator/drag-to (page/locator pg "#source") (page/locator pg "#target"))

;; Dispatch custom DOM event
(locator/dispatch-event (page/locator pg "#el") "click")
(locator/dispatch-event (page/locator pg "#el") "dragstart" {:dataTransfer {}})

;; Scroll element into view
(locator/scroll-into-view (page/locator pg "#offscreen"))

;; Tap (touch) element
(locator/tap-element (page/locator pg "#button"))

;; Evaluate JavaScript on element
(locator/evaluate-locator (page/locator pg "#el") "el => el.dataset.value")
(locator/evaluate-all (page/locator pg ".items") "els => els.length")

;; Take screenshot of specific element
(locator/locator-screenshot (page/locator pg ".card"))
(locator/locator-screenshot (page/locator pg ".card") {:path "card.png"})

;; Highlight element (visual debugging)
(locator/highlight (page/locator pg "#important"))

;; Get/set attributes
(locator/get-attribute (page/locator pg "a") "href")

;; Select dropdown option
(locator/select-option (page/locator pg "select") "value")
(locator/select-option (page/locator pg "select") ["val1" "val2"])  ; multi-select

;; Check/uncheck
(locator/check (page/locator pg "#checkbox"))
(locator/uncheck (page/locator pg "#checkbox"))

;; Hover
(locator/hover (page/locator pg ".tooltip-trigger"))
```

### Input Devices (input namespace)

```clojure
(require '[com.blockether.spel.input :as input])

;; Keyboard
(let [kb (page/page-keyboard pg)]
  (input/key-press kb "Enter")
  (input/key-press kb "Control+a")
  (input/key-press kb "Shift+ArrowRight" {:delay 100})
  (input/key-type kb "Hello World" {:delay 50})
  (input/key-down kb "Shift")
  (input/key-up kb "Shift")
  (input/key-insert-text kb "直接挿入"))  ; insert without key events

;; Mouse
(let [mouse (page/page-mouse pg)]
  (input/mouse-click mouse 100 200)
  (input/mouse-dblclick mouse 100 200)
  (input/mouse-move mouse 300 400 {:steps 10})
  (input/mouse-down mouse)
  (input/mouse-up mouse)
  (input/mouse-wheel mouse 0 100))    ; scroll down 100px

;; Touchscreen
(let [ts (page/page-touchscreen pg)]
  (input/touchscreen-tap ts 100 200))
```

### API Testing (api namespace)

```clojure
(require '[com.blockether.spel.api :as api])

;; Single API context
(api/with-api-context [ctx (api/new-api-context (api/api-request pw)
                             {:base-url "https://api.example.com"
                              :extra-http-headers {"Authorization" "Bearer token"}})]
  (let [resp (api/api-get ctx "/users")]
    (println (api/api-response-status resp))     ; 200
    (println (api/api-response-text resp))))      ; JSON body

;; Multiple API contexts
(api/with-api-contexts
  [users   (api/new-api-context (api/api-request pw) {:base-url "https://users.example.com"})
   billing (api/new-api-context (api/api-request pw) {:base-url "https://billing.example.com"})]
  (api/api-get users "/me")
  (api/api-get billing "/invoices"))

;; JSON encoding — MUST bind *json-encoder* before using :json option
(require '[cheshire.core :as json])
(binding [api/*json-encoder* json/generate-string]
  (api/api-post ctx "/users" {:json {:name "Alice" :age 30}}))
;; Or set globally:
(alter-var-root #'api/*json-encoder* (constantly json/generate-string))
;; Using :json WITHOUT binding *json-encoder* will throw!

;; HTTP methods
(api/api-get ctx "/users" {:params {:page 1}})
(api/api-post ctx "/users" {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
(api/api-put ctx "/users/1" {:data "{\"name\":\"Bob\"}"})
(api/api-patch ctx "/users/1" {:data "{\"name\":\"Charlie\"}"})
(api/api-delete ctx "/users/1")
(api/api-head ctx "/health")
(api/api-fetch ctx "/resource" {:method "OPTIONS"})

;; Form data
(let [fd (api/form-data)]
  (api/fd-set fd "name" "Alice")
  (api/fd-append fd "tag" "clojure")
  (api/api-post ctx "/submit" {:form fd}))
;; Or from map:
(api/api-post ctx "/submit" {:form (api/map->form-data {:name "Alice" :email "a@b.c"})})

;; Response inspection
(let [resp (api/api-get ctx "/users")]
  (api/api-response-status resp)         ; 200
  (api/api-response-status-text resp)    ; "OK"
  (api/api-response-url resp)
  (api/api-response-ok? resp)            ; true
  (api/api-response-headers resp)        ; {"content-type" "..."}
  (api/api-response-text resp)           ; body string
  (api/api-response-body resp)           ; byte[]
  (api/api-response->map resp))          ; {:status 200 :ok? true :headers {...} :body "..."}

;; Hooks (request/response interceptors)
(api/with-hooks
  {:on-request  (fn [method url opts] (println "→" method url) opts)
   :on-response (fn [method url resp] (println "←" method (api/api-response-status resp)) resp)}
  (api/api-get ctx "/users"))

;; Retry with backoff
(api/retry #(api/api-get ctx "/flaky")
  {:max-attempts 5 :delay-ms 1000 :backoff :linear
   :retry-when (fn [r] (= 429 (:status (api/api-response->map r))))})
;; Or with macro:
(api/with-retry {:max-attempts 3 :delay-ms 200}
  (api/api-post ctx "/endpoint" {:json {:action "process"}}))

;; Standalone request (no context setup needed)
(api/request! pw :get "https://api.example.com/health")
(api/request! pw :post "https://api.example.com/users"
  {:data "{\"name\":\"Alice\"}" :headers {"Content-Type" "application/json"}})
```

### Allure Test Reporting (allure namespace)

```clojure
(require '[com.blockether.spel.allure :as allure])

;; Labels (call inside test body)
(allure/epic "E2E Testing")
(allure/feature "Authentication")
(allure/story "Login Flow")
(allure/severity :critical)          ; :blocker :critical :normal :minor :trivial
(allure/owner "team@example.com")
(allure/tag "smoke")
(allure/description "Tests the complete login flow")
(allure/link "Docs" "https://example.com/docs")
(allure/issue "BUG-123" "https://github.com/example/issues/123")
(allure/tms "TC-456" "https://tms.example.com/456")
(allure/parameter "browser" "chromium")

;; Steps
(allure/step "Navigate to login page"
  (page/navigate pg "https://example.com/login"))

;; Nested steps
(allure/step "Login flow"
  (allure/step "Enter credentials"
    (locator/fill (page/locator pg "#user") "admin")
    (locator/fill (page/locator pg "#pass") "secret"))
  (allure/step "Submit"
    (locator/click (page/locator pg "#submit"))))

;; UI step (auto-captures before/after screenshots, requires *page* binding)
(allure/ui-step "Fill login form"
  (locator/fill username-input "admin")
  (locator/fill password-input "secret")
  (locator/click submit-btn))

;; API step (auto-attaches response details: status, headers, body)
(allure/api-step "Create user"
  (api/api-post ctx "/users" {:json {:name "Alice" :age 30}}))

;; Attachments
(allure/attach "Request Body" "{\"key\":\"value\"}" "application/json")
(allure/attach-bytes "Screenshot" (page/screenshot pg) "image/png")
(allure/screenshot pg "After navigation")        ; convenience: attach PNG screenshot
(allure/attach-api-response! resp)               ; attach full API response
```

### Allure Reporter

The built-in reporter handles everything automatically — JSON results, HTML report generation, embedded trace viewer, and build history. Just run:

```bash
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
```

### Allure Configuration

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `lazytest.allure.output` | `LAZYTEST_ALLURE_OUTPUT` | `allure-results` | Results output directory |
| `lazytest.allure.report` | `LAZYTEST_ALLURE_REPORT` | `allure-report` | HTML report directory |
| `lazytest.allure.history-limit` | `LAZYTEST_ALLURE_HISTORY_LIMIT` | `10` | Max builds retained in history |
| `lazytest.allure.report-name` | `LAZYTEST_ALLURE_REPORT_NAME` | _(auto: "spel vX.Y.Z")_ | Report title (shown in header and history). Auto-includes version when not set. |
| `lazytest.allure.version` | `LAZYTEST_ALLURE_VERSION` | _(SPEL_VERSION)_ | Project version shown in build history and environment. Falls back to `SPEL_VERSION` resource. |
| `lazytest.allure.logo` | `LAZYTEST_ALLURE_LOGO` | _(none)_ | Path to logo image for report header |

**Version in build listings**: When `lazytest.allure.version` is set (or `SPEL_VERSION` is on the classpath), each build in the Allure history is tagged with the version. The report name auto-generates as `"spel vX.Y.Z"` unless overridden by `report-name`. The version also appears in `environment.properties` as `project.version` and `spel.version`.

```bash
# Serve the generated report in browser (port 9999)
npx http-server allure-report -o -p 9999

# Tag build with custom version (overrides SPEL_VERSION)
clojure -J-Dlazytest.allure.version=1.2.3 -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure

# Keep last 20 builds in history
LAZYTEST_ALLURE_HISTORY_LIMIT=20 clojure -M:test \
  --output nested --output com.blockether.spel.allure-reporter/allure
```

> **Note**: The report MUST be served via HTTP (not `file://`) because the embedded Playwright trace viewer uses a Service Worker.

### Trace Viewer Integration

When using test fixtures (`with-page` / `with-traced-page`) or `with-testing-page` with Allure reporter active, Playwright tracing is automatically enabled:

- Screenshots captured on every action
- DOM snapshots included
- Network activity recorded
- Sources captured
- HAR file generated

Trace and HAR files are automatically attached to test results (MIME type `application/vnd.allure.playwright-trace`) and viewable directly in the Allure report via an embedded local trace viewer — no external service dependency.

`with-testing-page` auto-attaches traces for both the Lazytest reporter and the clojure.test reporter — no additional configuration needed. When the Allure `*context*` atom is bound (by either reporter's fixture), trace zip and HAR files are copied directly into `allure-results/` and registered as attachments.

#### Source Mapping in Trace Viewer

All step macros (`step`, `ui-step`, `api-step`, `describe`, `it`, `expect`) automatically capture the source file and line number at macro expansion time and pass them to `Tracing.group()` via `GroupOptions.setLocation()`. This means clicking a step in the Trace Viewer **Source** tab shows the actual test code where the step was written — not the allure.clj macro internals.

Source path resolution uses the `PLAYWRIGHT_JAVA_SRC` environment variable (auto-set to `src:test:dev` by `core/create`) to resolve classpath-relative paths (e.g. `com/blockether/spel/smoke_test.clj`) to project-relative paths (e.g. `test/com/blockether/spel/smoke_test.clj`) that match the trace's captured sources.

For custom source directories, set `PLAYWRIGHT_JAVA_SRC` before creating the Playwright instance:

```bash
PLAYWRIGHT_JAVA_SRC="src:test:test-e2e:dev" clojure -M:test ...
```

### JUnit XML Reporter

Produces JUnit XML output fully compliant with the Apache Ant JUnit schema — compatible with GitHub Actions, Jenkins, GitLab CI, and any CI system that consumes JUnit XML.

```bash
# Run with JUnit reporter (silent during run, writes XML on completion)
clojure -M:test --output com.blockether.spel.junit-reporter/junit

# Combine with visual output (recommended)
clojure -M:test --output nested --output com.blockether.spel.junit-reporter/junit

# Combine with both Allure and JUnit
clojure -M:test --output nested \
  --output com.blockether.spel.allure-reporter/allure \
  --output com.blockether.spel.junit-reporter/junit
```

#### JUnit Configuration

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `lazytest.junit.output` | `LAZYTEST_JUNIT_OUTPUT` | `test-results/junit.xml` | Output file path |

```bash
# Custom output path
clojure -J-Dlazytest.junit.output=reports/results.xml -M:test \
  --output nested --output com.blockether.spel.junit-reporter/junit

# Or via env var
LAZYTEST_JUNIT_OUTPUT=reports/results.xml clojure -M:test \
  --output nested --output com.blockether.spel.junit-reporter/junit
```

#### JUnit XML Features

- `<testsuites>` root with aggregate counts (tests, failures, errors, skipped, time)
- `<testsuite>` per namespace with timestamp, hostname, package, id
- `<testcase>` with classname (namespace), name (describe > it path), time, file
- `<failure>` vs `<error>` distinction (assertion failure vs unexpected exception)
- `<skipped>` support for pending tests
- `<properties>` with environment metadata (JVM version, OS, Clojure version)
- `<system-out>` / `<system-err>` — per-test captured stdout/stderr output

---

## Codegen - Record & Transform

Record browser sessions and transform to idiomatic Clojure.

### Workflow

```bash
# 1. Record browser session (opens interactive Playwright Codegen recorder)
# Defaults to --target=jsonl for the spel transform pipeline
spel codegen record -o recording.jsonl https://example.com

# 2. Transform JSONL to Clojure test
spel codegen recording.jsonl > my_test.clj
spel codegen --format=script recording.jsonl
spel codegen --format=body recording.jsonl
```

### Formats

| Format | Output |
|--------|--------|
| `:test` (default) | Full test file with `defdescribe`/`it`/`expect` (from `spel.allure`), `with-playwright`/`with-browser`/`with-context`/`with-traced-page` |
| `:script` | Standalone script with `require`/`import` + `with-playwright` chain |
| `:body` | Just action lines for pasting into existing code |

### Supported Actions

| Action | Codegen Output |
|--------|---------------|
| `navigate` | `(page/navigate pg "url")` |
| `click` | `(locator/click loc)` with modifiers, button, position |
| `click` (dblclick) | `(locator/dblclick loc)` when clickCount=2 |
| `click` (N>2) | `(locator/click loc {:click-count N})` |
| `fill` | `(locator/fill loc "text")` |
| `press` | `(locator/press loc "key")` with modifier combos |
| `hover` | `(locator/hover loc)` with optional position |
| `check`/`uncheck` | `(locator/check loc)` / `(locator/uncheck loc)` |
| `select` | `(locator/select-option loc "value")` |
| `setInputFiles` | `(locator/set-input-files! loc "path")` or vector |
| `assertText` | `(assert/has-text (assert/assert-that loc) "text")` |
| `assertChecked` | `(assert/is-checked (assert/assert-that loc))` |
| `assertVisible` | `(assert/is-visible (assert/assert-that loc))` |
| `assertValue` | `(assert/has-value (assert/assert-that loc) "val")` |
| `assertSnapshot` | `(assert/matches-aria-snapshot (assert/assert-that loc) "snapshot")` |

### Signal Handling

| Signal | Codegen Pattern |
|--------|----------------|
| `dialog` | `(page/on-dialog pg (fn [dialog] (.dismiss dialog)))` BEFORE action |
| `popup` | `(let [popup-pg (page/wait-for-popup pg #(action))] ...)` AROUND action |
| `download` | `(let [download (page/wait-for-download pg #(action))] ...)` AROUND action |

### Frame Navigation in Codegen

`framePath` array generates chained `.contentFrame()` calls:

```clojure
;; framePath: ["iframe.outer", "iframe.inner"]
(let [fl0 (.contentFrame (page/locator pg "iframe.outer"))
      fl1 (.contentFrame (.locator fl0 "iframe.inner"))]
  (locator/click (.locator fl1 "button")))
```

### Hard Errors

Codegen dies immediately on:
- Unknown action types
- Unknown signal types
- Unrecognized locator formats
- Missing locator/selector data

In CLI mode: prints full action data + `System/exit 1`.
In library mode: throws `ex-info` with `:codegen/error` and `:codegen/action`.

---

## CLI

Wraps Playwright CLI commands via the `spel` native binary.

> **Prefer `--eval` for multi-step automation.** Standalone CLI commands (`spel open`, `spel click @e1`, etc.) are useful for quick one-off actions, but for anything beyond a single command, use `spel --eval '<clojure-code>'` or `spel --eval script.clj`. This gives you full Clojure composition — loops, conditionals, variables, error handling — in a single persistent browser session. LLM-generated scripts can be piped via `echo '(code)' | spel --eval --stdin`.

> **Note**: `spel install` delegates to `com.microsoft.playwright.CLI`, which is a thin shim that spawns the same Node.js Playwright CLI that `npx playwright` uses. The driver version is pinned to the Playwright Java dependency (1.58.0), so browser versions always match.

```bash
spel install                        # Install browsers (Chromium by default)
spel install --with-deps chromium   # Install with system dependencies
spel codegen URL                    # Record interactions
spel open URL                       # Open browser
spel screenshot URL                 # Take screenshot
```

#### Corporate Proxy / Custom CA Certificates

Behind a corporate SSL-inspecting proxy, `spel install` may fail with "PKIX path building failed". Use these env vars to add corporate CA certs:

| Env Var | Format | On missing file | Description |
|---------|--------|----------------|-------------|
| `SPEL_CA_BUNDLE` | PEM file | Error | Extra CA certs (merged with defaults) |
| `NODE_EXTRA_CA_CERTS` | PEM file | Warning, skips | Shared with Node.js subprocess |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Error | Truststore (merged with defaults) |
| `SPEL_TRUSTSTORE_TYPE` | String | — | Default: JKS |
| `SPEL_TRUSTSTORE_PASSWORD` | String | — | Default: empty |

```bash
# Simplest — PEM file with corporate CA
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem
spel install --with-deps

# Or reuse Node.js var — covers both driver + browser downloads
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
spel install --with-deps
```

All options merge with built-in defaults — public CDN certs continue to work.

### Playwright Tools

Launch Playwright's built-in visual tools directly from `spel`:

```bash
# Inspector — opens a headed browser with the Playwright Inspector panel.
# Use to explore the page, pick locators, and record actions interactively.
spel inspector                                      # Open Inspector (blank page)
spel inspector https://example.com                  # Open Inspector on URL
spel inspector -b firefox https://example.com       # Use Firefox
spel inspector --device "iPhone 14" https://example.com  # Emulate device

# Trace Viewer — opens the Playwright Trace Viewer to inspect recorded traces.
# Traces are created via `spel trace start` / `spel trace stop` or automatically
# by test fixtures with Allure reporter active.
spel show-trace                     # Open Trace Viewer (blank)
spel show-trace trace.zip           # Open specific trace file
spel show-trace --port 8080 trace.zip  # Serve on specific port
```

**Inspector options** (all Playwright `open` flags are supported):

| Flag | Description |
|------|-------------|
| `-b, --browser <type>` | Browser engine: `cr`/`chromium`, `ff`/`firefox`, `wk`/`webkit` (default: chromium) |
| `--channel <channel>` | Chromium channel: `chrome`, `chrome-beta`, `msedge-dev`, etc. |
| `--device <name>` | Emulate device (e.g. `"iPhone 14"`, `"Pixel 7"`) |
| `--color-scheme <scheme>` | `light` or `dark` |
| `--geolocation <lat,lng>` | Geolocation coordinates |
| `--lang <locale>` | Language locale (e.g. `en-GB`) |
| `--timezone <tz>` | Timezone (e.g. `Europe/Rome`) |
| `--viewport-size <w,h>` | Viewport size (e.g. `1280,720`) |
| `--user-agent <ua>` | Custom user agent |
| `--proxy-server <url>` | Proxy server |
| `--ignore-https-errors` | Ignore HTTPS certificate errors |
| `--load-storage <file>` | Load saved storage state |
| `--save-storage <file>` | Save storage state on exit |
| `--save-har <file>` | Save HAR file on exit |
| `--timeout <ms>` | Action timeout in ms |

---

## Page Exploration (spel)

The `spel` CLI provides comprehensive page exploration capabilities without writing code.

### Basic Exploration Workflow

```bash
# 1. Navigate to a page
spel open https://example.com

# 2. Get accessibility snapshot with numbered refs (e1, e2, etc.)
spel snapshot

# 3. Take a screenshot for visual reference
spel screenshot page.png
```

### Snapshot Command

The primary exploration tool - returns an ARIA accessibility tree with numbered refs:

```bash
spel snapshot                           # Full accessibility tree
spel snapshot -i                        # Interactive elements only
spel snapshot -i -c                     # Compact format
spel snapshot -i -c -d 3               # Limit depth to 3 levels
spel snapshot -i -C                     # Include cursor/pointer elements
spel snapshot -s "#main"               # Scoped to CSS selector
```

**Output format:**
```
- heading "Example Domain" [@e1] [level=1]
- link "More information..." [@e2]
- button "Submit" [@e3]
```

### Get Page Information

```bash
spel get url                           # Current URL
spel get title                         # Page title
spel get text @e1                      # Text content of ref e1
spel get html @e1                      # Inner HTML
spel get value @e2                     # Input value
spel get attr @e1 href                 # Attribute value
spel get count ".items"               # Count matching elements
spel get box @e1                       # Bounding box {x, y, width, height}
```

### Check Element State

```bash
spel is visible @e1                    # Check visibility
spel is enabled @e1                    # Check if enabled
spel is checked @e3                    # Check checkbox state
```

### Find Elements (Semantic Locators)

Find and interact in one command:

```bash
# Find by ARIA role
spel find role button click
spel find role button click --name "Submit"

# Find by text content
spel find text "Login" click

# Find by label
spel find label "Email" fill "test@example.com"

# Position-based
spel find first ".item" click
spel find last ".item" click
spel find nth 2 ".item" click
```

### Visual Exploration

```bash
spel screenshot                        # Screenshot to stdout (base64)
spel screenshot shot.png              # Save to file
spel screenshot -f full.png           # Full page screenshot
spel pdf page.pdf                     # Save as PDF (Chromium only)
spel highlight @e1                    # Highlight element visually
```

### Network Exploration

```bash
spel network requests                  # View all captured requests
spel network requests --type fetch    # Filter by type (document, script, fetch, image, etc.)
spel network requests --method POST   # Filter by HTTP method
spel network requests --status 2      # Filter by status prefix (2=2xx, 4=4xx)
spel network requests --filter "/api" # Filter by URL regex
spel network clear                    # Clear captured requests
```

### JavaScript Evaluation

```bash
# Run JavaScript
spel eval "document.title"
spel eval "document.querySelector('h1').textContent"

# Base64-encoded result
spel eval "JSON.stringify([...document.querySelectorAll('a')].map(a => ({text: a.textContent, href: a.href})))" -b
```

### Console & Errors

Console messages and page errors are auto-captured from the moment a page opens. No `start` command needed.

```bash
spel console                           # View captured console messages
spel console clear                     # Clear captured messages

spel errors                            # View captured page errors
spel errors clear                      # Clear captured errors
```

### Complete Exploration Example

```bash
# Open page
spel open https://example.com

# Get initial snapshot
spel snapshot -i

# Take screenshot
spel screenshot initial.png

# Get page info
spel get title
spel get url

# Check specific element
spel get text @e2
spel is visible @e3

# Interact and re-snapshot
spel click @e2
spel snapshot -i

# View network activity
spel network requests

# Close browser when done
spel close
```

---

## Agent Scaffolding (init-agents)

Scaffolds agent definitions for Playwright E2E testing into any consuming project. Equivalent to Playwright's `npx playwright init-agents --loop=<target>` but for the Clojure/spel stack.

Supports three editor targets via `--loop`:

```bash
spel init-agents                      # OpenCode (default)
spel init-agents --loop=claude        # Claude Code
spel init-agents --loop=vscode        # VS Code / Copilot
```

### CLI Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--loop TARGET` | `opencode` | Agent format: `opencode`, `claude`, `vscode` |
| `--ns NS` | dir name | Base namespace for generated tests (e.g. `my-app` → `my-app.e2e.seed-test`) |
| `--dry-run` | - | Preview files without writing |
| `--force` | - | Overwrite existing files |
| `--test-dir DIR` | `test/e2e` | E2E test output directory |
| `--specs-dir DIR` | `test-e2e/specs` | Test plans directory (colocated with tests) |
| `-h, --help` | - | Show help |

### Generated Files by Target

**`--loop=opencode`** (default):

| File | Purpose |
|------|---------|
| `.opencode/agents/spel-test-planner.md` | Explores app, writes structured test plans to `test-e2e/specs/` |
| `.opencode/agents/spel-test-generator.md` | Reads test plans, generates Clojure test code using spel and `spel.allure` |
| `.opencode/agents/spel-test-healer.md` | Runs failing tests, diagnoses issues, applies fixes |
| `.opencode/prompts/spel-test-workflow.md` | Orchestrator prompt: plan → generate → heal cycle |
| `.opencode/skills/spel/SKILL.md` | Copy of this API reference skill (so agents work out-of-the-box) |

**`--loop=claude`**:

| File | Purpose |
|------|---------|
| `.claude/agents/spel-test-planner.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/agents/spel-test-generator.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/agents/spel-test-healer.md` | Same as OpenCode but with Claude Code frontmatter |
| `.claude/prompts/spel-test-workflow.md` | Orchestrator prompt |
| `.claude/docs/spel/SKILL.md` | API reference |

**`--loop=vscode`**:

| File | Purpose |
|------|---------|
| `.github/agents/spel-test-planner.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/agents/spel-test-generator.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/agents/spel-test-healer.agent.md` | Same as OpenCode but with VS Code / Copilot frontmatter |
| `.github/prompts/spel-test-workflow.md` | Orchestrator prompt |
| `.github/docs/spel/SKILL.md` | API reference |

**All targets** also generate:

| File | Purpose |
|------|---------|
| `test-e2e/specs/README.md` | Test plans directory README (colocated with tests) |
| `test-e2e/<ns>/e2e/seed_test.clj` | Seed test with `com.blockether.spel` replaced by `--ns` value (or directory name) |

### Agent Workflow

Three subagents work together in a plan → generate → heal loop:

1. **@spel-test-planner** — Explores the app using `spel` CLI commands (e.g., `spel snapshot`) and inline Clojure scripts with spel. Catalogs pages/flows, writes structured test plans as markdown files in `test-e2e/specs/`. Uses `spel` skill for API reference.

2. **@spel-test-generator** — Reads test plans from `test-e2e/specs/`, generates Clojure test files using `spel.allure` (`defdescribe`/`it`/`expect`), `spel` test fixtures (`{:context [with-playwright with-browser with-traced-page]}`) and `*page*` dynamic var. Verifies selectors with inline scripts and runs tests to confirm. Outputs to `test-e2e/`.

3. **@spel-test-healer** — Runs tests via `clojure -M:test`, captures failures, uses `spel` CLI commands and inline scripts for investigation, diagnoses root causes (stale selectors, timing, missing setup), and applies targeted fixes. Loops until green.

**Orchestration**: Use the `spel-test-workflow` prompt to trigger the full cycle, or invoke individual agents with `@agent-name`.

**No external dependencies**: All agents use spel directly — no Agent Browser or external MCP tools needed.

### Template System

Templates use `.clj.template` extension (not `.clj`) to avoid clojure-lsp parsing `com.blockether.spel` placeholders as Clojure code. The `process-template` function replaces `com.blockether.spel` with the `--ns` value (or falls back to the consuming project's directory name).

---

## Testing Conventions

- Framework: **`spel.allure`** (`defdescribe`, `describe`, `it`, `expect`) — NOT `lazytest.core`
- Fixtures: `:context` with shared `around` hooks from `com.blockether.spel.test-fixtures`
- Assertions: **Exact string matching** (NEVER substring unless explicitly `contains-text`)
- Require: `[com.blockether.spel.roles :as role]` for role-based locators (e.g. `role/button`, `role/heading`). All roles are also available in `--eval` mode via the `role/` namespace — see the Enums table in SCI Eval API Reference below
- Integration tests: Live against `example.com`

### Running Tests (Lazytest CLI)

```bash
# Run entire test suite
clojure -M:test

# Run a single namespace
clojure -M:test -n com.blockether.spel.core-test

# Run multiple namespaces
clojure -M:test -n com.blockether.spel.core-test -n com.blockether.spel.page-test

# Run a single test var (MUST be fully-qualified ns/var)
clojure -M:test -v com.blockether.spel.integration-test/proxy-integration-test

# Run multiple vars
clojure -M:test -v com.blockether.spel.options-test/launch-options-test \
                -v com.blockether.spel.options-test/context-options-test

# Run with metadata filter (include/exclude)
clojure -M:test -i :smoke          # only tests tagged ^:smoke
clojure -M:test -e :slow           # exclude tests tagged ^:slow

# Run with Allure reporter
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# Watch mode (re-runs on file changes)
clojure -M:test --watch

# Run tests from a specific directory
clojure -M:test -d test/com/blockether/spel
```

**IMPORTANT**: The `-v`/`--var` flag requires **fully-qualified symbols** (`namespace/var-name`), not bare var names. Using a bare name will throw `IllegalArgumentException: no conversion to symbol`.

### Test Fixtures

The project provides shared `around` hooks in `com.blockether.spel.test-fixtures`:

| Fixture | Binds | Scope |
|---------|-------|-------|
| `with-playwright` | `*pw*` | Shared Playwright instance |
| `with-browser` | `*browser*` | Shared headless Chromium browser |
| `with-traced-page` | `*page*` | **Default.** Fresh page per `it` block with tracing/HAR always enabled (auto-cleanup) |
| `with-page` | `*page*` | Fresh page per `it` block (auto-cleanup, tracing only when Allure is active) |
| `with-traced-page-opts` | `*page*` | Like `with-traced-page` but accepts context-opts map |
| `with-page-opts` | `*page*` | Like `with-page` but accepts context-opts map |
| `with-test-server` | `*test-server-url*` | Local HTTP test server |

**Always use `with-traced-page` as the default** — it enables Playwright tracing and HAR capture on every test run, so traces are always available for debugging. Use `with-page` only if you explicitly want tracing disabled outside Allure.

Use `{:context [with-playwright with-browser with-traced-page]}` on `describe` blocks. NEVER nest `with-playwright`/`with-browser`/`with-traced-page` manually inside `it` blocks.

#### Custom Context Options

To pass `Browser$NewContextOptions` (viewport, locale, color-scheme, storage-state, user-agent, etc.) use `with-page-opts` or `with-traced-page-opts`:

```clojure
;; Mobile viewport with French locale
(describe "mobile view"
  {:context [with-playwright with-browser
             (with-traced-page-opts {:viewport {:width 375 :height 812}
                                     :locale "fr-FR"})]}
  (it "renders mobile layout"
    (page/navigate *page* "https://example.com")
    (expect (= "fr-FR" (page/evaluate *page* "navigator.language")))))

;; Load saved auth state
(describe "authenticated tests"
  {:context [with-playwright with-browser
             (with-page-opts {:storage-state "auth.json"})]}
  (it "is logged in"
    (page/navigate *page* "https://app.example.com/dashboard")
    (expect (nil? (assert/has-url (assert/assert-that *page*) "dashboard")))))
```

All `*browser-context*` and `*browser-api*` bindings work the same as with the default fixtures.

### Test Example

```clojure
(ns my-app.test
  (:require
   [com.blockether.spel.assertions :as assert]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-traced-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

(defdescribe my-test
  (describe "example.com"
    {:context [with-playwright with-browser with-traced-page]}

    (it "navigates and asserts"
      (page/navigate *page* "https://example.com")
      (expect (= "Example Domain" (page/title *page*)))
      (expect (nil? (assert/has-text (assert/assert-that (page/locator *page* "h1")) "Example Domain"))))))
```


---

## Native Image CLI

The library includes a GraalVM native-image compiled binary for instant-start browser automation via CLI.

### Build & Run

```bash
# Build native binary
clojure -T:build uberjar
clojure -T:build native-image

# Install Playwright browsers
./target/spel install
```

## CLI Commands Reference (`spel`)

Auto-generated from CLI help text. Run `spel --help` for the full reference.

### Core Commands

| Command | Description |
|---------|-------------|
| `open <url>` | Navigate (aliases: goto, navigate) |
| `open <url> --interactive` | Navigate with visible browser |
| `click @e1` | Click element by ref or selector |
| `dblclick <sel>` | Double-click element |
| `fill @e2 \"text\"` | Clear and fill input |
| `type @e2 \"text\"` | Type without clearing |
| `press <key>` | Press key (Enter, Tab, Control+a) (alias: key) |
| `keydown <key>` | Hold key down |
| `keyup <key>` | Release key |
| `hover <sel>` | Hover element |
| `select <sel> <val>` | Select dropdown option |
| `check / uncheck <sel>` | Toggle checkbox |
| `focus <sel>` | Focus element |
| `clear <sel>` | Clear input |
| `scroll <dir> [px]` | Scroll (up/down/left/right) |
| `scrollintoview <sel>` | Scroll element into view |
| `drag <src> <tgt>` | Drag and drop |
| `upload <sel> <files...>` | Upload files |
| `close` | Close browser (aliases: quit, exit) |

### Snapshot & Screenshot

| Command | Description |
|---------|-------------|
| `snapshot` | Full accessibility tree with refs |
| `snapshot -i` | Interactive elements only |
| `snapshot -i -c -d 5` | Compact, depth-limited |
| `snapshot -i -C` | Interactive + cursor elements |
| `snapshot -s \"#main\"` | Scoped to selector |
| `screenshot [path]` | Take screenshot (-f full page) |
| `annotate` | Show annotation overlays (visible elements) |
| `-s, --scope <sel\|@ref>` | Scope annotations to a subtree |
| `--no-badges` | Hide ref labels |
| `--no-dimensions` | Hide size labels |
| `--no-boxes` | Hide bounding boxes |
| `unannotate` | Remove annotation overlays |
| `pdf <path>` | Save as PDF |

### Get Info

| Command | Description |
|---------|-------------|
| `get text @e1` | Get text content |
| `get html @e1` | Get innerHTML |
| `get value @e1` | Get input value |
| `get attr @e1 <name>` | Get attribute value |
| `get url / get title` | Get page URL or title |
| `get count <sel>` | Count matching elements |
| `get box <sel>` | Get bounding box |

### Find (Semantic Locators)

| Command | Description |
|---------|-------------|
| `find role <role> <action>` | By ARIA role |
| `find text <text> <action>` | By text content |
| `find label <text> <action> [value]` | By label |
| `find role button click --name Submit` | With name filter |
| `find first/last/nth <sel> <action>` | Position-based |

### Wait

| Command | Description |
|---------|-------------|
| `wait <sel>` | Wait for element visible |
| `wait <ms>` | Wait for timeout |
| `wait --text \"Welcome\"` | Wait for text to appear |
| `wait --url \"**/dash\"` | Wait for URL pattern |
| `wait --load networkidle` | Wait for load state |
| `wait --fn \"window.ready\"` | Wait for JS condition |

### Mouse Control

| Command | Description |
|---------|-------------|
| `mouse move <x> <y>` | Move mouse |
| `mouse down/up [button]` | Press/release button |
| `mouse wheel <dy> [dx]` | Scroll wheel |

### Browser Settings

| Command | Description |
|---------|-------------|
| `set viewport <w> <h>` | Set viewport size |
| `set device <name>` | Emulate device (iphone 14, pixel 7, etc.) |
| `set geo <lat> <lng>` | Set geolocation |
| `set offline [on\|off]` | Toggle offline mode |
| `set headers <json>` | Extra HTTP headers |
| `set credentials <u> <p>` | HTTP basic auth |
| `set media dark\|light` | Emulate color scheme |

### Cookies & Storage

| Command | Description |
|---------|-------------|
| `cookies` | Get all cookies |
| `cookies set <name> <val>` | Set cookie |
| `cookies clear` | Clear cookies |
| `storage local [key]` | Get localStorage |
| `storage local clear` | Clear localStorage |
| `storage session [key]` | Same for sessionStorage |

### Network

| Command | Description |
|---------|-------------|
| `network route <url>` | Intercept requests |
| `network route <url> --abort` | Block requests |
| `network unroute [url]` | Remove routes |
| `network requests [flags]` | View requests |
| `--filter <regex>` | Filter by URL regex |
| `--type <type>` | Filter by type (document, script, fetch, ...) |
| `--method <method>` | Filter by method (GET, POST, ...) |
| `--status <prefix>` | Filter by status (2, 30, 404, ...) |
| `network clear` | Clear tracked requests |

### Tabs & Windows

| Command | Description |
|---------|-------------|
| `tab` | List tabs |
| `tab new [url]` | New tab |
| `tab <n>` | Switch to tab |
| `tab close` | Close tab |

### Frames & Dialogs

| Command | Description |
|---------|-------------|
| `frame <sel>` | Switch to iframe |
| `frame main` | Back to main frame |
| `frame list` | List all frames |
| `dialog accept [text]` | Accept dialog |
| `dialog dismiss` | Dismiss dialog |

### Debug

| Command | Description |
|---------|-------------|
| `eval <js>` | Run JavaScript |
| `eval <js> -b` | Run JavaScript, base64-encode result |
| `connect <url>` | Connect to browser via CDP |
| `trace start / trace stop` | Record trace |
| `console / console clear` | View/clear console (auto-captured) |
| `errors / errors clear` | View/clear errors (auto-captured) |
| `highlight <sel>` | Highlight element |
| `inspector [url]` | Launch Playwright Inspector (headed browser) |
| `show-trace [trace]` | Open Playwright Trace Viewer |

### State Management

| Command | Description |
|---------|-------------|
| `state save [path]` | Save auth/storage state |
| `state load [path]` | Load saved state |
| `state list` | List state files |
| `state show <file>` | Show state file contents |
| `state rename <old> <new>` | Rename state file |
| `state clear [--all]` | Clear state files |
| `state clean [--older-than N]` | Remove states older than N days |

### Navigation

| Command | Description |
|---------|-------------|
| `back / forward / reload` | History navigation |

### Sessions

| Command | Description |
|---------|-------------|
| `--session <name>` | Use named session |
| `session` | Show current session info |
| `session list` | List active sessions |

### Options

| Command | Description |
|---------|-------------|
| `--session <name>` | Named session (default: \"default\") |
| `--json` | JSON output (for agents) |
| `--storage-state <path>` | Load storage state (cookies/localStorage JSON) |
| `--profile <path>` | Chrome user data directory (persistent profile) |
| `--executable-path <path>` | Custom browser executable |
| `--user-agent <ua>` | Custom user agent string |
| `--proxy <url>` | Proxy server URL |
| `--proxy-bypass <domains>` | Proxy bypass domains |
| `--headers <json>` | HTTP headers |
| `--args <args>` | Browser args (comma-separated) |
| `--cdp <url>` | Connect via CDP endpoint |
| `--ignore-https-errors` | Ignore HTTPS errors |
| `--allow-file-access` | Allow file:// access |
| `--timeout <ms>` | Playwright action timeout in ms (default: 30000) |
| `--debug` | Debug output |
| `--help, -h` | Show this help |

### Tools

| Command | Description |
|---------|-------------|
| `init-agents [opts]` | Scaffold E2E testing agents (--help for details) |
| `codegen record [url]` | Record browser session (interactive Playwright Codegen) |
| `codegen [opts] [file]` | Transform JSONL recording to Clojure code (--help for details) |
| `ci-assemble [opts]` | Assemble Allure site for CI deployment (--help for details) |
| `merge-reports [dirs]` | Merge N allure-results dirs into one (--help for details) |

### Utility

| Command | Description |
|---------|-------------|
| `install [--with-deps]` | Install Playwright browsers |
| `version` | Show version |

### Modes

| Command | Description |
|---------|-------------|
| `--eval '<code>'` | Evaluate Clojure expression |
| `--eval <file.clj>` | Evaluate Clojure file (e.g. codegen script) |
| `--eval --interactive` | Evaluate with visible browser (headed mode) |
| `--eval --load-state FILE` | Load auth/storage state before evaluation |


### CLI Configuration

Global flags apply to all commands and modes:

| Flag | Default | Purpose |
|------|---------|---------|
| `--timeout <ms>` | `30000` | Playwright action timeout in milliseconds |
| `--session <name>` | `default` | Named browser session (isolates state between sessions) |
| `--json` | off | JSON output format (for agent/machine consumption) |
| `--debug` | off | Debug output |
| `--autoclose` | off | Close daemon after `--eval` completes |
| `--interactive` | off | Headed (visible) browser for `--eval` mode |
| `--load-state <path>` | - | Load browser storage state (cookies/localStorage JSON) before evaluation |
| `--storage-state <path>` | - | Load storage state for CLI commands |
| `--profile <path>` | - | Chrome user data directory (persistent profile) |
| `--executable-path <path>` | - | Custom browser executable |
| `--user-agent <ua>` | - | Custom user agent string |
| `--proxy <url>` | - | Proxy server URL |
| `--proxy-bypass <domains>` | - | Proxy bypass domains |
| `--headers <json>` | - | Extra HTTP headers (JSON string) |
| `--args <args>` | - | Browser args (comma-separated) |
| `--cdp <url>` | - | Connect via Chrome DevTools Protocol endpoint |
| `--ignore-https-errors` | off | Ignore HTTPS certificate errors |
| `--allow-file-access` | off | Allow file:// access |

### CI Assemble (`spel ci-assemble`)

Assembles Allure report sites for CI/CD deployment. Replaces shell/Python scripts in CI workflows with a single Clojure command.

```bash
spel ci-assemble \
  --site-dir=gh-pages-site \
  --run=123 \
  --commit-sha=abc123def \
  --commit-msg="feat: add feature" \
  --report-url=https://example.github.io/repo/123/ \
  --test-passed=100 --test-failed=2
```

| Flag | Env Var | Purpose |
|------|---------|---------|
| `--site-dir DIR` | `SPEL_CI_SITE_DIR` | Site directory (default: `gh-pages-site`) |
| `--run NUMBER` | `RUN_NUMBER` | CI run number (required) |
| `--commit-sha SHA` | `COMMIT_SHA` | Git commit SHA |
| `--commit-msg MSG` | `COMMIT_MSG` | Commit message |
| `--commit-ts TS` | `COMMIT_TS` | Commit timestamp (ISO 8601) |
| `--tests-passed BOOL` | `TEST_PASSED` | Whether tests passed (`true`/`false`) |
| `--repo-url URL` | `REPO_URL` | Repository URL |
| `--run-url URL` | `RUN_URL` | CI run URL |
| `--version VER` | `VERSION` | Project version string |
| `--version-badge TYPE` | `VERSION_BADGE` | Badge type: `release` or `candidate` |
| `--test-passed N` | `TEST_COUNTS_PASSED` | Number of passed tests |
| `--test-failed N` | `TEST_COUNTS_FAILED` | Number of failed tests |
| `--test-broken N` | `TEST_COUNTS_BROKEN` | Number of broken tests |
| `--test-skipped N` | `TEST_COUNTS_SKIPPED` | Number of skipped tests |
| `--history-file FILE` | `ALLURE_HISTORY_FILE` | Allure history file (default: `.allure-history.jsonl`) |
| `--report-url URL` | `REPORT_URL` | Report URL for history patching |
| `--logo-file FILE` | `LOGO_FILE` | Logo SVG file path |
| `--index-file FILE` | `INDEX_FILE` | Index HTML file path |
| `--title TEXT` | `LANDING_TITLE` | Title to inject into index.html |
| `--subtitle TEXT` | `LANDING_SUBTITLE` | Subtitle to inject into index.html |

Operations performed (in order):
1. Patches `.allure-history.jsonl` with report URL and commit info (when `--report-url` set)
2. Generates `builds.json`, `builds-meta.json`, and `badge.json` (when site directory exists)
3. Patches `index.html` with logo and title placeholders (when `--index-file` set)

**In-Progress Build Tracking:**

The CI module supports tracking builds as "in progress" with a yellow animated badge on the landing page:

```clojure
;; At start of CI run — registers build with yellow "In Progress" badge
(ci/register-build-start!
  {:site-dir "gh-pages-site"
   :run-number "123"
   :commit-sha "abc123..."
   :commit-msg "feat: add feature"
   :commit-author "developer"
   :repo-url "https://github.com/org/repo"
   :run-url "https://github.com/org/repo/actions/runs/456"})

;; After tests complete — updates status to completed/failed
(ci/finalize-build!
  {:site-dir "gh-pages-site"
   :run-number "123"
   :passed true})
```

Flow: register → deploy pages (shows yellow badge) → run tests → finalize → regenerate metadata → re-deploy pages.

In CI workflows, call via JVM (Clojure CLI) rather than native binary:

```clojure
clojure -M -e "
  (require '[com.blockether.spel.ci :as ci])
  (ci/generate-builds-metadata! {:site-dir \"gh-pages-site\" ...})
  (ci/patch-index-html! {:index-file \"gh-pages-site/index.html\" ...})"
```

## SCI Eval API Reference (`spel --eval`)

Auto-generated from SCI namespace registrations. All functions are available in `spel --eval` mode without JVM startup.

### Enums — Java types available in `--eval` mode

All Playwright Java enums from `com.microsoft.playwright.options` are registered as SCI classes. Use them directly by `EnumName/VALUE` — no import needed.

| Enum | Values | Used For |
|------|--------|----------|
| `AriaRole` | `ALERT`, `ALERTDIALOG`, `APPLICATION`, `ARTICLE`, `BANNER`, `BLOCKQUOTE`, ... | `page/get-by-role`, `locator/loc-get-by-role`, `assert/has-role` |
| `ColorScheme` | `LIGHT`, `DARK`, `NO_PREFERENCE` | `page/emulate-media!` / context options `:color-scheme` |
| `ForcedColors` | `ACTIVE`, `NONE` | Context options `:forced-colors` |
| `HarContentPolicy` | `OMIT`, `EMBED`, `ATTACH` | HAR options `:record-har-content` |
| `HarMode` | `FULL`, `MINIMAL` | HAR recording options `:record-har-mode` |
| `HarNotFound` | `ABORT`, `FALLBACK` | Route-from-HAR options `:not-found` |
| `LoadState` | `LOAD`, `DOMCONTENTLOADED`, `NETWORKIDLE` | `page/wait-for-load-state` |
| `Media` | `SCREEN`, `PRINT` | `page/emulate-media!` options `:media` |
| `MouseButton` | `LEFT`, `RIGHT`, `MIDDLE` | Click options `:button` |
| `ReducedMotion` | `REDUCE`, `NO_PREFERENCE` | Context options `:reduced-motion` |
| `RouteFromHarUpdateContentPolicy` | `EMBED`, `ATTACH` | Route-from-HAR options `:update-content` |
| `SameSiteAttribute` | `STRICT`, `LAX`, `NONE` | Cookie options `:same-site` |
| `ScreenshotType` | `PNG`, `JPEG` | Screenshot options `:type` |
| `ServiceWorkerPolicy` | `ALLOW`, `BLOCK` | Context options `:service-workers` |
| `WaitForSelectorState` | `ATTACHED`, `DETACHED`, `VISIBLE`, `HIDDEN` | `page/wait-for-selector` options `:state` |
| `WaitUntilState` | `LOAD`, `DOMCONTENTLOADED`, `NETWORKIDLE`, `COMMIT` | Navigation options `:wait-until` |

**Usage in `--eval` mode:**

```clojure
;; Enums are available directly — no import required
(spel/$role AriaRole/BUTTON)
(spel/$role AriaRole/HEADING {:name "Installation"})

;; Use with library API (page/locator namespaces)
(page/get-by-role (spel/page) AriaRole/NAVIGATION)
(page/wait-for-load-state (spel/page) LoadState/NETWORKIDLE)

;; Compare enum values
(= AriaRole/BUTTON AriaRole/BUTTON)  ;; => true
```

**Usage in library code (requires import):**

```clojure
(:import [com.microsoft.playwright.options AriaRole LoadState WaitUntilState])
```

### `spel/` — Simplified API with implicit page (lifecycle, navigation, actions, assertions)

| Function | Args | Description |
|----------|------|-------------|
| `spel/$` | [sel-or-loc] | Resolves a ref ID to a Playwright Locator. |
| `spel/$$` | [sel] | Returns all elements matching the locator as individual locators. |
| `spel/$alt-text` | [text] | Locates elements by alt text. |
| `spel/$label` | [text] | Locates elements by their label text. |
| `spel/$placeholder` | [text] | Locates elements by placeholder text. |
| `spel/$role` | [role] \| [role opts] | Locates elements by their ARIA role. |
| `spel/$test-id` | [id] | Locates elements by test ID attribute. |
| `spel/$text` | [text] | Locates elements by their text content. |
| `spel/$title-attr` | [text] | Locates elements by title attribute. |
| `spel/add-script-tag` | [opts] | Adds a script tag to the page. |
| `spel/add-style-tag` | [opts] | Adds a style tag to the page. |
| `spel/all-inner-texts` | [sel] | Returns all inner texts for matching elements. |
| `spel/all-text-contents` | [sel] | Returns all text contents for matching elements. |
| `spel/annotate` | [refs] \| [refs opts] | Injects annotation overlays into the current page for visible elements. |
| `spel/annotated-screenshot` | [refs] \| [refs opts] | Takes a screenshot with annotation overlays (convenience function). |
| `spel/assert-accessible-description` | [sel desc] | Asserts the locator has the specified accessible description. |
| `spel/assert-accessible-error-message` | [sel msg] | Asserts the locator has the specified accessible error message. |
| `spel/assert-accessible-name` | [sel name-val] | Asserts the locator has the specified accessible name. |
| `spel/assert-attached` | [sel] \| [sel opts] | Asserts the locator is attached to the DOM. |
| `spel/assert-attr` | [sel attr-name value] \| [sel attr-name value opts] | Asserts the locator has the specified attribute with value. |
| `spel/assert-checked` | [sel] \| [sel opts] | Asserts the locator (checkbox/radio) is checked. |
| `spel/assert-class` | [sel class-val] \| [sel class-val opts] | Asserts the locator has the specified CSS class. |
| `spel/assert-contains-class` | [sel class-val] \| [sel class-val opts] | Asserts the locator's class attribute contains the specified class. |
| `spel/assert-contains-text` | [sel expected] \| [sel expected opts] | Asserts the locator contains the specified text. |
| `spel/assert-count` | [sel n] \| [sel n opts] | Asserts the locator resolves to the expected number of elements. |
| `spel/assert-css` | [sel css-name value] \| [sel css-name value opts] | Asserts the locator has the specified CSS property with value. |
| `spel/assert-disabled` | [sel] \| [sel opts] | Asserts the locator is disabled. |
| `spel/assert-editable` | [sel] \| [sel opts] | Asserts the locator is editable. |
| `spel/assert-empty` | [sel] | Asserts the locator (input) is empty. |
| `spel/assert-enabled` | [sel] \| [sel opts] | Asserts the locator is enabled. |
| `spel/assert-focused` | [sel] \| [sel opts] | Asserts the locator is focused. |
| `spel/assert-hidden` | [sel] \| [sel opts] | Asserts the locator is hidden. |
| `spel/assert-id` | [sel id] \| [sel id opts] | Asserts the locator has the specified ID. |
| `spel/assert-in-viewport` | [sel] \| [sel opts] | Asserts the locator is in the viewport. |
| `spel/assert-js-property` | [sel prop-name value] | Asserts the locator has the specified JavaScript property. |
| `spel/assert-matches-aria-snapshot` | [sel snapshot-str] | Asserts the locator matches the ARIA snapshot. |
| `spel/assert-not` | [sel] | Returns negated LocatorAssertions (expect the opposite). |
| `spel/assert-page-not` | [] | Returns negated PageAssertions (expect the opposite). |
| `spel/assert-role` | [sel role] | Asserts the locator has the specified ARIA role. |
| `spel/assert-text` | [sel expected] \| [sel expected opts] | Asserts the locator has the specified text. |
| `spel/assert-that` | [target] | Creates an assertion object for the given Playwright instance. |
| `spel/assert-title` | [expected] \| [expected opts] | Asserts the page has the specified title. |
| `spel/assert-url` | [expected] \| [expected opts] | Asserts the page has the specified URL. |
| `spel/assert-value` | [sel value] \| [sel value opts] | Asserts the locator (input) has the specified value. |
| `spel/assert-values` | [sel values] \| [sel values opts] | Asserts the locator (multi-select) has the specified values. |
| `spel/assert-visible` | [sel] \| [sel opts] | Asserts the locator is visible. |
| `spel/attr` | [sel name] | Returns the value of an attribute. |
| `spel/audit-screenshot` | [caption] \| [caption opts] | Takes a screenshot with a caption bar at the bottom. |
| `spel/back` | [] | Navigates back in history. |
| `spel/bbox` | [sel] | Returns the bounding box of the element. |
| `spel/blur` | [sel] | Blurs (removes focus from) the element. |
| `spel/bring-to-front` | [] | Brings page to front (activates tab). |
| `spel/browser` | [] | Returns the current Browser instance. |
| `spel/browser-connected?` | [] | Returns true if the browser is connected. |
| `spel/browser-version` | [] | Returns the browser version string. |
| `spel/check` | [sel] \| [sel opts] | Checks a checkbox or radio button. |
| `spel/checked?` | [sel] | Returns whether the element is checked. |
| `spel/clear` | [sel] | Clears input field content. |
| `spel/clear-refs!` | [] | Removes all data-pw-ref attributes from the page. |
| `spel/click` | [sel] \| [sel opts] | Clicks an element. |
| `spel/context` | [] | Returns the current BrowserContext instance. |
| `spel/context-clear-cookies!` | [] | Clears all cookies in the context. |
| `spel/context-clear-permissions!` | [] | Clears all granted permissions. |
| `spel/context-cookies` | [] | Returns all cookies in the context. |
| `spel/context-grant-permissions!` | [perms] | Grants permissions to the context. |
| `spel/context-route-from-har!` | [har] \| [har opts] | Routes requests in the context from a HAR file. Replays recorded responses |
| `spel/context-route-web-socket!` | [pattern handler] | Registers a handler for WebSocket connections matching a URL pattern |
| `spel/context-save-storage-state!` | [path] | Saves the storage state (cookies, localStorage) to a file. |
| `spel/context-set-extra-http-headers!` | [headers] | Sets extra HTTP headers for all requests in the context. |
| `spel/context-set-offline!` | [offline] | Sets the context to offline or online mode. |
| `spel/context-storage-state` | [] | Returns the storage state (cookies, localStorage) as a JSON string. |
| `spel/count-of` | [sel] | Returns the number of elements matching the locator. |
| `spel/dblclick` | [sel] \| [sel opts] | Double-clicks an element. |
| `spel/disabled?` | [sel] | Returns whether the element is disabled. |
| `spel/dispatch-event` | [sel type] | Dispatches a DOM event on the element. |
| `spel/drag-to` | [sel target-sel] | Drags this locator to another locator. |
| `spel/editable?` | [sel] | Returns whether the element is editable. |
| `spel/emulate-media!` | [opts] | Emulates media type and features. |
| `spel/enabled?` | [sel] | Returns whether the element is enabled. |
| `spel/eval-js` | [expr] \| [expr arg] | Evaluates JavaScript expression in the page context. |
| `spel/evaluate-all-locs` | [sel expr] \| [sel expr arg] | Evaluates JavaScript on all elements matching the locator. |
| `spel/evaluate-handle` | [expr] \| [expr arg] | Like evaluate, but returns a JSHandle. |
| `spel/evaluate-locator` | [sel expr] \| [sel expr arg] | Evaluates JavaScript on the element found by this locator. |
| `spel/expose-binding!` | [binding-name f] | Exposes a Clojure function as a binding. |
| `spel/expose-function!` | [fn-name f] | Exposes a Clojure function to JavaScript. |
| `spel/fill` | [sel value] \| [sel value opts] | Fills an input element with text. |
| `spel/finish-video-recording` | [] \| [opts] | Stops video recording by closing the context to finalize the video. |
| `spel/first` | [sel] | Returns the first element matching the locator. |
| `spel/focus` | [sel] | Focuses the element. |
| `spel/forward` | [] | Navigates forward in history. |
| `spel/frame-by-name` | [name] | Returns a frame by its name attribute. |
| `spel/frame-by-url` | [pattern] | Returns a frame by matching URL pattern. |
| `spel/frames` | [] | Returns all frames in the page. |
| `spel/full-snapshot` | [] \| [page] | Captures a snapshot of the page and all its iframes. |
| `spel/goto` | [url] \| [url opts] | Navigates the page to a URL. |
| `spel/help` | [] \| [query] | Lists all available SCI eval functions with arglists and descriptions. |
| `spel/hidden?` | [sel] | Returns whether the element is hidden. |
| `spel/highlight` | [sel] | Highlights the element for debugging. |
| `spel/hover` | [sel] \| [sel opts] | Hovers over an element. |
| `spel/html` | [] | Returns the full HTML content of the page. |
| `spel/info` | [] | Returns a map with current page :url, :title, :viewport, and :closed? state. |
| `spel/inner-html` | [sel] | Returns the inner HTML of the element. |
| `spel/inner-text` | [sel] | Returns the inner text of the element. |
| `spel/keyboard` | [] | Returns the Keyboard for this page. |
| `spel/last` | [sel] | Returns the last element matching the locator. |
| `spel/last-response` | [url] | Navigates to URL and returns response info map with :status, :ok?, :url, :headers. |
| `spel/loc-filter` | [sel opts] | Filters this locator to a narrower set. |
| `spel/loc-get-by-label` | [sel text] | Locates elements by label within this locator. |
| `spel/loc-get-by-role` | [sel role] | Locates elements by ARIA role within this locator. |
| `spel/loc-get-by-test-id` | [sel id] | Locates elements by test ID within this locator. |
| `spel/loc-get-by-text` | [sel text] | Locates elements by text within this locator. |
| `spel/loc-locator` | [sel sub-sel] | Creates a sub-locator within this locator. |
| `spel/loc-wait-for` | [sel] \| [sel opts] | Waits for the locator to satisfy a condition. |
| `spel/locator-screenshot` | [sel] \| [sel opts] | Takes a screenshot of the element. |
| `spel/main-frame` | [] | Returns the main frame of the page. |
| `spel/mark` | [& refs] | Highlights specific snapshot refs with prominent pre-action markers. |
| `spel/mouse` | [] | Returns the Mouse for this page. |
| `spel/navigate` | [url] \| [url opts] | Navigates the page to a URL. |
| `spel/new-tab!` | [] | Opens a new tab in the current context and switches to it. |
| `spel/nth` | [sel n] | Returns the nth element matching the locator. |
| `spel/on-close` | [handler] | Registers a handler for page close. |
| `spel/on-console` | [handler] | Registers a handler for console messages. |
| `spel/on-dialog` | [handler] | Registers a handler for dialogs. |
| `spel/on-download` | [handler] | Registers a handler for downloads. |
| `spel/on-page-error` | [handler] | Registers a handler for page errors. |
| `spel/on-popup` | [handler] | Registers a handler for popup pages. |
| `spel/on-request` | [handler] | Registers a handler for requests. |
| `spel/on-response` | [handler] | Registers a handler for responses. |
| `spel/once-dialog` | [handler] | Registers a one-time handler for the next dialog. |
| `spel/page` | [] | Returns the current Page instance. |
| `spel/page-context` | [] | Returns the BrowserContext that the page belongs to. |
| `spel/pdf` | [] \| [path-or-opts] | Generates a PDF of the page. Only works in Chromium headless. |
| `spel/press` | [sel key] \| [sel key opts] | Presses a key or key combination. |
| `spel/reload!` | [] | Reloads the page. |
| `spel/resolve-ref` | [ref-id] | Resolves a ref ID to a Playwright Locator. |
| `spel/restart!` | [] \| [opts] | Stops the current session and starts a new one with the given options. |
| `spel/route!` | [pattern handler] | Registers a route handler for URL pattern. |
| `spel/route-from-har!` | [har] \| [har opts] | Routes requests from a HAR file. Replays recorded responses for matching requests. |
| `spel/route-web-socket!` | [pattern handler] | Registers a handler for WebSocket connections matching a URL pattern. |
| `spel/save-annotated-screenshot!` | [refs path] \| [refs path opts] | Takes an annotated screenshot and saves it to a file. |
| `spel/save-audit-screenshot!` | [caption path] \| [caption path opts] | Takes an audit screenshot and saves it to a file. |
| `spel/screenshot` | [] \| [path-or-opts] | Takes a screenshot of the page. |
| `spel/scroll-into-view` | [sel] | Scrolls element into view. |
| `spel/select` | [sel values] | Selects options in a select element. |
| `spel/set-assertion-timeout!` | [ms] | Sets the default timeout for all assertions. |
| `spel/set-content!` | [html] \| [html opts] | Sets the HTML content of the page. |
| `spel/set-default-navigation-timeout!` | [ms] | Sets the default navigation timeout. |
| `spel/set-default-timeout!` | [ms] | Sets the default timeout for page operations. |
| `spel/set-extra-http-headers!` | [headers] | Sets extra HTTP headers for all requests on this page. |
| `spel/set-input-files!` | [sel files] | Sets the value of a file input element. |
| `spel/set-viewport-size!` | [width height] | Sets the viewport size. |
| `spel/sleep` | [ms] | Waits for the specified time in milliseconds. |
| `spel/snapshot` | [] \| [page-or-opts] \| [page opts] | Captures an accessibility snapshot of the page with numbered refs. |
| `spel/source` | [query] | Shows the source code of a SCI eval function. |
| `spel/start!` | [] \| [opts] | Creates a new Playwright instance. |
| `spel/start-video-recording` | [] \| [opts] | Starts video recording by creating a new context with video recording enabled. |
| `spel/stop!` | [] | Stops the Playwright session, closing browser and cleaning up resources. |
| `spel/switch-tab!` | [idx] | Switches to the tab at the given index. |
| `spel/tabs` | [] | Returns a list of all open tabs with their index, url, title, and active status. |
| `spel/tap` | [sel] | Taps an element (for touch devices). |
| `spel/text` | [sel] | Returns the text content of the element. |
| `spel/title` | [] | Returns the page title. |
| `spel/touchscreen` | [] | Returns the Touchscreen for this page. |
| `spel/trace-group` | [name] | Opens a named group in the trace. Groups nest actions visually in Trace Viewer. |
| `spel/trace-group-end` | [] | Closes the current trace group. |
| `spel/trace-start!` | [] \| [opts] | Starts Playwright tracing on the current context. |
| `spel/trace-stop!` | [] \| [opts] | Stops Playwright tracing and saves to a file. |
| `spel/type-text` | [sel text] \| [sel text opts] | Types text into an element character by character. |
| `spel/unannotate` | [] | Removes all annotation overlays from the current page. |
| `spel/uncheck` | [sel] \| [sel opts] | Unchecks a checkbox. |
| `spel/unmark` | [] | Removes all pre-action markers from the current page. |
| `spel/unroute!` | [pattern] | Removes a route handler. |
| `spel/url` | [] | Returns the current page URL. |
| `spel/value` | [sel] | Returns the input value of an input element. |
| `spel/video-path` | [] | Returns the video file path for the current page, or nil if not recording. |
| `spel/visible?` | [sel] | Returns whether the element is visible. |
| `spel/wait-for` | [sel] \| [sel opts] | Waits for a selector to satisfy a condition. |
| `spel/wait-for-download` | [action] \| [action opts] | Waits for a download to start while executing `action`. |
| `spel/wait-for-file-chooser` | [action] \| [action opts] | Waits for a file chooser dialog while executing `action`. |
| `spel/wait-for-function` | [expr] | Waits for a JavaScript function to return a truthy value. |
| `spel/wait-for-load` | [] \| [state] | Waits for the page to reach a load state. |
| `spel/wait-for-popup` | [action] \| [action opts] | Waits for a popup page to open while executing `action`. |
| `spel/wait-for-url` | [url] | Waits for the page to navigate to a URL. |

### `snapshot/` — Accessibility snapshot capture and ref resolution

| Function | Args | Description |
|----------|------|-------------|
| `snapshot/capture` | [] \| [page-or-opts] \| [page opts] | Captures an accessibility snapshot of the page with numbered refs. |
| `snapshot/capture-full` | [] \| [page] | Captures a snapshot of the page and all its iframes. |
| `snapshot/clear-refs!` | [] | Removes all data-pw-ref attributes from the page. |
| `snapshot/ref-bounding-box` | [refs ref-id] | Returns the bounding box for a ref from the last snapshot. |
| `snapshot/resolve-ref` | [ref-id] | Resolves a ref ID to a Playwright Locator. |

### `annotate/` — Page annotation overlays

| Function | Args | Description |
|----------|------|-------------|
| `annotate/annotated-screenshot` | [refs] \| [refs opts] | Takes a screenshot with annotation overlays (convenience function). |
| `annotate/audit-screenshot` | [caption] \| [caption opts] | Takes a screenshot with a caption bar at the bottom. |
| `annotate/mark!` | [& refs] | Highlights specific snapshot refs with prominent pre-action markers. |
| `annotate/save!` | [refs path] \| [refs path opts] | Takes an annotated screenshot and saves it to a file. |
| `annotate/save-audit!` | [caption path] \| [caption path opts] | Takes an audit screenshot and saves it to a file. |
| `annotate/unmark!` | [] | Removes all pre-action markers from the current page. |

### `input/` — Keyboard, mouse, touchscreen (explicit device arg)

| Function | Args | Description |
|----------|------|-------------|
| `input/key-down` | [keyboard key] | Dispatches a keydown event. |
| `input/key-insert-text` | [keyboard text] | Inserts text without key events. |
| `input/key-press` | [keyboard key] \| [keyboard key press-opts] | Presses a key on the keyboard. |
| `input/key-type` | [keyboard text] \| [keyboard text type-opts] | Types text character by character. |
| `input/key-up` | [keyboard key] | Dispatches a keyup event. |
| `input/mouse-click` | [mouse x y] \| [mouse x y click-opts] | Clicks at the given coordinates. |
| `input/mouse-dblclick` | [mouse x y] \| [mouse x y dblclick-opts] | Double-clicks at the given coordinates. |
| `input/mouse-down` | [mouse] | Dispatches a mousedown event. |
| `input/mouse-move` | [mouse x y] \| [mouse x y move-opts] | Moves the mouse to the given coordinates. |
| `input/mouse-up` | [mouse] | Dispatches a mouseup event. |
| `input/mouse-wheel` | [mouse delta-x delta-y] | Dispatches a wheel event. |
| `input/touchscreen-tap` | [ts x y] | Taps at the given coordinates. |

### `frame/` — Frame and FrameLocator operations (explicit Frame arg)

| Function | Args | Description |
|----------|------|-------------|
| `frame/child-frames` | [frame] | Returns child frames. |
| `frame/content` | [frame] | Returns the HTML content of the frame. |
| `frame/evaluate` | [frame expression] \| [frame expression arg] | Evaluates JavaScript in the frame context. |
| `frame/fl-first` | [fl] | Returns the first FrameLocator. |
| `frame/fl-get-by-label` | [fl text] | Locates by label within a FrameLocator. |
| `frame/fl-get-by-role` | [fl role] | Locates by ARIA role within a FrameLocator. |
| `frame/fl-get-by-text` | [fl text] | Locates by text within a FrameLocator. |
| `frame/fl-last` | [fl] | Returns the last FrameLocator. |
| `frame/fl-locator` | [fl selector] | Creates a Locator within a FrameLocator. |
| `frame/fl-nth` | [fl index] | Returns the nth FrameLocator. |
| `frame/frame-locator` | [page-or-frame selector] | Creates a FrameLocator for an iframe. |
| `frame/frame-page` | [frame] | Returns the page that owns this frame. |
| `frame/get-by-label` | [frame text] | Locates elements by label in the frame. |
| `frame/get-by-role` | [frame role] | Locates elements by ARIA role in the frame. |
| `frame/get-by-test-id` | [frame test-id] | Locates elements by test ID in the frame. |
| `frame/get-by-text` | [frame text] | Locates elements by text in the frame. |
| `frame/is-detached?` | [frame] | Returns whether the frame has been detached. |
| `frame/locator` | [frame selector] | Creates a Locator for the frame. |
| `frame/name` | [frame] | Returns the frame name. |
| `frame/navigate` | [frame url] \| [frame url nav-opts] | Navigates the frame to a URL. |
| `frame/parent-frame` | [frame] | Returns the parent frame. |
| `frame/set-content!` | [frame html] \| [frame html set-opts] | Sets the HTML content of the frame. |
| `frame/title` | [frame] | Returns the frame title. |
| `frame/url` | [frame] | Returns the frame URL. |
| `frame/wait-for-function` | [frame expression] | Waits for a JavaScript function to return truthy in the frame. |
| `frame/wait-for-load-state` | [frame] \| [frame state] | Waits for the frame to reach a load state. |
| `frame/wait-for-selector` | [frame selector] \| [frame selector wait-opts] | Waits for a selector in the frame. |

### `net/` — Network request/response/route (explicit object arg)

| Function | Args | Description |
|----------|------|-------------|
| `net/request-all-headers` | [req] | Returns all request headers including redirects. |
| `net/request-failure` | [req] | Returns the failure text if the request failed. |
| `net/request-frame` | [req] | Returns the frame that initiated this request. |
| `net/request-headers` | [req] | Returns the request headers as a map. |
| `net/request-is-navigation?` | [req] | Returns whether this is a navigation request. |
| `net/request-method` | [req] | Returns the request HTTP method. |
| `net/request-post-data` | [req] | Returns the request POST data. |
| `net/request-post-data-buffer` | [req] | Returns the request POST data as bytes. |
| `net/request-redirected-from` | [req] | Returns the request that redirected to this one. |
| `net/request-redirected-to` | [req] | Returns the request this was redirected to. |
| `net/request-resource-type` | [req] | Returns the resource type (e.g. document, script, image). |
| `net/request-response` | [req] | Returns the response for this request. |
| `net/request-timing` | [req] | Returns the request timing information. |
| `net/request-url` | [req] | Returns the request URL. |
| `net/response-all-headers` | [resp] | Returns all response headers. |
| `net/response-body` | [resp] | Returns the response body as bytes. |
| `net/response-finished` | [resp] | Returns nil when response finishes, or the failure error string. |
| `net/response-frame` | [resp] | Returns the frame that received this response. |
| `net/response-header-value` | [resp name] | Returns the value of a specific header. |
| `net/response-header-values` | [resp name] | Returns all values for a specific header. |
| `net/response-headers` | [resp] | Returns the response headers. |
| `net/response-ok?` | [resp] | Returns whether the response status is 2xx. |
| `net/response-request` | [resp] | Returns the request for this response. |
| `net/response-status` | [resp] | Returns the HTTP status code. |
| `net/response-status-text` | [resp] | Returns the HTTP status text. |
| `net/response-text` | [resp] | Returns the response body as text. |
| `net/response-url` | [resp] | Returns the response URL. |
| `net/route-abort!` | [route] \| [route error-code] | Aborts the route. |
| `net/route-continue!` | [route] \| [route opts] | Continues the route, optionally modifying the request. |
| `net/route-fallback!` | [route] | Falls through to the next route handler. |
| `net/route-fetch!` | [route] | Performs the request and returns the response. |
| `net/route-fulfill!` | [route opts] | Fulfills the route with a custom response. |
| `net/route-request` | [route] | Returns the request being routed. |
| `net/ws-is-closed?` | [ws] | Returns whether the WebSocket is closed. |
| `net/ws-on-close` | [ws handler] | Registers a handler for WebSocket close. |
| `net/ws-on-error` | [ws handler] | Registers a handler for WebSocket errors. |
| `net/ws-on-message` | [ws handler] | Registers a handler for incoming messages. |
| `net/ws-url` | [ws] | Returns the WebSocket URL. |
| `net/wsf-binary` | [frame] | Returns the binary content of a WebSocket frame. |
| `net/wsf-text` | [frame] | Returns the text content of a WebSocket frame. |
| `net/wsr-close!` | [wsr] | Closes the WebSocket connection from the server side. |
| `net/wsr-connect-to-server!` | [wsr] | Connects to the real server WebSocket. |
| `net/wsr-on-close` | [wsr handler] | Registers a handler for close events. |
| `net/wsr-on-message` | [wsr handler] | Registers a handler for client messages on the route. |
| `net/wsr-send!` | [wsr message] | Sends a message to the client. |
| `net/wsr-url` | [wsr] | Returns the URL of a WebSocketRoute. |

### `loc/` — Raw locator operations (explicit Locator arg)

| Function | Args | Description |
|----------|------|-------------|
| `loc/all` | [loc] | Returns all elements matching the locator as individual locators. |
| `loc/all-inner-texts` | [loc] | Returns all inner texts for matching elements. |
| `loc/all-text-contents` | [loc] | Returns all text contents for matching elements. |
| `loc/blur` | [loc] | Blurs (removes focus from) the element. |
| `loc/bounding-box` | [loc] | Returns the bounding box of the element. |
| `loc/check` | [loc] \| [loc check-opts] | Checks a checkbox or radio button. |
| `loc/clear` | [loc] | Clears input field content. |
| `loc/click` | [loc] \| [loc click-opts] | Clicks an element. |
| `loc/content-frame` | [loc] | Returns a FrameLocator pointing to the same iframe as this locator. |
| `loc/count-elements` | [loc] | Returns the number of elements matching the locator. |
| `loc/dblclick` | [loc] \| [loc dblclick-opts] | Double-clicks an element. |
| `loc/dispatch-event` | [loc type] | Dispatches a DOM event on the element. |
| `loc/drag-to` | [loc target] | Drags this locator to another locator. |
| `loc/element-handle` | [loc] | Returns the ElementHandle for the first matching element. |
| `loc/element-handles` | [loc] | Returns all ElementHandles matching the locator. |
| `loc/evaluate` | [loc expression] \| [loc expression arg] | Evaluates JavaScript on the element found by this locator. |
| `loc/evaluate-all` | [loc expression] \| [loc expression arg] | Evaluates JavaScript on all elements matching the locator. |
| `loc/fill` | [loc value] \| [loc value fill-opts] | Fills an input element with text. |
| `loc/first-element` | [loc] | Returns the first element matching the locator. |
| `loc/focus` | [loc] | Focuses the element. |
| `loc/get-attribute` | [loc name] | Returns the value of an attribute. |
| `loc/highlight` | [loc] | Highlights the element for debugging. |
| `loc/hover` | [loc] \| [loc hover-opts] | Hovers over an element. |
| `loc/inner-html` | [loc] | Returns the inner HTML of the element. |
| `loc/inner-text` | [loc] | Returns the inner text of the element. |
| `loc/input-value` | [loc] | Returns the input value of an input element. |
| `loc/is-checked?` | [loc] | Returns whether the element is checked. |
| `loc/is-disabled?` | [loc] | Returns whether the element is disabled. |
| `loc/is-editable?` | [loc] | Returns whether the element is editable. |
| `loc/is-enabled?` | [loc] | Returns whether the element is enabled. |
| `loc/is-hidden?` | [loc] | Returns whether the element is hidden. |
| `loc/is-visible?` | [loc] | Returns whether the element is visible. |
| `loc/last-element` | [loc] | Returns the last element matching the locator. |
| `loc/loc-filter` | [loc opts] | Filters this locator to a narrower set. |
| `loc/loc-get-by-label` | [loc text] | Locates elements by label within this locator. |
| `loc/loc-get-by-role` | [loc role] | Locates elements by ARIA role within this locator. |
| `loc/loc-get-by-test-id` | [loc test-id] | Locates elements by test ID within this locator. |
| `loc/loc-get-by-text` | [loc text] | Locates elements by text within this locator. |
| `loc/loc-locator` | [loc selector] | Creates a sub-locator within this locator. |
| `loc/nth-element` | [loc index] | Returns the nth element matching the locator. |
| `loc/press` | [loc key] \| [loc key press-opts] | Presses a key or key combination. |
| `loc/screenshot` | [loc] \| [loc ss-opts] | Takes a screenshot of the element. |
| `loc/scroll-into-view` | [loc] | Scrolls element into view. |
| `loc/select-option` | [loc values] | Selects options in a select element. |
| `loc/set-input-files!` | [loc files] | Sets the value of a file input element. |
| `loc/tap-element` | [loc] | Taps an element (for touch devices). |
| `loc/text-content` | [loc] | Returns the text content of the element. |
| `loc/type-text` | [loc text] \| [loc text type-opts] | Types text into an element character by character. |
| `loc/uncheck` | [loc] \| [loc uncheck-opts] | Unchecks a checkbox. |
| `loc/wait-for` | [loc] \| [loc wait-opts] | Waits for the locator to satisfy a condition. |

### `assert/` — Assertion functions (explicit assertion object arg)

| Function | Args | Description |
|----------|------|-------------|
| `assert/api-not` | [ara] | Returns negated APIResponseAssertions (expect the opposite). |
| `assert/assert-that` | [target] | Creates an assertion object for the given Playwright instance. |
| `assert/contains-class` | [la class-val] \| [la class-val opts] | Asserts the locator's class attribute contains the specified class. |
| `assert/contains-text` | [la text] \| [la text opts] | Asserts the locator contains the specified text. |
| `assert/has-accessible-description` | [la desc] | Asserts the locator has the specified accessible description. |
| `assert/has-accessible-error-message` | [la msg] | Asserts the locator has the specified accessible error message. |
| `assert/has-accessible-name` | [la name-val] | Asserts the locator has the specified accessible name. |
| `assert/has-attribute` | [la name value] \| [la name value opts] | Asserts the locator has the specified attribute with value. |
| `assert/has-class` | [la class-val] \| [la class-val opts] | Asserts the locator has the specified CSS class. |
| `assert/has-count` | [la count] \| [la count opts] | Asserts the locator resolves to the expected number of elements. |
| `assert/has-css` | [la name value] \| [la name value opts] | Asserts the locator has the specified CSS property with value. |
| `assert/has-id` | [la id] \| [la id opts] | Asserts the locator has the specified ID. |
| `assert/has-js-property` | [la name value] | Asserts the locator has the specified JavaScript property. |
| `assert/has-role` | [la role] | Asserts the locator has the specified ARIA role. |
| `assert/has-text` | [la text] \| [la text opts] | Asserts the locator has the specified text. |
| `assert/has-title` | [pa title] \| [pa title opts] | Asserts the page has the specified title. |
| `assert/has-url` | [pa url] \| [pa url opts] | Asserts the page has the specified URL. |
| `assert/has-value` | [la value] \| [la value opts] | Asserts the locator (input) has the specified value. |
| `assert/has-values` | [la values] \| [la values opts] | Asserts the locator (multi-select) has the specified values. |
| `assert/is-attached` | [la] \| [la opts] | Asserts the locator is attached to the DOM. |
| `assert/is-checked` | [la] \| [la opts] | Asserts the locator (checkbox/radio) is checked. |
| `assert/is-disabled` | [la] \| [la opts] | Asserts the locator is disabled. |
| `assert/is-editable` | [la] \| [la opts] | Asserts the locator is editable. |
| `assert/is-empty` | [la] | Asserts the locator (input) is empty. |
| `assert/is-enabled` | [la] \| [la opts] | Asserts the locator is enabled. |
| `assert/is-focused` | [la] \| [la opts] | Asserts the locator is focused. |
| `assert/is-hidden` | [la] \| [la opts] | Asserts the locator is hidden. |
| `assert/is-in-viewport` | [la] \| [la opts] | Asserts the locator is in the viewport. |
| `assert/is-ok` | [ara] | Asserts the API response status is 2xx. |
| `assert/is-visible` | [la] \| [la opts] | Asserts the locator is visible. |
| `assert/loc-not` | [la] | Returns negated LocatorAssertions (expect the opposite). |
| `assert/matches-aria-snapshot` | [la snapshot] | Asserts the locator matches the ARIA snapshot. |
| `assert/page-not` | [pa] | Returns negated PageAssertions (expect the opposite). |
| `assert/set-default-assertion-timeout!` | [timeout] | Sets the default timeout for all assertions. |

### `core/` — Browser lifecycle utilities and resource management

| Function | Args | Description |
|----------|------|-------------|
| `core/anomaly?` |  | Returns true if x is an anomaly map (has a recognized anomaly category). |
| `core/browser-connected?` | [browser] | Returns true if the browser is connected. |
| `core/browser-contexts` | [browser] | Returns all browser contexts. |
| `core/browser-version` | [browser] | Returns the browser version string. |
| `core/close!` | [pw] | Closes a Playwright instance and releases all resources. |
| `core/close-browser!` | [browser] | Closes a browser and all its pages. |
| `core/close-context!` | [context] | Closes a browser context and all its pages. |
| `core/close-page!` | [page] | Closes a page. |
| `core/context-browser` | [context] | Returns the browser that owns this context. |
| `core/context-clear-cookies!` | [context] | Clears all cookies in the context. |
| `core/context-clear-permissions!` | [context] | Clears all granted permissions. |
| `core/context-cookies` | [context] | Returns all cookies in the context. |
| `core/context-grant-permissions!` | [context permissions] | Grants permissions to the context. |
| `core/context-pages` | [context] | Returns all pages in a context. |
| `core/context-route-from-har!` | [context har] \| [context har route-opts] | Routes requests in the context from a HAR file. Replays recorded responses |
| `core/context-route-web-socket!` | [context pattern handler] | Registers a handler for WebSocket connections matching a URL pattern |
| `core/context-save-storage-state!` | [context path] | Saves the storage state (cookies, localStorage) to a file. |
| `core/context-set-default-navigation-timeout!` | [context timeout] | Sets the default navigation timeout. |
| `core/context-set-default-timeout!` | [context timeout] | Sets the default timeout for context operations. |
| `core/context-set-extra-http-headers!` | [context headers] | Sets extra HTTP headers for all requests in the context. |
| `core/context-set-offline!` | [context offline] | Sets the context to offline or online mode. |
| `core/context-storage-state` | [context] | Returns the storage state (cookies, localStorage) as a JSON string. |


### Snapshot with Refs

The snapshot system walks the DOM and assigns numbered refs (`e1`, `e2`, etc.) to interactive and meaningful elements:

```clojure
(spel/goto "https://example.com")

;; Get accessibility snapshot with refs
(def snap (spel/snapshot))
(:tree snap)
;; => "- heading \"Example Domain\" [@e1] [level=1]\n- link \"More information...\" [@e2]"
(:refs snap)
;; => {"e1" {:role "heading" :name "Example Domain" :tag "h1" :bbox {:x 0 :y 0 :width 500 :height 40}}
;;     "e2" {:role "link" :name "More information..." :tag "a" :bbox {:x 10 :y 100 :width 200 :height 20}}}

;; Use refs directly in ANY spel/ function — @eN and eN both work
(spel/click "@e2")
(spel/fill "@e3" "hello@example.com")
(spel/hover "@e1")
(spel/text "@e1")            ;; read text content of ref
(spel/visible? "@e1")        ;; check visibility of ref
(spel/highlight "@e1")       ;; highlight ref element for debugging
(spel/assert-visible "@e1")  ;; assert ref is visible

;; CSS selectors still work as before
(spel/click "button.submit")
(spel/text "h1")
```

### Page Annotation

Inject visual overlays (bounding boxes, ref badges, dimension labels) onto the page for **visible elements only**:

```clojure
;; Via CLI:
;;   spel annotate              — inject overlays for visible elements
;;   spel unannotate            — remove overlays
;;   spel screenshot out.png    — capture (with or without overlays)

;; Via Clojure API:
(def snap (spel/snapshot))
(annotate/inject-overlays! *page* (:refs snap))   ;; overlays now visible on page
;; ... inspect in headed mode, take screenshots, etc.
(annotate/remove-overlays! *page*)                 ;; clean up

;; One-shot convenience (inject → screenshot → cleanup):
(spel/save-annotated-screenshot! (:refs snap) "annotated.png")
```

### Daemon Architecture

- Background process keeps browser alive between CLI invocations
- Listens on Unix domain socket (`/tmp/spel-{session}.sock`)
- PID file at `/tmp/spel-{session}.pid`
- JSON protocol over socket (newline-delimited)
- Auto-starts on first CLI command if not running
- `close` command shuts down browser + daemon + cleans up files

---

## GitHub Actions CI/CD

Reference workflows for testing, reporting, native image builds, and deployment.

### CI — Tests + Lint + Native Image (`ci.yml`)

Multi-platform CI (Linux, macOS, Windows) that runs tests, lints, builds native images, and uploads binaries as artifacts.

```yaml
name: CI

on:
  push:
    branches: ["main"]
  pull_request:
    branches: ["main"]

permissions:
  contents: read

jobs:
  test:
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            artifact: spel-dev-linux-amd64
          - os: macos-latest
            artifact: spel-dev-macos-arm64
          - os: windows-latest
            artifact: spel-dev-windows-amd64

    runs-on: ${{ matrix.os }}
    name: CI (${{ matrix.os }})
    defaults:
      run:
        shell: bash
    env:
      # Normalize Playwright browser path across all OSes (macOS default differs)
      PLAYWRIGHT_BROWSERS_PATH: ~/.cache/ms-playwright

    steps:
      - uses: actions/checkout@v4

      - name: Setup GraalVM
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: 'graalvm'

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - uses: clojure-lsp/setup-clojure-lsp@v1
        with:
          clojure-lsp-version: 2025.11.28-12.47.43

      - name: Cache Clojure deps
        uses: actions/cache@v4
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
            ~/.clojure/.cpcache
          key: ${{ runner.os }}-ci-${{ hashFiles('deps.edn') }}
          restore-keys: |
            ${{ runner.os }}-ci-

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('deps.edn') }}
          restore-keys: |
            playwright-${{ runner.os }}-

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      - name: Lint (clojure-lsp)
        if: runner.os == 'Linux'
        run: clojure-lsp diagnostics --raw

      - name: Run tests
        run: clojure -M:test

      - name: Build native image
        run: clojure -T:build native-image

      - name: CLI smoke tests
        run: |
          chmod +x target/spel
          target/spel --help
          target/spel version

      - name: Upload binary
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: target/spel
```

**Key details:**
- `PLAYWRIGHT_BROWSERS_PATH` must be normalized — macOS uses a different default path than Linux
- Browser install uses the Java driver CLI (`CLI/main`) so versions match the library exactly
- GraalVM 25 + `graalvm` distribution for native-image
- Lint runs only on Linux (one platform is sufficient)

### Allure Report to GitHub Pages (`allure.yml`)

Runs tests with Allure reporter, generates HTML report with embedded Playwright traces, assembles a multi-build landing page, and deploys to GitHub Pages.

**Version Badges:**
The landing page displays version badges for each build:
- **Green "release" badge** for tagged commits (e.g., `v0.3.0`)
- **Yellow "candidate" badge** for untagged commits (e.g., `v0.3.1-candidate`)

Version is read from `resources/SPEL_VERSION` file and matched against git tags. The workflow automatically detects version and badge type, stores them in `builds-meta.json` and `builds.json`, and renders them in the landing page template.

```yaml
name: Allure Report

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

env:
  PAGES_BASE_URL: https://<org>.github.io/<repo>
  MAX_REPORTS: 10

jobs:
  report:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Cache Clojure deps
        uses: actions/cache@v4
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
            ~/.clojure/.cpcache
          key: allure-${{ hashFiles('deps.edn') }}

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      # Allure 3 uses .allure-history.jsonl for run history.
      # Cache it between builds so trend graphs and history work.
      - name: Restore Allure history
        uses: actions/cache/restore@v4
        with:
          path: .allure-history.jsonl
          key: allure-history-jsonl-${{ github.run_number }}
          restore-keys: allure-history-jsonl-

      # Restore previous per-build reports so the landing page accumulates.
      - name: Restore previous reports
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/restore@v4
        with:
          path: gh-pages-site
          key: allure-site-${{ github.run_number }}
          restore-keys: allure-site-

      - name: Run tests with Allure reporter
        id: tests
        env:
          LAZYTEST_ALLURE_LOGO: logo.svg
          FULL_MSG: ${{ github.event.head_commit.message }}
        run: |
          FIRST_LINE=$(echo "$FULL_MSG" | head -n1 | cut -c1-100)
          export LAZYTEST_ALLURE_REPORT_NAME="#${{ github.run_number }} · $(echo '${{ github.sha }}' | cut -c1-8) · ${FIRST_LINE}"
          clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
        continue-on-error: true

      # Inject the GitHub Pages URL and commit metadata into the latest
      # history entry so the landing page can link back to each report.
      - name: Inject report URL and commit info into history
        if: github.ref == 'refs/heads/main'
        env:
          REPORT_URL: ${{ env.PAGES_BASE_URL }}/${{ github.run_number }}
          COMMIT_SHA: ${{ github.sha }}
          COMMIT_MSG: ${{ github.event.head_commit.message }}
          RUN_NUMBER: ${{ github.run_number }}
        run: |
          if [ -f .allure-history.jsonl ]; then
            python3 -c "
          import json, os
          report_url = os.environ['REPORT_URL']
          sha = os.environ.get('COMMIT_SHA', '')[:8]
          msg = os.environ.get('COMMIT_MSG', '').split('\n')[0][:100]
          run = os.environ.get('RUN_NUMBER', '')
          name = f'#{run} · {sha} · {msg}'
          lines = open('.allure-history.jsonl').read().strip().split('\n')
          result = []
          for i, line in enumerate(lines):
              if not line.strip():
                  continue
              entry = json.loads(line)
              if i == len(lines) - 1:
                  entry['url'] = report_url
                  entry['name'] = name
                  for tid in entry.get('testResults', {}):
                      entry['testResults'][tid]['url'] = report_url
              result.append(json.dumps(entry, separators=(',', ':')))
          with open('.allure-history.jsonl', 'w') as f:
              f.write('\n'.join(result) + '\n')
          "
          fi

      # Assemble a multi-build site:
      #   gh-pages-site/
      #     index.html          — landing page with build list
      #     builds.json         — metadata for JS rendering
      #     latest/index.html   — redirect to newest report
      #     <run_number>/       — each Allure HTML report
      - name: Assemble site with per-build reports
        if: github.ref == 'refs/heads/main'
        env:
          RUN_NUMBER: ${{ github.run_number }}
          COMMIT_SHA: ${{ github.sha }}
          COMMIT_MSG: ${{ github.event.head_commit.message }}
          COMMIT_TS: ${{ github.event.head_commit.timestamp }}
          TEST_PASSED: ${{ steps.tests.outcome == 'success' }}
          REPO_URL: ${{ github.server_url }}/${{ github.repository }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          RUN="${{ github.run_number }}"
          mkdir -p gh-pages-site
          cp -r allure-report "gh-pages-site/${RUN}"

          cd gh-pages-site

          # Prune oldest reports beyond MAX_REPORTS
          DIRS=$(ls -1d [0-9]* 2>/dev/null | sort -n)
          COUNT=$(echo "$DIRS" | grep -c .)
          if [ "$COUNT" -gt "$MAX_REPORTS" ]; then
            REMOVE=$((COUNT - MAX_REPORTS))
            echo "$DIRS" | head -n "$REMOVE" | while read -r dir; do
              rm -rf "$dir"
            done
          fi

          # Generate builds.json for the landing page
          BUILDS_META="builds-meta.json"
          [ -f "$BUILDS_META" ] || echo '{}' > "$BUILDS_META"

          python3 -c "
          import json, os, time
          meta_file = '$BUILDS_META'
          meta = json.load(open(meta_file))
          run = os.environ['RUN_NUMBER']
          sha = os.environ.get('COMMIT_SHA', '')
          msg = os.environ.get('COMMIT_MSG', '').split('\n')[0]
          ts_str = os.environ.get('COMMIT_TS', '')
          ts = int(time.time() * 1000)
          if ts_str:
              from datetime import datetime
              try:
                  dt = datetime.fromisoformat(ts_str.replace('Z', '+00:00'))
                  ts = int(dt.timestamp() * 1000)
              except Exception:
                  pass
          passed = os.environ.get('TEST_PASSED', 'false') == 'true'
          repo_url = os.environ.get('REPO_URL', '')
          run_url = os.environ.get('RUN_URL', '')
          meta[run] = {'sha': sha, 'message': msg, 'timestamp': ts, 'passed': passed, 'repo_url': repo_url, 'run_url': run_url}
          dirs = sorted([d for d in os.listdir('.') if d.isdigit()], key=int, reverse=True)
          pruned = {k: v for k, v in meta.items() if k in dirs}
          json.dump(pruned, open(meta_file, 'w'), separators=(',', ':'))
          builds = []
          for d in dirs:
              entry = pruned.get(d, {})
              builds.append({'run': d, 'sha': entry.get('sha', ''), 'message': entry.get('message', ''), 'timestamp': entry.get('timestamp', 0), 'passed': entry.get('passed', True), 'repo_url': entry.get('repo_url', ''), 'run_url': entry.get('run_url', '')})
          json.dump(builds, open('builds.json', 'w'), separators=(',', ':'))
          "

          # Landing page + /latest redirect
          cp ../resources/allure-index.html index.html
          mkdir -p latest
          cat > latest/index.html <<EOF
          <!DOCTYPE html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=../${RUN}/"></head><body></body></html>
          EOF
          cd ..

      - name: Cache Allure history
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/save@v4
        with:
          path: .allure-history.jsonl
          key: allure-history-jsonl-${{ github.run_number }}

      - name: Cache site archive
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/save@v4
        with:
          path: gh-pages-site
          key: allure-site-${{ github.run_number }}

      - name: Upload Pages artifact
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-pages-artifact@v3
        with:
          path: gh-pages-site

      - name: Deploy to GitHub Pages
        if: github.ref == 'refs/heads/main'
        id: deployment
        uses: actions/deploy-pages@v4
```

**Key details:**
- **History**: `.allure-history.jsonl` is cached between builds — Allure 3 uses this for trend graphs and run history
- **Multi-build site**: Each run gets its own subdirectory (`gh-pages-site/<run_number>/`), pruned to `MAX_REPORTS`
- **Landing page**: `allure-index.html` renders `builds.json` — shows commit SHA (clickable to repo), date, pass/fail status, and links to each report
- **`/latest` redirect**: `gh-pages-site/latest/index.html` meta-refreshes to the newest report number
- **`continue-on-error: true`**: Test failures don't block report generation — the report shows what failed
- **Report naming**: `#<run> · <sha8> · <commit msg first line>` for clear identification in Allure history

### Native Image Build + Release (`native-image.yml`)

Cross-platform native image build with automatic GitHub Release on tags.

```yaml
name: Native Image Build

on:
  push:
    branches: [main]
    tags: ['v*']

permissions:
  contents: write

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            arch: amd64
            artifact: spel-linux-amd64
          - os: ubuntu-24.04-arm
            arch: arm64
            artifact: spel-linux-arm64
          - os: macos-latest
            arch: arm64
            artifact: spel-macos-arm64
          - os: windows-latest
            arch: amd64
            artifact: spel-windows-amd64

    runs-on: ${{ matrix.os }}
    defaults:
      run:
        shell: bash
    env:
      PLAYWRIGHT_BROWSERS_PATH: ~/.cache/ms-playwright

    steps:
      - uses: actions/checkout@v4

      - name: Setup GraalVM
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: 'graalvm'

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('deps.edn') }}

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      - name: Build uberjar + native image
        run: |
          clojure -T:build uberjar
          if [[ "$RUNNER_OS" == "Windows" ]]; then
            "$GRAALVM_HOME/bin/native-image.cmd" -jar target/spel-standalone.jar -o target/spel
          else
            native-image -jar target/spel-standalone.jar -o target/spel
          fi

      - name: CLI smoke tests
        run: |
          chmod +x target/spel || true
          target/spel --help
          target/spel version

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: target/spel*

  release:
    needs: build
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          path: artifacts
      - name: Create Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            artifacts/spel-linux-amd64/spel-linux-amd64
            artifacts/spel-linux-arm64/spel-linux-arm64
            artifacts/spel-macos-arm64/spel-macos-arm64
            artifacts/spel-windows-amd64/spel-windows-amd64.exe
```

**Key details:**
- Linux arm64 uses `ubuntu-24.04-arm` runner (GitHub's ARM runner)
- Windows native-image uses `$GRAALVM_HOME/bin/native-image.cmd` (not `native-image` directly)
- Release job downloads all platform artifacts and creates a GitHub Release with binaries attached
- Triggered on `v*` tags (e.g. `git tag v0.1.0 && git push --tags`)

### Deploy to Clojars (`deploy.yml`)

Publishes JAR to Clojars and creates a GitHub Release with auto-generated changelog on version tags.

```yaml
name: Deploy to Clojars

on:
  push:
    tags:
      - "v*"

permissions:
  contents: write

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Build & Deploy to Clojars
        env:
          VERSION: ${{ github.ref_name }}
          CLOJARS_USERNAME: <your-deployer>
          CLOJARS_PASSWORD: ${{ secrets.CLOJARS_DEPLOY_TOKEN }}
        run: clojure -T:build deploy

      - name: Update README + CHANGELOG
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          sed -i "s/{:mvn\/version \"[^\"]*\"}/{:mvn\/version \"$VERSION\"}/g" README.md
          # ... update CHANGELOG.md with git log between tags ...
          git config user.name "deployer"
          git config user.email "deploy@example.com"
          git add README.md CHANGELOG.md
          git commit -m "release: update for ${{ github.ref_name }}" || true
          git push origin HEAD:main
```

**Key details:**
- `fetch-depth: 0` — full git history needed for changelog generation between tags
- `CLOJARS_DEPLOY_TOKEN` stored as GitHub secret
- Auto-updates `README.md` version string and `CHANGELOG.md` after deploy
- Pushes version bump commit back to `main`

### Prerequisites for GitHub Pages

Before the Allure workflow can deploy:

1. **Enable GitHub Pages** in repo settings → Pages → Source: **GitHub Actions**
2. **Create environment** named `github-pages` (Settings → Environments)
3. **Set `PAGES_BASE_URL`** in the workflow env to `https://<org>.github.io/<repo>`
4. **Landing page**: Place `allure-index.html` in `resources/` — it renders `builds.json` client-side with build list, commit links, pass/fail badges, and date grouping

---

## Rules

| Rule | Detail |
|------|--------|
| **No per-file reflection warnings** | Handled globally by build tooling |
| **No `clj-kondo` CLI** | All linting via `clojure-lsp diagnostics --raw` |
| **Anomalies not exceptions** | Use `com.blockether.anomaly` pattern |
| **Error type keywords** | `:playwright.error/timeout`, `:playwright.error/target-closed`, etc. |
| **Exact string assertions** | Never use substring matching unless `contains-text` |
| **`new-page-from-context`** | For creating pages from BrowserContext (not `new-page`) |
| **Roles require** | Always `[com.blockether.spel.roles :as role]` in requires. Use `role/button`, `role/heading`, etc. |
| **`with-*` macros** | Always use for resource cleanup (never manual try/finally) |
| **Prefer `--eval` over CLI** | For multi-step browser automation, use `spel --eval '<code>'` or `spel --eval script.clj` instead of chaining standalone CLI commands. `--eval` gives full Clojure composition (loops, conditionals, variables, error handling) in a single browser session. Use standalone CLI commands only for quick one-off actions (e.g. `spel open`, `spel screenshot`). Pipe support: `echo '(code)' \| spel --eval --stdin` for LLM-generated scripts. |
| **Never use `sleep` / `wait-for-timeout`** | `spel/sleep` and `page/wait-for-timeout` are **anti-patterns**. They introduce flaky timing dependencies and slow down tests. Instead, use event-driven waits: `spel/wait-for` (element condition), `spel/wait-for-url` (navigation), `spel/wait-for-function` (JS predicate), `spel/wait-for-load` (load state), `locator/wait-for` (locator condition). The only acceptable use of sleep is waiting for an animation or transition with no observable state change — and even then, prefer `wait-for-function` with a CSS/JS check. |
| **Console auto-captured in `--eval`** | `spel --eval` automatically captures browser console messages (`console.log`, `console.warn`, `console.error`) and page errors during evaluation. They are printed to **stderr** after the eval result (format: `[console.TYPE] text`, `[page-error] message`). This is essential for debugging — if something fails silently in the browser, check stderr. In library code, use `page/on-console` and `page/on-page-error` handlers directly. |
| **SPA navigation: wait after interactions** | In Single Page Applications, elements may not exist in the DOM until their parent is expanded, a tab is clicked, or a route transition completes. After any interaction that changes the DOM (click, navigate, tab switch), always `spel/wait-for` the expected child element before interacting with it. Never assume elements appear synchronously. Pattern: `(spel/click "parent") → (spel/wait-for "child") → (spel/click "child")`. |
| **Prefer `networkidle` for SPA initial load** | When navigating to a SPA (Single Page Application) that renders client-side (React, Allure, etc.), the default load state (`load`) fires before JS frameworks finish rendering. Use `(spel/wait-for-load "networkidle")` after `spel/goto` to wait until all network requests settle. Then use `spel/wait-for-function` for app-specific readiness checks (e.g. `"() => document.body.innerText.length > 100"`). Pattern: `(spel/goto url) (spel/wait-for-load "networkidle") (spel/wait-for-function "() => ...")`. For non-SPA pages, the default `load` state is sufficient. |
| **Reports via `report->html` / `report->pdf`** | When asked to produce a report, audit, review, or visual summary, **always** use `spel/report->html` and `spel/report->pdf` from `--eval`. Build entries as typed maps (`:screenshot`, `:section`, `:observation`, `:issue`, `:good`, `:table`, `:meta`, `:text`, `:html`), then render. Save HTML with `(spit path (spel/report->html entries opts))` — no page needed. For PDF, use `(spel/report->pdf entries {:path "file.pdf"})`. Never hand-craft HTML strings for reports — the report builder handles layout, CSS, page breaks, and base64 image encoding. |
| **Delegation: ALWAYS pass `load_skills=["spel"]`** | When delegating ANY browser-related task to a subagent (via `task()`, Sisyphus-Junior, or any other agent), you **MUST** include `load_skills=["spel"]` in the delegation call. Subagents are **stateless** — they have zero knowledge of spel's API, CLI, SCI functions, or conventions unless they load this skill. Without it, agents will attempt to use generic Playwright MCP tools (which are disabled in this project) or hallucinate APIs. This applies to ALL delegations involving: browser automation, screenshots, E2E tests, Allure reports, scraping, form filling, visual audits, or anything that touches a browser. No exceptions. |

---

### API Discovery in `--eval` Mode

Use `spel/help` and `spel/source` to explore the eval API at runtime:

| Command | What it does |
|---------|-------------|
| `(spel/help)` | List all namespaces with function counts |
| `(spel/help "spel")` | List all functions in a namespace (table: name, arglists, description) |
| `(spel/help "click")` | Search across all namespaces by function name or description |
| `(spel/help "spel/click")` | Show details for a specific function (arglists, description, backing library function) |
| `(spel/source "spel/click")` | Show the SCI wrapper source code and which library function it delegates to |
| `(spel/source "goto")` | Search by bare name — shows source if unique match, lists candidates if multiple |

These are the **canonical** way to discover and understand the eval API. Prefer `spel/help` over reading this SKILL file when working in `--eval` mode.

---

## CLI Entry Points

The `spel` binary is the primary CLI interface:

| Command | Purpose |
|---------|---------|
| `spel <command>` | Browser automation CLI (100+ commands) |
| `spel codegen` | Record and transform browser sessions to Clojure |
| `spel init-agents` | Scaffold E2E testing agents (`--loop=opencode\|claude\|vscode`) |
