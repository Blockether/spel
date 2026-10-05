"""Pyright standard checks for the sources and tests of this extension."""

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

pytest.importorskip("pyright")

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    ROOT / "extension.py",
    ROOT / "src",
    ROOT / "tests",
)


def _where(item):
    line = item["range"]["start"]["line"] + 1
    return f"{os.path.relpath(item['file'], ROOT)}:{line}: {item['message']}"


def test_sources_and_tests_pass_pyright_standard(tmp_path):
    # Regression, issue Blockether/vis#313: a tag from the binding table was a `str`,
    # so Pyright refused it for `vis.method(tag=...)`.
    config = tmp_path / "pyrightconfig.json"
    config.write_text(
        json.dumps(
            {
                "typeCheckingMode": "standard",
                "pythonVersion": "3.11",
                "extraPaths": [str(ROOT / "src")],
            }
        )
    )
    command = [
        sys.executable,
        "-m",
        "pyright",
        "--project",
        str(config),
        "--pythonpath",
        sys.executable,
        "--warnings",
        "--outputjson",
        *(str(target) for target in TARGETS),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    assert result.stdout, result.stderr
    found = [_where(item) for item in json.loads(result.stdout)["generalDiagnostics"]]
    assert found == [], "\n".join(found)
    assert result.returncode == 0, result.stderr
