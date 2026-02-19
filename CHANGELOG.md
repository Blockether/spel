# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/Blockether/spel/compare/v0.2.0...HEAD
[v0.0.1]: https://github.com/Blockether/spel/releases/tag/v0.0.1
[v0.0.2]: https://github.com/Blockether/spel/releases/tag/v0.0.2
[v0.1.0]: https://github.com/Blockether/spel/releases/tag/v0.1.0
[v0.2.0]: https://github.com/Blockether/spel/releases/tag/v0.2.0
