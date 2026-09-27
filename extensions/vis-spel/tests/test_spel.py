import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError

import pytest

import vis_spel
from vis_spel import Spel, SpelError
from vis_spel.install import DEFAULT_VERSION


@pytest.fixture
def client(tmp_path, monkeypatch):
    spel = Spel(tmp_path)
    binary = tmp_path / "spel"
    binary.write_text("test executable")
    with spel._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)", ("0.9.40", str(binary))
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


def test_prepared_profile_survives_release_and_new_reservation(client):
    spel, calls = client
    prepared = spel.prepare_profile("x-com")
    assert prepared.name == "x-com"
    assert prepared.path == str(spel._home / "profiles" / "x-com")
    assert (spel._home / "profiles" / "x-com").is_dir()
    lease = spel.reserve("initial-login", profile="x-com", headed=True)
    spel.open(lease.id, "https://x.com/")
    assert calls[-1][1][-2:] == ["open", "https://x.com/"]
    assert calls[-1][1][calls[-1][1].index("--profile") + 1] == prepared.path
    assert "--headed" in calls[-1][1]
    spel.release(lease.id)
    assert spel.prepare_profile("x-com") == prepared
    reused = Spel(spel._home).reserve("next-visit", profile="x-com")
    Spel(spel._home).open(reused.id, "https://x.com/")
    assert calls[-1][1][calls[-1][1].index("--profile") + 1] == prepared.path
    Spel(spel._home).release(reused.id)
    assert prepared.path == str(spel._home / "profiles" / "x-com")


def test_managed_profiles_are_private_and_keep_existing_data(client):
    spel, calls = client
    prepared = spel.prepare_profile("work")
    path = spel._home / "profiles" / "work"
    (path / "session-marker").write_text("kept")
    assert spel.prepare_profile("work") == prepared
    assert (path / "session-marker").read_text() == "kept"
    assert path.stat().st_mode & 0o077 == 0
    assert path.parent.stat().st_mode & 0o077 == 0
    with pytest.raises(ValueError, match="prepare_profile"):
        spel.reserve(profile="unprepared")
    with pytest.raises(ValueError, match="Chromium"):
        spel.reserve(profile="work", browser="webkit")
    with spel._db() as db:
        db.execute("UPDATE installation SET version='0.9.39'")
    with pytest.raises(SpelError, match="Spel 0.9.40 or newer"):
        spel.reserve(profile="work")
    with spel._db() as db:
        db.execute("UPDATE installation SET version='0.9.40'")
    lease = spel.reserve(profile="work")
    with pytest.raises(ValueError, match="CDP cannot use"):
        spel.connect(lease.id, "http://127.0.0.1:9222")
    assert not calls
    spel.release(lease.id)


@pytest.mark.parametrize("name", ["", "../other", "x y", "x" * 81, None])
def test_invalid_profile_names_do_not_create_directories(client, name):
    spel, calls = client
    with pytest.raises(ValueError, match="profile name"):
        spel.prepare_profile(name)
    assert not (spel._home / "profiles").exists()
    assert not calls


def test_profile_symlinks_and_public_directories_are_refused(client, tmp_path):
    spel, calls = client
    root = spel._home / "profiles"
    root.mkdir(mode=0o700)
    (root / "linked").symlink_to(tmp_path, target_is_directory=True)
    with pytest.raises(SpelError, match="not links"):
        spel.prepare_profile("linked")
    root.chmod(0o755)
    with pytest.raises(SpelError, match="private"):
        spel.prepare_profile("new")
    assert not calls


def test_profile_is_exclusive_across_workers_and_released_on_close(client):
    spel, _ = client
    spel.prepare_profile("shared")

    def attempt(index):
        try:
            return Spel(spel._home).reserve(f"task-{index}", profile="shared")
        except SpelError:
            return None

    with ThreadPoolExecutor(max_workers=6) as pool:
        leases = list(pool.map(attempt, range(12)))
    assert sum(lease is not None for lease in leases) == 1
    lease = next(lease for lease in leases if lease is not None)
    with pytest.raises(SpelError, match="already reserved"):
        spel.reserve("different-label", profile="shared")
    spel.release(lease.id)
    assert spel.reserve("reused", profile="shared").id != lease.id


def test_read_and_reserve_never_start_browser(client):
    spel, calls = client
    assert spel.installed().version == "0.9.40"
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
        ["bridge"],
        ["bridge", "use"],
        ["bridge", "--eject-extension"],
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


# Regression, issue #137: blocked help flags hid the supported discovery path.
def test_command_help_failures_recommend_help(client):
    spel, calls = client
    lease = spel.reserve()
    for arguments in (["viewport", "--help"], ["set", "--help"]):
        with pytest.raises(ValueError, match=r"spel.help.*spel.command"):
            spel.command(lease.id, arguments)
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


def test_health_without_session_reports_spel_and_reservations_without_ids(
    client, monkeypatch
):
    spel, _ = client
    spel.prepare_profile("work")
    checkout = spel.reserve("checkout")
    signin = spel.reserve("sign-in", profile="work", headed=True)
    spel.open(checkout.id, "about:blank")
    monkeypatch.setattr(
        vis_spel, "_execute", lambda *a, **k: pytest.fail("health() ran Spel")
    )
    result = spel.health()
    assert (result.session, result.action) == ("", "health")
    assert result.data["status"] == "ok"
    assert result.data["version"] == "0.9.40"
    assert result.data["browsers_installed"] is True
    assert result.data["reservations"] == [
        {
            "label": "checkout",
            "browser": "chromium",
            "headed": False,
            "cdp": False,
            "profile": None,
            "started": True,
        },
        {
            "label": "sign-in",
            "browser": "chromium",
            "headed": True,
            "cdp": False,
            "profile": "work",
            "started": False,
        },
    ]
    text = json.dumps(result.data)
    assert checkout.id not in text and signin.id not in text
    assert str(spel._home) not in text
    assert not spel.spec("spel.health").parameters[0].required


def test_health_without_session_reports_missing_installation(tmp_path, monkeypatch):
    monkeypatch.setattr(
        vis_spel, "_execute", lambda *a, **k: pytest.fail("health() ran Spel")
    )
    assert Spel(tmp_path).health().data == {
        "status": "not_installed",
        "version": None,
        "browsers_installed": None,
        "reservations": [],
    }


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


# Regression, issue #138: explicit full_page=False silently produced full-page annotated PNGs.
@pytest.mark.parametrize(
    ("annotated", "full_page", "flags"),
    [
        (True, None, ["-a"]),
        (True, False, ["-a", "--viewport"]),
        (True, True, ["-a", "-f"]),
        (False, None, []),
        (False, False, []),
        (False, True, ["-f"]),
    ],
)
def test_screenshot_capture_modes(
    client, tmp_path, annotated, full_page, flags, monkeypatch
):
    spel, calls = client
    lease = spel.reserve()
    original_execute = vis_spel._execute

    def current_execute(binary, arguments, **kwargs):
        if arguments == ["version"]:
            calls.append((binary, arguments, kwargs))
            return 0, "spel 0.9.38\n", ""
        return original_execute(binary, arguments, **kwargs)

    monkeypatch.setattr(vis_spel, "_execute", current_execute)
    target = str(tmp_path / "mode.png")
    kwargs = {"annotated": annotated}
    if full_page is not None:
        kwargs["full_page"] = full_page
    spel.screenshot(lease.id, target, **kwargs)
    assert calls[-1][1][-2 - len(flags) :] == ["screenshot", target, *flags]
    if annotated and full_page is False:
        assert calls[-2][1] == ["version"]
        assert calls[-2][0] == calls[-1][0]


# Regression, issue #138: older native binaries ignore --viewport, yielding a
# successful full-page PNG instead of the requested annotated phone viewport.
def test_viewport_annotation_requires_a_capable_pinned_binary(
    client, tmp_path, monkeypatch
):
    spel, calls = client
    lease = spel.reserve()

    def old_execute(binary, arguments, **kwargs):
        calls.append((binary, arguments, kwargs))
        if arguments == ["version"]:
            return 0, "spel 0.9.37\n", ""
        return 0, json.dumps({"result": {"ok": True}}), ""

    monkeypatch.setattr(vis_spel, "_execute", old_execute)
    with pytest.raises(SpelError, match="Spel 0.9.38 or newer"):
        spel.screenshot(lease.id, str(tmp_path / "old.png"), full_page=False)
    assert not any("screenshot" in arguments for _, arguments, _ in calls)


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
        return 0, f"spel {DEFAULT_VERSION}" if args == ["version"] else "Installed", ""

    monkeypatch.setattr(vis_spel, "_execute", run)
    installed = spel.install()
    assert installed.browsers_installed
    assert installed.version == DEFAULT_VERSION == "0.9.40"
    assert calls == [["version"], ["install"]]
    assert spel.installed() == installed


@pytest.fixture
def versions(client, monkeypatch):
    spel, calls = client
    binaries = {}
    for version in ("0.9.33", "0.9.34"):
        binary = spel._home / version / "spel"
        binary.parent.mkdir()
        binary.touch()
        binaries[version] = binary
    monkeypatch.setattr(vis_spel, "download", lambda home, version: binaries[version])

    def execute(binary, arguments, **kwargs):
        calls.append((binary, arguments, kwargs))
        version = next(key for key, value in binaries.items() if str(value) == binary)
        return (
            0,
            f"spel {version}" if arguments == ["version"] else '{"result": "ok"}',
            "",
        )

    monkeypatch.setattr(vis_spel, "_execute", execute)
    return spel, calls, binaries


def test_install_switches_versions_without_changing_existing_reservations(versions):
    spel, calls, binaries = versions
    original = spel.install("0.9.33", browsers=False)
    old_lease = spel.reserve()
    upgraded = spel.install("0.9.34", browsers=False)
    new_lease = spel.reserve()
    assert Spel(spel._home).installed() == upgraded
    assert spel.install("0.9.33", browsers=False) == original
    rollback_lease = spel.reserve()
    for lease, version in (
        (old_lease, "0.9.33"),
        (new_lease, "0.9.34"),
        (rollback_lease, "0.9.33"),
    ):
        spel.open(lease.id, "about:blank")
        assert calls[-1][0] == str(binaries[version])
        spel.release(lease.id)
    assert not any(args == ["install"] for _, args, _ in calls)


@pytest.mark.parametrize("failure", ["download", "version", "browsers"])
def test_failed_switch_retains_installation_and_reservations(
    versions, monkeypatch, failure
):
    spel, calls, binaries = versions
    previous = spel.install("0.9.33", browsers=False)
    lease = spel.reserve()
    if failure == "download":

        def fail(*args):
            raise RuntimeError("Download failed")

        monkeypatch.setattr(vis_spel, "download", fail)
    else:
        monkeypatch.setattr(
            vis_spel,
            "_execute",
            lambda binary, args, **kwargs: (
                (0, "spel 0.9.34", "")
                if args == ["version"] and failure == "browsers"
                else (1, "", "failed")
            ),
        )
    with pytest.raises(RuntimeError):
        spel.install("0.9.34")
    assert Spel(spel._home).installed() == previous
    assert spel._reservation(lease.id)["executable"] == str(binaries["0.9.33"])
