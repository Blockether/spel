---
description: Full E2E test coverage workflow - plans, challenges, generates, and heals tests
---

# Playwright E2E test coverage workflow

Orchestrates up to four agents in a pipeline to plan, challenge, generate, and heal E2E tests.

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

**GATE**: The planner must present the full spec to the user. Do NOT proceed until the spec is reviewed and approved. The spec file at `test-e2e/specs/<feature>-test-plan.md` is the source of truth for all subsequent steps.

## Challenge the spec (optional)

> Agent: @spel-spec-skeptic
> Only available if scaffolded with `--only=spec-skeptic` or `--only=test,spec-skeptic`.

```xml
<challenge-spec>
  <spec-file>test-e2e/specs/{{feature}}-test-plan.md</spec-file>
  <url><!-- target application URL --></url>
</challenge-spec>
```

The Spec Skeptic will:
1. Read the planner's spec
2. Challenge each test case: missing edge cases? fragile selectors? unrealistic assertions?
3. Score gaps: +1 minor improvement, +5 missing edge case, +10 critical gap
4. Produce `test-e2e/specs/<feature>-spec-review.json`

**GATE**: Review the Spec Skeptic's challenges. The planner may revise the spec based on feedback. Once finalized, proceed to generation.

## Generate

> Agent: @spel-test-generator

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

**GATE**: Review generated tests and run results before proceeding to healing.

## Heal

> Agent: @spel-test-healer

```xml
<heal>Run all E2E tests and fix the failing ones one after another.</heal>
```

**GATE**: Review healing report — what was broken, what changed, and why.

## Notes

- Test style varies by `--flavour` flag: `lazytest` (default) or `clojure-test`
- Use `spel snapshot -S --json` alongside functional tests to capture visual state for regression detection
- The spec skeptic step is optional but recommended for critical flows
- Every step has a GATE — human review before proceeding
- Each agent uses its own named session for browser isolation
