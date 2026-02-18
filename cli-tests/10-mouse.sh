#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Mouse Control (4)"

OUT=$("$SPEL" --json mouse move 100 200 2>&1)
assert_jq "mouse move → .data.moved.x == 100" "$OUT" '.data.moved.x == 100'
assert_jq "mouse move → .data.moved.y == 200" "$OUT" '.data.moved.y == 200'

OUT=$("$SPEL" --json mouse down 2>&1)
assert_jq_eq "mouse down → .data.mouse_down" "$OUT" '.data.mouse_down' 'left'

OUT=$("$SPEL" --json mouse up 2>&1)
assert_jq_eq "mouse up → .data.mouse_up" "$OUT" '.data.mouse_up' 'left'

OUT=$("$SPEL" --json mouse wheel 100 2>&1)
assert_jq "mouse wheel → .data.wheel.dy == 100" "$OUT" '.data.wheel.dy == 100'

print_summary
