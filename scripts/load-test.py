#!/usr/bin/env python3
"""Run the measured standalone load harness with inspectable host/runtime evidence.

Build first: ./gradlew :varstore-testkit:classes :varstore-testkit:writeRuntimeClasspath
Select only a disposable database with VARSTORE_TEST_JDBC_URL. No credentials are
written to evidence. Run while Paper/game tests are active to record their overlap.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def utcnow():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def host_snapshot():
    memory = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        parts = line.split()
        if parts[0].rstrip(':') in ('MemTotal', 'MemAvailable', 'SwapTotal'):
            memory[parts[0].rstrip(':') + 'Bytes'] = int(parts[1]) * 1024
    cpu_model = next((line.partition(':')[2].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')), None)
    return {'at': utcnow(), 'logicalCpuCount': os.cpu_count(), 'cpuModel': cpu_model,
            'memory': memory, 'platform': platform.platform(), 'loadAverage': os.getloadavg(),
            'referenceHostMatched': False,
            'referenceHostNote': 'No assertion that this shared host matches the proposed 4-vCPU/8-GiB PostgreSQL reference profile.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path, default=ROOT/'varstore-testkit/build/runtime-classpath.txt')
    parser.add_argument('--java', default='/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
    parser.add_argument('--output', type=Path, default=ROOT/'verification')
    parser.add_argument('--baseline-seconds', type=int, default=60)
    parser.add_argument('--variant-seconds', type=int, default=20)
    parser.add_argument('--rate', type=int, default=100)
    parser.add_argument('--paper-evidence', type=Path)
    args = parser.parse_args()
    if not os.environ.get('VARSTORE_TEST_JDBC_URL'):
        parser.error('VARSTORE_TEST_JDBC_URL must select a disposable test database')
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {'startedAt': utcnow(), 'before': host_snapshot(),
                'requestedTiming': {'baselineSeconds': args.baseline_seconds, 'variantSeconds': args.variant_seconds, 'requestsPerSecond': args.rate},
                'paperEvidencePath': str(args.paper_evidence) if args.paper_evidence else None,
                'submissionProvenance': 'three standalone core clients; not Paper main-thread submissions'}
    environment = os.environ.copy()
    if args.paper_evidence:
        environment['VARSTORE_LOAD_PAPER_EVIDENCE'] = str(args.paper_evidence)
    command = [args.java, '-Xms128m', '-Xmx512m', '-cp', args.classpath_file.read_text().strip(),
               'kr.lunaf.varstore.testkit.LoadHarness', str(args.output),
               str(args.baseline_seconds), str(args.variant_seconds), str(args.rate)]
    process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # Stream the exact harness output; the launch arguments contain no credentials.
    with (args.output/'load-console.log').open('w') as log:
        for line in process.stdout:
            print(line, end='', flush=True)
            log.write(line)
    status = process.wait()
    evidence['exitCode'] = status
    evidence['after'] = host_snapshot()
    evidence['finishedAt'] = utcnow()
    if args.paper_evidence and args.paper_evidence.exists():
        paper = json.loads(args.paper_evidence.read_text())
        evidence['paperEvidenceObserved'] = True
        evidence['paperConcurrentGameActions'] = paper.get('concurrentGameActions', [])
        evidence['paperEvidenceStatusAtRead'] = paper.get('status')
        evidence['paperEvidenceNetworkAtRead'] = paper.get('network')
        evidence['paperEvidenceRecordedAtRead'] = paper.get('recordedAt')
        evidence['paperEvidenceNote'] = 'The Paper runner may write its final evidence after this load run ends; correlate final action timestamps with startedAt/finishedAt before claiming overlap.'
    else:
        evidence['paperEvidenceObserved'] = False
    (args.output/'load-environment.json').write_text(json.dumps(evidence, indent=2)+'\n')
    return status

if __name__ == '__main__':
    sys.exit(main())
