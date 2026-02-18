#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Navigation (7)"

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "open → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_eq "open → .data.title" "$OUT" '.data.title' 'Example Domain'
assert_jq_contains "open → .data.snapshot has refs" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json open example.com 2>&1)
assert_jq_eq "open bare domain → .data.url" "$OUT" '.data.url' 'https://example.com/'

# Navigate to a different URL to create real browser history entries
"$SPEL" open https://the-internet.herokuapp.com/ >/dev/null 2>&1

OUT=$("$SPEL" --json back 2>&1)
assert_jq "back → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json forward 2>&1)
assert_jq "forward → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json reload 2>&1)
assert_jq_contains "reload → snapshot has refs" "$OUT" '.data.snapshot' '[@e'

print_summary
