#!/usr/bin/env python3
import base64,json,os,time,threading,socket
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse,parse_qs
root=Path.cwd()
sessions={}
lock=threading.Lock()
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
  with (root/'requests').open('a') as f:f.write(json.dumps({'method':self.command,'path':path,'directory':directory})+'\n')
  if path=='/global/health':return self.reply({'healthy':True,'version':'1.16.0'})
  if path=='/event':
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
   try:
    for i in range(300):self.wfile.write(b'data: {"type":"message.part.updated"}\n\n');self.wfile.flush();time.sleep(.05)
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
   mid=body['messageID'];text=body['parts'][0]['text'];s['stop']=False;s['busy']=True
   user={'info':{'id':mid,'role':'user','sessionID':sid},'parts':[]}
   assistant={'info':{'id':'msg_answer'+mid,'role':'assistant','sessionID':sid,'parentID':mid,'time':{'created':1}},'parts':[{'type':'text','text':'partial'},{'type':'reasoning','text':'SECRET REASONING'},{'type':'tool','tool':'bash','state':{'status':'running','input':{'command':'pwd'},'output':'SECRET OUTPUT'}}]}
   s['messages'] += [user,assistant]
   if text=='early-stop':
    s['busy']=False
    time.sleep(.5)
    s['busy']=True;s['stop']=False
    while not s['stop']:time.sleep(.02)
   elif text=='disconnect':
    self.connection.shutdown(socket.SHUT_RDWR);self.connection.close()
    while not (root/'finish').exists():time.sleep(.02)
   elif text in ['stop','ignore-stop']:
    while not s['stop']:time.sleep(.02)
    if text=='ignore-stop':
     while not (root/'finish').exists():time.sleep(.02)
    time.sleep(.4)
   else:time.sleep(.5)
   assistant['parts'][0]['text']='final '+text
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
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
print('opencode server listening on http://127.0.0.1:'+str(server.server_port),flush=True)
server.serve_forever()
