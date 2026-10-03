# Lightpanda support in Spel

Finish the optional browser engine and verify each native release.

## Context

The CLI parses `--engine lightpanda`, and `daemon.clj` launches a CDP server.
The existing CLI test uses a mock, not a real browser.
The snapshot reads computed styles and geometry. The Vis extension has no engine selector.
Keep Chrome as the default. Do not promise visual parity for Lightpanda.
Do not replace Playwright or restart existing services.
Private host configuration belongs in the infrastructure repository.

## Phases

1. Real-browser baseline
   - Rationale: distinguish working features from unsupported browser behavior.
   - Data: native CLI tests, pinned Lightpanda binary, isolated Linux session.
   - Acceptance criteria: reproduce gaps with retained regression tests.
   - Unknowns: CDP startup, snapshots, actions and cleanup.
2. Supported browser workflow
   - Rationale: make the optional engine useful without changing Chrome behavior.
   - Data: daemon, CLI, configuration, snapshot and Vis extension owners.
   - Acceptance criteria: supported actions pass; unsupported options fail clearly; lifecycle is safe.
   - Unknowns: upstream compatibility limits and required capability checks.
3. Verification and CI
   - Rationale: mocks cannot prove native browser compatibility.
   - Data: unit suites, native tests, formatting, lint and real Linux execution.
   - Acceptance criteria: local gates and exact-commit CI pass with real Lightpanda coverage.
   - Unknowns: cross-platform availability and unrelated baseline failures.
4. Publication and recurring verification
   - Rationale: verify the published artifact and each later release on the test host.
   - Data: immutable release assets, checksum verification and isolated test sessions.
   - Acceptance criteria: release from green CI; published checks pass; recurring verification is active.
   - Unknowns: current private test infrastructure and release automation.

## Plan state

- Complete: reproduce native navigation timeouts on macOS and on the Linux test host.
- Complete: create an owned context, validate engine options and expose Lightpanda reservations.
- Complete: real native tests and the extension boundary test pass with Lightpanda 1.0.0.
- In progress: full checks and exact-commit CI. The local full JVM suite hits an occupied fixed CDP port.
- Pending: native release, extension release, released-artifact verification and the recurring test service.
- Initial checkout: clean `main`, commit `4e5bedeb76e`, development version `0.9.41`.
- Latest published native release: `v0.9.40`. Existing services must remain unchanged.
