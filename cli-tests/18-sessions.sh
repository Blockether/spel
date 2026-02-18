#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Sessions (3)"

OUT=$("$SPEL" --json session 2>&1)
assert_jq_eq "session → .data.session" "$OUT" '.data.session' 'default'

OUT=$("$SPEL" --json session list 2>&1)
assert_jq "session list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json --session testsession open https://example.com 2>&1)
assert_jq_eq "--session testsession → .data.url" "$OUT" '.data.url' 'https://example.com/'
"$SPEL" --session testsession close >/dev/null 2>&1

print_summary
