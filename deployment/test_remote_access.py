import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

import bridgectl
import remote_access
from state_files import atomic


class RemoteAccessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.manager = bridgectl.Manager(self.root, 'dev')
        self.path = self.manager.directory / 'remote-access.json'

    def run_remote(self, action, *options, password='  合成口令 Test  ', tty=True, running=False):
        stdout, stderr = io.StringIO(), io.StringIO()
        argv = ['bridgectl', 'remote', action, '--root', str(self.root), '--env', 'dev', *options]
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), \
                patch('sys.argv', argv), patch('sys.stdin.isatty', return_value=tty), \
                patch.object(stderr, 'isatty', return_value=tty), \
                patch.object(self.manager.service, 'is_loaded', return_value=running), \
                patch.object(bridgectl, 'Manager', return_value=self.manager), \
                patch.object(remote_access.getpass, 'getpass', side_effect=[password, password]):
            self.assertEqual(0, bridgectl.main())
        return stdout.getvalue(), stderr.getvalue()

    def test_setup_writes_private_utf8_verifier_and_displays_pairing_only_on_terminal(self):
        password = '  合成口令 Test  '
        output, terminal = self.run_remote('setup', password=password)
        config = json.loads(self.path.read_text(encoding='utf-8'))
        self.assertTrue(config['enabled'])
        self.assertEqual(1315, config['port'])
        salt = base64.b64decode(config['passwordSalt'])
        self.assertEqual(16, len(salt))
        self.assertEqual(
            hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, 600_000),
            base64.b64decode(config['passwordHash']),
        )
        self.assertNotIn(password, self.path.read_text(encoding='utf-8'))
        self.assertNotIn(config['totpSecret'], output)
        self.assertNotIn(password, terminal)
        parameters = parse_qs(urlsplit(terminal.split('otpauth://', 1)[1].strip()).query)
        self.assertEqual([config['totpSecret']], parameters['secret'])
        self.assertEqual(['30'], parameters['period'])
        self.assertEqual(['6'], parameters['digits'])
        if os.name != 'nt':
            self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertFalse((self.root / 'environments' / 'prod').exists())

    def test_password_change_and_disable_preserve_totp_state(self):
        self.run_remote('setup', '--port', '1316')
        config = remote_access._read_config(self.path)
        config.update(lastStep=100, failures=5, blockedUntil=123456)
        atomic(self.path, config)

        output, terminal = self.run_remote('disable', tty=False)
        self.assertIn('已关闭', output)
        self.assertEqual('', terminal)
        self.assertEqual(dict(config, enabled=False), remote_access._read_config(self.path))

        _, terminal = self.run_remote('setup', password='different password')
        self.assertEqual('', terminal)
        updated = remote_access._read_config(self.path)
        self.assertTrue(updated['enabled'])
        self.assertNotEqual(config['passwordHash'], updated['passwordHash'])
        for key in ('port', 'totpSecret', 'lastStep', 'failures', 'blockedUntil'):
            self.assertEqual(config[key], updated[key])

        _, terminal = self.run_remote('setup', '--rotate-totp')
        rotated = remote_access._read_config(self.path)
        self.assertNotEqual(updated['totpSecret'], rotated['totpSecret'])
        self.assertIn(rotated['totpSecret'], terminal)
        self.assertEqual((-1, 0, 0), tuple(rotated[key] for key in ('lastStep', 'failures', 'blockedUntil')))

    def test_setup_requires_terminal_and_stopped_service(self):
        with self.assertRaisesRegex(ValueError, '需要前台终端'):
            self.run_remote('setup', tty=False)
        with self.assertRaisesRegex(ValueError, 'bridgectl stop --env dev'):
            self.run_remote('setup', running=True)
        self.assertFalse(self.path.exists())

    def test_invalid_config_is_preserved(self):
        malformed = b'{"enabled":true,"password":"fake-plaintext-secret"}'
        atomic(self.path, malformed)
        for command in ('setup', 'disable'):
            with self.assertRaisesRegex(ValueError, '远程认证配置无效') as error:
                self.run_remote(command)
            self.assertNotIn('fake-plaintext-secret', str(error.exception))
            self.assertEqual(malformed, self.path.read_bytes())

    def test_setup_rejects_invalid_port_before_prompt(self):
        with self.assertRaisesRegex(ValueError, '端口必须在'):
            self.run_remote('setup', '--port', '65536')
        self.assertFalse(self.path.exists())

    def test_setup_accepts_minimal_disabled_config(self):
        atomic(self.path, {'enabled': False})
        self.run_remote('setup')
        self.assertTrue(remote_access._read_config(self.path)['enabled'])


if __name__ == '__main__':
    unittest.main()
