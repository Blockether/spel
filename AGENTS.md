# Spel repository guidance

Spel is a Clojure Playwright library with SCI and CLI/daemon surfaces. Implement once at the lowest owning layer and verify at the layer that exposes the behavior.

## Browser automation

- Load the `spel` skill before browser work.
- Create a unique named session (`agent-<timestamp>`) for every command; never use the user's default session, and always close sessions you create.
- Inspect `snapshot -i` before clicking and use element refs. Capture screenshots for visual changes.

## Architecture and SCI

- Build bottom-up: library (`page.clj`, `input.clj`, `locator.clj`) → SCI (`sci_env.clj`) → daemon/CLI (`daemon.clj`, `cli.clj`). Upper layers must call lower layers, not reimplement them.
- SCI binding-map entries are named `defn`s, never anonymous functions. `sci_eval` returns `pr-str`; plain `evaluate` returns raw values.
- Playwright evaluation returns Java maps/lists, not Clojure maps/vectors. Do not remove an unused public var solely to satisfy lint; it may be API.

## Tests and generated files

- Test at the owning surface: SCI/daemon in `cli_integration_test.clj`, parsing in `cli_test.clj`, native commands in `test-cli.sh`, and other behavior in its matching `*_test.clj`. Assert browser/DOM state, not only absence of exceptions.
- Before finishing, run `make lint`, `make test`, and `make test-cli`.
- Edit agent templates only under `resources/com/blockether/spel/templates/`; never edit generated `.opencode` files. After upgrades, regenerate with `spel init-agents --force --no-tests`.

## Releases

- `resources/SPEL_VERSION` is the single version source, embedded in binaries and printed by `spel version`.
- Releases are tag-only: workflows build/upload artifacts. Do not hand-build or hand-upload release binaries.
- Tag the exact green commit whose `resources/SPEL_VERSION` matches (`v$(cat resources/SPEL_VERSION)`). The release workflow rejects a mismatch and checks released binaries' version. Any subsequent changelog or next-version commit is not the release commit.
