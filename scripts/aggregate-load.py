#!/usr/bin/env python3
"""Independently aggregate raw benchmark samples from three real Paper servers.

Validates slot coverage, request proportions and main-thread provenance. Reported
percentiles use nearest rank over all individual samples, never mean percentiles.
Capacity errors remain visible; thresholds describe observations, not guarantees.
"""
from pathlib import Path
import argparse, csv, datetime, json, math, collections, re
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input',type=Path,default=root/'verification/paper-benchmark')
parser.add_argument('--output',type=Path,default=root/'verification/load-paper-summary.json')
args=parser.parse_args()
directory=args.input.resolve()
def source_name(path):
    try:return str(path.relative_to(root))
    except ValueError:return str(path)
def instant(value):
    # Java Instant has nanoseconds; Python3.10 accepts at most microseconds.
    text=re.sub(r"(\.\d{6})\d+(?=[+-]|$)",r"\1",value.replace("Z","+00:00"))
    return datetime.datetime.fromisoformat(text)
def percentile(values,p):
    values=sorted(values)
    return values[max(0, math.ceil(len(values)*p)-1)] if values else None
result={'generatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'provenance':'Independent union of raw CSV samples from three real Paper main-thread benchmark plugins', 'quantileMethod':'nearest rank on union of per-request samples; per-server percentiles are not averaged', 'baselineKeys':100000, 'phases':{}}
for phase in ('baseline','large','transaction','hot'):
    rows=[]; reports=[]; source_paths=[]
    for name in 'abc':
        csv_path=directory/f'{name}-{phase}.csv'; json_path=directory/f'{name}-{phase}.json'
        report=json.loads(json_path.read_text());reports.append(report)
        with csv_path.open() as handle:
            client_rows=list(csv.DictReader(handle))
        assert len(client_rows)==report['requests']
        assert all(int(row['sequence'])%3==report['serverIndex'] for row in client_rows)
        rows.extend(client_rows);source_paths.extend([source_name(csv_path),source_name(json_path)])
    durations={report['requestedSeconds'] for report in reports}
    assert len(durations)==1,'All three servers must run the same phase duration'
    seconds=durations.pop()
    assert {report['serverIndex'] for report in reports}=={0,1,2}
    assert len(rows)==100*seconds
    assert {int(row['sequence']) for row in rows}==set(range(seconds*100))
    assert all(row['main_thread']=='true' for row in rows)
    assert all(report['allSubmissionsOnMainThread'] and report['outstanding']==0 for report in reports)
    errors=collections.Counter(row['error'] for row in rows if row['error'])
    summary={'sourceFiles':source_paths,'requests':len(rows),'errors':dict(errors),'allMainThread':True,'allGlobalSlotsPresentExactlyOnce':True,'submissionP50Micros':percentile([float(row['submission_us']) for row in rows],.50),'submissionP95Micros':percentile([float(row['submission_us']) for row in rows],.95),'submissionP99Micros':percentile([float(row['submission_us']) for row in rows],.99)}
    starts=[instant(r['startedAt']) for r in reports]
    finishes=[instant(r['finishedAt']) for r in reports]
    span=(max(finishes)-min(starts)).total_seconds()
    summary['observedSpanSeconds']=span;summary['observedRequestsPerSecond']=len(rows)/span
    summary['observedSpanDefinition']='earliest first submission through latest completion report, including request drain'
    summary['startSkewMillis']=(max(starts)-min(starts)).total_seconds()*1000
    for operation,expected_tenths in [('read',7),('write',3)]:
        matching=[row for row in rows if row['operation']==operation]
        assert len(matching)==len(rows)*expected_tenths//10
        latencies=[float(row['latency_us'])/1000 for row in matching if not row['error']]
        summary[operation]={'count':len(matching),'successP50Millis':percentile(latencies,.5),'successP95Millis':percentile(latencies,.95),'successP99Millis':percentile(latencies,.99)}
    summary['transactionTargetSharing']=sorted({r.get('transactionTargetSharing','unspecified in source report') for r in reports})
    def within(value,limit):return value is not None and value<=limit
    summary['referenceThresholdsMet']={'readP95':within(summary['read']['successP95Millis'],25),'readP99':within(summary['read']['successP99Millis'],100),'writeP95':within(summary['write']['successP95Millis'],50),'writeP99':within(summary['write']['successP99Millis'],150),'submissionP99':within(summary['submissionP99Micros'],1000),'zeroErrors':not errors}
    result['phases'][phase]=summary
result['limitations']=['Nominal 100 requests/s comes from 5 slots per 20-Hz server tick; observed throughput reports actual wall time and server start skew.','Thresholds are measurements on the observed shared test host, not a universal performance guarantee.','These short runs do not establish absence of all long-term resource leaks.']
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({phase:{'readP95Ms':v['read']['successP95Millis'],'readP99Ms':v['read']['successP99Millis'],'writeP95Ms':v['write']['successP95Millis'],'writeP99Ms':v['write']['successP99Millis'],'submissionP99Us':v['submissionP99Micros'],'errors':v['errors']} for phase,v in result['phases'].items()},indent=2))
