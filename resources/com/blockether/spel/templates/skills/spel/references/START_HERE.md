# Start Here

Quick map of spel skill.

## Reply style

Shape every reply ADHD-shaped, caveman-terse — see the skill's **Reply style**. Action first, numbered steps (max 5), restate "Step N of M", concrete time, show wins, one <2-min next action, no fluff, errors `location → cause → fix`.

## What spel does

- Browser automation via Playwright-native Clojure wrappers
- `eval-sci` scripting against live daemon session
- E2E testing, exploratory QA, visual captures, browser-driven product analysis

## Fast routing

- Full API surface: `references/FULL_API.md`
- Session rules and safe defaults: `references/PROFILES_CDP.md`
- SCI eval patterns: `references/EVAL_GUIDE.md`
- Selectors + snapshots: `references/SELECTORS_SNAPSHOTS.md`
- Navigation + wait behavior: `references/NAVIGATION_WAIT.md`
- Browser/profile/CDP setup: `references/PROFILES_CDP.md` + `references/BROWSER_OPTIONS.md`
- Network routing/interception: `references/NETWORK_ROUTING.md`
- Test/assertion patterns: `references/ASSERTIONS_EVENTS.md` + `references/API_TESTING.md`
- Report templates: `references/spel-report.html`, `references/spel-report.md`

## Critical operating rules

- Always use named session; never rely on default
- CDP: sessions may share one endpoint (each gets its own tab); only the same tab is exclusive
- Prefer snapshot refs first for interaction targeting
- Promised output files = hard deliverables, not optional summaries

## Typical starting patterns

```bash
# Resolve the name once. If later commands run in fresh shells, retain this
# resolved value in agent state; do not evaluate the timestamp again.
SESSION="exp-$(date +%s)"
spel --session "$SESSION" open https://example.com
spel --session "$SESSION" snapshot -i
spel --session "$SESSION" eval-sci '(spel/title)'
spel --session "$SESSION" close
```

```bash
SESSION="auto-$(date +%s)"
spel --session "$SESSION" --auto-launch open https://example.com
spel --session "$SESSION" --auto-launch snapshot -i
spel --session "$SESSION" close
```

```bash
# Explicit CDP endpoint:
SESSION="cdp-$(date +%s)"
spel --session "$SESSION" --cdp http://127.0.0.1:9222 open https://example.com
spel --session "$SESSION" --cdp http://127.0.0.1:9222 snapshot -i
spel --session "$SESSION" close
```