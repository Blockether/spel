#!/usr/bin/env bash
# Issue #135 reported StackOverflowError in PipeTransport.send during native open.
# The first sections cover its option combination and repeated navigation. The
# last section reproduces the error: a request burst under a route.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export SPEL_SESSION_IDLE_TIMEOUT=0
# shellcheck source=helpers.sh
source "$SCRIPT_DIR/helpers.sh"
if [[ ! -x "$SPEL" ]]; then
  error "native binary is missing or not executable: $SPEL"
  print_summary
fi
SPEL="$(cd "$(dirname "$SPEL")" && pwd)/$(basename "$SPEL")"

if ! command -v timeout >/dev/null 2>&1; then
  timeout() { local duration="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$duration" "$@"; }
fi

WORK_DIR=$(mktemp -d)
SERVER_PID=""
cleanup_navigation() {
  cleanup
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}
trap 'cleanup_navigation' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Reuse the JVM suite's HTTP fixtures; only the server runs in the JVM. Every
# browser operation below crosses the native CLI, daemon socket and Chromium.
(cd "$SCRIPT_DIR/.." && exec clojure -M:dev -e '
  (require (quote [com.blockether.spel.test-server :as ts]))
  (let [server (ts/start-test-server)]
    (println (ts/server-port server))
    (flush)
    @(promise))') >"$WORK_DIR/server.log" 2>&1 &
SERVER_PID=$!
PORT=""
for ((i=0; i<120; i++)); do
  PORT=$(grep -E '^[0-9]+$' "$WORK_DIR/server.log" | head -1)
  [[ -n "$PORT" ]] && break
  kill -0 "$SERVER_PID" 2>/dev/null || break
  sleep 0.25
done
if [[ -z "$PORT" ]]; then
  cat "$WORK_DIR/server.log"
  error "navigation fixture did not start"
  print_summary
fi
BASE="http://localhost:$PORT"

run_spel() {
  local label="$1" expected="$2"
  shift 2
  OUT=$(timeout 30 "$SPEL" --session "$SESSION" "$@" 2>&1)
  local status=$?
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  if [[ "$status" -eq "$expected" ]]; then
    pass "$label: exit $expected"
  else
    fail "$label: exit $expected" "exit=$status: $OUT"
  fi
}

section "Native navigation stability (issue #135)"
"$SPEL" version
for cycle in 1 2; do
  PID=""
  for path in test-page second-page iframe-page redirect-page \
              navigation-assets navigation-assets navigation-fetches navigation-fetches; do
    case "$path" in
      test-page|redirect-page) title="Test Page"; expected_url="$BASE/test-page" ;;
      second-page) title="Second Page"; expected_url="$BASE/$path" ;;
      iframe-page) title="IFrame Page"; expected_url="$BASE/$path" ;;
      navigation-assets) title="Navigation assets"; expected_url="$BASE/$path" ;;
      navigation-fetches) title="Navigation fetches"; expected_url="$BASE/$path" ;;
    esac
    label="cycle $cycle $path"
    run_spel "$label open" 0 --browser chromium --allowed-domains localhost \
      --content-boundaries open "$BASE/$path"
    assert_contains "$label boundary and URL" "$OUT" "<untrusted-content>
$expected_url
</untrusted-content>"
    if [[ "$path" == navigation-* ]]; then
      run_spel "$label resources complete" 0 --json wait --fn 'window.loadedAssets === 400'
      assert_jq "$label wait completed" "$OUT" '.function_completed == true'
      run_spel "$label resource DOM" 0 --json eval-js 'window.loadedAssets'
      assert_jq "$label all 400 resources loaded" "$OUT" '.result == 400'
    fi
    run_spel "$label title" 0 --json get title
    assert_jq_eq "$label rendered title" "$OUT" '.title' "$title"
    run_spel "$label health" 0 --json health
    assert_jq "$label healthy browser and handlers" "$OUT" \
      '.status == "ok" and .handler_errors == [] and .browser.connected == true
       and .browser.page_open == true and .browser.page_crashed == false'
    if [[ -z "$PID" ]]; then
      PID=$(echo "$OUT" | jq -r '.pid')
      assert_jq "$label daemon PID exists" "$OUT" '.pid | type == "string" and length > 0'
    fi
    assert_jq_eq "$label same daemon, no silent restart" "$OUT" '.pid' "$PID"
  done

  # A normal policy rejection must return an ordinary navigation error.
  # Both hostnames resolve to this test server; no external target is contacted.
  run_spel "cycle $cycle denied host" 1 --browser chromium --allowed-domains localhost \
    --content-boundaries --json open "http://127.0.0.1:$PORT/test-page"
  assert_jq "policy rejection is not a stack overflow" "$OUT" \
    '.error | contains("ERR_BLOCKED_BY_CLIENT") and (contains("StackOverflowError") | not)'
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    timeout 15 "$SPEL" --session "$SESSION" logs -n 100
  fi
  run_spel "cycle $cycle close owned daemon" 0 close
  run_spel "cycle $cycle daemon stopped" 1 --json health
  assert_jq_eq "next cycle starts cold" "$OUT" '.status' 'down'
done

section "Request burst under a route (issue #135)"
# Playwright Java nests the route events that arrive while it handles one, one
# level for each request that a route pattern matches. A page that made 5000
# requests ended `open`, or the command after it, with StackOverflowError: with
# --allowed-domains (its route matched every URL) and with a `network route` mock.
# The reporter ran spel in a project directory. There, spel 0.9.55 gave the
# source directories to Playwright, which then recorded a stack trace for each
# call. The client became slow and the nesting deep, so start the daemon in
# such a directory.
mkdir -p src test
BURST=5000
BURST_RESULT='window.loadedAssets + " loaded, " + window.failedAssets + " failed, denied " + window.deniedFetch'
# Open a small page first. The first navigation of a new browser is slow on a
# busy machine, and the burst must time the route, not the browser start.
run_spel "burst allowlist start" 0 --browser chromium --allowed-domains localhost \
  --content-boundaries --timeout 25000 --json open "$BASE/test-page"
run_spel "burst allowlist open" 0 --json open "$BASE/navigation-burst?n=$BURST"
run_spel "burst allowlist complete" 0 --json wait --fn 'window.deniedFetch !== undefined'
run_spel "burst allowlist result" 0 --json eval-js "$BURST_RESULT"
assert_jq_eq "allowed requests loaded, denied sub-resource blocked" "$OUT" '.result' \
  "$BURST loaded, 0 failed, denied blocked"
run_spel "burst allowlist health" 0 --json health
assert_jq "burst allowlist left a healthy daemon" "$OUT" '.status == "ok" and .handler_errors == []'

# The allowlist is on the context: a tab that spel opens later is under it too.
DENIED_FETCH="fetch('http://127.0.0.1:$PORT/health',{mode:'no-cors'}).then(()=>'passed',()=>'blocked')"
run_spel "allowlist new tab" 0 --json tab new "$BASE/test-page"
run_spel "allowlist new tab fetch" 0 --json eval-js "$DENIED_FETCH"
assert_jq_eq "new tab blocks a denied sub-resource" "$OUT" '.result' 'blocked'
run_spel "allowlist new tab denied host" 1 --json open "http://127.0.0.1:$PORT/test-page"
assert_jq "new tab blocks a denied navigation" "$OUT" '.error | contains("ERR_BLOCKED_BY_CLIENT")'
run_spel "burst allowlist close" 0 close

run_spel "burst mock start" 0 --browser chromium --timeout 25000 --json open "$BASE/test-page"
run_spel "burst mock route" 0 --json network route '**/health?i=*' --body '{"status":"ok"}'
run_spel "burst mock open" 0 --json open "$BASE/navigation-burst?n=$BURST"
run_spel "burst mock complete" 0 --json wait --fn 'window.deniedFetch !== undefined'
run_spel "burst mock result" 0 --json eval-js "$BURST_RESULT"
assert_jq_eq "every mocked request answered" "$OUT" '.result' \
  "$BURST loaded, 0 failed, denied passed"
run_spel "burst mock health" 0 --json health
assert_jq "burst mock left a healthy daemon" "$OUT" '.status == "ok" and .handler_errors == []'
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  timeout 15 "$SPEL" --session "$SESSION" logs -n 100
fi
run_spel "burst mock close" 0 close

cleanup_navigation
trap - EXIT
print_summary
