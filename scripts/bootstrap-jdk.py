#!/usr/bin/env python3
"""Install the verified JDK in .tools only. No daemon, PATH or global Java changes."""
import hashlib
import json
import platform
import subprocess
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'scripts/jdk-lock.json').read_text())
if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
    raise SystemExit('This pinned JDK is for Linux x86_64; install Java 17 and set JAVA_HOME on other platforms')
destination = root / '.tools/jdk'
if (destination / 'bin/java').exists():
    subprocess.run([str(destination / 'bin/java'), '-version'], check=True)
else:
    archive = root / '.tools/jdk.tar.gz'
    archive.parent.mkdir(exist_ok=True)
    urllib.request.urlretrieve(lock['url'], archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']:
        raise SystemExit('JDK checksum mismatch; archive not extracted')
    destination.mkdir(exist_ok=True)
    subprocess.run(['tar', '-xzf', str(archive), '--strip-components=1', '-C', str(destination)], check=True)
    subprocess.run([str(destination / 'bin/java'), '-version'], check=True)
