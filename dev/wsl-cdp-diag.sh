#!/usr/bin/env bash
# wsl-cdp-diag.sh — diagnose whether spel inside WSL can reach a Chrome/Edge
# instance running on the Windows host via Chrome DevTools Protocol (CDP).
#
# Background: spel's CDP auto-discovery reads DevToolsActivePort from the
# browser user-data dir (which it can see via /mnt/c/Users/...) and then
# tries http://127.0.0.1:<port>. Under WSL2's default NAT networking that
# 127.0.0.1 is WSL's own loopback, NOT Windows's — so the connect fails
# even though Chrome is listening. This script confirms or disproves that
# failure mode on the user's actual machine.
#
# Usage: ./dev/wsl-cdp-diag.sh
#        ./dev/wsl-cdp-diag.sh --port 9222    # probe a specific port
#
# Run this INSIDE WSL, with a Chrome/Edge window already open on Windows.
# Exit codes:
#   0  — reachable via 127.0.0.1 (mirrored networking or port proxy; spel works)
#   1  — not running inside WSL
#   2  — Chrome reachable only via Windows host IP (NAT mode, the bug)
#   3  — Chrome not reachable at all (not launched with --remote-debugging-port,
#        or firewalled; spel bug is not the blocker)

set -u

EXPLICIT_PORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) EXPLICIT_PORT="$2"; shift 2 ;;
    --port=*) EXPLICIT_PORT="${1#*=}"; shift ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \?//'
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done

# ─────────────────────────────────────────────────────────────────────────
# Pretty output helpers
# ─────────────────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
  C_OK=$'\033[32m'; C_FAIL=$'\033[31m'; C_WARN=$'\033[33m'
  C_DIM=$'\033[2m';  C_BOLD=$'\033[1m';  C_END=$'\033[0m'
else
  C_OK=''; C_FAIL=''; C_WARN=''; C_DIM=''; C_BOLD=''; C_END=''
fi
ok()    { printf '  %s[OK]%s   %s\n'   "$C_OK"   "$C_END" "$*"; }
fail()  { printf '  %s[FAIL]%s %s\n'   "$C_FAIL" "$C_END" "$*"; }
warn()  { printf '  %s[WARN]%s %s\n'   "$C_WARN" "$C_END" "$*"; }
info()  { printf '  %s[..]%s   %s\n'   "$C_DIM"  "$C_END" "$*"; }
step()  { printf '\n%s%s%s\n' "$C_BOLD" "$*" "$C_END"; }

step "1. WSL detection"
if [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
  ok   "WSL_DISTRO_NAME=${WSL_DISTRO_NAME}"
elif [[ -n "${WSL_INTEROP:-}" ]]; then
  ok   "WSL_INTEROP set"
elif grep -qi 'microsoft\|wsl' /proc/version 2>/dev/null; then
  ok   "/proc/version says: $(tr -d '\0' </proc/version)"
else
  fail "not running inside WSL — rerun inside a WSL shell"
  exit 1
fi

# ─────────────────────────────────────────────────────────────────────────
# 2. Networking mode (mirrored vs NAT)
# ─────────────────────────────────────────────────────────────────────────
step "2. WSL networking mode"
WSLCONFIG_MODE=""
if command -v powershell.exe >/dev/null 2>&1; then
  WSLCONFIG_MODE=$(powershell.exe -NoProfile -Command '
    $cfg = Join-Path $env:USERPROFILE ".wslconfig"
    if (Test-Path $cfg) {
      $line = Select-String -Path $cfg -Pattern "^\s*networkingMode\s*=" -ErrorAction SilentlyContinue
      if ($line) { ($line.Line -split "=")[1].Trim() } else { "" }
    } else { "" }
  ' 2>/dev/null | tr -d '\r\n' || true)
fi
if [[ -n "$WSLCONFIG_MODE" ]]; then
  info "%USERPROFILE%\\.wslconfig → networkingMode=${WSLCONFIG_MODE}"
  if [[ "$WSLCONFIG_MODE" == "mirrored" ]]; then
    ok "mirrored networking — 127.0.0.1 inside WSL IS Windows 127.0.0.1"
  else
    warn "networkingMode=${WSLCONFIG_MODE} — loopback is NOT unified with Windows"
  fi
else
  warn "no networkingMode= line in .wslconfig → defaulting to NAT (separate loopback)"
fi

# ─────────────────────────────────────────────────────────────────────────
# 3. Windows host IP (default gateway from WSL's POV)
# ─────────────────────────────────────────────────────────────────────────
step "3. Windows host IP"
WIN_IP=$(ip route show default 2>/dev/null | awk '/default/ {print $3; exit}')
if [[ -n "$WIN_IP" ]]; then
  ok "default gateway (= Windows host under NAT mode): ${WIN_IP}"
else
  warn "no default gateway visible — unusual, may be mirrored mode or VPN"
fi

# ─────────────────────────────────────────────────────────────────────────
# 4. Windows username (used to locate /mnt/c/Users/<them>)
# ─────────────────────────────────────────────────────────────────────────
step "4. Windows username"
WIN_USER=""
if command -v cmd.exe >/dev/null 2>&1; then
  WIN_USER=$(cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r\n')
fi
if [[ -z "$WIN_USER" ]]; then
  # Fallback: pick the first non-system directory under /mnt/c/Users.
  # Glob loop instead of `ls | grep` to handle usernames with spaces.
  if [[ -d /mnt/c/Users ]]; then
    shopt -s nullglob
    for d in /mnt/c/Users/*/; do
      name="${d%/}"; name="${name##*/}"
      case "$name" in
        Public|Default|"Default User"|"All Users"|desktop.ini|WsiAccount) ;;
        *) WIN_USER="$name"; break ;;
      esac
    done
    shopt -u nullglob
  fi
fi
if [[ -n "$WIN_USER" ]]; then
  ok "Windows user: ${WIN_USER}"
else
  fail "could not determine Windows username — /mnt/c/Users not mounted?"
fi

# ─────────────────────────────────────────────────────────────────────────
# 5. Locate DevToolsActivePort across known Chromium-family browsers
# ─────────────────────────────────────────────────────────────────────────
step "5. Find Chrome/Edge DevToolsActivePort"
# Mirrors the catalog in src/com/blockether/spel/daemon.clj chromium-browser-catalog
declare -a BROWSERS=(
  "Google Chrome|/mnt/c/Users/${WIN_USER}/AppData/Local/Google/Chrome/User Data/DevToolsActivePort"
  "Chrome Beta|/mnt/c/Users/${WIN_USER}/AppData/Local/Google/Chrome Beta/User Data/DevToolsActivePort"
  "Chrome Canary|/mnt/c/Users/${WIN_USER}/AppData/Local/Google/Chrome SxS/User Data/DevToolsActivePort"
  "Chromium|/mnt/c/Users/${WIN_USER}/AppData/Local/Chromium/User Data/DevToolsActivePort"
  "Microsoft Edge|/mnt/c/Users/${WIN_USER}/AppData/Local/Microsoft/Edge/User Data/DevToolsActivePort"
  "Edge Beta|/mnt/c/Users/${WIN_USER}/AppData/Local/Microsoft/Edge Beta/User Data/DevToolsActivePort"
  "Edge Dev|/mnt/c/Users/${WIN_USER}/AppData/Local/Microsoft/Edge Dev/User Data/DevToolsActivePort"
  "Brave|/mnt/c/Users/${WIN_USER}/AppData/Local/BraveSoftware/Brave-Browser/User Data/DevToolsActivePort"
  "Vivaldi|/mnt/c/Users/${WIN_USER}/AppData/Local/Vivaldi/User Data/DevToolsActivePort"
  "Arc|/mnt/c/Users/${WIN_USER}/AppData/Local/Arc/User Data/DevToolsActivePort"
)

FOUND_PORT=""
FOUND_BROWSER=""
FOUND_WS_PATH=""
for entry in "${BROWSERS[@]}"; do
  label="${entry%%|*}"
  path="${entry#*|}"
  if [[ -f "$path" ]]; then
    # First line = port, second line (optional) = /devtools/browser/<uuid>
    p=$(sed -n '1p' "$path" | tr -d '\r\n')
    w=$(sed -n '2p' "$path" | tr -d '\r\n')
    if [[ "$p" =~ ^[0-9]+$ ]] && (( p > 0 && p <= 65535 )); then
      ok "${label} running → port ${p}"
      [[ -z "$FOUND_PORT" ]] && { FOUND_PORT="$p"; FOUND_BROWSER="$label"; FOUND_WS_PATH="$w"; }
    fi
  fi
done

if [[ -n "$EXPLICIT_PORT" ]]; then
  info "overriding discovered port with --port ${EXPLICIT_PORT}"
  FOUND_PORT="$EXPLICIT_PORT"
  FOUND_BROWSER="${FOUND_BROWSER:-explicit}"
fi

if [[ -z "$FOUND_PORT" ]]; then
  fail "no DevToolsActivePort found in any known user-data-dir"
  info "launch Chrome on Windows with:"
  info "  chrome.exe --remote-debugging-port=9222"
  info "then rerun this script, OR pass --port 9222 to probe it manually"
  exit 3
fi

# ─────────────────────────────────────────────────────────────────────────
# 6. Reachability probes — HTTP AND WebSocket
# ─────────────────────────────────────────────────────────────────────────
# CDP has two surfaces on the same TCP port:
#   • HTTP  /json/version — discovery endpoint spel uses to enumerate
#     browser contexts and read the WS path
#   • WS    ${ws-path}    — actual CDP transport; Playwright's
#     connectOverCDP speaks this after discovering it via /json/version
#
# A port proxy / firewall / Chrome flag can allow one and block the other:
#   --remote-debugging-pipe disables the HTTP surface entirely
#   some netsh portproxy setups drop the WS Upgrade handshake
# So we probe BOTH and report independently.
step "6. Reachability probes (HTTP + WebSocket)"

probe_http() {
  local target="$1"
  local body
  body=$(curl -sS --max-time 2 "http://${target}/json/version" 2>/dev/null || true)
  if [[ -n "$body" ]]; then
    local browser_field
    browser_field=$(printf '%s' "$body" | grep -oE '"Browser":\s*"[^"]+"' | head -n1 | sed 's/.*: *"\([^"]*\)".*/\1/')
    ok "HTTP  http://${target}/json/version → 200 (${browser_field:-???})"
    return 0
  else
    fail "HTTP  http://${target}/json/version → NOT reachable"
    return 1
  fi
}

probe_ws() {
  local target="$1"
  local ws_path="$2"    # e.g. /devtools/browser/<uuid> — may be empty
  if [[ -z "$ws_path" ]]; then
    # No path known yet — fetch it from /json/version first
    ws_path=$(curl -sS --max-time 2 "http://${target}/json/version" 2>/dev/null \
      | grep -oE '"webSocketDebuggerUrl":\s*"ws://[^"]+"' \
      | head -n1 \
      | sed -E 's@.*"ws://[^/]+(/[^"]*)".*@\1@')
  fi
  if [[ -z "$ws_path" ]]; then
    warn "WS    ws://${target}/…  → no ws-path known (HTTP endpoint silent), skipping"
    return 1
  fi
  # Perform a real RFC 6455 Upgrade handshake via curl and check for 101.
  # Sec-WebSocket-Key is a fixed base64-encoded 16-byte nonce (RFC 6455 §4.1);
  # the server's 101 response proves the Upgrade path works end-to-end.
  local status
  status=$(curl -sS --max-time 2 --http1.1 -o /dev/null -w '%{http_code}' \
    -H 'Connection: Upgrade' \
    -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    -H 'Host: '"${target}" \
    "http://${target}${ws_path}" 2>/dev/null || echo "000")
  if [[ "$status" == "101" ]]; then
    ok "WS    ws://${target}${ws_path} → 101 Switching Protocols"
    return 0
  elif [[ "$status" == "000" ]]; then
    fail "WS    ws://${target}${ws_path} → connection refused / timeout"
    return 1
  else
    fail "WS    ws://${target}${ws_path} → HTTP ${status} (Upgrade rejected)"
    return 1
  fi
}

LOOPBACK_HTTP=false; LOOPBACK_WS=false
HOST_HTTP=false;     HOST_WS=false

probe_http "127.0.0.1:${FOUND_PORT}"          && LOOPBACK_HTTP=true
probe_ws   "127.0.0.1:${FOUND_PORT}" "$FOUND_WS_PATH" && LOOPBACK_WS=true

if [[ -n "$WIN_IP" && "$WIN_IP" != "127.0.0.1" ]]; then
  probe_http "${WIN_IP}:${FOUND_PORT}"          && HOST_HTTP=true
  probe_ws   "${WIN_IP}:${FOUND_PORT}" "$FOUND_WS_PATH" && HOST_WS=true
fi

LOOPBACK_OK=false
HOST_OK=false
# "OK" = spel can actually drive the browser, which needs BOTH HTTP
# (for discovery) AND WS (for the CDP session). Partial success still
# gets surfaced in the verdict section so the user knows what broke.
$LOOPBACK_HTTP && $LOOPBACK_WS && LOOPBACK_OK=true
$HOST_HTTP     && $HOST_WS     && HOST_OK=true

# ─────────────────────────────────────────────────────────────────────────
# 7. End-to-end spel probe
# ─────────────────────────────────────────────────────────────────────────
# curl proving the TCP/HTTP/WS path works is necessary but NOT sufficient —
# we also want to confirm spel itself can drive the browser through that
# path. Pick the first target that passed BOTH HTTP+WS, fire up a throwaway
# spel session against it, and run a trivial read-only command.
step "7. spel end-to-end probe"

SPEL_TARGET=""
if $LOOPBACK_OK; then
  SPEL_TARGET="127.0.0.1:${FOUND_PORT}"
elif $HOST_OK; then
  SPEL_TARGET="${WIN_IP}:${FOUND_PORT}"
fi

SPEL_VERDICT="skipped"
if ! command -v spel >/dev/null 2>&1; then
  warn "spel not on PATH inside WSL — install it (scoop-style drop in ~/.local/bin) and rerun"
  warn "the diagnostic to get the full end-to-end verdict."
  SPEL_VERDICT="no-spel"
elif [[ -z "$SPEL_TARGET" ]]; then
  warn "neither 127.0.0.1 nor Windows host IP passed HTTP+WS — skipping spel probe"
  warn "(fix the networking first, then spel is worth retrying)"
  SPEL_VERDICT="no-target"
else
  info "$(spel --version 2>/dev/null || echo 'spel --version failed')"
  # Disposable session name so we never touch the user's real 'default'.
  SES="wsl-diag-$$-$(date +%s)"
  info "using disposable session: ${SES}"
  info "probing: spel --session ${SES} --cdp http://${SPEL_TARGET} get url"
  # Run with a hard timeout so a hung connect can't wedge the script.
  set +e
  SPEL_OUT=$(timeout 15 spel --session "$SES" --cdp "http://${SPEL_TARGET}" get url 2>&1)
  SPEL_RC=$?
  set -e
  if [[ $SPEL_RC -eq 0 ]]; then
    ok "spel connected via --cdp http://${SPEL_TARGET} and read the current URL:"
    printf '       %s\n' "$(printf '%s' "$SPEL_OUT" | head -n1)"
    SPEL_VERDICT="ok"
  elif [[ $SPEL_RC -eq 124 ]]; then
    fail "spel timed out after 15s — connect hung (usually a silent firewall drop on WS Upgrade)"
    SPEL_VERDICT="timeout"
  else
    fail "spel exited ${SPEL_RC} — output:"
    printf '%s\n' "$SPEL_OUT" | sed 's/^/       /'
    SPEL_VERDICT="error"
  fi
  # Always clean up the named session, even if the probe errored.
  spel --session "$SES" close >/dev/null 2>&1 || true
fi

# ─────────────────────────────────────────────────────────────────────────
# 8. Verdict
# ─────────────────────────────────────────────────────────────────────────
step "8. Verdict"

# Summary table so the user sees each transport × target at a glance.
# Column widths are applied to the UNCOLORED status string first (otherwise
# ANSI escape bytes count toward %-Ns width and the table misaligns); colors
# are re-applied after padding.
col_status() {
  local ok_flag="$1" text
  if $ok_flag; then text="YES"; else text="NO "; fi
  printf '%s' "$text"
}
paint() {
  # paint <text> <color>  — no-op if colors disabled
  if [[ -z "$C_END" ]]; then printf '%s' "$1"; else printf '%s%s%s' "$2" "$1" "$C_END"; fi
}
print_row() {
  # print_row <target> <http_flag> <ws_flag>
  local target="$1" h="$2" w="$3"
  local ht wt hcol wcol
  ht=$(col_status "$h")
  wt=$(col_status "$w")
  if $h; then hcol="$C_OK"; else hcol="$C_FAIL"; fi
  if $w; then wcol="$C_OK"; else wcol="$C_END"; fi
  $w || wcol="$C_FAIL"
  printf '  %-28s  %s  %s\n' "$target" "$(paint "$ht" "$hcol")" "$(paint "$wt" "$wcol")"
}
printf '  %-28s  %-3s  %-3s\n' "target" "HTTP" "WS"
print_row "127.0.0.1:${FOUND_PORT}" $LOOPBACK_HTTP $LOOPBACK_WS
if [[ -n "$WIN_IP" && "$WIN_IP" != "127.0.0.1" ]]; then
  print_row "${WIN_IP}:${FOUND_PORT}" $HOST_HTTP $HOST_WS
fi
printf '  spel end-to-end probe: %s\n' "$SPEL_VERDICT"
echo

if $LOOPBACK_OK; then
  ok   "127.0.0.1:${FOUND_PORT} — both HTTP AND WS work → spel auto-discovery will work"
  info "You have mirrored networking OR a netsh portproxy in place."
  info "Run:  spel --cdp http://127.0.0.1:${FOUND_PORT} open https://example.com"
  exit 0
elif $HOST_OK; then
  warn "Classic NAT networking — 127.0.0.1 doesn't reach Windows, but ${WIN_IP} does (both HTTP + WS)."
  info "This is the exact failure mode spel hits under default WSL2 networking."
  info ""
  info "Workarounds (pick one):"
  info "  A. Add to %USERPROFILE%\\.wslconfig on Windows:"
  info "       [wsl2]"
  info "       networkingMode=mirrored"
  info "     Then: wsl --shutdown  (from PowerShell). Fixes it permanently."
  info ""
  info "  B. Skip auto-discovery, pass the host IP explicitly:"
  info "       spel --cdp http://${WIN_IP}:${FOUND_PORT} open https://example.com"
  info ""
  info "  C. On Windows (admin PowerShell) add a port proxy:"
  info "       netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 \\"
  info "         listenport=${FOUND_PORT} connectaddress=127.0.0.1 connectport=${FOUND_PORT}"
  exit 2
elif ($LOOPBACK_HTTP || $HOST_HTTP) && ! ($LOOPBACK_WS || $HOST_WS); then
  fail "HTTP /json/version reachable, but the WebSocket upgrade is NOT."
  info "Playwright needs the WS to drive CDP, so spel will fail even though"
  info "discovery succeeds. Likely causes:"
  info "  • A netsh portproxy / firewall that passes plain HTTP but drops WS Upgrade"
  info "  • An HTTP proxy between WSL and Windows that strips Upgrade headers"
  info "  • Chrome launched with --remote-allow-origins restricting WS origin"
  info ""
  info "Try:  spel --cdp http://${WIN_IP:-<win-ip>}:${FOUND_PORT} — same result?"
  info "If yes, fix the proxy/firewall layer. Mirrored networking sidesteps it entirely."
  exit 2
elif (! $LOOPBACK_HTTP && ! $HOST_HTTP) && ($LOOPBACK_WS || $HOST_WS); then
  warn "WS handshake works but HTTP /json/version doesn't."
  info "Chrome is probably launched with --remote-debugging-pipe or similar,"
  info "which disables the HTTP discovery endpoint. spel's auto-discovery relies"
  info "on /json/version, so it can't find ws-path on its own."
  info ""
  info "Pass the ws URL directly:"
  info "  spel --cdp ws://${WIN_IP:-<win-ip>}:${FOUND_PORT}${FOUND_WS_PATH}"
  exit 2
else
  fail "Chrome is not reachable on ${FOUND_PORT} from either loopback or ${WIN_IP} (HTTP or WS)."
  info "Possible causes:"
  info "  • Chrome wasn't launched with --remote-debugging-port=${FOUND_PORT}"
  info "  • Windows Defender firewall is blocking the port"
  info "  • Chrome crashed / closed since DevToolsActivePort was written"
  info ""
  info "Quickest sanity check — on Windows PowerShell:"
  info "  Invoke-WebRequest http://127.0.0.1:${FOUND_PORT}/json/version -UseBasicParsing"
  info "If that also fails, the issue is on the Windows side, not WSL."
  exit 3
fi
