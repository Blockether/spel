"""Keep the published install command on the SDK's approved-version path."""

import ast
import re
import shlex
import tomllib
from pathlib import Path
from unittest.mock import Mock

from blockether.vis import extension_package

PROJECT = Path(__file__).resolve().parents[1]
README = (PROJECT / "README.md").read_text()


def test_readme_install_selects_the_published_package_version(tmp_path, monkeypatch):
    # A release tag passed as --revision failed before admission in vis-spel 0.1.2.
    command = next(
        line
        for line in README.splitlines()
        if line.startswith("vis-agent extension install ")
    )
    args = shlex.split(command)
    assert args[-1] == "--trust"
    options = dict(zip(args[4:-1:2], args[5:-1:2], strict=True))
    version = tomllib.loads((PROJECT / "pyproject.toml").read_text())["project"][
        "version"
    ]
    release = {"version": version, "revision": "a" * 40}
    releases = Mock(return_value=[release])
    admit = Mock(return_value=release)
    monkeypatch.setattr(extension_package, "_releases", releases)
    monkeypatch.setattr(extension_package, "_admit", admit)

    result = extension_package.install(
        args[3],
        tmp_path,
        trust=True,
        subdirectory=options.get("--subdirectory", ""),
        revision=options.get("--revision"),
        version=options.get("--version"),
    )

    assert result == release
    assert options["--version"] == version
    assert "--revision" not in options
    releases.assert_called_once_with(
        "https://github.com/Blockether/spel", "extensions/vis-spel"
    )
    admit.assert_called_once_with(
        args[3], tmp_path, "extensions/vis-spel", release["revision"], None, release
    )


def test_readme_python_examples_compile():
    for code in re.findall(r"```python\n(.*?)```", README, re.DOTALL):
        compile(code, "README.md", "exec", ast.PyCF_ALLOW_TOP_LEVEL_AWAIT)
