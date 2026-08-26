import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'wireless' / 'android-app'
ANDROID_NS = {'android': 'http://schemas.android.com/apk/res/android'}


def test_android_app_keeps_pc_wifi_bridge_without_accessibility():
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

    main_activity = (ANDROID / 'src' / 'com' / 'drone' / 'rcn1cbridge' / 'MainActivity.java').read_text(encoding='utf-8')
    stick_pad = (ANDROID / 'src' / 'com' / 'drone' / 'rcn1cbridge' / 'StickPadView.java').read_text(encoding='utf-8')
    assert 'padL.setPoint(lxv, lyv)' in main_activity
    assert 'padR.setPoint(rxv, ryv)' in main_activity
    assert 'Monitor locale: RC e dashboard · PC non collegato' in main_activity
    assert 'if (socket != null && pkt != null)' in main_activity
    assert 'ui.postDelayed(this, 100)' in main_activity
    assert 'LAYER_TYPE_SOFTWARE' not in stick_pad
    gradle = (ANDROID / 'build.gradle.kts').read_text(encoding='utf-8')
    assert 'create("stableDebug")' in gradle
    assert 'signingConfig = signingConfigs.getByName("stableDebug")' in gradle


if __name__ == '__main__':
    test_android_app_keeps_pc_wifi_bridge_without_accessibility()
    print('[PASS] Android app: bridge PC/Wi-Fi, dashboard e nessuna Accessibility')
