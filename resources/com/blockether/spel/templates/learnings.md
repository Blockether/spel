# LEARNINGS

Schema for `LEARNINGS.md` created lazily during `--learnings` run.

Purpose:
- Capture what worked, failed, confused each agent.
- Preserve exact reproductions for high-level issues.
- Feed evidence back into template/skill refinement.
- Produce corrective actions applicable to agents/skills/templates.

## High-Level Issues (cross-agent synthesis)

Validated cross-cutting issues only.

### ISSUE-001: <short title>
- Impact: <why this matters>
- Confidence: <high|medium|low>
- Affected agents: <comma-separated>
- Context: <page/feature/state>
- Preconditions: <required setup>
- Steps:
  1. <step one>
  2. <step two>
- Expected: <expected behavior>
- Actual: <actual behavior>
- Evidence: <screenshot path / log / snapshot ref>

## Agent-Scoped Learnings

Each agent appends to own section.
Don't overwrite existing sections → append newest.
Write/update after each completed stage/pipeline, not only at end.

### Agent: <agent-name>
#### What worked
- <beneficial decisions, patterns, instructions>

#### What went wrong
- <failures, dead ends, regressions>

#### Confusions (skills/instructions/tooling)
- <ambiguous instruction, conflicting guidance, missing context>

#### Root Cause and Corrective Action
- Root cause hypothesis: <why failed/confused>
- Correction proposal (agent/skill/template): <specific edit>
- Expected effect: <how behavior should improve>

#### Instruction Confusions (quote exact text)
- Confusing instruction: "<exact quote>"
- Why confusing: <failure mode>
- Proposed rewrite: "<replacement instruction>"

#### Beneficial patterns
- <reusable strategy that improved outcomes>

#### Exact Reproductions
##### ISSUE-<id>
- Context: <page/feature/state>
- Preconditions: <required setup>
- Steps:
  1. <step one>
  2. <step two>
- Expected: <expected behavior>
- Actual: <actual behavior>
- Evidence: <screenshot path / log / snapshot ref>

## Corrective Backlog

Prioritize concrete fixes discovered across agents.

### C-001: <fix title>
- Scope: <agent|skill|template|tooling>
- Priority: <P0|P1|P2>
- Confidence: <high|medium|low>
- Change: <exact proposed edit>
- Why now: <impact if fixed>
