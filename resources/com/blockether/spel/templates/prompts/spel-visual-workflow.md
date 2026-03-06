---
description: Visual QA and presentation workflow, regression checks and visual content creation
---

# Visual workflow

Orchestrates visual regression testing and visual content creation using spel subagents.

## Parameters

- Task: the visual check or presentation to create
- Target URL: the URL to capture (for visual-qa)
- Baseline dir (optional): defaults to `baselines/`
- Output dir (optional): defaults to `./spel-visual/`

## Pipeline overview

Two agents that can run independently or together.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Visual QA | @spel-visual-qa | `diff-report.json`, current snapshots/screenshots | Target URL, baseline dir |
| 2. Present | @spel-presenter | `spel-visual/<name>.html`, preview screenshot, manifest | Content to visualize |

## Visual regression check

```xml
<visual-qa>
  <task>Capture baseline or compare against existing baseline</task>
  <url>{{target-url}}</url>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</visual-qa>
```

GATE: Review the diff report before proceeding. Verify reported regressions are real and severity is accurate. If this is baseline capture (no prior baseline), confirm the captured state looks correct before it becomes the reference. Do NOT proceed until reviewed.

## Create visual explanation

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

GATE: Review the visual deliverable: HTML file, preview screenshot, and manifest. Verify rendering quality (squint test, swap test, both themes, no overflow). Do NOT approve if Mermaid diagrams have parse errors or labels are unreadable.

## Composition

- With bugfind workflow: run the visual regression check before the bug-finding pipeline. The Hunter reads `diff-report.json` to incorporate visual regressions into its candidate bug list.
- With test workflow: visual QA snapshots provide regression baselines alongside functional tests.
- With automation workflow: explorer snapshots can serve as initial baselines for visual-qa comparison.
- Presenter standalone: the presentation step can visualize ANY content (architecture diagrams, test reports, bug-finding summaries), not just visual QA output.

## Session isolation

Each agent uses its own named session:
- Visual QA: `vqa-<name>-<timestamp>`
- Presenter: `pres-<name>-<timestamp>`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage patterns

- Regression check only: run the first step alone
- Baseline capture only: run the first step with no existing baselines
- Presentation only: run the second step alone
- Full visual pipeline: run both steps — QA then present the regression report
- Upstream for bugfind: run the first step, then pass `diff-report.json` to the adversarial bug-finding workflow

## Notes

- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
