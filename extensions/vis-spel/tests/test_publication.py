"""Opt-in checks against the public approved catalog; no global install changes."""

import os
import tomllib
from pathlib import Path

import pytest
from blockether.vis import extension_package

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.environ.get("SPEL_PUBLICATION") != "1",
        reason="Set SPEL_PUBLICATION=1 after catalog publication",
    ),
]


def test_published_extension_install_update_and_rollback(tmp_path):
    project = Path(__file__).resolve().parents[1]
    version = tomllib.loads((project / "pyproject.toml").read_text())["project"][
        "version"
    ]
    directory = tmp_path / "extensions"

    installed = extension_package.install(
        "https://github.com/Blockether/spel",
        directory,
        subdirectory="extensions/vis-spel",
        version=version,
        trust=True,
    )
    assert installed["version"] == version
    assert installed["name"] == "vis-spel"
    assert installed["mode"] == "github"
    metadata = Path(installed["path"]) / "pyproject.toml"
    assert tomllib.loads(metadata.read_text())["project"]["version"] == version

    older = extension_package.rollback(
        "vis-spel", directory, version="0.1.1", trust=True
    )
    assert older["version"] == "0.1.1"
    assert older["revision"] != installed["revision"]
    assert tomllib.loads(metadata.read_text())["project"]["version"] == "0.1.1"

    updated = extension_package.update("vis-spel", directory, trust=True)
    assert updated["version"] == version
    assert updated["revision"] == installed["revision"]
    assert tomllib.loads(metadata.read_text())["project"]["version"] == version
