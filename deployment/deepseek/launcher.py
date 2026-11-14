"""Controlled DeepSeek launch, with no implicit downloads or user plugin layers."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

VERSION = "0.2.2-rc.1"
MODES = {"read-only", "workspace-write", "danger-full-access"}
SYSTEM_ENV = {
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TERM", "DISPLAY",
    "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "XAUTHORITY",
    "SYSTEMROOT", "WINDIR", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "TMP", "TEMP", "TMPDIR",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
    "all_proxy", "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR",
}
SUPPORT_FILES = ("dsh", "launcher.py", "bridge.patch.yml", "bridge-policy.mjs", "policy-core.mjs",
                 "auth-status.mjs", "auth-core.mjs", "auth.patch.yml", "oauth-login.mjs")


class LaunchError(Exception):
    pass


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def read_json(path: Path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("expected object")
        return value
    except (OSError, ValueError) as error:
        raise LaunchError("BRIDGE_DEEPSEEK_MANIFEST_UNREADABLE") from error


def runtime_files(root: Path) -> dict:
    entries = {}
    for base, directories, files in os.walk(root, followlinks=False):
        for name in list(directories) + files:
            path = Path(base) / name
            relative = path.relative_to(root).as_posix()
            if relative == "runtime-manifest.json":
                continue
            if path.is_symlink():
                try:
                    path.resolve(strict=True).relative_to(root.resolve())
                except (OSError, ValueError) as error:
                    raise LaunchError("BRIDGE_DEEPSEEK_RUNTIME_LINK_ESCAPE") from error
                entries[relative] = {"link": os.readlink(path)}
            elif path.is_file():
                entries[relative] = {"sha256": digest(path)}
    return entries


def verify_runtime(base: Path) -> Path:
    root = base / "runtime"
    manifest = read_json(root / "runtime-manifest.json")
    if manifest.get("version") != VERSION or manifest.get("schema") != 1:
        raise LaunchError("BRIDGE_DEEPSEEK_UNSUPPORTED_RUNTIME")
    if runtime_files(root) != manifest.get("files"):
        raise LaunchError("BRIDGE_DEEPSEEK_RUNTIME_INTEGRITY_FAILED")
    if {name: digest(base / name) for name in SUPPORT_FILES} != manifest.get("supportFiles"):
        raise LaunchError("BRIDGE_DEEPSEEK_POLICY_INTEGRITY_FAILED")
    cli = root / "node_modules/@deepseek-ai/dsh/lib/bin.js"
    package = read_json(cli.parent.parent / "package.json")
    if package.get("version") != VERSION or not cli.is_file():
        raise LaunchError("BRIDGE_DEEPSEEK_CLI_VERSION_MISMATCH")
    return cli


def node_path(env: dict) -> Path:
    raw = env.get("BRIDGE_DEEPSEEK_NODE", "")
    path = Path(raw)
    if not raw or not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
        raise LaunchError("BRIDGE_DEEPSEEK_NODE_REQUIRED")
    try:
        output = subprocess.run([str(path), "--version"], cwd=Path.cwd(), env=env, text=True,
                                capture_output=True, timeout=10, check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise LaunchError("BRIDGE_DEEPSEEK_NODE_UNAVAILABLE") from error
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", output)
    if match is None or tuple(map(int, match.groups())) < (24, 9, 0):
        raise LaunchError("BRIDGE_DEEPSEEK_NODE_TOO_OLD")
    return path.resolve()


def empty_patch(path: Path) -> None:
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_file():
            raise LaunchError("BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED")
        content = "\n".join(line.split("#", 1)[0] for line in path.read_text(encoding="utf-8").splitlines())
        if content.strip() != "[]":
            raise LaunchError("BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED")


def validate_profiles(home: Path, profile: str) -> None:
    empty_patch(home / "cordis.patch.yml")
    profiles = home / "profiles"
    directory = profiles / profile
    if profiles.is_symlink() or directory.is_symlink():
        raise LaunchError("BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED")
    for path in (profiles / "node_modules", directory / "node_modules", directory / "compatibility.json"):
        if path.exists() or path.is_symlink():
            raise LaunchError("BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED")
    empty_patch(directory / "cordis.patch.yml")
    manifest_path = directory / "package.json"
    if manifest_path.exists() or manifest_path.is_symlink():
        manifest = read_json(manifest_path)
        expected = ["@deepseek-ai/dsh-base", f"@deepseek-ai/dsh-{profile}-app"]
        if manifest_path.is_symlink() or manifest.get("dependencies", {}) != {} \
                or manifest.get("devDependencies", {}) != {} \
                or manifest.get("peerDependencies", {}) != {} \
                or manifest.get("optionalDependencies", {}) != {} \
                or manifest.get("dsh") != {"profile": {"bundles": expected}}:
            raise LaunchError("BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED")


def controlled_environment(original: dict) -> dict:
    env = {key: value for key, value in original.items() if key in SYSTEM_ENV or key.startswith("LC_")}
    for key in ("DSH_HOME", "DSH_AGENTS_HOME", "BRIDGE_DEEPSEEK_NODE"):
        raw = original.get(key, "")
        if not raw or not Path(raw).is_absolute():
            raise LaunchError("BRIDGE_DEEPSEEK_EXPLICIT_PATH_REQUIRED")
        env[key] = str(Path(raw).resolve())
    mode = original.get("BRIDGE_DEEPSEEK_SANDBOX_MODE", "read-only")
    if mode not in MODES:
        raise LaunchError("BRIDGE_DEEPSEEK_INVALID_SANDBOX_MODE")
    env["BRIDGE_DEEPSEEK_SANDBOX_MODE"] = mode
    for key, default in (("BRIDGE_DEEPSEEK_PROVIDER", "deepseek-official"),
                         ("BRIDGE_DEEPSEEK_MODEL", "deepseek-v4-flash")):
        value = original.get(key, default)
        if not value or len(value) > 256 or any(ord(character) < 32 for character in value):
            raise LaunchError("BRIDGE_DEEPSEEK_INVALID_MODEL_ROUTE")
        env[key] = value
    env["DSH_PERMISSION_MODE"] = mode
    env["DSH_TELEMETRY_DISABLED"] = "1"
    return env


def main(arguments=None) -> int:
    args = list(sys.argv[1:] if arguments is None else arguments)
    try:
        base = Path(__file__).resolve().parent
        cli = verify_runtime(base)
        env = controlled_environment(os.environ)
        node = node_path(env)
        if (Path.cwd() / ".env").exists() or (Path.cwd() / ".env").is_symlink():
            raise LaunchError("BRIDGE_DEEPSEEK_LAUNCH_DOTENV_REJECTED")
        home = Path(env["DSH_HOME"])
        if args == ["--version"]:
            print(VERSION)
            return 0
        if args == ["--bridge-auth-status"]:
            validate_profiles(home, "acp")
            result = subprocess.run([str(node), str(base / "auth-status.mjs")], cwd=Path.cwd(), env=env,
                                    text=True, capture_output=True, timeout=15)
            if result.returncode != 0:
                raise LaunchError("BRIDGE_DEEPSEEK_AUTH_CONFIGURATION_INVALID")
            info = json.loads(result.stdout)
            if not isinstance(info.get("configured"), bool) or not isinstance(info.get("writable"), bool):
                raise LaunchError("BRIDGE_DEEPSEEK_AUTH_STATUS_INVALID")
            if "kind" in info and info["kind"] not in {"api-key", "grant"}:
                raise LaunchError("BRIDGE_DEEPSEEK_AUTH_STATUS_INVALID")
            print(json.dumps({key: info[key] for key in ("configured", "source", "writable", "kind") if key in info}))
            return 0
        if args in (["--bridge-login"], ["--bridge-login", "--no-open"], ["--bridge-login", "--check"]):
            if env["BRIDGE_DEEPSEEK_PROVIDER"] == "openai-codex":
                validate_profiles(home, "acp")
                os.execve(str(node), [str(node), str(base / "oauth-login.mjs"), *args[1:]], env)
                return 1
            if "--check" in args:
                raise LaunchError("BRIDGE_DEEPSEEK_UNSUPPORTED_ARGUMENTS")
            validate_profiles(home, "web")
            launch = ["--profile", "web", "--patch", str(base / "auth.patch.yml"),
                      "--host", "127.0.0.1", "--port", "0"]
            if "--no-open" in args:
                launch.append("--no-open")
        elif args == ["--profile", "acp"]:
            validate_profiles(home, "acp")
            launch = ["--profile", "acp", "--patch", str(base / "bridge.patch.yml")]
        else:
            raise LaunchError("BRIDGE_DEEPSEEK_UNSUPPORTED_ARGUMENTS")
        os.execve(str(node), [str(node), str(cli), *launch], env)
    except (LaunchError, OSError, ValueError, subprocess.SubprocessError) as error:
        code = str(error) if isinstance(error, LaunchError) else "BRIDGE_DEEPSEEK_LAUNCH_FAILED"
        print(code, file=sys.stderr)
        return 1
    return 1
