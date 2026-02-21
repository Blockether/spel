# Agent Instructions

## Browser Automation — USE `spel` SKILL, NOT `playwright`

The built-in oh-my-opencode `playwright` skill is **disabled** in this project (see `.opencode/oh-my-opencode.json`). For ALL browser automation, testing, screenshots, scraping, and browser interactions, use the **`spel` skill** instead.

**Why spel is superior for this project:**

- `spel` is this project's own Clojure wrapper for Playwright — it knows our API, patterns, and idioms natively
- It includes accessibility snapshots with numbered refs, a persistent browser daemon, `--eval` scripting via SCI, codegen recording, and Allure reporting integration
- The spel skill (`.opencode/skills/spel/SKILL.md`) contains the full API reference agents need — locators, assertions, page operations, network, frames, and the native CLI
- The built-in `playwright` skill is a generic MCP wrapper with no knowledge of our Clojure API

**When delegating browser-related tasks**, always pass `load_skills=["spel"]`:

```
task(category="...", load_skills=["spel"], prompt="...")
```

**Never use `load_skills=["playwright"]` or `load_skills=["dev-browser"]` in this project.** They are disabled / irrelevant.

---

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

- Run `lsp_diagnostics` on changed files — **never shell out to `clj-kondo` directly**. The `clj-kondo` CLI is not installed on this machine. Always use `lsp_diagnostics` instead.
- This is a **library** — public vars flagged as unused are intentional API surface. Do **not** remove them; suppress with `#_:clj-kondo/ignore` or linter config if needed.
- Private vars / bindings flagged as unused should be evaluated case-by-case: remove if truly dead, suppress if kept for future use.

## Regenerating After Template Changes

**Trigger: ANY of these files changed → MUST regenerate:**
- Any template in `resources/com/blockether/spel/templates/`
- `src/com/blockether/spel/sci_env.clj` — adding/removing namespaces or functions from the SCI eval environment means the SKILL docs are stale (they reference what's available in `--eval` mode)
- `src/com/blockether/spel/gen_docs.clj` — the auto-generated API tables in SKILL come from here

After editing any of the above:

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

## Versioning

The single source of truth for the project version is `resources/SPEL_VERSION`. **Never hardcode version strings anywhere else.**

### How It Works

| Context | How version is resolved |
|---------|------------------------|
| **Local dev** (`build.clj`) | No `VERSION` env → reads `resources/SPEL_VERSION` |
| **Local dev** (`spel version`) | `native.clj` reads `SPEL_VERSION` from classpath resource |
| **CI release** (`git tag v0.1.0`) | `VERSION=v0.1.0` env → `build.clj` strips `v` prefix → publishes `0.1.0` to Clojars |
| **Post-release bump** | Deploy workflow parses tag, increments patch, writes next dev version back to `SPEL_VERSION` |
| **`init-agents` output** | `init_agents.clj` reads `SPEL_VERSION` for the deps.edn snippet shown to users |

### Release Flow (what happens when you push a tag)

```
git tag v0.1.0 && git push --tags
```

1. Deploy workflow triggers on `v*` tag
2. `build.clj` uses `VERSION=v0.1.0` env → publishes `0.1.0` JAR to Clojars
3. Workflow generates changelog entry in `CHANGELOG.md`
4. Workflow bumps `resources/SPEL_VERSION` to `0.1.1` (next patch)
5. Workflow commits `README.md`, `CHANGELOG.md`, `resources/SPEL_VERSION` back to main

### Rules for Agents

- **Never hardcode versions** in `native.clj`, `build.clj`, `init_agents.clj`, or anywhere else — always read from `SPEL_VERSION`
- **Never manually edit `SPEL_VERSION`** unless intentionally setting a pre-release dev version
- **The file contains a bare semver string** (e.g. `0.1.0`), no `v` prefix, no newline padding
- When adding new code that needs the version, read it the same way: `(slurp (io/resource "SPEL_VERSION"))` + `str/trim`

---

## API Design Policy

**No backward compatibility.** This project is pre-1.0. When improving an API:

- **Break old callers freely** — do not add shims, fallbacks, or "if old format then..." branches.
- **Remove deprecated functions immediately** — no deprecation period, no `-legacy` suffixes.
- **Prefer clean APIs over migration paths** — if the new design is better, ship it and update all call sites.

This applies to library functions, SCI wrappers, CLI commands, and entry formats alike.

---

## Testing Policy (MANDATORY)

Every code change **MUST** include corresponding tests. No exceptions.

### Rules

1. **Every change needs tests** — new features, bug fixes, refactors. If you change behavior, prove it works with a test.
2. **SCI environment changes → test in CLI integration tests** — when modifying `src/com/blockether/spel/sci_env.clj` (adding/removing namespaces, functions, bindings), add tests in `test/com/blockether/spel/cli_integration_test.clj` under the `sci-eval-integration-test` describe block. These tests exercise the daemon's `sci_eval` handler with a real browser.
3. **Daemon handler changes → test in CLI integration tests** — when modifying `src/com/blockether/spel/daemon.clj` (adding/modifying command handlers), add integration tests in `cli_integration_test.clj`.
4. **CLI arg parsing changes → test in CLI unit tests** — when modifying `src/com/blockether/spel/cli.clj` (parse-args, flags), add tests in `test/com/blockether/spel/cli_test.clj`.
5. **Core library changes → test in the relevant `*_test.clj`** — each source namespace has a corresponding test namespace (e.g. `core.clj` → `core_test.clj`, `page.clj` → `page_test.clj`).
6. **Test style** — use `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`. Follow the existing patterns in each test file.

---

## Verification Checklist (MANDATORY before completing any change)

Every code change MUST pass this full checklist before it's considered done:

1. **All tests pass**
   ```bash
   clojure -M:test
   ```
   All test cases must report 0 failures. Pre-existing failures must be noted explicitly.

2. **Native CLI builds**
   ```bash
   make install-local
   ```
   Builds uberjar → native-image → copies to `~/.local/bin/spel`. Must exit 0.

3. **Native CLI smoke test**
   ```bash
   spel version
   spel --help
   ```
   The freshly built binary must respond correctly.

4. **Regenerate agents/skills/docs** (if templates or source changed)
   ```bash
   make init-agents ARGS="--ns com.blockether.spel --force"
   ```
   Scaffolded files in `.opencode/` must be regenerated from templates.

**Skipping any step = incomplete work.** If a step fails, fix it before moving on.

## Running Tests (Lazytest)

```bash
# Full suite
clojure -M:test

# Single namespace
clojure -M:test -n com.blockether.spel.core-test

# Single var (MUST be fully-qualified: namespace/var-name)
clojure -M:test -v com.blockether.spel.integration-test/proxy-integration-test

# With Allure report
clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

# Watch mode
clojure -M:test --watch
```

**IMPORTANT**: `-v`/`--var` requires fully-qualified symbols. Bare var names throw `IllegalArgumentException`.
