---
description: Visual QA and presentation workflow, regression checks and visual content creation
---

# Visual workflow

Orchestrates visual regression testing and visual content creation using spel subagents.

## Parameters

- Task: the visual check or presentation to create
- Target URL: the URL to capture (for visual regression)
- Baseline dir (optional): defaults to `baselines/`
- Output dir (optional): defaults to `./spel-visual/`

## Pipeline overview

The Bug Hunter handles visual regression when baselines are present. The Presenter creates visual content independently.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Visual Regression | @spel-bug-hunter | `bugfind-reports/diff-report.json`, current snapshots/screenshots, `bugfind-reports/hunter-report.json` | Target URL, baseline dir |
| 2. Present | @spel-presenter | `spel-visual/<name>.html`, preview screenshot, manifest | Content to visualize |

## Visual regression check

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>visual regression</scope>
  <categories>visual</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

GATE: Review the diff report and hunter report before proceeding. Verify reported regressions are real and severity is accurate. If this is baseline capture (no prior baseline), confirm the captured state looks correct before it becomes the reference. Do NOT proceed until reviewed.

## Create visual explanation

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

GATE: Review the visual deliverable: HTML file, preview screenshot, and manifest. Verify rendering quality (squint test, swap test, both themes, no overflow). Do NOT approve if Mermaid diagrams have parse errors or labels are unreadable.

## Composition

- With bugfind workflow: the Hunter already handles visual regression as its Phase 0 when baselines exist.
- With test workflow: visual regression snapshots provide regression baselines alongside functional tests.
- With automation workflow: explorer snapshots provide baseline material for the Hunter's visual regression phase.
- Presenter standalone: the presentation step can visualize ANY content (architecture diagrams, test reports, bug-finding summaries), not just visual QA output.

## Session isolation

Each agent uses its own named session:
- Bug Hunter: `hunt-<name>-<timestamp>`
- Presenter: `pres-<name>-<timestamp>`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage patterns

- Regression check only: run the bug-hunter with visual regression scope
- Baseline capture only: run the bug-hunter with no existing baselines (captures initial state)
- Presentation only: run the second step alone
- Full visual pipeline: run both steps — Hunter for regression then Presenter for the report
- Upstream for bugfind: the Hunter’s visual regression is already Phase 0 of the bugfind workflow

## Notes

- Every step has a GATE — human review before proceeding
- Each agent produces machine-readable output for downstream composition
