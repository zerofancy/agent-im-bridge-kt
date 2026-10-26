"""Activation state must be refreshed for every JVM generation in one launcher."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location(
    "launcher_activation_manager", Path(__file__).with_name("bridgectl.py")
)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class FakeChild:
    def __init__(self, pid, returncode):
        self.pid = pid
        self.returncode = returncode

    def poll(self):
        return self.returncode

    def send_signal(self, signum):
        self.returncode = 0

    def wait(self, timeout=None):
        return self.returncode


class LauncherActivationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="bridge-launcher-activation-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        # Selecting a real platform manager can create files outside the temporary root.
        with patch.object(bridge, "get_service_manager", return_value=Mock()):
            self.manager = bridge.Manager(self.root, "prod")
        self.release = self.root / "releases/r-test"
        (self.release / "lib").mkdir(parents=True)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()

    def launch_two_generations(self, held, activated_release=None):
        """First JVM exits after optional activation; second exits on a mocked SIGTERM."""
        environments = []
        handlers = {}
        children = []

        def popen(argv, **kwargs):
            environments.append(dict(kwargs["env"]))
            if len(environments) == 1:
                if activated_release is not None:
                    bridge.atomic(
                        self.manager.deploydir / "activated",
                        {"release": activated_release, "job": "test-job"},
                    )
                child = FakeChild(1001, 70)
            else:
                self.assertEqual(
                    2, len(environments), "launcher spawned an unexpected generation"
                )
                child = FakeChild(1002, None)
            children.append(child)
            return child

        def sleep(seconds):
            self.assertEqual(2, len(children))
            handlers[bridge.signal.SIGTERM](bridge.signal.SIGTERM, None)

        settings = {"java": "/fake/java", "workspace": str(self.workspace)}
        with (
            patch.object(self.manager, "verify", return_value=self.release),
            patch.object(self.manager, "validate", return_value=settings),
            patch.object(self.manager, "system_boot", return_value="test-boot"),
            patch.object(self.manager, "reachable", return_value=False),
            patch.object(self.manager, "wait_with_edge_wake", return_value=False),
            patch.object(bridge.subprocess, "Popen", side_effect=popen),
            patch.object(bridge.time, "sleep", side_effect=sleep),
            patch.object(
                bridge.signal,
                "signal",
                side_effect=lambda signum, handler: handlers.update({signum: handler}),
            ),
        ):
            result = self.manager.launch("r-test", held)

        self.assertEqual(0, result)
        self.assertEqual(2, len(environments))
        return [environment["BRIDGE_HOLD"] for environment in environments]

    def test_activated_release_unfreezes_jvm_restart_in_held_launcher(self):
        self.assertEqual(["1", "0"], self.launch_two_generations(True, "r-test"))

    def test_missing_activation_keeps_restarted_jvm_held(self):
        self.assertEqual(["1", "1"], self.launch_two_generations(True))

    def test_other_release_activation_keeps_restarted_jvm_held(self):
        self.assertEqual(["1", "1"], self.launch_two_generations(True, "r-other"))

    def test_non_held_launcher_never_freezes_jvm(self):
        self.assertEqual(["0", "0"], self.launch_two_generations(False))

    def test_already_activated_release_starts_unfrozen(self):
        bridge.atomic(
            self.manager.deploydir / "activated",
            {"release": "r-test", "job": "previous-job"},
        )
        self.assertEqual(["0", "0"], self.launch_two_generations(True))


if __name__ == "__main__":
    unittest.main()
