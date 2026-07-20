# iOS provider — native applications and hybrid WKWebViews

`--provider ios` binds Appium/XCUITest to an installed application by bundle
identifier, or installs a simulator-built `.app`. The normal outer context is
`NATIVE_APP`, where compact XCTest snapshots provide clickable `@refs`.
macOS + Xcode + Appium are required.

```bash
spel --provider ios --bundle-id com.example.app snapshot -i
spel click @e1a2b3                                # native XCTest ref
spel click 'accessibility-id=Sign in'             # id=/role=/xpath=/predicate= too
spel get text @e1a2b3 && spel get count 'role=button'
spel wait 'role=button' --timeout 10000
spel click 200 400 && spel scroll down 400
```

Provider setup and orchestration are SCI-first:

```clojure
(spel/ios-doctor)
(spel/ios-devices)

(spel/with-webview-context
  {:title (spel/title)
   :url (spel/url)
   :button-count (spel/count-of "button")
   :metadata (spel/evaluate "window.checkoutMetadata")})

(spel/with-webview-context
  {:timeout-ms 15000
   :context "WEBVIEW_com.example.app"}
  {:title (spel/title)
   :metadata (spel/evaluate "window.checkoutMetadata")})
;; The exact prior context is restored after success or failure.
```

The body is evaluated only after an inspectable WebView becomes active, so
JavaScript and DOM operations run inside the temporary scope. Its final value
is returned unchanged. WKWebView DOM access requires `isInspectable = true`
on iOS 16.4+; native XCTest automation remains available otherwise. Do not manually switch the
session's active context.

Target selection: `--bundle-id com.example.app` for an installed app or
`--app build/My.app` to install a Simulator build. Native selectors:
`accessibility-id=`, `id=`, `role=`, `xpath=`, `class-chain=`, `predicate=`;
an unprefixed native selector is an accessibility id. Device selection uses
`--device "iPhone 16 Pro"`, `--udid <UDID>`, or `--platform-version 18.2`;
`--appium-url <url>` reuses external Appium. SCI exposes read-only context
diagnostics plus application lifecycle, installation, deep-link, permission,
and keyboard functions. Native snapshots, queries, waits, `spel/click`, and
`spel/scroll` reuse the normal SCI/CLI APIs. Playwright-only CDP, tracing/HAR,
network mocking, frames, tabs, emulation, and `--allowed-domains` remain
unsupported.

## Related CLI (from SKILL.md)

| Command | Purpose |
|---------|---------|
| `spel --provider ios --bundle-id <id> snapshot` | Native iOS app + hybrid WKWebView automation |
| `spel click @eXXX` / `spel click <x> <y>` | Native XCTest click or coordinate touch |
| `spel scroll up\|down\|left\|right [px]` | Native touch scrolling |
| `(spel/ios-devices)` / `(spel/ios-doctor)` | Discover iOS Simulators / check prerequisites via SCI |
