import asyncio
import json
import threading
import time
import urllib.request

import controller_viz as cv


def check_http():
    with urllib.request.urlopen(f'http://127.0.0.1:{cv.HTTP_PORT}', timeout=5) as r:
        body = r.read()
        return r.status == 200 and b'RC-N1C // Flight Deck' in body


async def probe(ws):
    m1 = json.loads(await asyncio.wait_for(ws.recv(), 5))
    m2 = json.loads(await asyncio.wait_for(ws.recv(), 5))
    return m1.get('t') == 'hello' and m2.get('t') == 's' and 'lx' in m2, m2


async def check_ws():
    import websockets
    try:
        async with websockets.connect(f'ws://127.0.0.1:{cv.WS_PORT}') as ws:
            return await probe(ws)
    except (ConnectionRefusedError, OSError):
        async with websockets.serve(cv.ws_handler, '127.0.0.1', cv.WS_PORT):
            async with websockets.connect(f'ws://127.0.0.1:{cv.WS_PORT}') as ws:
                return await probe(ws)


ok = True
owns_stack = False
httpd = None
serial_thread = None
try:
    external = False
    try:
        check_http()
        external = True
        print('[INFO] server live rilevato: eseguo i test contro il server in esecuzione')
    except Exception:
        pass

    if not external:
        from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

        class H(SimpleHTTPRequestHandler):
            def __init__(self, *a, **kw):
                super().__init__(*a, directory=str(cv.STATIC_DIR), **kw)

            def log_message(self, *a):
                pass

        serial_thread = threading.Thread(target=cv.serial_loop, daemon=True)
        serial_thread.start()
        httpd = ThreadingHTTPServer(('127.0.0.1', cv.HTTP_PORT), H)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        owns_stack = True
        time.sleep(1.0)

    http_ok = check_http()
    print(f'[{"PASS" if http_ok else "FAIL"}] HTTP dashboard servita (status+title)')
    ok &= http_ok

    ws_ok, snap = asyncio.run(check_ws())
    print(f'[{"PASS" if ws_ok else "FAIL"}] WebSocket handshake hello + snapshot con canali')
    ok &= ws_ok

    time.sleep(3.0)
    if external:
        import websockets as _ws

        async def get_snap():
            async with _ws.connect(f'ws://127.0.0.1:{cv.WS_PORT}') as w:
                json.loads(await asyncio.wait_for(w.recv(), 5))
                return json.loads(await asyncio.wait_for(w.recv(), 5))

        snap = asyncio.run(get_snap())

    if snap.get('connected') and snap.get('pkt', 0) > 10:
        print(f"[PASS] seriale reale attiva su {snap.get('port')} ({snap['pkt']} pacchetti)")
    else:
        print(f"[WARN] nessun dato seriale (connesso={snap.get('connected')}, "
              f"pkt={snap.get('pkt')}) - radiocomando acceso/collegato?")
finally:
    cv.STOP.set()
    if owns_stack and httpd:
        httpd.shutdown()

print('SELFTEST_PASS' if ok else 'SELFTEST_FAIL')
