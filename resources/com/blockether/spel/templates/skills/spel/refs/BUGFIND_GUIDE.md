# Adversarial bug-finding guide

The adversarial bug-finding pipeline uses a single agent (`spel-bug-hunter`) with built-in competing phases to produce a verified bug list with minimal false positives. Based on the Hunter/Skeptic/Referee methodology, now consolidated into one agent with three internal phases.

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
| Visual | `visual` | Layout shifts, style regressions, missing elements, responsive breakpoints, font/color issues, duplicate elements (2x logo/heading/nav), duplicate messages (same text in multiple places), text overflow and truncation (ellipsis, clipped labels), visual inequality between similar elements, visual incoherence (repeated patterns with inconsistent internal layout — e.g. badges that jump position based on content length), partially visible elements (clipped by overflow or off-screen), broken grid/flex layout (misaligned columns, collapsed rows) |
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
        "screenshots": ["evidence/bug-001-annotated.png"],
        "snapshot_refs": ["@e3"],
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
  "visual_checks": {
    "duplicate_elements": {"pass": true, "evidence": null},
    "duplicate_messages": {"pass": true, "evidence": null},
    "text_overflow": {"pass": true, "evidence": null},
    "text_truncation": {"pass": true, "evidence": null},
    "visual_inequality": {"pass": true, "evidence": null},
    "visual_coherence": {
      "pass": false,
      "snapshot_refs": ["@e4kqmn", "@e7xrtw", "@e9bnnq"],
      "screenshot": "evidence/visual-coherence-badges.png",
      "description": "Badge placement in task list rows is inconsistent — badges shift horizontally based on title length instead of staying right-aligned"
    },
    "partially_visible": {"pass": true, "evidence": null},
    "broken_layout": {"pass": true, "evidence": null}
  },
  "viewport_checks": {
    "homepage": {
      "desktop": {
        "screenshot": "evidence/homepage-desktop.png",
        "snapshot": "evidence/homepage-desktop.json",
        "overflow": false,
        "bugs_found": []
      },
      "tablet": {
        "screenshot": "evidence/homepage-tablet.png",
        "snapshot": "evidence/homepage-tablet.json",
        "overflow": false,
        "bugs_found": ["BUG-004"]
      },
      "mobile": {
        "screenshot": "evidence/homepage-mobile.png",
        "snapshot": "evidence/homepage-mobile.json",
        "overflow": true,
        "bugs_found": ["BUG-005", "BUG-006"]
      }
    }
  },
  "artifacts": [
    {"type": "annotated-screenshot", "path": "evidence/page-annotated.png"},
    {"type": "annotated-screenshot", "path": "evidence/bug-001-annotated.png"},
    {"type": "annotated-screenshot", "path": "evidence/visual-coherence-badges.png"},
    {"type": "annotated-screenshot", "path": "evidence/homepage-desktop.png"},
    {"type": "annotated-screenshot", "path": "evidence/homepage-tablet.png"},
    {"type": "annotated-screenshot", "path": "evidence/homepage-mobile.png"},
    {"type": "snapshot", "path": "evidence/page-snapshot.json"},
    {"type": "snapshot", "path": "evidence/homepage-desktop.json"},
    {"type": "snapshot", "path": "evidence/homepage-tablet.json"},
    {"type": "snapshot", "path": "evidence/homepage-mobile.json"}
  ]
}
```

**`visual_checks` rules:**
- `"pass": true` + `"evidence": null` = checked, no issue found.
- `"pass": false` = issue found. MUST include:
  - `"snapshot_refs"`: array of `@eXXXX` refs for the affected elements
  - `"screenshot"`: path to an annotated screenshot with action markers highlighting those refs
  - `"description"`: what's wrong, in one sentence
- The screenshot must be captured with `inject-action-markers!` + `save-audit-screenshot!` so the affected refs are visually highlighted.
- Every screenshot path must exist in `bugfind-reports/evidence/` and appear in the top-level `artifacts[]`.

**Evidence capture for visual_checks:**
```clojure
;; When a visual check fails, capture proof:
(def snap (spel/capture-snapshot))
(spel/inject-action-markers! "@e4kqmn" "@e7xrtw" "@e9bnnq")
(spel/save-audit-screenshot!
  "VISUAL CHECK: visual_coherence — badge position inconsistent across rows"
  "bugfind-reports/evidence/visual-coherence-badges.png"
  {:refs (:refs snap)})
(spel/remove-action-markers!)
```

**`viewport_checks` rules:**
- One entry per audited page. Each page has `desktop` (1280x720), `tablet` (768x1024), and `mobile` (375x667).
- Every viewport MUST have:
  - `"screenshot"`: annotated screenshot captured at that viewport via `save-audit-screenshot!`
  - `"snapshot"`: structural snapshot JSON captured at that viewport
  - `"overflow"`: boolean — did horizontal scrollbar appear?
  - `"bugs_found"`: array of bug IDs discovered at this viewport (empty array if clean)
- Use `spel/set-viewport-size!` to resize between captures. Re-snapshot after each resize.
- All screenshot/snapshot paths must exist in `bugfind-reports/evidence/` and appear in `artifacts[]`.

**Viewport capture workflow:**
```clojure
;; For each page, at each viewport:
(spel/set-viewport-size! 375 667)  ;; mobile
(spel/wait-for-load-state)
(def snap (spel/capture-snapshot))
(spel/save-audit-screenshot!
  "Homepage @ mobile (375x667)"
  "bugfind-reports/evidence/homepage-mobile.png"
  {:refs (:refs snap)})
;; Save snapshot JSON separately via CLI:
;; spel --session $SESSION snapshot -S --json > bugfind-reports/evidence/homepage-mobile.json

;; Check for horizontal overflow:
(let [sw (spel/evaluate "document.documentElement.scrollWidth")
      cw (spel/evaluate "document.documentElement.clientWidth")]
  (> sw cw))  ;; true = overflow bug
```

### Self-challenge review (built into hunter report)

The bug-hunter's internal skeptic phase produces challenge records within the hunter report:

```json
{
  "challenges": [
    {
      "bug_id": "BUG-001",
      "original_points": 5,
      "original_category": "functional",
      "counter_argument": "The submit button has a 200ms debounce handler. Re-testing shows double-submission is prevented.",
      "evidence": {
        "screenshots": ["evidence/challenge-bug-001-counter.png"]
      },
      "confidence": 90,
      "risk_calculation": "+5 correct, -10 wrong. EV = +3.5",
      "decision": "DISPROVE",
      "points_claimed": 5
    }
  ]
}
```

### Final verdict (built into hunter report)

The bug-hunter's internal referee phase produces the final verdict within the same report:

```json
{
  "verdict_summary": {
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
      "self_challenge": "200ms debounce prevents it",
      "final_observation": "Debounce exists but 300ms+ intervals bypass it. Real bug, lower severity.",
      "evidence": {
        "screenshots": ["evidence/verdict-bug-001.png"]
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
Phase 0 (optional): @spel-explorer + visual regression (built into Hunter)
  Exploration data + visual regression report
  ↓
Phase 1: @spel-bug-hunter — Hunt
  Reads exploration data (if available)
  Technical audit + Design audit (UX Architect lens)
  → bugfind-reports/hunter-report.json (bugs section)
  ↓
Phase 2: @spel-bug-hunter — Self-Challenge (internal)
  Re-verifies each finding independently
  Attempts to disprove weak claims
  → bugfind-reports/hunter-report.json (challenges section)
  ↓ GATE: User reviews findings and challenges
Phase 3: @spel-bug-hunter — Verdict (internal)
  Weighs hunt claims vs self-challenge evidence
  Independent verification of disputed bugs
  → bugfind-reports/hunter-report.json (verdict section — final deliverable)
```

---

## Directory convention

```
bugfind-reports/
  hunter-report.json
  evidence/
    <page>-snapshot.json
    <page>-annotated.png
    <page>-desktop.png
    <page>-desktop.json
    <page>-tablet.png
    <page>-tablet.json
    <page>-mobile.png
    <page>-mobile.json
    bug-001-annotated.png
    visual-coherence-badges.png
    challenge-bug-001-counter.png
    verdict-bug-001.png
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
| Component consistency | Similar elements identical across screens? Interactive elements obvious? States accounted for? Repeated list/card patterns maintain consistent internal layout (badges, icons, metadata in the same position regardless of content length)? |
| Density | Anything removable without losing meaning? Every element earning its place? Duplicate logos/headings/nav blocks? Same message text appearing in multiple places? |
| Responsiveness | Tested at all 3 viewports (desktop 1280x720, tablet 768x1024, mobile 375x667)? Annotated screenshot + snapshot captured at each? Touch targets ≥44x44px on mobile? No horizontal overflow? Navigation usable at every size? |

The Jobs Filter:
- "Would a user need to be told this exists?" → UX confusion bug
- "Can this be removed without losing meaning?" → Density bug
- "Does this feel inevitable?" → Design inconsistency bug / Visual coherence bug
- "Are there duplicate elements or repeated messages that shouldn't appear twice?" → Duplication bug
- "Does text fit its container or does it overflow/truncate?" → Content overflow bug
- "Is any meaningful content clipped, off-screen, or hidden behind an overlay?" → Visibility bug
- "Are grid columns aligned and flex rows intact?" → Layout bug
- "Do repeated UI patterns keep their internal layout consistent regardless of content?" → Visual coherence bug

---

## Evidence guidelines

1. Every bug needs at least one piece of evidence. No exceptions.
2. Annotated screenshots with action markers are the gold standard. Mark affected refs with `spel/inject-action-markers!`, then capture with `spel/save-audit-screenshot!` (includes caption + ref overlays + highlighted elements in one image).
3. Every annotated screenshot must show: (a) the ref labels so the reviewer can cross-reference with the snapshot, and (b) the action markers highlighting exactly which elements are affected.
4. Snapshot JSON provides structural proof. Style values, ARIA attributes, element hierarchy — all machine-verifiable. Always capture alongside screenshots.
5. For non-visual bugs, console output or network logs are acceptable, but pair with a screenshot when the bug has any visible effect.
6. Independent capture is mandatory for Skeptic and Referee. They must capture their OWN evidence in their OWN session. Reusing Hunter's evidence defeats the adversarial purpose.

---

## See also

- [AGENT_COMMON.md](AGENT_COMMON.md) — Session management, I/O contracts, gates, error recovery
- [VISUAL_QA_GUIDE.md](VISUAL_QA_GUIDE.md) — Baseline capture, structural diff, regression thresholds
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — Snapshot commands, annotation, style tiers
- [EVAL_GUIDE.md](EVAL_GUIDE.md) — SCI scripting for console/network inspection
