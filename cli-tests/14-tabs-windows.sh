#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Tabs & Windows (12)"

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json tab new 2>&1)
assert_jq_eq "tab new → .data.tab" "$OUT" '.data.tab' 'new'

OUT=$("$SPEL" --json tab 2>&1)
assert_jq "tab list after new → 2 tabs" "$OUT" '.success == true'

OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 switch → success" "$OUT" '.success == true'

"$SPEL" tab 1 >/dev/null 2>&1
OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (2nd tab) → .data.closed" "$OUT" '.data.closed == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after closing 2nd tab → back on example.com" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json tab new https://the-internet.herokuapp.com/login 2>&1)
assert_jq_eq "tab new url → .data.tab" "$OUT" '.data.tab' 'new'

OUT=$("$SPEL" --json tab 0 2>&1)
assert_jq "tab 0 (switch to original) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "tab 0 → url is example.com" "$OUT" '.data.url' 'https://example.com/'

OUT=$("$SPEL" --json tab 1 2>&1)
assert_jq "tab 1 (switch to 2nd) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_contains "tab 1 → url is the-internet" "$OUT" '.data.url' 'the-internet.herokuapp.com'

OUT=$("$SPEL" --json tab close 2>&1)
assert_jq "tab close (from 2nd tab) → .data.closed" "$OUT" '.data.closed == true'

OUT=$("$SPEL" --json get url 2>&1)
assert_jq_eq "after close → landed on tab 0" "$OUT" '.data.url' 'https://example.com/'

print_summary
