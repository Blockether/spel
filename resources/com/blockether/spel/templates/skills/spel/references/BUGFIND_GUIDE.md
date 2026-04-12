# Adversarial bug-finding guide

Single agent (`spel-bug-hunter`) with three internal competing phases — Hunter / Skeptic / Referee — produces a verified bug list with minimal false positives.

## Why adversarial?

Single-pass reviews fail two ways: over-report (noise → wasted review) or under-report (defects ship). Competing incentives solve both:

- Hunter over-reports (missed bug scores 0)
- Skeptic challenges aggressively but carefully (wrong dismissal costs 2×)
- Referee is precise (every wrong judgment costs 1 point)

## Scoring

### Hunter

| Points | Severity | Examples |
|:-----:|----------|----------|
| +1  | Low | Spacing inconsistency, cosmetic, unlikely edge case |
| +5  | Medium | Functional issue, broken interaction, a11y gap, UX confusion, perf degradation, layout shift |
| +10 | Critical | Security vulnerability, data loss risk, crash, complete UX failure, a11y blocker |

Goal: maximize total score. Report anything that *could* be a bug — false positives are acceptable, missing real bugs is not.

### Skeptic

| Action | Points |
|--------|:-----:|
| Successfully disprove a bug | +[bug's score] |
| Wrongly dismiss a real bug | −2× [bug's score] |

DISPROVE only when expected value is positive (confidence > 66%).

```
EV = (conf × score) + ((1 − conf) × −2 × score)
DISPROVE only when EV > 0
```

### Referee

Correct judgment +1, incorrect −1. Evidence over rhetoric; reproduction over theory.

## Categories

| Category | Code | Check |
|----------|------|-------|
| Functional | `functional` | Broken interactions, validation, dead links, JS errors, wrong redirects, state corruption |
| Visual | `visual` | Layout shifts, style regressions, missing elements, responsive breakpoints, font/color issues, duplicate elements/messages, text overflow/truncation, visual inequality or incoherence, partially visible content, broken grid/flex |
| Accessibility | `accessibility` | Missing ARIA, keyboard nav, contrast, SR flow, focus mgmt |
| UX | `ux` | Confusing flows, unclear CTAs, inconsistent terminology, poor errors, hierarchy failures |
| Performance | `performance` | Slow loads, large assets, excessive requests, render-blocking, layout thrashing |
| API/Network | `api` | Failed requests, wrong status codes, CORS, missing responses, timeouts |

## Hunter report — `hunter-report.json`

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
      "description": "Submit does not disable during submission, allowing double-submit",
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
    "duplicate_elements":  {"pass": true, "evidence": null},
    "duplicate_messages":  {"pass": true, "evidence": null},
    "text_overflow":       {"pass": true, "evidence": null},
    "text_truncation":     {"pass": true, "evidence": null},
    "visual_inequality":   {"pass": true, "evidence": null},
    "visual_coherence": {
      "pass": false,
      "snapshot_refs": ["@e4kqmn", "@e7xrtw", "@e9bnnq"],
      "screenshot": "evidence/visual-coherence-badges.png",
      "description": "Badge placement in task list rows is inconsistent — badges shift horizontally based on title length instead of staying right-aligned"
    },
    "partially_visible":   {"pass": true, "evidence": null},
    "broken_layout":       {"pass": true, "evidence": null}
  },
  "viewport_checks": {
    "homepage": {
      "desktop": {"screenshot":"evidence/homepage-desktop.png","snapshot":"evidence/homepage-desktop.json","overflow":false,"bugs_found":[]},
      "tablet":  {"screenshot":"evidence/homepage-tablet.png","snapshot":"evidence/homepage-tablet.json","overflow":false,"bugs_found":["BUG-004"]},
      "mobile":  {"screenshot":"evidence/homepage-mobile.png","snapshot":"evidence/homepage-mobile.json","overflow":true,"bugs_found":["BUG-005","BUG-006"]}
    }
  },
  "artifacts": [ /* … paths for every screenshot / snapshot mentioned above … */ ]
}
```

### `visual_checks` rules

- `"pass": true` + `"evidence": null` → checked, no issue.
- `"pass": false` MUST include `snapshot_refs[]`, `screenshot` (annotated, action markers on refs), and `description` (one sentence).
- Capture with `inject-action-markers!` + `save-audit-screenshot!`.
- Every screenshot path lives under `bugfind-reports/evidence/` and appears in the top-level `artifacts[]`.

```clojure
(def snap (spel/capture-snapshot))
(spel/inject-action-markers! "@e4kqmn" "@e7xrtw" "@e9bnnq")
(spel/save-audit-screenshot!
  "VISUAL CHECK: visual_coherence — badge position inconsistent across rows"
  "bugfind-reports/evidence/visual-coherence-badges.png"
  {:refs (:refs snap)})
(spel/remove-action-markers!)
```

### `viewport_checks` rules

One entry per audited page with `desktop` (1280×720), `tablet` (768×1024), `mobile` (375×667). Every viewport MUST have `screenshot`, `snapshot`, `overflow` (bool), `bugs_found[]`. Use `spel/set-viewport-size!` between captures and re-snapshot after each resize.

```clojure
(spel/set-viewport-size! 375 667)       ; mobile
(spel/wait-for-load-state)
(def snap (spel/capture-snapshot))
(spel/save-audit-screenshot!
  "Homepage @ mobile (375×667)"
  "bugfind-reports/evidence/homepage-mobile.png"
  {:refs (:refs snap)})
;; snapshot JSON via CLI:
;;   spel --session $SESSION snapshot -S --json > bugfind-reports/evidence/homepage-mobile.json

;; Overflow check:
(let [sw (spel/evaluate "document.documentElement.scrollWidth")
      cw (spel/evaluate "document.documentElement.clientWidth")]
  (> sw cw))                              ; true = overflow bug
```

## Self-challenge records (inside hunter report)

```json
{"challenges":[
  {"bug_id":"BUG-001","original_points":5,"original_category":"functional",
   "counter_argument":"Submit button has a 200ms debounce handler. Re-testing shows double-submission is prevented.",
   "evidence":{"screenshots":["evidence/challenge-bug-001-counter.png"]},
   "confidence":90,
   "risk_calculation":"+5 correct, -10 wrong. EV = +3.5",
   "decision":"DISPROVE",
   "points_claimed":5}]}
```

## Final verdict (inside hunter report)

```json
{"verdict_summary":{
   "total_bugs_reviewed":12,"confirmed_real":9,"dismissed":3,
   "severity_adjusted":2,"high_confidence":10,"medium_confidence":2,"low_confidence":0},
 "verdicts":[
   {"bug_id":"BUG-001",
    "hunter_claim":"Submit allows double-submission",
    "self_challenge":"200ms debounce prevents it",
    "final_observation":"Debounce exists but 300ms+ intervals bypass it. Real bug, lower severity.",
    "evidence":{"screenshots":["evidence/verdict-bug-001.png"]},
    "verdict":"REAL BUG","final_severity":"Low","final_points":1,"confidence":"High"}],
 "verified_bug_list":{
   "critical":[], "medium":[],
   "low":[{"bug_id":"BUG-001",
           "description":"Submit double-submission at 300ms+ intervals",
           "location":"Login page > Submit button",
           "category":"functional",
           "fix_suggestion":"Add server-side idempotency check"}]}}
```

## Pipeline

```
Phase 0 (optional): @spel-explorer + visual regression → exploration data + diff report
        ↓
Phase 1: @spel-bug-hunter — Hunt
        Recommended first step: `spel audit` (runs all 7: structure/contrast/colors/layout/fonts/links/headings)
        Technical audit + Design audit (UX architect lens)
        → bugfind-reports/hunter-report.json (bugs)
        ↓
Phase 2: @spel-bug-hunter — Self-Challenge (internal)
        Re-verifies each finding independently, tries to disprove weak claims
        → bugfind-reports/hunter-report.json (challenges)
        ↓ GATE: user reviews findings and challenges
Phase 3: @spel-bug-hunter — Verdict (internal)
        Weighs hunt vs challenge, independent verification of disputed bugs
        → bugfind-reports/hunter-report.json (verdict — final deliverable)
```

## Directory convention

```
bugfind-reports/
  hunter-report.json
  evidence/
    <page>-snapshot.json
    <page>-annotated.png
    <page>-{desktop,tablet,mobile}.{png,json}
    bug-001-annotated.png
    visual-coherence-badges.png
    challenge-bug-001-counter.png
    verdict-bug-001.png
```

## UX architect lens (Hunter phase 2)

Design audit inspired by Jobs/Ive. Per page:

| Dimension | Questions |
|-----------|-----------|
| Visual hierarchy | Eye lands where intended? Most important element most prominent? Scannable in 2 s? |
| Spacing & rhythm | Whitespace consistent + intentional? Vertical rhythm harmonious? |
| Typography | Clear size hierarchy? Too many weights? Calm or chaotic? |
| Color | Restrained + purposeful? Guides attention? Enough contrast? |
| Alignment & grid | Elements on a consistent grid? Anything 1–2 px off? |
| Component consistency | Same component looks identical across screens? States accounted for? Repeated patterns keep internal layout regardless of content length? |
| Density | Anything removable without losing meaning? Every element earning its place? Duplicate logos / headings / nav blocks? Same message text twice? |
| Responsiveness | All 3 viewports captured? Touch targets ≥ 44×44 on mobile? No horizontal overflow? Nav usable at every size? |

**Jobs filter**:
"Would a user need to be told this exists?" → UX confusion.
"Can this be removed without losing meaning?" → Density bug.
"Does this feel inevitable?" → Inconsistency / coherence bug.
"Are there duplicate elements or repeated messages?" → Duplication.
"Does text fit its container?" → Content overflow.
"Is meaningful content clipped, off-screen, or hidden behind an overlay?" → Visibility.
"Are grid columns aligned and flex rows intact?" → Layout.
"Do repeated UI patterns keep their internal layout regardless of content?" → Coherence.

## Evidence guidelines

1. Every bug needs ≥1 piece of evidence. No exceptions.
2. Annotated screenshots with action markers are gold standard — mark affected refs with `inject-action-markers!`, capture with `save-audit-screenshot!` (caption + ref overlays + highlights in one image).
3. Every annotated screenshot shows ref labels (for snapshot cross-ref) **and** action markers on affected elements.
4. Snapshot JSON provides structural proof — style values, ARIA, hierarchy. Capture alongside screenshots.
5. Non-visual bugs: console / network logs acceptable; pair with screenshot when the bug has any visible effect.
6. Skeptic and Referee must capture **own** evidence in **own** session — reusing Hunter's evidence defeats the purpose.

## See also

- `AGENT_COMMON.md` · `VISUAL_QA_GUIDE.md` · `SELECTORS_SNAPSHOTS.md` · `EVAL_GUIDE.md`
