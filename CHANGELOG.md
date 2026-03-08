# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.5.19] - 2026-03-08

### Changed
- chore: remove LEARNINGS.md and bugfind-reports from repo root
- release: update version files for v0.5.18, bump to next dev version
- feat(agents): add orchestrator routing, discovery pipeline, report template enhancements, and Unbound site audit


## [v0.5.18] - 2026-03-08

### Changed
- fix(init-agents): use record format for tools in product-analyst template
- fix(init-agents): suppress --ns warning when --no-tests is set
- release: update version files for v0.5.17, bump to next dev version


## [v0.5.17] - 2026-03-07

### Changed
- feat(init-agents): add opt-in learnings and markdown report scaffolding
- release: update version files for v0.5.16, bump to next dev version


## [v0.5.16] - 2026-03-07

### Changed
- feat(report): elevate unified report clarity and visual storytelling
- fix(init-agents): improve Claude template transformation fidelity
- chore: wire orphaned refs into subagent-ref-map + delete dogfood-report.html
- fix(init-agents): --no-tests now scaffolds all agents, skips only seed test + specs
- feat(cross-agent-integration): wire product-spec.json downstream + unified spel-report.html
- test(integration): add CLI regression tests for unified template scaffolding
- chore: delete old report templates, update init_agents ref-map, regenerate docs
- refactor(templates): update all remaining template references to spel-report.html
- refactor(product-analyst): update template reference to spel-report.html
- fix(templates): use canonical responsive_behavior dimension name in appendix
- feat(templates): create unified spel-report.html merging QA and product reports
- feat(bug-hunter): add product-spec.json consumption and feature-enriched coverage
- feat(product-analyst): add viewport mandate to Phase 6 coherence audit
- fix(visual-qa): remove duplicate severity/GATE blocks and add product-spec.json input
- chore: regenerate FULL_API.md and help-registry after discovery agent addition
- test(discovery): add CLI regression tests for discovery group
- docs(discovery): update agent counts and group references
- feat(discovery): wire product-analyst into init-agents scaffolding
- feat(discovery): add product-analyst to orchestrator pipelines
- feat(discovery): add product-report.html template with sidebar navigation
- feat(discovery): add product-analyst agent template and PRODUCT_DISCOVERY ref
- refactor(agents): strip duplicated patterns from test pipeline agents
- refactor(agents): strip duplicated patterns from automation+visual agents
- feat(discovery): add discovery workflow prompt
- refactor(agents): strip duplicated patterns from bugfind pipeline agents
- refactor(agents): extract 5 common patterns to AGENT_COMMON.md
- release: update version files for v0.5.15, bump to next dev version


## [v0.5.15] - 2026-03-07

### Changed
- fix(templates): change orchestrator mode from 'agent' to 'all' and add visual checks + viewport sections to QA report
- feat(templates): enforce multi-viewport testing with structured evidence
- fix(templates): require annotated screenshots with action markers as bug evidence
- feat(templates): add visual_checks schema and visual coherence detection
- fix(templates): restore re-snapshot examples and add visual anomaly detection
- style(templates): humanize all 43 agent/skill markdown files
- feat(action-log): add daemon-level action tracking with SRT export
- feat(templates): add QA report HTML template and integrate dogfood workflow
- release: update version files for v0.5.14, bump to next dev version


## [v0.5.14] - 2026-03-06

### Changed
- feat(templates): add 4 orchestrator agents for smart pipeline routing
- release: update version files for v0.5.13, bump to next dev version


## [v0.5.13] - 2026-03-06

### Changed
- fix(templates): allow all bash commands for subagent-only agents
- test(snapshot): add 6 Playwright integration tests for [pos:X,Y W×H] tree output
- feat(snapshot): add [pos:X,Y W×H] screen position to tree output
- feat(visual-diff): add semantic region enrichment with accessibility snapshot labels
- release: update version files for v0.5.12, bump to next dev version


## [v0.5.12] - 2026-03-06

### Changed
- feat(templates): integrate QA methodology into bugfind agent templates
- feat(templates): integrate OpenAI QA methodology into test agent templates
- fix(docs): replace deprecated --eval with eval-sci in source code, README, and docs
- fix(templates): replace deprecated --eval with eval-sci across all 27 template files
- feat(templates): add snapshot-first refs, flavor awareness, and JSON gate artifacts to test agents
- feat(templates): wire test agent pipeline with inter-agent handoffs and contracts
- fix(test-cli): use portable mktemp for macOS compatibility
- fix(docs): add missing search, role, and allure namespaces to generated API docs
- feat(templates): add cookie consent, snapshot-first interaction, and e-commerce patterns to agent templates
- release: update version files for v0.5.11, bump to next dev version


## [v0.5.11] - 2026-03-06

### Changed
- fix: visual diff shows original image with red overlay, snake_case JSON, CLI routing
- release: update version files for v0.5.10, bump to next dev version


## [v0.5.10] - 2026-03-06

### Changed
- fix: correct diff CLI test assertions to use kebab-case jq keys
- feat: expose constants/ and device/ namespaces in SCI eval environment
- fix: correct 5 ref docs to match implementation
- feat: rewrite agent system — 12 agents across 4 groups with contracts, gates, and adversarial bug-finding
- fix: XSS escape all user data in Allure builds page, disable empty failed builds, fix GraalVM boxed-math warnings
- feat: add pixel-level screenshot diffing via pixelmatch + Playwright Canvas
- docs: collapse proxy config and env vars under <details>
- fix: implement SPEL_CHANNEL env var + sync README env var table with code
- release: update version files for v0.5.9, bump to next dev version


## [v0.5.9] - 2026-03-05

### Changed
- feat(cdp): add Microsoft Edge support to auto-connect discovery
- fix(ci): reduce gh-pages size from 12.7 GB to under 1 GB — fix GitHub Pages deployment
- release: update version files for v0.5.8, bump to next dev version


## [v0.5.8] - 2026-03-05

### Changed
- fix(cdp): handle Chrome 136+ --user-data-dir requirement and M144+ WebSocket-only mode
- release: update version files for v0.5.7, bump to next dev version


## [v0.5.7] - 2026-03-05

### Changed
- feat: delete chrome_cookies, remove state export, add --auto-connect
- release: update version files for v0.5.6, bump to next dev version


## [v0.5.6] - 2026-03-05

### Changed
- fix(release): fetch tags in release job, reorder Clojars deploy after GitHub release
- chore: trigger release v0.5.5
- Delete LESSONS.md
- fix: restore CI workflows and remove unused chrome-cookies require
- feat: persist launch flags per session — --cdp only needed once
- chore: remove .github directory (cove images, CI workflows)
- fix: rename --eval to eval-sci in CLI tests, add LESSONS.md and snapshot rules to AGENTS.md
- feat: CDP support, eval-js/eval-sci rename, snapshot event listeners & clickable detection
- fix(docs): update stale style tier counts in docstrings (12/24/36 → 16/31/44)
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- docs: update AGENTS.md and README for subagent architecture + --only flag
- feat(templates): add workflow prompts for visual and automation subagent groups
- feat(templates): add 5 new subagent templates (explorer, automator, interactive, presenter, visual-qa)
- feat(templates): add presenter reference docs extracted from visual-explainer
- refactor(templates): refine test agent templates with priority refs
- refactor(init-agents): add --only flag for selective scaffolding
- refactor(init-agents): add --only flag for selective scaffolding
- feat(templates): add visual QA reference guide
- feat(cli): add -- args separator with *command-line-args* binding
- feat(cli): add -- args separator with *command-line-args* binding
- refactor(init-agents): smart ref assignment data structure
- refactor(init-agents): deprecate --loop=vscode with error message
- feat(snapshot): add position properties to MINIMAL style tier
- fix: resolve strict mode violations in drag tests and boxed math warnings
- release: update version files for v0.5.3, bump to next dev version
- docs: collapse guided install section by default


## [v0.5.3] - 2026-03-05

### Changed
- docs: agentic guided install with env vars reference
- docs: remove test example from README, link to SKILL.md instead
- feat: add drag-and-drop support with options across library, SCI, and CLI layers
- fix: support --profile and --channel flags in --eval mode
- docs: add library-first development order rule to AGENTS.md
- chore: add verify.sh script and update verification docs
- test: add CLI bash and Clojure tests for styles, viewport, device, --browser
- fix: replace bc with awk in test assertions and skip headed tests without display
- fix: make stitch command browser-aware via SPEL_BROWSER env
- feat: add viewport, device tracking, and --browser to snapshot pipeline
- feat: computed styles, clipboard ops, snapshot diff — library + daemon + CLI + SCI
- Update opencode.json
- chore: remove artistry override, B3 uses direct ACP spawn
- chore: add ultrabrain→opus override for CoVe B2
- chore: override artistry category to zai-coding-plan/glm-5 for CoVe B3
- fix: move One-Shot Installation heading outside details (visible header, collapsed content)
- docs: wrap LLM install section in details collapse
- docs: add one-shot LLM installation section + surface corporate CA setup (fixes #47)
- docs(skill): add daemon hang/kill troubleshooting to COMMON_PROBLEMS.md
- docs: add release process to AGENTS.md — tag-only, never manual
- release: update version files for v0.5.2, bump to next dev version
- release: bump to next dev version 0.5.3


## [v0.5.2] - 2026-03-02

### Changed
- release: v0.5.2 (v0.5.1 tag immutable, re-releasing as 0.5.2)
- release: bump to next dev version 0.5.2
- release: v0.5.1 — eprintln helpers, versioned skill, profile docs, CI fix
- fix(ci): Allure workflow now runs on failed CI — shows failures in builds page (BLO-163)
- docs(skill): add daemon launch modes comparison (Mode 1/2/3) (BLO-162)
- docs(skill): add profile vs state comparison, Edge support, Quick Reference (BLO-162)
- refactor: replace (binding [*out* *err*]) with eprintln/eprint helpers (BLO-160)
- feat(skill): add skill-version field and agent validation (BLO-161)
- fix(docs): audit and correct comparison file — 6 sections had stale claims
- docs: rewrite POTENTIAL_TASKS.md — correct 13 features mislabeled as missing
- chore: remove TASK-003 (sequential refs) — rejected by design, hash refs preferred
- release: update version files for v0.5.0, bump to next dev version
- chore: remove 9 completed tasks from TASKS.md — verified via CLI
- fix(docs): mark link URLs and structured refs as done in 0.5.0 — verified via CLI


## [v0.5.2] - 2026-03-02

### Changed
- refactor: replace (binding [*out* *err*]) with eprintln/eprint helpers (BLO-160)
- feat(skill): add skill-version field and agent validation — agents detect stale skills (BLO-161)
- docs(skill): add profile vs state comparison, Edge/channel support, Chrome profile docs (BLO-162)
- docs(skill): add daemon launch modes comparison (Mode 1/2/3) (BLO-162)
- fix(ci): Allure workflow now runs on failed CI — failures visible in builds page (BLO-163)
- fix(docs): audit and correct comparison file — 6 sections had stale claims
- docs: rewrite POTENTIAL_TASKS.md — correct 13 features mislabeled as missing

## [v0.5.0] - 2026-03-01

### Changed
- release: v0.5.0 — extensions, download, flat snapshots, profile support, comparison update
- feat(cli): add --extension flag for loading Chrome extensions (fixes #41)
- fix(cli): detect URL at any position in open command args
- fix(ci): add concurrency group and retry logic to pr-cleanup workflow
- fix(graal): add type hints to Files/write call for native image compatibility
- feat: Edge/Chrome profile support with persistent context, cookie injection, and logging
- fix: increment injected counter and reorder copy-cookies-db! for fail-fast
- fix: support Edge and other Chromium browsers for cookie decryption
- chore: add CoVe report screenshot
- fix: update test assertion to use example.org
- fix: replace example.com with example.org
- chore: add CoVe report screenshot
- feat: add --flat option for snapshot command
- chore: add CoVe report screenshot
- fix(ui): correct mobile view for PRs (fixes #63)
- fix(ui): correct mobile view for PRs (fixes #63)
- feat: add download CLI command (fixes #40)
- fix: resolve GraalVM reflection and boxed math warnings in daemon.clj and sci_env.clj
- fix: video save-as - close page/context before saveAs (fixes #43)
- fix: resolve CI failures - unused vars lint, console get dispatch
- feat: update TASK-013 - preview structure, network/console get, page refs (AC-20..AC-25)
- feat: unified snapshot enrichment - URLs, refs map, network/console scoping
- fix(ci): only run Allure Report on successful CI builds
- docs(agents): daemon session isolation rules
- fix(sci_eval): propagate call_log/selector in SCI error responses
- feat(daemon): propagate Playwright error context in CLI output
- fix: wrap unbound page refs with with-testing-page in skill docs
- fix(templates): migrate all templates from old fixtures to with-testing-page/with-testing-api
- fix(screenshot): crop-to-content for content shorter than viewport (#52)
- fix(ci): merge release + deploy-clojars into single job


## [v0.5.0] - 2026-03-01

### Added
- feat(cli): add `--extension` flag for loading Chrome extensions (#41)
- feat: Edge/Chrome profile support with persistent context, cookie injection, and logging
- feat: add `--flat` option for snapshot command
- feat: add `download` CLI command (#40)
- feat: unified snapshot enrichment — URLs, refs map, network/console scoping
- feat: preview structure, network/console get, page refs
- feat(daemon): propagate Playwright error context in CLI output

### Fixed
- fix(cli): detect URL at any position in `open` command args
- fix(graal): add type hints to `Files/write` call for native image compatibility
- fix: increment injected counter and reorder `copy-cookies-db!` for fail-fast
- fix: support Edge and other Chromium browsers for cookie decryption
- fix(ui): correct mobile view for PRs (#63)
- fix: resolve GraalVM reflection and boxed math warnings in `daemon.clj` and `sci_env.clj`
- fix: video `save-as` — close page/context before `saveAs` (#43)
- fix: resolve CI failures — unused vars lint, console get dispatch
- fix(sci_eval): propagate `call_log`/`selector` in SCI error responses
- fix(templates): migrate all templates from old fixtures to `with-testing-page`/`with-testing-api`
- fix(screenshot): `crop-to-content` for content shorter than viewport (#52)
- fix(ci): merge release + deploy-clojars into single job
- fix(ci): add concurrency group and retry logic to pr-cleanup workflow
- fix(ci): only run Allure Report on successful CI builds

## [v0.4.2] - 2026-02-27

### Added
- feat: `spel open --viewport WxH` — set viewport dimensions during navigation in one step
- feat: `spel screenshot --crop-to-content` — crop full-page screenshots to actual content height

### Fixed
- fix: full-page screenshots capturing empty space below content due to Playwright's `max(viewport, content)` behavior (#33)

## [v0.4.1] - 2026-02-27

### Added
- feat: validate URLs on `spel open` — reject invalid domains with clear error message (closes #36)
- feat: smooth scroll + element scroll across library, SCI, CLI, and daemon
- test: add 18 snapshot ref stability tests covering determinism, structural independence, and disambiguation

### Changed
- Switch spel daemon to virtual threads (BLO-132)
- Make stealth mode automatic for all CLI and eval commands
- Show page URL, title, and description in snapshot and eval output
- Close `--all-sessions`, show ref table on not-found error
- Enforce `@` prefix for snapshot refs — bare refs no longer auto-detected
- Auto-persist sessions by default, fix session list socket detection
- Deterministic content-hash snapshot refs, `get-by-ref`, `snapshot --all`, codegen improvements

### Fixed
- fix: replace hardcoded snapshot refs with generic `<sel>` and `@ref` in CLI help text
- fix: skip daemon restart when already running in headed mode
- fix: search reliability — block detection, faster timeouts, diagnostics, `--debug` flag

## [v0.4.0] - 2026-02-25

### Changed
- release: bump version to 0.4.0
- fix: rename spel/eval-js → spel/evaluate in test-cli.sh bash test
- Rename SCI embedded API to mirror library function names exactly
- Fix console/page-error output showing empty text in --eval mode
- Fix Safari TransformStream shim to return wrapper objects, not native instances
- Update README subtitle: highlight E2E testing, normalize punctuation
- Add Google Search module with CLI, SCI eval, and lazy pagination
- fix: only set is_pr=true when PR number is available
- BLO-118: reposition README — Swiss Army Knife for agents
- fix: standardize margins and positions between list and grid views in allure-index
- fix: merge view toggle and theme toggle into single toolbar
- fix: remove emoji icon from no-results empty state
- fix: render stats above builds list on filtered views
- Upgrade Safari TransformStream shim detection to test real zip.js patterns
- Add stealth mode, state export (cookies + localStorage), and standardized CLI naming
- Style theme toggle to match view-toggle pill container
- Add step type badges ([API], [UI], [UI+API]) to Allure reports
- Remove unnecessary start!/stop! from --eval examples across skill docs
- Fix corporate proxy install example to set both env vars together
- Consolidate README Quick Start and document shared-vs-separate Playwright traces
- Improve HTTP exchange display and add Allure report-only verification shortcut
- Inject MutationObserver-based markdown renderer into Allure reports
- Unify step, ui-step, api-step macros with composable options
- Add opencode.json
- Replace rich HTML exchange panels with markdown attachments
- Equalize margins above and below filter pills in dashboard
- Replace Allure plugin with auto network capture in test fixtures
- Fix date-group headline font-size mismatch between list and grid views
- Add Allure plugin for full-height HTML attachments
- Fixes
- Fix Keychain args for macOS profile, improve HTML preview toggle
- Fixes
- Fixes
- Fixes
- fixes
- Fixes
- Fixes
- Fixes
- In progress
- In progress
- feat: add rich HTTP exchange reporting for both API and browser network responses
- fix: pr-cleanup marks cancelled on close, improve merged styling, fix kondo hook
- fix: keep stats visible on filter, add Cancelled to PR stats
- fix: add Cancelled pill to static HTML, improve mobile search layout
- feat: add cancelled build status support across backend and landing page
- chore: add .claude/ to .gitignore
- chore: remove .claude/ directory — not permitted in this repo
- refactor: merge api namespace into core — eliminate com.blockether.spel.api
- fix: escape quotes in Allure workflow Clojure code
- fix: deduplicate PR comment link, add update-pr-statuses! for accurate CI status
- fix: coerce max-n to long for GraalVM primitive math
- feat: make PR badge URL configurable via pr_url field in pr-builds.json
- fix: PR badge links to GitHub PR, force scrollbar to prevent tab layout shift
- fix: unify list/grid max-width to 64rem (was 56rem list, none grid)
- feat: add PR tabs, merge tracking, and Clojure-based PR management to landing page
- fix: show workflow run_number for PR cards and enable stats dashboard on landing page
- fix: guard pr/ checkout with grep to avoid false-positive ls-tree exit code
- feat: add stats dashboard and date labels to test reports landing page
- BLO-108: fix README - remove allure section, add features overview, fix broken refs
- test: rewrite video recording test with meaningful browser activity
- feat: add PR reports to builds list on landing page
- fix: video attachments not appearing in Allure reports
- fix: resolve session flag passing and macOS timeout in CLI tests
- fix: resolve 6 pre-existing CLI test failures in test-cli.sh
- fix: add driver/ensure-driver! before stitch in native CLI
- feat: host PR Allure reports on GitHub Pages at /pr/<number>/
- fix: resolve boxed math warning and viewport sizing in stitch
- refactor: rewrite stitch to use Playwright instead of AWT/ImageIO
- fix: resolve remaining test-cli.sh failures + add ImageIO to native-image config
- fix: align test-cli.sh assertions with actual CLI --json output format
- ci: bust stale Playwright browser cache (v2 key)
- ci: fix PLAYWRIGHT_BROWSERS_PATH - use GITHUB_ENV instead of literal tilde
- ci: remove continue-on-error from test-cli.sh step
- ci: fix PLAYWRIGHT_BROWSERS_PATH tilde expansion + add daemon log debug output
- ci: mark test-cli.sh as continue-on-error (requires network access)
- BLO-20: fix Allure PR comment - rename CT to clojure-test, add CLI test reference
- BLO-20: add missing CLI tests (stitch, annotate, tool --help) and AGENTS.md CLI test requirements
- ci: add test-cli.sh bash regression to CI workflow (Linux + macOS)
- AGENTS.md: add test count sanity check with bash regression details
- BLO-99: fix Windows CI (tmpdir), add stitch docs to SKILL template, update verification checklist
- BLO-99: add SCI env registration, Allure attachments, and CLI/SCI tests for stitch
- BLO-99: add spel stitch command for vertical image stitching
- BLO-97: add video+Allure integration tests and fix *video-path* binding
- AGENTS.md: validate-safe-graal + install-local before CLI integration tests
- fix: unmatched delimiter in cli_integration_test — missing quote after escaped-tmpdir
- AGENTS.md: reorder verification checklist per Opus review (format first, no redundancy, better secret scan)
- AGENTS.md: verification checklist — binary note, pre-push scan, CI green check
- BLO-96: Fix Windows SCI tests — use java.io.tmpdir and escape backslashes
- BLO-96: Suppress unused-import in clj-kondo config (fixes CI lint)
- AGENTS.md: add make test full suite to verification checklist
- AGENTS.md: expand verification checklist — explicit test-cli-clj and test-cli steps
- BLO-96: Fix CI lint warnings and Windows path escaping in tests
- Fix Safari trace viewer, trim AGENTS.md, restructure Makefile and README
- Add left padding to grid cards
- Drop square aspect-ratio on mobile grid cards
- Fix list view width, mobile toggle overlap
- Update SKILL docs and agent instructions for SCI file I/O
- Add spel/navigate alias, file I/O, and Base64 to SCI sandbox
- Fix Allure reporter test-count parity with Lazytest
- Builds list grid: square cards, 6 per row, wider container
- CT reporter parity, builds list redesign, lint cleanup
- Bump opencode
- release: update version files for v0.3.1, bump to next dev version


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

[Unreleased]: https://github.com/Blockether/spel/compare/v0.5.19...HEAD
[v0.5.0]: https://github.com/Blockether/spel/compare/v0.4.2...v0.5.0
[v0.4.2]: https://github.com/Blockether/spel/compare/v0.4.1...v0.4.2
[v0.4.1]: https://github.com/Blockether/spel/compare/v0.4.0...v0.4.1
[v0.4.0]: https://github.com/Blockether/spel/compare/v0.3.1...v0.4.0
[v0.3.1]: https://github.com/Blockether/spel/releases/tag/v0.3.1
[v0.3.0]: https://github.com/Blockether/spel/releases/tag/v0.3.0
[v0.2.0]: https://github.com/Blockether/spel/releases/tag/v0.2.0
[v0.1.0]: https://github.com/Blockether/spel/releases/tag/v0.1.0
[v0.0.2]: https://github.com/Blockether/spel/releases/tag/v0.0.2
[v0.0.1]: https://github.com/Blockether/spel/releases/tag/v0.0.1
[v0.5.2]: https://github.com/Blockether/spel/releases/tag/v0.5.2
[v0.5.3]: https://github.com/Blockether/spel/releases/tag/v0.5.3
[v0.5.6]: https://github.com/Blockether/spel/releases/tag/v0.5.6
[v0.5.7]: https://github.com/Blockether/spel/releases/tag/v0.5.7
[v0.5.8]: https://github.com/Blockether/spel/releases/tag/v0.5.8
[v0.5.9]: https://github.com/Blockether/spel/releases/tag/v0.5.9
[v0.5.10]: https://github.com/Blockether/spel/releases/tag/v0.5.10
[v0.5.11]: https://github.com/Blockether/spel/releases/tag/v0.5.11
[v0.5.12]: https://github.com/Blockether/spel/releases/tag/v0.5.12
[v0.5.13]: https://github.com/Blockether/spel/releases/tag/v0.5.13
[v0.5.14]: https://github.com/Blockether/spel/releases/tag/v0.5.14
[v0.5.15]: https://github.com/Blockether/spel/releases/tag/v0.5.15
[v0.5.16]: https://github.com/Blockether/spel/releases/tag/v0.5.16
[v0.5.17]: https://github.com/Blockether/spel/releases/tag/v0.5.17
[v0.5.18]: https://github.com/Blockether/spel/releases/tag/v0.5.18
[v0.5.19]: https://github.com/Blockether/spel/releases/tag/v0.5.19
