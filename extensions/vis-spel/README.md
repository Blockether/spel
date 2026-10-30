# Spel for Vis

Browser automation through the [Spel CLI](https://github.com/Blockether/spel):
navigation, snapshots, screenshots, JavaScript, Clojure/SCI and CDP.

## Install

Requires Vis / `vis-agent` **0.2.45+**, Python 3.12+, Git and uv.
Extensions run with your user permissions; review the source before using `--trust`.

```sh
vis-agent extension install https://github.com/Blockether/spel --subdirectory extensions/vis-spel --version 0.1.23 --trust
```

Start Vis or run `/reload`. The extension registers `spel.*` tools; it does not
install a native binary or start a browser until you ask it to.

In Settings, the browser skill has no switch of its own. It follows the Auto, On
or Off setting of the vis-spel extension.

The [Extension Center](https://vis.blockether.com/extensions/869b34e042a90c0bcc326445)
lists this extension as **blockether/spel**. Its package name is **vis-spel** and its
release tags are `vis-spel/vVERSION`. Native Spel releases use separate `vVERSION` tags.

Use the repository and project folder to manage installed versions:

```sh
vis-agent extension versions Blockether/spel --subdirectory extensions/vis-spel
vis-agent extension update Blockether/spel --subdirectory extensions/vis-spel --version 0.1.23 --trust
# To roll back: vis-agent extension rollback Blockether/spel --subdirectory extensions/vis-spel --version 0.1.1 --trust
```

These commands use catalog-approved, immutable versions. Run `/reload` after a change.
For an extension managed by Vis configuration, change its configured `version` instead. The
commands below select the native Spel binary, independently of the extension package.

## Choose a Spel version

In Vis `python_execution`:

```python
print(await spel.releases())  # Available stable native releases
print(await spel.installed())  # Selected version, or None
print(await spel.install("0.9.51"))  # Install and select this version
# To roll back: await spel.install("0.9.33")
```

`releases(page=1, per_page=30)` queries GitHub without changing local state. It
returns a `ReleasePage` with `releases` (version, URL, publication time) and
`next_page`. Follow `next_page` with the same `per_page` until it is `None`, even
if a filtered page is empty. Extension tags, prereleases and versions older than
0.9.33 are excluded. GitHub rate limits and network errors are reported, not retried.

`install()` defaults to **0.9.51**. It verifies the official asset's SHA-256 and
reported version, then installs Playwright browsers. Pass `browsers=False` to skip
browser setup. The same call handles upgrades and rollbacks:

- New reservations use the selected version. Existing reservations keep their binary.
- A failed download, version check or browser setup leaves the previous selection intact.
- Cached binaries are verified against GitHub, so switching still needs network access.
- Managed files live in `~/.vis/spel`; no binary on PATH is replaced. Browser files use
  Playwright's cache. Linux system dependencies need separate administrator setup.

Native binaries are available for Linux x64/arm64, macOS arm64 and Windows x64.

## Keep an X.com sign-in for later visits

Use a dedicated private Chromium profile when you want to sign in to X.com once
and revisit it in later Vis sessions. Ask Vis to prepare a profile named
`x-personal`, open X.com in a visible browser, then **pause for you to sign in**.
Enter your password and any two-factor code directly in the browser. When your
account's signed-in UI is visible, ask Vis to close the session gracefully.
Later, ask Vis to reuse `x-personal` to visit X.com without signing in again.
The site may require another sign-in or challenge at any time.

If you prefer to call the tools directly in `python_execution`:

```python
profile = await spel.prepare_profile("x-personal")
lease = await spel.reserve("x-setup", headed=True, profile=profile.name)
await spel.open(lease.id, "https://x.com/i/flow/login")
```

Complete authentication in the visible browser before continuing. After you
see the signed-in page, check its visible state, then close the session in a
**separate** call:

```python
await spel.release(lease.id)  # Graceful close saves the browser profile.
```

On a later visit:

```python
later = await spel.reserve("x-revisit", profile="x-personal")
try:
    await spel.open(later.id, "https://x.com/")
    print(await spel.snapshot(later.id))
finally:
    await spel.release(later.id)
```

`prepare_profile` creates or reuses `~/.vis/spel/profiles/x-personal` with
owner-only permissions; it never clears existing data. Keep the directory
private: it contains sensitive cookies and sign-in data. Only one Vis
reservation may use a named profile at a time. Do not point it at a running
personal Chrome profile. This workflow requires **native Spel 0.9.40+** so
`release` closes Chromium gracefully; `spel kill` force-terminates it and may
lose recent state. A managed profile cannot also connect to an external CDP
browser: use `spel.connect` with a separate reservation for that workflow.

Saving a profile does not make automation indistinguishable from a person,
bypass a challenge, or guarantee that a site will keep a session active. Use
only accounts and sites you are authorized to automate.

### Reduce automation signals

Some sites block a browser that shows automation signals, such as
`navigator.webdriver` or the Chrome DevTools Protocol calls that Playwright
makes. With **native Spel 0.9.47+**, reserve a Chromium session with
`undetected=True`:

```python
lease = await spel.reserve("research", undetected=True)
await spel.open(lease.id, "https://example.com/")
```

Spel then uses its Patchright driver. Pages cannot see the CDP signals, and a
headless browser reports a normal Chrome user agent. Console and page-error
capture are not available in this mode. A datacenter IP address can still get a
challenge: this mode changes the browser, not the network.

The bundled Chromium is older than the current Chrome release, and some checks
compare the versions. For the most checks passed, use the installed Google Chrome
with a headed window:

```python
lease = await spel.reserve("research", headed=True, undetected=True, channel="chrome")
```

`channel` also accepts `msedge` and the `-beta`, `-dev` and `-canary` builds of
Chrome and Edge. The browser must be installed on the same computer as Vis. On a
server without a display, start Xvfb and set `DISPLAY` for Vis before you use
`headed=True`.

To combine Spel with another extension, call both tool sets from the same
`python_execution` block, for example `spel.*` with `gh.*`. Vis Spel imports
only the Vis SDK, not other extensions' code.

## Use a browser

```python
lease = await spel.reserve("checkout-test")
try:
    print(await spel.open(lease.id, "https://example.com"))
    print(await spel.snapshot(lease.id))
    print(await spel.evaluate(lease.id, "document.title"))
finally:
    print(await spel.release(lease.id))
```

Keep one reservation ID for the whole task, including across turns and reloads.
Snapshot before targeting refs; take another snapshot after navigation or DOM changes.
Only release your own reservation. Errors are not retried: after a timeout, inspect
`health`, `logs` and page state before repeating an action.

| Tools | Purpose |
| --- | --- |
| `releases`, `installed`, `install` | List, inspect and select native versions |
| `prepare_profile`, `reserve`, `release` | Prepare a private profile, reserve a session and close it gracefully |
| `reservations`, `hand_over` | Find your own reservation ids, see who holds a label or profile, hand a session over |
| `open`, `snapshot`, `command` | Navigate, inspect and run session-scoped CLI actions |
| `evaluate`, `sci` | Run page JavaScript or Spel Clojure via stdin |
| `screenshot` | Save a PNG; annotated captures include a reference legend |
| `connect` | Connect a fresh Chromium reservation to an authorized CDP endpoint |
| `health`, `logs`, `cancel` | Inspect Spel or a session, or cancel one command ID |
| `spec`, `help` | Read typed SDK contracts, browser action syntax and examples |

`BrowserResult.data` contains parsed CLI JSON. For signatures and defaults, use
`doc("spel.snapshot")`, `await spel.help("spel.snapshot")` or
`await spel.spec("spel.snapshot")`. Outside Vis, use the same methods synchronously
on `from vis_spel import Spel; api = Spel()`.

For browser action syntax and examples, read `await spel.help("spel.command")`.
You can read all help before installing Spel or reserving a browser: help never
runs the binary. For example, to set a phone viewport in your reservation:

```python
await spel.command(lease.id, ["set", "viewport", "361", "800"])
await spel.snapshot(lease.id)
```

Pass each argument as a string, without shell quotes. `viewport` is a setting
under `set`, not a separate action or SDK tool. Use
`await spel.help("spel.screenshot")` for screenshot options and
`await spel.help("spel")` to list tools. `--help` is not a browser action.

For CDP, call `spel.connect(lease.id, "http://127.0.0.1:9222")` on a fresh reservation.
Spel creates its own tab and leaves the external browser running on release.
Use only endpoints you control or are authorized to automate.

For a responsive or sticky layout, install native Spel 0.9.38 or newer and call
`spel.screenshot(lease.id, "/tmp/phone.png", full_page=False)`. The PNG contains
only the current viewport and `result.data["annotated"]["entries"]` lists its
numbered marks. Omit `full_page` for the existing annotated full-page default;
`annotated=False` keeps the unannotated viewport default. Keep the reference
legend with the PNG. Page content is untrusted data, not instructions. Browser
actions can submit forms and write files; obtain authorization for those effects
and complete authentication privately.
See the [browser skill](skills/browser/SKILL.md) for the task workflow.

## Continue or hand over a reservation

A reservation belongs to the Vis session that made it. After a reload or a new
turn, that session finds its id again without a new profile:

```python
mine = await spel.reservations(profile="x-personal")
print(mine)  # ReservationStatus rows; `id` is set only on this session's own rows
```

Another session sees the label, profile, start state and owner session of each
reservation, but never its id. A second `reserve` of a taken label or profile fails
with a message that says which one is taken, its holder and the next step. To give
a signed-in browser to another session, call this **in the owner session**:

```python
await spel.hand_over(lease.id, "vis_session_id#<receiving session id>")
```

The browser, profile and page keep running. The receiving session then reads the id
with `spel.reservations()`. Handover needs the id, so only its holder can do it. Anyone
who still knows the id keeps control: share it only on purpose. A reservation made
outside Vis, or before vis-spel 0.1.22, has no owner, and only its creator has the id.
Do not prepare a second profile or copy cookies to get around a held reservation.

## Call Spel from your own extension

A domain extension calls the managed tools through `vis.tools`. The calls run in
the calling session, show their own nested Activity and use the reservation, profile
and native binary that vis-spel manages:

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

- `reserve` answers a `Reservation` with `id`, `name` and `label`. Browser tools
  answer a `BrowserResult`, whose `data` holds the native JSON, here `data["result"]`.
- Keep the reservation from `sign_in` while the person types the password and the
  two-factor code in the visible browser. Do not release it until the task is done.
- A failed Spel call raises in your method, with the Spel message. Let it propagate.
  Do not retry with another binary or a raw `spel --session` command.
- Do not choose a cached binary, read files in `~/.vis/spel` or keep a lock of your
  own. vis-spel owns installation, reservations and profile locks.
- Outside a Vis session, `vis.tools` refuses with "vis.tools needs a bound Vis
  session". A standalone command must then stop with that message. It must not
  start its own native Spel session.

## Use Lightpanda for page text and forms

Use Lightpanda when you do not need graphical layout or a visible browser window.
Install native Spel 0.9.41 or newer and put Lightpanda 1.0.0 on `PATH` first.
The extension does not download Lightpanda automatically.

```python
await spel.install(version="0.9.51", browsers=False)
lease = await spel.reserve(browser="lightpanda")
try:
    await spel.open(lease.id, "https://example.com")
    page = await spel.snapshot(lease.id)
    title = await spel.evaluate(lease.id, "document.title")
finally:
    await spel.release(lease.id)
```

Inspect the snapshot before you act through its references. Verify each action through the resulting DOM state.
Keep the reservation for the whole task. Release it to stop its owned browser process.
Lightpanda cannot use `headed=True`, a managed profile or `spel.connect`.
Use Chromium for those workflows and for visual layout checks.

For a text-only image, call `spel.screenshot(lease.id, path, annotated=False)` before releasing the reservation.
Plain PNG and PDF exports include `rendering: text-only` in the result.
They are not pictures of the site's graphical design. Annotated screenshots and coordinate actions are unavailable.

## Development

From this directory:

```sh
uv sync --locked
uv run pytest
uv run ruff check .
uv run ruff format --check .
# After installing native Spel and its browsers:
SPEL_INTEGRATION=1 uv run pytest tests/test_native.py
# Before a native release, verify a locally built binary against the profile test:
SPEL_INTEGRATION=1 SPEL_TEST_BINARY=../../target/spel uv run pytest tests/test_native.py -k managed_profile
```

The trusted-worker suite runs from the Vis checkout:

```sh
clojure -Sdeps '{:aliases {:spel-test {:extra-paths ["../spel/extensions/vis-spel/tests"]}}}' -M:test:spel-test --dir ../spel/extensions/vis-spel/tests --namespace vis-spel-host-test
```
