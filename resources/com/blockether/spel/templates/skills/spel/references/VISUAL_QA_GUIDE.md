# Visual QA guide

Workflows for catching visual regressions: layout shifts, style changes, pixel-level diffs. Full cycle from baseline capture through diff analysis + reporting.

For snapshot syntax + ARIA assertions → [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md).
For snapshot assertions in tests → [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md).

---

## When to use visual regression testing

Catches what unit tests miss:

- Layout refactors: component restructuring preserving behavior but shifting elements
- Design system updates: token changes (colors, spacing, typography) rippling across pages
- CSS side effects: change in one component unexpectedly affecting another
- Responsive breakpoints: layout at specific viewport sizes
- Third-party widget changes: embedded content outside your control

Two complementary approaches:

| Approach | Tool | Catches |
|----------|------|---------|
| Structural diff | `spel snapshot -S --json` | Style value changes, missing/added elements, position shifts |
| Pixel diff | `spel screenshot` + external tool | Rendering differences, font rendering, image changes |

Use both. Structural diffs = fast, CI-friendly. Pixel diffs catch rendering subtleties structural misses.

---

## Baseline capture

Capture baselines on known-good state (main branch, post-design-review, etc.).

### Structural baseline

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
# Good for: thorough audits (more noise, use sparingly)
spel snapshot -S --max --json > baselines/home-max.json
```

One tier per test scenario. MINIMAL = fastest, least noise. MAX catches most but more false positives.

### Screenshot baseline

```bash
spel screenshot baselines/home-baseline.png
```

Full-page screenshots (content below fold):

```bash
spel eval-sci '(spel/screenshot {:path "baselines/home-full-baseline.png" :full-page true})'
```

### Naming convention

```
baselines/
  <page-name>-desktop.json             # desktop structural baseline
  <page-name>-desktop.png              # desktop screenshot
  <page-name>-tablet.json              # tablet structural baseline
  <page-name>-tablet.png               # tablet screenshot
  <page-name>-mobile.json              # mobile structural baseline
  <page-name>-mobile.png               # mobile screenshot
  <page-name>-full-baseline.png        # full-page pixel (desktop)
```

Examples: `baselines/checkout-desktop.json`, `baselines/checkout-mobile.png`.

### Mandatory viewport matrix

Baselines + comparisons MUST be captured at all three viewports:

| Viewport | Size | How to set |
|----------|------|------------|
| Desktop | 1280x720 | Default (or `spel/set-viewport-size! 1280 720`) |
| Tablet | 768x1024 | `(spel/set-viewport-size! 768 1024)` |
| Mobile | 375x667 | `(spel/set-viewport-size! 375 667)` |

Capture workflow per viewport:
```clojure
;; Set viewport
(spel/set-viewport-size! 768 1024)  ;; tablet
(spel/wait-for-load-state)

;; Structural snapshot
(def snap (spel/capture-snapshot))
(spit "baselines/homepage-tablet.json" (json/write-str snap))

;; Annotated screenshot
(spel/save-audit-screenshot!
  "Homepage baseline @ tablet (768x1024)"
  "baselines/homepage-tablet.png"
  {:refs (:refs snap)})
```

Repeat for desktop + mobile. Baseline set incomplete without all 3 viewports.
---

## Structural diff (JSON comparison)

`-S` flag attaches computed CSS styles to every ref. Comparing two JSON snapshots shows exactly which elements changed + how.

### Capture current state

```bash
spel snapshot -S --json > current.json
```

Use same tier as baseline.

### Compare with jq

```bash
# List all ref IDs in baseline
jq '[.refs | keys[]]' baselines/home-base.json

# Check specific element styles
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

### What to look for

Reviewing structural diff:

- Changed style values: `font-size: 14px` → `font-size: 16px`
- Position shifts: `top`, `left`, `right`, `bottom` diverged from expected (MINIMAL tier)
- Missing elements: ref in baseline absent in current (removed/hidden)
- New elements: refs in current not in baseline (new/revealed content)
- Display changes: `display: flex` → `display: block`
- Duplicate elements: multiple refs same role+name (two `img "Logo"`, two `heading "Site Title"`)
- Duplicate messages: identical text in multiple places
- Content overflow: elements larger than parent container
- Text truncation: labels/body cut off with ellipsis where full text should show
- Visual inequality: similar elements (cards, nav items) with different sizes/positions
- Partially visible: meaningful content clipped by overflow:hidden, off-screen, or obscured
- Broken layout: grid columns misaligned, flex rows collapsed, floating elements orphaned
- Visual incoherence: repeated UI patterns (rows, cards) with inconsistent internal layout — badges shifting position by content length instead of fixed column
- Broken layout: grid columns misaligned, flex rows collapsed, floating elements orphaned

### Tier selection for regression

| Scenario | Recommended tier | Why |
|----------|-----------------|-----|
| Position/layout regression | MINIMAL (`--minimal`) | Captures top/left/right/bottom, low noise |
| Typography changes | BASE (default) | Includes font-family, line-height, text-align |
| Full style audit | MAX (`--max`) | All 36 props, thorough one-off audits |
| CI speed-sensitive | MINIMAL | Smallest payload, fastest comparison |

---

## Screenshot comparison (pixel diff)

Pixel diffs catch rendering differences structural misses: anti-aliasing, image rendering, font hinting, shadow blur.

### Capture current screenshot

```bash
spel screenshot current.png
```

### Pixel diff tools

spel has no built-in pixel differ. Use:

ImageMagick (most CI systems):

```bash
# Compare + output diff image
compare -metric AE baseline.png current.png diff.png

# Get pixel difference count
compare -metric AE baseline.png current.png /dev/null 2>&1
```

pixelmatch (Node.js, precise):

```bash
npx pixelmatch baseline.png current.png diff.png 0.1
# Exit 0 = within threshold, 1 = exceeds
```

LooksSame (Node.js, anti-aliasing aware):

```bash
npx looks-same baseline.png current.png --tolerance 2
```

### Threshold guidelines

| Context | Acceptable difference | Reason |
|---------|----------------------|--------|
| Static content | < 0.1% pixels | Very stable, any change suspicious |
| Content with dates/counts | Mask or exclude | Crop to stable regions |
| Font rendering across OS | < 1% pixels | Sub-pixel rendering varies |
| Animations (screenshot mid-state) | Disable animations first | Use `prefers-reduced-motion` |

Disable CSS animations before capturing baselines + current screenshots:

```bash
spel eval-sci '(spel/add-style-tag {:content "*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }"})'
spel screenshot current.png
```

---

## Baseline management

### Storage strategy

Commit baselines to git (recommended, small teams):
- Baselines versioned alongside code
- PRs show baseline changes explicitly
- Works well when baselines stable

Store baselines externally (recommended, large suites):
- S3, GCS, artifact storage
- Avoids repo bloat
- Requires fetch step in CI

### Updating baselines

After intentional visual change, re-capture:

```bash
# Re-capture structural baseline
spel snapshot -S --json > baselines/home-base.json

# Re-capture screenshot baseline
spel screenshot baselines/home-baseline.png
```

Commit updated baselines with message explaining intentional change. Reviewers verify diff is expected.

### CI workflow

```bash
# On main branch: capture + store baselines
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

## Regression report

Useful regression report answers: what changed, intentional?, where's evidence?

### Structural change report

Per changed element, record:

```
Element: @e2yrjz (heading "Welcome")
Property: font-size
Baseline: 24px
Current:  22px
Verdict:  [REGRESSION / INTENTIONAL]
```

### Screenshot evidence

Capture annotated screenshots showing context around changed elements:

```bash
# One-liner: full-page screenshot with ref overlays + printed ref list
# maps visual labels → @refs (LLM-friendly, multimodal reasoning).
spel screenshot -a report/current-annotated.png

# Equivalent SCI form for programmatic access:
spel eval-sci '
  (def snap (spel/capture-snapshot))
  (annotate/save-annotated-screenshot! (:refs snap) "report/current-annotated.png")'
```
;; :tree includes [pos:X,Y W×H] screen coordinates per ref'd element.
;; Use position data for layout verification + element overlap detection.

Side-by-side: place `baseline.png` + `current.png` next to each other. Diff image from ImageMagick highlights changed pixels in red.

### PDF audit report

For formal sign-off, generate PDF combining screenshots + observations. See [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md) for `report->pdf` entry types + usage.

---

## Quick reference

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
| Annotated screenshot | `spel screenshot -a out.png` (full-page + ref overlays + printed `@ref role "name"` list) |

### Style tiers at a glance

| Flag | Props | Includes |
|------|-------|---------|
| `-S --minimal` | 12 | display, position, top, left, right, bottom, background-color, color, font-size, font-weight, padding, margin |
| `-S` (base) | 24 | MINIMAL + flex, gap, width, height, overflow, font-family, line-height, text-align, box-shadow, opacity, cursor, float, clear |
| `-S --max` | 36 | BASE + z-index, transforms, text-overflow, min/max sizes, background-image, pointer-events, outline |

---

## See also

- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — snapshot capture, ref selectors, annotated screenshots
- [SNAPSHOT_TESTING.md](SNAPSHOT_TESTING.md) — ARIA assertions, test patterns, PDF audit reports
- [PDF_STITCH_VIDEO.md](PDF_STITCH_VIDEO.md) — stitching screenshots into multi-page PDFs