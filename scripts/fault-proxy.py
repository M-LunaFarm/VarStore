#!/usr/bin/env python3
"""Local PostgreSQL TCP fault injector. Drops actual COMMIT replies, never mocks JDBC.

Only loopback listeners are permitted. Control: POST /arm, POST /clear, GET /status.
A matching frontend COMMIT frame is forwarded, then all backend responses on that
connection are discarded. Other connections continue to work.
"""
import argparse
import asyncio
import json
import time

state = {'armed': False, 'triggered': 0, 'dropped_bytes': 0, 'triggered_at': None}

def observe_frontend(data, connection):
    # Decode actual PostgreSQL frames: READ COMMITTED is not a COMMIT command.
    buffer = connection.setdefault('buffer', bytearray())
    buffer.extend(data)
    statements = connection.setdefault('statements', {})
    while len(buffer) >= 5:
        startup = buffer[0] == 0
        length = int.from_bytes(buffer[:4] if startup else buffer[1:5], 'big')
        total = length if startup else length + 1
        if length < 4 or total > 16_777_216:
            raise ConnectionError('Invalid PostgreSQL frame')
        if len(buffer) < total:
            return
        kind = buffer[0]
        payload = bytes(buffer[4:total] if startup else buffer[5:total])
        del buffer[:total]
        query = b''
        if not startup and kind == ord('P'):
            name, query, _ = payload.split(b'\0', 2)
            statements[name] = query
        elif not startup and kind == ord('Q'):
            query = payload.rstrip(b'\0')
        elif not startup and kind == ord('B'):
            _, name, _ = payload.split(b'\0', 2)
            query = statements.get(name, b'')
        sql = query.strip().rstrip(b';').upper()
        if sql.startswith(b'INSERT INTO VS_OPERATIONS'):
            connection['write_seen'] = True
        if sql == b'COMMIT':
            if state['armed'] and connection.get('write_seen'):
                state['armed'] = False
                state['triggered'] += 1
                state['triggered_at'] = time.time()
                connection['drop'] = True
                print('FAULT_TRIGGER COMMIT', flush=True)
            connection['write_seen'] = False

async def relay(reader, writer, *, frontend, connection):
    try:
        while data := await reader.read(65536):
            if frontend:
                observe_frontend(data, connection)
            if not frontend and connection['drop']:
                state['dropped_bytes'] += len(data)
                continue
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()

async def handle(client_r, client_w):
    try:
        upstream_r, upstream_w = await asyncio.open_connection('127.0.0.1', args.upstream)
    except OSError:
        client_w.close()
        return
    connection = {'drop': False}
    await asyncio.gather(relay(client_r, upstream_w, frontend=True, connection=connection),
                         relay(upstream_r, client_w, frontend=False, connection=connection))

async def control(reader, writer):
    try:
        line = await asyncio.wait_for(reader.readline(), 3)
        pieces = line.decode('ascii').split()
        if len(pieces) < 2:
            return
        method, path = pieces[:2]
        if method == 'POST' and path == '/arm':
            state['armed'] = True
        elif method == 'POST' and path == '/clear':
            state['armed'] = False
        payload = json.dumps(state).encode()
        writer.write(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(payload)).encode() + b'\r\nConnection: close\r\n\r\n' + payload)
        await writer.drain()
    finally:
        writer.close()

async def main():
    server = await asyncio.start_server(handle, '127.0.0.1', args.listen)
    api = await asyncio.start_server(control, '127.0.0.1', args.control)
    print(json.dumps({'ready': True, 'listen': args.listen, 'control': args.control}), flush=True)
    async with server, api:
        await asyncio.gather(server.serve_forever(), api.serve_forever())

parser = argparse.ArgumentParser()
parser.add_argument('--listen', type=int, default=25433)
parser.add_argument('--upstream', type=int, default=25432)
parser.add_argument('--control', type=int, default=25434)
args = parser.parse_args()
asyncio.run(main())
