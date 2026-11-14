"""Hang detection for the launcher process. Decisions are pure; side effects are explicit."""

import contextlib
import json
import os
import signal
import subprocess
import time
import urllib.request

from state_files import atomic, read
from process_tree import kill_tree

DEFAULTS = {
    'enabled': True,
    'probeSeconds': 5,
    'probeTimeoutSeconds': 3,
    'probeFailures': 3,
    'startupGraceSeconds': 120,
    'heartbeatStaleSeconds': 30,
    'inboundStaleSeconds': 90,
    'telegramWarnSeconds': 90,
    'telegramStaleSeconds': 120,
    'termGraceSeconds': 10,
    'maxKillsPerBoot': 5,
    'minKillIntervalSeconds': 300,
}


def settings(manager):
    """runtime.json 的 watchdog 段与默认值合并；未知键忽略。"""
    raw = read(manager.directory / 'runtime.json', {}) or {}
    merged = dict(DEFAULTS)
    configured = raw.get('watchdog')
    if isinstance(configured, dict):
        for key, value in configured.items():
            if key in merged and value is not None:
                merged[key] = value
    return merged


def probe(manager, child_pid, timeout_seconds):
    """Read the control endpoint and GET /status. Only the current child generation is trusted."""
    endpoint = read(manager.directory / 'control/endpoint.json')
    lifecycle = read(manager.directory / 'lifecycle/current.json')
    if not endpoint or not lifecycle:
        return {'state': 'starting'}
    try:
        if int(endpoint.get('pid', 0)) != int(child_pid) or int(lifecycle.get('pid', 0)) != int(child_pid):
            return {'state': 'starting'}
    except (TypeError, ValueError):
        return {'state': 'starting'}
    if endpoint.get('bootId') != lifecycle.get('bootId'):
        return {'state': 'starting'}
    request = urllib.request.Request(
        'http://127.0.0.1:%d/health' % int(endpoint['port']),
        headers={'Authorization': 'Bearer ' + endpoint['token']},
        method='GET',
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                return {'state': 'unreachable', 'detail': 'http_%d' % response.status}
            return {'state': 'ok', 'status': json.load(response)}
    except Exception as error:
        return {'state': 'unreachable', 'detail': type(error).__name__}


class Watchdog:
    """Holds per-boot kill budget and per-child failure counters."""

    def __init__(self, config):
        self.config = config
        self.disabled = not config.get('enabled', True)
        self.failures = 0
        self.kills = 0
        self.last_kill_at = 0.0
        self.child_started_at = time.monotonic()
        self.hang_reason = None

    def new_child(self, started_at):
        self.failures = 0
        self.child_started_at = started_at
        self.hang_reason = None

    def evaluate(self, result, now):
        """Return (action, reason); action is 'none', 'warn' or 'kill'."""
        if self.disabled:
            return ('none', None)
        if now - self.child_started_at < self.config['startupGraceSeconds']:
            return ('none', None)
        if result['state'] == 'starting':
            # The endpoint never appearing means the JVM died before it could serve anything;
            # after the startup grace this has to count as a failure, not as "still loading".
            self.failures += 1
            if self.failures >= self.config['probeFailures']:
                return ('kill', 'startup_timeout')
            return ('none', None)
        if result['state'] == 'unreachable':
            self.failures += 1
            if self.failures >= self.config['probeFailures']:
                return ('kill', 'status_unreachable')
            return ('none', None)
        self.failures = 0
        status = result['status']
        heartbeat = status.get('heartbeatAgeMs')
        if heartbeat is None:
            # Older releases do not publish the heartbeat; never judge them.
            self.disabled = True
            return ('warn', 'missing_heartbeat')
        if heartbeat < 0 or heartbeat > self.config['heartbeatStaleSeconds'] * 1000:
            return ('kill', 'heartbeat_stale')
        loops = status.get('loops') or {}
        inbound = loops.get('inbound') or {}
        incoming = status.get('incoming', 0) or 0
        if incoming > 0 and int(inbound.get('dequeuedAgoMs', -1)) > self.config['inboundStaleSeconds'] * 1000:
            return ('kill', 'inbound_stalled')
        telegram = loops.get('telegram') or {}
        poll_ago = int(telegram.get('pollStartedAgoMs', -1))
        if poll_ago > self.config['telegramStaleSeconds'] * 1000:
            return ('kill', 'telegram_poll_stalled')
        if poll_ago > self.config['telegramWarnSeconds'] * 1000:
            return ('warn', 'telegram_poll_slow')
        return ('none', None)

    def allow_kill(self, now):
        if self.kills >= self.config['maxKillsPerBoot']:
            return False
        if self.last_kill_at and now - self.last_kill_at < self.config['minKillIntervalSeconds']:
            return False
        return True

    def record_kill(self, now, reason):
        self.kills += 1
        self.last_kill_at = now
        self.hang_reason = reason


def log(manager, message):
    path = manager.directory / 'logs' / 'watchdog.log'
    try:
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with path.open('a', encoding='utf-8') as handle:
            handle.write('%s %s\n' % (time.strftime('%Y-%m-%dT%H:%M:%S%z'), message))
    except OSError:
        pass


def _identity(manager, child_pid):
    """Identity of the current child only; stale on-disk generations are never borrowed."""
    identity = {}
    if child_pid is None:
        return identity
    try:
        pid = int(child_pid)
    except (TypeError, ValueError):
        return identity
    endpoint = read(manager.directory / 'control/endpoint.json', {}) or {}
    lifecycle = read(manager.directory / 'lifecycle/current.json', {}) or {}
    for source in (endpoint, lifecycle):
        try:
            matches = int(source.get('pid', -1) or -1) == pid
        except (TypeError, ValueError):
            matches = False
        if matches and source.get('bootId'):
            identity['bootId'] = source['bootId']
            break
    return identity


def hang_record(manager, reason, result, child_pid=None, release=None):
    status = result.get('status') or {}
    identity = _identity(manager, child_pid)
    payload = {
        'at': time.time(),
        'reason': reason,
        'pid': status.get('pid') or child_pid or identity.get('pid'),
        'bootId': status.get('bootId') or identity.get('bootId'),
        'release': status.get('release') or release,
        'environment': status.get('environment') or manager.env,
        'heartbeatAgeMs': status.get('heartbeatAgeMs'),
        'loops': status.get('loops'),
        'counts': {
            key: status.get(key) for key in ('incoming', 'pending', 'running', 'queued') if key in status
        },
    }
    directory = manager.directory / 'lifecycle'
    atomic(directory / 'hang.json', payload)
    atomic(directory / ('hang-%d.json' % int(time.time())), payload)
    for old in sorted(directory.glob('hang-*.json'))[:-4]:
        with contextlib.suppress(OSError):
            old.unlink()
    return payload


def record_state(manager, watchdog, decision):
    atomic(
        manager.deploydir / 'watchdog-state.json',
        {
            'updatedAt': time.time(),
            'decision': decision,
            'disabled': watchdog.disabled,
            'failures': watchdog.failures,
            'kills': watchdog.kills,
            'lastKillAt': watchdog.last_kill_at,
        },
    )


def terminate(child, grace_seconds):
    """Stop the child gracefully, then kill the whole tree. Returns True when force was needed."""
    if os.name == 'nt':
        # TerminateProcess would kill the root first and leave detached descendants behind;
        # taskkill /T /F has to run while the root still exists.
        kill_tree(child.pid)
        with contextlib.suppress(Exception):
            child.wait(timeout=5)
        return True
    with contextlib.suppress(OSError):
        child.send_signal(signal.SIGTERM)
    try:
        child.wait(timeout=grace_seconds)
        return False
    except subprocess.TimeoutExpired:
        pass
    kill_tree(child.pid)
    with contextlib.suppress(Exception):
        child.wait(timeout=5)
    return True
