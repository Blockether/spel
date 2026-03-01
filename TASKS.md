# TASKS — spel Backlog

> Sourced from the spel vs agent-browser dogfood comparison (Feb 26, 2026).
> Benchmarked against agent-browser 0.9.0, tested on github.com and example.org.

---

## TASK-001: Propagate Playwright Error Context Instead of "Unknown error"

### Problem Statement

When a Playwright action fails (e.g., clicking a non-existent ref), spel returns a generic `"Error: Unknown error"` with no call log, no selector context, no wait trace. The Playwright exception contains rich debugging information (the selector attempted, the call log showing what was waited for, the timeout) — but the daemon swallows it and returns a flat error string.

This makes debugging from the CLI nearly impossible. The user has to guess what went wrong.

### Reproduction

```bash
spel open https://example.org
spel click @nonexistent
# Output: "Error: Unknown error"
# Exit code: 1

# Compare with agent-browser:
agent-browser open https://example.org
agent-browser click @nonexistent
# Output: "✗ locator.click: Unsupported token "@nonexistent" while parsing css selector "@nonexistent".
#          Did you mean to CSS.escape it?
#          Call log:
#            - waiting for @nonexistent"
# Exit code: 1
```

### Type

🐛 **Bug** — Error information exists in the Playwright exception but is being discarded.

### Priority

🔴 **P0 — Critical**. Every failed CLI command produces useless output. This is the single biggest DX pain point.

### Acceptance Criteria

1. `spel click @nonexistent` outputs the Playwright error message including: the action name (`locator.click`), the selector, the reason, and the call log
2. `spel fill @badref "text"` similarly shows the full Playwright error
3. Timeout errors show the timeout value and what was being waited for
4. `--json` mode returns structured error: `{"error": "locator.click: ...", "call_log": ["waiting for @nonexistent"], "selector": "@nonexistent"}`
5. Exit code remains 1 for all error cases
6. The "No page loaded" error (which is already good) remains unchanged

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Extract message + call log from PlaywrightException** | Full context, minimal change | Must handle all exception types |
| **B) Wrap all daemon handlers in generic exception-to-map** | Consistent, catches everything | May leak internal stack traces |
| **C) Return raw `.getMessage()` only** | Simplest change | Loses call log, which is the most useful part |

### Implementation Hints

- Look at `daemon.clj` — the handler functions catch exceptions and return error maps. The exception's `.getMessage()` contains the full Playwright error with call log, but it's likely being replaced with a generic message somewhere.
- `PlaywrightException` has `.getMessage()` which includes the call log. Parse it or pass it through directly.
- Check `cli.clj` `print-result` and `native.clj` output formatting — errors might also be truncated there.
- For `--json` mode, structure the error: `{:error (ex-message e) :type (.getSimpleName (class e))}`.

### Consequences for spel

- **Positive**: Immediately makes spel debuggable from the CLI. Agents can read errors and self-correct. Eliminates the #1 DX complaint from the comparison.
- **Negative**: Error output becomes multi-line (but that's a feature, not a bug). May need to handle formatting for very long call logs.

### Pareto Estimate

**20% effort / 80% impact**. This is likely a 1-2 hour fix in `daemon.clj`'s error handling path. The Playwright exception already contains everything — we just need to stop discarding it. Highest ROI task on this list.

---

## TASK-002: Fail on Invalid URLs Instead of Silent `about:blank`

### Problem Statement

`spel open not-a-url` silently navigates to `about:blank` and returns exit code 0. The user gets no indication that their URL was invalid. This is especially dangerous for AI agents who pass malformed URLs — they'll proceed to snapshot an empty page and waste their entire action loop.

### Reproduction

```bash
spel open not-a-url
# Output: "about:blank"
# Exit code: 0  ← should be 1

spel open https://this-domain-definitely-does-not-exist-xyz.com
# Hangs for ~30s then: Error (timeout)
# But "not-a-url" doesn't even try — it goes straight to about:blank

# Compare with agent-browser:
agent-browser open not-a-url
# Output: "✗ page.goto: net::ERR_NAME_NOT_RESOLVED at https://not-a-url/"
# Exit code: 1
```

### Type

🐛 **Bug** — Invalid input is silently accepted.

### Priority

🔴 **P0 — Critical**. Silent failures are the worst class of bug. Agents and scripts will proceed with stale/empty state.

### Acceptance Criteria

1. `spel open not-a-url` returns exit code 1 with error message: `"Error: Invalid URL 'not-a-url'. URLs must include a scheme (https://) or be a valid domain."`
2. `spel open foo` returns exit code 1 (no TLD, no scheme)
3. `spel open example.org` still works (auto-prepends `https://`)
4. `spel open https://nonexistent.example.org` fails with the DNS/network error from Playwright (not our validation)
5. `spel open file:///tmp/test.html` still works (valid scheme)
6. `spel open about:blank` still works (explicit intent)
7. `--json` mode: `{"error": "Invalid URL ...", "url": "not-a-url"}`

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Pre-validate URL before passing to Playwright** | Catches bad input early, clear error | Must handle edge cases (localhost, IP, file://) |
| **B) Check result URL after navigation** | Simple — if result is `about:blank` and input wasn't, fail | Race condition if page redirects to about:blank intentionally |
| **C) Let Playwright fail naturally** | No extra code | Playwright doesn't fail on `not-a-url` — it goes to about:blank |

### Implementation Hints

- Best approach: **A + B combined**. Pre-validate for obvious non-URLs (no dots, no scheme, no localhost/IP pattern), and also check post-navigation: if navigated URL is `about:blank` but input wasn't `about:blank`, return an error.
- The validation lives in `daemon.clj` in the navigate/open handler, before calling Playwright's `page.navigate()`.
- Keep it simple: `(or (re-matches #"^https?://.*" url) (re-matches #"^file://.*" url) (re-matches #".*\..*" url) (= "localhost" url) (re-matches #"\d+\.\d+\.\d+\.\d+.*" url))` — anything else is likely invalid.

### Consequences for spel

- **Positive**: Eliminates silent failures. Agents get immediate feedback on bad URLs. Scripts fail fast instead of proceeding on empty pages.
- **Negative**: Might reject some exotic URL schemes (custom protocols). Mitigate by only rejecting strings that clearly aren't URLs (no dots, no scheme).

### Pareto Estimate

**15% effort / 70% impact**. Simple validation logic, ~1 hour. Prevents an entire class of silent failures that waste agent loops and confuse debugging.

---

## TASK-003: Sequential Ref IDs for LLM Agents

### Problem Statement

spel uses 6-character random hash refs like `[@e2yrjz]`, `[@e9mter]`, `[@e6t2x4]`. agent-browser uses sequential refs like `[ref=e1]`, `[ref=e2]`, `[ref=e3]`. Sequential refs are measurably easier for LLMs to work with:

- Shorter (2-3 chars vs 6 chars) — less tokens per snapshot
- Predictable ordering — LLM can reason about position
- Easier to type/reference in follow-up commands — `@e3` vs `@e2yrjz`
- Lower hallucination risk — LLMs more reliably reproduce short sequential IDs than random hashes

On a complex page like github.com with 100+ refs, this means ~300 fewer tokens per snapshot.

### Reproduction

Not a bug — feature comparison:

```bash
# spel (current):
# - link "Skip to content" [@e27w9t]
# - button "Platform" [@e55nqg]
# - textbox "Enter your email" [@e3k2ih]

# agent-browser (desired):
# - link "Skip to content" [ref=e1]
# - button "Platform" [ref=e2]
# - textbox "Enter your email" [ref=e3]
```

### Type

✨ **Feature** — LLM ergonomics improvement.

### Priority

🟡 **P1 — High**. This is agent-browser's biggest UX advantage over spel. Eliminating it would remove the primary reason an AI agent team might choose agent-browser.

### Acceptance Criteria

1. Snapshot refs use sequential format: `@e1`, `@e2`, ... `@e107`
2. All commands that accept refs (`click @e3`, `fill @e5 "text"`, `get text @e7`) work with the new format
3. Refs are stable within a single snapshot — same page, same snapshot, same refs
4. Refs reset on each new snapshot call (they're ephemeral, not persistent)
5. Existing hash-based refs (`@e2yrjz`) continue to work during a transition period (backward compatibility)
6. `--json` output uses the new sequential refs
7. Snapshot `-i` flag produces a contiguous sequence (no gaps from filtered-out non-interactive elements)

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Sequential numbering (e1, e2, e3)** | Simplest, matches agent-browser | Refs change when page changes (but they already do with hashes) |
| **B) Role-prefixed sequential (btn1, lnk2, txt3)** | More semantic | Longer, harder to type, more token overhead |
| **C) Short hash (3 chars instead of 6)** | Minimal change | Still random, still hard to reference |
| **D) Dual mode: `--refs=sequential\|hash`** | Backward compatible | Complexity, two code paths |

### Implementation Hints

- The ref generation happens in `sci_env.clj` or wherever the accessibility tree is parsed and refs are assigned. Look for where `[@eXXXXX]` strings are constructed.
- The ref→locator mapping is maintained in the daemon state. Change the key generation from random hash to sequential counter.
- Use an atom with a counter that resets at the start of each `capture-snapshot` call.
- The `@` prefix in CLI commands (`click @e3`) routes through the ref resolver — this should work unchanged as long as the daemon maps `e3` to the correct locator.

### Consequences for spel

- **Positive**: Major LLM ergonomics win. ~300 fewer tokens per complex page snapshot. Easier manual use. Removes agent-browser's primary advantage.
- **Negative**: Breaking change for any tooling that parses ref format (unlikely outside of spel itself). Refs are less "unique" (but they're ephemeral per-snapshot anyway).

### Pareto Estimate

**25% effort / 75% impact**. Moderate refactor of the ref generation system. Possibly a few hours to trace the ref lifecycle through daemon → CLI → native. High impact because it directly affects every snapshot, every agent interaction.

---

## TASK-004: Include Link URLs in Snapshots

### Problem Statement

spel's accessibility tree snapshot doesn't include `href` values for links. To discover where a link goes, an agent must execute JavaScript: `spel eval "Array.from(document.querySelectorAll('a[href]')).map(a => a.href)"`. This costs an extra command round-trip and requires the agent to know the JS incantation.

agent-browser includes `/url: https://...` inline for every link in the snapshot tree. This lets agents make navigation decisions from the snapshot alone — no extra commands needed.

### Reproduction

```bash
spel open https://example.org
spel snapshot
# Output:
# - link "Learn more" [@e6t2x4]
# ← No URL shown. Agent doesn't know where this link goes.

agent-browser open https://example.org
agent-browser snapshot
# Output:
# - link "Learn more" [ref=e2]:
#     - /url: https://iana.org/domains/example
# ← Agent sees the URL immediately.
```

### Type

✨ **Feature** — Snapshot enrichment for agent usability.

### Priority

🟡 **P1 — High**. Link URLs are the second most-requested piece of information after element identity. Every web crawling / dogfood / audit agent needs this.

### Acceptance Criteria

1. Links in the snapshot tree show their href: `- link "Learn more" [@e3] [url=https://iana.org/domains/example]`
2. Only `<a href="...">` elements get the URL annotation (not buttons, not divs with onclick)
3. Relative URLs are resolved to absolute
4. `--json` mode includes URL in the refs map: `{"e3": {"role": "link", "name": "Learn more", "url": "https://..."}}`
5. Links with no href (e.g., `<a>text</a>`) show no URL annotation
6. Fragment-only links (`#section`) are resolved to full URL
7. `javascript:` hrefs are omitted or shown as `[url=javascript:void(0)]` for transparency
8. Performance: adding URLs doesn't measurably slow down snapshot (< 5ms overhead)

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Inline in tree text: `[url=...]`** | Visible in plain text output | Adds length to every link line |
| **B) Separate metadata section at bottom** | Keeps tree clean | Agent must cross-reference; two locations to parse |
| **C) Only in `--json` refs map** | Clean tree, structured data | Plain text users don't see URLs |
| **D) New flag `--urls` to opt-in** | No default change | Agents must know to pass the flag |

### Implementation Hints

- The snapshot tree is built from Playwright's accessibility tree API. Links are identified by `role: "link"`.
- The href can be extracted per-element: `element.getAttribute("href")` or from the locator.
- In `sci_env.clj` where the snapshot tree is assembled, when a node has `role=link`, fetch its `href` attribute and append to the display string.
- For JSON mode, add `"url"` to the ref metadata map (see TASK-005 for the refs map structure).
- Consider caching: batch-extract all hrefs via a single JS eval rather than per-element getAttribute to keep it fast.

### Consequences for spel

- **Positive**: Agents can make link-following decisions from a single snapshot. Eliminates the most common follow-up `eval` call. Crawling/dogfood workflows become one-command-per-page instead of two.
- **Negative**: Snapshots become longer (each link line grows by ~30-80 chars). Mitigated by `-c` compact mode and `-d` depth limiting.

### Pareto Estimate

**20% effort / 70% impact**. The href data is readily available from Playwright's element handles. Main work is wiring it into the snapshot tree builder and JSON formatter. ~2-3 hours. Major usability win for any agent that navigates.

---

## TASK-005: Structured Refs Map in JSON Output

### Problem Statement

spel's `--json` snapshot output includes `refs_count` (a number) but not the refs themselves. To know what `@e3` refers to, you must parse the tree text. agent-browser returns a structured `refs` map: `{"e3": {"name": "Submit", "role": "button"}}`.

For AI agents and programmatic consumers, the refs map is essential — it allows lookup without text parsing, supports building action plans from structured data, and enables validation ("is @e3 a button?").

### Reproduction

```bash
spel open https://example.org
spel snapshot --json
# {"snapshot": "- heading \"Example Domain\" [@e2yrjz]...", "refs_count": 4, "url": "...", "title": "..."}
# ← refs_count=4 but no way to know what e2yrjz IS without parsing the tree text

agent-browser open https://example.org
agent-browser snapshot --json
# {"data": {"refs": {"e1": {"name": "Example Domain", "role": "heading"}, "e2": {"name": "Learn more", "role": "link"}}, "snapshot": "..."}}
# ← Structured lookup: refs.e2.role === "link"
```

### Type

✨ **Feature** — JSON API enrichment.

### Priority

🟡 **P1 — High**. This + TASK-003 (sequential refs) + TASK-004 (link URLs) together make spel's snapshot output fully competitive with agent-browser's for machine consumption.

### Acceptance Criteria

1. `--json` snapshot includes a `refs` map: `{"refs": {"e1": {"role": "link", "name": "Learn more"}, ...}}`
2. Each ref entry includes at minimum: `role`, `name`
3. Links also include `url` (from TASK-004)
4. Inputs include `type` (text, email, password, etc.)
5. Checkboxes/radios include `checked` state
6. The `refs_count` field is retained for backward compatibility
7. Plain text output (non-JSON) is NOT changed — this is JSON-only enrichment
8. Performance: generating the refs map adds < 10ms overhead

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Flat refs map (agent-browser style)** | Simple, proven by AB | Must collect attributes during tree walk |
| **B) Refs map with full element metadata (tag, classes, bbox)** | Very rich | Potentially huge JSON, high token cost |
| **C) Separate `spel refs --json` command** | No snapshot change | Extra command, extra round-trip |

### Implementation Hints

- During the snapshot tree walk (where refs are assigned), collect `{ref-id -> {:role role :name name ...}}` into a map.
- Add this map to the JSON output alongside `snapshot` and `refs_count`.
- The tree walk already visits every node — adding metadata collection is O(1) per node.
- For link URLs, either do a batch JS eval or getAttribute per link-role node (see TASK-004).

### Consequences for spel

- **Positive**: JSON consumers (agents, scripts, MCP tools) get structured ref data. Enables building action plans without text parsing. Combined with TASK-003 and TASK-004, makes spel's JSON output best-in-class.
- **Negative**: JSON response size increases (~50-100 bytes per ref). On a 100-ref page, that's ~5-10KB extra. Negligible.

### Pareto Estimate

**25% effort / 65% impact**. Moderate implementation — need to collect attributes during tree walk and serialize. ~2-3 hours. Impact is high for machine consumers but invisible to human CLI users.

---

## TASK-006: `download` Command for Explicit File Downloads

### Problem Statement

spel has no dedicated command for downloading files by clicking elements. To download a file, you must use `--eval` with Playwright's download event handling — which requires knowing the Clojure/SCI API for promises and events. agent-browser has a simple `download <selector> <path>` command.

File downloads are common in automation: downloading reports, exporting CSVs, saving PDFs from web apps.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser (simple):
agent-browser download "#export-btn" ./report.csv

# spel (current workaround — requires SCI knowledge):
spel --eval '(let [dl (spel/expect-download (fn [] (spel/click (spel/locator "#export-btn"))))]
              (spel/download-save-as dl "./report.csv"))'
```

### Type

✨ **Feature** — New CLI command.

### Priority

🟢 **P2 — Medium**. Downloads are needed but not frequent. The `--eval` workaround exists, it's just ergonomically painful.

### Acceptance Criteria

1. `spel download "#export-btn" ./report.csv` clicks the element and saves the download to the specified path
2. `spel download @e5 ./file.zip` works with ref-based selectors
3. If no download is triggered within timeout, return error with exit code 1
4. Output shows: filename, size, save path
5. `--json` output: `{"filename": "report.csv", "size": 12345, "path": "./report.csv"}`
6. `--timeout <ms>` flag to override default download timeout
7. Works with links (`<a href="..." download>`) and JS-triggered downloads

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Dedicated `download` CLI command** | Simple, matches agent-browser | New command to maintain |
| **B) Add `--download <path>` flag to `click`** | No new command | Overloads `click` semantics |
| **C) SCI helper function only** | Less CLI surface | Still requires `--eval` for CLI use |

### Implementation Hints

- Playwright's download API: register a download handler before the click, then call `download.saveAs(path)`.
- In `daemon.clj`, create a `download` handler that: (1) sets up the download listener, (2) clicks the element, (3) waits for download, (4) saves to path.
- In `native.clj`, add the `download` command routing.
- In `cli.clj`, add output formatting.

### Consequences for spel

- **Positive**: Closes a feature gap with agent-browser. Makes file download automation accessible to non-Clojure users.
- **Negative**: Minimal — one more command to maintain. Download semantics can be tricky (timeouts, redirects, auth).

### Pareto Estimate

**30% effort / 40% impact**. Moderate implementation (download event handling is async), but the use case is not daily-driver frequent. Nice to have, not urgent.

---

## TASK-007: `--extension` Flag for Chrome Extensions

### Problem Statement

agent-browser supports loading Chrome extensions via `--extension <path>` (repeatable). spel has no equivalent. Extensions are useful for: ad blockers (cleaner screenshots), authentication extensions (corporate SSO), accessibility auditing extensions, and anti-detection.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser:
agent-browser --extension ./my-extension open https://example.org

# spel: No equivalent
```

### Type

✨ **Feature** — Browser launch option.

### Priority

🟢 **P2 — Medium**. Niche but valuable for corporate environments and specialized automation.

### Acceptance Criteria

1. `spel --extension /path/to/extension open https://example.org` loads the extension on browser launch
2. Multiple extensions: `spel --extension ./ext1 --extension ./ext2 open ...`
3. Extension persists across the session (all subsequent commands see it)
4. Error if extension path doesn't exist: `"Error: Extension not found: /path/to/ext"`
5. Works with `--headed` mode (user can see extension in toolbar)
6. Works with `--stealth` mode (extensions load alongside stealth patches)
7. `spel --help` documents the flag

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) `--extension` flag on launch** | Matches agent-browser, intuitive | Extensions must be loaded at browser launch, can't add mid-session |
| **B) `--args "--load-extension=..."` pass-through** | Already possible via `--args` | Undiscoverable, ugly, doesn't validate path |
| **C) Extension management commands** | Rich UX (list, enable, disable) | Over-engineered for the use case |

### Implementation Hints

- Playwright's Chromium launch options accept `--load-extension=path` and `--disable-extensions-except=path` as Chrome args.
- In `daemon.clj`, when `--extension` is provided, add these args to the browser launch config.
- Note: Extensions only work with Chromium, not Firefox or WebKit. Should warn if used with non-Chromium.
- The `--args` flag already passes arbitrary browser args — this is a convenience wrapper with validation.

### Consequences for spel

- **Positive**: Enables corporate SSO extensions, ad blockers for clean screenshots, specialized automation. Closes a feature gap.
- **Negative**: Chromium-only feature. Must document the limitation.

### Pareto Estimate

**15% effort / 25% impact**. Thin wrapper over existing `--args` functionality with path validation. ~1 hour. Low impact because most users don't need extensions, but high value for those who do.

---

## TASK-008: Reduce Snapshot Token Overhead for LLM Agents

### Problem Statement

spel's interactive snapshot for github.com is 314 lines. agent-browser's is 107 lines for the same page. The 3× difference comes from spel including structural elements (regions, landmarks, headings) alongside interactive elements.

While the structural context is valuable for understanding page layout, it costs ~600 extra tokens per snapshot. For an agent making 20 snapshots per session, that's ~12,000 wasted tokens — real money at API pricing.

### Reproduction

```bash
spel open https://github.com
spel snapshot -i | wc -l
# 314 lines

agent-browser open https://github.com
agent-browser snapshot -i | wc -l
# 107 lines
```

### Type

✨ **Feature** — Token efficiency optimization.

### Priority

🟢 **P2 — Medium**. The `-c -d 3` flags already bring spel down to 26 lines (vs 107 for agent-browser). The issue is that the default `-i` is too verbose without those flags.

### Acceptance Criteria

1. New flag `--flat` (or `-F`) produces agent-browser-style flat list: only interactive elements, no structural nesting, one element per line
2. `spel snapshot -i --flat` on github.com produces ≤ 120 lines
3. Each line shows: role, name, ref, and relevant attributes (url for links, type for inputs, checked for checkboxes)
4. No structural wrappers (region, banner, main, contentinfo) in flat mode
5. Existing `-i`, `-c`, `-d` flags continue to work as before
6. `--flat` implies `-i` (only interactive elements make sense in flat mode)
7. `--flat` works with `--json`

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) New `--flat` flag** | Explicit opt-in, no breaking changes | Yet another flag |
| **B) Make `-i` itself flatter by default** | No new flag | Breaking change for existing users |
| **C) Auto-detect agent mode** (via `--json` or MCP) | Automatic optimization | Magic behavior, hard to debug |
| **D) Smarter `-c` (compact) to remove more structural noise** | Improves existing flag | May be too aggressive for some use cases |

### Implementation Hints

- In the snapshot tree walker, when `--flat` is active: skip all non-interactive nodes, don't emit tree indentation, just emit a flat list.
- An "interactive" node is one with: a ref AND a role in `#{link button textbox checkbox radio combobox tab switch slider spinbutton menuitem option}`.
- This is a view concern — the underlying tree is the same, just rendered differently.

### Consequences for spel

- **Positive**: 3× token savings for LLM agents. Competitive with agent-browser's compact output. Makes spel viable for cost-sensitive agent deployments.
- **Negative**: Loss of structural context in flat mode (but that's the explicit trade-off the user opts into).

### Pareto Estimate

**20% effort / 50% impact**. Straightforward filtering of the existing snapshot tree. ~2 hours. Impact is significant for LLM agent users but irrelevant for human CLI users who already have `-c -d`.

---

## TASK-009: Video `save-as` Bug — "Page is not yet closed"

### Problem Statement

Calling `(spel/finish-video-recording {:save-as "/path/to/output.webm"})` via `--eval` throws `"Page is not yet closed"`. The Playwright video API requires the page/context to be closed before `video.saveAs()` can be called, but `finish-video-recording` attempts to save before the close completes.

### Reproduction

```bash
spel --eval '
(do
  (spel/navigate "https://example.org")
  (spel/start-video-recording)
  (spel/navigate "https://example.org")
  (Thread/sleep 2000)
  (spel/finish-video-recording {:save-as "/tmp/test-video.webm"}))'
# Error: "Page is not yet closed"
```

**Workaround** (currently required):
```bash
spel --eval '
(do
  (spel/navigate "https://example.org")
  (spel/start-video-recording)
  (spel/navigate "https://example.org")
  (Thread/sleep 2000)
  (let [path (spel/video-path)]
    (spel/finish-video-recording)  ;; no save-as
    (Thread/sleep 500)
    ;; then cp the file manually))'
```

### Type

🐛 **Bug** — Documented API option doesn't work.

### Priority

🟠 **P2 — Medium**. Workaround exists (get path → finish → cp), but it's unintuitive and fragile (the sleep is a guess).

### Acceptance Criteria

1. `(spel/finish-video-recording {:save-as "/tmp/out.webm"})` successfully saves the video to the specified path
2. No "Page is not yet closed" error
3. The function blocks until the file is fully written
4. If the path is not writable, returns a clear error
5. Works from both `--eval` and the Clojure library API

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Close context before saveAs, then reopen** | Matches Playwright's expectation | Disrupts session state |
| **B) Use video.path() + file copy after close** | Avoids saveAs entirely | Workaround-as-fix, doesn't fix the API |
| **C) Ensure close completes before saveAs** | Fixes the root cause | Need to understand the async lifecycle |

### Implementation Hints

- In `sci_env.clj`, `finish-video-recording` likely calls `context.close()` and then immediately `video.saveAs()`. The close is async — need to ensure it completes first.
- Playwright Java: `page.close()` is sync, but `video().saveAs()` requires the **context** to be closed, not just the page.
- Sequence should be: `page.close()` → `context.close()` → `video.saveAs(path)`. The video object must be captured BEFORE closing.

### Consequences for spel

- **Positive**: Video recording API works as documented. Dogfood/CI workflows can save videos without workarounds.
- **Negative**: Minimal — the fix is internal to the video lifecycle management.

### Pareto Estimate

**20% effort / 30% impact**. Likely a sequencing fix in the async lifecycle. ~1-2 hours to trace and fix. Impact is moderate — video recording works with the workaround, this just fixes the ergonomics.

---

## TASK-010: iOS Simulator Support

### Problem Statement

agent-browser supports iOS Simulator testing via Appium: `-p ios open https://example.org`, with device listing, swipe gestures, and tap interactions. spel has `set device <name>` for viewport/UA emulation but no real mobile browser engine testing.

Real iOS testing catches bugs that viewport emulation cannot: Safari-specific rendering, iOS-specific touch behaviors, platform-specific font rendering, iOS permission dialogs.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser:
agent-browser -p ios --device "iPhone 15 Pro" open https://example.org
agent-browser -p ios swipe up
agent-browser -p ios tap @e1
agent-browser -p ios device list

# spel:
spel set device "iphone 14"  # only viewport + UA emulation, not real Safari
```

### Type

✨ **Feature** — Platform expansion.

### Priority

🟢 **P3 — Low**. Nice to have for mobile-focused teams. Most users are fine with viewport emulation. Appium dependency adds significant complexity.

### Acceptance Criteria

1. `spel --provider ios open https://example.org` launches iOS Simulator with Safari
2. `spel --provider ios --device "iPhone 15 Pro" open ...` targets specific simulator
3. `spel --provider ios device list` shows available simulators
4. Standard commands work: `snapshot`, `screenshot`, `click`, `fill`
5. iOS-specific: `swipe up/down/left/right`, `tap @ref`
6. Requires Xcode + Appium (documented prerequisites)
7. Graceful error if Xcode/Appium not installed

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Appium integration (like agent-browser)** | Real Safari engine, proven approach | Heavy dependency (Xcode, Appium, Java), macOS-only |
| **B) Safari WebDriver via Playwright WebKit** | Already have WebKit engine | Not real iOS Safari, misses platform quirks |
| **C) BrowserStack/Sauce Labs cloud** | No local dependencies | Paid service, network latency |

### Implementation Hints

- agent-browser uses Appium under the hood for iOS. Would need to add Appium Java client as a dependency.
- Could be a separate provider module that implements the same command interface.
- Consider making this a plugin/extension rather than core — keeps the binary small for non-iOS users.

### Consequences for spel

- **Positive**: Real mobile Safari testing. Competitive with agent-browser on mobile.
- **Negative**: Appium is a heavy dependency. macOS-only. Xcode install is 12+ GB. Significantly increases complexity for a niche use case.

### Pareto Estimate

**80% effort / 15% impact**. Very heavy implementation (Appium integration, new provider abstraction, iOS-specific commands). Low impact — most users don't need real iOS testing. Consider only if mobile testing becomes a strategic priority.

---

## TASK-011: Cloud Browser Providers

### Problem Statement

agent-browser supports cloud browser providers: `-p browserbase`, `-p kernel`, `-p browseruse`. These provide serverless browser instances for CI/CD at scale — no local Chromium needed, automatic scaling, session recording built in.

spel requires a local browser installation. For CI pipelines running hundreds of parallel tests, local browsers don't scale.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser:
agent-browser -p browserbase open https://example.org  # cloud browser
agent-browser -p kernel open https://example.org        # another provider

# spel: local browser only
spel open https://example.org  # always local Chromium
```

### Type

✨ **Feature** — Infrastructure expansion.

### Priority

🟢 **P3 — Low**. Most users run local browsers. Cloud browsers are for enterprise-scale CI. CDP connect (`spel connect <url>`) already enables connecting to remote browsers — just not the managed provider APIs.

### Acceptance Criteria

1. `spel --provider browserbase open https://example.org` connects to a BrowserBase cloud browser
2. At least one cloud provider is supported (BrowserBase recommended — largest ecosystem)
3. Authentication via environment variables (API key)
4. All standard commands work through the cloud browser
5. Session recordings are accessible
6. Graceful error if API key is missing or invalid
7. Provider-specific options documented in `--help`

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Native provider integration** | First-class experience | Each provider has its own API, high maintenance |
| **B) CDP connect to provider's endpoint** | Leverage existing `connect` command | Providers often need custom handshake beyond CDP |
| **C) Plugin system for providers** | Extensible, community-driven | Architecture overhead for v1 |

### Implementation Hints

- BrowserBase provides a WebSocket CDP endpoint after session creation via REST API. Could be implemented as: (1) REST call to create session, (2) `connect` to the returned WebSocket URL.
- The `connect` command already handles CDP connections — the provider integration is mostly session lifecycle management.
- Consider starting with just BrowserBase (most popular) and adding others later.

### Consequences for spel

- **Positive**: Enterprise CI/CD scalability. No local browser installation needed. Competitive with agent-browser for cloud-native teams.
- **Negative**: External API dependency. Provider API changes break spel. Paid service requirement.

### Pareto Estimate

**60% effort / 20% impact**. Moderate implementation per provider, but each provider has a different API. Low impact — most users don't need cloud browsers. The existing `spel connect <cdp-url>` already covers the manual case.

---

## TASK-012: Snapshot Includes `href` Metadata via Refs Map (JSON-only Enhancement)

### Problem Statement

This is a refinement of TASK-004 and TASK-005 for the JSON `--json` output specifically. Currently, the `--json` snapshot output is a flat string with `refs_count`. For programmatic consumers (MCP servers, agent frameworks, CI scripts), a structured refs map with element metadata would eliminate all text parsing.

This task specifically addresses the **JSON API contract** — making `--json` output a first-class machine interface rather than a serialized human-readable string.

### Reproduction

```bash
spel open https://example.org
spel snapshot --json
# Current: {"snapshot": "...", "refs_count": 4, "url": "...", "title": "..."}
# Desired: {"snapshot": "...", "refs_count": 4, "url": "...", "title": "...",
#           "refs": {"e1": {"role": "heading", "name": "Example Domain", "level": 1},
#                    "e2": {"role": "link", "name": "Learn more", "url": "https://iana.org/domains/example"}}}
```

### Type

✨ **Feature** — API enrichment (combines with TASK-004 and TASK-005).

### Priority

🟡 **P1 — High**. This is the combined deliverable of TASK-004 + TASK-005. Implementing them together is more efficient than separately.

### Acceptance Criteria

See TASK-004 and TASK-005 acceptance criteria combined. Additionally:

1. The `refs` map is only included in `--json` output (plain text output unchanged)
2. Ref metadata includes: `role` (always), `name` (if present), `url` (if link), `type` (if input), `checked` (if checkbox/radio), `level` (if heading), `value` (if has value)
3. Metadata is consistent between the tree text and the refs map
4. Backward compatible — `refs_count`, `snapshot`, `url`, `title` fields unchanged

### Alternative Approaches Considered

See TASK-004 and TASK-005.

### Implementation Hints

See TASK-004 and TASK-005. Implement together — the tree walk that generates the snapshot text already visits every node, so collecting metadata into a map is a single-pass addition.

### Consequences for spel

- **Positive**: Makes `--json` a proper machine API. Agents can build action plans from structured data. Eliminates text parsing for programmatic consumers.
- **Negative**: JSON response size grows. Acceptable trade-off for machine consumers who requested `--json`.

### Pareto Estimate

**30% effort / 80% impact** (when combining TASK-004 + TASK-005 into a single implementation pass). This is the highest-leverage snapshot improvement. ~3-4 hours total. Eliminates the primary machine-consumption weakness.

---

## Summary — Priority Matrix

| Task | Type | Priority | Effort | Impact | Pareto |
|---|---|---|---|---|---|
| **TASK-001** Error propagation | 🐛 Bug | P0 | 20% | 80% | **Best ROI** |
| **TASK-002** Invalid URL detection | 🐛 Bug | P0 | 15% | 70% | **Best ROI** |
| **TASK-003** Sequential refs | ✨ Feature | P1 | 25% | 75% | High ROI |
| **TASK-004** Link URLs in snapshots | ✨ Feature | P1 | 20% | 70% | High ROI |
| **TASK-005** Structured refs in JSON | ✨ Feature | P1 | 25% | 65% | High ROI |
| **TASK-012** Combined JSON enrichment | ✨ Feature | P1 | 30% | 80% | **Best ROI (combined)** |
| **TASK-008** Flat snapshot mode | ✨ Feature | P2 | 20% | 50% | Medium ROI |
| **TASK-006** Download command | ✨ Feature | P2 | 30% | 40% | Medium ROI |
| **TASK-009** Video save-as bug | 🐛 Bug | P2 | 20% | 30% | Medium ROI |
| **TASK-007** Extension loading | ✨ Feature | P2 | 15% | 25% | Low-Medium ROI |
| **TASK-011** Cloud browser providers | ✨ Feature | P3 | 60% | 20% | Low ROI |
| **TASK-010** iOS Simulator | ✨ Feature | P3 | 80% | 15% | **Worst ROI** |

### Recommended Implementation Order

**Sprint 1 — Bug fixes (2-3 hours)**
1. TASK-001: Error propagation
2. TASK-002: Invalid URL detection

**Sprint 2 — LLM ergonomics (4-6 hours)**
3. TASK-012: Combined JSON enrichment (TASK-004 + TASK-005 together)
4. TASK-003: Sequential refs

**Sprint 3 — Polish (3-4 hours)**
5. TASK-008: Flat snapshot mode
6. TASK-009: Video save-as bug

**Backlog — As needed**
7. TASK-006: Download command
8. TASK-007: Extension loading
9. TASK-011: Cloud providers
10. TASK-010: iOS Simulator

---

*Generated from spel vs agent-browser comparison, February 26, 2026.*
