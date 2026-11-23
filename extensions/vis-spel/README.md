# Spel for Vis

Automate a browser with the Spel CLI. Verify the native installation, reserve a browser session, and use CDP, JavaScript, snapshots and screenshots.

## Install

Requires Vis / `vis-agent` **0.5.30+**, Python 3.12+, Git and uv. Review the source before you use `--trust`.

```bash
vis-agent extension install https://github.com/Blockether/spel --subdirectory extensions/vis-spel --version 0.1.36 --trust
vis-agent extension versions Blockether/spel --subdirectory extensions/vis-spel
vis-agent extension update Blockether/spel --subdirectory extensions/vis-spel --version 0.1.36 --trust
vis-agent extension rollback Blockether/spel --subdirectory extensions/vis-spel --version 0.1.1 --trust
```

Install once. Use `versions`, `update` or `rollback` only when you need to change versions. Reload Vis after a change.

## Use

Use the `spel.*` tools in `py3`. First call `spel.installed()` to check for a native binary. If none is selected, call `spel.install()`.

| Tools | Use |
| --- | --- |
| `releases`, `installed`, `install` | List, check or select native versions. |
| `prepare_profile`, `reserve`, `release` | Prepare a private profile, reserve a session or close it. |
| `reservations`, `hand_over`, `share`, `unshare` | Find a reservation, give it to another Vis session or share it with several. |
| `open`, `snapshot`, `command` | Open a page, inspect it or run an action. |
| `evaluate`, `sci` | Run JavaScript or Clojure. |
| `screenshot` | Save a PNG and optional reference legend. |
| `connect` | Connect to an authorized CDP endpoint. |
| `batch` | Run a saved list of browser actions. |
| `health`, `logs`, `cancel` | Check status or stop one command. |
| `spec`, `help` | Read the contracts and examples. |

Keep one reservation for the task. Snapshot before you act. Release the reservation when the task ends.

In your own extension, use `vis.tools.spel` to share the same managed session. This example keeps a sign-in open while the person uses the browser:

```python
import blockether.vis.extension as vis


class Portal:
    """Read the balance on portal.example through a managed Spel reservation."""

    def sign_in(self) -> str:
        """Open the portal sign-in for the person. Returns the reservation id."""
        mine = vis.tools.spel.reservations(label="portal")
        if mine and mine[0].id:
            # This session already holds it, for example after a reload.
            return mine[0].id
        vis.tools.spel.prepare_profile("portal")
        lease = vis.tools.spel.reserve("portal", headed=True, profile="portal")
        vis.tools.spel.open(lease.id, "https://portal.example/login")
        return lease.id

    def balance(self, session: str) -> str:
        """Read the balance from the signed-in page of reservation `session`."""
        result = vis.tools.spel.evaluate(
            session, "() => document.querySelector('#balance')?.textContent ?? null"
        )
        if result.data["result"] is None:
            raise RuntimeError("No balance is visible. Finish the sign-in first.")
        return result.data["result"]

    def finish(self, session: str) -> None:
        """Close the browser. The profile keeps the sign-in for later visits."""
        vis.tools.spel.release(session)


vis.register_extension(
    vis.Extension(
        name="portal",
        description="Portal balance through managed Spel sessions.",
        alias="portal",
        symbols=[vis.Symbol(Portal(), name="portal")],
    )
)
```

See the [browser guide](docs/browser-workflows.md) for native versions, private profiles, batch steps, CDP and Lightpanda.

## Development

```bash
uv sync --locked --group dev
uv run --group dev pytest
uv run --group dev ruff check . && uv run --group dev ruff format --check .
```
