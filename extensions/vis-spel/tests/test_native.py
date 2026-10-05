"""Real native/browser boundary. Enable with SPEL_INTEGRATION=1."""

import json
import os
import re
import socket
import sqlite3
import struct
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from vis_spel import Spel, _execute
from vis_spel.install import DEFAULT_VERSION

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.environ.get("SPEL_INTEGRATION") != "1",
        reason="Set SPEL_INTEGRATION=1 after explicit spel.install",
    ),
]


@pytest.mark.skipif(
    os.environ.get("SPEL_LIGHTPANDA") != "1",
    reason="Set SPEL_LIGHTPANDA=1 with the pinned Lightpanda on PATH",
)
def test_native_lightpanda_reservation(tmp_path):
    binary = os.environ.get("SPEL_TEST_BINARY")
    if not binary:
        installed = Spel().installed()
        assert installed is not None
        binary = installed.executable
    code, version, _ = _execute(binary, ["version"])
    assert code == 0
    client = Spel(tmp_path / "lightpanda")
    with client._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)",
            (version.strip().removeprefix("spel "), binary),
        )

    class Page(BaseHTTPRequestHandler):
        def do_GET(self):
            body = (
                b"<title>Lightpanda extension</title><label>Name<input id='name'></label>"
                b"<button onclick=\"document.querySelector('#result').textContent="
                b"document.querySelector('#name').value\">Save</button><p id='result'></p>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Page)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    lease = None
    try:
        lease = client.reserve(browser="lightpanda")
        client.open(lease.id, f"http://127.0.0.1:{server.server_port}/")
        snapshot = client.snapshot(lease.id).data["snapshot"]
        save = re.search(r'button "Save".*?\[@(e[a-z0-9]+)\]', snapshot)
        assert save, snapshot
        client.command(lease.id, ["fill", "#name", "Ada"])
        client.command(lease.id, ["click", f"@{save.group(1)}"])
        other = Spel(client._home)
        assert (
            other.evaluate(
                lease.id, "document.querySelector('#result').textContent"
            ).data["result"]
            == "Ada"
        )
        assert other.health(lease.id).data["browser"]["engine"] == "lightpanda"
        picture = client.screenshot(
            lease.id, str(tmp_path / "text.png"), annotated=False
        )
        assert picture.data["rendering"] == "text-only"
        assert (tmp_path / "text.png").read_bytes().startswith(b"\x89PNG")
    finally:
        if lease is not None:
            client.release(lease.id)
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


# Regression, user report: a missing NODE_OPTIONS preload blocked browser startup.
@pytest.mark.parametrize("with_node_options", [False, True])
def test_native_browser_workflow(tmp_path, monkeypatch, with_node_options):
    if with_node_options:
        monkeypatch.setenv(
            "NODE_OPTIONS", f"--require={tmp_path / 'missing-preload.cjs'}"
        )
    client = Spel()
    assert client.installed() is not None
    lease = client.reserve()
    try:
        assert client.health(lease.id).data["status"] != "ok"
        client.open(
            lease.id,
            "data:text/html,<title>Spel extension</title><main><button onclick='this.textContent=42'>Count</button><input aria-label='Name'></main>",
        )
        snapshot = client.snapshot(lease.id)
        assert "Count" in str(snapshot.data)
        assert "pos:" in str(snapshot.data)
        client.command(lease.id, ["fill", "input", "O'Reilly; --session=default"])
        client.command(lease.id, ["click", "button"])
        result = client.evaluate(
            lease.id,
            "() => ({title: document.title, count: document.querySelector('button').textContent, value: document.querySelector('input').value, quoted: '--session=default', nil: null})",
        )
        assert result.data["result"] == {
            "title": "Spel extension",
            "count": "42",
            "value": "O'Reilly; --session=default",
            "quoted": "--session=default",
            "nil": None,
        }
        assert client.sci(lease.id, "(+ 20 22)").data["result"] in (42, "42")
        # Native annotation is full-page; unannotated captures can be viewport-only.
        client.command(lease.id, ["set", "viewport", "800", "600"])
        client.evaluate(lease.id, "document.body.style.minHeight = '1800px'")
        image = tmp_path / "browser.png"
        picture = client.screenshot(lease.id, str(image))
        assert image.read_bytes().startswith(b"\x89PNG")
        assert struct.unpack(">II", image.read_bytes()[16:24])[1] >= 1800
        viewport = tmp_path / "viewport.png"
        client.screenshot(lease.id, str(viewport), annotated=False)
        assert struct.unpack(">II", viewport.read_bytes()[16:24]) == (800, 600)
        assert picture.data["annotated"]["count"] == 2
        entries = picture.data["annotated"]["entries"]
        assert {entry["name"] for entry in entries} == {"42", "Name"}
        assert all(
            entry["ref"] and entry["mark"] and entry["bbox"] for entry in entries
        )
        assert isinstance(client.logs(lease.id).data["lines"], list)
        assert client.health(lease.id).data["status"] == "ok"
        assert (
            Spel().evaluate(lease.id, "document.title").data["result"]
            == "Spel extension"
        )
    finally:
        client.release(lease.id)
    assert not Path(str(lease.name) + ".png").exists()


def test_native_managed_profile_persists_auth_storage_across_sessions(tmp_path):
    installed = Spel().installed()
    assert installed is not None
    binary = os.environ.get("SPEL_TEST_BINARY", installed.executable)
    code, version, _ = _execute(binary, ["version"])
    assert code == 0 and version.strip().startswith("spel ")
    client = Spel(tmp_path / "managed")
    with client._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)",
            (version.strip().removeprefix("spel "), binary),
        )
    profile = client.prepare_profile("login")

    class Page(BaseHTTPRequestHandler):
        def do_GET(self):
            body = b"<title>Profile storage test</title><main>Signed-in state fixture</main>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Page)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{server.server_port}/"
    try:
        first = client.reserve("initial", profile="login")
        try:
            client.open(first.id, url)
            assert client.evaluate(
                first.id,
                "() => {localStorage.setItem('profile-test', 'saved'); document.cookie = 'profile-test=saved; Max-Age=3600; SameSite=Lax'; return {storage: localStorage.getItem('profile-test'), cookie: document.cookie}}",
            ).data["result"] == {"storage": "saved", "cookie": "profile-test=saved"}
        finally:
            client.release(first.id)
        assert Path(profile.path).is_dir()
        with sqlite3.connect(Path(profile.path) / "Default" / "Cookies") as cookies:
            assert ("127.0.0.1", "profile-test") in cookies.execute(
                "SELECT host_key, name FROM cookies"
            ).fetchall()
        reused = Spel(client._home).reserve("again", profile="login")
        try:
            client.open(reused.id, url)
            assert client.evaluate(
                reused.id,
                "() => ({storage: localStorage.getItem('profile-test'), cookie: document.cookie})",
            ).data["result"] == {"storage": "saved", "cookie": "profile-test=saved"}
        finally:
            client.release(reused.id)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


# Regression, issue #138: full_page=False returned an annotated full-page PNG
# and mapped controls outside the phone viewport to marks that were not visible.
def test_native_annotated_viewport_preserves_marks_and_cleanup(tmp_path):
    installation = Spel().installed()
    assert installation is not None
    binary = Path(os.environ.get("SPEL_TEST_BINARY", installation.executable)).resolve()
    code, output, error = _execute(str(binary), ["version"])
    assert code == 0, error
    version = output.strip().removeprefix("spel ")
    assert tuple(map(int, version.split("."))) >= (0, 9, 38), version
    client = Spel(tmp_path / "home")
    with client._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)", (version, str(binary))
        )
    lease = client.reserve()
    try:
        client.open(
            lease.id,
            "data:text/html,<title>Phone viewport</title><button>Visible</button>"
            "<button style='position:absolute;top:1400px'>Below</button>"
            "<div style='height:1800px'></div>",
        )
        client.command(lease.id, ["set", "viewport", "361", "800"])
        full = tmp_path / "full.png"
        full_result = client.screenshot(lease.id, str(full))
        assert struct.unpack(">II", full.read_bytes()[16:24])[1] > 1800
        assert {e["name"] for e in full_result.data["annotated"]["entries"]} == {
            "Visible",
            "Below",
        }
        viewport = tmp_path / "viewport.png"
        viewport_result = client.screenshot(lease.id, str(viewport), full_page=False)
        assert struct.unpack(">II", viewport.read_bytes()[16:24]) == (361, 800)
        assert [e["name"] for e in viewport_result.data["annotated"]["entries"]] == [
            "Visible"
        ]
        assert viewport_result.data["annotated"]["count"] == 1
        assert (
            client.evaluate(
                lease.id, "document.querySelectorAll('[data-spel-annotate]').length"
            ).data["result"]
            == 0
        )
        plain = tmp_path / "plain.png"
        client.screenshot(lease.id, str(plain), annotated=False)
        assert struct.unpack(">II", plain.read_bytes()[16:24]) == (361, 800)
    finally:
        client.release(lease.id)


def test_cdp_release_preserves_external_browser():
    client = Spel()
    installation = client.installed()
    assert installation is not None
    binary = installation.executable
    external = f"agent-{int(time.time())}-{uuid.uuid4().hex[:12]}-cdp-host"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    lease = client.reserve()
    released = False
    try:
        status, _, error = _execute(
            binary,
            [
                "--session",
                external,
                "--json",
                "--no-stealth",
                "--args",
                f"--remote-debugging-port={port}",
                "open",
                "data:text/html,<title>External fixture</title>",
            ],
        )
        assert status == 0, error
        client.connect(lease.id, f"http://127.0.0.1:{port}")
        assert client.evaluate(lease.id, "document.title").data["result"] == ""
        client.evaluate(lease.id, "document.title = 'Owned automation tab'")
        client.release(lease.id)
        released = True
        status, output, error = _execute(
            binary, ["--session", external, "--json", "eval-js", "document.title"]
        )
        assert status == 0, error
        assert json.loads(output)["result"] == "External fixture"
    finally:
        if not released:
            client.release(lease.id)
        _execute(binary, ["--session", external, "--json", "close"])


def test_native_upgrade_and_rollback_preserve_live_sessions(tmp_path):
    client = Spel(tmp_path)
    leases = []
    try:
        original = client.install("0.9.33", browsers=False)
        old = client.reserve()
        leases.append(old)
        client.open(old.id, "data:text/html,<title>Original version</title>")
        upgraded = client.install(DEFAULT_VERSION, browsers=False)
        assert upgraded.version == DEFAULT_VERSION
        assert upgraded.executable != original.executable
        new = client.reserve()
        leases.append(new)
        client.open(new.id, "data:text/html,<title>Upgraded version</title>")
        assert client.install("0.9.33", browsers=False) == original
        assert (
            client.evaluate(old.id, "document.title").data["result"]
            == "Original version"
        )
        assert (
            client.evaluate(new.id, "document.title").data["result"]
            == "Upgraded version"
        )
        assert Spel(tmp_path).installed() == original
    finally:
        for lease in reversed(leases):
            client.release(lease.id)
