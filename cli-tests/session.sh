#!/usr/bin/env bash
# Shared ownership boundary for CLI fixtures (regression, Council #4728).
# Never inherit the caller's session or run destructive state commands in its cwd.
TEST_TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/spel-cli.XXXXXX") || exit 1
SESSION="agent-cli-$(date +%s)-$$"
export SPEL_SESSION="$SESSION"
TEMP_FILES=()

cleanup() {
  if [[ -n "${VIEW_SESSION:-}" ]]; then
    "$SPEL" --session "$VIEW_SESSION" close 2>/dev/null || true
  fi
  "$SPEL" --session "$SESSION" close 2>/dev/null || true
  for f in "${TEMP_FILES[@]}"; do
    rm -f -- "$f" 2>/dev/null
  done
  rm -rf -- "$TEST_TMP_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$TEST_TMP_DIR" || exit 1

preflight() {
  "$SPEL" --session "$SESSION" close 2>/dev/null || true
  "$SPEL" --session "$SESSION" open "${1:-https://example.com}" >/dev/null 2>&1
}
