#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Browser Settings (7)"

OUT=$("$SPEL" --json set viewport 1280 720 2>&1)
assert_jq "set viewport → .data.viewport.width == 1280" "$OUT" '.data.viewport.width == 1280'
assert_jq "set viewport → .data.viewport.height == 720" "$OUT" '.data.viewport.height == 720'

OUT=$("$SPEL" --json set device "iphone 14" 2>&1)
assert_jq "set device → success" "$OUT" '.success == true'
"$SPEL" set viewport 1280 720 >/dev/null 2>&1

OUT=$("$SPEL" --json set geo 37.7 -122.4 2>&1)
assert_jq "set geo → .data.geolocation.latitude == 37.7" "$OUT" '.data.geolocation.latitude == 37.7'

OUT=$("$SPEL" --json set offline on 2>&1)
assert_jq "set offline on → .data.offline" "$OUT" '.data.offline == true'
"$SPEL" set offline off >/dev/null 2>&1

OUT=$("$SPEL" --json set headers '{"X-Test":"value"}' 2>&1)
assert_jq "set headers → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json set credentials testuser testpass 2>&1)
assert_jq "set credentials → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json set media dark 2>&1)
assert_jq_eq "set media dark → .data.media.colorScheme" "$OUT" '.data.media.colorScheme' 'dark'

print_summary
