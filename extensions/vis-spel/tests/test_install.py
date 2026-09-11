import hashlib
import io
import json

import pytest

from vis_spel import install


class Response(io.BytesIO):
    url = "https://release-assets.githubusercontent.com/asset"


@pytest.fixture
def release(monkeypatch):
    binary = b"verified executable"
    url = (
        "https://github.com/Blockether/spel/releases/download/v0.9.33/spel-macos-arm64"
    )
    asset = {
        "name": "spel-macos-arm64",
        "digest": "sha256:" + hashlib.sha256(binary).hexdigest(),
        "size": len(binary),
        "browser_download_url": url,
    }
    data = {
        "tag_name": "v0.9.33",
        "draft": False,
        "prerelease": False,
        "assets": [asset],
    }
    monkeypatch.setattr(install.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(install.platform, "machine", lambda: "arm64")
    calls = []

    def request(req, **kwargs):
        calls.append(req.full_url)
        return Response(
            json.dumps(data).encode() if "api.github.com" in req.full_url else binary
        )

    monkeypatch.setattr(install, "urlopen", request)
    return data, asset, calls


def test_verified_install_is_atomic_and_repeatable(tmp_path, release):
    _, _, calls = release
    target = install.download(tmp_path, "0.9.33")
    assert target.read_bytes() == b"verified executable"
    assert target.stat().st_mode & 0o111
    assert install.download(tmp_path, "0.9.33") == target
    assert sum("releases/download" in call for call in calls) == 1
    assert list(target.parent.iterdir()) == [target]


@pytest.mark.parametrize(
    "field,value",
    [
        ("digest", None),
        ("digest", "sha256:" + "0" * 64),
        ("size", 1),
        ("size", install.MAX_BINARY + 1),
        ("browser_download_url", "https://example.com/other"),
    ],
)
def test_bad_release_never_admits_executable(tmp_path, release, field, value):
    _, asset, _ = release
    asset[field] = value
    with pytest.raises(RuntimeError):
        install.download(tmp_path, "0.9.33")
    assert not list(tmp_path.rglob("spel-macos-arm64"))
    assert not [path for path in tmp_path.rglob("*") if path.is_file()]


def test_corrupt_existing_binary_is_not_overwritten(tmp_path, release):
    target = install.download(tmp_path, "0.9.33")
    target.write_bytes(b"unexpected")
    with pytest.raises(RuntimeError, match="remove it explicitly"):
        install.download(tmp_path, "0.9.33")
    assert target.read_bytes() == b"unexpected"


@pytest.mark.parametrize(
    "system,machine,name",
    [
        ("Linux", "x86_64", "spel-linux-amd64"),
        ("Linux", "aarch64", "spel-linux-arm64"),
        ("Darwin", "arm64", "spel-macos-arm64"),
        ("Windows", "AMD64", "spel-windows-amd64.exe"),
    ],
)
def test_platform_assets(system, machine, name):
    assert install.asset_name(system, machine) == name


def test_unsupported_platform():
    with pytest.raises(ValueError):
        install.asset_name("Darwin", "x86_64")


@pytest.mark.parametrize("version", ["latest", "../main", "1.2.3?x", "1.2.3\n"])
def test_version_is_pinned(tmp_path, version):
    with pytest.raises(ValueError):
        install.download(tmp_path, version)
