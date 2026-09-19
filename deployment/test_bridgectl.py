import importlib.util
import base64
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
    def test_state_files_are_read_as_utf8(self):
        path = self.root / 'state.json'
        path.write_bytes('{"状态":"正常"}'.encode('utf-8'))
        self.assertEqual({'状态': '正常'}, b.read(path))

    def test_command_output_uses_system_encoding_and_replaces_invalid_bytes(self):
        from types import SimpleNamespace
        process = SimpleNamespace(returncode=0, stdout=b'out-\xff', stderr=b'err-\xfe')
        with patch.object(b.subprocess, 'run', return_value=process) as subprocess_run, \
                patch.object(b.locale, 'getencoding', return_value='utf-8'):
            result = b.run(['tool'])
        self.assertEqual('out-\ufffd', result.stdout)
        self.assertEqual('err-\ufffd', result.stderr)
        subprocess_run.assert_called_once_with(['tool'], capture_output=True, timeout=25)

    def test_opencode_login_uses_environment_state_without_inherited_secrets(self):
        from types import SimpleNamespace
        settings = {'workspace': str(self.root / 'workspace'), 'opencodeBinary': '/fake/opencode'}
        b.atomic(self.m.directory / 'config.json', {'backend': 'opencode'})
        seen = []
        def login(argv, **kwargs):
            seen.append(argv)
            self.assertEqual(str(self.m.directory / 'backend/opencode/data'), kwargs['env']['XDG_DATA_HOME'])
            self.assertNotIn('OPENCODE_CONFIG', kwargs['env'])
            self.assertNotIn('OPENAI_API_KEY', kwargs['env'])
            return SimpleNamespace(returncode=0)
        with patch.object(self.m, 'validate', return_value=settings), patch('sys.stdin.isatty', return_value=True), \
                patch.dict(os.environ, {'OPENCODE_CONFIG': '/other/config', 'OPENAI_API_KEY': 'do-not-inherit'}), \
                patch.object(b.subprocess, 'run', side_effect=login):
            self.m.login_model()
        self.assertEqual([['/fake/opencode', 'auth', 'login']], seen)

    def test_opencode_root_is_part_of_environment_isolation(self):
        from types import SimpleNamespace
        workspace = self.root / 'opencode-work'
        b.atomic(self.m.directory / 'runtime.json', {'workspace': str(workspace), 'java': '/usr/bin/java',
                 'opencodeHome': str(self.root / 'shared-opencode')})
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot'})
        b.atomic(self.root / 'environments/prod/runtime.json', {'workspace': str(self.root / 'peer-work'),
                 'opencodeHome': str(self.root / 'shared-opencode')})
        with patch.object(b, 'run', return_value=SimpleNamespace(stderr='java version "11"', stdout='')):
            with self.assertRaisesRegex(ValueError, '重叠'):
                self.m.validate()

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

    def test_interactive_dev_preserves_selected_backend_in_saved_config(self):
        from types import SimpleNamespace
        args = SimpleNamespace(workspace=None, release='snapshot', distribution=self.dist, java='/usr/bin/java')
        prod = self.root / 'environments/prod/config.json'
        b.atomic(prod, {'appId': 'prod-bot'})
        def authorize(argv, **kwargs):
            b.atomic(Path(argv[4]), {
                'appId': 'dev-bot',
                'appSecret': 'secret',
                'allowedUserId': 'ou_owner',
                'tenant': 'feishu',
                'backend': 'traex',
                'sandboxMode': 'read-only',
            })
            return SimpleNamespace(returncode=0)
        with patch('sys.stdin.isatty', return_value=True), patch.object(self.m, 'verify', return_value=self.dist), \
                patch.object(b.subprocess, 'run', side_effect=authorize), \
                patch.object(b, 'run', return_value=SimpleNamespace(stderr='java version "11"', stdout='')):
            self.m.setup_dev(args)
        saved = b.read(self.m.directory / 'config.json')
        self.assertEqual('traex', saved['backend'])
        self.assertEqual('read-only', saved['sandboxMode'])

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

    def test_traex_login_uses_created_isolated_homes_and_verifies_models(self):
        from types import SimpleNamespace
        settings = {'traexBinary': '/fake/traex'}
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot', 'backend': 'traex'})
        seen = []
        def login(argv, **kwargs):
            self.assertEqual(str(self.m.directory / 'backend/trae'), kwargs['env']['TRAE_HOME'])
            self.assertEqual(str(self.m.directory / 'backend/trae/cli'), kwargs['env']['TRAECLI_HOME'])
            seen.append(argv)
            if argv[-2:] == ['login', 'status']: return SimpleNamespace(returncode=1)
            return SimpleNamespace(returncode=0)
        with patch.object(self.m, 'validate', return_value=settings), patch('sys.stdin.isatty', return_value=True), \
                patch.object(b.subprocess, 'run', side_effect=login):
            self.m.login_model()
        self.assertEqual([['/fake/traex', 'login', 'status'], ['/fake/traex', 'login'], ['/fake/traex', 'debug', 'models']], seen)

    def test_traex_login_failure_resumes_on_next_run(self):
        from types import SimpleNamespace
        settings = {'traexBinary': '/fake/traex'}
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot', 'backend': 'traex'})
        seen = []
        def login(argv, **kwargs):
            seen.append(argv)
            return SimpleNamespace(returncode=1 if argv[-1] == 'login' else 1)
        with patch.object(self.m, 'validate', return_value=settings), patch('sys.stdin.isatty', return_value=True), \
                patch.object(b.subprocess, 'run', side_effect=login):
            with self.assertRaisesRegex(RuntimeError, 'Traex 登录未完成'):
                self.m.login_model()
        self.assertEqual([['/fake/traex', 'login', 'status'], ['/fake/traex', 'login']], seen)

    def test_traex_login_requires_models_after_successful_login(self):
        from types import SimpleNamespace
        settings = {'traexBinary': '/fake/traex'}
        b.atomic(self.m.directory / 'config.json', {'appId': 'dev-bot', 'backend': 'traex'})
        def login(argv, **kwargs):
            if argv[-2:] == ['login', 'status']: return SimpleNamespace(returncode=1)
            if argv[-2:] == ['debug', 'models']: return SimpleNamespace(returncode=1)
            return SimpleNamespace(returncode=0)
        with patch.object(self.m, 'validate', return_value=settings), patch('sys.stdin.isatty', return_value=True), \
                patch.object(b.subprocess, 'run', side_effect=login):
            with self.assertRaisesRegex(RuntimeError, '无法读取可用模型'):
                self.m.login_model()

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
        if sys.platform == 'win32':
            self.skipTest('POSIX fcntl only test')
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

    def test_windows_deployment_uses_one_fixed_executor_label(self):
        first = '303a0363-1d34-46af-8f99-c885203a7c7c'
        second = 'f32b3dc2-f745-4c75-ade0-0fed0d5c0eef'
        with patch.object(b.sys, 'platform', 'win32'):
            a = self.m.deployment_label(first)
            other = self.m.deployment_label(second)
        self.assertLessEqual(len(a), 80)
        self.assertEqual(a, other)
        self.assertEqual(self.m.label + '.deploy', a)

    def test_perform_active_runs_only_current_nonterminal_job(self):
        job, _ = self.fake()
        with patch.object(self.m, 'perform') as perform:
            self.m.perform_active()
        perform.assert_called_once_with(job['id'])
        job['state'] = 'succeeded'
        b.atomic(self.m.job_path(job['id']), job)
        with patch.object(self.m, 'perform') as perform:
            self.m.perform_active()
        perform.assert_not_called()

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

    def test_self_initiated_deploy_can_proceed_when_only_request_left_is_itself(self):
        job, actions = self.fake()
        job['selfInitiated'] = True
        b.atomic(self.m.job_path(job['id']), job)
        statuses = iter([
            {'pending': 3, 'running': 2, 'queued': 0, 'incoming': 0},
            {'pending': 2, 'running': 1, 'queued': 0, 'incoming': 0},
        ])

        def rpc(action='status'):
            actions.append(('rpc', action))
            if action == 'drain':
                self.assertNotIn(('stop', 'upgrade'), actions)
                return next(statuses)
            return {}

        self.m.rpc = rpc
        with patch.object(b.time, 'sleep', return_value=None):
            self.m.perform(job['id'])
        final = b.read(self.m.job_path(job['id']))
        self.assertEqual('succeeded', final['state'])
        self.assertEqual(2, actions.count(('rpc', 'drain')))
        self.assertIn(('stop', 'upgrade'), actions)

    def test_non_self_initiated_deploy_still_waits_for_real_pending_work(self):
        job, actions = self.fake()
        job['selfInitiated'] = False
        b.atomic(self.m.job_path(job['id']), job)
        statuses = iter([
            {'pending': 3, 'running': 2, 'queued': 0, 'incoming': 0},
            {'pending': 2, 'running': 1, 'queued': 0, 'incoming': 0},
            {'pending': 1, 'running': 0, 'queued': 0, 'incoming': 0},
            {'pending': 0, 'running': 0, 'queued': 0, 'incoming': 0},
        ])

        def rpc(action='status'):
            actions.append(('rpc', action))
            if action == 'drain':
                self.assertNotIn(('stop', 'upgrade'), actions)
                return next(statuses)
            return {}

        self.m.rpc = rpc
        with patch.object(b.time, 'sleep', return_value=None):
            self.m.perform(job['id'])
        final = b.read(self.m.job_path(job['id']))
        self.assertEqual('succeeded', final['state'])
        self.assertEqual(4, actions.count(('rpc', 'drain')))
        self.assertIn(('stop', 'upgrade'), actions)


class ServiceManagerTests(unittest.TestCase):
    """测试平台抽象层和服务管理器"""

    def test_platform_detection(self):
        """测试平台检测返回正确的服务管理器"""
        manager = b.get_service_manager('gui/1000', '/tmp/root')
        if sys.platform == 'darwin':
            self.assertIsInstance(manager, b.LaunchdManager)
        elif sys.platform == 'linux':
            self.assertIsInstance(manager, b.SystemdManager)
        elif sys.platform == 'win32':
            self.assertIsInstance(manager, b.WindowsServiceManager)

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


    def test_windows_quote_preserves_paths_and_quotes_spaces(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        self.assertEqual(r'C:\Python\python.exe', b._win_quote(r'C:\Python\python.exe'))
        self.assertEqual(r'"C:\Program Files\python.exe"', b._win_quote(r'C:\Program Files\python.exe'))

    def test_windows_xml_generation_has_restart_throttle_and_service_account(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        with tempfile.TemporaryDirectory() as tmpdir:
            mgr = b.WindowsServiceManager(tmpdir)
            xml = mgr._generate_xml('svc', [r'C:\Program Files\python.exe', 'worker', 'arg with space'],
                                    Path(tmpdir, 'logs/out.log'), password='secret')
            self.assertIn(r'<executable>C:\Program Files\python.exe</executable>', xml)
            self.assertIn('worker', xml)
            self.assertIn('arg with space', xml)
            self.assertIn('<onfailure action="restart" delay="3 sec"/>', xml)
            self.assertIn('<stoptimeout>15 sec</stoptimeout>', xml)
            self.assertIn('<password>secret</password>', xml)
            self.assertIn('<logpath>', xml)

    def test_windows_is_loaded_only_true_when_running(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with patch.object(b, 'run') as run:
            run.return_value = SimpleNamespace(returncode=0, stdout='STATE : 4 RUNNING')
            self.assertTrue(b.WindowsServiceManager('/r').is_loaded('svc'))
            run.return_value = SimpleNamespace(returncode=0, stdout='STATE : 1 STOPPED')
            self.assertFalse(b.WindowsServiceManager('/r').is_loaded('svc'))
            run.return_value = SimpleNamespace(returncode=1, stdout='')
            self.assertFalse(b.WindowsServiceManager('/r').is_loaded('svc'))

    def test_windows_alive_uses_process_exit_code(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        kernel32 = b.ctypes.windll.kernel32
        with patch.object(kernel32, 'OpenProcess', return_value=0), \
                patch.object(kernel32, 'GetLastError', return_value=87):
            self.assertFalse(b.alive(123))
        def active(_handle, code):
            code._obj.value = 259
            return 1
        with patch.object(kernel32, 'OpenProcess', return_value=456), \
                patch.object(kernel32, 'GetExitCodeProcess', side_effect=active), \
                patch.object(kernel32, 'CloseHandle') as close:
            self.assertTrue(b.alive(123))
            close.assert_called_once_with(456)

    def test_windows_load_installs_when_not_registered_and_strips_password_from_disk(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            mgr = b.WindowsServiceManager(tmpdir)
            calls = []
            def fake_run(argv, check=True):
                calls.append(argv)
                if argv[0] == 'sc.exe':
                    return SimpleNamespace(returncode=1, stdout='')  # 未注册
                return SimpleNamespace(returncode=0, stdout='')
            with patch.object(b, 'run', side_effect=fake_run), \
                    patch.object(b.shutil, 'which', return_value='C:/winsw/WinSW.exe'), \
                    patch.object(b.shutil, 'copy2'), \
                    patch.object(b.getpass, 'getpass', return_value='pw'), \
                    patch.object(b.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=True):
                mgr.load('svc', ['python', 'worker'], Path(tmpdir, 'logs/out.log'))
            self.assertTrue(any(c and c[-1] == 'install' for c in calls))
            self.assertIn(['sc.exe', 'start', 'svc'], calls)
            xml = (mgr.dir / 'svc.xml').read_text(encoding='utf-8')
            self.assertNotIn('pw', xml)

    def test_windows_load_updates_xml_when_registered_without_reinstall_or_password(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            mgr = b.WindowsServiceManager(tmpdir)
            calls = []
            def fake_run(argv, check=True):
                calls.append(argv)
                if argv[0] == 'sc.exe':
                    return SimpleNamespace(returncode=0, stdout='')  # 已注册
                return SimpleNamespace(returncode=0, stdout='')
            with patch.object(b, 'run', side_effect=fake_run), \
                    patch.object(b.shutil, 'which') as which, \
                    patch.object(b.shutil, 'copy2') as copy2, \
                    patch.object(b.getpass, 'getpass') as getpass, \
                    patch.object(b.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=False):
                mgr.load('svc', ['python', 'worker'], Path(tmpdir, 'logs/out.log'))
            getpass.assert_not_called()
            copy2.assert_not_called()
            which.assert_not_called()
            self.assertIn(['sc.exe', 'stop', 'svc'], calls)
            self.assertIn(['sc.exe', 'start', 'svc'], calls)

    def test_windows_deploy_runs_preprovisioned_limited_task(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            calls = []
            def fake_run(argv, check=True, timeout=25):
                calls.append(argv)
                return SimpleNamespace(returncode=0, stdout='')
            with patch.object(b, 'run', side_effect=fake_run):
                mgr = b.WindowsServiceManager(tmpdir)
                mgr.load_deploy('svc.deploy', ['ignored'], Path(tmpdir, 'deploy.log'))
            task = 'Agent IM Bridge svc.deploy deploy'
            self.assertEqual(['schtasks.exe', '/Query', '/TN', task], calls[0])
            self.assertEqual(['schtasks.exe', '/Run', '/TN', task], calls[1])
            self.assertFalse(any('install' in call for call in calls))

    def test_windows_unattended_setup_grants_service_rights_and_creates_limited_task(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            calls = []
            winsw = Path(tmpdir, 'deployment', 'winsw')
            winsw.mkdir(parents=True)
            (winsw / 'svc.xml').write_text('<service><username>yqmai</username></service>', encoding='utf-8')
            def fake_run(argv, check=True, timeout=25):
                calls.append(argv)
                if argv[:2] == ['sc.exe', 'query']:
                    return SimpleNamespace(returncode=0, stdout='STATE : 4 RUNNING')
                if argv[:2] == ['sc.exe', 'sdshow']:
                    return SimpleNamespace(returncode=0, stdout='D:(A;;CC;;;SY)S:(AU;SA;CC;;;WD)\n')
                if argv[0] == 'powershell.exe':
                    return SimpleNamespace(returncode=0, stdout='S-1-5-21-1-2-3-1001\n')
                return SimpleNamespace(returncode=0, stdout='')
            path = Path(tmpdir, 'state')
            with patch.object(b, 'run', side_effect=fake_run), \
                    patch.object(b.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=True):
                mgr = b.WindowsServiceManager(tmpdir)
                mgr.enable_unattended('svc', 'svc.deploy', ['python', 'worker.py'], [path])
            sdset = next(call for call in calls if call[:2] == ['sc.exe', 'sdset'])
            self.assertIn('(A;;CCLCSWRPWPLOCRRC;;;S-1-5-21-1-2-3-1001)S:', sdset[3])
            acl = next(call for call in calls if call[0] == 'icacls.exe')
            self.assertIn(os.environ.get('COMPUTERNAME', '.') + r'\yqmai:(OI)(CI)M', acl)
            create = next(call for call in calls if call[0] == 'powershell.exe' and '-EncodedCommand' in call)
            script = base64.b64decode(create[-1]).decode('utf-16le')
            self.assertIn('-LogonType S4U -RunLevel Limited', script)
            self.assertIn("Register-ScheduledTask -TaskName 'Agent IM Bridge svc.deploy deploy'", script)
            self.assertNotIn('password', script.lower())

    def test_windows_unattended_setup_finds_registered_service_from_root(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            winsw = Path(tmpdir, 'deployment', 'winsw')
            winsw.mkdir(parents=True)
            actual = 'top.ntutn.agent.bridge.actual.dev'
            (winsw / (actual + '.xml')).write_text('<service><username>yqmai</username></service>', encoding='utf-8')
            calls = []
            def fake_run(argv, check=True, timeout=25):
                calls.append(argv)
                if argv[:2] == ['sc.exe', 'query']:
                    return SimpleNamespace(returncode=0 if argv[2] == actual else 1, stdout='')
                if argv[:2] == ['sc.exe', 'sdshow']:
                    return SimpleNamespace(returncode=0, stdout='D:(A;;CC;;;SY)\n')
                if argv[0] == 'powershell.exe':
                    return SimpleNamespace(returncode=0, stdout='S-1-5-21-1-2-3-1001\n')
                return SimpleNamespace(returncode=0, stdout='')
            with patch.object(b, 'run', side_effect=fake_run), \
                    patch.object(b.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=True):
                b.WindowsServiceManager(tmpdir).enable_unattended(
                    'top.ntutn.agent.bridge.other.dev', 'top.ntutn.agent.bridge.other.dev.deploy',
                    ['python', 'worker.py'], [Path(tmpdir, 'state')])
            self.assertIn(['sc.exe', 'sdshow', actual], calls)
            create = next(call for call in calls if call[0] == 'powershell.exe' and '-EncodedCommand' in call)
            script = base64.b64decode(create[-1]).decode('utf-16le')
            self.assertIn('Agent IM Bridge top.ntutn.agent.bridge.other.dev.deploy deploy', script)

    def test_windows_missing_winsw_hints_install(self):
        if sys.platform != 'win32':
            self.skipTest('Windows only test')
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmpdir:
            with patch.object(b, 'run', return_value=SimpleNamespace(returncode=1, stdout='')), \
                    patch.object(b.shutil, 'which', return_value=None), \
                    patch.object(b.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=True):
                with self.assertRaisesRegex(RuntimeError, 'WinSW'):
                    b.WindowsServiceManager(tmpdir).load('svc', ['python', 'worker'], Path(tmpdir, 'out.log'))


if __name__ == '__main__': unittest.main()
