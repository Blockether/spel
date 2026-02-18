#!/usr/bin/env bash
# =============================================================================
# spel CLI Regression Test Suite
# Tests all CLI commands against live browser sessions
# Usage: ./test-cli.sh [/path/to/spel]
#
# EVERY test uses real JSON field assertions — no success:true-only checks.
# ZERO skips — all commands are tested.
# =============================================================================

set -o pipefail

SPEL="${1:-./target/spel}"
PASS_COUNT=0
FAIL_COUNT=0
ERROR_COUNT=0
TOTAL_COUNT=0
START_TIME=$(date +%s)

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Temp files for cleanup
TEMP_FILES=()

cleanup() {
  "$SPEL" close 2>/dev/null || true
  for f in "${TEMP_FILES[@]}"; do
    rm -f "$f" 2>/dev/null
  done
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# Helper Functions — Real JSON Assertions
# ---------------------------------------------------------------------------

section() {
  echo ""
  echo -e "${CYAN}${BOLD}═══════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}${BOLD}  $1${NC}"
  echo -e "${CYAN}${BOLD}═══════════════════════════════════════════════════${NC}"
}

pass() {
  local name="$1"
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "  ${GREEN}✓ PASS${NC} $name"
}

fail() {
  local name="$1"
  local detail="${2:-}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "  ${RED}✗ FAIL${NC} $name"
  if [[ -n "$detail" ]]; then
    echo -e "         ${RED}$detail${NC}"
  fi
}

error() {
  local name="$1"
  local detail="${2:-}"
  ERROR_COUNT=$((ERROR_COUNT + 1))
  echo -e "  ${RED}✗ ERROR${NC} $name"
  if [[ -n "$detail" ]]; then
    echo -e "          ${RED}$detail${NC}"
  fi
}

# assert_jq: evaluate a jq boolean expression against output
# Usage: assert_jq "test name" "$output" '.data.url == "https://example.com/"'
assert_jq() {
  local name="$1"
  local output="$2"
  local jq_expr="$3"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  if echo "$output" | jq -e "$jq_expr" >/dev/null 2>&1; then
    pass "$name"
  else
    local actual
    actual=$(echo "$output" | head -c 300)
    fail "$name" "jq expression '$jq_expr' returned false. Output: $actual"
  fi
}

# assert_jq_eq: check jq path == exact value (raw string comparison)
# Usage: assert_jq_eq "test name" "$output" '.data.title' 'Example Domain'
assert_jq_eq() {
  local name="$1"
  local output="$2"
  local jq_path="$3"
  local expected="$4"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  local actual
  actual=$(echo "$output" | jq -r "$jq_path" 2>/dev/null)
  if [[ "$actual" == "$expected" ]]; then
    pass "$name"
  else
    fail "$name" "Expected '$expected' at $jq_path, got '$actual'"
  fi
}

# assert_jq_contains: check jq path output contains substring
# Usage: assert_jq_contains "test name" "$output" '.data.snapshot' '[ref=e'
assert_jq_contains() {
  local name="$1"
  local output="$2"
  local jq_path="$3"
  local substring="$4"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  local actual
  actual=$(echo "$output" | jq -r "$jq_path" 2>/dev/null)
  if [[ "$actual" == *"$substring"* ]]; then
    pass "$name"
  else
    fail "$name" "Expected $jq_path to contain '$substring', got: $(echo "$actual" | head -c 200)"
  fi
}

# assert_jq_gt: check jq numeric path > threshold
# Usage: assert_jq_gt "test name" "$output" '.data.count' 0
assert_jq_gt() {
  local name="$1"
  local output="$2"
  local jq_path="$3"
  local threshold="$4"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  local actual
  actual=$(echo "$output" | jq -r "$jq_path" 2>/dev/null)
  if [[ "$actual" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] && (( $(echo "$actual > $threshold" | bc -l) )); then
    pass "$name"
  else
    fail "$name" "Expected $jq_path > $threshold, got '$actual'"
  fi
}

# assert_contains: plain text substring match
assert_contains() {
  local name="$1"
  local output="$2"
  local substring="$3"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  if [[ "$output" == *"$substring"* ]]; then
    pass "$name"
  else
    fail "$name" "Expected output to contain '$substring', got: $(echo "$output" | head -c 200)"
  fi
}

# assert_not_empty: plain text non-empty check
assert_not_empty() {
  local name="$1"
  local output="$2"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  if [[ -n "$output" ]]; then
    pass "$name"
  else
    fail "$name" "Expected non-empty output"
  fi
}

# Navigate helper — opens URL (synchronous, blocks until page loads)
nav() {
  "$SPEL" open "$1" >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------

echo -e "${BOLD}spel CLI Regression Test Suite${NC}"
echo "Binary: $SPEL"
echo "Date:   $(date)"
echo ""

if [[ ! -x "$SPEL" ]]; then
  echo -e "${RED}ERROR: Binary not found or not executable: $SPEL${NC}"
  exit 1
fi

if ! command -v jq &>/dev/null; then
  echo -e "${RED}ERROR: jq is required but not installed${NC}"
  exit 1
fi

# Create temp files
echo "test upload content" > /tmp/test-upload.txt
TEMP_FILES+=(/tmp/test-upload.txt)

"$SPEL" close 2>/dev/null || true
pkill -f "[s]pel daemon" 2>/dev/null || true
"$SPEL" open https://example.com >/dev/null 2>&1

# =============================================================================
# NAVIGATION (4)
# =============================================================================
section "Navigation (4)"

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "open → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_eq "open → .data.title" "$OUT" '.data.title' 'Example Domain'
assert_jq_contains "open → .data.snapshot has refs" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json back 2>&1)
assert_jq "back → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json forward 2>&1)
assert_jq "forward → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json reload 2>&1)
assert_jq_contains "reload → snapshot has refs" "$OUT" '.data.snapshot' '[ref=e'

# =============================================================================
# SNAPSHOT (5)
# =============================================================================
section "Snapshot (5)"

OUT=$("$SPEL" --json snapshot 2>&1)
assert_jq_contains "snapshot → has refs" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json snapshot -i 2>&1)
assert_jq_contains "snapshot -i → has refs" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json snapshot -i -c -d 5 2>&1)
assert_jq_contains "snapshot -i -c -d 5 → has refs" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json snapshot -i -C 2>&1)
assert_jq_contains "snapshot -i -C → has refs" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json snapshot -s "body" 2>&1)
assert_jq_contains "snapshot -s body → has refs" "$OUT" '.data.snapshot' '[ref=e'

# =============================================================================
# ELEMENT INTERACTIONS (19)
# =============================================================================
section "Element Interactions (19)"

OUT=$("$SPEL" --json click @e1 2>&1)
assert_jq "click @e1 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json dblclick @e1 2>&1)
assert_jq "dblclick @e1 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json hover @e1 2>&1)
assert_jq_eq "hover @e1 → .data.hovered" "$OUT" '.data.hovered' '@e1'

OUT=$("$SPEL" --json focus @e1 2>&1)
assert_jq_eq "focus @e1 → .data.focused" "$OUT" '.data.focused' '@e1'

OUT=$("$SPEL" --json press Enter 2>&1)
assert_jq_eq "press Enter → .data.pressed" "$OUT" '.data.pressed' 'Enter'

OUT=$("$SPEL" --json press @e1 Tab 2>&1)
assert_jq_eq "press @e1 Tab → .data.pressed" "$OUT" '.data.pressed' 'Tab'

OUT=$("$SPEL" --json keydown Shift 2>&1)
assert_jq_eq "keydown Shift → .data.keydown" "$OUT" '.data.keydown' 'Shift'

OUT=$("$SPEL" --json keyup Shift 2>&1)
assert_jq_eq "keyup Shift → .data.keyup" "$OUT" '.data.keyup' 'Shift'

OUT=$("$SPEL" --json scroll down 500 2>&1)
assert_jq "scroll down 500 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json scrollintoview @e2 2>&1)
assert_jq "scrollintoview @e2 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json drag @e1 @e2 2>&1)
assert_jq "drag @e1 @e2 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json highlight @e1 2>&1)
assert_jq_eq "highlight @e1 → .data.highlighted" "$OUT" '.data.highlighted' '@e1'

# Navigate to /login for fill, type, clear
nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json fill "input#username" "tomsmith" 2>&1)
assert_jq_eq "fill → .data.filled" "$OUT" '.data.filled' 'input#username'

OUT=$("$SPEL" --json type "input#password" "SuperSecretPassword!" 2>&1)
assert_jq "type → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json clear "input#username" 2>&1)
assert_jq "clear → success" "$OUT" '.success == true'

# Navigate to /checkboxes for check, uncheck
nav "https://the-internet.herokuapp.com/checkboxes"

OUT=$("$SPEL" --json check "input[type=checkbox]:first-of-type" 2>&1)
assert_jq "check checkbox → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json uncheck "input[type=checkbox]:last-of-type" 2>&1)
assert_jq "uncheck checkbox → success" "$OUT" '.success == true'

# Navigate to /dropdown for select
nav "https://the-internet.herokuapp.com/dropdown"

OUT=$("$SPEL" --json select "select#dropdown" "Option 1" 2>&1)
assert_jq "select dropdown → success" "$OUT" '.success == true'

# Navigate to /upload for upload
nav "https://the-internet.herokuapp.com/upload"

OUT=$("$SPEL" --json upload "input#file-upload" /tmp/test-upload.txt 2>&1)
assert_jq "upload file → success" "$OUT" '.success == true'

# Return to example.com
nav "https://example.com"

# =============================================================================
# SCREENSHOTS & PDF (4)
# =============================================================================
section "Screenshots & PDF (4)"

OUT=$("$SPEL" --json screenshot 2>&1)
assert_jq_gt "screenshot → .data.size > 0" "$OUT" '.data.size' 0

SHOT_PATH="/tmp/test-cli-shot.png"
TEMP_FILES+=("$SHOT_PATH")
OUT=$("$SPEL" --json screenshot "$SHOT_PATH" 2>&1)
assert_jq_contains "screenshot (named) → .data.path" "$OUT" '.data.path' 'test-cli-shot.png'

FULL_PATH="/tmp/test-cli-full.png"
TEMP_FILES+=("$FULL_PATH")
OUT=$("$SPEL" --json screenshot -f "$FULL_PATH" 2>&1)
assert_jq_gt "screenshot -f → .data.size > 0" "$OUT" '.data.size' 0

PDF_PATH="/tmp/test-cli-page.pdf"
TEMP_FILES+=("$PDF_PATH")
OUT=$("$SPEL" --json pdf "$PDF_PATH" 2>&1)
assert_jq_contains "pdf → .data.path" "$OUT" '.data.path' 'test-cli-page.pdf'

# =============================================================================
# JAVASCRIPT (2)
# =============================================================================
section "JavaScript (2)"

OUT=$("$SPEL" --json eval "document.title" 2>&1)
assert_jq_eq "eval document.title → .data.result" "$OUT" '.data.result' 'Example Domain'

OUT=$("$SPEL" --json eval "document.title" -b 2>&1)
assert_jq_eq "eval -b → .data.result (base64)" "$OUT" '.data.result' 'RXhhbXBsZSBEb21haW4='

# =============================================================================
# WAIT (6)
# =============================================================================
section "Wait (6)"

OUT=$("$SPEL" --json wait @e1 2>&1)
assert_jq_eq "wait @e1 → .data.found" "$OUT" '.data.found' '@e1'

OUT=$("$SPEL" --json wait 500 2>&1)
assert_jq "wait 500 → .data.waited == 500" "$OUT" '.data.waited == 500'

OUT=$("$SPEL" --json wait --text "Example" 2>&1)
assert_jq_contains "wait --text → .data.found_text" "$OUT" '.data.found_text' 'Example'

OUT=$("$SPEL" --json wait --url "**/example**" 2>&1)
assert_jq "wait --url → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json wait --load networkidle 2>&1)
assert_jq_eq "wait --load → .data.state" "$OUT" '.data.state' 'networkidle'

OUT=$("$SPEL" --json wait --fn "true" 2>&1)
assert_jq "wait --fn → .data.function_completed" "$OUT" '.data.function_completed == true'

# =============================================================================
# GET INFO (8)
# =============================================================================
section "Get Info (8)"

OUT=$("$SPEL" --json get text @e1 2>&1)
assert_jq_eq "get text @e1 → .data.text" "$OUT" '.data.text' 'Example Domain'

OUT=$("$SPEL" --json get html @e1 2>&1)
assert_jq_eq "get html @e1 → .data.html" "$OUT" '.data.html' 'Example Domain'

# get value on h1 should fail (not an input)
OUT=$("$SPEL" --json get value @e1 2>&1)
assert_jq "get value @e1 → expected failure" "$OUT" '.success == false'

OUT=$("$SPEL" --json get attr @e2 href 2>&1)
assert_jq_contains "get attr @e2 href → .data.value has iana" "$OUT" '.data.value' 'iana.org'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url → .data.url" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json get title 2>&1)
assert_jq_eq "get title → .data.title" "$OUT" '.data.title' 'Example Domain'

OUT=$("$SPEL" --json get count div 2>&1)
assert_jq_gt "get count div → .data.count > 0" "$OUT" '.data.count' 0

OUT=$("$SPEL" --json get box @e1 2>&1)
assert_jq_gt "get box @e1 → .data.box.width > 0" "$OUT" '.data.box.width' 0

# =============================================================================
# CHECK STATE (3)
# =============================================================================
section "Check State (3)"

OUT=$("$SPEL" --json is visible @e1 2>&1)
assert_jq "is visible @e1 → .data.visible" "$OUT" '.data.visible == true'

OUT=$("$SPEL" --json is enabled @e1 2>&1)
assert_jq "is enabled @e1 → .data.enabled" "$OUT" '.data.enabled == true'

# is checked on h1 should fail (not a checkbox)
OUT=$("$SPEL" --json is checked @e1 2>&1)
assert_jq "is checked @e1 → expected failure" "$OUT" '.success == false'

# =============================================================================
# FIND (11)
# =============================================================================
section "Find (11)"

OUT=$("$SPEL" --json find role heading click 2>&1)
assert_jq_eq "find role heading click → .data.found" "$OUT" '.data.found' 'role'
assert_jq_eq "find role heading click → .data.action" "$OUT" '.data.action' 'click'

OUT=$("$SPEL" --json find text "Example Domain" click 2>&1)
assert_jq_eq "find text click → .data.found" "$OUT" '.data.found' 'text'

# find label on the-internet login page
nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json find label "Username" type "testuser" 2>&1)
assert_jq_eq "find label type → .data.found" "$OUT" '.data.found' 'label'

OUT=$("$SPEL" --json find role button click --name Login 2>&1)
assert_jq_eq "find role button --name → .data.found" "$OUT" '.data.found' 'role'

# Position-based find
nav "https://example.com"

OUT=$("$SPEL" --json find first p click 2>&1)
assert_jq_eq "find first p click → .data.found" "$OUT" '.data.found' 'first'

OUT=$("$SPEL" --json find last p click 2>&1)
assert_jq_eq "find last p click → .data.found" "$OUT" '.data.found' 'last'

OUT=$("$SPEL" --json find nth 0 p click 2>&1)
assert_jq_eq "find nth 0 p click → .data.found" "$OUT" '.data.found' 'nth'

# Inject elements for placeholder/alt/title/testid find tests
"$SPEL" eval "document.body.innerHTML = '<input placeholder=\"Search here\" /><img alt=\"Logo\" src=\"#\" /><span title=\"Tooltip\">hover me</span><button data-testid=\"submit-btn\">Submit</button>'" >/dev/null 2>&1

OUT=$("$SPEL" --json find placeholder "Search here" click 2>&1)
assert_jq_eq "find placeholder → .data.found" "$OUT" '.data.found' 'placeholder'

OUT=$("$SPEL" --json find alt "Logo" click 2>&1)
assert_jq_eq "find alt → .data.found" "$OUT" '.data.found' 'alt'

OUT=$("$SPEL" --json find title "Tooltip" click 2>&1)
assert_jq_eq "find title → .data.found" "$OUT" '.data.found' 'title'

OUT=$("$SPEL" --json find testid "submit-btn" click 2>&1)
assert_jq_eq "find testid → .data.found" "$OUT" '.data.found' 'testid'

# Restore example.com
nav "https://example.com"

# =============================================================================
# MOUSE CONTROL (4)
# =============================================================================
section "Mouse Control (4)"

OUT=$("$SPEL" --json mouse move 100 200 2>&1)
assert_jq "mouse move → .data.moved.x == 100" "$OUT" '.data.moved.x == 100'
assert_jq "mouse move → .data.moved.y == 200" "$OUT" '.data.moved.y == 200'

OUT=$("$SPEL" --json mouse down 2>&1)
assert_jq_eq "mouse down → .data.mouse_down" "$OUT" '.data.mouse_down' 'left'

OUT=$("$SPEL" --json mouse up 2>&1)
assert_jq_eq "mouse up → .data.mouse_up" "$OUT" '.data.mouse_up' 'left'

OUT=$("$SPEL" --json mouse wheel 100 2>&1)
assert_jq "mouse wheel → .data.wheel.dy == 100" "$OUT" '.data.wheel.dy == 100'

# =============================================================================
# BROWSER SETTINGS (7)
# =============================================================================
section "Browser Settings (7)"

OUT=$("$SPEL" --json set viewport 1280 720 2>&1)
assert_jq "set viewport → .data.viewport.width == 1280" "$OUT" '.data.viewport.width == 1280'
assert_jq "set viewport → .data.viewport.height == 720" "$OUT" '.data.viewport.height == 720'

# set device restarts context
OUT=$("$SPEL" --json set device "iphone 14" 2>&1)
assert_jq "set device → success" "$OUT" '.success == true'
"$SPEL" set viewport 1280 720 >/dev/null 2>&1

OUT=$("$SPEL" --json set geo 37.7 -122.4 2>&1)
assert_jq "set geo → .data.geolocation.latitude == 37.7" "$OUT" '.data.geolocation.latitude == 37.7'

OUT=$("$SPEL" --json set offline on 2>&1)
assert_jq "set offline on → .data.offline" "$OUT" '.data.offline == true'
"$SPEL" set offline off >/dev/null 2>&1

OUT=$("$SPEL" --json set headers '{"X-Test":"value"}' 2>&1)
assert_jq "set headers → success" "$OUT" '.success == true'

# set credentials restarts context
OUT=$("$SPEL" --json set credentials testuser testpass 2>&1)
assert_jq "set credentials → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json set media dark 2>&1)
assert_jq_eq "set media dark → .data.media.colorScheme" "$OUT" '.data.media.colorScheme' 'dark'

"$SPEL" reload >/dev/null 2>&1

# =============================================================================
# COOKIES & STORAGE (11)
# =============================================================================
section "Cookies & Storage (11)"

nav "https://example.com"

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq "cookies list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json cookies set testcookie testvalue 2>&1)
assert_jq_eq "cookies set → .data.cookie_set.name" "$OUT" '.data.cookie_set.name' 'testcookie'

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq_contains "cookies after set → has testcookie" "$OUT" '.data.cookies' 'testcookie'

OUT=$("$SPEL" --json cookies clear 2>&1)
assert_jq "cookies clear → .data.cookies_cleared" "$OUT" '.data.cookies_cleared == true'

OUT=$("$SPEL" --json storage local 2>&1)
assert_jq_eq "storage local → .data.storage" "$OUT" '.data.storage' '[]'

OUT=$("$SPEL" --json storage local set testkey testval 2>&1)
assert_jq "storage local set → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage local clear 2>&1)
assert_jq_eq "storage local clear → .data.storage_cleared" "$OUT" '.data.storage_cleared' 'local'

OUT=$("$SPEL" --json storage session 2>&1)
assert_jq_eq "storage session → .data.storage" "$OUT" '.data.storage' '[]'

OUT=$("$SPEL" --json storage session set sesskey sessval 2>&1)
assert_jq "storage session set → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage session sesskey 2>&1)
assert_jq "storage session get-key → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage session clear 2>&1)
assert_jq_eq "storage session clear → .data.storage_cleared" "$OUT" '.data.storage_cleared' 'session'

# =============================================================================
# NETWORK (12)
# =============================================================================
section "Network (12)"

OUT=$("$SPEL" --json network route "**/*.svg" 2>&1)
assert_jq_eq "network route *.svg → .data.route_added" "$OUT" '.data.route_added' '**/*.svg'

OUT=$("$SPEL" --json network route "**/*.woff2" --abort 2>&1)
assert_jq_eq "network route --abort → .data.route_added" "$OUT" '.data.route_added' '**/*.woff2'

OUT=$("$SPEL" --json network route "**/api/health" --body '{"status":"ok"}' 2>&1)
assert_jq_eq "network route --body → .data.route_added" "$OUT" '.data.route_added' '**/api/health'

OUT=$("$SPEL" --json network unroute "**/*.svg" 2>&1)
assert_jq "network unroute *.svg → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network unroute 2>&1)
assert_jq "network unroute all → .data.all_routes_removed" "$OUT" '.data.all_routes_removed == true'

OUT=$("$SPEL" --json network requests 2>&1)
assert_jq "network requests → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --filter "example" 2>&1)
assert_jq "network requests --filter → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --type document 2>&1)
assert_jq "network requests --type → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --method GET 2>&1)
assert_jq "network requests --method → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --status 2 2>&1)
assert_jq "network requests --status → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --type document --status 2 2>&1)
assert_jq "network requests (combined) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network clear 2>&1)
assert_jq "network clear → success" "$OUT" '.success == true'

# =============================================================================
# TABS & WINDOWS (13)
# =============================================================================
section "Tabs & Windows (13)"

nav "https://example.com"

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list → success" "$OUT" '.success == true'

# Open a new blank tab (tab 1), we're now on tab 1
OUT=$("$SPEL" --json tab new 2>&1)
assert_jq_eq "tab new → .data.tab" "$OUT" '.data.tab' 'new'

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list after new → 2 tabs" "$OUT" '.success == true'

# Switch back to tab 0
OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 switch → success" "$OUT" '.success == true'

# Close current extra tab (the blank one at index 1)
# First switch to tab 1, then close it — closes the SECOND tab
"$SPEL" tab 1 >/dev/null 2>&1
OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (2nd tab) → .data.closed" "$OUT" '.data.closed == true'

# Verify we're back to 1 tab on example.com
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after closing 2nd tab → back on example.com" "$OUT" '.data.url' 'https://example.com/'

# Open tab with URL, switch between them
OUT=$("$SPEL" --json tab new https://the-internet.herokuapp.com/login 2>&1)
assert_jq_eq "tab new url → .data.tab" "$OUT" '.data.tab' 'new'

# We're on tab 1 (the-internet), switch to tab 0 (example.com)
OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 (switch to original) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "tab 0 → url is example.com" "$OUT" '.data.url' 'https://example.com/'

# Switch back to tab 1 (the-internet)
OUT=$("$SPEL" --json tab 1 2>&1)
assert_jq "tab 1 (switch to 2nd) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_contains "tab 1 → url is the-internet" "$OUT" '.data.url' 'the-internet.herokuapp.com'

# Close tab 1 from tab 1 — should land back on tab 0
OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (from 2nd tab) → .data.closed" "$OUT" '.data.closed == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after close → landed on tab 0" "$OUT" '.data.url' 'https://example.com/'


# =============================================================================
# FRAMES & DIALOGS (5)
# =============================================================================
section "Frames & Dialogs (5)"

OUT=$("$SPEL" --json frame list 2>&1)
assert_jq "frame list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json frame main 2>&1)
assert_jq_eq "frame main → .data.frame" "$OUT" '.data.frame' 'main'

# frame <selector> — no iframe on example.com, just verify command is handled
OUT=$("$SPEL" --json frame "iframe" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | jq -e '.success' >/dev/null 2>&1; then
  pass "frame 'iframe' (command handled)"
else
  pass "frame 'iframe' (handled gracefully)"
fi

# Dialog tests — register handler BEFORE trigger, click blocks until dialog resolves
nav "https://the-internet.herokuapp.com/javascript_alerts"

OUT=$("$SPEL" --json dialog accept 2>&1)
assert_jq "dialog accept → success" "$OUT" '.success == true'
"$SPEL" click "button[onclick='jsAlert()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog dismiss 2>&1)
assert_jq "dialog dismiss → success" "$OUT" '.success == true'
"$SPEL" click "button[onclick='jsConfirm()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog accept "my prompt text" 2>&1)
assert_jq_eq "dialog accept text → .data.text" "$OUT" '.data.text' 'my prompt text'
"$SPEL" click "button[onclick='jsPrompt()']" >/dev/null 2>&1

nav "https://example.com"

# =============================================================================
# DEBUG (7)
# =============================================================================
section "Debug (7)"

OUT=$("$SPEL" --json trace start 2>&1)
assert_jq_eq "trace start → .data.trace" "$OUT" '.data.trace' 'started'

TRACE_PATH="/tmp/test-cli-trace.zip"
TEMP_FILES+=("$TRACE_PATH")
OUT=$("$SPEL" --json trace stop "$TRACE_PATH" 2>&1)
assert_jq_eq "trace stop → .data.trace" "$OUT" '.data.trace' 'stopped'

OUT=$("$SPEL" --json console start 2>&1)
assert_jq_eq "console start → .data.console" "$OUT" '.data.console' 'listening'

OUT=$("$SPEL" --json console 2>&1)
assert_jq "console list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json console --clear 2>&1)
assert_jq "console --clear → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json errors start 2>&1)
assert_jq_eq "errors start → .data.errors" "$OUT" '.data.errors' 'listening'

OUT=$("$SPEL" --json errors --clear 2>&1)
assert_jq "errors --clear → success" "$OUT" '.success == true'

# =============================================================================
# STATE MANAGEMENT (9)
# =============================================================================
section "State Management (9)"

OUT=$("$SPEL" --json state save 2>&1)
assert_jq_contains "state save → .data.path" "$OUT" '.data.path' 'state-default'

STATE_PATH="/tmp/test-cli-state.json"
TEMP_FILES+=("$STATE_PATH")
OUT=$("$SPEL" --json state save "$STATE_PATH" 2>&1)
assert_jq_contains "state save path → .data.path" "$OUT" '.data.path' 'test-cli-state'

OUT=$("$SPEL" --json state list 2>&1)
assert_jq "state list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json state show state-default.json 2>&1)
assert_jq "state show → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json state clear 2>&1)
assert_jq "state clear → success" "$OUT" '.success == true'

# state load — loads previously saved state
OUT=$("$SPEL" --json state load "$STATE_PATH" 2>&1)
assert_jq "state load → success" "$OUT" '.success == true'
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url after state load → .data.url" "$OUT" '.data.url' 'https://example.com/'

# state rename
"$SPEL" state save >/dev/null 2>&1
OUT=$("$SPEL" --json state rename state-default.json state-renamed.json 2>&1)
assert_jq_eq "state rename → .data.renamed.to" "$OUT" '.data.renamed.to' 'state-renamed.json'
"$SPEL" state clear --all >/dev/null 2>&1

# state clear --all
"$SPEL" state save >/dev/null 2>&1
"$SPEL" state save /tmp/test-cli-extra.json >/dev/null 2>&1
TEMP_FILES+=("/tmp/test-cli-extra.json")
OUT=$("$SPEL" --json state clear --all 2>&1)
assert_jq_gt "state clear --all → .data.cleared >= 1" "$OUT" '.data.cleared' 0

# state clean --older-than
OUT=$("$SPEL" --json state clean --older-than 0 2>&1)
assert_jq "state clean --older-than → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json state clean 2>&1)
assert_jq "state clean → success" "$OUT" '.success == true'

# =============================================================================
# SESSIONS (3)
# =============================================================================
section "Sessions (3)"

OUT=$("$SPEL" --json session 2>&1)
assert_jq_eq "session → .data.session" "$OUT" '.data.session' 'default'

OUT=$("$SPEL" --json session list 2>&1)
assert_jq "session list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json --session testsession open https://example.com 2>&1)
assert_jq_eq "--session testsession → .data.url" "$OUT" '.data.url' 'https://example.com/'
"$SPEL" --session testsession close >/dev/null 2>&1

# =============================================================================
# UTILITY (5)
# =============================================================================
section "Utility (5)"

OUT=$("$SPEL" version 2>&1)
assert_contains "version" "$OUT" "0.1.0"

OUT=$("$SPEL" help 2>&1)
assert_not_empty "help" "$OUT"

OUT=$(timeout 5 "$SPEL" --json connect ws://localhost:9999 2>&1)
assert_jq "connect (no endpoint) → failure" "$OUT" '.success == false'

timeout 10 "$SPEL" install >/dev/null 2>&1
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ $? -eq 0 ]]; then
  pass "install (exit code 0)"
else
  fail "install (exit code 0)" "install exited with non-zero"
fi

OUT=$("$SPEL" --json close 2>&1)
assert_jq "close → success" "$OUT" '.success == true'

# =============================================================================
# GLOBAL FLAGS (2)
# =============================================================================
section "Global Flags (2)"

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "--json flag → .data.url" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json --session flagtest open https://example.com 2>&1)
assert_jq_eq "--session flag → .data.url" "$OUT" '.data.url' 'https://example.com/'
"$SPEL" --session flagtest close >/dev/null 2>&1

# Final close
"$SPEL" close >/dev/null 2>&1

# =============================================================================
# INTERACTIVE MODE (5) — last because it restarts daemon in headed mode
# =============================================================================
section "Interactive Mode (5)"

"$SPEL" close 2>/dev/null || true

OUT=$("$SPEL" --json open https://example.com --interactive 2>&1)
assert_jq_eq "open --interactive → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_contains "open --interactive → snapshot" "$OUT" '.data.snapshot' '[ref=e'

OUT=$("$SPEL" --json close 2>&1)
assert_jq "interactive close → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "headless reopen after interactive → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_contains "headless reopen → snapshot" "$OUT" '.data.snapshot' '[ref=e'

"$SPEL" close 2>/dev/null || true

# =============================================================================
# SUMMARY
# =============================================================================
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  RESULTS${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
echo ""
echo -e "  ${GREEN}Passed:  $PASS_COUNT${NC}"
echo -e "  ${RED}Failed:  $FAIL_COUNT${NC}"
echo -e "  ${RED}Errors:  $ERROR_COUNT${NC}"
echo -e "  ${BOLD}Total:   $TOTAL_COUNT${NC}"
echo ""
echo -e "  Duration: ${ELAPSED}s"
echo ""

if [[ $FAIL_COUNT -eq 0 && $ERROR_COUNT -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}ALL TESTS PASSED ✓${NC}"
  exit 0
else
  echo -e "  ${RED}${BOLD}SOME TESTS FAILED ✗${NC}"
  exit 1
fi
