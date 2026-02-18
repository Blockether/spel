#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
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

OUT=$("$SPEL" --json state load "$STATE_PATH" 2>&1)
assert_jq "state load → success" "$OUT" '.success == true'
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url after state load → .data.url" "$OUT" '.data.url' 'https://example.com/'

"$SPEL" state save >/dev/null 2>&1
OUT=$("$SPEL" --json state rename state-default.json state-renamed.json 2>&1)
assert_jq_eq "state rename → .data.renamed.to" "$OUT" '.data.renamed.to' 'state-renamed.json'
"$SPEL" state clear --all >/dev/null 2>&1

"$SPEL" state save >/dev/null 2>&1
"$SPEL" state save /tmp/test-cli-extra.json >/dev/null 2>&1
TEMP_FILES+=("/tmp/test-cli-extra.json")
OUT=$("$SPEL" --json state clear --all 2>&1)
assert_jq_gt "state clear --all → .data.cleared >= 1" "$OUT" '.data.cleared' 0

OUT=$("$SPEL" --json state clean --older-than 0 2>&1)
assert_jq "state clean --older-than → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json state clean 2>&1)
assert_jq "state clean → success" "$OUT" '.success == true'

print_summary
