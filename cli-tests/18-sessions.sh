#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Sessions (3)"

OUT=$("$SPEL" --json session 2>&1)
assert_jq_eq "session → owned name" "$OUT" '.session' "$SESSION"

OUT=$("$SPEL" --json session list 2>&1)
assert_jq "session list → owned name" "$OUT" "[.sessions[].name] | index(\"$SESSION\") != null"

OUT=$("$SPEL" --json --session "${SESSION}-testsession" open https://example.com 2>&1)
assert_jq_eq "named session → .url" "$OUT" '.url' 'https://example.com/'
"$SPEL" --session "${SESSION}-testsession" close >/dev/null 2>&1

print_summary
