#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "JavaScript (2)"

OUT=$("$SPEL" --json eval "document.title" 2>&1)
assert_jq_eq "eval document.title → .data.result" "$OUT" '.data.result' 'Example Domain'

OUT=$("$SPEL" --json eval "document.title" -b 2>&1)
assert_jq_eq "eval -b → .data.result (base64)" "$OUT" '.data.result' 'RXhhbXBsZSBEb21haW4='

print_summary
