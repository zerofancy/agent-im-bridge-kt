#!/usr/bin/env python3
"""Check repository text files, including non-ignored untracked files."""
import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / '.codex/file-line-exceptions.json'
LIMIT = 500


def check(root=ROOT, policy=POLICY):
    exceptions = json.loads(policy.read_text(encoding='utf-8'))
    if not isinstance(exceptions, dict):
        raise ValueError('豁免登记必须是 JSON 对象')
    for name, entry in exceptions.items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or str(path) != name:
            raise ValueError(f'非法豁免路径：{name}')
        if (not isinstance(entry, dict) or type(entry.get('max_lines')) is not int
                or entry['max_lines'] <= LIMIT or not isinstance(entry.get('reason'), str)
                or not entry['reason'].strip()):
            raise ValueError(f'豁免须有大于 {LIMIT} 的 max_lines 和非空 reason：{name}')
    names = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
        cwd=root, timeout=10,
    ).decode('utf-8').split('\0')
    errors = []
    for name in sorted(set(names) - {''}):
        path = root / name
        # Deleted files, submodules and symlinks have no local text to check.
        if path.is_symlink() or not path.is_file():
            continue
        data = path.read_bytes()
        if b'\0' in data:
            continue
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            continue
        # Physical lines: blank lines/comments count; a final unterminated line counts too.
        count = text.count('\n') + int(bool(text) and not text.endswith('\n'))
        limit = exceptions.get(name, {}).get('max_lines', LIMIT)
        if count > limit:
            errors.append(f'{name}: {count} 行，上限 {limit}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hook', action='store_true', help='Codex Stop hook JSON protocol')
    args = parser.parse_args()
    try:
        if args.hook:
            payload = json.load(sys.stdin)
            if not isinstance(payload, dict) or payload.get('hook_event_name') != 'Stop':
                raise ValueError('预期 Codex Stop hook 输入')
        errors = check()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        errors = [f'行数检查无法完成：{error}']
    reason = ('项目文件行数检查失败：\n' + '\n'.join(errors)
              + '\n请按职责拆分文件。确需豁免时，在 .codex/file-line-exceptions.json '
                '逐文件记录原因和上限；不要压缩代码、删除说明或扩大通配范围绕过检查。')
    if args.hook:
        print(json.dumps({'decision': 'block', 'reason': reason} if errors else {}, ensure_ascii=False))
        return 0
    print(reason if errors else '文件行数检查通过（默认上限 500 行）。', file=sys.stderr if errors else sys.stdout)
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())
