#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Global Flags (2)"

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "--json flag → .data.url" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json --session flagtest open https://example.com 2>&1)
assert_jq_eq "--session flag → .data.url" "$OUT" '.data.url' 'https://example.com/'
"$SPEL" --session flagtest close >/dev/null 2>&1

print_summary
