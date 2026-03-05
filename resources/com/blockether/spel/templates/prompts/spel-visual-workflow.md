---
description: Visual QA and presentation workflow — regression checks and visual content creation
---

# Visual Workflow

Orchestrates visual regression testing and visual content creation using spel subagents.

## Parameters

- **Task**: The visual check or presentation to create
- **Target URL**: The URL to capture (for visual-qa)
- **Baseline dir** (optional): Defaults to `baselines/`
- **Output dir** (optional): Defaults to `./spel-visual/`

## Pipeline Overview

Two agents that can run independently or together.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Visual QA | @spel-visual-qa | `diff-report.json`, current snapshots/screenshots | Target URL, baseline dir |
| 2. Present | @spel-presenter | `spel-visual/<name>.html`, preview screenshot, manifest | Content to visualize |

## Step 1: Visual Regression Check

> **Agent**: @spel-visual-qa

Invoke @spel-visual-qa with:

```xml
<visual-qa>
  <task>Capture baseline or compare against existing baseline</task>
  <url>{{target-url}}</url>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</visual-qa>
```

The visual-qa agent will:
1. Open the target URL in a named session
2. Capture accessibility snapshot with styles (`spel snapshot -S --json`)
3. Compare against baseline if one exists in `{{baseline-dir}}`
4. Generate `diff-report.json` showing structural and visual changes
5. Take screenshots for evidence
6. Classify regressions by severity (structural = critical, position > 5px = medium, sub-pixel = noise)

**GATE**: Review the diff report before proceeding. Verify reported regressions are real and severity is accurate. If this is baseline capture (no prior baseline), confirm the captured state looks correct before it becomes the reference. Do NOT proceed until reviewed.

## Step 2: Create Visual Explanation

> **Agent**: @spel-presenter

Invoke @spel-presenter with:

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

The presenter agent will:
1. Choose appropriate diagram type and aesthetic based on content and audience
2. Generate self-contained HTML file in `spel-visual/`
3. Preview with `spel open` in a named session
4. Capture screenshot evidence with `spel screenshot`
5. Write `output-manifest.json` with artifact index

**GATE**: Review the visual deliverable — HTML file, preview screenshot, and manifest. Verify rendering quality (squint test, swap test, both themes, no overflow). Do NOT approve if Mermaid diagrams have parse errors or labels are unreadable.

## Composition

- **With bugfind workflow**: Run Step 1 before the bug-finding pipeline — the Hunter reads `diff-report.json` to incorporate visual regressions into its candidate bug list.
- **With test workflow**: Visual QA snapshots provide regression baselines alongside functional tests.
- **With automation workflow**: Explorer snapshots can serve as initial baselines for visual-qa comparison.
- **Presenter standalone**: Step 2 can visualize ANY content — architecture diagrams, test reports, bug-finding summaries — not just visual QA output.

## Session Isolation

Each agent uses its own named session (see AGENT_COMMON.md):
- Visual QA: `vqa-<name>-<timestamp>`
- Presenter: `pres-<name>-<timestamp>`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage Patterns

- **Regression check only**: Run Step 1 alone — compare current state against baselines
- **Baseline capture only**: Run Step 1 with no existing baselines — captures initial reference state
- **Presentation only**: Run Step 2 alone — generate visual content without regression checking
- **Full visual pipeline**: Run Step 1 for QA, then Step 2 to present the regression report as a visual deliverable
- **Upstream for bugfind**: Run Step 1, then pass `diff-report.json` to the adversarial bug-finding workflow

## Notes

- Run Step 1 alone for regression checks (no presentation needed)
- Run Step 2 alone for visual content creation (no regression check needed)
- Run both when you need to verify visual state AND create documentation
- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
