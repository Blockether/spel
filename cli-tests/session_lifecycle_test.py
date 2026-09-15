"""Native session lifecycle regression tests, run by test-cli.sh (issue #136)."""

import http.server
import json
import os
import platform
import re
import shlex
import signal
import subprocess
import tempfile
import threading
import unittest
import uuid
from pathlib import Path


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


# Regression, Council #4728: test startup killed unrelated Spel daemons and
# removed their socket/PID files. Inspect before executing any harness startup.
def assert_harness_cleanup_scoped(test):
    repository = Path(__file__).resolve().parents[1]
    scripts = [
        repository / "test-cli.sh",
        *sorted((repository / "cli-tests").glob("*.sh")),
    ]
    unsafe = []
    for script in scripts:
        for number, line in enumerate(script.read_text().splitlines(), 1):
            if line.lstrip().startswith("#"):
                continue
            if re.search(r"\b(?:pkill|killall)\b|/tmp/spel-\*", line):
                unsafe.append(f"{script.name}:{number}: {line.strip()}")
    test.assertEqual(unsafe, [], "Global cleanup can terminate unrelated sessions")


class HarnessSafetyTest(unittest.TestCase):
    def test_cleanup_is_scoped(self):
        assert_harness_cleanup_scoped(self)


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
            check=False,
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
            argv, capture_output=True, text=True, timeout=90, env=self.env, check=False
        )
        self.assertEqual(opened.returncode, 0, opened.stdout + opened.stderr)
        before = [
            json.loads(line)
            for line in opened.stdout.splitlines()
            if line.startswith("{") and '"pid"' in line
        ][-1]
        self.assertEqual(before["pid"], self.command("health")["pid"])
        self.assert_state()

    # Regression, issue #133: auto-launch must open successfully and retain
    # browser state after its launcher PTY exits.
    def test_pty_chain_retains_auto_launched_browser_state(self):
        self.pty_chain(["--auto-launch"])

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

    # Regression, Council #4728: running CLI tests closed another task's browser.
    def test_harness_startup_and_cleanup_preserve_unrelated_browser(self):
        assert_harness_cleanup_scoped(self)  # Never reproduce with a real global kill.
        self.command("open", self.url)
        self.command("eval-js", self.seed())
        before = self.command("health")
        helpers = Path(__file__).with_name("helpers.sh")
        with tempfile.TemporaryDirectory() as caller_directory:
            caller_state = Path(caller_directory) / "state-unrelated.json"
            caller_state.write_text('{"owner":"sentinel"}')
            result = subprocess.run(
                [
                    "bash",
                    "-e",
                    "-c",
                    (
                        'source "$1"; preflight "$2"; '
                        'printf "OWNED_SESSION=%s\nOWNED_DIRECTORY=%s\n" "$SPEL_SESSION" "$TEST_TMP_DIR"; '
                        '"$SPEL" --json health; "$SPEL" state save; '
                        '"$SPEL" state clear --all; cleanup'
                    ),
                    "harness-isolation",
                    str(helpers),
                    self.url,
                ],
                capture_output=True,
                text=True,
                timeout=60,
                cwd=caller_directory,
                env={**self.env, "SPEL": self.binary, "SPEL_SESSION": self.session},
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(caller_state.read_text(), '{"owner":"sentinel"}')
        ownership = dict(
            line.split("=", 1)
            for line in result.stdout.splitlines()
            if line.startswith("OWNED_")
        )
        owned = ownership["OWNED_SESSION"]
        self.assertNotEqual(owned, self.session)
        self.assertNotEqual(owned, "default")
        self.assertFalse(Path(ownership["OWNED_DIRECTORY"]).exists())
        self.assertNotEqual(Path(ownership["OWNED_DIRECTORY"]), Path(caller_directory))
        owned_health = next(
            json.loads(line)
            for line in result.stdout.splitlines()
            if line.startswith("{") and '"pid"' in line
        )
        self.assertEqual(owned_health["status"], "ok")
        self.assertNotEqual(owned_health["pid"], before["pid"])
        closed = subprocess.run(
            [self.binary, "--session", owned, "--json", "health"],
            capture_output=True,
            text=True,
            timeout=45,
            env=self.env,
            check=False,
        )
        self.assertEqual(closed.returncode, 1, closed.stdout + closed.stderr)
        self.assertEqual(json.loads(closed.stdout)["status"], "down")
        self.assertEqual(before["pid"], self.command("health")["pid"])
        self.assert_state()

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
