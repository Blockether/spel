# Issues & Gotchas

## [2026-03-05] Pre-work Findings

### Line Number Corrections (Momus review)
- daemon.clj: plan said 580-640 for sci_eval, actual is ~1734 — search for (handle-cmd "sci_eval"
- cli.clj: plan said 1-50 for send-command!, actual is ~2125

### isDefaultStyle Filter Warning
- top/left/right/bottom default to 'auto' in CSS
- Most elements have position:static, so top/left/right/bottom won't appear for them
- Only positioned elements (relative/absolute/fixed/sticky) will show position values
- This is CORRECT behavior (filtered by isDefaultStyle) — not a bug

### init_agents.clj Headroom
- Currently 602 lines, limit 800 lines
- Need to add subagent-ref-map + subagent-groups + --only logic + 5 new agent registrations + 2 new prompt registrations
- Estimate: ~100-150 new lines needed. Will fit within 800L limit.
- If needed: extract data structures to top-level defs (already planned)

### No clj-kondo CLI
- Always use lsp_diagnostics for lint, never clj-kondo command

## [2026-03-05] Task 4 gotcha

### Evidence grep pipeline mismatch
- The requested pipeline `grep "spel-" ... | grep "agent-ref-names\|agent-name"` can produce empty output because `agent-ref-names` and individual `"spel-*"` entries are on different lines.
- Use a combined regex against original file for durable evidence capture (e.g. `grep -nE 'agent-ref-names|"spel-[a-z-]+"' ...`).
