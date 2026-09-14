#!/usr/bin/env bash
# Issue #135 reported StackOverflowError in PipeTransport.send during native open.
# The report has no deterministic fixture. This covers its option combination and
# repeated navigation, not a claimed RED/GREEN reproduction of the original error.
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
cleanup_navigation
trap - EXIT
print_summary
