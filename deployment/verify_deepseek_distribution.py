"""Validate the copied distribution after Gradle's include/exclude rules have run."""

import importlib.util
import json
from pathlib import Path
import sys


def verify(distribution):
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location(
        "deepseek_distribution_verifier", Path(__file__).parent / "deepseek/launcher.py"
    )
    launcher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(launcher)
    base = distribution / "libexec/deepseek"
    if any(path.is_symlink() for path in base.rglob("*")):
        raise ValueError("DeepSeek distribution contains symbolic links")
    launcher.verify_runtime(base)
    payload_spec = importlib.util.spec_from_file_location(
        "deepseek_codex_payload_verifier", Path(__file__).parent / "deepseek/codex_payload.py"
    )
    payload = importlib.util.module_from_spec(payload_spec)
    payload_spec.loader.exec_module(payload)
    manifest = json.loads((base / "runtime/runtime-manifest.json").read_text(encoding="utf-8"))
    if payload.verify_codex_payload(base / "runtime") != manifest.get("codexPayload"):
        raise ValueError("DeepSeek distribution Codex native payload does not match its manifest")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]).resolve())
    except Exception:
        print("DeepSeek distribution verification failed; rebuild the pinned runtime", file=sys.stderr)
        raise SystemExit(1)
