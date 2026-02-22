# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.3.1] - 2026-02-22

### Changed
- BLO-93: Fix CI lint - commit clj-kondo config to suppress unused-binding
- BLO-93: Fix CI lint failure - reduce unused-binding to info level
- Restore .lsp/config.edn with unused-public-var linter disabled
- Restore original LIST view - flex column with horizontal cards
- Fix lint issues: suppress unused public var warnings for API functions
- Make build cards full-width on mobile for better list experience
- Fix mobile responsiveness: 3-column grid on builds page, integrate API testing into Quick Start
- Add inline video player modal to Allure reports
- Fix compact view: equal-sized grid cards with min-height
- Add cljfmt indents for new API testing macros
- Add clojure.test version for new API testing functions
- Update README with full API testing docs and fix compact view on mobile
- Never commit lsp and clj-kondo
- Remove clj-kondo and .lsp configs
- Add clj-kondo hooks for with-testing-api and with-page-api macros
- feat: add page-api, context-api, with-testing-api, with-page-api for easy API testing
- feat: add compact view toggle to builds list + keep 50 builds
- fix: use workflow_run trigger so in-progress build shows correct run number
- fix: remove unused private var allure-result-file?
- ci: add commit author to Allure report name
- docs: vary SKILL link phrasing, tighten README prose
- docs: revamp README — lean landing page, merge Quick Start with with-testing-page
- chore: bump Allure CLI 3.1.0 → 3.2.0, add lint rule, clean up SKILL template
- docs: update SKILL.md.template with with-testing-page section and verification checklist
- style: format 14 namespaces with clojure-lsp
- docs: add with-testing-page to SKILL template and SKILL check rule to AGENTS.md
- feat: auto-attach traces to clojure.test Allure reporter
- feat: add merge-reports CLI command and library API
- feat: add :profile and browser launch opts to with-testing-page
- docs: add with-testing-page section to README
- docs: clarify clj-kondo CLI unavailability in AGENTS.md
- test: add with-testing-page tests for Lazytest and clojure.test
- feat: add clj-kondo hooks for with-testing-page dual-arity
- refactor: use shared devices namespace in daemon
- feat: add with-testing-page macro with device/viewport support and auto-tracing
- feat: add shared device and viewport presets namespace
- BLO-93: Align badges to left on mobile
- BLO-93: Fix version badge spacing on mobile
- Split Allure into two workflows to fix artifact conflict
- BLO-92: Store theme globally (shared across all builds)
- BLO-92: Remove unused with-video-page refer to fix lint
- BLO-92: Scope Allure localStorage by report path with 30-day auto-cleanup
- Fix artifact conflict - remove duplicate in-progress pages deploy
- Hide chevron arrow on in-progress build cards
- feat: add in-progress build tracking to Allure landing page
- Remove headless environments documentation from README
- Delete ARCHITECTURE-NOTES.md
- BLO-90: Remove LATEST badge from builds list
- feat: add commit author to build list and Allure report
- fix: eliminate boxed math and reflection warnings for GraalVM safety
- fix: unify landing page badges as HTML pills with visible borders
- feat: replace Python CI scripts with Clojure ci-assemble command
- feat: add spel/help and spel/source for runtime API discovery
- BLO-91: Update SKILL template with version badges documentation
- BLO-91: Add version badges to Allure landing page
- Add video recording support for browser sessions
- BLO-89: Add headless environments documentation to README
- BLO-89: Add xvfb-run auto-detection for headless codegen record
- BLO-90: Add version badges to Allure landing page
- Add cleanup step before Allure test runs
- BLO-87: Fix unmatched paren in test file
- BLO-87: Fix boxed math warnings and Windows line-ending test failures
- BLO-87: Fix ct namespace nesting in Allure - use common parent
- BLO-88: Fix Allure workflow - make github-pages environment conditional for PRs
- BLO-88: Enable Allure reports for PR/branches
- release: update version files for v0.3.0, bump to next dev version


## [v0.3.0] - 2026-02-20

### Changed
- BLO-86: Release v0.3.0
- fix: add missing closing paren in navigate handler defmethod
- BLO-85: Fix --screenshot flag not saving file when used with open command
- feat: add report/clean config to both Allure reporters, run clojure.test in CI
- feat: browser config via system properties (slow-mo, engine, interactive)
- feat: polymorphic report builder (report->html, report->pdf) with typed entries
- feat: auto-capture console/errors in --eval, add anti-sleep and SPA wait guidance
- feat: auto-resolve @eN refs in all spel/ functions, fix eval timeout architecture
- docs: remove verbose test mapping table from AGENTS.md
- docs: add testing policy to AGENTS.md, add stdout/stderr capture tests
- fix: capture both stdout and stderr, preserve output on error
- fix: capture stdout during sci_eval to enable println in --eval mode
- fix: resolve lint errors in markdown_test and init_agents
- refactor: global defmethod hooks for clojure.test Allure reporter
- feat: add clojure.test Allure reporter with auto-injected fixtures
- feat: add --flavour flag to init-agents for clojure.test support
- fix: replace Lazytest references with spel.allure in all templates, fix test-e2e paths
- feat: add spel.markdown (from/to-markdown-table), replace data.json with charred
- feat: add --no-tests flag to init-agents for interactive-only scaffolding
- docs: add prefer --eval over standalone CLI rule to SKILL template
- feat: include test-e2e in Playwright tracing source directories
- fix: promote Native CLI to top-level README section, fix broken nav anchor
- feat: add spel.roles namespace, replace AriaRole imports with idiomatic Clojure vars
- release: update version files for v0.2.0, bump to next dev version


## [v0.2.0] - 2026-02-19

### Changed
- chore: set SPEL_VERSION to 0.2.0 for release
- feat: Allure version in builds, classpath trace viewer, with-traced-page default, cljfmt
- feat: eval file support, --load-state flag, SCI page/locator namespaces
- release: update version files for v0.1.0, bump to next dev version


## [v0.1.0] - 2026-02-19

### Changed
- feat: interactive test mode, spel.allure imports, init-agents --dir, code formatting
- feat: add storage state API, version management, and SKILL docs
- feat: add --interactive to eval mode, storage state examples to codegen help
- release: update README and CHANGELOG for v0.0.2


## [v0.0.2] - 2026-02-19

### Changed
- Consolidate README: replace verbose Usage with SKILL reference, fix lint warning, update docs and tests
- Fix Trace Viewer source path resolution: prepend source directory prefix
- Fix Trace Viewer source mapping and codegen CLI improvements
- Add corporate CA certificate support for SSL-inspecting proxies
- feat(allure): integrate Allure reporting with lazytest macros
- Auto-save in-flight traces when daemon shuts down or context is replaced
- Fix codegen selector translation: extract role name, exact flag, and wrap assertions with assert-that
- Add tracing API to SCI --eval environment
- Add routeFromHAR and routeWebSocket wrappers for complete mock API coverage
- Add inspector and show-trace commands for Playwright visual tools
- Replace inline changelog in README with reference to CHANGELOG.md
- Make landing page title, subtitle, and logo configurable via env vars
- Fix spel install, update SKILL.md docs, add changelog, remove Makefile refs from templates
- Add clickable CI pipeline link to run number in Allure landing page
- Fix Allure report logo 404: copy logo file into report output dir
- Add landing page with clickable SHA links, date grouping, and pass/fail status
- Add logo support for Allure report header
- Add commit SHA and message to Allure report history entries
- Add per-build Allure report archives with clickable history links
- Fix reflection warning in detect-source-dirs String/join call
- Fix Allure history: switch to JSONL mechanism with configurable limit
- Auto-detect Clojure source dirs for Playwright trace sources
- Update clj-kondo sinker hook import
- Add --full flag to annotate: annotate all elements, not just viewport
- Fix try plus hook
- Fix Allure report history: preserve history/ across runs for trend dropdown
- release: update README and CHANGELOG for v0.0.1


## [v0.0.1] - 2026-02-18

### Changed
- Fix unresolved clojure.string namespace in integration test
- Update clj-kondo sinker hook import
- Regenerate scaffolded agents, skills, and specs from updated templates
- Update agent and skill templates for E2E test workflow
- Update README: rationale, install docs, Gatekeeper note, remove macOS Intel
- Fix Windows double .exe in build, remove macOS amd64 from CI, update Allure workflow
- Expand integration test coverage for codegen and tracing
- Improve daemon robustness and native image compatibility
- Fix codegen for Playwright 1.58+ locator kinds and improve CLI error messages
- Expand context options mapping and add anomaly helpers to core
- Add Allure trace groups, test title binding, and fixture error guards
- Preserve Allure report data across deployments so historical test results don't 404
- Auto-update README version on tag, consolidate release creation in native-image workflow
- Add dev build install instructions, macOS Gatekeeper note, and full release docs to README
- Fix CI: use Java driver CLI for browser install, handle Windows native-image.cmd, normalize cache paths
- Remove spel/start! from SKILL.md snapshot examples — daemon manages browser lifecycle
- Guard test fixtures against anomaly maps from safe-wrapped Playwright calls
- Upgrade setup-clojure to @13.5, restrict lint/graal checks to Linux
- Persist Allure history across CI runs via actions/cache
- Fix CI: install all browser deps, guard close-page!/close-browser! against anomaly maps
- Fix Windows CI: default to bash shell for cross-platform compatibility
- Add Allure Report badge and test report screenshots to README
- Fix README nav link and eval column wrapping, harden test fixture cleanup
- Update README.md and allure.yml
- Fix description of all spel evaluation
- Fix codegen for default roles
- Attempt to fix CI
- fix: use built-in Allure 3 reporter pipeline in CI, remove redundant allure generate
- Add docs regeneration scripts
- feat: daemon-backed --eval mode, per-command help, CLI tests, CI smoke tests, strict mode docs
- Initial commit


### Added
- Initial release of spel
- Clojure wrapper for Microsoft Playwright 1.58.0
- Browser automation: page navigation, screenshots, PDF generation
- Locator API for element selection and interaction
- Network interception and request/response handling
- Assertions module for test verification
- Input handling (keyboard, mouse, touch)
- Frame support for iframe interaction
- Codegen: record browser sessions and transform JSONL to Clojure test code
- CLI tools: install, codegen, open, screenshot, pdf
- Page exploration utility
- Allure test reporting integration
- OpenCode agent scaffolding via init-agents

[Unreleased]: https://github.com/Blockether/spel/compare/v0.3.1...HEAD
[v0.0.1]: https://github.com/Blockether/spel/releases/tag/v0.0.1
[v0.0.2]: https://github.com/Blockether/spel/releases/tag/v0.0.2
[v0.1.0]: https://github.com/Blockether/spel/releases/tag/v0.1.0
[v0.2.0]: https://github.com/Blockether/spel/releases/tag/v0.2.0
[v0.3.0]: https://github.com/Blockether/spel/releases/tag/v0.3.0
[v0.3.1]: https://github.com/Blockether/spel/releases/tag/v0.3.1
