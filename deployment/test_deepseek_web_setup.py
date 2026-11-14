"""Foreground-only bootstrap URLs for the isolated API-key configuration surface."""

from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import queue
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import deepseek_setup as setup
from state_files import atomic


class Terminal(io.StringIO):
    def isatty(self):
        return True


class DeepSeekWebSetupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="deepseek-web-setup-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / "environments/dev"
        self.snapshot = self.root / "releases/pinned"
        self.binary = self.snapshot / "libexec/deepseek/dsh"
        self.binary.parent.mkdir(parents=True)
        self.binary.write_text("#!/bin/sh\nexit 0\n")
        self.binary.chmod(0o755)
        self.node = self.root / "node"
        self.node.write_text("#!/bin/sh\nexit 0\n")
        self.node.chmod(0o755)
        self.settings = {"workspace": str(self.root / "workspace"), "deepseekNode": str(self.node)}
        self.manager = SimpleNamespace(
            directory=self.directory, env="dev", verify=Mock(return_value=self.snapshot),
            current=Mock(return_value="pinned"),
        )
        atomic(self.directory / "config.json", {"backend": "deepseek"})
        self.terminal = Terminal()
        self.child = SimpleNamespace(pid=12345, poll=Mock(return_value=None), wait=Mock(return_value=0))
        self.url = "http://127.0.0.1:40927/?token=complete-bootstrap-token"

    def checks(self):
        return [SimpleNamespace(returncode=0, stdout="v25.9.0"), *[
            SimpleNamespace(returncode=0, stdout='{"configured":%s,"writable":true,"source":"file"}' % value)
            for value in ("false", "true")
        ]]

    def test_ssh_handoff_prints_complete_url_and_forwarding_port_without_other_output(self):
        self.child.stdout = io.StringIO("API_KEY=must-not-print\nexternal error response\n" + f"dsh web: {self.url}\n")
        def confirm(prompt):
            self.assertIn(self.url, self.terminal.getvalue(), "Show the URL before asking the user to finish")
            return ""
        with (
            patch("sys.stdin.isatty", return_value=True), redirect_stdout(self.terminal),
            patch.object(setup, "_run", side_effect=self.checks()),
            patch.object(setup.subprocess, "Popen", return_value=self.child) as popen,
            patch.object(setup, "terminate_tree") as terminate, patch("builtins.input", side_effect=confirm),
            patch.dict(os.environ, {"SSH_CONNECTION": "ssh-session", "DEEPSEEK_API_KEY": "caller-secret"}),
        ):
            setup.login(self.manager, self.settings, "pinned")
        output = self.terminal.getvalue()
        self.assertIn(self.url, output)
        self.assertIn("ssh -L 40927:127.0.0.1:40927", output)
        self.assertNotIn("must-not-print", output)
        self.assertNotIn("external error response", output)
        self.assertNotIn("caller-secret", output)
        self.assertEqual([str(self.binary), "--bridge-login", "--no-open"], popen.call_args.args[0])
        arguments = popen.call_args.kwargs
        self.assertEqual(subprocess.DEVNULL, arguments["stdin"])
        self.assertEqual(subprocess.PIPE, arguments["stdout"])
        self.assertEqual(subprocess.STDOUT, arguments["stderr"])
        self.assertEqual(self.directory / "backend/deepseek-launch", arguments["cwd"])
        self.assertEqual(str(self.directory / "backend/deepseek"), arguments["env"]["DSH_HOME"])
        self.assertNotIn("DEEPSEEK_API_KEY", arguments["env"])
        terminate.assert_called_once_with(12345, 2)
        self.child.wait.assert_called_once_with(timeout=5)
        self.assertTrue(self.child.stdout.closed)

    def test_failed_startup_cleans_service_without_prompting_or_echoing_payload(self):
        self.child.stdout = io.StringIO("API_KEY=must-not-print\n" + "x" * 12000 + "\n")
        with (
            patch("sys.stdin.isatty", return_value=True), redirect_stdout(self.terminal),
            patch.object(setup, "_run", side_effect=self.checks()[:2]),
            patch.object(setup.subprocess, "Popen", return_value=self.child),
            patch.object(setup, "terminate_tree") as terminate, patch("builtins.input") as enter,
            self.assertRaisesRegex(RuntimeError, "入口未启动") as caught,
        ):
            setup.login(self.manager, self.settings, "pinned")
        self.assertNotIn("must-not-print", self.terminal.getvalue() + str(caught.exception))
        enter.assert_not_called()
        terminate.assert_called_once_with(12345, 2)
        self.assertTrue(self.child.stdout.closed)

    def test_redirected_output_cannot_capture_the_bootstrap_token(self):
        with (
            patch("sys.stdin.isatty", return_value=True), redirect_stdout(io.StringIO()),
            patch.object(setup, "_run", side_effect=self.checks()[:2]),
            patch.object(setup.subprocess, "Popen") as popen,
            self.assertRaisesRegex(ValueError, "交互终端"),
        ):
            setup.login(self.manager, self.settings, "pinned")
        popen.assert_not_called()

    def test_only_tokenized_loopback_readiness_urls_are_accepted(self):
        for line in (
            "dsh web: http://example.com:40927/?token=private\n",
            "dsh web: http://127.0.0.1:40927/\n",
            "dsh web: http://127.0.0.1:40927/?token=private&apiKey=private\n",
            "dsh web: http://user@127.0.0.1:40927/?token=private\n",
            "dsh web: http://@127.0.0.1:40927/?token=private\n",
            "dsh web: http://127.0.0.1:40927/?token=private#fragment\n",
            "dsh web: http://127.0.0.1:40927/?token=private extra-output\n",
        ):
            with self.subTest(line=line):
                self.assertIsNone(setup._bootstrap_url(line))
        ready = queue.Queue(maxsize=1)
        setup._read_bootstrap(io.StringIO("x" * 12000 + "\n" + f"dsh web: {self.url}\n"), ready)
        self.assertEqual((self.url, 40927), ready.get_nowait())
        self.assertTrue(ready.empty())


if __name__ == "__main__":
    unittest.main()
