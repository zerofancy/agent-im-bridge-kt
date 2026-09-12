#!/usr/bin/env python3
"""Opt-in systemd integration: temporary root, fake app-server, no Feishu credentials or real models."""
import importlib.util
import json
import os
import re
import signal
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

spec = importlib.util.spec_from_file_location('manager', Path(__file__).with_name('bridgectl.py'))
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
JAVA = r'''
package top.ntutn.agent.bridge;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class MainKt {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(System.getenv("BRIDGE_ROOT")); String env=System.getenv("BRIDGE_ENV"), release=System.getenv("BRIDGE_RELEASE");
  Path dir=root.resolve("environments/"+env); Files.createDirectories(dir.resolve("control"));
  FatalErrorHandler.INSTANCE.install();
  RuntimeLifecycle life=new RuntimeLifecycle(new RuntimeEnvironment(root,env,release)); FatalErrorHandler.INSTANCE.attach(life);
  Process backend=new ProcessBuilder("/bin/sleep","3600").start();
  Files.writeString(dir.resolve("child.pid"),Long.toString(backend.pid()));
  Runtime.getRuntime().addShutdownHook(new Thread(()->{backend.destroyForcibly(); life.finish(life.getStopReason(),0,null);}));
  String token=UUID.randomUUID().toString(); AtomicBoolean draining=new AtomicBoolean("1".equals(System.getenv("BRIDGE_HOLD")));
  boolean reject=Files.exists(root.resolve("releases/"+release+"/reject"));
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);
  JsonObject endpoint=new JsonObject();endpoint.addProperty("port",server.getAddress().getPort());endpoint.addProperty("token",token);
  endpoint.addProperty("bootId",life.getBootId());endpoint.addProperty("pid",ProcessHandle.current().pid());endpoint.addProperty("startedAt",life.getStartedAt().toString());
  Files.writeString(dir.resolve("control/endpoint.json"),endpoint.toString());
  server.createContext("/",x->{try{
   if(!("Bearer "+token).equals(x.getRequestHeaders().getFirst("Authorization"))){x.sendResponseHeaders(403,-1);return;}
   String action=x.getRequestURI().getPath();
   if(action.equals("/crash")){new Thread(()->{throw new IllegalStateException("secret-test");}).start();return;}
   if(action.equals("/drain"))draining.set(true);
   if(action.equals("/resume")||action.equals("/activate"))draining.set(false);
   if(action.startsWith("/reason/"))life.setStopReason(action.substring(8));
   JsonObject response=endpoint.deepCopy();response.remove("token");response.remove("port");
   response.addProperty("release",release);response.addProperty("connected",!reject);response.addProperty("closed",false);
   response.addProperty("draining",draining.get());response.addProperty("pending",Files.exists(dir.resolve("pending"))?1:0);
   byte[] data=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);
  }catch(Exception e){}finally{x.close();}});
  server.start();new CountDownLatch(1).await();
 }
}
'''


def wait(check, timeout=50):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=check()
            if result:return result
        except (OSError,RuntimeError,ValueError):pass
        time.sleep(.25)
    raise AssertionError('timed out waiting for integration checkpoint')


def main():
    if sys.platform!='linux': raise RuntimeError('Linux only')
    repo=Path(__file__).resolve().parent.parent
    root=Path(tempfile.mkdtemp(prefix='bridge systemd test-'))
    m=b.Manager(root,'dev')
    prod=b.Manager(root,'prod')
    print('TEST_ROOT='+str(root),flush=True)
    try:
        dist=root/'distribution';shutil.copytree(repo/'build/install/agent-im-bridge-kt',dist)
        sources=root/'source';sources.mkdir();(sources/'MainKt.java').write_text(JAVA)
        classes=root/'classes';classes.mkdir()
        java_home=Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-11-openjdk-amd64'))
        if not java_home.exists():
            # Try to find java home
            result=subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True)
            for line in result.stderr.split('\n'):
                if 'java.home' in line:
                    java_home=Path(line.split('=')[1].strip())
                    break
        cp=':'.join(str(p) for p in (dist/'lib').glob('*.jar'))
        subprocess.run([str(java_home/'bin/javac'),'-cp',cp,'-d',str(classes),str(sources/'MainKt.java')],check=True)
        subprocess.run([str(java_home/'bin/jar'),'cf',str(dist/'lib/000-probe.jar'),'-C',str(classes),'.'],check=True)
        workspace=root/'workspace';workspace.mkdir()
        config=root/'fake-config.json';b.atomic(config,{'appId':'test-only','appSecret':'not-a-credential','allowedUserId':'test','tenant':'feishu'})
        m.init(workspace,config,str(java_home/'bin/java'))
        old=m.publish(dist);b.atomic(m.deploydir/'current',old.encode());m.start(old)
        first=m.wait_ready(old,stable=1);old_child=int((m.directory/'child.pid').read_text())
        print('PASS initial start pid='+str(first['pid']),flush=True)
        prodwork=root/'prod-workspace';prodwork.mkdir()
        prodconfig=root/'prod-config.json';b.atomic(prodconfig,{'appId':'different-test-bot','appSecret':'not-a-credential','allowedUserId':'test','tenant':'feishu'})
        prod.init(prodwork,prodconfig,str(java_home/'bin/java'))
        b.atomic(prod.deploydir/'current',old.encode());prod.start(old)
        ps=prod.wait_ready(old,stable=1)
        assert ps['pid']!=first['pid'] and prod.directory!=m.directory
        prod.stop();assert m.rpc()['bootId']==first['bootId']
        print('PASS prod/dev concurrent isolation and independent stop',flush=True)
        try:m.rpc('crash')
        except Exception:pass
        second=wait(lambda: (s if (s:=m.rpc())['bootId']!=first['bootId'] else None),60)
        wait(lambda:not b.alive(old_child))
        previous=b.read(m.directory/'lifecycle/previous.json')
        assert 'IllegalStateException' in previous['reason'] and previous['exitCode']==70
        print('PASS fatal restart and child cleanup pid='+str(second['pid']),flush=True)
        # Replacing build files must not change the already-running release.
        (dist/'version.txt').write_text('new')
        new=m.publish(dist);assert m.rpc()['release']==old
        (m.directory/'pending').touch()
        cancel=m.submit(new);wait(lambda:b.read(m.job_path(cancel))['state']=='draining')
        b.atomic(m.job_path(cancel).with_suffix('.cancel'),b'1')
        wait(lambda:b.read(m.job_path(cancel))['state']=='cancelled')
        assert not m.rpc()['draining'];(m.directory/'pending').unlink()
        print('PASS draining cancellation and old release unchanged',flush=True)
        # Kill only this temporary deployment executor. The application must survive and the job must resume.
        (m.directory/'pending').touch()
        resumed=m.submit(new);wait(lambda:b.read(m.job_path(resumed))['state']=='draining')
        # For systemd, we need to find the service PID
        def executor_pid():
            result=subprocess.run(['systemctl', '--user', 'show', m.label+'.deploy.'+resumed, '--property=MainPID'], capture_output=True, text=True)
            match=re.search(r'MainPID=(\d+)', result.stdout)
            return int(match.group(1)) if match and match.group(1) != '0' else None
        worker=wait(executor_pid);before=m.rpc()['bootId'];os.kill(worker,signal.SIGKILL)
        assert m.rpc()['bootId']==before
        wait(lambda:(pid if (pid:=executor_pid()) and pid!=worker else None),60)
        (m.directory/'pending').unlink()
        wait(lambda:b.read(m.job_path(resumed))['state']=='succeeded',70)
        assert m.rpc()['release']==new
        print('PASS executor SIGKILL recovery without killing Bridge',flush=True)
        job=m.submit(new);wait(lambda:b.read(m.job_path(job))['state']=='succeeded',70)
        assert m.rpc()['release']==new and not m.rpc()['draining']
        print('PASS independent deployment and activation',flush=True)
        # The loaded systemd arguments are still held: durable activation must also survive a later crash.
        old_boot=m.rpc()['bootId']
        try:m.rpc('crash')
        except Exception:pass
        wait(lambda:(s if (s:=m.rpc())['bootId']!=old_boot else None),60)
        assert not m.rpc()['draining']
        print('PASS activated version restarts accepting work',flush=True)
        (dist/'reject').write_text('candidate never ready');bad=m.publish(dist)
        job=m.submit(bad);wait(lambda:b.read(m.job_path(job))['state']=='rolled-back',110)
        assert m.rpc()['release']==new
        print('PASS failed candidate rollback',flush=True)
        running=m.rpc();child=int((m.directory/'child.pid').read_text());m.stop()
        wait(lambda:not b.alive(child));time.sleep(2)
        assert not b.alive(running['pid'])
        # Verify systemd service is not active
        result=subprocess.run(['systemctl', '--user', 'is-active', m.label+'.service'], capture_output=True, text=True)
        assert result.returncode != 0
        print('PASS manual stop stays stopped',flush=True)
        print('ALL SYSTEMD CHECKS PASSED',flush=True)
    finally:
        try:m.stop()
        except Exception:pass
        try:prod.stop()
        except Exception:pass
        for manager in (m,prod):
            # Remove the temporary bridge and all deployment workers, including enabled completed jobs.
            unit_dir = Path.home()/'.config/systemd/user'
            for unit_path in unit_dir.glob(manager.label + '*.service'):
                subprocess.run(['systemctl', '--user', 'stop', unit_path.name], capture_output=True)
                subprocess.run(['systemctl', '--user', 'disable', unit_path.name], capture_output=True)
                unit_path.unlink(missing_ok=True)
        subprocess.run(['systemctl', '--user', 'daemon-reload'], capture_output=True)
        # Preserve test logs/records for diagnosis; there are no credentials in this root.
        print('Evidence retained at '+str(root),flush=True)

if __name__=='__main__':main()
