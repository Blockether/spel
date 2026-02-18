#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Snapshot (5)"

OUT=$("$SPEL" --json snapshot 2>&1)
assert_jq_contains "snapshot → has refs" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i 2>&1)
assert_jq_contains "snapshot -i → has refs" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i -c -d 5 2>&1)
assert_jq_contains "snapshot -i -c -d 5 → has refs" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -i -C 2>&1)
assert_jq_contains "snapshot -i -C → has refs" "$OUT" '.data.snapshot' '[@e'

OUT=$("$SPEL" --json snapshot -s "body" 2>&1)
assert_jq_contains "snapshot -s body → has refs" "$OUT" '.data.snapshot' '[@e'

print_summary
