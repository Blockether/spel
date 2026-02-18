# Agent Templates

The E2E testing agents (planner, generator, healer) are scaffolded from templates in `resources/com/blockether/spel/templates/`. The scaffolded output lives in `.opencode/agents/`, `.opencode/prompts/`, and `.opencode/skills/`.

**Never edit the scaffolded files directly.** Always edit the templates and regenerate.

## Template Sources

| Template | Scaffolded To |
|----------|---------------|
| `resources/.../templates/agents/spel-test-planner.md` | `.opencode/agents/spel-test-planner.md` |
| `resources/.../templates/agents/spel-test-generator.md` | `.opencode/agents/spel-test-generator.md` |
| `resources/.../templates/agents/spel-test-healer.md` | `.opencode/agents/spel-test-healer.md` |
| `resources/.../templates/prompts/spel-test-workflow.md` | `.opencode/prompts/spel-test-workflow.md` |
| `resources/.../templates/skills/spel/SKILL.md` | `.opencode/skills/spel/SKILL.md` |
| `resources/.../templates/seed_test.clj.template` | `test-e2e/<ns>/e2e/seed_test.clj` |

## Linting

Use **clojure-lsp diagnostics** (not the `clj-kondo` CLI) for all lint checks. The LSP integrates clj-kondo under the hood and provides richer analysis (e.g. `clojure-lsp/unused-public-var`).

- Run `lsp_diagnostics` on changed files — never shell out to `clj-kondo` directly.
- This is a **library** — public vars flagged as unused are intentional API surface. Do **not** remove them; suppress with `#_:clj-kondo/ignore` or linter config if needed.
- Private vars / bindings flagged as unused should be evaluated case-by-case: remove if truly dead, suppress if kept for future use.

## Regenerating After Template Changes

After editing any template in `resources/com/blockether/spel/templates/`:

```bash
# 1. Rebuild the native binary (templates are baked into the JAR/binary)
make install-local

# 2. Regenerate agents with --force to overwrite existing files
make init-agents ARGS="--ns com.blockether.spel --force"
```

Or as a one-liner:

```bash
make install-local && make init-agents ARGS="--ns com.blockether.spel --force"
```

### What This Does

1. `make install-local` — builds the uberjar, compiles the native image, and copies `spel` to `~/.local/bin/`
2. `make init-agents` — runs `spel init-agents` which reads templates from the classpath and writes them to `.opencode/`

The `--force` flag overwrites existing agent files. Without it, existing files are skipped.

### Dry Run

To preview what would be generated without writing files:

```bash
make init-agents ARGS="--ns com.blockether.spel --dry-run"
```

### Other Targets

For Claude Code or VS Code users consuming the library:

```bash
# Claude Code
spel init-agents --ns my-app --loop=claude --force

# VS Code / Copilot
spel init-agents --ns my-app --loop=vscode --force
```
