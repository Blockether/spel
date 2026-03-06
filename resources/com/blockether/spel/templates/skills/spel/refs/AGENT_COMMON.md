# Agent Common Patterns

Shared conventions for all spel subagents. Every agent MUST follow these patterns.

---

## Session Management

**Every agent MUST use a named session.** Never use the default session — it may belong to the user or another agent.

```bash
# Generate a unique session name for this task
SESSION="<agent-short-name>-$(date +%s)"

# Open with session
spel --session $SESSION open <url> --interactive

# All subsequent commands use the same session
spel --session $SESSION snapshot -i
spel --session $SESSION screenshot evidence.png
spel --session $SESSION eval-sci '(spel/title)'

# ALWAYS close on completion or error
spel --session $SESSION close
```

**Agent short names:**
- planner → `plan`, generator → `gen`, healer → `heal`
- explorer → `exp`, automator → `auto`, interactive → `iact`
- presenter → `pres`, visual-qa → `vqa`
- bug-hunter → `hunt`, bug-skeptic → `skep`, bug-referee → `ref`
- spec-skeptic → `sskep`

---

## Input/Output Contracts

Every agent declares what it reads and what it produces. This enables composition — agents can consume each other's output.

### Contract Format

Each agent's template includes a section:

```markdown
## Contract

**Inputs:**
- `<path>` — description (REQUIRED/OPTIONAL)

**Outputs:**
- `<path>` — description (format: JSON/PNG/MD)
```

### JSON Output Convention

Machine-readable outputs use JSON with these common fields:

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

A GATE is a mandatory pause point where the agent presents results to the user and waits for approval before proceeding.

### When to GATE

- **After producing a plan/spec** — User must review before execution
- **After making changes** — User must verify before the next agent consumes the output
- **After finding issues** — User must acknowledge before fixes are applied
- **Before destructive actions** — User must confirm before overwriting baselines, deleting files, etc.

### GATE Format

```markdown
**GATE: [What was produced]**

Present the [output] to the user:
1. Show the key findings/changes
2. Show evidence (screenshots, diffs)
3. Ask: "Approve to proceed, or provide feedback?"

Do NOT continue to the next phase until the user explicitly approves.
```

### Embedded vs Workflow Gates

- **Embedded GATE** — Inside the agent template itself. The agent pauses when invoked standalone.
- **Workflow GATE** — In the workflow prompt. The orchestrator pauses between agent invocations.

**Both are needed.** Embedded gates protect standalone invocation. Workflow gates protect the pipeline.

---

## Error Recovery

Every agent must handle common failure modes gracefully.

### Common Failures

| Failure | Detection | Recovery |
|---------|-----------|----------|
| URL unreachable | `spel open` returns error | Report: "Target URL unreachable: <url>. Verify the application is running." |
| Selector not found | `--timeout` expires | Capture snapshot, show what IS on the page. Suggest alternative selectors. |
| Session conflict | `spel --session` error | Generate a new unique session name and retry. |
| Page requires auth | Login form detected | Report: "Page requires authentication. Use @spel-interactive for human-in-the-loop login, or provide --load-state." |
| JavaScript errors | Console errors in snapshot | Capture and report. Continue unless the page is non-functional. |
| Network failures | Failed requests in network log | Capture and report. Distinguish blocking vs non-blocking failures. |

### Recovery Pattern

```bash
# Try the primary approach
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

## Evidence Capture

All evidence follows consistent naming and organization.

### Directory Convention

```
<output-dir>/
  report.json              # Machine-readable report (agent-specific schema)
  evidence/
    <page>-snapshot.json   # Accessibility snapshot with styles
    <page>-screenshot.png  # Visual screenshot
    <page>-annotated.png   # Annotated screenshot (with ref overlays)
    <element>-detail.png   # Close-up of specific element
```

### Capture Checklist

For every page/state you examine:

1. **Snapshot** — `spel --session $SESSION snapshot -S --json > evidence/<page>-snapshot.json`
2. **Screenshot** — `spel --session $SESSION screenshot evidence/<page>-screenshot.png`
3. **Annotate** — `spel --session $SESSION annotate` → `spel --session $SESSION screenshot evidence/<page>-annotated.png` → `spel --session $SESSION unannotate`

### Responsive Evidence

When responsiveness matters, capture at standard breakpoints:

```bash
for viewport in "375 812 mobile" "768 1024 tablet" "1440 900 desktop"; do
  set -- $viewport
  spel --session $SESSION eval-sci "(spel/viewport-size $1 $2)"
  spel --session $SESSION screenshot "evidence/<page>-$3.png"
done
```

---

## Daemon Notes (Do NOT duplicate in agent templates)

These apply to ALL agents — reference this doc instead of copy-pasting:

- `spel/start!` and `spel/stop!` are **NOT needed** — the daemon manages browser lifecycle
- Use `--timeout <ms>` to fail fast on bad selectors (default is 30s, which is too long for exploration)
- Use `--interactive` when the user should see the browser window
- Errors throw automatically in `eval-sci` mode — no need for explicit error checking unless you want custom recovery
- Use `spel open <url> --interactive` before `eval-sci` if the user wants to watch
- ALWAYS `spel --session $SESSION close` when done — never leave sessions open

---

## Video Recording

Record browser sessions for evidence and CI artifacts. Video provides indisputable proof of bugs and helps stakeholders who can't reproduce locally.

### Recording a Session

Use `--record-video` when opening a browser:

```bash
# CLI: open with video recording
spel --session $SESSION open <url> --interactive --record-video

# The video is saved when the session closes
spel --session $SESSION close
# Video saved to: videos/<session-name>.webm
```

In `eval-sci` mode:

```clojure
;; Start recording
(spel/start-video "videos/")

;; ... perform actions ...

;; Stop and save
(spel/stop-video)  ;; => "videos/session-1234.webm"
```

### SRT Transcript (Subtitles)

Generate an SRT transcript of agent actions for video overlay. Each action becomes a subtitle entry synced to the video timeline:

```
1
00:00:01,000 --> 00:00:03,500
[Hunter] Navigating to https://example.com/login

2
00:00:03,500 --> 00:00:06,200
[Hunter] Taking snapshot — found 12 interactive elements

3
00:00:06,200 --> 00:00:09,000
[Hunter] ISSUE-001: Submit button not keyboard-accessible
```

Agents should log actions with timestamps (use `(System/currentTimeMillis)`) and generate SRT format after the session.

### Video in QA Reports

The QA orchestrator can embed video in the HTML report using the `<video>` element with SRT track. See `refs/qa-report.html` for the template structure.

---

## Black-Box Testing Rule

**Never read application source code.** Bug-finding agents (Hunter, Skeptic, Referee) are black-box testers. They test what users see and experience — UI, behavior, accessibility, network responses.

Reading source code introduces bias:
- You start testing what you know is there, not what users encounter
- You miss bugs in the gap between intent and implementation
- You skip exploratory paths a real user would try

If you need to understand behavior, observe it through the browser. Use snapshots, screenshots, console output, and network logs — never `cat`, `grep`, or read `.js`/`.ts`/`.py` source files.

---

## See Also

- [FULL_API.md](FULL_API.md) — Complete spel CLI and library API
- [EVAL_GUIDE.md](EVAL_GUIDE.md) — SCI eval scripting patterns
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) — Snapshot and annotation workflows
- [VISUAL_QA_GUIDE.md](VISUAL_QA_GUIDE.md) — Visual regression methodology
