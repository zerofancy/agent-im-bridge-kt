"""Provision the single remote Bridge account while its environment is stopped."""

import base64
import binascii
import getpass
import hashlib
import json
import os
import re
import secrets
import stat
import sys
from urllib.parse import quote
import warnings

from state_files import atomic


DEFAULT_PORT = 1315
PASSWORD_ITERATIONS = 600_000
MAX_MILLIS = (1 << 63) - 1
INVALID_CONFIG = '远程认证配置无效；请检查 remote-access.json 的格式与私有权限。'
FIELDS = {
    'enabled', 'port', 'passwordSalt', 'passwordHash', 'totpSecret',
    'lastStep', 'failures', 'blockedUntil',
}


def _read_config(path):
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 4096:
        raise ValueError(INVALID_CONFIG)
    if os.name != 'nt' and stat.S_IMODE(metadata.st_mode) not in (0o600, 0o400):
        raise ValueError(INVALID_CONFIG)

    def unique_object(pairs):
        result = dict(pairs)
        if len(pairs) != len(result):
            raise ValueError(INVALID_CONFIG)
        return result

    try:
        value = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
    except ValueError:
        raise ValueError(INVALID_CONFIG) from None
    if isinstance(value, dict) and len(value) == 1 and value.get('enabled') is False:
        return None
    if not isinstance(value, dict) or value.keys() != FIELDS or type(value['enabled']) is not bool:
        raise ValueError(INVALID_CONFIG)

    def integer(key, minimum, maximum):
        return type(value[key]) is int and minimum <= value[key] <= maximum

    if not (
        integer('port', 1, 65535)
        and integer('lastStep', -1, MAX_MILLIS // 30000)
        and integer('failures', 0, 11)
        and integer('blockedUntil', 0, MAX_MILLIS)
        and (value['failures'] >= 5) == (value['blockedUntil'] > 0)
    ):
        raise ValueError(INVALID_CONFIG)
    secret = value['totpSecret']
    if not isinstance(secret, str) or not re.fullmatch(r'[A-Z2-7]{32}', secret):
        raise ValueError(INVALID_CONFIG)
    for key, size in (('passwordSalt', 16), ('passwordHash', 32)):
        encoded = value[key]
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except (binascii.Error, TypeError, ValueError):
            raise ValueError(INVALID_CONFIG) from None
        if len(decoded) != size or base64.b64encode(decoded).decode('ascii') != encoded:
            raise ValueError(INVALID_CONFIG)
    return value


def _password():
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ValueError('设置远程认证需要前台终端。')
    with warnings.catch_warnings():
        warnings.simplefilter('error', getpass.GetPassWarning)
        try:
            password = getpass.getpass('远程固定密码：')
            confirmation = getpass.getpass('再次输入密码：')
        except (getpass.GetPassWarning, EOFError):
            raise ValueError('无法从终端安全读取密码。') from None
    if password != confirmation:
        raise ValueError('两次密码输入不一致。')
    try:
        encoded = password.encode('utf-8')
    except UnicodeError:
        raise ValueError('密码必须是有效的 UTF-8 文本。') from None
    if not password.strip() or len(encoded) > 1024:
        raise ValueError('密码不能为空白，UTF-8 编码最多 1024 字节。')
    return encoded


def _setup(path, environment, previous, port, rotate_totp):
    port = port if port is not None else (previous['port'] if previous else DEFAULT_PORT)
    if not 1 <= port <= 65535:
        raise ValueError('端口必须在 1 到 65535 之间。')
    password = _password()
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac('sha256', password, salt, PASSWORD_ITERATIONS)
    config = dict(
        previous or {},
        enabled=True,
        port=port,
        passwordSalt=base64.b64encode(salt).decode('ascii'),
        passwordHash=base64.b64encode(digest).decode('ascii'),
    )
    paired = previous is None or rotate_totp
    if paired:
        config.update(
            totpSecret=base64.b32encode(secrets.token_bytes(20)).decode('ascii'),
            lastStep=-1,
            failures=0,
            blockedUntil=0,
        )
    atomic(path, config)
    if paired:
        issuer = quote(f'Agent Bridge {environment}', safe='')
        secret = config['totpSecret']
        print(
            f'TOTP 配对地址：otpauth://totp/{issuer}:{environment}'
            f'?secret={secret}&issuer={issuer}&algorithm=SHA1&digits=6&period=30',
            file=sys.stderr,
            flush=True,
        )
    print(f'{environment} 远程认证已启用，监听 127.0.0.1:{port}。配置将在启动时加载。')


def configure(manager, args, locked):
    if manager.service.is_loaded(manager.label):
        raise ValueError(f'请先运行 bridgectl stop --env {manager.env}。')
    directory = manager.directory
    instance_lock = directory / 'bridge.lock'
    if directory.resolve() != directory or instance_lock.is_symlink():
        raise ValueError('环境目录或实例锁不能是符号链接。')
    path = directory / 'remote-access.json'
    try:
        with locked(instance_lock, record_lock=True):
            current = _read_config(path)
            if args.job_id == 'setup':
                _setup(path, manager.env, current, args.port, args.rotate_totp)
            else:
                if current and current['enabled']:
                    atomic(path, dict(current, enabled=False))
                print(f'{manager.env} 远程入口已关闭。')
    except BlockingIOError:
        raise ValueError(f'请先运行 bridgectl stop --env {manager.env}。') from None
