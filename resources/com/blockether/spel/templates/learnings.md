# LEARNINGS

Use this schema when an agent needs to create `LEARNINGS.md` lazily during a `--learnings` run.

Purpose:
- Capture what worked, what failed, and what confused each agent.
- Preserve exact reproductions for high-level issues.
- Feed reliable evidence back into template/prompt refinement.
- Produce concrete corrective actions that can be applied in prompts/skills/templates.

## High-Level Issues (cross-agent synthesis)

Document only validated cross-cutting issues.

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

Every participating agent appends to its own section.
Do not overwrite existing sections; append newest entries.
Write/update agent sections immediately after each completed stage or pipeline, not only at the very end.

### Agent: <agent-name>
#### What worked
- <beneficial decisions, patterns, or instructions>

#### What went wrong
- <failures, dead ends, regressions>

#### Confusions (skills/instructions/tooling)
- <ambiguous instruction, conflicting guidance, missing context>

#### Root Cause and Corrective Action
- Root cause hypothesis: <why this failed/confused>
- Correction proposal (prompt/skill/template): <specific edit>
- Expected effect of correction: <how behavior should improve>

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
- Scope: <prompt|skill|template|tooling>
- Priority: <P0|P1|P2>
- Confidence: <high|medium|low>
- Change: <exact proposed edit>
- Why now: <impact if fixed>
