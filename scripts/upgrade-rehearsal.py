#!/usr/bin/env python3
"""Upgrade an explicitly selected, backed-up loopback V1 fixture and compare all rows.
Runs no production discovery; requires VARSTORE_UPGRADE_TEST_DB and a V1 tools JAR.
"""
import hashlib,json,os,pathlib,subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
DB=os.environ['VARSTORE_UPGRADE_TEST_DB']
if DB not in ('varstore_verify','varstore_upgrade'):raise SystemExit('Expected owned upgrade test database')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
ENV=dict(os.environ,VARSTORE_JDBC_URL='jdbc:postgresql://127.0.0.1:25432/'+DB,VARSTORE_DB_USER='varstore',VARSTORE_DB_PASSWORD='varstore-test',VARSTORE_TLS_MODE='disable')
def sql(query):return subprocess.check_output(['docker','exec','varstore-postgres','psql','-U','varstore','-d',DB,'-At','-v','ON_ERROR_STOP=1','-c',query],text=True).strip()
def fingerprint():
    out={}
    for table in ('vs_networks','vs_variables','vs_operations','vs_admin_audit'):
        count,digest=sql(f"SELECT count(*),md5(COALESCE(string_agg(row_hash,'' ORDER BY row_hash),'')) FROM (SELECT md5(row_to_json(t)::text) row_hash FROM {table} t) x").split('|')
        out[table]={'rows':int(count),'rowJsonMd5Aggregate':digest}
    out['v1History']=sql('SELECT row_to_json(t) FROM vs_schema_history t WHERE version=1')
    return out
old=ROOT/'.local/release-1.0.0/varstore-tools-1.0.0.jar'
new=ROOT/'varstore-tools/build/libs/varstore-tools-1.3.0.jar'
assert old.is_file() and new.is_file()
assert sql('SELECT max(version) FROM vs_schema_history')=='1','Fixture already upgraded; restore an owned backup first'
backup=ROOT/'.local/upgrade-backups'/f'{DB}-rehearsal-v1.dump';backup.parent.mkdir(exist_ok=True)
with backup.open('wb') as out:subprocess.run(['docker','exec','varstore-postgres','pg_dump','-U','varstore','-Fc',DB],stdout=out,check=True)
report={'schemaBefore':1,'backupBytes':backup.stat().st_size,'backupSha256':hashlib.sha256(backup.read_bytes()).hexdigest(),'before':fingerprint()}
subprocess.run([JAVA,'-jar',str(old),'validate'],env=ENV,check=True)
network=sql('SELECT network_id FROM vs_networks ORDER BY network_id LIMIT 1')
start=time.monotonic();subprocess.run([JAVA,'-jar',str(new),'migrate',network],env=ENV,check=True)
report['upgradeSeconds']=round(time.monotonic()-start,3);report['after']=fingerprint();assert report['before']==report['after']
subprocess.run([JAVA,'-jar',str(new),'validate'],env=ENV,check=True)
old_validation=subprocess.run([JAVA,'-jar',str(old),'validate'],env=ENV,capture_output=True,text=True)
assert old_validation.returncode!=0,'V1 runtime must reject V2 on startup'
report.update(schemaAfter=2,status='PASS',oldRuntimeStartup='REJECTED',recordedAt=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()))
(ROOT/'verification/upgrade-v1-v2.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
