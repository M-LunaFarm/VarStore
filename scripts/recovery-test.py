#!/usr/bin/env python3
"""Actual DB SIGKILL and complete pg_dump/pg_restore rehearsal on isolated fault DB.
Requires owned container varstore-fault-postgres on localhost25435 and built JARs.
Never points at a production URL. Full transcripts stay in ignored .local/.
"""
import json, os, pathlib, queue, subprocess, threading, time
root=pathlib.Path(__file__).resolve().parents[1]
java=os.environ.get('VARSTORE_TEST_JAVA','/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
container='varstore-fault-postgres'
cp=(root/'varstore-testkit/build/runtime-classpath.txt').read_text()
env=dict(os.environ,VARSTORE_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:25435/varstore_recovery',VARSTORE_TEST_DB_USER='varstore',VARSTORE_TEST_DB_PASSWORD='varstore-test')
report={'scope':'dedicated loopback PostgreSQL18 container, logical snapshot recovery','checks':{}}
def run(args,**kw):
    return subprocess.run(args,env=env,check=True,text=True,capture_output=True,**kw)
def harness(mode):
    return [java,'-cp',cp,'kr.lunaf.varstore.testkit.FaultHarness',mode]
def ready():
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if subprocess.run(['docker','exec',container,'pg_isready','-U','varstore'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:return
        time.sleep(.1)
    raise AssertionError('Fault database did not recover')
ready()
seed=run(harness('seed'))
start=time.monotonic();run(['docker','kill','--signal=KILL',container]);run(['docker','start',container]);ready()
verified=run(harness('verify'))
report['checks']['T03']={'passed':True,'fault':'SIGKILL actual PostgreSQL process/container','recovery_seconds':round(time.monotonic()-start,3),'verified':'acknowledged pre-crash STRING persisted'}
p=subprocess.Popen(harness('recovery'),env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
lines=queue.Queue();transcript=[]
def collect():
    for line in p.stdout:transcript.append(line);lines.put(line)
threading.Thread(target=collect,daemon=True).start()
def wait(marker):
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        try:
            line=lines.get(timeout=1)
            if marker in line:return
        except queue.Empty:
            if p.poll() is not None:raise AssertionError('Recovery harness exited: '+''.join(transcript[-15:]))
    raise AssertionError('Recovery marker timeout: '+marker)
try:
    wait('RECOVERY_BACKUP_READY')
    backup=root/'.local/recovery.dump'
    with backup.open('wb') as out:
        subprocess.run(['docker','exec',container,'pg_dump','-U','varstore','-d','varstore_recovery','-Fc'],stdout=out,check=True)
    backup_done=time.monotonic()
    p.stdin.write('backup completed\n');p.stdin.flush();wait('RECOVERY_RESTORE_READY')
    restore_start=time.monotonic()
    with backup.open('rb') as src:
        subprocess.run(['docker','exec','-i',container,'pg_restore','-U','varstore','-d','varstore_recovery','--clean','--if-exists','--exit-on-error'],stdin=src,check=True)
    tool=root/'varstore-tools/build/libs/varstore-tools-1.0.0-SNAPSHOT.jar'
    toolenv=dict(env,VARSTORE_JDBC_URL=env['VARSTORE_TEST_JDBC_URL'],VARSTORE_DB_USER='varstore',VARSTORE_DB_PASSWORD='varstore-test',VARSTORE_TLS_MODE='disable',VARSTORE_WRITERS_STOPPED='true')
    subprocess.run([java,'-jar',str(tool),'rotate-epoch','faulttest'],env=toolenv,check=True,capture_output=True,text=True)
    p.stdin.write('restored and epoch rotated\n');p.stdin.flush()
    wait('"test":"T18"');assert p.wait(timeout=15)==0
    report['checks']['T18']={'passed':True,'backup_bytes':backup.stat().st_size,'recovery_seconds':round(time.monotonic()-restore_start,3),'backup_age_at_restore_seconds':round(restore_start-backup_done,3),'verified':['value and original operation restored together','post-backup operation removed together','old open writer rejected STALE_EPOCH','fresh writer reads restored10 and commits40']}
    report['status']='PASS'
finally:
    if p.poll() is None:p.kill();p.wait()
    (root/'.local/recovery-transcript.log').write_text(''.join(transcript))
    report['recordedAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
    (root/'verification').mkdir(exist_ok=True)
    (root/'verification/recovery.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
