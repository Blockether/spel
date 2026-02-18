# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/Blockether/spel/compare/v0.0.1...HEAD
[v0.0.1]: https://github.com/Blockether/spel/releases/tag/v0.0.1
