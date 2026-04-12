---
description: Bug-finding workflow — Hunt, challenge, and verdict in a single-agent multi-phase pipeline
---

# Bug-finding workflow

Single-agent multi-phase pipeline → find, challenge, verify bugs in a live web
app. See `BUGFIND_GUIDE.md` for methodology, scoring, JSON schemas.

Orchestrator keeps `orchestration/qa-pipeline.json` current.

## Parameters

- Target URL (REQUIRED)
- Scope (pages / flows / areas; default full-site)
- Categories (default all: functional, visual, accessibility, ux, performance, api)
- Baseline dir (optional — enables visual regression)

## Pipeline — five phases, one agent

| Phase | Purpose | Output |
|-------|---------|--------|
| 0. Visual regression | Diff current vs `baselines/` (if present) | `bugfind-reports/diff-report.json` |
| 1–3. Hunt | Technical audit + design audit + evidence capture | `bugfind-reports/hunter-report.json` |
| 4. Self-challenge | Fresh session; classify CONFIRMED / FLAKY / FALSE-POSITIVE | integrated into verdict |
| 5. Verdict + reports | Final severity, reports | `verdict.json`, `qa-report.html`, `qa-report.md` |

## Optional pre-exploration

Skip unless higher-quality input needed. Run `@spel-explorer` first:

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

Produces `exploration-manifest.json` + page snapshots + screenshots that the Hunter will prioritize.

## Hunt — `@spel-bug-hunter`

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>{{scope}}</scope>
  <categories>{{categories}}</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

Hunter runs all five phases end-to-end: visual regression (if baselines) → hunt across all categories → self-challenge each finding → produce verdict + HTML + MD reports.

**GATE** — review Hunter's final report. Must contain specific, self-challenged, evidence-backed bugs. Weak → send back.

Required artifacts:

- `bugfind-reports/hunter-report.json`
- `bugfind-reports/verdict.json`
- `bugfind-reports/qa-report.html`
- `bugfind-reports/qa-report.md`
- `orchestration/qa-pipeline.json`

## Notes

- Hunter uses its own named session. Shared sessions contaminate evidence.
- Pre-exploration optional — Hunter can explore alone.
- Responsive testing: mobile 375×812, tablet 768×1024, desktop 1440×900.
- Missing artifacts fail closed.
