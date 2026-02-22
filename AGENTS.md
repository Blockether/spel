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

1. `make test-cli-clj` — Clojure tests (lazytest): 0 failures
2. `make test-cli` — CLI bash regression: 0 failures ⚠️ needs `./target/spel` — run `make install-local` first if binary absent
3. `make test` — full suite (both): 0 failures
4. `make format` — auto-format source
5. `make lint` — clojure-lsp diagnostics clean
6. `make validate-safe-graal` — no reflection/boxed-math warnings
7. `make gen-docs` — regenerate SKILL.md
8. `make install-local` — exit 0
9. `spel version && spel --help` — responds correctly
10. `make init-agents ARGS="--ns com.blockether.spel --force"` — if templates/source changed
11. Pre-push secret scan: `git diff HEAD | grep -iE "(sk_|lin_api_|password\s*=)"` — must return nothing
12. After push: verify GitHub Actions CI is green before declaring done

## Regeneration Triggers

ANY of these changed → MUST run steps 7-10:
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
