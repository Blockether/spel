# Learnings — Agent Consolidation

## Project conventions
- Templates live in: `resources/com/blockether/spel/templates/agents/`
- Workflow prompts in: `resources/com/blockether/spel/templates/prompts/`
- Ref docs in: `resources/com/blockether/spel/templates/skills/spel/refs/`
- SKILL.md template in: `resources/com/blockether/spel/templates/skills/spel/SKILL.md`
- Scaffolding code: `src/com/blockether/spel/init_agents.clj`
- Tests: `test/com/blockether/spel/init_agents_test.clj` + `test-cli.sh`
- Evidence dir: `.sisyphus/evidence/` (create files here)

## Anthropic skill-building guide principles
- Progressive disclosure: 3 levels (frontmatter → body → linked files)
- Keep templates under 5,000 words (aim ≤500 lines)
- Critical instructions at top
- Be specific and actionable
- Composability — skills work alongside others
- Error handling must be explicit
- "Instructions too verbose" is common failure mode

## Agent consolidation map (14→8)
- spel-test-generator.md + spel-test-healer.md → NEW spel-test-writer.md (Phase 1: Generate + Phase 2: Self-Heal)
- spel-test-generator-ct.md + spel-test-healer.md → NEW spel-test-writer-ct.md (clojure.test variant)
- spel-orchestrator.md absorbs spel-test-orchestrator.md + spel-qa-orchestrator.md (expand in-place)
- spel-test-planner.md absorbs spel-spec-skeptic.md (expand in-place, add Self-Challenge section)
- spel-bug-hunter.md absorbs spel-bug-skeptic.md + spel-bug-referee.md (expand in-place, add phases)
- KEEP AS-IS: spel-explorer.md, spel-automator.md, spel-presenter.md, spel-product-analyst.md

## Previous merge precedent (v0.5.21)
- visual-qa → bug-hunter merge was successful
- Pattern: edit templates → update init_agents.clj (7 data structures) → update tests → update docs → rebuild

## Critical gotchas from Metis review
- spel-test-generator-ct.md MUST become spel-test-writer-ct.md (ct? conditional in files-to-create at lines 149-151)
- --only=core group must redefine to 6 agents: orchestrator, test-planner, test-writer, explorer, bug-hunter, product-analyst
- workflow-required-agents for bugfind: #{:bug-hunter :bug-skeptic :bug-referee} → #{:bug-hunter}
- subagent-ref-map for merged agents must be UNION of all source agents' refs
- agent-ref-names must contain exactly 8 names (stale entries cause dangling @agent-name references)
- spel-report.html references PRODUCER: spel-bug-referee → must change to spel-bug-hunter

## Task 3 merge notes
- Expanded `spel-orchestrator.md` in place to inline test and QA execution flows while preserving existing automation flow and shared protocol sections.
- Kept orchestrator as coordination layer (stage-gate + artifacts) and moved deep execution detail into concise subsections to stay compact (251 lines).
- Added QA scope-to-pipeline mapping table and explicit adaptive-depth blocks for both test (`quick/full/single-feature`) and QA (`quick/standard/deep`).
- Removed stale references to `spel-test-orchestrator` and `spel-qa-orchestrator`; verification grep returns 0.
- Preserved handoff file contract paths: `orchestration/automation-pipeline.json`, `orchestration/test-pipeline.json`, `orchestration/qa-pipeline.json`.

## Task 3 stale-ref fix notes
- Post-merge cleanup must remove second-order stale names too: `spel-spec-skeptic`, `spel-test-generator`, `spel-test-healer`, `spel-bug-skeptic`, `spel-bug-referee`.
- Consolidated pipeline wording should reference merged agents directly: `spel-test-planner` + `spel-test-writer`, and `spel-bug-hunter` with self-challenge/final-report responsibilities.

## Task 1 merge notes
- New merged template created: `resources/com/blockether/spel/templates/agents/spel-test-writer.md`
- Structure used: frontmatter + mission + `## Phase 1: Generate` + `## Phase 2: Self-Heal` + hard rules/output gate
- Preserved generation contracts: spec inputs under `test-e2e/specs/`, seed test verification, test output under `test-e2e/`, and `generation-report.json`
- Preserved healing contracts: run failing scope first, diagnose by root cause class, minimal fixes, re-run loop (max 2 iterations), and `healing-report.json`
- Consolidation safety check: no stale references to retired agent names in the merged file (`grep` count = 0)

## Task 5 learnings (bug-hunter consolidation)
- Safe merge pattern: keep existing Phase 0-3 untouched; append Phase 4 (self-challenge) and Phase 5 (verdict/reporting) after current workflow gates.
- Independent verification requirement must be explicit and operational: use separate `verify-<name>-<timestamp>` session, never reuse hunt session artifacts.
- Consolidated output chain works best when stated in order: `hunter-report.json` -> `verdict.json` -> `qa-report.html` + `qa-report.md`.
- Keep `hunter-report.json` schema stable during consolidation; place reconciliation logic in Phase 4/5 instructions rather than schema mutation.

## Task 4: spec-skeptic → test-planner absorption
- Self-challenge section added as optional phase (lines 205-234, 30 lines)
- Trigger threshold: 5+ scenarios ("non-trivial")
- Scoring simplified to table format: ranges 1-4/5-7/8-10 instead of exact +1/+5/+10
- Integrated into test-plan.json as `self_review` key (no separate output file)
- Pre-delivery checklist updated with self-challenge item
- Final line count: 320/350 (40 lines added from 280 base)

## Task 6: init_agents.clj 7-structure consolidation
- `subagent-ref-map` cleanup must merge removed-agent refs into surviving owners: add `spel-report.html`/`spel-report.md` to `:bug-hunter` and `:orchestrator`.
- Keep `:test` as the shared keyword for `spel-test-planner` + `spel-test-writer`; no new group keyword is needed when refs are shared.
- `:core` should be keyword-level (`:orchestrator :test :explorer :bug-hunter :product-analyst`) so it still expands to 6 generated agents.
- `workflow-required-agents` for bugfind must be `#{:bug-hunter}` or `--only bugfind` can omit the workflow.
- Renaming `generator-template` to `writer-template` avoids stale `spel-test-generator` paths in clojure-test template selection.

## Task 8: Workflow prompt stale ref cleanup

- Only 2 of 5 workflow prompts had stale refs (test, bugfind). The other 3 (automation, visual, discovery) were already clean — they referenced current agents only.
- spel-test-workflow.md had the most stale refs: @spel-spec-skeptic, @spel-test-generator, @spel-test-healer — all replaced or folded into planner/writer built-in phases.
- spel-bugfind-workflow.md required the most structural rewrite: the 3-section pipeline (Hunt/Challenge/Judge) collapsed to a single Hunt section with 5 phases described inline.
- Key pattern: when consolidating multi-agent pipelines to single-agent, describe phases as "built into the agent — no separate invocation needed" to maintain clarity for orchestrators.
- Artifact file paths (bugfind-reports/, test-e2e/specs/, orchestration/) were preserved — these are downstream contracts that other tasks/agents depend on.
- Removed referee-verdict.json and skeptic-review.json from bugfind deliverables — they no longer exist as separate artifacts. The hunter-report.json now includes self-challenge verdicts.

## Task 9: Template reference docs agent name update
- spel-report.md had NO stale agent references (already clean) — only the HTML template had the `PRODUCER: spel-bug-referee` comments
- BUGFIND_GUIDE.md had the most complex changes: skeptic-review.json and referee-verdict.json schemas needed to become internal phases of the hunter report
- The directory convention in BUGFIND_GUIDE.md changed: `skeptic-review.json` and `referee-verdict.json` are gone, replaced by sections within `hunter-report.json`
- AGENT_COMMON.md agent short names needed careful update — removed 4 entries (gen/heal/skep/ref/sskep), replaced with just tw (test-writer)
- The HTML report had 3 separate `spel-bug-referee` references: 2 in HTML comments (PRODUCER) and 1 in footer text

## Task 13: test-cli.sh CLI regression tests

- Only 4 lines in test-cli.sh referenced removed agents (all `bug-referee` in Unified Report Template section lines 1588-1593)
- No references to bug-skeptic, test-healer, test-generator, test-orchestrator, qa-orchestrator, or spec-skeptic existed in the test file
- Added 1 new assertion for `spel-test-writer` in `--only test` section
- Total assertion count: 315 (floor: 150)
- Pattern: the existing tests were well-structured with --dry-run checks that don't create files, so most --only group tests only needed the bug-referee→bug-hunter fix

## Task 12: init_agents_test.clj updates

### Changes made
- Total agent count: 14 → 8
- `--only test`: test-planner + test-writer (was test-planner, test-generator, test-healer)
- `--only bugfind`: resolved set `#{:bug-hunter}` (was `#{:bug-hunter :bug-skeptic :bug-referee}`)
- `--only orchestrator`: resolved set `#{:orchestrator}` (was `#{:orchestrator :test-orchestrator :qa-orchestrator}`)
- `--only core`: resolved set `#{:orchestrator :test :explorer :bug-hunter :product-analyst}` → 6 agents
- Bugfind workflow test flipped: now INCLUDED when bug-hunter is selected (was excluded, since old requirement was 3 agents)
- clojure-test template: `spel-test-generator-ct.md` → `spel-test-writer-ct.md`
- Learnings contract: `spel-qa-orchestrator` → `spel-orchestrator` (removed agent)

### Key pattern
- The `orchestrator-agent?` tests (string-matching utility) were kept unchanged — they test function behavior not agent existence
- The `:test` keyword in `resolved-only` works for both test-planner and test-writer since both map to `:test` in `agent-to-subagent`
- 118 test cases, 0 failures
