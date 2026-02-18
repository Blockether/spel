#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"

"$SPEL" close 2>/dev/null || true
pkill -9 -xf ".*spel daemon.*" 2>/dev/null || true
pkill -9 -x spel 2>/dev/null || true
rm -f /tmp/spel-*.sock /tmp/spel-*.pid 2>/dev/null || true

section "Interactive Mode (5)"

OUT=$("$SPEL" --json open https://example.com --interactive 2>&1)
assert_jq_eq "open --interactive → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_contains "open --interactive → snapshot" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json close 2>&1)
assert_jq "interactive close → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json open https://example.com 2>&1)
assert_jq_eq "headless reopen after interactive → .data.url" "$OUT" '.data.url' 'https://example.com/'
assert_jq_contains "headless reopen → snapshot" "$OUT" '.data.snapshot' '[@e'

"$SPEL" close 2>/dev/null || true

print_summary
