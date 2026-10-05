"""Shared source of the current release version for operator scripts."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
match = re.search(r'^\s*version = "(\d+\.\d+\.\d+)"$', (ROOT / 'build.gradle.kts').read_text(), re.MULTILINE)
if match is None:
    raise ValueError('Expected a stable semantic release version in build.gradle.kts')
VERSION = match.group(1)
