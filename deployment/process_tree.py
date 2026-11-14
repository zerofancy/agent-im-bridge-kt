"""Process tree inspection and termination shared by the launcher watchdog and bridgectl."""

import contextlib
import os
import signal
import subprocess
import time


def _signal(pid, signum):
    with contextlib.suppress(OSError):
        os.kill(pid, signum)


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False


def descendants(pid):
    """Child pids breadth-first. Windows trees are terminated by taskkill instead."""
    if os.name == 'nt':
        return []
    try:
        output = subprocess.run(
            ['ps', '-A', '-o', 'pid=', '-o', 'ppid='],
            capture_output=True,
            text=True,
            timeout=10,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    children = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        try:
            child, parent = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        children.setdefault(parent, []).append(child)
    found = []
    queue = [int(pid)]
    seen = set(queue)
    while queue:
        current = queue.pop(0)
        for child in children.get(current, []):
            if child in seen:
                continue
            seen.add(child)
            found.append(child)
            queue.append(child)
    return found


def kill_tree(pid):
    """Immediately SIGKILL pid and its descendants, leaves first. Windows uses taskkill /T /F."""
    pid = int(pid)
    if os.name == 'nt':
        subprocess.run(
            ['taskkill', '/PID', str(pid), '/T', '/F'],
            capture_output=True,
            timeout=20,
        )
        return
    targets = [pid] + descendants(pid)
    for target in reversed(targets):
        _signal(target, signal.SIGKILL)


def terminate_tree(pid, grace_seconds=0):
    """SIGTERM the whole tree, wait up to grace_seconds, then SIGKILL whatever is left."""
    pid = int(pid)
    if os.name == 'nt':
        kill_tree(pid)
        return
    targets = [pid] + descendants(pid)
    for target in targets:
        _signal(target, signal.SIGTERM)
    deadline = time.monotonic() + max(0, grace_seconds)
    while grace_seconds and time.monotonic() < deadline and any(_alive(t) for t in targets):
        time.sleep(0.2)
    for target in reversed(targets):
        if _alive(target):
            _signal(target, signal.SIGKILL)
