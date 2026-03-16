# spel vs agent-browser — Comprehensive Comparison

> **Date**: March 16, 2026 (updated from March 1 comparison)
> **Versions**: spel 0.7.2 (Playwright 1.50.0) · agent-browser 0.20.11 (100% native Rust, direct CDP)
> **Method**: Hands-on dogfood of github.com + benchmarks on example.org
> **Platform**: macOS (Apple Silicon)

---

## Executive Summary

**spel is a browser automation _platform_. agent-browser is a browser automation _platform too_ now.**

Both tools provide shell-friendly browser automation designed for AI agents. Since the last comparison (March 1, 2026), the biggest change is agent-browser's architecture: v0.20.0 (March 14, 2026) dropped Node.js and Playwright entirely, rewriting to 100% native Rust with direct CDP communication. Install size dropped from 710MB to 7MB. Memory from 143MB to 8MB. They also added a native daemon, so the process-per-command overhead is largely gone.

spel 0.7.2 added: `find-scrollable` (scrollable element discovery), `scroll-position`, `smooth-scroll-to`/`smooth-scroll-by`, page-level `keyboard-press`, `allure-ct-reporter` for `clojure.test`, and `snapshot -S --styles` for computed CSS styles (fixing a gap from the last comparison).

spel still wins on per-command speed, programmability, testing/CI infrastructure, and total API coverage. agent-browser now leads on install size, binary footprint, and ease of setup. The performance gap narrowed significantly — from 9-17× to 2.4-31× depending on command — because AB's native daemon eliminated most of the Node.js startup overhead. The remaining gap is IPC latency vs CDP round-trip latency.

---

## 1. At a Glance

| Dimension | spel | agent-browser |
|---|---|---|
| Language | Clojure → GraalVM native image | 100% native Rust daemon (direct CDP) |
| Binary size | 71 MB (self-contained) | 7 MB (self-contained, no Node.js) |
| CLI commands | ~149 | ~160+ |
| Architecture | Long-running daemon (IPC) | Long-running daemon (CDP) |
| Programmability | Full Clojure scripting (`eval-sci`) | JS `eval` only |
| Testing framework | Built-in (Allure, assertions, codegen) | None |
| CI tooling | `ci-assemble`, `merge-reports`, `init-agents` | None |
| Diff engine | ❌ | ✅ (snapshot diff, pixel diff, URL diff) |
| Auth vault | ❌ | ✅ (AES-256-GCM encrypted) |
| Chrome extensions | ✅ `--extension` | ✅ `--extension` |
| Chrome profile | ✅ `--profile` (Edge/Chrome/Brave) | ✅ `--profile` (Brave auto-discovery) |
| iOS support | ❌ | ✅ (Appium + Xcode) |
| Cloud browsers | ❌ | ✅ (BrowserBase, Kernel, BrowserUse, browserless.io) |
| Requires Node.js | No | No (dropped in 0.20.0) |
| License | Source-available | Apache-2.0 |

---

## 2. Performance Benchmarks

Tested on `https://example.org` — cold start (first `open`) then sequential commands.

### Version check + First `open`

| Command | spel | agent-browser 0.20.11 | Speedup |
|---|---|---|---|
| Version check | **6ms** | 2ms | AB 3× faster |
| First open (cold) | **~1.0s** | 1.8s | spel 1.8× faster |

Version check favors AB — their native Rust binary starts faster than spel's GraalVM image. First open favors spel — AB's CDP setup adds overhead on cold start.

### Subsequent Commands (after browser is open)

| Command | spel | agent-browser 0.20.11 | Speedup |
|---|---|---|---|
| `snapshot -i --json` | **68ms** | 166ms | **spel 2.4×** |
| `screenshot` | **43ms** | 195ms | **spel 4.5×** |
| `get title` | **8ms** | 155ms | **spel 19×** |
| `get url` | **5ms** | 154ms | **spel 31×** |

**spel is 2.4–31× faster per command** after the browser is open. The gap narrowed significantly from the previous comparison (9–17×) — agent-browser's native daemon eliminated most of the Node.js startup overhead. The remaining gap is IPC latency (spel's Unix socket) vs CDP round-trip latency (AB's direct CDP).

The `get title`/`get url` gap (19–31×) is larger than `snapshot` (2.4×) because those are pure metadata reads — spel returns them from in-process state, while AB still needs a CDP round-trip.

For a 20-command session: ~1.3s total overhead for spel vs ~3.3s for agent-browser. For a 200-command E2E test suite: ~13s vs ~33s of pure overhead. Meaningful, but no longer the order-of-magnitude difference it was.

---

## 3. Snapshot Quality (Critical for AI Agents)

Snapshots are the primary way AI agents "see" a page. This is arguably the most important feature.

### Format Comparison

**spel** (example.org, `-i --json`, actual output from v0.5.0):
```
  - heading "Example Domain" [@e2yrjz] [level=1]
  - paragraph "This domain is for use in..." [@e9mter]
  - paragraph [@e2m4ty]
    - link "Learn more" [@e6t2x4] [url=https://iana.org/domains/example]
```

**agent-browser** (github.com, `-i -c -d 3`):
```
- link "Skip to content" [ref=e1]
- link "Homepage" [ref=e2]
- button "Platform" [ref=e3]
- button "Solutions" [ref=e4]
- textbox "Enter your email" [ref=e12]
- button "Sign up for GitHub" [ref=e13]
- link "Sign in" [ref=e10]
...
```

### Verdict: Different trade-offs

| Aspect | spel | agent-browser | Better for... |
|---|---|---|---|
| **Ref format** | `[@e2yrjz]` (6-char hash) | `[ref=e1]` (sequential) | AB — sequential refs are simpler for LLMs |
| **Tree structure** | Preserves DOM hierarchy | Flat list of interactive elements | Depends — hierarchy aids context, flat aids action |
| **Link URLs** | ✅ `[url=https://...]` inline | ✅ `/url: https://...` inline | Tie — both show link destinations |
| **Refs in JSON** | Full `refs` map with role/name/url + `pages`, `network`, `console` | Full `refs` map with role/name | spel — richer structured metadata |
| **Metadata** | URL, title, description | Success/error wrapper | spel — richer page context |
| **Size (github.com -i)** | 314 lines | 107 lines | AB — 3× more compact, less token waste |
| **Size (github.com -i -c -d 3)** | 26 lines | 107 lines | spel — depth limit is very effective |
| **Structural context** | Regions, landmarks, headings | Just interactive elements | spel — helps LLMs understand page layout |
| **Compact mode** | `-c` removes empty structural nodes | Already flat | spel — more tuning knobs |

**Key insight**: agent-browser's flat interactive-only snapshot is _excellent_ for LLM agents that just need to pick an action. spel's hierarchical snapshot is better for understanding page structure. Both are valid approaches for different use cases.

### JSON Output

**spel** (actual v0.5.0 output, truncated):
```json
{
  "snapshot": "- heading \"Example Domain\" [@e2yrjz] [level=1]\n  ...",
  "refs_count": 4,
  "refs": {
    "e2yrjz": {"role": "heading", "name": "Example Domain", "level": 1},
    "e6t2x4": {"role": "link", "name": "Learn more", "url": "https://iana.org/domains/example"}
  },
  "url": "https://example.org/",
  "title": "Example Domain",
  "pages": [{"ref": "@p1", "url": "https://example.org/", "status": 200, "title": "Example Domain"}],
  "network": [{"ref": "@n1", "method": "GET", "url": "https://example.org/", "status": 200}],
  "console": []
}
```

**agent-browser**:
```json
{
  "success": true,
  "data": {
    "refs": {
      "e1": {"name": "Example Domain", "role": "heading"},
      "e2": {"name": "Learn more", "role": "link"}
    },
    "snapshot": "- heading \"Example Domain\" [ref=e1]..."
  },
  "error": null
}
```

**spel's JSON is now significantly richer** — it includes the `refs` map (role/name/url per element), plus `pages` (navigation history with status codes), `network` (request/response summaries), and `console` (log messages). agent-browser has the `refs` map but lacks the page-level observability metadata. **For machine consumption, spel's format is more complete.**

---

## 4. Command Surface Area

### Commands spel has that agent-browser doesn't

| Category | Commands | Why it matters |
|---|---|---|
| **Annotation overlays** | `annotate`, `unannotate` with `--scope`, `--no-badges`, custom styles | Full visual overlay control (AB has `--annotate` flag for numbered labels, but no programmatic overlay/unannotate/scope) |
| **Stitch** | `stitch <imgs...>` | Combine screenshots into one image |
| **Google search** | `search <query>` | Built-in Google search from CLI |
| **Codegen** | `codegen record`, `codegen [file]` | Record → Clojure replay scripts |
| **CI tools** | `ci-assemble`, `merge-reports`, `init-agents` | Full CI/CD pipeline support |
| **Allure reporting** | Built-in test reporting framework | Structured test results with screenshots |
| **Inspector** | `inspector [url]`, `show-trace` | Playwright Inspector + Trace Viewer |
| **Chrome cookie export** | `state export` | Extract cookies from Chrome/Edge/Brave profiles |
| **Scrollable discovery** | `find-scrollable`, `scroll-position`, `smooth-scroll-to`, `smooth-scroll-by` | Discover and interact with scrollable containers |


### Commands agent-browser has that spel doesn't (at CLI level)

> **Important**: Many items below exist in spel's Clojure library and SCI (`eval-sci`) layer but lack a dedicated CLI daemon command. They are accessible via `spel eval-sci '(page/set-content! "...")` etc. Items marked ⚡ exist in library/SCI.

| Category | Commands | CLI-only? | Why it matters |
|---|---|---|---|
| **Diff engine** | `diff snapshot`, `diff screenshot`, `diff url` | ⚡ CLI exists | spel has `diff snapshot --baseline` (text diff) and `diff screenshot --baseline` (pixel comparison with threshold + diff image). Only `diff url` (compare two URLs) is missing |
| **Auth vault** | `auth save/login/list/delete/show` | ✅ Truly missing | AES-256-GCM encrypted credential store with auto-login |
| **Computed styles** | `get styles <sel>` | ⚡ Library/SCI | `snapshot -S --styles` (--minimal/default/--max); computed CSS styles in snapshot output |
| **Clipboard** | `clipboard read/write/copy/paste` | ⚡ CLI exists | spel has `clipboard copy`, `clipboard read`, `clipboard paste` as CLI commands |
| **Action policy** | `confirm`, `deny`, `--allowed-domains` | ✅ Truly missing | Safety guardrails for AI agents |
| **WebSocket streaming** | `AGENT_BROWSER_STREAM_PORT` env var | ✅ Truly missing | WebSocket live browser streaming (env-var activated, no CLI command) |
| ~~**Input injection**~~ | ~~`input_mouse`, `input_keyboard`, `input_touch`~~ | ❓ Removed | Was in 0.15.x, not in 0.20.11 `--help` — likely removed in Rust rewrite |
| **Profiler** | `profiler start/stop` | ✅ Truly missing | Chrome DevTools performance profiling |
| **Config file** | `agent-browser.json` | ✅ Truly missing | Cascading configuration |
| **iOS Simulator** | `-p ios`, `swipe`, `device list` | ✅ Truly missing | Real mobile Safari testing via Appium |
| **Cloud browsers** | `-p browserbase/kernel/browseruse` | ✅ Truly missing | Serverless browser instances |
| ~~**Window management**~~ | ~~`window new`~~ | ❓ Removed | Was in 0.15.x, not in 0.20.11 `--help` — likely folded into tab management |
| **DevTools inspect** | `inspect` | 🟡 Different | AB opens Chrome DevTools for the active page; spel has `inspector` which launches Playwright Inspector (locator picker / recorder) — different tools |
| **CDP URL** | `get cdp-url` | ✅ Truly missing | Expose CDP endpoint for external tools |
| **Screenshot options** | `--dir`, `--quality`, `--format` | ✅ Truly missing | Screenshot output directory, JPEG quality, format selection |
| **Alternative engine** | `--engine lightpanda` | ✅ Truly missing | Lightweight headless browser engine option |
| **Brave auto-discovery** | Automatic Brave detection | ✅ Truly missing | Finds Brave binary without explicit path |
| **Linux musl** | `spel-linux-musl` binary | ✅ Truly missing | Alpine Linux / musl libc support |
| **browserless.io** | `-p browserless` | ✅ Truly missing | Cloud browser via browserless.io provider |
| **HAR recording** | `har start/stop` | ⚡ Library/SCI | `:record-har-path` context opt, `route-from-har!`, auto-included in `with-traced-page` |
| **Permissions** | `permissions` grant/revoke | ⚡ Library/SCI | `context-grant-permissions!`, `context-clear-permissions!` in library+SCI |
| **Timezone** | `timezone <tz>` | ⚡ Library | `:timezone-id` context option at launch |
| **Locale** | `locale <loc>` | ⚡ Library | `:locale` context option at launch |
| **Touch tap** | `tap <sel>` | ⚡ Library/SCI | `locator/tap-element`, `sci-tap` |
| **Content injection** | `addscript`, `addstyle`, `addinitscript` | ⚡ Library/SCI | `page/add-script-tag`, `page/add-style-tag`, `page/add-init-script` |
| **Expose function** | `expose <name>` | ⚡ Library/SCI | `page/expose-function!`, `page/expose-binding!` |
| **Set content** | `setcontent <html>` | ⚡ Library/SCI | `page/set-content!` |
| **Dispatch event** | `dispatch <sel> <event>` | ⚡ Library/SCI | `locator/dispatch-event` |
| **Wait for download** | `waitfordownload` | ⚡ Library/SCI | `page/wait-for-download` |
| **Bring to front** | `bringtofront` | ⚡ Library/SCI | `page/bring-to-front` |
| **Multi-select** | `multiselect <sel> <vals>` | ⚡ Library/SCI | `locator/select-option` accepts multiple values |
| **Response body** | `responsebody <url>` | ⚡ Library | `page/wait-for-response` + `.text()` |
| **Video start/stop** | `video start/stop` | ⚡ Library/SCI | `start-video-recording`/`finish-video-recording` in SCI; `:record-video-dir` context option |
| **Select all** | `selectall <sel>` | ⚡ Trivial | `press "Control+a"` — one-liner composition |
| **Set value** | `setvalue <sel> <val>` | ⚡ Trivial | `eval "el.value = x"` — one-liner |
| **Pause** | `pause` | ⚡ Alternative | `spel inspector` / `spel --headed` covers debugging |

### Shared (both have)

open, click, dblclick, type, fill, press (page-level), keyboard type/inserttext, keydown/keyup, hover, focus, check/uncheck, select, drag, upload, scroll, scrollintoview, wait (selector/timeout/text/url/load/fn), screenshot, pdf, snapshot (-i/-c/-d/-s/--flat), eval, connect (CDP), close, sessions, viewport, device emulation, proxy, user-agent, stealth, JSON output, `--headed` mode, dialog accept/dismiss, frame switch/main/list, tab new/switch/close/list, mouse move/down/up/wheel, cookies get/set/clear, storage local/session get/set/clear, state save/load/list/show/rename/clear/clean, set media/geo/offline/headers/credentials, network route/unroute/requests/clear, get text/html/value/attr/url/title/count/box, is visible/enabled/checked, find role/text/label/placeholder/alt/title/testid/first/last/nth, trace start/stop, console/errors, highlight, download, clipboard read/write/paste, --extension, --profile, back/forward/reload.

---

## 5. Programmability

### spel: Full Clojure Scripting

```clojure
;; Run as: spel eval-sci script.clj
(spel/navigate "https://example.org")
(let [snapshot (spel/capture-snapshot {:interactive? true})
      title    (:title snapshot)
      links    (spel/evaluate "Array.from(document.querySelectorAll('a')).map(a => a.href)")]
  (doseq [link links]
    (spel/navigate link)
    (spel/screenshot (str "screenshots/" (hash link) ".png"))
    (expect/to-have-title (spel/page) #".*")  ;; assertion
    (allure/step (str "Visited " link)        ;; test reporting
      (allure/attach-screenshot))))
```

100+ SCI functions across namespaces: `spel/`, `input/`, `locator/`, `expect/`, `allure/`, `page/`, `browser/`. You can write entire test suites, data pipelines, and automation workflows as Clojure scripts.

### agent-browser: JavaScript eval only

```bash
# This is the extent of agent-browser's "scripting"
agent-browser eval "document.title"
agent-browser eval "Array.from(document.querySelectorAll('a')).map(a => a.href)"
```

No loops, no assertions, no file I/O, no test reporting. To build a multi-page workflow, you chain shell commands:

```bash
agent-browser open https://example.org
agent-browser snapshot -i
agent-browser click @e2
agent-browser wait --load networkidle
agent-browser screenshot page2.png
```

This works, but you're limited to what bash can do. No structured data manipulation, no error recovery, no conditional logic beyond `if/else` in shell.

### Verdict

**spel's programmability is in a different league.** It's the difference between a calculator and a spreadsheet. agent-browser is fine for simple linear automation; spel can build entire testing frameworks.

---

## 6. Pitfalls & Weaknesses

### spel Pitfalls

| Issue | Severity | Details |
|---|---|---|
| ~~**Bad URL silently succeeds**~~ | ✅ Fixed in 0.5.0 | `spel open not-a-url` now returns exit code 1 with clear error message. |
| ~~**Generic error messages**~~ | ✅ Fixed in 0.5.0 | `spel click @nonexistent` now propagates full Playwright error context including call log, selector, and reason. |
| ~~**No link URLs in snapshots**~~ | ✅ Fixed in 0.5.0 | Links now show `[url=https://...]` inline in snapshot tree. |
| ~~**No structured refs in JSON**~~ | ✅ Fixed in 0.5.0 | JSON snapshot now includes full `refs` map with role/name/url per ref, plus `pages`, `network`, `console` metadata. |
| **71MB binary** | 🟡 Medium | Large for a CLI tool. GraalVM native image trade-off. Gap widened against spel — AB is now 7MB vs spel's 71MB (10× difference). |
| **Snapshot verbosity** | 🟡 Medium | More tokens for LLMs than agent-browser’s flat format. Mitigated by `--flat`, `-c`, `-d` flags. |
| **Ref format less LLM-friendly** | 🟠 Low | `[@e2yrjz]` (6 random chars) vs `[ref=e1]` (sequential). Sequential is easier for LLMs to reference. |
| ~~**Video save-as bug**~~ | ✅ Fixed in 0.5.0 | Video `save-as` now correctly closes page/context before `saveAs`. |
| ~~**No diff engine**~~ | ✅ Done | spel has `diff snapshot --baseline` and `diff screenshot --baseline` (pixel comparison with threshold + diff image output). |
| **No auth vault** | 🟠 Low | No encrypted credential store. agent-browser has AES-256-GCM auth vault. |
| **No iOS/cloud browser support** | 🟠 Low | Missing for teams that need real mobile testing or serverless browsers. |

### agent-browser Pitfalls

> **Note**: agent-browser 0.20.11 fixed the biggest issue from the 0.15.1 comparison — the Node.js dependency and process-per-command overhead are gone. The table below reflects remaining issues and notes what changed.

| Issue | Severity | Details |
|---|---|---|
| **Still slower per command** | 🟡 Medium | Native daemon helps a lot, but CDP round-trips are still 2.4–31× slower than spel's IPC. Metadata reads (`get title`, `get url`) are the worst case. |
| **No test/assertion framework** | 🟡 Medium | Still no built-in way to assert page state. Must build from scratch. |
| **No CI tooling** | 🟠 Low | No report generation, no CI assembly, no report merging. |
| **No codegen** | 🟠 Low | No record-and-replay code generation. |
| ✅ Now 100% native Rust — no Node.js | | Dropped Node.js + Playwright in v0.20.0 (March 14, 2026). Direct CDP. |
| ✅ Install size: 710MB → 7MB | | Massive improvement. Was the biggest practical complaint. |
| ✅ Memory: 143MB → 8MB | | Idle memory footprint dropped dramatically. |
| ✅ Now has native daemon | | No more process-per-command. Daemon persists between commands. |
| ✅ Now has network interception | | `route/unroute/requests` added since 0.9.0 |
| ✅ Now has cookie/storage management | | Full CRUD for cookies and localStorage/sessionStorage |
| ✅ Now has trace recording | | `trace start/stop` added |
| ✅ Now has console/errors capture | | `console`, `errors` commands added |
| ✅ Now has dialog handling | | `dialog accept/dismiss` added |
| ✅ Now has frame support | | `frame`/`mainframe` added |
| ✅ Now has state persistence | | Full `state save/load/list/show/rename/clear/clean` |
| ✅ Now has tab management | | `tab_new/list/switch/close` + `window_new` |
---

## 7. Dogfood Results (github.com)

We ran the same dogfood scenario — systematic exploration of github.com — with both tools.

### spel Dogfood (completed)

| Metric | Value |
|---|---|
| Pages visited | 12 |
| Screenshots taken | 29 |
| Video | ✅ 13 MB WebM → 4.5 MB MP4 with SRT subtitles |
| Transcript | ✅ 24 entries |
| HTML report | ✅ 472-line report with findings |
| Findings identified | 5 (2 medium, 3 low severity) |
| Total output | 36 MB |
| Completion | Full |

### agent-browser Dogfood (stalled)

| Metric | Value |
|---|---|
| Pages visited | 2 |
| Screenshots taken | 4 |
| Video | ❌ Recording started but produced 0 bytes |
| Transcript | ❌ Not possible (no built-in capability) |
| HTML report | ❌ Not possible (no reporting framework) |
| Findings identified | N/A |
| Total output | 796 KB |
| Completion | ~15% — stalled due to tool limitations |

### Why agent-browser stalled

> **Note**: This dogfood was run against agent-browser 0.15.1 (Node.js-based). The architecture has since changed significantly in 0.20.0. Points 1-4 remain valid; point 5 is partially addressed by the native daemon.

1. **Video recording was broken** — `record start` appeared to succeed but the video directory remained empty
2. **`--annotate` timed out** — couldn't use visual annotations on github.com
3. **No transcript capability** — no way to generate structured output during a session
4. **No reporting** — no HTML template, no findings format, no structured output
5. **Slowness** — each command taking 200ms+ made the workflow painfully slow for 12-page exploration (native daemon in 0.20.0 reduces this to ~155-195ms, still slower than spel's 5-68ms)

### What agent-browser did well

- Screenshots were clean and properly sized
- Mobile viewport (`set viewport 375 812`) worked correctly
- Session management (`--session github-df`) worked reliably
- URL extraction via `eval` worked fine

---

## 8. Ease of Use

### For AI Agents (MCP/tool-use)

| Aspect | spel | agent-browser | Notes |
|---|---|---|---|
| **Snapshot token efficiency** | 🟡 | 🟢 | AB is 3× more compact by default |
| **Ref simplicity** | 🟡 | 🟢 | `@e1` vs `@e2yrjz` |
| **Link discovery** | 🟢 | 🟢 | Both include URLs in snapshots (spel: `[url=...]`, AB: `/url: ...`) |
| **Command speed** | 🟢 | 🟡 | spel's daemon = instant commands |
| **Error recovery** | 🟢 | 🟡 | spel has more wait/retry strategies |
| **Page understanding** | 🟢 | 🟡 | spel's hierarchy aids comprehension |
| **Structured JSON** | 🟢 | 🟡 | spel has `refs` map + `pages`/`network`/`console` metadata; AB has `refs` map only |

### For Human Developers

| Aspect | spel | agent-browser | Notes |
|---|---|---|---|
| **Installation** | `make install-local` (build from source) | Single 7MB binary, no dependencies | AB is trivially installable; gap widened in AB's favor |
| **Learning curve** | Steeper (149+ commands, Clojure eval) | Steeper now too (160+ commands, JS eval) | Both are complex tools now |
| **Documentation** | Excellent `--help`, FULL_API.md | Good `--help` | Both adequate |
| **Debugging** | Trace viewer, console, errors, inspector | None | spel by a mile |
| **Power ceiling** | Very high (full scripting, CI, reporting) | Low (shell piping only) | spel for serious work |
| **Setup overhead** | GraalVM build chain, Clojure ecosystem | Download single binary, run | AB wins on simplicity |

### For Test Engineers

| Aspect | spel | agent-browser |
|---|---|---|
| **Assertion library** | ✅ Built-in `expect/` namespace | ❌ None |
| **Test reporting** | ✅ Allure integration | ❌ None |
| **CI pipeline** | ✅ `ci-assemble`, `merge-reports` | ❌ None |
| **Codegen** | ✅ Record → Clojure scripts | ❌ None |
| **State management** | ✅ Save/load/export auth state | 🟡 Save/load (+ auth vault) |
| **Network mocking** | ✅ Route/unroute with body/abort | ✅ Route/unroute added in 0.15 |
| **Multi-tab** | ✅ Tab management | ✅ Tab management added in 0.15 |
| **Parallel sessions** | ✅ Named sessions | ✅ Named sessions |

---

## 9. Architecture Deep-Dive

### spel: Daemon + IPC

```
[CLI binary] --IPC--> [daemon process] --persistent--> [Chromium]
    6ms                  always running                  always open
```

The first `spel open` launches a daemon that stays alive. Subsequent commands are near-instant IPC calls (5-68ms depending on command). The browser stays open between commands. State (cookies, localStorage) auto-persists.

**Pros**: Blazing fast subsequent commands, persistent state, rich error context.
**Cons**: Daemon must be managed (start/stop), 71MB binary, occasional stale state.

### agent-browser: Native Rust Daemon (direct CDP)

```
[CLI binary] --CDP--> [Rust daemon] --CDP--> [Chromium]
    2ms                 always running          always open
```

As of v0.20.0 (March 14, 2026), agent-browser dropped Node.js and Playwright entirely. It's now a 100% native Rust binary that speaks Chrome DevTools Protocol directly. A daemon process persists between commands, keeping the browser connection alive. Install size dropped from 710MB to 7MB; idle memory from 143MB to 8MB.

**Pros**: Tiny footprint (7MB), no Node.js dependency, native daemon, crash-isolated, fast cold start.
**Cons**: CDP round-trips are still slower than spel's Unix socket IPC (155ms vs 5-8ms for metadata reads). No persistent server-side state beyond what CDP provides.

---

## 10. When to Use Which

### Use spel when:
- Building E2E test suites (Allure, assertions, codegen)
- Running CI pipelines (report assembly, merging)
- Complex automation (network mocking, state management, multi-tab)
- Performance matters (daemon = 2.4–31× faster per command depending on command type)
- You need programmability (Clojure scripting)
- Debugging is important (traces, console, inspector)
- You're already in the Clojure ecosystem

### Use agent-browser when:
- Quick LLM agent integration (simpler snapshot format, sequential refs)
- iOS mobile testing (Appium integration)
- Cloud browser instances (BrowserBase, Kernel, browserless.io)
- Minimal setup needed (download 7MB binary, done — no Node.js, no build chain)
- Simple linear automation (open → click → screenshot)
- Token efficiency matters (compact snapshots)
- Binary footprint matters (7MB vs 71MB)
- You want the simplest possible browser CLI

---

## 11. Feature Wishlist (What Each Could Steal)

### spel should consider adopting from agent-browser:
1. ~~**Sequential refs**~~ — ❌ Rejected — hash refs are stable across snapshots; sequential refs break cross-snapshot reference in multi-step agent workflows
2. ~~**Link URLs in snapshots**~~ — ✅ Done in spel 0.5.0 (`[url=https://...]` inline)
3. ~~**Structured refs in JSON**~~ — ✅ Done in spel 0.5.0 (full `refs` map with role/name/url + `pages`, `network`, `console`)
4. ~~**Diff engine**~~ — ✅ Done in spel (`diff snapshot --baseline`, `diff screenshot --baseline` with threshold + diff image; library: `visual-diff/compare-screenshots`, `snapshot/diff-snapshots`). Only `diff url` (two-URL comparison) is missing
5. ~~**Computed styles**~~ — ✅ Done in spel 0.7.2 (`snapshot -S --styles` with `--minimal`/default/`--max`)
6. ~~**Clipboard**~~ — ✅ Done in spel (`clipboard copy`, `clipboard read`, `clipboard paste` CLI commands)
7. ~~**Extension loading**~~ — ✅ Done in spel 0.5.0 (`--extension`)
8. ~~**`download` command**~~ — ✅ Done in spel 0.5.0
9. ~~**Better error propagation**~~ — ✅ Done in spel 0.5.0
10. ~~**Bad URL detection**~~ — ✅ Done in spel 0.5.0
11. ~~**Timezone/locale emulation**~~ — ✅ Already in spel library (`:timezone-id`, `:locale` context options)
12. ~~**Content injection**~~ — ✅ Already in spel library+SCI (`page/add-script-tag`, `page/add-style-tag`, `page/add-init-script`)
13. ~~**HAR recording**~~ — ✅ Already in spel library (`:record-har-path`, `:record-har-mode`, `route-from-har!`; auto-included in `with-traced-page`)
14. ~~**Permissions**~~ — ✅ Already in spel library+SCI (`context-grant-permissions!`, `context-clear-permissions!`)
15. ~~**Touch tap**~~ — ✅ Already in spel library+SCI (`locator/tap-element`)
16. ~~**Expose function**~~ — ✅ Already in spel library+SCI (`page/expose-function!`, `page/expose-binding!`)
17. ~~**Set content**~~ — ✅ Already in spel library+SCI (`page/set-content!`)
18. ~~**Dispatch event**~~ — ✅ Already in spel library+SCI (`locator/dispatch-event`)
19. ~~**Wait for download**~~ — ✅ Already in spel library+SCI (`page/wait-for-download`)
20. ~~**Bring to front**~~ — ✅ Already in spel library+SCI (`page/bring-to-front`)
21. **Auth vault** — encrypted credential store — low priority, `state save/load` covers 80%
22. ~~**Scrollable discovery**~~ — ✅ Done in spel 0.7.2 (`find-scrollable`, `scroll-position`, `smooth-scroll-to`, `smooth-scroll-by`)

### agent-browser should consider adopting from spel:
1. ~~**Daemon architecture**~~ — ✅ Done in AB 0.20.0 (native Rust daemon, direct CDP)
2. ~~**Network interception**~~ — ✅ Done in AB 0.15
3. ~~**Cookie/storage CRUD**~~ — ✅ Done in AB 0.15
4. ~~**Dialog handling**~~ — ✅ Done in AB 0.15
5. ~~**Frame navigation**~~ — ✅ Done in AB 0.15
6. ~~**Trace recording**~~ — ✅ Done in AB 0.15
7. ~~**Console/error capture**~~ — ✅ Done in AB 0.15
8. ~~**Tab management**~~ — ✅ Done in AB 0.15
9. **Assertions** — still no built-in state checking or testing framework
10. **CI tooling** — still no report generation and assembly
11. **Annotation overlays** — spel's scoped annotate with role-colored overlays
12. **Codegen** — record-and-replay code generation
---

## 12. Final Verdict

| Category | Winner | Margin |
|---|---|---|
| **Raw performance (per command)** | spel | Medium (2.4–31× depending on command; was 9–17×) |
| **Cold start** | agent-browser | Small (1.8s vs ~1.0s for first open; 2ms vs 6ms for version check) |
| **Command breadth** | Tie | Both ~149-160+ CLI commands. spel has ~460 SCI functions + full Clojure library on top |
| **Programmability** | spel | Massive (full Clojure vs JS eval) |
| **Testing/CI** | spel | Total (AB still has nothing) |
| **Debugging** | spel | spel has Inspector + Trace Viewer + HAR (via library). AB has profiler (CDP-only) |
| **Diff tooling** | Tie | Both have snapshot diff and pixel diff. AB also has `diff url` (two-URL comparison) |
| **Auth management** | agent-browser | Medium (encrypted vault vs state save/load — state files cover 80%) |
| **LLM snapshot ergonomics** | Tie | Both have link URLs and structured refs. AB is more compact; spel has richer metadata |
| **Safety/policy** | agent-browser | Total (action confirmation, domain allowlist) |
| **Ease of installation** | agent-browser | Large (7MB self-contained binary vs build from source; gap widened in AB's favor) |
| **Binary footprint** | agent-browser | Large (7MB vs 71MB — 10× difference; was 4.4MB+node_modules vs 71MB) |
| **Learning curve** | Tie | Both at ~149-160+ CLI commands; neither is simple anymore |
| **Mobile testing** | agent-browser | Total (iOS support) |
| **Cloud browsers** | agent-browser | Total (4 providers including browserless.io) |
| **Error messages** | Tie | Both now propagate errors well |
| **Documentation** | Tie | Both have good --help |

**Overall: spel has near-complete feature parity** when you count its 3 layers (CLI + SCI + library). Diff tooling (`diff snapshot`, `diff screenshot`), computed styles (`snapshot -S --styles`), clipboard (`clipboard copy/read/paste`), and scrollable discovery (`find-scrollable`) are all done. The only remaining CLI gap is `diff url` (two-URL comparison).

The competitive landscape shifted in March 2026. agent-browser's v0.20.0 rewrite to native Rust was a significant leap — it's no longer a "Node.js wrapper" and the install story is dramatically better (7MB, no dependencies). The performance gap narrowed from 9–17× to 2.4–31×. The binary size gap widened against spel (10× now vs roughly equal before when you counted node_modules).

**spel = testing platform + per-command speed + full Playwright API coverage + Clojure programmability.**
**agent-browser = tiny footprint + easy install + diff tooling + auth vault + mobile/cloud.**

The "breadth gap" is still largely illusory — most AB features exist in spel's library, just not as standalone CLI commands. But the "ease of adoption" gap is real and widened. For teams that just want a browser CLI without a build chain, agent-browser is now a more compelling choice than it was six months ago.

---

*Updated March 16, 2026. Previous update: March 1, 2026. Original comparison: February 26, 2026.*
