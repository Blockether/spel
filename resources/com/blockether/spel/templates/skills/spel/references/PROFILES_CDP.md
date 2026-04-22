# Browser profiles, device emulation, and CDP modes

Use this guide to pick the right browser startup mode for automation.

## Startup modes

| Mode | Flag | Behavior | Best for |
|---|---|---|---|
| Default | *(none)* | Starts managed browser context | Standard scripted flows |
| Auto-connect | `--auto-connect` | Connects to existing Chromium-family browser via CDP | Reusing a running browser |
| Auto-launch | `--auto-launch` | Launches isolated browser with unique debug port | Parallel isolated runs |
| Explicit CDP | `--cdp <url>` | Attaches to known DevTools endpoint | Advanced local setups |

## Profiles

Use your real Chrome/Edge profile when you need existing cookies, extensions, or saved state.

```bash
spel --channel chrome --profile "$HOME/.config/google-chrome/Default" open https://example.com
```

Notes:
- Avoid sharing the same profile across concurrent runs.
- If a profile is locked, close other browser instances or use a temp profile.

## Storage state

For portable auth without full profile coupling:

```bash
spel state export -o auth.json
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
