#!/usr/bin/env python3
"""Run identical v1/v2 write microbenchmarks followed by the bounded extension soak."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seconds', type=int, default=180)
    args = parser.parse_args()
    if not 180 <= args.seconds <= 600:
        parser.error('--seconds must be 180..600')
    env = dict(os.environ)
    env.setdefault('VARSTORE_TEST_JDBC_URL', 'jdbc:postgresql://127.0.0.1:25432/varstore_extensions')
    env.setdefault('VARSTORE_TEST_DB_USER', 'varstore')
    env.setdefault('VARSTORE_TEST_DB_PASSWORD', 'varstore-test')
    old = ROOT / '.local/release-1.0.0/varstore-tools-1.0.0.jar'
    harness = ROOT / 'varstore-testkit/build/libs/varstore-testkit-1.3.0.jar'
    runtime = (ROOT / 'varstore-testkit/build/runtime-classpath.txt').read_text().strip()
    current = str(harness) + os.pathsep + runtime
    artifacts = [old, harness] + [Path(p) for p in runtime.split(os.pathsep) if p.endswith('.jar') and '/varstore-' in p]
    provenance = {str(p.relative_to(ROOT)): digest(p) for p in artifacts}
    folder = ROOT / '.local'
    output = ROOT / 'verification/extensions-load.json'
    folder.mkdir(exist_ok=True)
    start = time.monotonic()
    with (folder / 'extensions-load.log').open('w') as log:
        def run(classpath, main_class, arguments, timeout):
            command = [JAVA, '-Xms64m', '-Xmx256m', '-cp', classpath, main_class] + arguments
            print('RUN', main_class, *arguments, flush=True)
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            log.flush()
            if result.returncode:
                raise RuntimeError(f'{main_class} failed ({result.returncode}); inspect .local/extensions-load.log')
        baseline = folder / 'extensions-write-v1.json'
        upgraded = folder / 'extensions-write-v2.json'
        soak = folder / 'extensions-soak.json'
        run(str(harness) + os.pathsep + str(old), 'kr.lunaf.varstore.testkit.WriteCostHarness', ['original-1.0.0', str(baseline)], 120)
        run(current, 'kr.lunaf.varstore.testkit.WriteCostHarness', ['extended-1.3.0', str(upgraded)], 120)
        print('WRITE_COMPARISON_FINISHED', flush=True)
        run(current, 'kr.lunaf.varstore.testkit.ExtensionLoadHarness', [str(args.seconds), str(soak)], args.seconds + 120)
    v1, v2, load = [json.loads(p.read_text()) for p in [baseline, upgraded, soak]]
    if v1['schemaVersion'] != 1 or v2['schemaVersion'] != 2:
        raise AssertionError('Baseline/current schema provenance mismatch')
    if v1['outboxEvents'] != 0 or v2['outboxEvents'] != 2200:
        raise AssertionError('Baseline/current event accounting mismatch')
    report = {'status': 'PASS', 'elapsedSeconds': time.monotonic() - start,
              'java': JAVA, 'artifactsSha256': provenance, 'baseline': v1, 'extended': v2,
              'walCounterScope': 'POSTGRESQL_CLUSTER (all databases and background activity)',
              'walFieldNote': 'databaseWideWalBytes is a legacy field name; pg_current_wal_lsn is cluster-wide, not database-specific.',
              'comparison': {'p50LatencyRatio': v2['p50Micros'] / v1['p50Micros'],
                             'p95LatencyRatio': v2['p95Micros'] / v1['p95Micros'],
                             'p99LatencyRatio': v2['p99Micros'] / v1['p99Micros'],
                             'walRatio': v2['databaseWideWalBytes'] / v1['databaseWideWalBytes'],
                             'order': 'original then extended, fresh schemas, same host, single sequential worker'},
              'soak': load}
    output.write_text(json.dumps(report, indent=2) + '\n')
    print('EXTENSION_LOAD_FINISHED', output, flush=True)

if __name__ == '__main__':
    main()
