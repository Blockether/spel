#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Cookies & Storage (11)"

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq "cookies list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json cookies set testcookie testvalue 2>&1)
assert_jq_eq "cookies set → .data.cookie_set.name" "$OUT" '.data.cookie_set.name' 'testcookie'

OUT=$("$SPEL" --json cookies 2>&1)
assert_jq_contains "cookies after set → has testcookie" "$OUT" '.data.cookies' 'testcookie'

OUT=$("$SPEL" --json cookies clear 2>&1)
assert_jq "cookies clear → .data.cookies_cleared" "$OUT" '.data.cookies_cleared == true'

OUT=$("$SPEL" --json storage local 2>&1)
assert_jq_eq "storage local → .data.storage" "$OUT" '.data.storage' '[]'

OUT=$("$SPEL" --json storage local set testkey testval 2>&1)
assert_jq "storage local set → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage local clear 2>&1)
assert_jq_eq "storage local clear → .data.storage_cleared" "$OUT" '.data.storage_cleared' 'local'

OUT=$("$SPEL" --json storage session 2>&1)
assert_jq_eq "storage session → .data.storage" "$OUT" '.data.storage' '[]'

OUT=$("$SPEL" --json storage session set sesskey sessval 2>&1)
assert_jq "storage session set → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage session sesskey 2>&1)
assert_jq "storage session get-key → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json storage session clear 2>&1)
assert_jq_eq "storage session clear → .data.storage_cleared" "$OUT" '.data.storage_cleared' 'session'

print_summary
