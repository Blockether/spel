---
description: Automation workflow — explore, script, and interact with browser sessions
---

# Automation Workflow

Orchestrates browser exploration, script creation, and interactive sessions using spel subagents.

## Parameters

- **Task**: The automation goal (explore a site, write a script, interactive session)
- **Target URL**: The URL to automate
- **Script output** (optional): Path for generated `.clj` scripts (default: `spel-scripts/`)
- **Args** (optional): Arguments to pass to eval scripts via `--`

## Pipeline Overview

Three agents in a progressive pipeline. Each step is independently useful — run only what you need.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | @spel-explorer | `exploration-manifest.json`, snapshots, screenshots | Target URL |
| 2. Automate | @spel-automator | `spel-scripts/<name>.clj` | Exploration data (optional) |
| 3. Interact | @spel-interactive | `auth-state.json`, authenticated screenshots | Target URL |

## Step 1: Explore

> **Agent**: @spel-explorer

Invoke @spel-explorer with:

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

The explorer agent will:
1. Open the URL and capture accessibility snapshot
2. Annotate and screenshot key elements
3. Extract structured data to JSON if needed
4. Identify selectors for automation
5. Produce `exploration-manifest.json` with artifact index

**GATE**: Review exploration artifacts — pages explored, selectors found, navigation coverage. Do NOT proceed until reviewed.

## Step 2: Automate

> **Agent**: @spel-automator

Invoke @spel-automator with:

```xml
<automate>
  <task>Write reusable automation scripts based on exploration findings</task>
  <url>{{target-url}}</url>
  <script-output>{{script-output}}</script-output>
  <args>{{args}}</args>
</automate>
```

The automator will:
1. Read `exploration-manifest.json` if available (from Step 1)
2. Write `.clj` eval scripts using selectors from exploration
3. Use `*command-line-args*` for parameterized scripts
4. Test: `spel eval-sci script.clj -- {{args}}`
5. Save scripts to the specified output directory

**GATE**: Review generated script — verify it runs with test args, handles errors, and produces expected output. Do NOT proceed until approved.

## Step 3: Interactive Refinement (Optional)

> **Agent**: @spel-interactive
> Only needed when human-in-the-loop is required (2FA, CAPTCHA, SSO).

Invoke @spel-interactive with:

```xml
<interact>
  <task>Open headed browser for user interaction, then continue automation</task>
  <url>{{target-url}}</url>
  <channel>chrome</channel>
</interact>
```

The interactive agent will:
1. Ask user for browser channel and profile preferences
2. Open headed browser for user action
3. Export `auth-state.json` for reuse by other sessions
4. Continue automation from authenticated state

**GATE**: Confirm authenticated state — verify `auth-state.json` was exported and screenshot shows expected page. Do NOT proceed until confirmed.

## Composition

- **With bugfind workflow**: Run Step 1 (Explore) before the bug-finding pipeline — the Hunter reads `exploration-manifest.json` for prioritized coverage.
- **With test workflow**: Exploration data helps the test planner identify selectors and flows.
- **With visual workflow**: Explorer snapshots provide baseline material for visual-qa.

## Session Isolation

Each agent uses its own named session (see AGENT_COMMON.md):
- Explorer: `exp-<name>`
- Automator: `auto-<name>`
- Interactive: `iact-<name>`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage Patterns

- **Data exploration only**: Run Step 1 alone
- **Full automation script creation**: Run Steps 1 + 2
- **Auth-gated automation**: Run Step 3 first for auth, then Steps 1 + 2 with `--load-state auth-state.json`
- **Quick script without exploration**: Run Step 2 alone (automator explores minimally on its own)

## Notes

- Scripts accept args via `--` separator: `spel eval-sci script.clj -- arg1 arg2`
- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
