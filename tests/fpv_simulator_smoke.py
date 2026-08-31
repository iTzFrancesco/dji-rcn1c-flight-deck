"""Real browser smoke test for the shared desktop/Android FPV simulator assets."""

import os
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / 'viz_app' / 'static'


class QuietStaticHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, *_args):
        pass


def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietStaticHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
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
            # This smoke test injects direct RC frames. Avoid coupling it to
            # the user's already-running desktop dashboard on port 8124.
            page.add_init_script("Object.defineProperty(window, 'WebSocket', { value: undefined });")

            page.goto(
                f'http://127.0.0.1:{server.server_port}/fpv-sim/',
                wait_until='load',
            )
            page.wait_for_selector('canvas')
            assert page.title() == 'RC-N1C // FPV Training'
            assert page.locator('canvas').count() == 1
            assert page.locator('#top-left-controls > *').count() == 3
            assert page.locator('#dashboard-link').inner_text() == '← FLIGHT DECK'
            assert page.locator('#bridge-chip').inner_text().startswith('RC BRIDGE')
            assert page.locator('#quick-reset').inner_text() == 'RESET'
            assert page.locator('#game-chrome, #quick-controls, #hud, #rcn1c-bridge-status').count() == 0
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
            center_state = page.evaluate('window.getFpvSimulatorState()')
            center_altitude = float(center_state['altitude'])
            assert abs(center_altitude - 5.0) < 0.2, center_altitude

            # Full throttle should ramp into the vertical model instead of
            # teleporting the filtered input to an endpoint in one frame.
            page.evaluate('window.setRcn1cFrame(1024, 1684, 1024, 1024, 0x1000, 1, 120, true)')
            page.wait_for_timeout(50)
            early_throttle_state = page.evaluate('window.getFpvSimulatorState()')
            assert 0.05 < float(early_throttle_state['throttleInput']) < 0.95, early_throttle_state
            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120, true)')
            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)

            # Small stick motion must remain live; this guards against a
            # reintroduced hard dead zone while the response filter settles.
            page.evaluate('window.setRcn1cFrame(1024, 958, 1024, 1024, 0x1000, 1, 120)')
            page.wait_for_timeout(180)
            small_input_state = page.evaluate('window.getFpvSimulatorState()')
            assert abs(float(small_input_state['throttleInput'])) > 0.05, small_input_state
            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120)')
            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)

            page.evaluate('window.setRcn1cFrame(1024, 364, 1024, 1024, 0x1000, 1, 120)')
            down_controls = page.evaluate('window.getRcn1cFlightControls()')
            assert down_controls['connected'] is True
            assert down_controls['throttle'] == 1
            page.wait_for_timeout(120)
            down_state = page.evaluate('window.getFpvSimulatorState()')
            assert -1 < down_state['throttleInput'] < -0.75
            assert down_state['altitude'] < center_altitude, (center_altitude, down_state)

            page.evaluate('window.setRcn1cFrame(1024, 1684, 1024, 1024, 0x1000, 1, 120)')
            up_controls = page.evaluate('window.getRcn1cFlightControls()')
            assert up_controls['throttle'] == -1
            page.wait_for_timeout(750)
            lifted_state = page.evaluate('window.getFpvSimulatorState()')
            assert 0.95 < lifted_state['throttleInput'] <= 1
            assert lifted_state['altitude'] > center_altitude + 0.05, (center_altitude, lifted_state)

            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120)')
            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)
            reset_altitude = float(page.evaluate('window.getFpvSimulatorState().altitude'))
            assert abs(reset_altitude - 5.0) < 0.2, reset_altitude
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'none'

            assert page.evaluate(
                'performance.getEntriesByType("resource").some(entry => entry.name.includes("fan_interval.wav"))'
            )
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'none'

            page.evaluate("window.setRcn1cStatus('RC collegato', true)")
            page.get_by_role('button', name='RESET').click()
            page.wait_for_timeout(150)
            assert page.locator('#game-bridge-state').inner_text() == 'ONLINE'

            page.evaluate('window.setRcn1cFrame(1024, 364, 1024, 1024, 0x1000, 1, 120)')
            page.wait_for_timeout(1000)
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'flex'
            crash_text = ' '.join(page.locator('#crash-overlay').inner_text().split())
            assert crash_text == 'CRASHED! Premi RESET o tocca lo schermo per ripartire'
            assert page.get_by_role('button', name='RESET').is_visible()
            page.evaluate('window.setRcn1cFrame(1024, 1024, 1024, 1024, 0x1000, 1, 120)')
            page.mouse.click(8, 8)
            page.wait_for_timeout(150)
            assert page.locator('#crash-overlay').evaluate("el => getComputedStyle(el).display") == 'none'
            assert abs(float(page.evaluate('window.getFpvSimulatorState().altitude')) - 5.0) < 0.2
            assert not errors, '; '.join(errors)
            browser.close()
    finally:
        server.shutdown()
        server.server_close()

    print('FPV_SIMULATOR_SMOKE_PASS')


if __name__ == '__main__':
    main()
