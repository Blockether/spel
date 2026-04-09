---
description: Visual QA and presentation workflow, regression checks and visual content creation
---

# Visual workflow

Visual regression testing + visual content creation via spel subagents.

## Parameters

- Task: visual check or presentation to create
- Target URL: URL to capture (visual regression)
- Baseline dir (optional): defaults to `baselines/`
- Output dir (optional): defaults to `$(pwd)/spel-visual/`

## Pipeline overview

Bug Hunter handles visual regression when baselines present. Presenter creates visual content independently.

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

GATE: Review diff report + hunter report before proceeding. Verify reported regressions are real, severity accurate. If baseline capture (no prior baseline), confirm captured state looks correct before becoming reference. Do NOT proceed until reviewed.

## Create visual explanation

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

GATE: Review visual deliverable: HTML file, preview screenshot, manifest. Verify rendering quality (squint test, swap test, both themes, no overflow). Do NOT approve if Mermaid diagrams have parse errors or labels unreadable.

## Composition

- With bugfind workflow: Hunter already handles visual regression as Phase 0 when baselines exist.
- With test workflow: visual regression snapshots provide regression baselines alongside functional tests.
- With automation workflow: explorer snapshots provide baseline material for Hunter's visual regression phase.
- Presenter standalone: presentation step visualizes ANY content (architecture diagrams, test reports, bug-finding summaries), not visual QA output only.

## Session isolation

Each agent uses own named session:
- Bug Hunter: `hunt-<name>-<timestamp>`
- Presenter: `pres-<name>-<timestamp>`

Sessions never overlap. Each agent closes session on completion or error.

## Usage patterns

- Regression check only: run bug-hunter with visual regression scope
- Baseline capture only: run bug-hunter with no existing baselines → captures initial state
- Presentation only: run step 2 alone
- Full visual pipeline: run both steps → Hunter for regression, then Presenter for report
- Upstream for bugfind: Hunter's visual regression = Phase 0 of bugfind workflow

## Notes

- Every step has GATE → human review before proceeding
- Each agent produces machine-readable output for downstream composition
