---
description: Visual QA and presentation workflow, regression checks and visual content creation
---

# Visual workflow

Bug Hunter handles visual regression when baselines exist. Presenter creates visual content independently.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Visual regression | `@spel-bug-hunter` | `bugfind-reports/diff-report.json`, current snapshots/screenshots, `hunter-report.json` | Target URL, baseline dir |
| 2. Present | `@spel-presenter` | `spel-visual/<name>.html`, preview screenshot, manifest | Content to visualize |

## Parameters

- Task (visual check / presentation)
- Target URL (regression)
- Baseline dir (default `baselines/`)
- Output dir (default `$(pwd)/spel-visual/`)

## 1. Visual regression

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>visual regression</scope>
  <categories>visual</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

**GATE** — review diff + hunter reports. Verify reported regressions are real,
severity accurate. Baseline capture mode (no prior baselines) → confirm state
looks correct before it becomes the reference.

## 2. Create visual content

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

**GATE** — HTML + preview screenshot + manifest. Verify squint/swap tests, both themes, no overflow, Mermaid parses, labels readable.

## Session isolation

- Bug Hunter: `hunt-<name>-<ts>`
- Presenter: `pres-<name>-<ts>`

## Patterns

- Regression only → run Hunter with visual scope
- Baseline capture → Hunter with no existing baselines
- Presentation only → Step 2 alone
- Full → Hunter then Presenter
- Upstream of bugfind: Hunter's visual regression is Phase 0 of bugfind

## Notes

- Presenter visualizes ANY content (architecture, reports, findings), not just visual QA output.
- Every step has a GATE.
