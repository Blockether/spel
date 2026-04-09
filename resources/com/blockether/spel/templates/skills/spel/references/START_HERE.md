# Start Here

Quick map of spel skill.

## What spel does

- Browser automation via Playwright-native Clojure wrappers
- `eval-sci` scripting against live daemon session
- E2E testing, exploratory QA, visual captures, browser-driven product analysis

## Fast routing

- Full API surface: `references/FULL_API.md`
- Common agent/session rules: `references/AGENT_COMMON.md`
- SCI eval patterns: `references/EVAL_GUIDE.md`
- Selectors + snapshots: `references/SELECTORS_SNAPSHOTS.md`
- Navigation + wait behavior: `references/NAVIGATION_WAIT.md`
- Browser/profile/CDP setup: `references/PROFILES_AGENTS.md` + `references/BROWSER_OPTIONS.md`
- Network routing/interception: `references/NETWORK_ROUTING.md`
- Test/assertion patterns: `references/ASSERTIONS_EVENTS.md` + `references/TESTING_CONVENTIONS.md`
- Product discovery/reporting: `references/PRODUCT_DISCOVERY.md`, `references/spel-report.html`, `references/spel-report.md`

## Critical operating rules

- Always use named session; never rely on default
- CDP: one session per endpoint; no concurrent multi-session attach
- Prefer snapshot refs first for interaction targeting
- Promised output files = hard deliverables, not optional summaries

## Typical starting patterns

```bash
spel --session exp-$(date +%s) open https://example.com
spel --session exp-$(date +%s) snapshot -i
spel --session exp-$(date +%s) eval-sci '(spel/title)'
```

```bash
spel --session auto-$(date +%s) --auto-launch open https://example.com
spel --session auto-$(date +%s) --auto-launch snapshot -i
```

```bash
# Explicit CDP endpoint:
spel --session cdp-$(date +%s) --cdp http://127.0.0.1:9222 open https://example.com
spel --session cdp-$(date +%s) --cdp http://127.0.0.1:9222 snapshot -i
```