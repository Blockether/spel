# iOS provider — native applications and hybrid WKWebViews

`--provider ios` binds Appium/XCUITest to an installed application by bundle
identifier, or installs a simulator-built `.app`. The outer context is
`NATIVE_APP`, where compact XCTest snapshots provide clickable `@refs`.
macOS + Xcode + Appium are required.

## Start one session and keep it

First run `spel version`; the generated skill may be older than the installed
binary. Resolve one unique session name once and pass that exact value to every
command. Do not evaluate `$(date +%s)` again in later shells.

```bash
spel version
SESSION="ios-check-$(date +%s)"
spel --session "$SESSION" --provider ios --bundle-id com.example.app snapshot -i
spel --session "$SESSION" screenshot /tmp/ios-before.png
spel --session "$SESSION" click @e1a2b3
spel --session "$SESSION" snapshot -i       # refs are stale after state changes
spel --session "$SESSION" screenshot /tmp/ios-after.png
spel --session "$SESSION" close
```

Use `--app build/My.app` instead of `--bundle-id` to install a Simulator build.
Select the device with `--device "iPhone 16 Pro"`, `--udid <UDID>`, or
`--platform-version 18.2`. Use `--appium-url <url>` only to attach to an
external Appium server; record ownership so cleanup stops only what this run
started.

## Native truth and WebView truth

Use both surfaces for hybrid applications:

- **Native snapshot/screenshot:** physical screen bounds, software keyboard,
  notification banners, system sheets, orientation, and what a user can tap.
- **`with-webview-context`:** DOM state, active element, `innerWidth`/
  `innerHeight`, `visualViewport`, computed styles, and application metrics.

A DOM measurement alone cannot prove there is no physical overflow. An XCTest
command succeeding cannot prove the web application painted the right frame.
Capture visual evidence for visual claims.

```bash
spel --session "$SESSION" eval-sci '
  (spel/with-webview-context
    {:title (spel/title)
     :url (spel/url)
     :metrics (spel/evaluate
                "JSON.stringify({inner:[innerWidth,innerHeight],root:[document.documentElement.clientWidth,document.documentElement.clientHeight],active:document.activeElement?.tagName})")})'
```

An exact context can be requested when auto-selection is ambiguous:

```clojure
(spel/with-webview-context
  {:timeout-ms 15000 :context "WEBVIEW_com.example.app"}
  {:title (spel/title)
   :metadata (spel/evaluate "window.checkoutMetadata")})
```

The body runs only after an inspectable WebView becomes active, and the exact
prior context is restored after success or failure. WKWebView DOM access
requires `isInspectable = true` on iOS 16.4+; native XCTest automation remains
available otherwise. Do not manually switch the session context around this
macro.

## Repeatable keyboard, lifecycle, and orientation loops

Drive lifecycle and orientation through the public SCI API, then verify the
observable state through native and WebView surfaces.

```bash
# Background/resume the bound app.
spel --session "$SESSION" eval-sci '(spel/ios-background-app! 5)'
spel --session "$SESSION" eval-sci '(spel/ios-activate-app!)'

# Read and change physical orientation.
spel --session "$SESSION" eval-sci '(spel/ios-orientation)'
spel --session "$SESSION" eval-sci '(spel/ios-set-orientation! :landscape)'
spel --session "$SESSION" screenshot /tmp/ios-landscape.png
spel --session "$SESSION" eval-sci '(spel/ios-set-orientation! :portrait)'
spel --session "$SESSION" screenshot /tmp/ios-portrait.png
```

For a performance or viewport bug, use a loop rather than one anecdotal run:

1. Recreate the same precondition (keyboard up/down, stream active, scroll
   position, orientation).
2. Capture native screenshot/bounds and WebView metrics before the action.
3. Perform one user-visible action through Spel.
4. Poll the smallest observable state and record the first matching frame.
5. Capture after-state evidence, repeat both directions, then compare.

Report the simulator/device, iOS version, keyboard state, and sampling method.
The wall time of `spel click`, `ios-set-orientation!`, or any Appium command
includes WebDriver dispatch and XCUITest quiescence. It is **not** pure app
latency. Do not present that wall time as animation duration; measure the first
observable matching frame instead.

`(spel/ios-hide-keyboard!)` delegates to WebDriverAgent. Some hybrid WKWebView
screens do not support that endpoint. A multi-second WDA failure is automation
timeout, not application keyboard latency. Prefer the same dismissal a user
performs (tap a visible control, blur through the app, navigate) and verify the
keyboard actually disappeared. Use `ios-hide-keyboard!` only when the target
supports it.

## Notification-open reproduction on Simulator

Spel owns the app lifecycle and verification; `simctl` supplies the simulator
push. Keep the app session named throughout.

1. Put the app in the exact precondition, such as keyboard visible.
2. `(spel/ios-background-app! 30)`.
3. Deliver a valid payload with `xcrun simctl push <UDID> <bundle-id> payload.apns`.
4. Take a fresh native screenshot/snapshot of the banner and tap its fresh ref.
5. If SpringBoard does not expose a stable ref, derive a coordinate from that
   fresh screenshot/device bounds. Never copy a hard-coded coordinate between
   devices or orientations.
6. Verify the resumed physical bounds and WebView geometry, not merely that the
   tap command returned successfully.

## If the iOS provider itself fails

Do not silently switch to raw Appium and call the product verified.

1. Capture `spel version`, `spel --session "$SESSION" health --json`, and
   `spel --session "$SESSION" logs -n 100`.
2. Reduce the failure against a simple native app such as Settings.
3. Use raw Appium/XCUITest only to isolate whether the defect is in Spel,
   Appium/WDA, or the application.
4. If Spel is defective, fix and test Spel first, then rerun the original flow
   through the public Spel CLI/SCI API. Raw Appium is diagnostic evidence, not
   the final acceptance path.

Old native binaries before 0.9.17 can fail while parsing XCTest XML with a SAX
parser arity error. Upgrade before debugging the application, then regenerate
the skill so its API reference matches the binary.

## Selectors and capability limits

Native selectors: `accessibility-id=`, `id=`, `role=`, `xpath=`,
`class-chain=`, and `predicate=`. An unprefixed native selector is an
accessibility id. Native snapshots, queries, waits, `spel/click`, and
`spel/scroll` reuse the normal SCI/CLI APIs. Playwright-only CDP, tracing/HAR,
network mocking, frames, tabs, emulation, and `--allowed-domains` are
unsupported for the iOS provider.

Before finishing, close the exact named Spel session. Stop Appium only if this
run started it; externally attached Appium is user-owned.

## Related commands

| Command | Purpose |
|---------|---------|
| `spel --session <name> --provider ios --bundle-id <id> snapshot -i` | Start native iOS automation and return fresh XCTest refs |
| `spel --session <name> click @eXXX` / `click <x> <y>` | Native XCTest ref or coordinate touch |
| `spel --session <name> scroll up\|down\|left\|right [px]` | Native touch scrolling |
| `(spel/with-webview-context ...)` | Scoped WKWebView DOM/metric access with context restoration |
| `(spel/ios-background-app! n)` / `(spel/ios-activate-app!)` | Lifecycle reproduction |
| `(spel/ios-orientation)` / `(spel/ios-set-orientation! :portrait)` | Read or change physical orientation |
| `(spel/ios-devices)` / `(spel/ios-doctor)` | Discover Simulators / check prerequisites |
