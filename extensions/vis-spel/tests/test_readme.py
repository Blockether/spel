"""Keep the published install command on the SDK's approved-version path."""

import ast
import re
import shlex
import tomllib
from pathlib import Path
from unittest.mock import Mock

from blockether.vis import extension_package
from packaging.requirements import Requirement

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
    normalized_source = "https://github.com/blockether/spel"
    releases.assert_called_once_with(normalized_source, "extensions/vis-spel")
    admit.assert_called_once_with(
        normalized_source,
        tmp_path,
        "extensions/vis-spel",
        release["revision"],
        None,
        release,
    )


def test_readme_management_selects_the_repository_and_project_folder():
    for operation in ("versions", "update", "rollback"):
        command = next(
            line.removeprefix("# To roll back: ")
            for line in README.splitlines()
            if f"vis-agent extension {operation} " in line
        )
        args = shlex.split(command)
        assert (
            extension_package.github_repository(args[3])
            == "https://github.com/blockether/spel"
        )
        assert args[args.index("--subdirectory") + 1] == "extensions/vis-spel"


def test_registration_sdk_requirement_matches_docs_and_lock():
    # Older SDKs have no register_extension entrypoint.
    project = tomllib.loads((PROJECT / "pyproject.toml").read_text())["project"]
    requirement = next(
        dependency
        for value in project["dependencies"]
        if (dependency := Requirement(value)).name == "vis-agent"
    )
    assert "0.2.15" in requirement.specifier
    assert "0.2.14" not in requirement.specifier
    assert "Vis / `vis-agent` **0.2.15+**" in README
    locked_sdk = next(
        package
        for package in tomllib.loads((PROJECT / "uv.lock").read_text())["package"]
        if package["name"] == "vis-agent"
    )
    assert locked_sdk["version"] in requirement.specifier


def test_readme_python_examples_compile():
    for code in re.findall(r"```python\n(.*?)```", README, re.DOTALL):
        compile(code, "README.md", "exec", ast.PyCF_ALLOW_TOP_LEVEL_AWAIT)
