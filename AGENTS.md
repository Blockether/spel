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
- Final verification is `./verify.sh`.

## Generated files and release

- Edit agent templates only under `resources/com/blockether/spel/templates/`; never edit generated `.opencode` files directly.
- Version truth is `resources/SPEL_VERSION`.
- Releases are tag-only; workflows own versioning and artifacts.
