#!/usr/bin/env bash
# Preview allure-index.html with mock data.
# Usage: ./dev/preview-landing.sh [--port PORT] [--no-open]
#
# Copies the landing page + mock fixtures to a temp dir and serves it.
# Ctrl+C to stop.

set -euo pipefail

PORT="${1:-8765}"
OPEN=true
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PREVIEW_DIR="$(mktemp -d)"

# Parse args
for arg in "$@"; do
  case "$arg" in
    --port=*) PORT="${arg#*=}" ;;
    --no-open) OPEN=false ;;
  esac
done

cleanup() {
  rm -rf "$PREVIEW_DIR"
  # Kill background server if still running
  [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Copy landing page + fixtures
cp "$PROJECT_DIR/resources/allure-index.html" "$PREVIEW_DIR/index.html"
cp "$SCRIPT_DIR/preview-fixtures/builds.json" "$PREVIEW_DIR/builds.json"
cp "$SCRIPT_DIR/preview-fixtures/pr-builds.json" "$PREVIEW_DIR/pr-builds.json"

# Copy logo if it exists (used by production deployments)
[[ -f "$PROJECT_DIR/logo.svg" ]] && cp "$PROJECT_DIR/logo.svg" "$PREVIEW_DIR/logo.svg"

echo "╭──────────────────────────────────────────╮"
echo "│  Landing page preview                    │"
echo "│  http://localhost:${PORT}                     │"
echo "│                                          │"
echo "│  Fixtures: dev/preview-fixtures/         │"
echo "│  Source:   resources/allure-index.html    │"
echo "│  Ctrl+C to stop                          │"
echo "╰──────────────────────────────────────────╯"

# Start server
if command -v python3 &>/dev/null; then
  python3 -m http.server "$PORT" --directory "$PREVIEW_DIR" &
  SERVER_PID=$!
elif command -v npx &>/dev/null; then
  npx http-server "$PREVIEW_DIR" -p "$PORT" -c-1 &
  SERVER_PID=$!
else
  echo "Error: needs python3 or npx (http-server)" >&2
  exit 1
fi

sleep 0.5

# Open in browser (macOS/Linux)
if $OPEN; then
  if command -v open &>/dev/null; then
    open "http://localhost:${PORT}"
  elif command -v xdg-open &>/dev/null; then
    xdg-open "http://localhost:${PORT}"
  fi
fi

# Wait for server
wait "$SERVER_PID"
