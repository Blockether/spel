"""Vis #203: discovery uses the SDK, before installation and without browser IO."""

import subprocess
from dataclasses import FrozenInstanceError

import blockether.vis.extension as vis
import pytest

from vis_spel import Spel


def _tool(spel: Spel, name: str) -> vis.ToolSpec:
    """Return one tool contract; Spel.spec(name) can also return a namespace."""
    spec = spel.spec(name)
    assert isinstance(spec, vis.ToolSpec)
    return spec


def test_catalog_is_available_without_registration_or_configuration(
    tmp_path, monkeypatch
):
    def forbidden(*args, **kwargs):
        pytest.fail("Discovery attempted IO")

    home = tmp_path / "not-created"
    spel = Spel(home)
    monkeypatch.setattr(spel, "_db", forbidden)
    monkeypatch.setattr(subprocess, "Popen", forbidden)
    namespaces = spel.spec()
    assert isinstance(namespaces, tuple)
    (namespace,) = namespaces
    assert isinstance(namespace, vis.NamespaceSpec)
    assert namespace.name == "spel"
    assert len(namespace.members) == 18
    assert not hasattr(spel, "native_help")
    assert _tool(spel, "spel.prepare_profile").returns.name == "BrowserProfile"
    assert _tool(spel, "spel.prepare_profile").tag == "mutation"
    assert _tool(spel, "spel.reserve").tag == "mutation"
    assert _tool(spel, "spel.spec").tag == "observation"
    assert _tool(spel, "spel.releases").tag == "observation"
    # Regression, issue #137: viewport help required a separate native tool.
    command_help = spel.help("spel.command").text
    assert '["set", "viewport", "361", "800"]' in command_help
    assert "native_help" not in command_help
    assert _tool(spel, "spel.releases").returns.name == "ReleasePage"
    assert "next_page" in spel.help("spel.releases").text
    snapshot = _tool(spel, "spel.snapshot")
    assert snapshot.parameters[1].kind == "keyword_only"
    assert "session" in [parameter.name for parameter in snapshot.parameters]
    assert snapshot.returns.name == "BrowserResult"
    document = spel.help("spel.snapshot")
    assert isinstance(document, vis.HelpDocument)
    assert "spel.snapshot(" in document.text
    assert "snapshot" in document.text
    with pytest.raises(FrozenInstanceError):
        snapshot.name = "changed"  # pyright: ignore[reportAttributeAccessIssue]
    assert not home.exists()


def test_catalog_help_and_registry_share_public_names_and_tags():
    symbol = vis.Symbol(Spel(), name="spel")
    public = [item for item in symbol._spec()["methods"] if not item["hidden"]]
    vis.testing.assert_catalog(
        vis.Catalog([symbol]),
        names=[item["contract"]["name"] for item in public],
        mutations=[
            "spel.install",
            "spel.prepare_profile",
            "spel.reserve",
            "spel.connect",
            "spel.open",
            "spel.command",
            "spel.evaluate",
            "spel.sci",
            "spel.screenshot",
            "spel.cancel",
            "spel.release",
        ],
    )
    spel = symbol.fn
    assert isinstance(spel, Spel)
    for item in public:
        assert item["doc"] in spel.help(item["contract"]["name"]).text
        assert getattr(Spel, item["name"]).__vis_symbol_activity__ is not None
    for name in ("unknown", "snapshot", "spel._db"):
        with pytest.raises(ValueError):
            spel.spec(name)
        with pytest.raises(ValueError, match=r"spel.help.*spel.command"):
            spel.help(name)
    # Regression, issue #137: a guessed viewport tool gave no usable help route.
    with pytest.raises(ValueError, match=r"spel.help.*spel.command"):
        spel.help("spel.viewport")
    with pytest.raises(TypeError):
        spel.spec(42)  # pyright: ignore[reportArgumentType]
    with pytest.raises(TypeError):
        spel.help(42)  # pyright: ignore[reportArgumentType]
