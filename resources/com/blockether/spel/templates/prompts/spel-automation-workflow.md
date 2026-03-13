---
description: Automation workflow: explore, script, and interact with browser sessions
---

# Automation workflow

Orchestrates browser exploration and script creation using spel subagents.

The orchestrator must maintain `orchestration/automation-pipeline.json` as the machine-readable handoff for stage status and produced artifacts.

## Parameters

- Task: the automation goal (explore a site, write a script, auth-gated automation)
- Target URL: the URL to automate
- Script output (optional): path for generated `.clj` scripts (default: `spel-scripts/`)
- Args (optional): arguments to pass to eval scripts via `--`

## Pipeline overview

Two agents in a progressive pipeline. Run only what you need.

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

GATE: Review generated script, verify it runs with test args, handles errors, and produces expected output. Do NOT proceed until approved.
Required artifacts before this gate:
- `spel-scripts/<name>.clj`
- Any exact JSON/data output paths requested by the user

## Auth-gated exploration (optional)

When the target site requires login (2FA, CAPTCHA, SSO), the explorer handles auth as its Step 0:

1. Explorer opens the site with `--interactive` and the user's browser profile
2. User authenticates manually
3. Explorer exports `auth-state.json` for reuse
4. Explorer continues with normal exploration workflow

The exported `auth-state.json` can then be used by the automator:
```bash
spel --load-state auth-state.json eval-sci script.clj
```

## Composition

- With bugfind workflow: run the explore step before the bug-finding pipeline. The Hunter reads `exploration-manifest.json` for prioritized coverage.
- With test workflow: exploration data helps the test planner identify selectors and flows.
- With visual workflow: explorer snapshots provide baseline material for the Bug Hunter's visual regression phase.

## Session isolation

Each agent uses its own named session:

- Explorer: `exp-<name>`
- Automator: `auto-<name>`

Sessions never overlap. Each agent closes its session on completion or error.

### CDP ownership and port policy

For CDP-based runs, enforce these rules:

1. One session owns one CDP endpoint for the duration of a stage.
2. Reuse that session across all commands in the stage (`open`, `snapshot`, `click`, `eval-sci`).
3. Prefer ephemeral debug ports for dedicated debug browsers; avoid assuming `9222` is free.
4. Use a dedicated `--user-data-dir` per run to avoid profile lock collisions.
5. Recovery must be targeted: relaunch only the dedicated debug browser for that stage. Never kill all Chrome processes.

Example launch/attach pattern:

```bash
SESSION="exp-<name>"
CDP_PORT=$(spel find-free-port)

open -na "Google Chrome" --args --remote-debugging-port=$CDP_PORT --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run

spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open <url>
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT snapshot -i
```

## Usage patterns

- Data exploration only: run the explore step alone
- Full automation script creation: run explore + automate
- Auth-gated automation: run explore with auth bootstrap, then automate with `--load-state auth-state.json`
- Quick script without exploration: run automate alone (automator explores minimally on its own)

## Final artifact gate (fail-closed)

Before finishing automation workflow output:

1. Enumerate required artifacts for the current task.
2. Verify each path exists and is non-empty.
3. If any artifact is missing/empty, perform one focused corrective pass that writes only missing outputs.
4. Re-check paths after corrective pass.
5. If still missing, mark handoff status `blocked` and list `missing_artifacts` explicitly.

Never emit `status: completed` while required artifacts are missing.

## Notes

- Scripts accept args via `--` separator: `spel eval-sci script.clj -- arg1 arg2`
- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
- Missing artifacts fail closed: if a promised JSON/data file is absent, the step is incomplete
- For heavy portal front pages (for example `onet.pl`, `wp.pl`), prefer split waits: `wait --load domcontentloaded` then `wait --url <domain>` before collecting artifacts
- For consent/postcode/login modals, treat overlay handling as mandatory state management: after each modal-related action run a fresh snapshot; if click logs indicate overlay/pointer interception, take full `snapshot` (not only `snapshot -i`), resolve/close modal, then resume navigation.
- If tool policy blocks patch-style editing, artifact files must be written with `bash`/`python` and then read back for verification
- Do not pause for generic external-helper checks when the task is standard spel automation; execute directly and keep handoff artifacts current
- For direct single-URL artifact tasks, skip exploration entirely: execute minimal commands in order (`open` -> waits -> `get title`/`get url` -> write JSON -> read/verify JSON), then update `orchestration/automation-pipeline.json`
- Shell command hygiene: tool `bash` command fields must contain only executable shell commands (no narrative comments/prose embedded in command text).
- End every direct artifact run with explicit file checks (for example `test -s`) and include the verification result in the handoff JSON.
