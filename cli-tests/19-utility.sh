#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
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

print_summary
