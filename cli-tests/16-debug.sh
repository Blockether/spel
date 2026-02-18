#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Debug (9)"

OUT=$("$SPEL" --json trace start 2>&1)
assert_jq_eq "trace start → .data.trace" "$OUT" '.data.trace' 'started'

TRACE_PATH="/tmp/test-cli-trace.zip"
TEMP_FILES+=("$TRACE_PATH")
OUT=$("$SPEL" --json trace stop "$TRACE_PATH" 2>&1)
assert_jq_eq "trace stop → .data.trace" "$OUT" '.data.trace' 'stopped'

# Console is auto-captured — no start needed. Trigger a message then read it.
"$SPEL" eval "console.log('spel-test-msg')" >/dev/null 2>&1

OUT=$("$SPEL" --json console 2>&1)
assert_jq "console → success" "$OUT" '.success == true'
assert_jq "console → has messages" "$OUT" '(.data.messages | length) > 0'
assert_jq_contains "console → captured log" "$OUT" '.data.messages[-1].text' 'spel-test-msg'

OUT=$("$SPEL" --json console clear 2>&1)
assert_jq "console clear → success" "$OUT" '.success == true'

# Errors are auto-captured — no start needed. Trigger an error then read it.
"$SPEL" eval "setTimeout(function(){throw new Error('spel-test-err')},0)" >/dev/null 2>&1
"$SPEL" eval "1" >/dev/null 2>&1

OUT=$("$SPEL" --json errors 2>&1)
assert_jq "errors → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json errors clear 2>&1)
assert_jq "errors clear → success" "$OUT" '.success == true'

print_summary
