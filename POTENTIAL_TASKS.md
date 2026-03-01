# POTENTIAL TASKS — Agent-Browser Features spel Doesn't Have

> Comprehensive Pareto analysis of ~30 agent-browser commands/features not present in spel.
> Each feature is assessed for fit, effort, impact, and implementation path.
> Generated March 1, 2026 against spel 0.5.0 and agent-browser 0.15.1.

---

## Priority Matrix

| # | Feature | Tier | Effort | Impact | Pareto | Verdict |
|---|---------|------|--------|--------|--------|---------|
| 1 | `setcontent` (set page HTML) | 🟢 T1 | 10% | 65% | **Best** | Do |
| 2 | `innertext` / `innerhtml` | 🟢 T1 | 5% | 30% | **Best** | Assess (may duplicate) |
| 3 | `tap` (touch tap) | 🟢 T1 | 10% | 50% | **Best** | Do |
| 4 | `selectall` (select all text) | 🟢 T1 | 5% | 25% | **Best** | Do |
| 5 | `bringtofront` | 🟢 T1 | 5% | 30% | **Best** | Do |
| 6 | `dispatch` (custom events) | 🟢 T1 | 10% | 50% | **Best** | Do |
| 7 | `multiselect` | 🟢 T1 | 10% | 45% | **Best** | Do |
| 8 | `setvalue` (bypass events) | 🟢 T1 | 10% | 40% | **Best** | Do |
| 9 | `waitfordownload` | 🟢 T1 | 15% | 45% | **Best** | Do |
| 10 | `clipboard` operations | 🟢 T1 | 15% | 50% | **Best** | Do |
| 11 | `permissions` | 🟢 T1 | 10% | 40% | **Best** | Do |
| 12 | `timezone` emulation | 🟢 T1 | 10% | 40% | **Best** | Do |
| 13 | `locale` emulation | 🟢 T1 | 10% | 40% | **Best** | Do |
| 14 | Content injection (`addscript`/`addstyle`/`addinitscript`) | 🟡 T2 | 25% | 55% | Medium | Do |
| 15 | `expose` function | 🟡 T2 | 30% | 35% | Medium | Do |
| 16 | `responsebody` | 🟡 T2 | 30% | 50% | Medium | Do |
| 17 | `styles` (computed CSS) | 🟡 T2 | 30% | 45% | Medium | Do |
| 18 | `evalhandle` (JS handle) | 🟡 T2 | 25% | 30% | Medium | SCI only |
| 19 | `pause` (debugger) | 🟡 T2 | 20% | 25% | Medium | Do |
| 20 | Diff engine (3 commands) | 🟡 T2 | 50% | 60% | Medium | Do |
| 21 | HAR recording | 🟡 T2 | 25% | 40% | Medium | Do |
| 22 | Video management (5 commands) | 🟡 T2 | 40% | 35% | Medium | Partial |
| 23 | Config file (`spel.json`) | 🟡 T2 | 35% | 45% | Medium | Do |
| 24 | Domain allowlist | 🟡 T2 | 30% | 40% | Medium | Do |
| 25 | Auth vault (5 commands) | 🔴 T3 | 60% | 35% | Low | Defer |
| 26 | Action policy / confirmation | 🔴 T3 | 65% | 30% | Low | Skip |
| 27 | Screencast / streaming | 🔴 T3 | 70% | 20% | Low | Skip |
| 28 | Input injection (CDP-level) | 🔴 T3 | 50% | 15% | Low | Skip |
| 29 | Profiler (DevTools) | 🔴 T3 | 40% | 20% | Low | Defer |
| 30 | Window management (`window_new`) | 🔴 T3 | 15% | 10% | Low | Skip |
| 31 | iOS Simulator | 🔴 T3 | 80% | 15% | Low | See TASK-010 |
| 32 | Cloud browser providers | 🔴 T3 | 60% | 20% | Low | See TASK-011 |

---

## Tier 1: High Pareto (≤30% effort, ≥25% impact)

These are quick wins. Each can be implemented in under a day, most in under an hour. Do them first.

---

### PT-01: `setcontent` — Set Page HTML Directly

**What it is**
Replaces the entire page content with raw HTML. Useful for testing components in isolation, rendering email templates, or setting up test fixtures without a server.

**How it works in agent-browser**
```bash
agent-browser setcontent '<html><body><h1>Hello</h1></body></html>'
```
- Params: `html` (string, required)
- Returns: `{url, title}` after content is set
- The page URL becomes `about:blank` (or stays the same if already loaded)

**Does it make sense for spel?**
Yes. Playwright Java has `page.setContent(html)` directly. This is a one-liner wrapper. Useful for testing, prototyping, and fixture setup. Worth having as both a CLI command and SCI function.

**Where to implement**
Library + SCI + CLI (all three layers).

**Pareto assessment**
10% effort / 65% impact. Trivial to implement, broadly useful.

**Implementation guidance**
- `page.clj`: Add `(defn set-content [page html & [opts]] (.setContent page html ...))`. Already has `content` (which gets HTML), this is the setter.
- `daemon.clj`: Add `(defmethod handle-cmd "setcontent" [_ {:strs [html]}] ...)` calling `page/set-content`.
- `native.clj`: Route `setcontent <html>` to the daemon handler.
- `cli.clj`: Format output as `{:url ... :title ...}`.
- `sci_env.clj`: Expose as `(page/set-content html)` in the SCI environment.
- Playwright Java API: `Page.setContent(String html, Page.SetContentOptions)`.

**Acceptance criteria**
1. `spel setcontent '<h1>Test</h1>'` sets the page HTML and returns the title
2. `spel setcontent '<h1>Test</h1>' && spel get text h1` returns "Test"
3. `--json` output: `{"url": "about:blank", "title": ""}`
4. Works with `--eval`: `(page/set-content "<h1>Hi</h1>")`
5. Empty string input sets blank page (no error)
6. Large HTML (>1MB) works without truncation

**How to test**
- `cli_integration_test.clj`: Send `setcontent` command, verify page content via `get_text`.
- `test-cli.sh`: `"$SPEL" setcontent '<h1>Hello</h1>' && "$SPEL" get text h1 | assert_contains "Hello"`.

**Verification steps**
1. `spel open about:blank`
2. `spel setcontent '<html><body><h1>Injected</h1><p>Content here</p></body></html>'`
3. `spel get text h1` returns "Injected"
4. `spel snapshot` shows the injected content tree
5. `spel screenshot /tmp/setcontent.png` shows rendered HTML

---

### PT-02: `innertext` / `innerhtml` — Dedicated Text/HTML Getters

**What it is**
Get the `innerText` or `innerHTML` of an element by selector. agent-browser has these as separate commands.

**How it works in agent-browser**
```bash
agent-browser innertext "#main"
# Returns: "Hello World\nSome paragraph text"

agent-browser innerhtml "#main"
# Returns: "<h1>Hello World</h1><p>Some paragraph text</p>"
```
- Params: `selector` (string, required)
- `innertext` returns the rendered text (respects CSS visibility, collapses whitespace)
- `innerhtml` returns raw HTML markup

**Does it make sense for spel?**
Partially. spel already has `get text <sel>` (which calls `locator.textContent()`) and `get html <sel>` (which calls `locator.innerHTML()`). The question is whether `innerText` differs meaningfully from `textContent`.

Key difference: `textContent` returns all text including hidden elements and `<script>` tags. `innerText` returns only rendered, visible text. For AI agents, `innerText` is usually what you want.

Verdict: Add `innerText` as an option or alias. `innerHTML` is already covered by `get html`.

**Where to implement**
CLI alias + daemon handler update. Possibly add `--inner` flag to `get text`.

**Pareto assessment**
5% effort / 30% impact. Tiny change, but the `textContent` vs `innerText` distinction matters for agents parsing page content.

**Implementation guidance**
- `daemon.clj`: Modify `handle-cmd "get_text"` to accept a `{:strs [selector inner]}` param. When `inner` is true, use `locator.evaluate("el => el.innerText")` instead of `locator.textContent()`.
- `native.clj`: Add `--inner` flag to `get text`, or add `innertext` as a standalone command alias.
- Alternative: Just add `innertext` and `innerhtml` as aliases that route to the same handler with different modes.
- Playwright Java API: No direct `innerText()` on Locator. Use `locator.evaluate("el => el.innerText")`.

**Acceptance criteria**
1. `spel innertext "#main"` returns visible text only (no hidden elements)
2. `spel innerhtml "#main"` returns HTML markup (same as `get html`)
3. Hidden elements (`display:none`) are excluded from `innertext` but included in `get text`
4. `--json` output: `{"text": "..."}` or `{"html": "..."}`
5. Works with ref selectors: `spel innertext @e3`

**How to test**
- `cli_integration_test.clj`: Set content with hidden elements, verify `innertext` excludes them while `get_text` includes them.
- `test-cli.sh`: `"$SPEL" setcontent '<div id="t"><span>visible</span><span style="display:none">hidden</span></div>' && "$SPEL" innertext "#t" | assert_contains "visible"`.

**Verification steps**
1. `spel setcontent '<div><span>A</span><span style="display:none">B</span></div>'`
2. `spel get text div` returns "AB" (textContent includes hidden)
3. `spel innertext div` returns "A" (innerText excludes hidden)

---

### PT-03: `tap` — Touch Tap Event

**What it is**
Fires a touch tap event (touchstart + touchend) on an element instead of mouse click events. Critical for mobile-optimized sites that listen for touch events specifically.

**How it works in agent-browser**
```bash
agent-browser tap "#mobile-menu"
```
- Params: `selector` (string, required)
- Fires `touchstart` then `touchend` at the element's center
- Different from `click` which fires `mousedown`, `mouseup`, `click`

**Does it make sense for spel?**
Yes. Playwright Java has `Locator.tap()` directly. Mobile-first sites often have different behavior for touch vs mouse. When using `set device` for mobile emulation, `tap` is the correct interaction primitive.

**Where to implement**
Library + SCI + CLI (all three layers).

**Pareto assessment**
10% effort / 50% impact. Direct Playwright API mapping. High value for mobile testing workflows.

**Implementation guidance**
- `page.clj`: Add `(defn tap [page selector] (.tap (locator page selector)))`. Or in `locator.clj`.
- `daemon.clj`: Add `(defmethod handle-cmd "tap" [_ {:strs [selector]}] ...)`.
- `native.clj`: Route `tap <sel>` to daemon.
- `sci_env.clj`: Expose as `(page/tap selector)` or `(locator/tap loc)`.
- Playwright Java API: `Locator.tap(Locator.TapOptions)`.
- Note: Tap requires the browser context to have `hasTouch: true`. When `set device` is used with a mobile device, this is automatic. Otherwise, need to enable it.

**Acceptance criteria**
1. `spel tap @e3` fires touch events on the referenced element
2. `spel tap "#button"` works with CSS selectors
3. Error if browser context doesn't have touch enabled: `"Error: tap requires touch-enabled context. Use 'set device' or launch with --has-touch"`
4. Works after `set device "iphone 14"` without extra config
5. `--json` output: `{"tapped": true, "selector": "..."}`

**How to test**
- `cli_integration_test.clj`: Set up a page with touch event listener, tap, verify event fired via JS eval.
- `test-cli.sh`: `"$SPEL" set device "iphone 14" && "$SPEL" setcontent '<button id="b" ontouchstart="this.textContent=\x27tapped\x27">tap me</button>' && "$SPEL" tap "#b" && "$SPEL" get text "#b" | assert_contains "tapped"`.

**Verification steps**
1. `spel set device "iphone 14"`
2. `spel setcontent '<button id="b" ontouchstart="this.textContent=&apos;touched&apos;">press</button>'`
3. `spel tap "#b"`
4. `spel get text "#b"` returns "touched"

---

### PT-04: `selectall` — Select All Text in Element

**What it is**
Selects all text within an element. Equivalent to focusing the element and pressing Ctrl+A (or Cmd+A on macOS).

**How it works in agent-browser**
```bash
agent-browser selectall "#editor"
```
- Params: `selector` (string, required)
- Selects all text content within the targeted element
- Useful before copy, delete, or overwrite operations

**Does it make sense for spel?**
Marginally. spel already has `fill` (which clears and types), `clear` (which clears), and `press "Control+a"` (which selects all). This is a convenience wrapper. Worth adding as a thin alias since it's trivial.

**Where to implement**
CLI alias only. Route to `focus` + `press Control+a` internally.

**Pareto assessment**
5% effort / 25% impact. Two-line implementation. Minor convenience.

**Implementation guidance**
- `daemon.clj`: Add handler that calls `focus` then `press "Control+a"` (or `Meta+a` on macOS).
- Actually simpler: `(defmethod handle-cmd "selectall" [_ {:strs [selector]}] (let [loc (resolve-locator selector)] (.focus loc) (.press loc "Control+a") {:selected true}))`.
- `native.clj`: Route `selectall <sel>`.
- Playwright Java API: `Locator.focus()` + `Locator.press("Control+a")`.

**Acceptance criteria**
1. `spel selectall "#input"` selects all text in the input
2. Works with textareas and contenteditable elements
3. Subsequent `press Delete` clears the selection
4. Works with ref selectors: `spel selectall @e5`

**How to test**
- `cli_integration_test.clj`: Fill an input, selectall, type replacement text, verify new value.
- `test-cli.sh`: `"$SPEL" setcontent '<input id="i" value="hello">' && "$SPEL" selectall "#i" && "$SPEL" press Delete && "$SPEL" get value "#i" | assert_jq_eq '.value' '""'`.

**Verification steps**
1. `spel setcontent '<input id="i" value="hello world">'`
2. `spel selectall "#i"`
3. `spel type "#i" "replaced"`
4. `spel get value "#i"` returns "replaced" (not "hello worldreplaced")

---

### PT-05: `bringtofront` — Bring Page to Foreground

**What it is**
Brings the current page/tab to the foreground. Useful when working with multiple tabs or windows and you need to ensure a specific page is active and visible.

**How it works in agent-browser**
```bash
agent-browser bringtofront
```
- No params
- Brings the current page to the front of the window stack
- Returns: `{success: true}`

**Does it make sense for spel?**
Yes. When using multi-tab workflows (`tab new`, `tab 0`, `tab 1`), bringing a tab to front ensures screenshots and visual operations target the right page. Playwright Java has `Page.bringToFront()` directly.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
5% effort / 30% impact. One-liner Playwright call. Useful for multi-tab automation.

**Implementation guidance**
- `page.clj`: Add `(defn bring-to-front [page] (.bringToFront page))`.
- `daemon.clj`: `(defmethod handle-cmd "bringtofront" [_ _] (.bringToFront (current-page)) {:success true})`.
- `native.clj`: Route `bringtofront`.
- `sci_env.clj`: Expose as `(page/bring-to-front)`.

**Acceptance criteria**
1. `spel bringtofront` succeeds with `{success: true}`
2. After `tab new`, `tab 0`, `bringtofront`, the original tab is in front
3. Screenshots after `bringtofront` capture the correct tab
4. No error when only one tab exists

**How to test**
- `cli_integration_test.clj`: Open two tabs, switch, bring to front, verify active page.
- `test-cli.sh`: `"$SPEL" bringtofront --json | assert_jq '.success' 'true'`.

**Verification steps**
1. `spel open https://example.org`
2. `spel tab new https://example.com`
3. `spel tab 0`
4. `spel bringtofront`
5. `spel get title` returns the first tab's title

---

### PT-06: `dispatch` — Dispatch Custom DOM Events

**What it is**
Dispatches a custom DOM event on an element. Enables triggering events that aren't covered by standard Playwright actions: custom events, drag events, resize events, etc.

**How it works in agent-browser**
```bash
agent-browser dispatch "#canvas" "mousedown" '{"bubbles": true, "clientX": 100, "clientY": 200}'
```
- Params: `selector` (string), `event` (event name), `eventInit?` (JSON object with event properties)
- Creates and dispatches the event on the matched element
- Returns: `{dispatched: true}`

**Does it make sense for spel?**
Yes. Playwright Java has `Locator.dispatchEvent(type, eventInit)`. This fills a gap for custom web components, canvas interactions, and framework-specific events (React synthetic events, Vue custom events).

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
10% effort / 50% impact. Direct Playwright mapping. Unlocks a class of interactions that currently require `eval` with raw JS.

**Implementation guidance**
- `page.clj` or `locator.clj`: Add `(defn dispatch-event [page selector event-type event-init] (.dispatchEvent (locator page selector) event-type event-init))`.
- `daemon.clj`: `(defmethod handle-cmd "dispatch" [_ {:strs [selector event eventInit]}] ...)`. Parse `eventInit` from JSON string to map.
- `native.clj`: Route `dispatch <sel> <event> [eventInit-json]`.
- Playwright Java API: `Locator.dispatchEvent(String type, Object eventInit)`.

**Acceptance criteria**
1. `spel dispatch "#el" "click"` dispatches a click event
2. `spel dispatch "#el" "custom-event" '{"detail": {"key": "value"}}'` dispatches custom event with init
3. `spel dispatch @e3 "input"` works with ref selectors
4. `--json` output: `{"dispatched": true, "event": "custom-event"}`
5. Error on invalid selector: proper Playwright error message

**How to test**
- `cli_integration_test.clj`: Set up page with custom event listener, dispatch, verify handler ran.
- `test-cli.sh`: `"$SPEL" setcontent '<div id="d"></div>' && "$SPEL" eval "document.getElementById('d').addEventListener('ping', () => document.title = 'ponged')" && "$SPEL" dispatch "#d" "ping" && "$SPEL" get title | assert_contains "ponged"`.

**Verification steps**
1. `spel setcontent '<button id="b">0</button>'`
2. `spel eval "document.getElementById('b').addEventListener('myevent', () => { document.getElementById('b').textContent = 'fired' })"`
3. `spel dispatch "#b" "myevent"`
4. `spel get text "#b"` returns "fired"

---

### PT-07: `multiselect` — Select Multiple Dropdown Values

**What it is**
Selects multiple values in a `<select multiple>` dropdown. spel's existing `select` command picks a single value. This handles multi-select scenarios.

**How it works in agent-browser**
```bash
agent-browser multiselect "#colors" '["red", "blue", "green"]'
```
- Params: `selector` (string), `values[]` (array of option values)
- Selects all specified options in a multi-select element
- Returns: `{selected: ["red", "blue", "green"]}`

**Does it make sense for spel?**
Yes, but consider extending the existing `select` command instead of adding a new one. Playwright's `Locator.selectOption()` already accepts multiple values. spel's `select` handler may just need to accept an array.

**Where to implement**
Extend existing `select` command in daemon + CLI. Add SCI support.

**Pareto assessment**
10% effort / 45% impact. Likely just needs the existing `select` handler to accept multiple values.

**Implementation guidance**
- `daemon.clj`: The existing `handle-cmd "select"` already receives `{:strs [selector values]}`. Check if `values` is already an array or single string. If single, wrap in vector. Pass to `locator.selectOption(values)`.
- `native.clj`: Allow `select <sel> val1 val2 val3` (multiple positional args) or `select <sel> --values '["a","b"]'`.
- Playwright Java API: `Locator.selectOption(String[] values)` (already supports arrays).
- Alternative: Add `multiselect` as a separate command that explicitly takes an array. Clearer semantics.

**Acceptance criteria**
1. `spel multiselect "#colors" red blue green` selects multiple options
2. `spel multiselect @e5 "opt1" "opt2"` works with refs
3. Error on non-multi-select element: `"Error: element is not a multi-select"`
4. `--json` output: `{"selected": ["red", "blue"]}`
5. Existing `select` command unchanged (single value)

**How to test**
- `cli_integration_test.clj`: Create multi-select, select multiple values, verify via JS eval.
- `test-cli.sh`: `"$SPEL" setcontent '<select id="s" multiple><option value="a">A</option><option value="b">B</option><option value="c">C</option></select>' && "$SPEL" multiselect "#s" a c --json | assert_jq '.selected | length' '2'`.

**Verification steps**
1. `spel setcontent '<select id="s" multiple><option value="r">Red</option><option value="g">Green</option><option value="b">Blue</option></select>'`
2. `spel multiselect "#s" r b`
3. `spel eval "Array.from(document.getElementById('s').selectedOptions).map(o => o.value)"` returns `["r", "b"]`

---

### PT-08: `setvalue` — Set Input Value Bypassing Events

**What it is**
Sets an input's value property directly without triggering `input`, `change`, or `keydown` events. Unlike `fill` (which simulates typing and fires all events), `setvalue` is a raw property assignment.

**How it works in agent-browser**
```bash
agent-browser setvalue "#price" "99.99"
```
- Params: `selector` (string), `value` (string)
- Sets `element.value = value` directly via JS
- No events fired
- Returns: `{value: "99.99"}`

**Does it make sense for spel?**
Yes. There are legitimate cases where you need to set a value without triggering framework reactivity (React controlled components, Angular form validation, date pickers with custom event handling). Currently requires `eval "document.querySelector('#x').value = 'y'"`.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
10% effort / 40% impact. Simple JS eval wrapper with better ergonomics.

**Implementation guidance**
- `daemon.clj`: `(defmethod handle-cmd "setvalue" [_ {:strs [selector value]}] (let [loc (resolve-locator selector)] (.evaluate loc "el => el.value = arguments[0]" value) {:value value}))`. Or use `locator.evaluate()`.
- `native.clj`: Route `setvalue <sel> <value>`.
- `sci_env.clj`: Expose as `(page/set-value selector value)`.
- Playwright Java API: `Locator.evaluate("(el, v) => el.value = v", value)`.

**Acceptance criteria**
1. `spel setvalue "#input" "hello"` sets the value without firing events
2. `spel get value "#input"` returns "hello" after setvalue
3. Event listeners on the input are NOT triggered (verify via JS)
4. Works with refs: `spel setvalue @e3 "test"`
5. `--json` output: `{"value": "hello"}`

**How to test**
- `cli_integration_test.clj`: Set up input with event listener that sets a flag, use setvalue, verify flag was NOT set.
- `test-cli.sh`: `"$SPEL" setcontent '<input id="i" oninput="window._fired=true">' && "$SPEL" setvalue "#i" "silent" && "$SPEL" eval "window._fired" | assert_contains "null"`.

**Verification steps**
1. `spel setcontent '<input id="i" oninput="document.title=this.value">'`
2. `spel setvalue "#i" "stealth"`
3. `spel get value "#i"` returns "stealth"
4. `spel get title` does NOT return "stealth" (event wasn't fired)

---

### PT-09: `waitfordownload` — Wait for Download Event

**What it is**
Waits for a download to be triggered (by any means), then saves it. Unlike the existing `download` command (which clicks an element and waits), this just waits passively for any download event.

**How it works in agent-browser**
```bash
agent-browser waitfordownload --path ./output/ --timeout 10000
```
- Params: `path?` (save directory), `timeout?` (ms)
- Blocks until a download event occurs
- Returns: `{path: "./output/report.csv", suggestedFilename: "report.csv"}`

**Does it make sense for spel?**
Yes. The existing `download` command requires a selector to click. But sometimes downloads are triggered by JS timers, redirects, or other async mechanisms. A passive wait fills that gap. Also useful in `--eval` scripts where the download trigger is a complex sequence.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
15% effort / 45% impact. Playwright has `page.waitForDownload()` directly. Moderate value for automation scripts.

**Implementation guidance**
- `page.clj`: Add `(defn wait-for-download [page & [opts]] (.waitForDownload page))` with timeout option.
- `daemon.clj`: `(defmethod handle-cmd "waitfordownload" [_ {:strs [path timeout-ms]}] ...)`. Use `page.waitForDownload()`, then `download.saveAs(path)` if path provided.
- `native.clj`: Route `waitfordownload [--path <dir>] [--timeout <ms>]`.
- Playwright Java API: `Page.waitForDownload(Runnable)` or `Page.waitForDownload()`.

**Acceptance criteria**
1. `spel waitfordownload --path /tmp/` blocks until download, then saves
2. Returns filename and path: `{"path": "/tmp/file.csv", "suggestedFilename": "file.csv"}`
3. Timeout error after `--timeout` ms if no download occurs
4. Works without `--path` (returns download info without saving)
5. Works in `--eval`: `(page/wait-for-download {:path "/tmp/"})`

**How to test**
- `cli_integration_test.clj`: Set up page with timed download trigger, call waitfordownload, verify file saved.
- `test-cli.sh`: Difficult to test in bash (needs async download trigger). Test `--help` output at minimum.

**Verification steps**
1. `spel setcontent '<a id="dl" href="data:text/plain,hello" download="test.txt">download</a>'`
2. In one terminal: `spel waitfordownload --path /tmp/ --timeout 5000`
3. In another: `spel click "#dl"`
4. First terminal returns with `{"suggestedFilename": "test.txt", "path": "/tmp/test.txt"}`

---

### PT-10: `clipboard` — Clipboard Operations

**What it is**
Read from, write to, and paste from the system clipboard. Three operations: `copy` (write text to clipboard), `paste` (type text from clipboard into focused element), `read` (return clipboard contents).

**How it works in agent-browser**
```bash
agent-browser clipboard copy "Hello World"
agent-browser clipboard paste
agent-browser clipboard read
```
- Params: `operation` (copy/paste/read), `text?` (for copy)
- `copy`: writes text to clipboard
- `paste`: types clipboard content into focused element
- `read`: returns clipboard text
- Returns: `{content: "Hello World"}` for read

**Does it make sense for spel?**
Yes. Clipboard operations are common in testing (copy-paste workflows, clipboard API testing). Currently requires `eval` with `navigator.clipboard` API (which needs permissions). A dedicated command handles the permission grant automatically.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
15% effort / 50% impact. Needs permission handling, but Playwright supports clipboard via `browserContext.grantPermissions(["clipboard-read", "clipboard-write"])`.

**Implementation guidance**
- `daemon.clj`: Add handler with three sub-operations. For `copy`: use `page.evaluate("text => navigator.clipboard.writeText(text)", text)`. For `read`: `page.evaluate("() => navigator.clipboard.readText()")`. For `paste`: `page.keyboard().press("Control+v")` after copy.
- Grant clipboard permissions on context creation or lazily on first clipboard command.
- `native.clj`: Route `clipboard copy "text"`, `clipboard paste`, `clipboard read`.
- Playwright Java API: `BrowserContext.grantPermissions(List.of("clipboard-read", "clipboard-write"))`.

**Acceptance criteria**
1. `spel clipboard copy "hello"` writes to clipboard
2. `spel clipboard read` returns `{"content": "hello"}`
3. `spel clipboard paste` types clipboard content into focused input
4. Clipboard permissions are granted automatically (no manual setup)
5. Works across tabs within the same session

**How to test**
- `cli_integration_test.clj`: Copy text, read it back, verify match. Copy, focus input, paste, verify input value.
- `test-cli.sh`: `"$SPEL" clipboard copy "test123" && "$SPEL" clipboard read --json | assert_jq '.content' '"test123"'`.

**Verification steps**
1. `spel open about:blank`
2. `spel clipboard copy "clipboard test"`
3. `spel clipboard read` returns "clipboard test"
4. `spel setcontent '<input id="i">'`
5. `spel focus "#i"`
6. `spel clipboard paste`
7. `spel get value "#i"` returns "clipboard test"

---

### PT-11: `permissions` — Grant/Revoke Browser Permissions

**What it is**
Grants or revokes browser permissions like geolocation, notifications, camera, microphone. Avoids permission prompt dialogs that block automation.

**How it works in agent-browser**
```bash
agent-browser permissions --permissions geolocation notifications --grant true
agent-browser permissions --permissions camera --grant false
```
- Params: `permissions[]` (list of permission names), `grant` (boolean)
- Supported: geolocation, notifications, camera, microphone, clipboard-read, clipboard-write, etc.
- Returns: `{granted: ["geolocation", "notifications"]}`

**Does it make sense for spel?**
Yes. spel already handles geolocation via `set geo`, but there's no general permission API. Notifications, camera, and microphone permissions are needed for testing PWAs, video chat apps, and notification-heavy sites.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
10% effort / 40% impact. Direct Playwright API. Unblocks testing of permission-dependent features.

**Implementation guidance**
- `daemon.clj`: `(defmethod handle-cmd "permissions" [_ {:strs [permissions grant]}] (.grantPermissions context (into-array String permissions)) ...)`. For revoke: `.clearPermissions()`.
- `native.clj`: Route `permissions --grant geolocation notifications` and `permissions --revoke`.
- Playwright Java API: `BrowserContext.grantPermissions(List<String>)`, `BrowserContext.clearPermissions()`.

**Acceptance criteria**
1. `spel permissions --grant geolocation notifications` grants both permissions
2. `spel permissions --revoke` clears all granted permissions
3. After granting geolocation, `navigator.permissions.query({name: "geolocation"})` returns "granted"
4. `--json` output: `{"granted": ["geolocation", "notifications"]}`
5. Invalid permission name returns error

**How to test**
- `cli_integration_test.clj`: Grant permission, verify via JS eval of `navigator.permissions.query()`.
- `test-cli.sh`: `"$SPEL" permissions --grant geolocation --json | assert_jq '.granted | length' '1'`.

**Verification steps**
1. `spel open about:blank`
2. `spel permissions --grant geolocation`
3. `spel eval "navigator.permissions.query({name: 'geolocation'}).then(r => document.title = r.state)"`
4. `spel get title` returns "granted"

---

### PT-12: `timezone` — Timezone Emulation

**What it is**
Sets the browser's timezone for the current context. Affects `Date` objects, `Intl.DateTimeFormat`, and any time-dependent rendering.

**How it works in agent-browser**
```bash
agent-browser timezone "America/New_York"
```
- Params: `timezone` (IANA timezone string, e.g., "America/New_York", "Europe/London", "Asia/Tokyo")
- Returns: `{timezone: "America/New_York"}`

**Does it make sense for spel?**
Yes. Timezone testing is important for internationalized apps, scheduling features, and date-dependent UI. Currently requires launching with specific env vars or using `eval` to mock `Date`. A proper emulation is cleaner.

**Where to implement**
CLI launch option + daemon handler. This is a context-level setting in Playwright, so it's best set at launch. But Playwright also supports `page.emulateTimezone()` via CDP.

**Pareto assessment**
10% effort / 40% impact. Playwright supports this via `BrowserContext` options or CDP. Quick win for i18n testing.

**Implementation guidance**
- For launch-time: Add `--timezone` flag to daemon launch options. Pass to `Browser.newContext(new BrowserContext.Options().setTimezoneId(tz))`.
- For runtime: Use CDP session to call `Emulation.setTimezoneOverride`. `daemon.clj`: `(defmethod handle-cmd "timezone" [_ {:strs [timezone]}] (.send cdp-session "Emulation.setTimezoneOverride" (json {"timezoneId" timezone})))`.
- `native.clj`: Route `timezone <tz>` and `--timezone <tz>` launch flag.
- Playwright Java API: `Browser.NewContextOptions.setTimezoneId(String)`.

**Acceptance criteria**
1. `spel timezone "America/New_York"` sets the timezone
2. `spel eval "new Date().getTimezoneOffset()"` returns the correct offset for the set timezone
3. `spel eval "Intl.DateTimeFormat().resolvedOptions().timeZone"` returns "America/New_York"
4. Invalid timezone returns error: `"Error: Invalid timezone: 'Foo/Bar'"`
5. `--json` output: `{"timezone": "America/New_York"}`

**How to test**
- `cli_integration_test.clj`: Set timezone, verify via JS Date offset.
- `test-cli.sh`: `"$SPEL" timezone "Pacific/Auckland" && "$SPEL" eval "Intl.DateTimeFormat().resolvedOptions().timeZone" | assert_contains "Pacific/Auckland"`.

**Verification steps**
1. `spel open about:blank`
2. `spel timezone "Asia/Tokyo"`
3. `spel eval "Intl.DateTimeFormat().resolvedOptions().timeZone"` returns "Asia/Tokyo"
4. `spel eval "new Date().getTimezoneOffset()"` returns -540 (JST is UTC+9)

---

### PT-13: `locale` — Locale Emulation

**What it is**
Sets the browser locale, affecting `navigator.language`, `Intl` formatting, and Accept-Language headers.

**How it works in agent-browser**
```bash
agent-browser locale "fr-FR"
```
- Params: `locale` (BCP 47 locale string, e.g., "en-US", "fr-FR", "ja-JP")
- Returns: `{locale: "fr-FR"}`

**Does it make sense for spel?**
Yes. Locale testing is essential for i18n. Playwright supports locale at context creation. spel's `set device` already sets locale for device presets, but there's no standalone locale command.

**Where to implement**
CLI launch option + daemon handler.

**Pareto assessment**
10% effort / 40% impact. Same pattern as timezone. Quick win.

**Implementation guidance**
- Launch-time: `--locale` flag passed to `Browser.newContext(new BrowserContext.Options().setLocale(locale))`.
- Runtime: Use CDP `Emulation.setLocaleOverride` or set `Accept-Language` header.
- `daemon.clj`: Handler that sets locale via CDP or header manipulation.
- `native.clj`: Route `locale <locale>` and `--locale <locale>` launch flag.
- Playwright Java API: `Browser.NewContextOptions.setLocale(String)`.

**Acceptance criteria**
1. `spel locale "fr-FR"` sets the locale
2. `spel eval "navigator.language"` returns "fr-FR"
3. `spel eval "new Intl.NumberFormat().format(1234.5)"` returns locale-appropriate format (e.g., "1 234,5" for fr-FR)
4. `--json` output: `{"locale": "fr-FR"}`
5. Invalid locale string returns error

**How to test**
- `cli_integration_test.clj`: Set locale, verify `navigator.language` via eval.
- `test-cli.sh`: `"$SPEL" locale "de-DE" && "$SPEL" eval "navigator.language" | assert_contains "de-DE"`.

**Verification steps**
1. `spel open about:blank`
2. `spel locale "ja-JP"`
3. `spel eval "navigator.language"` returns "ja-JP"

---

## Tier 2: Medium Pareto (30-60% effort, 35-60% impact)

These require more work but deliver solid value. Plan for 1-3 days each.

---

### PT-14: Content Injection (`addscript` / `addstyle` / `addinitscript`)

**What it is**
Three commands for injecting content into pages:
- `addscript`: Inject a `<script>` tag (inline or URL)
- `addstyle`: Inject a `<style>` or `<link>` tag (inline CSS or URL)
- `addinitscript`: Register a script that runs on every page navigation (persistent)

**How it works in agent-browser**
```bash
agent-browser addscript --content "window.myFlag = true"
agent-browser addscript --url "https://cdn.example.com/lib.js"
agent-browser addstyle --content "body { background: red; }"
agent-browser addstyle --url "https://cdn.example.com/theme.css"
agent-browser addinitscript --script "window.__injected = Date.now()"
```
- `addscript`: Params: `content?` (inline JS), `url?` (script URL). Adds a `<script>` tag to the page.
- `addstyle`: Params: `content?` (inline CSS), `url?` (stylesheet URL). Adds a `<style>` or `<link>` tag.
- `addinitscript`: Params: `script` (JS code). Runs before any page script on every navigation.
- Returns: `{added: true}` for all three.

**Does it make sense for spel?**
Yes. `addinitscript` is particularly valuable: it lets you inject polyfills, mock APIs, or set up test fixtures that persist across navigations. `addscript` and `addstyle` are useful for visual testing and injecting test utilities. spel's `eval` can do inline JS, but can't add external scripts by URL or persist across navigations.

**Where to implement**
Library + SCI + CLI for all three.

**Pareto assessment**
25% effort / 55% impact. Three commands, but each is a thin Playwright wrapper. `addinitscript` alone justifies the effort.

**Implementation guidance**
- `page.clj`: 
  - `(defn add-script-tag [page opts] (.addScriptTag page (doto (Page$AddScriptTagOptions.) ...)))`
  - `(defn add-style-tag [page opts] (.addStyleTag page (doto (Page$AddStyleTagOptions.) ...)))`
  - `(defn add-init-script [page script] (.addInitScript page script))`
- `daemon.clj`: Three handlers. `addinitscript` should use `context.addInitScript()` (context-level, not page-level) for persistence.
- `native.clj`: Route all three commands.
- Playwright Java API: `Page.addScriptTag()`, `Page.addStyleTag()`, `BrowserContext.addInitScript()`.

**Acceptance criteria**
1. `spel addscript --content "window.x = 42"` injects inline JS; `eval "window.x"` returns 42
2. `spel addscript --url "https://cdn.jsdelivr.net/npm/lodash/lodash.min.js"` loads external script
3. `spel addstyle --content "body { background: red }"` changes page styling
4. `spel addinitscript "window.__test = true"` persists across `reload` and `open`
5. After `addinitscript`, navigating to a new URL still has `window.__test === true`
6. `--json` output: `{"added": true}` for all three

**How to test**
- `cli_integration_test.clj`: Inject script, verify via eval. Inject init script, navigate, verify persistence.
- `test-cli.sh`: `"$SPEL" addscript --content "window.injected=true" && "$SPEL" eval "window.injected" | assert_contains "true"`.

**Verification steps**
1. `spel open about:blank`
2. `spel addinitscript "window.__marker = 'persistent'"`
3. `spel open https://example.org`
4. `spel eval "window.__marker"` returns "persistent"
5. `spel reload`
6. `spel eval "window.__marker"` still returns "persistent"

---

### PT-15: `expose` — Expose Function to Page

**What it is**
Exposes a named function on the page's `window` object. When page JavaScript calls `window.functionName()`, it triggers a callback in the automation context. Enables bidirectional communication between page code and the automation script.

**How it works in agent-browser**
```bash
agent-browser expose "myCallback"
```
- Params: `name` (function name to expose)
- After exposure, page JS can call `window.myCallback(data)` and the automation context receives the call
- Returns: `{exposed: "myCallback"}`

**Does it make sense for spel?**
Partially. In agent-browser's CLI model, the exposed function is a no-op callback that logs calls. The real value is in programmatic use (SCI/eval mode) where you can define what the callback does. For CLI-only use, it's limited. For SCI use, it's powerful: you could expose a function that collects data from the page.

**Where to implement**
SCI + Library primarily. CLI command for setup, but the callback logic lives in `--eval` scripts.

**Pareto assessment**
30% effort / 35% impact. The Playwright API is straightforward, but making the callback useful from CLI is tricky. Most value is in SCI/eval mode.

**Implementation guidance**
- `page.clj`: `(defn expose-function [page name callback] (.exposeFunction page name (reify com.microsoft.playwright.Page$ExposeFunction (call [_ args] (callback args)))))`.
- `daemon.clj`: For CLI, expose a function that stores calls in an atom. Add a `get-exposed-calls` handler to retrieve them.
- `sci_env.clj`: `(page/expose-function "name" (fn [args] ...))` where the callback is a SCI function.
- Playwright Java API: `Page.exposeFunction(String name, Page.ExposeFunction callback)`.

**Acceptance criteria**
1. `spel expose "myFn"` registers the function on `window`
2. `spel eval "typeof window.myFn"` returns "function"
3. In `--eval` mode: `(page/expose-function "getData" (fn [args] (println "called with" args)))` works
4. Page JS calling `window.myFn("hello")` doesn't throw
5. `--json` output: `{"exposed": "myFn"}`

**How to test**
- `cli_integration_test.clj`: Expose function, call from page JS via eval, verify no errors.
- `test-cli.sh`: `"$SPEL" expose "testFn" && "$SPEL" eval "typeof window.testFn" | assert_contains "function"`.

**Verification steps**
1. `spel open about:blank`
2. `spel expose "agentCallback"`
3. `spel eval "typeof window.agentCallback"` returns "function"
4. `spel eval "window.agentCallback('test')"` completes without error

---

### PT-16: `responsebody` — Get HTTP Response Body

**What it is**
Intercepts and returns the HTTP response body for a specific URL. Useful for inspecting API responses, verifying data payloads, and debugging network issues without browser DevTools.

**How it works in agent-browser**
```bash
agent-browser responsebody "https://api.example.com/data" --timeout 5000
```
- Params: `url` (URL pattern to match), `timeout?` (ms)
- Waits for a response matching the URL pattern
- Returns: `{url, status, headers, body}` where body is the response text/JSON

**Does it make sense for spel?**
Yes, but spel already has `network requests` which shows request/response metadata. The gap is getting the actual response body. This is valuable for API testing workflows where you navigate a page and want to inspect the XHR/fetch responses it made.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
30% effort / 50% impact. Needs response interception setup. High value for debugging and API verification.

**Implementation guidance**
- `daemon.clj`: Use `page.waitForResponse(urlPattern)` to wait for a matching response, then call `response.text()` or `response.body()` to get the body.
- `(defmethod handle-cmd "responsebody" [_ {:strs [url timeout-ms]}] (let [resp (.waitForResponse page (reify Predicate (test [_ r] (str/includes? (.url r) url))) (or timeout-ms 30000))] {:url (.url resp) :status (.status resp) :body (.text resp)}))`.
- `native.clj`: Route `responsebody <url-pattern> [--timeout ms]`.
- Playwright Java API: `Page.waitForResponse(Predicate<Response>)`, `Response.text()`, `Response.body()`.

**Acceptance criteria**
1. `spel responsebody "api.example.com/data"` waits for and returns the response body
2. JSON responses are returned as parsed JSON in `--json` mode
3. Timeout error if no matching response within timeout
4. URL pattern matching (substring match, not exact)
5. Returns status code and headers alongside body
6. `--json` output: `{"url": "...", "status": 200, "body": "...", "headers": {...}}`

**How to test**
- `cli_integration_test.clj`: Navigate to a page that makes fetch requests, capture response body.
- `test-cli.sh`: Hard to test without a real server. Test `--help` at minimum.

**Verification steps**
1. `spel open https://example.org`
2. `spel eval "fetch('/').then(r => r.text())"` (trigger a request)
3. In parallel: `spel responsebody "example.org" --timeout 5000` captures the response

---

### PT-17: `styles` — Get Computed CSS Styles

**What it is**
Returns computed CSS styles for elements matching a selector. Useful for visual regression testing, design system verification, and accessibility audits (font size, color contrast).

**How it works in agent-browser**
```bash
agent-browser styles "#heading"
```
- Params: `selector` (string)
- Returns: `{elements: [{tag: "h1", text: "Hello", box: {x,y,w,h}, styles: {fontSize: "32px", fontWeight: "700", fontFamily: "Arial", color: "rgb(0,0,0)", backgroundColor: "rgba(0,0,0,0)", borderRadius: "0px", border: "0px none", boxShadow: "none", padding: "0px"}}]}`
- Returns a curated set of commonly-needed CSS properties (not all 300+ computed styles)

**Does it make sense for spel?**
Yes. Visual regression testing and design system verification are real use cases. Currently requires `eval` with `getComputedStyle()`. A dedicated command with curated output is more ergonomic.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
30% effort / 45% impact. Needs JS evaluation to extract computed styles. Curating the right set of properties takes thought.

**Implementation guidance**
- `daemon.clj`: Use `locator.evaluate()` to run `getComputedStyle()` in the browser and extract a curated set of properties.
- JS snippet: `el => { const s = getComputedStyle(el); return { fontSize: s.fontSize, fontWeight: s.fontWeight, color: s.color, backgroundColor: s.backgroundColor, ... }; }`.
- `native.clj`: Route `styles <sel>`.
- Consider: Return all computed styles with a `--full` flag, curated set by default.

**Acceptance criteria**
1. `spel styles "#heading"` returns computed styles for the element
2. Default output includes: fontSize, fontWeight, fontFamily, color, backgroundColor, borderRadius, border, boxShadow, padding, margin
3. `--full` flag returns all computed styles
4. Multiple elements: `spel styles "p"` returns styles for all matching `<p>` elements
5. `--json` output matches agent-browser format
6. Works with refs: `spel styles @e3`

**How to test**
- `cli_integration_test.clj`: Set content with known styles, verify returned values match.
- `test-cli.sh`: `"$SPEL" setcontent '<h1 style="color:red" id="h">Hi</h1>' && "$SPEL" styles "#h" --json | assert_jq '.elements[0].styles.color' '"rgb(255, 0, 0)"'`.

**Verification steps**
1. `spel setcontent '<h1 style="font-size: 48px; color: blue;">Test</h1>'`
2. `spel styles h1 --json`
3. Verify `fontSize` is "48px" and `color` is "rgb(0, 0, 255)"

---

### PT-18: `evalhandle` — Evaluate JS and Return Handle

**What it is**
Evaluates JavaScript and returns a JSHandle (an opaque reference to a JS object in the browser) instead of serializing the result. The handle can be passed to other Playwright functions that accept element or JS handles.

**How it works in agent-browser**
```bash
agent-browser evalhandle "document.querySelector('#complex-widget')"
```
- Params: `script` (JS expression)
- Returns a handle reference (not the serialized value)
- The handle can be used in subsequent commands

**Does it make sense for spel?**
Only in SCI/eval mode. A JSHandle is meaningless in CLI mode (you can't pass opaque references between CLI invocations). In SCI scripts, it's useful for working with non-serializable objects (DOM nodes, Maps, Sets, Promises).

**Where to implement**
SCI only. No CLI command needed.

**Pareto assessment**
25% effort / 30% impact. Useful for advanced SCI scripting, but most users won't need it.

**Implementation guidance**
- `page.clj`: Add `(defn evaluate-handle [page script & args] (.evaluateHandle page script (into-array Object args)))`.
- `sci_env.clj`: Expose as `(page/eval-handle "script")`. Return the raw JSHandle object.
- The handle can then be passed to other functions: `(page/eval-handle "document.body")` then `(.getProperty handle "innerHTML")`.
- Playwright Java API: `Page.evaluateHandle(String expression)`.

**Acceptance criteria**
1. `(page/eval-handle "document.body")` returns a JSHandle object in SCI
2. The handle can be used with `.getProperty`, `.evaluate`, `.dispose`
3. `(page/eval-handle "new Map([['a', 1]])")` works (non-serializable)
4. No CLI command (SCI-only feature)

**How to test**
- `cli_integration_test.clj`: SCI eval test that creates a handle and reads a property from it.

**Verification steps**
1. `spel --eval '(let [h (page/eval-handle "document.body")] (.getProperty h "tagName"))'`
2. Returns "BODY"

---

### PT-19: `pause` — Pause Execution and Open Debugger

**What it is**
Pauses automation execution and opens the Playwright Inspector for interactive debugging. The user can step through actions, inspect the page, and resume.

**How it works in agent-browser**
```bash
agent-browser pause
```
- No params
- Opens Playwright Inspector
- Blocks until user resumes from the Inspector UI

**Does it make sense for spel?**
Partially. spel already has `inspector [url]` which launches a headed browser with the Playwright Inspector. The difference is that `pause` pauses a running session mid-flight, while `inspector` starts a new session. For `--eval` scripts, `pause` would let you stop at a breakpoint and inspect state.

**Where to implement**
CLI + SCI. The daemon handler calls `page.pause()`.

**Pareto assessment**
20% effort / 25% impact. Simple Playwright call, but niche use case. Most debugging happens via screenshots and snapshots.

**Implementation guidance**
- `daemon.clj`: `(defmethod handle-cmd "pause" [_ _] (.pause (current-page)) {:resumed true})`.
- `native.clj`: Route `pause`.
- `sci_env.clj`: Expose as `(page/pause)`.
- Playwright Java API: `Page.pause()`. Requires `PWDEBUG=1` environment variable or headed mode.
- Note: Only works in headed mode. Should error gracefully in headless: `"Error: pause requires headed mode (--headed or --interactive)"`.

**Acceptance criteria**
1. `spel pause` opens Playwright Inspector (in headed mode)
2. Execution blocks until user clicks "Resume" in Inspector
3. Error in headless mode: clear message about requiring headed mode
4. Works in `--eval` scripts: `(page/pause)` stops execution at that point
5. After resume, subsequent commands work normally

**How to test**
- `cli_integration_test.clj`: Difficult to test interactively. Test that headless mode returns an error.
- `test-cli.sh`: `"$SPEL" pause 2>/dev/null; test $? -ne 0` (should fail in headless).

**Verification steps**
1. `spel --headed open https://example.org`
2. `spel pause` (Inspector opens)
3. Click "Resume" in Inspector
4. `spel get title` works normally

---

### PT-20: Diff Engine (3 commands)

**What it is**
Three commands for comparing pages:
- `diff_snapshot`: Myers diff between two accessibility snapshots
- `diff_screenshot`: Pixel-level screenshot comparison
- `diff_url`: Compare two URLs (snapshot + screenshot diff combined)

**How it works in agent-browser**
```bash
# Snapshot diff against baseline
agent-browser diff_snapshot --baseline '{"snapshot": "...previous..."}'
# Returns: {diff, additions: 5, removals: 3, unchanged: 42, changed: 2}

# Screenshot diff
agent-browser diff_screenshot --baseline ./before.png --output ./diff.png --threshold 0.1
# Returns: {diffPath: "./diff.png", totalPixels: 1920000, differentPixels: 1234, mismatchPercentage: 0.064, match: true}

# URL comparison
agent-browser diff_url --url1 "https://staging.example.com" --url2 "https://prod.example.com"
# Returns: combined snapshot diff + screenshot diff
```

**Does it make sense for spel?**
Yes. Diff tooling is agent-browser's biggest feature advantage over spel. Visual regression testing, staging-vs-production comparison, and change detection are high-value workflows. The snapshot diff is particularly useful for agents that need to detect what changed after an action.

**Where to implement**
Library + SCI + CLI for all three. The diff algorithms are pure functions (no Playwright dependency for the diff logic itself).

**Pareto assessment**
50% effort / 60% impact. Significant implementation (Myers diff algorithm, pixel comparison, multi-page orchestration). But this is agent-browser's marquee feature and a real competitive gap.

**Implementation guidance**
- **Snapshot diff**: Implement Myers diff in Clojure (or use a JVM library like `java-diff-utils`). Compare two snapshot strings line-by-line. Count additions, removals, changes.
  - New file: `src/com/blockether/spel/diff.clj`.
  - `daemon.clj`: `(defmethod handle-cmd "diff_snapshot" [_ {:strs [baseline selector compact maxDepth]}] ...)`. Take current snapshot, diff against baseline.
- **Screenshot diff**: Use Java's `BufferedImage` to compare pixels. Calculate mismatch percentage. Generate diff image highlighting differences.
  - Same `diff.clj` file. Pixel comparison is straightforward with `BufferedImage.getRGB()`.
- **URL diff**: Navigate to url1, capture snapshot + screenshot. Navigate to url2, capture snapshot + screenshot. Run both diffs.
  - `daemon.clj`: Orchestration handler that calls the other two.
- `native.clj`: Route `diff snapshot [--baseline <file>]`, `diff screenshot --baseline <file>`, `diff url <url1> <url2>`.

**Acceptance criteria**
1. `spel diff snapshot --baseline snapshot.txt` returns diff with additions/removals/unchanged counts
2. `spel diff screenshot --baseline before.png --output diff.png` creates visual diff image
3. `spel diff screenshot --baseline before.png --threshold 0.05` returns match=true/false based on threshold
4. `spel diff url https://staging.example.com https://prod.example.com` compares both pages
5. `--json` output includes structured diff data
6. Diff image highlights changed pixels in red/magenta
7. Snapshot diff handles tree structure (not just line-by-line)

**How to test**
- Unit tests in `diff_test.clj`: Test Myers diff with known inputs. Test pixel comparison with synthetic images.
- `cli_integration_test.clj`: Take snapshot, modify page, take diff, verify counts.
- `test-cli.sh`: `"$SPEL" diff snapshot --help | assert_contains "baseline"`.

**Verification steps**
1. `spel open https://example.org`
2. `spel snapshot > /tmp/baseline.txt`
3. `spel screenshot /tmp/before.png`
4. `spel eval "document.querySelector('h1').textContent = 'Changed'"`
5. `spel diff snapshot --baseline /tmp/baseline.txt` shows 1 change
6. `spel diff screenshot --baseline /tmp/before.png --output /tmp/diff.png` shows pixel differences

---

### PT-21: HAR Recording

**What it is**
Records all HTTP traffic to a HAR (HTTP Archive) file. HAR files can be analyzed in Chrome DevTools, used for performance auditing, or replayed for testing.

**How it works in agent-browser**
```bash
agent-browser har_start
# ... browse around ...
agent-browser har_stop --path ./traffic.har
```
- `har_start`: No params. Begins recording all network traffic.
- `har_stop`: Params: `path` (file path to save HAR). Stops recording and saves.
- Returns: `{path: "./traffic.har", entries: 42}`

**Does it make sense for spel?**
Yes. Playwright has built-in HAR recording via `BrowserContext.Options.setRecordHarPath()`. HAR files are the standard format for network traffic analysis. Useful for performance testing, debugging API calls, and creating network mocks.

**Where to implement**
Library + SCI + CLI.

**Pareto assessment**
25% effort / 40% impact. Playwright handles the recording. spel just needs to expose start/stop lifecycle.

**Implementation guidance**
- The challenge: Playwright's HAR recording is set at context creation time (`setRecordHarPath`), not started/stopped dynamically. Two approaches:
  - **A) Context-level**: Add `--record-har <path>` launch flag. HAR is saved when context closes.
  - **B) Route-based**: Use `page.routeFromHAR()` in reverse. Or use CDP `Network.enable` + collect entries manually.
  - **C) Playwright's `recordHar` option**: Set on context, call `context.close()` to flush. Then reopen context.
- Recommended: Option A for simplicity. Add `--record-har` flag and `har stop` to flush/save.
- `daemon.clj`: Track HAR recording state. On `har_stop`, close and reopen context (or use Playwright's `context.close()` which flushes HAR).
- Playwright Java API: `Browser.NewContextOptions.setRecordHarPath(Path)`, `Browser.NewContextOptions.setRecordHarMode()`.

**Acceptance criteria**
1. `spel --record-har /tmp/traffic.har open https://example.org` starts HAR recording
2. `spel har stop` saves the HAR file and stops recording
3. The HAR file is valid JSON conforming to HAR 1.2 spec
4. HAR contains all requests/responses made during the session
5. `--json` output on stop: `{"path": "/tmp/traffic.har", "entries": 15}`
6. HAR file can be opened in Chrome DevTools Network tab

**How to test**
- `cli_integration_test.clj`: Start with HAR recording, navigate, stop, verify HAR file exists and contains entries.
- `test-cli.sh`: Test `--record-har` flag in help output.

**Verification steps**
1. `spel --record-har /tmp/test.har open https://example.org`
2. `spel open https://example.com`
3. `spel har stop`
4. `cat /tmp/test.har | python3 -m json.tool` (valid JSON)
5. HAR file contains entries for both URLs

---

### PT-22: Video Management (5 commands)

**What it is**
Five commands for video recording lifecycle:
- `video_start` / `video_stop`: Start/stop video recording
- `recording_start` / `recording_stop` / `recording_restart`: Coded recording (JSONL action log)

**How it works in agent-browser**
```bash
agent-browser video_start --path ./video.webm
agent-browser video_stop
agent-browser recording_start --path ./actions.jsonl --url https://example.org
agent-browser recording_stop
# Returns: {path: "./actions.jsonl", frames: 42}
agent-browser recording_restart --path ./new-actions.jsonl
```

**Does it make sense for spel?**
Partially. spel already has video recording via `--eval` with `start-video-recording` / `finish-video-recording`, and codegen recording via `spel codegen record`. The gap is CLI-level video start/stop commands (currently SCI-only).

The `recording_*` commands overlap with `spel codegen record` which already records browser sessions to JSONL.

Verdict: Add `video start` / `video stop` CLI commands. Skip `recording_*` (already covered by `codegen record`).

**Where to implement**
CLI commands wrapping existing SCI functions.

**Pareto assessment**
40% effort / 35% impact (for video start/stop CLI). The video lifecycle is tricky (context recreation). Recording commands are already covered.

**Implementation guidance**
- `daemon.clj`: Add `video_start` and `video_stop` handlers. `video_start` needs to recreate the browser context with `recordVideoDir` set. `video_stop` closes the context and saves the video.
- This is essentially the same problem as TASK-009 (video save-as bug). Fix that first, then expose as CLI commands.
- `native.clj`: Route `video start [--path <dir>]` and `video stop [--path <file>]`.
- Playwright Java API: `Browser.NewContextOptions.setRecordVideoDir(Path)`, `Page.video().path()`, `Page.video().saveAs(Path)`.

**Acceptance criteria**
1. `spel video start --path /tmp/videos/` starts video recording
2. `spel video stop --path /tmp/output.webm` saves the video
3. Video file is playable WebM
4. `--json` output: `{"recording": true, "path": "/tmp/videos/"}` on start, `{"path": "/tmp/output.webm", "size": 12345}` on stop
5. `recording_start` / `recording_stop` are NOT implemented (use `codegen record` instead)

**How to test**
- `cli_integration_test.clj`: Start video, navigate, stop, verify file exists.
- `test-cli.sh`: `"$SPEL" video start --help | assert_contains "path"`.

**Verification steps**
1. `spel video start --path /tmp/vids/`
2. `spel open https://example.org`
3. `spel video stop --path /tmp/test-video.webm`
4. `ls -la /tmp/test-video.webm` (file exists, non-zero size)

---

### PT-23: Config File (`spel.json`)

**What it is**
A cascading configuration file that stores default settings. Checked in project directory first, then home directory. Stores defaults for headless mode, viewport, browser, proxy, headers, timeout, etc.

**How it works in agent-browser**
```json
// agent-browser.json (in project root or ~/.config/)
{
  "headless": true,
  "viewport": {"width": 1280, "height": 720},
  "browser": "chromium",
  "proxy": "http://proxy.corp.com:8080",
  "headers": {"X-Custom": "value"},
  "timeout": 60000,
  "stealth": true
}
```
- Cascading: project dir > home dir > defaults
- CLI flags override config file values
- No command needed. Auto-loaded on startup.

**Does it make sense for spel?**
Yes. Currently, every spel invocation requires passing flags for non-default settings. A config file would reduce repetition, especially for team-wide settings (proxy, headers, viewport). Particularly useful for CI where you want consistent settings without long command lines.

**Where to implement**
`daemon.clj` (config loading on startup) + `native.clj` (merge with CLI flags).

**Pareto assessment**
35% effort / 45% impact. Config file parsing, cascading logic, merge with CLI flags. Moderate effort, good ergonomic payoff.

**Implementation guidance**
- New file: `src/com/blockether/spel/config.clj`. Load `spel.json` from: (1) current directory, (2) `~/.config/spel/config.json`, (3) `XDG_CONFIG_HOME/spel/config.json`.
- Use `clojure.data.json` (already a dependency) to parse.
- Merge order: defaults < config file < CLI flags.
- `daemon.clj`: On daemon start, load config and merge with launch options.
- `native.clj`: Before dispatching, load config and merge with parsed CLI args.
- Support all existing launch flags as config keys: `headless`, `viewport`, `browser`, `proxy`, `headers`, `timeout`, `stealth`, `userAgent`, `locale`, `timezone`, `device`.

**Acceptance criteria**
1. `spel.json` in project directory is auto-loaded
2. `~/.config/spel/config.json` is loaded as fallback
3. CLI flags override config file values
4. `spel config show` displays effective configuration (merged)
5. Invalid JSON in config file produces clear error
6. Missing config file is silently ignored (not an error)
7. All launch flags are supported as config keys

**How to test**
- Unit tests in `config_test.clj`: Test merge logic, cascading, override behavior.
- `test-cli.sh`: Create temp `spel.json`, verify settings are applied.

**Verification steps**
1. Create `spel.json` with `{"viewport": {"width": 800, "height": 600}}`
2. `spel open https://example.org`
3. `spel eval "window.innerWidth"` returns 800
4. `spel --viewport 1024 768 open https://example.org` (CLI overrides config)
5. `spel eval "window.innerWidth"` returns 1024

---

### PT-24: Domain Allowlist

**What it is**
Restricts navigation to specified domains. Any attempt to navigate to a non-allowed domain is blocked. Safety feature for AI agents to prevent them from wandering to unintended sites.

**How it works in agent-browser**
```bash
agent-browser --allowed-domains example.com,api.example.com open https://example.com
```
- Launch option: `--allowed-domains <comma-separated domains>`
- Sub-resource requests to other domains are also blocked
- Navigation attempts to non-allowed domains return an error
- Returns error: `"Navigation to https://evil.com blocked by domain allowlist"`

**Does it make sense for spel?**
Yes. For AI agent deployments, domain restriction is a safety guardrail. Prevents agents from navigating to arbitrary sites, leaking data, or getting distracted. This is a security feature, not a convenience feature.

**Where to implement**
Daemon launch option + route interception.

**Pareto assessment**
30% effort / 40% impact. Needs route interception for sub-resources. Navigation blocking is simpler. Important for enterprise/safety-conscious deployments.

**Implementation guidance**
- `daemon.clj`: On launch with `--allowed-domains`, set up a route that blocks requests to non-allowed domains. Also validate URLs in the `navigate` handler before calling Playwright.
- Route interception: `page.route("**/*", route -> { if (!allowedDomains.contains(route.request().url().host())) route.abort(); else route.resume(); })`.
- `native.clj`: Parse `--allowed-domains <domains>` flag.
- Consider: Also block in `tab new` and any other navigation entry points.

**Acceptance criteria**
1. `spel --allowed-domains example.com open https://example.com` works normally
2. `spel open https://evil.com` returns error: `"Navigation blocked: evil.com not in allowed domains"`
3. Sub-resource requests to non-allowed domains are blocked (images, scripts, etc.)
4. Multiple domains: `--allowed-domains "example.com,api.example.com"`
5. Subdomain matching: `--allowed-domains "example.com"` allows `sub.example.com`
6. `--json` error output: `{"error": "Navigation blocked", "domain": "evil.com", "allowed": ["example.com"]}`

**How to test**
- `cli_integration_test.clj`: Launch with allowed domains, try navigating to blocked domain, verify error.
- `test-cli.sh`: `"$SPEL" --allowed-domains example.com open https://example.org 2>&1 | assert_contains "blocked"`.

**Verification steps**
1. `spel --allowed-domains example.com open https://example.com` (succeeds)
2. `spel open https://google.com` (blocked, error)
3. `spel eval "fetch('https://api.evil.com').catch(e => 'blocked')"` returns "blocked"

---

## Tier 3: Low Pareto or Niche (>60% effort OR <30% impact)

These are either very expensive to build, serve a narrow audience, or both. Defer or skip unless strategic priorities change.

---

### PT-25: Auth Vault (5 commands)

**What it is**
Encrypted credential storage for automated login flows. Save username/password/selectors per site, then replay login with a single command.

**How it works in agent-browser**
```bash
agent-browser auth_save --name github --url https://github.com/login \
  --username user@example.com --password "secret" \
  --usernameSelector "#login_field" --passwordSelector "#password" \
  --submitSelector '[name="commit"]'

agent-browser auth_login --name github
# Navigates to URL, fills credentials, submits form

agent-browser auth_list
# Returns: [{name: "github", url: "https://github.com/login", username: "user@example.com"}]

agent-browser auth_delete --name github
agent-browser auth_show --name github
# Shows profile without password
```

**Does it make sense for spel?**
Questionable. spel already has `state save` / `state load` for persisting auth state (cookies + localStorage). The auth vault adds credential storage and replay, which is a different approach: instead of saving post-login state, it re-performs the login.

Concerns:
- Storing passwords (even encrypted) on disk is a security liability
- Login flows change frequently (selectors break)
- `state save` after manual login is more robust
- The encryption adds complexity (key management, rotation)

Verdict: **Defer**. The `state save/load` workflow covers 80% of the use case. If demand emerges, implement as a plugin.

**Where to implement**
Would need: new `auth.clj` module, AES-256-GCM encryption, file storage, 5 daemon handlers, 5 CLI commands.

**Pareto assessment**
60% effort / 35% impact. Heavy implementation for a feature that `state save/load` mostly covers.

**Acceptance criteria** (if implemented)
1. `spel auth save --name github --url ... --username ... --password ...` saves encrypted profile
2. `spel auth login --name github` performs automated login
3. Passwords are AES-256-GCM encrypted at rest
4. `spel auth list` shows profiles without passwords
5. `spel auth delete --name github` removes profile

**How to test**
- Unit tests for encryption/decryption. Integration test for save/login/delete lifecycle.

**Verification steps**
- Save a profile, login, verify cookies are set, delete profile, verify it's gone.

---

### PT-26: Action Policy / Confirmation

**What it is**
A safety system where certain actions require explicit confirmation before executing. An action policy file defines rules (e.g., "confirm before clicking any delete button"). When a confirmable action is triggered, execution pauses and returns a `confirmationId`. The caller must then `confirm` or `deny`.

**How it works in agent-browser**
```bash
# Launch with policy
agent-browser --confirm-actions "click,fill" open https://example.com

# When clicking:
agent-browser click "#delete-btn"
# Returns: {confirmationRequired: true, confirmationId: "abc123", action: "click", selector: "#delete-btn"}

# Confirm or deny:
agent-browser confirm --confirmationId abc123
agent-browser deny --confirmationId abc123
```

**Does it make sense for spel?**
No, not for the current use case. spel is a developer tool and testing framework. Action confirmation adds latency and complexity to every action. For AI agent safety, domain allowlisting (PT-24) is more practical. The confirmation pattern makes sense for untrusted AI agents in production, but spel's users are developers running controlled automation.

Verdict: **Skip**. Domain allowlist (PT-24) covers the safety concern more cleanly.

**Where to implement**
N/A (skipped).

**Pareto assessment**
65% effort / 30% impact. Complex state machine (pending actions, timeouts, confirmation IDs). Low value for spel's developer audience.

---

### PT-27: Screencast / Streaming

**What it is**
Live WebSocket streaming of browser frames. Enables real-time viewing of what the browser is doing, useful for monitoring and pair-browsing.

**How it works in agent-browser**
```bash
agent-browser screencast_start --format jpeg --quality 80 --maxWidth 1280
# Streams frames to AGENT_BROWSER_STREAM_PORT (WebSocket)

agent-browser screencast_stop
```

**Does it make sense for spel?**
Not really. spel's daemon architecture already supports `screenshot` at 18ms per call, which is fast enough for near-real-time monitoring. True streaming requires a WebSocket server, frame encoding pipeline, and a viewer client. The complexity is high and the use case is niche.

For debugging, `spel --headed` shows the browser directly. For CI, video recording captures everything. Streaming fills a narrow gap between these two.

Verdict: **Skip**. Use `--headed` for live viewing, video recording for playback.

**Where to implement**
N/A (skipped).

**Pareto assessment**
70% effort / 20% impact. WebSocket server, frame encoding, viewer client. Very niche.

---

### PT-28: Input Injection (CDP-level mouse/keyboard/touch)

**What it is**
Raw CDP (Chrome DevTools Protocol) input injection. Bypasses Playwright's input handling entirely and sends raw input events at the protocol level.

**How it works in agent-browser**
```bash
agent-browser input_mouse --type mouseMoved --x 100 --y 200
agent-browser input_keyboard --type keyDown --key "a" --code "KeyA"
agent-browser input_touch --type touchStart --touchPoints '[{"x":100,"y":200}]'
```

**Does it make sense for spel?**
No. spel already has `mouse move/down/up/wheel`, `press`, `type`, and (proposed) `tap`. These use Playwright's input API which is higher-level, more reliable, and cross-browser. CDP input injection is Chromium-only and bypasses Playwright's event coordination, which can cause flaky behavior.

The only use case for raw CDP input is pair-browsing (forwarding real user input to the browser), which is a niche scenario.

Verdict: **Skip**. Playwright's input API is sufficient. CDP injection adds complexity without clear benefit.

**Where to implement**
N/A (skipped).

**Pareto assessment**
50% effort / 15% impact. CDP-only (Chromium), bypasses Playwright's coordination, niche use case.

---

### PT-29: Profiler (DevTools)

**What it is**
Start/stop the Chrome DevTools profiler to capture CPU profiles and traces. Useful for performance analysis.

**How it works in agent-browser**
```bash
agent-browser profiler_start --categories "devtools.timeline,v8.execute"
agent-browser profiler_stop --path ./profile.json
```

**Does it make sense for spel?**
Marginally. spel already has `trace start` / `trace stop` which captures Playwright traces (including network, screenshots, and action timing). Chrome DevTools profiling is a different, lower-level tool for CPU/memory analysis.

For most users, Playwright traces are sufficient. DevTools profiling is for performance engineers doing deep analysis.

Verdict: **Defer**. Low priority unless performance testing becomes a strategic focus.

**Where to implement**
Would use CDP `Profiler.start()` / `Profiler.stop()` and `Tracing.start()` / `Tracing.end()`.

**Pareto assessment**
40% effort / 20% impact. CDP-based, Chromium-only, niche audience.

**Acceptance criteria** (if implemented)
1. `spel profiler start` begins CPU profiling
2. `spel profiler stop --path /tmp/profile.json` saves the profile
3. Profile file is compatible with Chrome DevTools "Performance" tab
4. `--categories` flag filters trace categories

**How to test**
- Start profiler, do some work, stop, verify file is valid JSON with profile data.

---

### PT-30: Window Management (`window_new`)

**What it is**
Opens a new browser window (not a tab). Creates a separate window with its own viewport.

**How it works in agent-browser**
```bash
agent-browser window_new --viewport '{"width": 800, "height": 600}'
# Returns: {index: 1, total: 2}
```

**Does it make sense for spel?**
Not really. spel's `tab new` creates new pages in the same context. A new window in Playwright terms is a new BrowserContext, which means separate cookies, storage, and state. This is already achievable via `--session` (separate daemon sessions).

The distinction between "window" and "tab" is a browser UI concept. In Playwright, it's "context" vs "page". spel's session model already handles the "separate context" case.

Verdict: **Skip**. Use `--session` for separate contexts. Use `tab new` for same-context pages.

**Where to implement**
N/A (skipped).

**Pareto assessment**
15% effort / 10% impact. Easy to implement but unclear value over existing `--session` and `tab new`.

---

### PT-31: iOS Simulator Support

**What it is**
Real iOS Safari testing via Appium and Xcode Simulator.

**Assessment**
See existing **TASK-010** in TASKS.md for full analysis. The assessment remains unchanged:
- 80% effort / 15% impact
- Requires Appium, Xcode (12+ GB), macOS-only
- spel's `set device` covers viewport/UA emulation for most cases
- Only worth pursuing if mobile Safari testing becomes a strategic priority

No new information to add. The Appium ecosystem hasn't changed significantly since the original assessment.

---

### PT-32: Cloud Browser Providers

**What it is**
Integration with cloud browser services (BrowserBase, Kernel, BrowserUse) for serverless browser instances.

**Assessment**
See existing **TASK-011** in TASKS.md for full analysis. The assessment remains unchanged:
- 60% effort / 20% impact
- spel's `connect <cdp-url>` already handles manual CDP connections to remote browsers
- Cloud provider integration is mostly session lifecycle management (REST API to create session, get CDP URL, connect)
- Worth pursuing only if enterprise CI/CD scaling becomes a priority

One update: BrowserBase now has a simpler REST API (v2) that returns a CDP WebSocket URL directly. This would reduce implementation effort to ~40% if only BrowserBase is supported.

---

## Recommended Sprint Plan

### Sprint 1: Quick Wins (1-2 days)

Focus: Ship 8-10 Tier 1 features. Each is under 2 hours.

| Day | Features | Est. Hours |
|-----|----------|------------|
| 1 AM | PT-01 `setcontent`, PT-04 `selectall`, PT-05 `bringtofront` | 2h |
| 1 PM | PT-06 `dispatch`, PT-08 `setvalue`, PT-03 `tap` | 3h |
| 2 AM | PT-11 `permissions`, PT-12 `timezone`, PT-13 `locale` | 3h |
| 2 PM | PT-07 `multiselect`, PT-10 `clipboard`, PT-02 `innertext` | 3h |

**Deliverable**: 12 new commands, all with CLI + SCI + tests.

### Sprint 2: Medium Features (3-5 days)

Focus: Tier 2 features that close competitive gaps.

| Day | Features | Est. Hours |
|-----|----------|------------|
| 3 | PT-14 Content injection (3 commands) | 4h |
| 4 | PT-20 Diff engine (snapshot diff first) | 6h |
| 5 | PT-20 Diff engine (screenshot diff + URL diff) | 6h |
| 6 | PT-16 `responsebody`, PT-17 `styles` | 5h |
| 7 | PT-21 HAR recording, PT-09 `waitfordownload` | 4h |

**Deliverable**: Diff engine (the biggest competitive gap), plus 5 utility commands.

### Sprint 3: Polish & Infrastructure (2-3 days)

| Day | Features | Est. Hours |
|-----|----------|------------|
| 8 | PT-23 Config file (`spel.json`) | 5h |
| 9 | PT-24 Domain allowlist, PT-19 `pause` | 4h |
| 10 | PT-22 Video CLI commands, PT-15 `expose`, PT-18 `evalhandle` | 5h |

**Deliverable**: Config file for ergonomics, safety features, remaining utility commands.

### Backlog (as needed)

- PT-25 Auth vault: Only if `state save/load` proves insufficient
- PT-29 Profiler: Only if performance testing becomes strategic
- PT-31 iOS: Only if mobile Safari testing becomes strategic
- PT-32 Cloud providers: Only if enterprise CI scaling is needed
- PT-26 Action policy: Skip (domain allowlist covers safety)
- PT-27 Screencast: Skip (headed mode + video recording covers this)
- PT-28 CDP input injection: Skip (Playwright input API is sufficient)
- PT-30 Window management: Skip (sessions + tabs cover this)

---

## Decision Log

| Feature | Decision | Reasoning |
|---------|----------|-----------|
| Action policy | **Skip** | Domain allowlist is simpler and more effective for safety |
| Screencast | **Skip** | `--headed` for live, video recording for playback |
| CDP input injection | **Skip** | Playwright input API is higher-level and cross-browser |
| Window management | **Skip** | `--session` and `tab new` already cover the use cases |
| Auth vault | **Defer** | `state save/load` covers 80% of the need |
| Profiler | **Defer** | Playwright traces cover most debugging needs |
| iOS | **Defer** | Heavy dependency, niche audience (see TASK-010) |
| Cloud providers | **Defer** | `connect` covers manual case (see TASK-011) |

---

*Generated March 1, 2026. Reassess quarterly or when strategic priorities shift.*
