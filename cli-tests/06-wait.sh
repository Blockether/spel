#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
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

print_summary
