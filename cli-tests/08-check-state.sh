#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Check State (3)"

OUT=$("$SPEL" --json is visible @e1 2>&1)
assert_jq "is visible @e1 → .data.visible" "$OUT" '.data.visible == true'

OUT=$("$SPEL" --json is enabled @e1 2>&1)
assert_jq "is enabled @e1 → .data.enabled" "$OUT" '.data.enabled == true'

OUT=$("$SPEL" --json is checked @e1 2>&1)
assert_jq "is checked @e1 → expected failure" "$OUT" '.success == false'

print_summary
