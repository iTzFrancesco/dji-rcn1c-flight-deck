import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'wireless' / 'android-app'
ANDROID_NS = {'android': 'http://schemas.android.com/apk/res/android'}


def test_android_app_is_pc_wifi_bridge_only():
    manifest = ET.parse(ANDROID / 'AndroidManifest.xml').getroot()
    activities = manifest.findall('application/activity')
    launcher = [
        activity for activity in activities
        if activity.find("intent-filter/action[@android:name='android.intent.action.MAIN']", ANDROID_NS) is not None
    ]

    assert len(launcher) == 1
    assert launcher[0].get('{http://schemas.android.com/apk/res/android}name') == '.MainActivity'
    assert not (ANDROID / 'res' / 'xml' / 'touch_accessibility_service.xml').exists()
    assert not any('PortableTouch' in path.name for path in (ANDROID / 'src').rglob('*'))
    manifest_text = (ANDROID / 'AndroidManifest.xml').read_text(encoding='utf-8')
    assert 'BIND_ACCESSIBILITY_SERVICE' not in manifest_text


if __name__ == '__main__':
    test_android_app_is_pc_wifi_bridge_only()
    print('[PASS] Android app: solo bridge PC/Wi-Fi e dashboard')
