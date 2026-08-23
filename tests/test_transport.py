import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from rcn1c_protocol import decode_button_mask
from rcn1c_transport import FRAME_V3, pack_frame, unpack_frame


def test_v3_roundtrip_preserves_buttons_and_axes():
    raw = pack_frame(7, 1500, 900, 1024, 700, 1600, 0x1060, 1)
    assert len(raw) == FRAME_V3.size
    decoded = unpack_frame(raw)
    assert decoded['version'] == 3
    assert decoded['seq'] == 7
    assert decoded['rx'] == 1500
    assert decoded['button_mask'] == 0x1060
    assert decoded['mode_code'] == 1


def test_button_mask_matches_verified_rc_mapping():
    state = decode_button_mask(0x1002)
    assert state['mode'] == 'NORMAL'
    assert state['fn'] is True
    assert state['shutter'] is False
    assert state['photo_video'] is False
    assert state['rth'] is False
