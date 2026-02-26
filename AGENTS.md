# Agent Instructions

## Critical Rules

Browser Automation:
- ALWAYS use `load_skills=["spel"]` for browser tasks. Load skill first: `skill(name="spel")`
- NEVER use `load_skills=["playwright"]` or `load_skills=["dev-browser"]` — disabled in this project

SCI Bindings:
- NEVER use inline `(fn ...)` lambdas for SCI-exposed functions — ALWAYS use `defn` with a docstring
- Every SCI user-facing function MUST be a named `defn` (e.g. `sci-thread-sleep`, `sci-viewport-size`)
- Lambdas break `gen-docs` introspection, hide functions from FULL_API.md, and make debugging impossible
- This applies to: `:bindings` map values, `make-ns-map` entries, `core/` namespace stubs

Paren Repair:
- NEVER fix unbalanced parens/brackets by hand — always: `clj-paren-repair path/to/file.clj`

Linting:
- ALWAYS use `lsp_diagnostics` on changed files — `clj-kondo` CLI is NOT installed
- This is a library — public vars flagged unused are intentional API surface, do NOT remove

Templates:
- NEVER edit scaffolded files in `.opencode/agents/`, `.opencode/skills/`, `.opencode/prompts/`
- ALWAYS edit source templates in `resources/com/blockether/spel/templates/` and regenerate

Versioning:
- Single source of truth: `resources/SPEL_VERSION` (bare semver, no `v` prefix)
- NEVER hardcode versions — read via `(slurp (io/resource "SPEL_VERSION"))` + `str/trim`

API Policy:
- Pre-1.0: break old callers freely, no shims, no deprecation periods


Screenshots:
- ALWAYS show screenshots to the user when making visual/UI changes
- After any change to HTML, CSS, or templates: take a screenshot with spel and display it
- Never declare a visual change done without showing proof

## Testing

Every code change MUST include tests. Use `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`.

| Source changed | Test location |
|---|---|
| `sci_env.clj` | `cli_integration_test.clj` → `sci-eval-integration-test` block |
| `daemon.clj` | `cli_integration_test.clj` |
| `cli.clj` | `cli_test.clj` |
| `native.clj` (new CLI command) | `test-cli.sh` — add section with `assert_jq` / `assert_contains` assertions |
| `native.clj` (tool command, e.g. stitch/codegen) | `test-cli.sh` — at minimum `--help` smoke test |
| Everything else | Corresponding `*_test.clj` (e.g. `page.clj` → `page_test.clj`) |
### CLI bash regression (`test-cli.sh`)
When adding a new CLI command or daemon action:
- Add a new `section "Name (N)"` block in `test-cli.sh` before the SUMMARY section
- Use `assert_jq`, `assert_jq_eq`, `assert_jq_contains` for `--json` output
- Use `assert_contains` for plain text output (e.g. `--help`)
- Register temp files in `TEMP_FILES+=("path")` for cleanup
- Quote the binary: always `"$SPEL"`, never `$SPEL`
- Tool commands (stitch, codegen, init-agents, ci-assemble, merge-reports, show-trace) MUST have at least a `--help` assertion

## Commands (via Makefile)

```bash
make test                    # ALL tests: Clojure (lazytest) + CLI bash regression
make test-cli                # CLI bash regression tests only
make test-cli-clj            # CLI Clojure integration tests only
make format                  # auto-format all source files
make lint                    # clojure-lsp diagnostics --raw
make validate-safe-graal     # check for reflection/boxed-math warnings
make gen-docs                # regenerate refs/FULL_API.md from source (run BEFORE install-local)
make install-local           # uberjar → native-image → ~/.local/bin/spel
make init-agents ARGS="--ns com.blockether.spel --force"  # regenerate agent scaffolding
```

Single test namespace / var:
```bash
clojure -M:test -n <ns>                  # e.g. com.blockether.spel.core-test
clojure -M:test -v <ns>/<var>            # MUST be fully-qualified
```

## Verification Checklist
Run these in order. On ANY failure -> fix -> restart from step 1.

### Shortcut: Allure report-only changes
When changes ONLY affect Allure report rendering (e.g. `inject-markdown-renderer!`, `inject-video-modal!`,
CSS/JS injection in `allure_reporter.clj`) and do NOT touch library API, CLI, or SCI env:

1. `make format` — auto-format
2. `make lint` — diagnostics clean
3. `make test-allure` — runs tests + generates report in one step. Confirms tests pass AND report generates.

Skip `validate-safe-graal`, `install-local`, `gen-docs`, `test-cli` — those are irrelevant for report-only changes.

### Full checklist (API, CLI, or SCI changes)

1. `make format` — auto-format source (must run BEFORE tests — format changes must be tested)
2. `make lint` — clojure-lsp diagnostics clean
3. `make validate-safe-graal` — no reflection/boxed-math warnings (must run before native compile)
4. `make gen-docs` — regenerate refs/FULL_API.md
5. `make install-local` — builds `./target/spel` -> `~/.local/bin/spel`: exit 0
6. `spel version && spel --help` — responds correctly
7. `make test` — full suite: 0 failures. This runs TWO things:
   - `clojure -M:test` — Lazytest: **~1268 test cases** (if significantly fewer, you ran a subset — investigate)
   - `./test-cli.sh` — CLI bash regression: **~179 assertions** across 24 sections (requires binary from step 5)
   - Both summary lines MUST appear — report exact numbers from `Ran N test cases` and `Total: N`
8. `make init-agents ARGS="--ns com.blockether.spel --force"` — if templates/source changed
9. `git diff --check` — no conflict markers, no trailing whitespace
10. Pre-push: `git diff origin/main..HEAD | grep -iE "(sk_|lin_api_|nvapi-|AIzaSy|ghp_|password\s*=\s*\S{8})"` — must return nothing
11. After push: verify GitHub Actions CI is green before declaring done

### Test count sanity check
The full `make test` runs **two test suites** (as of 2026-02-23):
- `clojure -M:test` — **~1268 lazytest cases**
- `./test-cli.sh` — **~179 bash assertions** (requires `./target/spel` binary)

CI also runs a separate clojure.test suite (23 tests, 63 assertions) via the Allure Report workflow.
CI runs `test-cli.sh` on Linux and macOS after native image build (not on Windows — bash script).

If you see significantly fewer lazytest cases (e.g. <1000), something is wrong:
- You may be running a single namespace (`-n`) instead of the full suite
- A test file may have a compile error causing it to be silently skipped
- Always report the exact `Ran N test cases` line from test output

## Regeneration Triggers

ANY of these changed → MUST run steps 4-8:
- Templates in `resources/com/blockether/spel/templates/`
- `src/com/blockether/spel/sci_env.clj`
- `src/com/blockether/spel/gen_docs.clj`

## Key References

| Resource | Location |
|---|---|
| API reference (agents) | `.opencode/skills/spel/SKILL.md` |
| SKILL source (hand-edited) | `resources/.../templates/skills/spel/SKILL.md` |
| Full API ref (auto-generated) | `resources/.../templates/skills/spel/refs/FULL_API.md` |
| Project docs | `README.md` |
| nREPL eval | `clj-nrepl-eval` (eval Clojure against running nREPL) |
| Paren repair | `clj-paren-repair <file>` |
