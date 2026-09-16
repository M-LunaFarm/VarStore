#!/usr/bin/env python3
"""Real PostgreSQL wire faults on owned loopback listeners, including atomic outbox."""
import hashlib,json,os,pathlib,socket,subprocess,time,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
ENV=dict(os.environ,VARSTORE_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:25432/varstore_extensions',VARSTORE_FAULT_JDBC_URL='jdbc:postgresql://127.0.0.1:25433/varstore_extensions',VARSTORE_TEST_DB_USER='varstore',VARSTORE_TEST_DB_PASSWORD='varstore-test')
for port in (25433,25434):
 with socket.socket() as probe:probe.bind(('127.0.0.1',port))
cp=(ROOT/'varstore-testkit/build/runtime-classpath.txt').read_text().strip()
report={'status':'FAIL','checks':{},'artifactSha256':hashlib.sha256((ROOT/'varstore-testkit/build/libs/varstore-testkit-1.3.0.jar').read_bytes()).hexdigest()}
log=(ROOT/'.local/commit-fault-proxy.log').open('w')
proxy=subprocess.Popen(['python3',str(ROOT/'scripts/fault-proxy.py'),'--upstream','25432'],stdout=log,stderr=subprocess.STDOUT)
try:
 for attempt in range(50):
  try:
   with urllib.request.urlopen('http://127.0.0.1:25434/status',timeout=1) as response:json.load(response)
   break
  except OSError:
   if proxy.poll() is not None:raise AssertionError('Fault proxy exited')
   time.sleep(.1)
 else:raise AssertionError('Fault proxy not ready')
 for mode in ('pre-commit','commit-response'):
  result=subprocess.run([JAVA,'-cp',cp,'kr.lunaf.varstore.testkit.FaultHarness',mode],env=ENV,check=True,text=True,capture_output=True,timeout=60)
  (ROOT/'.local'/('fault-'+mode+'.log')).write_text(result.stdout+result.stderr)
  reports=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
  assert reports and reports[-1]['passed'];report['checks'][mode]=reports[-1]
 with urllib.request.urlopen('http://127.0.0.1:25434/status',timeout=2) as response:report['proxy']=json.load(response)
 assert report['proxy']['triggered']==2 and report['proxy']['dropped_bytes']>0
 report['status']='PASS'
finally:
 proxy.terminate()
 try:proxy.wait(timeout=5)
 except subprocess.TimeoutExpired:proxy.kill();proxy.wait()
 log.close();report['ownedProxyTerminal']=proxy.poll() is not None
 report['recordedAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
 (ROOT/'verification/commit-response.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report,indent=2))
