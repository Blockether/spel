# Spel repository guidance

Spel is a Clojure Playwright library with SCI and CLI/daemon surfaces. Implement a behavior once, at the lowest layer that owns it. Verify it at the layer that exposes it.

## Browser automation

- Load the `spel` skill before browser work.
- Run a whole task in ONE unique named session (`agent-<timestamp>`), with the same `--session` on every command. Never use the user's default session. Close your session when the task ends.
- A session keeps browser state. A new session for each command relaunches the browser and loses the page.
- Inspect `snapshot -i -c` before you click, and act through its `@refs`. Each row has its box (`[pos:X,Y W×H]`); give geometry as those numbers, not as a picture description. `get box <sel>` gives one element's box.
- Prove a visual change with a full-page `screenshot -a <path>` (or `overview`). For responsive or sticky viewport layout, use `screenshot -a --viewport <path>`.
- Both modes outline actionable elements and put bare mark numbers in matching colours clear of the content. They print the `#N  @ref  role  name` legend for the answer.
- Prose (paragraphs, spans, list items) is not drawn. Read it from `snapshot`, or pass `--text` when the picture must show it.
- Scope a busy page first (`-s <sel>`, `-d N`, `--max-output N`). An unscoped article annotates hundreds of refs.
- `templates/agents/spel.md` and `templates/skills/spel/SKILL.md` hold the agent contract for the snapshot and screenshot rules. Keep them and this section in step.

## Architecture and SCI

- Build from the bottom up: library (`page.clj`, `input.clj`, `locator.clj`) → SCI (`sci_env.clj`) → daemon/CLI (`daemon.clj`, `cli.clj`). Upper layers call lower layers and do not reimplement them.
- SCI binding-map entries are named `defn`s, never anonymous functions. `sci_eval` returns `pr-str`; plain `evaluate` returns raw values.
- Playwright evaluation returns Java maps and lists, not Clojure maps and vectors.
- Do not remove an unused public var only to satisfy lint; it can be API.

## Fixing a reported bug: reproduce, RED, then GREEN

- Reproduce first, from the report's steps, before you change the implementation. If it does not reproduce, that IS the finding: narrow or refute the report, and do not fix something adjacent.
- Reproduce on the report's surface. A JVM-green reproduction proves nothing about a bug in the native `spel` binary: URL protocols, reflection and resources fail only there. Rebuild and drive the binary, or run `./test-cli.sh`, before you believe it.
- Make the reproduction a suite test. See it **fail against the unfixed code** (RED) for the reported reason, not for a typo, a missing require or a different error. A regression test that nobody saw red proves nothing.
- Then apply the fix and rerun the same test unchanged (GREEN). In a managed REPL: load the pre-fix namespace, run the test, keep the failure text, reload the fixed namespace, rerun. Report both. Narrow with `clojure -M:test -n com.blockether.spel.my-test` or `--var com.blockether.spel.my-test/my-test`.
- Name the issue in a comment **on each regression test**: `;; Regression, issue #N: <what used to happen>` directly above the `defdescribe`/`it`/`deftest`, or a section banner with `(issue #N)`. Describe the wrong behavior, not what the code does now. After the merge, it is the only link back to the report.
- Ship the fix and its test in the same commit. A fix without a red-then-green test is unfinished; do not commit it.

## Tests and generated files

- Test at the owning surface: SCI/daemon in `cli_integration_test.clj`, parsing in `cli_test.clj`, native commands in `test-cli.sh`, other behavior in its matching `*_test.clj`. Assert browser and DOM state, not only the absence of exceptions.
- Before you finish, run `make lint` (clojure-lsp and the GraalVM native-image gate of CI) and `make test` (the Clojure suite and `test-cli.sh`).
- The work is finished when CI on the pushed commit is green. Cut a release tag only from such a commit.
- Edit agent templates only under `resources/com/blockether/spel/templates/`, never generated `.opencode` files. After upgrades, regenerate them with `spel init-agents --force --no-tests`.

## Releases

- `resources/SPEL_VERSION` is the single version source. Binaries embed it, and `spel version` prints it.
- Releases are tag-only: workflows build and upload the artifacts. Do not build or upload release binaries by hand.
- Tag the exact green commit whose `resources/SPEL_VERSION` matches the tag (`v$(cat resources/SPEL_VERSION)`). The release workflow rejects a mismatch and checks the released binaries' version. A later changelog or next-version commit is not the release commit.
