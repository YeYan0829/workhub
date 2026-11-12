#!/usr/bin/env python3
"""Inspect/explicitly initialize the dedicated Redis model quota (no app startup reset)."""
import argparse
import decimal
import json
import os
import pathlib
import re
import socket
import uuid


def command(stream, *args):
    parts = [str(x).encode() if not isinstance(x, bytes) else x for x in args]
    stream.write(b'*' + str(len(parts)).encode() + b'\r\n' + b''.join(
        b'$' + str(len(p)).encode() + b'\r\n' + p + b'\r\n' for p in parts))
    stream.flush()
    return read(stream)


def read(stream):
    line = stream.readline()
    if not line.endswith(b'\r\n'):
        raise RuntimeError('Incomplete Redis response; do not assume initialization succeeded')
    kind, value = line[:1], line[1:-2]
    if kind == b'-':
        raise RuntimeError('Redis rejected operation')
    if kind == b':':
        return int(value)
    if kind == b'*':
        return [read(stream) for _ in range(int(value))]
    if kind == b'$':
        length = int(value)
        if length == -1:
            return None
        payload = stream.read(length + 2)
        if len(payload) != length + 2:
            raise RuntimeError('Incomplete Redis response')
        return payload[:-2].decode()
    return value.decode()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=['inspect', 'initialize'])
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=16380)
    p.add_argument('--scope', required=True)
    p.add_argument('--team-version', type=int, help='Connection version from the team configuration API')
    p.add_argument('--rate', default='2.0')
    p.add_argument('--burst', type=int, default=1)
    p.add_argument('--recovery-ms', type=int)
    p.add_argument('--confirmed-prior-cooldown', action='store_true', help='Operator has checked the prior quota window and all applicable cooldowns')
    a = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', a.scope):
        p.error('Invalid scope; use an opaque account/model-class identifier, never a credential')
    rate = decimal.Decimal(a.rate)
    if not rate.is_finite() or not 0 < rate <= 10000 or not 1 <= a.burst <= 10000:
        p.error('Invalid rate/burst')
    # Java Double.toString uses plain notation in this supported CLI range.
    if rate < decimal.Decimal('0.001'):
        p.error('CLI rate must be >= 0.001 requests/second')
    normalized = str(float(rate))
    if '.' not in normalized:
        normalized += '.0'
    if a.team_version is not None and (a.team_version < 1 or not a.scope.startswith('team-')):
        p.error('Team initialization requires a team scope and positive connection version')
    contract = f'team-v1|{a.team_version}|{normalized}|{a.burst}' if a.team_version is not None else f'v1|{normalized}|{a.burst}'
    key = 'workhub:model:{' + a.scope + '}'
    if a.operation == 'initialize' and (not a.confirmed_prior_cooldown or a.recovery_ms is None or a.recovery_ms < int((1000 * a.burst / rate).to_integral_value(rounding=decimal.ROUND_CEILING))):
        p.error('Initialization requires an explicit recovery period covering the quota window and confirmation of prior cooldowns')
    with socket.create_connection((a.host, a.port), timeout=1) as connection, connection.makefile('rwb') as stream:
        password = os.environ.get('MODEL_ADMISSION_REDIS_PASSWORD')
        if password:
            command(stream, 'AUTH', password)
        policy = command(stream, 'CONFIG', 'GET', 'maxmemory-policy')
        if policy != ['maxmemory-policy', 'noeviction']:
            raise RuntimeError('Use a dedicated noeviction Redis for model admission')
        if a.operation == 'inspect':
            result = command(stream, 'HGETALL', key)
            print(json.dumps(dict(zip(result[::2], result[1::2])), ensure_ascii=False, indent=2))
        else:
            lua = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/model/admission.lua'
            result = command(stream, 'EVAL', lua.read_bytes(), 2, key, key + ':init', 'initialize',
                             contract, normalized, a.burst, a.recovery_ms, str(uuid.uuid4()))
            print(json.dumps({'scope': a.scope, 'result': result}, ensure_ascii=False))
            if result[0] != 'INITIALIZED':
                raise SystemExit('Existing state was preserved; inspect it. This command never overwrites a live quota.')


if __name__ == '__main__':
    main()
