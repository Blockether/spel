"""Real Lightpanda coverage for the native CLI, without external websites."""

import http.server
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"""<!doctype html><html><head><title>Lightpanda fixture</title></head>
<body><h1>Local browser test</h1><a href="/next">Next page</a>
<label for="name">Name</label><input id="name">
<button id="save" onclick="document.querySelector('#result').textContent =
    'Hello ' + document.querySelector('#name').value">Save</button>
<p id="result">Not saved</p><button hidden>Hidden action</button>
<script>window.fixtureReady = true;</script></body></html>"""
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class LightpandaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = str(Path(os.environ.get("SPEL", "target/spel")).resolve())
        if not shutil.which("lightpanda"):
            raise RuntimeError("Install the pinned Lightpanda binary before this suite")
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        self.session = f"agent-lightpanda-{uuid.uuid4().hex[:12]}"
        self.env = {**os.environ, "SPEL_SESSION_IDLE_TIMEOUT": "0"}
        self.addCleanup(self.run_cli, "close")

    def run_cli(self, *args):
        return subprocess.run(
            [self.binary, "--session", self.session, "--json", *args],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )

    def command(self, *args):
        result = self.run_cli(*args)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def open_fixture(self):
        self.command("--engine", "lightpanda", "--no-persist", "open", self.url)

    def test_navigation_and_javascript(self):
        self.open_fixture()
        self.assertEqual("Lightpanda fixture", self.command("get", "title")["title"])
        self.assertTrue(self.command("eval-js", "window.fixtureReady")["result"])
        self.assertEqual(
            "Local browser test", self.command("get", "text", "h1")["text"]
        )

    def test_snapshot_refs_and_form_effects(self):
        self.open_fixture()
        snapshot = self.command("snapshot", "-i", "-c")["snapshot"]
        self.assertNotIn("Hidden action", snapshot)
        save = re.search(r'button "Save".*?\[@(e[a-z0-9]+)\]', snapshot)
        self.assertIsNotNone(save, snapshot)
        self.command("fill", "#name", "Ada")
        self.command("click", f"@{save.group(1)}")
        self.assertEqual("Hello Ada", self.command("get", "text", "#result")["text"])

    def test_close_stops_owned_browser(self):
        self.open_fixture()
        before = self.command("health")
        self.assertGreater(int(before["pid"]), 0)
        port = int(before["browser"]["cdp"].rsplit(":", 1)[1])
        self.command("close")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", port)) != 0:
                    break
            time.sleep(0.05)
        else:
            self.fail("The owned Lightpanda CDP server survived close")
        result = self.run_cli("health")
        self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_engine_identity_and_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "spel.json"
            config.write_text(json.dumps({"engine": "lightpanda"}))
            self.command("--config", str(config), "--no-persist", "open", self.url)
        health = self.command("health")
        self.assertEqual(health["browser"]["engine"], "lightpanda")
        self.assertEqual(self.command("session", "info")["engine"], "lightpanda")

    def test_unknown_engine_is_rejected(self):
        result = self.run_cli("--engine", "unknown", "open", self.url)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Unknown browser engine", result.stdout)

    def test_failed_cdp_connection_cleans_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "child.json"
            probed = Path(directory) / "child.json.probed"
            fake = Path(directory) / "lightpanda"
            fake.write_text(
                f"#!{sys.executable}\n"
                "import http.server, json, os, socketserver, sys, threading\n"
                "port = int(sys.argv[sys.argv.index('--port') + 1])\n"
                "marker = os.environ['SPEL_TEST_LP_CHILD']\n"
                "with open(marker, 'w') as output:\n"
                "    json.dump({'pid': os.getpid(), 'port': port}, output)\n"
                "class Handler(http.server.BaseHTTPRequestHandler):\n"
                "    def do_GET(self):\n"
                "        open(marker + '.probed', 'w').close()\n"
                "        self.send_response(200)\n"
                "        self.end_headers()\n"
                "        self.wfile.write(json.dumps({'Browser': 'Lightpanda/test', "
                "'webSocketDebuggerUrl': f'ws://127.0.0.1:{port}'}).encode())\n"
                "    def log_message(self, *args): pass\n"
                # HTTPServer looks up the host name before it listens. A slow
                # lookup must not exceed the Lightpanda startup limit.
                "class Server(socketserver.TCPServer):\n"
                "    allow_reuse_address = True\n"
                "server = Server(('127.0.0.1', port), Handler)\n"
                "threading.Timer(45, server.shutdown).start()\n"
                "server.serve_forever()\n"
            )
            fake.chmod(0o700)
            self.env["PATH"] = directory + os.pathsep + os.environ["PATH"]
            self.env["SPEL_TEST_LP_CHILD"] = str(marker)
            try:
                result = self.run_cli("--engine", "lightpanda", "open", self.url)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertTrue(marker.exists(), result.stdout + result.stderr)
                self.assertTrue(probed.exists(), result.stdout + result.stderr)
                child = json.loads(marker.read_text())
                with socket.socket() as probe:
                    self.assertNotEqual(
                        probe.connect_ex(("127.0.0.1", child["port"])),
                        0,
                        "A failed CDP connection left its Lightpanda process alive",
                    )
            finally:
                self.run_cli("close")
                if marker.exists():
                    try:
                        os.kill(json.loads(marker.read_text())["pid"], signal.SIGTERM)
                    except ProcessLookupError:
                        pass

    def test_engine_switch_keeps_the_live_session(self):
        self.open_fixture()
        result = self.run_cli("--engine", "chrome", "get", "title")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(json.loads(result.stdout)["error_code"], "engine_conflict")
        self.assertEqual(self.command("health")["browser"]["engine"], "lightpanda")
        self.assertEqual(self.command("get", "title")["title"], "Lightpanda fixture")

    def test_headed_lightpanda_is_rejected(self):
        result = self.run_cli("--engine", "lightpanda", "--headed", "open", self.url)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Lightpanda", result.stdout)
        self.assertIn("--headed", result.stdout)

    def test_text_exports_are_labeled(self):
        self.open_fixture()
        with tempfile.TemporaryDirectory() as directory:
            for command, suffix, magic in (
                ("screenshot", "png", b"\x89PNG"),
                ("pdf", "pdf", b"%PDF"),
            ):
                with self.subTest(command=command):
                    destination = Path(directory) / f"capture.{suffix}"
                    result = self.command(command, str(destination))
                    self.assertTrue(destination.read_bytes().startswith(magic))
                    self.assertEqual(result["rendering"], "text-only")

    def test_visual_annotations_fail_clearly(self):
        self.open_fixture()
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "capture.png"
            result = self.run_cli("screenshot", "-a", str(destination))
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("Lightpanda does not support visual layout", result.stdout)
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
