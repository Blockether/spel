#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Find (11)"

OUT=$("$SPEL" --json find role heading click 2>&1)
assert_jq_eq "find role heading click → .data.found" "$OUT" '.data.found' 'role'
assert_jq_eq "find role heading click → .data.action" "$OUT" '.data.action' 'click'

OUT=$("$SPEL" --json find text "Example Domain" click 2>&1)
assert_jq_eq "find text click → .data.found" "$OUT" '.data.found' 'text'

nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json find label "Username" type "testuser" 2>&1)
assert_jq_eq "find label type → .data.found" "$OUT" '.data.found' 'label'

OUT=$("$SPEL" --json find role button click --name Login 2>&1)
assert_jq_eq "find role button --name → .data.found" "$OUT" '.data.found' 'role'

nav "https://example.com"

OUT=$("$SPEL" --json find first p click 2>&1)
assert_jq_eq "find first p click → .data.found" "$OUT" '.data.found' 'first'

OUT=$("$SPEL" --json find last p click 2>&1)
assert_jq_eq "find last p click → .data.found" "$OUT" '.data.found' 'last'

OUT=$("$SPEL" --json find nth 0 p click 2>&1)
assert_jq_eq "find nth 0 p click → .data.found" "$OUT" '.data.found' 'nth'

"$SPEL" eval "document.body.innerHTML = '<input placeholder=\"Search here\" /><img alt=\"Logo\" src=\"#\" /><span title=\"Tooltip\">hover me</span><button data-testid=\"submit-btn\">Submit</button>'" >/dev/null 2>&1

OUT=$("$SPEL" --json find placeholder "Search here" click 2>&1)
assert_jq_eq "find placeholder → .data.found" "$OUT" '.data.found' 'placeholder'

OUT=$("$SPEL" --json find alt "Logo" click 2>&1)
assert_jq_eq "find alt → .data.found" "$OUT" '.data.found' 'alt'

OUT=$("$SPEL" --json find title "Tooltip" click 2>&1)
assert_jq_eq "find title → .data.found" "$OUT" '.data.found' 'title'

OUT=$("$SPEL" --json find testid "submit-btn" click 2>&1)
assert_jq_eq "find testid → .data.found" "$OUT" '.data.found' 'testid'

print_summary
