---
description: Automation workflow: explore, script, and interact with browser sessions
---

# Automation workflow

Browser exploration + script creation via spel subagents.

Orchestrator must maintain `orchestration/automation-pipeline.json` as machine-readable handoff for stage status + produced artifacts.

## Parameters

- Task: automation goal (explore site, write script, auth-gated automation)
- Target URL: URL to automate
- Script output (optional): path for generated `.clj` scripts (default: `spel-scripts/`)
- Args (optional): args to pass to eval scripts via `--`

## Pipeline overview

Two agents, progressive pipeline. Run only what needed.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | @spel-explorer | `exploration-manifest.json`, snapshots, screenshots, `auth-state.json` (optional) | Target URL |
| 2. Automate | @spel-automator | `spel-scripts/<name>.clj` | Exploration data (optional) |

## Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

GATE: Review exploration artifacts, pages explored, selectors found, navigation coverage. Do NOT proceed until reviewed.
Required artifact before this gate:
- `exploration-manifest.json`

## Automate

```xml
<automate>
  <task>Write reusable automation scripts based on exploration findings</task>
  <url>{{target-url}}</url>
  <script-output>{{script-output}}</script-output>
  <args>{{args}}</args>
</automate>
```

GATE: Review generated script, verify runs with test args, handles errors, produces expected output. Do NOT proceed until approved.
Required artifacts before this gate:
- `spel-scripts/<name>.clj`
- Any exact JSON/data output paths requested by user

## Auth-gated exploration (optional)

When target site requires login (2FA, CAPTCHA, SSO), explorer handles auth as Step 0:

1. Explorer opens site with `--interactive` + user's browser profile
2. User authenticates manually
3. Explorer exports `auth-state.json` for reuse
4. Explorer continues normal exploration workflow

Exported `auth-state.json` usable by automator:
```bash
spel --load-state auth-state.json eval-sci script.clj
```

## Composition

- With bugfind workflow: run explore step before bug-finding pipeline. Hunter reads `exploration-manifest.json` for prioritized coverage.
- With test workflow: exploration data helps test planner identify selectors + flows.
- With visual workflow: explorer snapshots provide baseline material for Bug Hunter's visual regression phase.

## Session isolation

Each agent uses own named session:

- Explorer: `exp-<name>`
- Automator: `auto-<name>`

Sessions never overlap. Each agent closes session on completion or error.

### CDP ownership and port policy

For CDP-based runs, enforce:

1. One session owns one CDP endpoint for duration of stage.
2. Reuse that session across all commands in stage (`open`, `snapshot`, `click`, `eval-sci`).
3. Prefer ephemeral debug ports for dedicated debug browsers; avoid assuming `9222` is free.
4. Use dedicated `--user-data-dir` per run → avoid profile lock collisions.
5. Recovery must be targeted: relaunch only dedicated debug browser for that stage. Never kill all Chrome processes.

Example launch/attach pattern:

```bash
SESSION="exp-<name>"
CDP_PORT=$(spel find-free-port)

open -na "Google Chrome" --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run

spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open <url>
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT snapshot -i
```

## Usage patterns

- Data exploration only: run explore step alone
- Full automation script creation: run explore + automate
- Auth-gated automation: run explore with auth bootstrap, then automate with `--load-state auth-state.json`
- Quick script without exploration: run automate alone (automator explores minimally on own)

## Final artifact gate (fail-closed)

Before finishing automation workflow output:

1. Enumerate required artifacts for current task.
2. Verify each path exists + is non-empty.
3. If any artifact missing/empty → one focused corrective pass writing only missing outputs.
4. Re-check paths after corrective pass.
5. If still missing → mark handoff status `blocked`, list `missing_artifacts` explicitly.

Never emit `status: completed` while required artifacts missing.

## Notes

- Scripts accept args via `--` separator: `spel eval-sci script.clj -- arg1 arg2`
- Every step has GATE → human review before proceeding
- Each agent produces machine-readable output for downstream composition
- Missing artifacts fail closed: if promised JSON/data file absent → step incomplete
- For heavy portal front pages (e.g. `onet.pl`, `wp.pl`), prefer split waits: `wait --load domcontentloaded` then `wait --url <domain>` before collecting artifacts
- For consent/postcode/login modals, treat overlay handling as mandatory state management: after each modal-related action run fresh snapshot; if click logs indicate overlay/pointer interception, take full `snapshot` (not only `snapshot -i`), resolve/close modal, resume navigation.
- If tool policy blocks patch-style editing, artifact files must be written with `bash`/`python` then read back for verification
- Do not pause for generic external-helper checks when task is standard spel automation; execute directly, keep handoff artifacts current
- For direct single-URL artifact tasks, skip exploration entirely: execute minimal commands in order (`open` -> waits -> `get title`/`get url` -> write JSON -> read/verify JSON), then update `orchestration/automation-pipeline.json`
- Shell command hygiene: tool `bash` command fields must contain only executable shell commands (no narrative comments/prose embedded in command text).
- End every direct artifact run with explicit file checks (e.g. `test -s`), include verification result in handoff JSON.
