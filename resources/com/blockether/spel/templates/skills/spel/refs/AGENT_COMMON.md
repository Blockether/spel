# Agent common patterns

Shared conventions for all spel subagents. Every agent MUST follow these patterns.

---

## Session management

**NEVER use the default session.** It may belong to the user or another agent. Always use a named session.

```bash
SESSION="<agent-short-name>-$(date +%s)"

spel --session $SESSION open <url> --interactive
spel --session $SESSION snapshot -i
spel --session $SESSION screenshot evidence.png
spel --session $SESSION eval-sci '(spel/title)'

# ALWAYS close on completion or error
spel --session $SESSION close
```

Agent short names:
- planner → `plan`, generator → `gen`, healer → `heal`
- explorer → `exp`, automator → `auto`, interactive → `iact`
- presenter → `pres`, visual-qa → `vqa`
- bug-hunter → `hunt`, bug-skeptic → `skep`, bug-referee → `ref`
- spec-skeptic → `sskep`

---

## Input/output contracts

Every agent declares what it reads and what it produces, enabling composition.

### Contract format

```markdown
## Contract

Inputs:
- `<path>` — description (REQUIRED/OPTIONAL)

Outputs:
- `<path>` — description (format: JSON/PNG/MD)
```

### JSON output convention

```json
{
  "agent": "spel-<name>",
  "timestamp": "2026-03-06T12:00:00Z",
  "target_url": "https://example.com",
  "session": "<session-name>",
  "status": "complete",
  "artifacts": [
    {"type": "screenshot", "path": "evidence/page.png"},
    {"type": "snapshot", "path": "evidence/page-snapshot.json"},
    {"type": "report", "path": "report.json"}
  ]
}
```

---

## Gates

A GATE is a mandatory pause where the agent presents results to the user and waits for approval before proceeding.

### When to GATE

- After producing a plan/spec: user must review before execution
- After making changes: user must verify before the next agent consumes the output
- After finding issues: user must acknowledge before fixes are applied
- Before destructive actions: user must confirm before overwriting baselines, deleting files, etc.

### GATE format

```markdown
**GATE: [What was produced]**

Present the [output] to the user:
1. Show the key findings/changes
2. Show evidence (screenshots, diffs)
3. Ask: "Approve to proceed, or provide feedback?"

Do NOT continue to the next phase until the user explicitly approves.
```

### Embedded vs workflow gates

- Embedded GATE: inside the agent template itself. The agent pauses when invoked standalone.
- Workflow GATE: in the workflow prompt. The orchestrator pauses between agent invocations.

Both are needed. Embedded gates protect standalone invocation. Workflow gates protect the pipeline.

---

## Error recovery

### Common failures

| Failure | Detection | Recovery |
|---------|-----------|----------|
| URL unreachable | `spel open` returns error | Report: "Target URL unreachable: <url>. Verify the application is running." |
| Selector not found | `--timeout` expires | Capture snapshot, show what IS on the page. Suggest alternative selectors. |
| Session conflict | `spel --session` error | Generate a new unique session name and retry. |
| Page requires auth | Login form detected | Report: "Page requires authentication. Use @spel-interactive for human-in-the-loop login, or provide --load-state." |
| JavaScript errors | Console errors in snapshot | Capture and report. Continue unless the page is non-functional. |
| Network failures | Failed requests in network log | Capture and report. Distinguish blocking vs non-blocking failures. |

### Recovery pattern

```bash
spel --session $SESSION open <url> --interactive
if [ $? -ne 0 ]; then
  echo "ERROR: Could not open <url>. Is the application running?"
  spel --session $SESSION close 2>/dev/null
  exit 1
fi
```

In `eval-sci` mode, errors throw automatically. Wrap risky operations:

```clojure
(try
  (spel/click (spel/get-by-text "Submit"))
  (catch Exception e
    (println "Could not find Submit button. Current page state:")
    (println (:tree (spel/capture-snapshot)))))
```

---

## Evidence capture

### Directory convention

```
<output-dir>/
  report.json              # Machine-readable report (agent-specific schema)
  evidence/
    <page>-snapshot.json   # Accessibility snapshot with styles
    <page>-screenshot.png  # Visual screenshot
    <page>-annotated.png   # Annotated screenshot (with ref overlays)
    <element>-detail.png   # Close-up of specific element
```

### Capture checklist

For every page/state you examine:

1. Snapshot: `spel --session $SESSION snapshot -S --json > evidence/<page>-snapshot.json`
2. Screenshot: `spel --session $SESSION screenshot evidence/<page>-screenshot.png`
3. Annotate: `spel --session $SESSION annotate` then `spel --session $SESSION screenshot evidence/<page>-annotated.png` then `spel --session $SESSION unannotate`

### Responsive evidence

```bash
for viewport in "375 812 mobile" "768 1024 tablet" "1440 900 desktop"; do
  set -- $viewport
  spel --session $SESSION eval-sci "(spel/viewport-size $1 $2)"
  spel --session $SESSION screenshot "evidence/<page>-$3.png"
done
```

---

## Daemon notes (do NOT duplicate in agent templates)

These apply to ALL agents. Reference this doc instead of copy-pasting:

- `spel/start!` and `spel/stop!` are NOT needed. The daemon manages browser lifecycle.
- Use `--timeout <ms>` to fail fast on bad selectors (default is 30s, which is too long for exploration)
- Use `--interactive` when the user should see the browser window
- Errors throw automatically in `eval-sci` mode. No need for explicit error checking unless you want custom recovery.
- Use `spel open <url> --interactive` before `eval-sci` if the user wants to watch
- ALWAYS `spel --session $SESSION close` when done. Never leave sessions open.

---

## Video recording

Record browser sessions for evidence and CI artifacts.

### Recording a session with action log

```clojure
;; Start video + clear action log
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/clear-action-log!)

;; Perform actions with natural pacing
(spel/navigate "https://example.org")
(spel/human-pause)    ;; 300-700ms random pause

(spel/smooth-scroll 300)
(spel/human-pause)

(spel/click "@e123")
(spel/human-pause 500 1000)

;; Export SRT before finishing video
(spit "/tmp/session.srt" (spel/export-srt))
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
```

Or via CLI:

```bash
spel --session $SESSION open <url> --interactive --record-video
spel --session $SESSION click @e123
spel --session $SESSION fill @e456 "hello"

# Export SRT and action log
spel --session $SESSION action-log --srt -o session.srt
spel --session $SESSION action-log --json -o session.json

# Close (finalizes video)
spel --session $SESSION close
```

### SRT transcript (automatic)

The action log generates SRT subtitles automatically:

```
1
00:00:00,000 --> 00:00:02,000
navigate https://example.org

2
00:00:02,000 --> 00:00:03,500
click @e123

3
00:00:03,500 --> 00:00:05,000
fill @e456 "search text"
```

The JSON export includes full context (URL, title, snapshot tree) for each action.

### FFmpeg post-processing (optional)

```bash
# Burn in subtitles
ffmpeg -i session.webm -vf "subtitles=session.srt" output.mp4

# Remove idle frames
ffmpeg -i session.webm -vf "mpdecimate,setpts=N/30/TB" -r 30 trimmed.mp4
```

See `refs/PDF_STITCH_VIDEO.md` for the complete FFmpeg reference.

### Video in QA reports

The QA orchestrator can embed video in the HTML report using the `<video>` element with SRT track. See `refs/qa-report.html` for the template structure.

---

## Black-box testing rule

**NEVER read application source code.** Bug-finding agents (Hunter, Skeptic, Referee) are black-box testers. They test what users see and experience: UI, behavior, accessibility, network responses.

Reading source code introduces bias: you test what you know is there, miss bugs in the gap between intent and implementation, and skip exploratory paths a real user would try.

To understand behavior, observe it through the browser. Use snapshots, screenshots, console output, and network logs. Never `cat`, `grep`, or read `.js`/`.ts`/`.py` source files.

---

## See also

- [FULL_API.md](FULL_API.md) — Complete spel CLI and library API
- [EVAL_GUIDE.md](EVAL_GUIDE.md) — SCI eval scripting patterns
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — Snapshot and annotation workflows
- [VISUAL_QA_GUIDE.md](VISUAL_QA_GUIDE.md) — Visual regression methodology
