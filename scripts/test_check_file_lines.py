import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check_file_lines', Path(__file__).with_name('check_file_lines.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class FileLinesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.policy = self.root / 'exceptions.json'
        self.policy.write_text('{}')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def check(self):
        return checker.check(self.root, self.policy)

    def test_boundary_and_unterminated_line(self):
        self.write('valid.kt', '\n' * 500)
        self.write('no-newline.kt', '\n' * 499 + 'last')
        self.assertEqual([], self.check())
        self.write('too-long.kt', '\n' * 500 + 'last')
        self.assertEqual(['too-long.kt: 501 行，上限 500'], self.check())

    def test_tracked_ignored_untracked_deleted_and_binary(self):
        self.write('.gitignore', '*.ignored\n')
        self.write('generated.ignored', '\n' * 501)
        self.write('tracked.ignored', '\n' * 501)
        deleted = self.write('deleted.kt', '\n' * 501)
        subprocess.run(['git', 'add', '-f', 'tracked.ignored', 'deleted.kt'], cwd=self.root, check=True)
        deleted.unlink()
        (self.root / 'image.bin').write_bytes(b'\0' + b'\n' * 501)
        self.assertEqual(['tracked.ignored: 501 行，上限 500'], self.check())

    def test_exact_exception_is_bounded(self):
        self.policy.write_text(json.dumps({'large.kt': {'max_lines': 502, 'reason': 'Legacy migration'}}))
        self.write('large.kt', '\n' * 502)
        self.assertEqual([], self.check())
        self.write('large.kt', '\n' * 503)
        self.assertEqual(['large.kt: 503 行，上限 502'], self.check())
        self.write('other/large.kt', '\n' * 501)
        self.assertEqual(2, len(self.check()))

    def test_exception_requires_reason_and_limit(self):
        for entry in ({'max_lines': 600}, {'max_lines': True, 'reason': 'x'},
                      {'max_lines': 500, 'reason': 'x'}, {'max_lines': 600, 'reason': ''}):
            self.policy.write_text(json.dumps({'a.kt': entry}))
            with self.assertRaises(ValueError):
                self.check()

    def test_symlink_is_not_followed(self):
        self.write('large.ignored', '\n' * 501)
        self.write('.gitignore', '*.ignored\n')
        (self.root / 'link.kt').symlink_to('large.ignored')
        self.assertEqual([], self.check())

    def test_hook_contract_for_success_failure_and_repeat(self):
        # Run a copy so the hook resolves the fixture repository, not the real workspace.
        script = self.root / 'scripts/check_file_lines.py'
        script.parent.mkdir()
        script.write_bytes(Path(checker.__file__).read_bytes())
        self.write('.codex/file-line-exceptions.json', '{}')
        for active, failing in ((False, False), (False, True), (True, True)):
            if failing:
                self.write('long.kt', '\n' * 501)
            result = subprocess.run(['python3', str(script), '--hook'], cwd=self.root,
                                    input=json.dumps({'hook_event_name': 'Stop', 'stop_hook_active': active}),
                                    text=True, capture_output=True, check=True)
            output = json.loads(result.stdout)
            self.assertEqual('block' if failing else None, output.get('decision'))
            if failing:
                self.assertIn('long.kt: 501', output['reason'])

    def test_malformed_hook_metadata_still_checks_files_and_returns_portable_json(self):
        script = self.root / 'scripts/check_file_lines.py'
        script.parent.mkdir()
        script.write_bytes(Path(checker.__file__).read_bytes())
        self.write('.codex/file-line-exceptions.json', '{}')
        for failing in (False, True):
            if failing:
                self.write('long.kt', '\n' * 501)
            for encoding in ('ascii', 'cp936', 'utf-8'):
                for payload in (b'{"message":"unescaped "quote"}', b'\xff\xfe', b''):
                    result = subprocess.run(
                        [sys.executable, str(script), '--hook'], cwd=self.root,
                        input=payload, capture_output=True, check=True,
                        env={**os.environ, 'PYTHONIOENCODING': encoding},
                    )
                    output = json.loads(result.stdout.decode('ascii'))
                    self.assertEqual('block' if failing else None, output.get('decision'))
                    if failing:
                        self.assertTrue(output['reason'].startswith('项目文件行数检查失败：\n'))
                        self.assertIn('long.kt: 501 行，上限 500', output['reason'])
                        self.assertNotIn('\ufffd', output['reason'])


if __name__ == '__main__':
    unittest.main()
