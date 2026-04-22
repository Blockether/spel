# Environment Variables Reference

All spel env vars optional. **CLI flags always take priority over env vars.**

## Browser Configuration

| Env Var | CLI equivalent | Description |
|---------|---------------|-------------|
| `SPEL_CHANNEL` | `--channel` | Browser channel: `chrome` (default), `msedge`, `brave` |
| `SPEL_PROFILE` | `--profile` | Chrome/Edge user data directory — extensions, passwords, bookmarks, everything |
| `SPEL_LOAD_STATE` | `--load-state` | Playwright storage state JSON path (alias: `SPEL_STORAGE_STATE`) — cookies + localStorage from `state export` |
| `SPEL_EXECUTABLE_PATH` | `--executable-path` | Custom browser binary path |
| `SPEL_USER_AGENT` | `--user-agent` | Custom user agent string |
| `SPEL_STEALTH` | `--no-stealth` | Set to `false` to disable stealth mode (ON by default) |

## Session

| Env Var | CLI equivalent | Description |
|---------|---------------|-------------|
| `SPEL_SESSION` | `--session` | Session name (default: `default`). Use unique names for concurrent automation |
| `SPEL_JSON` | `--json` | Set to `true` for JSON output (machine-readable) |
| `SPEL_TIMEOUT` | `--timeout` | Command timeout in milliseconds |

## Network

| Env Var | CLI equivalent | Description |
|---------|---------------|-------------|
| `SPEL_PROXY` | `--proxy` | Proxy server URL (e.g. `http://proxy.corp.com:8080`) |
| `SPEL_PROXY_BYPASS` | `--proxy-bypass` | Comma-separated bypass patterns |
| `SPEL_HEADERS` | `--headers` | Default HTTP headers as JSON string |
| `SPEL_IGNORE_HTTPS_ERRORS` | `--ignore-https-errors` | Set to `true` to ignore HTTPS certificate errors |

## SSL/TLS (Corporate Proxy)

| Env Var | Format | Description |
|---------|--------|-------------|
| `SPEL_CA_BUNDLE` | PEM file | Extra CA certs (merged with system defaults). Use for corporate proxies like Zscaler, Netskope |
| `NODE_EXTRA_CA_CERTS` | PEM file | Same as `SPEL_CA_BUNDLE`, also respected by Node.js subprocess (Playwright) |
| `SPEL_TRUSTSTORE` | JKS/PKCS12 | Java truststore path (alternative to PEM) |
| `SPEL_TRUSTSTORE_TYPE` | String | Truststore type (default: `JKS`) |
| `SPEL_TRUSTSTORE_PASSWORD` | String | Truststore password |

### Corporate Proxy Setup

```bash
# Before running spel install
export SPEL_CA_BUNDLE=/path/to/corporate-ca.pem
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
spel install --with-deps
```

## Daemon Lifecycle

| Env Var | CLI equivalent | Description |
|---------|---------------|-------------|
| `SPEL_SESSION_IDLE_TIMEOUT` | — | Auto-shutdown daemon after this many ms of inactivity (default: `1800000` = 30 min, `0` disables) |
| `SPEL_CDP_IDLE_TIMEOUT` | — | Auto-shutdown daemon after CDP disconnect if no reconnect within this window (ms, default: `1800000` = 30 min, `0` disables) |
| `SPEL_CDP_LOCK_WAIT` | — | Max seconds to wait for CDP route lock release (default: `120`, `0` = fail immediately) |
| `SPEL_CDP_LOCK_POLL_INTERVAL` | — | Poll interval in seconds when waiting for CDP route lock (default: `2`) |

## Advanced

| Env Var | CLI equivalent | Description |
|---------|---------------|-------------|
| `SPEL_CDP` | `--cdp` | Connect to existing browser via Chrome DevTools Protocol URL |
| `SPEL_AUTO_CONNECT` | `--auto-connect` | Auto-discover running chromium-family CDP endpoint — Chrome/Edge/Brave/Vivaldi/Opera/Arc/Thorium/Chromium (any value) |
| `SPEL_AUTO_LAUNCH` | `--auto-launch` | Launch browser with debug port, per-session isolation (any value) |
| `SPEL_ARGS` | `--args` | Extra Chromium launch args (comma-separated) |
| `SPEL_DEBUG` | `--debug` | Set to `true` for verbose debug logging |

## Examples

### Persistent Chrome Profile

```bash
export SPEL_PROFILE=~/.config/google-chrome/Default
spel open https://example.com  # uses your real Chrome with extensions
```

### Edge with Corporate Proxy

```bash
export SPEL_CHANNEL=msedge
export SPEL_PROFILE=~/.config/microsoft-edge/Default
export SPEL_CA_BUNDLE=/etc/ssl/certs/zscaler.pem
export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/zscaler.pem
spel open https://internal.company.com
```

### JSON Output for Automation

```bash
export SPEL_JSON=true
spel open https://example.com  # outputs JSON instead of human-readable
```

### Unique Session for Parallel Automation

```bash
export SPEL_SESSION=run-$(date +%s)
spel open https://example.com
# ... do work ...
spel close
```
