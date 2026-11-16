"""Build-time checks for the official, package-local Codex subagent payload."""

import hashlib
import json
import os
from pathlib import Path
import platform
import sys


PLUGIN_VERSION = "0.2.2-rc.1"
CODEX_VERSION = "0.153.4"
PLATFORMS = {
    ("linux", "x64"): ("linux-x64", "x86_64-unknown-linux-musl", "codex"),
    ("linux", "arm64"): ("linux-arm64", "aarch64-unknown-linux-musl", "codex"),
    ("darwin", "x64"): ("darwin-x64", "x86_64-apple-darwin", "codex"),
    ("darwin", "arm64"): ("darwin-arm64", "aarch64-apple-darwin", "codex"),
    ("win32", "x64"): ("win32-x64", "x86_64-pc-windows-msvc", "codex.exe"),
    ("win32", "arm64"): ("win32-arm64", "aarch64-pc-windows-msvc", "codex.exe"),
}
ARCHITECTURES = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}


def payload_platform(platform_name=None, machine=None):
    system = sys.platform if platform_name is None else platform_name
    processor = platform.machine() if machine is None else machine
    architecture = ARCHITECTURES.get(processor.lower())
    try:
        return PLATFORMS[(system, architecture)]
    except KeyError:
        raise RuntimeError("No pinned Codex subagent payload exists for this platform") from None


def _package(root, name, version):
    filename = root / "node_modules" / name / "package.json"
    try:
        value = json.loads(filename.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise RuntimeError(f"Missing or invalid pinned package: {name}") from None
    if not isinstance(value, dict) or value.get("version") != version:
        raise RuntimeError(f"Pinned package version mismatch: {name}")
    return filename.parent, value


def _regular_file(filename, root, executable=False):
    if not filename.is_file() or filename.is_symlink() or filename.stat().st_size == 0:
        raise RuntimeError("Pinned Codex subagent payload is missing or empty")
    try:
        filename.resolve(strict=True).relative_to(root)
    except ValueError:
        raise RuntimeError("Pinned Codex subagent payload escapes its runtime") from None
    if executable and not os.access(filename, os.X_OK):
        raise RuntimeError("Pinned Codex subagent native payload is not executable")


def verify_codex_payload(runtime, platform_name=None, machine=None):
    runtime = Path(runtime).resolve(strict=True)
    suffix, triple, executable = payload_platform(platform_name, machine)
    plugin, plugin_manifest = _package(runtime, "@deepseek-ai/dsh-subagent-codex", PLUGIN_VERSION)
    if plugin_manifest.get("dependencies", {}).get("@openai/codex") != CODEX_VERSION:
        raise RuntimeError("The subagent plugin must use the pinned official Codex package")
    _regular_file(plugin / "lib/index.js", runtime)
    codex, codex_manifest = _package(runtime, "@openai/codex", CODEX_VERSION)
    if codex_manifest.get("bin", {}).get("codex") != "bin/codex.js":
        raise RuntimeError("Pinned Codex package has an unsupported launcher")
    _regular_file(codex / "bin/codex.js", runtime)
    # A nested copy would change createRequire(import.meta.url) resolution inside the plugin.
    if (plugin / "node_modules/@openai/codex").exists():
        raise RuntimeError("Unexpected nested Codex package changes subagent payload resolution")
    platform_package = f"@openai/codex-{suffix}"
    native_version = f"{CODEX_VERSION}-{suffix}"
    if codex_manifest.get("optionalDependencies", {}).get(platform_package) != f"npm:@openai/codex@{native_version}":
        raise RuntimeError("Pinned Codex package has an unsupported native dependency")
    native, _ = _package(runtime, platform_package, native_version)
    binary = native / "vendor" / triple / "bin" / executable
    _regular_file(binary, runtime, executable=True)
    value = hashlib.sha256()
    with binary.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return {
        "pluginVersion": PLUGIN_VERSION,
        "codexVersion": CODEX_VERSION,
        "platformPackage": platform_package,
        "nativeVersion": native_version,
        "binary": binary.relative_to(runtime).as_posix(),
        "sha256": value.hexdigest(),
    }
