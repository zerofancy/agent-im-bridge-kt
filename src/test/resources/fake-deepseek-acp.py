#!/usr/bin/env python3
"""Deterministic ACP fixture: isolated state and no network or model credentials."""
import json
import os
import pathlib
import sys
import threading
import time
import uuid

home = pathlib.Path(os.environ['DSH_HOME'])
root = home.parent
home.mkdir(parents=True, exist_ok=True)
lock = threading.Lock()
sessions_path = home / 'sessions.json'
sessions = json.loads(sessions_path.read_text()) if sessions_path.exists() else {}
models_path = home / 'models.json'
models = json.loads(models_path.read_text()) if models_path.exists() else {}
active = set()
workers = {}
(root / 'pid').write_text(str(os.getpid()))
(root / 'launch.json').write_text(json.dumps({
    'cwd': os.getcwd(), 'home': str(home),
    'mode': os.environ.get('BRIDGE_DEEPSEEK_SANDBOX_MODE'),
    'inheritedSecret': 'DEEPSEEK_API_KEY' in os.environ,
}))


def record(frame):
    with lock:
        with (root / 'requests').open('a') as f:
            f.write(json.dumps(frame) + '\n')


def send(frame):
    with lock:
        print(json.dumps(dict(jsonrpc='2.0', **frame)), flush=True)


def result(frame, value):
    send({'id': frame['id'], 'result': value})


def error(frame, message, code=-32602):
    send({'id': frame['id'], 'error': {'code': code, 'message': message}})


def update(sid, kind, **values):
    send({'method': 'session/update', 'params': {
        'sessionId': sid, 'update': dict(sessionUpdate=kind, **values),
    }})


def public(sid, mid, text):
    update(sid, 'agent_message_chunk', messageId=mid, content={'type': 'text', 'text': text})


def config_options(sid):
    if (root / 'bad-model-metadata').exists():
        return {'SECRET': 'malformed optional model metadata'}
    selection = models.get(sid)
    if selection is None:
        return []
    value = json.dumps([selection['provider'], selection['model']], separators=(',', ':'))
    result = [{'id': 'model', 'category': 'model', 'name': 'Model', 'type': 'select',
               'currentValue': value, 'options': [
                   {'group': selection['provider'], 'name': 'Friendly Provider', 'options': [
                       {'value': value, 'name': 'Friendly ' + selection['model']},
                   ]},
               ]}]
    if 'effort' in selection:
        result.append({'id': 'reasoning_effort', 'category': 'thought_level',
                       'name': 'Reasoning effort', 'type': 'select',
                       'currentValue': selection['effort'], 'options': [
                           {'value': '', 'name': 'Provider default'},
                           {'value': 'low', 'name': 'Low'}, {'value': 'high', 'name': 'High'},
                       ]})
    return result


def prompt(frame):
    sid = frame['params']['sessionId']
    text = frame['params']['prompt'][0]['text']
    stop = threading.Event()
    workers[sid] = stop
    (root / ('prompt-' + sid)).write_text(text)
    if text == 'reject':
        error(frame, 'Invalid params')
        return
    external = {
        'auth-error': 'Invalid OAuth token for this user request SECRET',
        'rate-error': 'HTTP 429 rate_limit_error SECRET',
        'quota-error': 'Insufficient Balance SECRET',
        'network-error': 'ECONNRESET SECRET',
        'unknown-error': 'unexpected invariant SECRET',
    }
    if text in external:
        error(frame, 'Internal error: turn failed: ' + external[text], -32603)
        return
    if text == 'model-update':
        models[sid] = {'provider': 'fixture-updated', 'model': 'gpt-6.1-sol', 'effort': 'low'}
        models_path.write_text(json.dumps(models))
        update(sid, 'config_option_update', configOptions=config_options(sid))
    elif text == 'model-update-malformed':
        update(sid, 'config_option_update', configOptions={'SECRET': 'optional invalid metadata'})
    update(sid, 'agent_thought_chunk', messageId='reason', content={'type': 'text', 'text': 'SECRET'})
    if text == 'storage-after-submission':
        public(sid, 'mid-final', 'answer')
    elif text in {'stop', 'ignore-stop', 'disconnect', 'wait', 'close-hold'}:
        if text != 'disconnect':
            public(sid, 'mid-start', 'working')
        if text == 'disconnect':
            send({'id': {}, 'result': {}})
        while not (root / 'finish').exists():
            if stop.wait(0.02) and text != 'ignore-stop':
                result(frame, {'stopReason': 'cancelled'})
                return
        public(sid, 'mid-final', 'finished')
    elif text == 'permission':
        send({'id': 'permission-1', 'method': 'session/request_permission', 'params': {
            'sessionId': sid, 'options': [
                {'optionId': 'allow', 'kind': 'allow_once'},
                {'optionId': 'reject', 'kind': 'reject_once'},
            ],
        }})
        public(sid, 'mid-final', 'permission handled')
    elif text == 'no-message-id':
        update(sid, 'agent_message_chunk', content={'type': 'text', 'text': 'invalid'})
    elif text == 'empty':
        pass
    else:
        public(sid, 'mid-early', 'process text')
        update(sid, 'tool_call', toolCallId='tool-1', title='bash', status='in_progress', rawInput={'command': 'pwd'})
        update(sid, 'tool_call_update', toolCallId='tool-1', status='completed', content=[{'type': 'content', 'content': {'type': 'text', 'text': 'SECRET TOOL OUTPUT'}}])
        if text != 'tool-only':
            public(sid, 'mid-final', 'final ')
            public(sid, 'mid-final', text)
    result(frame, {'stopReason': 'max_tokens' if text == 'limit' else 'end_turn'})


for line in sys.stdin:
    frame = json.loads(line)
    record(frame)
    method = frame.get('method')
    params = frame.get('params', {})
    if method == 'initialize':
        if (root / 'hold-initialize').exists():
            (root / 'initializing').write_text('yes')
            continue
        result(frame, {'protocolVersion': 1, 'agentInfo': {'name': 'deepseek-harness-acp', 'version': '0.0.1'},
                       'agentCapabilities': {'sessionCapabilities': {'list': {}, 'resume': {}, 'close': {}}}})
    elif method == 'session/new':
        sid = str(uuid.uuid4())
        sessions[sid] = params['cwd']
        sessions_path.write_text(json.dumps(sessions))
        models[sid] = {'provider': os.environ.get('BRIDGE_DEEPSEEK_PROVIDER', 'fixture'),
                       'model': os.environ.get('BRIDGE_DEEPSEEK_MODEL', 'initial-model'), 'effort': ''}
        models_path.write_text(json.dumps(models))
        active.add(sid)
        if (root / 'hold-new').exists():
            (root / 'new-ready').write_text(sid)
            def delayed_new(frame=frame, sid=sid):
                while not (root / 'release-new').exists():
                    time.sleep(0.01)
                result(frame, {'sessionId': sid, 'configOptions': config_options(sid)})
            threading.Thread(target=delayed_new, daemon=True).start()
        else:
            result(frame, {'sessionId': sid, 'configOptions': config_options(sid)})
    elif method == 'session/list':
        result(frame, {'sessions': [{'sessionId': sid, 'cwd': cwd} for sid, cwd in sessions.items()]})
    elif method == 'session/resume':
        sid = params['sessionId']
        def resume(frame=frame, sid=sid, params=params):
            if (root / 'hold-resume').exists():
                (root / ('resume-ready-' + sid)).write_text('yes')
                while not (root / 'release-resume').exists():
                    time.sleep(0.01)
            if (root / 'resume-force-occupied').exists():
                error(frame, 'Invalid params: session is already active: ' + sid)
            elif (root / 'resume-force-internal').exists():
                error(frame, 'Internal error: unexpected invariant SECRET', -32603)
            elif (root / 'resume-disconnect').exists():
                send({'id': {}, 'result': {}})
            elif sid not in sessions:
                error(frame, 'session is not resumable: ' + sid)
            elif sid in active:
                error(frame, 'session is already active: ' + sid)
            elif sessions[sid] != params['cwd']:
                error(frame, 'session cwd does not match: ' + params['cwd'])
            else:
                active.add(sid)
                result(frame, {'configOptions': config_options(sid)})
            (root / ('resume-replied-' + sid)).write_text('yes')
        threading.Thread(target=resume, daemon=True).start()
    elif method == 'session/prompt':
        threading.Thread(target=prompt, args=(frame,), daemon=True).start()
    elif method == 'session/cancel':
        sid = params['sessionId']
        if sid in workers:
            workers[sid].set()
            (root / ('cancel-' + sid)).write_text('yes')
    elif method == 'session/close':
        sid = params['sessionId']
        (root / ('closed-' + sid)).write_text('yes')
        if sid in workers:
            workers[sid].set()
        if (root / 'hold-close').exists() or (root / ('hold-close-' + sid)).exists():
            (root / ('closing-' + sid)).write_text('yes')
            def finish_close(frame=frame, sid=sid):
                while not ((root / 'release-close').exists() or (root / ('release-close-' + sid)).exists()):
                    time.sleep(0.02)
                active.discard(sid)
                result(frame, {})
            threading.Thread(target=finish_close, daemon=True).start()
        else:
            active.discard(sid)
            result(frame, {})
    elif method is None:
        (root / 'client-answer').write_text(json.dumps(frame))
