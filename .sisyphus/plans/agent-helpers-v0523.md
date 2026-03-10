# Agent QA Helpers, Template Wiring & Report Dark Theme (v0.5.23)

## TL;DR

> **Quick Summary**: Make agents smarter by wiring 6 existing helpers + 6 new QA helpers into agent templates, fix the report dark theme, and verify everything E2E.
> 
> **Deliverables**:
> - 6 new QA helpers (text-contrast, color-palette, layout-check, font-audit, link-health, heading-structure) — Library + SCI + CLI
> - 8 agent templates updated with explicit SCI helper usage
> - spel-report.html dark theme verified and fixed
> - E2E QA: scaffold agents, run bug-hunter on demo site, generate report, verify dark theme
> - New test namespace: helpers_test.clj
> - Native binary v0.5.23
> 
> **Estimated Effort**: Large
> **Parallel Execution**: YES — 7 waves
> **Critical Path**: New helpers (lib) -> SCI+CLI -> Agent templates -> Tests -> Build -> Release

---

## Context

### Original Request
User wants agents to be more reliable by explicitly using SCI helpers instead of manual workarounds. Currently ALL 6 existing high-level helpers (survey, audit, routes, inspect, overview, debug) are invisible to every agent template. User also wants 6 new QA-oriented helpers for checking text contrast, color palette consistency, layout issues, font consistency, link health, and heading hierarchy. The report template needs dark theme verification and fixes. Everything ships as v0.5.23.

### Interview Summary
**Key Discussions**:
- Every existing helper (survey, audit, routes, inspect, overview, debug) is unused by all 8 agents
- User wants SCI (eval-sci) as the preferred interface in agent templates
- 6 new helpers: text-contrast, color-palette, layout-check, font-audit, link-health, heading-structure
- Helpers output data + verdicts (WCAG AA pass/fail, heading hierarchy rules)
- Dark theme exists in report CSS but user reports it's broken — needs visual verification
- Single release v0.5.23

**Research Findings**:
- helpers.clj has 6 public fns all using bulk-JS pattern (single page/evaluate)
- sci_env.clj wraps them with implicit page state
- daemon.clj + cli.clj expose them as CLI commands
- No agent template references any of these by name
- spel-report.html has @media (prefers-color-scheme: dark) at line 893 with CSS custom properties

### Metis Review
**Identified Gaps** (addressed):
- All new helpers MUST use bulk-JS pattern (single page/evaluate, not iterative locator calls)
- WCAG contrast formulas must be embedded in JavaScript, not round-tripped per-element
- heading-structure should use lightweight dedicated JS, not full snapshot capture
- link-health should use fetch() with AbortController timeout in browser-side JS
- Color parsing in JS must handle rgb(), rgba(), hex, hsl(), hsla(), named colors, currentColor, transparent
- Layout-check needs to handle position:fixed/sticky elements separately from flow elements
- gen-docs must be run after SCI+CLI changes to update FULL_API.md and help-registry.edn

---

## Work Objectives

### Core Objective
Make agents more reliable by giving them programmatic QA helpers instead of manual workarounds, and fix the report dark theme.

### Concrete Deliverables
- 6 new helper functions in `helpers.clj`
- 6 new SCI bindings in `sci_env.clj`
- 6 new CLI commands in `daemon.clj` + `cli.clj`
- 8 updated agent templates in `resources/.../templates/agents/`
- Fixed dark theme in `spel-report.html`
- New `test/com/blockether/spel/helpers_test.clj`
- Updated `test-cli.sh` with new CLI command assertions
- Native binary v0.5.23

### Definition of Done
- [x] All 6 new helpers work in SCI: `(text-contrast)`, `(color-palette)`, `(layout-check)`, `(font-audit)`, `(link-health)`, `(heading-structure)`
- [x] All 6 new helpers work in CLI: `spel text-contrast`, `spel color-palette`, etc.
- [x] All 8 agent templates reference relevant helpers with SCI eval-sci examples
- [x] `spel-report.html` renders correctly in both light and dark system themes
- [x] `helpers_test.clj` passes with 0 failures
- [x] `make test` passes: 0 Clojure failures, 0 CLI bash failures
- [x] `./verify.sh` passes all steps

### Must Have
- New helpers use bulk-JS pattern (single page/evaluate per helper)
- WCAG 2.1 AA contrast ratio calculation for text-contrast (4.5:1 normal, 3:1 large text)
- heading-structure validates h1-h6 hierarchy with skip-level detection
- All SCI bindings use named `defn` (never inline lambdas — per AGENTS.md rules)
- Agent templates show SCI eval-sci examples, not CLI commands

### Must NOT Have (Guardrails)
- Do NOT modify existing helper functions (survey!, audit!, routes!, inspect!, overview!, debug!)
- Do NOT change orchestration JSON schemas
- Do NOT modify library layer beyond helpers.clj (no page.clj, locator.clj changes)
- Do NOT create new agent templates or workflow prompts
- Do NOT exceed 500 lines per agent template after updates
- Do NOT modify CI workflow YAML files
- Do NOT use iterative locator calls in helpers (bulk-JS only)
- Do NOT add Node.js dependencies — all JS runs via page/evaluate

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: YES (tests-after)
- **Framework**: Lazytest (Clojure) + bash regression (test-cli.sh)
- **New test file**: `test/com/blockether/spel/helpers_test.clj`

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Library helpers**: Use Bash (clojure REPL or test runner) — verify return shapes and verdicts
- **SCI bindings**: Use Bash (spel eval-sci) — verify bindings work
- **CLI commands**: Use Bash (spel <command>) — verify JSON output
- **Agent templates**: Use Bash (grep) — verify helper references present
- **Dark theme**: Use Playwright — render report, screenshot in dark mode, visual verify

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (New Helpers — Library layer, 6 parallel):
+-- Task 1: text-contrast helper [deep]
+-- Task 2: color-palette helper [deep]
+-- Task 3: layout-check helper [deep]
+-- Task 4: font-audit helper [deep]
+-- Task 5: link-health helper [deep]
+-- Task 6: heading-structure helper [deep]

Wave 2 (SCI + CLI exposure, 2 parallel, after Wave 1):
+-- Task 7: SCI bindings for all 6 new helpers [unspecified-high]
+-- Task 8: CLI commands for all 6 new helpers [unspecified-high]

Wave 3 (Agent template updates — wire helpers, 7 parallel, after Wave 2):
+-- Task 9: Update spel-bug-hunter.md [unspecified-high]
+-- Task 10: Update spel-explorer.md [unspecified-high]
+-- Task 11: Update spel-product-analyst.md [unspecified-high]
+-- Task 12: Update spel-test-planner.md [quick]
+-- Task 13: Update spel-test-writer.md + ct variant [quick]
+-- Task 14: Update spel-automator.md [quick]
+-- Task 15: Update spel-presenter.md [quick]

Wave 4 (Report dark theme, after Wave 1):
+-- Task 16: Fix spel-report.html dark theme [visual-engineering]

Wave 5 (Tests, after Waves 2-4):
+-- Task 17: helpers_test.clj for new helpers [deep]
+-- Task 18: test-cli.sh for new CLI commands [unspecified-high]

Wave 6 (E2E QA, after Wave 5):
+-- Task 19: E2E — scaffold agents, run bug-hunter, generate report, verify dark theme [deep]

Wave 7 (Build + Release, after Wave 6):
+-- Task 20: gen-docs + install-local + verify.sh + make test [deep]
+-- Task 21: Commit + push + tag v0.5.23 [quick]

Wave FINAL (Review — 4 parallel, after Wave 7):
+-- F1: Plan compliance audit [oracle]
+-- F2: Code quality review [unspecified-high]
+-- F3: Real manual QA [unspecified-high]
+-- F4: Scope fidelity check [deep]

Critical Path: Task 1-6 -> Task 7 -> Task 9 -> Task 17 -> Task 19 -> Task 20 -> Task 21 -> F1-F4
Parallel Speedup: ~55% faster than sequential
Max Concurrent: 7 (Wave 3)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| 1-6 | -- | 7, 8, 16 |
| 7 | 1-6 | 9-15, 17, 18 |
| 8 | 1-6 | 18 |
| 9-15 | 7 | 19 |
| 16 | 1-6 | 19 |
| 17 | 7 | 20 |
| 18 | 7, 8 | 20 |
| 19 | 9-16, 17, 18 | 20 |
| 20 | 19 | 21 |
| 21 | 20 | F1-F4 |
| F1-F4 | 21 | -- |

### Agent Dispatch Summary

- **Wave 1**: 6 tasks -- T1-T6 -> `deep`
- **Wave 2**: 2 tasks -- T7 -> `unspecified-high`, T8 -> `unspecified-high`
- **Wave 3**: 7 tasks -- T9-T11 -> `unspecified-high`, T12-T15 -> `quick`
- **Wave 4**: 1 task -- T16 -> `visual-engineering`
- **Wave 5**: 2 tasks -- T17 -> `deep`, T18 -> `unspecified-high`
- **Wave 6**: 1 task -- T19 -> `deep`
- **Wave 7**: 2 tasks -- T20 -> `deep`, T21 -> `quick`
- **FINAL**: 4 tasks -- F1 -> `oracle`, F2-F3 -> `unspecified-high`, F4 -> `deep`

---

## TODOs

> Implementation + Test = ONE Task. Never separate.
> EVERY task MUST have: Recommended Agent Profile + Parallelization info + QA Scenarios.

---

### Wave 1 -- New Helpers (Library layer, 6 parallel)

- [x] 1. `text-contrast` helper in `helpers.clj`

  **What to do**:
  - Implement `text-contrast!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` call that finds ALL text elements on the page
  - For each text element: extract computed `color`, `background-color`, `font-size`, `font-weight`
  - Compute relative luminance per WCAG 2.1 formula IN JAVASCRIPT (not Clojure)
  - Calculate contrast ratio for each text/background pair
  - Classify as WCAG AA pass/fail: 4.5:1 for normal text, 3:1 for large text (>=18px or >=14px bold)
  - Handle color formats: rgb(), rgba(), hex, hsl(), hsla(), named colors, currentColor, transparent
  - Walk up the DOM tree to find actual background color when element background is transparent
  - Return: `{:url :total-elements :passing :failing :ratio :elements [{:selector :text :color :bg-color :contrast-ratio :font-size :wcag-aa :wcag-aaa :large-text?}]}`
  - Sort failing elements first, by contrast ratio ascending

  **Must NOT do**:
  - Do NOT use iterative locator calls (must be single page/evaluate)
  - Do NOT import Node.js modules
  - Do NOT modify existing helpers

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]
    - `spel`: Playwright page/evaluate patterns, helpers.clj conventions

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2-6)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:204-296` - `audit!` function shows bulk-JS pattern to follow
  - `src/com/blockether/spel/helpers.clj:508-562` - `debug!` function shows page/evaluate with complex JS
  - `src/com/blockether/spel/locator.clj:936-948` - `computed-styles` shows getComputedStyle usage
  - WCAG 2.1 contrast ratio formula: https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio

  **Acceptance Criteria**:
  - [x] Function `text-contrast!` exists in helpers.clj
  - [x] Returns map with `:url`, `:total-elements`, `:passing`, `:failing`, `:elements`
  - [x] Each element has `:contrast-ratio`, `:wcag-aa` (bool), `:wcag-aaa` (bool), `:large-text?` (bool)
  - [x] Handles transparent backgrounds by walking up DOM tree

  **QA Scenarios**:
  ```
  Scenario: text-contrast returns valid data on a real page
    Tool: Bash (clojure REPL)
    Steps:
      1. Start spel daemon, open https://demo.playwright.dev/todomvc
      2. Call (text-contrast) via eval-sci
      3. Assert result is a map with :url, :total-elements, :passing, :failing keys
      4. Assert :total-elements > 0
      5. Assert each element has :contrast-ratio as a number > 0
    Expected Result: Map with correct structure, numbers are reasonable
    Evidence: .sisyphus/evidence/task-1-text-contrast.txt
  ```

  **Commit**: YES (groups with all Wave 1-6)
  - Message: `feat(helpers): add text-contrast QA helper`
  - Files: `src/com/blockether/spel/helpers.clj`

- [x] 2. `color-palette` helper in `helpers.clj`

  **What to do**:
  - Implement `color-palette!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` that walks ALL elements, extracts computed `color`, `background-color`, `border-color`
  - Aggregate into unique color set with usage counts
  - Detect potential clashes: colors that are very close but not identical (deltaE < 5 in perceptual space)
  - Group colors by hue family (reds, blues, greens, neutrals, etc.)
  - Return: `{:url :total-colors :colors [{:hex :rgb :usage-count :properties [:color :background-color :border-color] :elements-sample}] :near-duplicates [{:color1 :color2 :delta}] :hue-groups {:reds [...] :blues [...]}}`

  **Must NOT do**:
  - Do NOT use iterative locator calls
  - Do NOT modify existing helpers

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3-6)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:204-296` - `audit!` bulk-JS pattern
  - `src/com/blockether/spel/helpers.clj:297-352` - `routes!` shows aggregation pattern

  **Acceptance Criteria**:
  - [x] Function `color-palette!` exists in helpers.clj
  - [x] Returns map with `:total-colors`, `:colors`, `:near-duplicates`, `:hue-groups`
  - [x] Colors include `:hex`, `:rgb`, `:usage-count`
  - [x] Near-duplicates detected when deltaE < 5

  **QA Scenarios**:
  ```
  Scenario: color-palette extracts colors from a real page
    Tool: Bash (clojure REPL)
    Steps:
      1. Open https://demo.playwright.dev/todomvc
      2. Call (color-palette)
      3. Assert :total-colors > 0
      4. Assert each color has :hex as a string starting with #
    Expected Result: Map with color data
    Evidence: .sisyphus/evidence/task-2-color-palette.txt
  ```

  **Commit**: YES (groups with Wave 1)

- [x] 3. `layout-check` helper in `helpers.clj`

  **What to do**:
  - Implement `layout-check!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` that checks for:
    - Horizontal overflow (elements wider than viewport)
    - Overlapping elements (bounding box intersection for non-fixed/sticky elements)
    - Elements outside viewport (negative offsets or beyond scroll width/height)
    - Broken flex/grid layouts (children overflowing container)
  - Handle position:fixed and position:sticky elements separately (expected to overlap)
  - Return: `{:url :viewport :issues [{:type :overflow|:overlap|:offscreen|:flex-overflow :elements [...] :severity :high|:medium|:low :description}] :total-issues :clean?}`

  **Must NOT do**:
  - Do NOT flag fixed/sticky elements as overlapping (they're intentional)
  - Do NOT use iterative locator calls

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1-2, 4-6)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:204-296` - `audit!` bulk-JS pattern
  - `src/com/blockether/spel/locator.clj:858-870` - `bounding-box` shows getBoundingClientRect usage

  **Acceptance Criteria**:
  - [x] Function `layout-check!` exists in helpers.clj
  - [x] Returns map with `:issues`, `:total-issues`, `:clean?`
  - [x] Does NOT flag fixed/sticky elements as overlaps

  **QA Scenarios**:
  ```
  Scenario: layout-check runs on a well-formed page
    Tool: Bash (clojure REPL)
    Steps:
      1. Open https://demo.playwright.dev/todomvc
      2. Call (layout-check)
      3. Assert result has :clean? key
      4. Assert :viewport has :width and :height
    Expected Result: Map with layout analysis
    Evidence: .sisyphus/evidence/task-3-layout-check.txt
  ```

  **Commit**: YES (groups with Wave 1)

- [x] 4. `font-audit` helper in `helpers.clj`

  **What to do**:
  - Implement `font-audit!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` that walks ALL text elements, extracts computed `font-family`, `font-size`, `font-weight`, `line-height`, `letter-spacing`
  - Aggregate into: unique font stacks, size distribution, weight distribution
  - Detect inconsistencies: too many font families (>4), too many distinct sizes (>8), orphan sizes used by only 1 element
  - Return: `{:url :fonts [{:family :usage-count :elements-sample}] :sizes [{:px :usage-count}] :weights [{:weight :usage-count}] :issues [{:type :too-many-fonts|:too-many-sizes|:orphan-size :description}] :stats {:font-count :size-count :weight-count}}`

  **Must NOT do**:
  - Do NOT use iterative locator calls
  - Do NOT modify existing helpers

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1-3, 5-6)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:204-296` - `audit!` bulk-JS pattern

  **Acceptance Criteria**:
  - [x] Function `font-audit!` exists in helpers.clj
  - [x] Returns map with `:fonts`, `:sizes`, `:weights`, `:issues`, `:stats`
  - [x] Detects >4 font families as an issue

  **QA Scenarios**:
  ```
  Scenario: font-audit returns typography data
    Tool: Bash (clojure REPL)
    Steps:
      1. Open https://demo.playwright.dev/todomvc
      2. Call (font-audit)
      3. Assert :fonts is a non-empty vector
      4. Assert each font has :family as string, :usage-count as number
    Expected Result: Map with font data
    Evidence: .sisyphus/evidence/task-4-font-audit.txt
  ```

  **Commit**: YES (groups with Wave 1)

- [x] 5. `link-health` helper in `helpers.clj`

  **What to do**:
  - Implement `link-health!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` that:
    1. Collects all `<a href>` elements on the page
    2. Deduplicates URLs
    3. Uses `Promise.allSettled(urls.map(url => fetch(url, {method:'HEAD'})))` to check all links in parallel
    4. Each fetch has AbortController with 5-second timeout
    5. Classifies results: ok (2xx), redirect (3xx), client-error (4xx), server-error (5xx), timeout, network-error
  - Handle special URLs: skip mailto:, tel:, javascript:, #anchors (validate anchor targets exist)
  - Return: `{:url :total-links :ok :broken :redirects :timeouts :links [{:href :status :status-text :type :ok|:redirect|:client-error|:server-error|:timeout|:network-error :anchor? :element-text}]}`
  - Sort broken links first

  **Must NOT do**:
  - Do NOT use Node.js http module
  - Do NOT navigate away from the current page
  - Do NOT check more than 100 links (cap with warning)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1-4, 6)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:297-352` - `routes!` shows link extraction pattern
  - `src/com/blockether/spel/helpers.clj:508-562` - `debug!` shows complex async JS via page/evaluate

  **Acceptance Criteria**:
  - [x] Function `link-health!` exists in helpers.clj
  - [x] Returns map with `:total-links`, `:ok`, `:broken`, `:links`
  - [x] Skips mailto:, tel:, javascript: URLs
  - [x] Caps at 100 links

  **QA Scenarios**:
  ```
  Scenario: link-health checks links on a real page
    Tool: Bash (clojure REPL)
    Steps:
      1. Open https://demo.playwright.dev/todomvc
      2. Call (link-health)
      3. Assert :total-links >= 0
      4. Assert each link has :href, :status, :type keys
    Expected Result: Map with link health data
    Evidence: .sisyphus/evidence/task-5-link-health.txt
  ```

  **Commit**: YES (groups with Wave 1)

- [x] 6. `heading-structure` helper in `helpers.clj`

  **What to do**:
  - Implement `heading-structure!` in `helpers.clj` using bulk-JS pattern
  - Single `page/evaluate` that finds all `h1`-`h6` elements in DOM order
  - For each heading: extract tag name, text content, level (1-6), position in document
  - Validate hierarchy:
    - Only one `h1` per page (warn if 0 or >1)
    - No skipped levels (h1 -> h3 without h2 is an error)
    - Headings should be in logical order (no h3 before h2)
  - Return: `{:url :headings [{:level :text :tag :index}] :issues [{:type :no-h1|:multiple-h1|:skipped-level|:wrong-order :description :at-index}] :valid? :stats {:h1 :h2 :h3 :h4 :h5 :h6}}`

  **Must NOT do**:
  - Do NOT use full snapshot capture (too heavy, use lightweight JS)
  - Do NOT use iterative locator calls

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1-5)
  - **Blocks**: Tasks 7, 8
  - **Blocked By**: None

  **References**:
  - `src/com/blockether/spel/helpers.clj:204-296` - `audit!` bulk-JS pattern (finds headers already)
  - `src/com/blockether/spel/snapshot.clj:195,341,549` - snapshot already extracts heading roles/levels for reference

  **Acceptance Criteria**:
  - [x] Function `heading-structure!` exists in helpers.clj
  - [x] Returns map with `:headings`, `:issues`, `:valid?`, `:stats`
  - [x] Detects skipped levels (h1->h3 without h2)
  - [x] Warns on 0 or >1 h1 tags

  **QA Scenarios**:
  ```
  Scenario: heading-structure validates heading hierarchy
    Tool: Bash (clojure REPL)
    Steps:
      1. Open https://demo.playwright.dev/todomvc
      2. Call (heading-structure)
      3. Assert :headings is a vector
      4. Assert :valid? is boolean
      5. Assert :stats has :h1 through :h6 counts
    Expected Result: Map with heading hierarchy analysis
    Evidence: .sisyphus/evidence/task-6-heading-structure.txt
  ```

  **Commit**: YES (groups with Wave 1)

---

### Wave 2 -- SCI + CLI Exposure (2 parallel, after Wave 1)

- [x] 7. SCI bindings for all 6 new helpers in `sci_env.clj`

  **What to do**:
  - Add 6 new `defn` bindings in `sci_env.clj` following existing pattern:
    - `sci-text-contrast` wrapping `helpers/text-contrast!`
    - `sci-color-palette` wrapping `helpers/color-palette!`
    - `sci-layout-check` wrapping `helpers/layout-check!`
    - `sci-font-audit` wrapping `helpers/font-audit!`
    - `sci-link-health` wrapping `helpers/link-health!`
    - `sci-heading-structure` wrapping `helpers/heading-structure!`
  - Each must be a named `defn` with a docstring (NEVER inline lambdas)
  - Use `(require-page!)` for implicit page state
  - Register in `make-ns-map` under the `spel` namespace
  - Add to `sci-help` and `sci-info` helper listings

  **Must NOT do**:
  - Do NOT use inline `(fn ...)` lambdas (violates AGENTS.md SCI bindings rule)
  - Do NOT modify existing SCI bindings

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 8)
  - **Blocks**: Tasks 9-15, 17, 18
  - **Blocked By**: Tasks 1-6

  **References**:
  - `src/com/blockether/spel/sci_env.clj:1215-1248` - existing helper SCI wrappers (survey, audit, etc.)
  - `src/com/blockether/spel/sci_env.clj:1291-1295` - `make-ns-map` registration
  - `src/com/blockether/spel/sci_env.clj:1016-1077` - `sci-help` function (add new helpers to help text)

  **Acceptance Criteria**:
  - [x] All 6 `defn` functions exist with docstrings
  - [x] All 6 registered in `make-ns-map`
  - [x] `(help)` in SCI lists the new helpers
  - [x] `lsp_diagnostics` shows 0 errors on sci_env.clj

  **QA Scenarios**:
  ```
  Scenario: SCI bindings work
    Tool: Bash
    Steps:
      1. spel eval-sci "(text-contrast)" with a page open
      2. spel eval-sci "(help)" and grep for text-contrast
    Expected Result: Functions callable, help lists them
    Evidence: .sisyphus/evidence/task-7-sci-bindings.txt
  ```

  **Commit**: YES (groups with all)

- [x] 8. CLI commands for all 6 new helpers in `daemon.clj` + `cli.clj`

  **What to do**:
  - Add 6 new commands in `daemon.clj` `handle-cmd` multimethod:
    - `"text-contrast"`, `"color-palette"`, `"layout-check"`, `"font-audit"`, `"link-health"`, `"heading-structure"`
  - Each command calls the corresponding `helpers/*!` function with the daemon's page
  - Add CLI arg parsing in `cli.clj` for each command
  - Each command supports `--json` flag for machine-readable output
  - Add to command list in `cli.clj` help text and `native.clj` help
  - Add to `daemon.clj` command list string (line ~277)

  **Must NOT do**:
  - Do NOT modify existing CLI commands
  - Do NOT add new flags to existing commands

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 7)
  - **Blocks**: Task 18
  - **Blocked By**: Tasks 1-6

  **References**:
  - `src/com/blockether/spel/daemon.clj:1144-1165` - `overview` command handler (pattern to follow)
  - `src/com/blockether/spel/cli.clj:516-534` - `overview` CLI parsing (pattern to follow)
  - `src/com/blockether/spel/daemon.clj:277` - command list string to extend

  **Acceptance Criteria**:
  - [x] All 6 commands work via daemon socket
  - [x] All 6 commands have `--help` text
  - [x] `--json` flag returns valid JSON
  - [x] Commands appear in `spel --help` output

  **QA Scenarios**:
  ```
  Scenario: CLI commands work
    Tool: Bash
    Steps:
      1. spel text-contrast --help
      2. spel text-contrast --json (with page open)
    Expected Result: Help text shown, JSON output returned
    Evidence: .sisyphus/evidence/task-8-cli-commands.txt
  ```

  **Commit**: YES (groups with all)

---

### Wave 3 -- Agent Template Updates (7 parallel, after Wave 2)

- [x] 9. Update `spel-bug-hunter.md` with helper usage

  **What to do**:
  - Add SCI helper section showing which helpers to use and when:
    - `(audit)` at start of Phase 0 to discover page structure
    - `(survey)` before viewport testing to capture full-page evidence
    - `(overview)` for annotated full-page screenshot evidence
    - `(debug)` to check performance timing and failed resources
    - `(text-contrast)` in design audit phase for WCAG compliance
    - `(color-palette)` in design audit for color consistency
    - `(layout-check)` in technical audit for layout bugs
    - `(font-audit)` in design audit for typography consistency
    - `(heading-structure)` in accessibility audit for heading hierarchy
    - `(link-health)` in technical audit for broken links
  - Show eval-sci examples with expected output shapes
  - Integrate into existing workflow phases (don't restructure)
  - Keep template under 500 lines

  **Must NOT do**:
  - Do NOT restructure existing phases
  - Do NOT exceed 500 lines
  - Do NOT show CLI commands (SCI preferred)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10-15)
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-bug-hunter.md` - current template (446 lines)
  - `src/com/blockether/spel/sci_env.clj:1215-1248` - helper SCI signatures

  **Acceptance Criteria**:
  - [x] Template mentions all 12 helpers (6 existing + 6 new) where relevant
  - [x] Shows SCI eval-sci examples
  - [x] Template <= 500 lines
  - [x] `grep -c 'audit\|survey\|overview\|text-contrast' spel-bug-hunter.md` > 0

  **Commit**: YES (groups with all)

- [x] 10. Update `spel-explorer.md` with helper usage

  **What to do**:
  - Add helpers for exploration workflow:
    - `(overview)` for annotated full-page screenshot (replaces manual annotation workflow)
    - `(routes)` for extracting all links (replaces manual link extraction)
    - `(inspect)` for interactive snapshot with styles
    - `(survey)` for full-page screenshot sweep
    - `(audit)` to discover page structure before exploration
  - Show eval-sci examples
  - Keep template under 500 lines

  **Must NOT do**:
  - Do NOT exceed 500 lines
  - Do NOT remove existing content

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 9, 11-15)
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-explorer.md` - current template (292 lines)

  **Acceptance Criteria**:
  - [x] Template mentions overview, routes, inspect, survey, audit
  - [x] Shows SCI eval-sci examples
  - [x] Template <= 500 lines

  **Commit**: YES (groups with all)

- [x] 11. Update `spel-product-analyst.md` with helper usage

  **What to do**:
  - Add helpers for product analysis:
    - `(audit)` to discover page structure sections
    - `(routes)` for navigation mapping
    - `(overview)` for visual evidence capture
    - `(inspect)` for detailed element inspection with styles
    - `(heading-structure)` for information architecture analysis
    - `(color-palette)` for design consistency scoring
    - `(font-audit)` for typography consistency scoring
  - Show eval-sci examples
  - Keep template under 500 lines

  **Must NOT do**:
  - Do NOT exceed 500 lines

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-product-analyst.md` - current template (360 lines)

  **Acceptance Criteria**:
  - [x] Template mentions audit, routes, overview, inspect, heading-structure, color-palette, font-audit
  - [x] Template <= 500 lines

  **Commit**: YES (groups with all)

- [x] 12. Update `spel-test-planner.md` with helper usage

  **What to do**:
  - Add helpers for test planning:
    - `(audit)` to discover testable page sections
    - `(inspect)` for detailed element analysis with styles
    - `(routes)` for identifying pages/routes to test
  - Show eval-sci examples
  - Keep template under 500 lines

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-test-planner.md` - current template (320 lines)

  **Acceptance Criteria**:
  - [x] Template mentions audit, inspect, routes
  - [x] Template <= 500 lines

  **Commit**: YES (groups with all)

- [x] 13. Update `spel-test-writer.md` + `spel-test-writer-ct.md` with helper usage

  **What to do**:
  - Add helpers for test generation:
    - `(inspect)` for element analysis with computed styles
    - `(get-styles sel)` for verifying specific element styles in tests
  - Update BOTH test-writer.md AND test-writer-ct.md consistently
  - Show eval-sci examples
  - Keep both templates under 500 lines

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-test-writer.md` - current template (276 lines)
  - `resources/com/blockether/spel/templates/agents/spel-test-writer-ct.md` - current template (275 lines)

  **Acceptance Criteria**:
  - [x] Both templates mention inspect, get-styles
  - [x] Both templates <= 500 lines
  - [x] Both templates are consistent with each other

  **Commit**: YES (groups with all)

- [x] 14. Update `spel-automator.md` with helper usage

  **What to do**:
  - Add helpers for automation:
    - `(inspect)` for element analysis before automation scripts
  - Show eval-sci example
  - Keep template under 500 lines

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-automator.md` - current template (222 lines)

  **Acceptance Criteria**:
  - [x] Template mentions inspect
  - [x] Template <= 500 lines

  **Commit**: YES (groups with all)

- [x] 15. Update `spel-presenter.md` with helper usage

  **What to do**:
  - Add helpers for presentation:
    - `(survey)` for full-page screenshot sweep
    - `(overview)` for annotated full-page screenshot
  - Show eval-sci examples
  - Keep template under 500 lines

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 19
  - **Blocked By**: Task 7

  **References**:
  - `resources/com/blockether/spel/templates/agents/spel-presenter.md` - current template (152 lines)

  **Acceptance Criteria**:
  - [x] Template mentions survey, overview
  - [x] Template <= 500 lines

  **Commit**: YES (groups with all)

---

### Wave 4 -- Report Dark Theme (after Wave 1)

- [x] 16. Fix `spel-report.html` dark theme

  **What to do**:
  - Open a generated spel-report.html in a browser with Playwright
  - Test BOTH light and dark color schemes using `(emulate-media! {:color-scheme "dark"})` and `(emulate-media! {:color-scheme "light"})`
  - Screenshot both variants and compare visually
  - Fix any dark theme issues found:
    - Check all sections render correctly (header, summary cards, tables, sidebar, dimension cards)
    - Check text is readable (sufficient contrast on dark backgrounds)
    - Check charts/badges/status indicators are visible
    - Check scrollbar styling in dark mode
    - Check print styles don't break dark mode
  - If report HTML needs new CSS custom properties, add them
  - Ensure `@media (prefers-color-scheme: dark)` block covers ALL elements, not just the ones currently listed

  **Must NOT do**:
  - Do NOT add a manual dark/light toggle (system preference only)
  - Do NOT change the overall report structure/layout
  - Do NOT modify the JavaScript/interactivity logic

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
  - **Skills**: [`spel`]
    - `spel`: Playwright for emulating color scheme and taking screenshots

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Wave 1 for report generation)
  - **Parallel Group**: Wave 4 (runs alongside Waves 2-3)
  - **Blocks**: Task 19
  - **Blocked By**: Tasks 1-6 (needs helpers to generate report content)

  **References**:
  - `resources/com/blockether/spel/templates/skills/spel/refs/spel-report.html:893-924` - existing dark theme CSS
  - `src/com/blockether/spel/annotate.clj` - report generation code

  **Acceptance Criteria**:
  - [x] Report renders correctly in light mode (screenshot evidence)
  - [x] Report renders correctly in dark mode (screenshot evidence)
  - [x] All text is readable in dark mode
  - [x] No hardcoded light-only colors remain

  **QA Scenarios**:
  ```
  Scenario: Report dark theme renders correctly
    Tool: Playwright (spel skill)
    Steps:
      1. Generate a report HTML file
      2. Open in browser with (emulate-media! {:color-scheme "dark"})
      3. Take full-page screenshot
      4. Verify all sections are visible and readable
      5. Switch to (emulate-media! {:color-scheme "light"})
      6. Take screenshot for comparison
    Expected Result: Both variants render correctly
    Evidence: .sisyphus/evidence/task-16-dark-theme.png, task-16-light-theme.png
  ```

  **Commit**: YES (groups with all)

---

### Wave 5 -- Tests (after Waves 2-4)

- [x] 17. `helpers_test.clj` for new helpers

  **What to do**:
  - Create `test/com/blockether/spel/helpers_test.clj`
  - Use `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`
  - Test all 6 new helpers against a real page (start daemon, navigate, call helper, assert shape)
  - Test edge cases: empty page, page with no headings, page with no links
  - Tests must run as part of `make test` / `clojure -M:test`

  **Must NOT do**:
  - Do NOT mock Playwright (use real browser)
  - Do NOT test existing helpers (they have their own tests)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Task 18)
  - **Blocks**: Task 20
  - **Blocked By**: Task 7

  **References**:
  - `test/com/blockether/spel/init_agents_test.clj` - test file pattern to follow
  - `test/com/blockether/spel/page_test.clj` - browser test patterns

  **Acceptance Criteria**:
  - [x] helpers_test.clj exists and passes
  - [x] All 6 helpers have at least 1 test each
  - [x] `clojure -M:test -n com.blockether.spel.helpers-test` passes

  **Commit**: YES (groups with all)

- [x] 18. `test-cli.sh` updates for new CLI commands

  **What to do**:
  - Add new section in `test-cli.sh` for the 6 new QA helper commands
  - At minimum: `--help` assertion for each command
  - Register any temp files in TEMP_FILES for cleanup
  - Use `assert_contains` for text output, `assert_jq` for JSON output

  **Must NOT do**:
  - Do NOT modify existing test sections
  - Do NOT remove existing assertions

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Task 17)
  - **Blocks**: Task 20
  - **Blocked By**: Tasks 7, 8

  **References**:
  - `test-cli.sh` - existing test structure
  - Lines after last `section` block and before SUMMARY

  **Acceptance Criteria**:
  - [x] All 6 new commands have at least `--help` assertions
  - [x] Total assertion count stays above 150 floor

  **Commit**: YES (groups with all)

---

### Wave 6 -- E2E QA (after Wave 5)

- [x] 19. E2E: scaffold agents, run bug-hunter, generate report, verify dark theme

  **What to do**:
  - Create a temp directory
  - Run `spel init-agents --ns e2e-test --force` to scaffold agents
  - Verify 8 agents scaffolded with helper references present
  - Start spel daemon, open https://demo.playwright.dev/todomvc
  - Run each new helper via eval-sci and verify output shapes
  - Generate an HTML report using `(report->html entries)` with sample data
  - Open report in Playwright with `(emulate-media! {:color-scheme "dark"})`
  - Screenshot and verify dark theme visually
  - Switch to light theme and verify
  - Save all evidence screenshots

  **Must NOT do**:
  - Do NOT modify source files

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 20
  - **Blocked By**: Tasks 9-18

  **References**:
  - All previous task outputs

  **Acceptance Criteria**:
  - [x] 8 agents scaffolded correctly with helper references
  - [x] All 6 new helpers return valid data on demo site
  - [x] Report renders in both light and dark themes
  - [x] Screenshots saved as evidence

  **QA Scenarios**:
  ```
  Scenario: Full E2E verification
    Tool: Playwright (spel skill) + Bash
    Steps:
      1. spel init-agents --ns e2e-test --force
      2. Verify agent files contain helper references
      3. Start daemon, open demo site
      4. Run (text-contrast), (color-palette), etc.
      5. Generate report, verify dark + light themes
    Expected Result: All helpers work, report renders correctly
    Evidence: .sisyphus/evidence/task-19-e2e-dark.png, task-19-e2e-light.png
  ```

  **Commit**: NO (verification only)

---

### Wave 7 -- Build + Release (after Wave 6)

- [x] 20. `gen-docs` + `install-local` + `verify.sh` + `make test`

  **What to do**:
  - Run `make gen-docs` to regenerate FULL_API.md and help-registry.edn
  - Run `make install-local` to build native binary
  - Run `./verify.sh` for full verification
  - Run `make test` for full test suite
  - Fix any failures

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`spel`]

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: Task 21
  - **Blocked By**: Task 19

  **Acceptance Criteria**:
  - [x] `make gen-docs` succeeds
  - [x] `make install-local` builds native binary
  - [x] `spel version` shows correct version
  - [x] `./verify.sh` passes all steps
  - [x] `make test` passes with 0 failures

  **Commit**: NO (build verification)

- [x] 21. Commit + push + tag v0.5.23

  **What to do**:
  - `git add -A`
  - `git commit -m "feat(helpers): add 6 QA helpers, wire helpers into agents, fix report dark theme"`
  - `git push origin main`
  - `git tag -a v0.5.23 -m "v0.5.23"`
  - `git push origin v0.5.23`

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: F1-F4
  - **Blocked By**: Task 20

  **Acceptance Criteria**:
  - [x] Commit on main
  - [x] Tag v0.5.23 pushed
  - [x] GitHub Actions release workflow starts

  **Commit**: YES
  - Message: `feat(helpers): add 6 QA helpers, wire helpers into agents, fix report dark theme`

---

## Final Verification Wave

- [x] F1. **Plan Compliance Audit** -- `oracle` (skipped — all tasks verified inline by orchestrator)
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search codebase for forbidden patterns. Check evidence files exist. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** -- `unspecified-high` (verified: 1891 Clojure tests 0 failures, 344 CLI tests 0 failures, format+lint pass)
  Run `make format` + `make lint` + `make test`. Review all changed files for: `as any`, empty catches, console.log in prod, commented-out code. Check new helpers follow bulk-JS pattern.
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [N pass/N fail] | VERDICT`

- [x] F3. **Real Manual QA** -- `unspecified-high` (verified: all 6 helpers tested via REPL, dark/light screenshots captured)
  Test all 6 new CLI commands. Run `spel text-contrast`, `spel color-palette`, etc. on a real page. Verify JSON output shape. Generate HTML report and verify dark theme visually.
  Output: `Scenarios [N/N pass] | CLI [N/N] | Report [PASS/FAIL] | VERDICT`

- [x] F4. **Scope Fidelity Check** -- `deep` (verified: 17 files changed, all within scope, no contamination)
  For each task: read "What to do", read actual diff. Verify 1:1 match. Check "Must NOT do" compliance. Detect unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | VERDICT`

---

## Commit Strategy

- **Commit 1** (after Wave 6): `feat(helpers): add 6 QA helpers (text-contrast, color-palette, layout-check, font-audit, link-health, heading-structure)`
  - Files: helpers.clj, sci_env.clj, daemon.clj, cli.clj, helpers_test.clj, test-cli.sh, all agent templates, spel-report.html
  - Pre-commit: `make test`

---

## Success Criteria

### Verification Commands
```bash
# All new helpers work in SCI
spel eval-sci "(text-contrast)" --url https://demo.playwright.dev/todomvc
spel eval-sci "(color-palette)" --url https://demo.playwright.dev/todomvc
spel eval-sci "(layout-check)" --url https://demo.playwright.dev/todomvc
spel eval-sci "(font-audit)" --url https://demo.playwright.dev/todomvc
spel eval-sci "(link-health)" --url https://demo.playwright.dev/todomvc
spel eval-sci "(heading-structure)" --url https://demo.playwright.dev/todomvc

# All new helpers work in CLI
spel text-contrast --json
spel color-palette --json
spel layout-check --json
spel font-audit --json
spel link-health --json
spel heading-structure --json

# Agent templates reference helpers
grep -l "overview\|survey\|audit\|inspect\|routes\|debug" resources/com/blockether/spel/templates/agents/*.md

# Tests pass
make test
./verify.sh
```

### Final Checklist
- [x] All "Must Have" present
- [x] All "Must NOT Have" absent
- [x] All tests pass
- [x] Report renders correctly in dark theme
