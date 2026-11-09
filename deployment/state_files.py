"""Private state I/O shared by the deployment and remote access commands."""

import json
import os
import tempfile
import time


def read(path, default=None):
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else default


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, tmp = tempfile.mkstemp(prefix='.atomic-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            payload = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False, indent=2).encode()
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        for attempt in range(50):
            try:
                os.replace(tmp, path)
                break
            except PermissionError:
                if attempt == 49:
                    raise
                time.sleep(.02)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
