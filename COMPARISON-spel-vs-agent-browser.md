# spel vs agent-browser — Comprehensive Comparison

> **Date**: March 1, 2026 (updated from Feb 26 comparison)
> **Versions**: spel 0.5.0 (Playwright 1.58.0) · agent-browser 0.15.1 (Playwright via Node.js)
> **Method**: Hands-on dogfood of github.com + benchmarks on example.org
> **Platform**: macOS (Apple Silicon)

---

## Executive Summary

**spel is a browser automation _platform_. agent-browser is a browser automation _platform too_ now.**

Both tools wrap Playwright behind a shell-friendly interface designed for AI agents. Since the last comparison (Feb 2026), agent-browser has grown from ~30 commands to ~143 — adding diff engine, auth vault, profiler, screencast, and more. spel has grown from ~90 to ~120+ CLI commands (plus ~460 SCI functions and full Clojure library), fixing all P0 bugs and adding extension loading, download command, flat snapshots, link URLs in snapshots, structured refs in JSON, and Edge/Chrome profile support.

spel wins on speed, programmability, testing/CI infrastructure, and total API coverage (most agent-browser features already exist in spel's library/SCI layer). agent-browser leads in diff tooling, auth vault, and mobile. The apparent CLI command count gap (~120 vs ~143) is misleading — spel covers ~120/143 features when you count its library and `--eval` scripting layer.

---

## 1. At a Glance

| Dimension | spel | agent-browser |
|---|---|---|
| Language | Clojure → GraalVM native image | Rust CLI shell → Node.js Playwright |
| Binary size | 71 MB (self-contained) | 4.4 MB node_modules (requires Node.js) |
| CLI commands | ~120+ | ~143 |
| Architecture | Long-running daemon (IPC) | Process-per-command |
| Programmability | Full Clojure scripting (`--eval`) | JS `eval` only |
| Testing framework | Built-in (Allure, assertions, codegen) | None |
| CI tooling | `ci-assemble`, `merge-reports`, `init-agents` | None |
| Diff engine | ❌ | ✅ (snapshot diff, pixel diff, URL diff) |
| Auth vault | ❌ | ✅ (AES-256-GCM encrypted) |
| Chrome extensions | ✅ `--extension` | ✅ `--extension` |
| Chrome profile | ✅ `--profile` (Edge/Chrome/Brave) | ✅ `--profile` |
| iOS support | ❌ | ✅ (Appium + Xcode) |
| Cloud browsers | ❌ | ✅ (BrowserBase, Kernel, BrowserUse) |
| License | Source-available | Apache-2.0 |

---

## 2. Performance Benchmarks

Tested on `https://example.org` — cold start (first `open`) then sequential commands.

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
| **Annotations** | `annotate`, `unannotate` with `--scope`, `--no-badges` | Visual debugging overlays on any page |
| **Stitch** | `stitch <imgs...>` | Combine screenshots into one image |
| **Google search** | `search <query>` | Built-in Google search from CLI |
| **Codegen** | `codegen record`, `codegen [file]` | Record → Clojure replay scripts |
| **CI tools** | `ci-assemble`, `merge-reports`, `init-agents` | Full CI/CD pipeline support |
| **Allure reporting** | Built-in test reporting framework | Structured test results with screenshots |
| **Inspector** | `inspector [url]`, `show-trace` | Playwright Inspector + Trace Viewer |
| **Chrome cookie export** | `state export` | Extract cookies from Chrome/Edge/Brave profiles |

### Commands agent-browser has that spel doesn't (at CLI level)

> **Important**: Many items below exist in spel's Clojure library and SCI (`--eval`) layer but lack a dedicated CLI daemon command. They are accessible via `spel --eval '(page/set-content! "...")` etc. Items marked ⚡ exist in library/SCI.

| Category | Commands | CLI-only? | Why it matters |
|---|---|---|---|
| **Diff engine** | `diff snapshot`, `diff screenshot`, `diff url` | ✅ Truly missing | Snapshot Myers diff, pixel comparison, URL comparison |
| **Auth vault** | `auth save/login/list/delete/show` | ✅ Truly missing | AES-256-GCM encrypted credential store with auto-login |
| **Computed styles** | `get styles <sel>` | ✅ Truly missing | Computed CSS styles (fontSize, color, etc.) |
| **Clipboard** | `clipboard copy/paste/read` | ✅ Truly missing | Clipboard operations with auto permission grant |
| **Action policy** | `confirm`, `deny`, `--allowed-domains` | ✅ Truly missing | Safety guardrails for AI agents |
| **Screencast** | `screencast start/stop` | ✅ Truly missing | WebSocket live browser streaming |
| **Input injection** | `input_mouse`, `input_keyboard`, `input_touch` | ✅ Truly missing | CDP-level pair-browsing / remote control |
| **Profiler** | `profiler start/stop` | ✅ Truly missing | Chrome DevTools performance profiling |
| **Config file** | `agent-browser.json` | ✅ Truly missing | Cascading configuration |
| **iOS Simulator** | `-p ios`, `swipe`, `device list` | ✅ Truly missing | Real mobile Safari testing via Appium |
| **Cloud browsers** | `-p browserbase/kernel/browseruse` | ✅ Truly missing | Serverless browser instances |
| **Window management** | `window new` | ✅ Truly missing | Open new browser window (not just tab) |
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

open, click, dblclick, type, fill, press, keydown/keyup, hover, focus, check/uncheck, select, drag, upload, scroll, scrollintoview, wait (selector/timeout/text/url/load/fn), screenshot, pdf, snapshot (-i/-c/-d/-s/--flat), eval, connect (CDP), close, sessions, viewport, device emulation, proxy, user-agent, stealth, JSON output, `--headed` mode, dialog accept/dismiss, frame switch/main/list, tab new/switch/close/list, mouse move/down/up/wheel, cookies get/set/clear, storage local/session get/set/clear, state save/load/list/show/rename/clear/clean, set media/geo/offline/headers/credentials, network route/unroute/requests/clear, get text/html/value/attr/url/title/count/box, is visible/enabled/checked, find role/text/label/placeholder/alt/title/testid/first/last/nth, trace start/stop, console/errors, highlight, download, --extension, --profile, back/forward/reload.

---

## 5. Programmability

### spel: Full Clojure Scripting

```clojure
;; Run as: spel --eval script.clj
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
| **91MB binary** | 🟡 Medium | Large for a CLI tool. GraalVM native image trade-off. |
| **Snapshot verbosity** | 🟡 Medium | More tokens for LLMs than agent-browser’s flat format. Mitigated by `--flat`, `-c`, `-d` flags. |
| **Ref format less LLM-friendly** | 🟠 Low | `[@e2yrjz]` (6 random chars) vs `[ref=e1]` (sequential). Sequential is easier for LLMs to reference. |
| ~~**Video save-as bug**~~ | ✅ Fixed in 0.5.0 | Video `save-as` now correctly closes page/context before `saveAs`. |
| **No diff engine** | 🟡 Medium | No snapshot diff or pixel comparison. agent-browser added Myers diff + pixel diff in 0.15.x. |
| **No auth vault** | 🟠 Low | No encrypted credential store. agent-browser has AES-256-GCM auth vault. |
| **No iOS/cloud browser support** | 🟠 Low | Missing for teams that need real mobile testing or serverless browsers. |

### agent-browser Pitfalls

> **Note**: agent-browser 0.15.1 has fixed several issues from the 0.9.0 comparison. The table below reflects remaining issues and notes what changed.

| Issue | Severity | Details |
|---|---|---|
| **Still no daemon — slower per command** | 🟡 Medium | Still process-per-command architecture. But Rust CLI is faster than before — overhead is ~100-150ms vs spel’s ~15-25ms. |
| **No test/assertion framework** | 🟡 Medium | Still no built-in way to assert page state. Must build from scratch. |
| **No CI tooling** | 🟠 Low | No report generation, no CI assembly, no report merging. |
| **Requires Node.js runtime** | 🟠 Low | Not self-contained. Needs Node.js installed. |
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
| **Link discovery** | 🟢 | 🟢 | Both include URLs in snapshots (spel: `[url=...]`, AB: `/url: ...`) |
| **Command speed** | 🟢 | 🟡 | spel's daemon = instant commands |
| **Error recovery** | 🟢 | 🟡 | spel has more wait/retry strategies |
| **Page understanding** | 🟢 | 🟡 | spel's hierarchy aids comprehension |
| **Structured JSON** | 🟢 | 🟡 | spel has `refs` map + `pages`/`network`/`console` metadata; AB has `refs` map only |

### For Human Developers

| Aspect | spel | agent-browser | Notes |
|---|---|---|---|
| **Installation** | `make install-local` (build from source) | `bun install -g agent-browser` | AB is trivially installable |
| **Learning curve** | Steeper (120+ commands, Clojure eval) | Steeper now too (143 commands, JS eval) | Both are complex tools now |
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
| **State management** | ✅ Save/load/export auth state | 🟡 Save/load (+ auth vault) |
| **Network mocking** | ✅ Route/unroute with body/abort | ✅ Route/unroute added in 0.15 |
| **Multi-tab** | ✅ Tab management | ✅ Tab management added in 0.15 |
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
1. ~~**Sequential refs**~~ — ❌ Rejected — hash refs are stable across snapshots; sequential refs break cross-snapshot reference in multi-step agent workflows
2. ~~**Link URLs in snapshots**~~ — ✅ Done in spel 0.5.0 (`[url=https://...]` inline)
3. ~~**Structured refs in JSON**~~ — ✅ Done in spel 0.5.0 (full `refs` map with role/name/url + `pages`, `network`, `console`)
4. **Diff engine** — snapshot diff (Myers), pixel diff, URL comparison — truly missing, worth implementing
5. **Computed styles** — `get styles` returns fontSize, color, etc. — truly missing, worth implementing
6. **Clipboard** — copy/paste/read with auto permission grant — truly missing, moderate value
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

### agent-browser should consider adopting from spel:
1. **Daemon architecture** — still 5-10× slower per command
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
| **Raw performance** | spel | Large (5-10× per command, daemon architecture) |
| **Command breadth** | Tie | Both ~120-143 CLI commands. spel has ~460 SCI functions + full Clojure library on top |
| **Programmability** | spel | Massive (full Clojure vs JS eval) |
| **Testing/CI** | spel | Total (AB still has nothing) |
| **Debugging** | spel | spel has Inspector + Trace Viewer + HAR (via library). AB has profiler (CDP-only) |
| **Diff tooling** | agent-browser | Total (spel has nothing) |
| **Auth management** | agent-browser | Medium (encrypted vault vs state save/load — state files cover 80%) |
| **LLM snapshot ergonomics** | Tie | Both have link URLs and structured refs. AB is more compact; spel has richer metadata |
| **Safety/policy** | agent-browser | Total (action confirmation, domain allowlist) |
| **Ease of installation** | agent-browser | Large (npm vs build from source) |
| **Learning curve** | Tie | Both grew to 120-143 CLI commands; neither is simple anymore |
| **Mobile testing** | agent-browser | Total (iOS support) |
| **Cloud browsers** | agent-browser | Total (3 providers) |
| **Error messages** | Tie | Both now propagate Playwright errors well |
| **Documentation** | Tie | Both have good --help |

**Overall: spel has near-complete feature parity** when you count its 3 layers (CLI + SCI + library). The only genuine gaps are diff tooling (snapshot/pixel/URL comparison), computed styles, and clipboard CLI. agent-browser's CLI command count is slightly higher (~143 vs ~120), but spel's library layer covers most of those "missing" commands — accessible via `--eval` or Clojure code.

The competitive landscape is now: **spel = testing platform + speed + full Playwright API coverage. agent-browser = simpler CLI surface + diff tooling + auth vault.** The "breadth gap" that appeared when agent-browser grew to 143 commands is largely illusory — most of those features already exist in spel's library, just not as standalone CLI commands.

---

*Updated March 1, 2026. Original comparison: February 26, 2026.*
