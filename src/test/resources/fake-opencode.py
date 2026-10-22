#!/usr/bin/env python3
import base64,json,os,time,threading,socket
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse,parse_qs
root=Path.cwd()
sessions={}
events=[]
lock=threading.Lock()
def fixture_prompt(text):
    prefix = ('你是通过即时通信与用户协作的 AI 助手。遵循当前环境与项目规则，使用中文简洁回复。\n\n'
              '以下是用户要求，请在遵循上述提示和当前环境规则的前提下处理：\n\n')
    return text[len(prefix):] if text.startswith(prefix) else text
(root/'pid').write_text(str(os.getpid()))
(root/'environment.json').write_text(json.dumps({k:v for k,v in os.environ.items() if k.startswith('XDG_')}))
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def reply(self,value,code=200):
  data=json.dumps(value).encode();self.send_response(code);self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers()
  try:self.wfile.write(data)
  except (BrokenPipeError,ConnectionResetError):pass
 def handle_request(self):
  expected='Basic '+base64.b64encode(('opencode:'+os.environ['OPENCODE_SERVER_PASSWORD']).encode()).decode()
  if self.headers.get('Authorization')!=expected:return self.reply({},401)
  url=urlparse(self.path);path=url.path;directory=parse_qs(url.query).get('directory',[''])[0]
  body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}')
  with (root/'requests').open('a') as f:f.write(json.dumps({'method':self.command,'path':path,'directory':directory,'body':body})+'\n')
  if path=='/global/health':return self.reply({'healthy':True,'version':'999.0.0'})
  if path=='/config':return self.reply({'model':'fixture/current'})
  if path=='/event':
   if (root/'hold-events').exists():
    (root/'events-waiting').write_text('yes')
    while not (root/'release-events').exists():time.sleep(.02)
   if (root/'fail-events').exists():return self.reply({},503)
   cursor=len(events)
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
   try:
    for i in range(300):
     pending=events[cursor:];cursor+=len(pending)
     for event in pending:self.wfile.write(('data: '+json.dumps(event)+'\n\n').encode())
     self.wfile.write(b'data: {"type":"message.part.updated"}\n\n');self.wfile.flush();time.sleep(.05)
   except (BrokenPipeError,ConnectionResetError):pass
   return
  if path in ['/permission','/question']:return self.reply([])
  if path=='/session/status':return self.reply({k:{'type':'busy'} for k,v in sessions.items() if v['busy']})
  if path=='/session' and self.command=='POST':
   with lock:
    sid='ses_'+str(len(sessions)+1);sessions[sid]={'id':sid,'directory':directory,'messages':[],'busy':False,'stop':False}
   return self.reply({'id':sid,'directory':directory})
  sid=path.split('/')[2] if path.startswith('/session/') else ''
  if sid not in sessions:return self.reply({},404)
  s=sessions[sid]
  if path.endswith('/abort'):
   (root/'aborted').write_text('yes');s['stop']=True
   return self.reply(True)
  if path.endswith('/message') and self.command=='POST':
   mid=body['messageID'];text=fixture_prompt(body['parts'][0]['text']);s['stop']=False;s['busy']=True
   user={'info':{'id':mid,'role':'user','sessionID':sid},'parts':[]}
   assistant={'info':{'id':'msg_answer'+mid,'role':'assistant','sessionID':sid,'parentID':mid,'time':{'created':1}},'parts':[{'type':'text','text':'partial'},{'type':'reasoning','text':'SECRET REASONING'},{'type':'tool','tool':'bash','state':{'status':'running','input':{'command':'pwd'},'output':'SECRET OUTPUT'}}]}
   s['messages'] += [user,assistant]
   if text in ['model-not-found','error-while-busy','other-session-error']:
    s['messages'].remove(assistant)
    if text=='model-not-found':s['busy']=False
    events.append({'type':'session.error','properties':{'sessionID':'ses_unrelated' if text=='other-session-error' else sid,'error':{'name':'ProviderModelNotFoundError','data':{'message':'SECRET ERROR BODY'}}}})
    if text=='model-not-found':
     while not (root/'release-response').exists():time.sleep(.02)
     return self.reply(None)
    while not (root/'finish').exists():time.sleep(.02)
    if text=='error-while-busy':
     s['busy']=False
     while not (root/'release-response').exists():time.sleep(.02)
     return self.reply(None)
    s['messages'].append(assistant)
   if text=='read-image':
    assistant['parts']=[{'id':'read-image-part','type':'tool','tool':'read','state':{'status':'completed','attachments':[{'type':'file','mime':'image/png','url':'data:image/png;base64,AA=='}]}}]
    assistant['info']['time']['completed']=2;assistant['info']['finish']='tool-calls'
    assistant={'info':{'id':'msg_final'+mid,'role':'assistant','sessionID':sid,'parentID':mid,'time':{'created':2}},'parts':[{'type':'text','text':''}]}
    s['messages'].append(assistant)
   if text in ['empty-response','failed-response']:
    s['messages'].remove(assistant);s['busy']=False
    return self.reply(None, 200 if text=='empty-response' else 500)
   if text=='early-stop':
    s['busy']=False
    time.sleep(.5)
    s['busy']=True;s['stop']=False
    while not s['stop']:time.sleep(.02)
   elif text=='disconnect':
    s['busy']=False # Idle alone cannot prove termination after a transport failure.
    self.connection.shutdown(socket.SHUT_RDWR);self.connection.close()
    while not (root/'finish').exists():time.sleep(.02)
   elif text in ['stop','ignore-stop']:
    while not s['stop']:time.sleep(.02)
    if text=='ignore-stop':
     while not (root/'finish').exists():time.sleep(.02)
    time.sleep(.4)
   else:time.sleep(.5)
   assistant['parts'][0]['text']='' if text=='read-image' else 'final '+text
   assistant['info']['time']['completed']=2;assistant['info']['finish']='stop'
   if s['stop']:assistant['info']['error']={'name':'MessageAbortedError'}
   s['busy']=False
   if text!='disconnect':return self.reply(assistant)
   return
  if path.endswith('/message'):return self.reply(s['messages'])
  return self.reply({'id':sid,'directory':s['directory']})
 def do_GET(self):self.handle_request()
 def do_POST(self):self.handle_request()
 def do_PATCH(self):self.handle_request()
class Server(ThreadingHTTPServer):
 request_queue_size=32
 daemon_threads=True
server=Server(('127.0.0.1',0),Handler)
print('opencode server listening on http://127.0.0.1:'+str(server.server_port),flush=True)
server.serve_forever()
