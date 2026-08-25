import asyncio
import json
import os
import threading
import time
import urllib.request

import controller_viz as cv
from registra_volo import OUT_DIR as REC_DIR


def check_http():
    with urllib.request.urlopen(f'http://127.0.0.1:{cv.HTTP_PORT}', timeout=5) as r:
        body = r.read()
        return r.status == 200 and b'RC-N1C // Flight Deck' in body


def api_rec_status():
    with urllib.request.urlopen(f'http://127.0.0.1:{cv.HTTP_PORT}/api/rec/status', timeout=5) as r:
        return json.loads(r.read())


def api_rec_post(endpoint, payload=None):
    data = json.dumps(payload or {}).encode()
    req = urllib.request.Request(
        f'http://127.0.0.1:{cv.HTTP_PORT}{endpoint}', data=data, method='POST',
        headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


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
        from http.server import ThreadingHTTPServer

        serial_thread = threading.Thread(target=cv.serial_loop, daemon=True)
        serial_thread.start()
        httpd = ThreadingHTTPServer(('127.0.0.1', cv.HTTP_PORT), cv.DashboardHandler)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        owns_stack = True
        time.sleep(1.0)

    http_ok = check_http()
    print(f'[{"PASS" if http_ok else "FAIL"}] HTTP dashboard servita (status+title)')
    ok &= http_ok

    try:
        st = api_rec_status()
        rec_api_ok = st.get('recording') is False
    except Exception:
        rec_api_ok = False
        st = {}
    print(f'[{"PASS" if rec_api_ok else "FAIL"}] API /api/rec/status raggiungible')
    ok &= rec_api_ok

    ws_ok, snap = asyncio.run(check_ws())
    rec_in_snap = 'rec' in snap
    print(f'[{"PASS" if ws_ok else "FAIL"}] WebSocket handshake hello + snapshot con canali')
    ok &= ws_ok
    print(f'[{"PASS" if rec_in_snap else "FAIL"}] stato REC incluso nello snapshot WS')
    ok &= rec_in_snap

    if owns_stack:
        code, started = api_rec_post('/api/rec/start', {'log_only': True, 'poll': 0.01})
        start_ok = code == 200 and started.get('ok') and started.get('recording')
        print(f'[{"PASS" if start_ok else "FAIL"}] avvio registrazione (log-only) via API')
        ok &= start_ok
        time.sleep(0.6)
        code, dup = api_rec_post('/api/rec/start', {'log_only': True})
        dup_ok = code == 409 and not dup.get('ok')
        print(f'[{"PASS" if dup_ok else "FAIL"}] doppio avvio rifiutato (409)')
        ok &= dup_ok
        code, stopped = api_rec_post('/api/rec/stop')
        rows_ok = code == 200 and stopped.get('ok') and stopped.get('rows', 0) > 10
        print(f'[{"PASS" if rows_ok else "FAIL"}] stop via API con {stopped.get("rows", 0)} righe')
        ok &= rows_ok
        files_ok = all(os.path.exists(os.path.join(REC_DIR, f))
                       for f in stopped.get('files', []))
        print(f'[{"PASS" if files_ok else "FAIL"}] file salvati: {", ".join(stopped.get("files", []))}')
        ok &= files_ok
        for f in stopped.get('files', []):
            try:
                os.remove(os.path.join(REC_DIR, f))
            except OSError:
                pass
        code, none_stop = api_rec_post('/api/rec/stop')
        none_ok = code == 409
        print(f'[{"PASS" if none_ok else "FAIL"}] stop senza registrazione rifiutato (409)')
        ok &= none_ok

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
    try:
        cv.rec_stop()
    except RuntimeError:
        pass
    if owns_stack and httpd:
        httpd.shutdown()

print('SELFTEST_PASS' if ok else 'SELFTEST_FAIL')
