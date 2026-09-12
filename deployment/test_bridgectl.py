import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('bridge_manager', Path(__file__).with_name('bridgectl.py'))
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)

if sys.platform == 'darwin':
    import plistlib

class DeploymentTests(unittest.TestCase):
    def test_interactive_dev_binds_then_creates_model_directories(self):
        from types import SimpleNamespace
        args = SimpleNamespace(workspace=None, release='snapshot', distribution=self.dist, java='/usr/bin/java')
        prod = self.root / 'environments/prod/config.json'
        b.atomic(prod, {'appId': 'prod-bot'})
        before = prod.read_bytes()
        def authorize(argv, **kwargs):
            self.assertEqual('top.ntutn.agent.bridge.DevRegistration', argv[3])
            b.atomic(Path(argv[4]), {'appId': 'dev-bot'})
            return SimpleNamespace(returncode=0)
        with patch('sys.stdin.isatty', return_value=True), patch.object(self.m, 'verify', return_value=self.dist), \
                patch.object(b.subprocess, 'run', side_effect=authorize), \
                patch.object(b, 'run', return_value=SimpleNamespace(stderr='java version "11"', stdout='')):
            self.m.setup_dev(args)
            self.m.setup_dev(args)  # Already configured: never authorize again.
        self.assertTrue((self.m.directory / 'workspace').is_dir())
        self.assertTrue((self.m.directory / 'backend/codex').is_dir())
        self.assertEqual(before, prod.read_bytes())
        self.assertEqual('dev-bot', b.read(self.m.directory / 'config.json')['appId'])

    def test_cancelled_registration_leaves_dev_unconfigured(self):
        from types import SimpleNamespace
        args = SimpleNamespace(workspace=None, release='snapshot', distribution=self.dist, java='/usr/bin/java')
        with patch('sys.stdin.isatty', return_value=True), patch.object(self.m, 'verify', return_value=self.dist), \
                patch.object(b.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(RuntimeError, '绑定未完成'): self.m.setup_dev(args)
        self.assertFalse((self.m.directory / 'config.json').exists())
        self.assertFalse((self.m.directory / 'runtime.json').exists())
        self.assertEqual([], list(self.m.directory.glob('.setup-*')))

    def test_dev_login_uses_created_isolated_home_and_resumes_after_failure(self):
        from types import SimpleNamespace
        settings = {'codexBinary': '/fake/codex'}
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot'})
        seen = []
        def login(argv, **kwargs):
            model = Path(kwargs['env']['CODEX_HOME'])
            self.assertEqual(self.m.directory / 'backend/codex', model)
            self.assertTrue(model.is_dir())
            seen.append(argv)
            return SimpleNamespace(returncode=1)
        with patch.object(self.m, 'validate', return_value=settings), patch.object(b.subprocess, 'run', side_effect=login):
            with self.assertRaisesRegex(RuntimeError, '下次运行'): self.m.login_dev()
        self.assertEqual([['/fake/codex', 'login', 'status'], ['/fake/codex', 'login']], seen)
        self.assertTrue((self.m.directory / 'config.json').exists())

    def test_init_creates_missing_workspace_and_preserves_existing_contents(self):
        workspace = self.root / 'new-parent' / 'workspace'
        config = self.root / 'bot.json'
        b.atomic(config, {'appId': 'dev-bot'})
        from types import SimpleNamespace
        with patch.object(b, 'run', return_value=SimpleNamespace(stderr='java version "11.0.32"', stdout='')):
            self.m.init(workspace, config, '/usr/bin/java')
            self.assertTrue(workspace.is_dir())
            marker = workspace / 'keep.txt'; marker.write_text('keep')
            self.m.validate()
            self.assertEqual('keep', marker.read_text())
            workspace_in_prod = self.root / 'prod-work' / 'missing'
            b.atomic(self.root / 'environments/prod/runtime.json', {'workspace': str(self.root / 'prod-work')})
            settings = b.read(self.m.directory / 'runtime.json')
            settings['workspace'] = str(workspace_in_prod)
            b.atomic(self.m.directory / 'runtime.json', settings)
            with self.assertRaisesRegex(ValueError, '重叠'): self.m.validate()
            self.assertFalse(workspace_in_prod.exists())

    def test_unconfigured_dev_has_no_launch_or_publish_side_effects(self):
        with tempfile.TemporaryDirectory() as root:
            prod = Path(root) / 'deployment/prod/current'
            b.atomic(prod, b'production-release')
            with patch('sys.argv', ['bridgectl', 'dev', '--root', root]), patch('sys.stdin.isatty', return_value=False), \
                    patch.object(b.Manager, 'publish') as publish, \
                    patch.object(b.Manager, 'start') as start, \
                    patch.object(b.Manager, 'stop') as stop:
                with self.assertRaisesRegex(ValueError, 'init --env dev'):
                    b.main()
                publish.assert_not_called(); start.assert_not_called(); stop.assert_not_called()
            self.assertEqual('production-release', prod.read_text())
            self.assertFalse((Path(root) / 'deployment/dev/current').exists())

    def test_prod_wizard_preserves_dev_and_reuses_binding_after_login_failure(self):
        from types import SimpleNamespace
        prod = b.Manager(self.root, 'prod')
        peer = self.m.directory / 'config.json'
        b.atomic(peer, {'appId': 'dev-bot'})
        before = peer.read_bytes()
        def authorize(argv, **kwargs):
            self.assertEqual(str(peer), argv[5])
            b.atomic(Path(argv[4]), {'appId': 'prod-bot'})
            return SimpleNamespace(returncode=0)
        args = ['bridgectl', 'init', '--env', 'prod', '--root', str(self.root), '--release', 'snapshot']
        with patch('sys.argv', args), patch('sys.stdin.isatty', return_value=True), \
                patch.object(b.Manager, 'verify', return_value=self.dist), \
                patch.object(b.subprocess, 'run', side_effect=authorize) as registration, \
                patch.object(b, 'run', return_value=SimpleNamespace(stderr='java version "11"', stdout='')), \
                patch.object(b.Manager, 'login_model', side_effect=[RuntimeError('login cancelled'), None]), \
                patch.object(b.Manager, 'start') as start, patch.object(b.Manager, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, 'login cancelled'): b.main()
            saved = (prod.directory / 'config.json').read_bytes()
            b.main()
            self.assertEqual(1, registration.call_count)
            self.assertEqual(saved, (prod.directory / 'config.json').read_bytes())
            start.assert_not_called(); stop.assert_not_called()
        self.assertEqual(before, peer.read_bytes())
        self.assertTrue((prod.directory / 'backend/codex').is_dir())
        self.assertIsNone(prod.current())

    def test_prod_wizard_rejects_peer_bot_and_cleans_staging(self):
        from types import SimpleNamespace
        prod = b.Manager(self.root, 'prod')
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot'})
        def authorize(argv, **kwargs):
            b.atomic(Path(argv[4]), {'appId': 'dev-bot'})
            return SimpleNamespace(returncode=0)
        args = SimpleNamespace(workspace=None, release='snapshot', distribution=self.dist, java='/usr/bin/java')
        with patch('sys.stdin.isatty', return_value=True), patch.object(prod, 'verify', return_value=self.dist), \
                patch.object(b.subprocess, 'run', side_effect=authorize):
            with self.assertRaisesRegex(ValueError, '相同机器人'): prod.setup_interactive(args)
        self.assertFalse((prod.directory / 'config.json').exists())
        self.assertFalse((prod.directory / 'runtime.json').exists())
        self.assertEqual([], list(prod.directory.glob('.setup-*')))

    def test_prod_wizard_rejects_nonterminal_and_partial_configuration(self):
        from types import SimpleNamespace
        prod = b.Manager(self.root, 'prod')
        with patch('sys.stdin.isatty', return_value=False), patch.object(prod, 'publish') as publish:
            with self.assertRaisesRegex(ValueError, '终端'): prod.setup_interactive(SimpleNamespace())
            publish.assert_not_called()
        path = prod.directory / 'runtime.json'
        b.atomic(path, {'workspace': 'keep'})
        before = path.read_bytes()
        with self.assertRaisesRegex(ValueError, '不完整'): prod.setup_interactive(SimpleNamespace())
        self.assertEqual(before, path.read_bytes())

    def test_prod_login_uses_prod_home(self):
        from types import SimpleNamespace
        prod = b.Manager(self.root, 'prod')
        b.atomic(prod.directory / 'config.json', {'appId': 'prod-bot'})
        settings = {'codexBinary': '/fake/codex'}
        def login(argv, **kwargs):
            self.assertEqual(str(prod.directory / 'backend/codex'), kwargs['env']['CODEX_HOME'])
            return SimpleNamespace(returncode=0 if argv[-1] == 'login' else 1)
        with patch.object(prod, 'validate', return_value=settings), patch.object(b.subprocess, 'run', side_effect=login):
            prod.login_model()

    def test_dev_command_still_starts_after_setup(self):
        args = ['bridgectl', 'dev', '--root', str(self.root), '--release', 'snapshot']
        with patch('sys.argv', args), patch('sys.stdin.isatty', return_value=True), \
                patch.object(b.Manager, 'setup_dev'), patch.object(b.Manager, 'validate'), \
                patch.object(b.Manager, 'login_dev'), patch.object(b.Manager, 'verify'), \
                patch.object(b.SystemdManager, 'is_loaded', return_value=False), \
                patch.object(b.LaunchdManager, 'is_loaded', return_value=False), \
                patch.object(b.Manager, 'start') as start, patch.object(b.Manager, 'wait_ready'):
            b.main()
            start.assert_called_once_with('snapshot')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.m = b.Manager(self.root)
        self.dist = self.root / 'dist'
        (self.dist / 'lib').mkdir(parents=True)
        (self.dist / 'lib/agent-im-bridge-kt.jar').write_bytes(b'old jar')
        (self.dist / 'libexec').mkdir(); (self.dist / 'libexec/bridgectl.py').write_text('manager')

    def test_release_is_snapshot_and_tampering_is_detected(self):
        old = self.m.publish(self.dist)
        (self.dist / 'lib/agent-im-bridge-kt.jar').write_bytes(b'new jar')
        new = self.m.publish(self.dist)
        self.assertNotEqual(old, new)
        self.assertEqual(b'old jar', (self.m.verify(old) / 'lib/agent-im-bridge-kt.jar').read_bytes())
        f = self.m.release(old) / 'lib/agent-im-bridge-kt.jar'; f.chmod(0o600); f.write_bytes(b'corrupt')
        with self.assertRaises(ValueError): self.m.verify(old)
        # Release directories are sealed, make them removable for the test's temporary-directory cleanup.
        for p in (self.root / 'releases').rglob('*'):
            if p.is_dir(): p.chmod(0o700)

    def test_legacy_migration_respects_posix_record_lock(self):
        import subprocess
        import sys
        lock = self.root / 'bridge.lock'
        child = subprocess.Popen([sys.executable, '-c',
            'import fcntl,sys; f=open(sys.argv[1],"w"); fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); sys.stdin.read()', str(lock)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual('locked', child.stdout.readline().strip())
            with self.assertRaises(BlockingIOError):
                with b.locked(lock, record_lock=True): pass
        finally:
            child.stdin.close(); child.wait(timeout=5); child.stdout.close()

    def test_preparation_failure_does_not_stop_healthy_old_release(self):
        job, actions = self.fake()
        def invalid(r): raise ValueError('invalid manifest')
        self.m.verify = invalid
        self.m.perform(job['id'])
        self.assertEqual('failed', b.read(self.m.job_path(job['id']))['state'])
        self.assertFalse(any(a[0] == 'stop' for a in actions))

    def test_prune_retains_live_previous_and_deployment_versions(self):
        releases=[]
        for n in range(6):
            (self.dist/'lib/agent-im-bridge-kt.jar').write_bytes(str(n).encode())
            releases.append(self.m.publish(self.dist))
            # Explicit ordering: rapid publishes can share a filesystem timestamp.
            os.utime(self.m.release(releases[-1]), (1_700_000_000 + n, 1_700_000_000 + n))
        b.atomic(self.m.deploydir/'current', releases[0].encode())
        b.atomic(self.m.deploydir/'previous', releases[1].encode())
        job={'id':'00000000-0000-4000-8000-000000000001','old':releases[1],'target':releases[2],'state':'draining'}
        b.atomic(self.m.job_path(job['id']), job)
        self.assertEqual([], self.m.prune())
        job['state']='cancelled';b.atomic(self.m.job_path(job['id']),job)
        self.assertEqual([releases[2]], self.m.prune())
        self.assertTrue(self.m.verify(releases[0]).exists())

    def test_activation_failure_is_retried_without_rollback(self):
        job, actions=self.fake('activating')
        def rpc(action='status'): raise OSError('response lost after activation')
        self.m.rpc=rpc
        with self.assertRaises(OSError): self.m.perform(job['id'])
        self.assertEqual('activating', b.read(self.m.job_path(job['id']))['state'])
        self.assertFalse(any(a[0]=='stop' for a in actions))

    def test_plist_keeps_group_and_uses_argument_arrays(self):
        if sys.platform != 'darwin':
            self.skipTest('macOS only test')
        p = plistlib.loads(self.m.launch_plist('test', ['/path with spaces/python', 'worker'], self.root / 'logs/out.log'))
        self.assertEqual(30, p['ThrottleInterval']); self.assertEqual(15, p['ExitTimeOut'])
        self.assertFalse(p['AbandonProcessGroup']); self.assertEqual('/path with spaces/python', p['ProgramArguments'][0])

    def test_same_bot_and_overlapping_paths_are_rejected(self):
        for env in ('prod', 'dev'):
            base = self.root / 'environments' / env
            workspace = self.root / ('work-' + env); workspace.mkdir()
            b.atomic(base / 'runtime.json', {'workspace': str(workspace), 'java': '/usr/bin/java'})
            b.atomic(base / 'config.json', {'appId': 'same'})
        with self.assertRaisesRegex(ValueError, '相同机器人'): self.m.validate()
        b.atomic(self.root / 'environments/dev/config.json', {'appId': 'different'})
        settings = b.read(self.root / 'environments/dev/runtime.json')
        settings['workspace'] = str(self.root / 'work-prod')
        b.atomic(self.root / 'environments/dev/runtime.json', settings)
        with self.assertRaisesRegex(ValueError, '重叠'): self.m.validate()

    def fake(self, state='prepared', fail=False):
        m = self.m
        job = {'id': '00000000-0000-4000-8000-000000000001', 'old': 'old', 'target': 'new', 'state': state}
        b.atomic(m.job_path(job['id']), job)
        actions = []
        m.verify = lambda r: actions.append(('verify', r))
        m.validate = lambda: {}
        m.rpc = lambda a='status': (actions.append(('rpc', a)) or {'pending': 0})
        m.stop = lambda reason='stop': actions.append(('stop', reason))
        m.start = lambda r=None, held=False: actions.append(('start', r, held))
        def ready(r):
            actions.append(('ready', r))
            if fail and r == 'new': raise RuntimeError('bad new release')
            return {}
        m.wait_ready = ready
        m.args = lambda *a: ['worker']
        m.plist = self.root / 'job.plist'
        return job, actions

    def test_upgrade_and_executor_recovery(self):
        for phase in ('prepared', 'draining', 'stopping', 'starting', 'checking', 'activating'):
            job, actions = self.fake(phase)
            self.m.perform(job['id'])
            self.assertEqual('succeeded', b.read(self.m.job_path(job['id']))['state'])
            self.assertIn(('rpc', 'activate'), actions)
            if phase in ('prepared', 'draining'): self.assertLess(actions.index(('rpc', 'drain')), actions.index(('stop', 'upgrade')))

    def test_failed_candidate_rolls_back_once(self):
        job, actions = self.fake(fail=True)
        self.m.perform(job['id'])
        self.assertEqual('rolled-back', b.read(self.m.job_path(job['id']))['state'])
        self.assertIn(('start', 'old', False), actions)
        self.assertEqual('old', self.m.current())

    def test_cancel_drain_resumes_without_stopping(self):
        job, actions = self.fake()
        b.atomic(self.m.job_path(job['id']).with_suffix('.cancel'), b'1')
        self.m.perform(job['id'])
        self.assertEqual('cancelled', b.read(self.m.job_path(job['id']))['state'])
        self.assertIn(('rpc', 'resume'), actions)
        self.assertFalse(any(a[0] == 'stop' for a in actions))

    def test_force_skips_wait_and_still_stops_owned_instance(self):
        job, actions = self.fake()
        b.atomic(self.m.job_path(job['id']).with_suffix('.force'), b'1')
        self.m.perform(job['id'])
        self.assertIn(('stop', 'upgrade'), actions)
        self.assertNotIn(('rpc', 'drain'), actions)


class ServiceManagerTests(unittest.TestCase):
    """测试平台抽象层和服务管理器"""

    def test_platform_detection(self):
        """测试平台检测返回正确的服务管理器"""
        manager = b.get_service_manager('gui/1000')
        if sys.platform == 'darwin':
            self.assertIsInstance(manager, b.LaunchdManager)
        elif sys.platform == 'linux':
            self.assertIsInstance(manager, b.SystemdManager)

    def test_systemd_unit_generation(self):
        """测试systemd单元文件生成"""
        if sys.platform != 'linux':
            self.skipTest('Linux only test')
        manager = b.SystemdManager()
        unit = manager._generate_unit('test.service', ['/usr/bin/python3', 'worker'], Path('/tmp/test.log'), True)
        self.assertIn('[Unit]', unit)
        self.assertIn('[Service]', unit)
        self.assertIn('[Install]', unit)
        self.assertIn('ExecStart="/usr/bin/python3" "worker"', unit)
        self.assertIn('Restart=always', unit)

    def test_systemd_unit_generation_no_restart(self):
        """测试systemd单元文件生成（不重启）"""
        if sys.platform != 'linux':
            self.skipTest('Linux only test')
        manager = b.SystemdManager()
        unit = manager._generate_unit('test.service', ['/usr/bin/python3', 'worker'], Path('/tmp/test.log'), False)
        self.assertIn('Restart=no', unit)

    def test_systemd_load_writes_plain_unit_file(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            with patch('pathlib.Path.home', return_value=Path(tmpdir)), patch.object(b, 'run'):
                manager = b.SystemdManager()
                manager.load('test', ['/usr/bin/python3', 'worker'], Path(tmpdir) / 'test.log')
                unit = (manager.unit_dir / 'test.service').read_text()
                import configparser
                parsed = configparser.ConfigParser(interpolation=None)
                parsed.read_string(unit)
                self.assertEqual(parsed['Service']['ExecStart'], '"/usr/bin/python3" "worker"')
                self.assertEqual(parsed['Install']['WantedBy'], 'default.target')

    def test_deploy_executor_restarts_only_on_failure(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            with patch('pathlib.Path.home', return_value=Path(tmpdir)), patch.object(b, 'run'):
                manager = b.SystemdManager()
                manager.load_deploy('deploy', ['/usr/bin/python3', 'worker'], Path(tmpdir) / 'deploy.log')
                unit = (manager.unit_dir / 'deploy.service').read_text()
                self.assertIn('Restart=on-failure', unit)
                self.assertIn('StartLimitIntervalSec=0', unit)

    def test_systemd_arguments_preserve_spaces_and_expansions(self):
        self.assertEqual('"/path with spaces/python"', b.systemd_quote('/path with spaces/python', command=True))
        self.assertEqual('"$$HOME/%%n"', b.systemd_quote('$HOME/%n', command=True))
        self.assertEqual('"HOME=/path with spaces"', b.systemd_quote('HOME=/path with spaces'))

    def test_systemd_unit_dir_creation(self):
        """测试systemd单元目录创建"""
        if sys.platform != 'linux':
            self.skipTest('Linux only test')
        with tempfile.TemporaryDirectory() as tmpdir:
            with patch('pathlib.Path.home', return_value=Path(tmpdir)):
                manager = b.SystemdManager()
                self.assertTrue(manager.unit_dir.exists())
                self.assertEqual(manager.unit_dir, Path(tmpdir) / '.config' / 'systemd' / 'user')


if __name__ == '__main__': unittest.main()
