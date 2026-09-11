# Browser profiles, device emulation, and CDP modes

**Use when:** Choose a launch mode or attach to an authorized existing browser. Ask before disrupting user-owned browsers or using personal profiles.

Use this guide to pick the right browser startup mode for automation.

## Startup modes

| Mode | Flag | Behavior | Best for |
|---|---|---|---|
| Default | *(none)* | Starts managed browser context | Standard scripted flows |
| Auto-connect | `--auto-connect` | Connects to existing Chromium-family browser via CDP | Reusing a running browser |
| Auto-launch | `--auto-launch` | Launches isolated browser with unique debug port | Parallel isolated runs |
| Explicit CDP | `--cdp <url>` | Attaches to known DevTools endpoint | Advanced local setups |

## Attaching to a browser the user already has open

Attachment requires a browser started with remote debugging. Ask before relaunching a user-owned browser; prefer an isolated instance. The following is a manual setup example, not permission to close a running browser.

```bash
# macOS example (Edge; Chrome is the same with its own binary path)
osascript -e 'tell application "Microsoft Edge" to quit'; sleep 3
"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge" \
  --remote-debugging-address=127.0.0.1 \
  --remote-debugging-port=9222 \
  --remote-allow-origins='*' \
  --profile-directory="Default" &
```

Verify the endpoint before involving spel:

```bash
curl -s http://127.0.0.1:9222/json/version   # must return webSocketDebuggerUrl
```

Then attach and work in a named session:

```bash
SESSION="agent-$(date +%s)"
spel --session "$SESSION" connect http://127.0.0.1:9222
spel --session "$SESSION" health --json
spel --session "$SESSION" close      # detaches the session; the user's browser stays open
```

Notes:
- `403 Forbidden` / rejected origin during connect almost always means `--remote-allow-origins='*'` was missing.
- The browser must be fully quit before relaunching; a surviving process ignores the new flags.
- Wrong `--profile-directory` yields a logged-out browser; list profiles first (`ls "$HOME/Library/Application Support/Microsoft Edge"`).
- Some builds also gate this behind a devtools/remote-debugging setting in browser settings.
- On an attached browser never use `kill`; `close` the spel session only, and never close the user's tabs.

### Tab ownership on an attached browser

spel attaches to the browser's existing context (so cookies and logins are reused) but **always opens its own new tab** and drives that one. It never takes over a tab the user was using.

| Resource | Owner | spel may close it? |
|---|---|---|
| The browser and its context | User | No |
| Tabs that existed before attach | User | No |
| Tabs the user opens after attach | User | No |
| Tabs opened by spel (`connect`, `tab new`) | spel | Yes |

- `tab list` / `tab switch` still see every tab; only closing is restricted.
- Closing a foreign tab fails with `:error_code "tab_not_owned"`.
- `close` and daemon shutdown close only spel-opened tabs and detach the local driver; the user's browser keeps running.

Stale endpoints fail fast instead of hanging: a cached `ws://.../devtools/browser/<id>` that no longer exists is rejected in ~2s with `:error_code "cdp_endpoint_unreachable"`, and `session list` no longer advertises it. If that happens, re-discover with `curl http://127.0.0.1:9222/json/version` or connect to `http://127.0.0.1:9222` directly.


## Profiles

Use your real Chrome/Edge profile when you need existing cookies, extensions, or saved state.

```bash
spel --channel chrome --profile "$HOME/.config/google-chrome/Default" open https://example.com
```

Notes:
- Avoid sharing the same profile across concurrent runs.
- If a profile is locked, use an isolated temporary profile; do not close another browser without permission.

## Storage state

For portable auth without full profile coupling:

```bash
spel state save auth.json
spel --load-state auth.json open https://example.com
```

## Device emulation

CLI:

```bash
spel inspector --device "iPhone 14" https://example.com
```

Library:

```clojure
(core/with-testing-page {:device :iphone-14 :locale "en-US"} [pg]
  (page/navigate pg "https://example.com"))
```

## Session naming

Always use named sessions for concurrent work:

```bash
SESSION="run-$(date +%s)"
spel --session "$SESSION" open https://example.com
spel --session "$SESSION" close
```

## Proxy and TLS

For corporate proxy environments, configure CA certs before `spel install`:

```bash
export SPEL_CA_BUNDLE=/path/to/corp.pem
export NODE_EXTRA_CA_CERTS=/path/to/corp.pem
spel install --with-deps
```
