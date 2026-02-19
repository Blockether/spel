---
description: Full E2E test coverage workflow - plans, generates, and heals tests
---

# Playwright E2E Test Coverage Workflow

Parameters:
- Task: the feature or area to test
- Target URL: the URL of the running application
- Seed file (optional): defaults to `test-e2e/<ns>/e2e/seed_test.clj`
- Test plan file (optional): under `test-e2e/specs/` folder

## Step 1: Plan & Explore (SPEC FIRST)

The planner opens the browser **interactively** (visible to the user), explores the application using snapshots and annotations, and produces a **spec** (test plan).

Call @spel-test-planner with:

<plan>
  <task><!-- the feature to test --></task>
  <url><!-- target application URL --></url>
  <seed-file><!-- path to seed file, default: test-e2e/<ns>/e2e/seed_test.clj --></seed-file>
  <plan-file><!-- path to test plan file to generate, e.g. test-e2e/specs/auth-test-plan.md --></plan-file>
</plan>

**GATE**: The planner must present the full spec to the user. Do NOT proceed to Step 2 until the spec is reviewed. The spec file at `test-e2e/specs/<feature>-test-plan.md` is the source of truth for all subsequent steps.

## Step 2: Generate

For each test case from the spec (1.1, 1.2, ...), one after another (NOT in parallel), call @spel-test-generator with:

The generator opens the browser **interactively** to verify selectors from the spec against the live app before writing test code.

<generate>
  <test-suite><!-- Verbatim name of the test group without ordinal, e.g. "Login Flow" --></test-suite>
  <test-name><!-- Name of the test case without ordinal, e.g. "successful login with valid credentials" --></test-name>
  <test-file><!-- File path, e.g. test-e2e/<ns>/e2e/auth/login_test.clj --></test-file>
  <seed-file><!-- Seed file from plan --></seed-file>
  <body><!-- Test case steps and expectations from the spec --></body>
</generate>

## Step 3: Heal

Call @spel-test-healer with:

The healer opens the browser **interactively** to investigate failures visually.

<heal>Run all E2E tests and fix the failing ones one after another.</heal>
