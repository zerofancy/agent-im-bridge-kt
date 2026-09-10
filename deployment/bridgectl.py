#!/usr/bin/env python3
"""Immutable releases and user launchd jobs. Never runs deployment inside the Bridge process tree."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

TERMINAL = {'succeeded', 'cancelled', 'rolled-back', 'failed'}


def read(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, tmp = tempfile.mkstemp(prefix='.atomic-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False, indent=2).encode())
            f.flush(); os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)


@contextlib.contextmanager
def locked(path, blocking=False, record_lock=False):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        (fcntl.lockf if record_lock else fcntl.flock)(fd, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        yield
    finally:
        os.close(fd)


def run(argv, check=True):
    r = subprocess.run([str(x) for x in argv], capture_output=True, text=True, timeout=25)
    if check and r.returncode: raise RuntimeError('命令失败: ' + str(argv[0]) + ' (exit=' + str(r.returncode) + ')')
    return r


def alive(pid):
    try: os.kill(int(pid), 0); return True
    except (OSError, TypeError, ValueError): return False


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()


class Manager:
    def __init__(self, root, env='dev'):
        self.root = Path(root).expanduser().resolve()
        if env not in ('prod', 'dev'): raise ValueError('环境必须为 prod/dev')
        self.env = env
        self.directory = self.root / 'environments' / env
        self.deploydir = self.root / 'deployment' / env
        self.label = 'top.ntutn.agent.bridge.' + hashlib.sha256(str(self.root).encode()).hexdigest()[:12] + '.' + env
        self.domain = 'gui/' + str(os.getuid())
        self.plist = Path.home() / 'Library/LaunchAgents' / (self.label + '.plist')

    def release(self, name):
        if not name or not re.fullmatch(r'[A-Za-z0-9._-]+', name) or name in ('.', '..'): raise ValueError('无效发布版本')
        p = self.root / 'releases' / name
        if p.resolve() != p: raise ValueError('发布目录不能是符号链接')
        return p

    def current(self):
        p = self.deploydir / 'current'
        return p.read_text().strip() if p.exists() else None

    def verify(self, name):
        p = self.release(name)
        m = read(p / 'manifest.json')
        if not m or m.get('stateVersion') != 3 or m.get('minJava') != 11: raise ValueError('发布版本或状态格式不兼容')
        actual = {str(f.relative_to(p)): sha(f) for f in sorted(p.rglob('*')) if f.is_file() and f != p / 'manifest.json'}
        if any(f.is_symlink() for f in p.rglob('*')) or actual != m['files']: raise ValueError('发布文件校验失败')
        return p

    def publish(self, distribution):
        source = Path(distribution).resolve()
        if not (source / 'lib/agent-im-bridge-kt.jar').is_file(): raise ValueError('请先执行 ./gradlew test installDist')
        files = {str(f.relative_to(source)): sha(f) for f in sorted(source.rglob('*')) if f.is_file()}
        if any(f.is_symlink() for f in source.rglob('*')): raise ValueError('构建产物不能包含符号链接')
        digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()[:20]
        name = 'r-' + digest
        destination = self.release(name)
        with locked(self.root / 'deployment/publish.lock'):
            if destination.exists(): self.verify(name); return name
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            staging = Path(tempfile.mkdtemp(prefix='.staging-', dir=destination.parent))
            try:
                shutil.copytree(source, staging, dirs_exist_ok=True)
                manifest = {'release': name, 'minJava': 11, 'stateVersion': 3, 'files': files}
                atomic(staging / 'manifest.json', manifest)
                copied = {str(f.relative_to(staging)): sha(f) for f in sorted(staging.rglob('*')) if f.is_file() and f != staging / 'manifest.json'}
                if copied != files: raise ValueError('构建产物在发布期间发生变化，请重新构建')
                for f in staging.rglob('*'):
                    if f.is_file(): f.chmod(0o555 if os.access(f, os.X_OK) else 0o444)
                for d in sorted((f for f in staging.rglob('*') if f.is_dir()), reverse=True): d.chmod(0o555)
                staging.chmod(0o555)
                os.rename(staging, destination)
            finally:
                if staging.exists():
                    for d in [staging] + list(staging.rglob('*')):
                        if d.is_dir(): d.chmod(0o700)
                    shutil.rmtree(staging)
        return name

    def validate(self):
        settings = read(self.directory / 'runtime.json')
        config = read(self.directory / 'config.json')
        if not settings or not config:
            raise ValueError(
                f'{self.env} 环境未配置：{self.directory}。请先执行 '
                f'./bridgectl init --env {self.env} --workspace <独立工作目录> --config <机器人配置.json>。'
                + ('dev 必须使用独立测试机器人，不会复用或停止 prod。' if self.env == 'dev' else '')
            )
        workspace = Path(settings['workspace'])
        if workspace.exists() and not workspace.is_dir(): raise ValueError('工作目录路径已存在，但不是目录')
        other = self.root / 'environments' / ('dev' if self.env == 'prod' else 'prod')
        def overlap(a, b): return a == b or a in b.parents or b in a.parents
        def paths(s, base):
            return [Path(s['workspace']).resolve(), Path(s.get('codexHome', base / 'backend/codex')).resolve(),
                    Path(s.get('traeHome', base / 'backend/trae')).resolve(), Path(s.get('traeCliHome', base / 'backend/trae/cli')).resolve()]
        if overlap(self.directory.resolve(), other.resolve()): raise ValueError('环境状态目录重叠')
        roots = paths(settings, self.directory)
        if any(overlap(a, other.resolve()) or overlap(a, (self.root / 'releases').resolve()) for a in roots): raise ValueError('运行目录覆盖其他环境或发布目录')
        peer = read(other / 'runtime.json')
        peer_config = read(other / 'config.json')
        if peer_config and config['appId'] == peer_config['appId']: raise ValueError('调试和正式不能使用相同机器人')
        if peer and any(overlap(a, b) for a in roots for b in paths(peer, other)): raise ValueError('模型目录或工作目录重叠')
        java = Path(settings['java'])
        r = run([java, '-version'])
        version = re.search(r'version "(\d+)', r.stderr + r.stdout)
        if not version or int(version.group(1)) < 11: raise ValueError('需要 JDK 11 或更新版本')
        # Create only after isolation and runtime checks, never inside a rejected peer path.
        workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
        return settings

    def rpc(self, action='status'):
        endpoint = read(self.directory / 'control/endpoint.json')
        life = read(self.directory / 'lifecycle/current.json')
        if not endpoint or not life or not alive(endpoint['pid']) or any(endpoint[k] != life[k] for k in ('pid', 'startedAt', 'bootId')):
            raise RuntimeError('实例未运行或管理地址已失效')
        req = urllib.request.Request('http://127.0.0.1:%d/%s' % (endpoint['port'], action),
            headers={'Authorization': 'Bearer ' + endpoint['token']}, method='GET' if action == 'status' else 'POST',
            data=None if action == 'status' else b'')
        with urllib.request.urlopen(req, timeout=5) as r: result = json.load(r)
        if result['bootId'] != endpoint['bootId']: raise RuntimeError('启动代次不匹配')
        return result

    def active_job(self):
        for p in sorted((self.deploydir / 'jobs').glob('*.json')):
            j = read(p)
            if j['state'] not in TERMINAL: return j
        return None

    def args(self, release, command, *extra):
        settings = self.validate()
        return [settings['python'], str(self.release(release) / 'libexec/bridgectl.py'), command,
                '--root', str(self.root), '--env', self.env] + list(extra)

    def launch_plist(self, label, argv, log, keep=True):
        log.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        for p in [log, log.with_suffix('.err.log')]:
            fd = os.open(p, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600); os.close(fd)
        return plistlib.dumps({'Label': label, 'ProgramArguments': argv, 'RunAtLoad': True,
            'KeepAlive': keep, 'ThrottleInterval': 30, 'ExitTimeOut': 15, 'AbandonProcessGroup': False,
            'WorkingDirectory': str(self.root), 'Umask': 63,
            'StandardOutPath': str(log), 'StandardErrorPath': str(log.with_suffix('.err.log'))})

    def start(self, release=None, held=False):
        release = release or self.current()
        self.verify(release); self.validate()
        if run(['launchctl', 'print', self.domain + '/' + self.label], check=False).returncode == 0:
            raise RuntimeError('实例已由 launchd 管理；升级请使用 deploy')
        if held: (self.deploydir / 'activated').unlink(missing_ok=True)
        args = self.args(release, '_launch', '--release', release)
        if held: args.append('--held')
        atomic(self.plist, self.launch_plist(self.label, args, self.directory / 'logs/bridge.log'))
        run(['launchctl', 'enable', self.domain + '/' + self.label])
        run(['launchctl', 'bootstrap', self.domain, self.plist])

    def stop(self, reason='stop'):
        with contextlib.suppress(Exception): self.rpc('reason/' + reason)
        old = read(self.directory / 'lifecycle/current.json')
        run(['launchctl', 'disable', self.domain + '/' + self.label], check=False)
        result = run(['launchctl', 'bootout', self.domain + '/' + self.label], check=False)
        if result.returncode and run(['launchctl', 'print', self.domain + '/' + self.label], check=False).returncode == 0:
            raise RuntimeError('launchd 未能停止实例')
        # launchd owns the process group, never send signals to a PID from stale metadata.
        until = time.monotonic() + 20
        while old and alive(old['pid']):
            current = read(self.directory / 'lifecycle/current.json')
            if current and current['bootId'] != old['bootId']: raise RuntimeError('停止期间出现新实例')
            if time.monotonic() > until: raise RuntimeError('旧实例未确认退出')
            time.sleep(.2)

    def wait_ready(self, release, timeout=60, stable=10):
        deadline = time.monotonic() + timeout
        since = None; boot = None
        while time.monotonic() < deadline:
            try:
                s = self.rpc()
                if s['release'] != release or not s['connected'] or s['closed']: raise RuntimeError('not ready')
                if s['bootId'] != boot: boot = s['bootId']; since = time.monotonic()
                if time.monotonic() - since >= stable: return s
            except (OSError, ValueError, RuntimeError): since = None; boot = None
            time.sleep(.25)
        raise RuntimeError('新版未在健康检查窗口内就绪')

    def submit(self, target):
        self.verify(target); self.validate()
        with locked(self.deploydir / 'deploy.lock'):
            if self.active_job(): raise RuntimeError('已有部署事务，请查询或取消')
            job = {'id': str(uuid.uuid4()), 'env': self.env, 'old': self.current(), 'target': target,
                   'state': 'prepared', 'createdAt': time.time()}
            atomic(self.deploydir / 'jobs' / (job['id'] + '.json'), job)
            label = self.label + '.deploy.' + job['id']
            path = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
            args = self.args(target, '_deploy', '--job', job['id'])
            atomic(path, self.launch_plist(label, args, self.directory / 'logs' / ('deploy-' + job['id'] + '.log'), {'SuccessfulExit': False}))
            try: run(['launchctl', 'bootstrap', self.domain, path])
            except Exception:
                job['state'] = 'failed'; job['error'] = '无法启动独立部署执行器'; atomic(self.deploydir / 'jobs' / (job['id'] + '.json'), job); raise
            return job['id']

    def perform(self, job_id):
        path = self.job_path(job_id)
        with locked(self.deploydir / 'deploy.lock', blocking=True):
            job = read(path)
            if not job or job['state'] in TERMINAL: return
            def state(value, **extra):
                job.update(state=value, **extra); atomic(path, job)
            try:
                self.verify(job['target']); self.validate()
                if job['old']: self.verify(job['old'])
                if job['state'] in ('prepared', 'draining'):
                    state('draining')
                    while True:
                        if path.with_suffix('.cancel').exists():
                            with contextlib.suppress(Exception): self.rpc('resume')
                            state('cancelled'); return
                        if path.with_suffix('.force').exists(): break
                        try:
                            s = self.rpc('drain')
                            if s['pending'] == 0: break
                        except (OSError, RuntimeError, ValueError):
                            # A crash loses the in-memory queue; the current guarded release may be restarting.
                            previous = read(self.directory / 'lifecycle/current.json', {})
                            loaded = run(['launchctl', 'print', self.domain + '/' + self.label], check=False).returncode == 0
                            if not loaded and not alive(previous.get('pid')): break
                        time.sleep(.5)
                    # Persist intent before changing launchd or pointers; retrying stop is safe.
                    state('stopping')
                if job['state'] == 'stopping':
                    self.stop('upgrade')
                    if job['old']: atomic(self.deploydir / 'previous', job['old'].encode())
                    atomic(self.deploydir / 'current', job['target'].encode())
                    state('starting')
                if job['state'] == 'starting':
                    # On executor recovery, restart held so no unverified generation can receive messages.
                    self.stop('upgrade'); self.start(job['target'], held=True)
                    state('checking')
                if job['state'] == 'checking':
                    self.wait_ready(job['target'])
                    state('activating')
                if job['state'] == 'activating':
                    self.wait_ready(job['target'])
                    # Activation is the commit point. Once durable, never roll back potentially accepted work.
                    atomic(self.deploydir / 'activated', {'release': job['target'], 'job': job_id})
                    self.rpc('activate')
                    # Persist non-held restart arguments without restarting the healthy JVM.
                    args = self.args(job['target'], '_launch', '--release', job['target'])
                    atomic(self.plist, self.launch_plist(self.label, args, self.directory / 'logs/bridge.log'))
                    atomic(self.deploydir / 'activated', {'release': job['target'], 'job': job_id})
                    state('succeeded')
                if job['state'] == 'rolling-back': self.recover_old(job, state)
            except Exception as e:
                if job['state'] in ('prepared', 'draining'):
                    with contextlib.suppress(Exception): self.rpc('resume')
                    state('failed', error=type(e).__name__)
                    return
                if job['state'] == 'activating':
                    atomic(path, job)
                    raise  # launchd retries this committed activation; it must not discard newly admitted work.
                state('rolling-back', error=type(e).__name__)
                self.recover_old(job, state)

    def recover_old(self, job, state):
        try:
            self.stop('rollback')
            if not job['old']:
                (self.deploydir / 'current').unlink(missing_ok=True)
                state('failed'); return
            self.verify(job['old'])
            atomic(self.deploydir / 'current', job['old'].encode())
            atomic(self.deploydir / 'activated', {'release': job['old'], 'rollback': job['id']})
            self.start(job['old'])
            self.wait_ready(job['old'])
            state('rolled-back')
        except Exception as e:
            state('failed', rollbackError=type(e).__name__)

    def job_path(self, job_id):
        if str(uuid.UUID(job_id)) != job_id: raise ValueError('无效部署编号')
        return self.deploydir / 'jobs' / (job_id + '.json')

    def launch(self, release, held):
        p = self.verify(release); settings = self.validate()
        for d in ('tmp', 'attachments', 'logs', 'control', 'lifecycle'): (self.directory / d).mkdir(parents=True, exist_ok=True, mode=0o700)
        activated = read(self.deploydir / 'activated', {})
        held = held and activated.get('release') != release
        env = {k: v for k, v in os.environ.items() if k not in ('CODEX_HOME', 'TRAE_HOME', 'TRAECLI_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')}
        env.update(BRIDGE_ROOT=str(self.root), BRIDGE_ENV=self.env, BRIDGE_RELEASE=release,
                   BRIDGE_HOLD='1' if held else '0', TMPDIR=str(self.directory / 'tmp'),
                   PATH=settings.get('path', '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin'))
        classpath = ':'.join(str(f) for f in sorted((p / 'lib').glob('*.jar')))
        argv = [settings['java'], '-Djava.io.tmpdir=' + str(self.directory / 'tmp'), '-cp', classpath, 'top.ntutn.agent.bridge.MainKt']
        child = subprocess.Popen(argv, env=env, cwd=settings['workspace'], stdin=subprocess.DEVNULL)
        def terminate(signum, frame):
            if child.poll() is None: child.send_signal(signum)
        signal.signal(signal.SIGTERM, terminate); signal.signal(signal.SIGINT, terminate)
        code = child.wait()
        life = read(self.directory / 'lifecycle/current.json', {})
        if life.get('pid') == child.pid:
            observed = {k: life[k] for k in ('pid', 'startedAt', 'bootId')}
            if code < 0: observed['signal'] = -code
            else: observed['exitCode'] = code
            atomic(self.directory / 'lifecycle/observed-exit.json', observed)
        return code if code >= 0 else 128 - code

    def prune(self):
        protected = set()
        for env in ('prod', 'dev'):
            dep = self.root / 'deployment' / env
            for name in ('current', 'previous'):
                p = dep / name
                if p.exists(): protected.add(p.read_text().strip())
            for p in (dep / 'jobs').glob('*.json'):
                job = read(p)
                if job['state'] not in TERMINAL: protected.update((job.get('old'), job['target']))
            life = read(self.root / 'environments' / env / 'lifecycle/current.json', {})
            if alive(life.get('pid')): protected.add(life.get('release'))
        releases = sorted((self.root / 'releases').glob('r-*'), key=lambda p: p.stat().st_mtime, reverse=True)
        protected.update(p.name for p in releases[:3])
        removed = []
        for p in releases:
            if p.name in protected: continue
            self.verify(p.name)
            for d in [p] + list(p.rglob('*')):
                if d.is_dir(): d.chmod(0o700)
            shutil.rmtree(p); removed.append(p.name)
        return removed

    def init(self, workspace, config_path, java, legacy=False):
        if (self.directory / 'config.json').exists(): raise ValueError('环境已存在，不覆盖')
        config = read(Path(config_path))
        if not config: raise ValueError('缺少机器人配置')
        workspace = Path(workspace).resolve()
        java = str(Path(java).resolve())
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        settings = {'workspace': str(workspace), 'java': java, 'python': str(Path(sys.executable).resolve()),
                    'codexBinary': shutil.which('codex') or 'codex', 'traexBinary': shutil.which('traex') or 'traex',
                    'path': os.environ.get('PATH', '/usr/bin:/bin')}
        if legacy:
            settings.update(codexHome=str(Path(os.environ.get('CODEX_HOME') or Path.home() / '.codex').resolve()),
                            traeHome=str(Path(os.environ.get('TRAE_HOME') or Path.home() / '.trae').resolve()))
            settings['traeCliHome'] = str(Path(os.environ.get('TRAECLI_HOME') or Path(settings['traeHome']) / 'cli').resolve())
        atomic(self.directory / 'config.json', config); atomic(self.directory / 'runtime.json', settings)
        try: self.validate()
        except Exception:
            (self.directory / 'config.json').unlink(); (self.directory / 'runtime.json').unlink(); raise
        if legacy and (self.root / 'sessions.json').exists(): atomic(self.directory / 'sessions.json', read(self.root / 'sessions.json'))
        self.model_directories(settings)

    def model_directories(self, settings):
        for key, fallback in (('codexHome', 'backend/codex'), ('traeHome', 'backend/trae'), ('traeCliHome', 'backend/trae/cli')):
            Path(settings.get(key, self.directory / fallback)).mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.directory / 'tmp').mkdir(parents=True, exist_ok=True, mode=0o700)

    def setup_dev(self, args):
        if self.env != 'dev': raise ValueError('交互初始化仅用于 dev')
        configured = [(self.directory / name).exists() for name in ('config.json', 'runtime.json')]
        if all(configured): return
        if any(configured): raise ValueError('dev 配置不完整，请检查环境配置；不会覆盖已有文件')
        if not sys.stdin.isatty():
            raise ValueError('请在终端运行 ./bridgectl dev 交互绑定测试机器人；自动化可先使用 init --env dev 导入配置')
        print('首次启动 dev：将在浏览器中选择或创建测试机器人，请勿选择正式机器人。', flush=True)
        workspace = Path(args.workspace).expanduser().resolve() if args.workspace else self.directory / 'workspace'
        print('测试模型的默认工作目录：' + str(workspace) + '（自动创建）', flush=True)
        release = args.release or self.publish(args.distribution)
        snapshot = self.verify(release)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        with tempfile.TemporaryDirectory(prefix='.setup-', dir=self.directory) as staging:
            config = Path(staging) / 'config.json'
            classpath = ':'.join(str(f) for f in sorted((snapshot / 'lib').glob('*.jar')))
            env = {k: v for k, v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')}
            result = subprocess.run([args.java, '-cp', classpath, 'top.ntutn.agent.bridge.DevRegistration',
                str(config), str(self.root / 'environments/prod/config.json')], env=env)
            if result.returncode: raise RuntimeError('机器人绑定未完成，请重新运行 ./bridgectl dev')
            self.init(workspace, config, args.java)
        args.release = release
        print('测试机器人已绑定；配置仅保存在 dev 环境。', flush=True)

    def login_dev(self):
        settings = self.validate()
        self.model_directories(settings)
        if read(self.directory / 'config.json').get('backend', 'codex') != 'codex': return
        env = os.environ.copy()
        env.update(CODEX_HOME=str(Path(settings.get('codexHome', self.directory / 'backend/codex'))),
                   TMPDIR=str(self.directory / 'tmp'), TMP=str(self.directory / 'tmp'), TEMP=str(self.directory / 'tmp'))
        binary = settings['codexBinary']
        status = subprocess.run([binary, 'login', 'status'], env=env, capture_output=True, timeout=25)
        if status.returncode == 0: return
        print('请登录 dev 的独立模型环境（不会复制或修改 prod 登录状态）。', flush=True)
        if subprocess.run([binary, 'login'], env=env).returncode:
            raise RuntimeError('模型登录未完成；下次运行 ./bridgectl dev 将继续登录')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['publish','prune','init','migrate-legacy','dev','status','start','stop','deploy','deploy-status','deploy-force','deploy-cancel','rollback','_launch','_deploy'])
    parser.add_argument('--root', default=str(Path.home() / '.agent-im-bridge-kt'))
    parser.add_argument('--env', choices=['dev','prod'])
    parser.add_argument('--release'); parser.add_argument('--job'); parser.add_argument('--held', action='store_true')
    parser.add_argument('--distribution', default='build/install/agent-im-bridge-kt')
    parser.add_argument('--workspace'); parser.add_argument('--config'); parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('job_id', nargs='?')
    args = parser.parse_args()
    if args.command.startswith('deploy-') and not args.env:
        job_id = args.job_id or args.job
        if not job_id or str(uuid.UUID(job_id)) != job_id: parser.error('需要有效部署编号')
        matches = [e for e in ('prod', 'dev') if (Path(args.root).expanduser() / 'deployment' / e / 'jobs' / (job_id + '.json')).exists()]
        if len(matches) == 1: args.env = matches[0]
    if args.command not in ('dev', 'publish') and not args.env: parser.error('必须显式指定 --env dev/prod')
    m = Manager(args.root, args.env or 'dev')
    if args.command == 'dev':
        if m.env != 'dev': raise ValueError('dev 只能使用调试环境')
        with locked(m.deploydir / 'deploy.lock'):
            if m.active_job(): raise RuntimeError('部署中，请使用部署控制命令')
            m.setup_dev(args)
            m.validate()
            if sys.stdin.isatty(): m.login_dev()
    if args.command == 'publish': print(m.publish(args.distribution))
    elif args.command == 'prune':
        # Lock both environments in fixed order so no deployment can acquire a soon-to-be-deleted version.
        with locked(m.root / 'deployment/prod/deploy.lock'), locked(m.root / 'deployment/dev/deploy.lock'), locked(m.root / 'deployment/publish.lock'):
            print(json.dumps(m.prune()))
    elif args.command == 'init':
        if not args.config or not args.workspace: parser.error('init 需要 --config 和 --workspace')
        m.init(args.workspace, args.config, args.java); print('环境已初始化；请在独立模型目录登录')
    elif args.command == 'migrate-legacy':
        if args.env != 'prod' or not args.workspace: parser.error('迁移需要 --env prod 和 --workspace')
        with locked(m.root / 'bridge.lock', record_lock=True):
            backup = m.root / 'backups' / ('legacy-' + str(int(time.time())))
            for name in ('config.json', 'sessions.json'):
                if (m.root / name).exists(): atomic(backup / name, read(m.root / name))
            m.init(args.workspace, m.root / 'config.json', args.java, legacy=True)
        print('已备份并迁移正式环境；旧模型历史原地保留，未启动')
    elif args.command == '_launch': return m.launch(args.release, args.held)
    elif args.command == '_deploy': m.perform(args.job)
    elif args.command.startswith('deploy-'):
        p = m.job_path(args.job_id or args.job)
        job = read(p)
        if not job: raise ValueError('部署不存在')
        if args.command == 'deploy-status': print(json.dumps(job, ensure_ascii=False, indent=2))
        else:
            if job['state'] not in ('prepared','draining'): raise ValueError('仅排空阶段可强制或取消')
            atomic(p.with_suffix('.force' if args.command == 'deploy-force' else '.cancel'), b'1')
            print('已提交部署控制请求')
    elif args.command in ('deploy', 'rollback'):
        target = args.release if args.command == 'deploy' else (m.deploydir / 'previous').read_text().strip()
        print(m.submit(target))
    elif args.command == 'status':
        try: status = m.rpc()
        except Exception: status = {'running':False, 'lifecycle':read(m.directory / 'lifecycle/current.json'), 'release':m.current()}
        status['deployment'] = m.active_job(); print(json.dumps(status, ensure_ascii=False, indent=2))
    else:
        with locked(m.deploydir / 'deploy.lock'):
            if m.active_job(): raise RuntimeError('部署中，请使用部署控制命令')
            if args.command == 'stop': m.stop(); print('已停止，守护已卸载')
            else:
                release = args.release or (m.publish(args.distribution) if args.command == 'dev' else m.current())
                m.verify(release)
                if run(['launchctl', 'print', m.domain + '/' + m.label], check=False).returncode == 0:
                    raise RuntimeError('环境已运行；请使用 deploy 升级')
                atomic(m.deploydir / 'current', release.encode())
                m.start(release); m.wait_ready(release); print('已连接 environment=' + m.env + ' release=' + release)
    return 0


if __name__ == '__main__':
    os.umask(0o077)
    try: sys.exit(main())
    except KeyboardInterrupt:
        print('\n操作已取消；可重新运行原命令继续。', file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        # Never print exception bodies from external commands, network requests, or credential-bearing JSON.
        print('bridgectl: ' + type(error).__name__ + ': ' + (str(error) if isinstance(error, (ValueError, RuntimeError, BlockingIOError)) else '操作失败，请检查环境和日志'), file=sys.stderr)
        sys.exit(1)
