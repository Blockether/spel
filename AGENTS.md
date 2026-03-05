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

Daemon Session Isolation:
- ALWAYS use a named session when starting a daemon during development/testing — NEVER use the default "default" session
- Generate a unique session name at task start: `SESSION=agent-$(date +%s)`
- Use it on every spel command: `spel --session $SESSION open <url>`, `spel --session $SESSION click ...`
- Teardown at end (or on error): `spel --session $SESSION close` — kills ONLY your session
- NEVER run `spel close` without `--session` — it kills the default session which may belong to the user
- Verify isolation: `spel session list` shows active sessions by name

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

## Verification

All verification logic lives in `./verify.sh`. Run it instead of a manual checklist.

```bash
./verify.sh              # Full verification (default) — format, lint, graal, gen-docs, build, test, secrets
./verify.sh --allure     # Allure-only — format, lint, test-allure (for report rendering changes)
./verify.sh --quick      # Format + lint only (no build/test)
```

Each step writes logs to `.verification/<step>.log` and exit codes to `.verification/<step>.code`.
On failure the script stops, shows the last 20 lines of the failing step's log, and tells you where to look.

The script auto-detects regeneration triggers (templates, `sci_env.clj`, `gen_docs.clj`) and skips
`gen-docs` / `init-agents` when nothing relevant changed.

### Test count sanity check
`verify.sh --full` validates that the full test suite ran enough cases:
- Lazytest: **~1268 cases** (fails if <1000)
- CLI bash: **~179 assertions** (fails if <150)

After push: verify GitHub Actions CI is green before declaring done.

## Key References

| Resource | Location |
|---|---|
| API reference (agents) | `.opencode/skills/spel/SKILL.md` |
| SKILL source (hand-edited) | `resources/.../templates/skills/spel/SKILL.md` |
| Full API ref (auto-generated) | `resources/.../templates/skills/spel/refs/FULL_API.md` |
| Project docs | `README.md` |
| nREPL eval | `clj-nrepl-eval` (eval Clojure against running nREPL) |
| Paren repair | `clj-paren-repair <file>` |
