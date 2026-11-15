#!/usr/bin/env python3
"""Create local credentials and optionally select an explicitly supplied image."""
import argparse
import os
import re
import secrets
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', help='Set WORKHUB_IMAGE while preserving existing credentials')
args = parser.parse_args()
if args.image is not None and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./:@-]*', args.image):
    parser.error('Image must be a Docker reference without whitespace or shell characters')

root = Path(__file__).resolve().parents[1]
target = root / '.env'
existing = target.exists()
if existing and args.image is None:
    print('.env already exists; left unchanged')
    raise SystemExit(0)

content = target.read_text() if existing else (root / '.env.example').read_text()
if not existing:
    for key in ('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'RABBITMQ_PASSWORD'):
        content = content.replace(key + '=\n', key + '=' + secrets.token_hex(24) + '\n')
if args.image:
    lines = [line for line in content.splitlines() if not line.startswith('WORKHUB_IMAGE=')]
    content = '\n'.join(lines) + '\nWORKHUB_IMAGE=' + args.image + '\n'

if existing:
    fd, temporary = tempfile.mkstemp(prefix='.env-', dir=root)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(content)
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
else:
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(content)
print('Selected image; existing credentials preserved' if existing else
      'Created .env with generated credentials' + (' and selected image' if args.image else ''))
