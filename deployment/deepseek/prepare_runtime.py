#!/usr/bin/env python3
"""Stage the exact npm lock closure; never use a mutable source checkout in a release."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from launcher import SUPPORT_FILES, VERSION, digest, runtime_files

VCS_FILES = {".gitignore", ".gitattributes", ".gitmodules", ".cvsignore", ".DS_Store",
             ".hgignore", ".hgtags", ".bzrignore", ".bzrtags"}
VCS_DIRECTORIES = {".git", ".svn", ".hg", ".bzr", "CVS", "SCCS"}


def remove_vcs_metadata(root: Path) -> None:
    # Gradle/Ant omits these by default; remove non-runtime metadata before
    # sealing so source and distributed integrity inventories stay identical.
    for directory, directories, files in os.walk(root):
        for name in list(directories):
            if name in VCS_DIRECTORIES:
                shutil.rmtree(Path(directory) / name)
                directories.remove(name)
        for name in files:
            if name in VCS_FILES:
                (Path(directory) / name).unlink()


def materialize_links(root: Path) -> None:
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in list(directories) + files:
            path = Path(directory) / name
            if not path.is_symlink():
                continue
            target = path.resolve(strict=True)
            target.relative_to(root.resolve())
            path.unlink()
            if target.is_dir():
                shutil.copytree(target, path, symlinks=False)
            else:
                shutil.copy2(target, path)


def stage(source: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    destination = output / "runtime"
    lock = source / "package-lock.json"
    manifest_path = destination / "runtime-manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("version") == VERSION and manifest.get("lockSha256") == digest(lock) \
                and manifest.get("files") == runtime_files(destination):
            materialize_links(destination)
            remove_vcs_metadata(destination)
            manifest["files"] = runtime_files(destination)
            manifest["supportFiles"] = {name: digest(source / name) for name in SUPPORT_FILES}
            manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            return
    npm = shutil.which("npm")
    if npm is None:
        raise RuntimeError("npm is required to prepare the pinned DeepSeek runtime")
    with tempfile.TemporaryDirectory(prefix=".deepseek-stage-", dir=output) as temporary:
        temporary_root = Path(temporary)
        staged = temporary_root / "runtime"
        staged.mkdir()
        for name in ("package.json", "package-lock.json"):
            shutil.copy2(source / name, staged / name)
        config = temporary_root / "npmrc"
        config.write_text("registry=https://registry.npmjs.org/\n", encoding="utf-8")
        environment = {key: value for key, value in os.environ.items()
                       if key in {"PATH", "HOME", "LANG", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
                                  "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                                  "http_proxy", "https_proxy", "all_proxy", "no_proxy"}}
        subprocess.run([npm, "ci", "--ignore-scripts", "--omit=dev", "--no-audit", "--no-fund",
                        "--userconfig", str(config)], cwd=staged, env=environment, check=True)
        materialize_links(staged)
        remove_vcs_metadata(staged)
        cli_package = staged / "node_modules/@deepseek-ai/dsh/package.json"
        if json.loads(cli_package.read_text(encoding="utf-8")).get("version") != VERSION:
            raise RuntimeError("the installed DeepSeek version does not match the release lock")
        manifest = {"schema": 1, "version": VERSION, "lockSha256": digest(lock),
                    "files": runtime_files(staged),
                    "supportFiles": {name: digest(source / name) for name in SUPPORT_FILES}}
        (staged / "runtime-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(str(staged), destination)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    stage(Path(__file__).resolve().parent, arguments.output.resolve())


if __name__ == "__main__":
    main()
