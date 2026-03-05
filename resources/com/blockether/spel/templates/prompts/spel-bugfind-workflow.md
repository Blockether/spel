---
description: Adversarial bug-finding workflow — Hunt, Challenge, Judge using three competing agents
---

# Adversarial Bug-Finding Workflow

Orchestrates a three-agent adversarial pipeline to find, challenge, and verify bugs in a live web application. See BUGFIND_GUIDE.md for methodology, scoring, and JSON schemas.

## Parameters

- **Target URL**: The URL to audit
- **Scope** (optional): Specific pages, flows, or areas to focus on. Defaults to full-site audit.
- **Bug categories** (optional): Defaults to all: functional, visual, accessibility, ux, performance, api.
- **Baseline dir** (optional): Directory with baseline snapshots for visual regression. If absent, no baseline comparison.

## Pipeline Overview

Three agents with **competing scoring incentives**:

| Agent | Incentive | Output |
|-------|-----------|--------|
| **Hunter** | +1/+5/+10 per bug found | `bugfind-reports/hunter-report.json` |
| **Skeptic** | +score per disproval, -2x for wrong dismissal | `bugfind-reports/skeptic-review.json` |
| **Referee** | +1 correct, -1 incorrect judgment | `bugfind-reports/referee-verdict.json` |

## Step 0 (Optional): Pre-Exploration

> Skip if you want the Hunter to do its own exploration.

If @spel-explorer and @spel-visual-qa are scaffolded, invoke them first for higher-quality input data:

### 0a. Explore

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

Produces: `exploration-manifest.json`, page snapshots, screenshots.

### 0b. Visual Regression (if baselines exist)

```xml
<visual-qa>
  <task>Compare against existing baselines</task>
  <url>{{target-url}}</url>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</visual-qa>
```

Produces: `diff-report.json`, current vs baseline comparison.

## Step 1: Hunt

> **Agent**: @spel-bug-hunter

Invoke @spel-bug-hunter with:

```xml
<hunt>
  <url>{{target-url}}</url>
  <scope>{{scope}}</scope>
  <categories>{{categories}}</categories>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</hunt>
```

The Hunter will:
1. Read exploration data if available (from Step 0)
2. **Technical Audit** — Console errors, network failures, broken interactions, form validation, accessibility, responsive layouts
3. **Design Audit** — Visual hierarchy, spacing, typography, color, alignment, component consistency (UX Architect lens)
4. Capture evidence for every finding
5. Produce `bugfind-reports/hunter-report.json`

**GATE**: Review the Hunter's report. It should contain specific bugs with evidence, not vague observations. If weak, send back with feedback.

## Step 2: Challenge

> **Agent**: @spel-bug-skeptic

Invoke @spel-bug-skeptic with:

```xml
<challenge>
  <url>{{target-url}}</url>
  <hunter-report>bugfind-reports/hunter-report.json</hunter-report>
</challenge>
```

The Skeptic will:
1. Read every bug from the Hunter's report
2. Open pages in a **separate browser session**
3. Attempt to reproduce and disprove each bug
4. Calculate risk before each DISPROVE decision (only disprove when confidence > 66%)
5. Capture independent counter-evidence
6. Produce `bugfind-reports/skeptic-review.json`

**GATE**: Review the Skeptic's challenges. Ensure disproved bugs have counter-evidence and the Skeptic didn't rubber-stamp everything as ACCEPT.

## Step 3: Judge

> **Agent**: @spel-bug-referee

Invoke @spel-bug-referee with:

```xml
<judge>
  <url>{{target-url}}</url>
  <hunter-report>bugfind-reports/hunter-report.json</hunter-report>
  <skeptic-review>bugfind-reports/skeptic-review.json</skeptic-review>
</judge>
```

The Referee will:
1. Auto-confirm undisputed bugs (Skeptic accepted them)
2. Independently investigate every disputed bug in a **third browser session**
3. Deliver REAL BUG / NOT A BUG verdicts with confidence levels
4. May adjust severity levels
5. Produce `bugfind-reports/referee-verdict.json` with the **verified bug list**

## Final Deliverable

`bugfind-reports/referee-verdict.json` → `verified_bug_list` ordered by severity.

## Notes

- **Session isolation is critical** — each agent uses its own named session. Shared sessions contaminate evidence.
- **All three steps are required** for full adversarial value. Running only the Hunter produces an unfiltered list.
- **Step 0 is optional** — the Hunter can explore on its own, but specialist agents produce higher-quality input.
- **Responsive testing** — The Hunter captures at mobile (375x812), tablet (768x1024), and desktop (1440x900).
