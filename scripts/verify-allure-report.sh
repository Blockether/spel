#!/usr/bin/env bash
# Allure Report Verification — download CI artifact, serve locally, screenshot with spel, generate PDF, comment on PR
# Usage: ./scripts/verify-allure-report.sh <PR_NUMBER>
set -euo pipefail

PR_NUMBER="${1:?Usage: $0 <PR_NUMBER>}"
ARTIFACT_NAME="allure-report-pr-${PR_NUMBER}"
DOWNLOAD_DIR="/tmp/allure-verify-pr${PR_NUMBER}"
PORT=8299
REPO="Blockether/spel"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Allure Report Verification: PR #${PR_NUMBER} ==="

# 1. Find PR branch
BRANCH=$(gh pr view "$PR_NUMBER" --repo "$REPO" --json headRefName --jq '.headRefName')
echo "Branch: $BRANCH"

# 2. Find latest successful Allure Report CI run for this branch
RUN_ID=$(gh run list --repo "$REPO" --workflow allure.yml \
  --branch "$BRANCH" --limit 10 \
  --json databaseId,conclusion,status \
  --jq 'first(.[] | select(.conclusion == "success")) | .databaseId' 2>/dev/null || echo "")

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
  echo "ERROR: No successful Allure Report CI run found for branch '$BRANCH'"
  echo "Check: https://github.com/${REPO}/actions?query=branch%3A${BRANCH}"
  exit 1
fi

echo "CI Run ID: $RUN_ID"

# 3. Download artifact
echo "Downloading artifact '$ARTIFACT_NAME'..."
rm -rf "$DOWNLOAD_DIR"
mkdir -p "$DOWNLOAD_DIR"
if ! gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT_NAME" --dir "$DOWNLOAD_DIR" 2>&1; then
  echo "ERROR: Artifact '$ARTIFACT_NAME' not found in run $RUN_ID"
  echo "Artifact may still be uploading — wait for CI to complete"
  exit 1
fi

REPORT_DIR="$DOWNLOAD_DIR"
if [ ! -f "$REPORT_DIR/index.html" ]; then
  echo "ERROR: index.html not found in $REPORT_DIR — download may have failed"
  exit 1
fi

echo "Artifact downloaded to: $REPORT_DIR"

# 4. Serve locally
pkill -f "python3.*http.server $PORT" 2>/dev/null || true
sleep 1
nohup python3 -m http.server "$PORT" --directory "$REPORT_DIR" > /tmp/allure-server-pr${PR_NUMBER}.log 2>&1 &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null || true; exit" EXIT INT TERM
sleep 3

# Verify server is up
if ! curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/" | grep -q "200"; then
  echo "ERROR: Local server failed to start on port $PORT"
  exit 1
fi
echo "Server running on http://localhost:$PORT (pid $SERVER_PID)"

# 5. Screenshot with spel
REPORT_URL="http://localhost:${PORT}/"
SS_OVERVIEW="/tmp/allure-pr${PR_NUMBER}-overview.png"
SS_RESULTS="/tmp/allure-pr${PR_NUMBER}-results.png"
SS_AWESOME="/tmp/allure-pr${PR_NUMBER}-awesome.png"

echo "Taking screenshots..."
spel open "$REPORT_URL" --device :desktop-1920 --screenshot "$SS_OVERVIEW" --timeout 12000 2>/dev/null || true
sleep 2
spel open "${REPORT_URL}#results" --device :desktop-1920 --screenshot "$SS_RESULTS" --timeout 12000 2>/dev/null || true
sleep 2
spel open "${REPORT_URL}#awesome" --device :desktop-1920 --screenshot "$SS_AWESOME" --timeout 12000 2>/dev/null || true

# 6. Count test results from artifact data
PASSED=0
FAILED=0
BROKEN=0
if [ -d "$REPORT_DIR/data/test-results" ]; then
  PASSED=$(python3 -c "
import json, glob, sys
files = glob.glob('$REPORT_DIR/data/test-results/*.json')
print(sum(1 for f in files if json.load(open(f)).get('status') == 'passed'))
" 2>/dev/null || echo 0)
  FAILED=$(python3 -c "
import json, glob
files = glob.glob('$REPORT_DIR/data/test-results/*.json')
print(sum(1 for f in files if json.load(open(f)).get('status') == 'failed'))
" 2>/dev/null || echo 0)
  BROKEN=$(python3 -c "
import json, glob
files = glob.glob('$REPORT_DIR/data/test-results/*.json')
print(sum(1 for f in files if json.load(open(f)).get('status') == 'broken'))
" 2>/dev/null || echo 0)
fi
TOTAL=$((PASSED + FAILED + BROKEN))

echo "Test counts: passed=$PASSED failed=$FAILED broken=$BROKEN total=$TOTAL"

# 7. Generate HTML report
HTML_FILE="/tmp/allure-pr${PR_NUMBER}-report.html"
PDF_FILE="/tmp/allure-pr${PR_NUMBER}-report.pdf"

# Embed screenshots as base64
b64_overview=""
b64_results=""
b64_awesome=""
[ -f "$SS_OVERVIEW" ] && b64_overview=$(base64 -w0 "$SS_OVERVIEW")
[ -f "$SS_RESULTS"  ] && b64_results=$(base64 -w0 "$SS_RESULTS")
[ -f "$SS_AWESOME"  ] && b64_awesome=$(base64 -w0 "$SS_AWESOME")

STATUS_COLOR="green"
[ "$FAILED" -gt 0 ] || [ "$BROKEN" -gt 0 ] && STATUS_COLOR="red"

cat > "$HTML_FILE" <<HTMLEOF
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Allure Report Verification — PR #${PR_NUMBER}</title>
  <style>
    body { font-family: Arial, sans-serif; max-width: 1200px; margin: 0 auto; padding: 20px; }
    h1 { color: #333; }
    table { border-collapse: collapse; width: 100%; margin: 20px 0; }
    td, th { border: 1px solid #ddd; padding: 8px 12px; }
    th { background: #f5f5f5; }
    .status { color: ${STATUS_COLOR}; font-weight: bold; }
    img { max-width: 100%; margin: 20px 0; border: 1px solid #ddd; }
    h2 { margin-top: 40px; color: #555; }
  </style>
</head>
<body>
  <h1>Allure Report Verification — PR #${PR_NUMBER}</h1>
  <table>
    <tr><th>Repository</th><td>${REPO}</td></tr>
    <tr><th>PR</th><td>#${PR_NUMBER} (branch: ${BRANCH})</td></tr>
    <tr><th>CI Run</th><td><a href="https://github.com/${REPO}/actions/runs/${RUN_ID}">${RUN_ID}</a></td></tr>
    <tr><th>Verified at</th><td>$(date -u '+%Y-%m-%d %H:%M UTC')</td></tr>
    <tr><th>Tests passed</th><td class="status">${PASSED}</td></tr>
    <tr><th>Tests failed</th><td>${FAILED}</td></tr>
    <tr><th>Tests broken</th><td>${BROKEN}</td></tr>
    <tr><th>Total</th><td>${TOTAL}</td></tr>
  </table>

  <h2>Overview</h2>
  $([ -n "$b64_overview" ] && echo "<img src=\"data:image/png;base64,${b64_overview}\" alt=\"Overview\">" || echo "<p>Screenshot not available</p>")

  <h2>Results</h2>
  $([ -n "$b64_results" ] && echo "<img src=\"data:image/png;base64,${b64_results}\" alt=\"Results\">" || echo "<p>Screenshot not available</p>")

  <h2>Awesome View</h2>
  $([ -n "$b64_awesome" ] && echo "<img src=\"data:image/png;base64,${b64_awesome}\" alt=\"Awesome\">" || echo "<p>Screenshot not available</p>")
</body>
</html>
HTMLEOF

echo "HTML report: $HTML_FILE"

# 8. Convert to PDF
if command -v wkhtmltopdf &>/dev/null; then
  wkhtmltopdf --enable-local-file-access "$HTML_FILE" "$PDF_FILE" 2>/dev/null && echo "PDF: $PDF_FILE" || echo "WARNING: PDF generation failed — HTML report available at $HTML_FILE"
else
  echo "WARNING: wkhtmltopdf not installed — HTML report available at $HTML_FILE"
  PDF_FILE="$HTML_FILE"
fi

# 9. Comment on PR
BADGE="✅"
[ "$FAILED" -gt 0 ] || [ "$BROKEN" -gt 0 ] && BADGE="❌"

COMMENT="${BADGE} **Allure Report Verified** — PR #${PR_NUMBER}

| Metric | Value |
|--------|-------|
| Tests passed | ${PASSED} |
| Tests failed | ${FAILED} |
| Tests broken | ${BROKEN} |
| Total | ${TOTAL} |
| Verified at | $(date -u '+%Y-%m-%d %H:%M UTC') |

Screenshots taken: overview · results · test details
[Download CI artifact](https://github.com/${REPO}/actions/runs/${RUN_ID}) (retention: 7 days)

_Local verification via \`./scripts/verify-allure-report.sh ${PR_NUMBER}\`_"

gh pr comment "$PR_NUMBER" --repo "$REPO" --body "$COMMENT"
echo "PR comment posted."

# 10. Summary
echo ""
echo "=== Verification Complete ==="
echo "PR:       #${PR_NUMBER} (${BRANCH})"
echo "Passed:   ${PASSED} | Failed: ${FAILED} | Broken: ${BROKEN}"
echo "Overview: $SS_OVERVIEW"
echo "PDF:      ${PDF_FILE}"
echo ""
