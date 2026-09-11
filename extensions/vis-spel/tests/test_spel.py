import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError

import pytest

import vis_spel
from vis_spel import Spel, SpelError


@pytest.fixture
def client(tmp_path, monkeypatch):
    spel = Spel(tmp_path)
    binary = tmp_path / "spel"
    binary.write_text("test executable")
    with spel._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)", ("0.9.33", str(binary))
        )
    calls = []

    def execute(binary, arguments, **kwargs):
        calls.append((binary, arguments, kwargs))
        return 0, json.dumps({"result": {"ok": True}}), ""

    monkeypatch.setattr(vis_spel, "_execute", execute)
    return spel, calls


def test_reservations_survive_workers_and_reload(client):
    spel, calls = client
    lease = spel.reserve("checkout")
    assert lease.name.startswith("agent-")
    with pytest.raises(FrozenInstanceError):
        lease.id = "changed"
    other = Spel(spel._home)
    with pytest.raises(SpelError, match="already reserved"):
        other.reserve("checkout")
    other.open(lease.id, "about:blank")
    assert calls[-1][1][1] == lease.name
    other.release(lease.id)
    with pytest.raises(SpelError, match="released"):
        spel.health(lease.id)
    assert spel.reserve("checkout").id != lease.id


def test_concurrent_reservation_is_atomic(client):
    spel, _ = client

    def attempt(_):
        try:
            return Spel(spel._home).reserve("shared")
        except SpelError:
            return None

    with ThreadPoolExecutor(max_workers=6) as pool:
        leases = list(pool.map(attempt, range(12)))
    assert sum(lease is not None for lease in leases) == 1


def test_read_and_reserve_never_start_browser(client):
    spel, calls = client
    assert spel.installed().version == "0.9.33"
    spel.reserve()
    assert not calls


def test_missing_install_does_not_install_implicitly(tmp_path):
    spel = Spel(tmp_path)
    assert spel.installed() is None
    with pytest.raises(SpelError, match="install explicitly"):
        spel.reserve()


@pytest.mark.parametrize("label", ["default", "", "../other", "x y", "x" * 81])
def test_invalid_labels(client, label):
    with pytest.raises(ValueError):
        client[0].reserve(label)


@pytest.mark.parametrize("identifier", ["default", "agent-other", "--all", "a" * 32])
def test_unknown_sessions_never_run(client, identifier):
    spel, calls = client
    with pytest.raises((ValueError, SpelError)):
        spel.release(identifier)
    assert not calls


def test_argv_and_stdin_preserve_javascript(client):
    spel, calls = client
    lease = spel.reserve()
    script = (
        "() => ({text: `quote ' \" --session=default`, items: [1, 2], value: null})"
    )
    result = spel.evaluate(lease.id, script, timeout=45)
    _, arguments, kwargs = calls[-1]
    assert arguments[-2:] == ["eval-js", "--stdin"]
    assert "--session" in arguments and arguments[1] == lease.name
    assert "--no-stealth" in arguments
    assert kwargs == {"stdin": script, "timeout": 45}
    assert result.data == {"result": {"ok": True}}
    spel.sci(lease.id, '(str "--session=default")')
    assert calls[-1][1][-2:] == ["eval-sci", "--stdin"]


@pytest.mark.parametrize(
    "arguments",
    [
        ["close"],
        ["batch"],
        ["session", "list"],
        ["click", "--session=default"],
        ["fill", "#input", "--cdp"],
        ["click", "--all-sessions"],
        ["get", "url", "--json"],
        ["tab", "list", "--provider=ios"],
    ],
)
def test_command_cannot_escape_reservation(client, arguments):
    spel, calls = client
    with pytest.raises(ValueError):
        spel.command(spel.reserve().id, arguments)
    assert not calls


def test_command_preserves_arguments_without_shell(client):
    spel, calls = client
    text = "text; $(not-a-command) ' quoted"
    spel.command(spel.reserve().id, ["fill", "@ref", text])
    assert calls[-1][1][-3:] == ["fill", "@ref", text]


def test_cdp_requires_unused_chromium_reservation(client):
    spel, calls = client
    lease = spel.reserve()
    spel.connect(lease.id, "http://127.0.0.1:9222")
    assert calls[-1][1][-2:] == ["open", "about:blank"]
    assert "http://127.0.0.1:9222" in calls[-1][1]
    with pytest.raises(SpelError, match="already used"):
        spel.connect(lease.id, "http://127.0.0.1:9223")
    with pytest.raises(ValueError, match="Chromium"):
        spel.connect(spel.reserve(browser="firefox").id, "http://127.0.0.1:9222")


@pytest.mark.parametrize(
    "endpoint",
    [
        "9222",
        "ftp://127.0.0.1",
        "http://user:password@127.0.0.1",
        "ws://127.0.0.1?token=private",
        "http://127.0.0.1/#fragment",
    ],
)
def test_invalid_cdp_never_runs(client, endpoint):
    spel, calls = client
    with pytest.raises(ValueError):
        spel.connect(spel.reserve().id, endpoint)
    assert not calls


def test_no_retry_and_failed_release_retains_lease(client, monkeypatch):
    spel, _ = client
    lease = spel.reserve("retry")
    calls = []

    def fail(*args, **kwargs):
        calls.append(args)
        return 1, '{"error":"Ref missing. Take a fresh snapshot."}', "warning"

    monkeypatch.setattr(vis_spel, "_execute", fail)
    with pytest.raises(SpelError, match="fresh snapshot"):
        spel.release(lease.id)
    assert len(calls) == 1
    assert spel._reservation(lease.id)["label"] == "retry"


def test_health_returns_unhealthy_state_and_does_not_mark_started(client, monkeypatch):
    spel, _ = client
    lease = spel.reserve()
    monkeypatch.setattr(
        vis_spel, "_execute", lambda *a, **k: (1, '{"status":"stopped"}', "")
    )
    assert spel.health(lease.id).data["status"] == "stopped"
    assert not spel._reservation(lease.id)["started"]


def test_snapshot_scope_and_screenshot_are_explicit(client, tmp_path):
    spel, calls = client
    lease = spel.reserve()
    spel.snapshot(lease.id, scope="#main", depth=5)
    assert calls[-1][1][-7:] == ["snapshot", "-c", "-i", "-s", "#main", "-d", "5"]
    target = tmp_path / "capture.png"
    spel.screenshot(lease.id, str(target))
    assert calls[-1][1][-3:] == ["screenshot", str(target), "-a"]
    target.touch()
    with pytest.raises(ValueError, match="new absolute"):
        spel.screenshot(lease.id, str(target))


def test_cancel_requires_one_id(client):
    spel, calls = client
    lease = spel.reserve()
    with pytest.raises(ValueError):
        spel.cancel(lease.id, "all")
    spel.cancel(lease.id, "command-123")
    assert calls[-1][1][-2:] == ["cancel", "command-123"]


def test_output_errors_are_not_silently_accepted(client, monkeypatch):
    spel, _ = client
    monkeypatch.setattr(
        vis_spel, "_execute", lambda *a, **k: (0, "bad output", "diagnostic")
    )
    with pytest.raises(SpelError, match="non-JSON"):
        spel.open(spel.reserve().id, "about:blank")


def test_install_runs_verified_binary_before_recording(client, monkeypatch):
    spel, calls = client
    binary = spel._home / "downloaded"
    binary.touch()
    monkeypatch.setattr(vis_spel, "download", lambda *a: binary)

    def run(binary, args, **kwargs):
        calls.append(args)
        return 0, "spel 0.9.33" if args == ["version"] else "Installed", ""

    monkeypatch.setattr(vis_spel, "_execute", run)
    installed = spel.install()
    assert installed.browsers_installed
    assert calls == [["version"], ["install"]]
    assert spel.installed() == installed
