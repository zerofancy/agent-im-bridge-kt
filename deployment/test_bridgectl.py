import importlib.util
import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('bridge_manager', Path(__file__).with_name('bridgectl.py'))
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)

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

if __name__ == '__main__': unittest.main()
