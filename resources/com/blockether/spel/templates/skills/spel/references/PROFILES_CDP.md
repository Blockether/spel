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

Attach without navigating or opening a tab:

```bash
SESSION="agent-$(date +%s)"
spel --session "$SESSION" --auto-connect session
# Or, for a known endpoint:
# spel --session "$SESSION" --cdp http://127.0.0.1:9222 session
spel --session "$SESSION" tab
spel --session "$SESSION" open https://example.com  # opens a spel-owned tab
spel --session "$SESSION" cdp disconnect  # closes spel-owned tabs and detaches
spel --session "$SESSION" close           # stops the daemon; the user's browser stays open
```

Notes:
- `403 Forbidden` / rejected origin during connect almost always means `--remote-allow-origins='*'` was missing.
- The browser must be fully quit before relaunching; a surviving process ignores the new flags.
- Wrong `--profile-directory` yields a logged-out browser; list profiles first (`ls "$HOME/Library/Application Support/Microsoft Edge"`).
- Some builds also gate this behind a devtools/remote-debugging setting in browser settings.
- Use `cdp disconnect` before stopping an attached session to clean up its own tabs. Never close the user's tabs.

### Tab ownership on an attached browser

spel reuses the browser's existing context, including cookies and logins. `--auto-connect session` and `--cdp <url> session` attach without creating a tab. Repeating the command reuses that named connection. A bare `session` command only reports status; it never attaches.

The first page command, such as `open`, creates a spel-owned tab rather than navigating a user's existing tab. Explicit `connect <url>` still opens a spel-owned tab immediately. `tab` and `session list` do not create tabs.

| Resource | Owner | spel may close it? |
|---|---|---|
| The browser and its context | User | No |
| Tabs that existed before attach | User | No |
| Tabs the user opens after attach | User | No |
| Tabs opened by spel (`connect`, `tab new`) | spel | Yes |

- `tab list` / `tab switch` still see every tab; only closing is restricted.
- Closing a foreign tab fails with `:error_code "tab_not_owned"`.
- `cdp disconnect` closes only spel-opened tabs and detaches the local driver; the user's browser keeps running. `close` and `kill` force-stop the daemon without waiting for browser cleanup, so disconnect first when you want to remove spel-owned tabs.

Discovery reads advertised endpoints and checks reachability without opening a WebSocket. Only the actual attachment requests browser authorization. A live port does not prove a cached browser target is still valid: the bounded attachment rejects stale targets. If that happens, rediscover the endpoint or connect to `http://127.0.0.1:9222` directly when the browser exposes HTTP CDP.

Tab and session listings redact credential values in URL query parameters and fragments, including OAuth tokens and authorization codes. This applies to human-readable and `--json` output; it does not change the browser's URL. Raw page content and explicit evaluation results are not sanitized, so avoid printing credentials from those surfaces.


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
