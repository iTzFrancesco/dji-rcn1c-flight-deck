import hashlib
import shutil
import subprocess
import sys
import threading
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from viz_app.controller_viz import DashboardHandler


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


def test_android_and_pc_share_the_same_runtime_assets():
    for name in ('simulator-bridge.js', 'fpv-assets.js', 'motor-audio.js', 'three.min.js'):
        assert digest(PC_SIM / name) == digest(ANDROID_SIM / name), name
    assert '<title>RC-N1C // FPV Training</title>' in (ANDROID_SIM / 'index.html').read_text(encoding='utf-8')


def test_simulator_stays_local_and_lightweight():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    bridge = (PC_SIM / 'simulator-bridge.js').read_text(encoding='utf-8')
    assert 'cdnjs.cloudflare.com' not in html
    assert 'fonts.googleapis.com' not in html
    assert '<script src="drone-audio.js"></script>' in html
    assert 'ws://' in bridge and ':8124' in bridge
    assert 'setRcn1cFrame' in bridge
    assert 'addTrainingWorld' in html
    assert "mode: 'freefly'" in html
    assert 'createTrainingGate' not in html
    assert 'countdown-overlay' not in html
    assert 'race-info' not in html
    assert 'startRace' not in html
    assert 'id="top-left-controls"' in html
    assert 'id="dashboard-link"' in html
    assert 'id="bridge-chip"' in html
    assert 'id="quick-reset"' in html
    assert 'id="game-chrome"' not in html
    assert 'id="quick-controls"' not in html
    assert 'id="hud"' not in html
    assert 'id="rcn1c-bridge-status"' not in html
    assert 'Premi <strong>RESET</strong> per ripartire' in html
    assert 'z-index: 90;' in html
    assert 'const controlSensitivity = mobileProfile ? 1.12 : 1.0;' in html
    assert 'function filterControl(value, axis, dt)' in html
    assert 'if (deflection < 0.02) return 0;' not in html
    assert 'const renderPixelRatio = mobileProfile' in html
    assert 'const scatteredBlockCount = mobileProfile ? 12 : 30;' in html
    assert 'The detailed floor-by-floor shell is hundreds of draw calls' in html


def test_android_has_the_same_audio_runtime_asset_as_the_browser_game():
    browser_audio = PC_SIM / 'audio' / 'fan_interval.wav'
    mobile_audio = ANDROID_SIM / 'audio' / 'fan_interval.wav'
    assert browser_audio.exists()
    assert mobile_audio.exists()
    assert digest(browser_audio) == digest(mobile_audio)


def test_simulator_physics_has_stable_hover_and_real_ground_contact():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    # Hover stabile a centro stick (0.50) per RC-N1C senza molla sul gas: se lasci lo stick non sale.
    assert 'hoverThrottle: 0.50' in html
    assert 'maxThrust: 58.0' in html
    assert 'dragCoeff: 0.008' in html
    assert 'fallAssist: 13.5' in html
    assert 'maxSpeed: 48' in html
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


def test_pc_simulator_is_fullscreen_plug_and_play():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    assert '#sidebar { display: none !important; }' in html
    assert 'id="top-left-controls"' in html
    assert 'id="quick-reset"' in html
    assert 'droneGroup.visible = false;' in html
    assert 'rawRoll = -directInput.roll;' in html
    assert 'rawYaw = -directInput.yaw;' in html
    assert 'rawThrottle = -directInput.throttle;' in html
    assert 'window.getFpvSimulatorState' in html
    assert 'sendPlayerUpdate(dt);' in html
    assert 'const updateInterval = mobileProfile ? 1 / 15 : 1 / 30;' in html
    assert "rawThrottle = filterControl(hasInput ? rawThrottle : 0, 'throttle', dt);" in html
    assert 'const CONFIG_VERSION = 4;' in html
    assert 'id="roll-center" value="100"' in html
    assert 'id="roll-max" value="900"' in html
    assert 'id="roll-expo" value="0.15"' in html
    assert 'function armMotorAudio()' in html


def test_pc_simulator_has_stronger_throttle_and_local_drone_audio():
    html = (PC_SIM / 'index.html').read_text(encoding='utf-8')
    audio = (PC_SIM / 'drone-audio.js').read_text(encoding='utf-8')
    assert '<script src="drone-audio.js"></script>' in html
    assert 'hoverThrottle: 0.50' in html
    assert 'maxThrust: 58.0' in html
    assert 'fallAssist: 13.5' in html
    assert 'dragCoeff: 0.008' in html
    assert 'maxSpeed: 48' in html
    assert "audio/fan_interval.wav" in audio
    assert "audio/propeller_cartoon_loop.wav" not in audio
    assert (PC_SIM / 'audio' / 'fan_interval.wav').stat().st_size > 100_000
    assert not (PC_SIM / 'audio' / 'propeller_cartoon_loop.wav').exists()


if __name__ == '__main__':
    test_simulator_is_served_by_existing_dashboard_server()
    test_android_and_pc_share_the_same_runtime_assets()
    test_simulator_stays_local_and_lightweight()
    print('[PASS] simulatore FPV integrato in dashboard e sincronizzato Android')
