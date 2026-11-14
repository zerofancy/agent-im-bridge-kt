"""Watchdog decisions must not depend on anything but the status snapshot and its own budget."""

import json
import signal
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

import launcher_watchdog as w


class FakeManager:
    def __init__(self, root):
        self.root = Path(root)
        self.directory = self.root / 'environments/prod'
        self.deploydir = self.root / 'deployment/prod'
        self.env = 'prod'
        (self.directory / 'control').mkdir(parents=True, exist_ok=True)
        self.deploydir.mkdir(parents=True, exist_ok=True)

    def write_generation(self, pid, boot, port, token, lifecycle_pid=None):
        w.atomic(
            self.directory / 'control/endpoint.json',
            {'pid': pid, 'bootId': boot, 'port': port, 'token': token},
        )
        w.atomic(
            self.directory / 'lifecycle/current.json',
            {
                'pid': lifecycle_pid if lifecycle_pid is not None else pid,
                'bootId': boot,
                'startedAt': 't',
            },
        )


def healthy_status(**overrides):
    payload = {
        'heartbeatAgeMs': 100,
        'incoming': 0,
        'pending': 0,
        'running': 0,
        'queued': 0,
        'loops': {
            'inbound': {'dequeuedSeq': 1, 'dequeuedAgoMs': 300},
            'feishu': {'frameAgoMs': 100, 'pongAgoMs': 1000},
            'telegram': {'pollStartedAgoMs': -1, 'pollReturnedAgoMs': -1, 'inflightUpdateId': -1},
        },
    }
    payload.update(overrides)
    return payload


def config(**overrides):
    merged = dict(w.DEFAULTS)
    merged.update(overrides)
    return merged


class StatusServer:
    def __init__(self, payload, status=200):
        self.payload = payload
        self.status = status
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                outer.requests.append(self.path)
                body = json.dumps(outer.payload).encode()
                self.send_response(outer.status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.server.server_address[1]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class SettingsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='bridge-watchdog-')
        self.addCleanup(temporary.cleanup)
        self.manager = FakeManager(temporary.name)

    def test_defaults_apply_without_configuration(self):
        self.assertEqual(w.DEFAULTS, w.settings(self.manager))

    def test_runtime_overrides_unknown_keys_ignored(self):
        w.atomic(
            self.manager.directory / 'runtime.json',
            {'watchdog': {'heartbeatStaleSeconds': 60, 'nonsense': 1, 'enabled': False}},
        )
        merged = w.settings(self.manager)
        self.assertEqual(60, merged['heartbeatStaleSeconds'])
        self.assertFalse(merged['enabled'])
        self.assertNotIn('nonsense', merged)


class ProbeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='bridge-probe-')
        self.addCleanup(temporary.cleanup)
        self.manager = FakeManager(temporary.name)

    def test_ok_requires_matching_generation(self):
        server = StatusServer(healthy_status())
        self.addCleanup(server.close)
        self.manager.write_generation(1001, 'boot-a', server.port, 'token')
        result = w.probe(self.manager, 1001, 3)
        self.assertEqual('ok', result['state'])
        self.assertEqual(1, len(server.requests))

    def test_starting_when_child_generation_differs(self):
        server = StatusServer(healthy_status())
        self.addCleanup(server.close)
        self.manager.write_generation(1001, 'boot-a', server.port, 'token')
        self.assertEqual({'state': 'starting'}, w.probe(self.manager, 1002, 3))

    def test_starting_when_boot_id_differs(self):
        server = StatusServer(healthy_status())
        self.addCleanup(server.close)
        self.manager.write_generation(1001, 'boot-a', server.port, 'token')
        w.atomic(
            self.manager.directory / 'lifecycle/current.json',
            {'pid': 1001, 'bootId': 'boot-b', 'startedAt': 't'},
        )
        self.assertEqual({'state': 'starting'}, w.probe(self.manager, 1001, 3))

    def test_starting_when_endpoint_missing(self):
        self.assertEqual({'state': 'starting'}, w.probe(self.manager, 1001, 3))

    def test_unreachable_when_nothing_listens(self):
        self.manager.write_generation(1001, 'boot-a', 1, 'token')
        result = w.probe(self.manager, 1001, 1)
        self.assertEqual('unreachable', result['state'])


class DecisionTests(unittest.TestCase):
    def decide(self, status, now=1000.0, started=0.0, cfg=None):
        dog = w.Watchdog(cfg or config(startupGraceSeconds=0))
        dog.new_child(started)
        return dog.evaluate({'state': 'ok', 'status': status}, now)

    def test_healthy_is_none(self):
        self.assertEqual(('none', None), self.decide(healthy_status()))

    def test_startup_grace_suppresses_all_judgement(self):
        self.assertEqual(
            ('none', None),
            self.decide(
                healthy_status(heartbeatAgeMs=90_000),
                now=1000.0,
                started=950.0,
                cfg=config(startupGraceSeconds=120),
            ),
        )

    def test_stale_heartbeat_kills(self):
        self.assertEqual(('kill', 'heartbeat_stale'), self.decide(healthy_status(heartbeatAgeMs=31_000)))

    def test_long_turn_with_fresh_heartbeat_is_not_a_hang(self):
        self.assertEqual(
            ('none', None),
            self.decide(healthy_status(running=1, pending=2, heartbeatAgeMs=200)),
        )

    def test_inbound_backlog_without_progress_kills(self):
        status = healthy_status(
            incoming=3,
            loops={'inbound': {'dequeuedSeq': 7, 'dequeuedAgoMs': 95_000}},
        )
        self.assertEqual(('kill', 'inbound_stalled'), self.decide(status))

    def test_inbound_stall_without_backlog_is_idle(self):
        status = healthy_status(
            incoming=0,
            loops={'inbound': {'dequeuedSeq': 7, 'dequeuedAgoMs': 95_000}},
        )
        self.assertEqual(('none', None), self.decide(status))

    def test_telegram_slow_warns_then_stalls(self):
        status = healthy_status(
            loops={'telegram': {'pollStartedAgoMs': 100_000}},
        )
        self.assertEqual(('warn', 'telegram_poll_slow'), self.decide(status))
        status = healthy_status(loops={'telegram': {'pollStartedAgoMs': 130_000}})
        self.assertEqual(('kill', 'telegram_poll_stalled'), self.decide(status))

    def test_telegram_field_absent_on_feishu_never_triggers(self):
        status = healthy_status(loops={'telegram': {'pollStartedAgoMs': -1}})
        self.assertEqual(('none', None), self.decide(status))

    def test_missing_heartbeat_disables_judgement(self):
        dog = w.Watchdog(config(startupGraceSeconds=0))
        dog.new_child(0.0)
        status = healthy_status()
        del status['heartbeatAgeMs']
        first = dog.evaluate({'state': 'ok', 'status': status}, 1000.0)
        self.assertEqual(('warn', 'missing_heartbeat'), first)
        self.assertTrue(dog.disabled)
        self.assertEqual(('none', None), dog.evaluate({'state': 'ok', 'status': status}, 2000.0))

    def test_starting_becomes_a_failure_after_the_grace(self):
        dog = w.Watchdog(config(startupGraceSeconds=120, probeFailures=3))
        dog.new_child(0.0)
        self.assertEqual(('none', None), dog.evaluate({'state': 'starting'}, 130.0))
        self.assertEqual(('none', None), dog.evaluate({'state': 'starting'}, 135.0))
        self.assertEqual(('kill', 'startup_timeout'), dog.evaluate({'state': 'starting'}, 140.0))

    def test_starting_within_grace_is_never_a_failure(self):
        dog = w.Watchdog(config(startupGraceSeconds=120, probeFailures=3))
        dog.new_child(0.0)
        for tick in (5.0, 30.0, 60.0, 100.0):
            self.assertEqual(('none', None), dog.evaluate({'state': 'starting'}, tick))
        self.assertEqual(0, dog.failures)

    def test_consecutive_probe_failures_are_required(self):
        dog = w.Watchdog(config(startupGraceSeconds=0, probeFailures=3))
        dog.new_child(0.0)
        results = [dog.evaluate({'state': 'unreachable'}, 1000.0 + i) for i in range(3)]
        self.assertEqual(['none', 'none', 'kill'], [action for action, _ in results])
        self.assertEqual('status_unreachable', results[-1][1])

    def test_success_resets_failure_counter(self):
        dog = w.Watchdog(config(startupGraceSeconds=0, probeFailures=3))
        dog.new_child(0.0)
        dog.evaluate({'state': 'unreachable'}, 1000.0)
        dog.evaluate({'state': 'unreachable'}, 1001.0)
        dog.evaluate({'state': 'ok', 'status': healthy_status()}, 1002.0)
        self.assertEqual(0, dog.failures)

    def test_kill_budget_and_interval(self):
        dog = w.Watchdog(config(minKillIntervalSeconds=300, maxKillsPerBoot=2))
        dog.new_child(0.0)
        self.assertTrue(dog.allow_kill(1000.0))
        dog.record_kill(1000.0, 'heartbeat_stale')
        self.assertFalse(dog.allow_kill(1100.0))
        self.assertTrue(dog.allow_kill(1400.0))
        dog.record_kill(1400.0, 'heartbeat_stale')
        self.assertFalse(dog.allow_kill(2000.0))


class RecordTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='bridge-hang-')
        self.addCleanup(temporary.cleanup)
        self.manager = FakeManager(temporary.name)

    def test_hang_record_is_written_and_rotated(self):
        for index in range(6):
            w.atomic(
                self.manager.directory / ('lifecycle/hang-%d.json' % (1000 + index)),
                {'reason': 'old'},
            )
        payload = w.hang_record(
            self.manager,
            'heartbeat_stale',
            {'state': 'ok', 'status': healthy_status(heartbeatAgeMs=40_000, pid=1001)},
        )
        latest = w.read(self.manager.directory / 'lifecycle/hang.json', {})
        self.assertEqual('heartbeat_stale', latest['reason'])
        self.assertEqual(40_000, latest['heartbeatAgeMs'])
        self.assertEqual(1001, latest['pid'])
        self.assertEqual(40_000, payload['heartbeatAgeMs'])
        history = sorted((self.manager.directory / 'lifecycle').glob('hang-*.json'))
        self.assertEqual(4, len(history))

    def test_hang_record_keeps_identity_when_status_is_unreachable(self):
        w.atomic(
            self.manager.directory / 'lifecycle/current.json',
            {'pid': 4321, 'bootId': 'boot-a', 'startedAt': 't'},
        )
        payload = w.hang_record(
            self.manager, 'status_unreachable', {'state': 'unreachable'}, 4321, 'r-test'
        )
        self.assertEqual(4321, payload['pid'])
        self.assertEqual('boot-a', payload['bootId'])
        self.assertEqual('r-test', payload['release'])
        self.assertEqual('prod', payload['environment'])

    def test_hang_record_never_borrows_the_previous_generation(self):
        w.atomic(
            self.manager.directory / 'lifecycle/current.json',
            {'pid': 111, 'bootId': 'boot-old', 'startedAt': 't'},
        )
        (self.manager.deploydir / 'current').write_text('r-new', encoding='utf-8')
        payload = w.hang_record(self.manager, 'startup_timeout', {'state': 'starting'}, 222, 'r-new')
        self.assertEqual(222, payload['pid'])
        self.assertIsNone(payload['bootId'])
        self.assertEqual('r-new', payload['release'])
        self.assertEqual('prod', payload['environment'])

    def test_state_file_records_decision(self):
        dog = w.Watchdog(config())
        dog.new_child(0.0)
        dog.record_kill(1000.0, 'inbound_stalled')
        w.record_state(self.manager, dog, 'inbound_stalled')
        state = w.read(self.manager.deploydir / 'watchdog-state.json', {})
        self.assertEqual('inbound_stalled', state['decision'])
        self.assertEqual(1, state['kills'])


class TerminateTests(unittest.TestCase):
    def test_posix_graceful_stop_does_not_kill_the_tree(self):
        child = Mock()
        child.pid = 4242
        with patch.object(w, 'kill_tree') as kill:
            self.assertFalse(w.terminate(child, 10))
        child.send_signal.assert_called_once_with(signal.SIGTERM)
        kill.assert_not_called()

    def test_windows_kills_the_tree_before_the_root_is_reaped(self):
        child = Mock()
        child.pid = 4243
        with patch.object(w.os, 'name', 'nt'), patch.object(w, 'kill_tree') as kill:
            self.assertTrue(w.terminate(child, 10))
        kill.assert_called_once_with(4243)
        child.wait.assert_called_once()


if __name__ == '__main__':
    unittest.main()
