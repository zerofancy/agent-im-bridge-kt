"""Isolated foreground setup for the release-owned DeepSeek Harness runtime."""

import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
from urllib.parse import parse_qs, urlsplit

from process_tree import terminate_tree
from state_files import atomic, read


SAFE_ENVIRONMENT = frozenset((
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
    "all_proxy", "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR", "SYSTEMROOT", "WINDIR",
    "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA", "USERPROFILE", "DISPLAY",
    "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "XAUTHORITY",
))


def safe_environment(inherited, home, temporary, node):
    result = {key: value for key, value in inherited.items() if key in SAFE_ENVIRONMENT}
    result.update(
        DSH_HOME=str(home), DSH_AGENTS_HOME=str(Path(home) / "agents"),
        DSH_TELEMETRY_DISABLED="1", BRIDGE_DEEPSEEK_NODE=str(node),
        TMPDIR=str(temporary), TMP=str(temporary), TEMP=str(temporary),
    )
    return result


def node_path():
    executable = shutil.which("node")
    if not executable:
        raise ValueError("DeepSeek Harness 需要 Node >=24.9.0，请先安装后重新初始化")
    return str(Path(executable).resolve())


def validate_configuration(manager, settings, config):
    if config.get("backend") != "deepseek":
        return
    if manager.env == "prod" and settings.get("deepseekBinary"):
        raise ValueError("正式 DeepSeek 运行必须使用发布快照，不能设置 deepseekBinary")
    for key in ("deepseekHome", "deepseekNode", "deepseekBinary"):
        if key in settings and (not isinstance(settings[key], str) or not Path(settings[key]).is_absolute()):
            raise ValueError(f"{key} 必须使用绝对路径")
    from model_setup import MODEL_DIRECTORIES
    home = Path(settings.get("deepseekHome", manager.directory / "backend/deepseek")).resolve()
    launch = (manager.directory / "backend/deepseek-launch").resolve()
    peers = [Path(settings["workspace"]).resolve(), (Path.home() / ".dsh").resolve()] + [
        Path(settings.get(key, manager.directory / fallback)).resolve()
        for key, fallback in MODEL_DIRECTORIES if key != "deepseekHome"
    ]
    def overlap(left, right):
        return left == right or left in right.parents or right in left.parents
    if overlap(home, launch) or any(overlap(own, peer) for own in (home, launch) for peer in peers):
        raise ValueError("DeepSeek 模型与启动目录不能与工作目录、其他后端或桌面 .dsh 目录重叠")
    if settings.get("deepseekBinary"):
        binary = Path(settings["deepseekBinary"])
        manifest = binary.parent / "runtime/runtime-manifest.json"
        if binary.name != "dsh" or not binary.is_file() or binary.absolute() != binary.resolve() \
                or not manifest.is_file() or manifest.is_symlink():
            raise ValueError("deepseekBinary 只能覆盖为带固定运行清单的受控 dsh wrapper，不能使用普通 CLI")


def _controlled_override(binary, reference):
    def digest(path):
        value = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                value.update(chunk)
        return value.digest()
    if not reference.is_file() or digest(binary) != digest(reference):
        raise ValueError("deepseekBinary 必须与选定发布版本的受控 wrapper 一致，不能使用普通 CLI")


def _run(argv, environment, cwd):
    try:
        return subprocess.run(
            argv, env=environment, cwd=cwd, stdin=subprocess.DEVNULL,
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=25,
        )
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("DeepSeek 配置检查未完成，请检查固定运行时与独立环境") from None


def _check_node(executable, environment, cwd):
    if not Path(executable).is_absolute() or not os.access(executable, os.X_OK):
        raise ValueError("deepseekNode 必须指向可执行 Node 的绝对路径")
    result = _run([executable, "--version"], environment, cwd)
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)\s*", result.stdout)
    if result.returncode or not match or tuple(map(int, match.groups())) < (24, 9, 0):
        raise ValueError("DeepSeek Harness 需要 Node >=24.9.0，请更新本环境 deepseekNode")


def _configured(binary, environment, cwd):
    result = _run([str(binary), "--bridge-auth-status"], environment, cwd)
    if result.returncode:
        raise RuntimeError("DeepSeek 独立认证状态检查失败；不会覆盖已有配置")
    try:
        status = json.loads(result.stdout)
        if not isinstance(status, dict) or type(status.get("configured")) is not bool:
            raise ValueError()
        if type(status.get("writable")) is not bool:
            raise ValueError()
        if environment.get("BRIDGE_DEEPSEEK_PROVIDER") == "openai-codex":
            if set(status) - {"configured", "writable", "kind"}:
                raise ValueError()
            if status.get("kind") not in (None, "grant", "api-key"):
                raise ValueError()
            return status["configured"] and status.get("kind") == "grant" and status["writable"]
        if set(status) - {"configured", "writable", "source"}:
            raise ValueError()
        if "source" in status and not isinstance(status["source"], str):
            raise ValueError()
        return status["configured"] and status.get("source") == "file" and status["writable"]
    except (ValueError, TypeError):
        raise RuntimeError("DeepSeek 认证检查响应无效；不会显示外部响应正文") from None


def _bootstrap_url(line):
    if not line.startswith("dsh web: "):
        return None
    value = line[len("dsh web: "):].strip()
    if any(ord(character) <= 32 or ord(character) == 127 for character in value):
        return None
    try:
        parsed = urlsplit(value)
        query = parse_qs(parsed.query, strict_parsing=True)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port \
                or parsed.netloc != f"127.0.0.1:{parsed.port}" \
                or parsed.username or parsed.password or parsed.fragment \
                or set(query) != {"token"} or len(query["token"]) != 1 or not query["token"][0]:
            return None
        return value, parsed.port
    except ValueError:
        return None


def _read_bootstrap(stream, ready):
    published = False
    truncated = False
    try:
        while True:
            line = stream.readline(8193)
            if not line:
                break
            if not line.endswith("\n"):
                truncated = True
                continue
            if truncated:
                truncated = False
                continue
            bootstrap = _bootstrap_url(line)
            if bootstrap is not None and not published:
                ready.put_nowait(bootstrap)
                published = True
    except (OSError, ValueError):
        pass
    finally:
        if not published:
            ready.put_nowait(None)


def login(manager, settings, release=None):
    config = read(manager.directory / "config.json")
    validate_configuration(manager, settings, config)
    home = Path(settings.get("deepseekHome", manager.directory / "backend/deepseek")).resolve()
    launch = manager.directory / "backend/deepseek-launch"
    if (launch / ".env").exists() or (launch / ".env").is_symlink():
        raise ValueError("DeepSeek 专用启动目录不能含 .env，请移走后重试")
    launch.mkdir(parents=True, exist_ok=True, mode=0o700)
    node = settings.get("deepseekNode") or node_path()
    environment = safe_environment(os.environ, home, manager.directory / "tmp", node)
    provider = config.get("deepseekProvider", "deepseek-official")
    model = config.get("deepseekModel", "deepseek-v4-flash")
    if not all(isinstance(value, str) and value.strip() and len(value) <= 256
               and not any(ord(character) < 32 for character in value) for value in (provider, model)):
        raise ValueError("DeepSeek provider/model 配置无效；不会使用调用者的模型变量")
    environment.update(BRIDGE_DEEPSEEK_PROVIDER=provider, BRIDGE_DEEPSEEK_MODEL=model)
    target = f"{provider}/{model}"
    environment["PATH"] = settings.get("path", environment.get("PATH", os.defpath))
    _check_node(node, environment, launch)
    if "deepseekNode" not in settings:
        settings = dict(settings, deepseekNode=node)
        atomic(manager.directory / "runtime.json", settings)
    selected = release or manager.current()
    if not selected:
        raise ValueError("DeepSeek 初始化需要已发布版本，请先运行 bridgectl publish 并指定 --release")
    reference = manager.verify(selected) / "libexec/deepseek/dsh"
    if settings.get("deepseekBinary"):
        binary = Path(settings["deepseekBinary"])
        _controlled_override(binary, reference)
    else:
        binary = reference
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError("发布版本缺少 DeepSeek 固定运行时，请重新构建并发布")
    if _configured(binary, environment, launch):
        return
    if not sys.stdin.isatty():
        raise ValueError(f"请在前台终端运行 bridgectl dev/init 完成 {target} 的独立认证")
    action = "完成 ChatGPT 账户授权" if provider == "openai-codex" else "保存 API Key"
    print(f"正在启动 {manager.env} 的独立 DeepSeek Harness 模型配置（{target}）；请在浏览器{action}。", flush=True)
    if provider == "openai-codex":
        try:
            # Foreground-only authorization handoff; inherit terminal descriptors so the
            # official manual callback fallback can read stdin. Never use daemon logs here.
            result = subprocess.run(
                [str(binary), "--bridge-login"], env=environment, cwd=launch,
                stdin=sys.stdin, stdout=sys.stdout, stderr=sys.stderr,
            )
        except OSError:
            raise RuntimeError(f"{target} 独立授权未完成，请在前台终端重试") from None
        if result.returncode or not _configured(binary, environment, launch):
            raise RuntimeError(f"{target} 登录未完成，请在前台终端重试；其他环境未改动")
        return
    if not sys.stdout.isatty():
        raise ValueError("DeepSeek 临时配置链接只能显示在交互终端，请勿将输出重定向到日志")
    child = None
    reader = None
    try:
        child = subprocess.Popen(
            [str(binary), "--bridge-login", "--no-open"],
            env=environment, cwd=launch, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        ready = queue.Queue(maxsize=1)
        reader = threading.Thread(target=_read_bootstrap, args=(child.stdout, ready), daemon=True)
        reader.start()
        try:
            bootstrap = ready.get(timeout=30)
        except queue.Empty:
            raise RuntimeError("DeepSeek 本地配置入口启动超时；未输出外部响应正文") from None
        if bootstrap is None:
            raise RuntimeError("DeepSeek 本地配置入口未启动；未输出外部响应正文")
        url, port = bootstrap
        print(f"临时本地配置链接（含临时登录令牌，请勿分享）：\n{url}", flush=True)
        print(f"通过 SSH 访问时，先在本机执行 ssh -L {port}:127.0.0.1:{port} <SSH主机>，再打开上述链接。", flush=True)
        input(f"完成 {provider} 的授权或凭证配置后按 Enter 继续：")
        if child.poll() is not None or not _configured(binary, environment, launch):
            raise RuntimeError(f"{target} 登录未完成，请在前台终端重试；其他环境未改动")
    except (OSError, EOFError):
        raise RuntimeError("DeepSeek 独立配置未完成，请在前台终端重试") from None
    finally:
        if child is not None:
            try:
                if child.poll() is None:
                    terminate_tree(child.pid, 2)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    raise RuntimeError("DeepSeek 临时配置服务清理未确认") from None
            finally:
                if reader is not None:
                    reader.join(timeout=5)
                    if reader.is_alive():
                        raise RuntimeError("DeepSeek 临时配置输出清理未确认")
                if child.stdout is not None:
                    child.stdout.close()
