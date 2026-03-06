# Visual QA Guide

Workflows for catching visual regressions: layout shifts, style changes, and pixel-level differences. This guide covers the full cycle from baseline capture through diff analysis and reporting.

For snapshot command syntax and ARIA assertions, see [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md).
For writing snapshot assertions in tests, see [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md).

---

## When to Use Visual Regression Testing

Visual regression testing catches things that unit tests miss:

- **Layout refactors** — component restructuring that preserves behavior but shifts elements
- **Design system updates** — token changes (colors, spacing, typography) that ripple across pages
- **CSS side effects** — a change in one component that unexpectedly affects another
- **Responsive breakpoints** — layout at specific viewport sizes
- **Third-party widget changes** — embedded content you don't control

Two complementary approaches:

| Approach | Tool | Catches |
|----------|------|---------|
| Structural diff | `spel snapshot -S --json` | Style value changes, missing/added elements, position shifts |
| Pixel diff | `spel screenshot` + external tool | Rendering differences, font rendering, image changes |

Use both. Structural diffs are fast and CI-friendly. Pixel diffs catch rendering subtleties that structural diffs miss.

---

## Baseline Capture

Capture baselines on a known-good state (main branch, after design review, etc.).

### Structural Baseline

```bash
# MINIMAL tier: 12 props — display, position, top, left, right, bottom,
#   background-color, color, font-size, font-weight, padding, margin
# Good for: layout/position regression (top/left/right/bottom now included)
spel snapshot -S --minimal --json > baselines/home-minimal.json

# BASE tier: 24 props — MINIMAL + flex, gap, width, height, overflow,
#   font-family, line-height, text-align, box-shadow, opacity, cursor, etc.
# Good for: broader style coverage, most regression scenarios
spel snapshot -S --json > baselines/home-base.json

# MAX tier: 36 props — BASE + z-index, transforms, text-overflow, min/max sizes, etc.
# Good for: comprehensive audits (more noise, use sparingly)
spel snapshot -S --max --json > baselines/home-max.json
```

Choose one tier per test scenario. MINIMAL is fastest and produces the least noise. MAX catches the most but generates more false positives from minor rendering variation.

### Screenshot Baseline

```bash
spel screenshot baselines/home-baseline.png
```

For full-page screenshots (captures content below the fold):

```bash
spel eval-sci '(spel/screenshot {:path "baselines/home-full-baseline.png" :full-page true})'
```

### Naming Convention

```
baselines/
  <page-name>-<tier>-baseline.json   # structural
  <page-name>-baseline.png           # pixel
  <page-name>-full-baseline.png      # full-page pixel
```

Examples: `baselines/checkout-minimal-baseline.json`, `baselines/nav-base-baseline.json`.

---

## Structural Diff (JSON Comparison)

The `-S` flag attaches computed CSS styles to every ref in the snapshot. Comparing two JSON snapshots tells you exactly which elements changed and how.

### Capture Current State

```bash
spel snapshot -S --json > current.json
```

Use the same tier as your baseline.

### Compare with jq

```bash
# List all ref IDs in baseline
jq '[.refs | keys[]]' baselines/home-base.json

# Check a specific element's styles
jq '.refs["e2yrjz"].styles' baselines/home-base.json
jq '.refs["e2yrjz"].styles' current.json

# Find refs where font-size changed
jq -n \
  --slurpfile base baselines/home-base.json \
  --slurpfile curr current.json \
  '[$base[0].refs, $curr[0].refs] |
   [.[0] | to_entries[] |
    .key as $k |
    select(.[0].value.styles["font-size"] != ($curr[0].refs[$k].styles["font-size"] // null))] |
   map(.key)'
```

### What to Look For

When reviewing a structural diff:

- **Changed style values** — `font-size: 14px` became `font-size: 16px`
- **Position shifts** — `top`, `left`, `right`, `bottom` changed from expected values (MINIMAL tier captures these)
- **Missing elements** — a ref present in baseline is absent in current (element removed or hidden)
- **New elements** — refs in current that weren't in baseline (new content or revealed elements)
- **Display changes** — `display: flex` became `display: block`

### Tier Selection for Regression

| Scenario | Recommended Tier | Why |
|----------|-----------------|-----|
| Position/layout regression | MINIMAL (`--minimal`) | Captures top/left/right/bottom, low noise |
| Typography changes | BASE (default) | Includes font-family, line-height, text-align |
| Full style audit | MAX (`--max`) | All 36 props, use for thorough one-off audits |
| CI speed-sensitive | MINIMAL | Smallest payload, fastest comparison |

---

## Screenshot Comparison (Pixel Diff)

Pixel diffs catch rendering differences that structural diffs miss: anti-aliasing, image rendering, font hinting, shadow blur.

### Capture Current Screenshot

```bash
spel screenshot current.png
```

### Pixel Diff Tools

spel doesn't include a built-in pixel differ. Use one of these:

**ImageMagick** (available on most CI systems):

```bash
# Compare and output diff image
compare -metric AE baseline.png current.png diff.png

# Get pixel difference count
compare -metric AE baseline.png current.png /dev/null 2>&1
```

**pixelmatch** (Node.js, precise control):

```bash
npx pixelmatch baseline.png current.png diff.png 0.1
# Exit code 0 = within threshold, 1 = exceeds threshold
```

**LooksSame** (Node.js, anti-aliasing aware):

```bash
npx looks-same baseline.png current.png --tolerance 2
```

### Threshold Guidelines

| Context | Acceptable Difference | Reason |
|---------|----------------------|--------|
| Static content | < 0.1% pixels | Very stable, any change is suspicious |
| Dynamic content (dates, counts) | Mask or exclude | Crop to stable regions |
| Font rendering across OS | < 1% pixels | Sub-pixel rendering varies |
| Animations (screenshot mid-state) | Disable animations first | Use `prefers-reduced-motion` |

Disable CSS animations before capturing baselines and current screenshots to avoid false positives:

```bash
spel eval-sci '(spel/add-style-tag {:content "*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }"})'
spel screenshot current.png
```

---

## Baseline Management

### Storage Strategy

Two options, each with tradeoffs:

**Commit baselines to git** (recommended for small teams):
- Baselines are versioned alongside code
- PRs show baseline changes explicitly
- Works well when baselines are stable

**Store baselines externally** (recommended for large suites):
- S3, GCS, or artifact storage
- Avoids bloating the repo
- Requires a fetch step in CI

### Updating Baselines

After an intentional visual change, re-capture:

```bash
# Re-capture structural baseline
spel snapshot -S --json > baselines/home-base.json

# Re-capture screenshot baseline
spel screenshot baselines/home-baseline.png
```

Commit the updated baselines with a message that explains the intentional change. Reviewers can then verify the diff is expected.

### CI Workflow

```bash
# On main branch: capture and store baselines
spel open https://staging.example.com
spel snapshot -S --json > baselines/home-base.json
spel screenshot baselines/home-baseline.png
spel close

# On PR branch: compare against stored baselines
spel open https://pr-preview.example.com
spel snapshot -S --json > current.json
spel screenshot current.png
spel close

# Diff
compare -metric AE baselines/home-baseline.png current.png diff.png
```

---

## Regression Report

A useful regression report answers three questions: what changed, was it intentional, and where is the evidence?

### Structural Change Report

For each changed element, record:

```
Element: @e2yrjz (heading "Welcome")
Property: font-size
Baseline: 24px
Current:  22px
Verdict:  [REGRESSION / INTENTIONAL]
```

### Screenshot Evidence

Capture annotated screenshots to show context around changed elements:

```bash
# Annotated screenshot with ref overlays (shows which elements changed)
spel eval-sci '
  (def snap (spel/capture-snapshot))
  (spel/save-annotated-screenshot! (:refs snap) "report/current-annotated.png")'
```
;; The :tree includes [pos:X,Y W×H] screen coordinates for each ref'd element.
;; Use position data for layout verification and element overlap detection.

Side-by-side comparison: place `baseline.png` and `current.png` next to each other in your report. The diff image from ImageMagick highlights changed pixels in red.

### PDF Audit Report

For formal sign-off, generate a PDF report combining screenshots and observations. See [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md) for the `report->pdf` entry types and usage.

---

## Quick Reference

| Task | Command |
|------|---------|
| Structural baseline (MINIMAL) | `spel snapshot -S --minimal --json > baselines/<page>-minimal.json` |
| Structural baseline (BASE) | `spel snapshot -S --json > baselines/<page>-base.json` |
| Structural baseline (MAX) | `spel snapshot -S --max --json > baselines/<page>-max.json` |
| Screenshot baseline | `spel screenshot baselines/<page>-baseline.png` |
| Full-page screenshot | `spel eval-sci '(spel/screenshot {:path "..." :full-page true})'` |
| Capture current (structural) | `spel snapshot -S --json > current.json` |
| Capture current (pixel) | `spel screenshot current.png` |
| Pixel diff (ImageMagick) | `compare -metric AE baseline.png current.png diff.png` |
| Disable animations | `spel eval-sci '(spel/add-style-tag {:content "* { animation-duration: 0s !important; }"})' ` |
| Annotated screenshot | `spel eval-sci '(spel/save-annotated-screenshot! (:refs (spel/capture-snapshot)) "out.png")'` |

### Style Tiers at a Glance

| Flag | Props | Includes |
|------|-------|---------|
| `-S --minimal` | 12 | display, position, top, left, right, bottom, background-color, color, font-size, font-weight, padding, margin |
| `-S` (base) | 24 | MINIMAL + flex, gap, width, height, overflow, font-family, line-height, text-align, box-shadow, opacity, cursor, float, clear |
| `-S --max` | 36 | BASE + z-index, transforms, text-overflow, min/max sizes, background-image, pointer-events, outline |

---

## See Also

- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — snapshot capture, ref selectors, annotated screenshots
- [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md) — ARIA assertions, test patterns, PDF audit reports
- [PDF_STITCH_VIDEO.md](PDF_STITCH_VIDEO.md) — stitching screenshots into multi-page PDFs
