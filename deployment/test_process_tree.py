"""Process tree termination must reach grandchildren, not only the direct child."""

import os
import subprocess
import time
import unittest

import process_tree


def alive(pid):
    # A zombie keeps answering signal 0 until reaped; treat it as dead for this test's purpose.
    try:
        state = subprocess.run(
            ['ps', '-o', 'stat=', '-p', str(pid)],
            capture_output=True,
            text=True,
            timeout=5,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return False
    return bool(state) and not state.startswith('Z')


def wait_dead(pid, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not alive(pid):
            return True
        time.sleep(0.1)
    return False


@unittest.skipIf(os.name == 'nt', 'POSIX signals only')
class ProcessTreeTests(unittest.TestCase):
    def spawn(self, script):
        child = subprocess.Popen(['/bin/sh', '-c', script])
        self.addCleanup(self.reap, child)
        return child

    def reap(self, child):
        if child.poll() is None:
            process_tree.kill_tree(child.pid)
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass

    def test_descendants_include_grandchildren(self):
        child = self.spawn('sh -c "sleep 60" & wait')
        time.sleep(0.3)
        found = process_tree.descendants(child.pid)
        self.assertTrue(found, 'no descendants detected')
        self.assertNotIn(child.pid, found)

    def test_kill_tree_reaches_every_process(self):
        child = self.spawn('sh -c "sleep 60" & sleep 60 & wait')
        time.sleep(0.3)
        found = process_tree.descendants(child.pid)
        self.assertGreaterEqual(len(found), 2)
        process_tree.kill_tree(child.pid)
        self.assertTrue(wait_dead(child.pid), 'root survived')
        for pid in found:
            self.assertTrue(wait_dead(pid, timeout=2.0), 'descendant %d survived' % pid)

    def test_terminate_tree_escalates_to_sigkill_when_term_is_ignored(self):
        # The shell ignores SIGTERM and keeps looping, so only SIGKILL can end it.
        child = self.spawn('trap "" TERM; while true; do sleep 1; done')
        time.sleep(0.3)
        process_tree.terminate_tree(child.pid, grace_seconds=0.5)
        self.assertTrue(wait_dead(child.pid), 'root survived SIGKILL')
        for pid in process_tree.descendants(child.pid):
            self.assertTrue(wait_dead(pid, timeout=2.0))

    def test_kill_tree_tolerates_missing_pid(self):
        process_tree.kill_tree(999999999)


if __name__ == '__main__':
    unittest.main()
