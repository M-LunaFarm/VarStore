#!/usr/bin/env python3
"""Record real JDBC socket activity in the three owned smoke JVMs using JDK Flight Recorder."""
import argparse, collections, json, os, pathlib, subprocess, time

parser = argparse.ArgumentParser()
parser.add_argument('run_directory', type=pathlib.Path)
parser.add_argument('--seconds', type=int, default=140)
args = parser.parse_args()
run = args.run_directory.resolve()
root = pathlib.Path(__file__).resolve().parents[1]
jdk = pathlib.Path(os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')).parent
targets = {}
for proc in pathlib.Path('/proc').iterdir():
    if not proc.name.isdigit(): continue
    try:
        cwd = (proc / 'cwd').resolve()
        if cwd.parent == run and cwd.name in ('a', 'b', 'c') and b'paper.jar' in (proc / 'cmdline').read_bytes():
            targets[cwd.name] = int(proc.name)
    except (FileNotFoundError, PermissionError): pass
assert set(targets) == {'a', 'b', 'c'}, f'Expected three live owned JVMs: {targets}'
config = run / 'jdbc-io.jfc'
config.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="VarStore JDBC thread probe" description="Socket events at zero threshold" provider="VarStore tests">
  <event name="jdk.SocketRead"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.SocketWrite"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
</configuration>''')
started = time.time()
for name, pid in targets.items():
    subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.start', 'name=VarStoreIo', 'settings=' + str(config),
                    'filename=' + str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True)
print(json.dumps({'event': 'io-probe-started', 'pids': targets, 'seconds': args.seconds}), flush=True)
deadline = time.monotonic() + args.seconds
while time.monotonic() < deadline:
    time.sleep(min(30, max(0, deadline - time.monotonic())))
    print(json.dumps({'event': 'io-probe-recording', 'remainingSeconds': max(0, int(deadline - time.monotonic()))}), flush=True)
report = {'startedAt': started, 'endedAt': time.time(), 'durationSeconds': args.seconds,
          'method': 'JFR SocketRead/SocketWrite threshold 0ms; PostgreSQL stack frames; actual Paper JVM threads',
          'scope': 'Observed JDBC socket I/O during this interval; does not measure API submission latency or prove unobserved code paths',
          'servers': {}}
for name, pid in targets.items():
    subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.stop', 'name=VarStoreIo'], check=True, capture_output=True, text=True)
    raw = subprocess.run([str(jdk / 'jfr'), 'print', '--json', '--events', 'jdk.SocketRead,jdk.SocketWrite', '--stack-depth', '64', str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True)
    events = json.loads(raw.stdout)['recording']['events']
    counts = collections.Counter()
    violations = []
    for event in events:
        values = event['values']
        stack = values.get('stackTrace') or {}
        frames = stack.get('frames') or []
        jdbc = any('postgresql' in frame.get('method', {}).get('type', {}).get('name', '') for frame in frames)
        if not jdbc: continue
        thread = values.get('eventThread', {}).get('javaName', 'unknown')
        counts[thread] += 1
        if thread == 'Server thread': violations.append({'event': event['type'], 'at': values.get('startTime')})
    report['servers'][name] = {'pid': pid, 'jdbcSocketEvents': sum(counts.values()), 'threadCounts': dict(counts), 'mainThreadJdbcEvents': violations}
report['status'] = 'PASS' if all(s['jdbcSocketEvents'] > 0 and not s['mainThreadJdbcEvents'] for s in report['servers'].values()) else 'FAIL'
output = root / 'verification' / 'paper-io-probe.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if report['status'] != 'PASS': raise SystemExit(1)
