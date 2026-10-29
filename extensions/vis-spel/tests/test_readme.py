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


def test_browser_skill_description_explains_when_to_use_it():
    # LintLang H1.8: listing capabilities alone did not tell the agent when to load the skill.
    skill = (PROJECT / "skills/browser/SKILL.md").read_text()
    description = next(
        line.removeprefix("description: ")
        for line in skill.splitlines()
        if line.startswith("description: ")
    )
    assert description.startswith(
        "Use this skill when you need authorized browser navigation"
    )
    assert "with the Vis Spel extension." in description


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
    # SDKs before 0.2.31 reject the skill table that hides its settings switch.
    # SDK 0.2.45 pins the LiteLLM build that corporate Artifactory mirrors serve.
    project = tomllib.loads((PROJECT / "pyproject.toml").read_text())["project"]
    requirement = next(
        dependency
        for value in project["dependencies"]
        if (dependency := Requirement(value)).name == "vis-agent"
    )
    assert "0.2.45" in requirement.specifier
    assert "0.2.44" not in requirement.specifier
    assert "Vis / `vis-agent` **0.2.45+**" in README
    locked_sdk = next(
        package
        for package in tomllib.loads((PROJECT / "uv.lock").read_text())["package"]
        if package["name"] == "vis-agent"
    )
    assert locked_sdk["version"] in requirement.specifier


def test_browser_skill_follows_the_extension_setting():
    text = (PROJECT / "pyproject.toml").read_text()
    manifest = extension_package.manifest_metadata(text)
    assert manifest["skills"] == [{"path": "skills/browser", "settings": False}]


def test_readme_python_examples_compile():
    for code in re.findall(r"```python\n(.*?)```", README, re.DOTALL):
        compile(code, "README.md", "exec", ast.PyCF_ALLOW_TOP_LEVEL_AWAIT)


# Regression, vis#425: a domain tool picked its own cached binary and ran raw
# `spel --session` commands, because no example showed the managed vis.tools path.
def test_readme_domain_tool_example_uses_the_managed_reservation(tmp_path, monkeypatch):
    import json

    import blockether.vis.extension as vis
    import pytest

    import vis_spel
    from vis_spel import Spel, SpelError

    (code,) = [
        block
        for block in re.findall(r"```python\n(.*?)```", README, re.DOTALL)
        if "class Portal" in block
    ]
    spel = Spel(tmp_path)
    binary = tmp_path / "spel"
    binary.write_text("test executable")
    with spel._db() as db:
        db.execute(
            "INSERT INTO installation VALUES (1, ?, ?, 1)", ("0.9.48", str(binary))
        )
    answers = []

    def execute(binary, arguments, **kwargs):
        return answers.pop(0) if answers else (0, json.dumps({"result": None}), "")

    monkeypatch.setattr(vis_spel, "_execute", execute)
    monkeypatch.setattr(
        vis_spel, "_caller", lambda: "11111111-1111-4111-8111-111111111111"
    )
    monkeypatch.setattr(vis, "tools", Mock(spel=spel), raising=False)
    registered = []
    monkeypatch.setattr(vis, "register_extension", registered.append)
    namespace = {}
    exec(compile(code, "README.md", "exec"), namespace)
    assert [extension.name for extension in registered] == ["portal"]
    portal = namespace["Portal"]()

    session = portal.sign_in()
    # A reload or a new turn finds the same reservation, not a second profile.
    assert namespace["Portal"]().sign_in() == session
    with pytest.raises(RuntimeError, match="Finish the sign-in"):
        portal.balance(session)
    answers.append((1, json.dumps({"error": "page crashed"}), ""))
    with pytest.raises(SpelError, match="page crashed"):
        portal.balance(session)
    answers.append((0, json.dumps({"result": "42.00 EUR"}), ""))
    assert portal.balance(session) == "42.00 EUR"
    # The reservation survived the waits and failures above.
    assert [row.id for row in spel.reservations()] == [session]
    portal.finish(session)
    assert spel.reservations() == ()
