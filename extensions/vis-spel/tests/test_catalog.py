"""Vis #203: discovery uses the SDK, before installation and without browser IO."""

import subprocess
from dataclasses import FrozenInstanceError

import blockether.vis.extension as vis
import pytest

from vis_spel import Spel


def test_catalog_is_available_without_registration_or_configuration(
    tmp_path, monkeypatch
):
    def forbidden(*args, **kwargs):
        pytest.fail("Discovery attempted IO")

    home = tmp_path / "not-created"
    spel = Spel(home)
    monkeypatch.setattr(spel, "_db", forbidden)
    monkeypatch.setattr(subprocess, "Popen", forbidden)
    (namespace,) = spel.spec()
    assert isinstance(namespace, vis.NamespaceSpec)
    assert namespace.name == "spel"
    assert len(namespace.members) == 17
    assert spel.spec("spel.reserve").tag == "mutation"
    assert spel.spec("spel.spec").tag == "observation"
    assert spel.spec("spel.releases").tag == "observation"
    assert spel.spec("spel.releases").returns.name == "ReleasePage"
    assert "next_page" in spel.help("spel.releases").text
    snapshot = spel.spec("spel.snapshot")
    assert snapshot.parameters[1].kind == "keyword_only"
    assert "session" in [parameter.name for parameter in snapshot.parameters]
    assert snapshot.returns.name == "BrowserResult"
    document = spel.help("spel.snapshot")
    assert isinstance(document, vis.HelpDocument)
    assert "spel.snapshot(" in document.text
    assert "snapshot" in document.text
    with pytest.raises(FrozenInstanceError):
        snapshot.name = "changed"
    assert not home.exists()


def test_catalog_help_and_registry_share_public_names_and_tags():
    symbol = vis.Symbol(Spel(), name="spel")
    public = [item for item in symbol._spec()["methods"] if not item["hidden"]]
    vis.testing.assert_catalog(
        vis.Catalog([symbol]),
        names=[item["contract"]["name"] for item in public],
        mutations=[
            "spel.install",
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
    for item in public:
        assert item["doc"] in spel.help(item["contract"]["name"]).text
        assert getattr(Spel, item["name"]).__vis_symbol_activity__ is not None
    for name in ("unknown", "snapshot", "spel._db"):
        with pytest.raises(ValueError):
            spel.spec(name)
        with pytest.raises(ValueError):
            spel.help(name)
    with pytest.raises(TypeError):
        spel.spec(42)
