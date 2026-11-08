#!/usr/bin/env python3
"""Download the pinned official Gradle distribution and verify its SHA-256.

The wrapper asks https://services.gradle.org for gradle-8.11.1-bin.zip. That
URL redirects to the official GitHub release, which has been returning HTTP 504
before any bytes arrive. This bootstrap retries that official URL, then fetches
the same release asset from gradle/gradle-distributions. The zip is kept only in
the local wrapper directory. Nothing is uploaded.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urljoin, urlsplit, urlunsplit

PINNED_URL = "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
PINNED_SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
PINNED_NAME = "gradle-8.11.1-bin.zip"
PINNED_VERSION = "8.11.1"
PINNED_TAG = "v8.11.1"
PINNED_BROWSER_URL = (
    "https://github.com/gradle/gradle-distributions/releases/download/"
    "v8.11.1/gradle-8.11.1-bin.zip"
)
PINNED_API_RELEASE = (
    "https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v8.11.1"
)
WRAPPER_DIST_HASH = "bpt9gzteqjrbo1mjrsomdt32c"
BUDGET_SECONDS = 8 * 60
MAX_TIME_SECONDS = 180
TRANSIENT_STATUS = {408, 429, 500, 502, 503, 504}
USER_AGENT = "SvodkaLocalBootstrap/1.0"


class TransientDownloadError(RuntimeError):
    pass


class RejectedUrl(RuntimeError):
    pass


def wrapper_dist_hash(url: str) -> str:
    number = int.from_bytes(hashlib.md5(url.encode("utf-8")).digest(), "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    if number == 0:
        return "0"
    encoded = ""
    while number:
        number, remainder = divmod(number, 36)
        encoded = alphabet[remainder] + encoded
    return encoded


def allowed(url: str) -> None:
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.username or parts.password:
        raise RejectedUrl(f"адрес не https без секрета в URL: {public_url(url)}")
    if parts.port not in (None, 443):
        raise RejectedUrl(f"нестандартный порт: {public_url(url)}")
    host = (parts.hostname or "").lower()
    path = parts.path
    asset_prefix = "/repos/gradle/gradle-distributions/releases/assets/"
    release_path = "/gradle/gradle-distributions/releases/download/v8.11.1/gradle-8.11.1-bin.zip"
    distribution_path = "/distributions/gradle-8.11.1-bin.zip"
    if host in {"services.gradle.org", "downloads.gradle.org"} and path == distribution_path:
        return
    if host == "github.com" and path == release_path:
        return
    if host == "api.github.com" and path == "/repos/gradle/gradle-distributions/releases/tags/v8.11.1":
        return
    if host == "api.github.com" and path.startswith(asset_prefix) and path[len(asset_prefix) :].isdigit():
        return
    if host in {
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
    }:
        return
    raise RejectedUrl(f"адрес вне официальной раздачи Gradle: {public_url(url)}")


def public_url(url: str) -> str:
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


def load_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().replace("\\:", ":").replace("\\\\", "\\")
    return values


def require_pinned_properties(root: Path) -> None:
    props = load_properties(root / "gradle" / "wrapper" / "gradle-wrapper.properties")
    if props.get("distributionUrl") != PINNED_URL:
        raise SystemExit("distributionUrl должен быть официальным адресом Gradle 8.11.1.")
    if props.get("distributionSha256Sum") != PINNED_SHA256:
        raise SystemExit("distributionSha256Sum не совпадает с официальным отпечатком 8.11.1.")
    if wrapper_dist_hash(PINNED_URL) != WRAPPER_DIST_HASH:
        raise SystemExit("хеш каталога wrapper не совпал с проверкой.")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def curl_once(url: str, body: Path, accept: str) -> tuple[int, str]:
    allowed(url)
    header = body.with_suffix(body.suffix + ".hdr")
    command = [
        "curl",
        "--silent",
        "--show-error",
        "--http1.1",
        "--connect-timeout",
        "15",
        "--max-time",
        str(MAX_TIME_SECONDS),
        "--speed-limit",
        "1024",
        "--speed-time",
        "30",
        "--retry",
        "0",
        "-A",
        USER_AGENT,
        "-H",
        f"Accept: {accept}",
        "-D",
        str(header),
        "-o",
        str(body),
        "-w",
        "%{http_code}",
        url,
    ]
    try:
        completed = subprocess.run(command, check=False, text=True, capture_output=True)
    except FileNotFoundError as error:
        raise SystemExit("Нужен curl.") from error
    status_text = (completed.stdout or "").strip()
    status = int(status_text) if status_text.isdigit() else 0
    location = ""
    if header.exists():
        for line in header.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.lower().startswith("location:"):
                location = line.split(":", 1)[1].strip()
        header.unlink(missing_ok=True)
    if completed.returncode != 0 and status == 0:
        message = (completed.stderr or "").strip().splitlines()
        detail = message[-1] if message else f"curl {completed.returncode}"
        if completed.returncode in {6, 7, 18, 28, 35, 52, 56}:
            raise TransientDownloadError(detail)
        raise RuntimeError(detail)
    return status, location


def download_following(start: str, destination: Path, accept: str, expect_zip: bool = False) -> str:
    current = start
    with tempfile.TemporaryDirectory(prefix="svodka-gradle-hop-") as temporary:
        hop_body = Path(temporary) / "body"
        for _ in range(6):
            status, location = curl_once(current, hop_body, accept)
            print(f"HTTP {status} {public_url(current)}", flush=True)
            if status == 200:
                size = hop_body.stat().st_size
                with hop_body.open("rb") as handle:
                    magic = handle.read(2)
                if expect_zip and (size < 10_000_000 or magic != b"PK"):
                    raise TransientDownloadError(f"ответ не похож на zip ({size} байт) {public_url(current)}")
                if size == 0:
                    raise TransientDownloadError(f"пустой ответ {public_url(current)}")
                destination.parent.mkdir(parents=True, exist_ok=True)
                os.replace(hop_body, destination)
                return current
            if status in {301, 302, 303, 307, 308}:
                if not location:
                    raise RuntimeError("редирект без адреса")
                current = urljoin(current, location)
                allowed(current)
                hop_body.unlink(missing_ok=True)
                continue
            if status in TRANSIENT_STATUS or status == 0:
                raise TransientDownloadError(f"HTTP {status} {public_url(current)}")
            raise RuntimeError(f"HTTP {status} {public_url(current)}")
    raise RuntimeError("слишком много редиректов")


def official_asset_url() -> str:
    with tempfile.TemporaryDirectory(prefix="svodka-gradle-api-") as temporary:
        body = Path(temporary) / "release.json"
        download_following(PINNED_API_RELEASE, body, "application/vnd.github+json", expect_zip=False)
        payload = json.loads(body.read_text(encoding="utf-8"))
    if payload.get("tag_name") != PINNED_TAG:
        raise RuntimeError("тег релиза не v8.11.1")
    matches = [item for item in payload.get("assets", []) if item.get("name") == PINNED_NAME]
    if len(matches) != 1:
        raise RuntimeError("в официальном релизе нет ровно одного gradle-8.11.1-bin.zip")
    asset = matches[0]
    if asset.get("browser_download_url") != PINNED_BROWSER_URL:
        raise RuntimeError("адрес файла релиза не совпал с официальным")
    asset_id = asset.get("id")
    if not isinstance(asset_id, int):
        raise RuntimeError("у файла релиза нет числового id")
    return f"https://api.github.com/repos/gradle/gradle-distributions/releases/assets/{asset_id}"


def fetch_verified_zip(destination: Path) -> None:
    temporary = destination.with_suffix(".partial")
    deadline = time.monotonic() + BUDGET_SECONDS
    delay = 2
    attempt = 0
    last: Exception | None = None
    try:
        while True:
            attempt += 1
            temporary.unlink(missing_ok=True)
            try:
                print(f"официальный дистрибутив, круг {attempt}", flush=True)
                download_following(PINNED_URL, temporary, "application/octet-stream", expect_zip=True)
                break
            except TransientDownloadError as error:
                last = error
                print(f"прямой адрес не отдал файл: {error}", flush=True)
            temporary.unlink(missing_ok=True)
            try:
                asset_url = official_asset_url()
                download_following(asset_url, temporary, "application/octet-stream", expect_zip=True)
                break
            except (TransientDownloadError, RuntimeError) as error:
                last = error
                print(f"файл релиза не отдал байты: {error}", flush=True)
            remaining = deadline - time.monotonic()
            if remaining < 1:
                raise TransientDownloadError(f"бюджет {BUDGET_SECONDS} с исчерпан: {last}")
            pause = min(delay, remaining)
            print(f"пауза {pause:.0f} с", flush=True)
            time.sleep(pause)
            delay = min(delay * 2, 20)
        actual = sha256_file(temporary)
        if actual != PINNED_SHA256:
            raise SystemExit(
                "SHA-256 дистрибутива не совпал с официальным отпечатком Gradle 8.11.1. "
                f"Получено {actual}."
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"SHA-256 совпал: {PINNED_SHA256}", flush=True)


def distribution_zip(gradle_user_home: Path) -> Path:
    return (
        gradle_user_home
        / "wrapper"
        / "dists"
        / f"gradle-{PINNED_VERSION}-bin"
        / WRAPPER_DIST_HASH
        / PINNED_NAME
    )


def self_test(root: Path) -> None:
    require_pinned_properties(root)
    if wrapper_dist_hash(PINNED_URL) != WRAPPER_DIST_HASH:
        raise SystemExit("self-test: хеш wrapper")
    allowed(PINNED_URL)
    allowed("https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip")
    allowed(PINNED_BROWSER_URL)
    allowed(PINNED_API_RELEASE)
    allowed("https://api.github.com/repos/gradle/gradle-distributions/releases/assets/207876967")
    allowed("https://release-assets.githubusercontent.com/github-production-release-asset/1/abc")
    rejected = [
        "http://services.gradle.org/distributions/gradle-8.11.1-bin.zip",
        "https://user:pass@services.gradle.org/distributions/gradle-8.11.1-bin.zip",
        "https://example.com/gradle-8.11.1-bin.zip",
        "https://github.com/evil/gradle-distributions/releases/download/v8.11.1/gradle-8.11.1-bin.zip",
        "https://github.com/gradle/gradle-distributions/releases/download/v9.9.0/gradle-9.9.0-bin.zip",
        "https://mirrors.example/gradle-8.11.1-bin.zip",
        "https://github.com/gradle/gradle-distributions/archive/refs/tags/v8.11.1.zip",
    ]
    for url in rejected:
        try:
            allowed(url)
        except RejectedUrl:
            continue
        raise SystemExit(f"self-test: адрес должен быть отклонён: {url}")
    print("self-test: ok")


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    if "--self-test" in sys.argv[1:]:
        self_test(root)
        return
    if sys.argv[1:]:
        raise SystemExit("Лишний аргумент. Поддерживается только --self-test.")
    if shutil.which("curl") is None:
        raise SystemExit("Нужен curl.")
    require_pinned_properties(root)
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    target = distribution_zip(gradle_home)
    if target.is_file() and sha256_file(target) == PINNED_SHA256:
        print("Дистрибутив Gradle уже лежит в каталоге wrapper, SHA-256 совпал.")
        return
    fetch_verified_zip(target)
    print(f"Дистрибутив готов для wrapper: gradle-{PINNED_VERSION}-bin")


if __name__ == "__main__":
    try:
        main()
    except RejectedUrl as error:
        raise SystemExit(str(error)) from error
    except TransientDownloadError as error:
        raise SystemExit(f"Не удалось скачать официальный дистрибутив Gradle: {error}") from error
