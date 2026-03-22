"""
Binary download and subprocess management for the CloudMeter sidecar.

The binary lives at ~/.cloudmeter/bin/cloudmeter-sidecar.
On first use it is downloaded from the latest GitHub Release automatically.
"""
import atexit
import json
import os
import platform
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from threading import Lock

GITHUB_REPO = os.environ.get("CLOUDMETER_REPO", "studymadara/cloudmeter")

_lock = Lock()
_process: "subprocess.Popen | None" = None
_ingest_port = 7778


def _install_dir() -> Path:
    return Path.home() / ".cloudmeter" / "bin"


def _platform_asset() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()

    if system == "linux":
        os_name = "linux"
    elif system == "darwin":
        os_name = "macos"
    elif system == "windows":
        os_name = "windows"
    else:
        raise RuntimeError(f"Unsupported OS: {system}")

    if machine in ("x86_64", "amd64"):
        arch = "x86_64"
    elif machine in ("aarch64", "arm64"):
        arch = "arm64"
    else:
        raise RuntimeError(f"Unsupported CPU architecture: {machine}")

    ext = ".exe" if system == "windows" else ""
    return f"cloudmeter-sidecar-{os_name}-{arch}{ext}"


def _binary_path() -> Path:
    ext = ".exe" if platform.system().lower() == "windows" else ""
    return _install_dir() / f"cloudmeter-sidecar{ext}"


def _download() -> None:
    asset = _platform_asset()
    binary = _binary_path()

    api_url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
    try:
        req = urllib.request.Request(api_url, headers={"User-Agent": "cloudmeter-python"})
        with urllib.request.urlopen(req, timeout=10) as r:
            release = json.loads(r.read())
    except Exception as e:
        raise RuntimeError(f"[cloudmeter] Could not fetch release info: {e}") from e

    download_url = next(
        (a["browser_download_url"] for a in release.get("assets", []) if a["name"] == asset),
        None,
    )
    if download_url is None:
        raise RuntimeError(
            f"[cloudmeter] No binary found for your platform ({asset}). "
            f"See https://github.com/{GITHUB_REPO}/releases"
        )

    print(f"[cloudmeter] Downloading {asset} ({release['tag_name']})...", file=sys.stderr)
    binary.parent.mkdir(parents=True, exist_ok=True)

    tmp = binary.with_suffix(".tmp")
    try:
        urllib.request.urlretrieve(download_url, tmp)
        tmp.chmod(tmp.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
        tmp.rename(binary)
    except Exception as e:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"[cloudmeter] Download failed: {e}") from e

    print(f"[cloudmeter] Installed to {binary}", file=sys.stderr)


def ensure_binary() -> Path:
    binary = _binary_path()
    if not binary.exists():
        _download()
    return binary


def start(
    provider: str = "AWS",
    region: str = "us-east-1",
    target_users: int = 1000,
    requests_per_user_per_second: float = 1.0,
    budget_usd: float = 0.0,
    ingest_port: int = 7778,
    dashboard_port: int = 7777,
    **_ignored,
) -> None:
    """Start the sidecar subprocess. No-op if already running."""
    global _process, _ingest_port

    with _lock:
        if _process is not None and _process.poll() is None:
            return  # already running

        try:
            binary = ensure_binary()
        except RuntimeError as e:
            print(e, file=sys.stderr)
            return  # degrade gracefully — app still runs, just no cost data

        cmd = [
            str(binary),
            "--provider", provider,
            "--region", region,
            "--target-users", str(target_users),
            "--requests-per-user-per-second", str(requests_per_user_per_second),
            "--budget-usd", str(budget_usd),
            "--ingest-port", str(ingest_port),
            "--dashboard-port", str(dashboard_port),
        ]
        _process = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        _ingest_port = ingest_port
        atexit.register(stop)

        # Wait for sidecar to be ready (up to 5 seconds)
        status_url = f"http://127.0.0.1:{ingest_port}/api/status"
        for _ in range(50):
            try:
                urllib.request.urlopen(status_url, timeout=0.5)
                print(
                    f"[cloudmeter] Sidecar ready — dashboard at http://localhost:{dashboard_port}",
                    file=sys.stderr,
                )
                return
            except (urllib.error.URLError, OSError):
                time.sleep(0.1)

        print("[cloudmeter] Warning: sidecar may not be ready yet", file=sys.stderr)


def stop() -> None:
    """Terminate the sidecar subprocess."""
    global _process
    with _lock:
        if _process is not None:
            _process.terminate()
            try:
                _process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                _process.kill()
            _process = None


def get_ingest_port() -> int:
    return _ingest_port
