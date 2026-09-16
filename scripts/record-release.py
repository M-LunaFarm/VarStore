#!/usr/bin/env python3
"""Validate release evidence and record current source/binary provenance."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / 'verification'
VERSION = '1.3.0'

def read(name):
    return json.loads((VERIFY / name).read_text())

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def write(name, value):
    (VERIFY / name).write_text(json.dumps(value, indent=2) + '\n')

def main():
    reports = {name: read(name) for name in ('contracts.json', 'paper-extension.json',
        'extension-io-probe.json', 'extensions-load.json', 'commit-response.json',
        'recovery.json', 'outage.json', 'upgrade-v1-v2.json', 'operator-tools.json')}
    for name, report in reports.items():
        if name == 'operator-tools.json':
            assert report['existingCollisionExitCode'] == 2
            assert 'databaseWrites=0 rows=4 validRows=4 invalidRows=0' in report['importOffline'][0]
            assert 'databaseChecked=true' in report['importDatabase'][0]
        else:
            assert report['status'] == 'PASS', (name, report.get('status'))
    contracts = reports['contracts.json']
    current_source = hashlib.sha256()
    for path in sorted(ROOT.glob('**/src/**/*')):
        if path.is_file() and '.local' not in path.parts and 'node_modules' not in path.parts:
            current_source.update(str(path.relative_to(ROOT)).encode() + b'\0' + path.read_bytes())
    assert current_source.hexdigest() == contracts['sourceTreeSha256'], 'Tests must describe current source'
    for suite in contracts['suites']:
        assert suite['tests'] and not any(suite[k] for k in ('failures', 'errors', 'skipped'))
    for rel, expected in reports['extensions-load.json']['artifactsSha256'].items():
        assert sha(ROOT / rel) == expected, ('load artifact changed', rel)
    for module, expected in reports['paper-extension.json']['artifactSha256'].items():
        if module.startswith(('varstore-', 'examples/')):
            path = ROOT / module / 'build/libs' / f'{module.split("/")[-1]}-{VERSION}.jar'
            assert sha(path) == expected, ('Paper artifact changed', module)
    assert reports['paper-extension.json']['ownedProcessesTerminal']
    assert len(reports['paper-extension.json']['checks']) == 11
    for name, check in reports['paper-extension.json']['checks'].items():
        assert (check.startswith('PASS') if isinstance(check, str) else check['status'] == 'PASS'), name
    paper_path = ROOT / f'varstore-paper/build/libs/varstore-paper-{VERSION}.jar'
    with zipfile.ZipFile(paper_path) as jar:
        names = jar.namelist()
        assert any('internal/postgresql/' in n for n in names), 'Missing relocated JDBC'
        assert any('internal/hikari/' in n for n in names), 'Missing relocated pool'
        assert not any(n.startswith(('org/postgresql/', 'com/zaxxer/')) for n in names)
    for module in ('preferences', 'rewards', 'quests', 'structured'):
        with zipfile.ZipFile(ROOT / f'examples/{module}/build/libs/{module}-{VERSION}.jar') as jar:
            assert not any(n.startswith('kr/lunaf/varstore/api/') for n in jar.namelist())
    files = sorted(p for p in ROOT.glob(f'**/build/libs/*-{VERSION}*.jar') if '.local' not in p.parts)
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    implementation = subprocess.check_output(['git', 'log', '-1', '--format=%H', '--',
        ':(glob)**/src/**'], cwd=ROOT, text=True).strip()
    write('artifacts.json', {'version': VERSION, 'schemaVersion': 2, 'recordedAt': now,
        'implementationCommit': implementation, 'sourceTreeSha256': current_source.hexdigest(),
        'artifacts': [{'path': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size, 'sha256': sha(p)} for p in files],
        'serverJarChecks': {'relocatedJdbcAndPool': True, 'examplesExcludeApiClasses': True}})
    gates = {
        'existingStorageContracts': ['contracts.json', 'commit-response.json', 'recovery.json', 'outage.json'],
        'sequentialMigrationAndV1Receipts': ['upgrade-v1-v2.json', 'contracts.json:ExtensionContractTest'],
        'definitionsAndBoundedOwnerPrefixPages': ['contracts.json:LocalKeyRegistryTest', 'contracts.json:ExtensionContractTest', 'paper-extension.json:admin-metadata-cache'],
        'boundedSameIdUnknownOutcomeRecovery': ['contracts.json:PendingWriteManagerTest', 'commit-response.json'],
        'paperSessionTransferAndLifecycle': ['contracts.json:PaperSessionsTest', 'contracts.json:TrackedWritesTest', 'paper-extension.json'],
        'atomicOutboxFanoutLeasesOrderRetryRetentionResync': ['contracts.json:ExtensionContractTest', 'contracts.json:EventHubLifecycleTest', 'extensions-load.json'],
        'explicitCacheBudgetsRacesExpiryAndEpoch': ['contracts.json:DisplayCacheTest', 'contracts.json:ExtensionIntegrationTest', 'extensions-load.json'],
        'placeholderAndSkriptPinnedOptionalAddons': ['paper-extension.json'],
        'boundedImmutableVersionedCodec': ['contracts.json:CodecAdapterTest', 'paper-extension.json:structured-codec-consumer'],
        'capacityAndReadOnlyCsvImportValidation': ['operator-tools.json', 'contracts.json:CsvDryRunTest'],
        'gameThreadStorageIoAndIdGeneration': ['extension-io-probe.json', 'contracts.json:RuntimeIdsTest'],
        'writeCostAndFiniteResourceBounds': ['extensions-load.json', 'outage.json'],
    }
    write('release-audit.json', {'version': VERSION, 'schemaVersion': 2, 'recordedAt': now,
        'implementationCommit': implementation, 'sourceTreeSha256': current_source.hexdigest(),
        'totalTests': contracts['totalTests'], 'status': 'PASS',
        'scope': 'goal.md proposed releases 1.1, 1.2, 1.3 including bounded Codec; historical v1 evidence is archived separately',
        'gates': {name: {'status': 'PASS', 'evidence': evidence} for name, evidence in gates.items()},
        'evidenceSha256': {name: sha(VERIFY / name) for name in reports},
        'limitations': [
            'Finite local integration/soak observations do not establish all long-term leak or production latency guarantees.',
            'Paper TPS/MSPT snapshots are low-player functional observations, not a capacity benchmark.',
            'JFR first-use PluginClassLoader JAR reads are retained separately; zero application I/O is not zero classloading I/O.',
            'WAL LSN deltas cover the whole PostgreSQL cluster, including background activity and other databases.',
            'V1 large Paper benchmarks are historical and are not results for the extension runtime.',
            'Upgrade rehearsal retained all legacy row hashes; migration sources were unchanged by later ID and subscription lifecycle fixes.'
        ],
        'preservedDiagnostics': ['diagnostic-extension-initial/', 'diagnostic-extension-jfr/',
            'diagnostic-build-contention/', 'diagnostic-extension-churn/'],
        'explicitlyDeferred': ['TTL', 'proxy JVM adapters', 'Folia', 'additional storage engines',
            'large objects', 'inventory synchronization', 'economy-specific ledger']})
    print(f'PASS: {contracts["totalTests"]} tests; {len(files)} binary artifacts; {len(gates)} evidence gates')

if __name__ == '__main__':
    main()
