#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Screenshots & PDF (4)"

OUT=$("$SPEL" --json screenshot 2>&1)
assert_jq_gt "screenshot → .size > 0" "$OUT" '.size' 0

SHOT_PATH="/tmp/test-cli-shot.png"
TEMP_FILES+=("$SHOT_PATH")
OUT=$("$SPEL" --json screenshot "$SHOT_PATH" 2>&1)
assert_jq_contains "screenshot (named) → .path" "$OUT" '.path' 'test-cli-shot.png'

FULL_PATH="/tmp/test-cli-full.png"
TEMP_FILES+=("$FULL_PATH")
OUT=$("$SPEL" --json screenshot -f "$FULL_PATH" 2>&1)
assert_jq_gt "screenshot -f → .size > 0" "$OUT" '.size' 0

PDF_PATH="/tmp/test-cli-page.pdf"
TEMP_FILES+=("$PDF_PATH")
OUT=$("$SPEL" --json pdf "$PDF_PATH" 2>&1)
assert_jq_contains "pdf → .path" "$OUT" '.path' 'test-cli-page.pdf'

# open --screenshot (named path): file written, url/title correct, valid PNG
OPEN_SHOT_PATH="/tmp/test-cli-open-shot.png"
TEMP_FILES+=("$OPEN_SHOT_PATH")
OUT=$("$SPEL" --json open https://example.com --screenshot "$OPEN_SHOT_PATH" 2>&1)
assert_jq_contains "open --screenshot (named) → .screenshot" "$OUT" '.screenshot' 'test-cli-open-shot.png'
assert_jq_gt "open --screenshot (named) → .size > 0" "$OUT" '.size' 0
assert_exists "open --screenshot (named) → file exists on disk" "$OPEN_SHOT_PATH"
assert_jq_eq "open --screenshot (named) → .url" "$OUT" '.url' 'https://example.com/'
assert_jq_eq "open --screenshot (named) → .title" "$OUT" '.title' 'Example Domain'

# PNG magic bytes: 89 50 4e 47 = \x89PNG
PNG_MAGIC=$(xxd -l 4 -p "$OPEN_SHOT_PATH")
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$PNG_MAGIC" == "89504e47" ]]; then
  pass "open --screenshot (named) → file is valid PNG"
else
  fail "open --screenshot (named) → file is valid PNG" "Expected PNG magic 89504e47, got $PNG_MAGIC"
fi

# open --screenshot (auto path): temp file written and exists on disk
OUT=$("$SPEL" --json open https://example.com --screenshot 2>&1)
assert_jq_contains "open --screenshot (auto) → .screenshot contains spel-screenshot" "$OUT" '.screenshot' 'spel-screenshot'
assert_jq_gt "open --screenshot (auto) → .size > 0" "$OUT" '.size' 0

AUTO_SHOT_PATH=$(echo "$OUT" | jq -r '.screenshot' 2>/dev/null)
if [[ -n "$AUTO_SHOT_PATH" && "$AUTO_SHOT_PATH" != "null" ]]; then
  TEMP_FILES+=("$AUTO_SHOT_PATH")
  assert_exists "open --screenshot (auto) → file exists on disk" "$AUTO_SHOT_PATH"
fi

# reversed arg order: --screenshot <path> before URL
REV_SHOT_PATH="/tmp/test-cli-open-shot-rev.png"
TEMP_FILES+=("$REV_SHOT_PATH")
OUT=$("$SPEL" --json open --screenshot "$REV_SHOT_PATH" https://example.com 2>&1)
assert_jq_contains "open --screenshot (reversed) → .screenshot" "$OUT" '.screenshot' 'test-cli-open-shot-rev.png'
assert_jq_eq "open --screenshot (reversed) → .url" "$OUT" '.url' 'https://example.com/'
assert_exists "open --screenshot (reversed) → file exists on disk" "$REV_SHOT_PATH"

# goto alias with --screenshot
GOTO_SHOT_PATH="/tmp/test-cli-goto-shot.png"
TEMP_FILES+=("$GOTO_SHOT_PATH")
OUT=$("$SPEL" --json goto https://example.com --screenshot "$GOTO_SHOT_PATH" 2>&1)
assert_jq_contains "goto --screenshot → .screenshot" "$OUT" '.screenshot' 'test-cli-goto-shot.png'
assert_jq_gt "goto --screenshot → .size > 0" "$OUT" '.size' 0
assert_exists "goto --screenshot → file exists on disk" "$GOTO_SHOT_PATH"

# bare domain + --screenshot (URL normalization)
BARE_SHOT_PATH="/tmp/test-cli-bare-shot.png"
TEMP_FILES+=("$BARE_SHOT_PATH")
OUT=$("$SPEL" --json open example.com --screenshot "$BARE_SHOT_PATH" 2>&1)
assert_jq_eq "open bare domain --screenshot → .url" "$OUT" '.url' 'https://example.com/'
assert_jq_contains "open bare domain --screenshot → .screenshot" "$OUT" '.screenshot' 'test-cli-bare-shot.png'
assert_exists "open bare domain --screenshot → file exists on disk" "$BARE_SHOT_PATH"

print_summary
