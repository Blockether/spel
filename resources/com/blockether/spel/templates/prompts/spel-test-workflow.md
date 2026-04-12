---
description: E2E test coverage workflow — one agent explores, generates, and self-heals tests
---

# Playwright E2E test coverage workflow

Single-agent pipeline. No spec file, no planner handoff — the writer explores, generates, and heals in one pass.

## Parameters

- Feature/area to test
- Target URL (running application)
- Seed file (default `test-e2e/<ns>/e2e/seed_test.clj`)

## Generate + heal — `@spel-test-writer`

```xml
<generate>
  <task>{{feature or scope to test}}</task>
  <url>{{target-url}}</url>
  <seed-file>test-e2e/<ns>/e2e/seed_test.clj</seed-file>
  <test-file>test-e2e/<ns>/e2e/{{feature}}_test.clj</test-file>
</generate>
```

Writer reads the seed, explores the live app, designs happy/edge/error scenarios, generates the test file, runs it, and self-heals failures (up to 2 iterations).

**GATE** — review generated tests + run results. Writer's report lists what was generated, what failed, what was healed, and why.

Required artifacts:

- `generation-report.json`
- `orchestration/test-pipeline.json`

## Notes

- Style varies by `--flavour`: `lazytest` (default) or `clojure-test`.
- Visual/responsive scope → writer captures `spel snapshot -S --json` alongside functional tests.
- Writer uses its own named session for browser isolation.
- Missing artifacts fail closed.
