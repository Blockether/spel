"""Native CDP session regressions for Blockether/vis issue #227."""

import http.server
import json
import os
import platform
import shutil
import subprocess
import tempfile
import threading
import unittest
import uuid
from pathlib import Path


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"<title>CDP fixture</title><body><button>Original</button></body>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class WebSocketOnlyFixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append(self.headers.get("Upgrade"))
        self.send_response(101 if self.headers.get("Upgrade") else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


class CDPSessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = str(Path(os.environ.get("SPEL", "target/spel")).resolve())
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
        self.temp = tempfile.TemporaryDirectory(prefix="spel-cdp-227-")
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.owner = f"agent-227-owner-{uuid.uuid4().hex[:10]}"
        self.session = f"agent-227-{uuid.uuid4().hex[:10]}"
        self.env = {**os.environ, "SPEL_SESSION_IDLE_TIMEOUT": "0"}
        self.addCleanup(self.run_cli, self.owner, "close")
        self.addCleanup(self.run_cli, self.session, "close")
        self.profile = self.home / "profile"
        self.command(
            self.owner,
            "--no-persist",
            "--profile",
            str(self.profile),
            "--args",
            "--remote-debugging-port=0",
            "open",
            self.url,
        )
        self.devtools = self.profile / "DevToolsActivePort"
        self.assertTrue(self.devtools.exists(), "Fixture browser did not expose CDP")
        self.port = int(self.devtools.read_text().splitlines()[0])
        self.cdp = f"http://127.0.0.1:{self.port}"

    def run_cli(self, session, *args, discover=False):
        options = [f"-Duser.home={self.home}"] if discover else []
        return subprocess.run(
            [self.binary, *options, "--session", session, *args],
            capture_output=True,
            text=True,
            timeout=60,
            env=self.env,
            check=False,
        )

    def command(self, session, *args, discover=False):
        result = self.run_cli(session, "--json", *args, discover=discover)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def advertise(self):
        # Isolate discovery from the user's browser profiles while reusing only
        # the installed driver cache. The browser's actual profile stays separate.
        cache = Path.home() / ".cache" / "spel"
        destination = self.home / ".cache" / "spel"
        destination.parent.mkdir(parents=True)
        if os.name == "posix":
            destination.symlink_to(cache, target_is_directory=True)
        else:
            shutil.copytree(cache, destination)
        if platform.system() == "Darwin":
            directory = self.home / "Library/Application Support/Google/Chrome"
        elif os.name == "nt":
            self.env["LOCALAPPDATA"] = str(self.home / "AppData/Local")
            self.env["APPDATA"] = str(self.home / "AppData/Roaming")
            directory = self.home / "AppData/Local/Google/Chrome/User Data"
        else:
            directory = self.home / ".config/google-chrome"
        directory.mkdir(parents=True)
        target = directory / "DevToolsActivePort"
        shutil.copyfile(self.devtools, target)
        return target

    # Regression, Blockether/vis issue #227: --auto-connect session reported no
    # active session instead of attaching, forcing a navigating open workaround.
    def test_auto_connect_session_preserves_tabs_and_reuses_attachment(self):
        self.advertise()
        self.command(self.owner, "tab", "new", self.url + "?tab=second")
        self.command(self.owner, "eval-js", "window.continuity='preserved'")
        before = self.command(self.owner, "tab")["tabs"]
        attached = self.command(
            self.session, "--auto-connect", "session", discover=True
        )
        self.assertTrue(attached.get("cdp_connected"), attached)
        self.assertIn(f":{self.port}", attached["cdp_url"])
        self.assertEqual(before, self.command(self.owner, "tab")["tabs"])
        self.assertEqual(len(before), len(self.command(self.session, "tab")["tabs"]))
        pid = self.command(self.session, "health")["pid"]
        for _ in range(2):
            info = self.command(
                self.session, "--auto-connect", "session", discover=True
            )
            self.assertTrue(info["cdp_connected"])
            self.assertEqual(pid, self.command(self.session, "health")["pid"])
            self.assertEqual(before, self.command(self.owner, "tab")["tabs"])
        self.command(self.session, "open", self.url + "?tab=owned")
        self.assertEqual(len(before) + 1, len(self.command(self.owner, "tab")["tabs"]))
        self.assertEqual(
            "preserved",
            self.command(self.owner, "eval-js", "window.continuity")["result"],
        )
        self.command(self.session, "cdp", "disconnect")
        self.assertEqual(before, self.command(self.owner, "tab")["tabs"])
        # CLI close deliberately force-stops a daemon; detach gracefully first.
        self.command(self.session, "close")
        self.assertEqual(before, self.command(self.owner, "tab")["tabs"])

    # Regression, Blockether/vis issue #227: read-only discovery opened a
    # WebSocket authorization handshake, and even --help eagerly probed CDP.
    def test_read_only_discovery_does_not_authorize(self):
        advertised = self.advertise()
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), WebSocketOnlyFixture)
        server.requests = []
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        advertised.write_text(f"{server.server_port}\n/devtools/browser/fixture\n")
        with self.subTest(command="help"):
            result = self.run_cli(
                self.session, "--auto-connect", "session", "--help", discover=True
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([], server.requests)
        server.requests.clear()
        with self.subTest(command="session list"):
            result = self.run_cli(
                self.session,
                "--json",
                "--auto-connect",
                "session",
                "list",
                discover=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            external = json.loads(result.stdout).get("external_cdp", [])
            self.assertIn(server.server_port, [entry["port"] for entry in external])
            self.assertNotIn("websocket", server.requests)

    # Regression, Blockether/vis issue #227: tab text and JSON exposed callback
    # credentials from both the query and fragment in terminal transcripts.
    def test_tab_urls_redact_credentials_without_changing_browser_url(self):
        url = (
            self.url + "?access_token=synthetic-access&view=summary"
            "#refresh_token=synthetic-refresh&mode=preview"
        )
        self.command(self.owner, "open", url)
        for args in [("tab",), ("--json", "tab")]:
            with self.subTest(args=args):
                result = self.run_cli(self.owner, *args)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn("synthetic-access", result.stdout)
                self.assertNotIn("synthetic-refresh", result.stdout)
                self.assertIn("view=summary", result.stdout)
                self.assertIn("mode=preview", result.stdout)
        self.assertEqual(
            url, self.command(self.owner, "eval-js", "location.href")["result"]
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
