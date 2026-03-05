#!/usr/bin/env bash
# verify.sh — Automated verification for spel
#
# Replaces the manual verification checklist. All logic lives here.
#
# Usage:
#   ./verify.sh              # Full verification (default)
#   ./verify.sh --allure     # Allure-only (report rendering changes)
#   ./verify.sh --quick      # Format + lint only (no build/test)
#
# Each step writes:
#   .verification/<step>.log   — stdout + stderr
#   .verification/<step>.code  — exit code (0=pass, non-zero=fail, skip=skipped)
#   .verification/summary.log  — final report
#
# On failure: stops at the failed step. Fix the issue and re-run.
# The .verification/ directory is gitignored — never committed.

set -uo pipefail

# Prevent Playwright from auto-downloading browsers (slow + geo-blocked in some regions).
# Browsers should be installed via `spel install` or system packages.
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

VERIFY_DIR=".verification"
rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR"

TOTAL=0
PASSED=0
SKIPPED=0
FAILED_STEP=""

# --- Colors (disabled when piped) ---
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BLUE=''; BOLD=''; NC=''
fi

# Use Firefox for tests when Playwright Chromium is not installed.
# Playwright's CDN is geo-blocked in some regions (403 on chrome-for-testing-public).
# System Chromium works for the CLI (--executable-path) but Playwright's Java test
# fixtures require a Playwright-managed browser. Firefox installs via Mozilla CDN and
# is always available after `spel install firefox`.
if [ ! -d "${HOME}/.cache/ms-playwright/chromium_headless_shell-1208" ]; then
  if [ -d "${HOME}/.cache/ms-playwright/firefox-1509" ]; then
    export SPEL_BROWSER=firefox
    printf "${YELLOW}NOTE: Using Firefox for tests (Playwright Chromium not installed)${NC}\n"
  fi
fi

# --- Step runner ---
# Runs a command, captures output to .verification/<name>.log,
# writes exit code to .verification/<name>.code.
# Returns 1 on failure (caller should bail).
step() {
  local name="$1" desc="$2"
  shift 2
  TOTAL=$((TOTAL + 1))
  printf "${BLUE}[%02d]${NC} %-45s " "$TOTAL" "$desc"

  if "$@" > "$VERIFY_DIR/$name.log" 2>&1; then
    echo "0" > "$VERIFY_DIR/$name.code"
    PASSED=$((PASSED + 1))
    printf "${GREEN}PASS${NC}\n"
    return 0
  else
    local code=$?
    echo "$code" > "$VERIFY_DIR/$name.code"
    FAILED_STEP="$name"
    printf "${RED}FAIL${NC} (exit $code)\n"
    printf "  ${RED}see: .verification/$name.log${NC}\n"
    echo ""
    tail -20 "$VERIFY_DIR/$name.log" | sed 's/^/    /'
    echo ""
    return 1
  fi
}

# --- Skip a step (logged but not executed) ---
skip() {
  local name="$1" desc="$2" reason="$3"
  TOTAL=$((TOTAL + 1))
  SKIPPED=$((SKIPPED + 1))
  PASSED=$((PASSED + 1))
  printf "${BLUE}[%02d]${NC} %-45s ${YELLOW}SKIP${NC} (%s)\n" "$TOTAL" "$desc" "$reason"
  echo "skip" > "$VERIFY_DIR/$name.code"
  echo "Skipped: $reason" > "$VERIFY_DIR/$name.log"
}

# --- Summary ---
summary() {
  local failed=$((TOTAL - PASSED))
  echo ""
  if [ $failed -eq 0 ]; then
    printf "${GREEN}${BOLD}All %d steps passed" "$TOTAL"
    [ $SKIPPED -gt 0 ] && printf " (%d skipped)" "$SKIPPED"
    printf "${NC}\n"
  else
    printf "${RED}${BOLD}Failed at step: %s (%d of %d passed)${NC}\n" "$FAILED_STEP" "$PASSED" "$TOTAL"
    printf "Fix the issue, then re-run: ${BOLD}./verify.sh${NC}\n"
  fi
  echo ""

  # Write machine-readable summary
  {
    echo "verify.sh — $(date -Iseconds)"
    echo "total=$TOTAL passed=$PASSED failed=$failed skipped=$SKIPPED"
    [ -n "$FAILED_STEP" ] && echo "stopped_at=$FAILED_STEP"
    echo ""
    for f in "$VERIFY_DIR"/*.code; do
      [ -f "$f" ] || continue
      local sname
      sname=$(basename "$f" .code)
      [ "$sname" = "summary" ] && continue
      printf "  %-25s %s\n" "$sname" "$(cat "$f")"
    done
  } > "$VERIFY_DIR/summary.log"

  [ $failed -eq 0 ]
}

# =============================================================================
# Custom step functions
# =============================================================================

# Lint: info-level diagnostics are OK (pre-existing, intentional).
# Only fail on error or warning.
_lint() {
  local output
  output=$(clojure-lsp diagnostics --raw 2>&1) || true
  echo "$output"
  if echo "$output" | grep -E ": (error|warning):" > /dev/null 2>&1; then
    echo ""
    echo "FAILED: Lint errors or warnings found (see above)"
    return 1
  fi
  echo ""
  echo "OK: info-level diagnostics only (pre-existing, not errors)"
}

# Test with sanity check on test counts.
_test_full() {
  local output code=0
  output=$(make test 2>&1) || code=$?
  echo "$output"
  [ $code -ne 0 ] && return $code

  # Sanity: lazytest should run ~1268 cases
  local lt_count
  lt_count=$(echo "$output" | grep -oP 'Ran \K\d+(?= test cases)' | tail -1 || true)
  if [ -n "$lt_count" ] && [ "$lt_count" -lt 1000 ]; then
    echo ""
    echo "WARNING: Only $lt_count lazytest cases (expected ~1268). Possible subset run."
    return 1
  fi

  # Sanity: CLI bash should run ~270 assertions
  local cli_count
  cli_count=$(echo "$output" | grep -oP 'Total: \K\d+' | tail -1 || true)
  if [ -n "$cli_count" ] && [ "$cli_count" -lt 200 ]; then
    echo ""
    echo "WARNING: Only $cli_count CLI assertions (expected ~270). Possible subset run."
    return 1
  fi
}

# Secret scan against base branch.
_secret_scan() {
  local base
  base=$(git merge-base HEAD origin/main 2>/dev/null || git merge-base HEAD main 2>/dev/null || echo "")
  if [ -z "$base" ]; then
    echo "No base branch found — skipping secret scan"
    return 0
  fi
  local hits
  hits=$(git diff "$base"..HEAD | grep -iE "(sk_|lin_api_|nvapi-|AIzaSy|ghp_|password\s*=\s*\S{8})" || true)
  if [ -n "$hits" ]; then
    echo "FAILED: Potential secrets in diff:"
    echo "$hits"
    return 1
  fi
  echo "No secrets found"
}

# =============================================================================
# Detect what changed (for smart skipping)
# =============================================================================

NEEDS_REGEN=true  # Default: run everything

detect_regen_triggers() {
  local base
  base=$(git merge-base HEAD origin/main 2>/dev/null || git merge-base HEAD main 2>/dev/null || echo "")
  [ -z "$base" ] && return  # Can't detect — run everything

  local changed
  changed=$(git diff --name-only "$base"..HEAD 2>/dev/null || echo "")

  NEEDS_REGEN=false
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    if [[ "$file" =~ templates/ || "$file" =~ sci_env\.clj || "$file" =~ gen_docs\.clj ]]; then
      NEEDS_REGEN=true
      return
    fi
  done <<< "$changed"
}

# =============================================================================
# Verification modes
# =============================================================================

verify_quick() {
  printf "\n${BOLD}Quick verification (format + lint)${NC}\n\n"
  step "format" "Format source"    make format || return 1
  step "lint"   "Lint diagnostics" _lint       || return 1
  summary
}

verify_allure() {
  printf "\n${BOLD}Allure-only verification${NC}\n\n"
  step "format" "Format source"         make format     || return 1
  step "lint"   "Lint diagnostics"      _lint           || return 1
  step "test"   "Test + Allure report"  make test-allure || return 1
  summary
}

verify_full() {
  detect_regen_triggers
  printf "\n${BOLD}Full verification${NC}\n\n"

  # 1. Format (must run BEFORE tests — format changes must be tested)
  step "format"         "Format source"                           make format              || return 1

  # 2. Lint (errors/warnings fail, info is OK)
  step "lint"           "Lint diagnostics"                        _lint                    || return 1

  # 3. GraalVM safety
  step "validate-graal" "GraalVM safety (reflection/boxed math)"  make validate-safe-graal || return 1

  # 4. Gen docs (only if regen triggers changed)
  if $NEEDS_REGEN; then
    step "gen-docs" "Generate API docs" make gen-docs || return 1
  else
    skip "gen-docs" "Generate API docs" "no regen triggers changed"
  fi

  # 5. Build native binary
  step "install-local"  "Build native binary"                     make install-local       || return 1

  # 6. Smoke test
  step "smoke"          "Smoke test (version + help)"             bash -c 'spel version && spel --help > /dev/null' || return 1

  # 7. Full test suite (lazytest + CLI bash regression)
  step "test"           "Full test suite (lazytest + CLI)"        _test_full               || return 1

  # 8. Regenerate agent scaffolding (only if regen triggers changed)
  if $NEEDS_REGEN; then
    step "init-agents" "Regenerate agent scaffolding" make init-agents 'ARGS=--ns com.blockether.spel --force' || return 1
  else
    skip "init-agents" "Regenerate agent scaffolding" "no regen triggers changed"
  fi

  # 9. Git hygiene
  step "git-check"      "Git hygiene (markers, whitespace)"       git diff --check         || return 1

  # 10. Secret scan
  step "secrets"        "Secret scan"                             _secret_scan             || return 1

  summary
}

# =============================================================================
# Entry point
# =============================================================================

MODE="${1:-full}"

case "$MODE" in
  --quick|-q)   verify_quick  ;;
  --allure|-a)  verify_allure ;;
  --full|-f|*)  verify_full   ;;
esac
