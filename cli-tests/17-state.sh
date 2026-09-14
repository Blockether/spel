#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "State Management (9)"

OUT=$("$SPEL" --json state save 2>&1)
assert_jq_contains "state save → .path" "$OUT" '.path' "state-$SESSION"

STATE_PATH="$TEST_TMP_DIR/test-cli-state.json"
TEMP_FILES+=("$STATE_PATH")
OUT=$("$SPEL" --json state save "$STATE_PATH" 2>&1)
assert_jq_contains "state save path → .path" "$OUT" '.path' 'test-cli-state'

OUT=$("$SPEL" --json state list 2>&1)
assert_jq "state list → owned state" "$OUT" ".states | index(\"state-$SESSION.json\") != null"

OUT=$("$SPEL" --json state show "state-$SESSION.json" 2>&1)
assert_jq "state show → cookies and origins" "$OUT" '.state | has("cookies") and has("origins")'

OUT=$("$SPEL" --json state clear 2>&1)
assert_jq_eq "state clear → owned state" "$OUT" '.cleared' "state-$SESSION.json"

OUT=$("$SPEL" --json state load "$STATE_PATH" 2>&1)
assert_jq_eq "state load → loaded" "$OUT" '.state' 'loaded'
OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url after state load → .url" "$OUT" '.url' 'https://example.com/'

"$SPEL" state save >/dev/null 2>&1
OUT=$("$SPEL" --json state rename "state-$SESSION.json" state-renamed.json 2>&1)
assert_jq_eq "state rename → .renamed.to" "$OUT" '.renamed.to' 'state-renamed.json'
"$SPEL" state clear --all >/dev/null 2>&1

"$SPEL" state save >/dev/null 2>&1
"$SPEL" state save "$TEST_TMP_DIR/test-cli-extra.json" >/dev/null 2>&1
TEMP_FILES+=("$TEST_TMP_DIR/test-cli-extra.json")
OUT=$("$SPEL" --json state clear --all 2>&1)
assert_jq_gt "state clear --all → .cleared >= 1" "$OUT" '.cleared' 0

OUT=$("$SPEL" --json state clean --older-than 0 2>&1)
assert_jq "state clean --older-than → count" "$OUT" '.cleaned >= 0 and .older_than_days == 0'

OUT=$("$SPEL" --json state clean 2>&1)
assert_jq "state clean → count" "$OUT" '.cleaned >= 0 and .older_than_days == 30'

print_summary
