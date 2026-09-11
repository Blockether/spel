# Spel 0.9.34 and vis-spel 0.1.3

Publish the native release and add release discovery to the Vis extension.

## Context

At task start, `resources/SPEL_VERSION` was 0.9.34 and native CI was green for
`7946bf9ba169df52cbe179ed6c3b6fdbc8f40609`. The latest published native release was
0.9.33. `extensions/vis-spel` was prepared for 0.1.2 and required SDK 0.1.69.
Its verified installer already selects a version for new reservations; existing
reservations retain their executable. Add release discovery rather than a second
installer or a redundant switching API. Simplify the extension README.

## Phases

1. Native publication
   - Rationale: extension CI must be able to install its new default version.
   - Data: native CI for the exact 0.9.34 commit; local make lint/test.
   - Acceptance criteria: tag that green commit; release workflow publishes all
     native assets and the Clojars artifact. Do not build or upload release binaries locally.
   - Unknowns: resolved; local gates and the release workflow passed.
2. Extension implementation and verification
   - Rationale: callers need discoverable releases and predictable switching.
   - Data: GitHub release metadata, existing installer and reservation tests.
   - Acceptance criteria: typed paginated release results; stable supported native
     versions only; upgrade/rollback and failure retention tests; explicit Activity;
     concise README; Python, native and trusted-worker checks pass.
   - Unknowns: resolved; live release metadata and version switching verified.
3. Extension publication
   - Rationale: ship the API and documentation together.
   - Data: scoped diff, extension version/lock, same-commit CI.
   - Acceptance criteria: verified changes pushed; green CI; vis-spel/v0.1.2
     published without marking it as the latest native release.
   - Unknowns: resolved; 0.1.2 was published after both CI workflows passed.
4. Publication verification and correction
   - Rationale: a GitHub release must also have working install instructions and
     an approved catalog version; native notes must not compare extension tags.
   - Data: the initial follow-up found a README command that failed SHA validation,
     a catalog still selecting 0.1.1 and native changelog selectors choosing extension tags.
   - Acceptance criteria: regression tests fail before correction and pass after;
     publish immutable extension 0.1.3 from green CI; approve it through the maintained
     catalog workflow; verify published install, update and rollback in a temporary directory.
   - Unknowns: resolved; publication, catalog approval and package lifecycle checks passed.

## Plan state

- Native 0.9.34 published from 7946bf9ba169df52cbe179ed6c3b6fdbc8f40609.
  Release workflow 34630736323 passed; all four digested binaries and Clojars
  deployment succeeded. Main's generated next-development version is now 0.9.35.
- Local make lint passed, including the native-image reflection gate. make test
  passed: 2,922 Clojure cases and the complete native CLI suite.
- Extension implementation complete: typed paginated release discovery, explicit
  Activity, native default 0.9.34 and a rewritten README. Version switching reuses
  the existing verified installer and preserves reservation executables.
- Release-discovery tests were red before implementation. Final checks passed:
  80 Python unit tests, three native browser tests (including an actual
  0.9.33 → 0.9.34 → 0.9.33 switch with live sessions), two trusted-worker tests,
  Python formatting/lint, Clojure formatting/reflection/lint and README links/examples.
- Extension [0.1.2](https://github.com/Blockether/spel/releases/tag/vis-spel/v0.1.2)
  published from 8b74535e7cbb29b4e41b79ccd75d109b06e8ca11 after native and extension CI passed.
- Follow-up regression tests reproduced the invalid README selector and both mixed-tag
  changelog selectors. The README now uses the approved-version path for 0.1.3;
  native changelog generation filters native tags, and the 0.9.34 section includes
  the commits omitted by the former selector. Published native notes already compare
  v0.9.33...v0.9.34; the four native artifacts do not need replacement.
- Follow-up local gates passed: make lint, make test (2,923 Clojure cases and the
  native CLI suite), 82 Python unit tests, workflow regression tests, Python formatting/lint,
  Clojure formatting/reflection/lint, actionlint and diff checks.
- Extension [0.1.3](https://github.com/Blockether/spel/releases/tag/vis-spel/v0.1.3)
  published from 1f24b0a1f008250d9b8f6ef2869687a5980f7640. Exact-commit native CI
  34639703963 passed on Linux, macOS and Windows; extension CI 34639703954 passed
  on Python 3.11 and 3.14, including the native browser checks.
- Catalog publication workflow 34644569927 passed. The public API and
  `vis-agent extension versions` both select approved 0.1.3 at that exact SHA,
  and the catalog serves the corrected README. The 0.1.2 release notes direct
  new installations to the corrected, approved 0.1.3 release.
- The retained opt-in publication test passed against the public catalog:
  install 0.1.3, roll back to 0.1.1 and update to 0.1.3 in a temporary directory.
  The test checks the selected revision and actual installed package metadata.
  No global extension installation or browser reservation was changed.
- Native v0.9.34 remains GitHub's latest release; Clojars reports 0.9.34 as its
  latest release and all four native assets have digests. Allure Report 34644422043
  and Pages deployment 34644680876 passed.
- Complete: native binaries, library and catalog-approved extension are published
  and verified. No release tags or artifacts were replaced.
