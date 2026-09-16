#!/usr/bin/env python3
"""Record real JUnit results after a clean build with PostgreSQL enabled."""
import datetime
import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
suites = []
for path in sorted(root.glob('*/build/test-results/test/TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({'suite': suite.attrib['name'],
                   **{key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')},
                   'seconds': float(suite.attrib['time']),
                   'cases': [case.attrib['name'] for case in suite.findall('testcase')]})
required = {'kr.lunaf.varstore.api.ApiContractTest', 'kr.lunaf.varstore.core.PostgresContractTest',
            'kr.lunaf.varstore.postgres.PostgresContractTest', 'kr.lunaf.varstore.postgres.RuntimeRoleTest',
            'kr.lunaf.varstore.postgres.DeadlockRetryTest',
            'kr.lunaf.varstore.postgres.PostgresSettingsTest', 'kr.lunaf.varstore.paper.AdminPlanTest',
            'kr.lunaf.varstore.paper.ConfirmationTokensTest',
            'kr.lunaf.varstore.api.RuntimeIdsTest', 'kr.lunaf.varstore.core.ExtensionIntegrationTest', 'kr.lunaf.varstore.core.EventHubLifecycleTest', 'kr.lunaf.varstore.core.LocalKeyRegistryTest',
            'kr.lunaf.varstore.core.PendingWriteManagerTest', 'kr.lunaf.varstore.postgres.ExtensionContractTest',
            'kr.lunaf.varstore.cache.DisplayCacheTest', 'kr.lunaf.varstore.codec.CodecAdapterTest',
            'kr.lunaf.varstore.tools.CsvDryRunTest', 'kr.lunaf.varstore.paper.PaperSessionsTest',
            'kr.lunaf.varstore.paper.TrackedWritesTest'}
passed = required <= {suite['suite'] for suite in suites} and all(
    suite['tests'] > 0 and not any(suite[key] for key in ('failures', 'errors', 'skipped')) for suite in suites)
digest = hashlib.sha256()
for path in sorted(root.glob('**/src/**/*')):
    if path.is_file() and '.local' not in path.parts and 'node_modules' not in path.parts:
        digest.update(str(path.relative_to(root)).encode() + b'\0' + path.read_bytes())
report = {'status': 'PASS' if passed else 'FAIL', 'totalTests': sum(s['tests'] for s in suites),
          'suites': suites, 'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'sourceTreeSha256': digest.hexdigest()}
(root / 'verification').mkdir(exist_ok=True)
(root / 'verification/contracts.json').write_text(json.dumps(report, indent=2) + '\n')
print(f"{report['status']}: {report['totalTests']} tests, {len(suites)} suites")
raise SystemExit(0 if passed else 1)
