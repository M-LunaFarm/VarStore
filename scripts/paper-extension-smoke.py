#!/usr/bin/env python3
"""Real loopback Paper/Bungee/bot verification. Requires built jars, DB18 and explicit test inputs.

Input jars: .local/paper.jar (Paper1.21.11), .local/bungee.jar (Bungee2093),
.local/bungee-modules/cmd_server.jar. npm ci --prefix scripts/bots before running.
All owned processes are shut down. Existing listeners are never reused or killed.
"""
import collections, hashlib, json, os, pathlib, queue, re, shutil, socket, subprocess, threading, time, urllib.parse, uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('VARSTORE_TEST_JAVA', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java')
RUN = ROOT / '.local' / ('paper-extension-' + time.strftime('%Y%m%d-%H%M%S'))
REPORT = ROOT / 'verification' / 'paper-extension.json'
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
                if needle == "VS_SCALAR_LIST_PASS" and "VS_TEST_FAILED" in line: raise AssertionError(line.strip())
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
    modules = ['varstore-paper', 'examples/preferences', 'examples/rewards', 'examples/quests', 'examples/structured', 'varstore-skript', 'varstore-placeholderapi']

    for module in modules:
        shutil.copy2(artifact(module, '*.jar'), path / 'plugins')
    config = (ROOT / 'varstore-paper/src/main/resources/config.yml').read_text()
    config = config.replace('network-id: production', 'network-id: ' + NETWORK).replace('server-id: survival-1', 'server-id: ' + name).replace('tls-mode: verify-full', 'tls-mode: disable')
    config = config.replace('enabled: false', 'enabled: true').replace('shared-namespaces: {}', 'shared-namespaces:\n  VarStorePlaceholders: [varstorepreferences, varstorequests]')
    (path / 'plugins/VarStore/config.yml').write_text(config)
    for addon in ('skript', 'placeholderapi'): shutil.copy2(ROOT / '.local' / (addon + '.jar'), path / 'plugins')
    scriptdir = path / 'plugins/Skript/scripts'; scriptdir.mkdir(parents=True)
    script = (ROOT / 'scripts/fixtures/varstore-addon.sk').read_text()
    for key in ('STRING', 'LONG', 'ADD', 'BOOL', 'UUID', 'STALE', 'UNLOAD'): script = script.replace('@' + key + '_ID@', str(uuid.uuid4()))
    (scriptdir / 'varstore-addon.sk').write_text(script)
    placeholders = path / 'plugins/VarStorePlaceholders'; placeholders.mkdir()
    (placeholders / 'config.yml').write_text('max-entries: 1000\nmax-bytes: 2097152\nmax-age-seconds: 30\nrefresh-ticks: 10\nstates:\n  miss: LOADING\n  absent: ABSENT\n  stale: STALE\n  unavailable: UNAVAILABLE\nmappings:\n  chat:\n    namespace: varstorepreferences\n    key: chat-visible\n    scope: network\n  kills:\n    namespace: varstorequests\n    key: quests/monster-kills\n    scope: network\n')
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

held_locks = []

def held_row(namespace, key):
    lock = subprocess.Popen(['psql', '-h', '127.0.0.1', '-p', '25432', '-U', ENV['VARSTORE_DB_USER'], '-d', DB_NAME, '-XqAt', '-v', 'ON_ERROR_STOP=1'],
                            env=dict(ENV, PGPASSWORD=ENV['VARSTORE_DB_PASSWORD']), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
    lock.stdin.write(f"BEGIN; SELECT variable_key FROM vs_variables WHERE network_id='{NETWORK}' AND namespace='{namespace}' AND variable_key='{key}' FOR UPDATE;\n\\echo LOCKED\n"); lock.stdin.flush()
    held_locks.append(lock)
    assert lock.stdout.readline().strip() == key
    assert lock.stdout.readline().strip() == 'LOCKED'
    return lock

def release(lock):
    lock.stdin.write('COMMIT;\n\\q\n'); lock.stdin.flush(); assert lock.wait(timeout=10) == 0

def health_snapshot(servers):
    result = {}
    for name, server in servers.items():
        tps = server.command('tps', 'TPS from')
        start = len(server.lines); server.command('mspt', 'Server tick times'); time.sleep(.1)
        result[name] = {'tps': tps, 'msptRawLines': server.lines[start:]}
    return result

def write(server, namespace, key, kind, value):
    line = server.command(f'varstore set {NETWORK} {namespace} NETWORK _ SYSTEM addon-test {key} {kind} {value}', 'Confirm within')
    return server.command('varstore confirm ' + re.search(r'confirm ([0-9a-f-]{36})', line).group(1), 'outcome=APPLIED')

io_probe = None
try:
    RUN.mkdir(parents=True); REPORT.parent.mkdir(exist_ok=True)
    for port in (25580, 25581, 25582):
        with socket.socket() as probe: probe.bind(('127.0.0.1', port))
    for module in ('varstore-paper', 'varstore-tools', 'examples/preferences', 'examples/rewards', 'examples/quests', 'examples/structured', 'varstore-skript', 'varstore-placeholderapi'):
        report['artifactSha256'][module] = hashlib.sha256(artifact(module, '*.jar').read_bytes()).hexdigest()
    for addon in ('skript', 'placeholderapi'): report['artifactSha256'][addon] = hashlib.sha256((ROOT / '.local' / (addon + '.jar')).read_bytes()).hexdigest()
    report['skript'] = '2.16.1'; report['placeholderapi'] = '2.12.3'
    subprocess.run([JAVA, '-jar', str(artifact('varstore-tools', '*.jar')), 'migrate', NETWORK], env=ENV, check=True)
    for name, port in (('a', 25581), ('b', 25582)): prepare_paper(name, port)
    servers = {name: start_paper(name) for name in ('a', 'b')}
    for server in servers.values(): server.wait('Successfully registered internal expansion: varstore', seconds=30)
    print(json.dumps({'event': 'extension-paper-ready', 'run': str(RUN), 'network': NETWORK}), flush=True)
    report['healthBeforeGameplay'] = health_snapshot(servers)
    io_probe = Process('io-probe', ['python3', str(ROOT / 'scripts/paper-io-probe.py'), str(RUN), '--servers', 'a,b', '--seconds', '300', '--stop-file', str(RUN / 'io-completed'), '--output', str(ROOT / 'verification/extension-io-probe.json')], ROOT)
    io_probe.wait('io-probe-started', seconds=30)
    servers['a'].command('vstest', 'VS_SCALAR_LIST_PASS', seconds=60)
    assert not any('VS_TEST_FAILED' in line for line in servers['a'].lines)
    report['checks']['skript-scalars-list-errors'] = 'PASS: actual Skript continuation with STRING/LONG/BOOLEAN/UUID, atomic increment, two metadata pages, explicit network/server scope, ABSENT vs TYPE_MISMATCH failure'
    servers['a'].command('varstore describe varstorepreferences chat-visible', 'Local definition type=BOOLEAN')
    servers['a'].command(f'varstore keys {NETWORK} varstoreskript NETWORK _ SYSTEM addon-test test/ 2', 'Metadata page=')
    servers['a'].command('varstore cache', 'Display cache CacheMetrics')
    report['checks']['admin-metadata-cache'] = 'PASS: live definition/list/cache diagnostics'
    proxy = RUN / 'proxy'; (proxy / 'modules').mkdir(parents=True)
    shutil.copy2(ROOT / '.local/bungee.jar', proxy / 'bungee.jar')
    shutil.copy2(ROOT / '.local/bungee-modules/cmd_server.jar', proxy / 'modules/cmd_server.jar')
    (proxy / 'modules.yml').write_text('version: 2\nmodules: []\n')
    (proxy / 'config.yml').write_text('online_mode: false\nip_forward: true\nconnection_throttle: -1\nserver_connect_timeout: 5000\ntimeout: 30000\nplayer_limit: 10\npermissions:\n  default:\n  - bungeecord.command.server\nservers:\n  a:\n    address: 127.0.0.1:25581\n    restricted: false\n    motd: A\n  b:\n    address: 127.0.0.1:25582\n    restricted: false\n    motd: B\nlisteners:\n- host: 127.0.0.1:25580\n  query_enabled: false\n  priorities: [a]\n  force_default_server: true\n  max_players: 10\n  motd: VarStore extension smoke\n  tab_list: GLOBAL_PING\n  tab_size: 10\n  ping_passthrough: false\n  bind_local_address: false\n  proxy_protocol: false\n  forced_hosts: {}\n')
    proxy_process = Process('proxy', [JAVA, '-Xms64m', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-jar', 'bungee.jar'], proxy)
    proxy_process.wait('Listening on', seconds=60)
    bot = Process('bot', ['node', str(ROOT / 'scripts/bots/client.cjs')], ROOT)
    bot.wait('"event":"spawn"', seconds=60); bot.wait('Quest ready:', seconds=30)
    bot_command(bot, '/preferences on', 'Preference committed: true')
    profile_operation = str(uuid.uuid4())
    bot_command(bot, '/profilesettings get', 'Profile settings: not set')
    bot_command(bot, '/profilesettings save ko_kr true ' + profile_operation, 'Profile settings committed: outcome=APPLIED')
    bot_command(bot, '/profilesettings save ko_kr true ' + profile_operation, 'Profile settings committed: outcome=APPLIED')
    bot_command(bot, '/profilesettings save en_us false ' + profile_operation, 'IDEMPOTENCY_KEY_REUSED')
    def placeholder(server, expression, expected, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            start = len(server.lines); server.send('papi parse VarStoreSmoke ' + expression)
            time.sleep(.15)
            payloads = [re.sub(r'\x1b\[[0-9;]*m', '', line).split(']: ', 1)[-1].strip() for line in server.lines[start:]]
            if expected in payloads: return
        raise AssertionError('Placeholder did not produce ' + expected)
    placeholder(servers['a'], '%varstore_chat%', 'true')
    placeholder(servers['a'], '%varstore_forbidden%', '%varstore_forbidden%')
    report['checks']['placeholder-allowlist'] = 'PASS: configured chat display loads; unknown placeholder remains unexpanded'
    if ENV.get('VARSTORE_TEST_MAIN_DB_OUTAGE') == 'true':
        parsed = urllib.parse.urlsplit(ENV['VARSTORE_JDBC_URL'].removeprefix('jdbc:'))
        assert parsed.hostname == '127.0.0.1' and parsed.port == 25432 and DB_NAME == 'varstore_extensions'
        container = json.loads(subprocess.check_output(['docker', 'inspect', 'varstore-postgres'], text=True))[0]
        assert container['Name'] == '/varstore-postgres' and container['Config']['Image'] == 'postgres:18'
        assert container['HostConfig']['PortBindings'] == {'5432/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '25432'}]}
        assert container['State']['Running'] is True
        try:
            subprocess.run(['docker', 'stop', '-t', '1', 'varstore-postgres'], check=True, capture_output=True, text=True)
            time.sleep(3)
            placeholder(servers['a'], '%varstore_chat%', 'UNAVAILABLE', timeout=10)
        finally:
            subprocess.run(['docker', 'start', 'varstore-postgres'], check=True, capture_output=True, text=True)
        deadline = time.monotonic() + 40
        for server in servers.values():
            while time.monotonic() < deadline:
                line = server.command('varstore status', 'VarStore state=')
                if 'state=READY' in line: break
                time.sleep(.5)
            else: raise AssertionError('Store did not recover after owned DB restart')
        placeholder(servers['a'], '%varstore_chat%', 'true', timeout=20)
        report['checks']['placeholder-db-outage'] = 'PASS: populated VALUE -> actual PostgreSQL stop -> UNAVAILABLE (never ABSENT/default); DB restarted in finally; both stores READY and primary value restored'
    else:
        report['checks']['placeholder-db-outage'] = 'NOT_RUN: requires VARSTORE_TEST_MAIN_DB_OUTAGE=true and the strictly verified owned loopback test container'

    player_id = next(json.loads(line)['uuid'] for line in bot.lines if '"event":"spawn"' in line)
    preview = servers['b'].command(f'varstore set {NETWORK} varstorepreferences NETWORK _ PLAYER {player_id} chat-visible BOOLEAN false', 'Confirm within')
    token = re.search(r'confirm ([0-9a-f-]{36})', preview).group(1)
    servers['b'].command('varstore confirm ' + token, 'outcome=APPLIED')
    invalidation_started = time.monotonic()
    placeholder(servers['a'], '%varstore_chat%', 'false', timeout=2)
    report['checks']['placeholder-fanout'] = {'status': 'PASS', 'maxAgeSeconds': 30, 'observedSecondsAfterOtherServerCommit': time.monotonic() - invalidation_started, 'description': 'A cached true before B committed false; A reload occurred well before TTL'}
    bot_command(bot, '/preferences on', 'Preference committed: true')
    servers['a'].command('execute at VarStoreSmoke run summon zombie ~ ~ ~ {Health:1f,NoAI:1b}', 'Summoned')
    servers['a'].command('damage @e[type=minecraft:zombie,limit=1,sort=nearest] 5 minecraft:player_attack by VarStoreSmoke', 'Applied')
    bot.wait('Quest progress committed: 1', seconds=30)
    lock = held_row('varstorepreferences', 'chat-visible')
    before_transfer = len(servers['b'].lines)
    before_request = len(servers['a'].lines)
    bot.send(json.dumps({'action': 'chat', 'text': '/preferences off'})); time.sleep(.08)
    bot.send(json.dumps({'action': 'chat', 'text': '/preferences transfer b'}))
    servers['a'].wait('issued server command: /preferences transfer b', before_request, seconds=3)
    time.sleep(.1)
    assert not any('VarStoreSmoke joined the game' in line for line in servers['b'].lines[before_transfer:])
    release(lock)
    bot.wait('Quest ready: monster kills=1', seconds=30)
    bot_command(bot, '/preferences', 'Network chat is hidden')
    bot_command(bot, '/questprogress', 'Quest monster kills: 1')
    bot_command(bot, '/profilesettings get', 'Profile settings: language=ko_kr notifications=true')
    report['checks']['structured-codec-consumer'] = 'PASS: explicit Codec save/replay/conflicting-payload rejection and decoded profile settings after actual server movement'
    placeholder(servers['b'], '%varstore_kills%', '1')
    bot_command(bot, '/dailyreward', 'Daily reward committed: +1 point')
    bot_command(bot, '/server a', 'Quest ready: monster kills=1')
    bot_command(bot, '/dailyreward', 'Daily reward already committed')
    report['checks']['game-consumers-transfer'] = 'PASS: actual killed monster increment, tracked-write-gated Bungee movement, Primary quest load on B, daily reward replay across servers'
    write(servers['a'], 'varstoreskript', 'delayed', 'LONG', '1')
    lock = held_row('varstoreskript', 'delayed')
    start = len(servers['a'].lines)
    bot.send(json.dumps({'action': 'chat', 'text': '/vsstale'})); servers['a'].wait('VS_STALE_SUBMITTED', start)
    bot.send(json.dumps({'action': 'quit'})); bot.p.wait(timeout=10)
    time.sleep(.7); release(lock)
    bot = Process('bot', ['node', str(ROOT / 'scripts/bots/client.cjs')], ROOT)
    bot.wait('"event":"spawn"', seconds=60); bot.wait('Quest ready:', seconds=30)
    time.sleep(1)
    assert not any('VS_STALE_CONTINUED' in line for line in servers['a'].lines[start:])
    report['checks']['skript-stale-session'] = 'PASS: blocked write completion after player disconnect does not continue old script into reconnected session'
    lock = held_row('varstoreskript', 'delayed'); start = len(servers['a'].lines)
    servers['a'].command('vsunload', 'VS_UNLOAD_SUBMITTED')
    servers['a'].command('skript disable varstore-addon', 'Successfully disabled')
    time.sleep(.7); release(lock); time.sleep(1)
    assert not any('VS_UNLOAD_CONTINUED' in line for line in servers['a'].lines[start:])
    report['checks']['skript-unloaded-script'] = 'PASS: a real unloaded Skript script cannot resume after pending DB completion'
    report['healthAfterGameplay'] = health_snapshot(servers)
    report['healthScope'] = 'Low-player functional smoke with JFR enabled; raw TPS/MSPT observations, not a performance guarantee'
    (RUN / 'io-completed').touch()
    assert io_probe.p.wait(timeout=120) == 0, 'Extension JFR thread-boundary probe failed'
    report['checks']['extension-io-probe'] = 'PASS: actual extension paths recorded; see verification/extension-io-probe.json for precise scope'
    servers['a'].stop()
    for module in ('varstore-skript', 'varstore-placeholderapi'):
        (RUN / 'a/plugins' / artifact(module, '*.jar').name).unlink()
    for addon in ('skript', 'placeholderapi'): (RUN / 'a/plugins' / (addon + '.jar')).unlink()
    servers['a'] = start_paper('a')
    servers['a'].command(f'varstore inspect {NETWORK} varstoreskript NETWORK _ SYSTEM addon-test test/long LONG', 'value=42')
    report['checks']['optional-addons-absent'] = 'PASS: restarted base VarStore without Skript or PlaceholderAPI jars; committed data still reads'
    report['status'] = 'PASS'
except BaseException as error:
    report['status'] = 'FAIL'; report['error'] = repr(error)
    raise
finally:
    (RUN / 'io-completed').touch()
    if io_probe is not None and io_probe.p.poll() is None:
        try: io_probe.p.wait(timeout=120)
        except subprocess.TimeoutExpired: io_probe.p.kill(); io_probe.p.wait()
    for lock in held_locks:
        if lock.poll() is None:
            try: release(lock)
            except Exception: lock.kill(); lock.wait()
    for process in reversed(processes):
        if process.name == 'bot':
            if process.p.poll() is None:
                try: process.send(json.dumps({'action': 'quit'})); process.p.wait(timeout=5)
                except Exception: process.p.kill(); process.p.wait()
        else: process.stop()
    report['recordedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    report['ownedProcessesTerminal'] = all(process.p.poll() is not None for process in processes)
    REPORT.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)
