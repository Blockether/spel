#!/usr/bin/env bash
# =============================================================================
# Shared test helpers for spel CLI tests
# Source this from individual test scripts:
#   source "$(dirname "$0")/helpers.sh"
# =============================================================================

set -o pipefail

SPEL="${SPEL:-$(dirname "$0")/../target/spel}"
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
# Assertion Functions
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

# assert_jq_eq: check jq path == exact value
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
# Pre-flight: ensure clean state
# ---------------------------------------------------------------------------
preflight() {
  "$SPEL" close 2>/dev/null || true
  # Kill only actual spel binary processes (daemon), not bash scripts referencing spel
  pkill -9 -xf ".*spel daemon.*" 2>/dev/null || true
  pkill -9 -x spel 2>/dev/null || true
  rm -f /tmp/spel-*.sock /tmp/spel-*.pid 2>/dev/null || true
  "$SPEL" open https://example.com >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
print_summary() {
  local END_TIME
  END_TIME=$(date +%s)
  local ELAPSED=$((END_TIME - START_TIME))

  echo ""
  echo -e "${BOLD}───────────────────────────────────────────────────${NC}"
  echo -e "  ${GREEN}Passed:  $PASS_COUNT${NC}"
  echo -e "  ${RED}Failed:  $FAIL_COUNT${NC}"
  echo -e "  ${RED}Errors:  $ERROR_COUNT${NC}"
  echo -e "  ${BOLD}Total:   $TOTAL_COUNT${NC}"
  echo -e "  Duration: ${ELAPSED}s"
  echo ""

  if [[ $FAIL_COUNT -eq 0 && $ERROR_COUNT -eq 0 ]]; then
    echo -e "  ${GREEN}${BOLD}ALL PASSED ✓${NC}"
    exit 0
  else
    echo -e "  ${RED}${BOLD}FAILURES ✗${NC}"
    exit 1
  fi
}
