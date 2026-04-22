# Agent common patterns

Shared conventions for every spel subagent. Follow these.

## Session management

**Never use the default session** — it may belong to the user or another agent.

```bash
SESSION="<agent-short>-$(date +%s)"
spel --session $SESSION open <url> --interactive
spel --session $SESSION snapshot -i
spel --session $SESSION screenshot evidence.png
spel --session $SESSION eval-sci '(spel/title)'
spel --session $SESSION close                   # always
```

Agent short names: `plan`, `tw`, `exp`, `auto`, `pres`, `hunt`, `disc`.

Stuck/orphaned session → clean daemon then stale sockets:

```bash
spel session list
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
```

### CDP policy — one session owns one endpoint

- One session for the whole flow; never recreate it per command.
- Never attach two agents to the same CDP endpoint concurrently.
- Never `pkill -f "chrome"` / `pkill -f "Google Chrome"` as recovery — it kills the user's browser.

Simplest pattern (auto-launch):

```bash
SESSION="exp-$(date +%s)"
spel --session $SESSION --auto-launch open https://example.com
spel --session $SESSION snapshot -i
spel --session $SESSION click @eXXXXX
spel --session $SESSION close
```

Manual CDP (when you need a specific browser/profile):

```bash
SESSION="exp-$(date +%s)"
CDP_PORT=$(spel find-free-port)
open -na "Google Chrome" --args --remote-debugging-port=$CDP_PORT \
     --user-data-dir="/tmp/spel-cdp-$SESSION" --no-first-run
spel --session $SESSION --cdp http://127.0.0.1:$CDP_PORT open https://example.com
```

CDP attach fails (`TargetClosedError`, `ECONNREFUSED`):

1. Keep session name.
2. `curl http://127.0.0.1:$CDP_PORT/json/version` to verify endpoint.
3. Relaunch only the dedicated debug browser, reattach.
4. Re-snapshot, continue.

## I/O contracts

### Contract format

```markdown
## Contract
Inputs:  - `<path>` — description (REQUIRED/OPTIONAL)
Outputs: - `<path>` — description (JSON/PNG/MD)
```

### JSON output convention

```json
{"agent":"spel-<name>", "timestamp":"2026-03-06T12:00:00Z",
 "target_url":"...", "session":"...", "status":"complete",
 "artifacts":[{"type":"screenshot","path":"evidence/page.png"},
              {"type":"snapshot","path":"evidence/page-snapshot.json"},
              {"type":"report","path":"report.json"}]}
```

### Artifact verification (before announcing or opening a GATE)

- Every promised file exists and is non-empty.
- JSON outputs parse and match expected shape.
- Every referenced evidence path exists on disk.
- User explicitly asked for JSON → those paths are hard requirements.

Missing artifact = incomplete stage. Fix before summarizing.

### Pipeline handoff

Orchestrated runs leave a handoff file in `orchestration/`:

```json
{"pipeline":"automation|qa|test|discovery",
 "stage":"explore|hunt|generate|complete",
 "status":"awaiting_user_approval|complete|blocked",
 "required_artifacts":["..."], "missing_artifacts":[],
 "artifacts":[{"kind":"report","path":"..."}],
 "next_step":"challenge", "open_questions":[]}
```

Update after every stage transition.

## Gates

GATE = mandatory pause — present results and wait for approval.

When: after a plan/spec, after changes, after finding issues, before destructive actions.

Format:

```markdown
**GATE: [What was produced]**

1. Show key findings/changes
2. Show evidence (screenshots, diffs)
3. Ask: "Approve to proceed, or provide feedback?"

Do NOT continue until user explicitly approves.
```

Embedded GATE (inside agent template) protects standalone invocation; workflow GATE protects the pipeline — both apply.

A GATE is valid only when: required artifacts exist, `missing_artifacts` is empty, user sees exact file paths, next step is blocked pending approval. Missing required artifact → don't ask yet. Fix the stage.

## Error recovery

| Failure | Detection | Recovery |
|---------|-----------|----------|
| URL unreachable | `spel open` errors | Report "Target URL unreachable: …" |
| Selector not found | `--timeout` expires | Snapshot, show what IS present, suggest alternatives |
| Heavy-page click times out | Portal/SPA click hangs on wait | `wait --load domcontentloaded` or `wait --url <partial>` after click. Never bypass user action by navigating directly. |
| Budget spent on scans, no artifacts | Agent runs helpers/scans without writing artifacts | Fast-path: skip helpers + broad scans, run minimal `open → wait → get → write → verify` |
| Session conflict | `--session` error | Generate fresh unique name, retry |
| CDP attach flake | `TargetClosedError` / `ECONNREFUSED` | One-owner session per endpoint; relaunch only the dedicated debug browser |
| Edit tool denied by policy | Tool-permission error on artifact write | Write with `bash`/`python`, then read back + verify content/paths |
| Auth required | Login form detected | Report + suggest auth bootstrap or `--load-state` |
| JS errors / network failures | Console / network log | Capture, continue unless page is non-functional; distinguish blocking vs non-blocking |

```bash
spel --session $SESSION open <url> --interactive \
  || { echo "ERROR: Could not open <url>. Is the app running?";
       spel --session $SESSION close 2>/dev/null; exit 1; }
```

eval-sci errors throw automatically. Wrap risky ops:

```clojure
(try (spel/click (spel/get-by-text "Submit"))
     (catch Exception e
       (println "Could not find Submit. Page state:")
       (println (:tree (spel/capture-snapshot)))))
```

## Evidence capture

```
<output-dir>/
  report.json              # agent-specific schema
  evidence/
    <page>-snapshot.json   # accessibility snapshot with styles
    <page>-screenshot.png
    <page>-annotated.png   # annotated (ref overlays)
    <element>-detail.png
```

Per page/state: snapshot → screenshot → annotate → screenshot → unannotate.

Responsive:

```bash
for viewport in "375 812 mobile" "768 1024 tablet" "1440 900 desktop"; do
  set -- $viewport
  spel --session $SESSION eval-sci "(spel/viewport-size $1 $2)"
  spel --session $SESSION screenshot "evidence/<page>-$3.png"
done
```

## Position annotations in snapshot refs

Each ref includes `[pos:X,Y W×H]` — pixel coords (X,Y from top-left) + dimensions.

```
button "Submit" @e2yrjz [pos:150,200 120×40]
input  "Email"  @e3kqmn [pos:100,100 300×30]
```

Use for layout verification, overlap/clipping detection, viewport fit, spatial reasoning, duplicate detection (repeated logos/headings/nav blocks, identical message text), visual symmetry, broken grid/flex, repeat-pattern coherence (list rows/cards keep badges + icons in the same position regardless of content length).

## Selector strategy — snapshot refs first

Always snapshot before interacting.

Why refs over CSS: deterministic (same element → same ref across snapshots until nav), semantic (role/name-hashed via FNV-1a), resilient (survive CSS refactors), universal (every spel fn accepts them).

Priority (high → low): snapshot ref (`@eXXXX`) → semantic locator (role + name / label / text) → `data-testid` → CSS (last resort).

After nav, re-snapshot:

```bash
spel --session $SESSION snapshot -i
spel --session $SESSION click @eXXXXX
spel --session $SESSION snapshot -i   # fresh refs
spel --session $SESSION click @eYYYYY
```

## Navigation decision table

| Situation | Action | Wait | Why |
|-----------|--------|------|-----|
| Traditional multi-page | `open <url>` | `wait --load load` | Full load is a good ready signal |
| Heavy / ad-laden portal | Click only if needed | `wait --load domcontentloaded` or `wait --url <partial>` | Full load delayed by 3rd-party |
| SPA route known | Reach via clicks/keyboard | `wait --url <partial>` then `wait --load domcontentloaded` | Keeps user-like nav, reliable SPA readiness |
| SPA route unknown | Snapshot first, then click | `wait --url <partial>` + content wait | URL change more stable than full resource completion |

Rules: split `open` + `wait`. Don't fix flakiness by raising timeouts. If clicks repeatedly time out, keep user-visible flow, adjust waits.

## Cookie consent / first-visit popups

EU/GDPR sites show consent on first visit — handle before extraction.

Common button text: EN `Accept all` / `Accept cookies` / `I agree`; PL `Akceptuję` / `Zgadzam się` / `Zaakceptuj wszystko`; DE `Alle akzeptieren`.

```bash
spel --session $SESSION snapshot -i
spel --session $SESSION click @eXXXXX            # consent button

# Postal-code / location popup? Snapshot again and fill.
spel --session $SESSION snapshot -i
spel --session $SESSION fill  @eXXXXX "31-564"
spel --session $SESSION click @eXXXXX
spel --session $SESSION snapshot -i              # confirm clean state
```

eval-sci equivalent:

```clojure
(let [snap (spel/capture-snapshot)]
  (when (str/includes? (:tree snap) "cookie")
    (try (spel/click (spel/get-by-role role/button {:name "Accept all"}))
         (catch Exception _ nil))
    (spel/wait-for-load)))
```

> In library test code (not SCI/CLI) use `page/get-by-role page role/button`, not `spel/get-by-role role/button`.

## Mandatory viewport check

Every tested page at all three viewports. No exceptions.

| Viewport | Size | Set via |
|----------|------|---------|
| Desktop | 1280×720 | default (or `(spel/set-viewport-size! 1280 720)`) |
| Tablet | 768×1024 | `(spel/set-viewport-size! 768 1024)` |
| Mobile | 375×667 | `(spel/set-viewport-size! 375 667)` |

Per viewport, capture annotated screenshot + snapshot JSON + overflow check:

```clojure
(let [sw (spel/evaluate "document.documentElement.scrollWidth")
      cw (spel/evaluate "document.documentElement.clientWidth")]
  (println "Overflow:" (> sw cw) "scroll:" sw "client:" cw))
```

## Mandatory exploratory pass

After structured testing, 30–90 s unscripted exploration:

1. Click without a plan, try unlikely paths.
2. Submit forms with empty / too-long / special-char data.
3. Rapidly double-click / spam a button.
4. Browser back/forward during multi-step flows.
5. Resize viewport mid-interaction.
6. Open the same flow in a second tab.

Document anything unexpected. Exploratory passes surface the highest-severity bugs.

## Daemon notes (do not duplicate in agents)

- `spel/start!` / `spel/stop!` are not needed — daemon manages lifecycle.
- `--timeout <ms>` to fail fast on bad selectors (default 30s = too long for exploration).
- `--interactive` when the user should see the browser.
- eval-sci errors throw — no explicit checks unless custom recovery is desired.
- Always `spel --session $SESSION close` when done.

## Video recording (quick)

See `PDF_STITCH_VIDEO.md` for full reference.

Minimal eval-sci:

```clojure
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/clear-action-log!)
(spel/navigate "https://example.org")
(spel/human-pause)
(spel/smooth-scroll 300)
(spel/human-pause)
(spel/click "@e123")
(spit "/tmp/session.srt" (spel/export-srt))
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
```

CLI:

```bash
spel --session $SESSION open <url> --interactive --record-video
spel --session $SESSION click @e123
spel --session $SESSION action-log --srt -o session.srt
spel --session $SESSION close   # finalizes video
```

QA report embeds video via `<video>` + SRT track — see `spel-report.html` / `spel-report.md`.

## Black-box testing rule

**Never read application source code.** Bug-finding agents (Hunter) test what users see: UI, behavior, a11y, network. Reading source biases testing to what you know is there — miss bugs in the gap between intent and implementation; skip exploratory paths a real user would try. Observe through snapshots, screenshots, console output, network logs. Never `cat`/`grep` `.js`/`.ts`/`.py` sources.

## See also

- `FULL_API.md` · `EVAL_GUIDE.md` · `SELECTORS_SNAPSHOTS.md`
