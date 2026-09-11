# Vis Spel extension and marketplace

Publish a tested browser automation package from the Spel repository.

## Context

The Python package belongs in `extensions/vis-spel`, not Vis. Existing CLI owns
browser behavior. Review of 250 matching Vis sessions found repeated named-session,
snapshot, JavaScript, screenshot, CDP, timeout and stale-reference workflows.
Do not restore the removed browser bridge or duplicate Playwright in Python.
The marketplace lives in Vis `apps/vis-docs`. Coordinate with concurrent docs work
and preserve its separately committed UI and repository-validation changes.

## Phases

1. Package and session lifecycle
   - Rationale: isolate concurrent callers and remove shell quoting from browser work.
   - Data: Spel CLI JSON, official release assets and SDK package contracts.
   - Acceptance criteria: explicit verified install; persistent exclusive leases;
     isolated browser/CDP operations; typed results and explicit Activities.
   - Unknowns: user clarification of “connect with code” (pairing vs SCI).
2. Verification
   - Rationale: unit results alone do not prove trusted-worker/browser compatibility.
   - Data: pytest, real native CLI, extension registration and tool execution.
   - Acceptance criteria: package tests, lint/format, make lint/test and green CI.
   - Unknowns: native test prerequisites and external CI availability.
3. Marketplace and publication
   - Rationale: users need package details and feedback on the listing.
   - Data: Vis Worker, D1, catalog UI and existing moderation workflow.
   - Acceptance criteria: README, descriptions, comments, package/comment votes;
     tested UI/API; source pushed to GitHub and reviewed listing published.
   - Unknowns: resolved through the authenticated Extension Center publication workflow.

## Plan state

- Research complete: 250 relevant historical sessions reviewed.
- Package implemented: 14 explicit tools, verified installer, persistent reservations,
  authorized CDP, stdin JavaScript/SCI and bundled browser skill.
- Verification passed: 55 Python tests including real DOM and external CDP isolation;
  trusted-worker suite; Python formatting/lint and Clojure reflection/lint; make lint/test.
- Package 0.1.0 published as GitHub Release `vis-spel/v0.1.0` at
  f0f8d51cb65b51475251d095f845945a9cdf66d4. Extension CI and full
  Linux/macOS/Windows native CI passed before the tag. No native version change.
- Marketplace README, descriptions, moderated comments and package/comment votes
  implemented and deployed in Vis fe7497c34; 117 tests and lint/build checks passed.
- Uses the released PyPI SDK 0.1.64. Latest extension verification: 60 Python tests,
  including real native browser/CDP cases; formatting and lint pass.
- Public listing approved at https://vis.blockether.com/extensions/869b34e042a90c0bcc326445.
  GitHub stars are refreshed independently of package releases and match GitHub.
- Linux validation passed: approved-version installation from project YAML, cached and
  offline sync preserving source/uv state, tool registration and both real browser/CDP tests.
  The existing Vis binary and gateway were not replaced or restarted.
- Desktop and touch layouts checked. Native full-page annotation overlays shifted
  on the responsive page; documented the unannotated viewport alternative.
- Pairing-code meaning still requires clarification; the removed bridge is not restored.
