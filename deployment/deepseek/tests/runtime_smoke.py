#!/usr/bin/env python3
"""Keyless integration against the shipped wrapper and an isolated fake-adapter release."""
from __future__ import annotations

import argparse
import errno
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from urllib.parse import urlparse

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from launcher import SUPPORT_FILES, runtime_files


class Client:
    def __init__(self, base: Path, work: Path, environment: dict):
        self.process = subprocess.Popen([str(base / "dsh"), "--profile", "acp"], cwd=work,
                                        env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, bufsize=0)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ, "stdout")
        self.selector.register(self.process.stderr, selectors.EVENT_READ, "stderr")
        self.buffer = b""
        self.errors = b""
        self.responses = {}
        self.updates = []
        self.next_id = 0

    def send(self, method: str, params: dict, notification=False):
        frame = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            self.next_id += 1
            frame["id"] = self.next_id
        self.process.stdin.write(json.dumps(frame).encode() + b"\n")
        self.process.stdin.flush()
        return frame.get("id")

    def pump(self, wait: float):
        for key, _ in self.selector.select(wait):
            chunk = os.read(key.fileobj.fileno(), 65536)
            if not chunk:
                self.selector.unregister(key.fileobj)
                continue
            if key.data == "stderr":
                self.errors = (self.errors + chunk)[-10000:]
                continue
            self.buffer += chunk
            while b"\n" in self.buffer:
                line, self.buffer = self.buffer.split(b"\n", 1)
                frame = json.loads(line)
                if "id" in frame and "method" not in frame:
                    self.responses[frame["id"]] = frame
                elif frame.get("method") == "session/update":
                    self.updates.append(frame["params"])
                elif "id" in frame:
                    self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": frame["id"],
                        "result": {"outcome": {"outcome": "cancelled"}}}).encode() + b"\n")
                    self.process.stdin.flush()

    def response(self, request_id, timeout=25):
        deadline = time.monotonic() + timeout
        while request_id not in self.responses:
            self.pump(min(0.1, max(0, deadline - time.monotonic())))
            if self.process.poll() is not None or time.monotonic() >= deadline:
                raise AssertionError("wrapper RPC did not settle: " + self.errors.decode(errors="replace"))
        frame = self.responses.pop(request_id)
        assert "error" not in frame, frame
        return frame["result"]

    def call(self, method, params):
        return self.response(self.send(method, params))

    def close(self):
        self.process.stdin.close()
        deadline = time.monotonic() + 10
        while self.process.poll() is None and time.monotonic() < deadline:
            self.pump(0.1)
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
            raise AssertionError("wrapper did not exit after stdin EOF")
        assert self.process.returncode == 0, self.errors.decode(errors="replace")
        self.selector.close()

    def crash(self):
        self.process.kill()
        self.process.wait(timeout=10)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()
        self.selector.close()


def environment(home: Path, node: str, mode: str) -> dict:
    return {"PATH": os.environ["PATH"], "HOME": str(home / "os-home"),
            "DSH_HOME": str(home / "dsh"), "DSH_AGENTS_HOME": str(home / "agents"),
            "BRIDGE_DEEPSEEK_NODE": node, "BRIDGE_DEEPSEEK_SANDBOX_MODE": mode,
            "BRIDGE_DEEPSEEK_PROVIDER": "deepseek-official", "BRIDGE_DEEPSEEK_MODEL": "deepseek-v4-flash"}


def initialize(client: Client):
    result = client.call("initialize", {"protocolVersion": 1, "clientCapabilities": {}})
    assert result["protocolVersion"] == 1
    assert set(result["agentCapabilities"]["sessionCapabilities"]) == {"list", "resume", "close"}


def web_login_smoke(base: Path, work: Path, environment_: dict):
    process = subprocess.Popen([str(base / "dsh"), "--bridge-login", "--no-open"], cwd=work,
                               env=environment_, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    output = b""
    errors = b""
    try:
        deadline = time.monotonic() + 25
        origin = None
        while time.monotonic() < deadline and process.poll() is None:
            for key, _ in selector.select(0.1):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                elif key.data == "stdout":
                    output += data
                else:
                    errors = (errors + data)[-5000:]
            matched = re.search(rb"dsh web: (http://127\.0\.0\.1:\d+[^\s]*)", output)
            if matched:
                origin = matched.group(1).decode()
                break
        assert origin is not None, "Web login did not bind loopback: " + errors.decode(errors="replace")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                             urllib.request.HTTPCookieProcessor(CookieJar()))
        with opener.open(origin, timeout=10) as response:
            assert response.status == 200
            assert response.read(64)
    finally:
        selector.close()
        process.terminate()
        try:
            process.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise AssertionError("Web login did not stop after SIGTERM")


def oauth_login_smoke(base: Path, work: Path, environment_: dict):
    process = subprocess.Popen([str(base / "dsh"), "--bridge-login", "--no-open"], cwd=work,
                               env=environment_, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    output = b""
    try:
        deadline = time.monotonic() + 20
        url = None
        while time.monotonic() < deadline and process.poll() is None:
            for key, _ in selector.select(0.1):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                elif key.data == "stdout":
                    output += data
            for line in output.splitlines():
                event = json.loads(line)
                if event.get("type") == "authorization-url":
                    url = event["url"]
            if url is not None:
                break
        assert url is not None, "OAuth helper did not enter its public authorization flow"
        assert urlparse(url).hostname == "127.0.0.1" and urlparse(url).port != 1455
        time.sleep(0.2)
        assert process.poll() is None, "stdin EOF prematurely ended the callback wait"
        with urllib.request.urlopen(url, timeout=10) as response:
            assert response.status == 200
        remaining, errors = process.communicate(timeout=15)
        assert process.returncode == 0, errors.decode(errors="replace")
        events = [json.loads(line) for line in (output + remaining).splitlines()]
        assert events[-1] == {"type": "authorized", "configured": True, "kind": "grant"}
        status = subprocess.run([str(base / "dsh"), "--bridge-auth-status"], cwd=work,
                                env=environment_, capture_output=True, text=True, timeout=20)
        assert status.returncode == 0
        assert json.loads(status.stdout) == {"configured": True, "writable": True, "kind": "grant"}
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()


def copy_release(source: Path, target: Path):
    target.mkdir()
    for name in SUPPORT_FILES:
        shutil.copy2(source / name, target / name)
    def copy_file(original, copied):
        try:
            os.link(original, copied)
        except OSError as error:
            if error.errno != errno.EXDEV:
                raise
            shutil.copy2(original, copied)
    shutil.copytree(source / "runtime", target / "runtime", copy_function=copy_file)
    adapter = target / "runtime/node_modules/@deepseek-ai/dsh-llm-deepseek-api-key/lib/index.js"
    adapter.unlink()
    shutil.copy2(Path(__file__).with_name("keyless-provider.mjs"), adapter)
    oauth = target / "runtime/node_modules/@earendil-works/pi-ai/dist/auth/oauth/openai-codex.js"
    oauth.unlink()
    shutil.copy2(Path(__file__).with_name("keyless-oauth.mjs"), oauth)
    manifest = target / "runtime/runtime-manifest.json"
    content = json.loads(manifest.read_text(encoding="utf-8"))
    manifest.unlink()  # Break the fixture hard link before writing; source stays immutable.
    content["files"] = runtime_files(target / "runtime")
    manifest.write_text(json.dumps(content, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run(base: Path, node: str):
    with tempfile.TemporaryDirectory(prefix="bridge-deepseek-runtime-smoke-") as temporary, \
            tempfile.TemporaryDirectory(prefix=".bridge-deepseek-launch-smoke-", dir=Path.home()) as launch_root:
        root = Path(temporary)
        release = root / "release"
        copy_release(base, release)
        work = Path(launch_root) / "launch"
        work.mkdir()
        workspace = Path(launch_root) / "project"
        workspace.mkdir()
        outside = Path(launch_root) / "outside"
        outside.mkdir()
        (workspace / "escape").symlink_to(outside, target_is_directory=True)
        catalog_home = root / "catalog-state"
        catalog_environment = environment(catalog_home, node, "danger-full-access")
        catalog_environment.update({"BRIDGE_DEEPSEEK_PROVIDER": "openai-codex", "BRIDGE_DEEPSEEK_MODEL": "gpt-6.1-sol"})
        catalog = Client(base, work, catalog_environment)
        try:
            initialize(catalog)
            created = catalog.call("session/new", {"cwd": str(workspace), "mcpServers": []})
            model_option = next(option for option in created["configOptions"] if option["id"] == "model")
            options = [item for group in model_option["options"]
                       for item in (group["options"] if "group" in group else [group])]
            selected = next(option for option in options if option["name"] == "GPT-6.1 Sol")
            assert "gpt-6.1-sol" in selected["value"]
            assert model_option["currentValue"] == selected["value"]
            catalog.call("session/close", {"sessionId": created["sessionId"]})
        finally:
            catalog.close()
        status = subprocess.run([str(base / "dsh"), "--bridge-auth-status"], cwd=work,
                                env=catalog_environment, capture_output=True, text=True, timeout=20)
        assert status.returncode == 0, status.stderr
        assert json.loads(status.stdout) == {"configured": False, "writable": True}
        check = subprocess.run([str(base / "dsh"), "--bridge-login", "--check"], cwd=work,
                               env=catalog_environment, capture_output=True, text=True, timeout=20)
        assert check.returncode == 0, check.stderr
        assert json.loads(check.stdout) == {"provider": "openai-codex", "method": "oauth", "available": True}
        print("PASS: unchanged runtime exact openai-codex/gpt-6.1-sol catalog and absent OAuth metadata; no prompt")
        web_environment = {**catalog_environment, "BRIDGE_DEEPSEEK_PROVIDER": "deepseek-official",
                           "BRIDGE_DEEPSEEK_MODEL": "deepseek-v4-flash"}
        web_login_smoke(base, work, web_environment)
        print("PASS: isolated API-key Web login binds loopback and serves its page")
        oauth_environment = environment(root / "oauth-fixture-state", node, "danger-full-access")
        oauth_environment.update({"BRIDGE_DEEPSEEK_PROVIDER": "openai-codex", "BRIDGE_DEEPSEEK_MODEL": "gpt-6.1-sol"})
        oauth_login_smoke(release, work, oauth_environment)
        print("PASS: real authorization/credential seam with mock callback survives stdin EOF and commits grant")
        home = root / "state"
        full = environment(home, node, "danger-full-access")
        first = Client(release, work, full)
        try:
            initialize(first)
            session = first.call("session/new", {"cwd": str(workspace), "mcpServers": []})["sessionId"]
            assert first.call("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "fresh-policy-probe"}]}) == {"stopReason": "end_turn"}
            first.call("session/close", {"sessionId": session})
            first.call("session/resume", {"sessionId": session, "cwd": str(workspace), "mcpServers": []})
            crash_request = first.send("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "crash-pending-probe"}]})
            trace = home / "dsh/bridge-policy-test-trace.jsonl"
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                first.pump(0.05)
                if crash_request in first.responses:
                    raise AssertionError("crash fixture settled prematurely: " + json.dumps(first.responses[crash_request]))
                if any(json.loads(line).get("crashPending") for line in trace.read_text().splitlines()):
                    break
            else:
                raise AssertionError("fixture did not persist its crash-pending inbox item")
            first.crash()
            first = None
        finally:
            if first is not None:
                first.close()
        print("PASS: real wrapper full-access fresh/prompt/close and durable pending crash fixture")

        readonly = environment(home, node, "read-only")
        second = Client(release, work, readonly)
        try:
            initialize(second)
            second.call("session/resume", {"sessionId": session, "cwd": str(workspace), "mcpServers": []})
            assert second.call("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "delegate-probe"}]}) == {"stopReason": "end_turn"}
            second.call("session/close", {"sessionId": session})
            second.call("session/resume", {"sessionId": session, "cwd": str(workspace), "mcpServers": []})
            second.call("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "filesystem-probe"}]})
            assert not list(workspace.glob("*-read-only")) and not list(outside.iterdir())
            second.call("session/close", {"sessionId": session})
            second.call("session/resume", {"sessionId": session, "cwd": str(workspace), "mcpServers": []})
            trace = home / "dsh/bridge-policy-test-trace.jsonl"
            before = len(trace.read_text().splitlines())
            prompt = second.send("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "cancel-probe"}]})
            deadline = time.monotonic() + 10
            while len(trace.read_text().splitlines()) == before and time.monotonic() < deadline:
                second.pump(0.05)
            assert len(trace.read_text().splitlines()) > before
            second.send("session/cancel", {"sessionId": session}, notification=True)
            assert second.response(prompt) == {"stopReason": "cancelled"}
            second.call("session/close", {"sessionId": session})
            records = [record for line in trace.read_text().splitlines() if "mode" in (record := json.loads(line))]
            assert any(record["child"] and record["mode"] == "read-only" for record in records), records
            assert records[0]["mode"] == "danger-full-access"
            assert records[1]["mode"] == "danger-full-access"
            assert all(record["mode"] == "read-only" and record["approval"] == "never" for record in records[2:])
        finally:
            second.close()
        print("PASS: crash resume discards stale inbox; downgrade/pre-step/child/native cancel")

        third = Client(release, work, environment(home, node, "workspace-write"))
        try:
            initialize(third)
            third.call("session/resume", {"sessionId": session, "cwd": str(workspace), "mcpServers": []})
            third.call("session/prompt", {"sessionId": session,
                "prompt": [{"type": "text", "text": "filesystem-probe"}]})
            assert (workspace / "fs-inside-workspace-write").read_text() == "probe"
            assert (workspace / "shell-inside-workspace-write").read_text() == "probe"
            assert not list(outside.iterdir())
            third.call("session/close", {"sessionId": session})
        finally:
            third.close()
        print("PASS: real write/bash tools: read-only denies; workspace-write allows inside, denies outside/symlink")

        for name in ("web", "acp"):
            directory = home / "dsh/profiles" / name
            directory.mkdir(parents=True, exist_ok=True)
        result = subprocess.run([str(release / "dsh"), "--bridge-auth-status"], cwd=work,
                                env=readonly, capture_output=True, text=True, timeout=20)
        assert result.returncode == 0, result.stderr
        assert json.loads(result.stdout) == {"configured": False, "writable": True}
        (home / "dsh/profiles/acp/cordis.patch.yml").write_text("- insert: []\n")
        rejected = subprocess.run([str(release / "dsh"), "--profile", "acp"], cwd=work,
                                  env=readonly, capture_output=True, text=True, timeout=20)
        assert rejected.returncode != 0 and "CUSTOM_PROFILE_REJECTED" in rejected.stderr
        print("PASS: keyless credential.describe and unsafe profile rejection")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--node", default=str(Path(shutil.which("node")).resolve()))
    args = parser.parse_args()
    run(args.base.resolve(), args.node)
