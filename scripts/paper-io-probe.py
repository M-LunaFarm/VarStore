#!/usr/bin/env python3
"""Record real JDBC socket activity in the three owned smoke JVMs using JDK Flight Recorder."""
import argparse, collections, json, os, pathlib, re, subprocess, time

parser = argparse.ArgumentParser()
parser.add_argument('run_directory', type=pathlib.Path)
parser.add_argument('--seconds', type=int, default=140)
parser.add_argument('--existing', action='store_true', help='Analyze three already recorded a/b/c-jdbc.jfr files without starting JVM recordings')
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
if not args.existing:
    assert set(targets) == {'a', 'b', 'c'}, f'Expected three live owned JVMs: {targets}'
else:
    targets = {name: None for name in ('a', 'b', 'c')}
    assert all((run / (name + '-jdbc.jfr')).exists() for name in targets), 'Existing recordings are required for all three servers'
config = run / 'jdbc-io.jfc'
config.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="VarStore JDBC thread probe" description="Socket events at zero threshold" provider="VarStore tests">
  <event name="jdk.SocketRead"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.SocketWrite"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
</configuration>''')
started = None
if not args.existing:
    started = time.time()
    for name, pid in targets.items():
        subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.start', 'name=VarStoreIo', 'settings=' + str(config),
                        'filename=' + str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True)
    print(json.dumps({'event': 'io-probe-started', 'pids': targets, 'seconds': args.seconds}), flush=True)
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        time.sleep(min(30, max(0, deadline - time.monotonic())))
        print(json.dumps({'event': 'io-probe-recording', 'remainingSeconds': max(0, int(deadline - time.monotonic()))}), flush=True)
report = {'startedAt': started, 'endedAt': None if args.existing else time.time(), 'requestedDurationSeconds': args.seconds,
          'method': 'JFR SocketRead/SocketWrite threshold 0ms; PostgreSQL stack frames; actual Paper JVM threads',
          'scope': 'Observed JDBC socket I/O during this interval; does not measure API submission latency or prove unobserved code paths',
          'servers': {}}
if not args.existing:
    for name, pid in targets.items():
        subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.stop', 'name=VarStoreIo'], check=True, capture_output=True, text=True)
for name, pid in targets.items():
    summary = subprocess.run([str(jdk / 'jfr'), 'summary', str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True).stdout
    duration = re.search(r'Duration:\s+(\d+)\s+s', summary)
    recording_start = re.search(r'Start:\s+([^\n]+)', summary)
    # Stream text records rather than materializing a potentially huge JSON tree.
    printing = subprocess.Popen([str(jdk / 'jfr'), 'print', '--events', 'jdk.SocketRead,jdk.SocketWrite', '--stack-depth', '24', str(run / (name + '-jdbc.jfr'))], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    counts = collections.Counter()
    violations = []
    thread, event_type, event_time, jdbc = 'unknown', None, None, False
    for line in printing.stdout:
        if line.startswith('jdk.Socket') and line.rstrip().endswith('{'):
            event_type = line.split()[0]; thread = 'unknown'; event_time = None; jdbc = False
        elif 'eventThread = ' in line:
            match = re.search(r'eventThread = "([^"]+)"', line)
            if match: thread = match.group(1)
        elif 'startTime = ' in line: event_time = line.strip().partition(' = ')[2]
        elif 'postgresql' in line: jdbc = True
        elif line.strip() == '}' and event_type and jdbc:
            counts[thread] += 1
            if thread == 'Server thread': violations.append({'event': event_type, 'at': event_time})
            event_type = None
    assert printing.wait(timeout=30) == 0, 'JFR event decoding failed'

    report['servers'][name] = {'pid': pid, 'recordingStart': recording_start.group(1).strip() if recording_start else None, 'recordingDurationSeconds': int(duration.group(1)) if duration else None, 'jdbcSocketEvents': sum(counts.values()), 'threadCounts': dict(counts), 'mainThreadJdbcEvents': violations}
report['status'] = 'PASS' if all(s['jdbcSocketEvents'] > 0 and not s['mainThreadJdbcEvents'] for s in report['servers'].values()) else 'FAIL'
output = root / 'verification' / 'paper-io-probe.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if report['status'] != 'PASS': raise SystemExit(1)
