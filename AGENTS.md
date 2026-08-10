# Spel repository guidance

Spel is a Clojure Playwright library with SCI and CLI/daemon surfaces. Implement once at the lowest owning layer and verify at the layer that exposes the behavior.

## Browser automation

- Load the `spel` skill before browser work.
- Create a unique named session (`agent-<timestamp>`) for every command; never use the user's default session, and always close sessions you create.
- Inspect `snapshot -i -c` before clicking and act through its `@refs`. Every row carries its box (`[pos:X,Y W×H]`), so state geometry as those figures instead of describing a picture; `get box <sel>` answers a single element.
- Prove a visual change with `screenshot -a <path>` (or `overview`): it outlines each element, stamps it with a bare mark number placed clear of the content, and prints the `#N  @ref  role  name` reference table — that table is the legend and belongs in the answer. Scope a busy page first (`-s <sel>`, `-d N`, `--max-output N`) — an unscoped article annotates thousands of refs.
- The agent-facing contract for the two rules above lives in `templates/agents/spel.md` and `templates/skills/spel/SKILL.md`; keep them and this section in step.

## Architecture and SCI

- Build bottom-up: library (`page.clj`, `input.clj`, `locator.clj`) → SCI (`sci_env.clj`) → daemon/CLI (`daemon.clj`, `cli.clj`). Upper layers must call lower layers, not reimplement them.
- SCI binding-map entries are named `defn`s, never anonymous functions. `sci_eval` returns `pr-str`; plain `evaluate` returns raw values.
- Playwright evaluation returns Java maps/lists, not Clojure maps/vectors. Do not remove an unused public var solely to satisfy lint; it may be API.

## Fixing a reported bug: reproduce, RED, then GREEN

- Reproduce first, from the report's own steps, before touching the implementation. If it does not reproduce, that IS the finding: narrow or refute the report instead of fixing something adjacent.
- Reproduce on the surface the report used. A JVM-green reproduction proves nothing about a bug reported against the native `spel` binary (URL protocols, reflection, resources are native-only failures) — rebuild and drive the binary, or `./test-cli.sh`, before believing it.
- Turn the reproduction into a test in the suite and watch it **fail against the unfixed code** (RED), for the reported reason — not for a typo, a missing require, or a different error. A regression test nobody saw red proves nothing.
- Then apply the fix and rerun the same test unchanged (GREEN). In a managed REPL: load the pre-fix namespace, run the test, keep the failure text, reload the fixed namespace, rerun. Report both. Narrow with `clojure -M:test -n com.blockether.spel.my-test` or `--var com.blockether.spel.my-test/my-test`.
- Every regression test names its issue in a comment **on the test** — `;; Regression, issue #N: <what used to happen>` directly above the `defdescribe`/`it`/`deftest` (or a section banner carrying `(issue #N)`). The comment describes the wrong behavior, not what the code now does; it is the only link back to the report after the branch is merged.
- The fix and its test ship in the same commit. A fix without a red-then-green test is unfinished and stays uncommitted.

## Tests and generated files

- Test at the owning surface: SCI/daemon in `cli_integration_test.clj`, parsing in `cli_test.clj`, native commands in `test-cli.sh`, and other behavior in its matching `*_test.clj`. Assert browser/DOM state, not only absence of exceptions.
- Before finishing, run `make lint`, `make test`, and `make test-cli`.
- Edit agent templates only under `resources/com/blockether/spel/templates/`; never edit generated `.opencode` files. After upgrades, regenerate with `spel init-agents --force --no-tests`.

## Releases

- `resources/SPEL_VERSION` is the single version source, embedded in binaries and printed by `spel version`.
- Releases are tag-only: workflows build/upload artifacts. Do not hand-build or hand-upload release binaries.
- Tag the exact green commit whose `resources/SPEL_VERSION` matches (`v$(cat resources/SPEL_VERSION)`). The release workflow rejects a mismatch and checks released binaries' version. Any subsequent changelog or next-version commit is not the release commit.
