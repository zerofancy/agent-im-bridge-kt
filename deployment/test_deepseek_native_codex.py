"""Native Codex child settings are local, opt-in and separate from the parent route."""

import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import deepseek_setup as setup
from state_files import atomic
from test_deepseek_setup import bridge


class DeepSeekNativeCodexTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="bridge-dsh-native-codex-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        with patch.object(bridge, "get_service_manager", return_value=Mock()):
            self.manager = bridge.Manager(self.root, "dev")
        self.settings = {"workspace": str(self.root / "workspace"), "java": "/usr/bin/java"}

    def test_explicit_child_home_and_model_replace_inherited_values_only_in_full_access(self):
        home = self.manager.directory / "backend/codex"
        for mode in ("read-only", "workspace-write", "danger-full-access"):
            environment = setup.safe_environment({
                "CODEX_HOME": "/desktop", "BRIDGE_DEEPSEEK_CODEX_HOME": "/prod",
                "BRIDGE_DEEPSEEK_CODEX_MODEL": "wrong", "BRIDGE_DEEPSEEK_CODEX_ENABLED": "1",
                "OPENAI_API_KEY": "private", "NODE_OPTIONS": "injected",
            }, self.root / "dsh", self.root / "tmp", "/fixed/node", codex_home=home,
                codex_enabled=True, codex_model="child-model", sandbox_mode=mode)
            self.assertEqual("1" if mode == "danger-full-access" else "0",
                             environment["BRIDGE_DEEPSEEK_CODEX_ENABLED"])
            self.assertEqual(str(home), environment["BRIDGE_DEEPSEEK_CODEX_HOME"])
            self.assertEqual("child-model", environment["BRIDGE_DEEPSEEK_CODEX_MODEL"])
            for key in ("CODEX_HOME", "OPENAI_API_KEY", "NODE_OPTIONS"):
                self.assertNotIn(key, environment)
        environment = setup.safe_environment({}, self.root / "dsh", self.root / "tmp", "/fixed/node")
        self.assertEqual("0", environment["BRIDGE_DEEPSEEK_CODEX_ENABLED"])
        self.assertEqual("gpt-6-astra", environment["BRIDGE_DEEPSEEK_CODEX_MODEL"])
        self.assertNotIn("BRIDGE_DEEPSEEK_CODEX_HOME", environment)

    def test_enabled_native_configuration_requires_explicit_home_and_strict_types(self):
        with self.assertRaisesRegex(ValueError, "显式"):
            setup.safe_environment({}, self.root / "dsh", self.root / "tmp", "/fixed/node",
                                   codex_enabled=True, sandbox_mode="danger-full-access")
        for value in (None, 1, "true", {}):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "配置无效"):
                setup.validate_configuration(self.manager, self.settings,
                                             {"backend": "deepseek", "deepseekCodexEnabled": value})
        for value in (None, True, "", " ", "bad\nmodel"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "配置无效"):
                setup.validate_configuration(self.manager, self.settings,
                                             {"backend": "deepseek", "deepseekCodexModel": value})

    def test_enabled_child_home_cannot_share_workspace_other_backend_or_desktop(self):
        native = {"backend": "deepseek", "deepseekCodexEnabled": True}
        desktop = self.root / "desktop-user/.codex"
        with patch.object(Path, "home", return_value=desktop.parent):
            for home in (self.root / "workspace", self.manager.directory / "backend/opencode",
                         self.manager.directory / "backend/doubao", desktop, desktop / "nested"):
                with self.subTest(home=home), self.assertRaisesRegex(ValueError, "Codex 子智能体"):
                    setup.validate_configuration(self.manager, dict(self.settings, codexHome=str(home)), native)
            setup.validate_configuration(self.manager, dict(self.settings, codexHome=str(desktop)),
                                         {"backend": "deepseek", "deepseekCodexEnabled": False})
        with self.assertRaisesRegex(ValueError, "绝对路径"):
            setup.validate_configuration(self.manager, dict(self.settings, codexHome="relative"), native)

    def test_native_codex_home_cannot_point_into_the_other_environment(self):
        atomic(self.manager.directory / "runtime.json", dict(
            self.settings, codexHome=str(self.root / "environments/prod/backend/codex")))
        atomic(self.manager.directory / "config.json", {
            "appId": "dev-bot", "backend": "deepseek", "deepseekCodexEnabled": True,
        })
        with patch.object(bridge, "run") as java, self.assertRaisesRegex(ValueError, "覆盖|重叠"):
            self.manager.validate()
        java.assert_not_called()

    def test_foreground_auth_handoff_receives_explicit_native_settings_without_changing_parent(self):
        release = self.root / "releases/r-test"
        binary = release / "libexec/deepseek/dsh"
        binary.parent.mkdir(parents=True)
        binary.write_text("#!/bin/sh\nexit 0\n")
        binary.chmod(0o755)
        node = self.root / "node"
        node.write_text("#!/bin/sh\nexit 0\n")
        node.chmod(0o755)
        settings = dict(self.settings, deepseekNode=str(node))
        atomic(self.manager.directory / "config.json", {
            "appId": "dev-bot", "backend": "deepseek", "sandboxMode": "danger-full-access",
            "deepseekProvider": "deepseek-official", "deepseekModel": "parent-model",
            "deepseekCodexEnabled": True, "deepseekCodexModel": "child-model",
        })
        def invoke(argv, **kwargs):
            environment = kwargs["env"]
            self.assertEqual("1", environment["BRIDGE_DEEPSEEK_CODEX_ENABLED"])
            self.assertEqual(str(self.manager.directory / "backend/codex"), environment["BRIDGE_DEEPSEEK_CODEX_HOME"])
            self.assertEqual("child-model", environment["BRIDGE_DEEPSEEK_CODEX_MODEL"])
            self.assertEqual("parent-model", environment["BRIDGE_DEEPSEEK_MODEL"])
            return SimpleNamespace(returncode=0, stdout="v25.9.0" if argv[-1] == "--version" else
                                   '{"configured":true,"source":"file","writable":true}')
        with (patch.object(self.manager, "validate", return_value=settings),
              patch.object(self.manager, "verify", return_value=release),
              patch.object(setup.subprocess, "run", side_effect=invoke)):
            setup.login(self.manager, settings, "r-test")


if __name__ == "__main__":
    unittest.main()
