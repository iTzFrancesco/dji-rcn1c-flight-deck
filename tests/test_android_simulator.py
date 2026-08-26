import xml.etree.ElementTree as ET
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
    assert 'file:///android_asset/fpv-sim/index.html' in source
    assert 'setRcn1cFrame(' in source
    assert 'dispatchGesture' not in source
    assert 'AccessibilityService' not in source

    main = (PACKAGE / 'MainActivity.java').read_text(encoding='utf-8')
    assert 'SIMULATORE FPV' in main
    assert 'SimulatorActivity.class' in main

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


if __name__ == '__main__':
    test_mobile_simulator_surface_is_local_and_directly_connected_to_rc()
    print('[PASS] Android app: simulatore FPV locale collegato al lettore RC')
