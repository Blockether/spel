# spel vs agent-browser — Comprehensive Comparison

> **Date**: February 26, 2026
> **Versions**: spel 0.4.1 (Playwright 1.58.0) · agent-browser 0.9.0 (Playwright via Node.js)
> **Method**: Hands-on dogfood of github.com + benchmarks on example.com
> **Platform**: macOS (Apple Silicon)

---

## Executive Summary

**spel is a browser automation _platform_. agent-browser is a browser automation _CLI_.**

Both tools wrap Playwright behind a shell-friendly interface designed for AI agents. But they serve different scopes: agent-browser does the basics well and stays minimal. spel does the basics, the advanced stuff, the testing infrastructure, the CI pipeline, and the programmability layer.

spel wins on breadth, speed, and programmability. agent-browser wins on snapshot ergonomics for LLMs and has a few niche features (iOS, cloud browsers) that spel lacks. Both have real pitfalls.

---

## 1. At a Glance

| Dimension | spel | agent-browser |
|---|---|---|
| Language | Clojure → GraalVM native image | Rust CLI shell → Node.js Playwright |
| Binary size | 71 MB (self-contained) | 4.4 MB node_modules (requires Node.js) |
| CLI commands | ~90+ | ~30 |
| Architecture | Long-running daemon (IPC) | Process-per-command |
| Programmability | Full Clojure scripting (`--eval`) | JS `eval` only |
| Testing framework | Built-in (Allure, assertions, codegen) | None |
| CI tooling | `ci-assemble`, `merge-reports`, `init-agents` | None |
| iOS support | ❌ | ✅ (Appium + Xcode) |
| Cloud browsers | ❌ | ✅ (BrowserBase, Kernel, BrowserUse) |
| License | Source-available | MIT |

---

## 2. Performance Benchmarks

Tested on `https://example.com` — cold start (first `open`) then sequential commands.

### Cold Start (version check)

| Tool | Time |
|---|---|
| spel | **24ms** |
| agent-browser | 61ms |

spel is 2.5× faster for the trivial case (native binary vs Node.js startup).

### First `open` (browser launch + navigation)

| Tool | Time |
|---|---|
| spel | **1.034s** |
| agent-browser | 1.140s |

Nearly identical — dominated by Chromium launch time.

### Subsequent Commands (after browser is open)

This is where the daemon architecture crushes process-per-command:

| Command | spel | agent-browser | Speedup |
|---|---|---|---|
| `snapshot -i --json` | **18ms** | 237ms | **13×** |
| `screenshot` | **26ms** | 229ms | **9×** |
| `get title` | **13ms** | 210ms | **16×** |
| `get url` | **12ms** | 210ms | **17×** |
| `close` | **14ms** | 238ms | **17×** |

**spel is 9–17× faster per command** after the browser is open. The daemon keeps a persistent connection; agent-browser spawns a new Node.js process, reconnects to the browser, executes, and exits — every single time.

For a 20-command dogfood session, this means ~0.3s total overhead for spel vs ~4.7s for agent-browser. For a 200-command E2E test suite, it's 3s vs 47s of pure overhead.

---

## 3. Snapshot Quality (Critical for AI Agents)

Snapshots are the primary way AI agents "see" a page. This is arguably the most important feature.

### Format Comparison

**spel** (github.com, `-i -c -d 3`):
```
- region [@e33f1m]
    - link "Skip to content" [@e27w9t]
    - banner [@e3k2ih]
      - heading "Navigation Menu" [@e31tmw] [level=2]
  - main [@e833h0]
      - region [@e6wz3n]
      ...
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
| **Link URLs** | ❌ Not in snapshot | ✅ `/url: https://...` inline | AB — agents can see where links go |
| **Refs in JSON** | `refs_count` only | Full `refs` map with role/name | AB — structured ref metadata |
| **Metadata** | URL, title, description | Success/error wrapper | spel — richer page context |
| **Size (github.com -i)** | 314 lines | 107 lines | AB — 3× more compact, less token waste |
| **Size (github.com -i -c -d 3)** | 26 lines | 107 lines | spel — depth limit is very effective |
| **Structural context** | Regions, landmarks, headings | Just interactive elements | spel — helps LLMs understand page layout |
| **Compact mode** | `-c` removes empty structural nodes | Already flat | spel — more tuning knobs |

**Key insight**: agent-browser's flat interactive-only snapshot is _excellent_ for LLM agents that just need to pick an action. spel's hierarchical snapshot is better for understanding page structure. Both are valid approaches for different use cases.

### JSON Output

**spel**:
```json
{
  "snapshot": "- heading \"Example Domain\" [@e2yrjz]...",
  "refs_count": 4,
  "url": "https://example.com/",
  "title": "Example Domain"
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

agent-browser's JSON is more structured — the `refs` map lets you look up element role/name without parsing the tree text. spel gives you more page-level metadata (URL, title). **For machine consumption, agent-browser's format is slightly better.**

---

## 4. Command Surface Area

### Commands spel has that agent-browser doesn't

| Category | Commands | Why it matters |
|---|---|---|
| **Annotations** | `annotate`, `unannotate` with `--scope`, `--no-badges` | Visual debugging overlays on any page |
| **Network interception** | `network route/unroute/requests/clear` with URL/type/method/status filters | Mock APIs, block tracking, test error states |
| **Full cookie/storage CRUD** | `cookies set/clear`, `storage local/session set/clear` | Auth testing, state manipulation |
| **State management** | `state save/load/list/show/rename/clear/export` | Persist and replay auth across sessions |
| **Mouse control** | `mouse move/down/up/wheel` | Drag operations, canvas interactions, hover states |
| **Dialog handling** | `dialog accept/dismiss` | Confirm/alert handling in test flows |
| **Frame navigation** | `frame <sel>/main/list` | iframe-heavy apps (embeds, payment forms) |
| **Trace & debug** | `trace start/stop`, `show-trace`, `console`, `errors`, `inspector` | Rich post-mortem debugging |
| **Tab management** | `tab/tab new/tab close/tab <n>` | Multi-tab workflows (OAuth, new window) |
| **Get info** | `get text/html/value/attr/url/title/count/box` | 8 dedicated extraction commands |
| **State checks** | `is visible/enabled/checked` | Boolean assertions for test flows |
| **Wait variants** | `wait --text/--url/--load/--fn` | 4 additional wait strategies |
| **Find (semantic)** | `find role/text/label/first/last/nth` with chained actions | Accessible, position-based element targeting |
| **Stitch** | `stitch <imgs...>` | Combine screenshots into one image |
| **Device emulation** | `set device <name>` (named devices) | Quick mobile testing without manual viewport |
| **Media/geo/offline** | `set media dark\|light`, `set geo`, `set offline` | Environment simulation |
| **Key hold/release** | `keydown/keyup` | Modifier key testing, keyboard shortcuts |
| **Google search** | `search <query>` | Search from CLI without browser UI |
| **Codegen** | `codegen record`, `codegen [file]` | Record → Clojure replay scripts |
| **CI tools** | `ci-assemble`, `merge-reports`, `init-agents` | Full CI/CD pipeline support |
| **Allure reporting** | Built-in test reporting framework | Structured test results with screenshots |

### Commands agent-browser has that spel doesn't

| Category | Commands | Why it matters |
|---|---|---|
| **iOS Simulator** | `-p ios`, `device list`, `swipe`, `tap` | Real mobile Safari testing via Appium |
| **Cloud browsers** | `-p browserbase/kernel/browseruse` | Serverless browser instances for CI/scale |
| **Extensions** | `--extension <path>` | Load Chrome extensions (ad-blockers, auth) |
| **Download** | `download <sel> <path>` | Explicit file download by clicking element |
| **WebSocket streaming** | `AGENT_BROWSER_STREAM_PORT` | Live browser state streaming |

### Shared (both have)

open, click, dblclick, type, fill, press, hover, focus, check/uncheck, select, drag, upload, scroll, scrollintoview, wait, screenshot, pdf, snapshot, eval, connect (CDP), close, sessions, viewport, proxy, user-agent, stealth/anti-detection, JSON output, `--headed` mode.

---

## 5. Programmability

### spel: Full Clojure Scripting

```clojure
;; Run as: spel --eval script.clj
(spel/navigate "https://example.com")
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
agent-browser open https://example.com
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
| **Bad URL silently succeeds** | 🔴 High | `spel open not-a-url` navigates to `about:blank` with exit code 0. Should fail like agent-browser does. |
| **Generic error messages** | 🔴 High | `spel click @nonexistent` → "Unknown error". agent-browser gives the full Playwright call log. Playwright's error context is being swallowed. |
| **No link URLs in snapshots** | 🟡 Medium | Snapshot tree doesn't include `href` for links. Agent must use `eval` to extract URLs. agent-browser includes `/url:` inline. |
| **No structured refs in JSON** | 🟡 Medium | JSON snapshot only has `refs_count`, not a map of ref → role/name. agent-browser provides this. |
| **71MB binary** | 🟡 Medium | Large for a CLI tool. GraalVM native image trade-off. |
| **Snapshot verbosity** | 🟡 Medium | 314 lines for github.com `-i` vs agent-browser's 107. More tokens for LLMs. Mitigated by `-c -d` flags. |
| **Ref format less LLM-friendly** | 🟠 Low | `[@e2yrjz]` (6 random chars) vs `[ref=e1]` (sequential). Sequential is easier for LLMs to reference. |
| **Video save-as bug** | 🟠 Low | `finish-video-recording {:save-as "..."}` throws "Page not yet closed". Must use workaround (get path → finish → cp). |
| **No snapshot with no browser returns good error** | ✅ Fine | "No page loaded. Navigate first: spel open <url>" — this is actually good. |
| **No iOS/cloud browser support** | 🟠 Low | Missing for teams that need real mobile testing or serverless browsers. |

### agent-browser Pitfalls

| Issue | Severity | Details |
|---|---|---|
| **No daemon — 9-17× slower per command** | 🔴 High | Every command spawns a new Node.js process. 210-240ms overhead per command adds up fast. |
| **Empty snapshot with no browser** | 🔴 High | `agent-browser snapshot` with no browser open returns `- document` with exit code 0. Should error. |
| **Annotate times out on complex pages** | 🔴 High | `--annotate` flag timed out on github.com. Unusable on real-world sites. |
| **Video recording produced zero bytes** | 🔴 High | `record start` appeared to work but `videos/` directory was empty after recording. Unreliable. |
| **No network interception** | 🟡 Medium | Can't mock APIs, block requests, or test error states. |
| **No cookie/storage management** | 🟡 Medium | Can't inspect or modify cookies/storage. Must use `eval` for everything. |
| **No test/assertion framework** | 🟡 Medium | No built-in way to assert page state. Must build from scratch. |
| **No trace/debug tooling** | 🟡 Medium | No trace recording, no console capture, no error capture. |
| **No dialog handling** | 🟡 Medium | Confirm/alert dialogs will block the page with no way to dismiss. |
| **No frame support** | 🟡 Medium | iframe-heavy apps (payment forms, embeds) are inaccessible. |
| **No state persistence** | 🟡 Medium | `--state` loads state but no save/export/manage workflow. |
| **No tab management** | 🟠 Low | Multi-tab workflows (OAuth redirects, new windows) unsupported. |
| **Requires Node.js runtime** | 🟠 Low | Not self-contained. Needs Node.js installed. |
| **No CI tooling** | 🟠 Low | No report generation, no CI assembly, no report merging. |

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

1. **Video recording was broken** — `record start` appeared to succeed but the video directory remained empty
2. **`--annotate` timed out** — couldn't use visual annotations on github.com
3. **No transcript capability** — no way to generate structured output during a session
4. **No reporting** — no HTML template, no findings format, no structured output
5. **Slowness** — each command taking 200ms+ made the workflow painfully slow for 12-page exploration

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
| **Link discovery** | 🟡 | 🟢 | AB includes URLs in snapshots |
| **Command speed** | 🟢 | 🟡 | spel's daemon = instant commands |
| **Error recovery** | 🟢 | 🟡 | spel has more wait/retry strategies |
| **Page understanding** | 🟢 | 🟡 | spel's hierarchy aids comprehension |
| **Structured JSON** | 🟡 | 🟢 | AB's refs map is more machine-friendly |

### For Human Developers

| Aspect | spel | agent-browser | Notes |
|---|---|---|---|
| **Installation** | `make install-local` (build from source) | `bun install -g agent-browser` | AB is trivially installable |
| **Learning curve** | Steeper (90+ commands, Clojure eval) | Gentle (30 commands, JS eval) | AB is simpler to start |
| **Documentation** | Excellent `--help`, FULL_API.md | Good `--help` | Both adequate |
| **Debugging** | Trace viewer, console, errors, inspector | None | spel by a mile |
| **Power ceiling** | Very high (full scripting, CI, reporting) | Low (shell piping only) | spel for serious work |
| **Setup overhead** | GraalVM build chain, Clojure ecosystem | npm/bun install | AB wins on simplicity |

### For Test Engineers

| Aspect | spel | agent-browser |
|---|---|---|
| **Assertion library** | ✅ Built-in `expect/` namespace | ❌ None |
| **Test reporting** | ✅ Allure integration | ❌ None |
| **CI pipeline** | ✅ `ci-assemble`, `merge-reports` | ❌ None |
| **Codegen** | ✅ Record → Clojure scripts | ❌ None |
| **State management** | ✅ Save/load/export auth state | 🟡 Load only |
| **Network mocking** | ✅ Route/unroute with body/abort | ❌ None |
| **Multi-tab** | ✅ Tab management | ❌ None |
| **Parallel sessions** | ✅ Named sessions | ✅ Named sessions |

---

## 9. Architecture Deep-Dive

### spel: Daemon + IPC

```
[CLI binary] --IPC--> [daemon process] --persistent--> [Chromium]
   24ms                  always running                  always open
```

The first `spel open` launches a daemon that stays alive. Subsequent commands are near-instant IPC calls (12-26ms). The browser stays open between commands. State (cookies, localStorage) auto-persists.

**Pros**: Blazing fast subsequent commands, persistent state, rich error context.
**Cons**: Daemon must be managed (start/stop), 71MB binary, occasional stale state.

### agent-browser: Process-per-Command

```
[CLI binary] --spawn--> [Node.js] --reconnect--> [Chromium]
   ~50ms                  ~100ms                   ~50ms
```

Every command spawns a new Node.js process that connects to an existing browser (via `--session`). The browser persists, but the Node.js orchestration layer does not.

**Pros**: Simple model, no daemon to manage, small footprint, crash-isolated.
**Cons**: 200ms+ overhead per command, no persistent server-side state, no streaming.

---

## 10. When to Use Which

### Use spel when:
- Building E2E test suites (Allure, assertions, codegen)
- Running CI pipelines (report assembly, merging)
- Complex automation (network mocking, state management, multi-tab)
- Performance matters (daemon = 10× faster per command)
- You need programmability (Clojure scripting)
- Debugging is important (traces, console, inspector)
- You're already in the Clojure ecosystem

### Use agent-browser when:
- Quick LLM agent integration (simpler snapshot format, sequential refs)
- iOS mobile testing (Appium integration)
- Cloud browser instances (BrowserBase, Kernel)
- Minimal setup needed (npm install, done)
- Simple linear automation (open → click → screenshot)
- Token efficiency matters (compact snapshots)
- You want the simplest possible browser CLI

---

## 11. Feature Wishlist (What Each Could Steal)

### spel should consider adopting from agent-browser:
1. **Sequential refs** (`@e1`, `@e2`) — simpler for LLMs than `@e2yrjz`
2. **Link URLs in snapshots** — `/url: https://...` inline saves an eval call
3. **Structured refs in JSON** — `refs` map with role/name per ref
4. **iOS Simulator support** — real mobile Safari testing
5. **Cloud browser providers** — BrowserBase/Kernel for CI at scale
6. **Extension loading** — `--extension` flag for Chrome extensions
7. **`download` command** — explicit file download
8. **Better error propagation** — pass through Playwright's call log, don't swallow it
9. **Bad URL detection** — fail on obviously invalid URLs instead of `about:blank`

### agent-browser should consider adopting from spel:
1. **Daemon architecture** — 10× command speedup
2. **Network interception** — route/unroute for API mocking
3. **Cookie/storage CRUD** — full state management
4. **Dialog handling** — accept/dismiss for confirm/alert
5. **Frame navigation** — iframe support
6. **Trace recording** — Playwright traces for debugging
7. **Console/error capture** — surface browser errors
8. **Tab management** — multi-tab workflows
9. **Assertions** — built-in state checking
10. **CI tooling** — report generation and assembly
11. **Fix video recording** — it produced zero bytes in our test
12. **Fix annotate on complex pages** — timed out on github.com

---

## 12. Final Verdict

| Category | Winner | Margin |
|---|---|---|
| **Raw performance** | spel | Large (9-17× per command) |
| **Command breadth** | spel | Large (3× more commands) |
| **Programmability** | spel | Massive (full language vs eval) |
| **Testing/CI** | spel | Total (AB has nothing) |
| **Debugging** | spel | Large (trace, console, inspector) |
| **LLM snapshot ergonomics** | agent-browser | Medium (more compact, structured refs) |
| **Ease of installation** | agent-browser | Large (npm vs build from source) |
| **Learning curve** | agent-browser | Medium (simpler surface) |
| **Mobile testing** | agent-browser | Total (iOS support) |
| **Cloud browsers** | agent-browser | Total (3 providers) |
| **Error messages** | Mixed | AB better for Playwright errors, spel better for "no browser" |
| **Documentation** | Tie | Both have good --help |

**Overall: spel is the more capable, faster, and more complete tool by a significant margin.** It's what you reach for when browser automation is core to your workflow. agent-browser is what you reach for when you need a quick, simple browser CLI for an LLM agent and don't need the testing/CI/debugging ecosystem.

The gap will narrow if agent-browser adds a daemon mode and more commands. The gap will widen if spel improves its LLM ergonomics (sequential refs, inline URLs, structured JSON refs) — which would eliminate agent-browser's few advantages while keeping all of spel's.

---

*Generated by dogfooding both tools against github.com on February 26, 2026.*
