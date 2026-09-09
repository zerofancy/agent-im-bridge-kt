#!/usr/bin/python3
import json, os, sys, uuid, subprocess
root = os.path.dirname(os.path.realpath(sys.argv[0]))
active = {}
children = []
def send(x):
    print(json.dumps(x), flush=True)
def result(req, value):
    send({'id': req['id'], 'result': value})
def event(method, tid, turn_id, **values):
    send({'method': method, 'params': dict(threadId=tid, turnId=turn_id, **values)})
def completed(tid, turn, status):
    event('turn/completed', tid, turn, turn={'id':turn, 'status':status})
def item(tid, turn, name, text, phase=None):
    event('item/completed', tid, turn, item={'id':name,'type':'agentMessage','text':text,'phase':phase})
with open(root+'/pid','w') as f: f.write(str(os.getpid()))
for line in sys.stdin:
    req = json.loads(line)
    with open(root+'/requests','a') as f: f.write(json.dumps(req)+'\n')
    method = req.get('method')
    p = req.get('params',{})
    if method == 'initialize':
        result(req, {'userAgent':'fake'})
    elif method in ('thread/start','thread/resume'):
        tid = p.get('threadId',str(uuid.uuid4()))
        if tid == '00000000-0000-4000-8000-000000000000':
            send({'id':req['id'],'error':{'code':-32600,'message':'no rollout found for thread id '+tid}})
        elif tid == '00000000-0000-4000-8000-000000000001':
            send({'id':req['id'],'error':{'code':-32600,'message':'some session not found'}})
        else: result(req, {'thread':{'id':tid}})
    elif method == 'turn/start':
        tid = p['threadId']; turn=str(uuid.uuid4()); prompt=p['input'][0]['text']
        active[tid]=(turn,prompt)
        event('turn/started',tid,turn,turn={'id':turn,'status':'inProgress'})
        # Exercise notifications before the start response and unrelated events.
        item(tid, 'unrelated-turn', 'wrong', 'must not leak', 'final_answer')
        if prompt == 'exit': sys.exit(9)
        if prompt == 'broken':
            os.close(1)
            while True: __import__('time').sleep(60)
        if prompt == 'reject':
            send({'id':req['id'],'error':{'code':-32600,'message':'ordinary failure'}}); continue
        if prompt == 'wait-start':
            __import__('threading').Timer(0.5, lambda request=req, turn_id=turn: result(request, {'turn':{'id':turn_id,'status':'inProgress'}})).start()
        else: result(req, {'turn':{'id':turn,'status':'inProgress'}})
        if prompt.startswith('wait'):
            if prompt == 'wait-child':
                child=subprocess.Popen(['sleep','60']); children.append(child)
                with open(root+'/child','w') as f:f.write(str(child.pid))
            continue
        if prompt == 'progress':
            for i in range(1000): event('item/agentMessage/delta',tid,turn,delta='x'*1000)
            item(tid,turn,'progress','commentary must not leak','commentary')
            item(tid,turn,'answer','final only','final_answer')
            item(tid,turn,'answer','final only','final_answer')
            sys.stderr.write('x'*262144+'\n');sys.stderr.flush()
        elif prompt == 'approval':
            send({'id':'approval-1','method':'item/commandExecution/requestApproval','params':{'threadId':tid,'turnId':turn}})
            continue
        elif prompt != 'empty':
            item(tid,turn,'answer', prompt, None if prompt == 'legacy' else 'final_answer')
        completed(tid,turn,'failed' if prompt == 'failed' else 'completed')
    elif method == 'turn/interrupt':
        tid=p['threadId']; turn,prompt=active[tid]
        result(req,{})
        if prompt != 'wait-ignore':
            for child in children: child.terminate(); child.wait()
            completed(tid,turn,'interrupted')
    elif req.get('id') == 'approval-1':
        tid=next(reversed(active));turn,_=active[tid]
        item(tid,turn,'answer','denied','final_answer');completed(tid,turn,'completed')
