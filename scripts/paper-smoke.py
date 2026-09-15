#!/usr/bin/env python3
"""Real loopback Paper/Bungee/bot verification. Requires built jars, DB18 and explicit test inputs.

Input jars: .local/paper.jar (Paper1.21.11), .local/bungee.jar (Bungee2093),
.local/bungee-modules/cmd_server.jar. npm ci --prefix scripts/bots before running.
All owned processes are shut down. Existing listeners are never reused or killed.
"""
import collections, hashlib, json, os, pathlib, queue, re, shutil, socket, subprocess, threading, time, urllib.parse, uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
RUN = ROOT / '.local' / ('paper-smoke-' + time.strftime('%Y%m%d-%H%M%S'))
REPORT = ROOT / 'verification' / 'paper-smoke.json'
ENV = dict(os.environ)
ENV.update(VARSTORE_JDBC_URL=os.environ.get('VARSTORE_JDBC_URL', 'jdbc:postgresql://127.0.0.1:25432/varstore'),
           VARSTORE_DB_USER=os.environ.get('VARSTORE_DB_USER', 'varstore'),
           VARSTORE_DB_PASSWORD=os.environ.get('VARSTORE_DB_PASSWORD', 'varstore-test'), VARSTORE_TLS_MODE='disable')
DB_NAME = os.environ.get('VARSTORE_TEST_DB_NAME') or urllib.parse.urlsplit(ENV['VARSTORE_JDBC_URL'].removeprefix('jdbc:')).path.lstrip('/')
NETWORK = 'papersmoke-' + uuid.uuid4().hex[:8]
processes = []
report = {'network': NETWORK, 'paper': '1.21.11 build 132', 'bungee': '2093', 'java': '21',
          'postgres': '18', 'scope': 'loopback offline-auth integration only', 'checks': {},
          'artifactSha256': {name: hashlib.sha256((ROOT / '.local' / name).read_bytes()).hexdigest() for name in ('paper.jar', 'bungee.jar')},
          'mineflayer': '4.39.0'}

class Process:
    def __init__(self, name, args, cwd):
        self.name, self.lines, self.events = name, [], queue.Queue()
        self.file = (RUN / (name + '-' + str(len(processes)) + '.log')).open('w')
        self.p = subprocess.Popen(args, cwd=cwd, env=ENV, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
        processes.append(self)
        threading.Thread(target=self.read, daemon=True).start()
    def read(self):
        for line in self.p.stdout:
            self.file.write(line); self.file.flush(); self.lines.append(line); self.events.put(line)
    def send(self, text):
        self.p.stdin.write(text + '\n'); self.p.stdin.flush()
    def wait(self, needle, start=0, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            for line in self.lines[start:]:
                if needle in line: return line.strip()
            if self.p.poll() is not None: raise AssertionError(f'{self.name} exited {self.p.returncode}: {self.lines[-8:]}')
            time.sleep(.05)
        raise AssertionError(f'{self.name} timed out waiting for {needle}: {self.lines[-8:]}')
    def command(self, text, expected, seconds=30):
        start = len(self.lines); self.send(text); return self.wait(expected, start, seconds)
    def stop(self):
        if self.p.poll() is None:
            try: self.send('end' if self.name == 'proxy' else 'stop'); self.p.wait(timeout=40)
            except (subprocess.TimeoutExpired, BrokenPipeError): self.p.kill(); self.p.wait()

def artifact(module, pattern):
    choices = list((ROOT / module / 'build' / 'libs').glob(pattern))
    choices = [p for p in choices if not any(x in p.name for x in ('sources', 'javadoc', 'thin'))]
    if len(choices) != 1: raise AssertionError(f'Expected one built {module} artifact, got {choices}')
    return choices[0]

def prepare_paper(name, port):
    path = RUN / name; (path / 'plugins' / 'VarStore').mkdir(parents=True)
    shutil.copy2(ROOT / '.local/paper.jar', path / 'paper.jar')
    for cache_name in ('cache', 'libraries', 'versions'):
        cached = ROOT / '.local/paper-cache' / cache_name
        if cached.is_dir(): (path / cache_name).symlink_to(cached, target_is_directory=True)
    modules = ['varstore-paper', 'examples/preferences', 'examples/rewards']
    if (ROOT / 'varstore-testkit-paper/build/libs').is_dir(): modules.append('varstore-testkit-paper')
    for module in modules:
        shutil.copy2(artifact(module, '*.jar'), path / 'plugins')
    config = (ROOT / 'varstore-paper/src/main/resources/config.yml').read_text()
    config = config.replace('network-id: production', 'network-id: ' + NETWORK).replace('server-id: survival-1', 'server-id: ' + name).replace('tls-mode: verify-full', 'tls-mode: disable')
    (path / 'plugins/VarStore/config.yml').write_text(config)
    (path / 'eula.txt').write_text('eula=true\n')
    (path / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\ngenerator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}\ngenerate-structures=false\ngamemode=creative\nmax-players=10\nallow-nether=false\nsync-chunk-writes=true\n')
    (path / 'spigot.yml').write_text('settings:\n  bungeecord: true\nworld-settings:\n  default:\n    verbose: false\n')
    (path / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')
    (path / 'config').mkdir(exist_ok=True)
    (path / 'config/paper-global.yml').write_text('proxies:\n  bungee-cord:\n    online-mode: false\n')
    return path

def start_paper(name):
    process = Process(name, [JAVA, '-Xms256m', '-Xmx640m', '-XX:ActiveProcessorCount=2', '-jar', 'paper.jar', '--nogui'], RUN / name)
    process.wait('Done (', seconds=300); process.wait('VarStore READY', seconds=30)
    return process

def set_value(server, key, kind, value):
    line = server.command(f'varstore set {NETWORK} smoke NETWORK _ SYSTEM global {key} {kind} {value}', 'Confirm within')
    token = re.search(r'confirm ([0-9a-f-]{36})', line).group(1)
    return server.command('varstore confirm ' + token, 'outcome=APPLIED')

def inspect(server, key, kind, value):
    line = server.command(f'varstore inspect {NETWORK} smoke NETWORK _ SYSTEM global {key} {kind}', 'value/version=')
    assert f'value={value}' in line, line

def bot_command(bot, text, expected):
    start = len(bot.lines)
    bot.send(json.dumps({'action': 'chat', 'text': text, 'id': uuid.uuid4().hex}))
    return bot.wait(expected, start, 30)

try:
    RUN.mkdir(parents=True); REPORT.parent.mkdir(exist_ok=True)
    for module in ('varstore-paper', 'varstore-tools', 'examples/preferences', 'examples/rewards'):
        report['artifactSha256'][module] = hashlib.sha256(artifact(module, '*.jar').read_bytes()).hexdigest()
    for port in (25580, 25581, 25582, 25583):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    subprocess.run([JAVA, '-jar', str(artifact('varstore-tools', '*.jar')), 'migrate', NETWORK], env=ENV, check=True)
    for name, port in (('a', 25581), ('b', 25582), ('c', 25583)): prepare_paper(name, port)
    servers = {name: start_paper(name) for name in ('a', 'b', 'c')}
    print(json.dumps({'event': 'paper-ready', 'pids': {name: server.p.pid for name, server in servers.items()}, 'network': NETWORK}), flush=True)
    set_value(servers['a'], 'admin-conflict', 'LONG', '1')
    preview = servers['a'].command(f'varstore set {NETWORK} smoke NETWORK _ SYSTEM global admin-conflict LONG 2', 'Confirm within')
    stale_token = re.search(r'confirm ([0-9a-f-]{36})', preview).group(1)
    set_value(servers['b'], 'admin-conflict', 'LONG', '3')
    servers['a'].command('varstore confirm ' + stale_token, 'outcome=CONDITION_FAILED')
    servers['a'].command('varstore confirm ' + stale_token, 'Invalid or expired confirmation token')
    inspect(servers['b'], 'admin-conflict', 'LONG', '3')
    report['checks']['T21'] = 'PASS: real console preview on A, commit on B, stale confirmation condition fails and token cannot be reused'
    values = [('string', 'STRING', 'durable-value'), ('long', 'LONG', '9223372036854775807'),
              ('boolean', 'BOOLEAN', 'false'), ('uuid', 'UUID', str(uuid.uuid4()))]
    for key, kind, value in values: set_value(servers['a'], key, kind, value)
    # SIGKILL immediately follows the last confirmed commit (no orderly plugin drain).
    servers['a'].p.kill(); servers['a'].p.wait()
    for key, kind, value in values: inspect(servers['b'], key, kind, value)
    report['checks']['T02'] = 'PASS: SIGKILL after acknowledged writes; independent B reads all 4 types'
    servers['a'] = start_paper('a')
    for key, kind, value in values: inspect(servers['a'], key, kind, value)
    servers['a'].stop(); servers['a'] = start_paper('a')
    for key, kind, value in values: inspect(servers['a'], key, kind, value)
    report['checks']['T01'] = 'PASS: all 4 types and values after process restart'
    unicode_owner = '운영팀-섬'
    preview = servers['a'].command(f'varstore set {NETWORK} smoke NETWORK _ SYSTEM {unicode_owner} title STRING unicode-owner-value', 'Confirm within')
    token = re.search(r'confirm ([0-9a-f-]{36})', preview).group(1)
    servers['a'].command('varstore confirm ' + token, 'outcome=APPLIED')
    line = servers['b'].command(f'varstore inspect {NETWORK} smoke NETWORK _ SYSTEM {unicode_owner} title STRING', 'value/version=')
    assert 'value=unicode-owner-value' in line, line
    report['checks']['unicode-owner'] = 'PASS: well-formed UTF-8 SYSTEM owner, spelling preserved; saved on A and read on B'
    report['checks']['T13'] = 'PASS: 3 Paper servers; confirmed writes and cross-server reads with 0 connected players'
    proxy = RUN / 'proxy'; (proxy / 'modules').mkdir(parents=True)
    shutil.copy2(ROOT / '.local/bungee.jar', proxy / 'bungee.jar')
    shutil.copy2(ROOT / '.local/bungee-modules/cmd_server.jar', proxy / 'modules/cmd_server.jar')
    (proxy / 'modules.yml').write_text('version: 2\nmodules: []\n')
    (proxy / 'config.yml').write_text('online_mode: false\nip_forward: true\nconnection_throttle: -1\nnetwork_compression_threshold: 256\nserver_connect_timeout: 5000\ntimeout: 30000\nplayer_limit: 10\nlog_commands: false\nlog_pings: false\npermissions:\n  default:\n  - bungeecord.command.server\nservers:\n  a:\n    address: 127.0.0.1:25581\n    restricted: false\n    motd: A\n  b:\n    address: 127.0.0.1:25582\n    restricted: false\n    motd: B\n  c:\n    address: 127.0.0.1:25583\n    restricted: false\n    motd: C\nlisteners:\n- host: 127.0.0.1:25580\n  query_enabled: false\n  query_port: 25584\n  priorities: [a]\n  force_default_server: true\n  max_players: 10\n  motd: VarStore isolated smoke\n  tab_list: GLOBAL_PING\n  tab_size: 10\n  ping_passthrough: false\n  bind_local_address: false\n  proxy_protocol: false\n  forced_hosts: {}\n')
    proxy_process = Process('proxy', [JAVA, '-Xms64m', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-jar', 'bungee.jar'], proxy)
    proxy_process.wait('Listening on', seconds=60)
    bot = Process('bot', ['node', str(ROOT / 'scripts/bots/client.cjs')], ROOT)
    bot.wait('"event":"spawn"', seconds=60)
    bot.wait('Network chat is', seconds=30)
    bot_command(bot, '/preferences on', 'Preference committed: true')
    bot_command(bot, '/varstore status', 'Permission denied.')
    bot_command(bot, '/varstore inspect', 'Permission denied.')
    bot_command(bot, '/varstore set', 'Permission denied.')
    bot_command(bot, '/varstore diagnostics', 'Permission denied.')
    report['checks']['permissions'] = 'PASS: unprivileged bot denied all 4 administrator permission families'
    # Actual held database row lock delays the consumer save; transfer follows its commit callback.
    lock_env = dict(ENV, PGPASSWORD=ENV['VARSTORE_DB_PASSWORD'])
    lock = subprocess.Popen(['psql', '-h', '127.0.0.1', '-p', '25432', '-U', ENV['VARSTORE_DB_USER'], '-d', DB_NAME, '-XqAt', '-v', 'ON_ERROR_STOP=1'],
                            env=lock_env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
    lock.stdin.write(f"BEGIN; SELECT variable_key FROM vs_variables WHERE network_id='{NETWORK}' AND namespace='varstorepreferences' FOR UPDATE;\n\\echo LOCKED\n")
    lock.stdin.flush()
    assert lock.stdout.readline().strip() == 'chat-visible', 'Expected the existing preference row to be locked'
    assert lock.stdout.readline().strip() == 'LOCKED', 'Database lock must be acquired before submitting the delayed write'
    def release_lock():
        lock.stdin.write('COMMIT;\n\\q\n'); lock.stdin.flush()
    release = threading.Timer(.3, release_lock); release.start()
    started = time.monotonic(); bot_command(bot, '/preferences off', 'Preference committed: false')
    delay = time.monotonic() - started
    release.join()
    assert lock.wait(timeout=10) == 0 and delay >= .2, f'Expected real DB-delayed save, observed {delay}'
    bot_command(bot, '/server b', 'Network chat is hidden')
    bot_command(bot, '/preferences', 'Network chat is hidden')
    report['checks']['T14'] = f'PASS: DB row lock delayed acknowledged preference save by {delay:.3f}s; actual Bungee transfer A -> B loaded committed false'
    bot_command(bot, '/dailyreward', 'Daily reward committed: +1 point')
    bot_command(bot, '/server a', 'Network chat is hidden')
    bot_command(bot, '/dailyreward', 'Daily reward already committed')
    bot_command(bot, '/dailyreward balance', 'Reward points: 1')
    report['checks']['consumers'] = 'PASS: persistent chat visibility + UTC daily reward; cross-server replay retained exactly 1 DB point'
    report['status'] = 'PASS'
    bench_done = threading.Event()
    bench_errors = []
    def run_benchmarks():
        try:
            if os.environ.get('VARSTORE_BENCH_REQUIRE_START_SIGNAL') == 'true':
                print(json.dumps({'event': 'paper-benchmark-awaiting-signal', 'runDirectory': str(RUN), 'marker': str(RUN / 'benchmark-start')}), flush=True)
                while not (RUN / 'benchmark-start').exists(): time.sleep(.5)
            bench_env = dict(ENV, VARSTORE_TEST_JDBC_URL=ENV['VARSTORE_JDBC_URL'])
            classpath = (ROOT / 'varstore-testkit/build/runtime-classpath.txt').read_text().strip()
            seed = subprocess.run([JAVA, '-cp', classpath, 'kr.lunaf.varstore.testkit.LoadHarness', 'seed-paper', NETWORK],
                                  cwd=ROOT, env=bench_env, check=True, capture_output=True, text=True)
            print(seed.stdout, flush=True)
            def ready(server):
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    line = server.command('varstore status', 'VarStore state=')
                    if 'state=READY ' in line: return
                    time.sleep(1)
                raise AssertionError('Storage did not recover before next benchmark phase: ' + server.name)
            def heaps():
                results = {}
                for name, server in servers.items():
                    status = pathlib.Path('/proc') / str(server.p.pid) / 'status'
                    rss = next((line.partition(':')[2].strip() for line in status.read_text().splitlines() if line.startswith('VmRSS:')), None)
                    snapshot = subprocess.run([str(pathlib.Path(JAVA).parent / 'jcmd'), str(server.p.pid), 'GC.heap_info'],
                                              check=True, capture_output=True, text=True)
                    results[name] = {'pid': server.p.pid, 'rss': rss, 'heapInfo': snapshot.stdout}
                return results
            for server in servers.values(): ready(server)
            warm_positions = {name: len(server.lines) for name, server in servers.items()}
            for index, name in enumerate(('a', 'b', 'c')): servers[name].send(f'varstorebench start 20 {index} baseline')
            for index, name in enumerate(('a', 'b', 'c')):
                servers[name].wait(f'VARSTORE_BENCH_COMPLETED phase=baseline server={index}', warm_positions[name], 60)
            report['benchmarkConfiguration'] = {'warmupSeconds': 20, 'jfrDuringTimedBenchmark': False,
                    'paperJvmArguments': ['-Xms256m', '-Xmx640m', '-XX:ActiveProcessorCount=2'],
                    'transactionKeys': '16 distinct keys per server; shared LONG is tested separately by hot phase'}
            report['paperHeapsBeforeBenchmark'] = heaps()
            print(json.dumps({'event': 'paper-bench-ready', 'runDirectory': str(RUN), 'heapSnapshots': report['paperHeapsBeforeBenchmark']}), flush=True)
            benchmark_directory = ROOT / 'verification/paper-benchmark'
            benchmark_directory.mkdir(parents=True, exist_ok=True)
            report['paperBenchmarkReports'] = []
            report['stressOutcomes'] = {}
            for phase, seconds in (('baseline', 60), ('large', 20), ('transaction', 20), ('hot', 20)):
                for server in servers.values(): ready(server)
                positions = {name: len(server.lines) for name, server in servers.items()}
                for index, name in enumerate(('a', 'b', 'c')):
                    servers[name].send(f'varstorebench start {seconds} {index} {phase}')
                aggregate = aggregate_reads = aggregate_writes = 0
                read_errors, write_errors = collections.Counter(), collections.Counter()
                for index, name in enumerate(('a', 'b', 'c')):
                    servers[name].wait(f'VARSTORE_BENCH_COMPLETED phase={phase} server={index}', positions[name], seconds + 45)
                    folder = RUN / name / 'plugins/VarStoreBench'
                    files = sorted(folder.glob(f'bench-{index}-{phase}-*.json'))
                    assert files, f'Missing benchmark report for {name}/{phase}'
                    source = files[-1]
                    data = json.loads(source.read_text())
                    assert data['network'] == NETWORK and data['allSubmissionsOnMainThread'] and data['outstanding'] == 0, data
                    aggregate += data['requests']; aggregate_reads += data['reads']['count']; aggregate_writes += data['writes']['count']
                    read_errors.update(data['reads']['errors']); write_errors.update(data['writes']['errors'])
                    for extension in ('.json', '.csv'):
                        shutil.copy2(source.with_suffix(extension), benchmark_directory / (name + '-' + phase + extension))
                    report['paperBenchmarkReports'].append('verification/paper-benchmark/' + name + '-' + phase + '.json')
                assert aggregate == seconds * 100, (phase, aggregate)
                assert aggregate_reads == seconds * 70 and aggregate_writes == seconds * 30, (phase, aggregate_reads, aggregate_writes)
                if phase == 'baseline': assert not read_errors and not write_errors, ('Baseline errors', read_errors, write_errors)
                else: report['stressOutcomes'][phase] = {'reads': dict(read_errors), 'writes': dict(write_errors)}
                if phase == 'hot':
                    for server in servers.values(): ready(server)
                    check = subprocess.run(['psql', '-h', '127.0.0.1', '-p', '25432', '-U', ENV['VARSTORE_DB_USER'], '-d', DB_NAME, '-Atc',
                            f"SELECT long_value FROM vs_variables WHERE network_id='{NETWORK}' AND namespace='varstorebench' AND owner_id='load' AND variable_key='hot'"],
                            env=dict(ENV, PGPASSWORD=ENV['VARSTORE_DB_PASSWORD']), check=True, capture_output=True, text=True)
                    actual = int(check.stdout.strip())
                    confirmed = aggregate_writes - sum(write_errors.values())
                    uncertain = write_errors.get('UNKNOWN_COMMIT_OUTCOME', 0)
                    assert confirmed <= actual <= confirmed + uncertain, (actual, confirmed, uncertain)
                    report['hotKeyIndependentCounter'] = {'actual': actual, 'confirmedSuccesses': confirmed, 'unknownOutcomes': uncertain}
                print(json.dumps({'event': 'paper-bench-phase-complete', 'phase': phase, 'aggregateRequests': aggregate,
                                  'readErrors': dict(read_errors), 'writeErrors': dict(write_errors)}), flush=True)
            report['checks']['paper-main-thread-benchmark'] = 'PASS: warmed baseline zero errors; 3 real Bukkit main-thread submitters;100req/s70/30;12 JSON+CSV reports; stress error counts reported separately'
            report['paperHeapsAfterBenchmark'] = heaps()
            # Observe JDBC threading separately so event stack capture does not distort timed phases.
            probe = Process('io-probe', ['python3', str(ROOT / 'scripts/paper-io-probe.py'), str(RUN), '--seconds', '10'], ROOT)
            probe.wait('io-probe-started', seconds=30)
            probe.p.wait(timeout=50)
            assert probe.p.returncode == 0, 'JFR I/O probe failed; inspect its owned log'
            print(json.dumps({'event': 'paper-load-ready', 'runDirectory': str(RUN), 'network': NETWORK}), flush=True)
        except Exception as failure:
            bench_errors.append(str(failure))
        finally:
            bench_done.set()
    benchmarking = (ROOT / 'varstore-testkit-paper/build/libs').is_dir()
    if benchmarking: threading.Thread(target=run_benchmarks, daemon=True).start()
    else: bench_done.set()
    hold = min(600, max(0, int(os.environ.get('VARSTORE_SMOKE_HOLD_SECONDS', '180' if benchmarking else '0'))))
    report['concurrentGameActions'] = []
    while hold:
        if bench_errors: raise AssertionError('Paper benchmark: ' + bench_errors[0])
        if bench_done.is_set() and (RUN / 'load-completed').exists(): break
        print(json.dumps({'event': 'paper-hold', 'remainingSeconds': hold}), flush=True)
        for text in ('/preferences toggle', '/dailyreward balance'):
            first = len(bot.lines)
            bot.send(json.dumps({'action': 'chat', 'text': text, 'id': uuid.uuid4().hex}))
            response = bot.wait('\"event\":\"message\"', first, 30)
            report['concurrentGameActions'].append({'at': time.time(), 'command': text, 'response': json.loads(response)['message']})
        interval = min(5, hold); time.sleep(interval); hold -= interval
    assert bench_done.wait(timeout=30), 'Paper benchmark did not finish within hold'
    if bench_errors: raise AssertionError('Paper benchmark: ' + bench_errors[0])
    bot.send(json.dumps({'action': 'quit'})); bot.p.wait(timeout=10)
except Exception as error:
    report['status'] = 'FAIL'; report['error'] = str(error)
    raise
finally:
    for process in reversed(processes):
        if process.name == 'bot' and process.p.poll() is None: process.p.kill(); process.p.wait()
        else: process.stop()
    report['recordedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    REPORT.parent.mkdir(exist_ok=True); REPORT.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
