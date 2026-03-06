---
description: Automation workflow: explore, script, and interact with browser sessions
---

# Automation workflow

Orchestrates browser exploration, script creation, and interactive sessions using spel subagents.

## Parameters

- Task: the automation goal (explore a site, write a script, interactive session)
- Target URL: the URL to automate
- Script output (optional): path for generated `.clj` scripts (default: `spel-scripts/`)
- Args (optional): arguments to pass to eval scripts via `--`

## Pipeline overview

Three agents in a progressive pipeline. Run only what you need.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | @spel-explorer | `exploration-manifest.json`, snapshots, screenshots | Target URL |
| 2. Automate | @spel-automator | `spel-scripts/<name>.clj` | Exploration data (optional) |
| 3. Interact | @spel-interactive | `auth-state.json`, authenticated screenshots | Target URL |

## Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

GATE: Review exploration artifacts, pages explored, selectors found, navigation coverage. Do NOT proceed until reviewed.

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

## Interactive refinement (optional)

Only needed when human-in-the-loop is required (2FA, CAPTCHA, SSO).

```xml
<interact>
  <task>Open headed browser for user interaction, then continue automation</task>
  <url>{{target-url}}</url>
  <channel>chrome</channel>
</interact>
```

GATE: Confirm authenticated state, verify `auth-state.json` was exported and screenshot shows expected page. Do NOT proceed until confirmed.

## Composition

- With bugfind workflow: run the explore step before the bug-finding pipeline. The Hunter reads `exploration-manifest.json` for prioritized coverage.
- With test workflow: exploration data helps the test planner identify selectors and flows.
- With visual workflow: explorer snapshots provide baseline material for visual-qa.

## Session isolation

Each agent uses its own named session:

- Explorer: `exp-<name>`
- Automator: `auto-<name>`
- Interactive: `iact-<name>`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage patterns

- Data exploration only: run the explore step alone
- Full automation script creation: run explore + automate
- Auth-gated automation: run interactive first, then explore + automate with `--load-state auth-state.json`
- Quick script without exploration: run automate alone (automator explores minimally on its own)

## Notes

- Scripts accept args via `--` separator: `spel eval-sci script.clj -- arg1 arg2`
- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
