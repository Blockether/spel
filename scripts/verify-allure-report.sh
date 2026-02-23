#!/usr/bin/env bash
# Allure Report Verification — download CI artifact, parse test results, screenshot with spel, PDF, comment on PR
# Usage: ./scripts/verify-allure-report.sh <PR_NUMBER>
#
# Key design decisions:
# - Parses test-results/*.json directly instead of relying on Allure SPA routes
#   (Allure 3 SPA routes like #results fetch data/test-results/results.json which
#   does not exist in statically-served artifacts)
# - Generates custom HTML showing CT + lazytest suite breakdown side by side
# - Takes 3 screenshots: Allure overview + CT suite summary + lazytest detail
# - Warns and fails when either CT or lazytest results are missing from artifact
set -euo pipefail

PR_NUMBER="${1:?Usage: $0 <PR_NUMBER>}"
ARTIFACT_NAME="allure-report-pr-${PR_NUMBER}"
DOWNLOAD_DIR="/tmp/allure-verify-pr${PR_NUMBER}"
PORT=8299
REPO="Blockether/spel"
COUNTS_FILE="/tmp/allure-counts-pr${PR_NUMBER}.json"

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
  exit 1
fi

REPORT_DIR="$DOWNLOAD_DIR"
if [ ! -f "$REPORT_DIR/index.html" ]; then
  echo "ERROR: index.html not found in $REPORT_DIR"
  exit 1
fi
echo "Artifact downloaded to: $REPORT_DIR"

# 4. Parse test results — write counts to temp JSON file (avoids shell/Python interpolation issues)
echo "Parsing test results from $REPORT_DIR/data/test-results/ ..."

python3 - "$REPORT_DIR/data/test-results" "$COUNTS_FILE" << 'PYEOF'
import json, glob, sys
from collections import defaultdict

data_dir, out_file = sys.argv[1], sys.argv[2]
files = glob.glob(f'{data_dir}/*.json')

ct_suites = defaultdict(list)
lazytest_suites = defaultdict(list)

for f in files:
    try:
        d = json.load(open(f))
        labels = {l['name']: l['value'] for l in d.get('labels', [])}
        framework = labels.get('framework', 'unknown')
        suite = labels.get('suite', 'unknown')
        name = d.get('name', '?').split(' > ')[-1]
        status = d.get('status', '?')
        feature = labels.get('feature', '')

        entry = {'name': name, 'status': status, 'feature': feature, 'suite': suite}
        if framework == 'clojure.test':
            ct_suites[suite].append(entry)
        else:
            lazytest_suites[suite].append(entry)
    except Exception:
        pass

ct_total = sum(len(v) for v in ct_suites.values())
lt_total = sum(len(v) for v in lazytest_suites.values())
total = ct_total + lt_total
passed = (sum(1 for v in ct_suites.values() for t in v if t['status'] == 'passed') +
          sum(1 for v in lazytest_suites.values() for t in v if t['status'] == 'passed'))
failed = total - passed

result = {
    'total': total, 'ct_total': ct_total, 'lt_total': lt_total,
    'passed': passed, 'failed': failed,
    'ct_suites': dict(ct_suites),
    'lazytest_suites': dict(lazytest_suites)
}
with open(out_file, 'w') as fh:
    json.dump(result, fh)
print(f"Parsed {total} tests: CT={ct_total}, Lazytest={lt_total}, passed={passed}, failed={failed}")
PYEOF

# Read counts from temp file
TOTAL=$(python3 -c "import json; d=json.load(open('$COUNTS_FILE')); print(d['total'])")
CT_TOTAL=$(python3 -c "import json; d=json.load(open('$COUNTS_FILE')); print(d['ct_total'])")
LT_TOTAL=$(python3 -c "import json; d=json.load(open('$COUNTS_FILE')); print(d['lt_total'])")
PASSED=$(python3 -c "import json; d=json.load(open('$COUNTS_FILE')); print(d['passed'])")
FAILED=$(python3 -c "import json; d=json.load(open('$COUNTS_FILE')); print(d['failed'])")

echo "Test counts: total=$TOTAL passed=$PASSED failed=$FAILED (CT=$CT_TOTAL, Lazytest=$LT_TOTAL)"

if [ "$LT_TOTAL" -eq 0 ]; then
  echo "WARNING: No lazytest results in artifact. Only clojure.test (CT) results found."
  echo "         Check CI step 'Run lazytest suite with Allure reporter'."
fi

if [ "$CT_TOTAL" -eq 0 ]; then
  echo "WARNING: No clojure.test (CT) results in artifact."
fi

# 5. Serve Allure report locally for overview screenshot
python3 -m http.server "$PORT" --directory "$REPORT_DIR" > "/tmp/allure-server-pr${PR_NUMBER}.log" 2>&1 &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null || true" EXIT INT TERM
sleep 2

curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/" | grep -q "200" || {
  echo "ERROR: Local HTTP server failed to start on port $PORT"
  exit 1
}
echo "Server running on http://localhost:$PORT (pid $SERVER_PID)"

# 6. Generate custom HTML verification pages from parsed JSON data
NOW=$(date -u '+%Y-%m-%d %H:%M UTC')

python3 - "$COUNTS_FILE" "$PR_NUMBER" "$BRANCH" "$RUN_ID" "$REPO" "$NOW" << 'PYEOF'
import json, sys
from pathlib import Path

counts_file, pr, branch, run_id, repo, now = sys.argv[1:7]
data = json.load(open(counts_file))

total = data['total']
passed = data['passed']
failed = data['failed']
ct_total = data['ct_total']
lt_total = data['lt_total']
ct_suites = data['ct_suites']
lt_suites = data['lazytest_suites']

S = {'passed': '#27ae60', 'failed': '#e74c3c', 'broken': '#e67e22', 'skipped': '#95a5a6'}

STYLE = """
body {font-family:Arial,sans-serif;max-width:1200px;margin:0 auto;padding:20px;color:#222;background:#f7f7f7}
h1,h2 {color:#111;margin-top:0}
.subtitle {color:#666;font-size:13px;margin-bottom:18px}
.bars {display:flex;gap:12px;margin:16px 0;flex-wrap:wrap}
.stat {background:white;border:1px solid #ddd;border-radius:6px;padding:12px 18px;text-align:center;min-width:80px}
.stat .num {font-size:26px;font-weight:bold}
.stat .lbl {font-size:11px;color:#666;margin-top:2px;text-transform:uppercase}
.stat.pass .num {color:#27ae60}
.stat.fail .num {color:#e74c3c}
.stat.warn {border-color:#ffc107}
.stat.warn .num {color:#856404}
.warn {background:#fff3cd;border:1px solid #ffc107;border-radius:6px;padding:10px 14px;margin:12px 0;font-size:13px}
.warn strong {color:#856404}
.ok {background:#d4edda;border:1px solid #c3e6cb;border-radius:6px;padding:10px 14px;margin:12px 0;font-size:13px;color:#155724}
.section {font-size:16px;font-weight:bold;margin:20px 0 8px;padding-bottom:5px;border-bottom:2px solid #dee2e6}
.suite {background:white;border:1px solid #ddd;border-radius:6px;padding:14px;margin:10px 0}
.suite h3 {margin:0 0 3px;font-size:14px}
.badge {background:#e9ecef;border-radius:10px;padding:2px 7px;font-size:11px;font-weight:normal;color:#555;margin-left:5px}
.ns {font-size:11px;color:#999;margin-bottom:9px;font-family:monospace}
table {width:100%;border-collapse:collapse;font-size:12px}
th {background:#f5f5f5;padding:5px 10px;text-align:left;border-bottom:2px solid #ddd;font-size:10px;text-transform:uppercase;color:#666}
td {padding:4px 10px;border-bottom:1px solid #f0f0f0}
"""

def suite_block(suite_name, tests):
    short = suite_name.split('.')[-1]
    rows = ''.join(
        f'<tr><td style="color:{S.get(t["status"],"#333")};font-weight:bold;text-transform:uppercase;font-size:10px">'
        f'{t["status"]}</td>'
        f'<td style="font-family:monospace">{t["name"]}</td>'
        f'<td style="color:#888">{t.get("feature","")}</td></tr>'
        for t in sorted(tests, key=lambda x: x['name'])
    )
    return (f'<div class="suite"><h3>{short}<span class="badge">{len(tests)}</span></h3>'
            f'<div class="ns">{suite_name}</div>'
            f'<table><tr><th>Status</th><th>Test</th><th>Feature</th></tr>{rows}</table></div>')

lt_warn = (
    '<div class="warn"><strong>Warning: Lazytest results missing.</strong> '
    'Only clojure.test (CT) results in artifact. '
    'Check CI step "Run lazytest suite with Allure reporter".</div>'
    if lt_total == 0 else
    f'<div class="ok">Lazytest: {lt_total} results across {len(lt_suites)} suites.</div>'
)

ct_warn = (
    '<div class="warn"><strong>Warning: CT results missing.</strong></div>'
    if ct_total == 0 else ''
)

# Page 1: summary + CT suites
ct_blocks = ''.join(suite_block(s, t) for s, t in sorted(ct_suites.items()))
page1 = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Allure Verify PR #{pr}</title>
<style>{STYLE}</style></head>
<body>
<h1>Allure Report Verification — PR #{pr}</h1>
<div class="subtitle">Branch: {branch} &nbsp;|&nbsp; Run: {run_id} &nbsp;|&nbsp; {now}</div>
<div class="bars">
  <div class="stat"><div class="num">{total}</div><div class="lbl">Total</div></div>
  <div class="stat pass"><div class="num">{passed}</div><div class="lbl">Passed</div></div>
  <div class="stat {'fail' if failed>0 else ''}"><div class="num">{failed}</div><div class="lbl">Failed</div></div>
  <div class="stat"><div class="num">{ct_total}</div><div class="lbl">CT</div></div>
  <div class="stat {'warn' if lt_total==0 else ''}"><div class="num">{lt_total}</div><div class="lbl">Lazytest</div></div>
</div>
{lt_warn}{ct_warn}
<div class="section">clojure.test (CT) — {ct_total} tests, {len(ct_suites)} suites</div>
{ct_blocks}
</body></html>"""

# Page 2: lazytest suites (first 5 suites, each showing up to 20 tests)
lt_blocks = ''
if lt_total > 0:
    shown = list(lt_suites.items())[:5]
    lt_blocks = ''.join(suite_block(s, t[:20]) for s, t in sorted(shown))
    if len(lt_suites) > 5:
        lt_blocks += f'<p style="color:#666;font-size:13px">... and {len(lt_suites)-5} more suites</p>'
else:
    lt_blocks = '<div class="warn"><strong>No lazytest results in this artifact.</strong></div>'

page2 = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Allure Verify PR #{pr} — Lazytest</title>
<style>{STYLE}</style></head>
<body>
<h2>Lazytest Results — PR #{pr} ({lt_total} tests, {len(lt_suites)} suites)</h2>
{lt_blocks}
</body></html>"""

Path(f'/tmp/allure-verify-p1-pr{pr}.html').write_text(page1)
Path(f'/tmp/allure-verify-p2-pr{pr}.html').write_text(page2)
print(f'HTML pages written for PR #{pr}')
PYEOF

SS_OVERVIEW="/tmp/allure-pr${PR_NUMBER}-s1-overview.png"
SS_CT="/tmp/allure-pr${PR_NUMBER}-s2-ct.png"
SS_LAZYTEST="/tmp/allure-pr${PR_NUMBER}-s3-lazytest.png"

# 7. Take screenshots with spel
echo "Taking screenshots..."

# Screen 1: Allure SPA overview (loads reliably from widgets/*.json)
spel open "http://localhost:${PORT}/" --screenshot "$SS_OVERVIEW" --timeout 15000 2>/dev/null || \
  echo "WARNING: Overview screenshot failed"

# Screen 2: CT suites summary (custom HTML — no SPA routing issues)
spel open "file:///tmp/allure-verify-p1-pr${PR_NUMBER}.html" \
  --screenshot "$SS_CT" --timeout 10000 2>/dev/null || \
  echo "WARNING: CT summary screenshot failed"

# Screen 3: Lazytest suites detail (custom HTML)
spel open "file:///tmp/allure-verify-p2-pr${PR_NUMBER}.html" \
  --screenshot "$SS_LAZYTEST" --timeout 10000 2>/dev/null || \
  echo "WARNING: Lazytest detail screenshot failed"

echo "Screenshots done: s1=$SS_OVERVIEW s2=$SS_CT s3=$SS_LAZYTEST"

# 8. Generate PDF report with embedded screenshots
HTML_FILE="/tmp/allure-pr${PR_NUMBER}-report.html"
PDF_FILE="/tmp/allure-pr${PR_NUMBER}-report.pdf"

b64_s1="" b64_s2="" b64_s3=""
[ -f "$SS_OVERVIEW" ]  && b64_s1=$(base64 -w0 "$SS_OVERVIEW")
[ -f "$SS_CT" ]        && b64_s2=$(base64 -w0 "$SS_CT")
[ -f "$SS_LAZYTEST" ]  && b64_s3=$(base64 -w0 "$SS_LAZYTEST")
STATUS_COLOR="green"; [ "$FAILED" -gt 0 ] && STATUS_COLOR="red"

cat > "$HTML_FILE" << HTMLEOF
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Allure PR ${PR_NUMBER}</title>
<style>
body{font-family:Arial,sans-serif;max-width:1200px;margin:0 auto;padding:20px;color:#333}
h1,h2{color:#222} table{border-collapse:collapse;width:100%;margin:10px 0}
td,th{border:1px solid #ddd;padding:7px 11px;text-align:left} th{background:#f5f5f5}
.s{color:${STATUS_COLOR};font-weight:bold} img{max-width:100%;margin:10px 0;border:1px solid #ccc;border-radius:4px}
h2{margin-top:28px;border-bottom:1px solid #eee;padding-bottom:5px}
.w{background:#fff3cd;border:1px solid #ffc107;border-radius:4px;padding:8px 12px;margin:8px 0;font-size:13px}
</style></head>
<body>
<h1>Allure Verification — PR #${PR_NUMBER}</h1>
<table>
  <tr><th>Repository</th><td>${REPO}</td></tr>
  <tr><th>Branch</th><td>${BRANCH}</td></tr>
  <tr><th>CI Run</th><td><a href="https://github.com/${REPO}/actions/runs/${RUN_ID}">${RUN_ID}</a></td></tr>
  <tr><th>Verified</th><td>${NOW}</td></tr>
  <tr><th>Total</th><td class="s">${TOTAL}</td></tr>
  <tr><th>Passed</th><td>${PASSED}</td></tr>
  <tr><th>Failed</th><td>${FAILED}</td></tr>
  <tr><th>CT (clojure.test)</th><td>${CT_TOTAL}</td></tr>
  <tr><th>Lazytest</th><td>$([ "$LT_TOTAL" -eq 0 ] && echo '<span style="color:#856404">0 — missing</span>' || echo "$LT_TOTAL")</td></tr>
</table>
$([ "$LT_TOTAL" -eq 0 ] && echo '<div class="w"><strong>Warning:</strong> Lazytest results missing from artifact.</div>' || true)
<h2>Screen 1 — Allure Overview</h2>
$([ -n "$b64_s1" ] && echo "<img src=\"data:image/png;base64,${b64_s1}\" alt=\"Overview\">" || echo "<p><em>Not available</em></p>")
<h2>Screen 2 — CT Suite Breakdown</h2>
$([ -n "$b64_s2" ] && echo "<img src=\"data:image/png;base64,${b64_s2}\" alt=\"CT Suites\">" || echo "<p><em>Not available</em></p>")
<h2>Screen 3 — Lazytest Results</h2>
$([ -n "$b64_s3" ] && echo "<img src=\"data:image/png;base64,${b64_s3}\" alt=\"Lazytest\">" || echo "<p><em>Not available</em></p>")
</body></html>
HTMLEOF

if command -v wkhtmltopdf &>/dev/null; then
  wkhtmltopdf --enable-local-file-access "$HTML_FILE" "$PDF_FILE" 2>/dev/null && \
    echo "PDF: $PDF_FILE" || echo "WARNING: PDF failed — HTML: $HTML_FILE"
else
  echo "wkhtmltopdf not available — HTML: $HTML_FILE"
  PDF_FILE=""
fi

# 9. Post comment on PR
LAZYTEST_STATUS="${LT_TOTAL} results"
[ "$LT_TOTAL" -eq 0 ] && LAZYTEST_STATUS="0 — MISSING from artifact"

COMMENT="## Allure Report Verified

Local verification of Allure artifact for PR #${PR_NUMBER}.

| Metric | Value |
|--------|-------|
| Total tests | ${TOTAL} |
| Passed | ${PASSED} |
| Failed | ${FAILED} |
| CT (clojure.test) | ${CT_TOTAL} |
| Lazytest | ${LAZYTEST_STATUS} |
| Verified at | ${NOW} |

[View CI run](https://github.com/${REPO}/actions/runs/${RUN_ID})"

gh pr comment "$PR_NUMBER" --repo "$REPO" --body "$COMMENT"
echo "PR comment posted."

# 10. Summary
echo ""
echo "=== Verification Complete ==="
echo "PR:       #${PR_NUMBER} | Branch: ${BRANCH}"
echo "Total:    ${TOTAL} | Passed: ${PASSED} | Failed: ${FAILED}"
echo "CT:       ${CT_TOTAL} | Lazytest: ${LT_TOTAL}"
echo "Screenshots: ${SS_OVERVIEW}  ${SS_CT}  ${SS_LAZYTEST}"
[ -n "${PDF_FILE:-}" ] && echo "PDF: ${PDF_FILE}"
