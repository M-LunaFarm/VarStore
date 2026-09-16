#!/usr/bin/env python3
"""Collect release JARs and a checksummed operator/developer distribution."""
import hashlib
import json
import pathlib
import re
import shutil
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
version = re.search(r'version = "([^"]+)"', (root / 'build.gradle.kts').read_text()).group(1)
output = root / '.local' / ('release-' + version)
output.mkdir(parents=True, exist_ok=True)
artifacts = [
    ('varstore-paper', 'varstore-paper', ''),
    ('varstore-tools', 'varstore-tools', ''),
    ('varstore-api', 'varstore-api', ''),
    ('varstore-api', 'varstore-api', '-sources'),
    ('varstore-api', 'varstore-api', '-javadoc'),
    ('varstore-testkit', 'varstore-testkit', ''),
    ('examples/preferences', 'preferences', ''),
    ('examples/rewards', 'rewards', ''),
    ('examples/quests', 'quests', ''),
    ('examples/structured', 'structured', ''),
    ('varstore-placeholderapi', 'varstore-placeholderapi', ''),
    ('varstore-skript', 'varstore-skript', ''),
    *[(module, module, suffix) for module in ('varstore-cache', 'varstore-codec') for suffix in ('', '-sources', '-javadoc')],
]
names = []
for module, base, suffix in artifacts:
    name = f'{base}-{version}{suffix}.jar'
    source = root / module / 'build/libs' / name
    if not source.is_file():
        raise SystemExit('Missing built artifact: ' + str(source))
    shutil.copy2(source, output / name)
    names.append(name)
archive = output / f'varstore-{version}-distribution.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
    for name in names:
        bundle.write(output / name, 'jars/' + name)
    for name in ('README.md', 'LICENSE'):
        bundle.write(root / name, name)
    for source in sorted((root / 'verification').rglob('*')):
        if source.is_file():
            bundle.write(source, str(source.relative_to(root)))
    for module in ('preferences', 'rewards', 'quests', 'structured'):
        for source in sorted((root / 'examples' / module / 'src').rglob('*')):
            if source.is_file():
                bundle.write(source, str(source.relative_to(root)))
names.append(archive.name)
checksums = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in names}
(output / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in checksums.items()))
print(json.dumps({'version': version, 'output': str(output), 'artifacts': checksums}, indent=2))
