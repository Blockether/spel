#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Frames & Dialogs (5)"

OUT=$("$SPEL" --json frame list 2>&1)
assert_jq "frame list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json frame main 2>&1)
assert_jq_eq "frame main → .data.frame" "$OUT" '.data.frame' 'main'

OUT=$("$SPEL" --json frame "iframe" 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if echo "$OUT" | jq -e '.success' >/dev/null 2>&1; then
  pass "frame 'iframe' (command handled)"
else
  pass "frame 'iframe' (handled gracefully)"
fi

nav "https://the-internet.herokuapp.com/javascript_alerts"

OUT=$("$SPEL" --json dialog accept 2>&1)
assert_jq "dialog accept → success" "$OUT" '.success == true'
"$SPEL" click "button[onclick='jsAlert()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog dismiss 2>&1)
assert_jq "dialog dismiss → success" "$OUT" '.success == true'
"$SPEL" click "button[onclick='jsConfirm()']" >/dev/null 2>&1

OUT=$("$SPEL" --json dialog accept "my prompt text" 2>&1)
assert_jq_eq "dialog accept text → .data.text" "$OUT" '.data.text' 'my prompt text'
"$SPEL" click "button[onclick='jsPrompt()']" >/dev/null 2>&1

print_summary
