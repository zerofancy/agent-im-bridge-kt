#!/usr/bin/env python3
import json
import os
import sys
import time
from pathlib import Path

root = Path.cwd()
requests_file = root / "doubao-requests.jsonl"


def find_state_file():
    # The real CLI keeps an account-global history; emulate that by walking up from cwd, so
    # --list launched from the runtime root still discovers sessions created in workspaces below.
    directory = Path.cwd()
    while True:
        candidate = directory / "doubao-sessions.json"
        if candidate.exists():
            return candidate
        if directory.parent == directory:
            return Path.cwd() / "doubao-sessions.json"
        directory = directory.parent


state_file = find_state_file()


def load_state():
    if state_file.exists():
        return json.loads(state_file.read_text())
    return {"next": 38450000000000000, "sessions": {}}


def save_state(state):
    state_file.write_text(json.dumps(state))


if "--version" in sys.argv:
    print("doubao 2.31.2")
    sys.exit(0)

args = sys.argv[1:]
workspace = None
resume = None
prompt = None
list_sessions = False
i = 0
while i < len(args):
    if args[i] == "-C":
        workspace = args[i + 1]
        i += 2
    elif args[i] == "--resume":
        resume = args[i + 1]
        i += 2
    elif args[i] == "-p":
        prompt = args[i + 1]
        i += 2
    elif args[i] == "--list":
        list_sessions = True
        i += 1
    else:
        i += 1

if list_sessions:
    state = load_state()
    for session_id, info in state["sessions"].items():
        print(f"{session_id}  {info.get('prompt', '会话')}")
    sys.exit(0)

if not prompt:
    print("invalid_arguments: 请在 `-p` 后填写非空提示词", file=sys.stderr)
    sys.exit(2)

state = load_state()
if resume is not None and resume not in state["sessions"]:
    print("Error: IM request failed: 712017001", file=sys.stderr)
    sys.exit(1)

if resume is None:
    session = str(state["next"])
    state["next"] += 1
    state["sessions"][session] = {"workspace": workspace, "prompt": prompt}
    save_state(state)
else:
    session = resume

with requests_file.open("a") as handle:
    handle.write(
        json.dumps(
            {
                "prompt": prompt,
                "resume": resume,
                "workspace": workspace,
                "danger": "--dangerously-bypass-approvals-and-sandbox" in args,
                "permission_mode": args[args.index("--permission-mode") + 1]
                if "--permission-mode" in args
                else None,
                "update_check_disabled": os.environ.get("DOUBAO_CLI_NO_UPDATE_CHECK"),
            }
        )
        + "\n"
    )

session_event = json.dumps({"type": "session", "session_id": session})
if prompt == "stderr-session":
    print(session_event, file=sys.stderr, flush=True)
else:
    print(session_event, flush=True)

if prompt == "wait" or prompt.endswith("\n\nwait"):
    (root / "waiting").write_text("yes")
    while True:
        time.sleep(0.05)
elif prompt == "hello":
    print("partial", flush=True)
    time.sleep(0.05)
    print("final hello", flush=True)
elif prompt == "stderr-session":
    print("session from stderr", flush=True)
elif prompt == "tool-flow":
    print("· 正在执行代码", flush=True)
    time.sleep(0.05)
    print("· 已运行 echo hi", flush=True)
    time.sleep(0.05)
    print("最终答案", flush=True)
elif resume is not None:
    print(f"resumed {prompt}", flush=True)
else:
    print(f"final {prompt}", flush=True)
