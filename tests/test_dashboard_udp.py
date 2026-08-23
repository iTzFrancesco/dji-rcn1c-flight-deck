import asyncio
import json
import os
import socket
import struct
import sys
import threading
import time
import urllib.request
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'viz_app'))
HTTP_PORT = int(os.environ.get('VIZ_HTTP', '8125'))
WS_PORT = int(os.environ.get('VIZ_WS', '8126'))
UDP_PORT = int(os.environ.get('VIZ_UDP', '26799'))

os.environ.setdefault('VIZ_HTTP', str(HTTP_PORT))
os.environ.setdefault('VIZ_WS', str(WS_PORT))
import controller_viz as cv  # noqa: E402

cv.HTTP_PORT = HTTP_PORT
cv.WS_PORT = WS_PORT

import websockets  # noqa: E402

FRAME = struct.Struct('<IHHHH')
FRAME2 = struct.Struct('<IHHHHHBB')
FRAME3 = struct.Struct('<IHHHHHHBB')


class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(cv.STATIC_DIR), **kw)

    def log_message(self, *a):
        pass


def norm(raw):
    span = (cv.RAW_MAX - cv.RAW_MIN) / 2
    return max(-1.0, min(1.0, (raw - cv.RAW_CENTER) / span))


async def snap():
    async with websockets.connect(f'ws://127.0.0.1:{cv.WS_PORT}') as w:
        hello = json.loads(await asyncio.wait_for(w.recv(), 5))
        state = json.loads(await asyncio.wait_for(w.recv(), 5))
        return hello.get('t') == 'hello', state


def ws_server():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    async def run():
        async with websockets.serve(cv.ws_handler, '127.0.0.1', cv.WS_PORT):
            await asyncio.Future()

    loop.run_until_complete(run())


ok = True
httpd = ThreadingHTTPServer(('127.0.0.1', cv.HTTP_PORT), H)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
threading.Thread(target=ws_server, daemon=True).start()
threading.Thread(target=cv.udp_loop, args=(UDP_PORT,), daemon=True).start()
threading.Thread(target=cv.gamepad_loop, daemon=True).start()
time.sleep(0.8)

with urllib.request.urlopen(f'http://127.0.0.1:{cv.HTTP_PORT}', timeout=5) as r:
    body_ok = r.status == 200 and b'RC-N1C // Flight Deck' in r.read()
print(f'[{"PASS" if body_ok else "FAIL"}] dashboard: HTTP servita')
ok &= body_ok

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
pong_ok = False
for i in range(170):
    if i >= 167:
        sock.sendto(FRAME3.pack(i, 1500, 1024, 1024, 700, 1600, 0x1062, 1, 0), ('127.0.0.1', UDP_PORT))
    elif i >= 164:
        sock.sendto(FRAME2.pack(i, 1500, 1024, 1024, 700, 1600, 0x01, 0), ('127.0.0.1', UDP_PORT))
    else:
        sock.sendto(FRAME.pack(i, 1500, 1024, 1024, 700), ('127.0.0.1', UDP_PORT))
    if i % 40 == 10:
        ping = b'PNG1' + struct.pack('<iQ', i, 777)
        sock.sendto(ping, ('127.0.0.1', UDP_PORT))
        sock.settimeout(0.3)
        try:
            echo, _ = sock.recvfrom(64)
            if echo == ping:
                pong_ok = True
        except OSError:
            pass
    time.sleep(0.01)
print(f'[{"PASS" if pong_ok else "FAIL"}] dashboard: ping/pong')
ok &= pong_ok

h, s = asyncio.run(snap())
checks = [
    ('ws hello', h),
    ('connected', s.get('connected') is True),
    ('port label', s.get('port') == f'UDP:{UDP_PORT}'),
    ('lx normalizzato', abs(s.get('lx', 9) - norm(700)) < 1e-6),
    ('rx normalizzato', abs(s.get('rx', 9) - norm(1500)) < 1e-6),
    ('pkt>=150', s.get('pkt', 0) >= 150),
    ('pps>0', s.get('pps', 0) > 0),
    ('fn via frame v2', s.get('fn') is True),
    ('scatto via frame v3', s.get('shutter') is True),
    ('rotella destra (B)', s.get('btn_b') is True),
]
for name, res in checks:
    print(f'[{"PASS" if res else "FAIL"}] dashboard: {name}')
    ok &= bool(res)

time.sleep(1.3)
_, s2 = asyncio.run(snap())
stale_ok = s2.get('connected') is False and s2.get('lx') == 0.0 and s2.get('pps') == 0
print(f'[{"PASS" if stale_ok else "FAIL"}] dashboard: failsafe silenzio')
ok &= stale_ok

cv.STOP.set()
httpd.shutdown()
print('DASHBOARD_TEST_' + ('PASS' if ok else 'FAIL'))
sys.exit(0 if ok else 1)
