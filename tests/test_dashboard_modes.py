import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'viz_app'))

import controller_viz as cv  # noqa: E402


def test_gamepad_mode_selection():
    assert cv.should_start_gamepad('serial', False, False) is False
    assert cv.should_start_gamepad('serial', False, True) is True
    assert cv.should_start_gamepad('serial', True, True) is False
    assert cv.should_start_gamepad('udp', False, False) is True
    assert cv.should_start_gamepad('udp', True, False) is False


def test_desktop_launcher_enables_usb_gamepad():
    launcher = (ROOT / 'tools' / 'RC-N1C-Dashboard.bat').read_text(encoding='utf-8')
    assert 'controller_viz.py" --gamepad --no-browser' in launcher
    assert 'controller_viz.py" --source udp --gamepad --no-browser' in launcher


def test_serial_gamepad_cli_starts_both_threads(monkeypatch):
    started = []

    class FakeThread:
        def __init__(self, target, args=(), daemon=False):
            started.append((target.__name__, args, daemon))

        def start(self):
            return None

    async def fake_main_async(_args):
        return None

    monkeypatch.setattr(cv.threading, 'Thread', FakeThread)
    monkeypatch.setattr(cv, 'main_async', fake_main_async)
    monkeypatch.setattr(sys, 'argv', ['controller_viz.py', '--gamepad', '--no-browser'])
    cv.main()

    assert ('serial_loop', (), True) in started
    assert ('gamepad_loop', ('USB',), True) in started
