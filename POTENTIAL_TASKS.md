# POTENTIAL TASKS — spel vs agent-browser Feature Parity

> Definitive cross-reference of spel 0.5.0 (100 daemon actions, ~460 SCI bindings, full Playwright Java library)
> vs agent-browser 0.15.1 (~143 commands). Generated March 1, 2026.

---

## Executive Summary

| Category | Count | Notes |
|----------|-------|-------|
| **Full CLI parity** | ~105 | spel daemon command ↔ agent-browser command |
| **SCI/Library only** | ~15 | Available via `eval-sci` or Clojure library, no CLI command needed |
| **Truly missing** | ~23 | Not in spel at any level |
| **Pareto-optimal** | **3** | Worth implementing (clear the 20/80 bar) |
| **Not worth it** | 20 | Too niche, already covered by alternatives, or wrong fit |

**Bottom line**: spel has **~120/143 feature parity** when you count SCI + library coverage.
The previous version of this file incorrectly listed 13 "Tier 1 quick wins" that **already existed**
in spel's library and SCI layers. This rewrite corrects the record.

---

## Features That Already Exist (Previously Mislabeled as "Missing")

These were listed as missing in the initial analysis. They all exist in spel's library (`page.clj`,
`locator.clj`, `core.clj`, `options.clj`) and most are exposed in SCI (`sci_env.clj`):

| agent-browser command | spel equivalent | Level |
|---|---|---|
| `setcontent` | `page/set-content!`, `sci-set-content!` | Library + SCI |
| `innertext` | `locator/inner-text`, `sci-inner-text` | Library + SCI |
| `innerhtml` | `locator/inner-html`, `sci-inner-html` | Library + SCI |
| `tap` | `locator/tap-element`, `sci-tap` | Library + SCI |
| `bringtofront` | `page/bring-to-front`, `sci-bring-to-front` | Library + SCI |
| `dispatch` | `locator/dispatch-event`, `sci-dispatch-event` | Library + SCI |
| `waitfordownload` | `page/wait-for-download`, `sci-wait-for-download` | Library + SCI |
| `permissions` (grant) | `core/context-grant-permissions!`, `sci-context-grant-permissions!` | Library + SCI |
| `permissions` (clear) | `core/context-clear-permissions!`, `sci-context-clear-permissions!` | Library + SCI |
| `timezone` | `:timezone-id` context option | Library (context creation) |
| `locale` | `:locale` context option | Library (context creation) |
| `addscript` | `page/add-script-tag`, `sci-add-script-tag` | Library + SCI |
| `addstyle` | `page/add-style-tag`, `sci-add-style-tag` | Library + SCI |
| `addinitscript` | `page/add-init-script` | Library |
| `expose` | `page/expose-function!`, `page/expose-binding!` | Library + SCI |
| `evalhandle` | `page/evaluate-handle`, `sci-evaluate-handle` | Library + SCI |
| `har_start`/`har_stop` | `:record-har-path`, `:record-har-mode`, `route-from-har!` | Library + test fixtures |
| `multiselect` | `locator/select-option` (accepts multiple values) | Library + SCI |
| `selectall` | `(locator/press loc "Control+a")` — trivial composition | Library + SCI |
| `setvalue` | `(page/evaluate page "..." val)` — one-liner | Library + SCI |
| `responsebody` | `page/wait-for-response` + `.text()` | Library |

**These should NOT be promoted to CLI commands** — they're already accessible via `spel eval-sci`
and the Clojure library. Adding thin CLI wrappers for each would bloat the daemon without
meaningful benefit to the primary CLI workflow.

---

## The 3 Pareto-Optimal Features (Worth Implementing)

These are the only features that clear the 20/80 bar: meaningful impact, reasonable effort,
and not already covered by existing spel capabilities.

---

### PT-01: Diff Engine — `diff snapshot`, `diff screenshot`, `diff url`

**What it is**: Three commands for comparing page states. Myers diff on accessibility snapshots,
pixel-level screenshot comparison, and combined URL-vs-URL comparison.

**Why it matters**: This is agent-browser's marquee differentiator. Change detection after actions,
visual regression testing, and staging-vs-production comparison are high-value agent workflows.
Nothing in spel covers this today — you'd need external tooling.

**Effort**: 40% (1-2 days). Myers diff algorithm + pixel comparison + multi-page orchestration.
**Impact**: 60%. Enables an entire class of workflows spel can't do today.

**How it works in agent-browser**:
```bash
agent-browser diff_snapshot --baseline '{"snapshot": "...previous..."}'
# Returns: {diff, additions: 5, removals: 3, unchanged: 42, changed: 2}

agent-browser diff_screenshot --baseline ./before.png --output ./diff.png --threshold 0.1
# Returns: {diffPath, totalPixels, differentPixels, mismatchPercentage, match}

agent-browser diff_url --url1 "https://staging.example.com" --url2 "https://prod.example.com"
```

**Implementation guidance**:
- New file: `src/com/blockether/spel/diff.clj`
- Snapshot diff: Use `java-diff-utils` (JVM library) or implement Myers diff. Compare snapshot strings line-by-line.
- Screenshot diff: `BufferedImage.getRGB()` pixel comparison. Generate diff image with changed pixels highlighted.
- URL diff: Navigate to url1, capture snapshot + screenshot. Navigate to url2, capture both. Run both diffs.
- `daemon.clj`: Three handlers: `diff_snapshot`, `diff_screenshot`, `diff_url`.
- `native.clj`: Route `diff snapshot --baseline <file>`, `diff screenshot --baseline <file>`, `diff url <url1> <url2>`.

**Acceptance criteria**:
1. `spel diff snapshot --baseline snapshot.txt` returns additions/removals/unchanged counts
2. `spel diff screenshot --baseline before.png --output diff.png` creates visual diff image
3. `spel diff screenshot --baseline before.png --threshold 0.05` returns match=true/false
4. `spel diff url <url1> <url2>` compares both pages (snapshot + screenshot)
5. `--json` output with structured diff data

**How to test**:
- Unit tests in `diff_test.clj`: Myers diff with known inputs, pixel comparison with synthetic images
- `cli_integration_test.clj`: Take snapshot, modify page, diff, verify counts
- `test-cli.sh`: `"$SPEL" diff snapshot --help | assert_contains "baseline"`

---

### PT-02: Computed Styles — `styles <selector>`

**What it is**: Returns computed CSS styles for elements. Curated set of commonly-needed properties
(fontSize, color, backgroundColor, etc.) with `--full` flag for all 300+ properties.

**Why it matters**: Visual regression testing, design system verification, accessibility audits
(font size, color contrast). Currently requires manual `eval` with `getComputedStyle()`.
A dedicated command with curated output is significantly more ergonomic.

**Effort**: 15% (2-3 hours). Single JS evaluation extracting computed styles.
**Impact**: 40%. Useful for visual testing, accessibility, and design system work.

**How it works in agent-browser**:
```bash
agent-browser styles "#heading"
# Returns: {elements: [{tag, text, box, styles: {fontSize, fontWeight, color, ...}}]}
```

**Implementation guidance**:
- `daemon.clj`: `handle-cmd "styles"` — resolve selector, evaluate JS `getComputedStyle()`, extract curated property set.
- JS snippet: `el => { const s = getComputedStyle(el); return { fontSize: s.fontSize, fontWeight: s.fontWeight, color: s.color, backgroundColor: s.backgroundColor, borderRadius: s.borderRadius, padding: s.padding, margin: s.margin }; }`
- `native.clj`: Route `styles <sel> [--full]`.
- Curated defaults: fontSize, fontWeight, fontFamily, color, backgroundColor, borderRadius, border, boxShadow, padding, margin.

**Acceptance criteria**:
1. `spel styles "#heading"` returns curated computed styles
2. `--full` flag returns all computed styles
3. Multiple elements: `spel styles "p"` returns styles for all `<p>` elements
4. Works with refs: `spel styles @e3`
5. `--json` output: `{"elements": [{"tag": "h1", "styles": {"fontSize": "32px", ...}}]}`

**How to test**:
- `cli_integration_test.clj`: Set content with known styles, verify returned values
- `test-cli.sh`: `"$SPEL" styles --help | assert_contains "selector"`

---

### PT-03: Clipboard — `clipboard copy/paste/read`

**What it is**: Read from, write to, and paste from the browser clipboard. Handles permission
grants automatically.

**Why it matters**: Clipboard operations are common in testing copy-paste workflows and clipboard
API testing. Currently requires `eval` with `navigator.clipboard` plus manual permission setup.
A dedicated command handles the permission grant automatically.

**Effort**: 20% (half day). Permission granting + JS clipboard API.
**Impact**: 25%. Useful but not a daily driver for most workflows.

**How it works in agent-browser**:
```bash
agent-browser clipboard copy "Hello World"
agent-browser clipboard paste
agent-browser clipboard read
```

**Implementation guidance**:
- `daemon.clj`: Handler with three sub-operations. Grant clipboard permissions lazily on first use via `context-grant-permissions!` (already exists in spel).
- `copy`: `page.evaluate("text => navigator.clipboard.writeText(text)", text)`
- `read`: `page.evaluate("() => navigator.clipboard.readText()")`
- `paste`: `page.keyboard().press("Control+v")` after copy
- `native.clj`: Route `clipboard copy "text"`, `clipboard paste`, `clipboard read`.

**Acceptance criteria**:
1. `spel clipboard copy "hello"` writes to clipboard
2. `spel clipboard read` returns `{"content": "hello"}`
3. `spel clipboard paste` types clipboard content into focused input
4. Permissions are granted automatically
5. Works across tabs within the same session

**How to test**:
- `cli_integration_test.clj`: Copy text, read back, verify. Copy, focus input, paste, verify value.
- `test-cli.sh`: `"$SPEL" clipboard copy "test123" && "$SPEL" clipboard read --json | assert_jq '.content' '"test123"'`

---

## Features That Don't Clear the Pareto Bar

These 20 features are technically absent from spel but not worth implementing, for the reasons listed.

| # | Feature | Why NOT |
|---|---------|---------|
| 1 | `selectall` | `press "Control+a"` — trivial composition, no command needed |
| 2 | `setvalue` | `eval "el.value = x"` — one-liner, niche use case |
| 3 | `pause` (debugger) | `spel --headed` + `inspector` already covers interactive debugging |
| 4 | `video_start`/`video_stop` | SCI already has `start-video-recording`/`finish-video-recording`; context options handle it in library |
| 5 | `recording_start`/`stop`/`restart` | `spel codegen record` already does this |
| 6 | `responsebody` | `page/wait-for-response` + `.text()` in library. `network requests` shows metadata in CLI |
| 7 | Config file (`spel.json`) | Moderate effort (35%), marginal benefit. CLI flags + shell aliases cover it |
| 8 | Domain allowlist | Safety feature for untrusted agents. spel's users are developers — overkill |
| 9 | Auth vault | `state save/load` covers 80% of auth persistence. Storing passwords on disk is a liability |
| 10 | Action policy / confirmation | Wrong model for a developer tool. Domain allowlist is simpler if ever needed |
| 11 | Screencast / streaming | `--headed` for live, video recording for playback. WebSocket server is heavy |
| 12 | CDP input injection | Playwright's input API is higher-level and cross-browser. CDP is Chromium-only |
| 13 | Profiler (DevTools) | `trace start`/`trace stop` covers most debugging. CPU profiling is niche |
| 14 | Window management | `--session` (separate contexts) + `tab new` (same context) already cover this |
| 15 | iOS Simulator | 80% effort, macOS-only, requires Appium+Xcode. `set device` covers viewport/UA. See TASK-010 |
| 16 | Cloud browser providers | `connect <cdp-url>` already works. Session lifecycle is provider-specific. See TASK-011 |
| 17 | `timezone` CLI command | `:timezone-id` context option exists. Runtime override via CDP is Chromium-only |
| 18 | `locale` CLI command | `:locale` context option exists. Runtime override is Chromium-only |
| 19 | `permissions` CLI command | `context-grant-permissions!` exists in library+SCI. CLI is niche |
| 20 | Content injection CLI | `add-script-tag`/`add-style-tag`/`add-init-script` all exist in library+SCI |

---

## Pareto Chart

```
Impact
  ▲
  │                                          ┌─────────┐
60│                                          │ PT-01   │
  │                                          │  Diff   │
  │                                          │ Engine  │
  │                                          └─────────┘
40│  ┌──────────┐
  │  │ PT-02    │         ┌──────────┐
  │  │ Computed │         │ PT-03    │
  │  │ Styles   │         │Clipboard │
  │  └──────────┘         └──────────┘
25│
  │
20│  - - - - - - - - - - everything else below this line - - -
  │
  └──────────────────────────────────────────────────────────► Effort
     15%       20%        25%         30%        35%     40%
```

**Recommendation**: Implement PT-02 (Computed Styles) first — best effort/impact ratio.
Then PT-01 (Diff Engine) if agent-oriented workflows become strategic.
PT-03 (Clipboard) only if user demand emerges.

---

## Decision Log

| Feature | Decision | Reasoning |
|---------|----------|-----------|
| Diff engine | **Do** (P2) | Biggest competitive gap. Enables change detection workflows |
| Computed styles | **Do** (P2) | Best ROI. 2-3 hours for meaningful visual testing capability |
| Clipboard | **Do** (P3) | Moderate value. Only if demand emerges |
| 20 other features | **Skip/Defer** | Already covered by library/SCI, or wrong fit for spel |

---

*Corrected March 1, 2026. Previous version (1573 lines) mislabeled 13 existing features as missing.
This version reflects the actual gap after auditing all 3 spel layers (CLI, SCI, library).*
