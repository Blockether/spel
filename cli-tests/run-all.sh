#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export SPEL="${1:-$SCRIPT_DIR/../target/spel}"

echo "spel CLI Test Suite (per-section)"
echo "Binary: $SPEL"
echo "Date:   $(date)"

TOTAL_PASS=0
TOTAL_FAIL=0
FAILED_SCRIPTS=()
OVERALL_START=$(date +%s)

for script in "$SCRIPT_DIR"/[0-9]*.sh; do
  name=$(basename "$script")
  echo ""
  echo "▶ $name"
  if timeout 120 bash "$script"; then
    TOTAL_PASS=$((TOTAL_PASS + 1))
  else
    TOTAL_FAIL=$((TOTAL_FAIL + 1))
    FAILED_SCRIPTS+=("$name")
  fi
done

OVERALL_END=$(date +%s)
OVERALL_ELAPSED=$((OVERALL_END - OVERALL_START))

echo ""
echo "════════════════════════════════════════"
echo "  Scripts passed: $TOTAL_PASS"
echo "  Scripts failed: $TOTAL_FAIL"
echo "  Duration:       ${OVERALL_ELAPSED}s"
if [[ ${#FAILED_SCRIPTS[@]} -gt 0 ]]; then
  echo "  Failed:"
  for f in "${FAILED_SCRIPTS[@]}"; do
    echo "    - $f"
  done
fi
echo "════════════════════════════════════════"
[[ $TOTAL_FAIL -eq 0 ]]
