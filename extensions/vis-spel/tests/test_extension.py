import importlib.util
from pathlib import Path

import blockether.vis.extension as vis
import pytest

from vis_spel import BrowserResult, Installation, Reservation, Spel
from vis_spel.install import Release, ReleasePage


@pytest.fixture
def extension(monkeypatch):
    # Registration regression: the entrypoint called removed vis.register.
    monkeypatch.setattr(vis, "_registration", {"spec": None})
    registered = []
    register_extension = vis.register_extension

    def register(declaration):
        register_extension(declaration)
        registered.append(declaration)

    monkeypatch.setattr(vis, "register_extension", register)
    spec = importlib.util.spec_from_file_location(
        "spel_entry", Path(__file__).parents[1] / "extension.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert len(registered) == 1
    assert vis._registration["spec"]["name"] == "vis-spel"
    return registered[0], module


def test_registration_and_all_activity_states(extension):
    declaration, _ = extension
    assert declaration.name == "vis-spel"
    assert declaration.alias == "spel"
    contract = declaration.symbols[0].contract
    assert contract["name"] == "spel"
    assert len(contract["members"]) == 18
    for member in contract["members"]:
        method = getattr(Spel, member["name"].split(".")[-1])
        activity = method.__vis_symbol_activity__
        assert isinstance(activity, vis.Activity)
        assert activity.label[0].isupper()
        assert not activity.label.isupper()
        for phase in ["start", "failure"]:
            result = activity.render(
                phase=phase, error=RuntimeError("specific failure")
            )
            assert isinstance(result, vis.ActivityPresentation)
            if phase == "failure":
                assert "specific failure" in str(result.content)
        assert isinstance(
            activity.render(
                phase="success", result=BrowserResult("agent-test", "open", {})
            ),
            vis.ActivityPresentation,
        )
    assert Spel.installed.__vis_symbol_activity__.show_start is False
    assert Spel.native_help.__vis_symbol_activity__.show_start is False
    assert Spel.reserve.__vis_symbol_activity__.show_start is False
    assert Spel.health.__vis_symbol_activity__.show_start is False
    assert Spel.install.__vis_symbol_activity__.show_start is True
    assert Spel.releases.__vis_symbol_activity__.show_start is True
    assert Spel.evaluate.__vis_symbol_tag__ == "mutation"


# Regression, issue #137: native help needs a readable Activity, not a generic result.
def test_native_help_activity_shows_cli_syntax(extension):
    render = Spel.native_help.__vis_symbol_activity__.render
    document = vis.HelpDocument("spel set --help", "set viewport <width> <height>")
    presentation = render(phase="success", result=document)
    assert presentation.summary == "spel set --help"
    assert "set viewport <width> <height>" in str(presentation.content)


def test_activity_preserves_failure_empty_and_bounded_data(extension):
    render = Spel.snapshot.__vis_symbol_activity__.render
    assert "No managed" in render(phase="success", result=None).summary
    assert (
        "0.9.33"
        in render(
            phase="success", result=Installation("0.9.33", "/binary", True)
        ).summary
    )
    assert (
        "Reserved example"
        in render(
            phase="success", result=Reservation("a", "agent-test", "example")
        ).summary
    )
    health = render(
        phase="success",
        result=BrowserResult("agent-test", "health", {"status": "stopped"}),
    )
    assert "stopped" in health.summary
    output = render(
        phase="success",
        result=BrowserResult(
            "agent-test", "snapshot", {"snapshot": "ą" * 40000}, "warning"
        ),
    )
    assert "excerpt" in str(output.content)
    assert "warning" in str(output.content)
    assert len(str(output.to_wire()).encode()) < 32768
    failure = render(phase="failure", error=RuntimeError("ą" * 40000))
    assert len(str(failure.to_wire()).encode()) < 32768
    assert "excerpt" in str(failure.content)


def test_release_activity_shows_versions_count_and_pagination(extension):
    render = Spel.releases.__vis_symbol_activity__.render
    result = ReleasePage(
        (
            Release(
                "0.9.34",
                "https://github.com/Blockether/spel/releases/tag/v0.9.34",
                None,
            ),
        ),
        1,
        2,
    )
    presentation = render(phase="success", result=result)
    assert presentation.summary == "1 release; next page 2"
    assert "0.9.34" in str(presentation.content)
    assert result.releases[0].url in str(presentation.content)
    empty = render(phase="success", result=ReleasePage((), 2, None))
    assert empty.summary == "0 releases"
    assert empty.content == ()
    assert (
        "next page 3" in render(phase="success", result=ReleasePage((), 2, 3)).summary
    )
