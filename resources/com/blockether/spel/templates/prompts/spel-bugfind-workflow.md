---
description: Bug-finding workflow — Hunt, challenge, and verdict in a single-agent multi-phase pipeline
---

# Bug-finding workflow

Orchestrates a single-agent multi-phase pipeline to find, challenge, and verify bugs in a live web application. See `BUGFIND_GUIDE.md` for methodology, scoring, and JSON schemas.

The orchestrator must maintain `orchestration/qa-pipeline.json` as the machine-readable handoff for stage status and produced artifacts.

## Parameters

- Target URL: the URL to audit
- Scope (optional): specific pages, flows, or areas to focus on. Defaults to full-site audit.
- Bug categories (optional): defaults to all: functional, visual, accessibility, ux, performance, api.
- Baseline dir (optional): directory with baseline snapshots for visual regression. If absent, no baseline comparison.

## Pipeline overview

One agent with five phases:

| Phase | Purpose | Output |
|-------|---------|--------|
| 0. Visual regression | Diff against baselines (if present) | `bugfind-reports/diff-report.json` |
| 1-3. Hunt | Explore + test across all categories | `bugfind-reports/hunter-report.json` |
| 4. Self-challenge | Challenge each finding: real bug or false positive? | Integrated into final report |
| 5. Verdict + report | Final severity scoring and report generation | `bugfind-reports/qa-report.html`, `bugfind-reports/qa-report.md` |

## Pre-exploration (optional)

> Skip if you want the Bug Hunter to do its own exploration.

If @spel-explorer is scaffolded, invoke it first for higher-quality input data. The Bug Hunter handles visual regression as its Phase 0 when baselines exist.

### Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

Produces: `exploration-manifest.json`, page snapshots, screenshots.

## Hunt

> Agent: @spel-bug-hunter

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>{{scope}}</scope>
  <categories>{{categories}}</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

The Bug Hunter runs all five phases in sequence:

1. **Visual regression** (Phase 0): If baselines exist, captures current state, diffs against baselines, generates `bugfind-reports/diff-report.json`.
2. **Hunt** (Phases 1–3): Explores the target, tests across all bug categories, collects evidence with screenshots and reproduction steps.
3. **Self-challenge** (Phase 4): Challenges each finding — disproves false positives, verifies real bugs with counter-evidence, adjusts severity scores.
4. **Verdict + report** (Phase 5): Produces the final verified bug list ordered by severity, generates `bugfind-reports/qa-report.html` and `bugfind-reports/qa-report.md`.

**GATE**: Review the Bug Hunter's final report. It should contain specific, self-challenged bugs with evidence — not vague observations or unverified claims. If weak, send back with feedback.
Required artifacts before this gate:
- `bugfind-reports/hunter-report.json`
- `bugfind-reports/qa-report.html`
- `bugfind-reports/qa-report.md`
- `orchestration/qa-pipeline.json`

## Final deliverable

- `bugfind-reports/hunter-report.json` → machine-readable bug list with self-challenge verdicts
- `bugfind-reports/qa-report.html` → stakeholder report
- `bugfind-reports/qa-report.md` → LLM/agent handoff report with exact reproductions per issue
- `orchestration/qa-pipeline.json` → pipeline handoff + gate state

## Notes

- Session isolation is critical: the Bug Hunter uses its own named session. Shared sessions contaminate evidence.
- The self-challenge phase is built into the Bug Hunter — no separate agent invocation needed.
- Pre-exploration is optional: the Bug Hunter can explore on its own, but specialist agents produce higher-quality input.
- Responsive testing: the Bug Hunter captures at mobile (375x812), tablet (768x1024), and desktop (1440x900).
- Missing artifacts fail closed: if a promised JSON/report file is absent, the step is incomplete.
