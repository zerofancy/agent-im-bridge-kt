"""Metadata-only authentication and fixed Web launch contract."""
import contextlib
import io
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import launcher


class LauncherAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "DSH_HOME": "/isolated/dev/backend/deepseek",
            "DSH_AGENTS_HOME": "/isolated/dev/backend/deepseek/agents",
            "BRIDGE_DEEPSEEK_NODE": "/absolute/node",
            "BRIDGE_DEEPSEEK_SANDBOX_MODE": "danger-full-access",
            "BRIDGE_DEEPSEEK_PROVIDER": "openai-codex",
            "BRIDGE_DEEPSEEK_MODEL": "gpt-6.1-sol",
        }

    def test_caller_api_keys_and_node_injection_do_not_join_subscription_environment(self):
        incoming = {**self.environment, "OPENAI_API_KEY": "unrelated",
                    "NODE_OPTIONS": "unrelated", "DEEPSEEK_API_KEY": "unrelated"}
        result = launcher.controlled_environment(incoming)
        self.assertEqual(result["BRIDGE_DEEPSEEK_PROVIDER"], "openai-codex")
        self.assertEqual(result["BRIDGE_DEEPSEEK_MODEL"], "gpt-6.1-sol")
        for key in ("OPENAI_API_KEY", "DEEPSEEK_API_KEY", "NODE_OPTIONS"):
            self.assertNotIn(key, result)

    def test_login_uses_only_owned_web_overlay_and_loopback(self):
        deepseek = {**self.environment, "BRIDGE_DEEPSEEK_PROVIDER": "deepseek-official",
                    "BRIDGE_DEEPSEEK_MODEL": "deepseek-v4-flash"}
        with patch.object(launcher, "verify_runtime", return_value=Path("/snapshot/cli.js")), \
                patch.object(launcher, "controlled_environment", return_value=deepseek), \
                patch.object(launcher, "node_path", return_value=Path("/absolute/node")), \
                patch.object(launcher, "validate_profiles") as validate, \
                patch.object(launcher.os, "execve") as execute:
            launcher.main(["--bridge-login"])
        validate.assert_called_once_with(Path(self.environment["DSH_HOME"]), "web")
        command, arguments, environment = execute.call_args.args
        self.assertEqual(command, "/absolute/node")
        self.assertEqual(arguments[:4], ["/absolute/node", "/snapshot/cli.js", "--profile", "web"])
        self.assertEqual(arguments[4], "--patch")
        self.assertEqual(Path(arguments[5]).name, "auth.patch.yml")
        self.assertEqual(arguments[6:], ["--host", "127.0.0.1", "--port", "0"])
        self.assertEqual(environment, deepseek)

    def test_oauth_status_preserves_only_public_record_metadata(self):
        response = types.SimpleNamespace(returncode=0, stdout=json.dumps({
            "configured": True, "writable": True, "kind": "grant", "payload": "must not escape",
        }))
        output = io.StringIO()
        with patch.object(launcher, "verify_runtime", return_value=Path("/snapshot/cli.js")), \
                patch.object(launcher, "controlled_environment", return_value=self.environment), \
                patch.object(launcher, "node_path", return_value=Path("/absolute/node")), \
                patch.object(launcher, "validate_profiles"), \
                patch.object(launcher.subprocess, "run", return_value=response) as run, \
                contextlib.redirect_stdout(output):
            result = launcher.main(["--bridge-auth-status"])
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(output.getvalue()), {"configured": True, "writable": True, "kind": "grant"})
        self.assertEqual(run.call_args.kwargs["cwd"], Path.cwd())

    def test_login_can_leave_browser_opening_to_the_foreground_controller(self):
        with patch.object(launcher, "verify_runtime", return_value=Path("/snapshot/cli.js")), \
                patch.object(launcher, "controlled_environment", return_value=self.environment), \
                patch.object(launcher, "node_path", return_value=Path("/absolute/node")), \
                patch.object(launcher, "validate_profiles"), \
                patch.object(launcher.os, "execve") as execute:
            launcher.main(["--bridge-login", "--no-open"])
        self.assertEqual(execute.call_args.args[1][-1], "--no-open")
        self.assertEqual(Path(execute.call_args.args[1][1]).name, "oauth-login.mjs")

    def test_oauth_check_uses_authorization_helper_without_opening_web(self):
        with patch.object(launcher, "verify_runtime", return_value=Path("/snapshot/cli.js")), \
                patch.object(launcher, "controlled_environment", return_value=self.environment), \
                patch.object(launcher, "node_path", return_value=Path("/absolute/node")), \
                patch.object(launcher, "validate_profiles") as validate, \
                patch.object(launcher.os, "execve") as execute:
            launcher.main(["--bridge-login", "--check"])
        validate.assert_called_once_with(Path(self.environment["DSH_HOME"]), "acp")
        self.assertEqual(Path(execute.call_args.args[1][1]).name, "oauth-login.mjs")
        self.assertEqual(execute.call_args.args[1][2:], ["--check"])

    def test_native_codex_is_explicit_and_does_not_inherit_desktop_home(self):
        incoming = {**self.environment, "CODEX_HOME": "/unrelated/desktop",
                    "BRIDGE_DEEPSEEK_CODEX_ENABLED": "1",
                    "BRIDGE_DEEPSEEK_CODEX_HOME": "/isolated/dev/backend/codex",
                    "BRIDGE_DEEPSEEK_CODEX_MODEL": "gpt-6.1-sol"}
        result = launcher.controlled_environment(incoming)
        self.assertEqual(result["BRIDGE_DEEPSEEK_CODEX_ENABLED"], "1")
        self.assertEqual(result["BRIDGE_DEEPSEEK_CODEX_HOME"], "/isolated/dev/backend/codex")
        self.assertEqual(result["BRIDGE_DEEPSEEK_CODEX_MODEL"], "gpt-6.1-sol")
        self.assertNotIn("CODEX_HOME", result)
        for mode in ("read-only", "workspace-write"):
            restricted = launcher.controlled_environment({**incoming, "BRIDGE_DEEPSEEK_SANDBOX_MODE": mode})
            self.assertEqual(restricted["BRIDGE_DEEPSEEK_CODEX_ENABLED"], "0")
            self.assertNotIn("BRIDGE_DEEPSEEK_CODEX_HOME", restricted)

    def test_native_codex_rejects_missing_ambiguous_or_overlapping_settings(self):
        enabled = {**self.environment, "BRIDGE_DEEPSEEK_CODEX_ENABLED": "1",
                   "BRIDGE_DEEPSEEK_CODEX_HOME": "/isolated/dev/backend/codex"}
        for invalid in (
            {"BRIDGE_DEEPSEEK_CODEX_ENABLED": "true"},
            {"BRIDGE_DEEPSEEK_CODEX_HOME": "relative"},
            {"BRIDGE_DEEPSEEK_CODEX_HOME": str(Path.home() / ".codex")},
            {"BRIDGE_DEEPSEEK_CODEX_HOME": self.environment["DSH_HOME"]},
            {"BRIDGE_DEEPSEEK_CODEX_MODEL": "bad\nmodel"},
        ):
            with self.subTest(keys=list(invalid)):
                with self.assertRaisesRegex(launcher.LaunchError, "CODEX_CONFIGURATION_INVALID"):
                    launcher.controlled_environment({**enabled, **invalid})
        with self.assertRaisesRegex(launcher.LaunchError, "CODEX_CONFIGURATION_INVALID"):
            launcher.controlled_environment({**self.environment, "BRIDGE_DEEPSEEK_CODEX_ENABLED": "1"})


if __name__ == "__main__":
    unittest.main()
