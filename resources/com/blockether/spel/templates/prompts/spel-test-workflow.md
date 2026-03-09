---
description: E2E test coverage workflow — plans and writes tests with built-in challenge and self-heal
---

# Playwright E2E test coverage workflow

Orchestrates two agents in a pipeline to plan, challenge, generate, and heal E2E tests.

The orchestrator must maintain `orchestration/test-pipeline.json` as the machine-readable handoff for stage status and produced artifacts.

## Parameters

- Task: the feature or area to test
- Target URL: the URL of the running application
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

The planner explores the target, writes a test spec, and optionally self-challenges the spec (checking for missing edge cases, fragile selectors, unrealistic assertions). The self-challenge is built into the planner — no separate agent invocation needed.

**GATE**: The planner must present the full spec to the user. Do NOT proceed until the spec is reviewed and approved. The spec file at `test-e2e/specs/<feature>-test-plan.md` is the source of truth for all subsequent steps.
Required artifacts before this gate:
- `test-e2e/specs/<feature>-test-plan.md`
- `test-e2e/specs/<feature>-test-plan.json`

## Write tests (generate + heal)

> Agent: @spel-test-writer

For each test case from the spec (1.1, 1.2, ...), one after another (NOT in parallel):

```xml
<generate>
  <test-suite><!-- Verbatim name of the test group without ordinal, e.g. "Login Flow" --></test-suite>
  <test-name><!-- Name of the test case without ordinal, e.g. "successful login with valid credentials" --></test-name>
  <test-file><!-- File path, e.g. test-e2e/<ns>/e2e/auth/login_test.clj --></test-file>
  <seed-file><!-- Seed file from plan --></seed-file>
  <body><!-- Test case steps and expectations from the spec --></body>
</generate>
```

The test writer generates each test, runs it, and self-heals any failures. The generate-and-heal cycle is built into the writer — no separate agent invocation needed.

**GATE**: Review generated tests and run results. The writer's report includes what was generated, what failed, what was healed, and why.
Required artifacts before this gate:
- `generation-report.json`
- `orchestration/test-pipeline.json`

## Notes

- Test style varies by `--flavour` flag: `lazytest` (default) or `clojure-test`
- Use `spel snapshot -S --json` alongside functional tests to capture visual state for regression detection
- The planner's self-challenge step is optional but recommended for critical flows
- Every step has a GATE — human review before proceeding
- Each agent uses its own named session for browser isolation
- Missing artifacts fail closed: if a promised JSON file is absent, the step is incomplete
