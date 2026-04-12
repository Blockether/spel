---
description: Automation workflow: explore, script, and interact with browser sessions
---

# Automation workflow

Two-agent pipeline. Orchestrator keeps `orchestration/automation-pipeline.json` current.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | `@spel-explorer` | `exploration-manifest.json`, snapshots, screenshots, `auth-state.json` (opt.) | Target URL |
| 2. Automate | `@spel-automator` | `spel-scripts/<name>.clj` | Exploration data (opt.) |

## Parameters

- Task (explore site / write script / auth-gated automation)
- Target URL
- Script output dir (default `spel-scripts/`)
- Args forwarded via `--`

## 1. Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

**GATE** — review pages explored, selectors found, navigation coverage.
Required artifact: `exploration-manifest.json`.

### Auth-gated (optional)

Login needs 2FA/CAPTCHA/SSO? Explorer runs Step 0: open with `--interactive` +
user's profile, user authenticates manually, explorer exports `auth-state.json`,
then continues normal exploration. Reuse with `spel --load-state auth-state.json …`.

## 2. Automate

```xml
<automate>
  <task>Write reusable automation scripts based on exploration findings</task>
  <url>{{target-url}}</url>
  <script-output>{{script-output}}</script-output>
  <args>{{args}}</args>
</automate>
```

**GATE** — review generated script, run with test args, verify error handling + expected output.

Required artifacts:
- `spel-scripts/<name>.clj`
- Any JSON/data output paths requested by user

## Session isolation + CDP

One stage = one named session (`exp-<name>`, `auto-<name>`) reused across every command. For CDP runs: one session owns one endpoint; prefer ephemeral ports (never assume 9222); dedicated `--user-data-dir` per run; recovery = relaunch only the dedicated debug browser, never global Chrome kills.

```bash
SESSION="exp-<name>"
CDP_PORT=$(spel find-free-port)
open -na "Google Chrome" --args --remote-debugging-port=$CDP_PORT \
     --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open <url>
```

## Navigation defaults

- Simulate user actions; never `spel open <url>` to skip steps (only initial load).
- Heavy portal pages: `wait --load domcontentloaded` then `wait --url <domain>` before extraction.
- Modal/consent/postcode: fresh `snapshot` after each action. Pointer-interception error → stop retries, run full `snapshot` (not `-i`), resolve modal, resume.
- Longer click timeouts are a last resort.
- Tool policy blocks file edits → write artifacts via `bash`/`python`, read back to verify.

## Final artifact gate (fail closed)

1. Enumerate required artifacts.
2. Verify each exists + non-empty.
3. Missing → one focused corrective pass writing only missing outputs.
4. Still missing → status `blocked`, list `missing_artifacts` explicitly.
5. End direct-artifact runs with a concrete file check (`test -s <path>`) recorded in the handoff JSON.

## Notes

- Scripts take args via `--`: `spel eval-sci script.clj -- arg1 arg2`.
- Skip exploration for single-URL artifact tasks — execute `open → waits → get title/url → write JSON → verify` in one turn, update the pipeline JSON.
- Compose with bugfind (exploration seeds Hunter), test (selectors/flows feed the test-writer), visual (snapshots feed Hunter's Phase 0).
