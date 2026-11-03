#!/usr/bin/env python3
"""Create local-only development credentials; never overwrite existing configuration."""
import os
import secrets
from pathlib import Path

root = Path(__file__).resolve().parents[1]
target = root / '.env'
if target.exists():
    print('.env already exists; left unchanged')
else:
    content = (root / '.env.example').read_text()
    for key in ('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'RABBITMQ_PASSWORD'):
        content = content.replace(key + '=\n', key + '=' + secrets.token_hex(24) + '\n')
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(content)
    print('Created .env with generated development credentials (not displayed)')
