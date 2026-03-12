# Start Here

Use this file first when you need a quick map of the spel skill.

## What spel is for

- Browser automation with Playwright-native Clojure wrappers
- `eval-sci` scripting against a live daemon session
- E2E testing, exploratory QA, visual captures, and browser-driven product analysis

## Fast routing

- Need the full API surface: `references/FULL_API.md`
- Need common agent/session rules: `references/AGENT_COMMON.md`
- Need SCI eval patterns: `references/EVAL_GUIDE.md`
- Need selectors and snapshots: `references/SELECTORS_SNAPSHOTS.md`
- Need navigation and wait behavior: `references/NAVIGATION_WAIT.md`
- Need browser/profile/CDP setup: `references/PROFILES_AGENTS.md` and `references/BROWSER_OPTIONS.md`
- Need network routing/interception: `references/NETWORK_ROUTING.md`
- Need test/assertion patterns: `references/ASSERTIONS_EVENTS.md` and `references/TESTING_CONVENTIONS.md`
- Need product discovery/reporting: `references/PRODUCT_DISCOVERY.md`, `references/spel-report.html`, `references/spel-report.md`

## Critical operating rules

- Always use a named session; never rely on the default session
- For CDP, one session owns one endpoint; do not attach multiple sessions concurrently
- Prefer snapshot refs first for interaction targeting
- Treat promised output files as hard deliverables, not optional summaries

## Typical starting patterns

```bash
spel --session exp-$(date +%s) open https://example.com
spel --session exp-$(date +%s) snapshot -i
spel --session exp-$(date +%s) eval-sci '(spel/title)'
```

```bash
spel --session auto-$(date +%s) --cdp http://127.0.0.1:9222 open https://example.com
spel --session auto-$(date +%s) --cdp http://127.0.0.1:9222 snapshot -i
```
