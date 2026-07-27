# Spel repository guidance

Spel is a Clojure Playwright library plus SCI and CLI/daemon surfaces. Keep this file to non-obvious contracts; inspect source and tests for implementation detail.

## Vis-native execution

- Use Vis native tools: `grep` first when location is unknown, then `struct_index`/`cat`; edit supported Clojure with `struct_patch`; use `lint_code`, `format_code`, and `run_tests` rather than shell replicas.
- A managed Clojure REPL is guaranteed. Read its live session state, reuse/start it with `repl`, reproduce with `repl_eval`, reload edited namespaces, and execute the changed path. Use the smallest relevant test when REPL proof is insufficient.
- Preserve unrelated work and stop only resources you started.

## Browser automation

- Load the `spel` skill before browser work. Never use the disabled Playwright/dev-browser skills.
- Always use a unique named session (`agent-<timestamp>`), pass it on every command, and close that exact session. Never operate on the user's default session.
- Read `snapshot -i` before clicking; click element refs, not guessed selectors. Use full snapshots only when structure/text is needed.
- Visual changes require screenshots and inspection.

## Architecture contract

Implement features in this order:

1. Library (`page.clj`, `input.clj`, `locator.clj`) — source of truth around Playwright Java.
2. SCI (`sci_env.clj`) — session-atom convenience that reuses library functions.
3. CLI/daemon (`daemon.clj`, `cli.clj`) — JSON/Unix-socket surface that reuses the library.

Never reimplement lower-layer behavior in SCI or CLI.

## Clojure and SCI

- SCI bindings must be named `defn`s with docstrings. Never place anonymous functions directly in binding maps; docs generation cannot expose them.
- If parentheses break, run `clj-paren-repair`; do not repair them by hand.
- Unused public vars may be intentional API; do not remove them merely for diagnostics.
- `sci_eval` returns `pr-str` output, so string results include quotes. Plain `evaluate` returns raw values.
- Playwright evaluation returns Java collections (`java.util.Map`/`java.util.List`), not Clojure maps/vectors.

## Testing

- Every behavior change gets a test at its owning layer. SCI/daemon changes belong in `cli_integration_test.clj`; CLI parsing in `cli_test.clj`; native CLI commands in `test-cli.sh`; other code in matching `*_test.clj`.
- Verify observable browser/DOM state, not merely “did not throw.” Add stable routes to `test_server.clj` when needed.
- Mobile touch fixtures must not shift layout between touch and synthesized click; status/log elements need fixed geometry.
- Capture long test output to a file before inspecting it; do not pipe away failure details.
- Prefer the smallest namespace/var test during development. Final project verification is `./verify.sh` (`--quick` only for format/lint).

## Commands and generated files

- Prefer Make targets: `make format`, `make lint`, `make test`, `make gen-docs`, `make validate-safe-graal`.
- Edit agent templates only under `resources/com/blockether/spel/templates/`, then regenerate. Never edit generated `.opencode/agents`, `.opencode/skills`, or `.opencode/prompts` directly.
- Version truth is `resources/SPEL_VERSION`; read it as a resource and trim it. Do not hardcode versions.
- New tool commands require `--help`; new CLI/daemon actions require matching CLI tests.

## Release

Release is tag-only. Never manually create GitHub releases, upload binaries, edit release changelogs, or bump `SPEL_VERSION`; the workflow owns those steps.
