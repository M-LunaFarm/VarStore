#!/usr/bin/env python3
"""Finite repeated-outage resource rehearsal against the owned loopback fault container.
Build runtime-classpath.txt first. This script never targets a supplied production URL.
"""
import argparse
import json
import os
import pathlib
import queue
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTAINER = 'varstore-fault-postgres'
DATABASE = 'varstore_soak'
parser = argparse.ArgumentParser()
parser.add_argument('--duration-seconds', type=int, default=180)
parser.add_argument('--cycles', type=int, default=10)
args = parser.parse_args()
if args.duration_seconds < 180 or args.cycles < 10:
    parser.error('Recorded T22 rehearsal requires at least 180 seconds and 10 cycles')
if args.duration_seconds / args.cycles < 10:
    parser.error('Allow at least 10 seconds per outage/recovery cycle')
JAVA = os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
classpath = (ROOT / 'varstore-testkit/build/runtime-classpath.txt').read_text().strip()
env = dict(os.environ, VARSTORE_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:25435/varstore_soak',
           VARSTORE_TEST_DB_USER='varstore', VARSTORE_TEST_DB_PASSWORD='varstore-test')
(ROOT / '.local').mkdir(exist_ok=True)
(ROOT / 'verification').mkdir(exist_ok=True)
report = {'test': 'T22', 'status': 'FAIL', 'fault': 'actual docker stop/start of dedicated PostgreSQL18 container',
          'requestedDurationSeconds': args.duration_seconds, 'requestedCycles': args.cycles, 'outages': []}
transcript = []
lines = queue.Queue()
process = None
stopped = False
started = time.monotonic()


def run(command, **kwargs):
    return subprocess.run(command, check=True, capture_output=True, text=True, **kwargs)


def database_ready():
    until = time.monotonic() + 30
    while time.monotonic() < until:
        result = subprocess.run(['docker', 'exec', CONTAINER, 'pg_isready', '-U', 'varstore'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return
        time.sleep(.2)
    raise AssertionError('Dedicated database did not become ready')


def command(text):
    process.stdin.write(text + '\n')
    process.stdin.flush()


def marker(expected, timeout=45):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            line = lines.get(timeout=.5)
            if line.strip() == expected:
                print(expected, flush=True)
                return
        except queue.Empty:
            if process.poll() is not None:
                raise AssertionError('Outage harness exited before ' + expected + ': ' + ''.join(transcript[-8:]))
    raise AssertionError('Timed out awaiting ' + expected)


def sleep_until(deadline):
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError('Outage harness stopped unexpectedly')
        time.sleep(min(.5, deadline - time.monotonic()))


try:
    info = json.loads(run(['docker', 'inspect', CONTAINER]).stdout)[0]
    if not info['Config']['Image'].startswith('postgres:18'):
        raise AssertionError('Expected dedicated PostgreSQL18 image')
    ports = info['HostConfig']['PortBindings'].get('5432/tcp', [])
    if not any(binding['HostPort'] == '25435' and binding['HostIp'] in ('127.0.0.1', '::1') for binding in ports):
        raise AssertionError('Expected fault database bound only to loopback port25435')
    database_ready()
    exists = run(['docker', 'exec', CONTAINER, 'psql', '-U', 'varstore', '-d', 'postgres', '-Atc',
                  "SELECT 1 FROM pg_database WHERE datname='varstore_soak'"]).stdout.strip()
    if exists != '1':
        run(['docker', 'exec', CONTAINER, 'createdb', '-U', 'varstore', DATABASE])
    process = subprocess.Popen([JAVA, '-Xmx256m', '-cp', classpath,
                                'kr.lunaf.varstore.testkit.OutageHarness', str(ROOT / '.local/outage-harness.json')],
                               cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)

    def collect():
        for line in process.stdout:
            transcript.append(line)
            lines.put(line)

    threading.Thread(target=collect, daemon=True).start()
    marker('OUTAGE_READY')
    workload_started = time.monotonic()
    for cycle in range(1, args.cycles + 1):
        sleep_until(workload_started + ((cycle - 1) + .25) * args.duration_seconds / args.cycles)
        outage_start = time.monotonic()
        run(['docker', 'stop', '--time', '2', CONTAINER])
        stopped = True
        command('DOWN ' + str(cycle))
        marker('OUTAGE_DOWN_OBSERVED ' + str(cycle), 15)
        sleep_until(outage_start + 2)
        run(['docker', 'start', CONTAINER])
        stopped = False
        database_ready()
        restart_ready = time.monotonic()
        command('UP ' + str(cycle))
        marker('OUTAGE_RECOVERED ' + str(cycle))
        report['outages'].append({'cycle': cycle, 'downStartedAfterSeconds': round(outage_start - workload_started, 3),
                                  'databaseUnavailableSeconds': round(restart_ready - outage_start, 3),
                                  'fullReconciliationSeconds': round(time.monotonic() - outage_start, 3),
                                  'explicitReadFailureObserved': True, 'explicitWriteFailureObserved': True,
                                  'originalIdsReconciledAndCounterVerified': True})
    sleep_until(workload_started + args.duration_seconds)
    command('FINISH')
    marker('OUTAGE_FINISHED PASS', 60)
    if process.wait(timeout=15) != 0:
        raise AssertionError('Outage harness returned failure')
    report['measurement'] = json.loads((ROOT / '.local/outage-harness.json').read_text())
    report['workloadSeconds'] = round(time.monotonic() - workload_started, 3)
    if report['measurement']['status'] != 'PASS' or report['measurement']['cycles'] != args.cycles:
        raise AssertionError('Incomplete or failed measurements')
    report['status'] = 'PASS'
except Exception as error:
    report['failure'] = type(error).__name__ + ': ' + str(error)
    if (ROOT / '.local/outage-harness.json').exists():
        report['measurement'] = json.loads((ROOT / '.local/outage-harness.json').read_text())
    raise
finally:
    if stopped:
        subprocess.run(['docker', 'start', CONTAINER], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if process is not None and process.poll() is None:
        process.kill()
        process.wait(timeout=10)
    report['elapsedSeconds'] = round(time.monotonic() - started, 3)
    report['recordedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    (ROOT / '.local/outage-transcript.log').write_text(''.join(transcript))
    (ROOT / 'verification/outage.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'measurement'}, indent=2), flush=True)
