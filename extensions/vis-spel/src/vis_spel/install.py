"""Verified, explicit installation of official Spel release assets."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.request import Request, urlopen

DEFAULT_VERSION = "0.9.38"
MIN_VERSION = (0, 9, 33)
MAX_BINARY = 256 * 1024 * 1024


@dataclass(frozen=True)
class Release:
    """A supported stable native Spel release, not a vis-spel package release."""

    version: str
    url: str
    published_at: str | None


@dataclass(frozen=True)
class ReleasePage:
    """Filtered GitHub page. Follow next_page even when releases is empty."""

    releases: tuple[Release, ...]
    page: int
    next_page: int | None


def _metadata(path: str) -> tuple[dict | list, str]:
    request = Request(
        f"https://api.github.com/repos/Blockether/spel/releases{path}",
        headers={"User-Agent": "vis-spel", "Accept": "application/vnd.github+json"},
    )
    with urlopen(request, timeout=30) as response:
        payload = response.read(1024 * 1024 + 1)
        link = response.headers.get("Link", "")
    if len(payload) > 1024 * 1024:
        raise RuntimeError("Release metadata exceeds 1 MiB")
    return json.loads(payload), link


def list_releases(page: int, per_page: int) -> ReleasePage:
    """Read one GitHub page, excluding extension tags and unsupported native versions."""
    if type(page) is not int or page < 1:
        raise ValueError("page must be a positive integer")
    if type(per_page) is not int or not 1 <= per_page <= 100:
        raise ValueError("per_page must be an integer between 1 and 100")
    payload, link = _metadata(f"?per_page={per_page}&page={page}")
    if not isinstance(payload, list) or any(
        not isinstance(item, dict) for item in payload
    ):
        raise RuntimeError("GitHub returned invalid release metadata")
    releases = []
    for item in payload:
        tag = item.get("tag_name", "")
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            continue
        version = tag[1:]
        if (
            item.get("draft")
            or item.get("prerelease")
            or tuple(map(int, version.split("."))) < MIN_VERSION
        ):
            continue
        releases.append(
            Release(
                version,
                f"https://github.com/Blockether/spel/releases/tag/{tag}",
                item.get("published_at"),
            )
        )
    return ReleasePage(
        tuple(releases), page, page + 1 if 'rel="next"' in link else None
    )


def asset_name(system: str, machine: str) -> str:
    """Resolve only platforms with an official native release."""
    arch = {
        "x86_64": "amd64",
        "amd64": "amd64",
        "aarch64": "arm64",
        "arm64": "arm64",
    }.get(machine.lower())
    names = {
        ("Linux", "amd64"): "spel-linux-amd64",
        ("Linux", "arm64"): "spel-linux-arm64",
        ("Darwin", "arm64"): "spel-macos-arm64",
        ("Windows", "amd64"): "spel-windows-amd64.exe",
    }
    try:
        return names[system, arch]
    except KeyError:
        raise ValueError(f"No official Spel binary for {system}/{machine}") from None


def download(home: Path, version: str) -> Path:
    """Verify the GitHub release digest before atomically admitting an executable."""
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("version must be a stable version such as 0.9.33")
    if tuple(map(int, version.split("."))) < MIN_VERSION:
        raise ValueError(
            "Spel 0.9.33 or newer is required; the browser bridge is not supported"
        )
    name = asset_name(platform.system(), platform.machine())
    release, _ = _metadata(f"/tags/v{version}")
    if (
        release.get("tag_name") != f"v{version}"
        or release.get("draft")
        or release.get("prerelease")
    ):
        raise RuntimeError("GitHub did not return the requested stable release")
    asset = next(
        (item for item in release.get("assets", []) if item.get("name") == name), None
    )
    if not asset or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or ""):
        raise RuntimeError("Release asset has no SHA-256 digest; installation refused")
    expected_url = (
        f"https://github.com/Blockether/spel/releases/download/v{version}/{name}"
    )
    if asset.get("browser_download_url") != expected_url:
        raise RuntimeError("Release asset URL does not match the official release")
    size = asset.get("size")
    if type(size) is not int or not 0 < size <= MAX_BINARY:
        raise RuntimeError("Release asset size is invalid")
    destination = home / "bin" / version / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    digest = asset["digest"][7:]
    if destination.is_file():
        with destination.open("rb") as existing:
            actual = hashlib.file_digest(existing, "sha256").hexdigest()
        if actual != digest:
            raise RuntimeError(
                "Existing managed binary has a different SHA-256; remove it explicitly before reinstalling"
            )
        return destination
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=destination.parent, delete=False
        ) as output:
            temporary = Path(output.name)
            total, checksum, deadline = 0, hashlib.sha256(), time.monotonic() + 180
            with urlopen(
                Request(expected_url, headers={"User-Agent": "vis-spel"}), timeout=30
            ) as response:
                if not response.url.startswith("https://"):
                    raise RuntimeError("Release download left HTTPS")
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > size or time.monotonic() > deadline:
                        raise RuntimeError(
                            "Release download exceeded its size or time limit"
                        )
                    checksum.update(chunk)
                    output.write(chunk)
            if total != size or checksum.hexdigest() != digest:
                raise RuntimeError(
                    "Release download failed SHA-256 or size verification"
                )
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(0o755)
        os.replace(temporary, destination)
        return destination
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
