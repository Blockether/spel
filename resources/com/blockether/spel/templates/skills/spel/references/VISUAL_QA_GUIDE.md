# Visual QA guide

Catching visual regressions: layout shifts, style changes, pixel-level diffs. Baseline capture → diff → report.

Snapshot syntax / ARIA assertions → `SELECTORS_SNAPSHOTS.md`. Snapshot assertions in tests → `SNAPSHOT_TESTING.md`.

## When to use it

Catches what unit tests miss: layout refactors, design-system token changes, CSS side effects, responsive breakpoints, third-party widget changes.

Two complementary approaches:

| Approach | Tool | Catches |
|----------|------|---------|
| Structural diff | `spel snapshot -S --json` | Style values, missing/added elements, position shifts |
| Pixel diff | `spel screenshot` + external tool | Rendering, font hinting, image changes |

Use both — structural diffs are fast + CI-friendly; pixel diffs catch rendering subtleties.

## Baseline capture

Capture on a known-good state (main branch, post-design-review, …).

### Structural

```bash
# MINIMAL — 12 props (display, position, top/left/right/bottom, bg-color, color, font-size, font-weight, padding, margin)
spel snapshot -S --minimal --json > baselines/home-minimal.json

# BASE — 24 props (+ flex, gap, width, height, overflow, font-family, line-height, text-align, box-shadow, opacity, cursor, float, clear)
spel snapshot -S --json          > baselines/home-base.json

# MAX — 36 props (+ z-index, transforms, text-overflow, min/max sizes, background-image, pointer-events, outline)
spel snapshot -S --max --json    > baselines/home-max.json
```

MINIMAL = fastest, least noise. MAX catches more at the cost of false positives.

### Screenshot

```bash
spel screenshot baselines/home-baseline.png

# Full-page (content below fold)
spel eval-sci '(spel/screenshot {:path "baselines/home-full-baseline.png" :full-page true})'
```

### Naming

```
baselines/
  <page>-desktop.{json,png}
  <page>-tablet.{json,png}
  <page>-mobile.{json,png}
  <page>-full-baseline.png
```

### Mandatory viewports

| Viewport | Size | Set via |
|----------|------|---------|
| Desktop | 1280×720 | default (or `spel/set-viewport-size! 1280 720`) |
| Tablet | 768×1024 | `(spel/set-viewport-size! 768 1024)` |
| Mobile | 375×667 | `(spel/set-viewport-size! 375 667)` |

Per viewport:

```clojure
(spel/set-viewport-size! 768 1024)          ; tablet
(spel/wait-for-load-state)

(def snap (spel/capture-snapshot))
(spit "baselines/homepage-tablet.json" (json/write-str snap))

(spel/save-audit-screenshot!
  "Homepage baseline @ tablet (768×1024)"
  "baselines/homepage-tablet.png"
  {:refs (:refs snap)})
```

Baseline set is incomplete without all 3 viewports.

## Structural diff

```bash
spel snapshot -S --json > current.json        # match baseline tier
```

### Compare via `jq`

```bash
jq '[.refs | keys[]]'                         baselines/home-base.json
jq '.refs["e2yrjz"].styles'                   baselines/home-base.json
jq '.refs["e2yrjz"].styles'                   current.json

# Refs where font-size changed
jq -n --slurpfile base baselines/home-base.json --slurpfile curr current.json '
  [$base[0].refs, $curr[0].refs] |
   [.[0] | to_entries[] | .key as $k |
    select(.[0].value.styles["font-size"] != ($curr[0].refs[$k].styles["font-size"] // null))] |
   map(.key)'
```

### What to look for

- Changed style values (`font-size: 14px → 16px`)
- Position shifts (`top`, `left`, `right`, `bottom` in MINIMAL tier)
- Missing / new elements (refs in one, not the other)
- Display changes (`flex → block`)
- Duplicate elements or messages (multiple refs with same role+name)
- Content overflow / text truncation (unwanted ellipsis)
- Visual inequality between similar elements (cards, nav items)
- Partially visible content (clipped by `overflow:hidden`, off-screen, obscured)
- Broken grid/flex layout (misaligned columns, collapsed rows, orphaned floats)
- Visual incoherence (repeated UI patterns with inconsistent internal layout — badges shifting by content length instead of staying in a fixed column)

### Tier selection

| Scenario | Tier | Why |
|----------|------|-----|
| Position/layout regression | MINIMAL | Captures top/left/right/bottom, low noise |
| Typography changes | BASE | Font-family, line-height, text-align |
| Full style audit | MAX | All 36 props; one-off thorough audits |
| CI speed-sensitive | MINIMAL | Smallest payload, fastest |

## Screenshot comparison (pixel diff)

```bash
spel screenshot current.png
```

spel has no built-in pixel differ. Use:

```bash
# ImageMagick
compare -metric AE baseline.png current.png diff.png
compare -metric AE baseline.png current.png /dev/null 2>&1     # count only

# pixelmatch (Node)
npx pixelmatch baseline.png current.png diff.png 0.1
# exit 0 = within threshold, 1 = exceeds

# looks-same (anti-aliasing-aware)
npx looks-same baseline.png current.png --tolerance 2
```

### Thresholds

| Context | Acceptable | Reason |
|---------|-----------|--------|
| Static content | < 0.1 % | Any change suspicious |
| Dates/counts | mask / exclude | Crop to stable regions |
| Font rendering across OS | < 1 % | Sub-pixel variance |
| Mid-animation screenshots | disable first | `prefers-reduced-motion` |

Disable animations before capturing:

```bash
spel eval-sci '(spel/add-style-tag {:content "*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }"})'
spel screenshot current.png
```

## Baseline management

Storage:

- **Commit to git** — small teams, baselines versioned alongside code, PRs show explicit changes.
- **External storage** (S3, GCS, artifact store) — large suites, avoids repo bloat, requires a CI fetch step.

After an intentional visual change, re-capture + commit with a message explaining the change so reviewers can verify the diff is expected.

```bash
spel snapshot -S --json > baselines/home-base.json
spel screenshot           baselines/home-baseline.png
```

### CI flow

```bash
# main branch → capture + store baselines
spel open https://staging.example.com
spel snapshot -S --json > baselines/home-base.json
spel screenshot           baselines/home-baseline.png
spel close

# PR branch → capture + diff
spel open https://pr-preview.example.com
spel snapshot -S --json > current.json
spel screenshot           current.png
spel close

compare -metric AE baselines/home-baseline.png current.png diff.png
```

## Regression report

Per changed element:

```
Element: @e2yrjz (heading "Welcome")
Property: font-size
Baseline: 24px
Current:  22px
Verdict:  [REGRESSION / INTENTIONAL]
```

Screenshot evidence:

```bash
# Full page + ref overlays + printed ref list (LLM-friendly, multimodal)
spel screenshot -a report/current-annotated.png

# Programmatic
spel eval-sci '
  (def snap (spel/capture-snapshot))
  (annotate/save-annotated-screenshot! (:refs snap) "report/current-annotated.png")'
```

`:tree` includes `[pos:X,Y W×H]` screen coords per ref, enabling layout verification + overlap detection. Side-by-side `baseline.png`/`current.png` + ImageMagick diff highlights changed pixels.

For formal sign-off, generate a PDF combining screenshots + observations — see `SNAPSHOT_TESTING.md` for `report->pdf` entry types.

## Quick reference

| Task | Command |
|------|---------|
| Structural baseline (MINIMAL) | `spel snapshot -S --minimal --json > baselines/<page>-minimal.json` |
| Structural baseline (BASE) | `spel snapshot -S --json > baselines/<page>-base.json` |
| Structural baseline (MAX) | `spel snapshot -S --max --json > baselines/<page>-max.json` |
| Screenshot baseline | `spel screenshot baselines/<page>-baseline.png` |
| Full-page shot | `spel eval-sci '(spel/screenshot {:path "…" :full-page true})'` |
| Capture current (struct) | `spel snapshot -S --json > current.json` |
| Capture current (pixel) | `spel screenshot current.png` |
| Pixel diff (IM) | `compare -metric AE baseline.png current.png diff.png` |
| Disable animations | `spel eval-sci '(spel/add-style-tag {:content "* { animation-duration: 0s !important; }"})'` |
| Annotated screenshot | `spel screenshot -a out.png` |

### Style tiers

| Flag | Props | Includes |
|------|------:|---------|
| `-S --minimal` | 12 | display, position, top/left/right/bottom, bg-color, color, font-size, font-weight, padding, margin |
| `-S` (base) | 24 | MINIMAL + flex, gap, width, height, overflow, font-family, line-height, text-align, box-shadow, opacity, cursor, float, clear |
| `-S --max` | 36 | BASE + z-index, transforms, text-overflow, min/max sizes, background-image, pointer-events, outline |

## See also

- `SELECTORS_SNAPSHOTS.md` · `SNAPSHOT_TESTING.md` · `PDF_STITCH_VIDEO.md`
