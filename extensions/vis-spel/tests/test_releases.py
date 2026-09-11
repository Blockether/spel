"""Release discovery lists native versions without changing the installation."""

import io
import json
from dataclasses import FrozenInstanceError
from urllib.error import HTTPError

import pytest

import vis_spel
from vis_spel import Spel, install


class Response(io.BytesIO):
    def __init__(self, payload, link=""):
        super().__init__(json.dumps(payload).encode())
        self.headers = {"Link": link}


def test_releases_filter_native_versions_and_preserve_pagination(tmp_path, monkeypatch):
    calls = []
    payload = [
        {"tag_name": "v0.9.34", "published_at": "2026-09-11T12:00:00Z"},
        {"tag_name": "vis-spel/v0.1.2"},
        {"tag_name": "v0.9.33"},
        {"tag_name": "v0.9.32"},
        {"tag_name": "v0.9.35", "draft": True},
        {"tag_name": "v0.9.36", "prerelease": True},
        {"tag_name": "v0.9.37-rc.1"},
    ]

    def request(req, **kwargs):
        calls.append((req.full_url, kwargs))
        return Response(
            payload,
            '<https://api.github.com/repos/Blockether/spel/releases?per_page=10&page=3>; rel="next"',
        )

    def forbidden(*args, **kwargs):
        pytest.fail("Release discovery attempted installation or browser IO")

    home = tmp_path / "not-created"
    client = Spel(home)
    monkeypatch.setattr(install, "urlopen", request)
    monkeypatch.setattr(client, "_db", forbidden)
    monkeypatch.setattr(vis_spel, "_execute", forbidden)
    result = client.releases(page=2, per_page=10)
    assert [release.version for release in result.releases] == ["0.9.34", "0.9.33"]
    assert result.page == 2
    assert result.next_page == 3
    assert (
        result.releases[0].url
        == "https://github.com/Blockether/spel/releases/tag/v0.9.34"
    )
    assert result.releases[0].published_at == "2026-09-11T12:00:00Z"
    assert result.releases[1].published_at is None
    with pytest.raises(FrozenInstanceError):
        result.releases[0].version = "changed"
    assert calls == [
        (
            "https://api.github.com/repos/Blockether/spel/releases?per_page=10&page=2",
            {"timeout": 30},
        )
    ]
    assert not home.exists()


@pytest.mark.parametrize(
    "link,next_page", [("", None), ('<https://api.github.com/next>; rel="next"', 2)]
)
def test_empty_filtered_page_keeps_next_page(tmp_path, monkeypatch, link, next_page):
    monkeypatch.setattr(
        install,
        "urlopen",
        lambda *a, **k: Response([{"tag_name": "vis-spel/v0.1.2"}], link),
    )
    result = Spel(tmp_path).releases()
    assert result.releases == ()
    assert result.next_page == next_page


@pytest.mark.parametrize(
    "kwargs",
    [
        {"page": 0},
        {"page": -1},
        {"page": True},
        {"page": "1"},
        {"per_page": 0},
        {"per_page": 101},
        {"per_page": False},
        {"per_page": 2.5},
    ],
)
def test_invalid_pagination_does_not_request(tmp_path, monkeypatch, kwargs):
    def forbidden(*args, **kwargs):
        pytest.fail("Invalid pagination performed a request")

    monkeypatch.setattr(install, "urlopen", forbidden)
    with pytest.raises(ValueError):
        Spel(tmp_path).releases(**kwargs)


@pytest.mark.parametrize("payload", [{"message": "bad response"}, [None]])
def test_invalid_metadata_is_reported(tmp_path, monkeypatch, payload):
    monkeypatch.setattr(install, "urlopen", lambda *a, **k: Response(payload))
    with pytest.raises(RuntimeError, match="release metadata"):
        Spel(tmp_path).releases()


def test_oversized_metadata_is_refused(tmp_path, monkeypatch):
    monkeypatch.setattr(
        install, "urlopen", lambda *a, **k: Response("x" * (1024 * 1024))
    )
    with pytest.raises(RuntimeError, match="exceeds 1 MiB"):
        Spel(tmp_path).releases()


def test_github_error_is_not_retried(tmp_path, monkeypatch):
    calls = []

    def request(req, **kwargs):
        calls.append(req.full_url)
        raise HTTPError(req.full_url, 403, "API rate limit exceeded", {}, None)

    monkeypatch.setattr(install, "urlopen", request)
    with pytest.raises(HTTPError, match="403"):
        Spel(tmp_path).releases()
    assert len(calls) == 1
