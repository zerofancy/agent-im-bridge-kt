"""Offline release gates for the pinned Codex plugin and optional native payload."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


source = Path(__file__).parent
payload = module("codex_payload_test_subject", source / "deepseek/codex_payload.py")
launcher = module("codex_payload_test_launcher", source / "deepseek/launcher.py")
distribution = module("codex_payload_test_distribution", source / "verify_deepseek_distribution.py")


class CodexPayloadTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="deepseek-codex-payload-")
        self.addCleanup(temporary.cleanup)
        self.distribution = Path(temporary.name)
        self.base = self.distribution / "libexec/deepseek"
        self.runtime = self.base / "runtime"
        self.plugin = self.runtime / "node_modules/@deepseek-ai/dsh-subagent-codex"
        self.codex = self.runtime / "node_modules/@openai/codex"
        suffix, triple, executable = payload.payload_platform()
        self.native = self.runtime / f"node_modules/@openai/codex-{suffix}"
        self.binary = self.native / "vendor" / triple / "bin" / executable
        self.write(self.plugin / "package.json", {
            "version": payload.PLUGIN_VERSION,
            "dependencies": {"@openai/codex": payload.CODEX_VERSION},
        })
        self.write(self.plugin / "lib/index.js", "export const name = 'fixture';")
        self.write(self.codex / "package.json", {
            "version": payload.CODEX_VERSION, "bin": {"codex": "bin/codex.js"},
            "optionalDependencies": {
                f"@openai/codex-{suffix}": f"npm:@openai/codex@{payload.CODEX_VERSION}-{suffix}",
            },
        })
        self.write(self.codex / "bin/codex.js", "// official wrapper fixture")
        self.write(self.native / "package.json", {"version": f"{payload.CODEX_VERSION}-{suffix}"})
        self.write(self.binary, "native-payload-fixture")
        self.binary.chmod(0o755)

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value) if isinstance(value, dict) else value, encoding="utf-8")

    def manifest(self):
        dsh = self.runtime / "node_modules/@deepseek-ai/dsh"
        self.write(dsh / "package.json", {"version": launcher.VERSION})
        self.write(dsh / "lib/bin.js", "// CLI fixture")
        for name in launcher.SUPPORT_FILES:
            self.write(self.base / name, "// support fixture")
        value = {
            "schema": 1, "version": launcher.VERSION,
            "files": launcher.runtime_files(self.runtime),
            "supportFiles": {name: launcher.digest(self.base / name) for name in launcher.SUPPORT_FILES},
            "codexPayload": payload.verify_codex_payload(self.runtime),
        }
        self.write(self.runtime / "runtime-manifest.json", value)
        return value

    def test_platform_matrix_is_explicit_and_unsupported_architecture_fails_closed(self):
        self.assertEqual(("linux-x64", "x86_64-unknown-linux-musl", "codex"), payload.payload_platform("linux", "x86_64"))
        self.assertEqual(("darwin-arm64", "aarch64-apple-darwin", "codex"), payload.payload_platform("darwin", "arm64"))
        self.assertEqual(("win32-arm64", "aarch64-pc-windows-msvc", "codex.exe"), payload.payload_platform("win32", "aarch64"))
        with self.assertRaisesRegex(RuntimeError, "No pinned"):
            payload.payload_platform("linux", "riscv64")

    def test_selected_payload_is_versioned_hashed_and_runtime_local(self):
        result = payload.verify_codex_payload(self.runtime)
        self.assertEqual("0.2.2-rc.1", result["pluginVersion"])
        self.assertEqual("0.153.4", result["codexVersion"])
        self.assertEqual(self.binary.relative_to(self.runtime).as_posix(), result["binary"])
        self.assertEqual(hashlib.sha256(self.binary.read_bytes()).hexdigest(), result["sha256"])

    def test_missing_optional_native_package_cannot_fall_back_to_host_path(self):
        self.binary.unlink()
        host = self.distribution / "host-bin"
        self.write(host / "codex", "#!/bin/sh\necho host-fallback-is-forbidden\n")
        (host / "codex").chmod(0o755)
        with patch.dict(os.environ, {"PATH": str(host)}), self.assertRaisesRegex(RuntimeError, "missing"):
            payload.verify_codex_payload(self.runtime)

    def test_wrong_native_version_and_changed_plugin_dependency_are_rejected(self):
        native_manifest = self.native / "package.json"
        original = native_manifest.read_text()
        self.write(native_manifest, {"version": "0.153.5"})
        with self.assertRaisesRegex(RuntimeError, "version mismatch"):
            payload.verify_codex_payload(self.runtime)
        native_manifest.write_text(original)
        self.write(self.plugin / "package.json", {
            "version": payload.PLUGIN_VERSION, "dependencies": {"@openai/codex": "^0.153.4"},
        })
        with self.assertRaisesRegex(RuntimeError, "pinned official"):
            payload.verify_codex_payload(self.runtime)

    def test_non_executable_and_nested_resolution_cannot_pass_release_gate(self):
        with patch.object(payload.os, "access", return_value=False), self.assertRaisesRegex(RuntimeError, "not executable"):
            payload.verify_codex_payload(self.runtime)
        (self.plugin / "node_modules/@openai/codex").mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, "nested Codex"):
            payload.verify_codex_payload(self.runtime)

    def test_distribution_checks_native_attestation_after_copy(self):
        manifest = self.manifest()
        distribution.verify(self.distribution)
        manifest["codexPayload"]["sha256"] = "0" * 64
        self.write(self.runtime / "runtime-manifest.json", manifest)
        with self.assertRaisesRegex(ValueError, "native payload"):
            distribution.verify(self.distribution)

    def test_distribution_detects_native_mutation_and_symbolic_links(self):
        self.manifest()
        self.binary.write_text("tampered")
        with self.assertRaisesRegex(Exception, "BRIDGE_DEEPSEEK_RUNTIME_INTEGRITY_FAILED"):
            distribution.verify(self.distribution)
        link = self.base / "host-link"
        link.symlink_to(self.binary)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            distribution.verify(self.distribution)


if __name__ == "__main__":
    unittest.main()
