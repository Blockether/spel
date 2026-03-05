---
description: Full E2E test coverage workflow - plans, challenges, generates, and heals tests
---

# Playwright E2E Test Coverage Workflow

Orchestrates up to four agents in a pipeline to plan, challenge, generate, and heal E2E tests.

## Parameters

- **Task**: The feature or area to test
- **Target URL**: The URL of the running application
- **Seed file** (optional): defaults to `test-e2e/<ns>/e2e/seed_test.clj`
- **Test plan file** (optional): under `test-e2e/specs/` folder

## Step 1: Plan & Explore (SPEC FIRST)

> **Agent**: @spel-test-planner

The planner opens the browser **interactively** (visible to the user), explores the application using snapshots and annotations, and produces a **spec** (test plan).

Call @spel-test-planner with:

```xml
<plan>
  <task><!-- the feature to test --></task>
  <url><!-- target application URL --></url>
  <seed-file><!-- path to seed file, default: test-e2e/<ns>/e2e/seed_test.clj --></seed-file>
  <plan-file><!-- path to test plan file to generate, e.g. test-e2e/specs/auth-test-plan.md --></plan-file>
</plan>
```

**GATE**: The planner must present the full spec to the user. Do NOT proceed until the spec is reviewed and approved. The spec file at `test-e2e/specs/<feature>-test-plan.md` is the source of truth for all subsequent steps.

## Step 1.5 (Optional): Challenge the Spec

> **Agent**: @spel-spec-skeptic
> Only available if scaffolded with `--only=spec-skeptic` or `--only=test,spec-skeptic`.

If @spel-spec-skeptic is available, invoke it to adversarially review the test plan before generation:

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

**GATE**: Review the Spec Skeptic's challenges. The planner may revise the spec based on feedback. Once the spec is finalized, proceed to generation.

## Step 2: Generate

> **Agent**: @spel-test-generator

For each test case from the spec (1.1, 1.2, ...), one after another (NOT in parallel), call @spel-test-generator with:

The generator opens the browser **interactively** to verify selectors from the spec against the live app before writing test code.

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

## Step 3: Heal

> **Agent**: @spel-test-healer

The healer opens the browser **interactively** to investigate failures visually.

```xml
<heal>Run all E2E tests and fix the failing ones one after another.</heal>
```

**GATE**: Review healing report — what was broken, what changed, and why.

## Notes

- Test style varies by `--flavour` flag: `lazytest` (default) or `clojure-test`
- Use `spel snapshot -S --json` alongside functional tests to capture visual state for regression detection
- Step 1.5 (Spec Skeptic) is optional but recommended for critical flows — it catches missing edge cases before code is generated
- Every step has a GATE — human review before proceeding
- Each agent uses its own named session for browser isolation
