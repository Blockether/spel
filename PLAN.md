# Plan — session-list cleanup, data-driven browser catalog, tests

## Round 1 — shipped

### 1. `spel session list` runs without spinning up a daemon
Moved the handler logic out of `daemon.clj`'s `handle-cmd "session_list"` into CLI-side functions called directly from the arg dispatcher, *before* `ensure-daemon!`. Verified 40 ms on a clean machine, zero sockets spawned.

- `active-sessions-on-disk` — reads `/tmp/spel-*.sock`, filters by `daemon-running?`. Pure.
- `build-session-list-data` — composes `list-active-cdp-endpoints` + `discover-external-cdp-endpoints`. Pure.
- Dispatcher short-circuit: `session_list` never reaches `ensure-daemon!`.
- **Also:** `session_info` (bare `spel session`) short-circuits when the target daemon isn't alive — no auto-spawn for info queries either.
- Fan-out parallelized with `pmap`.
- Timeout/error surfaced via a `STATUS` column that only appears when a row has a problem.

### 2. Data-driven `chromium-user-data-dirs`
Replaced the 100-line OS-specific literals with `chromium-browser-catalog` + a projection fn. Adding a new browser is one line.

### 3. Tests
New `test/com/blockether/spel/daemon_cdp_discovery_test.clj` — 17 passing cases covering:
- `chromium-user-data-dirs` (non-empty, canonical labels, well-formed)
- `wsl?` (boolean contract — see Round 2 critique item #16)
- `discover-external-cdp-endpoints` (real in-process `HttpServer` round-trip)
- `list-active-cdp-endpoints` (stale lock cleanup)
- `parse-devtools-active-port` (valid / missing / port-only)
- `render-table` (auto-size, empty rows, no-trailing-whitespace)

---

## Round 2 — critique (self-review after shipping Round 1)

### 🔴 Real bugs / correctness gaps

1. **`probe-http-cdp` still trusts any HTTP 200.** The plan said I'd "partially fix this by checking the response body has a `Browser` field" — I didn't. Any random HTTP server returning 200 on `/json/version` still gets flagged as a CDP endpoint.
2. **Duplicate session-list paths.** `handle-cmd "session_list"` in `daemon.clj` still exists and does almost the same work as `build-session-list-data` in `cli.clj`. Two sources of truth — guaranteed to drift. One should go.
3. **Three separate `.sock` enumerations.** `discover-sessions` (cli.clj), `active-sessions-on-disk` (cli.clj), and the daemon-side `session_list` handler all re-implement the same glob-and-parse loop.
4. **`:cdp_port` is set on session rows but never read anywhere.** Dead field.

### 🟠 Architecture / reliability

5. **Unbounded `pmap` fan-out.** Default thread pool (~cores+2). Fine for 1–10 sessions, bad at 100+. Process-exit mid-fan-out leaks futures until socket timeout.
6. **Silent `catch Exception _` everywhere.** No logging. When things break the user sees `—` with no breadcrumb.
7. **`session_info` calls `.cookies` and `.pages` on every invocation.** Linear work — a context with 10k cookies makes `spel session list` O(N·sessions). Cap or report "N/A" over a threshold.
8. **`discover-external-cdp-endpoints` runs on every `session list` call.** No TTL cache.
9. **`fanout-timeout-ms 1500` is a hardcoded magic number.** No `def`, no env var.

### 🟡 Data modeling / API hygiene

10. **`:_status` / `:_error` leak into `--json` output.** Internal rendering hints survive into the public JSON envelope.
11. **`:current` semantics shifted.** Old: "daemon that answered the query". New: "session the CLI was targeted at". For `spel --session work session list` with no `work` session, the arrow now points at a non-existent session.
12. **`session_info` wraps `socket-path` in a try/catch** — but `socket-path` is a pure `Path/of` call that never throws. Dead defensive code.

### 🟢 Cosmetic / minor

13. Unicode STATUS markers (`⏱` `⚠`) won't render on some terminals. No ASCII fallback.
14. `wsl-windows-user-dirs` hardcodes English folder names — breaks on non-English Windows.
15. No tests for `win-roaming` / `linux-snap` / `linux-flatpak` expansion in the catalog.
16. The WSL test is a tautology — `(expect (not (#'sut/wsl?)))` always passes on the dev box. Needs a pure helper to unit-test.
17. `render-table` doesn't right-align numeric columns (REFS/TABS).
18. `fmt-url` hardcodes 48-char truncation. Should derive from terminal width.
19. **Inconsistent HTTP timeouts** across `probe-http-cdp` callers: 200 ms, 300 ms, 1000 ms, 2000 ms.
20. `PLAN.md` lives in repo root — should move to `docs/` or be deleted when the work lands.

---

## Round 2 — Top 3 to fix now

### Fix A — tighten `probe-http-cdp` (critique #1)
- Change signature to return the port **only when** HTTP 200 *and* the body parses as JSON with a non-blank `Browser` field.
- Share the parse logic with `fetch-cdp-browser-label` — both call a new `read-cdp-json-version` helper that returns `{:port N :browser "Chrome/…"}` or `nil`.
- `discover-external-cdp-endpoints` and `list-active-cdp-endpoints` automatically benefit — no further call-site changes.

### Fix B — strip `:_status` / `:_error` from `--json` output (critique #10)
- In the `session_list` CLI short-circuit, before calling `print-result`, enrich the sessions the same way the formatter does, and **drop the `:_`-prefixed keys** before handing the map to the JSON writer.
- Better still: move the enrichment out of the formatter cond into `build-session-list-data` so *both* text and JSON paths see the same data. The formatter just reads `:session_info` fields from the already-enriched map. `:_status`/`:_error` become regular `:status`/`:status_error` fields in both outputs — still visible, no longer leaky.

### Fix C — delete dead `handle-cmd "session_list"` + consolidate sock enumerations (critique #2 + #3)
- Delete `daemon.clj`'s `handle-cmd "session_list"` (now unreachable from the CLI path).
- Introduce a single `discover-session-files` in `daemon.clj` (public) that returns `[{:name :sock-path :alive?}]`.
- Rewrite `cli.clj`'s `discover-sessions` and `active-sessions-on-disk` as thin wrappers around that helper.

**Order:**
1. Add shared `read-cdp-json-version` helper in `daemon.clj`, rewire `probe-http-cdp` + `fetch-cdp-browser-label`.
2. Add `discover-session-files` helper in `daemon.clj`, rewire the two CLI helpers, delete the daemon handler.
3. Move session-list enrichment into `build-session-list-data`, drop `:_` prefix, strip internal keys in JSON path.
4. Update tests (adapt the `discover-external-cdp-endpoints` test that uses `/json/version` with a `Browser` field — it already does the right thing, just verify it still passes).
5. Run full suite, rebuild native, smoke-test both text and `--json` output of `session list`.
