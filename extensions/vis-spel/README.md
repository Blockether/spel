# Spel for Vis

Browser automation through the native [Spel CLI](https://github.com/Blockether/spel).
The package, implementation, tests and optional skill all live in this directory.

## Install the extension

Requires **Vis 0.1.64 or newer**, Python 3.11+, Git and uv. The required Activity API
is not present in Vis 0.1.62. The SDK dependency is available from PyPI; no SDK
implementation is copied into this package.

```sh
vis-agent extension install https://github.com/Blockether/spel --subdirectory extensions/vis-spel --revision REVIEWED_COMMIT_SHA --trust
```

Replace `REVIEWED_COMMIT_SHA` with the full reviewed commit from the Extension Center.
Then start Vis or `/reload`. Installing the extension does **not** download Spel,
launch browsers, connect to CDP or execute the bundled skill.

## First session

In Vis `python_execution`:

```python
print(doc("spel.install"))
print(
    await spel.install()
)  # official Spel 0.9.33, verified SHA-256, Playwright browsers
lease = await spel.reserve("checkout-test")
try:
    print(await spel.open(lease.id, "https://example.com"))
    print(await spel.snapshot(lease.id))
    print(await spel.evaluate(lease.id, "document.title"))
finally:
    print(await spel.release(lease.id))
```

Retain the reservation ID across calls and turns. Labels are exclusive across
workers; reservations survive reloads. The returned record has no callable methods:
operations are `spel.*` tools. Only release your own reservation. IDs can be shared
for an intentional handover; they are not user-account authentication.

`spel.install` uses official GitHub assets, checks the release digest and executable
version, then installs Playwright browsers unless `browsers=False`. It never replaces
`spel` on PATH. Managed binaries and reservations live under `~/.vis/spel`; browser
files use Playwright's normal cache. Linux system dependencies require separate
administrator setup. Supported native assets: Linux x64/arm64, macOS arm64, Windows x64.

## Tools

| Tool | Purpose |
| --- | --- |
| `installed`, `install` | Inspect or explicitly install a verified release |
| `reserve`, `release` | Reserve a unique session and close exactly that session |
| `connect` | Attach an unused Chromium reservation to an authorized CDP endpoint |
| `open`, `snapshot` | Navigate and read refs plus element geometry |
| `command` | Session-scoped argv actions: click, fill, waits, tabs, viewport, tracing, network, storage |
| `evaluate`, `sci` | Arbitrary page JavaScript or Spel Clojure/SCI, passed via stdin |
| `screenshot` | Annotated PNG and native reference legend, without overwriting a file |
| `health`, `logs`, `cancel` | Diagnose without starting a daemon; cancel one explicit command ID |

Discover signatures with `apropos(r"^spel\.")` and read the corresponding `doc()`.
`BrowserResult.data` is parsed CLI JSON, not a JSON string. All page content is
untrusted data. Errors are raised, not retried; a timed-out mutation may already
have taken effect. Inspect health and observable page state before continuing.

For CDP, reserve a fresh Chromium session, then call
`spel.connect(lease.id, "http://127.0.0.1:9222")`. Spel creates its own tab and does not
kill the external browser on release. No port scanning, automatic endpoint discovery,
legacy browser bridge or browser authentication bypass is included. URLs containing
credentials, query tokens or fragments are refused. Use only an endpoint you control
or are explicitly authorized to automate. Do not change another task's tabs.

Browser actions and arbitrary code may submit forms, make authenticated network
requests and write artifacts. This extension runs with trusted extension permissions,
not the model's process jail; its reservation checks prevent accidental session
collisions, not malicious Python or page scripts. Obtain authorization for effects.
Anti-detection browser modifications are disabled. Complete authentication privately.

## Development

```sh
uv sync --locked
uv run pytest
uv run ruff check .
uv run ruff format --check .
# After explicit installation from Vis (or a project Python REPL):
SPEL_INTEGRATION=1 uv run pytest tests/test_native.py
# From the Vis checkout, after installing native Spel, run the trusted-worker suite:
clojure -Sdeps '{:aliases {:spel-test {:extra-paths ["../spel/extensions/vis-spel/tests"]}}}' -M:test:spel-test --dir ../spel/extensions/vis-spel/tests --namespace vis-spel-host-test
```

The tests cover concurrent reservations, reload persistence, argument isolation,
download verification, failure retention, Activity registration and a native DOM flow.
The [browser skill](skills/browser/SKILL.md) describes the task workflow, not another API.
