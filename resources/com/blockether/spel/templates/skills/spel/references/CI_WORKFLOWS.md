# CI and reports

**Use when:** Adding Spel tests or reports to a project's CI. Start from that project's workflow and runner, not Spel's own release pipeline.

## Minimal test job

1. Install the project's pinned JDK and Clojure CLI.
2. Install Playwright browsers using the project's dependency version. Linux may also need system libraries:

   ```bash
   clojure -M -e '(com.microsoft.playwright.CLI/main (into-array String ["install" "--with-deps"]))'
   ```

3. Run the project's test alias. `references/TESTING_CONVENTIONS.md` matches the selected scaffold flavour; Lazytest and Cognitect do not accept the same reporter flags.
4. Fail the job on test failures. Upload requested diagnostic artifacts even on failure; do not let a successful report upload replace the test verdict.

Cache dependencies and browser downloads by the relevant dependency lockfile or manifest. If setting `PLAYWRIGHT_BROWSERS_PATH`, expand the home directory explicitly; a literal `~` in workflow environment values is not expanded by the JVM.

## Optional reporting

Read `references/ALLURE_REPORTING.md` only when reports are required. Keep test execution separate from publication. Traces, HARs, screenshots and auth state may contain private data: inspect/redact them, restrict retention and access, and never publish credentials or raw storage state.

## Working on Spel itself

The repository's `AGENTS.md` owns verification and release rules. Inspect `.github/workflows/ci.yml`, `allure.yml` and `release.yml` at the current revision for jobs, versions, caches and artifact names. This generated skill deliberately does not duplicate that changing inventory or authorize a release.
