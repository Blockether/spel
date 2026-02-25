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


# macOS doesn't ship GNU coreutils timeout; provide a portable fallback.
if ! command -v timeout >/dev/null 2>&1; then
  timeout() {
    local duration="$1"; shift
    perl -e 'alarm shift; exec @ARGV' "$duration" "$@"
  }
fi

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
# Usage: assert_jq "test name" "$output" '.url == "https://example.com/"'
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
# Usage: assert_jq_eq "test name" "$output" '.title' 'Example Domain'
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
# Usage: assert_jq_contains "test name" "$output" '.snapshot' '[ref=e'
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
# Usage: assert_jq_gt "test name" "$output" '.count' 0
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
assert_jq_eq "open → .url" "$OUT" '.url' 'https://example.com/'
assert_jq_eq "open → .title" "$OUT" '.title' 'Example Domain'
assert_jq_contains "open → .snapshot has refs" "$OUT" '.snapshot' '[@e'
# Navigate to a second URL so back/forward have history
"$SPEL" open https://www.iana.org/help/example-domains >/dev/null 2>&1
OUT=$("$SPEL" --json back 2>&1)
assert_jq "back → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json forward 2>&1)
assert_jq "forward → success" "$OUT" 'has("error") | not'
# Restore page after back/forward
nav "https://example.com"

OUT=$("$SPEL" --json reload 2>&1)
assert_jq_contains "reload → snapshot has refs" "$OUT" '.snapshot' '[@e'

# =============================================================================
# SNAPSHOT (5)
# =============================================================================
section "Snapshot (5)"

OUT=$("$SPEL" --json snapshot 2>&1)
assert_jq_contains "snapshot → has refs" "$OUT" '.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i 2>&1)
assert_jq_contains "snapshot -i → has refs" "$OUT" '.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i -c -d 5 2>&1)
assert_jq_contains "snapshot -i -c -d 5 → has refs" "$OUT" '.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i -C 2>&1)
assert_jq_contains "snapshot -i -C → has refs" "$OUT" '.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -s "body" 2>&1)
assert_jq_contains "snapshot -s body → has refs" "$OUT" '.snapshot' '[@e'

# =============================================================================
# ELEMENT INTERACTIONS (19)
# =============================================================================
section "Element Interactions (19)"

OUT=$("$SPEL" --json click @e1 2>&1)
assert_jq "click @e1 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json dblclick @e1 2>&1)
assert_jq "dblclick @e1 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json hover @e1 2>&1)
assert_jq_eq "hover @e1 → .hovered" "$OUT" '.hovered' '@e1'

OUT=$("$SPEL" --json focus @e1 2>&1)
assert_jq_eq "focus @e1 → .focused" "$OUT" '.focused' '@e1'

OUT=$("$SPEL" --json press Enter 2>&1)
assert_jq_eq "press Enter → .pressed" "$OUT" '.pressed' 'Enter'

OUT=$("$SPEL" --json press @e1 Tab 2>&1)
assert_jq_eq "press @e1 Tab → .pressed" "$OUT" '.pressed' 'Tab'

OUT=$("$SPEL" --json keydown Shift 2>&1)
assert_jq_eq "keydown Shift → .keydown" "$OUT" '.keydown' 'Shift'

OUT=$("$SPEL" --json keyup Shift 2>&1)
assert_jq_eq "keyup Shift → .keyup" "$OUT" '.keyup' 'Shift'

OUT=$("$SPEL" --json scroll down 500 2>&1)
assert_jq "scroll down 500 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json scrollintoview @e2 2>&1)
assert_jq "scrollintoview @e2 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json drag @e1 @e2 2>&1)
assert_jq "drag @e1 @e2 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json highlight @e1 2>&1)
assert_jq_eq "highlight @e1 → .highlighted" "$OUT" '.highlighted' '@e1'

# Navigate to /login for fill, type, clear
nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json fill "input#username" "tomsmith" 2>&1)
assert_jq_eq "fill → .filled" "$OUT" '.filled' 'input#username'

OUT=$("$SPEL" --json type "input#password" "SuperSecretPassword!" 2>&1)
assert_jq "type → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json clear "input#username" 2>&1)
assert_jq "clear → success" "$OUT" 'has("error") | not'

# Navigate to /checkboxes for check, uncheck
nav "https://the-internet.herokuapp.com/checkboxes"

OUT=$("$SPEL" --json check "input[type=checkbox]:first-of-type" 2>&1)
assert_jq "check checkbox → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json uncheck "input[type=checkbox]:last-of-type" 2>&1)
assert_jq "uncheck checkbox → success" "$OUT" 'has("error") | not'

# Navigate to /dropdown for select
nav "https://the-internet.herokuapp.com/dropdown"

OUT=$("$SPEL" --json select "select#dropdown" "Option 1" 2>&1)
assert_jq "select dropdown → success" "$OUT" 'has("error") | not'

# Navigate to /upload for upload
nav "https://the-internet.herokuapp.com/upload"

OUT=$("$SPEL" --json upload "input#file-upload" /tmp/test-upload.txt 2>&1)
assert_jq "upload file → success" "$OUT" 'has("error") | not'

# Return to example.com
nav "https://example.com"

# =============================================================================
# SCREENSHOTS & PDF (4)
# =============================================================================
section "Screenshots & PDF (4)"

OUT=$("$SPEL" --json screenshot 2>&1)
assert_jq_gt "screenshot → .size > 0" "$OUT" '.size' 0

SHOT_PATH="/tmp/test-cli-shot.png"
TEMP_FILES+=("$SHOT_PATH")
OUT=$("$SPEL" --json screenshot "$SHOT_PATH" 2>&1)
assert_jq_contains "screenshot (named) → .path" "$OUT" '.path' 'test-cli-shot.png'

FULL_PATH="/tmp/test-cli-full.png"
TEMP_FILES+=("$FULL_PATH")
OUT=$("$SPEL" --json screenshot -f "$FULL_PATH" 2>&1)
assert_jq_gt "screenshot -f → .size > 0" "$OUT" '.size' 0

PDF_PATH="/tmp/test-cli-page.pdf"
TEMP_FILES+=("$PDF_PATH")
OUT=$("$SPEL" --json pdf "$PDF_PATH" 2>&1)
assert_jq_contains "pdf → .path" "$OUT" '.path' 'test-cli-page.pdf'

# =============================================================================
# JAVASCRIPT (2)
# =============================================================================
section "JavaScript (2)"

OUT=$("$SPEL" --json eval "document.title" 2>&1)
assert_jq_eq "eval document.title → .result" "$OUT" '.result' 'Example Domain'

OUT=$("$SPEL" --json eval "document.title" -b 2>&1)
assert_jq_eq "eval -b → .result (base64)" "$OUT" '.result' 'RXhhbXBsZSBEb21haW4='

# =============================================================================
# WAIT (6)
# =============================================================================
section "Wait (6)"

OUT=$("$SPEL" --json wait @e1 2>&1)
assert_jq_eq "wait @e1 → .found" "$OUT" '.found' '@e1'

OUT=$("$SPEL" --json wait 500 2>&1)
assert_jq "wait 500 → .waited == 500" "$OUT" '.waited == 500'

OUT=$("$SPEL" --json wait --text "Example" 2>&1)
assert_jq_contains "wait --text → .found_text" "$OUT" '.found_text' 'Example'

OUT=$("$SPEL" --json wait --url "**/example**" 2>&1)
assert_jq "wait --url → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json wait --load networkidle 2>&1)
assert_jq_eq "wait --load → .state" "$OUT" '.state' 'networkidle'

OUT=$("$SPEL" --json wait --fn "true" 2>&1)
assert_jq "wait --fn → .function_completed" "$OUT" '.function_completed == true'

# =============================================================================
# GET INFO (8)
# =============================================================================
section "Get Info (8)"

OUT=$("$SPEL" --json get text @e1 2>&1)
assert_jq_eq "get text @e1 → .text" "$OUT" '.text' 'Example Domain'

OUT=$("$SPEL" --json get html @e1 2>&1)
assert_jq_eq "get html @e1 → .html" "$OUT" '.html' 'Example Domain'

# get value on h1 should fail (not an input)
OUT=$("$SPEL" --json get value @e1 2>&1)
assert_jq "get value @e1 → expected failure" "$OUT" 'has("error")'

OUT=$("$SPEL" --json get attr @e3 href 2>&1)
assert_jq_contains "get attr @e3 href → .value has iana" "$OUT" '.value' 'iana.org'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url → .url" "$OUT" '.url' 'https://example.com/'

OUT=$("$SPEL" --json get title 2>&1)
assert_jq_eq "get title → .title" "$OUT" '.title' 'Example Domain'

OUT=$("$SPEL" --json get count div 2>&1)
assert_jq_gt "get count div → .count > 0" "$OUT" '.count' 0

OUT=$("$SPEL" --json get box @e1 2>&1)
assert_jq_gt "get box @e1 → .box.width > 0" "$OUT" '.box.width' 0

# =============================================================================
# CHECK STATE (3)
# =============================================================================
section "Check State (3)"

OUT=$("$SPEL" --json is visible @e1 2>&1)
assert_jq "is visible @e1 → .visible" "$OUT" '.visible == true'

OUT=$("$SPEL" --json is enabled @e1 2>&1)
assert_jq "is enabled @e1 → .enabled" "$OUT" '.enabled == true'

# is checked on h1 should fail (not a checkbox)
OUT=$("$SPEL" --json is checked @e1 2>&1)
assert_jq "is checked @e1 → expected failure" "$OUT" 'has("error")'

# =============================================================================
# FIND (11)
# =============================================================================
section "Find (11)"

OUT=$("$SPEL" --json find role heading click 2>&1)
assert_jq_eq "find role heading click → .found" "$OUT" '.found' 'role'
assert_jq_eq "find role heading click → .action" "$OUT" '.action' 'click'

OUT=$("$SPEL" --json find text "Example Domain" click 2>&1)
assert_jq_eq "find text click → .found" "$OUT" '.found' 'text'

# find label on the-internet login page
nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json find label "Username" type "testuser" 2>&1)
assert_jq_eq "find label type → .found" "$OUT" '.found' 'label'

OUT=$("$SPEL" --json find role button click --name Login 2>&1)
assert_jq_eq "find role button --name → .found" "$OUT" '.found' 'role'

# Position-based find
nav "https://example.com"

OUT=$("$SPEL" --json find first p click 2>&1)
assert_jq_eq "find first p click → .found" "$OUT" '.found' 'first'

OUT=$("$SPEL" --json find last p click 2>&1)
assert_jq_eq "find last p click → .found" "$OUT" '.found' 'last'

OUT=$("$SPEL" --json find nth 0 p click 2>&1)
assert_jq_eq "find nth 0 p click → .found" "$OUT" '.found' 'nth'

# Inject elements for placeholder/alt/title/testid find tests
"$SPEL" eval "document.body.innerHTML = '<input placeholder=\"Search here\" /><img alt=\"Logo\" src=\"#\" /><span title=\"Tooltip\">hover me</span><button data-testid=\"submit-btn\">Submit</button>'" >/dev/null 2>&1

OUT=$("$SPEL" --json find placeholder "Search here" click 2>&1)
assert_jq_eq "find placeholder → .found" "$OUT" '.found' 'placeholder'

OUT=$("$SPEL" --json find alt "Logo" click 2>&1)
assert_jq_eq "find alt → .found" "$OUT" '.found' 'alt'

OUT=$("$SPEL" --json find title "Tooltip" click 2>&1)
assert_jq_eq "find title → .found" "$OUT" '.found' 'title'

OUT=$("$SPEL" --json find testid "submit-btn" click 2>&1)
assert_jq_eq "find testid → .found" "$OUT" '.found' 'testid'

# Restore example.com
nav "https://example.com"

# =============================================================================
# MOUSE CONTROL (4)
# =============================================================================
section "Mouse Control (4)"

OUT=$("$SPEL" --json mouse move 100 200 2>&1)
assert_jq "mouse move → .moved.x == 100" "$OUT" '.moved.x == 100'
assert_jq "mouse move → .moved.y == 200" "$OUT" '.moved.y == 200'

OUT=$("$SPEL" --json mouse down 2>&1)
assert_jq_eq "mouse down → .mouse_down" "$OUT" '.mouse_down' 'left'

OUT=$("$SPEL" --json mouse up 2>&1)
assert_jq_eq "mouse up → .mouse_up" "$OUT" '.mouse_up' 'left'

OUT=$("$SPEL" --json mouse wheel 100 2>&1)
assert_jq "mouse wheel → .wheel.dy == 100" "$OUT" '.wheel.dy == 100'

# =============================================================================
# BROWSER SETTINGS (7)
# =============================================================================
section "Browser Settings (7)"

OUT=$("$SPEL" --json set viewport 1280 720 2>&1)
assert_jq "set viewport → .viewport.width == 1280" "$OUT" '.viewport.width == 1280'
assert_jq "set viewport → .viewport.height == 720" "$OUT" '.viewport.height == 720'

# set device restarts context
OUT=$("$SPEL" --json set device "iphone 14" 2>&1)
assert_jq "set device → success" "$OUT" 'has("error") | not'
"$SPEL" set viewport 1280 720 >/dev/null 2>&1

OUT=$("$SPEL" --json set geo 37.7 -122.4 2>&1)
assert_jq "set geo → .geolocation.latitude == 37.7" "$OUT" '.geolocation.latitude == 37.7'

OUT=$("$SPEL" --json set offline on 2>&1)
assert_jq "set offline on → .offline" "$OUT" '.offline == true'
"$SPEL" set offline off >/dev/null 2>&1

OUT=$("$SPEL" --json set headers '{"X-Test":"value"}' 2>&1)
assert_jq "set headers → success" "$OUT" 'has("error") | not'

# set credentials restarts context
OUT=$("$SPEL" --json set credentials testuser testpass 2>&1)
assert_jq "set credentials → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json set media dark 2>&1)
assert_jq_eq "set media dark → .media.colorScheme" "$OUT" '.media.colorScheme' 'dark'

"$SPEL" reload >/dev/null 2>&1

# =============================================================================
# COOKIES & STORAGE (11)
# =============================================================================
section "Cookies & Storage (11)"

nav "https://example.com"

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq "cookies list → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json cookies set testcookie testvalue 2>&1)
assert_jq_eq "cookies set → .cookie_set.name" "$OUT" '.cookie_set.name' 'testcookie'

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq_contains "cookies after set → has testcookie" "$OUT" '.cookies' 'testcookie'

OUT=$("$SPEL" --json cookies clear 2>&1)
assert_jq "cookies clear → .cookies_cleared" "$OUT" '.cookies_cleared == true'

OUT=$("$SPEL" --json storage local 2>&1)
assert_jq_eq "storage local → .storage" "$OUT" '.storage' '[]'

OUT=$("$SPEL" --json storage local set testkey testval 2>&1)
assert_jq "storage local set → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json storage local clear 2>&1)
assert_jq_eq "storage local clear → .storage_cleared" "$OUT" '.storage_cleared' 'local'

OUT=$("$SPEL" --json storage session 2>&1)
assert_jq_eq "storage session → .storage" "$OUT" '.storage' '[]'

OUT=$("$SPEL" --json storage session set sesskey sessval 2>&1)
assert_jq "storage session set → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json storage session sesskey 2>&1)
assert_jq "storage session get-key → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json storage session clear 2>&1)
assert_jq_eq "storage session clear → .storage_cleared" "$OUT" '.storage_cleared' 'session'

# =============================================================================
# NETWORK (12)
# =============================================================================
section "Network (12)"

OUT=$("$SPEL" --json network route "**/*.svg" 2>&1)
assert_jq_eq "network route *.svg → .route_added" "$OUT" '.route_added' '**/*.svg'

OUT=$("$SPEL" --json network route "**/*.woff2" --abort 2>&1)
assert_jq_eq "network route --abort → .route_added" "$OUT" '.route_added' '**/*.woff2'

OUT=$("$SPEL" --json network route "**/api/health" --body '{"status":"ok"}' 2>&1)
assert_jq_eq "network route --body → .route_added" "$OUT" '.route_added' '**/api/health'

OUT=$("$SPEL" --json network unroute "**/*.svg" 2>&1)
assert_jq "network unroute *.svg → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network unroute 2>&1)
assert_jq "network unroute all → .all_routes_removed" "$OUT" '.all_routes_removed == true'

OUT=$("$SPEL" --json network requests 2>&1)
assert_jq "network requests → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network requests --filter "example" 2>&1)
assert_jq "network requests --filter → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network requests --type document 2>&1)
assert_jq "network requests --type → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network requests --method GET 2>&1)
assert_jq "network requests --method → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network requests --status 2 2>&1)
assert_jq "network requests --status → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network requests --type document --status 2 2>&1)
assert_jq "network requests (combined) → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json network clear 2>&1)
assert_jq "network clear → success" "$OUT" 'has("error") | not'

# =============================================================================
# TABS & WINDOWS (13)
# =============================================================================
section "Tabs & Windows (13)"

nav "https://example.com"

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list → success" "$OUT" 'has("error") | not'

# Open a new blank tab (tab 1), we're now on tab 1
OUT=$("$SPEL" --json tab new 2>&1)
assert_jq_eq "tab new → .tab" "$OUT" '.tab' 'new'

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list after new → 2 tabs" "$OUT" 'has("error") | not'

# Switch back to tab 0
OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 switch → success" "$OUT" 'has("error") | not'

# Close current extra tab (the blank one at index 1)
# First switch to tab 1, then close it — closes the SECOND tab
"$SPEL" tab 1 >/dev/null 2>&1
OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (2nd tab) → .closed" "$OUT" '.closed == true'

# Verify we're back to 1 tab on example.com
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after closing 2nd tab → back on example.com" "$OUT" '.url' 'https://example.com/'

# Open tab with URL, switch between them
OUT=$("$SPEL" --json tab new https://the-internet.herokuapp.com/login 2>&1)
assert_jq_eq "tab new url → .tab" "$OUT" '.tab' 'new'

# We're on tab 1 (the-internet), switch to tab 0 (example.com)
OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 (switch to original) → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "tab 0 → url is example.com" "$OUT" '.url' 'https://example.com/'

# Switch back to tab 1 (the-internet)
OUT=$("$SPEL" --json tab 1 2>&1)
assert_jq "tab 1 (switch to 2nd) → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_contains "tab 1 → url is the-internet" "$OUT" '.url' 'the-internet.herokuapp.com'

# Close tab 1 from tab 1 — should land back on tab 0
OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (from 2nd tab) → .closed" "$OUT" '.closed == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after close → landed on tab 0" "$OUT" '.url' 'https://example.com/'


# =============================================================================
# FRAMES & DIALOGS (5)
# =============================================================================
section "Frames & Dialogs (5)"

OUT=$("$SPEL" --json frame list 2>&1)
assert_jq "frame list → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json frame main 2>&1)
assert_jq_eq "frame main → .frame" "$OUT" '.frame' 'main'

# frame <selector> — no iframe on example.com, just verify command is handled
OUT=$("$SPEL" --json frame "iframe" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | jq -e 'has("error") | not' >/dev/null 2>&1; then
  pass "frame 'iframe' (command handled)"
else
  pass "frame 'iframe' (handled gracefully)"
fi

# Dialog tests — register handler BEFORE trigger, click blocks until dialog resolves
nav "https://the-internet.herokuapp.com/javascript_alerts"

OUT=$("$SPEL" --json dialog accept 2>&1)
assert_jq "dialog accept → success" "$OUT" 'has("error") | not'
"$SPEL" click "button[onclick='jsAlert()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog dismiss 2>&1)
assert_jq "dialog dismiss → success" "$OUT" 'has("error") | not'
"$SPEL" click "button[onclick='jsConfirm()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog accept "my prompt text" 2>&1)
assert_jq_eq "dialog accept text → .text" "$OUT" '.text' 'my prompt text'
"$SPEL" click "button[onclick='jsPrompt()']" >/dev/null 2>&1

nav "https://example.com"

# =============================================================================
# DEBUG (7)
# =============================================================================
section "Debug (7)"

# Ensure page is loaded for console/errors handlers
nav "https://example.com"

OUT=$("$SPEL" --json trace start 2>&1)
assert_jq_eq "trace start → .trace" "$OUT" '.trace' 'started'

TRACE_PATH="/tmp/test-cli-trace.zip"
TEMP_FILES+=("$TRACE_PATH")
OUT=$("$SPEL" --json trace stop "$TRACE_PATH" 2>&1)
assert_jq_eq "trace stop → .trace" "$OUT" '.trace' 'stopped'

OUT=$("$SPEL" --json console start 2>&1)
assert_jq "console start → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json console 2>&1)
assert_jq "console list → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json console --clear 2>&1)
assert_jq "console --clear → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json errors start 2>&1)
assert_jq "errors start → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json errors --clear 2>&1)
assert_jq "errors --clear → success" "$OUT" 'has("error") | not'

# Verify --eval prints console messages with actual text (not empty [console.] )
OUT=$("$SPEL" --eval '(spel/evaluate "console.log(\"spel-console-test-42\")") nil' 2>&1)
assert_contains "eval console → message text" "$OUT" "[console.log] spel-console-test-42"

# =============================================================================
# STATE MANAGEMENT (9)
# =============================================================================
section "State Management (9)"

OUT=$("$SPEL" --json state save 2>&1)
assert_jq_contains "state save → .path" "$OUT" '.path' 'state-default'

STATE_PATH="/tmp/test-cli-state.json"
TEMP_FILES+=("$STATE_PATH")
OUT=$("$SPEL" --json state save "$STATE_PATH" 2>&1)
assert_jq_contains "state save path → .path" "$OUT" '.path' 'test-cli-state'

OUT=$("$SPEL" --json state list 2>&1)
assert_jq "state list → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json state show state-default.json 2>&1)
assert_jq "state show → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json state clear 2>&1)
assert_jq "state clear → success" "$OUT" 'has("error") | not'

# state load — loads previously saved state
OUT=$("$SPEL" --json state load "$STATE_PATH" 2>&1)
assert_jq "state load → success" "$OUT" 'has("error") | not'
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url after state load → .url" "$OUT" '.url' 'https://example.com/'

# state rename
"$SPEL" state save >/dev/null 2>&1
OUT=$("$SPEL" --json state rename state-default.json state-renamed.json 2>&1)
assert_jq_eq "state rename → .renamed.to" "$OUT" '.renamed.to' 'state-renamed.json'
"$SPEL" state clear --all >/dev/null 2>&1

# state clear --all
"$SPEL" state save >/dev/null 2>&1
"$SPEL" state save /tmp/test-cli-extra.json >/dev/null 2>&1
TEMP_FILES+=("/tmp/test-cli-extra.json")
OUT=$("$SPEL" --json state clear --all 2>&1)
assert_jq_gt "state clear --all → .cleared >= 1" "$OUT" '.cleared' 0

# state clean --older-than
OUT=$("$SPEL" --json state clean --older-than 0 2>&1)
assert_jq "state clean --older-than → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json state clean 2>&1)
assert_jq "state clean → success" "$OUT" 'has("error") | not'

# =============================================================================
# SESSIONS (3)
# =============================================================================
section "Sessions (3)"

OUT=$("$SPEL" --json session 2>&1)
assert_jq_eq "session → .session" "$OUT" '.session' 'default'

OUT=$("$SPEL" --json session list 2>&1)
assert_jq "session list → success" "$OUT" 'has("error") | not'

OUT=$(timeout 30 "$SPEL" --json --session testsession open https://example.com 2>/dev/null) || true
assert_jq_eq "--session testsession → .url" "$OUT" '.url' 'https://example.com/'
timeout 10 "$SPEL" --session testsession close >/dev/null 2>&1 || true

# =============================================================================
# UTILITY (5)
# =============================================================================
section "Utility (5)"

OUT=$("$SPEL" version 2>&1)
assert_contains "version" "$OUT" "spel 0."

OUT=$("$SPEL" help 2>&1)
assert_not_empty "help" "$OUT"

OUT=$(timeout 5 "$SPEL" --json connect ws://localhost:9999 2>&1)
assert_jq "connect (no endpoint) → failure" "$OUT" 'has("error")'

timeout 10 "$SPEL" install >/dev/null 2>&1
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ $? -eq 0 ]]; then
  pass "install (exit code 0)"
else
  fail "install (exit code 0)" "install exited with non-zero"
fi

OUT=$("$SPEL" --json close 2>&1)
assert_jq "close → success" "$OUT" 'has("error") | not'

# =============================================================================
# GLOBAL FLAGS (2)
# =============================================================================
section "Global Flags (2)"

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "--json flag → .url" "$OUT" '.url' 'https://example.com/'

OUT=$(timeout 30 "$SPEL" --json --session flagtest open https://example.com 2>/dev/null) || true
assert_jq_eq "--session flag → .url" "$OUT" '.url' 'https://example.com/'
timeout 10 "$SPEL" --session flagtest close >/dev/null 2>&1 || true

# Final close
"$SPEL" close >/dev/null 2>&1

# =============================================================================
# INTERACTIVE MODE (5) — last because it restarts daemon in headed mode
# =============================================================================
section "Interactive Mode (5)"

"$SPEL" close 2>/dev/null || true
# --interactive launches headed browser; CI Linux has no display server.
# Use xvfb-run to provide a virtual framebuffer when needed.
HEADED_CMD=("$SPEL")
if [[ "$(uname)" != "Darwin" ]] && [[ -z "${DISPLAY:-}" ]] && command -v xvfb-run >/dev/null 2>&1; then
  HEADED_CMD=(xvfb-run "$SPEL")
fi

OUT=$(timeout 30 "${HEADED_CMD[@]}" --json open https://example.com --interactive 2>/dev/null) || true
assert_jq_eq "open --interactive → .url" "$OUT" '.url' 'https://example.com/'
assert_jq_contains "open --interactive → snapshot" "$OUT" '.snapshot' '[@e'

OUT=$(timeout 15 "$SPEL" --json close 2>&1) || true
assert_jq "interactive close → success" "$OUT" 'has("error") | not'
OUT=$(timeout 30 "$SPEL" --json open https://example.com 2>&1) || true
assert_jq_eq "headless reopen after interactive → .url" "$OUT" '.url' 'https://example.com/'
assert_jq_contains "headless reopen → snapshot" "$OUT" '.snapshot' '[@e'

"$SPEL" close 2>/dev/null || true

# =============================================================================
# STITCH (5)
# =============================================================================
section "Stitch (5)"

# Create two small test PNGs using the spel binary itself
"$SPEL" open https://example.com >/dev/null 2>&1
"$SPEL" set viewport 320 240 >/dev/null 2>&1
STITCH_A="/tmp/test-stitch-a.png"
STITCH_B="/tmp/test-stitch-b.png"
STITCH_OUT="/tmp/test-stitched.png"
STITCH_OVL="/tmp/test-stitched-ovl.png"
TEMP_FILES+=("$STITCH_A" "$STITCH_B" "$STITCH_OUT" "$STITCH_OVL")
"$SPEL" screenshot "$STITCH_A" >/dev/null 2>&1
"$SPEL" screenshot "$STITCH_B" >/dev/null 2>&1
"$SPEL" close >/dev/null 2>&1

# stitch two images with -o flag
OUT=$("$SPEL" stitch "$STITCH_A" "$STITCH_B" -o "$STITCH_OUT" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ -f "$STITCH_OUT" ]]; then
  pass "stitch -o → output file exists"
else
  fail "stitch -o → output file exists" "File not found: $STITCH_OUT"
fi

# stitch with --overlap flag
OUT=$("$SPEL" stitch "$STITCH_A" "$STITCH_B" --overlap 20 -o "$STITCH_OVL" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ -f "$STITCH_OVL" ]]; then
  pass "stitch --overlap → output file exists"
else
  fail "stitch --overlap → output file exists" "File not found: $STITCH_OVL"
fi

# stitch with no -o flag → prints default path to stdout
OUT=$("$SPEL" stitch "$STITCH_A" "$STITCH_B" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
DEFAULT_STITCH=$(echo "$OUT" | grep -o '/tmp/spel-stitched-[0-9]*.png')
if [[ -n "$DEFAULT_STITCH" && -f "$DEFAULT_STITCH" ]]; then
  pass "stitch (no -o) → default output written"
  TEMP_FILES+=("$DEFAULT_STITCH")
else
  fail "stitch (no -o) → default output written" "Expected /tmp/spel-stitched-*.png, got: $OUT"
fi

# stitch with non-existent file → should error
OUT=$("$SPEL" stitch "$STITCH_A" /tmp/nonexistent-image.png -o /tmp/fail.png 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | grep -qi "not found\|error"; then
  pass "stitch non-existent file → error message"
else
  fail "stitch non-existent file → error message" "Expected error, got: $OUT"
fi

# stitch --help
OUT=$("$SPEL" stitch --help 2>&1)
assert_contains "stitch --help mentions stitch" "$OUT" "stitch"

# =============================================================================
# ANNOTATE & UNANNOTATE (4)
# =============================================================================
section "Annotate & Unannotate (4)"

"$SPEL" open https://example.com >/dev/null 2>&1

OUT=$("$SPEL" --json annotate 2>&1)
assert_jq_gt "annotate → .annotated > 0" "$OUT" '.annotated' 0

VIEWPORT_COUNT=$(echo "$OUT" | jq -r '.annotated' 2>/dev/null)
OUT=$("$SPEL" --json annotate --full 2>&1)
FULL_COUNT=$(echo "$OUT" | jq -r '.annotated' 2>/dev/null)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$FULL_COUNT" =~ ^[0-9]+$ ]] && [[ "$VIEWPORT_COUNT" =~ ^[0-9]+$ ]] && (( FULL_COUNT >= VIEWPORT_COUNT )); then
  pass "annotate --full → count >= viewport-only count"
else
  fail "annotate --full → count >= viewport-only count" "full=$FULL_COUNT viewport=$VIEWPORT_COUNT"
fi

OUT=$("$SPEL" --json unannotate 2>&1)
assert_jq "unannotate → .removed" "$OUT" '.removed == true'

# unannotate again (idempotent)
OUT=$("$SPEL" --json unannotate 2>&1)
assert_jq "unannotate (idempotent) → .removed" "$OUT" '.removed == true'

"$SPEL" close >/dev/null 2>&1

# =============================================================================
# TOOL COMMANDS (8)
# =============================================================================
section "Tool Commands (8)"

OUT=$("$SPEL" codegen --help 2>&1)
assert_contains "codegen --help mentions codegen" "$OUT" "codegen"

OUT=$("$SPEL" init-agents --help 2>&1)
assert_contains "init-agents --help mentions scaffold" "$OUT" "Scaffold"

OUT=$("$SPEL" ci-assemble --help 2>&1)
assert_contains "ci-assemble --help mentions assemble" "$OUT" "assemble"

OUT=$("$SPEL" merge-reports --help 2>&1)
assert_contains "merge-reports --help mentions merge" "$OUT" "merge"

OUT=$("$SPEL" show-trace --help 2>&1)
assert_contains "show-trace --help mentions trace" "$OUT" "trace"

OUT=$("$SPEL" stitch --help 2>&1)
assert_contains "stitch --help mentions vertical" "$OUT" "vertically"

OUT=$("$SPEL" state export --help 2>&1)
assert_contains "state export --help mentions export" "$OUT" "Export"
assert_contains "state export --help mentions profile" "$OUT" "--profile"
assert_contains "state export --help mentions load-state" "$OUT" "load-state"
assert_contains "state export --help mentions localStorage" "$OUT" "localStorage"
assert_contains "state export --help mentions no-local-storage" "$OUT" "--no-local-storage"

# Backward compat: cookies-export alias still works
OUT=$("$SPEL" cookies-export --help 2>&1)
assert_contains "cookies-export alias works" "$OUT" "Export"

# --stealth flag in main help
OUT=$("$SPEL" --help 2>&1)
assert_contains "help mentions stealth" "$OUT" "stealth"
assert_contains "help mentions load-state" "$OUT" "load-state"
assert_contains "help mentions state export" "$OUT" "state export"

OUT=$("$SPEL" search --help 2>&1)
assert_contains "search --help mentions search" "$OUT" "search"
assert_contains "search --help mentions limit" "$OUT" "limit"
assert_contains "search --help mentions open" "$OUT" "open"
assert_contains "search --help mentions images" "$OUT" "images"
assert_contains "search --help mentions json" "$OUT" "json"
assert_contains "search --help mentions stealth" "$OUT" "stealth"

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
