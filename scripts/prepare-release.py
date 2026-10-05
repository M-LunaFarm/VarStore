#!/usr/bin/env python3
"""Gate a patch release on current DB-backed tests and record fresh provenance."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from release_support import ROOT, VERSION


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    notes = ROOT / 'release-notes' / f'{VERSION}.txt'
    if not notes.is_file():
        raise SystemExit(f'Missing release notes: {notes}')
    evidence = ROOT / 'verification' / f'v{VERSION}'
    contracts_path = evidence / 'contracts.json'
    subprocess.run([sys.executable, str(ROOT / 'scripts/record-contracts.py'),
                    '--output', str(contracts_path)], check=True)
    contracts = json.loads(contracts_path.read_text())
    files = sorted(p for p in ROOT.glob(f'**/build/libs/*-{VERSION}*.jar')
                   if '.local' not in p.parts)
    if not files:
        raise SystemExit('No current-version JARs')
    for module in ('varstore-paper', 'varstore-tools'):
        with zipfile.ZipFile(ROOT / module / 'build/libs' / f'{module}-{VERSION}.jar') as jar:
            names = jar.namelist()
            if not all(any(n.startswith(prefix) for n in names) for prefix in
                       ('kr/lunaf/varstore/internal/postgresql/', 'kr/lunaf/varstore/internal/hikari/')):
                raise SystemExit(f'Missing shaded dependencies: {module}')
            if any(n.startswith(('org/postgresql/', 'com/zaxxer/hikari/')) for n in names):
                raise SystemExit(f'Unrelocated JDBC/pool: {module}')
            if module == 'varstore-paper' and f"version: '{VERSION}'" not in jar.read('plugin.yml').decode():
                raise SystemExit('Paper plugin version mismatch')
    for module in ('preferences', 'rewards', 'quests', 'structured'):
        with zipfile.ZipFile(ROOT / 'examples' / module / 'build/libs' / f'{module}-{VERSION}.jar') as jar:
            if any(n.startswith('kr/lunaf/varstore/api/') for n in jar.namelist()):
                raise SystemExit(f'Example bundles API classes: {module}')
    report = {
        'version': VERSION, 'schemaVersion': 2, 'status': 'PASS',
        'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'buildCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain',
                                                        '--untracked-files=no'], cwd=ROOT, text=True).strip()),
        'sourceTreeSha256': contracts['sourceTreeSha256'],
        'tests': contracts['totalTests'], 'contractReportSha256': sha(contracts_path),
        'artifactChecks': ['relocated JDBC and Hikari', 'Paper version', 'examples exclude API'],
        'artifacts': [{'path': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size,
                       'sha256': sha(p)} for p in files],
        'limitations': [
            'Patch verification includes fresh unit and PostgreSQL contract tests.',
            'Live Paper smoke, fault and load evidence elsewhere in verification is historical; not rerun for this patch.',
            'Static security review and finite tests do not prove absence of all vulnerabilities.'
        ]
    }
    (evidence / 'release.json').write_text(json.dumps(report, indent=2) + '\n')
    subprocess.run([sys.executable, str(ROOT / 'scripts/package-release.py')], check=True)
    output = ROOT / '.local' / f'release-{VERSION}'
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write(f'version={VERSION}\noutput={output}\nnotes={notes}\n')
    print(f'PASS: v{VERSION}; {contracts["totalTests"]} tests; {output}')


if __name__ == '__main__':
    main()
