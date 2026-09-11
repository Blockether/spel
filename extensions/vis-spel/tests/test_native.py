"""Real native/browser boundary. Enable with SPEL_INTEGRATION=1."""

import json
import os
import socket
import struct
import time
import uuid
from pathlib import Path

import pytest

from vis_spel import Spel, _execute

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.environ.get("SPEL_INTEGRATION") != "1",
        reason="Set SPEL_INTEGRATION=1 after explicit spel.install",
    ),
]


def test_native_browser_workflow(tmp_path):
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


def test_cdp_release_preserves_external_browser():
    client = Spel()
    binary = client.installed().executable
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
