# Linux 部署适配计划

## 概述

为项目添加 Linux systemd 用户级服务支持，保持与现有 macOS launchd 相同的部署体验。

## 修改范围

### 1. `deployment/bridgectl.py` - 核心修改

#### 1.1 添加平台抽象层

```python
# 添加平台检测
if sys.platform == 'darwin':
    import plistlib

# 创建抽象基类
class ServiceManager:
    """平台无关的守护进程管理接口"""

    def is_loaded(self) -> bool:
        """检查服务是否已加载"""
        raise NotImplementedError

    def load(self, label: str, argv: list, log: Path, keep: bool = True):
        """加载并启动服务"""
        raise NotImplementedError

    def unload(self, label: str):
        """卸载服务"""
        raise NotImplementedError

    def enable(self, label: str):
        """启用服务"""
        raise NotImplementedError

    def disable(self, label: str):
        """禁用服务"""
        raise NotImplementedError
```

#### 1.2 实现 LaunchdManager

```python
class LaunchdManager(ServiceManager):
    """macOS launchd 服务管理"""

    def __init__(self, domain: str):
        self.domain = domain

    def is_loaded(self, label: str) -> bool:
        return run(['launchctl', 'print', f'{self.domain}/{label}'], check=False).returncode == 0

    def load(self, label: str, argv: list, log: Path, keep: bool = True):
        # 生成 plist 并 bootstrap
        plist = self._generate_plist(label, argv, log, keep)
        path = Path.home() / 'Library/LaunchAgents' / f'{label}.plist'
        atomic(path, plist)
        run(['launchctl', 'enable', f'{self.domain}/{label}'])
        run(['launchctl', 'bootstrap', self.domain, path])

    def unload(self, label: str):
        run(['launchctl', 'disable', f'{self.domain}/{label}'], check=False)
        run(['launchctl', 'bootout', f'{self.domain}/{label}'], check=False)

    def _generate_plist(self, label, argv, log, keep):
        return plistlib.dumps({...})
```

#### 1.3 实现 SystemdManager

```python
class SystemdManager(ServiceManager):
    """Linux systemd 用户级服务管理"""

    def __init__(self):
        self.unit_dir = Path.home() / '.config' / 'systemd' / 'user'
        self.unit_dir.mkdir(parents=True, exist_ok=True)

    def is_loaded(self, label: str) -> bool:
        return run(['systemctl', '--user', 'is-active', f'{label}.service'], check=False).returncode == 0

    def load(self, label: str, argv: list, log: Path, keep: bool = True):
        unit = self._generate_unit(label, argv, log, keep)
        path = self.unit_dir / f'{label}.service'
        atomic(path, unit)
        run(['systemctl', '--user', 'daemon-reload'])
        run(['systemctl', '--user', 'enable', f'{label}.service'])
        run(['systemctl', '--user', 'start', f'{label}.service'])

    def unload(self, label: str):
        run(['systemctl', '--user', 'stop', f'{label}.service'], check=False)
        run(['systemctl', '--user', 'disable', f'{label}.service'], check=False)

    def _generate_unit(self, label, argv, log, keep):
        return f"""[Unit]
Description=Agent IM Bridge ({label})
After=network.target

[Service]
Type=simple
ExecStart={' '.join(argv)}
WorkingDirectory={Path.cwd()}
StandardOutput=append:{log}
StandardError=append:{log.with_suffix('.err.log')}
Restart={'always' if keep else 'no'}
RestartSec=5
Environment=HOME={Path.home()}

[Install]
WantedBy=default.target
"""
```

#### 1.4 修改 Manager 类

```python
class Manager:
    def __init__(self, root, env='dev'):
        # ... 现有代码 ...

        # 根据平台选择服务管理器
        if sys.platform == 'darwin':
            self.service = LaunchdManager(self.domain)
        elif sys.platform == 'linux':
            self.service = SystemdManager()
        else:
            raise RuntimeError(f'不支持的平台: {sys.platform}')

    def start(self, release=None, held=False):
        release = release or self.current()
        self.verify(release); self.validate()
        if self.service.is_loaded(self.label):
            raise RuntimeError('实例已由服务管理器管理；升级请使用 deploy')
        if held: (self.deploydir / 'activated').unlink(missing_ok=True)
        args = self.args(release, '_launch', '--release', release)
        if held: args.append('--held')
        self.service.load(self.label, args, self.directory / 'logs/bridge.log')

    def stop(self, reason='stop'):
        with contextlib.suppress(Exception): self.rpc('reason/' + reason)
        old = read(self.directory / 'lifecycle/current.json')
        self.service.unload(self.label)
        # 等待进程退出
        until = time.monotonic() + 20
        while old and alive(old['pid']):
            current = read(self.directory / 'lifecycle/current.json')
            if current and current['bootId'] != old['bootId']: raise RuntimeError('停止期间出现新实例')
            if time.monotonic() > until: raise RuntimeError('旧实例未确认退出')
            time.sleep(.2)

    def submit(self, target):
        self.verify(target); self.validate()
        with locked(self.deploydir / 'deploy.lock'):
            if self.active_job(): raise RuntimeError('已有部署事务，请查询或取消')
            job = {'id': str(uuid.uuid4()), 'env': self.env, 'old': self.current(), 'target': target,
                   'state': 'prepared', 'createdAt': time.time()}
            atomic(self.deploydir / 'jobs' / (job['id'] + '.json'), job)
            label = self.label + '.deploy.' + job['id']
            args = self.args(target, '_deploy', '--job', job['id'])
            try:
                self.service.load(label, args, self.directory / 'logs' / ('deploy-' + job['id'] + '.log'), keep=False)
            except Exception:
                job['state'] = 'failed'; job['error'] = '无法启动独立部署执行器'
                atomic(self.deploydir / 'jobs' / (job['id'] + '.json'), job); raise
            return job['id']
```

### 2. `deployment/test_bridgectl.py` - 添加测试

```python
class SystemdTests(unittest.TestCase):
    def test_systemd_unit_generation(self):
        """测试 systemd 单元文件生成"""
        # ... 测试代码 ...

    def test_platform_detection(self):
        """测试平台检测"""
        # ... 测试代码 ...
```

### 3. `deployment/live_systemd_test.py` - 新增集成测试

创建 Linux 等价的集成测试，验证：
- 服务启动/停止
- 致命异常重启
- 子进程清理
- 部署升级
- 失败回滚

### 4. `README.md` - 更新文档

添加 Linux 部署章节：

```markdown
## 部署与运行（Linux）

需要 JDK 11+、Python 3.9+，以及 systemd 用户级服务支持。正式服务由用户级 systemd 守护。

### 前置条件

1. 确保 systemd 用户服务可用：
   ```bash
   systemctl --user status
   ```

2. 如果显示 "Failed to connect to bus"，需要启用用户级 systemd：
   ```bash
   sudo loginctl enable-linger $(whoami)
   ```

### 部署命令

与 macOS 相同：
```bash
./gradlew test installDist
./bridgectl publish
./bridgectl start --env prod --release r-<内容哈希>
./bridgectl status --env prod
```

### systemd 特有操作

查看服务日志：
```bash
journalctl --user -u top.ntutn.agent.bridge.*.prod -f
```

查看服务状态：
```bash
systemctl --user status top.ntutn.agent.bridge.*.prod
```
```

### 5. `AGENTS.md` - 更新约定

```markdown
## 部署与环境隔离

- macOS 正式运行由用户级 launchd 守护，Linux 由用户级 systemd 守护，使用 `bridgectl`。
- 不要直接执行 build/install 的 JVM 启动脚本，也不要用 nohup 绕过守护。
- systemd 服务文件位于 `~/.config/systemd/user/`，与 launchd plist 保持相同的生命周期管理。
```

## 实施步骤

1. **第一步：创建平台抽象层**
   - 在 bridgectl.py 中添加 ServiceManager 抽象基类
   - 实现 LaunchdManager 封装现有代码
   - 实现 SystemdManager 支持 systemd

2. **第二步：修改 Manager 类**
   - 根据平台自动选择服务管理器
   - 修改 start/stop/submit 方法使用服务管理器

3. **第三步：添加测试**
   - 更新 test_bridgectl.py 添加 systemd 相关测试
   - 创建 live_systemd_test.py 集成测试

4. **第四步：更新文档**
   - 更新 README.md 添加 Linux 章节
   - 更新 AGENTS.md 约定

## 注意事项

1. **systemd 用户级服务**：使用 `--user` 标志，不需要 root 权限
2. **日志管理**：systemd 使用 journal，可以使用 `journalctl --user` 查看
3. **服务启用**：需要 `loginctl enable-linger` 确保用户登出后服务继续运行
4. **路径差异**：Linux 下 PATH 默认值需要调整，移除 `/opt/homebrew/bin`

## 兼容性

- 保持与现有 macOS launchd 完全相同的部署体验
- 所有 bridgectl 命令在两个平台上行为一致
- 部署事务机制（deploy/rollback）在两个平台上相同
