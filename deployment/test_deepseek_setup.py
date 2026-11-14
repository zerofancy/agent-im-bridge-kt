"""Release resolution, foreground authentication and environment isolation for DeepSeek."""

import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from contextlib import redirect_stdout

import deepseek_setup as setup
from model_setup import model_paths
from state_files import atomic, read


spec = importlib.util.spec_from_file_location("deepseek_manager", Path(__file__).with_name("bridgectl.py"))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class DeepSeekSetupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="bridge-deepseek-setup-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        with patch.object(bridge, "get_service_manager", return_value=Mock()):
            self.manager = bridge.Manager(self.root, "dev")
        self.release = self.root / "releases/r-pinned"
        self.binary = self.release / "libexec/deepseek/dsh"
        self.binary.parent.mkdir(parents=True)
        self.binary.write_text("#!/bin/sh\nexit 0\n")
        self.binary.chmod(0o755)
        self.node = self.root / "node"
        self.node.write_text("#!/bin/sh\nexit 0\n")
        self.node.chmod(0o755)
        self.settings = {
            "workspace": str(self.root / "workspace"), "java": "/usr/bin/java",
            "deepseekNode": str(self.node),
        }
        atomic(self.manager.directory / "config.json", {"appId": "dev-bot", "backend": "deepseek"})
        self.manager.model_directories(self.settings)

    def status(self, configured=True, source="file"):
        return SimpleNamespace(returncode=0, stdout=json.dumps({
            "configured": configured, "source": source, "writable": True,
        }), stderr="")

    def run_login(self, subprocess_run, **kwargs):
        with (
            patch.object(self.manager, "validate", return_value=self.settings),
            patch.object(self.manager, "verify", return_value=self.release),
            patch.object(setup.subprocess, "run", side_effect=subprocess_run),
        ):
            self.manager.login_model("r-pinned", **kwargs)

    def test_init_records_interpreter_only_and_creates_owned_paths(self):
        config = self.root / "bot.json"
        atomic(config, {"appId": "dev-bot", "backend": "deepseek"})
        (self.manager.directory / "config.json").unlink()
        with (
            patch.object(setup, "node_path", return_value=str(self.node)),
            patch.object(bridge, "run", return_value=SimpleNamespace(stdout="", stderr='java version "21"')),
            patch.dict(os.environ, {"DSH_HOME": str(self.root / "desktop"), "DEEPSEEK_API_KEY": "private"}),
        ):
            self.manager.init(self.root / "workspace", config, "/usr/bin/java")
        runtime = read(self.manager.directory / "runtime.json")
        self.assertEqual(str(self.node), runtime["deepseekNode"])
        self.assertNotIn("deepseekBinary", runtime)
        self.assertNotIn("private", json.dumps(runtime))
        self.assertTrue((self.manager.directory / "backend/deepseek").is_dir())
        self.assertTrue((self.manager.directory / "backend/deepseek-launch").is_dir())

    def test_release_wrapper_and_isolated_cwd_are_used_without_inherited_keys(self):
        calls = []

        def invoke(argv, **kwargs):
            calls.append(argv)
            environment = kwargs["env"]
            self.assertEqual(self.manager.directory / "backend/deepseek-launch", kwargs["cwd"])
            self.assertEqual(str(self.manager.directory / "backend/deepseek"), environment["DSH_HOME"])
            self.assertEqual(str(self.manager.directory / "backend/deepseek/agents"), environment["DSH_AGENTS_HOME"])
            self.assertEqual(str(self.node), environment["BRIDGE_DEEPSEEK_NODE"])
            self.assertEqual("1", environment["DSH_TELEMETRY_DISABLED"])
            for key in ("DEEPSEEK_API_KEY", "OPENAI_API_KEY", "NODE_OPTIONS", "DSH_MODEL"):
                self.assertNotIn(key, environment)
            self.assertEqual("deepseek-official", environment["BRIDGE_DEEPSEEK_PROVIDER"])
            self.assertEqual("deepseek-v4-flash", environment["BRIDGE_DEEPSEEK_MODEL"])
            return SimpleNamespace(returncode=0, stdout="v25.9.0\n") if argv[-1] == "--version" else self.status()

        with patch.dict(os.environ, {
            "DSH_HOME": "/private/home", "DSH_MODEL": "wrong", "NODE_OPTIONS": "--require injected",
            "DEEPSEEK_API_KEY": "private", "OPENAI_API_KEY": "private", "BRIDGE_DEEPSEEK_PROVIDER": "wrong",
        }):
            self.run_login(invoke)
        self.assertEqual([[str(self.node), "--version"], [str(self.binary), "--bridge-auth-status"]], calls)

    def test_saved_oauth_route_replaces_caller_model_variables_for_status(self):
        atomic(self.manager.directory / "config.json", {
            "appId": "dev-bot", "backend": "deepseek", "deepseekProvider": "openai-codex",
            "deepseekModel": "gpt-6.1-sol",
        })
        def invoke(argv, **kwargs):
            self.assertEqual("openai-codex", kwargs["env"]["BRIDGE_DEEPSEEK_PROVIDER"])
            self.assertEqual("gpt-6.1-sol", kwargs["env"]["BRIDGE_DEEPSEEK_MODEL"])
            self.assertNotIn("OPENAI_API_KEY", kwargs["env"])
            if argv[-1] == "--version":
                return SimpleNamespace(returncode=0, stdout="v25.9.0")
            return SimpleNamespace(returncode=0, stdout=json.dumps({
                "configured": True, "writable": True, "kind": "grant",
            }))
        with patch.dict(os.environ, {
            "BRIDGE_DEEPSEEK_PROVIDER": "wrong", "BRIDGE_DEEPSEEK_MODEL": "wrong",
            "OPENAI_API_KEY": "caller-secret",
        }), patch.object(setup.subprocess, "Popen") as popen:
            self.run_login(invoke)
        popen.assert_not_called()

    def test_oauth_requires_grant_and_writable_metadata_without_a_source_field(self):
        for configured, writable, kind, expected in (
            (True, True, "grant", True), (False, True, None, False),
            (True, False, "grant", False), (True, True, "api-key", False),
        ):
            status = {"configured": configured, "writable": writable}
            if kind is not None:
                status["kind"] = kind
            with self.subTest(status=status), patch.object(setup, "_run", return_value=SimpleNamespace(
                returncode=0, stdout=json.dumps(status),
            )):
                self.assertEqual(expected, setup._configured(self.binary, {
                    "BRIDGE_DEEPSEEK_PROVIDER": "openai-codex",
                }, self.root))

    def test_auth_metadata_rejects_payload_and_invalid_types_without_echo(self):
        for status in (
            {"configured": True, "writable": True, "kind": "grant", "payload": "private-payload"},
            {"configured": True, "writable": True, "kind": "private-payload"},
            {"configured": "private-payload", "writable": True, "kind": "grant"},
            {"configured": True, "writable": "private-payload", "kind": "grant"},
        ):
            with self.subTest(status=status), patch.object(setup, "_run", return_value=SimpleNamespace(
                returncode=0, stdout=json.dumps(status),
            )), self.assertRaises(RuntimeError) as caught:
                setup._configured(self.binary, {"BRIDGE_DEEPSEEK_PROVIDER": "openai-codex"}, self.root)
            self.assertNotIn("private-payload", str(caught.exception))

    def test_oauth_login_identifies_the_target_and_inherits_terminal_descriptors(self):
        atomic(self.manager.directory / "config.json", {
            "appId": "dev-bot", "backend": "deepseek", "deepseekProvider": "openai-codex",
            "deepseekModel": "gpt-6.1-sol",
        })
        results = [SimpleNamespace(returncode=0, stdout="v25.9.0")]
        results += [SimpleNamespace(returncode=0, stdout=json.dumps(status)) for status in (
            {"configured": False, "writable": True},
            {"configured": True, "writable": True, "kind": "grant"},
        )]
        handoff = {}
        def invoke(argv, **kwargs):
            if argv[-1] == "--bridge-login":
                handoff.update(kwargs)
                return SimpleNamespace(returncode=0)
            return results.pop(0)
        output = io.StringIO()
        with (
            patch("sys.stdin.isatty", return_value=True), patch("builtins.input") as enter,
            patch.object(setup.subprocess, "Popen") as popen, redirect_stdout(output),
        ):
            self.run_login(invoke)
        environment = handoff["env"]
        self.assertEqual("openai-codex", environment["BRIDGE_DEEPSEEK_PROVIDER"])
        self.assertEqual("gpt-6.1-sol", environment["BRIDGE_DEEPSEEK_MODEL"])
        self.assertIs(setup.sys.stdin, handoff["stdin"])
        self.assertIs(output, handoff["stdout"])
        self.assertIs(setup.sys.stderr, handoff["stderr"])
        self.assertNotIn("capture_output", handoff)
        self.assertEqual(self.manager.directory / "backend/deepseek-launch", handoff["cwd"])
        self.assertEqual([], results, "The completed flow must recheck stored grant metadata")
        popen.assert_not_called()
        enter.assert_not_called()
        self.assertIn("openai-codex/gpt-6.1-sol", output.getvalue())
        self.assertIn("ChatGPT 账户授权", output.getvalue())
        self.assertNotIn("API Key", output.getvalue())

    def test_oauth_handoff_failure_does_not_echo_external_errors_or_launch_web(self):
        atomic(self.manager.directory / "config.json", {
            "appId": "dev-bot", "backend": "deepseek", "deepseekProvider": "openai-codex",
            "deepseekModel": "gpt-6.1-sol",
        })
        for outcome in (1, 0, OSError("private-authorization-response")):
            def invoke(argv, **kwargs):
                if argv[-1] == "--version":
                    return SimpleNamespace(returncode=0, stdout="v25.9.0")
                if argv[-1] == "--bridge-auth-status":
                    return SimpleNamespace(returncode=0, stdout='{"configured":false,"writable":true}')
                if isinstance(outcome, Exception):
                    raise outcome
                return SimpleNamespace(returncode=outcome)
            with (
                self.subTest(outcome=outcome), patch("sys.stdin.isatty", return_value=True),
                patch.object(setup.subprocess, "Popen") as popen, redirect_stdout(io.StringIO()),
                self.assertRaises(RuntimeError) as caught,
            ):
                self.run_login(invoke)
            self.assertNotIn("private-authorization-response", str(caught.exception))
            popen.assert_not_called()

    def test_invalid_saved_route_never_falls_back_to_caller_variables(self):
        for provider, model in (("", "gpt-6.1-sol"), ("openai-codex", None), ("private\nvalue", "model")):
            atomic(self.manager.directory / "config.json", {
                "appId": "dev-bot", "backend": "deepseek", "deepseekProvider": provider,
                "deepseekModel": model,
            })
            with self.subTest(provider=provider, model=model), patch.dict(os.environ, {
                "BRIDGE_DEEPSEEK_PROVIDER": "openai-codex", "BRIDGE_DEEPSEEK_MODEL": "gpt-6.1-sol",
            }), self.assertRaisesRegex(ValueError, "provider/model"):
                self.run_login(Mock(side_effect=AssertionError("must reject before spawning")))

    def test_unconfigured_auth_requires_foreground_and_never_starts_service(self):
        results = [SimpleNamespace(returncode=0, stdout="v25.9.0"), self.status(False)]
        with patch("sys.stdin.isatty", return_value=False), patch.object(setup.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(ValueError, "前台终端"):
                self.run_login(lambda *args, **kwargs: results.pop(0))
            popen.assert_not_called()

    def test_successful_foreground_login_closes_only_temporary_process(self):
        results = [SimpleNamespace(returncode=0, stdout="v25.9.0"), self.status(False), self.status()]
        child = Mock(pid=8765)
        child.poll.return_value = None
        child.stdout = io.StringIO("dsh web: http://127.0.0.1:4080/?token=test-bootstrap\n")
        with (
            patch("sys.stdin.isatty", return_value=True), patch("sys.stdout.isatty", return_value=True),
            patch("builtins.input", return_value=""),
            patch.object(setup.subprocess, "Popen", return_value=child) as popen,
            patch.object(setup, "terminate_tree") as terminate,
        ):
            self.run_login(lambda *args, **kwargs: results.pop(0))
        argv = popen.call_args.args[0]
        self.assertEqual([str(self.binary), "--bridge-login", "--no-open"], argv)
        self.assertEqual(subprocess.PIPE, popen.call_args.kwargs["stdout"])
        self.assertEqual(subprocess.STDOUT, popen.call_args.kwargs["stderr"])
        self.assertTrue(child.stdout.closed)
        terminate.assert_called_once_with(8765, 2)
        child.wait.assert_called_once_with(timeout=5)

    def test_cancelled_foreground_login_still_cleans_process(self):
        results = [SimpleNamespace(returncode=0, stdout="v25.9.0"), self.status(False)]
        child = Mock(pid=8765)
        child.poll.return_value = None
        child.stdout = io.StringIO("dsh web: http://127.0.0.1:4080/?token=test-bootstrap\n")
        with (
            patch("sys.stdin.isatty", return_value=True), patch("sys.stdout.isatty", return_value=True),
            patch("builtins.input", side_effect=EOFError),
            patch.object(setup.subprocess, "Popen", return_value=child),
            patch.object(setup, "terminate_tree") as terminate,
        ):
            with self.assertRaisesRegex(RuntimeError, "未完成"):
                self.run_login(lambda *args, **kwargs: results.pop(0))
        terminate.assert_called_once_with(8765, 2)

    def test_external_error_and_malformed_status_do_not_expose_payload(self):
        for result in (
            SimpleNamespace(returncode=1, stdout="secret-response", stderr="secret-response"),
            SimpleNamespace(returncode=0, stdout="secret-response", stderr=""),
        ):
            with self.subTest(result=result), self.assertRaises(RuntimeError) as caught:
                self.run_login(lambda argv, **kwargs: SimpleNamespace(returncode=0, stdout="v25.9.0")
                               if argv[-1] == "--version" else result)
            self.assertNotIn("secret-response", str(caught.exception))

    def test_environment_credentials_cannot_count_as_independent_authentication(self):
        results = [SimpleNamespace(returncode=0, stdout="v25.9.0"), self.status(source="env")]
        with patch("sys.stdin.isatty", return_value=False), self.assertRaisesRegex(ValueError, "前台终端"):
            self.run_login(lambda *args, **kwargs: results.pop(0))

    def test_invalid_node_and_project_env_fail_before_authentication(self):
        for version in ("v24.8.0", "v22.20.0", "unexpected-secret"):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Node >=24.9.0"):
                self.run_login(lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout=version))
        (self.manager.directory / "backend/deepseek-launch/.env").write_text("PRIVATE_KEY=private")
        with self.assertRaisesRegex(ValueError, "不能含 .env"):
            self.run_login(Mock(side_effect=AssertionError("should not execute")))

    def test_deepseek_home_overlap_is_rejected_before_path_creation(self):
        own = dict(self.settings, deepseekHome=str(self.root / "shared"))
        atomic(self.manager.directory / "runtime.json", own)
        atomic(self.root / "environments/prod/runtime.json", {
            "workspace": str(self.root / "peer-work"), "deepseekHome": str(self.root / "shared"),
        })
        with patch.object(bridge, "run") as java, self.assertRaisesRegex(ValueError, "重叠"):
            self.manager.validate()
        java.assert_not_called()
        self.assertFalse((self.root / "shared").exists())

    def test_deepseek_home_cannot_overlap_any_same_environment_backend_or_workspace(self):
        home = self.manager.directory / "backend/deepseek"
        roots = ["workspace", "codexHome", "doubaoHome", "traeHome", "traeCliHome", "opencodeHome"]
        for key in roots:
            for peer in (home, home / "nested", home.parent):
                with self.subTest(key=key, peer=peer), self.assertRaisesRegex(ValueError, "重叠"):
                    setup.validate_configuration(self.manager, dict(self.settings, **{key: str(peer)}), {"backend": "deepseek"})

    def test_deepseek_launch_cannot_overlap_same_environment_paths_or_its_home(self):
        launch = self.manager.directory / "backend/deepseek-launch"
        for key in ("workspace", "codexHome", "doubaoHome", "traeHome", "traeCliHome", "opencodeHome", "deepseekHome"):
            for peer in (launch, launch / "nested", launch.parent):
                with self.subTest(key=key, peer=peer), self.assertRaisesRegex(ValueError, "重叠"):
                    setup.validate_configuration(self.manager, dict(self.settings, **{key: str(peer)}), {"backend": "deepseek"})

    def test_deepseek_desktop_home_and_symlinked_overlap_are_rejected(self):
        desktop = self.root / "desktop-user/.dsh"
        desktop.mkdir(parents=True)
        with patch.object(Path, "home", return_value=desktop.parent):
            for home in (desktop, desktop / "bridge", desktop.parent):
                with self.subTest(home=home), self.assertRaisesRegex(ValueError, "桌面 .dsh"):
                    setup.validate_configuration(self.manager, dict(self.settings, deepseekHome=str(home)), {"backend": "deepseek"})
            alias = self.root / "home-alias"
            alias.symlink_to(desktop, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "重叠"):
                setup.validate_configuration(self.manager, dict(self.settings, deepseekHome=str(alias / "not-created")), {"backend": "deepseek"})

    def test_same_environment_deepseek_checks_do_not_change_legacy_backend_rules(self):
        shared = dict(self.settings, workspace=str(self.manager.directory / "backend/deepseek"))
        for backend in ("codex", "traex", "doubao", "opencode"):
            with self.subTest(backend=backend):
                setup.validate_configuration(self.manager, shared, {"backend": backend})

    def test_launch_directory_is_in_isolation_roots_and_prod_rejects_binary_override(self):
        self.assertIn((self.manager.directory / "backend/deepseek-launch").resolve(), model_paths(self.settings, self.manager.directory))
        with patch.object(bridge, "get_service_manager", return_value=Mock()):
            prod = bridge.Manager(self.root, "prod")
        with self.assertRaisesRegex(ValueError, "发布快照"):
            setup.validate_configuration(prod, dict(self.settings, deepseekBinary=str(self.binary)), {"backend": "deepseek"})
        with self.assertRaisesRegex(ValueError, "绝对路径"):
            setup.validate_configuration(self.manager, {"deepseekHome": "relative"}, {"backend": "deepseek"})

    def test_launcher_drops_provider_and_node_injection_before_starting_jvm(self):
        child = SimpleNamespace(pid=7654, returncode=0, poll=lambda: 0)
        with (
            patch.object(self.manager, "verify", return_value=self.release),
            patch.object(self.manager, "validate", return_value=self.settings),
            patch.object(self.manager, "system_boot", return_value="test-boot"),
            patch.object(self.manager, "reachable", return_value=False),
            patch.object(self.manager, "deploy_busy", return_value=True),
            patch.object(bridge.signal, "signal"),
            patch.object(bridge.subprocess, "Popen", return_value=child) as popen,
            patch.dict(os.environ, {"DEEPSEEK_API_KEY": "private", "NODE_OPTIONS": "injected", "DSH_HOME": "/desktop"}),
        ):
            self.assertEqual(0, self.manager.launch("r-pinned", False))
        environment = popen.call_args.kwargs["env"]
        self.assertNotIn("DEEPSEEK_API_KEY", environment)
        self.assertNotIn("NODE_OPTIONS", environment)
        self.assertEqual(str(self.manager.directory / "backend/deepseek"), environment["DSH_HOME"])
        self.assertEqual("r-pinned", environment["BRIDGE_RELEASE"])
        self.assertEqual(self.settings["workspace"], popen.call_args.kwargs["cwd"])

    def test_authentication_uses_requested_rollback_snapshot_instead_of_current(self):
        with (
            patch.object(self.manager, "validate", return_value=self.settings),
            patch.object(self.manager, "verify", return_value=self.release) as verify,
            patch.object(self.manager, "current", return_value="r-newer") as current,
            patch.object(setup.subprocess, "run", side_effect=[
                SimpleNamespace(returncode=0, stdout="v25.9.0"), self.status(),
            ]),
        ):
            self.manager.login_model("r-pinned")
        verify.assert_called_once_with("r-pinned")
        current.assert_not_called()

    def test_dev_override_rejects_raw_cli_even_when_it_claims_a_compatible_version(self):
        raw = self.root / "external/dsh"
        (raw.parent / "runtime").mkdir(parents=True)
        raw.write_text("#!/bin/sh\necho 0.2.2-rc.1\n")
        raw.chmod(0o755)
        (raw.parent / "runtime/runtime-manifest.json").write_text("{}")
        self.settings["deepseekBinary"] = str(raw)
        calls = []
        def invoke(argv, **kwargs):
            calls.append(argv)
            return SimpleNamespace(returncode=0, stdout="v25.9.0")
        with self.assertRaisesRegex(ValueError, "受控 wrapper"):
            self.run_login(invoke)
        self.assertEqual([[str(self.node), "--version"]], calls)

    def test_dev_override_requires_an_immutable_runtime_manifest(self):
        with self.assertRaisesRegex(ValueError, "固定运行清单"):
            setup.validate_configuration(self.manager, dict(self.settings, deepseekBinary=str(self.binary)), {"backend": "deepseek"})
        manifest = self.binary.parent / "runtime/runtime-manifest.json"
        manifest.parent.mkdir()
        manifest.write_text("{}")
        setup.validate_configuration(self.manager, dict(self.settings, deepseekBinary=str(self.binary)), {"backend": "deepseek"})

    def test_existing_environment_without_current_release_publishes_before_login(self):
        service = Mock()
        service.is_loaded.return_value = False
        for command in ("dev", "init"):
            argv = ["bridgectl", command, "--root", str(self.root), "--env", "dev"]
            with (
                self.subTest(command=command), patch("sys.argv", argv),
                patch("sys.stdin.isatty", return_value=True),
                patch.object(bridge, "get_service_manager", return_value=service),
                patch.object(bridge.Manager, "setup_dev"), patch.object(bridge.Manager, "setup_interactive"),
                patch.object(bridge.Manager, "validate", return_value=self.settings),
                patch.object(bridge.Manager, "active_job", return_value=None),
                patch.object(bridge.Manager, "publish", return_value="r-pinned") as publish,
                patch.object(bridge.Manager, "login_model") as login,
                patch.object(bridge.Manager, "verify", return_value=self.release),
                patch.object(bridge.Manager, "start"), patch.object(bridge.Manager, "wait_ready"),
            ):
                self.assertEqual(0, bridge.main())
                publish.assert_called_once()
                login.assert_called_once_with("r-pinned")

    def test_publish_manifest_covers_cli_and_policy_and_detects_tampering(self):
        distribution = self.root / "distribution"
        (distribution / "lib").mkdir(parents=True)
        (distribution / "lib/agent-im-bridge-kt.jar").write_bytes(b"jar")
        runtime = distribution / "libexec/deepseek/runtime"
        runtime.mkdir(parents=True)
        (runtime / "cli.js").write_text("cli")
        (runtime.parent / "bridge-policy.mjs").write_text("policy")
        release = self.manager.publish(distribution)
        snapshot = self.manager.verify(release)
        manifest = read(snapshot / "manifest.json")
        self.assertIn("libexec/deepseek/runtime/cli.js", manifest["files"])
        self.assertIn("libexec/deepseek/bridge-policy.mjs", manifest["files"])
        policy = snapshot / "libexec/deepseek/bridge-policy.mjs"
        policy.chmod(0o600)
        policy.write_text("modified")
        with self.assertRaisesRegex(ValueError, "校验失败"):
            self.manager.verify(release)


if __name__ == "__main__":
    unittest.main()
