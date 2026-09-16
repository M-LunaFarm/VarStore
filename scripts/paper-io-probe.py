#!/usr/bin/env python3
"""Record real JDBC socket activity in the three owned smoke JVMs using JDK Flight Recorder."""
import argparse, collections, json, os, pathlib, re, subprocess, time

parser = argparse.ArgumentParser()
parser.add_argument('run_directory', type=pathlib.Path)
parser.add_argument('--seconds', type=int, default=140)
parser.add_argument('--servers', default='a,b,c', help='Comma-separated owned server directories')
parser.add_argument('--output', type=pathlib.Path, help='Report path (default verification/paper-io-probe.json)')
parser.add_argument('--stop-file', type=pathlib.Path, help='Stop early after this marker appears')
parser.add_argument('--existing', action='store_true', help='Analyze three already recorded a/b/c-jdbc.jfr files without starting JVM recordings')
args = parser.parse_args()
run = args.run_directory.resolve()
root = pathlib.Path(__file__).resolve().parents[1]
jdk = pathlib.Path(os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')).parent
names = set(args.servers.split(','))
assert names and names <= {'a', 'b', 'c'}, 'Server names must be a,b,c'
targets = {}
for proc in pathlib.Path('/proc').iterdir():
    if not proc.name.isdigit(): continue
    try:
        cwd = (proc / 'cwd').resolve()
        if cwd.parent == run and cwd.name in names and b'paper.jar' in (proc / 'cmdline').read_bytes():
            targets[cwd.name] = int(proc.name)
    except (FileNotFoundError, PermissionError): pass
if not args.existing:
    assert set(targets) == names, f'Expected live owned JVMs {names}: {targets}'
else:
    targets = {name: None for name in names}
    assert all((run / (name + '-jdbc.jfr')).exists() for name in targets), 'Existing recordings are required for all three servers'
config = run / 'jdbc-io.jfc'
config.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="VarStore JDBC thread probe" description="Socket events at zero threshold" provider="VarStore tests">
  <event name="jdk.SocketRead"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.SocketWrite"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.ThreadPark"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.FileRead"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
  <event name="jdk.FileWrite"><setting name="enabled">true</setting><setting name="stackTrace">true</setting><setting name="threshold">0 ms</setting></event>
</configuration>''')
started = None
if not args.existing:
    combined = run / 'profile-jdbc.jfc'
    subprocess.run([str(jdk / 'jfr'), 'configure', '--input', str(jdk.parent / 'lib/jfr/profile.jfc') + ',' + str(config), '--output', str(combined)], check=True, capture_output=True, text=True)
    config = combined
    started = time.time()
    for name, pid in targets.items():
        subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.start', 'name=VarStoreIo', 'settings=' + str(config), 'dumponexit=true',
                        'filename=' + str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True)
    print(json.dumps({'event': 'io-probe-started', 'pids': targets, 'seconds': args.seconds}), flush=True)
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline and not (args.stop_file and args.stop_file.exists()):
        time.sleep(min(1 if args.stop_file else 30, max(0, deadline - time.monotonic())))
        print(json.dumps({'event': 'io-probe-recording', 'remainingSeconds': max(0, int(deadline - time.monotonic()))}), flush=True)
report = {'startedAt': started, 'endedAt': None if args.existing else time.time(), 'requestedDurationSeconds': args.seconds,
          'method': 'JFR profile plus SocketRead/SocketWrite, FileRead/FileWrite and ThreadPark threshold 0ms; stack-filtered real Paper JVM threads',
          'scope': 'Observed JDBC socket I/O and VarStore-attributed file I/O/future waits during this interval; first-use PluginClassLoader JAR reads are separately retained, not treated as application storage I/O; does not prove unobserved code paths or waits shorter than recording precision',
          'servers': {}}
if not args.existing:
    for name, pid in targets.items():
        subprocess.run([str(jdk / 'jcmd'), str(pid), 'JFR.stop', 'name=VarStoreIo'], check=True, capture_output=True, text=True)
for name, pid in targets.items():
    summary = subprocess.run([str(jdk / 'jfr'), 'summary', str(run / (name + '-jdbc.jfr'))], check=True, capture_output=True, text=True).stdout
    duration = re.search(r'Duration:\s+(\d+)\s+s', summary)
    recording_start = re.search(r'Start:\s+([^\n]+)', summary)
    # Stream text records rather than materializing a potentially huge JSON tree.
    printing = subprocess.Popen([str(jdk / 'jfr'), 'print', '--events', 'jdk.SocketRead,jdk.SocketWrite,jdk.ThreadPark,jdk.FileRead,jdk.FileWrite', '--stack-depth', '64', str(run / (name + '-jdbc.jfr'))], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    counts = collections.Counter()
    violations, file_violations, wait_violations, classloading_reads = [], [], [], []
    thread, event_type, event_time, jdbc, varstore, future_wait = 'unknown', None, None, False, False, False
    path, stack, classloader = None, [], False
    for line in printing.stdout:
        if line.startswith('jdk.') and line.rstrip().endswith('{'):
            event_type = line.split()[0]; thread = 'unknown'; event_time = None; jdbc = varstore = future_wait = False; path = None; stack = []; classloader = False
        elif 'eventThread = ' in line:
            match = re.search(r'eventThread = "([^"]+)"', line)
            if match: thread = match.group(1)
        elif 'startTime = ' in line: event_time = line.strip().partition(' = ')[2]
        elif 'path = ' in line: path = line.strip().partition(' = ')[2].strip(chr(34))
        elif line.strip() == '}' and event_type:
            evidence = {'event': event_type, 'at': event_time, 'path': path, 'stack': stack}
            if event_type.startswith('jdk.Socket') and jdbc:
                counts[thread] += 1
                if thread == 'Server thread': violations.append(evidence)
            if thread == 'Server thread' and varstore:
                if event_type.startswith('jdk.File'):
                    if event_type == 'jdk.FileRead' and classloader and path and path.endswith(('.jar', '.class')):
                        classloading_reads.append(evidence)
                    else:
                        file_violations.append(evidence)
                if event_type == 'jdk.ThreadPark' and future_wait: wait_violations.append(evidence)
            event_type = None
        else:
            if line.startswith('    ') and '(' in line: stack.append(line.strip())
            classloader |= 'org.bukkit.plugin.java.PluginClassLoader' in line or 'java.lang.ClassLoader.loadClass' in line
            jdbc |= 'postgresql' in line
            varstore |= 'kr.lunaf.varstore.' in line
            future_wait |= any(name in line for name in ('CompletableFuture', 'FutureTask', 'CountDownLatch.await'))
    assert printing.wait(timeout=30) == 0, 'JFR event decoding failed'

    report['servers'][name] = {'pid': pid, 'recordingStart': recording_start.group(1).strip() if recording_start else None, 'recordingDurationSeconds': int(duration.group(1)) if duration else None, 'jdbcSocketEvents': sum(counts.values()), 'threadCounts': dict(counts), 'mainThreadJdbcEvents': violations, 'mainThreadVarStoreFileEvents': file_violations, 'mainThreadClassloadingJarReads': classloading_reads, 'mainThreadClassloadingJarReadCount': len(classloading_reads), 'mainThreadVarStoreFutureWaitEvents': wait_violations}
report['status'] = 'PASS' if all(s['jdbcSocketEvents'] > 0 and not s['mainThreadJdbcEvents'] and not s['mainThreadVarStoreFileEvents'] and not s['mainThreadVarStoreFutureWaitEvents'] for s in report['servers'].values()) else 'FAIL'
output = args.output or root / 'verification' / 'paper-io-probe.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if report['status'] != 'PASS': raise SystemExit(1)
