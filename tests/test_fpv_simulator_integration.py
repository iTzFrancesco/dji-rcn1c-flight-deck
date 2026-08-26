import hashlib
import shutil
import subprocess
import threading
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from viz_app.controller_viz import DashboardHandler


ROOT = Path(__file__).resolve().parents[1]
PC_SIM = ROOT / 'viz_app' / 'static' / 'fpv-sim'
ANDROID_SIM = ROOT / 'wireless' / 'android-app' / 'assets' / 'fpv-sim'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_simulator_is_served_by_existing_dashboard_server():
    dashboard = (ROOT / 'viz_app' / 'static' / 'index.html').read_text(encoding='utf-8')
    assert 'href="/fpv-sim/"' in dashboard
    assert (PC_SIM / 'index.html').exists()
    assert (PC_SIM / 'three.min.js').exists()
    assert (PC_SIM / 'simulator-bridge.js').exists()
    assert (PC_SIM / 'fpv-assets.js').exists()
    assert (PC_SIM / 'motor-audio.js').exists()

    httpd = ThreadingHTTPServer(('127.0.0.1', 0), DashboardHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        with urllib.request.urlopen(
                f'http://127.0.0.1:{httpd.server_port}/fpv-sim/', timeout=5) as response:
            body = response.read().decode('utf-8')
            assert response.status == 200
            assert 'RC-N1C // FPV Training' in body
            assert 'fpv-assets.js' in body
    finally:
        httpd.shutdown()
        thread.join(timeout=5)


def test_android_and_pc_simulator_assets_are_identical():
    for name in ('index.html', 'simulator-bridge.js', 'fpv-assets.js', 'motor-audio.js', 'three.min.js'):
        assert digest(PC_SIM / name) == digest(ANDROID_SIM / name), name


def test_simulator_stays_local_and_lightweight():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    bridge = (PC_SIM / 'simulator-bridge.js').read_text(encoding='utf-8')
    assert 'cdnjs.cloudflare.com' not in html
    assert 'fonts.googleapis.com' not in html
    assert '<script src="motor-audio.js"></script>' in html
    assert 'ws://' in bridge and ':8124' in bridge
    assert 'setRcn1cFrame' in bridge
    assert 'addTrainingWorld' in html


def test_simulator_physics_has_stable_hover_and_real_ground_contact():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    assert 'maxThrust: 19.62' in html
    assert 'maxSpeed: 42' in html
    assert 'speedBeforeDrag' in html
    assert 'physics.position.y = Math.max(physics.position.y, physics.droneRadius)' not in html
    assert html.index('if (checkGroundCollision())') < html.index(
        '// Sync after collision handling')


def test_motor_audio_has_a_webview_safe_fallback():
    node = shutil.which('node')
    if node is None:
        return

    harness = r'''
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
function param(value) { return { value: value }; }
function FakeAudioContext() {
  this.currentTime = 0;
  this.state = 'running';
  this.destination = {};
}
FakeAudioContext.prototype.resume = function () {};
FakeAudioContext.prototype.createGain = function () {
  return { gain: param(0), connect: function () {} };
};
FakeAudioContext.prototype.createOscillator = function () {
  return {
    type: '', detune: param(0), frequency: param(0),
    connect: function () {}, start: function () {}
  };
};
global.window = { AudioContext: FakeAudioContext };
vm.runInThisContext(fs.readFileSync('viz_app/static/fpv-sim/motor-audio.js', 'utf8'));
assert.strictEqual(window.FPVAudio.toggle(), true);
window.FPVAudio.update(0.75, 12);
assert.strictEqual(window.FPVAudio.isEnabled(), true);
console.log('MOTOR_AUDIO_WEBVIEW_PASS');
'''
    result = subprocess.run(
        [node, '-e', harness],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=30,
    )
    assert result.returncode == 0, result.stderr or result.stdout
    assert 'MOTOR_AUDIO_WEBVIEW_PASS' in result.stdout


if __name__ == '__main__':
    test_simulator_is_served_by_existing_dashboard_server()
    test_android_and_pc_simulator_assets_are_identical()
    test_simulator_stays_local_and_lightweight()
    print('[PASS] simulatore FPV integrato in dashboard e sincronizzato Android')
