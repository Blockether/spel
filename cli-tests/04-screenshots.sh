#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
preflight
section "Screenshots & PDF (4)"

OUT=$("$SPEL" --json screenshot 2>&1)
assert_jq_gt "screenshot → .data.size > 0" "$OUT" '.data.size' 0

SHOT_PATH="/tmp/test-cli-shot.png"
TEMP_FILES+=("$SHOT_PATH")
OUT=$("$SPEL" --json screenshot "$SHOT_PATH" 2>&1)
assert_jq_contains "screenshot (named) → .data.path" "$OUT" '.data.path' 'test-cli-shot.png'

FULL_PATH="/tmp/test-cli-full.png"
TEMP_FILES+=("$FULL_PATH")
OUT=$("$SPEL" --json screenshot -f "$FULL_PATH" 2>&1)
assert_jq_gt "screenshot -f → .data.size > 0" "$OUT" '.data.size' 0

PDF_PATH="/tmp/test-cli-page.pdf"
TEMP_FILES+=("$PDF_PATH")
OUT=$("$SPEL" --json pdf "$PDF_PATH" 2>&1)
assert_jq_contains "pdf → .data.path" "$OUT" '.data.path' 'test-cli-page.pdf'

# open --screenshot: screenshot saved to named path after navigation
OPEN_SHOT_PATH="/tmp/test-cli-open-shot.png"
TEMP_FILES+=("$OPEN_SHOT_PATH")
OUT=$("$SPEL" --json open https://example.com --screenshot "$OPEN_SHOT_PATH" 2>&1)
assert_jq_contains "open --screenshot (named) → .data.screenshot" "$OUT" '.data.screenshot' 'test-cli-open-shot.png'
assert_jq_gt "open --screenshot (named) → .data.size > 0" "$OUT" '.data.size' 0
assert_exists "open --screenshot (named) → file exists on disk" "$OPEN_SHOT_PATH"

# open --screenshot (no path): screenshot saved to auto-generated temp file
OUT=$("$SPEL" --json open https://example.com --screenshot 2>&1)
assert_jq_contains "open --screenshot (auto) → .data.screenshot contains spel-screenshot" "$OUT" '.data.screenshot' 'spel-screenshot'
assert_jq_gt "open --screenshot (auto) → .data.size > 0" "$OUT" '.data.size' 0

print_summary
