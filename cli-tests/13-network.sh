#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Network (12)"

OUT=$("$SPEL" --json network route "**/*.svg" 2>&1)
assert_jq_eq "network route *.svg → .data.route_added" "$OUT" '.data.route_added' '**/*.svg'

OUT=$("$SPEL" --json network route "**/*.woff2" --abort 2>&1)
assert_jq_eq "network route --abort → .data.route_added" "$OUT" '.data.route_added' '**/*.woff2'

OUT=$("$SPEL" --json network route "**/api/health" --body '{"status":"ok"}' 2>&1)
assert_jq_eq "network route --body → .data.route_added" "$OUT" '.data.route_added' '**/api/health'

OUT=$("$SPEL" --json network unroute "**/*.svg" 2>&1)
assert_jq "network unroute *.svg → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network unroute 2>&1)
assert_jq "network unroute all → .data.all_routes_removed" "$OUT" '.data.all_routes_removed == true'

OUT=$("$SPEL" --json network requests 2>&1)
assert_jq "network requests → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --filter "example" 2>&1)
assert_jq "network requests --filter → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --type document 2>&1)
assert_jq "network requests --type → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --method GET 2>&1)
assert_jq "network requests --method → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --status 2 2>&1)
assert_jq "network requests --status → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network requests --type document --status 2 2>&1)
assert_jq "network requests (combined) → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json network clear 2>&1)
assert_jq "network clear → success" "$OUT" '.success == true'

print_summary
