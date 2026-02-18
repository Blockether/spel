#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Element Interactions (19)"

OUT=$("$SPEL" --json click @e1 2>&1)
assert_jq "click @e1 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json dblclick @e1 2>&1)
assert_jq "dblclick @e1 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json hover @e1 2>&1)
assert_jq_eq "hover @e1 → .data.hovered" "$OUT" '.data.hovered' '@e1'

OUT=$("$SPEL" --json focus @e1 2>&1)
assert_jq_eq "focus @e1 → .data.focused" "$OUT" '.data.focused' '@e1'

OUT=$("$SPEL" --json press Enter 2>&1)
assert_jq_eq "press Enter → .data.pressed" "$OUT" '.data.pressed' 'Enter'

OUT=$("$SPEL" --json press @e1 Tab 2>&1)
assert_jq_eq "press @e1 Tab → .data.pressed" "$OUT" '.data.pressed' 'Tab'

OUT=$("$SPEL" --json keydown Shift 2>&1)
assert_jq_eq "keydown Shift → .data.keydown" "$OUT" '.data.keydown' 'Shift'

OUT=$("$SPEL" --json keyup Shift 2>&1)
assert_jq_eq "keyup Shift → .data.keyup" "$OUT" '.data.keyup' 'Shift'

OUT=$("$SPEL" --json scroll down 500 2>&1)
assert_jq "scroll down 500 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json scrollintoview @e2 2>&1)
assert_jq "scrollintoview @e2 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json drag @e1 @e2 2>&1)
assert_jq "drag @e1 @e2 → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json highlight @e1 2>&1)
assert_jq_eq "highlight @e1 → .data.highlighted" "$OUT" '.data.highlighted' '@e1'

nav "https://the-internet.herokuapp.com/login"

OUT=$("$SPEL" --json fill "input#username" "tomsmith" 2>&1)
assert_jq_eq "fill → .data.filled" "$OUT" '.data.filled' 'input#username'

OUT=$("$SPEL" --json type "input#password" "SuperSecretPassword!" 2>&1)
assert_jq "type → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json clear "input#username" 2>&1)
assert_jq "clear → success" "$OUT" '.success == true'

nav "https://the-internet.herokuapp.com/checkboxes"

OUT=$("$SPEL" --json check "input[type=checkbox]:first-of-type" 2>&1)
assert_jq "check checkbox → success" "$OUT" '.success == true'

OUT=$("$SPEL" --json uncheck "input[type=checkbox]:last-of-type" 2>&1)
assert_jq "uncheck checkbox → success" "$OUT" '.success == true'

nav "https://the-internet.herokuapp.com/dropdown"

OUT=$("$SPEL" --json select "select#dropdown" "Option 1" 2>&1)
assert_jq "select dropdown → success" "$OUT" '.success == true'

nav "https://the-internet.herokuapp.com/upload"

echo "test upload content" > /tmp/test-upload.txt
TEMP_FILES+=(/tmp/test-upload.txt)
OUT=$("$SPEL" --json upload "input#file-upload" /tmp/test-upload.txt 2>&1)
assert_jq "upload file → success" "$OUT" '.success == true'

print_summary
