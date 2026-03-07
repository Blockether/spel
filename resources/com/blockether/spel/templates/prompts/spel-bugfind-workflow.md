---
description: Adversarial bug-finding workflow — Hunt, Challenge, Judge using three competing agents
---

# Adversarial bug-finding workflow

Orchestrates a three-agent adversarial pipeline to find, challenge, and verify bugs in a live web application. See `BUGFIND_GUIDE.md` for methodology, scoring, and JSON schemas.

## Parameters

- Target URL: the URL to audit
- Scope (optional): specific pages, flows, or areas to focus on. Defaults to full-site audit.
- Bug categories (optional): defaults to all: functional, visual, accessibility, ux, performance, api.
- Baseline dir (optional): directory with baseline snapshots for visual regression. If absent, no baseline comparison.

## Pipeline overview

Three agents with competing scoring incentives:

| Agent | Incentive | Output |
|-------|-----------|--------|
| Hunter | +1/+5/+10 per bug found | `bugfind-reports/hunter-report.json` |
| Skeptic | +score per disproval, -2x for wrong dismissal | `bugfind-reports/skeptic-review.json` |
| Referee | +1 correct, -1 incorrect judgment | `bugfind-reports/referee-verdict.json` |

## Pre-exploration (optional)

> Skip if you want the Hunter to do its own exploration.

If @spel-explorer and @spel-visual-qa are scaffolded, invoke them first for higher-quality input data:

### Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

Produces: `exploration-manifest.json`, page snapshots, screenshots.

### Visual regression (if baselines exist)

```xml
<visual-qa>
  <task>Compare against existing baselines</task>
  <url>{{target-url}}</url>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</visual-qa>
```

Produces: `diff-report.json`, current vs baseline comparison.

## Hunt

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>{{scope}}</scope>
  <categories>{{categories}}</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

GATE: Review the Hunter's report. It should contain specific bugs with evidence, not vague observations. If weak, send back with feedback.

## Challenge

```xml
<challenge>
  <url>{{target-url}}</url>
  <hunter-report>bugfind-reports/hunter-report.json</hunter-report>
</challenge>
```

GATE: Review the Skeptic's challenges. Check that disproved bugs have counter-evidence and the Skeptic didn't rubber-stamp everything as ACCEPT.

## Judge

```xml
<judge>
  <url>{{target-url}}</url>
  <hunter-report>bugfind-reports/hunter-report.json</hunter-report>
  <skeptic-review>bugfind-reports/skeptic-review.json</skeptic-review>
</judge>
```

## Final deliverable

- `bugfind-reports/referee-verdict.json` -> canonical machine verdict (`verified_bug_list` ordered by severity)
- `bugfind-reports/qa-report.html` -> stakeholder report
- `bugfind-reports/qa-report.md` -> LLM/agent handoff report with exact reproductions per issue

## Notes

- Session isolation is critical: each agent uses its own named session. Shared sessions contaminate evidence.
- All three steps are required for full adversarial value. Running only the Hunter produces an unfiltered list.
- Pre-exploration is optional: the Hunter can explore on its own, but specialist agents produce higher-quality input.
- Responsive testing: the Hunter captures at mobile (375x812), tablet (768x1024), and desktop (1440x900).
