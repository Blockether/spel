# spel.js — pure in-page automation engine

`spel.js` is "Playwright, but a `<script>` tag": a dependency-free browser
engine that installs `window.__spel` and maps the spel/Playwright verb surface
onto real DOM operations. **No bundler, no build step, no Node, no CDP.**

It ships **inside the spel native image** as a classpath resource. This is the
canonical copy served at `GET /spel.js` by `spel bridge` and unpacked by
`spel bridge --eject`. Do not fork it elsewhere.

## Load it

```html
<!-- Load FIRST in <head> for full network capture (see below) -->
<script src="http://127.0.0.1:8787/spel.js"></script>
<script>window.__spel.connect({url:"http://127.0.0.1:8787/spel"})</script>
```

Or eject a standalone copy / a bookmarklet loader:

```bash
spel bridge --eject -o spel.js         # the raw engine
spel bridge --eject --bookmarklet      # draggable javascript: loader
spel bridge --eject --console          # same loader, paste into DevTools
```

## Contract

One entry point, one uniform result shape (a Promise that never rejects —
errors come back as `ok:false`):

```js
window.__spel.invoke({ action: "click", selector: "@e5" })
  // -> Promise<{ action, ok: true, value }>
```

Selectors: `@eNN` resolves a snapshot ref (`[data-pw-ref="eNN"]`); `text=…` /
`css=…` / `xpath=…` (or a bare CSS/`//xpath`) use the matching engine.

~100 handlers grouped as: interaction, read/props, state checks,
geometry/overflow, ARIA + snapshot, navigation (`goto`), waits (`wait_for*`),
refs, storage, cookies, network capture + same-origin `route` mocking, dialogs,
console/error capture, input (`upload`/`tap`/`dispatch_event`), same-origin
frames, env emulation (`emulate`), HAR export (`network_har`), DOM `screenshot`,
overlay picker, and server/transport (plus an `evaluate` escape hatch).

## Keymap

- **Ctrl+Shift+L** — toggle the overlay element picker (hover-highlight, click
  to capture a ref; `Escape` stops).
- **Ctrl+Shift+K** — choose a different spel server and (re)connect.

Both are configurable via the `configure` handler.

## Network capture — load early

`window.fetch` and `XMLHttpRequest` are captured by *wrapping* them at load
time; a `PerformanceObserver` picks up passive resources. So **only traffic
after this script runs is visible** — inject it as the first `<script>` in
`<head>` for a complete picture. Cross-origin bodies/headers are limited to what
CORS exposes; opaque (no-cors) bodies are unreadable.

## What it cannot do (no CDP)

Cross-origin/protocol-level interception (`route` mocks only same-origin
fetch/XHR), cross-origin iframes, OS-level tabs / downloads / file chooser,
trusted (`isTrusted`) input, PDF/video/trace capture, HAR **replay**, real
viewport resize / permission grants, and traffic before load. (`screenshot`,
`network_har` and JS-level `emulate` cover the DOM-reachable half of Playwright's
screenshot/HAR/emulation.) `console`/`dialog`/errors
are captured in-page but read by polling, not pushed as live events.
When those matter, use the daemon + CDP path (`--cdp`/`--auto-connect`).

Full docs: the skill reference `references/BRIDGE.md`.
