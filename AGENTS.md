# Spel repository guidance

Spel is a Clojure Playwright library with SCI and CLI/daemon surfaces.

## Browser automation

- Load the `spel` skill before browser work.
- Use a unique named session (`agent-<timestamp>`) on every command; never touch the user's default session; close your session.
- Read `snapshot -i` before clicking and use element refs. Visual changes require screenshots.

## Architecture

Implement behavior once, in order: library (`page.clj`, `input.clj`, `locator.clj`), SCI (`sci_env.clj`), then CLI/daemon (`daemon.clj`, `cli.clj`). Upper layers reuse lower layers.

## Clojure and SCI

- SCI bindings are named `defn`s, never anonymous functions in binding maps.
- `sci_eval` returns `pr-str`; plain `evaluate` returns raw values.
- Playwright evaluation returns Java maps/lists, not Clojure maps/vectors.
- Unused public vars may be intentional API.

## Testing

- Test behavior at its owning layer: SCI/daemon in `cli_integration_test.clj`, CLI parsing in `cli_test.clj`, native commands in `test-cli.sh`, other code in matching `*_test.clj`.
- Verify observable browser/DOM state, not only absence of exceptions.
- Final verification is `make lint`, `make test`, and `make test-cli`.

## Generated files

- Edit agent templates only under `resources/com/blockether/spel/templates/`; never edit generated `.opencode` files directly.
- Regenerate the agent/skill tree with `spel init-agents --force --no-tests` after every upgrade; those docs ship inside the binary (README documents the user-facing flags).

## Releasing

Release facts live here and nowhere else.

- `resources/SPEL_VERSION` is the single source of truth: it is baked into every binary and printed by `spel version`.
- Releases are tag-only; the workflows own versioning and artifacts. Never hand-build or hand-upload binaries.
- **A tag must match the `resources/SPEL_VERSION` of the commit it points at**, because the Release workflow reuses the binaries CI built for that exact commit. Tagging an older commit is what shipped `v0.9.13` binaries that printed `0.9.12`.
- The Release workflow now refuses to publish when the tag and `resources/SPEL_VERSION` disagree, and re-checks that the built binaries actually print the tag version.
- After publishing it cuts the CHANGELOG, updates the README version, and bumps `resources/SPEL_VERSION` to the next patch on `main` — so always tag *after* that bump commit, never a commit before it.

```bash
# 1. main must be green for the commit you tag (Release downloads that commit's CI artifacts)
cat resources/SPEL_VERSION          # e.g. 0.9.14 — exactly what the binaries will report

# 2. tag that same commit with the matching version
git tag -a v0.9.14 -m v0.9.14
git push origin v0.9.14
```
