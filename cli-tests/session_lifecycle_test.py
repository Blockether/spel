#!/usr/bin/env python3
"""Native session lifecycle regression tests, run by test-cli.sh (issue #136)."""

import http.server
import json
import os
from pathlib import Path
import platform
import shlex
import signal
import subprocess
import tempfile
import threading
import unittest
import uuid


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"<title>Session fixture</title><body><button>Original</button></body>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@unittest.skipUnless(os.name == "posix", "POSIX terminal lifecycle")
class SessionLifecycleTest(unittest.TestCase):
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
        self.session = f"agent-136-{uuid.uuid4().hex[:12]}"
        self.args = [self.binary, "--session", self.session]
        self.env = {**os.environ, "SPEL_SESSION_IDLE_TIMEOUT": "0"}
        self.addCleanup(self.run_cli, "close")

    def run_cli(self, *args):
        return subprocess.run(
            self.args + list(args),
            capture_output=True,
            text=True,
            timeout=45,
            env=self.env,
        )

    def command(self, *args, expected=0):
        result = self.run_cli("--json", *args)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def seed(self):
        return (
            "window.continuity='retained'; "
            "sessionStorage.setItem('continuity','retained'); "
            "localStorage.setItem('continuity','retained'); "
            "document.cookie='continuity=retained; path=/'; "
            "document.querySelector('button').textContent='Retained DOM'; "
            "window.continuity"
        )

    def assert_state(self):
        state = self.command(
            "eval-js",
            "({memory: window.continuity || null, "
            "session: sessionStorage.getItem('continuity'), "
            "local: localStorage.getItem('continuity'), "
            "cookie: document.cookie, "
            "dom: document.querySelector('button').textContent})",
        )["result"]
        self.assertEqual(
            state,
            {
                "memory": "retained",
                "session": "retained",
                "local": "retained",
                "cookie": "continuity=retained",
                "dom": "Retained DOM",
            },
        )
        snapshot = self.run_cli("snapshot", "-i", "-c")
        self.assertEqual(snapshot.returncode, 0, snapshot.stdout + snapshot.stderr)
        self.assertIn("Retained DOM", snapshot.stdout)

    def pty_chain(self, launch):
        commands = [
            launch + ["--json", "open", self.url],
            ["snapshot", "-i", "-c"],
            ["--json", "eval-js", self.seed()],
            ["--json", "health"],
        ]
        chain = " && ".join(shlex.join(self.args + cmd) for cmd in commands)
        if platform.system() == "Darwin":
            argv = ["script", "-q", "/dev/null", "/bin/bash", "-c", chain]
        else:
            argv = [
                "script",
                "-q",
                "-e",
                "-c",
                shlex.join(["/bin/bash", "-c", chain]),
                "/dev/null",
            ]
        opened = subprocess.run(
            argv, capture_output=True, text=True, timeout=90, env=self.env
        )
        self.assertEqual(opened.returncode, 0, opened.stdout + opened.stderr)
        before = [
            json.loads(line)
            for line in opened.stdout.splitlines()
            if line.startswith("{") and '"pid"' in line
        ][-1]
        self.assertEqual(before["pid"], self.command("health")["pid"])
        self.assert_state()

    # Regression, issue #136: a chained launcher PTY exited with the daemon alive
    # but its browser gone; URL restoration silently lost DOM, cookies and storage.
    def test_pty_chain_retains_standard_browser_state(self):
        self.pty_chain(["--browser", "chromium"])

    # Regression, issue #136: persistent launches also inherited terminal hangup.
    def test_pty_chain_retains_persistent_browser_state(self):
        with tempfile.TemporaryDirectory() as profile:
            try:
                self.pty_chain(["--browser", "chromium", "--profile", profile])
            finally:
                self.command("close")

    def test_pipe_commands_retain_state(self):
        self.command("open", self.url)
        self.command("eval-js", self.seed())
        before = self.command("health")
        self.assert_state()
        self.assertEqual(before["pid"], self.command("health")["pid"])

    # Regression, issue #136: health called cached browser fields a healthy check.
    def test_health_identifies_cached_browser_observations(self):
        self.command("open", self.url)
        health = self.command("health")
        self.assertEqual(health["browser"].get("state_source"), "cached")
        self.assertIn("not a live probe", self.run_cli("health").stdout)

    # Regression, issue #136: evaluate silently ran again on a replacement browser.
    def test_browser_loss_is_reported_without_replaying_evaluate(self):
        self.command("open", self.url)
        self.command("eval-js", self.seed())
        before = self.command("health")
        daemon_pid = int(before["pid"])
        process_rows = subprocess.check_output(
            ["ps", "-axo", "pid=,ppid=,comm="], text=True
        )
        processes = [line.strip().split(None, 2) for line in process_rows.splitlines()]
        children = {int(pid): (int(ppid), comm) for pid, ppid, comm in processes}
        drivers = {pid for pid, (ppid, _) in children.items() if ppid == daemon_pid}
        browsers = [
            pid
            for pid, (ppid, comm) in children.items()
            if ppid in drivers and ("chrom" in comm.lower())
        ]
        self.assertEqual(len(browsers), 1, browsers)
        os.kill(browsers[0], signal.SIGKILL)  # Only this test daemon's verified child.
        lost = self.run_cli("--json", "eval-js", "document.body.dataset.replayed='yes'")
        self.assertNotEqual(lost.returncode, 0, lost.stdout)
        self.assertEqual(
            json.loads(lost.stdout).get("error_code"), "browser_state_lost"
        )
        after = self.command("health", expected=1)
        self.assertEqual(before["pid"], after["pid"])
        self.assertEqual(after["status"], "degraded")
        self.assertTrue(after["browser"].get("state_lost"))
        # An explicit navigation recovers. It cannot restore the lost session state.
        self.command("open", self.url)
        self.assertIsNone(
            self.command("eval-js", "document.body.dataset.replayed || null")["result"]
        )
        self.assertIsNone(
            self.command("eval-js", "window.continuity || null")["result"]
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
