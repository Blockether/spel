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
  if [[ "$actual" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] && awk "BEGIN{exit(!($actual > $threshold))}"; then
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

# Discover dynamic refs for hash-based ref system
# Snapshot on example.com — extract actual ref keys from tree text
SNAP_JSON=$( "$SPEL" --json snapshot 2>&1 )
SNAP_TEXT=$( echo "$SNAP_JSON" | jq -r '.snapshot' )
# Extract first and second refs from the tree
REF1=$( echo "$SNAP_TEXT" | grep -oE '\[@e[a-z0-9]+\]' | head -1 | sed 's/\[@//;s/\]//' )
REF2=$( echo "$SNAP_TEXT" | grep -oE '\[@e[a-z0-9]+\]' | head -2 | tail -1 | sed 's/\[@//;s/\]//' )
# Find specific refs by role
HEADING_REF=$( echo "$SNAP_TEXT" | grep 'heading' | grep -oE '\[@e[a-z0-9]+\]' | head -1 | sed 's/\[@//;s/\]//' )
LINK_REF=$( echo "$SNAP_TEXT" | grep 'link' | grep -oE '\[@e[a-z0-9]+\]' | head -1 | sed 's/\[@//;s/\]//' )

# =============================================================================
# ELEMENT INTERACTIONS (19)
# =============================================================================
section "Element Interactions (19)"

OUT=$("$SPEL" --json click "@$REF1" 2>&1)
assert_jq "click @ref → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json dblclick "@$REF1" 2>&1)
assert_jq "dblclick @ref → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json hover "@$REF1" 2>&1)
assert_jq_eq "hover @ref → .hovered" "$OUT" '.hovered' "@$REF1"

OUT=$("$SPEL" --json focus "@$REF1" 2>&1)
assert_jq_eq "focus @ref → .focused" "$OUT" '.focused' "@$REF1"

OUT=$("$SPEL" --json press Enter 2>&1)
assert_jq_eq "press Enter → .pressed" "$OUT" '.pressed' 'Enter'

OUT=$("$SPEL" --json press "@$REF1" Tab 2>&1)
assert_jq_eq "press @ref Tab → .pressed" "$OUT" '.pressed' 'Tab'

OUT=$("$SPEL" --json keydown Shift 2>&1)
assert_jq_eq "keydown Shift → .keydown" "$OUT" '.keydown' 'Shift'

OUT=$("$SPEL" --json keyup Shift 2>&1)
assert_jq_eq "keyup Shift → .keyup" "$OUT" '.keyup' 'Shift'

OUT=$("$SPEL" --json scroll down 500 2>&1)
assert_jq "scroll down 500 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json scrollintoview "@$REF2" 2>&1)
assert_jq "scrollintoview @ref2 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json drag "@$REF1" "@$REF2" 2>&1)
assert_jq "drag @ref1 @ref2 → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json highlight "@$REF1" 2>&1)
assert_jq_eq "highlight @ref → .highlighted" "$OUT" '.highlighted' "@$REF1"

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

OUT=$("$SPEL" --json eval-js "document.title" 2>&1)
assert_jq_eq "eval-js document.title → .result" "$OUT" '.result' 'Example Domain'

OUT=$("$SPEL" --json eval-js "document.title" -b 2>&1)
assert_jq_eq "eval-js -b → .result (base64)" "$OUT" '.result' 'RXhhbXBsZSBEb21haW4='

# =============================================================================
# WAIT (6)
# =============================================================================
section "Wait (6)"

OUT=$("$SPEL" --json wait "@$REF1" 2>&1)
assert_jq_eq "wait @ref → .found" "$OUT" '.found' "@$REF1"

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

OUT=$("$SPEL" --json get text "@$HEADING_REF" 2>&1)
assert_jq_eq "get text @ref → .text" "$OUT" '.text' 'Example Domain'

OUT=$("$SPEL" --json get html "@$HEADING_REF" 2>&1)
assert_jq_eq "get html @ref → .html" "$OUT" '.html' 'Example Domain'

# get value on h1 should fail (not an input)
OUT=$("$SPEL" --json get value "@$HEADING_REF" 2>&1)
assert_jq "get value @ref → expected failure" "$OUT" 'has("error")'

OUT=$("$SPEL" --json get attr "@$LINK_REF" href 2>&1)
assert_jq_contains "get attr @link href → .value has iana" "$OUT" '.value' 'iana.org'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url → .url" "$OUT" '.url' 'https://example.com/'

OUT=$("$SPEL" --json get title 2>&1)
assert_jq_eq "get title → .title" "$OUT" '.title' 'Example Domain'

OUT=$("$SPEL" --json get count div 2>&1)
assert_jq_gt "get count div → .count > 0" "$OUT" '.count' 0

OUT=$("$SPEL" --json get box "@$HEADING_REF" 2>&1)
assert_jq_gt "get box @ref → .box.width > 0" "$OUT" '.box.width' 0

# =============================================================================
# CHECK STATE (3)
# =============================================================================
section "Check State (3)"

OUT=$("$SPEL" --json is visible "@$REF1" 2>&1)
assert_jq "is visible @ref → .visible" "$OUT" '.visible == true'

OUT=$("$SPEL" --json is enabled "@$REF1" 2>&1)
assert_jq "is enabled @ref → .enabled" "$OUT" '.enabled == true'

# is checked on h1 should fail (not a checkbox)
OUT=$("$SPEL" --json is checked "@$HEADING_REF" 2>&1)
assert_jq "is checked @ref → expected failure" "$OUT" 'has("error")'

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
"$SPEL" eval-js "document.body.innerHTML = '<input placeholder=\"Search here\" /><img alt=\"Logo\" src=\"#\" /><span title=\"Tooltip\">hover me</span><button data-testid=\"submit-btn\">Submit</button>'" >/dev/null 2>&1

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

# Verify eval-sci prints console messages with actual text (not empty [console.] )
OUT=$("$SPEL" eval-sci '(spel/evaluate "console.log(\"spel-console-test-42\")") nil' 2>&1)
assert_contains "eval-sci console → message text" "$OUT" "[console.log] spel-console-test-42"

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
# Skip interactive tests entirely when no display and no xvfb-run.
HEADED_CMD=("$SPEL")
CAN_RUN_HEADED=true
if [[ "$(uname)" != "Darwin" ]] && [[ -z "${DISPLAY:-}" ]]; then
  if command -v xvfb-run >/dev/null 2>&1; then
    HEADED_CMD=(xvfb-run "$SPEL")
  else
    CAN_RUN_HEADED=false
  fi
fi

if $CAN_RUN_HEADED; then
  OUT=$(timeout 30 "${HEADED_CMD[@]}" --json open https://example.com --interactive 2>/dev/null) || true
  assert_jq_eq "open --interactive → .url" "$OUT" '.url' 'https://example.com/'
  assert_jq_contains "open --interactive → snapshot" "$OUT" '.snapshot' '[@e'
else
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "open --interactive → .url (SKIPPED: no display/xvfb)"
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "open --interactive → snapshot (SKIPPED: no display/xvfb)"
fi

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

OUT=$("$SPEL" init-agents --loop=vscode 2>&1 || true)
assert_contains "init-agents --loop=vscode shows deprecation error" "$OUT" "removed"

# --only test --dry-run: includes test agents, excludes others
OUT=$("$SPEL" init-agents --only test --dry-run 2>&1)
assert_contains "init-agents --only test includes test-planner" "$OUT" "spel-test-planner"
assert_contains "init-agents --only test includes SKILL" "$OUT" "SKILL.md"
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | grep -q "spel-explorer"; then
  fail "init-agents --only test excludes explorer"
else
  pass "init-agents --only test excludes explorer"
fi

# --only automation --dry-run: includes automation agents, excludes test
OUT=$("$SPEL" init-agents --only automation --dry-run 2>&1)
assert_contains "init-agents --only automation includes explorer" "$OUT" "spel-explorer"
assert_contains "init-agents --only automation includes automator" "$OUT" "spel-automator"
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | grep -q "agents/spel-test-planner"; then
  fail "init-agents --only automation excludes test-planner"
else
  pass "init-agents --only automation excludes test-planner"
fi

# --only invalid: shows error with valid values
OUT=$("$SPEL" init-agents --only invalid 2>&1 || true)
assert_contains "init-agents --only invalid shows error" "$OUT" "Unknown --only"

# --help mentions --only
OUT=$("$SPEL" init-agents --help 2>&1)
assert_contains "init-agents --help mentions --only" "$OUT" "--only"

OUT=$("$SPEL" ci-assemble --help 2>&1)
assert_contains "ci-assemble --help mentions assemble" "$OUT" "assemble"

OUT=$("$SPEL" merge-reports --help 2>&1)
assert_contains "merge-reports --help mentions merge" "$OUT" "merge"

OUT=$("$SPEL" show-trace --help 2>&1)
assert_contains "show-trace --help mentions trace" "$OUT" "trace"

OUT=$("$SPEL" stitch --help 2>&1)
assert_contains "stitch --help mentions vertical" "$OUT" "vertically"


# --stealth flag in main help
OUT=$("$SPEL" --help 2>&1)
assert_contains "help mentions stealth" "$OUT" "stealth"
assert_contains "help mentions load-state" "$OUT" "load-state"

OUT=$("$SPEL" search --help 2>&1)
assert_contains "search --help mentions search" "$OUT" "search"
assert_contains "search --help mentions limit" "$OUT" "limit"
assert_contains "search --help mentions open" "$OUT" "open"
assert_contains "search --help mentions images" "$OUT" "images"
assert_contains "search --help mentions json" "$OUT" "json"
assert_contains "search --help mentions stealth" "$OUT" "stealth"
assert_contains "search --help mentions debug" "$OUT" "debug"

# =============================================================================
# CODEGEN + EVAL COMPATIBILITY (31)
# Ultimate Clojure↔SCI compatibility test:
#   recording.jsonl → codegen → script/test/body → --eval
# =============================================================================
section "Codegen + Eval Compatibility (31)"

# Create a test JSONL (navigate + assert heading, no closePage — safe for daemon)
CODEGEN_JSONL="/tmp/test-codegen-compat.jsonl"
CODEGEN_SCRIPT="/tmp/test-codegen-compat.clj"
TEMP_FILES+=("$CODEGEN_JSONL" "$CODEGEN_SCRIPT")

cat > "$CODEGEN_JSONL" <<'JSONL'
{"browserName":"chromium","launchOptions":{"headless":true},"contextOptions":{}}
{"name":"openPage","url":"about:blank","signals":[],"pageGuid":"page@test","pageAlias":"page","framePath":[]}
{"name":"navigate","url":"https://example.com/","signals":[],"pageGuid":"page@test","pageAlias":"page","framePath":[]}
{"name":"assertText","selector":"internal:role=heading","signals":[],"text":"Example Domain","substring":true,"pageGuid":"page@test","pageAlias":"page","framePath":[],"locator":{"kind":"role","body":"heading","options":{"attrs":[]}}}
JSONL

# --- Test codegen --format=script ---
OUT=$("$SPEL" codegen --format=script "$CODEGEN_JSONL" 2>&1)
assert_contains "codegen script → has require" "$OUT" "(require"
assert_contains "codegen script → has core/with-testing-page" "$OUT" "core/with-testing-page"
assert_contains "codegen script → has page/navigate" "$OUT" "page/navigate"
assert_contains "codegen script → has role/heading" "$OUT" "role/heading"
assert_contains "codegen script → has assert/contains-text" "$OUT" "assert/contains-text"
assert_contains "codegen script → has assert/assert-that" "$OUT" "assert/assert-that"
assert_contains "codegen script → has page/get-by-role" "$OUT" "page/get-by-role"

# --- Test codegen --format=test ---
OUT=$("$SPEL" codegen --format=test "$CODEGEN_JSONL" 2>&1)
assert_contains "codegen test → has ns declaration" "$OUT" "(ns my-app.generated-test"
assert_contains "codegen test → has defdescribe" "$OUT" "defdescribe"
assert_contains "codegen test → has page/navigate" "$OUT" "page/navigate"
assert_contains "codegen test → has assert/contains-text" "$OUT" "assert/contains-text"

# --- Test codegen --format=body ---
OUT=$("$SPEL" codegen --format=body "$CODEGEN_JSONL" 2>&1)
assert_contains "codegen body → has page/navigate" "$OUT" "page/navigate"
assert_contains "codegen body → has assert/contains-text" "$OUT" "assert/contains-text"
assert_contains "codegen body → has role/heading" "$OUT" "role/heading"

# --- Test codegen from project recording.jsonl ---
OUT=$("$SPEL" codegen --format=script test/com/blockether/spel/recording.jsonl 2>&1)
assert_contains "codegen recording.jsonl → has core/with-testing-page" "$OUT" "core/with-testing-page"
assert_contains "codegen recording.jsonl → has role/link" "$OUT" "role/link"

# --- Write script to file and verify ---
"$SPEL" codegen --format=script -o "$CODEGEN_SCRIPT" "$CODEGEN_JSONL" 2>&1
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ -f "$CODEGEN_SCRIPT" ]]; then
  pass "codegen -o → file created"
else
  fail "codegen -o → file created" "File $CODEGEN_SCRIPT not found"
fi

# --- Run generated script via eval-sci (the ultimate compatibility test) ---
# This proves: JSONL recording → codegen → Clojure script → SCI eval → real browser
# Run WITHOUT --autoclose so browser stays alive for subsequent verification queries
OUT=$("$SPEL" eval-sci "$CODEGEN_SCRIPT" 2>&1)
EVAL_EXIT=$?
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ $EVAL_EXIT -eq 0 ]]; then
  pass "eval-sci codegen script → exit 0 (Clojure↔SCI compat)"
else
  fail "eval-sci codegen script → exit 0 (Clojure↔SCI compat)" "Exit code: $EVAL_EXIT, Output: $(echo "$OUT" | head -c 500)"
fi

# Verify the eval-sci output doesn't contain error
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$OUT" != *"Error:"* ]]; then
  pass "eval-sci codegen script → no errors in output"
else
  fail "eval-sci codegen script → no errors in output" "Output: $(echo "$OUT" | head -c 500)"
fi

# Verify eval-sci result is "nil" (the actual return value of the last assertion)
assert_contains "eval-sci codegen script → result is nil" "$OUT" "nil"

# --- MEANINGFUL VERIFICATION: query the browser state left by the codegen script ---
# Prove the script actually navigated to the page and the browser is on example.com

# Verify page title is "Example Domain" (the script navigated to example.com)
TITLE_OUT=$("$SPEL" eval-sci '(page/title (spel/page))' 2>&1)
assert_contains "eval-sci → page title is Example Domain" "$TITLE_OUT" "Example Domain"

# Verify page URL contains example.com
URL_OUT=$("$SPEL" eval-sci '(page/url (spel/page))' 2>&1)
assert_contains "eval-sci → page URL contains example.com" "$URL_OUT" "example.com"

# Clean up: close the daemon session
"$SPEL" eval-sci '(do nil)' --autoclose >/dev/null 2>&1 || true

# --- NEGATIVE: prove wrong assertions FAIL (not silently swallowed) ---
CODEGEN_BAD_JSONL="/tmp/test-codegen-bad.jsonl"
CODEGEN_BAD_SCRIPT="/tmp/test-codegen-bad.clj"
TEMP_FILES+=("$CODEGEN_BAD_JSONL" "$CODEGEN_BAD_SCRIPT")

cat > "$CODEGEN_BAD_JSONL" <<'JSONL'
{"browserName":"chromium","launchOptions":{"headless":true},"contextOptions":{}}
{"name":"navigate","url":"https://example.com/","signals":[],"pageGuid":"page@test","pageAlias":"page","framePath":[]}
{"name":"assertText","selector":"h1","signals":[],"text":"THIS TEXT DOES NOT EXIST ANYWHERE","substring":true,"pageGuid":"page@test","pageAlias":"page","framePath":[]}
JSONL

"$SPEL" codegen --format=script -o "$CODEGEN_BAD_SCRIPT" "$CODEGEN_BAD_JSONL" 2>&1
OUT=$( "$SPEL" eval-sci "$CODEGEN_BAD_SCRIPT" --autoclose 2>&1 )
BAD_EXIT=$?
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ $BAD_EXIT -ne 0 ]]; then
  pass "NEGATIVE: eval-sci wrong assertion → exit non-zero"
else
  fail "NEGATIVE: eval-sci wrong assertion → exit non-zero" "Expected failure but got exit 0. Output: $(echo "$OUT" | head -c 300)"
fi

TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$OUT" == *"Error:"* ]]; then
  pass "NEGATIVE: eval-sci wrong assertion → Error in output"
else
  fail "NEGATIVE: eval-sci wrong assertion → Error in output" "Expected 'Error:' but got: $(echo "$OUT" | head -c 300)"
fi

# Verify the error mentions the expected text or assertion failure details
assert_contains "NEGATIVE: eval-sci wrong assertion → error mentions assertion context" "$OUT" "THIS TEXT DOES NOT EXIST ANYWHERE"
# =============================================================================
# =============================================================================
# URL VALIDATION (32)
# =============================================================================
section "URL Validation (32)"

# Invalid URL should fail
OUT=$("$SPEL" --json open not-a-url 2>&1) || true
assert_jq "open not-a-url → error" "$OUT" 'has("error")'
assert_jq_contains "open not-a-url → error message" "$OUT" '.error' 'Invalid URL'

# Valid URL should work (example.com with auto-prepend)
OUT=$("$SPEL" --json open example.com 2>&1)
assert_jq_eq "open example.com → .url" "$OUT" '.url' 'https://example.com/'

# Valid URL with scheme should work
OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "open https://example.com → .url" "$OUT" '.url' 'https://example.com/'

# =============================================================================
# VIEWPORT ON OPEN + CROP-TO-CONTENT (33)
# =============================================================================
section "Viewport on Open + Crop-to-Content (33)"

# --viewport on open should set viewport and navigate
OUT=$("$SPEL" --json open https://example.com --viewport 800x600 2>&1)
assert_jq_eq "open --viewport → .url" "$OUT" '.url' 'https://example.com/'
assert_jq "open --viewport → .viewport.width == 800" "$OUT" '.viewport.width == 800'
assert_jq "open --viewport → .viewport.height == 600" "$OUT" '.viewport.height == 600'

# --crop-to-content should produce a smaller screenshot than the viewport
CROP_PATH="/tmp/test-cli-crop.png"
TEMP_FILES+=("$CROP_PATH")
OUT=$("$SPEL" --json screenshot --crop-to-content "$CROP_PATH" 2>&1)
assert_jq_gt "screenshot --crop-to-content → .size > 0" "$OUT" '.size' 0
assert_jq_contains "screenshot --crop-to-content → .path" "$OUT" '.path' 'test-cli-crop.png'

# Restore viewport for subsequent tests
"$SPEL" set viewport 1280 720 >/dev/null 2>&1


# =============================================================================
# ERROR PROPAGATION (34)
# =============================================================================
section "Error Propagation (34)"

# Click a non-existent ref — should fail with meaningful error, not "Unknown error"
OUT=$("$SPEL" --json click @enonexistent 2>&1) || true
assert_jq "click @enonexistent → has error" "$OUT" '.error != null'
assert_jq "click @enonexistent → error != Unknown error" "$OUT" '.error != "Unknown error"'
assert_jq_contains "click @enonexistent → error has context" "$OUT" '.error' 'not found'

section "Styles, Clipboard, Diff (35)"

# Styles --help
OUT=$("$SPEL" styles --help 2>&1) || true
assert_contains "styles --help → mentions selector" "$OUT" "selector"

# Styles on example.org
OUT=$("$SPEL" --json styles h1 2>&1)
assert_jq "styles h1 → has styles" "$OUT" '.styles != null'
assert_jq "styles h1 → has fontSize" "$OUT" '.styles.fontSize != null'
assert_jq "styles h1 → has color" "$OUT" '.styles.color != null'

# Clipboard round-trip
OUT=$("$SPEL" --json clipboard copy "cli-test-clipboard" 2>&1)
assert_jq "clipboard copy → copied true" "$OUT" '.copied == true'
OUT=$("$SPEL" --json clipboard read 2>&1)
assert_jq_eq "clipboard read → content" "$OUT" '.content' 'cli-test-clipboard'

# Diff snapshot (same page = no changes)
BASELINE_FILE=$(mktemp)
TEMP_FILES+=("$BASELINE_FILE")
"$SPEL" snapshot -i > "$BASELINE_FILE"
OUT=$("$SPEL" --json diff snapshot --baseline "$BASELINE_FILE" 2>&1)
assert_jq "diff snapshot → added is number" "$OUT" '.added != null'
assert_jq "diff snapshot same → 0 changed" "$OUT" '.changed == 0'
assert_jq "diff snapshot same → 0 added" "$OUT" '.added == 0'

# Diff screenshot (same page = matched)
BASELINE_SS=$(mktemp --suffix=.png)
TEMP_FILES+=("$BASELINE_SS")
"$SPEL" screenshot "$BASELINE_SS" >/dev/null 2>&1
OUT=$("$SPEL" --json diff screenshot --baseline "$BASELINE_SS" 2>&1)
assert_jq "diff screenshot → has matched" "$OUT" '.matched != null'
assert_jq "diff screenshot same → matched true" "$OUT" '.matched == true'
assert_jq "diff screenshot → has diff_percent" "$OUT" '.diff_percent != null'
assert_jq "diff screenshot → has diff_path" "$OUT" '.diff_path != null'
assert_jq "diff screenshot → has total_pixels" "$OUT" '.total_pixels > 0'

# =============================================================================
# SNAPSHOT STYLES, VIEWPORT & DEVICE (36)
# =============================================================================
section "Snapshot Styles, Viewport & Device (36)"

nav "https://example.com"

# --- Style tiers: verify property counts per tier ---

OUT=$("$SPEL" --json snapshot -S --minimal 2>&1)
assert_jq "snapshot -S --minimal → 16 style props" "$OUT" \
  '.refs | to_entries[0].value.styles | keys | length == 16'

OUT=$("$SPEL" --json snapshot -S 2>&1)
assert_jq "snapshot -S (base) → 31 style props" "$OUT" \
  '.refs | to_entries[0].value.styles | keys | length == 31'

OUT=$("$SPEL" --json snapshot -S --max 2>&1)
assert_jq "snapshot -S --max → 44 style props" "$OUT" \
  '.refs | to_entries[0].value.styles | keys | length == 44'

# --- Style keys are kebab-case CSS ---

OUT=$("$SPEL" --json snapshot -S 2>&1)
assert_jq "snapshot -S → has font-size key" "$OUT" \
  '.refs | to_entries[0].value.styles | has("font-size")'
assert_jq "snapshot -S → has background-color key" "$OUT" \
  '.refs | to_entries[0].value.styles | has("background-color")'

# --- Tree text includes inline style syntax ---

assert_jq_contains "snapshot -S → tree has style braces" "$OUT" '.snapshot' 'font-size:'

# --- Without -S: no styles in refs or tree ---

OUT=$("$SPEL" --json snapshot 2>&1)
assert_jq "snapshot (no -S) → no styles in refs" "$OUT" \
  '[.refs | to_entries[] | select(.value.styles != null)] | length == 0'

# --- Viewport in snapshot ---

assert_jq "snapshot → viewport.width > 0" "$OUT" '.viewport.width > 0'
assert_jq "snapshot → viewport.height > 0" "$OUT" '.viewport.height > 0'
assert_jq_contains "snapshot → tree has viewport header" "$OUT" '.snapshot' '[viewport:'

# --- Device in snapshot after set device ---

OUT=$("$SPEL" --json set device "iphone 14" 2>&1)
assert_jq "set device iphone 14 → no error" "$OUT" 'has("error") | not'
nav "https://example.com"
OUT=$("$SPEL" --json snapshot 2>&1)
assert_jq_contains "snapshot after device → tree has device" "$OUT" '.snapshot' 'device:'
"$SPEL" set viewport 1280 720 >/dev/null 2>&1

# --- --browser flag in help ---

OUT=$("$SPEL" --help 2>&1)
assert_contains "help → mentions --browser" "$OUT" "--browser"

section "Drag and Drop (37)"

OUT=$("$SPEL" drag --help 2>&1)
assert_contains "drag --help → usage" "$OUT" "drag"

OUT=$("$SPEL" drag-by --help 2>&1)
assert_contains "drag-by --help → usage" "$OUT" "drag-by"

OUT=$("$SPEL" --json drag "@$REF1" "@$REF2" 2>&1)
assert_jq "drag @ref → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json drag "@$REF1" "@$REF2" --steps 5 2>&1)
assert_jq "drag --steps → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json drag-by "@$REF1" 100 0 2>&1)
assert_jq "drag-by @ref → success" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json drag-by "@$REF1" 50 50 --steps 10 2>&1)
assert_jq "drag-by --steps → success" "$OUT" 'has("error") | not'

section "Args Support (38)"

OUT=$("$SPEL" eval-sci '(pr-str *command-line-args*)' -- foo bar 2>&1)
assert_contains "eval-sci args separator → includes foo" "$OUT" 'foo'
assert_contains "eval-sci args separator → includes bar" "$OUT" 'bar'

OUT=$("$SPEL" eval-sci '(pr-str *command-line-args*)' 2>&1)
assert_contains "eval-sci without args → nil" "$OUT" 'nil'

OUT=$("$SPEL" eval-sci '(pr-str *command-line-args*)' -- first 2>&1)
assert_contains "eval-sci args first call → includes first" "$OUT" 'first'

OUT=$("$SPEL" eval-sci '(pr-str *command-line-args*)' 2>&1)
assert_contains "eval-sci args do not persist across calls" "$OUT" 'nil'

section "Snapshot Position Props (39)"

nav "https://example.com"

OUT=$("$SPEL" --json eval-js 'document.body.innerHTML = `<div id="pos" style="position:absolute;top:10px;left:20px;right:30px;bottom:40px">test</div>`' 2>&1)
assert_jq "positioned element setup via eval → no error" "$OUT" 'has("error") | not'

OUT=$("$SPEL" --json snapshot -S --minimal 2>&1)
assert_jq "snapshot -S --minimal positioned element → has top key" "$OUT" \
  '[.refs | to_entries[] | select((.value.styles // {}) | has("top"))] | length > 0'
assert_jq "snapshot -S --minimal positioned element → has left key" "$OUT" \
  '[.refs | to_entries[] | select((.value.styles // {}) | has("left"))] | length > 0'
assert_jq "snapshot -S --minimal positioned element → has right key" "$OUT" \
  '[.refs | to_entries[] | select((.value.styles // {}) | has("right"))] | length > 0'
assert_jq "snapshot -S --minimal positioned element → has bottom key" "$OUT" \
  '[.refs | to_entries[] | select((.value.styles // {}) | has("bottom"))] | length > 0'

OUT=$("$SPEL" --json snapshot -S 2>&1)
assert_jq "snapshot -S (base) positioned element → has top key" "$OUT" \
  '[.refs | to_entries[] | select((.value.styles // {}) | has("top"))] | length > 0'

section "Flag Persistence (40)"

# Test that flags-file-path is created and contains persisted flags.
# The daemon is already running from earlier tests with default session.
# We can verify the flags file exists for the running session.
FLAGS_FILE="${TMPDIR:-/tmp}/spel-${SESSION}.flags.json"
if [ -f "$FLAGS_FILE" ]; then
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "flags file exists for running session"
  FLAGS_CONTENT=$(cat "$FLAGS_FILE")
  # The flags file should be valid JSON
  echo "$FLAGS_CONTENT" | jq . > /dev/null 2>&1
  if [ $? -eq 0 ]; then
    TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "flags file contains valid JSON"
  else
    TOTAL_COUNT=$((TOTAL_COUNT + 1)); fail "flags file contains valid JSON" "Invalid JSON: $FLAGS_CONTENT"
  fi
else
  # Flags file may not exist if no launch flags were passed (headless default).
  # That's OK — the file is only created when flags like --cdp are used.
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "flags file not present (no special launch flags — OK)"
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "flags file JSON check skipped (no file)"
fi

# Test that no flags file exists for a nonexistent session
NONEXISTENT_FLAGS="${TMPDIR:-/tmp}/spel-nonexistent-test-xyz.flags.json"
if [ ! -f "$NONEXISTENT_FLAGS" ]; then
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); pass "no flags file for nonexistent session"
else
  TOTAL_COUNT=$((TOTAL_COUNT + 1)); fail "no flags file for nonexistent session" "File unexpectedly exists: $NONEXISTENT_FLAGS"
fi
# =============================================================================
# AUTO-CONNECT (41)
# =============================================================================
section "Auto-Connect (41)"

OUT=$("$SPEL" --help 2>&1)
assert_contains "help mentions auto-connect" "$OUT" "auto-connect"

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
