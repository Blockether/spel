# Alternative Allure Report — Visual Bug Hunt

**Targets**
- Alternative: `https://blockether.github.io/spel/544/alternative/`
- Default (baseline): `https://blockether.github.io/spel/544/`

**Environment**
- Viewports tested: 1440×900 desktop, 768×1024 tablet, 375×667 mobile
- Dark mode: verified by injecting the `prefers-color-scheme: dark` CSS rule-set that already exists in the stylesheet
- Evidence path: `/Users/fierycod/playwright-clj/bugfind-reports/evidence/`

## Summary

**12 findings** — 1 blocker, 4 high, 5 medium, 2 low.

The alt report is visually well-designed at desktop and mobile, and its dark-mode theme is clean. However, the Playwright trace viewer is completely broken inside the embedded iframe modal (blocker), several pieces of commit/run metadata that the default report shows are silently dropped, and the DOM is ~108× larger than the default's, which is a real performance concern for 2k+ test reports. Everything else is polish: the filter bar’s native `<select>` breaks the otherwise-consistent visual language, empty filter states show nothing, interactive buttons with low-affordance styling, and a handful of tiny (≤10 px) uppercase labels.

---

## Findings

### 🔴 Blockers

#### 1. "Open Trace" modal is blank — trace viewer never renders inside the iframe
- **Repro**
  1. Open `https://blockether.github.io/spel/544/alternative/` at 1440×900.
  2. Search for `showcase` to narrow the list.
  3. Expand any showcase test that has `1 attachment` (e.g. "performs full health check with detailed steps").
  4. Click the "OPEN TRACE" button near the top of the expansion.
- **Expected** A modal opens with the Playwright Trace Viewer loaded for the selected trace (actions list, timeline, screenshots). Confirmed working when the viewer URL is visited directly: `trace-viewer/index.html?trace=../data/attachments/<uuid>-attachment.zip` renders the full viewer with actions, timeline, and screenshots (evidence: `alt/15-trace-direct.png`).
- **Actual** A modal appears with header "Playwright Trace" + "Close" button, but the iframe body is completely white/blank indefinitely (waited 5+ seconds, waited on `networkidle`). Closing and re-opening yields the same result.
- **Root-cause indicator** Inspecting `page.frames()` shows the iframe's *current* URL is not the trace viewer URL but a `data:text/html;base64,…` URL that decodes to literally `<body></body><style>body { color-scheme: light dark; background: light-dark(white, #333) }</style>`. Network panel confirms that the zip (`200`, ~300 B) and the trace viewer HTML (`200`, 1337 B) both fetched successfully, so this is not a 404. It looks like the Playwright trace viewer's SW/bootstrapping code falls back to a blank data URL when it finishes initializing inside the iframe under the `trace-modal` — most likely the service worker registration or zip fetch is breaking because of the relative `trace=../data/attachments/...` URL passed from the embedding page's context, or Playwright's SW bootstrap blows away the frame’s document. The direct-URL case still works because it has a different base.
- **Impact** Every trace attachment in the report (there are 399 download-zip attachments across 2196 tests) is effectively inaccessible to anyone who clicks the in-report viewer. Download still works, but that is the default-report’s fallback — the whole point of the alt’s modal viewer is gone.
- **Screenshots**
  - Blank iframe after click: `bugfind-reports/evidence/alt/13-trace-viewer.png`, `alt/14-trace-waited.png`, `alt/16-trace-iframe-loaded.png`, `alt/22-trace-iframe-wait5s.png`
  - Same viewer rendering correctly when loaded directly: `bugfind-reports/evidence/alt/15-trace-direct.png`

---

### 🟠 High

#### 2. Run metadata (commit SHA, author, date, PR/run link) is silently dropped from the header
- **Repro** Load either report at any viewport. Compare the area immediately under the top bar.
- **Expected (from default)** Default renders `#544 · f9e04421f828d6fb24c46a405559aee3b5f3c7af · Michał Kruk · refine alternative report UX and add split-trace loading` as an `<h2>` plus `April 10, 2026 at 10:42:53 AM` below it, plus a GitHub chip `report #546` linking to the Actions run.
- **Actual (alt)** The header only shows a kicker "ALLURE REPORT" and an `<h1>` titled literally "Allure Report". None of the commit SHA, author, commit subject, run timestamp, or PR/run link is present anywhere on the page. `document.title` is also just "Allure Report" (default sets it to the full commit line).
- **Impact** When multiple reports are open in tabs (common for comparing runs), every alt tab reads "Allure Report — Allure Report" and there is no way to tell which report is which. Users cannot copy/paste the commit SHA, click through to the CI run, or even see when the run was produced. For a report intended to replace the default one, dropping the run identity is a regression most users will notice immediately.
- **Screenshots**
  - Default header: `bugfind-reports/evidence/default/01-home-desktop.png`
  - Alt header: `bugfind-reports/evidence/alt/03-header-desktop.png`, `alt/21-header.png`

#### 3. DOM size is ~65,000 elements (vs ~600 on default) — big performance smell
- **Repro** Load each report, run `document.querySelectorAll("*").length`.
- **Expected** Comparable element count, or at least virtualized rendering so only the visible suites materialize.
- **Actual** Default: **603** elements. Alternative: **65,665** elements (≈108×), with **4,637 `<details>` elements** and all 2,196 test rows materialized up-front. Initial paint and scroll still feel OK on a desktop M-class machine, but Lighthouse warns above ~1,500 elements and this would stutter noticeably on low-end mobile hardware (Chrome ships a warning for "Avoid an excessive DOM size" over 1,500). Combined with click-to-expand on every suite/test, it also makes CSS/JS query costs scale linearly with test count — already 2,196 tests → how this behaves on a 10k-test monorepo is worrying.
- **Impact** Noticeable scroll jank/TTI regression on mobile and older hardware; potential crash or very slow open-all on 10k+ test reports.
- **Evidence**
  - Values captured via `document.querySelectorAll("*").length` in each session (alt: 65665, default: 603)
  - Full-page screenshot (desktop) of alt at `bugfind-reports/evidence/alt/06-expanded-all.png` (25 MB PNG, page height ~111,716 px) — note the file size and height alone make the point

#### 4. Filter bar’s Sort dropdown is a native `<select>` — breaks the rest of the visual language
- **Repro** Observe the filter bar at `1440×900`. The "Sort: Status / Longest first / Shortest first / Name A-Z" control is the only control in that row that is a native OS dropdown.
- **Expected** All controls in the filter bar should look like part of the same design system (rounded 6 px pill buttons with the Inter font, matching the ALL/PASSED/FAILED/BROKEN and EXPAND/COLLAPSE buttons right next to it).
- **Actual** It’s a raw `<select>` with the system arrow, a system font, and a thin system-default border. It is noticeably taller and narrower-padded than its neighbours, and on mobile it eats most of the row width so the Search box is squeezed.
- **Screenshots**
  - Desktop: `bugfind-reports/evidence/alt/01-home-desktop.png`
  - Mobile: `bugfind-reports/evidence/alt/23-mobile-top.png`

#### 5. "FAILED (0)" (and other empty filter states) render a blank page
- **Repro** On desktop, click "FAILED (0)" or "BROKEN (0)" in the filter bar.
- **Expected** An empty state message: something like "No failed tests 🎉" or "0 tests match this filter".
- **Actual** The suites section goes completely empty except for the `Test suites` heading and an orphan breadcrumb `com.blockether.spel`. The user is left staring at ~700 px of whitespace with no feedback that a filter is actually applied (beyond the red pill remaining highlighted). The search-box analog (no matches) has the same problem: searching for `xyz123` also shows nothing.
- **Screenshot** `bugfind-reports/evidence/alt/18-filter-failed.png`

---

### 🟡 Medium

#### 6. Trace / Download-zip buttons look like static text, not buttons
- **Repro** Expand any test that has an attached trace. Look at the "OPEN TRACE" / "DOWNLOAD ZIP" controls.
- **Expected** A visually obvious button (solid background or stronger border + proper hover state).
- **Actual** Computed style for `.trace-launch`: `background: transparent`, `border: 1px solid rgba(0,0,0,0.1)`, `color: rgb(75,85,99)`, `font-size: 12px`, `text-transform: uppercase`. The 10% black border is nearly invisible on the `--bg-panel` off-white background, so at a glance users read "OPEN TRACE" as a label, not a button. Combined with blocker #1 this compounds: users who do notice it's clickable get a blank modal for their effort.
- **Screenshots** `bugfind-reports/evidence/alt/12-test-header-zoom.png`

#### 7. Chip / label font sizes are below the 12 px floor across the board
- **Repro** Inspect computed font-size of `.summary-chip-label`, `.suite-stat`, `.test-status-badge`, `.test-chip`, `.label-pill`, `.report-kicker`.
- **Expected** ≥12 px body text per modern accessibility guidance (and the default report’s chips all sit at 13–14 px).
- **Actual** Measured values: `.report-kicker` **10.88 px**, `.summary-chip-label` **10.4 px**, `.suite-stat` **10.4 px**, `.test-status-badge` **9.92 px**, `.test-chip` **10.4 px**, `.label-pill` **10.4 px**. At a normal desktop 1.0 zoom these are legible but feel cramped; on a 375×667 mobile at 1×, "✓ PASSED" badges and suite counts become genuinely hard to read, and the all-uppercase styling makes it worse.
- **Impact** Borderline WCAG issue (no hard minimum font size, but these violate most design-system guidance and are all-caps small which hurts legibility further). Also forces users who need to zoom to do so just to read the summary strip.
- **Screenshots** `bugfind-reports/evidence/alt/21-header.png`, `alt/23-mobile-top.png`, `alt/25-test-labels.png`

#### 8. Label pills read as one run-on string because the spacing is only 4.8 px
- **Repro** Expand a showcase test and look at the label row (`epic`, `feature`, `story`, `severity`, `tag`).
- **Expected** Enough breathing room between pills that each reads as a discrete token; default report leaves ~8 px plus slightly taller pills.
- **Actual** `.test-labels` is `display:flex; gap:4.8px`. Combined with the 10.4 px pill font, the six pills for the health-check test read visually as `APITestingHealthCheckServiceHealthVerificationblockerapihealth`. The individual background colours *are* different but the eye has to work to parse word boundaries.
- **Fix direction** Bump `gap` to 8 px and pill horizontal padding to 8 px.
- **Screenshot** `bugfind-reports/evidence/alt/12-test-header-zoom.png`

#### 9. No user-controllable theme toggle — dark mode is OS-only
- **Repro** Look for a sun/moon button anywhere on the page.
- **Expected** Either a header toggle (default renders a monitor/sun toggle in the top bar) or an explicit opt-in.
- **Actual** There is exactly one `@media (prefers-color-scheme: dark)` rule (678 chars) that redefines the `:root` CSS variables and nothing else. Users who prefer dark mode for their test reports specifically (but keep the OS in light mode, which is common for devs) cannot opt in. The default report has a theme switcher button.
- **Severity note** Dark mode itself looks great when forced (see `alt/17-dark-mode.png`, `alt/24-dark-mobile.png`) — nothing broken visually — so this is a feature-parity gap, not a rendering bug.
- **Screenshots** `bugfind-reports/evidence/alt/17-dark-mode.png`, `alt/24-dark-mobile.png`

#### 10. Suite step indentation is almost invisible for child steps
- **Repro** Expand any suite → expand any test → look at the nested step list.
- **Expected** Clear visual hierarchy (indent + guide rail) between parent step, child step, and assertion.
- **Actual** Parent step "performs full health check with detailed steps" (x=46) → child "Create API context" (x=72) → grand-child "expect: (some? ctx)" (x=97). Each level only adds ~13 px of indent, so deeply-nested step trees read as a flat list. Assertions at the bottom look like sibling steps of their `expect:` parent.
- **Screenshots** `bugfind-reports/evidence/alt/11-test-steps.png`, `alt/12-test-header-zoom.png`

---

### 🟢 Low

#### 11. Missing favicon — 404 on every page load
- **Repro** Open the alt page, check DevTools console / Network.
- **Expected** `favicon.ico` or a `<link rel="icon">` that resolves.
- **Actual** Console: `Failed to load resource: the server responded with a status of 404 () — https://blockether.github.io/favicon.ico:0:0`. No `<link rel="icon">` is emitted for the alt, so the browser falls back to a root favicon request that 404s. Default report ships a favicon (`spel` logo orb).
- **Impact** Cosmetic: tab icon is the generic page icon, plus a red 404 in every visitor's DevTools.

#### 12. Title bar kicker duplicates the h1
- **Repro** Look at the top-left of the alt header.
- **Expected** The small-caps kicker above the title should be a category / section label (as in the default, which does not use a kicker at all and renders a real h2 with the commit line).
- **Actual** The kicker reads `ALLURE REPORT` and directly below it the h1 reads `Allure Report`. It is literally the same string in two font sizes. This wastes the kicker slot — it should either be removed, or carry the commit short-SHA / run number (fixing finding #2 at the same time).
- **Code reference** `<div class="report-kicker">Allure Report</div><h1 class="report-title">Allure Report</h1>` (captured via outerHTML).
- **Screenshot** `bugfind-reports/evidence/alt/21-header.png`

---

## Out-of-scope / non-issues

- **Default report h2 gets clipped on mobile (375×667).** The default’s huge commit-line h2 overflows the viewport (right edge at 428 px vs client width 375) and is clipped by an ancestor’s `overflow:hidden`. This is a bug in the default renderer, not the alt. The alt is *correct* in not exposing that particular overflow — it just dropped the content entirely, which is finding #2 instead.
- **Tablet (768×1024) filter bar wraps onto 2–3 rows** in the alt. Expected given the 7 controls, and each row lines up; not a defect.
- **Environment grid shows 6 items in the first row, 3 in the second.** 3-column × 3-row grid, viewport cropped the second and third rows in my zoomed screenshot but structurally correct.
- **399 `<a download>` elements have empty `innerText`** per Playwright evaluate. Self-challenged: their `outerHTML` confirms the link text is "Download zip" — they only read empty because they’re inside collapsed `<details>` whose ancestors are `display:none`, so `innerText` (but not `textContent`) is empty. Not a bug.
- **`contentDocument` on the trace iframe returns `false`.** Same-origin introspection fails because the trace viewer replaces its own document with a `data:` URL — see blocker #1. This is a symptom of that bug, not an extra finding.
- **`.trace-viewer/index.html?trace=` resource returns only 1337 bytes.** That’s the trace viewer shell HTML (verified via `curl`) — the rest is lazy-loaded JS/CSS, normal.
- **Sort native `<select>` wraps its `<option>`s into the accessibility name when serialized via `spel snapshot -i`.** That’s a snapshot artifact, not a DOM issue.
- **Dark-mode rule list is only 1 CSS rule (678 chars).** It *is* sufficient because the whole theme is driven by CSS variables redefined on `:root`. Works as designed. The missing feature is just the toggle (finding #9).

---

## Coverage matrix

| Area / Page | Functional | Visual | A11y | UX | Perf | API | Audited? |
|-------------|-----------|--------|------|-----|------|-----|----------|
| Home / summary strip | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | yes |
| Filter bar (ALL/PASSED/FAILED/BROKEN) | ✓ | ✓ | ✓ | ✓ | - | - | yes |
| Search box | ✓ | ✓ | - | ✓ | - | - | yes |
| Sort dropdown | ✓ | ✓ | - | ✓ | - | - | yes |
| Expand/Collapse | ✓ | ✓ | - | ✓ | ✓ | - | yes |
| Environment section | - | ✓ | - | - | - | - | yes |
| Suite row (collapsed) | ✓ | ✓ | ✓ | - | - | - | yes |
| Test card (expanded) | ✓ | ✓ | ✓ | ✓ | - | - | yes |
| Step tree | - | ✓ | - | ✓ | - | - | yes |
| Request/response inline card | - | ✓ | - | - | - | - | yes |
| Attachments: download zip | ✓ | ✓ | - | - | - | ✓ | yes |
| Attachments: open trace modal | ✓ (BROKEN) | ✓ | - | ✓ | - | ✓ | yes |
| Dark mode | - | ✓ | - | - | - | - | yes (forced via CSS injection) |
| Mobile 375×667 | ✓ | ✓ | ✓ | ✓ | - | - | yes |
| Tablet 768×1024 | - | ✓ | - | - | - | - | yes |
| Desktop 1440×900 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | yes |

**Not audited** (noted for completeness, not blocking ship decision): global quality gates tab, global attachments tab, global errors tab (default has them; the alt does not appear to have tabs at all, which itself is a design choice worth revisiting), keyboard navigation in the filter bar, screen reader labels on status badges.
