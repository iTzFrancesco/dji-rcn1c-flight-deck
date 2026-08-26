import xml.etree.ElementTree as ET
import shutil
import subprocess
import textwrap
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'wireless' / 'android-app'
ANDROID_NS = {'android': 'http://schemas.android.com/apk/res/android'}
PACKAGE = ANDROID / 'src' / 'com' / 'drone' / 'rcn1cbridge'
ASSETS = ANDROID / 'assets' / 'fpv-sim'


def test_mobile_simulator_surface_is_local_and_directly_connected_to_rc():
    manifest = ET.parse(ANDROID / 'AndroidManifest.xml').getroot()
    activities = manifest.findall('application/activity')
    names = {
        activity.get('{http://schemas.android.com/apk/res/android}name')
        for activity in activities
    }
    assert '.SimulatorActivity' in names
    simulator = next(
        activity for activity in activities
        if activity.get('{http://schemas.android.com/apk/res/android}name') == '.SimulatorActivity'
    )
    assert simulator.get('{http://schemas.android.com/apk/res/android}exported') == 'false'

    source = (PACKAGE / 'SimulatorActivity.java').read_text(encoding='utf-8')
    assert 'Rcn1cUsbReader' in source
    assert 'loadDataWithBaseURL' in source
    assert 'appassets.androidplatform.net' in source
    assert 'setAllowFileAccess(false)' in source
    assert 'file:///' not in source
    assert 'stopAndWait(500L)' in source
    assert 'readerDeviceId' in source
    assert 'setRcn1cFrame(' in source
    assert 'dispatchGesture' not in source
    assert 'AccessibilityService' not in source

    reader = (PACKAGE / 'Rcn1cUsbReader.java').read_text(encoding='utf-8')
    assert 'controlTransfer' in reader
    assert 'firstIn' in reader and 'firstOut' in reader
    assert 'stopAndWait' in reader

    main = (PACKAGE / 'MainActivity.java').read_text(encoding='utf-8')
    assert 'SIMULATORE FPV' in main
    assert 'SimulatorActivity.class' in main

    gradle = (ANDROID / 'build.gradle.kts').read_text(encoding='utf-8')
    assert 'assets.srcDirs("assets")' in gradle

    html = (ASSETS / 'index.html').read_text(encoding='utf-8')
    assert '<script src="three.min.js"></script>' in html
    assert '<script src="simulator-bridge.js"></script>' in html
    assert 'cdnjs.cloudflare.com' not in html
    assert 'fonts.googleapis.com' not in html

    bridge = (ASSETS / 'simulator-bridge.js').read_text(encoding='utf-8')
    assert 'setRcn1cFrame' in bridge
    assert 'getRcn1cGamepads' in bridge
    assert 'navigator.getGamepads' in bridge
    assert 'gamepadconnected' in bridge
    assert 'state.connected' in bridge
    assert (ASSETS / 'THREE_JS_LICENSE').exists()


def test_simulator_bridge_runtime_fails_safe_when_rc_is_absent_or_detached():
    node = shutil.which('node')
    if node is None:
        return

    harness = textwrap.dedent(
        """
        const fs = require('fs');
        const vm = require('vm');
        const assert = require('assert');
        const elements = {};
        const events = [];
        global.window = { dispatchEvent: e => events.push(e.type), setTimeout, console };
        global.navigator = { getGamepads: () => [] };
        global.performance = { now: () => 123.4 };
        global.document = {
          body: { appendChild: e => { elements[e.id] = e; } },
          createElement: () => ({ style: {}, id: '', textContent: '' }),
          getElementById: id => elements[id] || null
        };
        global.Event = class Event { constructor(type) { this.type = type; } };
        vm.runInThisContext(fs.readFileSync('wireless/android-app/assets/fpv-sim/simulator-bridge.js', 'utf8'));
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [0, 1, 0, 0]);
        window.setRcn1cFrame(32767, -32767, 16384, -16384, 0x0080, 1, 120.0);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [1, 1, 0.500015259254738, 0.500015259254738]);
        window.setRcn1cFrame(364, 1684, 1024, 1024, 0, 1, 120.0);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [-1, -1, 0, 0]);
        assert.deepStrictEqual(window.getRcn1cFlightControls(), {
          connected: true, yaw: -1, throttle: 1, roll: 0, pitch: 0
        });
        window.setRcn1cStatus('RC scollegato', false);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [0, 1, 0, 0]);
        assert.deepStrictEqual(events, ['gamepadconnected', 'gamepaddisconnected']);
        console.log('BRIDGE_JS_FAILSAFE_PASS');
        """
    )
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
    assert 'BRIDGE_JS_FAILSAFE_PASS' in result.stdout


if __name__ == '__main__':
    test_mobile_simulator_surface_is_local_and_directly_connected_to_rc()
    print('[PASS] Android app: simulatore FPV locale collegato al lettore RC')
