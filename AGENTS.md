# Agent Instructions

## Critical Rules

Browser Automation:
- ALWAYS use `load_skills=["spel"]` for browser tasks. Load skill first: `skill(name="spel")`
- NEVER use `load_skills=["playwright"]` or `load_skills=["dev-browser"]` — disabled in this project

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

## Testing

Every code change MUST include tests. Use `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`.

| Source changed | Test location |
|---|---|
| `sci_env.clj` | `cli_integration_test.clj` → `sci-eval-integration-test` block |
| `daemon.clj` | `cli_integration_test.clj` |
| `cli.clj` | `cli_test.clj` |
| Everything else | Corresponding `*_test.clj` (e.g. `page.clj` → `page_test.clj`) |

## Commands (via Makefile)

```bash
make test                    # ALL tests: Clojure (lazytest) + CLI bash regression
make test-cli                # CLI bash regression tests only
make test-cli-clj            # CLI Clojure integration tests only
make format                  # auto-format all source files
make lint                    # clojure-lsp diagnostics --raw
make validate-safe-graal     # check for reflection/boxed-math warnings
make gen-docs                # regenerate SKILL.md from template (run BEFORE install-local)
make install-local           # uberjar → native-image → ~/.local/bin/spel
make init-agents ARGS="--ns com.blockether.spel --force"  # regenerate agent scaffolding
```

Single test namespace / var:
```bash
clojure -M:test -n <ns>                  # e.g. com.blockether.spel.core-test
clojure -M:test -v <ns>/<var>            # MUST be fully-qualified
```

## Verification Checklist
Run these in order. On ANY failure → fix → restart from step 1.

1. `make format` — auto-format source (must run BEFORE tests — format changes must be tested)
2. `make lint` — clojure-lsp diagnostics clean
3. `make validate-safe-graal` — no reflection/boxed-math warnings (must run before native compile)
4. `make gen-docs` — regenerate SKILL.md
5. `make install-local` — builds `./target/spel` → `~/.local/bin/spel`: exit 0
6. `spel version && spel --help` — responds correctly
7. `make test-cli-clj` — Clojure integration tests (lazytest): 0 failures
8. `make test` — full suite against fresh binary: 0 failures (Clojure + CLI bash)
9. `make init-agents ARGS="--ns com.blockether.spel --force"` — if templates/source changed
10. `git diff --check` — no conflict markers, no trailing whitespace
11. Pre-push: `git diff origin/main..HEAD | grep -iE "(sk_|lin_api_|nvapi-|AIzaSy|ghp_|password\s*=\s*\S{8})"` — must return nothing
12. After push: verify GitHub Actions CI is green before declaring done

## Allure Report Verification (MANDATORY for PRs)

After CI passes on a PR — verify the Allure report visually before merging:

```bash
./scripts/verify-allure-report.sh <PR_NUMBER>
```

What the script does:
1. Downloads `allure-report-pr-{N}` artifact from the latest successful Allure CI run
2. Serves it locally on port 8299
3. Takes 3 screenshots (overview, results, test details) using spel
4. Generates a PDF report with embedded screenshots
5. Posts a verification comment on the PR with test counts

Output: `/tmp/allure-pr{N}-report.pdf` + PR comment

**When to run:** After every PR CI completes. Before merging.
**CI green ≠ report is correct** — always verify visually.

## Regeneration Triggers

ANY of these changed → MUST run steps 4-9:
- Templates in `resources/com/blockether/spel/templates/`
- `src/com/blockether/spel/sci_env.clj`
- `src/com/blockether/spel/gen_docs.clj`

## Key References

| Resource | Location |
|---|---|
| API reference (agents) | `.opencode/skills/spel/SKILL.md` |
| SKILL template (edit this) | `resources/.../templates/skills/spel/SKILL.md.template` |
| Project docs | `README.md` |
| nREPL eval | `clj-nrepl-eval` (eval Clojure against running nREPL) |
| Paren repair | `clj-paren-repair <file>` |
