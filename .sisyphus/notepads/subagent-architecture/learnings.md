# Learnings

## [2026-03-05 Wave 1 Start] Session: ses_342b3f246ffeFm5cQBPy07rvdF

### Codebase Layout
- Working dir: /root/.local/share/opencode/worktree/0f5014e3651592faf1bb593d0d536259147d1883/witty-tiger
- Templates: resources/com/blockether/spel/templates/
  - agents/ (4 files: test-planner, test-generator, test-generator-ct, test-healer)
  - prompts/ (1 file: spel-test-workflow.md)
  - skills/spel/refs/ (20 files incl. ENVIRONMENT_VARIABLES.md)
  - flavours/
- Source: src/com/blockether/spel/
- Tests: test/com/blockether/spel/

### Key Source Locations (Momus-verified)
- snapshot.clj:251-296 — STYLE_MINIMAL/BASE/MAX arrays + isDefaultStyle() ✅
- snapshot.clj:528-538 — style-display-order vector ✅
- init_agents.clj:45-66 — loop-targets map (has vscode) ✅
- init_agents.clj:72-92 — ref-files vector ✅
- init_agents.clj:94-145 — files-to-create function ✅
- init_agents.clj:257-259 — agent-ref-names (3 entries, needs 8) ✅
- init_agents.clj:296-309 — transform-for-vscode function ✅
- init_agents.clj:557-562 — error handler for unknown loop target ✅
- native.clj:538-599 — run-eval! function (--eval dispatch) ✅
- sci_env.clj:79-106 — session state atoms (zero dynamic vars currently) ✅
- daemon.clj ~1734 — handle-cmd "sci_eval" multimethod (corrected from plan's 580-640)
- cli.clj ~2125 — send-command! function (corrected from plan's 1-50)

### Snapshot Tier Counts (current)
- MINIMAL: 12 props (display, position, backgroundColor, color, fontSize, fontWeight, padding, margin, width, height, borderRadius, border)
- BASE: 24 props (above + 12 more)
- MAX: 36 props (above + 12 more)
- MISSING: top, left, right, bottom, transform, visibility, float, clear

### init_agents.clj Size
- Currently 602 lines. Limit: 800 lines. Headroom: ~198 lines.

### Conventions
- NEVER use inline lambdas for SCI-exposed functions — always defn with docstring
- NEVER fix parens by hand — use clj-paren-repair
- Lint via lsp_diagnostics (clj-kondo not installed)
- All tests: make test (lazytest ~1268 cases, CLI bash ~179 assertions)
- Verify: ./verify.sh

## [2026-03-05 Wave 1 Task 4] init_agents data foundation

### Smart ref assignment data model
- Added `subagent-ref-map` as the canonical ref grouping source (core/test/explorer/automator/interactive/presenter/visual-qa).
- Added missing `ENVIRONMENT_VARIABLES.md` to `:core` so scaffolding now includes env var docs.
- Added `subagent-groups` as group→subagent-set mapping to support upcoming `--only` selection logic.

### Backward-compatible wiring in files-to-create
- `files-to-create` now computes selected refs through `subagent-ref-map`/`subagent-groups`, then deduplicates.
- Current default behavior remains unchanged: scaffold full ref set when no filtering is requested.

### Registration expansion
- Added 5 agent entries in scaffold list: spel-explorer, spel-automator, spel-interactive, spel-presenter, spel-visual-qa.
- Added 2 prompt entries: spel-visual-workflow, spel-automation-workflow.
- Expanded `agent-ref-names` from 3 to 8 names for cross-reference rewriting.
