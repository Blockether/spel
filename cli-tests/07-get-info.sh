#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Get Info (8)"

OUT=$("$SPEL" --json get text @e1 2>&1)
assert_jq_eq "get text @e1 → .data.text" "$OUT" '.data.text' 'Example Domain'

OUT=$("$SPEL" --json get html @e1 2>&1)
assert_jq_eq "get html @e1 → .data.html" "$OUT" '.data.html' 'Example Domain'

OUT=$("$SPEL" --json get value @e1 2>&1)
assert_jq "get value @e1 → expected failure" "$OUT" '.success == false'

OUT=$("$SPEL" --json get attr @e3 href 2>&1)
assert_jq_contains "get attr @e3 href → .data.value has iana" "$OUT" '.data.value' 'iana.org'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "get url → .data.url" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json get title 2>&1)
assert_jq_eq "get title → .data.title" "$OUT" '.data.title' 'Example Domain'

OUT=$("$SPEL" --json get count div 2>&1)
assert_jq_gt "get count div → .data.count > 0" "$OUT" '.data.count' 0

OUT=$("$SPEL" --json get box @e1 2>&1)
assert_jq_gt "get box @e1 → .data.box.width > 0" "$OUT" '.data.box.width' 0

print_summary
