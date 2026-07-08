# Bridge — CDP-free in-page automation (`spel bridge`)

The bridge lets spel drive a **real browser tab without CDP**. It works where
classic Playwright/CDP automation is dead — locked-down corporate machines,
managed browsers, disabled DevTools Protocol — because it never touches the
debugging protocol at all.

## Why it exists

CDP is often disabled in corporate environments, so `--cdp` / `--auto-connect`
/ `--auto-launch` cannot attach. But **loopback traffic (`127.0.0.1`) never
leaves the machine**, so a page that embeds a small script can talk to a local
spel server the proxy/firewall never sees. That is the whole trick: you stop
fighting the network and move the conversation on-box.

The moving parts:

- **`spel.js`** — a pure, dependency-free browser engine (no bundler, no
  extension, no CDP) that installs `window.__spel` with a single
  `invoke(command)` entry point mapping the spel/Playwright verb surface onto
  real DOM operations. It ships **inside the native image** as a classpath
  resource.
- **The bridge server** — a JDK-native loopback HTTP server
  (`com.sun.net.httpserver`, no extra deps) that serves `spel.js` and a
  two-way transport: **SSE** for commands (server → browser) and **JSON POST**
  for results (browser → server).

SSE + POST is used rather than WebSocket because it is JDK-native and behaves
predictably on loopback. `spel.js` tries WebSocket first, then falls back to
the SSE path the bundled server implements.

## CLI

```
spel bridge                         Start the loopback bridge (SSE + POST) on 127.0.0.1:8787
spel bridge use [url]               Route regular `spel <verb>` commands through the bridge
                                    (bare = the local bridge; or a remote http://host:port/spel)
spel bridge off                     Stop routing — commands go back to the Playwright daemon
spel bridge status                  Show the saved target + whether a browser tab is connected
spel bridge --eject                 Print the embedded spel.js to stdout
spel bridge --eject -o spel.js      Write spel.js to a file
spel bridge --eject --bookmarklet   Print a `javascript:` bookmarklet loader
spel bridge --eject --console       Print the same loader without the prefix (paste into DevTools)
```

Options: `--host` (default `127.0.0.1`), `-p/--port` (default `8787`),
`--path` (default `/spel`), `-o/--output <file>`, `--token <token>` (default:
auto-generated). `--bookmarklet` / `--console` accept an optional
`http://host:port/spel` URL to target a remote bridge, and pick up the running
bridge's token automatically. If the fixed port is busy the bridge falls back to
an ephemeral one and prints it.

## Two ways to talk to a page

### 1. Embed the engine (full power, load it FIRST)

```html
<script src="http://127.0.0.1:8787/spel.js"></script>
<script>window.__spel.connect({url:"http://127.0.0.1:8787/spel",token:"<token>"})</script>
```

Open `http://127.0.0.1:8787/` for a ready-made harness page that does exactly
this. **Load `spel.js` as the first `<script>` in `<head>`** if you want full
network capture — see "Network capture" below.

### 2. Bookmarklet / console loader (when you don't control the HTML)

`spel bridge --eject --bookmarklet` emits a draggable `javascript:` bookmarklet
(Edge favorites / Chrome bookmarks — same Chromium engine). Clicking it on any
page injects `<origin>/spel.js` and connects. Idempotent: a second click just
re-connects. `--console` emits the same loader without the `javascript:` prefix
for pasting into the DevTools Console or saving as a Sources Snippet (bypasses
bookmark restrictions).

Caveat: bookmarklets load **late** (you click manually), so they are good for
*steering* a page but miss early network traffic. And a page's
`Content-Security-Policy` or a managed-browser policy can still block
inline/bookmarklet execution — that is policy, not network, and cannot be
worked around from the script.

## Routing regular `spel` commands through the bridge

Once you run `spel bridge use`, every regular `spel <verb>` (`click`, `fill`,
`snapshot`, `get_text`, …) is sent to the connected in-page engine over the
loopback bridge **instead of** the Playwright daemon — no `bridge send`, no CDP.
The target ("where/how we talk") is saved in **`~/.spel/bridge.json`**
(`{"url":"http://127.0.0.1:8787/spel"}`). Turn it off with `spel bridge off`;
inspect it with `spel bridge status`. On the same box `spel bridge use` (bare)
also copies the running bridge's **token** from `~/.spel/bridge-runtime.json`, so
routed commands authenticate with zero extra flags; for a remote bridge pass
`spel bridge use http://host:port/spel --token <token>`.

```bash
spel bridge &                       # start the server
spel bridge use                     # save the local bridge as the route
# ... open a page that embeds spel.js and connects ...
spel snapshot -i                    # runs in the real tab, via the bridge
spel click @e12
spel fill @e7 "hello"
spel bridge off                     # back to the daemon
```

Output shape is identical whether a command hit the daemon or the bridge (the
bridge adapts the engine's `{ok,value,error}` into the daemon's
`{:success :data :error}`).

## Security — token-gated loopback

Loopback is on-box, but **any page open in the same browser can `fetch`
`http://127.0.0.1:<port>`**. To stop a rogue page driving the tab or reading
captured traffic, the bridge auto-generates a **token** on start and only accepts
requests that carry it:

- **SSE** (`GET /spel`) — token as `?t=` (EventSource cannot set headers).
- **`/spel/command`** and **`/spel/result`** — `?t=` or an `X-Spel-Token` header.

A missing/wrong token gets a `403`. The token is embedded into the served
harness page and the ejected loader/bookmarklet, published to
`~/.spel/bridge-runtime.json` for same-box discovery, and passed by
`route-command!` when routing regular commands. `spel.js` public source
(`/spel.js`) carries no secret. Setting an empty token disables auth (not
recommended).

## Surviving navigation (re-inject)

`window.__spel` lives in the page, so a **full-page navigation** rebuilds the
window and drops the engine + its SSE connection. To survive that, `connect`
remembers its route (`{url, token}`) in **sessionStorage** (per-tab, per-origin).
When a fresh page re-loads `spel.js` — e.g. a site template that `<script
src>`-embeds it on every page — the engine reads that route on install and
**auto-reconnects** without a manual `connect`. `connect` also replaces any
prior connection, so a tab holds exactly one live SSE/WS. `disconnect` clears
the saved route.

Caveats: SPA (same-document) route changes keep the engine alive and need
nothing. A bookmarklet-injected engine still dies on navigation because the new
page does not re-load `spel.js` — only embedding it in the page's HTML gives
true re-inject across full navigations.

## What the engine can do (`window.__spel.invoke`)

All driven through the single `invoke(command)` entry point, returning a
uniform `{action, ok, value|error}` promise (never rejects). ~80 handlers:

- **Interaction** — `click`, `dblclick`, `hover`, `focus`, `fill`, `clear`,
  `type`, `press` (modifier parsing), `keyboard_type`, `keydown`, `keyup`,
  `check`, `uncheck`, `select`, `scrollintoview`, `scroll`, `drag`, `highlight`.
- **Read/props** — `get_text`, `get_value`, `input_value`, `get_attribute`,
  `get_property`, `text_content`, `inner_text`, `inner_html`, `tag_name`,
  `get_count`, `count`.
- **State checks** — `is_visible`, `is_enabled`, `is_checked`, `is_editable`,
  `is_hidden`, `is_disabled`, `is_focused`.
- **Geometry/overflow** — `bounding_box`, `get_box`, `get_styles`,
  `overflow_info`, `is_overflowing`, `is_clipped`, `scroll_position`,
  `viewport_size`.
- **ARIA** — `get_role`, `get_accessible_name`, `get_aria`, `aria_snapshot`,
  plus the interactive `snapshot` (`@eXXX` refs via `data-pw-ref`).
- **Navigation** — `url`, `title`, `content`, `reload`, `back`, `forward`,
  `wait_for` (state `visible|hidden|attached|detached`, timeout).
- **Refs** — `resolve_ref`, `clear_refs`, `find`.
- **Storage** — `storage_get`, `storage_set`, `storage_clear`.
- **Network capture** — `network_list`, `network_requests`, `network_get`,
  `network_get_ref`, `network_clear`, `network_capture`.
- **Overlay picker** — `pick`, `pick_stop`, `pick_toggle`, `picked`.
- **Server/transport** — `configure`, `set_server`, `get_server`,
  `choose_server`, `connect`, `disconnect`, `is_connected`, `ping`, `ready`.
- **Escape hatch** — `evaluate` (arbitrary in-page JS).

### Selector convention

`@eXXX` (regex `@e[a-z0-9]+`) resolves a snapshot ref
(`[data-pw-ref='eXXX']`); anything else is treated as a CSS selector.

## Network capture (no CDP)

Without CDP you cannot attach to the debugging protocol, but pure in-page JS
captures traffic three ways: `window.fetch` (wrapped — method, URL, status,
request/response headers, request + response body via `clone().text()`,
timing, size), `XMLHttpRequest` (wrapped the same), and `PerformanceObserver`
(passive resources — img/script/css/beacon — URL, `transferSize`, timings; no
body/headers).

**Consequence: load `spel.js` first.** Capture works by *wrapping* `fetch`/XHR
at execution time — anything that fired before the script ran used the
original, unwrapped functions and is invisible. So: synchronous first
`<script>` in `<head>` for full capture; a late bookmarklet only sees traffic
from click-time onward.

## Overlay element picker (keymap)

The engine installs a global keydown listener:

- **Ctrl+Shift+L** — toggle the overlay picker: hover highlights elements, a
  click resolves the target to a ref/selector (retrieve with the `picked`
  handler). `Escape` stops it.
- **Ctrl+Shift+K** — choose a different spel server (`prompt()` for the URL),
  then (re)connect.

Both hotkeys are configurable via the `configure` handler
(`{:hotkey … :serverHotkey … :server …}`).

## Distribution & native image

`spel.js` lives at `resources/com/blockether/spel/browser/spel.js` (classpath),
so it ships **inside the native binary** — the resource pattern
`com/blockether/spel/browser/.*` is listed in `resource-config.json`.
`spel bridge --eject` unpacks it from the running binary for standalone hosting.

## Limitations — document these upfront

Pure in-page JS cannot replicate everything Playwright's driver does. These do
**not** work through the bridge (by design, no CDP):

- **True cross-origin isolation / real network interception & mocking** — you
  can *observe* fetch/XHR, not block or rewrite them at the protocol layer
  (that is CDP/`route`). Use `references/NETWORK_ROUTING.md` (daemon path) when
  you need real interception.
- **Cross-origin iframe reach** — same-origin frames only; the browser's
  same-origin policy blocks the rest.
- **New tabs / popups / downloads / file chooser at the OS level** — no driver
  to own the new target; `click --new-tab` and native file dialogs are out.
- **Real trusted input at the OS level** — events are synthetic
  (`dispatchEvent` + native value setters); most apps accept them, but a site
  checking `event.isTrusted` will not.
- **Traffic before the engine loads** — see "Network capture" (load first).
- **Pages that forbid injection** — a strict `Content-Security-Policy` or a
  managed-browser policy can block the `<script>`/bookmarklet outright.
- **Multi-tab targeting** — a routed command is broadcast to every subscribed
  tab and the *first* result wins; there is no per-tab addressing yet. Keep one
  tab connected per bridge for deterministic routing.
- **Server-pushed events** — the transport is pull-only. There is no server-side
  subscription to `console` / `dialog` / JS errors / navigation events; poll
  in-page state (`wait_for`, `network_list`) instead.

When any of these matter, prefer the daemon + CDP path (`--cdp`,
`--auto-connect`, `--auto-launch`) documented in `references/PROFILES_CDP.md`.
