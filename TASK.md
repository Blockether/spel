# Task: Allure Verification — Clojure Namespace + merge-reports Integration

Read AGENTS.md in this repo before doing anything.

## Context

The GitHub CI workflow (`allure.yml`) runs 3 steps to produce the Allure report:

```
Step 1: Run lazytest suite
  clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
  env: LAZYTEST_ALLURE_GENERATE_REPORT=false

Step 2: Run clojure.test (CT) suite
  clojure -M:test-ct
  env: ALLURE_CLOJURE_TEST_ENABLED=true, ALLURE_CLOJURE_TEST_CLEAN=false, ALLURE_CLOJURE_TEST_REPORT=false

Step 3: Generate combined Allure HTML report
  clojure -M -e "(require '[com.blockether.spel.allure-reporter :as r])
                  (r/generate-html-report! \"allure-results\" \"allure-report\")"
```

The verification script must mirror this **EXACTLY** (1:1) — run both suites, generate combined report.
It should NOT just download CI artifacts. It should run tests locally the same way CI does.

## What to implement

### 1. New namespace: `src/com/blockether/spel/allure_verify.clj`

```clojure
(ns com.blockether.spel.allure-verify
  "Local Allure verification — mirrors the GitHub CI flow 1:1:
   1. Run lazytest suite (clojure -M:test with allure reporter)
   2. Run CT suite (clojure -M:test-ct with Allure CT reporter)
   3. Generate combined HTML report
   4. Parse results, take screenshots, generate PDF."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockether.spel.allure-reporter :as reporter]
            [com.cnuernber.charred :as charred]))
```

Key functions:

**`(run-lazytest! [opts])`**
- Mirrors CI "Run lazytest suite" step
- Runs in subprocess: `clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure`
- Sets env: `LAZYTEST_ALLURE_GENERATE_REPORT=false`
- opts: `:results-dir` (default: `"allure-results"`), `:clean` (default: true, cleans results-dir first)
- Returns `{:exit 0, :out "...", :err "..."}`

**`(run-ct! [opts])`**
- Mirrors CI "Run clojure.test suite" step
- Runs in subprocess: `clojure -M:test-ct`
- Sets env: `ALLURE_CLOJURE_TEST_ENABLED=true`, `ALLURE_CLOJURE_TEST_CLEAN=false`, `ALLURE_CLOJURE_TEST_REPORT=false`
- opts: `:results-dir` (default: `"allure-results"`)
- Returns `{:exit 0, :out "...", :err "..."}`

**`(parse-results [results-dir])`**
- Parses `allure-results/*.json` (raw runner output, `-result.json` suffix)
- OR `data/test-results/*.json` (downloaded CI artifact, UUID-named files)
- Auto-detects format by checking file naming
- Groups by `framework` label: `"clojure.test"` → ct-suites, others → lazytest-suites
- Returns: `{:ct-suites {suite [tests]}, :lazytest-suites {suite [tests]}, :total N, :passed N, :failed N, :ct-total N, :lt-total N}`
- Each test: `{:name str, :status str, :feature str, :suite str}`

**`(generate-html-pages! [results-map out-dir])`**
- Generates 2 HTML files in out-dir:
  - `verify-ct.html` — CT suites (all suites, all tests visible)
  - `verify-lazytest.html` — lazytest suites (first 5 suites, 20 tests each, note total)
- Returns `[ct-html-path lazytest-html-path]`

**`(verify! [opts])`**
Main entry point — mirrors the full CI flow:
```clojure
{:keys [results-dir report-dir out-dir skip-run? pr-number repo post-comment? generate-pdf?]
 :or   {results-dir  "allure-results"
        report-dir   "allure-report"
        out-dir      "/tmp/allure-verify"
        skip-run?    false    ; set true to skip running tests (just verify existing results)
        post-comment? false
        generate-pdf? true}}
```

Steps:
1. If `(not skip-run?)`:
   a. Clean `results-dir`
   b. `(run-lazytest! {:results-dir results-dir :clean true})`
   c. `(run-ct! {:results-dir results-dir})`
   d. `(reporter/generate-html-report! results-dir report-dir)` — mirrors CI "Generate combined" step
2. `(parse-results results-dir)`
3. Warn (but don't fail) if `:ct-total 0` or `:lt-total 0`
4. `(generate-html-pages! results-map out-dir)`
5. Launch headless browser, screenshot each HTML page, close browser
6. If `generate-pdf?`, run `wkhtmltopdf` via ProcessBuilder
7. If `post-comment?` and `pr-number`, post PR comment via `gh pr comment`
8. Return summary: `{:total :passed :failed :ct-total :lt-total :screenshots :pdf :results-dir}`

### 2. New namespace: `src/com/blockether/spel/allure_verify_cli.clj`

CLI entry point for two new subcommands:

**`spel verify-pr <PR_NUMBER> [--repo Blockether/spel] [--skip-run] [--no-pdf] [--no-comment]`**
- Downloads artifact `allure-report-pr-{N}` via `gh run download`
- Calls `(verify! {:results-dir downloaded-artifact-data-test-results-path :skip-run? true ...})`
- `--skip-run` is default here (artifact already has results; no need to re-run tests)

**`spel verify-results [--results-dir allure-results] [--pr N] [--skip-run] [--no-pdf] [--no-comment]`**
- Default: runs full CI flow locally (lazytest + CT + report), then verifies
- With `--skip-run`: only verify existing results (no test execution)

Use `ProcessBuilder` for all subprocess calls.

### 3. Integrate into `allure_reporter.clj` — `merge-results!`

After merge, if `:verify true` is passed, call `verify!` with `skip-run? true` on the merged output:

```clojure
(when verify
  (require '[com.blockether.spel.allure-verify :as av])
  ((resolve 'av/verify!) (merge {:results-dir output-dir :skip-run? true} verify-opts)))
```

Add `--verify` to `merge-reports-help` and `parse-merge-reports-args` in `native.clj`.

### 4. Wire subcommands in `native.clj`

In the main dispatch cond, add:
- `"verify-pr"` → calls `allure-verify-cli/-main`
- `"verify-results"` → calls `allure-verify-cli/-main`

Add 2 lines to the help text in `native.clj` under Utility commands.

### 5. Tests: `test/com/blockether/spel/allure_verify_test.clj`

Create test fixtures in `test/resources/allure-verify/`:
- `ct/` — 3 sample `-result.json` files with `framework: clojure.test` label
- `lazytest/` — 3 sample `-result.json` files with `framework: lazytest` label
- `artifact/` — 3 UUID-named JSON files (CI artifact format)

Tests:
- `parse-results` with `ct/` returns ct-total=3, lt-total=0
- `parse-results` with `lazytest/` returns ct-total=0, lt-total=3
- `parse-results` with mixed returns correct counts
- `parse-results` with `artifact/` (UUID format) works correctly
- `generate-html-pages!` creates expected files
- `verify!` with `skip-run? true` returns correct summary map (no subprocess calls)

## Important constraints

- Use `com.cnuernber.charred` for JSON (already in deps.edn)
- Use `ProcessBuilder` for subprocess calls (not `clojure.java.shell`)
- Screenshots use `com.blockether.spel.core` functions
- Do NOT break existing `merge-results!` behavior (`:verify` defaults to false)
- Run `make format` and `make lint` and `make test-cli-clj` before committing
- Commit: `BLO-98: add allure-verify Clojure namespace + verify-pr/verify-results subcommands + merge-reports integration`
- Push to `feature/allure-verify-flow`
- Delete `scripts/verify-allure-report.sh` (replaced by Clojure)
