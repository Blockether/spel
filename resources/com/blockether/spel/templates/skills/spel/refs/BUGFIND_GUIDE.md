# Adversarial bug-finding guide

The adversarial bug-finding pipeline uses three competing agents with opposing incentives to produce a verified bug list with minimal false positives. Based on the Hunter/Skeptic/Referee methodology.

---

## Why adversarial?

Single-pass bug reviews have two failure modes:
1. Over-reporting — Aggressive finders report noise. Engineering time wasted on false positives.
2. Under-reporting — Conservative finders miss real bugs. Defects ship.

The adversarial approach solves both:
- The Hunter is incentivized to over-report (missing a bug scores 0)
- The Skeptic is incentivized to challenge aggressively but carefully (wrong dismissals cost 2x)
- The Referee is incentivized to be precise (every wrong judgment costs a point)

Competing incentives break the echo chamber of self-validation.

---

## Scoring system

### Hunter scoring

| Points | Severity | Examples |
|--------|----------|---------|
| +1 | Low | Minor spacing inconsistency, cosmetic issue, unlikely edge case |
| +5 | Medium | Functional issue, broken interaction, a11y gap, UX confusion, perf degradation, layout shift |
| +10 | Critical | Security vulnerability, data loss risk, crash, complete UX failure, a11y blocker |

Objective: maximize total score. Report anything that *could* be a bug. False positives are acceptable — missing real bugs is not.

### Skeptic scoring

| Action | Points |
|--------|--------|
| Successfully disprove a bug | +[bug's original score] |
| Wrongly dismiss a real bug | -2x [bug's original score] |

Objective: maximize score. Only DISPROVE when expected value is positive (confidence > 66%).

Risk calculation:
```
Expected value = (confidence × bug_score) + ((1 - confidence) × -2 × bug_score)
DISPROVE only when expected value > 0
```

### Referee scoring

| Action | Points |
|--------|--------|
| Correct judgment | +1 |
| Incorrect judgment | -1 |

Objective: be precise. Evidence over rhetoric. Reproduction over theory.

---

## Bug categories

| Category | Code | What to Check |
|----------|------|--------------|
| Functional | `functional` | Broken interactions, form validation, dead links, JS errors, wrong redirects, state corruption |
| Visual | `visual` | Layout shifts, style regressions, missing elements, responsive breakpoints, font/color issues |
| Accessibility | `accessibility` | Missing ARIA labels, keyboard nav, contrast ratios, screen reader flow, focus management |
| UX | `ux` | Confusing flows, unclear CTAs, inconsistent terminology, poor error messages, hierarchy failures |
| Performance | `performance` | Slow loads, large assets, excessive requests, render-blocking resources, layout thrashing |
| API/Network | `api` | Failed requests, wrong status codes, CORS issues, missing responses, timeout errors |

---

## JSON report schemas

### Hunter report (`hunter-report.json`)

```json
{
  "agent": "spel-bug-hunter",
  "timestamp": "2026-03-06T12:00:00Z",
  "target_url": "https://example.com",
  "pages_audited": ["https://example.com/", "https://example.com/login"],
  "total_score": 47,
  "bugs": [
    {
      "id": "BUG-001",
      "category": "functional",
      "location": "Login page > Submit button",
      "description": "Submit button does not disable during form submission, allowing double-submit",
      "impact": "Medium",
      "points": 5,
      "evidence": {
        "screenshots": ["evidence/bug-001-screenshot.png"],
        "snapshot_ref": "e3",
        "console_output": null,
        "network_log": null
      },
      "steps_to_reproduce": [
        "Navigate to /login",
        "Fill email and password",
        "Click Submit rapidly twice"
      ]
    }
  ],
  "artifacts": [
    {"type": "screenshot", "path": "evidence/page-screenshot.png"},
    {"type": "snapshot", "path": "evidence/page-snapshot.json"}
  ]
}
```

### Skeptic review (`skeptic-review.json`)

```json
{
  "agent": "spel-bug-skeptic",
  "timestamp": "2026-03-06T12:30:00Z",
  "hunter_report": "bugfind-reports/hunter-report.json",
  "total_bugs_reviewed": 12,
  "total_disproved": 4,
  "total_accepted": 8,
  "skeptic_score": 14,
  "reviews": [
    {
      "bug_id": "BUG-001",
      "original_points": 5,
      "original_category": "functional",
      "counter_argument": "The submit button has a 200ms debounce handler. Re-testing shows double-submission is prevented.",
      "evidence": {
        "screenshots": ["evidence/skeptic-bug-001-counter.png"]
      },
      "confidence": 90,
      "risk_calculation": "+5 correct, -10 wrong. EV = +3.5",
      "decision": "DISPROVE",
      "points_claimed": 5
    }
  ]
}
```

### Referee verdict (`referee-verdict.json`)

```json
{
  "agent": "spel-bug-referee",
  "timestamp": "2026-03-06T13:00:00Z",
  "hunter_report": "bugfind-reports/hunter-report.json",
  "skeptic_review": "bugfind-reports/skeptic-review.json",
  "summary": {
    "total_bugs_reviewed": 12,
    "confirmed_real": 9,
    "dismissed": 3,
    "severity_adjusted": 2,
    "high_confidence": 10,
    "medium_confidence": 2,
    "low_confidence": 0
  },
  "verdicts": [
    {
      "bug_id": "BUG-001",
      "hunter_claim": "Submit allows double-submission",
      "skeptic_counter": "200ms debounce prevents it",
      "your_observation": "Debounce exists but 300ms+ intervals bypass it. Real bug, lower severity.",
      "evidence": {
        "screenshots": ["evidence/referee-bug-001-verdict.png"]
      },
      "verdict": "REAL BUG",
      "final_severity": "Low",
      "final_points": 1,
      "confidence": "High"
    }
  ],
  "verified_bug_list": {
    "critical": [],
    "medium": [],
    "low": [
      {
        "bug_id": "BUG-001",
        "description": "Submit double-submission at 300ms+ intervals",
        "location": "Login page > Submit button",
        "category": "functional",
        "fix_suggestion": "Add server-side idempotency check"
      }
    ]
  }
}
```

---

## Pipeline flow

```
Phase 0 (optional): @spel-explorer + @spel-visual-qa
  Exploration data + visual regression report
  ↓
Phase 1: @spel-bug-hunter
  Reads exploration data (if available)
  Technical audit + Design audit (UX Architect lens)
  → bugfind-reports/hunter-report.json
  ↓ GATE: User reviews findings
Phase 2: @spel-bug-skeptic
  Reads hunter-report.json
  Independent verification in separate browser session
  → bugfind-reports/skeptic-review.json
  ↓ GATE: User reviews challenges
Phase 3: @spel-bug-referee
  Reads both reports
  Independent verification of disputed bugs in third session
  → bugfind-reports/referee-verdict.json (final deliverable)
```

---

## Directory convention

```
bugfind-reports/
  hunter-report.json
  skeptic-review.json
  referee-verdict.json
  evidence/
    <page>-snapshot.json
    <page>-screenshot.png
    <page>-annotated.png
    responsive-mobile.png
    responsive-tablet.png
    responsive-desktop.png
    bug-001-screenshot.png
    skeptic-bug-001-counter.png
    referee-bug-001-verdict.png
```

---

## UX architect lens (Hunter Phase 2)

The Hunter applies a design quality audit inspired by Jobs/Ive design philosophy. For every page:

| Dimension | Questions |
|-----------|-----------|
| Visual hierarchy | Eye lands where it should? Most important element most prominent? Scannable in 2 seconds? |
| Spacing & rhythm | Whitespace consistent and intentional? Vertical rhythm harmonious? |
| Typography | Clear hierarchy in type sizes? Too many weights competing? Calm or chaotic? |
| Color | Used with restraint and purpose? Guides attention? Sufficient contrast? |
| Alignment & grid | Elements on consistent grid? Anything off by 1-2px? |
| Component consistency | Similar elements identical across screens? Interactive elements obvious? States accounted for? |
| Density | Anything removable without losing meaning? Every element earning its place? |
| Responsiveness | Works at mobile/tablet/desktop? Touch targets sized for thumbs? |

The Jobs Filter:
- "Would a user need to be told this exists?" → UX confusion bug
- "Can this be removed without losing meaning?" → Density bug
- "Does this feel inevitable?" → Design inconsistency bug

---

## Evidence guidelines

1. Every bug needs at least one piece of evidence. No exceptions.
2. Screenshots beat descriptions. A screenshot showing the bug beats a paragraph explaining it.
3. Annotated screenshots are the strongest evidence. `spel annotate` + `spel screenshot` shows exactly which element is affected.
4. Snapshot JSON provides structural proof. Style values, ARIA attributes, element hierarchy — all machine-verifiable.
5. Independent capture is mandatory for Skeptic and Referee. They must capture their OWN evidence in their OWN session. Re-using Hunter's evidence defeats the adversarial purpose.

---

## See also

- [AGENT_COMMON.md](AGENT_COMMON.md) — Session management, I/O contracts, gates, error recovery
- [VISUAL_QA_GUIDE.md](VISUAL_QA_GUIDE.md) — Baseline capture, structural diff, regression thresholds
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — Snapshot commands, annotation, style tiers
- [EVAL_GUIDE.md](EVAL_GUIDE.md) — SCI scripting for console/network inspection
