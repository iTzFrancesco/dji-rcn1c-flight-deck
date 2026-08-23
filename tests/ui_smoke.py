import asyncio
import socket
import struct
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'viz_app'))
import controller_viz as cv  # noqa: E402
import websockets  # noqa: E402

FRAME3 = struct.Struct('<IHHHHHHBB')


def main():
    errors = []
    cv.STOP.clear()

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(cv.STATIC_DIR), **kwargs)

        def log_message(self, *_args):
            pass

    httpd = ThreadingHTTPServer(('127.0.0.1', cv.HTTP_PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    threading.Thread(target=cv.udp_loop, args=(cv.DEFAULT_UDP_PORT,), daemon=True).start()

    def ws_thread():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def run():
            async with websockets.serve(cv.ws_handler, '127.0.0.1', cv.WS_PORT):
                await asyncio.Future()

        loop.run_until_complete(run())

    threading.Thread(target=ws_thread, daemon=True).start()
    time.sleep(0.8)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(viewport={'width': 1440, 'height': 1000}, device_scale_factor=1)
            page.on('pageerror', lambda exc: errors.append(f'pageerror: {exc}'))
            page.on('console', lambda msg: errors.append(f'console {msg.type}: {msg.text}')
                     if msg.type == 'error' else None)
            page.goto('http://127.0.0.1:8123', wait_until='networkidle')
            page.wait_for_timeout(100)
            for seq in range(100):
                sock.sendto(
                    FRAME3.pack(seq, 1500, 900, 1024, 700, 1600, 0x1060, 1, 0),
                    ('127.0.0.1', cv.DEFAULT_UDP_PORT),
                )
                page.wait_for_timeout(20)
            page.wait_for_timeout(100)
            assert page.title() == 'RC-N1C // Flight Deck'
            assert page.locator('.input-key').count() == 4
            assert page.locator('.pad').count() == 2
            assert page.locator('#bandChart').count() == 1
            assert page.locator('#statusText').inner_text() == 'Radiocomando connesso', \
                page.locator('#statusText').inner_text()
            assert page.locator('#keyShutter').evaluate('(el) => el.classList.contains("on")')
            page.screenshot(path=str(ROOT / 'tests' / 'flight_deck_smoke.png'), full_page=True)
            browser.close()
    finally:
        sock.close()
        cv.STOP.set()
        httpd.shutdown()
    if errors:
        raise AssertionError('; '.join(errors))
    print('UI_SMOKE_PASS')


if __name__ == '__main__':
    main()
