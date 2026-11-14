"""Foreground model authentication and explicitly owned backend directories."""

import os
from pathlib import Path
import subprocess
import sys

from state_files import read


MODEL_DIRECTORIES = (
    ("opencodeHome", "backend/opencode"),
    ("codexHome", "backend/codex"),
    ("traeHome", "backend/trae"),
    ("traeCliHome", "backend/trae/cli"),
    ("doubaoHome", "backend/doubao"),
    ("deepseekHome", "backend/deepseek"),
)


def model_paths(settings, base):
    return [Path(settings["workspace"]).resolve()] + [
        Path(settings.get(key, base / fallback)).resolve()
        for key, fallback in MODEL_DIRECTORIES
    ] + [(base / "backend/deepseek-launch").resolve()]


def model_directories(manager, settings):
    for key, fallback in MODEL_DIRECTORIES:
        Path(settings.get(key, manager.directory / fallback)).mkdir(
            parents=True, exist_ok=True, mode=0o700
        )
    for child in ("tmp", "backend/deepseek-launch"):
        (manager.directory / child).mkdir(parents=True, exist_ok=True, mode=0o700)


def login_model(self, release=None):
    settings = self.validate()
    self.model_directories(settings)
    backend = read(self.directory / 'config.json').get('backend', 'codex')
    if backend == 'deepseek':
        from deepseek_setup import login
        return login(self, settings, release)
    if backend == 'opencode':
        home = Path(settings.get('opencodeHome', self.directory / 'backend/opencode'))
        env = {k: v for k, v in os.environ.items() if k in (
            'PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'LANG', 'LC_ALL', 'LC_CTYPE',
            'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
            'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy', 'SSL_CERT_FILE', 'SSL_CERT_DIR')}
        for key, child in (('XDG_CONFIG_HOME', 'config'), ('XDG_DATA_HOME', 'data'),
                           ('XDG_CACHE_HOME', 'cache'), ('XDG_STATE_HOME', 'state')):
            env[key] = str(home / child)
        env.update(TMPDIR=str(self.directory / 'tmp'), TMP=str(self.directory / 'tmp'), TEMP=str(self.directory / 'tmp'))
        auth = read(home / 'data/opencode/auth.json', {})
        if auth: return
        if not sys.stdin.isatty(): raise ValueError('请在前台终端运行 bridgectl dev 完成 OpenCode 独立登录')
        print(f'请登录 {self.env} 的独立 OpenCode 环境。', flush=True)
        if subprocess.run([settings.get('opencodeBinary', 'opencode'), 'auth', 'login'], env=env, cwd=home).returncode:
            raise RuntimeError('OpenCode 登录未完成，请在前台终端重试')
        return
    if backend == 'traex':
        env = os.environ.copy()
        env.update(TRAE_HOME=str(Path(settings.get('traeHome', self.directory / 'backend/trae'))),
                   TRAECLI_HOME=str(Path(settings.get('traeCliHome', self.directory / 'backend/trae/cli'))),
                   TMPDIR=str(self.directory / 'tmp'), TMP=str(self.directory / 'tmp'), TEMP=str(self.directory / 'tmp'))
        binary = settings.get('traexBinary', 'traex')
        home = Path(env['TRAECLI_HOME'])
        status = subprocess.run([binary, 'login', 'status'], env=env, cwd=home, capture_output=True, timeout=25)
        if status.returncode == 0: return
        print(f'请登录 {self.env} 的独立 Traex 环境（不会复制或修改其他环境登录状态）。', flush=True)
        if subprocess.run([binary, 'login'], env=env, cwd=home).returncode:
            command = './bridgectl dev' if self.env == 'dev' else f'./bridgectl init --env {self.env}'
            raise RuntimeError(f'Traex 登录未完成；下次运行 {command} 将继续登录')
        models = subprocess.run([binary, 'debug', 'models'], env=env, cwd=home, capture_output=True, timeout=25)
        if models.returncode != 0:
            raise RuntimeError('Traex 登录后仍无法读取可用模型，请检查独立环境登录状态和模型配置')
        return
    if backend != 'codex': return
    env = os.environ.copy()
    env.update(CODEX_HOME=str(Path(settings.get('codexHome', self.directory / 'backend/codex'))),
               TMPDIR=str(self.directory / 'tmp'), TMP=str(self.directory / 'tmp'), TEMP=str(self.directory / 'tmp'))
    binary = settings['codexBinary']
    home = Path(env['CODEX_HOME'])
    status = subprocess.run([binary, 'login', 'status'], env=env, cwd=home, capture_output=True, timeout=25)
    if status.returncode == 0: return
    print(f'请登录 {self.env} 的独立模型环境（不会复制或修改其他环境登录状态）。', flush=True)
    if subprocess.run([binary, 'login'], env=env, cwd=home).returncode:
        command = './bridgectl dev' if self.env == 'dev' else f'./bridgectl init --env {self.env}'
        raise RuntimeError(f'模型登录未完成；下次运行 {command} 将继续登录')
