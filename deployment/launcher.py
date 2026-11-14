"""Launcher process: owns the JVM child, restarts it, and judges whether it stopped working."""

import os
import signal
import subprocess
import sys
import time

from launcher_watchdog import (
    Watchdog,
    hang_record,
    log,
    probe,
    record_state,
    settings,
    terminate,
)
from state_files import atomic, read


def run_launcher(manager, release, held):
    release_path = manager.verify(release)
    runtime = manager.validate()
    for directory in ('tmp', 'attachments', 'logs', 'control', 'lifecycle'):
        (manager.directory / directory).mkdir(parents=True, exist_ok=True, mode=0o700)
    env = {
        key: value
        for key, value in os.environ.items()
        if key
        not in (
            'CODEX_HOME',
            'TRAE_HOME',
            'TRAECLI_HOME',
            'JAVA_TOOL_OPTIONS',
            '_JAVA_OPTIONS',
            'JDK_JAVA_OPTIONS',
            'CLASSPATH',
        )
    }
    default_path = (
        '/usr/local/bin:/usr/bin:/bin'
        if sys.platform == 'linux'
        else '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin'
    )
    env.update(
        BRIDGE_ROOT=str(manager.root),
        BRIDGE_ENV=manager.env,
        BRIDGE_RELEASE=release,
        TMPDIR=str(manager.directory / 'tmp'),
        PATH=runtime.get('path', default_path),
    )
    classpath = os.pathsep.join(str(f) for f in sorted((release_path / 'lib').glob('*.jar')))
    argv = [
        runtime['java'],
        '-Djava.io.tmpdir=' + str(manager.directory / 'tmp'),
        '-cp',
        classpath,
        'top.ntutn.agent.bridge.MainKt',
    ]

    stopping = {'value': False}
    current = {'child': None}

    def terminate_service(signum, frame):
        stopping['value'] = True
        child = current['child']
        if child is not None and child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGTERM, terminate_service)
    signal.signal(signal.SIGINT, terminate_service)

    boot = manager.system_boot()
    breaker_path = manager.directory / 'lifecycle/restart-breaker.json'
    breaker = read(breaker_path, {})
    if not breaker or breaker.get('boot') != boot or manager.reachable():
        attempts = 0
    else:
        attempts = int(breaker.get('attempts', 0))

    watchdog = Watchdog(settings(manager))
    if watchdog.disabled:
        log(manager, 'watchdog disabled by runtime.json')

    while True:
        activated = read(manager.deploydir / 'activated', {})
        env['BRIDGE_HOLD'] = '1' if held and activated.get('release') != release else '0'
        child = subprocess.Popen(argv, env=env, cwd=runtime['workspace'], stdin=subprocess.DEVNULL)
        current['child'] = child
        started = time.monotonic()
        watchdog.new_child(started)
        next_probe = started + watchdog.config['probeSeconds']
        while child.poll() is None:
            if stopping['value']:
                terminate(child, 20)
                return 0
            time.sleep(2)
            if attempts and time.monotonic() - started >= manager.HEALTHY_DURATION:
                attempts = 0
            now = time.monotonic()
            if watchdog.disabled or now < next_probe:
                continue
            next_probe = now + watchdog.config['probeSeconds']
            result = probe(manager, child.pid, watchdog.config['probeTimeoutSeconds'])
            action, reason = watchdog.evaluate(result, now)
            if action == 'kill':
                if manager.deploy_busy():
                    log(manager, 'watchdog skip %s: deployment active' % reason)
                elif watchdog.allow_kill(now):
                    detail = hang_record(manager, reason, result, child.pid)
                    log(
                        manager,
                        'watchdog kill %s pid=%s heartbeatAgeMs=%s loops=%s'
                        % (
                            reason,
                            detail.get('pid'),
                            detail.get('heartbeatAgeMs'),
                            detail.get('loops'),
                        ),
                    )
                    watchdog.record_kill(now, reason)
                    terminate(child, watchdog.config['termGraceSeconds'])
                else:
                    log(manager, 'watchdog suppress %s: kill budget exhausted' % reason)
                record_state(manager, watchdog, reason)
            elif action == 'warn':
                log(manager, 'watchdog warn %s pid=%s' % (reason, child.pid))
                record_state(manager, watchdog, reason)
        code = child.returncode
        current['child'] = None
        hung = watchdog.hang_reason
        watchdog.hang_reason = None
        life = read(manager.directory / 'lifecycle/current.json', {})
        if life.get('pid') == child.pid:
            observed = {key: life[key] for key in ('pid', 'startedAt', 'bootId')}
            if code < 0:
                observed['signal'] = -code
            else:
                observed['exitCode'] = code
            if hung:
                observed['hangReason'] = hung
            atomic(manager.directory / 'lifecycle/observed-exit.json', observed)
        mapped = code if code >= 0 else 128 - code
        if stopping['value']:
            return 0
        # A deployment owns stop/start while draining and activating; never respawn behind its back.
        if manager.deploy_busy():
            return mapped
        if hung:
            attempts += 1
        delay = manager.backoff_delay(attempts)
        woken = manager.wait_with_edge_wake(delay, stopping)
        if not woken:
            if not hung:
                attempts += 1
            atomic(
                breaker_path,
                {
                    'attempts': attempts,
                    'boot': boot,
                    'lastCode': mapped,
                    'since': time.time(),
                },
            )
