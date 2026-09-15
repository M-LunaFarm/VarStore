#!/usr/bin/env python3
"""Fail a public commit containing forbidden documents or credential patterns."""
import pathlib
import re
import subprocess
import sys

paths = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
errors = []
for name in filter(None, paths):
    path = pathlib.Path(name)
    if path.suffix.lower() in ('.md', '.markdown', '.mdown') and name != 'README.md':
        errors.append(f'Forbidden Markdown file: {name}')
    if path.is_file() and path.stat().st_size < 2_000_000:
        content = path.read_bytes()
        if re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----)', content):
            errors.append(f'Credential pattern: {name}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'Public tree checked: {sum(bool(p) for p in paths)} tracked paths')
