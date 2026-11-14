"""Validate the copied distribution after Gradle's include/exclude rules have run."""

import importlib.util
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


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]).resolve())
    except Exception:
        print("DeepSeek distribution verification failed; rebuild the pinned runtime", file=sys.stderr)
        raise SystemExit(1)
