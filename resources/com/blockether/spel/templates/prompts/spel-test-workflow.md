---
description: E2E test coverage workflow — plans and writes tests with built-in challenge and self-heal
---

# Playwright E2E test coverage workflow

Two-agent pipeline → plan, challenge, generate, heal E2E tests.

Orchestrator must maintain `orchestration/test-pipeline.json` as machine-readable handoff for stage status + produced artifacts.

## Parameters

- Task: feature or area to test
- Target URL: running application URL
- Seed file (optional): defaults to `test-e2e/<ns>/e2e/seed_test.clj`
- Test plan file (optional): under `test-e2e/specs/` folder

## Plan and explore (spec first)

> Agent: @spel-test-planner

```xml
<plan>
  <task><!-- the feature to test --></task>
  <url><!-- target application URL --></url>
  <seed-file><!-- path to seed file, default: test-e2e/<ns>/e2e/seed_test.clj --></seed-file>
  <plan-file><!-- path to test plan file to generate, e.g. test-e2e/specs/auth-test-plan.md --></plan-file>
</plan>
```

Planner explores target, writes test spec, optionally self-challenges spec (checks missing edge cases, fragile selectors, unrealistic assertions). Self-challenge built into planner → no separate agent invocation needed.

**GATE**: Planner must present full spec to user. Do NOT proceed until spec reviewed + approved. Spec file at `test-e2e/specs/<feature>-test-plan.md` = source of truth for all subsequent steps.
Required artifacts before this gate:
- `test-e2e/specs/<feature>-test-plan.md`
- `test-e2e/specs/<feature>-test-plan.json`

## Write tests (generate + heal)

> Agent: @spel-test-writer

For each test case from spec (1.1, 1.2, ...), one after another (NOT parallel):

```xml
<generate>
  <test-suite><!-- Verbatim name of the test group without ordinal, e.g. "Login Flow" --></test-suite>
  <test-name><!-- Name of the test case without ordinal, e.g. "successful login with valid credentials" --></test-name>
  <test-file><!-- File path, e.g. test-e2e/<ns>/e2e/auth/login_test.clj --></test-file>
  <seed-file><!-- Seed file from plan --></seed-file>
  <body><!-- Test case steps and expectations from the spec --></body>
</generate>
```

Writer generates each test, runs it, self-heals failures. Generate-and-heal cycle built into writer → no separate agent invocation needed.

**GATE**: Review generated tests + run results. Writer's report includes what was generated, what failed, what was healed, and why.
Required artifacts before this gate:
- `generation-report.json`
- `orchestration/test-pipeline.json`

## Notes

- Test style varies by `--flavour` flag: `lazytest` (default) or `clojure-test`
- Use `spel snapshot -S --json` alongside functional tests → capture visual state for regression detection
- Planner's self-challenge step optional but recommended for critical flows
- Every step has GATE → human review before proceeding
- Each agent uses own named session for browser isolation
- Missing artifacts fail closed: if promised JSON file absent → step incomplete
