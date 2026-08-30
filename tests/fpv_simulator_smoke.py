"""Real browser smoke test for the shared desktop/Android FPV simulator assets."""

import os
import asyncio
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright
import websockets


ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / 'viz_app' / 'static'


class QuietStaticHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, *_args):
        pass


def start_desktop_bridge_probe():
    """Keep the simulator's optional desktop bridge healthy during the browser test."""
    ready = threading.Event()
    stop = threading.Event()
    failure = []

    def run():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def handler(websocket):
            await websocket.wait_closed()

        async def serve_until_stopped():
            try:
                server = await websockets.serve(handler, '127.0.0.1', 8124)
                ready.set()
                while not stop.is_set():
                    await asyncio.sleep(0.05)
                server.close()
                await server.wait_closed()
            except Exception as error:
                failure.append(error)
                ready.set()

        loop.run_until_complete(serve_until_stopped())
        loop.close()

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    if not ready.wait(5):
        raise RuntimeError('desktop bridge probe did not start')
    if failure:
        raise failure[0]
    return stop, thread


def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietStaticHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    ws_stop, ws_thread = start_desktop_bridge_probe()
    errors = []

    try:
        with sync_playwright() as playwright:
            launch_options = {'headless': True}
            chrome_path = os.environ.get('FPV_SMOKE_CHROME')
            if chrome_path:
                launch_options['executable_path'] = chrome_path
            browser = playwright.chromium.launch(**launch_options)
            page = browser.new_page(
                viewport={'width': 800, 'height': 480},
                device_scale_factor=1,
                user_agent='Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
            )
            page.on('pageerror', lambda error: errors.append(f'pageerror: {error}'))
            page.on('console', lambda message: errors.append(f'console error: {message.text}')
                     if message.type == 'error' else None)

            page.goto(
                f'http://127.0.0.1:{server.server_port}/fpv-sim/',
                wait_until='load',
            )
            page.wait_for_selector('canvas')
            assert page.title() == 'RC-N1C // FPV Training'
            assert page.locator('canvas').count() == 1
            assert page.locator('#quick-controls button').count() == 3
            assert page.get_by_role('button', name='RACE').count() == 0
            assert page.locator('#race-info').count() == 0
            assert page.locator('#countdown-overlay').count() == 0
            assert page.evaluate('document.body.innerText.trim().length > 0')
            assert page.evaluate(
                'document.querySelector(".vite-error-overlay, #webpack-dev-server-client-overlay") === null'
            )

            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120)')
            page.wait_for_timeout(350)
            controls = page.evaluate('window.getRcn1cFlightControls()')
            assert controls == {
                'connected': True,
                'yaw': 0,
                'throttle': 0,
                'roll': 0,
                'pitch': 0,
            }
            center_altitude = float(page.locator('#hud-alt').inner_text())
            assert abs(center_altitude - 5.0) < 0.2, center_altitude

            page.evaluate('window.setRcn1cFrame(1024, 364, 1024, 1024, 0x1000, 1, 120)')
            max_controls = page.evaluate('window.getRcn1cFlightControls()')
            assert max_controls['connected'] is True
            assert max_controls['throttle'] == 1
            page.wait_for_timeout(750)
            lifted_altitude = float(page.locator('#hud-alt').inner_text())
            assert lifted_altitude > center_altitude + 0.05, (center_altitude, lifted_altitude)

            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120)')
            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)
            reset_altitude = float(page.locator('#hud-alt').inner_text())
            assert abs(reset_altitude - 5.0) < 0.2, reset_altitude
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'none'

            page.get_by_role('button', name='FREE FLIGHT').click()
            page.wait_for_timeout(150)
            assert 'FREE FLIGHT' in page.locator('#game-chrome .title').inner_text()
            assert page.evaluate(
                'performance.getEntriesByType("resource").some(entry => entry.name.includes("fan_interval.wav"))'
            )
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'none'

            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)
            assert page.locator('#game-bridge-state').inner_text() in ('IN ATTESA', 'ONLINE')
            assert not errors, '; '.join(errors)
            browser.close()
    finally:
        ws_stop.set()
        ws_thread.join(timeout=5)
        server.shutdown()
        server.server_close()

    print('FPV_SIMULATOR_SMOKE_PASS')


if __name__ == '__main__':
    main()
