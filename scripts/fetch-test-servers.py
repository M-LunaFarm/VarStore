#!/usr/bin/env python3
"""Fetch exact tested Paper/Bungee artifacts from official hosts and verify SHA-256 before use."""
import argparse, hashlib, os, pathlib, shutil, subprocess, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'
ARTIFACTS = (
    ('paper.jar', 'https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar',
     '5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba'),
    ('bungee.jar', 'https://hub.spigotmc.org/jenkins/job/BungeeCord/2093/artifact/bootstrap/target/BungeeCord.jar',
     '4270d174c64516a27d5e8be3775ab19182f2ce2153635212af957d6feef7a604'),
    ('bungee-modules/cmd_server.jar', 'https://hub.spigotmc.org/jenkins/job/BungeeCord/2093/artifact/module/cmd-server/target/cmd_server.jar',
     '7745b88cada6c3eeffbbd2d513fc4030f09fa5d6d258da489e945d1709228242'),
    ('skript.jar', 'https://github.com/SkriptLang/Skript/releases/download/2.16.1/Skript-2.16.1.jar',
     '8357a348b27cd8a2cf749998e4ad14bd1dca3160026bd3042d6d17cfb4c98a70'),
    ('placeholderapi.jar', 'https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar',
     'fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437'),
)

def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): checksum.update(block)
    return checksum.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-bootstrap', action='store_true', help='Only download and verify the pinned artifacts')
    default_java = os.environ.get('VARSTORE_TEST_JAVA') or (str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    parser.add_argument('--java', default=default_java, help='Java executable for Paper patch-only cache preparation (use Java21)')
    args = parser.parse_args()
    for name, url, checksum in ARTIFACTS:
        destination = LOCAL / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists() or digest(destination) != checksum:
            temporary = destination.with_suffix('.download')
            request = urllib.request.Request(url, headers={'User-Agent': 'VarStore-Verification/1.0 (pinned integration fixtures)'})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output)
            if digest(temporary) != checksum:
                temporary.unlink()
                raise SystemExit('Checksum mismatch for ' + name + '; artifact was not installed or executed')
            temporary.replace(destination)
        print(name + ' SHA256 verified: ' + checksum, flush=True)
    if not args.no_bootstrap:
        if not args.java: parser.error('Java21 is required; supply --java or VARSTORE_TEST_JAVA')
        cache = LOCAL / 'paper-cache'; cache.mkdir(exist_ok=True)
        subprocess.run([args.java, '-Dpaperclip.patchonly=true', '-jar', str(LOCAL / 'paper.jar')], cwd=cache, check=True)
        print('Paper patch-only cache ready; no Minecraft server was started.', flush=True)

if __name__ == '__main__': main()
