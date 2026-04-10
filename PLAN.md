# Plan — alternative Allure report: fix 12 findings from the bug hunt

Source: `OBSERVATIONS.md` at project root.
Primary file: `src/com/blockether/spel/spel_allure_alternative_html_report.clj` (the alt report renderer).
Secondary: `src/com/blockether/spel/allure_reporter.clj` (`inject-trace-viewer-prewarm!` and friends, shared with the default report shell).

Every finding maps to a concrete code site. Line numbers below come from a read at HEAD.

---

## 🔴 Blockers

### 1. "Open Trace" modal renders blank

**Where:**
- Modal markup + `openTraceModal` JS: `spel_allure_alternative_html_report.clj:1443–1508`
- SW prewarm shared with default: `allure_reporter.clj:683–716`
- Modal CSS: `.trace-modal` / `.trace-frame` in the `<style>` block around `spel_allure_alternative_html_report.clj:1100–1180`

**What I don't yet know:** the bug-hunter saw the outer iframe pointing at a `data:text/html;base64,PGJvZHk+PC9ib2R5Pg==` URL *inside* `page.frames()`. That is very likely the Playwright trace viewer's **inner snapshot iframe**, not our outer `<iframe id="traceFrame">`. The outer frame might be loading the viewer shell correctly, but the viewer's inner snapshot iframe cannot fetch the trace because:

- (a) the service worker's `scope` is `./trace-viewer/` registered from the top page, but when the trace viewer is rendered inside our iframe its effective scope base changes; the fetch for `contexts?...` misses the SW and returns the report's index.html instead of trace data, **or**
- (b) the `.trace-modal` / `.trace-frame` CSS renders with zero height until a layout pass that never triggers, **or**
- (c) the split-trace `blob:` URL path builds a viewer URL (`trace-viewer/index.html?trace=<encodeURIComponent(blob:...)>`) that the SW can't resolve because blob URLs aren't in scope.

**Approach — diagnose before fixing:**

1. Open `/544/alternative/` in real Chrome with DevTools > Application > Service Workers panel. Confirm the SW is `activated and running` on `./trace-viewer/`.
2. Click an `Open Trace` button. In the Network panel, filter `trace-viewer` and verify the sequence: `index.html` → `sw.bundle.js` → `contexts?trace=…`. If `contexts?…` returns HTML instead of trace data, the SW is not intercepting (symptom of the single-trace case).
3. Test the iframe size: in the Elements panel, pick `iframe#traceFrame` and check computed height. If 0, it's a CSS bug.
4. Test the split-trace path separately (test with `SPEL_REPORT_TRACE_CHUNK_BYTES=1048576` so chunking activates) — this uses a `blob:` URL which the SW can **definitely** not intercept unless the viewer already reads `?trace=blob:…` directly.

**Fix A — if (a):** register the SW from the parent page and ALSO call `navigator.serviceWorker.ready` before letting `openTraceModal` set `frame.src`. If the Playwright viewer ships a "prewarm" message channel, post it the blob/URL through `postMessage` instead of via query string.

**Fix B — if (b):** add explicit `height: 100%; min-height: 600px;` on `.trace-frame` and ensure `.trace-modal.show` is `display: flex; flex-direction: column`.

**Fix C — if (c):** for the split-trace blob path, *do not* pass the blob through the URL. Instead, open the trace viewer at `trace-viewer/index.html` (no `?trace=`), then `postMessage({trace: blobUrl})` to the viewer once it signals ready. Playwright's viewer already supports a `message`-based trace handoff — verify via the viewer's source in `copy-trace-viewer!`.

**Acceptance:** single-trace (`default/trace.zip`) and split-trace (`SPEL_REPORT_TRACE_CHUNK_BYTES=1048576`) both render the full viewer (actions, timeline, screenshots) inside our modal at 1440×900 and 375×667.

---

## 🟠 High

### 2. Run metadata (commit SHA, author, date, PR/run link) dropped from header

**Where:**
- Header HTML: `spel_allure_alternative_html_report.clj:1565–1584`
- `load-environment` already reads `environment.properties` at line 38–61
- `ci.clj` writes commit metadata to `.allure-history.jsonl`, but the DEFAULT report consumes `executor.json` (Allure's standard file, dropped in the results dir by CI)

**Approach:**
1. Add `load-executor` helper next to `load-environment` that reads `<results-dir>/executor.json`. Standard Allure shape: `{name, type, url, buildOrder, buildName, buildUrl, reportUrl, reportName}`.
2. Also accept an extended shape we already populate in `ci.clj`: `commit_sha`, `commit_author`, `commit_subject`, `commit_ts`, `run_url`. Either extend `executor.json` with those fields or add them to `environment.properties` as `commit.sha`, `commit.author`, etc. (env.properties is simpler — no new file).
3. Update `generate!` opts to accept `:executor` map pass-through, and have the CLI `report-html` command populate it from `environment.properties` if present.
4. Render the new header (replaces finding #12 kicker-dup at the same time):

   ```
   <header class="report-header">
     <div class="report-header-main">
       <div class="report-kicker">#<build-order> · <short-sha> · <author></div>
       <h1 class="report-title"><commit-subject-or-title></h1>
       <div class="report-subtitle">
         <time datetime="..."><formatted-date></time>
         · <a href="<run-url>">run #<build-order></a>
       </div>
     </div>
     <div class="summary-chips">...</div>
   </header>
   ```
5. Also set `<title>` to `<short-sha> · <commit-subject>` so multi-tab comparisons work.

**Acceptance:** header shows commit SHA (click-to-copy), author, subject, timestamp, and a linked run chip when the data is present; falls back to the current "Allure Report" static kicker when it's absent.

### 3. DOM size ~65k elements vs default's ~603

**Where:**
- Main suite-rendering loop: `spel_allure_alternative_html_report.clj` around `render-suite` / `render-test-card` (roughly 400–1100 based on earlier grep)

**Approach — progressive / lazy render without a full virtualization lib:**
1. Render **only** the suite headers + collapsed `<details>` for each suite at SSR time. Leave each `<details>` *empty* except for a `data-suite-id` attribute and a placeholder spinner div.
2. Emit a JSON blob (`window.__SPEL_SUITES__`) at the bottom of the body containing `{suite-id → HTML string of tests}`. Compressed via base64-encoded LZString or just a plain JSON string (test content is ~1–2 MB uncompressed for 2196 tests — acceptable).
3. Attach a `toggle` listener on each `<details>`: on first open, hydrate the placeholder from the JSON blob and cache the result. Never re-render.
4. Keep "Expand all" working by iterating suites in `requestIdleCallback` chunks of ~10.

**Alternative if #3.1 is too big a rewrite:** emit the placeholder + `data-suite-json` base64 on each `<details>` element directly (no global blob). Slightly larger HTML (each suite repeats its own data) but no global index; renderer stays a pure string concat.

**Acceptance:** initial `document.querySelectorAll("*").length` < 2000 on a 2196-test report. Expanding any single suite still works. "Expand all" doesn't freeze the tab. Scroll stays smooth on a 4-core machine at 4× CPU slowdown.

### 4. Filter bar's Sort dropdown is a native `<select>` — breaks visual language

**Where:** `spel_allure_alternative_html_report.clj:1596` — `<select id="sortSelect" class="toolbar-sort">`

**Approach:**
1. Replace the `<select>` with a pill button that opens a custom menu:
   ```html
   <div class="toolbar-sort" data-sort="status">
     <button type="button" class="filter-btn" aria-haspopup="menu" aria-expanded="false">
       Sort: Status ▾
     </button>
     <ul class="toolbar-sort-menu" role="menu" hidden>
       <li role="menuitem" data-value="status">Status</li>
       <li role="menuitem" data-value="longest">Longest first</li>
       ...
     </ul>
   </div>
   ```
2. Reuse `.filter-btn` styling so the Sort button visually matches the ALL/PASSED/FAILED pills immediately to its right.
3. Wire click → toggle menu visibility, click-outside → close, `Esc` → close, keyboard arrow nav → move highlight.
4. Keep the existing `sortSuites()` JS entry point; just drive it from `data-value` of the clicked `<li>`.

**Acceptance:** on desktop the Sort control looks like part of the pill row. On mobile it does **not** eat the width of the Search box. Keyboard navigation works (Tab to focus, Enter to open, Arrow to change).

### 5. Empty filter states render a blank page

**Where:** `spel_allure_alternative_html_report.clj:1617, 1636` — the current `.empty-state` markup only kicks in for "no env" and "no results". There is no empty-state rendering *after* a filter hides all suites.

**Approach:**
1. Add a sibling element `<div id="suitesEmptyState" class="empty-state" hidden>` right after the suites container with copy like:
   ```
   <strong>No tests match the current filter.</strong>
   <p>Try clearing the search box or clicking <kbd>ALL</kbd>.</p>
   <button type="button" class="filter-btn" data-reset-filters>Clear filters</button>
   ```
2. In the filter/search JS, after updating visibility of each `.suite-section`, count the visible ones. If zero, unhide `#suitesEmptyState` and hide the suites container (`#suitesRoot.hidden`). Otherwise reverse.
3. Wire `[data-reset-filters]` → reset filter pills to ALL + clear search box value + re-run the filter.

**Acceptance:** clicking FAILED (0) with no failures shows the empty-state card instead of ~700 px of whitespace. Searching `xyz123` with no matches shows the same card.

---

## 🟡 Medium

### 6. Trace / Download buttons look like static text

**Where:** `.trace-launch` / `.attachment-link-button` in the `<style>` block (~line 920–960; verify via grep).

**Approach:**
- Bump base state: `background: var(--bg-accent)` (already exists for pills), `border: 1px solid var(--accent)`, `color: var(--text-primary)`, `font-weight: 600`.
- Hover: slightly darker background + cursor pointer + subtle box-shadow.
- Active/focus-visible: 2 px accent outline.
- Keep `font-size: 0.75rem` (12 px) — but ensure uppercase is **letter-spaced** enough to not read as a label: `letter-spacing: 0.04em`.

**Acceptance:** at 1× zoom, `OPEN TRACE` is visually a button without hover — not text.

### 7. Font sizes below 12 px across chips/badges/pills

**Where:** `spel_allure_alternative_html_report.clj` style block:
- `.report-kicker` — line ~653 (`0.68rem` → 10.88 px)
- `.summary-chip-label` — line ~690 (`0.65rem` → 10.4 px)
- `.suite-stat` / `.test-chip` — line ~897–900 (`0.65rem`)
- `.test-status-badge` — line ~943–945 (`0.62rem` → 9.92 px)
- `.label-pill` — (to find)

**Approach:**
Introduce a design token for "micro" text and bump the floor to 0.75 rem (12 px):
```css
:root { --text-micro: 0.75rem; --text-small: 0.8125rem; /* 13 px */ --text-body: 0.875rem; }
```
Then:
- `.report-kicker` → `var(--text-micro); letter-spacing: 0.1em;`
- `.summary-chip-label` → `var(--text-micro);`
- `.summary-chip-value` → 1.125rem (18 px) — already bold, grow the number not the label.
- `.suite-stat`, `.test-chip`, `.label-pill` → `var(--text-micro);`
- `.test-status-badge` → `var(--text-micro); font-weight: 700;`

Verify computed `px` values after rebuild.

**Acceptance:** no text in the chip/badge/pill family is smaller than 12 px. Default-compared on the same 375×667 viewport, legibility is equal or better.

### 8. Label pills run into a wall of text

**Where:** `.test-labels { display: flex; gap: 4.8px }` (grep for `test-labels` in the style block).

**Approach:**
- `gap: 8px` (half-rem)
- Label pill horizontal padding: `0 8px`
- Add a very subtle 1 px inner border `box-shadow: inset 0 0 0 1px rgba(0,0,0,0.04)` so adjacent different-colour pills stay visually separated even on a busy background.

**Acceptance:** the six-pill label row for a showcase test reads as six tokens at 1× zoom on desktop.

### 9. No theme toggle — dark mode is OS-only

**Where:** header HTML at line 1565 + CSS variables at `:root` (line 564) and `@media (prefers-color-scheme: dark)` (line 589).

**Approach:**
1. Add `data-theme` attribute at the `<html>` tag, default `auto`.
2. CSS: scope the dark-mode variables under `html[data-theme="dark"]` **and** `html[data-theme="auto"] @media (prefers-color-scheme: dark)`. Light is the default under `html[data-theme="light"]` and `html[data-theme="auto"] @media (prefers-color-scheme: light)`.
3. Add a 3-state toggle button in the header (next to the summary chips): `auto / light / dark`. Icon pattern: `⦾` / `☀` / `☾`. Persist choice in `localStorage.theme`.
4. On load, `document.documentElement.dataset.theme = localStorage.theme || 'auto'`.

**Acceptance:** clicking the toggle cycles auto → light → dark → auto, stylesheet swaps immediately, choice persists across reloads.

### 10. Step indentation nearly invisible (~13 px / level)

**Where:** `.step-children` / `.test-step` CSS (grep for `step-children` in the style block).

**Approach:**
- Per-level indent: bump from implicit ~13 px to `calc(1.25rem + 0.5rem * var(--step-depth, 0))` using a CSS custom property set via inline style on each step: `style="--step-depth: 2"`.
- Add a left guide rail: `border-left: 1px solid var(--border); padding-left: 0.75rem;` on `.step-children`.
- Use `::before` on `.test-step` to draw a short horizontal tick connecting the step to the rail. Purely cosmetic but makes the hierarchy obvious.

**Acceptance:** looking at a test with a 3-level step tree, you can follow the tree visually without reading the labels.

---

## 🟢 Low

### 11. Missing favicon — 404 in every console

**Where:** `<head>` block at `spel_allure_alternative_html_report.clj:1553–1563`

**Approach:**
- Inline the favicon as a base64 `data:` URL so the report stays self-contained. A 32×32 PNG of the spel orb is ~1 KB base64:
  ```html
  <link rel="icon" type="image/png" href="data:image/png;base64,AAAA...">
  ```
- Source PNG lives (or should live) in `resources/com/blockether/spel/templates/skills/spel/icon.png` — if not, steal it from `allure_reporter.clj`'s packaged assets or generate a simple SVG and inline that instead.

**Acceptance:** no 404 for `/favicon.ico` in the console. Tab shows the spel icon.

### 12. Title kicker duplicates the h1

**Where:** `spel_allure_alternative_html_report.clj:1567–1568`:
```clojure
"<div class=\"report-kicker\">" (html-escape kicker) "</div>
 <h1 class=\"report-title\">" (html-escape title) "</h1>
```

**Approach:** this is **resolved for free** by finding #2 — when we put commit metadata in the kicker slot, it no longer reads "ALLURE REPORT / Allure Report". If #2 is not shipped in the same pass, then as a temporary fix: if `kicker == title`, hide the kicker (`when (not= kicker title)`).

**Acceptance:** kicker and title never contain the same string.

---

## Order of operations

Group by touched region so we rebuild natively once per logical step.

**Pass 1 — CSS-only (cheapest, ship-ready ASAP):**
1. #7 font sizes — bump the micro tokens, verify computed `px` values via a headless browser check.
2. #8 label pill gap + padding.
3. #10 step indent + guide rail.
4. #6 trace button affordance.
5. #12 kicker dedup (quick conditional).

**Pass 2 — HTML / header:**
6. #2 executor.json + commit metadata header (also resolves #12 permanently).
7. #11 inline favicon.

**Pass 3 — JS / interactivity:**
8. #5 empty-state for filters.
9. #4 custom Sort dropdown.
10. #9 theme toggle.

**Pass 4 — architecture:**
11. #3 lazy suite hydration.

**Pass 5 — blocker diagnosis + fix:**
12. #1 trace modal. Diagnose first, then apply Fix A, B, or C depending on what DevTools shows. This is last because it requires live Chrome debugging and should not block the cosmetic fixes that can ship today.

After each pass:
- `clojure -M:test -n com.blockether.spel.spel-allure-alternative-html-report-test` — existing snapshot tests.
- Regenerate a report against real allure-results: `spel report-html .allure-results/ target/report-test/` (or whatever the CLI is called — verify in `native.clj`).
- Open in Chrome, sanity-check the finding is gone and no regressions in adjacent areas.

## Testing additions

New test cases in `test/com/blockether/spel/spel_allure_alternative_html_report_test.clj`:
- Given an `executor.json` with commit metadata, the rendered header contains the SHA, author, and run URL.
- Given a 100-test input, the rendered HTML has fewer than `2 × N` top-level elements (virtualization smoke test).
- Given an empty `environment.properties`, the header still renders (regression guard for #2).
- Snapshot test: the header HTML matches a fixture string (catch kicker regressions).
- Unit test for the filter empty-state JS — verify the DOM element exists and is hidden by default.

## Out of scope for this round

- Replacing `<details>` with a fully-virtualized viewport (needs an intersection observer rewrite).
- Keyboard nav polish on filter pills (noted in OBSERVATIONS §164 as not-audited).
- Quality-gates / global attachments / global errors tabs (the alt doesn't have tabs; separate design discussion).
- Screen-reader labeling pass — warrants its own accessibility audit round.
